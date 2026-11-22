# Phase 2 — Lộ trình cải tiến kiến trúc và thuộc tính chất lượng

> Bản điều phối v2: CI và ruleset đã triển khai; các hạng mục nghiệp vụ còn lại chưa được nghiệm thu nếu chưa có contract PASS và quyết định của A.
> Cập nhật: 17/11/2026. A = chủ project Mango-Bird, vừa code vừa review/nghiệm thu và duy nhất merge. B = thành viên triển khai nền tảng, không review PR của A.
> Cơ sở khảo sát: `phase2_assessment.md`. Tài liệu này chốt lại phạm vi Phase 2 theo thuộc tính chất lượng, thay cho hướng mở rộng multi-wallet ở bản khảo sát ban đầu.

## 1. Mục tiêu, phạm vi và nguyên tắc

**Mục tiêu:** chuyển backend hiện tại thành Modular Monolith có ranh giới được kiểm chứng, một luồng thực thi giao dịch nguyên tử, idempotency bền vững và Transactional Outbox. Chứng minh tác động tới khả năng bảo trì, độ tin cậy, khả năng phục hồi, khả năng quan sát và hiệu năng bằng test và số đo trước–sau.

### 1.1. Trong phạm vi

- Giữ Java 21, Spring Boot, MySQL và API nghiệp vụ Phase 1 làm nền.
- Tổ chức lại module Identity, Wallet, Banking, Payment, Notification; thêm Reconciliation sau khi đường ghi tiền ổn định.
- Idempotency cho topup, withdraw, transfer; tiền, bản ghi giao dịch, kết quả replay và outbox cùng transaction.
- Kiểm soát đồng thời trên DB, rollback, retry hữu hạn và kiểm thử nhiều app instance.
- Notification inbox trong DB làm kênh nghiệm thu chính; worker phục hồi và xử lý chống trùng.
- Migration, CI, phân trang lịch sử, metrics/log, đối soát và benchmark tái lập được.

### 1.2. Ngoài phạm vi

- Multi-wallet, đa tiền tệ, FX, UI mới, ngân hàng thật, email/SMS production, Kubernetes, Kafka và chia microservices.
- Ledger sổ kép đầy đủ, tài khoản kế toán và quy trình quyết toán tiền thật. Bảng giao dịch hiện tại tiếp tục là lịch sử nghiệp vụ; không gọi nó là sổ kép.
- Nạp/rút vẫn là mô phỏng; mọi tài liệu demo phải ghi rõ giới hạn này.

### 1.3. Nguyên tắc bắt buộc

1. Số dư không âm, không mất cập nhật, một yêu cầu idempotent có tối đa một tác động lên tiền.
2. Không cho phép eventual consistency giữa debit, credit và lịch sử của cùng giao dịch nội bộ.
3. Notification và đối soát được phép xử lý sau commit; lỗi worker không làm mất giao dịch tiền đã commit.
4. Mỗi thay đổi kiến trúc phải có contract, bằng chứng và cách phục hồi. Đổi package đơn thuần chưa đủ chứng minh chất lượng tăng.
5. Không sửa code ngoài phạm vi ticket để tiện tay refactor. Không đổi kết quả kỳ vọng của test chỉ để vượt gate.

## 2. Kiến trúc đích và quyền sở hữu

Contract chuẩn triển khai: [architecture-contracts.v1](phase2/contracts/architecture-contracts-v1.md), gồm module/API/ownership, transaction, HTTP/error, idempotency, event và migration reservations. Onboarding composition use case được mô tả đầy đủ ở đó; sơ đồ dưới là tổng quan.

```mermaid
flowchart TB
    Web[REST adapters] --> Identity[Identity public API]
    Web --> Wallet[Wallet public API]
    Web --> Payment[Payment public API]
    Web --> Banking[Banking public API]
    Payment --> Wallet
    Payment --> Banking
    Payment --> History[Payment history]
    Payment --> Outbox[(Outbox records)]
    Outbox --> Worker[Outbox worker]
    Worker --> Inbox[Notification inbox]
    Reconcile[Reconciliation] --> Wallet
    Reconcile --> History
```

### 2.1. Cấu trúc đề xuất

```text
sa.uet.digitalwallet_system
  identity/{api,application,domain,adapter}
  wallet/{api,application,domain,adapter}
  banking/{api,application,domain,adapter}
  payment/{api,application,domain,adapter}
  notification/{api,application,domain,adapter}
  reconciliation/{application,adapter}
  platform/{config,security,observability,outbox}
```

`api` ở đây là Java API nội bộ của module, không đồng nghĩa REST controller. DTO công khai không chứa JPA entity. `platform` chỉ chứa hạ tầng dùng chung; không trở thành nơi gom quy tắc nghiệp vụ.

| Module | Sở hữu dữ liệu | Public API dự kiến | Được phụ thuộc |
|---|---|---|---|
| Identity | users | đăng ký, đăng nhập, truy vấn định danh tối thiểu | platform |
| Wallet | wallets | tạo ví, truy vấn có quyền, debit/credit trong transaction hiện tại | platform |
| Banking | linked_bank_accounts | link/unlink, xác nhận liên kết ACTIVE thuộc ví | Wallet API, platform |
| Payment | transactions, idempotency_requests | topup/withdraw/transfer, tra cứu/lịch sử | Wallet API, Banking API, outbox port |
| Notification | notifications | ghi inbox idempotent, đọc inbox của caller | event contract, platform |
| Reconciliation | reconciliation_runs/findings | chạy đối soát và đọc báo cáo | API đọc Wallet/Payment |

