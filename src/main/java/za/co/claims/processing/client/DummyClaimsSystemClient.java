package za.co.claims.processing.client;

import org.springframework.stereotype.Component;
import za.co.claims.processing.dto.ClaimSubmissionRequest;
import za.co.claims.processing.enums.ClaimStatus;
import za.co.claims.processing.exception.ClaimNotFoundException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DummyClaimsSystemClient implements ClaimsSystemClient {

    private final Map<String, ClaimDetails> claims = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    private final Map<String, ClaimStatus> statuses = new ConcurrentHashMap<>();

    @Override
    public String createClaim(ClaimSubmissionRequest request, String idempotencyKey) {
        String existingClaimId = idempotencyIndex.get(idempotencyKey);
        if (existingClaimId != null) {
            return existingClaimId;
        }

        String claimId = "CLM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        ClaimDetails details = new ClaimDetails(
                claimId,
                request.getClientId(),
                request.getPolicyNumber(),
                request.getClaimType());

        claims.put(claimId, details);
        statuses.put(claimId, ClaimStatus.RECEIVED);
        idempotencyIndex.put(idempotencyKey, claimId);
        return claimId;
    }

    @Override
    public ClaimDetails getClaim(String claimId) {
        ClaimDetails claim = claims.get(claimId);
        if (claim == null) {
            throw new ClaimNotFoundException(claimId);
        }
        return claim;
    }

    @Override
    public void updateClaimStatus(String claimId, ClaimStatus status) {
        if (!claims.containsKey(claimId)) {
            throw new ClaimNotFoundException(claimId);
        }
        statuses.put(claimId, status);
    }
}
