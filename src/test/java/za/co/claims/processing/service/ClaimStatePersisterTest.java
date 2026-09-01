package za.co.claims.processing.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import za.co.claims.processing.entity.ClaimProcessingState;
import za.co.claims.processing.entity.OutboxEvent;
import za.co.claims.processing.repository.ClaimStateRepository;
import za.co.claims.processing.repository.OutboxEventRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers the idempotency race directly: two requests with the same Idempotency-Key can both pass
 * ClaimService's existence check before either commits, so the database's unique constraint on
 * idempotency_key is the real guard. This asserts the loser's insert failure is turned into
 * "return the winner's claim" rather than propagating as an unhandled exception (which
 * previously surfaced to callers as a raw 500).
 */
class ClaimStatePersisterTest {

    private final ClaimStateRepository claimStateRepository = mock(ClaimStateRepository.class);
    private final OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
    private final ClaimStatePersister persister = new ClaimStatePersister(claimStateRepository, outboxEventRepository);

    @Test
    void concurrentDuplicateReturnsWinningClaimInsteadOfPropagatingConstraintViolation() {
        ClaimProcessingState losing = new ClaimProcessingState();
        losing.setIdempotencyKey("key-1");

        ClaimProcessingState winning = new ClaimProcessingState();
        winning.setClaimId("CLM-WINNER");
        winning.setIdempotencyKey("key-1");

        when(claimStateRepository.save(losing))
                .thenThrow(new DataIntegrityViolationException("unique constraint: idempotency_key"));
        when(claimStateRepository.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.of(winning));

        ClaimStatePersister.Result result = persister.persist(losing, new OutboxEvent(), "key-1");

        assertThat(result.duplicate()).isTrue();
        assertThat(result.state()).isSameAs(winning);
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void freshSubmissionPersistsStateAndOutboxEventTogether() {
        ClaimProcessingState state = new ClaimProcessingState();
        state.setIdempotencyKey("key-2");
        OutboxEvent event = new OutboxEvent();

        when(claimStateRepository.save(state)).thenReturn(state);

        ClaimStatePersister.Result result = persister.persist(state, event, "key-2");

        assertThat(result.duplicate()).isFalse();
        assertThat(result.state()).isSameAs(state);
        verify(outboxEventRepository).save(event);
    }
}