Identity đăng ký và tạo ví qua orchestration ở composition layer hoặc một use case phụ thuộc Wallet API; chốt một chiều bằng ADR để tránh vòng phụ thuộc Identity↔Wallet. Wallet không import Identity internals.

### 2.2. Transaction boundary

- Payment execution service là chủ transaction của thao tác ghi tiền.
- Wallet/Banking tham gia cùng transaction manager và datasource; không dùng `REQUIRES_NEW` cho debit/credit, lịch sử hoặc outbox.
- Debit/credit chỉ cho gọi trong transaction hợp lệ; quy tắc này được kiểm tra bằng integration test và cấu hình transaction phù hợp.
- Retry facade nằm ngoài execution service; mỗi attempt tạo transaction mới và đọc lại dữ liệu. Không gọi method qua `this` rồi kỳ vọng Spring proxy áp dụng transaction/retry.
- Worker transaction độc lập với transaction Payment. Không gửi email, HTTP hoặc chờ dịch vụ ngoài khi giữ khóa ví.
- Kiểm tra kiến trúc bằng ArchUnit hoặc công cụ tương đương: cấm vòng phụ thuộc, import repository/entity ngoài module, domain import Spring/JPA; application được phép dùng Spring theo ADR đã chốt.

## 3. Thuộc tính chất lượng và tiêu chí đánh giá

Các mốc thời gian dưới đây là mục tiêu ban đầu cho môi trường nghiệm thu, không phải số đo đã đạt. Khóa cấu hình máy và workload tại C00; chỉ điều chỉnh mục tiêu qua ADR kèm lý do trước khi đo kết quả cuối.

| ID | Kịch bản | Phản ứng mong đợi | Ngưỡng/gate |
|---|---|---|---|
| QA01 | 100 request đồng thời, cùng actor/operation/key/payload | Một giao dịch, các response thành công tham chiếu cùng mã | 0 ghi tiền trùng; request đang chạy có thể nhận lỗi retryable được đặc tả |
| QA02 | Ví 100, chi đồng thời 80 và 70 | Một thành công, một thiếu tiền sau khi xử lý conflict | Số dư cuối 20 hoặc 30; đúng một lịch sử thành công |
| QA03 | Chèn lỗi sau debit, trước credit hoặc trước commit | Rollback toàn bộ | 0 thay đổi tiền/lịch sử/outbox/idempotency hoàn tất |
| QA04 | Commit xong, mất response, restart app rồi retry | Replay kết quả cũ | 0 giao dịch mới và 0 outbox mới |
| QA05 | Dừng worker 5 phút, Payment tiếp tục chạy | Sự kiện tồn được xử lý khi worker trở lại | Không mất event; inbox không trùng; mục tiêu xử lý hết backlog thử nghiệm trong 2 phút |
| QA06 | Hai worker claim cùng backlog; một worker crash | Lease hết hạn được thu hồi, xử lý tiếp | 0 notification logic trùng; không có event bị kẹt vô hạn |
| QA07 | Một module import repository module khác | Build từ chối | Architecture test thất bại đúng lỗi |
| QA08 | Chạy tải chuẩn trước/sau | Đo khả năng đáp ứng và regression | 0 invariant violation; p95 không tăng quá 10% và throughput không giảm quá 10% ở tải chuẩn đã khóa |
| QA09 | Gây sai lệch số dư trong DB test | Đối soát phát hiện đúng ví và chênh lệch | Phát hiện toàn bộ ca gieo lỗi; không báo sai ca đúng |
| QA10 | Request của user A tra cứu dữ liệu B | Bị từ chối theo contract | Không lộ số dư, giao dịch, inbox hoặc liên kết ngân hàng |

QA08 là gate regression ban đầu, không phải tuyên bố hệ thống nhanh hơn. Báo cáo riêng cả chi phí độ trễ của idempotency/outbox. QA05 phải ghi số event, cấu hình worker và tốc độ sự kiện mới; không dùng ngưỡng 2 phút cho backlog không giới hạn.

## 4. Phân công hai người — bản v2

A đã thực hiện baseline test, sửa 5 lỗi hiện hữu, thiết lập workflow CI và cấu hình quyền main. PR #2 và #3 đã được A merge; main đã đổi sang lịch sử mới. Không giao lại phần CI bootstrap cho B như công việc chưa làm. C00 còn benchmark; C01 có ADR dự thảo, chưa đồng nghĩa thiết kế đã được chốt; C02 còn migration và kiểm thử DB nghiệp vụ.

| Người | Phần việc code | Quyền và trách nhiệm |
|---|---|---|
| **A — Mango-Bird, chủ project** | Kiến trúc/public contracts; Wallet/Banking/Payment; idempotency; transaction/retry; publisher; query; ownership/security nghiệp vụ | Tự kiểm tra PR mình; review PR B; chốt contract và scope; nghiệm thu, merge, quản lý ruleset và quyết định release |
| **B — Platform & Quality Engineering** | Benchmark baseline; migration; DB fixture/test infrastructure; Identity/platform; architecture tests; outbox storage/worker/inbox; telemetry; reconciliation; benchmark cuối | Tự test code mình; mở PR và bàn giao evidence; sửa theo review của A. Không review, approve hoặc ký PR của A; không merge hoặc thay ruleset |

B viết test harness và test cho module mình là công việc triển khai, không phải bước review bắt buộc đối với PR A. A chịu trách nhiệm đầy đủ test nghiệp vụ cho phần A code; không chờ B xác nhận để nghiệm thu phần A.

### 4.1. Ownership và file dùng chung

