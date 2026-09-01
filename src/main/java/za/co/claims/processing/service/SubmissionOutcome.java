package za.co.claims.processing.service;

import za.co.claims.processing.dto.ClaimSubmissionResponse;

/**
 * Lets ClaimController distinguish a brand-new submission (202 Accepted) from a retried one that
 * matched an existing Idempotency-Key (200 OK), without ClaimService reaching into HTTP concerns.
 */
public record SubmissionOutcome(ClaimSubmissionResponse response, boolean duplicate) {
}
