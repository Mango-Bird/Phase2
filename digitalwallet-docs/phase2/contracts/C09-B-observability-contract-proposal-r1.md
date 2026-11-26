# C09-B observability contract proposal r1

Status: **PROPOSED — NOT ACCEPTED**

Owner of proposal: B (`TuanThanh06`)

Decision/acceptance owner: A (`Mango-Bird`)

Date: 2026-11-24 (Asia/Saigon)

This document resolves the open design questions recorded in `C09-B-r1.md`. B may implement a review candidate for C09-B-owned infrastructure, but every new convention remains **PROPOSED** until A accepts it. Existing C01/ADR/work-order requirements remain authoritative and are labelled **EXISTING** below.

## 1. Decisions requested from A

A is asked to accept, reject or revise these five decision groups:

1. Metric catalogue, bounded labels and histogram buckets in section 3.
2. `X-Correlation-ID` UUID policy, response echo and MDC keys in section 4.
3. Dedicated management listener, authentication boundary and health groups in section 5.
4. Ownership/seams between C06-A, C07-B, C09-A and C09-B in section 6.
5. Acceptance matrix and thresholds in section 7.

Approval of this proposal must be recorded by A as a contract revision or PR review. A green CI run or B's candidate implementation does not make the proposal ACCEPTED.

## 2. Existing contract versus new proposal

### 2.1 Already specified — EXISTING

- Platform owns configuration, metrics, security infrastructure and `outbox_events`; it does not call business modules directly.
- C09 requires a generic 500 response with `traceId`; internal exception details stay in controlled server logs. Authentication errors use the same error model.
- A correlation ID is created or validated at ingress and connects API, retries, transactions and events. `PaymentCompletedV1.correlationId` is required and persisted in immutable event payload v1.
- Required metric categories are endpoint duration/status, conflict/deadlock/retry, idempotency replay, DB pool, outbox pending/oldest age/DEAD and reconciliation findings.
- User ID, transaction ID and idempotency key must not be metric labels. Secrets, passwords, tokens, full bank account data and sensitive request bodies must not be logged.
- The outbox defaults are poll 1s, batch 50, lease 30s, maximum five attempts and retry delays 1/2/4/8s plus 0..250ms jitter. DEAD is an observable/auditable state, not by itself a reason to restart the application.
- Worker failure must be distinguishable from a Payment DB outage. A stopped worker must be observable while Payment remains available when the DB is healthy.
- C09-A owns security/ownership and business-log semantics. C09-B owns metric/log infrastructure, configuration and health. C06-A owns the atomic Payment publisher; C07-B owns worker/inbox/recovery.

Sources: `architecture-contracts-v1.md` sections 2, 5 and 7; `work-orders-v2.md` C06-A/C07-B/C09-A/C09-B; `phase2_implementation_plan.md` C09; ADR-002 and ADR-004.

### 2.2 Not yet specified — PROPOSED here

- Exact metric names, types, base units, label values, histogram buckets and Prometheus mapping.
- Correlation header name and syntax, invalid-input behavior, response propagation, MDC keys and asynchronous/worker cleanup.
- Management port/address, exposed endpoints, authentication boundary and health-group membership.
- Concrete telemetry and correlation seams that avoid concurrent edits to another task owner's code.
- Executable acceptance cases and worker-staleness thresholds.

## 3. Metric contract — PROPOSED

### 3.1 Naming and export rules

- Canonical names below are Micrometer names. Prometheus converts dots to underscores, counters to `_total`, and timers to `_seconds` series.
- Application-owned metrics use prefix `digitalwallet.`. Existing Spring Boot/Micrometer HTTP and Hikari metrics are reused instead of duplicated.
- Metric descriptions and base units are mandatory at registration. A metric name must have exactly one type and one fixed label-key set for the lifetime of the process.
- Labels contain only the bounded values listed below. Never use raw URI, username, user/wallet/transaction/event/reference IDs, idempotency key, exception message, SQL, claim owner or correlation ID as a label.
- A missing measurement is not fabricated as success. Outbox gauges are backed by one immutable, timestamped snapshot collected every `5s`. A failed collection does not advance the timestamp; the last successful snapshot remains visible for the configured `15s` scrape interval and then all four gauges return `NaN`.
- Gauge callbacks are memory-only and never acquire a JDBC connection or trigger a refresh. One fixed-delay background task executes one aggregate query for all four values, filters at the database boundary to `PENDING`, `CLAIMED` and `DEAD` (never retained DONE history), atomically publishes only a complete successful result, and rejects overlapping collection attempts.
- Collection has a `1500ms` end-to-end deadline covering connection acquisition and query/network I/O. It uses a dedicated read-only Hikari pool (`outbox-metrics-pool`) capped at one connection, so an unavailable metrics query cannot consume or reconfigure the Payment datasource. The collector owns one bounded daemon thread and closes its scheduler and pool during application shutdown; timed-out JDBC work is cancelled by the driver/network timeout rather than abandoned in another executor.

