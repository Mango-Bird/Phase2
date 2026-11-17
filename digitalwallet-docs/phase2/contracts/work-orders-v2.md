# Work orders v2 — phân việc và contract bàn giao A/B

Ngày: 17/11/2026. A = Mango-Bird, vừa code vừa duy nhất nghiệm thu/merge. B không review, approve, chạy lại kiểm thử thay A hoặc ký PR của A. Mỗi ticket có một owner và PR riêng. Dùng [mẫu contract](PR-CONTRACT-TEMPLATE.md); tiêu chí QA và checklist kỹ thuật đầy đủ nằm trong [kế hoạch](../../phase2_implementation_plan.md).

## 1. Phần đã có và việc mở đầu

- A đã thiết lập CI, main rulesets và sửa 5 lỗi test baseline. Không tạo ticket yêu cầu B xây lại các phần này.
- C00 benchmark và C02 migration còn chưa xong. C01-A đã có [baseline contract v1](architecture-contracts-v1.md) và [biên bản bàn giao](C01-A-r1.md), chờ A review/merge để có hiệu lực.
- **A bắt đầu C01-A; B có thể bắt đầu C00-B song song sau khi A giao ticket.** C02-B chỉ code khi schema policy được A chốt. Đây là phân công, không phải xác nhận B đã bắt đầu.
- PR cũ đã merge và root mới đã có evidence; code tiếp theo phải branch từ main mới, không merge lại lịch sử Phase 1.

## 2. Contract theo ticket

### C01-A — Chốt kiến trúc và public contracts

Owner A; tự nghiệm thu. C01-A chuyển ADR-001…004 từ DRAFT sang DECIDED candidate; sau A merge trở thành ACCEPTED baseline theo contract. Xem [contract chi tiết](architecture-contracts-v1.md) và [checklist C01-A-r1](C01-A-r1.md).

- [ ] A đổi trạng thái ADR sang ACCEPTED khi thực sự chốt; ghi quyết định/đánh đổi, không đánh accepted hàng loạt vì file tồn tại.
- [ ] Chốt module dependency graph và register/create-wallet orchestration, không có Identity↔Wallet cycle.
- [ ] Chốt chữ ký public APIs, ownership dữ liệu, error codes và transaction owner.
- [ ] Chốt idempotency normalization/in-flight/errors, outbox payload/envelope và versioning.
- [ ] Lập danh sách thay đổi API có chủ đích, migration policy và version reservation.

Đầu ra: ADR được A chấp nhận, bảng interface/schema/event có version để B dựa vào. Gate: đối chiếu register, topup, withdraw, transfer, unlink; mỗi bước có module owner và transaction boundary. Không yêu cầu B duyệt thiết kế của A. CI của PR tài liệu phải PASS trước A merge.

### C00-B — Baseline tải có thể tái lập

Owner B; A review. Đầu vào: main có CI, chưa thay đường chạy payment.

- [ ] Chốt baseline SHA, hardware, app/DB/load-generator, dataset/seed và kịch bản dưới tải chuẩn/hot-wallet.
- [ ] Tách load generator khỏi SUT hoặc công bố hạn chế đo; không lấy số liệu Kaggle cũ thay baseline hiện tại.
- [ ] Chạy warm-up và tối thiểu 3 lượt tải chuẩn; ghi achieved RPS, p95/p99, lỗi kỹ thuật/nghiệp vụ, dropped iterations và tài nguyên.
- [ ] Đối soát số dư sau tải, lưu raw metrics và lệnh tái lập.

Đầu ra: `benchmark/baseline.md`, config/seed/scripts và artifact. Gate: A chạy lại hoặc kiểm tra evidence đủ tái lập; số đo có nguyên nhân cho lỗi, không tuyên bố 0 violation là chứng minh tuyệt đối. B không làm thay baseline test đã có của A.

### C02-B — Migration và DB integration harness

