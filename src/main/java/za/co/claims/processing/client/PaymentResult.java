package za.co.claims.processing.client;

import za.co.claims.processing.enums.PaymentStatus;

public record PaymentResult(String paymentId, PaymentStatus status) {
}
