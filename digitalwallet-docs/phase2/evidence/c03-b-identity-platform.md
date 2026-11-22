# C03-B local verification evidence

Date: 2026-11-22 (Asia/Saigon), updated after A review on head `920aa75`

## Environment and command

- JDK toolchain: Java 21
- Database: Testcontainers `mysql:8.4`
- Command: `cd digitalwallet-system && .\\gradlew clean build --no-daemon --stacktrace`
- Result: `BUILD SUCCESSFUL`
- JUnit result: **117 tests, 0 failures, 0 errors, 0 skipped**

The full suite includes the pre-existing endpoint and C02 migration tests plus the C03-B identity, HTTP/JWT, transaction, architecture, and negative architecture-fixture tests.

## Required risk evidence

- Actual Spring proxies and MySQL are used for the Onboarding transaction tests.
- A deterministic wallet-number collision makes the wallet write fail after user creation; the transaction leaves exactly the pre-existing user and wallet and no orphan user.
- Direct invocation of the Identity registration API outside a transaction throws `IllegalTransactionStateException` and writes nothing.
- JWT issuance and authentication filter round-trip preserves the numeric principal and `ROLE_USER` authority.
- Production architecture verification excludes test bytecode. Production and negative fixtures now execute the same parameterized `verifyProduction` engine; the separate `verifyFixture` path was removed.
- Deliberately invalid compiled fixtures prove rejection of all reviewed gaps: a public Identity API exposing its own domain/password-hash type, a legacy application service importing Identity persistence, and Payment calling `WalletCreationApi`. Existing fixtures continue proving cross-module internal-import and cycle rejection.
- `WalletViewContractTest` asserts all eight accepted C01 fields. The legacy bridge maps `version`, `createdAt`, and `updatedAt`; MySQL integration verifies version zero and non-null timestamps, with legacy `DATETIME` interpreted as UTC and exposed as `Instant`.

GitHub candidate SHA, required `Backend build and test` run URL, and conclusion are pending push/PR and will be recorded in the PR body. This document does not claim A acceptance.

