package za.co.claims.processing.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import za.co.claims.processing.enums.ClaimPriority;
import za.co.claims.processing.enums.ClaimStatus;

import java.time.LocalDateTime;

@Entity
@Table(name = "claim_processing_state", indexes = {
        @Index(name = "idx_claim_state_claim_id", columnList = "claim_id"),
        @Index(name = "idx_claim_state_idempotency_key", columnList = "idempotency_key")
})
@Getter
@Setter
public class ClaimProcessingState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, unique = true)
    private String claimId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 120)
    private String idempotencyKey;

    @Column(name = "correlation_id", nullable = false, unique = true)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimPriority priority;

    private String paymentId;

    @Column(nullable = false)
    private int retryCount;

    private String failureReason;

    @Column(nullable = false)
    private LocalDateTime receivedAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /** When a PROCESSING_FAILED claim becomes eligible for the next retry attempt. Null once the
     *  claim leaves PROCESSING_FAILED (either recovering or moving to MANUAL_REVIEW). */
    private LocalDateTime nextRetryAt;

    /** Set once SlaMonitor has raised a breach for this claim, so the same claim doesn't
     *  re-alarm on every scheduled sweep. */
    @Column(nullable = false)
    private boolean slaBreachNotified = false;

    @Version
    private Long version;
}
