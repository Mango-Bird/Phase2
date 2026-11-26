# C01-A — Architecture & public contracts v1

Ngày: 17/11/2026. Owner: A / Mango-Bird, triển khai tài liệu qua Codex. Phiên bản thiết kế: `phase2-contracts.v1`.

Đây là baseline thiết kế của C01-A, có hiệu lực triển khai sau khi A review/merge PR này. Không phải mô tả code đã refactor hoặc xác nhận QA runtime đã PASS. B không cần approve PR A. Thay đổi contract sau khi merge phải có revision, impact và thứ tự migration/merge do A chốt.

## 1. Quyết định phạm vi

- Một executable, một MySQL 8.4/InnoDB, một datasource/transaction manager; module theo nghiệp vụ, hexagonal bên trong.
- Giữ quan hệ một user–một ví và đơn vị VND integer trong Phase 2. Không thêm FX, broker hoặc ngân hàng thật.
- Application được phép dùng Spring transaction/service; domain không import Spring/JPA. Public DTO không chứa domain/JPA entity hoặc Spring Data Page.
- Không thêm event sourcing hoặc sổ kép; transactions là lịch sử nghiệp vụ có thể đối soát, không phải ledger kế toán hoàn chỉnh.

## 2. Dependency graph và data ownership

| Module | Được gọi public API của | Dữ liệu được ghi | Owner code |
|---|---|---|---|
| identity | platform contracts | users | B |
| wallet | platform contracts | wallets | A |
| banking | wallet query API, platform contracts | linked_bank_accounts | A |
| payment | wallet posting/query API, banking eligibility API, platform outbox append port | transactions, idempotency_requests | A |
| notification | platform event contract | notifications | B |
| reconciliation | wallet/payment reconciliation read API | reconciliation_runs, reconciliation_findings, reconciliation_openings | B |
| onboarding (composition use case) | identity registration API, wallet creation API | Qua API owner, không repository riêng | B trong C03-B; A chốt contract |
| platform | Không gọi nghiệp vụ trực tiếp | outbox_events qua outbox adapter; config/metrics/security | B |

Root `bootstrap`/Spring composition wiring có thể import API và implementation để tạo beans, không chứa nghiệp vụ hay ghi DB trực tiếp. `onboarding` là package use case điều phối riêng ở composition layer, không đặt trong identity hoặc wallet để tránh cycle. Các module không import internals của platform; hạ tầng có thể implement port nghiệp vụ qua wiring root nhưng không gọi ngược module để xử lý nghiệp vụ.

Outbox worker trong platform gọi `EventHandlerPort` do Notification adapter implement, thông qua bean wiring. Platform không import Notification API; Notification chỉ phụ thuộc `PaymentCompletedV1` ở `platform.api.events`. Payment dựng event DTO từ dữ liệu nghiệp vụ, gọi append port; không dùng worker hoặc Notification trực tiếp.

Database FK được phép trỏ sang bảng module khác; không đồng nghĩa cho phép JPA relationship xuyên module. Chỉ lưu scalar IDs; không expose lazy entity, repository hoặc transaction object qua API.

### 2.1. Quy tắc ArchUnit tại C03

- Không cycle giữa module; chỉ API packages được import qua ranh giới.
- Cấm import repository/entity/adapter/application/domain của module khác; test code được tổ chức riêng, không được loại bỏ kiểm tra toàn bộ production graph.
- `domain..` không phụ thuộc Spring/JPA; `api..` không phụ thuộc adapter/JPA hoặc Spring Data.
- `onboarding` chỉ điều phối identity/wallet APIs; wallet không import identity; identity không import wallet.
- Bootstrap chỉ wiring; boundary này cần kiểm tra code review ngoài architecture tests.
- Chỉ Payment được import WalletPostingApi và BankEligibilityApi; chỉ Onboarding được import IdentityRegistrationApi/WalletCreationApi. Banking và Reconciliation chỉ dùng read APIs được cho phép, không gọi posting.
- C03-B ghi ngoại lệ chuyển tiếp theo package cụ thể để app còn chạy; C03-A phải gỡ ngoại lệ khi Payment/Wallet/Banking đã di chuyển. Không release khi còn allow-all cho modules đích.

