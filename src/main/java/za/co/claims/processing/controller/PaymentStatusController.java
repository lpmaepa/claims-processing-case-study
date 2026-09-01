package za.co.claims.processing.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import za.co.claims.processing.dto.ClaimStatusResponse;
import za.co.claims.processing.dto.PaymentStatusRequest;
import za.co.claims.processing.service.PaymentStatusService;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentStatusController {

    private final PaymentStatusService paymentStatusService;

    public PaymentStatusController(PaymentStatusService paymentStatusService) {
        this.paymentStatusService = paymentStatusService;
    }

    @PostMapping("/status")
    public ResponseEntity<ClaimStatusResponse> updateStatus(
            @Valid @RequestBody PaymentStatusRequest request) {
        return ResponseEntity.ok(paymentStatusService.updatePaymentStatus(request));
    }
}
