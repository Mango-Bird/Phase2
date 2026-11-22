# C05-A — money/concurrency evidence

Ngày: 22/11/2026. Base `8f07d01` (PR #10 đã merge). Candidate SHA/run URLs nằm trong PR body để không tự tham chiếu SHA trong commit.

## 1. Môi trường và lệnh

Java 21, Gradle 9.8.1, MySQL mysql:8.4/Testcontainers, schema V1+V2, Hibernate validate. Test data synthetic, không chạy vào DB người dùng. Mỗi suite HTTP có app trong JVM test và process JVM phụ với HTTP port/pool/PID riêng, chung một DB; read probe xác minh session lock wait=2.

```powershell
cd D:\SAUETDigitalWallet\digitalwallet-system
.\gradlew.bat test --tests '*MoneyConcurrencyIntegrationTest' --tests '*MoneyRulesTest' --tests '*PaymentRetryClassifierTest' --tests '*JitterPaymentRetryDelayTest' --no-daemon
.\gradlew.bat build --no-daemon
# Case wire A↔B qua hai JVM bổ sung sau full build:
.\gradlew.bat test --tests '*MoneyConcurrencyIntegrationTest.oppositeTransfersAcrossTwoJvmProcessesBothCommitSafely' --no-daemon

cd D:\SAUETDigitalWallet
& 'C:/Users/Admin/miniconda3/python.exe' -m unittest discover -s benchmark/tests -p 'test_*.py'
```

- Focused money/retry/bank suite: **34/34 PASS** sau khi xác minh biến thể timeout thực tế của Connector/J.
- Full local build: **247 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESSFUL; JAR được tạo.
- Case A↔B HTTP qua hai JVM mới bổ sung: **1/1 PASS**. Production code không đổi sau full build; CI head cuối phải xác minh tổng **248 tests**.
- Benchmark harness: **18/18 PASS**. Không chạy benchmark k6 mới, không tuyên bố throughput/latency tăng.

### Review fix — DELETE unlink timeout contract

- Bổ sung runtime OpenAPI `409` với `application/json` / `ErrorResponse`, mô tả `RESOURCE_BUSY`, `retryable=true` và retry DELETE sau backoff **không dùng Idempotency-Key**; cập nhật bảng endpoint và contract nghiệm thu.
- `unlinkHttpTimeoutWhilePaymentHoldsBankLockCanBeRetriedWithoutKey`: payment giữ bank row lock trong transaction thật; DELETE qua JVM thứ hai timeout trả 409 đúng code/retryable. Khi lock còn giữ, liên kết vẫn ACTIVE, balances/versions/history/key chưa có committed effects. Sau release payment commit đúng một effect; retry chính DELETE không key trả 200/UNLINKED, không thêm payment effect.
- `runtimeOpenApiDeclaresUnlinkResourceBusyAndNoIdempotencyKey`: đọc `/v3/api-docs` thật, kiểm tra JSON ErrorResponse, mô tả retry và không có key parameter.
- Lệnh `./gradlew.bat test --tests '*MoneyConcurrencyIntegrationTest' --no-daemon`: **23 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESSFUL. CI head sửa phải xác minh toàn bộ **250 tests**; SHA/run URLs và kết quả cuối được ghi trong PR body.

## 2. QA02 — money/range/atomicity

- MoneyRules tests xác minh null/0/âm bị chặn, 0→MAX và MAX→0 đúng, debit thiếu tiền không âm, credit overflow không wrap. Direct use-case tests kiểm tra độc lập với HTTP/facade, stale resolved wallet ID và overflow trước bất kỳ debit.
- Hai khoản 80/70 từ ví 100 có barrier tại hai first snapshots: một success, một INSUFFICIENT_BALANCE sau retry. Chỉ một history/key, tổng đúng, không âm.
- Hai khoản 60/40 từ 100: hai success, source=0, targets/versions/history đúng.
- A↔B có synchronized first snapshots trong một app và test HTTP riêng qua hai JVM: cả hai commit, số dư/tổng cuối đúng, versions tăng theo success effects.
- Receiver có ID thấp hơn source: spy gọi save thật và kiểm tra SQL-visible balance sau flush; IDs thực tế theo [11,22], kể cả source=22.
- Hot receiver nhận từ 8 senders qua hai JVM, client barrier: mọi response 200 hoặc 409 kỹ thuật đúng code; count chỉ các success, đối chiếu từng sender, receiver delta/version, history/keys và tổng BigInteger. Không coi 409 là payment success.
- Topup/transfer overflow trả MONEY_OVERFLOW trước write, không lưu failed key; full-range topup/withdraw đúng Long.MAX_VALUE.
- Faults AFTER_DEBIT/AFTER_CREDIT gọi saveAndFlush thật, AFTER_HISTORY gọi save thật, BEFORE_COMMIT đăng ký TransactionSynchronization.beforeCommit sau COMPLETED. Trong transaction đã nhìn thấy SQL effects, sau exception DB balances/versions/history/key đều về trước. Không retry fault business/unknown giả định.

## 3. QA03 — fresh retries và bank serialization

- Failed debit SQL rồi optimistic exception: pause xác minh không có actual transaction và failed claim/money đã rollback. Một payment khác commit trong pause; attempt sau đọc balance mới và dừng ở INSUFFICIENT_BALANCE. Ghi nhận EntityManagers khác nhau, đúng READ-COMMITTED trong actual write transactions.
- Budget exhaustion: 3 attempts, 3 EntityManagers khác nhau, chỉ pause trước attempts 2/3; HTTP 409 CONCURRENT_UPDATE/retryable=true và không leftover writes.
- Deadlock thật: external test transaction khóa receiver trước và sửa 30 dummy user rows để InnoDB chọn payment nhẹ hơn làm victim. Sau debit source, external cố khóa source trong khi payment cần receiver, gây 1213/40001 thật. Test capture error code thực, xác minh rollback trước pause rồi retry transaction mới commit đúng một effect. External fixture rollback, tổng tiền giữ nguyên.
- Unit classification riêng kiểm tra optimistic, nested JDBC deadlock, duplicate/connectivity/unknown/cyclic causes; client-retryable business errors không thành server-retryable.
- Jitter/sleeper tests kiểm tra inclusive 25–50/50–100 ms và interrupt flag, không sleep thật trong unit test.
- Bank races dùng latches sau scoped row lock và trước request thứ hai; second request không hoàn tất khi lock đầu còn giữ. Payment-first hoàn tất tiền rồi unlink; unlink-first khiến payment mới thấy UNLINKED và thất bại, không money/key/history.
- Giữ bank lock thật quá session wait: HTTP 409 RESOURCE_BUSY/retryable=true, không pause/retry, không effects. API outside caller transaction bị MANDATORY từ chối.

## 4. Regression và thay đổi test harness

- C04 HTTP/duplicate/hash/receipt/body cap/INVALID_AMOUNT/kill-restart/migration cases tiếp tục chạy trong full suite. Infrastructure HTTP/DB được tách thành abstract support không chứa @Test để dùng lại ở C04/C05, không nhân bản test hoặc bỏ coverage.
- Open-in-view=false, write RC và owner flush theo ID là implementation mới; readonly/default datasource isolation không bị đổi toàn cục. Bank chỉ lock scoped row, không đổi Wallet sang pessimistic.
- Read-only BankAccountQueryApi chuyển tiếp được gỡ. Architecture rules/caller restriction dùng BankEligibilityApi và có negative fixture Wallet gọi sai API dù fixture graph cho phép dependency.
- Lỗi harness đã sửa: Link/unlink timestamp assertions đổi sang UTC đúng contract writes mới; persisted bank fixture điền wallet_id như schema NOT NULL. Không nới invariant hoặc bỏ test.
- Vòng đầu bank timeout lộ Connector/J báo **1205 với SQLState 40001**. Đã sửa classifier dựa trên vendor code, thêm unit guard: 1213/40001 mới là deadlock retry, 1205 vẫn timeout/no implicit retry. Không broad retry theo SQLState đơn lẻ.

## 5. CI và giới hạn

Required check giữ nguyên Backend build and test. CI clean build trên checkout sạch, XML/HTML/JAR và 18 Python tests theo workflow hiện có; candidate/head/run/artifacts/counts cập nhật trong PR body khi hoàn tất. Local không clean build vì build chứa review worktrees cũ; không xóa các worktree đó.

Không sửa V1/V2, ruleset/CI, raw C00 hoặc ngưỡng benchmark. C05 chứng minh bounded correctness, không hứa mọi distinct payment đều 200 dưới hot contention. Không external bank provider/network trong money transaction; bank vẫn mô phỏng. Outbox/worker/reconciliation/pagination/benchmark cuối thuộc các task tiếp theo.