C03-A đóng giai đoạn chuyển tiếp: `WalletCreationService` dùng persistence thuộc Wallet; không còn `wallet.adapter.legacy` hoặc business packages `application/domain/adapter` ở root. Hai exception maps của architecture policy rỗng. Web handler nằm trong Platform và chỉ nhận `platform.api.error.ModuleFailure`; Identity/Wallet/Banking publish lỗi kế thừa contract này, nên Platform không phụ thuộc ngược các module nghiệp vụ.

Architecture gate kiểm tra cả production graph, cấm tái đưa business class về root hoặc package ngoài module bất kỳ dù class chưa có dependency. Revision C03-A-r2 chỉ chấp nhận ngoài module đúng entry point `DigitalwalletSystemApplication` và namespaces hạ tầng `bootstrap`/`migration`; có negative fixtures tại root và service, positive fixtures cho hạ tầng hợp lệ. Chỉ Payment được dùng `WalletPostingApi` và `BankEligibilityApi`; owner được implement chính API của mình. Chỉ Onboarding ngoài owner được dùng hai creation APIs. Negative fixtures chạy chung rule engine để chứng minh các giới hạn này.

### 2.2. Revision C03-A-r1 — triển khai theo từng task

Các chữ ký ở mục 3 là đích toàn Phase 2. C03-A triển khai `WalletQueryApi`, `WalletPostingApi`, native `WalletCreationApi` và immutable records `PostingWallet/BalanceChange`; không thay HTTP. `BalanceChange(walletId, expectedVersion, expectedBalance, newBalance)` dùng số dư tuyệt đối từ snapshot; owner reload, kiểm tra snapshot rồi ghi bằng JPA `@Version`, trong transaction bắt buộc của Payment. Một lần gọi không tự commit, không retry.

C03/C04 đã publish `BankAccountQueryApi.requireActiveAccount(linkedBankId,walletId)` làm seam chưa khóa row. C05-A thay seam này bằng `BankEligibilityApi.requireActiveForPosting`: scoped ownership + current PESSIMISTIC_WRITE refresh, giữ lock tới commit/rollback; unlink dùng cùng port. Read-only seam cũ được gỡ, không giữ hai đường eligibility khác nhau. Internal BankLink và Payment use-case ports chỉ được controller cùng module gọi, chưa phải public contracts cho module khác. Payment command nhận actor do auth adapter cấp, không nhận source wallet từ body.

`PaymentCommandApi` kèm key/receipt được publish tại C04-A (xem C04-A-r1); history API có cursor tại C08-A. Không tạo tham số key bị bỏ qua hoặc lớp receipt giả tại C03. C06/C10 vẫn phải dùng các public event/read APIs được chốt ở mục 3 khi triển khai; không import use case/domain/persistence hiện tại. Revision này không thay thứ tự migration, invariant hoặc acceptance gates của các task sau.

HTTP timestamps giữ định dạng LocalDateTime cũ; Wallet public view theo cách diễn giải UTC đã có ở C03-B, HTTP adapter chuyển ngược cùng ZoneOffset để không đổi giá trị hiển thị. Không cập nhật hay gắn lại timezone dữ liệu lịch sử.

### 2.3. Revision C05-A-r1 — fresh attempt và bank serialization

MoneyRules kiểm tra positive/range/exact arithmetic trước writes; Wallet owner vẫn versioned optimistic, flush mỗi thay đổi theo ID tăng dần. Facade NEVER retry tối đa 3 attempts, chỉ optimistic/1213+40001, sleep sau rollback (25–50/50–100 ms). Business errors và lock timeout 1205 không implicit retry, kể cả Connector/J báo state 40001.

Payment executor/write use cases và bank write entrypoints dùng READ_COMMITTED; open-in-view=false để không reuse L1 cache/request context qua attempt. Default datasource/read-only/reconciliation snapshot policy không đổi. Bank row current refresh + lock được dùng chung giữa eligibility/unlink; không có Wallet pessimistic strategy hoặc JVM lock mới. Hash/receipt/schema v1/v2 và replay protocol C04 giữ nguyên. Chi tiết/evidence theo C05-A-r1 và PR candidate, A nghiệm thu trước merge.

## 3. Java public interfaces — chữ ký đích

Các chữ ký sau là contract thiết kế, chưa tạo `.java` ở C01. Package đầy đủ theo `<module>.api`; thời gian đích dùng `Instant`, ID `long`, amount `long`, DTO immutable records. Enum nghiệp vụ được publish dưới API module owner; domain có thể map nội bộ, không phụ thuộc adapter.

