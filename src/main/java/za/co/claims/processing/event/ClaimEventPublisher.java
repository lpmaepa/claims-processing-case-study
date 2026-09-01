package za.co.claims.processing.event;

import za.co.claims.processing.entity.OutboxEvent;

public interface ClaimEventPublisher {
    void publish(OutboxEvent outboxEvent);
}