- A sở hữu public Java APIs, HTTP/error semantics, idempotency lifecycle và event payload nghiệp vụ. B chỉ tích hợp theo phiên bản A đã chốt.
- B sở hữu cơ chế migration, test fixture, delivery envelope và platform implementations. A chốt migration ordering và review PR tương ứng.
- `build.gradle`, cấu hình Spring, Docker và workflow: người làm ticket khai báo trước; B thường thực hiện bảo trì, A là người chốt và merge. Không được tự đổi tên required check.
- Migration mới được cấp version trong contract trước khi code; không sửa migration đã merge/applied. Nội dung nghiệp vụ do owner ticket viết, B duy trì công cụ chạy.
- C03/C06/C09 tách thành ticket A/B và PR riêng; không có PR chung hai owner, không giao cả hai sửa một file cùng lúc.
- Dependency hoàn tất khi PR đã được A merge và SHA đầu vào được ghi nhận; code đang nằm trên branch của người khác chưa phải dependency đã nghiệm thu.

### 4.2. Việc bắt đầu ngay

1. **A: C01-A** — chốt 4 ADR, module API và schema/error/event contracts để mở khóa triển khai.
2. **B: C00-B** — hoàn thiện benchmark baseline trên main có CI hiện tại. Có thể chạy song song với C01-A vì không đổi kiến trúc.
3. **B: C02-B** — sau khi A chốt schema policy, triển khai migration và DB fixtures. Không dựng lại CI từ đầu.
4. Sau C02-B được merge: B làm C03-B (Identity/platform và architecture rules), rồi A làm C03-A (Wallet/Banking/Payment). Chia thứ tự này để tránh hai refactor cấu trúc cạnh tranh.

Chi tiết giao việc và contract từng ticket: [work orders v2](phase2/contracts/work-orders-v2.md). Mẫu điền mỗi PR: [PR contract template](phase2/contracts/PR-CONTRACT-TEMPLATE.md).

## 5. Lộ trình còn lại và dependency

6 tuần dưới đây là ước lượng phần việc còn lại sau nền CI, không phải cam kết thời hạn. Một tuần có thể kéo dài nếu gate chưa đạt. Chưa bắt đầu ticket phụ thuộc khi A chưa chốt đầu vào.

| Đợt | A code/chốt | B code/bàn giao | Gate để sang đợt |
|---|---|---|---|
| 1 | C01-A ADR/public contracts; review PR baseline/migration | C00-B benchmark baseline; C02-B migration + DB fixtures | A chốt thiết kế; baseline có raw evidence; migration mới/cũ đều pass |
| 2 | C03-A Wallet/Banking/Payment sau C03-B | C03-B Identity/platform + architecture tests | Không cycle/import internals; regression và required CI PASS |
| 3 | C04-A idempotency; C05-A atomicity/concurrency | C06-B outbox schema/storage sau event contract; hỗ trợ test infrastructure bằng ticket riêng nếu cần | Duplicate/retry không double debit, race/rollback đúng trên MySQL |
| 4 | C06-A publisher nguyên tử | C07-B worker/inbox sau C06-A; recovery tests | Không mất event, inbox không trùng, lease recovery đúng |
| 5 | C08-A query; C09-A security/ownership | C09-B metrics/log/health; C10-B reconciliation | Query có giới hạn; quyền đúng; quan sát/đối soát có evidence |
| 6 | Sửa lỗi phần A; C12-A tổng nghiệm thu/release | C11-B benchmark/failure campaign; C12-B runbook/restore evidence | QA bắt buộc PASS; A review và quyết định release |

Dependency: C01-A → C02-B → C03-B → C03-A → C04-A → C05-A. C00-B hoàn thành trước thay đổi đường chạy cần so sánh hiệu năng. C06-B cần schema/event contract A chốt; C06-A cần C05-A và C06-B; C07-B cần C06-A. C08-A/C09-A/C09-B có thể song song nếu ownership file rõ. C10-B cần dữ liệu C05/C06 ổn định. C11-B cần C07–C10 và baseline C00-B; C12 cần C11 cùng mọi gate bắt buộc.

## 6. Contract dùng chung cho mọi lần sửa

### 6.1. Definition of Ready — trước khi code

- [ ] Có ticket ID có hậu tố owner (ví dụ C04-A), owner duy nhất và người nghiệm thu/merge A; PR A ghi rõ tự kiểm tra.
- [ ] Dependency đã merge, base SHA và contract version đầu vào được ghi.
- [ ] Dùng branch từ main lịch sử mới; không merge branch kế thừa lịch sử cũ.
- [ ] Ghi vấn đề/kịch bản chất lượng được tác động.
- [ ] Chốt input/output, error codes và tương thích API.
- [ ] Ghi module/file/schema được phép thay đổi.
- [ ] Chốt invariant, transaction boundary và dependency.
- [ ] Có test nghiệm thu với kết quả mong đợi, dữ liệu khởi tạo và cách chạy.
- [ ] Có chiến lược rollback hoặc roll-forward; xác định dữ liệu mới có đọc được bởi bản cũ không.

### 6.2. Definition of Done — trước merge

- [ ] Code đúng scope; public contract và tài liệu khớp implementation.
- [ ] Test liên quan và regression bắt buộc đều pass trên môi trường ghi rõ.
- [ ] Có test thất bại trước bản sửa hoặc bằng chứng tái hiện vấn đề khi phù hợp.
- [ ] Migration thử trên DB mới và DB Phase 1 có dữ liệu nếu schema thay đổi.
- [ ] Không log token/password/secret/số tài khoản đầy đủ; không trả exception nội bộ cho client.
- [ ] Không phá architecture rules; không tăng dependency trái ADR.
- [ ] Evidence ghi commit SHA, command, config, kết quả và hạn chế; không ghi “pass” khi chưa chạy.
- [ ] A ghi ACCEPTED/REQUEST CHANGES và lý do gắn candidate SHA; test evidence ghi PASS/FAIL riêng. PR A tự kiểm tra, không cần B approve; thiếu bằng chứng thì chưa merge.
- [ ] GitHub required checks PASS trên commit mới nhất; lưu URL workflow run và head SHA. Không dùng kết quả local thay cho GitHub CI.
- [ ] Không push trực tiếp main, không auto-merge; chỉ A thực hiện merge sau khi thỏa mãn CI và contract.

