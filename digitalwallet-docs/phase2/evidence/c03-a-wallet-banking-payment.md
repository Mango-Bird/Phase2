# C03-A — Evidence native Wallet/Banking/Payment

Ngày chạy: 22/11/2026. Base `df4304d` (PR #8 đã merge). Candidate head và GitHub run URL được ghi trong PR body; không coi SHA base là SHA đã kiểm thử của candidate.

## 1. Môi trường và lệnh

- Java 21, Gradle Wrapper 9.8.1, MySQL `mysql:8.4` qua Testcontainers, Flyway V1, Hibernate `validate`.
- Windows local; Docker Desktop. Test tạo database/container riêng, dữ liệu tổng hợp; không chạy trên database người dùng.
- Schema V1, config CI và ruleset main không thay đổi trong C03-A.

```powershell
cd D:\SAUETDigitalWallet\digitalwallet-system
.\gradlew.bat build --no-daemon
# Kiểm tra lại fault proof sau khi thêm flush SQL và assert nguyên nhân lỗi:
.\gradlew.bat test --tests '*PaymentTransactionIntegrationTest' --no-daemon

cd D:\SAUETDigitalWallet
& 'C:/Users/Admin/miniconda3/python.exe' -m unittest discover -s benchmark/tests -p 'test_*.py'
```

Full build local: **128 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESSFUL; boot JAR và plain JAR được tạo. Benchmark harness: **18/18 PASS**. Focused fault-proof rerun: **6/6 PASS**, 0 failures/errors/skipped, sau khi bổ sung flush SQL và kiểm tra đúng nguyên nhân lỗi history. Required GitHub CI được xác nhận trong PR body trước bàn giao.

## 2. Bằng chứng chức năng và transaction

`PaymentTransactionIntegrationTest` dùng service/persistence thật, một datasource và transaction manager, không mock lỗi DB:

1. Transfer 250: số dư 1000/500 thành 750/750; tổng 1500, một history; version cả hai tăng từ 0 lên 1.
2. History description 256 ký tự bị MySQL từ chối. Hook test chỉ flush EntityManager và gọi tiếp implementation history thật; xác nhận trong transaction cả hai UPDATE đã xuống DB ở 750/750. Sau exception: DB trả lại 1000/500, version 0/0, không history. Assert stack trace đúng lỗi cột description để không pass vì lỗi xảy ra trước posting.
3. Link thuộc ví khác và link UNLINKED bị từ chối; không ghi tiền/history.
4. Native creation, load-for-posting và apply-change ngoài transaction bị MANDATORY từ chối.
5. Snapshot cũ không thể ghi đè balance/version đã commit.
6. Wallet query không trả ví có ID thuộc actor khác.

`OnboardingTransactionIntegrationTest` tiếp tục chứng minh register commit user+wallet cùng nhau và lỗi wallet-number collision rollback user mới sau khi bridge được thay native implementation.

Endpoint suite giữ các case validation, missing/invalid JWT, wallet/bank ownership, inactive/insufficient, success và persistence failure. `WalletHttpContractTest` bổ sung kiểm tra fields/timestamp/ẩn version, 404 và 401. Không xóa hoặc skip test để làm build xanh.

## 3. Bằng chứng kiến trúc

- Production graph được import đầy đủ bằng ArchUnit; hai exception maps rỗng.
- Không còn root business packages hoặc `wallet.adapter.legacy` trong production source.
- Chỉ public API được gọi xuyên module; domain không Spring/JPA, API không expose internal/JPA types, không cycle.
- Caller restrictions giữ creation cho Onboarding, posting và bank-account query cho Payment.
- Negative fixtures cùng rule engine kiểm tra internal imports, cycle, own-domain API leak, unauthorized creation/posting caller, và root legacy business class không có dependency.

## 4. Evidence CI và giới hạn

Required check vẫn là `Backend build and test`. CI chạy `clean build` trong checkout sạch, toàn bộ Java suite và 18 Python tests; XML/HTML/JAR được upload theo workflow hiện có. Run URL, đúng head SHA, conclusion và artifact names nằm trong PR body sau khi CI kết thúc. A tự quyết định merge.

Local chạy `build` thay vì `clean build` vì thư mục build hiện chứa các review worktree cũ; không xóa chúng chỉ để dọn output. Gradle quản lý compile outputs của source đã di chuyển; architecture gate quét compiled production classes. CI checkout sạch là kiểm chứng độc lập cuối.

Hai lỗi cấu hình test đã được sửa trước lần full build PASS: fixture UNLINKED thiếu `unlinked_at` vi phạm CHECK và test Wallet HTTP mới đăng ký JWT filter trùng. Không thay CHECK/schema hay production security để né lỗi.

Chưa chạy benchmark hiệu năng mới và không tuyên bố throughput/latency cải thiện. Raw C00 không sửa. Bank query hiện không khóa row; concurrency/retry/overflow và unlink race thuộc C05. Idempotency/outbox/worker/pagination thuộc các task kế tiếp. C03-A chỉ nghiệm thu modularization, tương thích và transaction boundaries.
