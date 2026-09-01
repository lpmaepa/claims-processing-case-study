package za.co.claims.processing.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ClaimControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void submissionReturnsAcceptedAndHighPriorityForDeathClaim() throws Exception {
        mockMvc.perform(post("/api/v1/claims")
                        .header("Idempotency-Key", "TEST-DEATH-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientId": "CLIENT123",
                                  "policyNumber": "POL456",
                                  "claimType": "DEATH"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.priority").value("HIGH"));
    }

    @Test
    void sameIdempotencyKeyReturnsSameClaimId() throws Exception {
        String body = """
                {
                  "clientId": "CLIENT123",
                  "policyNumber": "POL456",
                  "claimType": "DEATH"
                }
                """;

        String first = mockMvc.perform(post("/api/v1/claims")
                        .header("Idempotency-Key", "TEST-IDEMPOTENT-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        // Retrying the same Idempotency-Key returns the original claim with 200 OK, not another
        // 202 -- distinguishing "here's what you already submitted" from "newly accepted".
        String second = mockMvc.perform(post("/api/v1/claims")
                        .header("Idempotency-Key", "TEST-IDEMPOTENT-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);
    }
}
