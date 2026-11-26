# C06-A — Payment outbox verification

Ngày: 26/11/2026. Base main `250d688`, Java 21, Gradle 9.8.1, Testcontainers MySQL 8.4, V1–V3 unchanged. Không chạy trên DB người dùng. SHA/run URLs và kết quả CI cuối ghi trong PR body.

## Lệnh

```powershell
cd D:\SAUETDigitalWallet\digitalwallet-system
.\gradlew.bat test --tests '*PaymentOutboxIntegrationTest' --tests '*TopupEndpointTest' --tests '*WithdrawEndpointTest' --tests '*TransferEndpointTest' --tests '*TransactionServiceInvariantTest' --no-daemon
.\gradlew.bat build --no-daemon
cd D:\SAUETDigitalWallet
& 'C:/Users/Admin/miniconda3/python.exe' -m unittest discover -s benchmark/tests -p 'test_*.py'
```

## Evidence map

- `PaymentOutboxIntegrationTest.committedPayloadMatchesHistoryVerifiedRecipientsAndCorrelationAndReplayIsReadOnly` × 3 operations: HTTP request ở JVM phụ có `-Duser.timezone=Asia/Bangkok` tường minh, DTO consumer decode từ MySQL JSON, exact business timestamp giữa event/receipt/GET history, verified recipients/reference, normalized HTTP correlation, payload không chứa description/secret. Replay từ app còn lại không đổi payload hoặc thêm event. Chạy trên CI UTC vẫn kiểm tra được producer UTC+7.
- `everyFailureRollsBackMoneyHistoryKeyAndOutbox` × 3 operations × 4 fault points: before append, after actual SQL INSERT, after receipt complete, TransactionSynchronization.beforeCommit. Assert uncommitted SQL effects trước khi ném lỗi rồi đối soát committed DB từ thread khác. Sau bỏ fault, cùng key thành công đúng một effect.
- `realOutboxConstraintFailureRollsBackTheEntirePayment`: gọi INSERT thật hai lần trong cùng transaction, MySQL unique violation làm rollback cả event đầu, tiền, history và claim. Không retry implicit đối với integrity failure.
- `retryAfterInsertedEventRollsBackOldEventAndCommitsExactlyOneNewEvent`: optimistic fault sau actual INSERT lần đầu; attempt mới có UUID khác, DB chỉ giữ UUID cuối, wallet version chỉ tăng 1.
- Shared C04 `oneEffect` kiểm tra outbox=1, nên burst 100 duplicate requests/two JVM và lost response/restart xác minh thêm outbox. Shared C05 `noEffects` kiểm tra outbox=0, bao gồm fault debit/credit/history/beforeCommit, timeout và exhaustion.
- MVC slices dùng no-op append chỉ để kiểm tra HTTP contract; mọi chứng minh atomicity dùng production JPA/JDBC adapters và MySQL thật.
- Giữ nguyên mapping lịch sử trước C06-A. `PaymentHistoryUpgradeIntegrationTest` chạy `LegacyPaymentFixture` trong JVM UTC+7 riêng, với entity mapping cũ không có direct Java-time override. Fixture ghi và đọc history bằng Hibernate thật, lưu receipt v1 và commit balances/history/key nhưng không có outbox (pre-C06). Khẳng định old logical time=10:15, DATETIME literal=03:15; app mới phải giữ GET history=10:15 và replay receipt byte-for-byte. Payment mới sau đó có đúng một event, legacy row/receipt không bị ghi lại và không backfill event. Mapping candidate direct LOCAL_DATE_TIME đã được rút khỏi PR để tránh reinterpret legacy rows.
- Shared support đối soát committed history ↔ outbox sau mọi case C04/C05/C06: count bằng nhau, không thiếu event/orphan, aggregate transactionId và reference trong JSON khớp history. Cả 2 JVM dùng cùng DB, PID/port/pool riêng.

## Kết quả

- Full local `gradlew.bat build --no-daemon` sau sửa compatibility: **299 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESSFUL, JAR được tạo.
- Focused upgrade/outbox: **18 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESSFUL; outbox 17 và legacy upgrade 1. Full regression bao gồm C04 14/14 và C05 23/23 PASS.
- Harness regression: **18/18 PASS**. Không chạy benchmark k6 và không có performance claim.
- Required CI phải chạy lại trên head cuối; kết quả và artifact URLs ghi trong PR body. Chưa tuyên bố A nghiệm thu/merge.
