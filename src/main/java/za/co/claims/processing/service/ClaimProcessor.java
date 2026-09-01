package za.co.claims.processing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import za.co.claims.processing.client.*;
import za.co.claims.processing.config.ClaimsRetryProperties;
import za.co.claims.processing.dto.PaymentRequest;
import za.co.claims.processing.client.PaymentResult;
import za.co.claims.processing.dto.PolicyValidationResult;
import za.co.claims.processing.entity.ClaimProcessingState;
import za.co.claims.processing.enums.ClaimStatus;
import za.co.claims.processing.enums.PaymentStatus;
import za.co.claims.processing.event.ClaimSubmittedEvent;
import za.co.claims.processing.exception.ClaimNotFoundException;
import za.co.claims.processing.exception.DownstreamTechnicalException;
import za.co.claims.processing.repository.ClaimStateRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

@Service
public class ClaimProcessor {

    private static final Logger log = LoggerFactory.getLogger(ClaimProcessor.class);

    /**
     * Claims already past the point ClaimRetryScheduler or a duplicate outbox delivery should
     * touch again. PROCESSING_FAILED is deliberately NOT in this set: it is a retryable state, and
     * ClaimRetryScheduler re-invokes process() for exactly these claims until they either recover
     * or exhaust retries into MANUAL_REVIEW.
     */
    private static final Set<ClaimStatus> TERMINAL_OR_ADVANCED = EnumSet.of(
            ClaimStatus.CLIENT_VALIDATION_FAILED,
            ClaimStatus.POLICY_VALIDATION_FAILED,
            ClaimStatus.PAYMENT_PROCESSING,
            ClaimStatus.PAYMENT_COMPLETED,
            ClaimStatus.PAYMENT_FAILED,
            ClaimStatus.MANUAL_REVIEW
    );

    private final ClaimStateRepository claimStateRepository;
    private final ClaimsSystemClient claimsSystemClient;
    private final ClientRegistryClient clientRegistryClient;
    private final PolicyManagerClient policyManagerClient;
    private final PaymentClient paymentClient;
    private final ClaimsRetryProperties retryProperties;

    public ClaimProcessor(
            ClaimStateRepository claimStateRepository,
            ClaimsSystemClient claimsSystemClient,
            ClientRegistryClient clientRegistryClient,
            PolicyManagerClient policyManagerClient,
            PaymentClient paymentClient,
            ClaimsRetryProperties retryProperties) {

        this.claimStateRepository = claimStateRepository;
        this.claimsSystemClient = claimsSystemClient;
        this.clientRegistryClient = clientRegistryClient;
        this.policyManagerClient = policyManagerClient;
        this.paymentClient = paymentClient;
        this.retryProperties = retryProperties;
    }

    @Async
    @EventListener
    public void process(ClaimSubmittedEvent event) {

        ClaimProcessingState state =
                claimStateRepository.findByClaimId(event.claimId())
                        .orElseThrow(() ->
                                new ClaimNotFoundException(event.claimId()));

        if (TERMINAL_OR_ADVANCED.contains(state.getStatus())) {
            return;
        }

        MDC.put("claimId", state.getClaimId());
        MDC.put("correlationId", state.getCorrelationId());
        try {
            runWorkflow(state);
        } finally {
            MDC.clear();
        }
    }

