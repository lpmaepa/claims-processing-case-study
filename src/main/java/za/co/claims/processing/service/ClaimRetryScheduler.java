package za.co.claims.processing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import za.co.claims.processing.client.ClaimsSystemClient;
import za.co.claims.processing.config.ClaimsRetryProperties;
import za.co.claims.processing.entity.ClaimProcessingState;
import za.co.claims.processing.enums.ClaimPriority;
import za.co.claims.processing.enums.ClaimStatus;
import za.co.claims.processing.enums.QueueName;
import za.co.claims.processing.event.ClaimSubmittedEvent;
import za.co.claims.processing.repository.ClaimStateRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The local, in-process equivalent of an SQS redrive: periodically re-drives claims sitting in
 * PROCESSING_FAILED once their backoff window has elapsed, up to claims.retry.max-attempts. Once
 * a claim exceeds that limit it moves to MANUAL_REVIEW -- the sample's dead-letter queue.
 * <p>
 * In production this loop is replaced by SQS's own visibility-timeout/redrive-policy mechanics;
 * this scheduler exists so the retry behaviour described in the design doc is actually
 * demonstrable locally rather than only described.
 */
@Service
public class ClaimRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClaimRetryScheduler.class);

    private final ClaimStateRepository claimStateRepository;
    private final ClaimsSystemClient claimsSystemClient;
    private final ClaimProcessor claimProcessor;
    private final ClaimsRetryProperties retryProperties;

    public ClaimRetryScheduler(
            ClaimStateRepository claimStateRepository,
            ClaimsSystemClient claimsSystemClient,
            ClaimProcessor claimProcessor,
            ClaimsRetryProperties retryProperties) {
        this.claimStateRepository = claimStateRepository;
        this.claimsSystemClient = claimsSystemClient;
        this.claimProcessor = claimProcessor;
        this.retryProperties = retryProperties;
    }

    @Scheduled(fixedDelayString = "${claims.retry.scheduler-delay-ms:5000}")
    public void redriveFailedClaims() {
        LocalDateTime now = LocalDateTime.now();
        List<ClaimProcessingState> failed = claimStateRepository.findByStatus(ClaimStatus.PROCESSING_FAILED);

        for (ClaimProcessingState state : failed) {
            try {
                // ClaimProcessor already moves a claim straight to MANUAL_REVIEW on the attempt
                // that exhausts retries, so this is a defensive safety net rather than the
                // expected path -- it only fires if a claim was ever left in PROCESSING_FAILED
                // at/above the limit some other way.
                if (state.getRetryCount() >= retryProperties.maxAttempts()) {
                    moveToManualReview(state);
                    continue;
                }
                if (state.getNextRetryAt() != null && state.getNextRetryAt().isAfter(now)) {
                    continue; // backoff window not elapsed yet
                }

                log.info("Redriving claim {} (attempt {} of {})",
                        state.getClaimId(), state.getRetryCount() + 1, retryProperties.maxAttempts());

                // ClaimProcessor is a distinct Spring bean, so this still goes through the
                // @Async proxy correctly -- it is not a same-class self-invocation.
                claimProcessor.process(new ClaimSubmittedEvent(
                        UUID.randomUUID().toString(),
                        state.getClaimId(),
                        state.getPriority(),
                        state.getPriority() == ClaimPriority.HIGH
                                ? QueueName.HIGH_PRIORITY
                                : QueueName.STANDARD));

            } catch (Exception exception) {
                // One claim's retry sweep failing (e.g. an OptimisticLockException from a
                // concurrent update) should never stop the sweep for every other claim.
                log.error("Retry sweep failed for claim {}", state.getClaimId(), exception);
            }
        }
    }

    private void moveToManualReview(ClaimProcessingState state) {
        state.setStatus(ClaimStatus.MANUAL_REVIEW);
        state.setNextRetryAt(null);
        state.setUpdatedAt(LocalDateTime.now());
        claimStateRepository.save(state);
        claimsSystemClient.updateClaimStatus(state.getClaimId(), ClaimStatus.MANUAL_REVIEW);
        log.error("Claim {} exceeded {} retry attempts and requires manual review (DLQ equivalent)",
                state.getClaimId(), retryProperties.maxAttempts());
    }
}
