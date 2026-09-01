package za.co.claims.processing.client;

import org.springframework.stereotype.Component;
import za.co.claims.processing.dto.PaymentRequest;
import za.co.claims.processing.enums.PaymentStatus;
import za.co.claims.processing.exception.DownstreamTechnicalException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DummyPaymentClient implements PaymentClient {

    private final Map<String, PaymentResult> paymentsByIdempotencyKey =
            new ConcurrentHashMap<>();

    @Override
    public PaymentResult initiatePayment(PaymentRequest request) {

        if (request.amount() == null ||
                request.amount().signum() <= 0) {

            throw new DownstreamTechnicalException(
                    "Payment request contains an invalid payable amount"
            );
        }

        if (request.claimId().endsWith("FFFF")) {
            throw new DownstreamTechnicalException(
                    "Payment System is temporarily unavailable"
            );
        }

        return paymentsByIdempotencyKey.computeIfAbsent(
                request.idempotencyKey(),
                ignored -> new PaymentResult(
                        "PAY-" +
                                UUID.randomUUID()
                                        .toString()
                                        .substring(0, 8)
                                        .toUpperCase(),
                        PaymentStatus.PROCESSING
                )
        );
    }
}