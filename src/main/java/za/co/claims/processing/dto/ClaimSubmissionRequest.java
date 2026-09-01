package za.co.claims.processing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import za.co.claims.processing.enums.ClaimType;

@Getter
@Setter
public class ClaimSubmissionRequest {

    @NotBlank
    private String clientId;

    @NotBlank
    private String policyNumber;

    @NotNull
    private ClaimType claimType;
}
