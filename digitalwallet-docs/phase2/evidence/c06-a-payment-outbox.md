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

- `PaymentOutboxIntegrationTest.committedPayloadMatchesHistoryVerifiedRecipientsAndCorrelationAndReplayIsReadOnly` × 3 operations: HTTP request ở JVM phụ có `-Duser.timezone=Asia/Bangkok` tường minh, DTO consumer decode từ MySQL JSON, exact persisted history timestamp/recipients/reference, normalized HTTP correlation, payload không chứa description/secret. Replay từ app còn lại không đổi payload hoặc thêm event. Chạy trên CI UTC vẫn kiểm tra được producer UTC+7.
- `everyFailureRollsBackMoneyHistoryKeyAndOutbox` × 3 operations × 4 fault points: before append, after actual SQL INSERT, after receipt complete, TransactionSynchronization.beforeCommit. Assert uncommitted SQL effects trước khi ném lỗi rồi đối soát committed DB từ thread khác. Sau bỏ fault, cùng key thành công đúng một effect.
- `realOutboxConstraintFailureRollsBackTheEntirePayment`: gọi INSERT thật hai lần trong cùng transaction, MySQL unique violation làm rollback cả event đầu, tiền, history và claim. Không retry implicit đối với integrity failure.
- `retryAfterInsertedEventRollsBackOldEventAndCommitsExactlyOneNewEvent`: optimistic fault sau actual INSERT lần đầu; attempt mới có UUID khác, DB chỉ giữ UUID cuối, wallet version chỉ tăng 1.
- Shared C04 `oneEffect` kiểm tra outbox=1, nên burst 100 duplicate requests/two JVM và lost response/restart xác minh thêm outbox. Shared C05 `noEffects` kiểm tra outbox=0, bao gồm fault debit/credit/history/beforeCommit, timeout và exhaustion.
- MVC slices dùng no-op append chỉ để kiểm tra HTTP contract; mọi chứng minh atomicity dùng production JPA/JDBC adapters và MySQL thật.
- Test timestamp ban đầu tái hiện MySQL DATETIME literal lệch -7 giờ so với event/business instant trên JVM UTC+7. Sửa riêng history field sang Hibernate direct LOCAL_DATE_TIME JDBC binding; truncate business time theo DATETIME(3) sẵn có. Không sửa V1–V3 hoặc dữ liệu lịch sử; không nới assertion timestamp. Đối chiếu SQL DATE_FORMAT literal tránh thêm timezone conversion của JDBC Timestamp trong test.
- Shared support đối soát committed history ↔ outbox sau mọi case C04/C05/C06: count bằng nhau, không thiếu event/orphan, aggregate transactionId và reference trong JSON khớp history. Cả 2 JVM dùng cùng DB, PID/port/pool riêng.

## Kết quả

- Full local `gradlew.bat build --no-daemon`: **298 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESSFUL, JAR được tạo.
- C06-A outbox: **17/17 PASS**; C04 idempotency/two-JVM/restart: **14/14 PASS**; C05 money/concurrency: **23/23 PASS**.
- Harness regression: **18/18 PASS**. Không chạy benchmark k6 và không có performance claim.
- Required CI phải chạy lại trên head cuối; kết quả và artifact URLs ghi trong PR body. Chưa tuyên bố A nghiệm thu/merge.
