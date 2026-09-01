package za.co.claims.processing.client;

import za.co.claims.processing.dto.PolicyValidationResult;

public interface PolicyManagerClient {
    PolicyValidationResult validatePolicy(String policyNumber, String clientId);
}
