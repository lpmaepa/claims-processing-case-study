package za.co.claims.processing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retry/backoff policy for technical downstream failures. See ClaimRetryScheduler and
 * ClaimProcessor. Values are configurable (application.yml: claims.retry.*) because the case
 * study's SLA and retry limits are not fixed by the brief.
 */
@ConfigurationProperties(prefix = "claims.retry")
public record ClaimsRetryProperties(
        int maxAttempts,
        long initialBackoffMs,
        double backoffMultiplier,
        long maxBackoffMs) {

    public ClaimsRetryProperties {
        if (maxAttempts <= 0) {
            maxAttempts = 3;
        }
        if (initialBackoffMs <= 0) {
            initialBackoffMs = 2000;
        }
        if (backoffMultiplier <= 0) {
            backoffMultiplier = 2.0;
        }
        if (maxBackoffMs <= 0) {
            maxBackoffMs = 30_000;
        }
    }
}