Owner B; A review. Dependency: schema policy C01-A đã merge. CI hiện có là đầu vào cần duy trì.

- [ ] Chọn migration tool theo quyết định A; ownership SQL init và migration rõ ràng.
- [ ] Test DB trống, DB Phase 1 có dữ liệu, restart và schema sai; không mất số dư/ID/lịch sử.
- [ ] Cung cấp DB fixture/reset chỉ cho test, transaction rollback/fault infrastructure và khả năng chạy hai app instance trong test sau này.
- [ ] Tích hợp test mới vào job hiện có; giữ tên `Backend build and test` và upload reports cả khi fail.
- [ ] Chuyển cấu hình production sang validate có kế hoạch tương thích; không đổi toàn bộ config khi chưa có migration.

Đầu ra: migration scripts, DB harness, tài liệu chạy, evidence SQL trước/sau. Gate: DB mới/cũ nâng cấp được, chạy lại không lặp migration; schema sai fail rõ. Không đánh PASS cho toàn bộ concurrency/idempotency chưa được A code.

### C03-B — Identity/platform và architecture tests

Owner B; A review. Dependency: C01-A/C02-B.

- [ ] Di chuyển Identity và platform theo contract; không sửa Wallet/Banking/Payment internals ngoài seam A đã giao.
- [ ] Thiết lập architecture rules; có test/case chứng minh import internals và cycle bị phát hiện.
- [ ] Trong giai đoạn chuyển tiếp, exception phải ghi phạm vi và ticket gỡ, không allow toàn bộ graph.
- [ ] Giữ login/register/JWT contract; kiểm tra register orchestration không phá atomicity.

Gate: regression PASS và quy tắc kiến trúc thực sự bắt lỗi. A review rồi merge trước khi A bắt đầu C03-A.

### C03-A — Wallet/Banking/Payment modules

Owner A; tự nghiệm thu. Dependency: C03-B đã merge.

- [ ] Di chuyển từng module, dùng API công khai và gỡ transitional exceptions liên quan.
- [ ] Giữ HTTP contract ở bước refactor; không gộp idempotency hay worker vào cùng PR.
- [ ] DB transaction và mapper không thay đổi ý nghĩa; regression/architecture tests PASS.

Gate: không dependency cycle, không truy cập entity/repository xuyên module, source/target/history không bị tách transaction do refactor. A tự cung cấp test và evidence, không chờ B review.

### C04-A — Idempotency

Owner A; tự nghiệm thu. Dependency: C03-A và DB harness C02-B.

- [ ] Header/schema unique/hash/replay/in-flight theo ADR; update OpenAPI/client/k6 nếu API đổi.
- [ ] Claim key, money/history/success result cùng transaction; xử lý unique conflict sau rollback.
- [ ] Test 100 request cùng key, payload mismatch, hai actor/operation, hai key cùng payload và replay sau restart/mất response.
- [ ] Chỉ một business effect; không lưu success giả khi rollback, không giữ key chỉ trong memory.

Gate: QA01/QA04 PASS trên MySQL/two-instance scenario. A viết test nghiệp vụ; B không là reviewer hay người phê duyệt test của A.

### C05-A — Money invariants và concurrency

Owner A; tự nghiệm thu. Dependency: C04-A.

- [ ] Amount/overflow/giới hạn tiền/ACTIVE/ownership được kiểm tra ở use case/domain.
- [ ] Hoàn thiện race unlink-payment, không coi việc kiểm tra ACTIVE tĩnh đã đủ.
- [ ] Retry facade/executor tách boundary, tối đa 3 attempts, transaction mới và lỗi kỹ thuật/nghiệp vụ phân biệt.
- [ ] Test 100/80/70, tổng chi đủ tiền, A↔B, hot receiver, fault sau debit/credit/history và trước commit.
- [ ] Kiểm tra DB sau test, không chỉ HTTP; có barriers/timeouts/seed cho race tests.

