package za.co.claims.processing.event;

import za.co.claims.processing.enums.ClaimPriority;
import za.co.claims.processing.enums.QueueName;

public record ClaimSubmittedEvent(
        String eventId,
        String claimId,
        ClaimPriority priority,
        QueueName queueName) {
}
