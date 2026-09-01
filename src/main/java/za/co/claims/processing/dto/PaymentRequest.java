package za.co.claims.processing.dto;

import java.math.BigDecimal;

public record PaymentRequest(
        String claimId,
        BigDecimal amount,
        String currency,
        String idempotencyKey
) {
}