```java
// identity.api — trả DTO an toàn, không trả password hash
interface IdentityRegistrationApi {
    UserView createUser(RegisterUserInput input); // tham gia transaction onboarding
}
interface IdentityAuthApi {
    AuthResult login(LoginInput input);
}
// onboarding.api — HTTP register gọi interface này
interface RegisterAccountApi {
    RegisteredAccount register(RegisterUserInput input);
}

// wallet.api
interface WalletCreationApi {
    WalletView createInitialWallet(long userId); // MANDATORY transaction
}
interface WalletQueryApi {
    WalletView getOwnedWallet(long actorUserId);
    WalletView getOwnedWalletById(long actorUserId, long walletId);
}
interface WalletPostingApi {
    PostingWallet loadOwnedForPosting(long actorUserId); // cả ví inactive
    PostingWallet loadTargetForPosting(String walletNumber);
    void applyBalanceChange(BalanceChange change); // MANDATORY; optimistic version
}
interface WalletReconciliationReadApi {
    List<WalletBalanceRow> scanBalances(long afterWalletId, int limit);
    // MANDATORY read transaction, keyset theo walletId; limit <= 1000
}

// banking.api
interface BankLinkApi {
    LinkedBankView link(long actorUserId, LinkBankInput input);
    List<LinkedBankView> listActive(long actorUserId);
    void unlink(long actorUserId, long linkedBankId);
}
interface BankEligibilityApi {
    EligibleBank requireActiveForPosting(long linkedBankId, long walletId);
    // MANDATORY; khóa bank-link row đến khi caller commit/rollback
}

// payment.api — API này nhận actor từ auth adapter, không từ HTTP body
interface PaymentCommandApi {
    PaymentReceipt topup(long actorUserId, String key, TopupInput input);
    PaymentReceipt withdraw(long actorUserId, String key, WithdrawInput input);
    PaymentReceipt transfer(long actorUserId, String key, TransferInput input);
}
interface PaymentQueryApi {
    PaymentView getOwned(long actorUserId, String referenceCode);
    PaymentSlice history(long actorUserId, HistoryQuery query);
}
interface PaymentReconciliationReadApi {
    long lastCommittedTransactionId(); // dùng trong lúc đã dừng/drain writers để tạo opening
    List<WalletDeltaRow> sumDeltas(List<Long> walletIds, long afterTransactionId);
    // MANDATORY, cùng read snapshot caller; walletIds <= 1000
}

// platform.api.outbox — implementation không mở transaction mới
interface OutboxAppendPort {
    void append(PaymentCompletedV1 event); // MANDATORY
}
interface EventHandlerPort {
    String eventType();
    void handle(EventEnvelope envelope); // worker transaction hiện tại
}

// notification.api
interface NotificationQueryApi {
    NotificationSlice inbox(long actorUserId, InboxQuery query);
}

// reconciliation.api — job nội bộ, không mở public admin REST ở Phase 2
interface RunReconciliationApi {
    ReconciliationReport run(ReconciliationInput input);
}
```

### 3.1. DTO bắt buộc và semantics

| DTO | Trường tối thiểu / quy tắc |
|---|---|
| RegisterUserInput | username, password, fullName, email; password chỉ đi qua auth/registration, không log/return |
| UserView | id, username, fullName, email, role, createdAt |
| RegisteredAccount | UserView + WalletView; HTTP register giữ response cũ nếu chưa version API |
| AuthResult | accessToken, tokenType, expiresIn; map theo HTTP response hiện tại ở C03 |
| WalletView | id, userId, walletNumber, balance, status, version, createdAt, updatedAt |
| PostingWallet | id, userId, walletNumber, balance, status, version; snapshot immutable, không JPA entity |
| BalanceChange | walletId, expectedVersion, expectedBalance, newBalance; cả balance/version từ snapshot vừa đọc |
| TopupInput / WithdrawInput | linkedBankAccountId, amount, description |
| TransferInput | targetWalletNumber, amount, description; source luôn resolve từ actor |
| EligibleBank | id, walletId, bankCode; không số tài khoản đầy đủ |
| LinkedBankView / LinkBankInput | Giữ trường hợp đồng Phase 1, mask chỉ để hiển thị; chưa xác minh ngân hàng thật |
| PaymentReceipt | httpStatus, responseVersion, immutable success body snapshot, referenceCode, replayed; adapter không tự dựng timestamp mới khi replay |
| PaymentView | Các trường TransactionResponse hiện tại; không expose idempotency key hoặc request hash |
| PaymentSlice | items, nextCursor nullable, hasMore; không total count bắt buộc |
| HistoryQuery / InboxQuery | cursor nullable, limit default 20/max 100; HistoryQuery có type/from/to |
| NotificationSlice | items, nextCursor, hasMore; NotificationView có id, eventId, transactionReference, kind, createdAt |
| WalletBalanceRow / WalletDeltaRow | walletId và balance/delta; tổng delta dùng BigInteger/DECIMAL đủ rộng, không cộng dồn bằng long gây overflow trung gian |
| ReconciliationInput/Report | baselineId, transaction watermark/snapshot policy, runId, totals, findings(expected/actual/delta); không tự sửa balance |

