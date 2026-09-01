package za.co.claims.processing.service;

import org.springframework.stereotype.Service;
import za.co.claims.processing.client.ClaimsSystemClient;
import za.co.claims.processing.dto.ClaimStatusResponse;
import za.co.claims.processing.dto.PaymentStatusRequest;
import za.co.claims.processing.entity.ClaimProcessingState;
import za.co.claims.processing.enums.ClaimStatus;
import za.co.claims.processing.enums.PaymentStatus;
import za.co.claims.processing.exception.ClaimNotFoundException;
import za.co.claims.processing.exception.InvalidPaymentStatusException;
import za.co.claims.processing.repository.ClaimStateRepository;

import java.time.LocalDateTime;

@Service
public class PaymentStatusService {

    private final ClaimStateRepository claimStateRepository;
    private final ClaimsSystemClient claimsSystemClient;
    private final ClaimResponseMapper claimResponseMapper;

    public PaymentStatusService(
            ClaimStateRepository claimStateRepository,
            ClaimsSystemClient claimsSystemClient,
            ClaimResponseMapper claimResponseMapper) {
        this.claimStateRepository = claimStateRepository;
        this.claimsSystemClient = claimsSystemClient;
        this.claimResponseMapper = claimResponseMapper;
    }

    public ClaimStatusResponse updatePaymentStatus(PaymentStatusRequest request) {
        ClaimProcessingState state = claimStateRepository.findByClaimId(request.getClaimId())
                .orElseThrow(() -> new ClaimNotFoundException(request.getClaimId()));

        if (state.getPaymentId() == null || !state.getPaymentId().equals(request.getPaymentId())) {
            throw new InvalidPaymentStatusException("Payment reference does not match the claim");
        }

        ClaimStatus newStatus;
        if (request.getStatus() == PaymentStatus.COMPLETED) {
            newStatus = ClaimStatus.PAYMENT_COMPLETED;
        } else if (request.getStatus() == PaymentStatus.FAILED) {
            newStatus = ClaimStatus.PAYMENT_FAILED;
        } else {
            throw new InvalidPaymentStatusException("Final payment callback must be COMPLETED or FAILED");
        }

        state.setStatus(newStatus);
        state.setFailureReason(newStatus == ClaimStatus.PAYMENT_FAILED ? "Payment failed" : null);
        state.setUpdatedAt(LocalDateTime.now());
        claimStateRepository.save(state);
        claimsSystemClient.updateClaimStatus(state.getClaimId(), newStatus);
        return claimResponseMapper.toStatusResponse(state);
    }
}