### 3.2 Standard metrics reused without aliases

| Canonical Micrometer name | Type / unit | Allowed labels | Policy |
|---|---|---|---|
| `http.server.requests` | Timer / seconds | `method`, templated `uri`, `status`, `outcome`, `exception` | `uri` must be a Spring route template or `UNKNOWN`, never a raw path. `exception` is the simple bounded exception class emitted by the framework, never a message. |
| `hikaricp.connections.active` | Gauge / connections | `pool` | Standard Hikari binder; pool name is configured and bounded. |
| `hikaricp.connections.idle` | Gauge / connections | `pool` | Standard Hikari binder. |
| `hikaricp.connections.pending` | Gauge / threads | `pool` | Standard Hikari binder; alert on sustained waiters. |
| `hikaricp.connections.max` | Gauge / connections | `pool` | Standard Hikari binder. |
| `hikaricp.connections.min` | Gauge / connections | `pool` | Standard Hikari binder. |
| `hikaricp.connections.acquire` | Timer / seconds | `pool` | Standard Hikari acquisition timer; no custom duplicate histogram. |
| `hikaricp.connections.timeout` | Counter / timeouts | `pool` | Standard Hikari timeout count. |

### 3.3 Digital-wallet metrics

| Canonical Micrometer name | Type / base unit | Exact labels and values | Meaning |
|---|---|---|---|
| `digitalwallet.payment.command.duration` | Timer / seconds | `operation=topup|withdraw|transfer`, `outcome=success|business_failure|technical_failure`, `replayed=true|false` | End-to-end `PaymentCommandApi` duration seen by the caller, including bounded retries and replay lookup. |
| `digitalwallet.payment.conflicts` | Counter / conflicts | `operation=topup|withdraw|transfer`, `reason=optimistic|deadlock|lock_timeout|claim_in_progress` | Classified concurrency/lock conflict observed by Payment. This records the conflict, whether or not it is retried. |
| `digitalwallet.payment.retries` | Counter / retries | `operation=topup|withdraw|transfer`, `reason=optimistic|deadlock`, `attempt=2|3` | A retry actually started. Lock timeout and claim-in-progress are not implicit retries under the existing contract. |
| `digitalwallet.payment.idempotency.replays` | Counter / replays | `operation=topup|withdraw|transfer`, `result=success|key_reused|in_progress` | Replay/result lookup classification; no idempotency key label. |
| `digitalwallet.outbox.events` | Gauge / events | `status=pending|claimed|dead` | Current row count by actionable state. DONE is intentionally excluded because it is retained history, not backlog. |
| `digitalwallet.outbox.oldest.pending.age` | Gauge / seconds | none | `max(0, now_utc - min(created_at))` over PENDING rows; `0` when none. Read with the PENDING count to distinguish empty from newly queued. |
| `digitalwallet.outbox.worker.polls` | Counter / polls | `result=success|db_error|unexpected_error` | Completed poll-loop iterations, including successful empty polls. |
| `digitalwallet.outbox.worker.events` | Counter / events | `result=done|retry_scheduled|dead|reclaimed` | Worker state transitions after a token-valid operation commits. |
| `digitalwallet.outbox.worker.last.success.age` | Gauge / seconds | none | Age since the last successfully completed poll; `NaN` before the first successful poll. |
| `digitalwallet.outbox.worker.up` | Gauge / ratio | none | `1` when the worker health rule in section 5 is UP, otherwise `0`. |
| `digitalwallet.reconciliation.findings` | Gauge / findings | `severity=error|warning` | Finding count from the latest completed reconciliation run; implemented only when C10-B publishes the real seam. |

