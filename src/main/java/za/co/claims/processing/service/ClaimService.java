package za.co.claims.processing.service;

import org.springframework.stereotype.Service;
import za.co.claims.processing.client.ClaimsSystemClient;
import za.co.claims.processing.dto.ClaimStatusResponse;
import za.co.claims.processing.dto.ClaimSubmissionRequest;
import za.co.claims.processing.entity.ClaimProcessingState;
import za.co.claims.processing.entity.OutboxEvent;
import za.co.claims.processing.enums.ClaimPriority;
import za.co.claims.processing.enums.ClaimStatus;
import za.co.claims.processing.enums.OutboxStatus;
import za.co.claims.processing.enums.QueueName;
import za.co.claims.processing.exception.ClaimNotFoundException;
import za.co.claims.processing.exception.InvalidRequestException;
import za.co.claims.processing.repository.ClaimStateRepository;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class ClaimService {

    private final ClaimStateRepository claimStateRepository;
    private final ClaimStatePersister claimStatePersister;
    private final ClaimsSystemClient claimsSystemClient;
    private final PriorityResolver priorityResolver;
    private final ClaimResponseMapper claimResponseMapper;

    public ClaimService(
            ClaimStateRepository claimStateRepository,
            ClaimStatePersister claimStatePersister,
            ClaimsSystemClient claimsSystemClient,
            PriorityResolver priorityResolver,
            ClaimResponseMapper claimResponseMapper) {
        this.claimStateRepository = claimStateRepository;
        this.claimStatePersister = claimStatePersister;
        this.claimsSystemClient = claimsSystemClient;
        this.priorityResolver = priorityResolver;
        this.claimResponseMapper = claimResponseMapper;
    }

    /**
     * Idempotent intake. Retrying the same Idempotency-Key returns the already-created claim
     * (duplicate=true) instead of ever calling downstream systems a second time.
     * <p>
     * Note this method is intentionally NOT {@code @Transactional}. {@link #claimsSystemClient}
     * represents a real network call to the existing Claims System in production; it cannot
     * participate in a local ACID transaction, and holding a DB transaction open for the duration
     * of an external HTTP call is a connection-pool/lock-contention risk under load. The local
     * writes that CAN be atomic -- ClaimProcessingState + the outbox event -- are delegated to
     * {@link ClaimStatePersister}, which is where the transaction boundary actually lives.
     * <p>
     * This does leave one residual, documented gap: if createClaim() succeeds but the local
     * persist step then fails for a reason other than the idempotency race (e.g. the database is
     * down), the authoritative system would have a claim with no local processing state and no
     * outbox event, so it would never be picked up for orchestration. Closing that fully needs a
     * two-phase/reconciliation approach across systems, which is out of scope for this case study;
     * it's called out here and in the design doc rather than left implicit.
     */
    public SubmissionOutcome submitClaim(ClaimSubmissionRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidRequestException("Idempotency-Key header must not be blank");
        }

        ClaimProcessingState existing = claimStateRepository
                .findByIdempotencyKey(idempotencyKey)
                .orElse(null);

        if (existing != null) {
            return new SubmissionOutcome(claimResponseMapper.toSubmissionResponse(existing), true);
        }

        ClaimPriority priority = priorityResolver.resolve(request.getClaimType());
        String claimId = claimsSystemClient.createClaim(request, idempotencyKey);
        LocalDateTime now = LocalDateTime.now();

        ClaimProcessingState state = new ClaimProcessingState();
        state.setClaimId(claimId);
        state.setIdempotencyKey(idempotencyKey);
        state.setCorrelationId(UUID.randomUUID().toString());
        state.setStatus(ClaimStatus.RECEIVED);
        state.setPriority(priority);
        state.setRetryCount(0);
        state.setReceivedAt(now);
        state.setUpdatedAt(now);

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventId(UUID.randomUUID().toString());
        outboxEvent.setAggregateId(claimId);
        outboxEvent.setEventType("CLAIM_SUBMITTED");
        outboxEvent.setQueueName(priority == ClaimPriority.HIGH ? QueueName.HIGH_PRIORITY : QueueName.STANDARD);
        outboxEvent.setStatus(OutboxStatus.PENDING);
        outboxEvent.setCreatedAt(now);

        ClaimStatePersister.Result result = claimStatePersister.persist(state, outboxEvent, idempotencyKey);

        return new SubmissionOutcome(claimResponseMapper.toSubmissionResponse(result.state()), result.duplicate());
    }

    public ClaimStatusResponse getClaimState(String claimId) {
        ClaimProcessingState state = claimStateRepository.findByClaimId(claimId)
                .orElseThrow(() -> new ClaimNotFoundException(claimId));
        return claimResponseMapper.toStatusResponse(state);
    }
}
