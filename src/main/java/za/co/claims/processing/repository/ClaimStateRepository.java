package za.co.claims.processing.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.claims.processing.entity.ClaimProcessingState;
import za.co.claims.processing.enums.ClaimStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClaimStateRepository extends JpaRepository<ClaimProcessingState, Long> {
    Optional<ClaimProcessingState> findByClaimId(String claimId);
    Optional<ClaimProcessingState> findByIdempotencyKey(String idempotencyKey);

    /** Claims currently in a retryable technical-failure state, for ClaimRetryScheduler. */
    List<ClaimProcessingState> findByStatus(ClaimStatus status);

    /** Non-terminal claims not yet SLA-alarmed, for SlaMonitor. */
    List<ClaimProcessingState> findByStatusNotInAndSlaBreachNotifiedFalse(Collection<ClaimStatus> terminalStatuses);
}