Gate: QA02/QA03; không số dư âm, double debit hoặc partial commit. Không nới invariant để giảm tỷ lệ lỗi. Thay locking strategy cần evidence benchmark riêng.

### C06-B — Outbox schema/storage

Owner B; A review. Dependency: migration tool và event contract A chốt; có thể làm song song C04/C05 nếu không sửa Payment.

- [ ] Migration outbox, payload v1/envelope, uniqueness, indexes và mapping theo contract.
- [ ] Outbox append port có thể tham gia transaction của caller, không tự commit độc lập.
- [ ] Test storage/rollback trong DB harness, không gọi HTTP/provider ngoài.
- [ ] Bàn giao interface và lease/claim semantics cho C06-A/C07-B; chưa cần triển khai cả worker trong PR storage.

Gate: storage không phá caller transaction; migration tương thích; A nghiệm thu schema/interface. B không sửa publisher/payment của A để tiện tích hợp.

### C06-A — Publisher nguyên tử

Owner A; tự nghiệm thu. Dependency: C05-A và C06-B.

- [ ] Ghi một `payment.completed.v1` cho mỗi payment thành công, không thêm event khi idempotency replay.
- [ ] Debit/credit/history/idempotency/outbox cùng transaction.
- [ ] Fault ở trước/sau append và trước commit không tạo event mồ côi hoặc thiếu event của payment thành công.

Gate: count và DB invariants chính xác, transaction rollback toàn bộ; payload consumer đọc được. A tự test phần publisher.

### C07-B — Worker/inbox/recovery

Owner B; A review. Dependency: C06-A đã merge.

- [ ] Claim batch/lease/token, reclaim khi crash và ngăn token cũ ghi đè.
- [ ] Insert inbox + mark done cùng transaction; unique event/recipient/channel.
- [ ] Retry/backoff/DEAD/replay có audit; poison event không chặn event khác.
- [ ] Chạy hai worker; thử crash sau claim/insert/trước mark done và outage 5 phút theo workload đã khóa.
- [ ] API inbox có quyền và phân trang; worker tắt không chặn Payment.

Gate: QA05/QA06, không mất event hoặc inbox trùng. SLA backlog ghi số event/tốc độ cụ thể. Không hứa exactly-once email/SMS.

### C08-A — Query/history

Owner A; tự nghiệm thu. Dependency: C03-A, dữ liệu test C02-B.

- [ ] Cursor `(createdAt,id)`, mặc định 20/max 100; version/compatibility HTTP được chốt.
- [ ] Test quyền, timestamp trùng, cursor sai và ghi mới trong khi duyệt; semantics rõ.
- [ ] EXPLAIN/latency trên ít nhất 100.000 giao dịch, không load hết vào memory.

Gate: không lặp/mất trên dataset cố định, không lộ lịch sử người khác; báo chi phí ghi của index.

### C09-A — Security và semantics log nghiệp vụ

Owner A; tự nghiệm thu. Dependency: contracts API và error model đã chốt.

- [ ] Ownership trên mọi đường đọc/ghi; không lấy actor từ request body.
- [ ] Generic 500 có message chung/trace id, không trả exception nội bộ.
- [ ] Log payment/retry/idempotency nối được transaction reference, không lộ secret/PII.
- [ ] Cập nhật tests nếu error contract đổi có chủ đích; giải thích trước–sau, không thay expectation để che bug.

Gate: QA10 và fault log cases PASS. Metric/log infrastructure thuộc C09-B; nếu chưa có dùng contract interface/mock rõ, nghiệm thu tích hợp sau C09-B.

### C09-B — Observability và cấu hình

Owner B; A review. Dependency: tên metrics/error/correlation contract A chốt; có thể song song C09-A khi không sửa cùng file.

- [ ] Production thiếu JWT secret fail startup; test/dev có secret riêng, không dùng credential deployment trong CI.
- [ ] Correlation ingress, structured logs, metrics latency/conflict/retry/outbox/pool, readiness/health.
- [ ] Không đưa userId/transactionId/key vào metric labels; không public endpoint quản trị ngoài policy.
- [ ] Dashboard/runbook phân biệt worker unhealthy với Payment DB outage; có cách quan sát oldest event/DEAD.

