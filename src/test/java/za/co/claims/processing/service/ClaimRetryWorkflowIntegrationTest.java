package za.co.claims.processing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the retry/DLQ loop end to end: a claim that keeps hitting a technical failure
 * (CLIENT_ERROR, the same trigger DummyClientRegistryClient already uses) should be re-driven by
 * ClaimRetryScheduler with backoff, and once claims.retry.max-attempts is exhausted it should land
 * in MANUAL_REVIEW -- the sample's dead-letter equivalent -- rather than sitting in
 * PROCESSING_FAILED forever.
 * <p>
 * Runs with its own fast retry cadence via @TestPropertySource so the loop completes well inside
 * a short Awaitility window; the default (src/test/resources/application.yml) keeps the retry
 * scheduler effectively off so it doesn't interfere with the other workflow tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // A distinct property set means Spring creates a separate ApplicationContext (and
        // therefore a separate DataSource bean) from the other @SpringBootTest classes. Pointing
        // it at its own named H2 in-memory database avoids that new context's create-drop DDL
        // racing against a schema the other, still-cached contexts are also using -- H2 shares an
        // in-memory database by name across connections within the same JVM, so reusing
        // "claimsdb" here would risk one context dropping tables another still has data in.
        "spring.datasource.url=jdbc:h2:mem:claimsdb-retry;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "claims.retry.max-attempts=2",
        "claims.retry.initial-backoff-ms=100",
        "claims.retry.backoff-multiplier=1",
        "claims.retry.max-backoff-ms=100",
        "claims.retry.scheduler-delay-ms=150"
})
class ClaimRetryWorkflowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void repeatedTechnicalFailureEventuallyMovesToManualReview() throws Exception {
        String body = """
                {
                  "clientId": "CLIENT_ERROR",
                  "policyNumber": "POL456",
                  "claimType": "OTHER"
                }
                """;

        String submissionJson = mockMvc.perform(post("/api/v1/claims")
                        .header("Idempotency-Key", "TEST-RETRY-DLQ-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String claimId = objectMapper.readTree(submissionJson).get("claimId").asText();

        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    String statusJson = mockMvc.perform(get("/api/v1/claims/" + claimId))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString();

                    JsonNode node = objectMapper.readTree(statusJson);
                    assertThat(node.get("status").asText()).isEqualTo("MANUAL_REVIEW");
                    assertThat(node.get("retryCount").asInt()).isGreaterThanOrEqualTo(2);
                });
    }
}
