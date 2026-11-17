# Khảo sát và đề xuất Phase 2

> Cập nhật phạm vi: theo trao đổi sau khảo sát, Phase 2 tập trung cải tiến kiến trúc và thuộc tính chất lượng; multi-wallet/đa tiền tệ không thuộc phạm vi chính. Xem [lộ trình triển khai, phân công và contract nghiệm thu](phase2_implementation_plan.md). Các đề xuất dưới đây giữ lại làm thông tin khảo sát ban đầu.

Ngày khảo sát: 17/11/2026. Phạm vi: mã nguồn backend, schema SQL, cấu hình triển khai, bộ test và báo cáo tải trong repository. Đây là đánh giá tĩnh; chưa chạy ứng dụng, Gradle test hoặc tái lập stress test. Các số liệu hiệu năng là kết quả được tài liệu hiện có báo cáo.

## 1. Kết luận

Phase 1 có nền tảng tốt cho một backend ví mô phỏng: tách ports/adapters, transaction bao quanh thao tác tiền, optimistic locking, ràng buộc DB và bộ test HTTP/security. Phase 2 nên ưu tiên độ đúng khi retry và cạnh tranh đồng thời, sau đó mở rộng nhiều ví/tiền tệ và thông báo bền vững. Tiếp tục dùng monolith hiện tại để giữ giao dịch nội bộ trong một database transaction.

Ba yêu cầu trong `phase2_suggestion.md` cần được diễn giải thành:

1. Một user sở hữu nhiều ví, mỗi ví có currency và quyền sở hữu rõ ràng.
2. Một yêu cầu nghiệp vụ được ghi tiền một lần dù client gửi lại; thông báo có cơ chế chống trùng.
3. Hai yêu cầu cùng chi tiền không được làm số dư âm hoặc mất cập nhật; yêu cầu chạy sau phải đọc lại số dư trước khi quyết định.

## 2. Hiện trạng và khoảng trống

| Hạng mục | Bằng chứng trong repository | Nhận định |
|---|---|---|
| Kiến trúc | `application/port`, `application/service`, `adapter/in`, `adapter/out` | Có thể mở rộng qua ports. Application vẫn dùng Spring `@Service`, `@Transactional`; mô tả core hoàn toàn độc lập framework trong tài liệu cần điều chỉnh hoặc triển khai transaction decorator. |
| Chống trùng | `TransactionService` sinh `UUID.randomUUID()` cho mỗi lần topup/withdraw/transfer | `reference_code` unique là mã giao dịch, chưa chống được retry cùng yêu cầu. |
| Đồng thời | `WalletJpaEntity` có `@Version`; handler đổi optimistic conflict thành HTTP 409 | Đã có cơ chế phát hiện cập nhật cạnh tranh. Chưa chứng minh bằng test DB đồng thời; không nên coi đây là bài toán hoàn toàn chưa xử lý. |
| Nạp/rút | `TransactionService` sửa balance và ghi transaction, không gọi bank port | Luồng mô phỏng, chưa xác nhận ngân hàng thực sự ghi nợ/ghi có. Cần ghi rõ giới hạn này khi demo. |
| Ngân hàng đã unlink | topup/withdraw gọi `loadByIdAndWalletId`; repository chỉ lọc id và wallet | Chưa kiểm tra `ACTIVE` ở đường thực thi nạp/rút. Cần xử lý cả trường hợp unlink đồng thời với giao dịch. |
| Nhiều ví | SQL unique `wallets.user_id`, entity `unique=true`, `findByUserId` trả `Optional`, controller tự lấy ví của user | Phải đổi schema, ports, API và kiểm tra quyền; bỏ unique một cột chưa đủ. |
| Tiền tệ | Wallet/transaction dùng `Long`, chưa có currency | Cần đơn vị tiền nhỏ nhất, giới hạn tiền và chống overflow khi cộng; validation hiện chủ yếu ở HTTP DTO. |
| Lịch sử | `TransactionRepository.findByWalletId` trả toàn bộ list, sort theo `createdAt` | Cần phân trang và thứ tự ổn định `(createdAt,id)`. |
| Bảo mật/cấu hình | JWT có secret mặc định; generic handler trả `ex.getMessage()`; `ddl-auto=update` | Production phải bắt buộc secret, tránh lộ lỗi nội bộ và dùng migration có phiên bản. |
| Kiểm thử | Endpoint tests dùng `@WebMvcTest`, mock persistence ports | Có ích cho contract và orchestration, chưa chứng minh rollback, khóa, constraint hay atomicity trên MySQL. |
| Hiệu năng | README và Report có mô tả khác nhau về endpoint phát sinh 409; k6 và SUT dùng chung CPU | Chưa đủ căn cứ kết luận năng lực tối đa hoặc nguyên nhân của mọi failure. Không suy ra deadlock từ HTTP 409. |

## 3. Backlog đề xuất

### P0 — Độ đúng của giao dịch

**P2-01: Idempotency cho cả ba API ghi tiền.**