`applyBalanceChange` phải update dựa trên expectedVersion và expectedBalance; nếu row không còn khớp thì ném retryable optimistic conflict, không ghi đè snapshot cũ. Adapter có thể dùng JPA `@Version` với validate balance trước persist/flush; test phải chứng minh update stale không thành công. Không dùng bulk update bỏ qua version. Các ví được apply theo id tăng dần, không mặc định source trước target.

## 4. Transaction, tiền và lỗi cạnh tranh

### 4.1. Các transaction owner

| Use case | Chủ transaction | Thành phần cùng commit | Lỗi phải rollback |
|---|---|---|---|
| register | Onboarding application service | create user + initial wallet | username/email/wallet-number conflict, lỗi tạo ví |
| topup/withdraw/transfer | Payment executor, facade bên ngoài | idempotency claim/result + wallet changes + transaction + outbox (sau C06) | Mọi exception trước commit |
| unlink | Banking service | bank-link status/unlinkedAt | lock timeout hoặc lỗi persist |
| inbox consumption | Outbox worker processor | notification insert + outbox DONE | lỗi handler/mark done |
| reconciliation | Job đọc consistent snapshot | Đọc balance/history cùng snapshot; persist report riêng sau đọc | Không cập nhật ví dù phát hiện sai |

Facade payment không giữ transaction. Executor `REQUIRED` được gọi qua Spring proxy từ facade không transactional; mỗi attempt có transaction mới. Write APIs Wallet/Identity registration/Outbox dùng `MANDATORY` để fail nếu gọi ngoài transaction. Banking unlink/link là use case độc lập `REQUIRED`. Không `REQUIRES_NEW` trong phần ghi tiền.

### 4.2. Validation và lock order

1. Auth → validate kiểu/độ dài/body/amount và key → resolve wallet ID theo actor (kể cả ví inactive) → idempotency claim/replay.
2. Replay thành công trả snapshot cũ ngay; không revalidate balance/status/bank đã đổi sau giao dịch cũ. Authentication và quyền actor vẫn bắt buộc.
3. Với topup/withdraw mới: acquire bank-link row lock theo id, xác nhận thuộc wallet và ACTIVE; sau đó load wallet snapshot lại. Unlink cũng khóa đúng row trước đổi status.
4. Với transfer: load source/target snapshots; reject self-transfer, inactive/thiếu tiền; apply các ví theo id tăng dần. Optimistic version bảo vệ mọi balance/status change; nếu status/freeze được thêm sau này phải tăng version.
5. Amount 1..`Long.MAX_VALUE`, balance 0..`Long.MAX_VALUE`; VND không có phần thập phân. Dùng exact arithmetic cho credit/debit, overflow trả MONEY_OVERFLOW trước commit. Không tự thêm hạn mức thương mại/luật định ở C01; nếu cần config cap phải có ADR revision và test boundary.
6. Flush trước success return và chỉ trả HTTP sau khi transaction proxy commit thành công.

Retry tối đa 3 attempts tổng cộng cho optimistic conflict/MySQL deadlock được phân loại; delay trước attempt 2 là 25–50 ms, trước attempt 3 là 50–100 ms, jitter injectable trong test. Không retry business/validation/auth errors. Hết retry trả `CONCURRENT_UPDATE` 409, retryable=true. Bank-link lock timeout trả `RESOURCE_BUSY` 409, không retry ngầm. Claim-key contention timeout trả `REQUEST_IN_PROGRESS` 409, `Retry-After: 1`.

C02/C04 cấu hình `innodb_lock_wait_timeout=2` giây cho **pool test và app đích**, kiểm tra session thực tế và tính tác động cho mọi lock; không đổi global MySQL. Đây là thời gian chờ lock, không phải SLA tổng HTTP. Không dùng kết quả transport timeout để kết luận giao dịch thất bại; client retry cùng key.

