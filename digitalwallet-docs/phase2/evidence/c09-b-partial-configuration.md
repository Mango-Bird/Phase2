# C09-B partial evidence — JWT configuration hardening

Date: 2026-11-24 (Asia/Saigon)

## Implemented and verified

- Production configuration has no JWT secret fallback.
- Startup probe rejects unresolved, blank and shorter-than-32-byte signing material.
- Explicit test/development signing material starts both issuer and authentication filter.
- Existing JWT issue/filter round-trip remains unchanged.

Focused command:

```text
cd digitalwallet-system
.\gradlew test --tests "*JwtConfigurationStartupTest" --tests "*JwtRoundTripTest"
```

Result: PASS.

Full regression command:

```text
cd digitalwallet-system
.\gradlew clean build --no-daemon --stacktrace
```

Result: `BUILD SUCCESSFUL` in 3m 2s; 265 tests, 0 failures, 0 errors, 0 skipped. The suite includes MySQL Testcontainers migration/integration tests and architecture tests.

## Not implemented / not claimed

- Correlation ingress/MDC/structured business logs.
- Custom metrics, Prometheus/dashboard integration or metric-name compatibility.
- Actuator exposure, readiness/liveness groups or management access policy.
- Publisher/worker health and outbox runtime metrics.

These are BLOCKED for the reasons and required A decisions in `C09-B-r1.md`. This evidence is not a claim that C09-B is ready for acceptance.
