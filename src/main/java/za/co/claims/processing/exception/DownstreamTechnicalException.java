package za.co.claims.processing.exception;

public class DownstreamTechnicalException extends RuntimeException {
    public DownstreamTechnicalException(String message) {
        super(message);
    }
}
