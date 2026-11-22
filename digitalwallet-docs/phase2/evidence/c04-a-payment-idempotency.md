# C04-A — durable idempotency evidence

Ngày: 22/11/2026. Base `8f5402c` (PR #9 đã merge). Candidate SHA, PR URL và run URL được ghi trong PR body sau push; không dùng kết quả base hoặc head cũ để nghiệm thu candidate mới.

## 1. Môi trường/lệnh

- Java 21, Gradle Wrapper 9.8.1, Spring Boot 4.0.8; MySQL `mysql:8.4` qua Testcontainers, Flyway V1+V2, Hibernate validate.
- Một app trong JVM test và một app trong **JVM OS process riêng**, HTTP ports/pools/transaction managers riêng, chung database. JVM phụ chỉ load production runtime và một launcher test, không load MVC test doubles.
- Launcher ghi PID/port và giá trị SESSION innodb_lock_wait_timeout thực tế sau bootstrap; không thêm diagnostic HTTP endpoint vào sản phẩm. Test xác nhận hai PID khác nhau, timeout=2 ở cả hai app, và PID đổi sau kill/restart.

```powershell
cd D:\SAUETDigitalWallet\digitalwallet-system
.\gradlew.bat build --no-daemon
.\gradlew.bat test --tests '*PaymentIdempotencyIntegrationTest' --no-daemon

cd D:\SAUETDigitalWallet
& 'C:/Users/Admin/miniconda3/python.exe' -m unittest discover -s benchmark/tests -p 'test_*.py'
```

Local full build đã PASS 184 tests, 0 failures/errors/skipped. Bộ MySQL JVM-process riêng đã PASS 10/10 sau khi chuyển từ context thứ hai sang process thực. Full build cuối với JVM-process config cũng PASS 184/184. Sau khi thêm assertion Unicode end-to-end, focused transfer replay PASS 1/1; production classes không đổi. CI/head cuối được cập nhật trên PR khi hoàn tất. Python benchmark harness PASS 18/18.

## 2. QA01 / QA04 proof

`PaymentIdempotencyIntegrationTest` dùng HTTP thật, MySQL thật và hai app:

- Burst 100 duplicate requests, chia đều hai instance: chỉ một history, một COMPLETED key/result, ví tăng đúng một lần. Các response 200 có body đồng nhất; nếu có in-flight 409 thì phải đúng REQUEST_IN_PROGRESS. Retry cuối vẫn cùng body, không thêm effect.
- Sau success, đổi bank sang UNLINKED và wallet sang FROZEN; replay qua instance khác trả nguyên raw snapshot, không revalidate business state.
- Amount mismatch và null→empty description bị 409 KEY_REUSED; row/balance/history không đổi.
- Cùng key nhưng khác actor/operation, key khác case và key mới cùng payload tạo đúng các effects độc lập. Không dùng referenceCode làm key.
- Transfer replay không debit/credit lần hai; Unicode description và canonical vectors được kiểm tra.
- Fault injector gọi implementation complete thật sau flush tiền/history, rồi ném lỗi: rollback cả COMPLETED row, số dư/version/history. Retry cùng key với payload khác được phép vì lần trước không commit.
- Insufficient balance không lưu failed key; trạng thái/payload phù hợp sau đó có thể thành công.
- Transaction giữ claim: app kia chờ bounded theo session timeout, trả 409/Retry-After=1/retryable=true. Holder rollback không để row PROCESSING bền; retry thành công.
- Client ghi đầy đủ HTTP request rồi đóng socket không đọc response. Test đợi DB xác nhận commit, **kill JVM phụ**, khởi động process mới và retry: response bằng stored DB snapshot, tiền/history/key vẫn đúng một lần.
- Facade NEVER từ chối caller transaction; executor/replay reader là hai proxy transaction độc lập sau rollback.

Đây là duplicate delivery/recovery proof, không tuyên bố exactly-once transport hoặc hoàn thành retry/concurrency invariants của C05.

## 3. HTTP, hash, schema và client

- Sáu accepted cross-parser vectors khớp canonical UTF-8 text và SHA-256; có Unicode/null/empty/max long.
- Strict JSON tests tại ba command endpoints: duplicate/unknown fields, coercion/string/float/exponent/out-of-long, type sai, root/trailing JSON; key missing/invalid/length; không gọi business API khi fail.
- Success shape test đủ 9 TransactionResponse fields, giữ explicit nulls; không leak key/hash.
- Error model thêm code/retryable/traceId; auth entrypoint dùng cùng metadata tối thiểu. C09 còn hardening/redaction toàn diện.
- C02 baseline tests target V1 có chủ đích, giữ strict four-table verifier. C04 tests fresh V1+V2 và verified baseline V1→V2, so sánh business data trước/sau; incomplete COMPLETED bị CHECK từ chối, restart không apply lại.
- Existing endpoint tests giữ real facade/executor/financial services; chỉ storage/flush ở WebMvc slice dùng test doubles. Atomicity/durable claims được kiểm chứng riêng trên MySQL, không dựa test doubles này.
- Node sandbox kiểm tra k6 API: key mới mỗi ý định, result trả key, retry optional argument dùng lại đúng header, cả ba financial methods gửi key. Không auto-retry.

## 4. Kết quả trong quá trình phát triển

Các lỗi harness đã được sửa trước khi bàn giao: fault injection phải unwrap Spring transactional proxy để stub đúng spy target; fixture khóa ví phải dùng FROZEN theo CHECK, không dùng INACTIVE. Không sửa CHECK/schema V1, disable regression hoặc nới invariant để làm test pass.

Response được đọc lại từ MySQL JSON cho cả lần đầu và replay để tránh khác biệt whitespace/key order do JSON storage normalize. Toàn bộ success timestamp/reference/body giữ nguyên khi replay. Không có cache key hoặc durable PROCESSING transaction riêng.

## 5. CI, phạm vi và hạn chế

Required check giữ nguyên **Backend build and test**. CI chạy clean build trong checkout sạch cùng 18 Python tests; XML/HTML/JAR theo workflow hiện có. Candidate SHA/run/artifact/conclusion/failure/skip counts ghi trong PR body sau CI.

Local chạy build để giữ review worktrees cũ nằm trong build; không xóa các worktree đó. Child JVM/probe/log test nằm trong ignored build directory; artifact upload không bao gồm classpath/argument files này.

Chưa chạy k6 load/benchmark mới; không tuyên bố throughput/latency tăng. Raw/evidence C00 giữ nguyên. C05 sở hữu exact arithmetic, retry/lock order, distinct-payment concurrency và unlink race; C06 sở hữu outbox. Không drop receipts/keys khi rollback; chuyển từ C03 cần drain toàn bộ old writers theo contract C04-A-r1.


## 6. Revision C04-A-r2 — runtime OpenAPI

Test HTTP `/v3/api-docs` bổ sung sau candidate b83deef đã phát hiện OpenAPI thiếu entry application/json cho raw String receipt, dù header required/maxLength đúng. Mapping và success @Content được khai báo media type tường minh. Regression trước sửa FAIL, sau sửa PASS cho cả ba commands, trỏ đúng PaymentSuccessResponse. Không thay money/hash/result/schema/transaction.

Local full suite trước metadata fix: 184/184 PASS; focused runtime metadata mới: 1/1 PASS. Full CI head mới phải xác minh 185 tests (gồm test mới), không dùng 184 tests của head cũ làm kết luận candidate mới. Xem contract C04-A-r2 và CI evidence trong PR body.