## 5. HTTP compatibility và error contract

| Giai đoạn | Policy |
|---|---|
| C03 | Giữ `/api/v1` path/body/status và success/error shape hiện có; chỉ refactor bên trong |
| C04 | Giữ v1 topup/withdraw/transfer nhưng bắt buộc Idempotency-Key có chủ đích; cập nhật OpenAPI/tests/k6 cùng PR. Thêm `code`, `retryable`, `traceId` vào error model, giữ trường cũ |
| C08 | Giữ GET `/api/v1/transactions` list cho compatibility, đánh deprecated và không dùng để benchmark pagination. Thêm GET `/api/v2/transactions` cho slice/cursor và GET `/api/v2/transactions/{referenceCode}` có ownership |
| C07 | Thêm GET `/api/v2/notifications` với slice/cursor; không có public API replay DEAD |
| C09 | 500 message chung, traceId; không trả exception nội bộ. Auth errors cùng model, không lộ danh tính tài khoản |

Việc giữ history v1 không giải quyết truy vấn không giới hạn cho client cũ. Chỉ công bố mục tiêu bounded query cho v2; việc xóa/giới hạn v1 cần migration/deprecation contract riêng, không âm thầm truncate list.

| HTTP | code | Retry cùng key? |
|---|---|---|
| 400 | INVALID_REQUEST, INVALID_AMOUNT, MONEY_OVERFLOW, SAME_WALLET, WALLET_NOT_ACTIVE, BANK_NOT_ACTIVE, INSUFFICIENT_BALANCE | Không tự retry; request thất bại không cache business result |
| 400 | IDEMPOTENCY_KEY_REQUIRED / IDEMPOTENCY_KEY_INVALID | Sửa header |
| 401 | UNAUTHENTICATED | Auth lại; không đổi key nếu chưa biết kết quả payment |
| 404 | WALLET_NOT_FOUND, BANK_NOT_FOUND, TRANSACTION_NOT_FOUND | Không tự retry; tài nguyên không thuộc caller cũng trả 404 |
| 409 | IDEMPOTENCY_KEY_REUSED | Không retry payload khác với key đã commit |
| 409 | REQUEST_IN_PROGRESS | Có, chờ theo Retry-After |
| 409 | CONCURRENT_UPDATE, RESOURCE_BUSY | Có, backoff; không tự tạo key mới |
| 409 | USER_ALREADY_EXISTS | Không retry đăng ký nguyên trạng |
| 500 | INTERNAL_ERROR | Outcome có thể chưa rõ; payment retry cùng key |

Lỗi có cấu trúc nội bộ `ModuleFailure(code, retryable)` dưới API owner hoặc platform.api.error, không phụ thuộc HTTP. Web handler map code → status, trả `timestamp/status/error/message/path/errors/code/retryable/traceId`. Không catch `Exception` rồi commit kết quả giả trong executor.

## 6. Idempotency contract chi tiết

### 6.1. Key và hash

- Key case-sensitive ASCII `[A-Za-z0-9._:-]{1,64}`, không trim; sai khoảng trắng/Unicode/độ dài trả 400. DB key dùng binary/case-sensitive collation.
- Unique `(actor_id, operation, idempotency_key)`; operation enum TOPUP/WITHDRAW/TRANSFER. Key không phải referenceCode. Key khác nhau là hai ý định khác nhau dù payload giống.
- Reject JSON duplicate fields/unknown fields ở ba command endpoints tại C04, tránh hai parser hiểu payload khác nhau. Amount JSON integer, không chuỗi/floating-point/coercion/truncate.
- Description thiếu hoặc null chuẩn hóa thành null; chuỗi rỗng khác null. Giữ nguyên case/whitespace/Unicode của description, không Unicode normalization; validate tối đa 255 ký tự theo policy hiện tại.
- Target wallet number là chuỗi chính xác, không trim/casefold; reject leading/trailing whitespace. Actor và callerWalletId resolve bởi server; linkedBankId integer hoặc null theo operation.
- Canonical UTF-8 JSON **array**, không BOM, không spaces/newline, escape theo JSON chuẩn, không escape non-ASCII mặc định, số integer base10 không leading zeros; thứ tự cố định:

```text
["payment-request.v1",actorId,"OPERATION",callerWalletId,linkedBankIdOrNull,targetWalletNumberOrNull,amount,descriptionOrNull]
```

