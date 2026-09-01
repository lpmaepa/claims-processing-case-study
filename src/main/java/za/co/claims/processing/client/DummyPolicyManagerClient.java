package za.co.claims.processing.client;

import org.springframework.stereotype.Component;
import za.co.claims.processing.dto.PolicyValidationResult;

import java.math.BigDecimal;

@Component
public class DummyPolicyManagerClient
        implements PolicyManagerClient {

    @Override
    public PolicyValidationResult validatePolicy(
            String policyNumber,
            String clientId) {

        if ("INVALID_POLICY".equalsIgnoreCase(policyNumber)) {
            return new PolicyValidationResult(
                    false,
                    null
            );
        }

        return new PolicyValidationResult(
                true,
                new BigDecimal("50000.00")
        );
    }


}