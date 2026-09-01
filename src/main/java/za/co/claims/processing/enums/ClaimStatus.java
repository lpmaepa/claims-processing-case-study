package za.co.claims.processing.enums;

public enum ClaimStatus {
    RECEIVED,
    CLIENT_VALIDATION_PENDING,
    CLIENT_VALIDATED,
    CLIENT_VALIDATION_FAILED,
    POLICY_VALIDATION_PENDING,
    POLICY_VALIDATED,
    POLICY_VALIDATION_FAILED,
    APPROVED,
    PAYMENT_REQUESTED,
    PAYMENT_PROCESSING,
    PAYMENT_COMPLETED,
    PAYMENT_FAILED,
    /** Retryable technical failure; ClaimRetryScheduler will re-drive this claim until
     *  retryCount reaches claims.retry.max-attempts, at which point it moves to MANUAL_REVIEW. */
    PROCESSING_FAILED,
    /** Terminal, DLQ-equivalent state: retries are exhausted and a claims analyst must
     *  investigate the underlying downstream outage before the claim can proceed. */
    MANUAL_REVIEW
}
