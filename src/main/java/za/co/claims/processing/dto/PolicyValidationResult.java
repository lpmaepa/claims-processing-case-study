package za.co.claims.processing.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class PolicyValidationResult {

    private boolean valid;
    private BigDecimal payableAmount;
}