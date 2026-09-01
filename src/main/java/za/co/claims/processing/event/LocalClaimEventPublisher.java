package za.co.claims.processing.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import za.co.claims.processing.entity.OutboxEvent;
import za.co.claims.processing.entity.ClaimProcessingState;
import za.co.claims.processing.exception.ClaimNotFoundException;
import za.co.claims.processing.repository.ClaimStateRepository;

@Component
public class LocalClaimEventPublisher implements ClaimEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final ClaimStateRepository claimStateRepository;

    public LocalClaimEventPublisher(
            ApplicationEventPublisher applicationEventPublisher,
            ClaimStateRepository claimStateRepository) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.claimStateRepository = claimStateRepository;
    }

    @Override
    public void publish(OutboxEvent outboxEvent) {
        ClaimProcessingState state = claimStateRepository.findByClaimId(outboxEvent.getAggregateId())
                .orElseThrow(() -> new ClaimNotFoundException(outboxEvent.getAggregateId()));

        applicationEventPublisher.publishEvent(new ClaimSubmittedEvent(
                outboxEvent.getEventId(),
                outboxEvent.getAggregateId(),
                state.getPriority(),
                outboxEvent.getQueueName()));
    }
}