- Nhận `Idempotency-Key`; giữ `reference_code` làm định danh giao dịch riêng.
- Bảng `idempotency_requests`: actor, operation, key, request hash chuẩn hóa, trạng thái, transaction id và kết quả cần replay; unique `(actor_id, operation, key)`.
- Cùng key/cùng payload trả lại cùng kết quả đã hoàn tất; cùng key/khác payload trả lỗi conflict. Cùng payload nhưng khác key là hai yêu cầu khác nhau.
- Với giao dịch nội bộ đồng bộ: việc chiếm key, ghi tiền, ghi transaction và lưu kết quả phải commit nguyên tử. Khi unique conflict, rollback transaction hiện tại rồi đọc kết quả trong transaction mới; tránh tiếp tục dùng transaction đã lỗi. Quy định rõ cách xử lý request đang chạy và lỗi retryable.
- Công bố thời hạn lưu key; không để xóa key sớm làm retry muộn tạo giao dịch trùng.
- Nghiệm thu: 100 request đồng thời cùng key chỉ tạo một giao dịch; mất response sau commit rồi retry không đổi số dư lần nữa; restart app vẫn replay được.

**P2-02: Hoàn thiện xử lý cạnh tranh.**

- Trước mắt giữ `@Version`; thêm retry hữu hạn cho optimistic conflict/deadlock, mỗi lần chạy trong transaction mới, đọc lại dữ liệu và kiểm tra lại nghiệp vụ. Đặt retry ở ngoài transaction boundary, tránh self-invocation làm mất proxy semantics.
- Chỉ retry lỗi kỹ thuật tạm thời; không retry thiếu tiền, ví khóa hoặc request không hợp lệ.
- Nếu đo được contention cao, thử pessimistic row locking theo thứ tự wallet id tăng dần; so sánh latency, throughput và tỷ lệ conflict trước khi chọn. Khi dùng pessimistic locking, đọc/khóa rồi mới kiểm tra balance/status.
- Với balance=100, hai request chi 80 và 70: chỉ một được thành công; sau retry request còn lại nhận lỗi thiếu tiền. Với hai khoản tổng nhỏ hơn số dư, cả hai có thể thành công.
- Nghiệm thu thêm chuyển A→B và B→A, nhiều người cùng nhận tiền, lỗi sau khi debit nhưng trước credit/ledger; không có partial commit.

**P2-03: Bất biến nghiệp vụ.**

- Kiểm tra amount dương và giới hạn tại core/use case; dùng phép cộng có kiểm tra overflow và quy định số dư tối đa.
- Chặn ngân hàng `UNLINKED` cho topup/withdraw; xác định thứ tự tuần tự hóa unlink với giao dịch bằng khóa hoặc cơ chế version chung phù hợp.
- Chuẩn hóa status/type bằng enum và gom quy tắc debit/credit trong domain.
- Nghiệm thu bằng MySQL thật/Testcontainers, gồm rollback, optimistic conflict, constraint và các kịch bản đồng thời; chạy được với ít nhất hai app instance để loại trừ giải pháp chỉ khóa trong JVM.

### P1 — Yêu cầu nghiệp vụ Phase 2

**P2-04: User 1–N Wallet và currency.**

- Chốt phạm vi một ví mỗi currency/user; dùng unique `(user_id,currency)`. Nếu cần nhiều ví cùng currency, thay bằng quy tắc product cụ thể.
- Migration backfill ví hiện tại thành VND, đổi unique ở DB lẫn JPA, đổi ports trả danh sách.
- API đề xuất: `GET/POST /api/v2/wallets`, `GET /api/v2/wallets/{walletId}`; các thao tác tiền chỉ rõ source wallet.
- Kiểm tra quyền sở hữu trong use case cho mọi thao tác đọc/ghi; user không được tự chọn ví nguồn của người khác.
- Lưu số tiền theo đơn vị nhỏ nhất bằng integer và kèm currency. Phase 2 chỉ chuyển cùng currency; FX cần đặc tả tỷ giá, phí, làm tròn và thời hạn báo giá riêng.
- Chốt liên kết ngân hàng thuộc user hay ví; giữ theo ví là phương án ít thay đổi nhất nhưng cần kiểm tra khả năng hỗ trợ currency.
- Nghiệm thu: dữ liệu cũ giữ nguyên giá trị VND; không truy cập chéo user, không chuyển chéo currency, không tạo trùng ví dưới tải đồng thời.

**P2-05: Thông báo bền vững qua transactional outbox.**

- Ghi `outbox_events` cùng DB transaction với giao dịch tiền. Worker gửi sau commit, retry có backoff, lưu attempts và lỗi, có hàng đợi lỗi hoặc trạng thái cần xử lý thủ công.
- Consumer deduplicate bằng event id; notification inbox có unique `(event_id,recipient_id,channel)`.
- Với inbox trong ứng dụng có thể đảm bảo một bản ghi hiển thị. Với email/SMS, cần provider hỗ trợ idempotency để tránh gửi trùng ở cửa sổ crash sau gửi nhưng trước đánh dấu; không hứa exactly-once delivery nếu chưa có điều kiện đó.
- Nghiệm thu: rollback không sinh thông báo; crash worker ở các điểm trước/sau publish không mất event; publish lại không tạo inbox trùng.

