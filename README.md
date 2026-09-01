# Claims Processing Case Study — Java & AWS

A runnable Spring Boot sample that demonstrates the integration/orchestration layer described in the insurance claims case study.

## Purpose

The **Existing Claims System remains the authoritative system of record for the claim**. This application is the orchestration layer. It stores only processing metadata needed to reliably move a claim through Client Registry, Policy Manager and Payment.

The sample runs locally with H2 and dummy downstream clients. The production target described by the design is AWS: API Gateway, ECS/Fargate, RDS/PostgreSQL, separate high-priority and standard SQS queues, DLQ, CloudWatch, SNS, IAM and Secrets Manager.

## Key design decisions implemented

- `POST /api/v1/claims` returns **202 Accepted** with `RECEIVED` for a new claim, or **200 OK** with the original claim if the same `Idempotency-Key` is retried; it does not synchronously call Client Registry, Policy Manager or Payment.
- The caller supplies an `Idempotency-Key` header. Retrying the same operation returns the existing claim instead of creating another -- including when two identical requests race each other; see [Idempotency](#idempotency) below.
- `ClaimProcessingState` stores workflow/processing metadata only; it is not a second Claims System.
- `ClaimsSystemClient` represents the existing authoritative Claims System. `DummyClaimsSystemClient` is only the local case-study adapter.
- Claim state and an outbox record are written in the **same database transaction**, in `ClaimStatePersister` -- deliberately a separate call to the external `ClaimsSystemClient`, which cannot itself participate in that transaction. See [Transactional outbox](#transactional-outbox).
- `OutboxPublisher` publishes pending events after commit. The local implementation uses Spring application events to simulate a queue without AWS credentials.
- `ClaimProcessor` performs downstream workflow asynchronously, with `claimId`/`correlationId` in every log line via MDC.
- Death claims are HIGH priority and map to `HIGH_PRIORITY`; other supported demo claim types map to `STANDARD`.
- Client validation belongs to `ClientRegistryClient`; policy/plan/benefit validation belongs to `PolicyManagerClient`; payment initiation belongs to `PaymentClient`.
- Payment completion is asynchronous through `POST /api/v1/payments/status` as a local callback fallback.
- Business validation failures and technical integration failures are treated differently; technical failures are retried with backoff and, after `claims.retry.max-attempts`, moved to `MANUAL_REVIEW` -- see [Retry, backoff and dead-letter handling](#retry-backoff-and-dead-letter-handling).
- `SlaMonitor` periodically flags claims that have exceeded their SLA window -- see [SLA monitoring](#sla-monitoring).
- API entities are not exposed directly; request/response DTOs define the HTTP contract.
- Centralised exception handling is provided by `GlobalExceptionHandler`.
- Swagger/OpenAPI is enabled for demo use.

## Local architecture

```text
Web Form / Swagger
        |
        | POST /api/v1/claims + Idempotency-Key
        v
ClaimController -> ClaimService
                        |
                        | 1. ClaimsSystemClient.createClaim()  (external call, outside any local tx)
                        v
                   ClaimStatePersister  --@Transactional--> ClaimProcessingState + OutboxEvent
                                                              status=RECEIVED, event=PENDING
                                                                     |
                                                                     | scheduled publisher
                                                                     v
                                                              Local queue adapter
                                                                     |
                                                                     | @Async event
                                                                     v
                                                              ClaimProcessor
                                                               /    |      \
                                                              v     v       v
                                                       Client     Policy   Payment
                                                       Registry   Manager  System
                                                                             |
                                                                             | final callback/event
                                                                             v
                                                                     PaymentStatusController

Technical failure (DownstreamTechnicalException) inside ClaimProcessor:
   PROCESSING_FAILED (retryCount++, nextRetryAt = now + backoff)
        |
        | ClaimRetryScheduler re-invokes ClaimProcessor.process() once nextRetryAt elapses
        v
   recovers -> continues the workflow            OR            retryCount >= max-attempts -> MANUAL_REVIEW
```

## Production queue mapping

The local `LocalClaimEventPublisher` intentionally avoids AWS credentials so the reviewer can run the sample immediately. In AWS it is replaced by an SQS adapter:

```text
HIGH priority   -> High Priority SQS -> claim processor workers
NORMAL priority -> Standard SQS      -> claim processor workers
                                      -> DLQ after configured retries
```

The same `ClaimEventPublisher` abstraction allows the transport to change without changing `ClaimService`.

## Transactional outbox

`ClaimStatePersister.persist(...)` runs one local transaction that saves:

1. `ClaimProcessingState` with `RECEIVED`.
2. `OutboxEvent` with `PENDING`.

If that transaction fails, neither record is committed. If the queue is temporarily unavailable after commit, the pending outbox row remains available for a later publish attempt. This avoids the classic failure where the claim is persisted but the queue message is silently lost.

The outbox protects the **database-to-message-broker boundary**. It is a separate Spring bean rather than a private method on `ClaimService` deliberately: a `@Transactional` method reached via a same-class `this.foo()` call bypasses Spring's proxy and silently runs with no transaction at all, so the guarantee only holds if the annotated method is invoked through another bean.

`ClaimService.submitClaim(...)` calls `ClaimsSystemClient.createClaim(...)` (the existing, authoritative Claims System) **before** calling `ClaimStatePersister`, and deliberately outside that local transaction: a real HTTP call to another system cannot participate in a local ACID transaction, and holding a database transaction open for the duration of an external call is a connection-pool/lock-contention risk under load. The trade-off is a narrower, explicit gap: if `createClaim()` succeeds but the local persist step then fails for a reason unrelated to the idempotency race (e.g. the database itself is unreachable), the authoritative system has a claim with no local processing state or outbox event, and it is never picked up for orchestration. Fully closing that needs a two-phase or reconciliation approach across systems and is out of scope here; it's called out rather than left implicit.

## Idempotency

The web form creates an `Idempotency-Key` before its first POST and reuses the same key when retrying that same submission.

```text
POST key=ABC -> 202 Accepted, CLM-123 created
network timeout
POST key=ABC -> 200 OK, existing CLM-123 returned (no second downstream call)
```

This is technical request idempotency. It is separate from business duplicate-claim detection, whose exact rules must be confirmed with the business (for example policy, client, claim type and incident/event reference).

Payment initiation uses a separate stable idempotency key: `<claimId>-PAYMENT`.

**Concurrent duplicates.** `ClaimService` checks for an existing claim before creating one, which is a check-then-act: two requests with the same key can both pass that check before either commits. The database's unique constraint on `idempotency_key` is the real guard -- `ClaimStatePersister` catches the loser's `DataIntegrityViolationException`, re-reads the winner, and returns it with `duplicate=true` instead of letting a raw constraint violation surface as a 500. `ClaimStatePersisterTest` exercises this directly with mocks.

## Running the app

Requirements:

- Java 17
- Maven 3.8+

```bash
mvn clean test
mvn spring-boot:run
```

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

H2 console:

```text
http://localhost:8080/h2-console
JDBC URL: jdbc:h2:mem:claimsdb
User: sa
Password: <blank>
```

## Retry, backoff and dead-letter handling

A `DownstreamTechnicalException` (a technical failure -- timeout, service unavailable -- as opposed to a business rejection) moves a claim to `PROCESSING_FAILED` with an exponential backoff timer (`claims.retry.*` in `application.yml`). `ClaimRetryScheduler` polls for claims whose backoff window has elapsed and re-invokes `ClaimProcessor.process(...)` for them. Because every downstream call in the workflow is idempotent (the dummy client/policy checks are deterministic; `DummyPaymentClient` dedupes by `<claimId>-PAYMENT`), safely replaying the whole workflow from the top is correct, not just convenient.

Once `retryCount` reaches `claims.retry.max-attempts`, the claim moves to `MANUAL_REVIEW` instead of retrying again -- the local, single-instance equivalent of a message landing in a dead-letter queue after SQS exhausts its redrive policy. `ClaimRetryWorkflowIntegrationTest` drives a `CLIENT_ERROR` claim through this end to end and asserts it lands in `MANUAL_REVIEW`.

In production this scheduler is replaced by SQS's own visibility-timeout/redrive-policy mechanics and a real DLQ; the local version exists so the retry behaviour is demonstrated, not only described in the design doc.

## SLA monitoring

`SlaMonitor` periodically scans non-terminal claims and compares elapsed time since `receivedAt` against a per-priority threshold (`claims.sla.high-priority-minutes` / `claims.sla.standard-minutes`). A breach is logged at `ERROR` and the claim is flagged `slaBreachNotified` so it doesn't re-alarm on every sweep; `GET /api/v1/claims/{claimId}` exposes this as `slaBreached` so a claims analyst querying the new service sees the same signal an ops CloudWatch alarm would raise, rather than that visibility existing only on the ops side. In production this becomes a CloudWatch metric and alarm feeding SNS. Exact SLA durations were not specified by the case study, so the thresholds are configuration, not constants.

## Demo flow

### 1. Submit a death claim

Header:

```text
Idempotency-Key: DEMO-DEATH-001
```

Body:

```json
{
  "clientId": "CLIENT123",
  "policyNumber": "POL456",
  "claimType": "DEATH"
}
```

Immediate response is `202 Accepted`:

```json
{
  "claimId": "CLM-XXXXXXXX",
  "status": "RECEIVED",
  "priority": "HIGH"
}
```

### 2. Query processing state

```text
GET /api/v1/claims/{claimId}
```

After the async processor runs, a valid claim normally reaches `PAYMENT_PROCESSING`.

### 3. Complete payment

Use the `claimId` and `paymentId` returned by GET:

```json
{
  "claimId": "CLM-XXXXXXXX",
  "paymentId": "PAY-XXXXXXXX",
  "status": "COMPLETED"
}
```

POST it to:

```text
/api/v1/payments/status
```

The final state becomes `PAYMENT_COMPLETED`.

## Dummy scenarios

The dummy clients make failure paths easy to demo:

| Input | Result |
|---|---|
| `clientId = INVALID_CLIENT` | `CLIENT_VALIDATION_FAILED` (business rejection, terminal, no retry) |
| `clientId = CLIENT_ERROR` | simulated technical Client Registry failure -> `PROCESSING_FAILED`, retried with backoff, then `MANUAL_REVIEW` once `claims.retry.max-attempts` is exhausted |
| `policyNumber = INVALID_POLICY` | `POLICY_VALIDATION_FAILED` (business rejection, terminal, no retry) |
| `policyNumber = POLICY_ERROR` | simulated technical Policy Manager failure -> same retry/`MANUAL_REVIEW` path as `CLIENT_ERROR` |
| normal values | progresses to `PAYMENT_PROCESSING` |

With the default `application.yml` values (3 attempts, 2s/4s backoff), submitting a `CLIENT_ERROR` claim and polling `GET /api/v1/claims/{claimId}` a few times over ~10 seconds shows `retryCount` climbing and the status settling on `MANUAL_REVIEW`.

## Package structure

```text
za.co.claims.processing
├── client       external-system boundaries + dummy adapters
├── config       OpenAPI + externalised retry/SLA configuration (ClaimsRetryProperties, ClaimsSlaProperties)
├── controller   HTTP boundary only
├── dto          API request/response contracts
├── entity       JPA persistence models
├── enums        controlled domain/workflow values
├── event        message/event abstraction and local adapter
├── exception    typed exceptions + global handler
├── repository   Spring Data persistence
└── service      submission, priority, outbox, orchestration, retry/DLQ, SLA monitoring and payment status
```

## Recommended class review order

1. `ClaimSubmissionRequest`
2. `ClaimSubmissionResponse`
3. `ClaimController`
4. `ClaimType`, `ClaimPriority`, `ClaimStatus`
5. `ClaimProcessingState`
6. `ClaimStateRepository`
7. `ClaimsSystemClient` + `DummyClaimsSystemClient`
8. `PriorityResolver`
9. `ClaimResponseMapper`
10. `ClaimService`
11. `ClaimStatePersister` + `SubmissionOutcome`
12. `OutboxEvent` + `OutboxEventRepository`
13. `ClaimEventPublisher` + `LocalClaimEventPublisher`
14. `OutboxPublisher`
15. `ClientRegistryClient` + dummy
16. `PolicyManagerClient` + dummy
17. `PaymentClient` + dummy
18. `ClaimProcessor`
19. `ClaimRetryScheduler`, `ClaimsRetryProperties`
20. `SlaMonitor`, `ClaimsSlaProperties`
21. `PaymentStatusController` + `PaymentStatusService`
22. `GlobalExceptionHandler`
23. tests

## SOLID choices

- **SRP:** controllers handle HTTP, repositories persistence, clients external boundaries, processor workflow orchestration, priority resolver priority policy.
- **OCP:** real AWS/SQS or downstream HTTP adapters can replace dummy adapters behind existing interfaces.
- **LSP:** dummy adapters implement the same contracts expected from production adapters.
- **ISP:** each client interface exposes only the operations this application needs.
- **DIP:** services depend on `ClaimsSystemClient`, `ClientRegistryClient`, `PolicyManagerClient`, `PaymentClient` and `ClaimEventPublisher` abstractions rather than concrete integrations.

## Important production gaps intentionally left out

This is a case-study sample rather than a production deployment. Retry/backoff, dead-letter handling, correlation IDs and SLA monitoring are now demonstrated locally (see above) rather than only described, but the local versions are simplified stand-ins, not the real thing. Production hardening would still add: OAuth2/OIDC/JWT integration and service-to-service authentication; real AWS SQS adapters, visibility timeouts and DLQ redrive in place of `ClaimRetryScheduler`'s in-process polling; RDS/PostgreSQL migrations in place of H2; real CloudWatch metrics/alarms and SNS notification in place of `SlaMonitor`'s log lines; business duplicate-claim rules (see [Idempotency](#idempotency) -- this is deliberately out of scope, not overlooked); secrets in Secrets Manager; API contract tests; and deployment infrastructure. The residual dual-write gap between the external Claims System call and the local transaction (see [Transactional outbox](#transactional-outbox)) is also a known, documented limitation rather than something a full production build would ship with unaddressed.
