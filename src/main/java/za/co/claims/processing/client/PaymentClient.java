package za.co.claims.processing.client;

import za.co.claims.processing.dto.PaymentRequest;

import java.math.BigDecimal;

public interface PaymentClient {
    PaymentResult initiatePayment(PaymentRequest request);
}