- SHA-256 trên bytes đó, hex lowercase 64 ký tự. Không hash raw JSON body; không đưa header/trace/timestamp/key vào hash. C04 phải có test vectors cross-parser cho null/empty/Unicode và field order.

### 6.2. Lifecycle

Trong transaction: insert PROCESSING → validate nghiệp vụ/ghi tiền/history → store response và COMPLETED → commit. PROCESSING không commit riêng, không có durable processing lease trong scope đồng bộ. Concurrent insert chờ unique lock của transaction đầu; sau duplicate rollback transaction hiện tại rồi đọc kết quả committed bằng transaction mới. Lock timeout là REQUEST_IN_PROGRESS, không phải hash mismatch khi chưa đọc được row.

COMPLETED + cùng hash replay snapshot success và status 200. COMPLETED + khác hash 409 KEY_REUSED. Nếu transaction đầu rollback thì row biến mất, request sau được xử lý mới. Vì không cache failed requests, key dùng ở một lần thất bại có thể dùng lại với payload khác khi chưa có row committed; guarantee mismatch áp dụng với key đã hoàn tất thành công.

Response snapshot lưu toàn bộ success body theo responseVersion v1, kể cả timestamp envelope/createdAt; chỉ transport headers như trace id có thể khác. Replay không thêm payment hoặc outbox và không rebuild response từ balance/status hiện tại. Không lưu access token/secret/request body nhạy cảm. Phase 2 chưa cleanup key; không thêm TTL ở worker.

## 7. Event và delivery contract

`PaymentCompletedV1` là public immutable DTO ở platform.api.events để Payment/Notification không phụ thuộc nhau. Type `payment.completed.v1`, schemaVersion=1. Payload:

```json
{
  "eventId": "UUID",
  "eventType": "payment.completed.v1",
  "schemaVersion": 1,
  "transactionId": 123,
  "referenceCode": "UUID",
  "type": "TRANSFER",
  "amount": 100,
  "currency": "VND",
  "sourceWalletId": 10,
  "sourceUserId": 1,
  "targetWalletId": 20,
  "targetUserId": 2,
  "occurredAt": "2026-11-17T00:00:00Z",
  "correlationId": "request-trace-id"
}
```

TOPUP source wallet/user null; WITHDRAW target wallet/user null. Recipient IDs lấy từ wallet snapshots đã xác thực; không lấy từ request body. Không đưa description/full name/bank account/token vào event. eventId/referenceCode là hai UUID riêng; occurredAt cùng instant nghiệp vụ của payment, mọi timestamp mới UTC.

Envelope storage thêm status/attempts/nextAttemptAt/leaseUntil/claimToken/lastError; metadata delivery không thuộc immutable payload. Unique `(aggregate_type,aggregate_id,event_type)` với aggregate PAYMENT/transactionId, không chỉ unique UUID.

- Recipient: TOPUP target user một inbox; WITHDRAW source user một inbox; TRANSFER source user SENT và target user RECEIVED. Unique `(event_id,recipient_id,channel)`; vì self-transfer bị cấm và 1 user–1 wallet nên không cần hai inbox cho một recipient/event.
- Inbox channel=IN_APP. At-least-once invocation, một logical inbox row nhờ unique; không đảm bảo thứ tự toàn cục, không dùng event để cập nhật balance.
- Worker mặc định batch=50, lease=30s, poll=1s, tối đa 5 attempts gồm lần đầu. Failure retry delays 1/2/4/8s + jitter 0..250ms, sau lần 5 sang DEAD. Test inject clock/jitter; tham số được phép tuning với evidence.
- Claim transaction ngắn dùng row locking và SKIP LOCKED; processing transaction xác minh token hiện tại, insert inbox và mark DONE cùng commit. Chỉ claim token hiện tại được mark done/fail; crash sau claim được reclaim khi lease hết hạn. Batch không được renew lease vô hạn; xử lý nhanh nội bộ, payload poison không chặn backlog.
- Attempts tăng khi claim thành công, kể cả attempt bị crash. Event hết lease mà đã dùng đủ 5 attempts chuyển DEAD có lỗi/audit, không bị bỏ mất; vận hành có thể replay giữ eventId. Claim token và lease phải hợp lệ tại lúc bắt đầu processing transaction, khóa row để reclaimer không đổi token giữa insert inbox và DONE.
- Error payload/version sai → retry hữu hạn rồi DEAD, không mark DONE khi bỏ qua event. Replay DEAD là công cụ vận hành nội bộ có audit, giữ eventId; không tạo inbox trùng.
- Mapping old schema khi nâng version phải được test; không sửa payload event đã commit. C06 test event schema producer–consumer bằng fixture chung, C07 test crash/two-worker.

