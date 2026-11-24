# C09-B candidate evidence — configuration and observability infrastructure

Date: 2026-11-24 (Asia/Saigon)

Status: **PARTIAL / PROPOSED — NOT ACCEPTED**

Proposal: `contracts/C09-B-observability-contract-proposal-r1.md`

## Implemented and verified

- Production has no JWT fallback; unresolved, blank or shorter-than-32-byte signing material fails startup without printing the value.
- `X-Correlation-ID` accepts one UUID, normalizes uppercase, replaces absent/invalid/multiple values, echoes the effective UUID and installs identical `correlationId`/`traceId` MDC values with `finally` cleanup.
- Concurrent filter tests prove no MDC leak across reused threads. `CorrelationIdProvider` and scoped install are available for C06-A/C07-B integration.
- Actuator/Prometheus run on a dedicated loopback management listener by default. Only `health` and `prometheus` are exposed. Liveness/readiness probes are permitted on that listener; Prometheus and worker details retain current JWT authentication. The application listener does not serve Actuator.
- HTTP server and Hikari use their standard Micrometer metrics. Server histogram buckets are configured according to the proposal.
- Outbox PENDING/CLAIMED/DEAD and oldest-PENDING-age gauges query the real C06-B table. A DB read failure returns `NaN`, never a fabricated zero.
- `OutboxWorkerObserver` and `OutboxWorkerMonitor` provide typed callback, bounded counters and separate health semantics. With no C07-B worker, the real status is `OUT_OF_SERVICE/DISABLED`; no heartbeat or event transition is invented.
- Integration evidence inserts a real PENDING row into MySQL 8.4 and verifies its value in authenticated Prometheus exposition.

## Commands and results

Focused unit/integration regression:

```text
.\gradlew test --tests "*CorrelationIdFilterTest" --tests "*OutboxMetricsBinderTest" \
  --tests "*OutboxWorkerMonitorTest" --tests "*ManagementObservabilityIntegrationTest" --no-daemon
```

Result: PASS.

Full backend regression:

```text
.\gradlew clean build --no-daemon --stacktrace
```

Result: `BUILD SUCCESSFUL` in 3m 48s; **276 tests, 0 failures, 0 errors, 0 skipped**. This includes WebMvc/security slices, architecture tests and MySQL Testcontainers migration/transaction/integration tests.

## Explicitly blocked integration — not claimed

- C09-A: generic 500 and auth error bodies still generate their own `traceId`; making the body equal the effective correlation ID and hardening business/error logs requires A's owned `GlobalExceptionHandler`, `SecurityConfig` and Payment log points.
- C06-A: no publisher exists on this base, so `PaymentCompletedV1.correlationId` is not yet populated through `CorrelationIdProvider`; Payment conflict/retry/replay counters also need an A-owned typed telemetry hook.
- C07-B: no worker exists, so observer callbacks, event correlation restoration and live worker-stop/recovery evidence cannot be integrated. Disabled worker health is reported honestly.
- C10-B: reconciliation findings metric waits for the real reconciliation result seam.
- The custom `digitalwallet.payment.*` metric catalogue is specified but not registered with synthetic zeroes and not inferred from HTTP status.

This evidence proves only the independent C09-B infrastructure. It is not a claim that the full C09 acceptance gate or proposal is ACCEPTED.