Prometheus examples are `digitalwallet_payment_retries_total`, `digitalwallet_outbox_events`, and `digitalwallet_outbox_oldest_pending_age_seconds`. Dashboards and alerts must use exported names but source code/tests assert the canonical Micrometer name and tag set.

### 3.4 Histogram policy

- Enable percentile histograms only for `http.server.requests` and `digitalwallet.payment.command.duration`.
- Service-level buckets, in seconds: `0.010, 0.025, 0.050, 0.100, 0.250, 0.500, 1, 2, 5, 10`.
- Do not publish client-side percentile gauges. Compute p50/p95/p99 from histogram buckets in the monitoring backend so instances can be aggregated.
- HTTP histogram buckets are registered consistently; the product-latency dashboard queries only templated `/api/**` routes and excludes Swagger/management series.
- Counters and gauges never enable histograms. Standard Hikari timers retain their framework defaults unless a later performance contract provides evidence for extra buckets.

## 4. Correlation and structured-log infrastructure — PROPOSED

### 4.1 HTTP ingress and response

- Header: `X-Correlation-ID` (case-insensitive per HTTP).
- Accepted value: exactly one canonical UUID string, 36 characters, matching UUID syntax. Uppercase hex is accepted then normalized to lowercase; whitespace is not trimmed.
- Missing header: generate a cryptographically strong random UUID v4.
- Invalid, blank or multiple values: do not trust or echo them; replace with a new UUID v4 and continue. Record one bounded debug event, without the rejected value. This avoids reflecting attacker-controlled log content and avoids turning correlation into an availability gate.
- Echo the effective normalized/generated value as `X-Correlation-ID` on every HTTP response, including validation, authentication/authorization and generic 500 responses.
- Existing error field `traceId` equals the effective correlation ID. No second random identifier is generated by `GlobalExceptionHandler` or `SecurityConfig`.

### 4.2 Context, MDC and cleanup

- A platform API `CorrelationIdProvider` exposes the current effective ID and can create a safe internal ID when no HTTP context exists. It does not expose servlet, SLF4J or Micrometer types.
- Exact MDC keys: `correlationId` and `traceId`, both containing the same value for compatibility with the HTTP error field. Business fields such as operation/reference are structured log arguments, not permanent MDC keys.
- The ingress filter runs before Spring Security. It saves any prior MDC values, installs both keys, executes the chain, writes the response header, and restores/removes both keys in `finally`.
- Thread-pool/async work must capture an immutable context snapshot at submission, install it only for task execution and restore prior values in `finally`. Do not use inheritable thread locals. The current synchronous request path does not justify adding async execution.
- C07-B worker installs the persisted `PaymentCompletedV1.correlationId` around one event-processing attempt and always clears/restores it in `finally`, including handler failure, token loss and cancellation.
- Values are logged as structured fields. Never concatenate unvalidated header content into a log line. JSON log output is preferred in deployment; human-readable dev output may remain, provided the same named fields are present.

### 4.3 Business log boundary

C09-A owns the actual Payment/retry/idempotency/error log events. C09-B supplies correlation/context and structured encoder infrastructure. Proposed bounded event names for A to confirm are:

- `payment.command.completed`
- `payment.retry.scheduled`
- `payment.idempotency.replayed`
- `payment.command.failed`
- `outbox.event.appended` (C06-A)
- `outbox.event.processed` / `outbox.event.retry_scheduled` / `outbox.event.dead` (C07-B)

Logs may include `operation`, `attempt`, machine error code, event type and transaction `referenceCode` when needed for the existing traceability requirement. They must not contain JWTs, password/secret, idempotency key, request body, full bank account, raw SQL or exception messages known to contain sensitive input. Stack traces for unexpected server errors remain server-side at ERROR with the safe correlation fields.

## 5. Management and health contract — PROPOSED

### 5.1 Endpoint and access policy

