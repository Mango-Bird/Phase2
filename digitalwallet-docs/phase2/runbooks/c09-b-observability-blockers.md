# C09-B runbook — available configuration and pending observability contract

## JWT deployment

Set a unique `APP_JWT_SECRET` containing at least 32 random UTF-8 bytes before starting the application. Do not place it in Git, command output, dashboard variables or logs. Production startup intentionally fails when the variable is missing, blank or too short.

The `dev` profile has an explicit development-only fallback. Never enable that profile in production. The Gradle `test` task injects a separate test-process-only value; CI's value is also test-only and is not a deployment credential.

Local validation examples:

```powershell
$env:APP_JWT_SECRET = ''
./gradlew bootRun                  # expected startup failure

$env:APP_JWT_SECRET = '<unique secret from an approved secret store>'
./gradlew bootRun                  # uses deployment-provided material
```

Do not paste a real secret into issue/PR comments or committed `.env` files.

## Observability integration status

No custom metric names, correlation header or management endpoint has been invented on this branch. The following dashboard sections remain blocked until A publishes their exact contract:

| Intended panel | Required contract before implementation |
|---|---|
| HTTP/payment duration and status | metric name, unit, buckets/SLO, route/status label normalization |
| Conflict/deadlock/retry/replay | separate counter names and bounded reason/operation labels |
| Outbox pending/oldest/DEAD | metric names, seconds vs milliseconds, query cadence and error behavior |
| Worker health | real C07-B heartbeat/state seam and health-to-readiness mapping |
| DB pool | approved standard Hikari meter names and dashboard labels |
| Reconciliation findings | C10-B seam and approved metric name/labels |

## Triage policy after unblock

- Correlation lookup instructions require the approved header/MDC/response policy; currently generated error `traceId` values are local response metadata and are not yet an end-to-end correlation guarantee.
- DB outage should make the approved DB readiness component fail.
- Worker unhealthy with a healthy DB must remain a separate degraded signal and must not make Payment unavailable. This cannot be proven until C07-B publishes a real worker-health seam.
- Oldest pending and DEAD observations must be derived from real `outbox_events` rows, never synthetic gauges. C06-B supplies the table; C07-B/C09-B will define safe polling/instrumentation after the contract is merged.