Gate: lỗi Payment/worker có thể truy vết; worker dừng được phát hiện, API vẫn dùng được khi DB khỏe. Không đổi ruleset hay tên check mà không được A chốt.

### C10-B — Reconciliation

Owner B; A review. Dependency: Wallet/Payment contracts ổn định sau C05/C06.

- [ ] Opening balance/cutoff nhất quán; không giả định ví kế thừa có số dư 0.
- [ ] Balance/history cùng consistent snapshot; tạo findings có expected/actual/delta/runId.
- [ ] Test dữ liệu đúng, balance sai, thiếu history và đang có traffic; không tự sửa tiền.

Gate: QA09; phát hiện ca gieo lỗi và không báo giả do snapshot lệch. Ghi giới hạn khi history và balance cùng sai nhưng khớp nhau.

### C11-B — Benchmark cuối và failure campaign

Owner B; A review. Dependency: C00-B và các module mục tiêu đã merge.

- [ ] Cùng config/workload baseline, tối thiểu 3 lần, raw output và biến động.
- [ ] Test duplicate/hot-wallet/worker outage/restart/two-instance; kết quả đối soát sau tải.
- [ ] Báo achieved RPS, p95/p99, errors theo nhóm, resource, dropped iterations và overhead tính đúng.
- [ ] QA08 theo budget đã khóa; nếu vượt thì mở ticket đúng owner, không tự giảm tải để lấy PASS.

Gate: QA01–QA10 có evidence bắt buộc, lỗi phần A do A sửa, lỗi phần B do B sửa. Benchmark của B không là review PR A; đây là nghiệm thu tích hợp sản phẩm do A quyết định.

### C12-B / C12-A — Bàn giao vận hành và release

C12-B owner B, A review: runbook deploy/migration/recovery, replay DEAD, backup/restore thử trên DB biệt lập, seed/demo và evidence vận hành.

C12-A owner A, tự nghiệm thu/release:

- [ ] Mọi dependency đã merge, required CI head/main PASS và không còn blocker tiền/quyền/mất dữ liệu.
- [ ] Contract từng ticket được A ACCEPTED, QA có evidence và known limits.
- [ ] Chạy smoke cuối; xác minh DB upgrade/restore; version OpenAPI/event/ADR/docs khớp code.
- [ ] A quyết định release/hoãn, ghi commit/release version và rollback plan.

B chỉ bàn giao phần mình, không ký thay A, không phê duyệt phần A và không tự tạo merge/release.

## 3. Contract hợp tác và xử lý thay đổi

1. B cần thay public interface/schema/event: nêu vấn đề và phương án trong ticket; A chốt revision trước code phụ thuộc. A không cần B approve thay đổi A, nhưng phải thông báo contract revision để B tích hợp.
2. B bị chặn bởi PR A chưa merge: ghi BLOCKED cùng dependency, chuyển sang ticket độc lập được giao; không chép code chưa merge rồi trộn hai PR.
3. A thay đầu vào sau khi B bắt đầu: ghi compatibility, phần B phải sửa và thứ tự merge; tách adapter/migration nếu cần.
4. Không dùng một PR cho C03-A/B hoặc C06-A/B. Sửa file chung cần bàn giao ownership rõ ràng.
5. Sau mỗi push, owner theo dõi GitHub CI đến SUCCESS; FAIL/NOT RUN không đủ để A nghiệm thu. A là người duy nhất chuyển ACCEPTED/merge; CI xanh không tự đồng nghĩa contract hoàn tất.
6. Docs-only PR vẫn chạy CI hiện tại. Tick contract thực tế trên PR và gắn head SHA; không tạo chuỗi commit chỉ để cập nhật SHA của chính file.