### 6.3. Contract mỗi PR và mỗi lần sửa

Dùng [PR-CONTRACT-TEMPLATE.md](phase2/contracts/PR-CONTRACT-TEMPLATE.md) làm mẫu chuẩn duy nhất. Ghi owner A/B, người nghiệm thu A, dependency/base/candidate SHA, contract đầu vào/đầu ra, ma trận Given/When/Then, run URL và kế hoạch phục hồi.

Test case có trạng thái PASS/FAIL/NOT RUN/BLOCKED; quyết định nghiệm thu của A có trạng thái ACCEPTED/REQUEST CHANGES. Không dùng một chữ PASS cho cả hai. Mỗi lần sửa do review đổi revision `r1 → r2`, giữ evidence và lỗi lần trước; head đổi thì CI/review của head cũ không thay kết quả mới. Không cần chạy full benchmark cho sửa tài liệu nhưng required GitHub CI vẫn chạy theo workflow hiện tại.

Candidate SHA cuối và quyết định A có thể ghi trên PR body/comment, tránh commit chỉ để cập nhật SHA của chính file contract. Không tick A đã nghiệm thu thay A. Thay đổi API/schema/event/transaction boundary phải cập nhật contract/ADR trước khi triển khai phần phụ thuộc.

## 7. Checklist và contract theo đợt sửa

### C00 — Baseline có thể tái lập

**C00-TEST: A đã có evidence. C00-B: B hoàn thiện baseline tải; A nghiệm thu và merge.** Đầu vào benchmark là main sau CI bootstrap, trước thay đổi kiến trúc/đường ghi tiền. Số liệu Phase 1 chỉ là tham chiếu lịch sử, không thay baseline mới.

- [ ] Ghi commit gốc, JDK, Gradle, MySQL, CPU/RAM, cấu hình app/DB/load generator.
- [ ] Kiểm kê endpoint, payload, status code và các test hiện có; ghi rõ test mock và DB thật.
- [ ] Chạy test hiện tại; phân loại lỗi môi trường và lỗi code, không âm thầm bỏ test.
- [ ] Tạo seed có thể reset trên DB test riêng; không chạy reset trên dữ liệu người dùng.
- [ ] Tách load generator và SUT, hoặc công bố rõ hạn chế nếu chưa có máy riêng.
- [ ] Lưu baseline workload, latency p50/p95/p99, achieved RPS, lỗi theo nhóm, tài nguyên.

**Contract:** cùng commit/config/seed chạy lại được các bước; số liệu có raw output. Test chưa chạy phải ghi NOT RUN. Gate chưa yêu cầu Phase 1 pass toàn bộ, nhưng mọi failure phải được liệt kê và có ticket xử lý. Rollback: không thay đổi hành vi ứng dụng.

### C01 — ADR và module contracts

**Owner A; A tự kiểm tra và nghiệm thu, không cần B review.**

- [ ] ADR-001: Modular Monolith, dependency graph và mức Spring coupling cho application.
- [ ] ADR-002: transaction owner, retry boundary và chiến lược khóa ban đầu.
- [ ] ADR-003: idempotency lifecycle và compatibility policy.
- [ ] ADR-004: outbox/inbox delivery semantics, lease và event versioning.
- [ ] Chốt Java public interfaces, quyền sở hữu bảng, error code và event schema.

**Contract:** mỗi bảng có một owner; graph không có cycle; mọi thao tác tiền có chủ transaction; không có lời hứa exactly-once gửi ra hệ thống ngoài. Reviewer đối chiếu ít nhất một luồng topup, transfer, unlink và register. Gate này nghiệm thu thiết kế, chưa tuyên bố code thực hiện đúng.

### C02 — Migration và nền kiểm thử DB/CI

**C02-CI: A đã triển khai và merge. C02-B: B phụ trách migration/DB test còn lại, A nghiệm thu và merge.**

- [x] CI bootstrap chạy build/test với MySQL riêng; hai ruleset bảo vệ main đã active, có runbook/evidence.
- [ ] Thêm Flyway hoặc Liquibase, chọn một công cụ.
- [ ] Baseline schema Phase 1 được quản lý rõ; không tự động đánh baseline một DB bất kỳ mà không xác minh schema.
- [ ] Tách config test/dev/production; production dùng validate thay vì update.
- [ ] Testcontainers MySQL hoặc DB test biệt lập tương đương; CI không dùng DB cá nhân.
- [x] CI chạy clean build và bộ unit/web/context hiện tại, lưu artifact test/JAR.
- [x] Bổ sung DB integration tests nghiệp vụ ở C02-B và architecture tests ở C03; C03-B có negative fixtures chứng minh rule bắt import nội bộ và cycle.
- [ ] Loại bỏ xung đột giữa SQL init của Docker và migration owner; test cả volume mới và DB đã tồn tại.

**Contract:** DB trống khởi tạo được; DB Phase 1 có user/ví/liên kết/giao dịch nâng cấp không mất giá trị; restart không chạy lặp migration. Schema không tương thích phải fail rõ. Rollback ứng dụng chỉ khi schema tương thích; migration đã áp dụng được sửa bằng migration mới, không sửa file cũ.

