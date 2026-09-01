package za.co.claims.processing.service;

import org.springframework.stereotype.Component;
import za.co.claims.processing.dto.ClaimStatusResponse;
import za.co.claims.processing.dto.ClaimSubmissionResponse;
import za.co.claims.processing.entity.ClaimProcessingState;

@Component
public class ClaimResponseMapper {

    public ClaimSubmissionResponse toSubmissionResponse(ClaimProcessingState state) {
        return new ClaimSubmissionResponse(
                state.getClaimId(),
                state.getStatus(),
                state.getPriority());
    }

    public ClaimStatusResponse toStatusResponse(ClaimProcessingState state) {
        return new ClaimStatusResponse(
                state.getClaimId(),
                state.getStatus(),
                state.getPriority(),
                state.getCorrelationId(),
                state.getPaymentId(),
                state.getRetryCount(),
                state.getFailureReason(),
                state.getReceivedAt(),
                state.getUpdatedAt(),
                state.getNextRetryAt(),
                state.isSlaBreachNotified());
    }
}
