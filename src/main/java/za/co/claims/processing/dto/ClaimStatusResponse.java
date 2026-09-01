package za.co.claims.processing.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import za.co.claims.processing.enums.ClaimPriority;
import za.co.claims.processing.enums.ClaimStatus;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class ClaimStatusResponse {
    private String claimId;
    private ClaimStatus status;
    private ClaimPriority priority;
    private String correlationId;
    private String paymentId;
    private int retryCount;
    private String failureReason;
    private LocalDateTime receivedAt;
    private LocalDateTime updatedAt;
    /** Non-null only while status is PROCESSING_FAILED and a retry is still pending. */
    private LocalDateTime nextRetryAt;
    /** True once SlaMonitor has flagged this claim as over its SLA window. Gives a claims
     *  analyst the same signal an ops CloudWatch alarm would raise, without leaving the
     *  existing Claims System UI blind to it. */
    private boolean slaBreached;
}