    private void runWorkflow(ClaimProcessingState state) {
        try {

            ClaimDetails claim =
                    claimsSystemClient.getClaim(state.getClaimId());

            state = updateState(
                    state,
                    ClaimStatus.CLIENT_VALIDATION_PENDING,
                    null
            );

            log.info("Validating client {}", claim.clientId());

            if (!clientRegistryClient.isClientValid(claim.clientId())) {

                state = updateState(
                        state,
                        ClaimStatus.CLIENT_VALIDATION_FAILED,
                        "Client validation failed"
                );

                claimsSystemClient.updateClaimStatus(
                        state.getClaimId(),
                        state.getStatus()
                );

                log.info("Claim rejected: client validation failed");
                return;
            }

            state = updateState(
                    state,
                    ClaimStatus.CLIENT_VALIDATED,
                    null
            );

            state = updateState(
                    state,
                    ClaimStatus.POLICY_VALIDATION_PENDING,
                    null
            );

            log.info("Validating policy {}", claim.policyNumber());

            PolicyValidationResult policyResult =
                    policyManagerClient.validatePolicy(
                            claim.policyNumber(),
                            claim.clientId()
                    );

            if (!policyResult.isValid()) {

                state = updateState(
                        state,
                        ClaimStatus.POLICY_VALIDATION_FAILED,
                        "Policy validation failed"
                );

                claimsSystemClient.updateClaimStatus(
                        state.getClaimId(),
                        state.getStatus()
                );

                log.info("Claim rejected: policy validation failed");
                return;
            }

            state = updateState(
                    state,
                    ClaimStatus.POLICY_VALIDATED,
                    null
            );

            state = updateState(
                    state,
                    ClaimStatus.APPROVED,
                    null
            );

            state = updateState(
                    state,
                    ClaimStatus.PAYMENT_REQUESTED,
                    null
            );

            PaymentRequest paymentRequest =
                    new PaymentRequest(
                            state.getClaimId(),
                            policyResult.getPayableAmount(),
                            "ZAR",
                            state.getClaimId() + "-PAYMENT"
                    );

            log.info("Requesting payment of {} {}", paymentRequest.amount(), paymentRequest.currency());

            PaymentResult payment =
                    paymentClient.initiatePayment(paymentRequest);

            state.setPaymentId(payment.paymentId());

            ClaimStatus paymentState =
                    payment.status() == PaymentStatus.PROCESSING
                            ? ClaimStatus.PAYMENT_PROCESSING
                            : ClaimStatus.PAYMENT_FAILED;

            state = updateState(
                    state,
                    paymentState,
                    null
            );

            claimsSystemClient.updateClaimStatus(
                    state.getClaimId(),
                    state.getStatus()
            );

            log.info("Claim reached {}", state.getStatus());

        } catch (DownstreamTechnicalException exception) {
            handleTechnicalFailure(state, exception);
        }
    }

    /**
     * Technical (as opposed to business) downstream failures are retried with exponential backoff
     * up to claims.retry.max-attempts, at which point the claim is moved to MANUAL_REVIEW -- the
     * local equivalent of a message landing in a dead-letter queue after retries are exhausted.
     * ClaimRetryScheduler is what actually re-invokes process() once the backoff window elapses.
     */
    private void handleTechnicalFailure(ClaimProcessingState state, DownstreamTechnicalException exception) {
        int attempt = state.getRetryCount() + 1;
        state.setRetryCount(attempt);

        if (attempt >= retryProperties.maxAttempts()) {
            state = updateState(state, ClaimStatus.MANUAL_REVIEW, exception.getMessage());
            log.error("Claim {} moved to MANUAL_REVIEW after {} attempts: {}",
                    state.getClaimId(), attempt, exception.getMessage());
        } else {
            long backoffMs = computeBackoffMs(attempt);
            state.setNextRetryAt(LocalDateTime.now().plus(Duration.ofMillis(backoffMs)));
            state = updateState(state, ClaimStatus.PROCESSING_FAILED, exception.getMessage());
            log.warn("Claim {} technical failure (attempt {} of {}), retrying in {}ms: {}",
                    state.getClaimId(), attempt, retryProperties.maxAttempts(), backoffMs, exception.getMessage());
        }

        claimsSystemClient.updateClaimStatus(state.getClaimId(), state.getStatus());
    }

    private long computeBackoffMs(int attempt) {
        double raw = retryProperties.initialBackoffMs() * Math.pow(retryProperties.backoffMultiplier(), attempt - 1);
        return (long) Math.min(raw, retryProperties.maxBackoffMs());
    }

    private ClaimProcessingState updateState(
            ClaimProcessingState state,
            ClaimStatus status,
            String failureReason) {

        state.setStatus(status);
        state.setFailureReason(failureReason);
        state.setUpdatedAt(LocalDateTime.now());
        if (status != ClaimStatus.PROCESSING_FAILED) {
            // Only PROCESSING_FAILED is ever waiting on a backoff window; every other transition
            // (including MANUAL_REVIEW) clears any stale retry timer.
            state.setNextRetryAt(null);
        }

        return claimStateRepository.save(state);
    }
}