- Add Spring Boot Actuator and the Prometheus registry only after approval.
- Dedicated management listener: `MANAGEMENT_SERVER_PORT` default `9091`; `MANAGEMENT_SERVER_ADDRESS` default `127.0.0.1`. It must never be routed through the public application ingress.
- Base path `/actuator`. Only `health` and `prometheus` are exposed. Explicitly do not expose `env`, `configprops`, `beans`, `mappings`, `loggers`, `heapdump`, `threaddump` or `shutdown`.
- `/actuator/health/liveness` and `/actuator/health/readiness` are unauthenticated only on the private management listener so an orchestrator can probe them. Health details are `never` for anonymous callers.
- `/actuator/prometheus`, `/actuator/health/worker` and component details require the repository's current JWT authentication. No new monitoring credential, role or security identity is invented in C09-B; A may require a dedicated role in a later security revision.
- If a deployment binds management to a non-loopback address, private network ACL plus current authentication are both mandatory. Public ingress tests must prove `/actuator/**` is not served on the application port.

### 5.2 Health groups and semantics

| Group/component | Members | Status rule | Orchestrator effect |
|---|---|---|---|
| `liveness` | `livenessState` only | DOWN only when the process cannot make progress | Restart may occur; DB and worker are excluded to prevent restart loops. |
| `readiness` | `readinessState`, `db` | DOWN when the app is refusing traffic or DB validation fails | Remove application instance from service. Worker and DEAD count are excluded. |
| `worker` | `outboxWorker` | Rules below | Alert/triage only; never used as application liveness/readiness probe. |

Worker rules:

- `UP`: worker is enabled, loop is alive and a poll completed successfully within `max(10s, 3 * pollInterval)`.
- `UNKNOWN` with reason `STARTING`: enabled but still within a 15-second startup grace and no successful poll exists.
- `OUT_OF_SERVICE` with reason `DISABLED`: intentionally disabled. This is visible/alertable but does not change Payment readiness.
- `DOWN`: loop terminated unexpectedly (`LOOP_STOPPED`), success age exceeded the threshold (`POLL_STALE`), or three consecutive polls failed. Use reason `DB_UNAVAILABLE` when the classified failure is DB connectivity; otherwise `POLL_FAILED`.
- Health details contain only reason, safe timestamps/ages, threshold and consecutive-failure count. They never expose exception text, SQL, event payload or credentials.
- One or more DEAD rows do not mark worker/app DOWN. Alert separately from `digitalwallet.outbox.events{status="dead"}` and follow replay/audit runbook.

## 6. Integration ownership and merge order — PROPOSED

### 6.1 C09-B-owned infrastructure

- Dependencies/configuration for Actuator, Prometheus, structured encoder, metric binders and health groups.
- Ingress correlation filter, `CorrelationIdProvider`, safe context/MDC lifecycle and tests.
- Read-only outbox backlog gauges using C06-B schema/indexes; queries never update/claim/replay rows.
- Metric adapters and worker health recorder that other owners call through typed seams.

### 6.2 C09-A handoff

- A changes `GlobalExceptionHandler` and `SecurityConfig` so error/auth responses reuse the effective correlation ID and generic 500 does not expose `ex.getMessage()`.
- A owns Payment/retry/idempotency business log points and classification. C09-B does not edit those files concurrently.
- A should publish a typed Payment telemetry output port under Payment ownership (operation, outcome, conflict reason, retry attempt and replay result). C09-B supplies its Micrometer adapter. This avoids stringly typed labels and prevents Platform from calling Payment.

### 6.3 C06-A handoff

- When constructing `PaymentCompletedV1`, C06-A obtains the effective value through the approved platform `CorrelationIdProvider`; it appends once inside the existing Payment transaction and does not create a different event trace.
- C06-A emits the `outbox.event.appended` business log only after append succeeds; transaction rollback still means no durable outbox row. Metric/log emission must not change transaction outcome.

### 6.4 C07-B handoff

- C07-B calls a C09-B worker observer after committed state transitions and reports poll success/failure/lifecycle heartbeat. Telemetry failure is caught and logged safely; it never marks an event DONE or changes retry semantics.
- Worker processing restores the event correlation ID around the token-valid attempt. Claim owner/token/event ID may appear in controlled debug logs but never metric labels.
- Health derives from real loop callbacks, not a synthetic scheduled gauge. C09-B must not ship final worker-health integration before the real C07-B seam exists.