### C03 — Modular hóa không đổi hành vi

**C03-B: B làm Identity/platform + architecture tests trước. C03-A: A làm Wallet/Banking/Payment sau. Tách PR, A tự kiểm tra phần A và review/merge phần B.**

- [ ] Di chuyển từng module, giữ hexagonal bên trong; mỗi lần di chuyển build/test trước khi sang module tiếp.
- [ ] Thay import trực tiếp bằng public module API.
- [ ] Giữ REST path/body/status Phase 1 ở bước này.
- [ ] Domain không import Spring/JPA; không expose JPA entity qua module API.
- [ ] Architecture tests bảo vệ dependency graph, repository ownership và cycle.
- [ ] Cập nhật sơ đồ kiến trúc theo code cuối.

**Contract:** regression API đã chốt C00 pass; chuyển tiền vẫn nguyên tử trên DB; test cố tình import internals phải bị architecture rule phát hiện. Không tạo migration đổi dữ liệu chỉ để đổi package. Rollback: revert refactor commit khi schema chưa đổi.

### C04 — Idempotency bền vững

**C04-A: A code và viết test idempotency, tự nghiệm thu/merge. Dùng DB harness đã merge của B; không cần B review.**

- [ ] Bảng unique `(actor_id, operation, idempotency_key)`, request hash, response snapshot cần replay và transaction reference.
- [ ] Header bắt buộc cho API ghi tiền ở release Phase 2; thiếu/không hợp lệ trả 400 với mã lỗi ổn định. Đây là breaking change có chủ đích: cập nhật OpenAPI, client test và k6; nếu cần client cũ phải thiết kế version API riêng trước triển khai.
- [ ] Hash dùng request đã chuẩn hóa, gồm source wallet, target/bank, amount, description theo policy; actor lấy từ authentication.
- [ ] Same key/same payload replay status và business response đã lưu; không yêu cầu trace id/timestamp hạ tầng mới phải giống response cũ.
- [ ] Same key/different payload trả 409 `IDEMPOTENCY_KEY_REUSED`.
- [ ] Request đang chạy chờ có giới hạn hoặc trả 409 `REQUEST_IN_PROGRESS` và hướng dẫn retry cùng key; không chạy giao dịch thứ hai.
- [ ] Đối với transaction đồng bộ: claim key và hoàn tất cùng transaction; rollback không để key thành công giả. Duplicate unique được xử lý sau khi transaction lỗi đã rollback.
- [ ] Chỉ lưu success đã commit trong scope này; business failure không cache. Ghi rõ retry một business failure sau thay đổi trạng thái có thể cho kết quả khác.
- [ ] Giữ key trong toàn bộ thời gian Phase 2, chưa triển khai cleanup; thiết kế retention sau phải xét thời hạn retry và rủi ro tái xử lý.

**Contract:** QA01/QA04 pass trên hai instance cùng DB; khác actor hoặc operation không nhầm replay; hai payload giống nhau với hai key khác nhau là hai yêu cầu. Replay không thêm transaction/outbox. Rollback: dừng traffic ghi trước khi hạ về bản chưa hỗ trợ idempotency; không drop bảng hoặc giả định bản cũ giữ được guarantee này.

### C05 — Money invariants, atomicity và concurrency

**Owner A; A tự kiểm tra và nghiệm thu, không cần B review.**

- [ ] Validation amount tại use case/domain; không chỉ dựa `@Valid` ở controller.
- [ ] Kiểm tra overflow khi credit; quy định giới hạn giao dịch/số dư trong contract cấu hình.
- [ ] Kiểm tra ví ACTIVE, quyền sở hữu và liên kết ngân hàng ACTIVE.
- [ ] Unlink và topup/withdraw có điểm tuần tự hóa rõ: khóa cùng bản ghi bank link trong transaction, hoặc thiết kế tương đương được chứng minh.
- [ ] Retry optimistic conflict/deadlock tối đa 3 attempts tổng cộng; backoff có jitter và giới hạn thời gian; không retry lỗi nghiệp vụ.
- [ ] Mỗi attempt transaction mới; hết retry trả lỗi conflict retryable có machine code riêng; deadlock/lock timeout không bị gộp nhầm thành thiếu tiền.
- [ ] Nếu thử pessimistic locking, lock ví theo id tăng dần rồi kiểm tra balance/status; chỉ thay mặc định sau benchmark so sánh.
- [ ] Fault hook chỉ bật ở test để fail sau debit, sau credit, sau ghi history và trước commit.

**Contract:** QA02/QA03 pass; thêm tổng chi nhỏ hơn balance, A↔B, hot receiver, unlink đồng thời và overflow. Đối soát DB sau mỗi test, không chỉ kiểm tra HTTP. Repeat bộ race ít nhất 100 vòng có timeout, synchronization barrier và ghi seed. Repetition là bổ sung, không thay cho thiết kế lịch chạy có kiểm soát. Khi lỗi, không sửa bằng cách nới invariant.

### C06 — Event contract và ghi outbox nguyên tử

**C06-B: B làm schema/storage sau khi A chốt event contract. C06-A: A làm publisher nguyên tử sau C06-B/C05-A. Hai PR riêng, A nghiệm thu và merge.**

- [ ] Event `payment.completed.v1`: eventId, transactionId/referenceCode, type, amount, source/target wallet IDs phù hợp, occurredAt, correlationId, schemaVersion; không kèm secret hoặc thông tin tài khoản đầy đủ.
- [ ] Mỗi transaction thành công tạo một event nghiệp vụ; unique theo transaction/event type để bảo vệ phát trùng do bug.
- [ ] Outbox có payload, status, attempts, nextAttemptAt, leaseUntil, claimToken/owner và lastError được giới hạn độ dài.
- [ ] Debit/credit/history/idempotency result/outbox commit cùng nhau.
- [ ] Chốt ordering: chưa bảo đảm thứ tự notification toàn cục; consumer không dựa delivery order để tính số dư.

