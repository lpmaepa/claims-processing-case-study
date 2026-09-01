package za.co.claims.processing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import za.co.claims.processing.entity.OutboxEvent;
import za.co.claims.processing.enums.OutboxStatus;
import za.co.claims.processing.event.ClaimEventPublisher;
import za.co.claims.processing.repository.OutboxEventRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OutboxPublisher {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ClaimEventPublisher claimEventPublisher;

    public OutboxPublisher(
            OutboxEventRepository outboxEventRepository,
            ClaimEventPublisher claimEventPublisher) {

        this.outboxEventRepository = outboxEventRepository;
        this.claimEventPublisher = claimEventPublisher;
    }

    @Scheduled(fixedDelayString = "${claims.outbox.publish-delay-ms:1000}")
    public void publishPendingEvents() {

        List<OutboxEvent> pendingEvents =
                outboxEventRepository
                        .findTop50ByStatusOrderByCreatedAtAsc(
                                OutboxStatus.PENDING
                        );

        for (OutboxEvent event : pendingEvents) {

            try {

                claimEventPublisher.publish(event);

                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(LocalDateTime.now());

                outboxEventRepository.save(event);

            } catch (Exception exception) {

                LOGGER.error(
                        "Failed to publish outbox event {} for claim {}",
                        event.getEventId(),
                        event.getAggregateId(),
                        exception
                );
            }
        }
    }
}