# C02-B Database Migration Evidence

Date: 2026-11-21 (Asia/Saigon)

Base commit: `2797255bdf03558fc010f446b7141e7e3ec594f8`

Branch: `feat/c02-b-database-migration`

## Contract mapping

| Requirement | Evidence |
| --- | --- |
| Empty database | V1 creates exactly the four Phase 1 tables; harness migration count is 1. |
| Phase 1 data | Fixture preserves explicit IDs, wallet balance, version, transaction history, and millisecond timestamps across explicit V1 baseline. |
| Restart | A second Flyway process executes zero migrations; one successful V1 history row remains. |
| Wrong schema | Metadata verifier reports the drift and Flyway refuses a non-empty schema because automatic baseline is false. |
| Schema ownership | Hibernate uses `validate`; Docker and CI no longer run the destructive Phase 1 dump. |
| Version ownership | V1 exists; V2-V6 remain uncreated and reserved for their contract owners. |
| MySQL compatibility | Flyway is pinned to 11.15.0, the first 11.x release verified through MySQL 9.4, avoiding the MySQL 8.4 warning emitted by Boot's former 11.14.1 default. |

## Data invariants exercised by the legacy fixture

Before and after explicit baseline:

- user ID: `101`
- wallet ID: `201`
- linked bank ID: `301`
- transaction ID: `401`
- wallet balance: `987654321`
- wallet optimistic-lock version: `7`
- transaction amount/history: `123456789`, reference
  `00000000-0000-0000-0000-000000000401`
- timestamp precision: `DATETIME(3)` values retained

The baseline tool additionally compares counts, maximum IDs, total balance, and
total transaction amount for a real Phase 1 database. This repository evidence
uses synthetic test-only data; it does not claim that a production database was
baselined.

An operational dry-run of `baselinePhase1` against an isolated MySQL 8.4 Phase 1
fixture also passed. It recorded V1 and printed identical before/after snapshots
(`users=1`, `wallets=1`, `walletBalanceSum=987654321`, maximum user ID `101`,
maximum wallet ID `201`). The test container was removed afterward.

## Test execution

Command:

```text
gradlew.bat test --tests *DatabaseMigrationIntegrationTest --no-daemon
```

Result: PASS, 4 tests, MySQL 8.4 through Testcontainers.

Full local equivalent of the CI backend command:

```text
gradlew.bat clean build --no-daemon --stacktrace
```

Result: PASS, 115 tests, 0 failures, 0 errors, 0 skipped. The application
context used a separate empty MySQL 8.4 test database, so this run also exercised
Flyway V1 followed by Hibernate validation. The temporary database container was
removed after the build.

The GitHub Actions result is recorded in the PR after the branch is pushed.
Failed-test XML and HTML reports remain uploaded by the existing
`Backend build and test` CI job.

## Timezone evidence

`digitalwallet_p1.sql` sets `TIME_ZONE='+00:00'` for its dump session, so the
repository legacy fixture is treated as UTC. Because `DATETIME` is timezone-free,
no legacy timestamp conversion occurs. The runbook requires recording the real
database and Phase 1 application timezone before any environment is baselined.

## Compatibility references

- Redgate Flyway MySQL support: <https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql>
- Flyway issue confirming MySQL support through 9.4 from 11.15.0:
  <https://github.com/flyway/flyway/issues/4168>
