package za.co.claims.processing.client;

import za.co.claims.processing.enums.ClaimType;

public record ClaimDetails(
        String claimId,
        String clientId,
        String policyNumber,
        ClaimType claimType) {
}
