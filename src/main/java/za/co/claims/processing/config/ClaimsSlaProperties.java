package za.co.claims.processing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SLA thresholds used by SlaMonitor. The case study does not specify exact SLA durations, so
 * these remain externally configurable (application.yml: claims.sla.*) rather than hard-coded.
 */
@ConfigurationProperties(prefix = "claims.sla")
public record ClaimsSlaProperties(
        long highPriorityMinutes,
        long standardMinutes) {

    public ClaimsSlaProperties {
        if (highPriorityMinutes <= 0) {
            highPriorityMinutes = 20;
        }
        if (standardMinutes <= 0) {
            standardMinutes = 60;
        }
    }
}
