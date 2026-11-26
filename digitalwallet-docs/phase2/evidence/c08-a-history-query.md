# C08-A — query verification

27/11/2026. Base `598b9be`. Java 21, Gradle 9.8.1, MySQL 8.4 Testcontainers. Synthetic history fixtures, không chạy trên DB người dùng. Test seed có thể chứa history pre-C06 không outbox để kiểm tra read, không phải evidence đối soát tiền.

## Verification commands

```powershell
cd D:\SAUETDigitalWallet\digitalwallet-system
.\gradlew.bat test --tests '*PaymentHistoryQueryIntegrationTest' --tests '*HistoryQueryPlanIntegrationTest' --no-daemon
.\gradlew.bat build --no-daemon
cd D:\SAUETDigitalWallet
& 'C:/Users/Admin/miniconda3/python.exe' -m unittest discover -s benchmark/tests -p 'test_*.py'
```

## Correctness evidence

PaymentHistoryQueryIntegrationTest uses real HTTP/JPA/JDBC/MySQL with independent app JVMs. 53 owned rows share one timestamp; one foreign row is excluded. Traversal alternates instances and limit 7/11 and compares exactly IDs 53..1. It checks default 20/max100, v1 full list shape, detail ownership, auth, type/date boundary on UTC+7, foreign/filter cursor rejection, malformed query/cursor corpus and live insert behavior. OpenAPI test checks v1 deprecated plus v2 200/400/401/404.

Cursor is a position, not an authorization credential. Wallet scope is resolved afresh from actor and remains in every branch and detail query. JDBC IDs and entity hydration share one read transaction. The unchanged legacy timestamp mapping is covered by the C06 upgrade regression in full build.

## 100k read experiment

HistoryQueryPlanIntegrationTest inserts 100.000 rows (10.000 source wallet 11, 100.000 total involvement for wallet 22), with timestamp ties of 100 rows, and ANALYZE TABLE. It compares bounded OR vs two-branch UNION on sparse first page, dense first page, sparse deep cursor. Each candidate has 3 warmups + 7 timed samples; IDs must match and returned count=21. Measurements cover candidate-key SQL only (exclude HTTP and entity hydration), with local wall times and EXPLAIN ANALYZE; not production SLA/C11 money benchmark. JSON plans/samples are written to build/c08-history-query-plans.json and JUnit stdout for GitHub test evidence artifacts.

Measured local MySQL 8.4.13, 100.000 rows, seed insert 5356,24 ms (includes existing indexes; not Payment command latency):

| Scenario | OR median ms | UNION median ms |
|---|---:|---:|
| sparse first | 42.249 | 8.731 |
| dense first | 51.753 | 37.198 |
| sparse deep | 34.580 | 5.607 |

Selected UNION for this candidate. Full raw samples/plans: [local 100k evidence](c08-local-100k-plans.json). Existing source/target indexes are used, but EXPLAIN still shows filesort and substantial branch scans, especially dense wallets. Application hydration is bounded; DB scanned rows are not guaranteed O(limit). No new index is added, so incremental index storage/write cost is 0. V5 is retired before any SQL/applied migration; C10-B V6 follows V4. A future index uses a new version above applied/reserved versions with fresh evidence. Do not infer production p95 or money throughput from these local warm read medians.

## Status

### Review fix — DST boundaries

`PaymentHistoryDstIntegrationTest` launches the independent production HTTP app on JVM `America/New_York`; parent JVM timezone is unchanged. Legacy-shaped SQL fixtures store `2026-03-08 07:05` and fall `2026-11-01 05:05/06:05`, which that server displays as 03:05 and twice 01:05. Six parameterized invalid-boundary cases cover from/to in spring gaps and fall overlaps. Two positive cases check valid bounds crossing spring/fall transitions, including both occurrences during the overlap. Scope is boundary validation before Timestamp binding; no history data/mapping rewrite.

Focused New York regression: **8/8 PASS**, zero failures/errors/skips. Full local rerun: **326/326 PASS**, zero failures/errors/skips, BUILD SUCCESSFUL. Required CI must run on the final DST-fix head; previous 318-test CI is superseded.

- Full local backend build after DST fix: **326 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESSFUL; JAR built.
- C08 HTTP **18/18 PASS**, New York DST **8/8 PASS**, 100k OR/UNION experiment **1/1 PASS**. Runtime OpenAPI assertion includes the 400 gap/overlap policy.
- Harness **18/18 PASS**. No C11/k6 benchmark was run.
- Candidate SHA, required final-head CI and artifact verification are recorded in PR body; A acceptance remains pending.
