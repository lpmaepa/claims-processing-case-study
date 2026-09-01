package za.co.claims.processing.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the workflow scenarios listed in the case study's Testing Strategy
 * section that were not yet backed by automated tests:
 * <ul>
 *   <li>Invalid client -&gt; CLIENT_VALIDATION_FAILED, Payment System never reached</li>
 *   <li>Invalid policy -&gt; POLICY_VALIDATION_FAILED, Payment System never reached</li>
 *   <li>Valid client + valid policy -&gt; payment initiated</li>
 *   <li>Technical downstream failure -&gt; retryable failure, not a business rejection</li>
 * </ul>
 * Processing runs asynchronously ({@code @Async @EventListener} in
 * {@code ClaimProcessor}, published by the outbox on a 1s scheduled poll),
 * so each scenario submits a claim and then polls the status endpoint with
 * Awaitility until the expected terminal-for-this-scenario status appears.
 * The trigger values below (INVALID_CLIENT, CLIENT_ERROR, INVALID_POLICY)
 * are the same ones already wired into the Dummy* client implementations.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClaimProcessingWorkflowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void invalidClientFailsValidationAndNeverReachesPayment() throws Exception {
        JsonNode statusResponse = submitAndAwaitStatus(
                "INVALID_CLIENT", "POL456", "OTHER",
                "TEST-INVALID-CLIENT-1", "CLIENT_VALIDATION_FAILED");

        assertThat(statusResponse.get("paymentId").isNull()).isTrue();
        assertThat(statusResponse.get("failureReason").asText())
                .contains("Client validation failed");
    }

    @Test
    void invalidPolicyFailsValidationAndNeverReachesPayment() throws Exception {
        JsonNode statusResponse = submitAndAwaitStatus(
                "CLIENT123", "INVALID_POLICY", "OTHER",
                "TEST-INVALID-POLICY-1", "POLICY_VALIDATION_FAILED");

        assertThat(statusResponse.get("paymentId").isNull()).isTrue();
        assertThat(statusResponse.get("failureReason").asText())
                .contains("Policy validation failed");
    }

    @Test
    void validClientAndPolicyInitiatesPayment() throws Exception {
        JsonNode statusResponse = submitAndAwaitStatus(
                "CLIENT123", "POL456", "OTHER",
                "TEST-VALID-PAYMENT-1", "PAYMENT_PROCESSING");

        assertThat(statusResponse.get("paymentId").asText()).isNotBlank();
        assertThat(statusResponse.get("failureReason").isNull()).isTrue();
    }

    @Test
    void technicalDownstreamFailureIsRetryableNotBusinessRejection() throws Exception {
        JsonNode statusResponse = submitAndAwaitStatus(
                "CLIENT_ERROR", "POL456", "OTHER",
                "TEST-TECHNICAL-FAILURE-1", "PROCESSING_FAILED");

        assertThat(statusResponse.get("retryCount").asInt()).isGreaterThan(0);
        assertThat(statusResponse.get("status").asText())
                .isNotIn("CLIENT_VALIDATION_FAILED", "POLICY_VALIDATION_FAILED");
    }

    private JsonNode submitAndAwaitStatus(
            String clientId,
            String policyNumber,
            String claimType,
            String idempotencyKey,
            String expectedStatus) throws Exception {

        String body = String.format("""
                {
                  "clientId": "%s",
                  "policyNumber": "%s",
                  "claimType": "%s"
                }
                """, clientId, policyNumber, claimType);

        String submissionJson = mockMvc.perform(post("/api/v1/claims")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String claimId = objectMapper.readTree(submissionJson).get("claimId").asText();

        JsonNode[] latestStatus = new JsonNode[1];

        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String statusJson = mockMvc.perform(get("/api/v1/claims/" + claimId))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString();

                    JsonNode node = objectMapper.readTree(statusJson);
                    latestStatus[0] = node;
                    assertThat(node.get("status").asText()).isEqualTo(expectedStatus);
                });

        return latestStatus[0];
    }
}
