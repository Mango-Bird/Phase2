# C09-B candidate evidence — configuration and observability infrastructure

Date: 2026-11-26 (Asia/Saigon)

Status: **PARTIAL / PROPOSED — NOT ACCEPTED**

Proposal: `contracts/C09-B-observability-contract-proposal-r1.md`

## Implemented and verified

- Production has no JWT fallback; unresolved, blank or shorter-than-32-byte signing material fails startup without printing the value.
- `X-Correlation-ID` accepts one UUID, normalizes uppercase, replaces absent/invalid/multiple values, echoes the effective UUID and installs identical `correlationId`/`traceId` MDC values with `finally` cleanup.
- Concurrent filter tests prove no MDC leak across reused threads. `CorrelationIdProvider` and scoped install are available for C06-A/C07-B integration.
- Actuator/Prometheus run on a dedicated loopback management listener by default. Only `health` and `prometheus` are exposed. Liveness/readiness probes are permitted on that listener; Prometheus and worker details retain current JWT authentication. The application listener does not serve Actuator.
- HTTP server and Hikari use their standard Micrometer metrics. Server histogram buckets are configured according to the proposal.
- Outbox PENDING/CLAIMED/DEAD and oldest-PENDING-age gauges read only an atomic immutable memory snapshot. A single fixed-delay collector refreshes all four values from the real C06-B table with one aggregate query; Prometheus scrapes never execute JDBC.
- Collection defaults are interval `5s`, end-to-end deadline `1500ms`, and freshness TTL `15s`. The dedicated read-only `outbox-metrics-pool` is capped at one connection and is separate from Payment's datasource. Failed collection retains the prior timestamp/value only within the TTL; no success or stale success exports `NaN`; recovery publishes current database values.
- MySQL 8.4 integration tests exercise the authenticated Prometheus endpoint during a real `SLEEP(5)` query, an exhausted metrics pool and a closed metrics datasource. Each scrape remains below the one-second test bound and still contains `http_server_requests_seconds` and `hikaricp_connections_active`; timeout cleanup leaves no waiter/active-connection leak, and collection recovers afterward.

## Actionable-only snapshot query and execution plan

The P2 follow-up excludes retained DONE history in the database predicate while preserving the aggregate semantics:

```sql
-- Before
FROM outbox_events

-- After
FROM outbox_events
WHERE status IN ('PENDING', 'CLAIMED', 'DEAD')
```

Evidence was collected on the dedicated MySQL 8.4 Testcontainers database after `ANALYZE TABLE outbox_events`. The deterministic fixture contained `DONE=10000`, `PENDING=1`, `CLAIMED=1`, and `DEAD=1`. Empty and DONE-only states both produced a valid all-zero snapshot. The one PENDING row was 10 seconds old while all DONE rows were one day old; the returned oldest-pending age remained based only on PENDING.

The C06-B schema already supplies `idx_outbox_dispatch(status, next_attempt_at, created_at)` and `idx_outbox_lease(status, lease_until)`. The former is selected and covering for the snapshot query, so no schema migration or `FORCE INDEX` was added.

| Query | Access type | Selected key | Estimated input rows | Actual input rows | Aggregate execution |
|---|---|---|---:|---:|---:|
| Before predicate | `index` | `idx_outbox_dispatch` | 9990 | 10003 | 2.03 ms |
| After actionable predicate | `range` | `idx_outbox_dispatch` | 3 | 3 | 0.142 ms |

`EXPLAIN ANALYZE` before reported a covering full index scan (`actual time=0.0261..0.904`, 10003 rows). After reported a covering index range scan over exactly CLAIMED, DEAD and PENDING (`actual time=0.0138..0.0203`, 3 rows), followed by the status filter (`actual time=0.0352..0.0421`, 3 rows). These are local test-environment measurements, not production performance claims.
- `OutboxWorkerObserver` and `OutboxWorkerMonitor` provide typed callback, bounded counters and separate health semantics. With no C07-B worker, the real status is `OUT_OF_SERVICE/DISABLED`; no heartbeat or event transition is invented.
- Integration evidence inserts a real PENDING row into MySQL 8.4 and verifies its value in authenticated Prometheus exposition.

## Commands and results

Focused unit/integration regression:

```text
.\gradlew test --tests "*CorrelationIdFilterTest" --tests "*OutboxMetricsBinderTest" \
  --tests "*OutboxWorkerMonitorTest" --tests "*ManagementObservabilityIntegrationTest" --no-daemon
```

Result: PASS, including five MySQL 8.4 management integration scenarios and controlled-clock/no-overlap snapshot regressions.

Full backend regression:

```text
.\gradlew clean build --no-daemon --stacktrace
```

Result: `BUILD SUCCESSFUL` in 4m 21s; **281 tests, 0 failures, 0 errors, 0 skipped**. This includes WebMvc/security slices, architecture tests and MySQL Testcontainers migration/transaction/integration tests.

## Explicitly blocked integration — not claimed

- C09-A: generic 500 and auth error bodies still generate their own `traceId`; making the body equal the effective correlation ID and hardening business/error logs requires A's owned `GlobalExceptionHandler`, `SecurityConfig` and Payment log points.
- C06-A: no publisher exists on this base, so `PaymentCompletedV1.correlationId` is not yet populated through `CorrelationIdProvider`; Payment conflict/retry/replay counters also need an A-owned typed telemetry hook.
- C07-B: no worker exists, so observer callbacks, event correlation restoration and live worker-stop/recovery evidence cannot be integrated. Disabled worker health is reported honestly.
- C10-B: reconciliation findings metric waits for the real reconciliation result seam.
- The custom `digitalwallet.payment.*` metric catalogue is specified but not registered with synthetic zeroes and not inferred from HTTP status.

This evidence proves only the independent C09-B infrastructure. It is not a claim that the full C09 acceptance gate or proposal is ACCEPTED.
