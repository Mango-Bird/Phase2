# C02-B Database Migration Runbook

## Ownership and version policy

- C02-B owns `V1__phase1_baseline.sql` only.
- V2 is reserved for C04-A, V3 for C06-B, V4 for C07-B, V5 for C08-A, and V6 for C10-B.
- Do not create placeholder migrations for V2-V6 and do not edit an applied migration.
- Flyway is the only schema owner. Hibernate is fixed to `validate`.
- `baselineOnMigrate=false` is mandatory in every environment.

## Empty database

Start the application with an empty MySQL 8.4 database. Flyway applies V1 before
Hibernate validates the four Phase 1 tables. The old Phase 1 dump is not mounted
by Docker Compose and is not run by CI.

## Existing Phase 1 database

Do not point a Phase 2 application at a populated Phase 1 database until all of
these steps are complete:

1. Stop writers and create a restorable, encrypted backup. Record its location,
   checksum, restore owner, and a successful restore check outside Git.
2. Record `SELECT @@global.time_zone, @@session.time_zone, @@system_time_zone;`
   and confirm the timezone used by the Phase 1 application with its owner.
3. Export the required variables without placing credentials in a command line,
   shell history, log, or repository:

   ```powershell
   $env:SPRING_DATASOURCE_URL = 'jdbc:mysql://host:3306/database?serverTimezone=UTC'
   $env:SPRING_DATASOURCE_USERNAME = '<from secret store>'
   $env:SPRING_DATASOURCE_PASSWORD = '<from secret store>'
   $env:C02_B_LEGACY_TIMEZONE = 'UTC'
   $env:C02_B_BACKUP_CONFIRMED = 'true'
   .\gradlew.bat baselinePhase1 --no-daemon
   ```

   On Linux/macOS use the same environment names with `./gradlew`.

The command fails before writing when a table, column, required index, or named
constraint differs from the Phase 1 contract. Column equivalence includes all
four primary-key `AUTO_INCREMENT` attributes plus write-affecting defaults and
`ON UPDATE` expressions. Every required CHECK must match its named clause and
report `ENFORCED=YES`; a present but `NOT ENFORCED` constraint is rejected. The
tool never repairs drift. It also fails if schema history already exists or
backup/timezone attestations are absent. On success, its only database write is
Flyway's V1 baseline record. It compares table counts, maximum IDs, total wallet
balance, and total transaction amount before and after.

After the baseline succeeds, start the application normally. Do not enable
automatic baselining as a shortcut.

## Timezone policy

The repository Phase 1 dump explicitly sets its import/export session to `+00:00`;
the C02-B legacy fixture is therefore documented as UTC. MySQL `DATETIME` does not
store a timezone, so C02-B does not rewrite legacy timestamps and does not append
`Z` to them. A real environment must independently record and confirm its legacy
timezone before baseline. If it is unknown or conflicts with the application
owner's record, stop the rollout and resolve that evidence first.

V1 preserves the legacy `DATETIME(3)` precision. New Phase 2 timestamp columns
owned by later migrations use `DATETIME(6)` and UTC according to the architecture
contract.

## Verification and failure handling

Run the migration harness with Docker available:

```powershell
.\gradlew.bat test --tests '*DatabaseMigrationIntegrationTest' --no-daemon
```

It covers an empty database, populated Phase 1 data, process restart, an
incompatible non-empty schema, a required CHECK changed to `NOT ENFORCED`, a
primary key with `AUTO_INCREMENT` removed, and default/`ON UPDATE` drift. Legacy
tests load an independent Phase 1 test fixture rather than the production V1
migration. Drift cases invoke the same verification/baseline entry point as the
CLI and assert no Flyway history row and no business-data change. Test-only
databases are isolated and removed by the harness. No production reset or
Flyway `clean` is enabled.

There are no down migrations. If a deployment must be reversed after a failed
schema change, stop writers and restore the verified backup. Never use `DROP`,
`TRUNCATE`, or manual edits to the Flyway history table as rollback.
