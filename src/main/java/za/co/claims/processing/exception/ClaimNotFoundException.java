package za.co.claims.processing.exception;

public class ClaimNotFoundException extends RuntimeException {
    public ClaimNotFoundException(String claimId) {
        super("Claim not found: " + claimId);
    }
}