## 8. Schema ownership và migration policy

Chọn **Flyway SQL migrations** ở C02-B. Một owner schema, production Hibernate validate; Docker init không tiếp tục tự tạo bảng khi Flyway đã sở hữu lifecycle. SQL dump Phase 1 hiện có chứa DROP TABLE, chỉ dùng tham chiếu/test seed, không copy nguyên vào production migration.

| Version reservation | Owner ticket | Thay đổi |
|---|---|---|
| V1 | B C02-B | Baseline 4 bảng Phase 1 bằng create statements không DROP/seed người dùng |
| V2 | A C04-A | idempotency_requests và constraints |
| V3 | B C06-B | outbox_events |
| V4 | B C07-B | notifications |
| V5 | A C08-A | RETIRED/không tạo: EXPLAIN + 100k evidence chọn existing indexes; không tái dùng slot này |
| V6 | B C10-B | reconciliation openings/runs/findings |

Revision C08-A: sau EXPLAIN/đo 100k rows, C08-A không thêm index và không tạo V5. Slot V5 retired trước khi có migration applied; C10-B V6 cần theo sau V4, không phải chờ một file V5. Index lịch sử trong tương lai phải nhận version mới cao hơn các version đã applied/reserved, không thêm V5 sau V6. Giữ out-of-order disabled và migration applied immutable. [Flyway versioned migration ordering](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html).

DB trống migrate từ V1. DB cũ chỉ được baseline V1 bằng thao tác có kiểm tra metadata/constraint/index và backup trước đó; `baselineOnMigrate=false`. Kiểm tra schema tương đương rồi baseline version 1 rõ ràng; schema khác phải từ chối. Migration đã applied là immutable, sửa bằng migration mới, không tự động clean/drop/undo dữ liệu.

### 8.1. Các bảng mới — contract logic tối thiểu

| Bảng | Các cột/quy tắc bắt buộc |
|---|---|
| idempotency_requests (Payment) | id BIGINT PK, actor_id, operation VARCHAR(16), key VARCHAR(64) binary collation, request_hash CHAR(64), status PROCESSING/COMPLETED, transaction_id nullable cho PROCESSING, response_version, response_status, response_body JSON, created_at/completed_at; unique actor/operation/key; COMPLETED bắt buộc response + transaction |
| outbox_events (Platform) | event_id CHAR(36) PK, aggregate_type/id, event_type, schema_version, payload JSON, status PENDING/CLAIMED/DONE/DEAD, attempts>=0, next_attempt_at, lease_until nullable, claim_token nullable, last_error nullable, created_at/processed_at; unique aggregate/type; index status/next_attempt_at/created_at và status/lease_until |
| notifications (Notification) | id BIGINT PK, event_id, recipient_id, channel, kind, transaction_reference, created_at; unique event/recipient/channel; index recipient/created_at/id; event_id FK outbox có thể dùng trong cùng DB nhưng không import JPA entity |
| reconciliation_openings (Reconciliation) | baseline_id, wallet_id, opening_balance, opening_last_transaction_id và captured_at; unique baseline/wallet; metadata baseline phải có trạng thái COMPLETE trước khi dùng |
| reconciliation_runs/findings | run_id, baseline/cutoff, started/completed/status/counts; findings run/wallet/expected/actual/delta; không FK cascade xóa lịch sử tiền |

Tất cả timestamp mới DATETIME(6) UTC theo app/DB session; không tự đổi precision cột cũ trong refactor C03. FK/index/check chi tiết DDL phải đối chiếu MySQL tại contract implementation; không coi bảng logic này là SQL chạy được. Không cascade delete user/wallet/history/idempotency/outbox để làm mất bằng chứng tiền.

C02 phải ghi timezone thực tế của dữ liệu legacy. Không tự gắn `Z` vào DATETIME cũ khi chưa biết nguồn timezone; giữ HTTP timestamp format hiện tại ở C03 bằng adapter, mọi chuyển đổi dữ liệu thời gian cần migration contract riêng.

### 8.2. Opening baseline và snapshot đối soát

