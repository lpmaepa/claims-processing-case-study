package za.co.claims.processing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import za.co.claims.processing.enums.PaymentStatus;

@Getter
@Setter
public class PaymentStatusRequest {
    @NotBlank
    private String claimId;
    @NotBlank
    private String paymentId;
    @NotNull
    private PaymentStatus status;
}