**Contract:** giao dịch commit có đúng một outbox event; rollback ở mọi fault hook không để event mồ côi. Replay không thêm event. Payload được consumer test đọc đúng theo schema v1. Thay schema sau này phải có version/compatibility tests.

### C07 — Worker, inbox và phục hồi

**Owner B; reviewer A.**

- [ ] Worker claim theo batch trong transaction ngắn, có lease và claim token; không giữ khóa DB khi gọi tác vụ ngoài.
- [ ] Khi hoàn tất chỉ owner có token hiện tại được cập nhật claim; worker cũ không ghi đè lease mới.
- [ ] Lease hết hạn được thu hồi; timeout xử lý và thời hạn lease được cấu hình, có test clock hoặc fault orchestration.
- [ ] Với inbox cùng MySQL: insert inbox và đánh dấu outbox hoàn tất trong cùng transaction xử lý.
- [ ] Unique `(event_id, recipient_id, channel)`; chọn người nhận theo quy tắc từng loại giao dịch.
- [ ] Retry có backoff/jitter, giới hạn attempts, trạng thái DEAD và thao tác replay có audit.
- [ ] Có công tắc tắt worker, không tắt API ghi tiền.
- [ ] API đọc inbox có phân trang và kiểm tra caller; nếu chỉ demo nội bộ, vẫn test ownership tại service.

**Contract:** QA05/QA06 pass; hai worker không tạo inbox trùng; kill sau claim, sau insert và trước mark done đều phục hồi đúng. Poison event vào DEAD không chặn event khác. Replay DEAD không tạo inbox trùng. Kênh email/SMS không thuộc guarantee nghiệm thu.

### C08 — Query lịch sử có giới hạn

**Owner A; A tự kiểm tra và nghiệm thu, không cần B review.**

- [ ] Tách query service khỏi command execution, vẫn cùng DB; chưa xây projection bất đồng bộ.
- [ ] Cursor `(created_at,id)`, page size mặc định 20 và tối đa 100; xác thực cursor và filter.
- [ ] Chọn version API hoặc compatibility adapter vì chuyển List thành page là thay đổi contract.
- [ ] Kiểm tra EXPLAIN trên seed lịch sử lớn trước khi thêm index.
- [ ] Đo riêng truy vấn OR hiện tại với phương án hai nhánh, chọn dựa dữ liệu.

**Contract:** quyền đọc đúng; nhiều transaction cùng timestamp không lặp/mất bản ghi khi duyệt dataset cố định; ghi mới trong lúc phân trang có semantics được mô tả; không tải toàn lịch sử vào memory. Báo số liệu trên seed ít nhất 100.000 giao dịch và ghi phần chi phí index khi ghi tiền.

### C09 — Security và observability

**C09-A: A làm ownership/security và log nghiệp vụ. C09-B: B làm config/metrics/health/log infrastructure. Hai PR riêng; A nghiệm thu và merge.**

- [ ] Production thiếu JWT secret thì startup fail; test/dev có secret riêng.
- [ ] Generic 500 không lộ `ex.getMessage()`; log server có trace id và stack trace đã kiểm soát dữ liệu nhạy cảm.
- [ ] Correlation id được tạo/validate ở ingress; log nối được API, retry, transaction và event.
- [ ] Metrics: duration/status theo endpoint, conflict/deadlock/retry, replay, pool DB, outbox pending/oldest age/DEAD, reconciliation findings.
- [ ] Không dùng userId/transactionId/idempotency key làm metrics label gây cardinality lớn.
- [ ] Health/readiness phân biệt app/DB; worker lỗi được cảnh báo riêng, không restart app liên tục chỉ vì có DEAD event.
- [ ] Log và dashboard không public qua cấu hình mặc định.

**Contract:** QA10 pass; tái hiện 500 thấy trace id nhưng không thấy nội bộ ở response; truy được retry và event từ log; secret không có trong artifact. Worker ngừng tạo tín hiệu quan sát rõ và Payment vẫn phục vụ khi DB còn hoạt động.

### C10 — Đối soát độc lập

**Owner B; reviewer A.**

- [ ] Baseline số dư đầu kỳ theo wallet tại cutoff nhất quán trước khi mở traffic Phase 2; không mặc định ví cũ có số dư 0.
- [ ] Expected balance = opening balance + topup + transfer-in − withdraw − transfer-out sau cutoff; chỉ tính giao dịch thành công đã commit.
- [ ] Đọc balance và transactions trong cùng consistent snapshot của MySQL hoặc cơ chế cutoff tương đương; không báo sai do đọc hai thời điểm.
- [ ] Báo runId, cutoff, wallet, expected/actual/delta; ghi kết quả đối soát, không tự động sửa tiền.
- [ ] Có test dữ liệu hợp lệ, sai balance, thiếu history và trường hợp opening balance khác 0.

**Contract:** QA09 pass; chạy trong lúc có giao dịch không báo sai do snapshot lệch. Công bố giới hạn: nếu tiền và history cùng sai theo cách khớp nhau, phép đối soát này có thể không phát hiện; nó không thay thế sổ kép hoặc đối soát ngân hàng độc lập.

### C11 — Benchmark và chiến dịch lỗi

**Owner B; A hỗ trợ phân tích/sửa lỗi; reviewer A.**

