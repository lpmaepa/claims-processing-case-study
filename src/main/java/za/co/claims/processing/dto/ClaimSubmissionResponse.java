package za.co.claims.processing.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import za.co.claims.processing.enums.ClaimPriority;
import za.co.claims.processing.enums.ClaimStatus;

@Getter
@AllArgsConstructor
public class ClaimSubmissionResponse {
    private String claimId;
    private ClaimStatus status;
    private ClaimPriority priority;
}