Phase 2 chọn opening **trong maintenance window**: dừng nhận thao tác ghi tiền/đăng ký ví trên tất cả app instance, drain toàn bộ transaction đang chạy, mở consistent snapshot, lấy balances cùng `max(transaction.id)`, lưu opening và COMPLETE baseline rồi mới mở writers. Không dùng timestamp đơn thuần hoặc max ID khi còn transaction đang chạy vì transaction có thể được cấp ID/createdAt trước nhưng commit sau cutoff.

Mỗi run sau đó mở một read transaction REPEATABLE_READ/snapshot duy nhất; các API đọc ở trên tham gia cùng transaction. Expected balance = opening + tổng signed delta của history có id lớn hơn opening watermark và visible trong cùng snapshot. TOPUP +target, WITHDRAW −source, TRANSFER −source/+target; không tự loại transaction chưa có status vì bảng hiện tại chỉ ghi successful committed history.

Ví được tạo sau opening có opening=0 theo createInitialWallet contract và chỉ hợp lệ nếu created-after-baseline được xác minh; thiếu opening cho ví đã tồn tại là lỗi baseline, không âm thầm coi bằng 0. Không reset/reuse transaction IDs hoặc ghi history bằng đường bypass API sau opening. Report persist trong transaction riêng sau khi đóng read snapshot; nó ghi snapshot/run metadata, không hứa phản ánh balance tại thời điểm người dùng mở báo cáo. B phải test transaction bắt đầu trước cutoff/commit sau và từ chối tạo opening khi writers chưa drain.

## 9. Walkthrough nghiệm thu thiết kế

| Luồng | Chuỗi module/API | Quyết định và case bắt buộc |
|---|---|---|
| Register | Web → Onboarding → Identity.createUser → Wallet.createInitialWallet | Một transaction; lỗi ví rollback user; username/email unique và wallet-number collision không để user mồ côi |
| Topup | Auth → Payment facade → claim → Banking.requireActive → Wallet snapshot/change → history → outbox → result | Linked bank mô phỏng, thuộc ví và ACTIVE; duplicate sau commit replay dù bank unlink sau đó |
| Withdraw | Như topup + debit guard | Bank lock trước wallet; thiếu tiền rollback cả claim/history/event; retry phải đọc lại balance |
| Transfer | Payment → claim → Wallet owned/target → apply sorted IDs → history/outbox/result | Ví 100 chi 80/70: một success, một insufficient sau retry; không partial debit/credit |
| Unlink | Web → Banking → Wallet ownership query → bank-link row lock → UNLINKED | Nếu unlink lock trước, payment mới thấy inactive; nếu payment lock trước, unlink chờ và payment được hoàn tất trước |
| Notify | Outbox worker → handler port → Notification insert → DONE | Claim khác token không mark done; crash rollback inbox/DONE hoặc reclaim lease; duplicate unique không thêm inbox |

C01 chỉ kiểm tra logic/ownership/dependency của các luồng. Runtime proof QA01–QA10 do C02–C11 tạo; CI xanh hiện tại không chứng minh các API/tables đích đã tồn tại.

## 10. Bàn giao và hiệu lực

- A chốt baseline bằng PR C01-A, tự kiểm tra/merge; không chờ review B. Tài liệu không tự thay quyết định merge của chủ project.
- B dùng contract này cho C02-B/C03-B/C06-B; không tự mở rộng scope hoặc đổi naming semantics giữa các PR.
- Architecture tests, DDL và Java signatures được triển khai ở ticket owner tương ứng. Trường hợp implementation cần đổi contract phải có revision trước khi code phụ thuộc.
- Compatibility: C01 docs-only, không đổi runtime/schema/workflow. Rollback: revert docs qua PR; nếu code phụ thuộc đã merge phải đánh giá lại compatibility thay vì chỉ xóa ADR.

## 11. Tham chiếu kỹ thuật

- [MySQL locking reads và SKIP LOCKED](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html): dùng skip-locked cho queue claim, không dùng nó để đọc balance quyết định chi tiền.
- [Flyway baseline command](https://documentation.red-gate.com/flyway/reference/commands/baseline): đánh dấu version đã tồn tại cho DB cũ sau khi xác minh.
- [Flyway baselineOnMigrate](https://documentation.red-gate.com/fd/flyway-baseline-on-migrate-setting-277578974.html): không tự baseline nhầm DB; thiết kế này chọn baseline thủ công có kiểm tra.