- [ ] Giữ workload/config C00; điều chỉnh script chỉ để đáp ứng contract mới và ghi rõ khác biệt.
- [ ] Chạy warm-up, baseline, step load, hot-wallet, duplicate burst, worker outage, restart và hai instance.
- [ ] Mỗi bài hiệu năng chính chạy ít nhất 3 lần; báo từng lần và độ biến động, không chỉ lấy lần đẹp nhất.
- [ ] Thu raw metrics, achieved RPS, p95/p99, expected business errors, conflict/deadlock/5xx/timeouts và dropped iterations.
- [ ] Đối soát DB sau tải; kiểm tra count unique theo key/event và tổng tiền có tính topup/withdraw.
- [ ] So sánh optimistic retry/pessimistic nếu được thử; ghi trade-off, không chỉ tỷ lệ 409.

**Contract:** QA01–QA10 có evidence hoặc lý do NOT RUN; gate bắt buộc chưa chạy thì chưa release. QA08 vượt budget phải phân tích và sửa hoặc xin quyết định thay đổi mục tiêu được ghi nhận, không tự đổi threshold sau khi thấy số đo.

### C12 — Release và bàn giao

**A chủ trì release và nghiệm thu cuối; B bàn giao phần việc và evidence của B. Không yêu cầu B ký PR của A.**

- [ ] Tất cả contract bắt buộc PASS; danh sách lỗi tồn có mức độ và người chịu trách nhiệm.
- [ ] Runbook deploy/migration, bật/tắt worker, replay DEAD, đối soát, backup/restore và rollback.
- [ ] Thử nâng cấp DB Phase 1 có seed; smoke test auth/ví/link bank/ba loại giao dịch/history/inbox.
- [ ] Thử phục hồi backup trên DB biệt lập và kiểm tra dữ liệu, không chỉ xác nhận file backup tồn tại.
- [ ] OpenAPI, architecture, event schema, ADR và báo cáo trước–sau đúng code cuối.
- [ ] Ghi rõ phạm vi demo, ngân hàng mô phỏng và giới hạn thông báo/đối soát.

**Contract release:** không còn lỗi có thể gây sai tiền, truy cập chéo tài khoản, mất event đã commit hoặc migration mất dữ liệu; toàn bộ QA bắt buộc có bằng chứng; A ký nghiệm thu và quyết định release; B ghi nhận bàn giao phần mình. Không coi tỷ lệ request thành công cao là thay thế cho invariant.

## 8. Quy trình code, GitHub CI và merge

### 8.1. Luồng PR của B

1. B nhận ticket/contract đã được A chốt; tạo branch riêng từ main mới nhất.
2. B code và chạy checks local tương ứng trước mỗi push; mở PR vào main, ghi contract và evidence.
3. Mỗi lần push phải kích hoạt GitHub CI. CI FAIL thì B sửa trên branch đó; CI đang chạy/FAIL/CANCELLED hoặc thiếu check bắt buộc thì chưa được merge.
4. Khi required checks PASS trên head SHA mới nhất, B bàn giao PR cho A review.
5. A yêu cầu sửa nếu cần; mỗi lần sửa phải chạy lại CI. Review hoặc CI của commit cũ không đủ nghiệm thu commit mới.
6. A kiểm tra contract, kết quả CI và diff cuối, rồi tự quyết định merge. B không tự merge, bật auto-merge hoặc push trực tiếp main.

### 8.2. Luồng PR của A

1. A tạo branch riêng, code, tự kiểm tra và chạy checks local trước push.
2. A mở PR và chờ GitHub required checks PASS trên head SHA mới nhất.
3. A tự kiểm tra diff/contract/evidence, xử lý mọi lỗi rồi quyết định merge. B không cần review, approve, chạy lại test hoặc ký PR này.
4. Contract ghi rõ A vừa là tác giả vừa tự nghiệm thu; không mô tả đây là review độc lập.

### 8.3. Checklist mỗi lần push và merge

Cấu hình đã hoạt động: ruleset `main-required-pr-and-ci` (27444067) không có bypass; `main-only-Mango-Bird-can-merge` (27444068) chỉ cho User Mango-Bird cập nhật qua PR. Chi tiết trong [runbook](phase2/runbooks/github-governance.md).

**Trước push — owner A hoặc B:**

- [ ] Branch từ main mới; đúng ticket/scope, không có secret hoặc artifact không cần thiết.
- [ ] Chạy local checks phù hợp, ghi PASS/FAIL/NOT RUN và lý do thực tế.
- [ ] Cập nhật contract: API/schema/event, dependency SHA, test cases và rollback.

**Sau mỗi push — owner theo dõi tới kết quả:**

- [ ] Mở/cập nhật PR vào main; check `Backend build and test` phải xuất hiện, kể cả docs-only.
- [ ] CI phải SUCCESS trên head SHA mới nhất; không coi pending/cancelled/skip/neutral/missing là bằng chứng PASS theo contract nhóm.
- [ ] Lưu run URL, head SHA, test counts và artifact liên quan. Lỗi thì sửa, push lại và kiểm tra run mới.
- [ ] B chỉ chuyển trạng thái READY FOR A khi evidence đủ; B không bấm merge.

**Trước merge — chỉ A:**

- [ ] Kiểm tra diff/contract/evidence; tự kiểm tra PR A, review PR B; không yêu cầu B ký PR A.
- [ ] Branch up-to-date theo main và CI cuối PASS; thay đổi SHA hoặc base làm gate cũ hết hiệu lực, chạy/kiểm tra lại theo GitHub.
- [ ] Resolve conversations và xử lý mọi lỗi blocker.
- [ ] Ghi ACCEPTED hoặc REQUEST CHANGES trong PR, gắn candidate SHA. B không thể ghi ACCEPTED thay A.
- [ ] Merge qua PR; nếu GitHub hiện `Bypass rules and merge`, chỉ dùng quyền vượt rule hạn chế người cập nhật. Không sửa/tắt ruleset CI để né check.
- [ ] Ghi merge SHA/URL, kiểm tra CI trên main; không coi một tick trong file contract là bằng chứng cho commit thay đổi sau đó.

