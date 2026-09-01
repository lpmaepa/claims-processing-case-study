package za.co.claims.processing.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import za.co.claims.processing.entity.ClaimProcessingState;
import za.co.claims.processing.entity.OutboxEvent;
import za.co.claims.processing.repository.ClaimStateRepository;
import za.co.claims.processing.repository.OutboxEventRepository;

/**
 * Owns the single local database transaction that makes claim intake atomic: the workflow state
 * and its outbox event are written together, or neither is (the transactional outbox pattern).
 * <p>
 * Deliberately a separate Spring bean rather than a private method on {@link ClaimService}: a
 * {@code @Transactional} method invoked via {@code this.foo()} from within the same class bypasses
 * Spring's proxy and silently runs without a transaction. Putting it on its own bean forces the
 * call to go through the proxy.
 * <p>
 * Also absorbs the idempotency race: two requests with the same Idempotency-Key can both pass
 * {@code ClaimService}'s existence check before either has committed. The database's unique
 * constraint on {@code idempotency_key} is the real guard; the loser's insert fails here with a
 * {@link DataIntegrityViolationException}, which is caught and turned into "return the winner's
 * claim" instead of bubbling up as an unhandled 500.
 */
@Component
public class ClaimStatePersister {

    private final ClaimStateRepository claimStateRepository;
    private final OutboxEventRepository outboxEventRepository;

    public ClaimStatePersister(ClaimStateRepository claimStateRepository,
                                OutboxEventRepository outboxEventRepository) {
        this.claimStateRepository = claimStateRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public Result persist(ClaimProcessingState state, OutboxEvent outboxEvent, String idempotencyKey) {
        try {
            // IDENTITY id generation forces Hibernate to INSERT immediately here (it can't batch
            // identity inserts), so a unique-constraint violation on idempotency_key surfaces on
            // this call rather than silently at a later flush/commit.
            claimStateRepository.save(state);
        } catch (DataIntegrityViolationException raceLost) {
            ClaimProcessingState winner = claimStateRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> raceLost);
            return new Result(winner, true);
        }

        outboxEventRepository.save(outboxEvent);
        return new Result(state, false);
    }

    public record Result(ClaimProcessingState state, boolean duplicate) {
    }
}
