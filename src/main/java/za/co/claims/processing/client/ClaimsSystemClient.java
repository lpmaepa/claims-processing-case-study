package za.co.claims.processing.client;

import za.co.claims.processing.dto.ClaimSubmissionRequest;
import za.co.claims.processing.enums.ClaimStatus;

public interface ClaimsSystemClient {
    String createClaim(ClaimSubmissionRequest request, String idempotencyKey);
    ClaimDetails getClaim(String claimId);
    void updateClaimStatus(String claimId, ClaimStatus status);
}