Proposed merge order: C09-B supplies the proposal and independently testable correlation/management/metric infrastructure as one Draft PR -> A reviews the proposal and candidate together -> C09-A and C06-A integrate their owned seams -> C07-B integrates worker callbacks -> C09-B/C07-B run the final worker-health acceptance cases. Any different order needs an explicit compatibility adapter and removal ticket.

## 7. Acceptance criteria and test scenarios — PROPOSED

| Case | Scenario | Expected result |
|---|---|---|
| C09B-01 | Production startup without/blank/short JWT secret | Startup fails without revealing the value; existing JWT tests remain PASS. Already implemented on the partial branch. |
| C09B-02 | No correlation header | One UUID v4 is generated; response header, error `traceId`, MDC and appended event correlation match. |
| C09B-03 | Valid uppercase UUID | Accepted, normalized lowercase and echoed consistently. |
| C09B-04 | Invalid/blank/multiple correlation header | Untrusted value is not logged/echoed; a fresh UUID is used and request behavior otherwise remains unchanged. |
| C09B-05 | Parallel requests on reused servlet threads | Each response/log has only its own ID; MDC is empty/restored after each request and after exceptions/auth rejection. |
| C09B-06 | Worker processes/fails/reclaims two events concurrently | Each attempt uses its event correlation and clears it; no cross-event context leak. |
| C09B-07 | Exercise success, business failure, deadlock/optimistic retry, replay and lock timeout | Exact counters/timer labels increment once at their defined observation point; no forbidden/high-cardinality labels. |
| C09B-08 | Inspect Prometheus exposition | Required standard/custom names, types, units and histogram buckets exist; no raw URI, ID, key, correlation or message labels. |
| C09B-09 | Query backlog with no rows, PENDING/CLAIMED/DEAD rows, slow/lost DB and exhausted metrics pool | One real MySQL aggregate produces a complete snapshot; Prometheus scrape remains responsive and still exports HTTP/Hikari series; a recent success is retained, then becomes `NaN`; recovery replaces it with current DB values. No collection overlap, orphan task or Payment-pool starvation occurs. |
| C09B-10 | Public application port requests `/actuator/**` | Not exposed. Private liveness/readiness work without details; Prometheus/worker details reject missing/invalid monitoring credentials. |
| C09B-11 | DB unavailable | Readiness DOWN; liveness remains UP; worker reason is `DB_UNAVAILABLE`; Payment is unavailable as expected. |
| C09B-12 | Worker loop stopped while DB remains healthy | Worker DOWN and alert metric changes; readiness/liveness stay UP and a Payment command can still complete/append. |
| C09B-13 | Worker intentionally disabled | Worker OUT_OF_SERVICE/DISABLED is observable; API readiness remains UP with healthy DB. |
| C09B-14 | Secret/PII scan and unexpected 500 | Client sees generic message and matching trace ID; controlled server log has stack trace/correlation but no secret, token, key, body or full bank account. |
| C09B-15 | Full backend, MySQL integration and architecture suite | Required `Backend build and test` succeeds on candidate head; no task-owner boundary or existing transaction/event semantics change. |

## 8. Compatibility and rollback

- HTTP business paths/bodies/statuses remain unchanged. `X-Correlation-ID` is an additive response header. Reusing it in the existing error `traceId` field is the proposed C09-A integration target and is not claimed by the independent C09-B candidate.
- `PaymentCompletedV1` event type/schema version and storage schema remain unchanged; only the already-required correlation value receives an exact generation policy.
- Metrics/health are additive and read-only. Removing the implementation rolls back observability but must not migrate or delete business/outbox data.
- Management bind address is deployment configuration. The candidate reuses current JWT authentication and introduces no committed monitoring credential. A deployment must not widen listener exposure before its ACL/auth checks pass.
- Dashboard/alert thresholds beyond the worker staleness rule remain operational tuning, recorded with evidence; they must not change financial or retry behavior.

## 9. A review record — intentionally blank

- Decision: **PENDING**
- Accepted/rejected sections:
- Required revisions:
- A / date / candidate SHA:

Until this record or an equivalent A review is present, this document and its implementation remain PROPOSED and must not be described as ACCEPTED or release-ready.
