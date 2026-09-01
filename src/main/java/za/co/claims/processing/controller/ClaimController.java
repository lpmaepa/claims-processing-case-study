package za.co.claims.processing.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import za.co.claims.processing.dto.ClaimStatusResponse;
import za.co.claims.processing.dto.ClaimSubmissionRequest;
import za.co.claims.processing.dto.ClaimSubmissionResponse;
import za.co.claims.processing.service.ClaimService;
import za.co.claims.processing.service.SubmissionOutcome;

@RestController
@RequestMapping("/api/v1/claims")
public class ClaimController {

    private final ClaimService claimService;

    public ClaimController(ClaimService claimService) {
        this.claimService = claimService;
    }

    /**
     * Entry point called by the Channel System web form.
     * Idempotent: retrying with the same Idempotency-Key returns the original claim (200) instead
     * of creating a duplicate (202).
     */
    @PostMapping
    public ResponseEntity<ClaimSubmissionResponse> submitClaim(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ClaimSubmissionRequest request) {

        SubmissionOutcome outcome = claimService.submitClaim(request, idempotencyKey);
        HttpStatus status = outcome.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(outcome.response());
    }

    @GetMapping("/{claimId}")
    public ResponseEntity<ClaimStatusResponse> getClaim(@PathVariable String claimId) {
        return ResponseEntity.ok(claimService.getClaimState(claimId));
    }
}