**Sau merge nếu CI main FAIL:** A tạm dừng tích hợp ticket phụ thuộc, quyết định fix-forward hoặc revert qua PR; owner sửa phần mình. B chỉ sửa hạ tầng khi lỗi thuộc hạ tầng, không phải review code A. Không tắt CI để tiếp tục release.

Bảo trì quyền: A giữ quyền sửa ruleset, không cấp credential A cho B. Hiện chưa test thực tế bằng credential B; khi B được onboard, xác minh push feature branch được và update main bị chặn. Không thay required-check name hoặc bypass actors nếu A chưa chốt.

Mức kiểm tra: docs kiểm tra link/nội dung; domain chạy unit và DB cases liên quan; schema/transaction/worker chạy integration/failure cases; query/pool/locking chạy benchmark liên quan; release chạy toàn bộ gate. B không phải reviewer dự phòng của A.

## 9. Cấu trúc evidence và bảng theo dõi

```text
digitalwallet-docs/phase2/
  adr/ADR-001-....md
  contracts/C00-r1.md ... C12-rN.md
  evidence/Cxx/<commit-sha>/README.md
  evidence/Cxx/<commit-sha>/test-results/
  evidence/Cxx/<commit-sha>/metrics/
  benchmark/baseline.md
  benchmark/comparison.md
  runbooks/deployment.md
  runbooks/recovery.md
  acceptance.md
```

Không commit dump chứa secret/PII hoặc artifact tải quá lớn; dùng seed tổng hợp và đường dẫn lưu artifact phù hợp. Mỗi evidence README ghi rõ dữ liệu đã được ẩn và phần chưa đo.

| Hạng mục | Owner | Trạng thái có bằng chứng | Bước tiếp theo / người nghiệm thu |
|---|---|---|---|
| C00-TEST | A | VERIFIED: baseline local 111/13 lỗi; sau sửa GitHub 111 PASS | Giữ evidence, A xác nhận phạm vi test; không suy ra benchmark hoàn tất |
| C00-B | B | MERGED (PR #6) | Baseline tại `benchmark/baseline.md`; giữ nguyên raw/evidence cho lần so sánh sau |
| C01-A | A | MERGED (PR #5) | Public contracts v1; revision theo từng task được ghi rõ trong contract |
| C02-CI | A | MERGED + VERIFIED | CI/rulesets hoạt động; bảo trì theo mỗi SHA |
| C02-B | B | MERGED (PR #7) | Flyway V1 + MySQL harness; migrations đã applied không được sửa |
| C03-B / C03-A | B rồi A | C03-B MERGED (PR #8); C03-A LOCAL VERIFIED / CHỜ CI + A | Native modules; bridge/exceptions đã gỡ; 128 Java + 18 Python PASS, MySQL fault-proof rerun 6/6 PASS; xem C03-A-r1 và PR candidate |
| C04-A / C05-A | A | TODO | Idempotency và concurrency, A tự kiểm tra |
| C06-B / C06-A | B rồi A | TODO | Storage rồi publisher, A nghiệm thu |
| C07-B | B | TODO | Worker/inbox/recovery, A review |
| C08-A | A | TODO | Query/history, A tự kiểm tra |
| C09-A / C09-B | A / B | TODO | Security / observability, A nghiệm thu |
| C10-B / C11-B | B | TODO | Reconciliation / benchmark cuối, A review |
| C12-B / C12-A | B / A | TODO | B bàn giao vận hành, A release |

Trạng thái triển khai: TODO → READY → IN PROGRESS → CI RUNNING → READY FOR A → ACCEPTED BY A → MERGED. PR A vẫn ghi rõ tự kiểm tra, không có bước review của B. FAIL/REQUEST CHANGES quay lại IN PROGRESS; BLOCKED ghi dependency và điều kiện mở chặn. VERIFIED chỉ nói bằng chứng kỹ thuật, không tự thay ACCEPTED BY A. Các checkbox mẫu mặc định chưa tick; chỉ tick trong contract của candidate đã kiểm tra. Không đánh toàn bộ C00/C02 hoàn tất khi mới xong test/CI.

## 10. Rủi ro và phương án thu gọn

| Rủi ro | Cách kiểm soát |
|---|---|
| Hai người refactor trùng file | Chia ownership, merge module tuần tự, khóa contract trước khi song song |
| Idempotency chỉ hoạt động một instance | Unique DB và test hai instance, không dựa khóa JVM |
| Retry chạy trong transaction đã rollback-only | Tách facade/executor và test fault thực trên MySQL |
| Worker nhận trùng hoặc lease hết hạn khi đang xử lý | Claim token, lease recovery và inbox unique; fault tests hai worker |
| Đối soát báo sai khi còn traffic | Consistent snapshot và opening baseline rõ |
| Tăng nhiều hạ tầng nhưng không đủ thời gian chứng minh | Ưu tiên C00–C07 và C09/C11/C12; giảm dashboard/phạm vi query/đối soát theo quyết định scope trước triển khai |

Nếu phải thu gọn, không bỏ idempotency, money invariants, atomicity, test DB hoặc kiểm tra quyền. Module Reconciliation có thể chỉ là job chạy thủ công; worker và inbox dùng MySQL hiện tại. Mọi thay đổi phạm vi cập nhật lại bảng QA và điều kiện release trước khi đánh giá cuối.