**P2-06: Lịch sử và tra cứu giao dịch.**

- Cursor pagination theo `(created_at,id)`, giới hạn page size, lọc loại/ngày và tra cứu mã giao dịch có kiểm tra quyền.
- Kiểm tra execution plan trước khi thêm index `(source_wallet_id,created_at,id)` và `(target_wallet_id,created_at,id)`; cân nhắc hai nhánh query nếu OR làm mất hiệu quả index.

### P1/P2 — Khả năng vận hành

- P1: bỏ JWT secret fallback trong production; lỗi 500 trả thông điệp chung và trace id, giữ chi tiết trong server logs; giới hạn login và thao tác nhạy cảm; audit việc thay đổi trạng thái ví.
- P1: Flyway/Liquibase migration, production `ddl-auto=validate`; kiểm tra nâng cấp từ DB Phase 1 và quy trình backup/restore.
- P1: CI build/test và MySQL integration tests; metrics latency p95/p99 theo endpoint, conflict/deadlock/retry, connection pool, outbox lag.
- P2: access token ngắn hạn cùng refresh token có rotation/revocation nếu cần phiên đăng nhập dài; chức năng freeze/unfreeze có phân quyền và audit.

## 4. Hướng mở rộng sau phần bắt buộc

Nếu mục tiêu là tích hợp ngân hàng, cần `BankGatewayPort`, provider reference, callback xác thực và chống trùng; trạng thái pending/succeeded/failed/unknown, cơ chế giữ tiền khi rút và đối soát khi timeout. Không gọi ngân hàng trong một DB transaction kéo dài, không ghi nhận topup thành công chỉ vì client yêu cầu. Có thể bắt đầu bằng bank simulator có callback trễ, trùng và timeout.

Nếu mục tiêu là nền tảng tiền thật, bổ sung journal/ledger entries ghi sổ kép, tài khoản đối ứng cho nạp/rút, reversal bằng bút toán mới và đối soát balance với ledger. Bảng `transactions` hiện là lịch sử nghiệp vụ, chưa có mô hình sổ kép. Phạm vi này cần thiết kế riêng và không đồng nghĩa sản phẩm đã đủ điều kiện production.

Chưa ưu tiên microservices, Kafka, sharding hoặc cache số dư làm nguồn quyết định chi tiền. Chỉ bổ sung khi có số đo hoặc yêu cầu triển khai cụ thể.

## 5. Lộ trình tham khảo

Ước lượng cho nhóm đã quen Java/Spring; cần hiệu chỉnh sau khi phân công và chốt scope.

| Mốc | Nội dung | Điều kiện hoàn thành |
|---|---|---|
| Tuần 1–2 | P2-01/02/03, migration nền, test MySQL | Không double debit, không số dư âm, rollback đầy đủ, retry đúng boundary |
| Tuần 3 | P2-04 | Migration VND an toàn, nhiều ví và kiểm tra ownership/currency |
| Tuần 4 | P2-05/06 | Inbox không trùng, worker phục hồi sau restart, lịch sử phân trang |
| Tuần 5 | Security, CI, metrics, chạy lại tải | Báo cáo tái lập được, có ngưỡng nghiệm thu trên môi trường được mô tả |

## 6. Sửa phương pháp benchmark

- Tách load generator khỏi SUT; ghi cấu hình CPU/RAM, DB, app replicas và điều kiện mạng. Tunnel nếu dùng phải được xem là biến gây nhiễu.
- Phân biệt request hợp lệ thành công, lỗi nghiệp vụ mong đợi, optimistic conflict, deadlock, timeout và 5xx; không gộp tất cả thành công về nghiệp vụ.
- Đo achieved RPS, p95/p99, dropped iterations, CPU từng tiến trình, DB lock waits; có workload hot-wallet và retry/idempotency riêng.
- Đối soát DB sau tải: tổng tiền theo currency có tính net topup/withdraw, balance không âm, một business effect mỗi key và balance khớp lịch sử/ledger.
- Không chấp nhận sai lệch tiền tạm thời ở sổ gốc. Read model/thông báo có thể trễ trong giới hạn công bố; cần phân biệt với vi phạm bất biến số dư.
- Báo cáo hiện tại ghi zero violation chỉ là bằng chứng trong phạm vi workload/cách đo đã chạy, không phải chứng minh toàn vẹn tuyệt đối.

## 7. Tham khảo thiết kế

- MySQL: thứ tự truy cập nhất quán và xử lý retry khi deadlock: https://dev.mysql.com/doc/refman/8.0/en/innodb-deadlocks-handling.html
- Transactional outbox và yêu cầu consumer idempotent: https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html
