package za.co.claims.processing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import za.co.claims.processing.config.ClaimsSlaProperties;
import za.co.claims.processing.entity.ClaimProcessingState;
import za.co.claims.processing.enums.ClaimPriority;
import za.co.claims.processing.enums.ClaimStatus;
import za.co.claims.processing.repository.ClaimStateRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Local stand-in for a CloudWatch alarm on claim age: periodically scans claims that are still
 * in flight and flags any that have exceeded their SLA window (death claims get a shorter window
 * than standard claims -- see claims.sla.* in application.yml). In production this becomes a
 * CloudWatch metric/alarm feeding SNS; here it's a structured log line, but the point is the same
 * signal exists and is demonstrated rather than only described in the design doc.
 */
@Service
public class SlaMonitor {

    private static final Logger log = LoggerFactory.getLogger(SlaMonitor.class);

    private static final Set<ClaimStatus> TERMINAL = EnumSet.of(
            ClaimStatus.CLIENT_VALIDATION_FAILED,
            ClaimStatus.POLICY_VALIDATION_FAILED,
            ClaimStatus.PAYMENT_COMPLETED,
            ClaimStatus.PAYMENT_FAILED,
            ClaimStatus.MANUAL_REVIEW
    );

    private final ClaimStateRepository claimStateRepository;
    private final ClaimsSlaProperties slaProperties;

    public SlaMonitor(ClaimStateRepository claimStateRepository, ClaimsSlaProperties slaProperties) {
        this.claimStateRepository = claimStateRepository;
        this.slaProperties = slaProperties;
    }

    @Scheduled(fixedDelayString = "${claims.sla.check-delay-ms:5000}")
    public void checkSlaBreaches() {
        LocalDateTime now = LocalDateTime.now();
        List<ClaimProcessingState> inFlight =
                claimStateRepository.findByStatusNotInAndSlaBreachNotifiedFalse(TERMINAL);

        for (ClaimProcessingState state : inFlight) {
            long thresholdMinutes = state.getPriority() == ClaimPriority.HIGH
                    ? slaProperties.highPriorityMinutes()
                    : slaProperties.standardMinutes();

            Duration elapsed = Duration.between(state.getReceivedAt(), now);
            if (elapsed.toMinutes() < thresholdMinutes) {
                continue;
            }

            log.error("SLA BREACH: claim {} ({} priority) has been {} for {} minute(s) (threshold {}m)",
                    state.getClaimId(), state.getPriority(), state.getStatus(), elapsed.toMinutes(), thresholdMinutes);

            state.setSlaBreachNotified(true);
            claimStateRepository.save(state);
        }
    }
}
