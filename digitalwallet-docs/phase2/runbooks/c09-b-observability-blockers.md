# C09-B candidate runbook — configuration, metrics and health

Status: **PROPOSED**. Apply final production routing/alerts only after A reviews the proposal and candidate implementation.

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

## Management and worker configuration

- Management defaults to `127.0.0.1:9091`. Override with `MANAGEMENT_SERVER_ADDRESS`/`MANAGEMENT_SERVER_PORT` only on a private interface protected by network ACL and current JWT authentication.
- Never route `/actuator/**` through the public application ingress.
- `APP_OUTBOX_WORKER_ENABLED=false` is truthful until C07-B supplies a worker. Do not set it true merely to make health green.
- Worker defaults: poll interval `1s`, stale threshold at least `max(10s, 3 * poll interval)`, startup grace `15s`.

| Endpoint | Access | Purpose |
|---|---|---|
| `/actuator/health/liveness` | Management listener, no auth | Process progress only; excludes DB and worker |
| `/actuator/health/readiness` | Management listener, no auth | Application readiness plus DB |
| `/actuator/health/worker` | Management listener, current JWT | Worker state/reason; never an app restart probe |
| `/actuator/prometheus` | Management listener, current JWT | Standard HTTP/Hikari and proposed `digitalwallet.*` metrics |

Only `health` and `prometheus` are exposed. Do not enable env/configprops/beans/mappings/loggers/heapdump/threaddump/shutdown.

## Triage

- Readiness DOWN + worker reason `DB_UNAVAILABLE`: investigate MySQL/connectivity/pool; Payment is expected to be unavailable.
- Readiness UP + worker DOWN (`LOOP_STOPPED`, `POLL_STALE`, `POLL_FAILED`): keep the API serving, alert the worker owner and inspect backlog. Do not restart healthy Payment instances solely for worker health.
- Worker `OUT_OF_SERVICE/DISABLED`: expected before C07-B or when intentionally disabled; alert according to deployment intent.
- `digitalwallet.outbox.events{status="dead"} > 0`: investigate/replay through the future audited C07-B operation. DEAD alone does not make readiness or liveness fail.
- A `NaN` backlog gauge means collection/DB failure, not zero backlog. Cross-check readiness and Hikari pending/timeouts.

## Correlation lookup

Send a single UUID in `X-Correlation-ID`, or let the server create one. The effective UUID is echoed and appears in log fields `correlationId` and `traceId`. Invalid/multiple values are replaced and never reflected.

Full response-body/event correlation is pending C09-A/C06-A integration. Until those owners connect `CorrelationIdProvider`, do not claim that the current error-body `traceId` or outbox event always equals the response header.

## Dashboard panels after A review

- HTTP request rate/error and p95/p99 from `http.server.requests`, grouped only by templated URI/method/status.
- Hikari active/idle/pending/max and acquire/timeout.
- Outbox pending/claimed/dead plus oldest pending age.
- Worker up, last-success age, poll results and committed transitions after C07-B integration.
- Payment duration/conflict/retry/replay after A publishes the typed hook; do not derive these from all HTTP 409/500 responses.
