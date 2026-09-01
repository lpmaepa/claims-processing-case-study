package za.co.claims.processing.service;

import org.springframework.stereotype.Component;
import za.co.claims.processing.enums.ClaimPriority;
import za.co.claims.processing.enums.ClaimType;

@Component
public class PriorityResolver {

    public ClaimPriority resolve(ClaimType claimType) {
        return claimType == ClaimType.DEATH ? ClaimPriority.HIGH : ClaimPriority.NORMAL;
    }
}
