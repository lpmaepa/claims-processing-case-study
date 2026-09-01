package za.co.claims.processing.service;

import org.junit.jupiter.api.Test;
import za.co.claims.processing.enums.ClaimPriority;
import za.co.claims.processing.enums.ClaimType;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityResolverTest {

    private final PriorityResolver resolver = new PriorityResolver();

    @Test
    void deathClaimIsHighPriority() {
        assertThat(resolver.resolve(ClaimType.DEATH)).isEqualTo(ClaimPriority.HIGH);
    }

    @Test
    void nonDeathClaimIsNormalPriority() {
        assertThat(resolver.resolve(ClaimType.OTHER)).isEqualTo(ClaimPriority.NORMAL);
    }
}
