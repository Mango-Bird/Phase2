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
| CHECK enforcement | A named/clause-matching CHECK with `ENFORCED=NO` is rejected before baseline history is written. |
| Column write contract | All four PKs require `AUTO_INCREMENT`; defaults and `ON UPDATE` expressions are compared before baseline. |
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

Result: PASS, 7 tests, MySQL 8.4 through Testcontainers.

The legacy path uses `db/legacy/phase1-schema.sql`, an independent test fixture
derived from the Phase 1 dump rather than loading the V1 migration under test.
The valid fixture is baselined through the same `Phase1BaselineTool` entry point
used by the CLI. Regression cases apply these real MySQL 8.4 drifts:

```sql
ALTER TABLE wallets
ALTER CHECK chk_wallets_balance NOT ENFORCED;

ALTER TABLE transactions
MODIFY COLUMN id BIGINT NOT NULL;
```

Both are rejected before Flyway creates a history row. A third regression changes
`users.role`'s exact default and removes `wallets.updated_at`'s `ON UPDATE`
expression. For all
three failures, the integration harness asserts zero history rows and an
unchanged business-data snapshot. The valid schema still records exactly one V1
BASELINE row and a following migrate executes zero migrations.

Full local equivalent of the CI backend command:

```text
gradlew.bat clean build --no-daemon --stacktrace
```

Result: PASS, 118 tests, 0 failures, 0 errors, 0 skipped. The application
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
