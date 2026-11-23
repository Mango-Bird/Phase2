# C06-B verification evidence

Date: 2026-11-23 (Asia/Saigon)

Review update: P2/P3 fixes after A review on candidate `0d18ca6`; contract revision `C06-B-r2`.

## Environment

- Base SHA: `f3cce42eaf7b924880bda72d11feb87a30e1ebf6`
- Java toolchain: 21
- Database: Testcontainers `mysql:8.4`, UTC session
- Flyway: 11.15.0; `baselineOnMigrate=false`; out-of-order disabled

## Results

- Focused migration, storage, event and architecture checks: PASS.
- Full CI-equivalent: `cd digitalwallet-system && .\\gradlew clean build --no-daemon --stacktrace` — `BUILD SUCCESSFUL`.
- JUnit XML aggregate after review fixes: **262 tests, 0 failures, 0 errors, 0 skipped**.
- No benchmark was run; C06-B does not modify workload or Payment execution.

Verified behavior:

- Fresh V1→V3 migration and populated V2→V3 upgrade.
- Phase 1 business snapshot remains byte-for-byte logically equal across V3; migration restart executes zero changes.
- Required PK, unique key, dispatch/lease indexes and seven enforced CHECK constraints exist in MySQL metadata.
- Append participates in the caller transaction; outside-transaction call is rejected; injected post-append fault rolls back the row.
- Duplicate aggregate/event type is rejected by MySQL and leaves one committed event.
- Producer/consumer JSON round-trip preserves v1 payload and envelope metadata.
- Architecture guards prohibit non-Payment callers of `OutboxAppendPort`; storage imports no Payment internal, HTTP or provider client.
- Nested `ObjectNode`/`ArrayNode` mutation through both constructor input and accessor output cannot alter `EventEnvelope` internal payload; exact serialization/deserialization and JDBC persistence remain compatible.
- `PaymentCompletedV1` rejects equal `eventId`/`referenceCode`, accepts distinct IDs, and preserves existing null/type/version/participant validation.

Candidate SHA, PR URL, required CI run and artifact retention are recorded in the PR body after push. This document does not claim A acceptance.

