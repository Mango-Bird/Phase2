# Hợp đồng API — Digital Wallet baseline pha 1

Tài liệu này mô tả API của baseline hiện tại, đối chiếu với [OpenAPI 3.1 JSON](swaggerAPI/api-docs.json), controller và DTO trong `digitalwallet-system`. Các ví dụ thời gian và định danh chỉ nhằm minh họa.

## Cập nhật runtime C04-A — payment idempotency

Phần còn lại của tài liệu và `swaggerAPI/api-docs.json` là snapshot baseline Phase 1. Khi dùng runtime C04-A, áp dụng [contract C04-A-r1](phase2/contracts/C04-A-r1.md) và OpenAPI runtime `/v3/api-docs`:

- POST `/api/v1/transactions/topup`, `/withdraw`, `/transfer` bắt buộc `Idempotency-Key`, ASCII case-sensitive `[A-Za-z0-9._:-]{1,64}`. Không dùng transaction reference thay key.
- Tạo key một lần cho mỗi ý định thanh toán. Timeout/mất response/500/REQUEST_IN_PROGRESS: retry **cùng key và payload**; không tạo key mới để dò kết quả.
- Cùng actor/operation/key + cùng payload đã commit: 200 và nguyên success body đã lưu, kể cả timestamp. Khác payload: 409 `IDEMPOTENCY_KEY_REUSED`.
- Claim đang bị giữ lock có thể trả 409 `REQUEST_IN_PROGRESS`, retryable=true, `Retry-After: 1`. Request thất bại rollback cả key, không cache business failure.
- Body của ba command tối đa **16 KiB (16,384 bytes)**, kể cả chunked/không Content-Length và replay. Vượt ngưỡng trả 413 `PAYLOAD_TOO_LARGE`, retryable=false; amount 0/âm trả 400 `INVALID_AMOUNT`.
- Ba command endpoints từ chối JSON unknown/duplicate fields và numeric coercion: amount phải là JSON integer dương trong long, không chuỗi/float/exponent. Description thiếu/null tương đương; empty khác null; giữ Unicode/whitespace hợp lệ.
- Success envelope/data giữ shape hiện tại, kể cả null fields. Error giữ fields cũ và thêm `code`, `retryable`, `traceId`. Auth vẫn bắt buộc khi replay.

```bash
curl -X POST 'http://localhost:8080/api/v1/transactions/topup' \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: topup-intent-001' \
  -d '{"linkedBankAccountId":31,"amount":100,"description":null}'
```

Lặp lại nguyên lệnh với cùng key/payload để retry ý định đó. Muốn topup lần mới thì tạo key mới. GET history, bank APIs và auth không yêu cầu header này. C04 chưa hoàn thiện retry concurrency/unlink locking của C05.

## Cập nhật C05-A — money/concurrency

- Amount vẫn integer VND 1..Long.MAX_VALUE. Overflow balance trả 400 MONEY_OVERFLOW; không wrap hoặc ghi tiền trước khi báo lỗi. ACTIVE/ownership/insufficient vẫn là business failures, không server retry.
- Optimistic conflict/MySQL deadlock xác định được retry tối đa 3 attempts tổng cộng, transaction mới mỗi lần. Hết budget trả 409 CONCURRENT_UPDATE/retryable=true; client giữ nguyên Idempotency-Key và payload.
- Bank-link row được khóa chung giữa payment/unlink. Lock timeout trả 409 RESOURCE_BUSY/retryable=true, không retry ngầm. Claim-key timeout vẫn REQUEST_IN_PROGRESS/Retry-After:1 như C04.
- Replay receipt đã commit vẫn trả nguyên body, không revalidate bank/wallet. Không có schema/HTTP body/path mới; không coi retry của server là lý do tạo key mới ở client.

## 1. Quy ước chung

- Base path: `/api/v1`. Request có body dùng `Content-Type: application/json`.
- `POST /auth/register` và `POST /auth/login` là public. Các API nghiệp vụ yêu cầu `Authorization: Bearer <access_token>`.
- Số tiền dùng số nguyên VND (`Long` trong Java), phải lớn hơn 0. Không dùng số thực cho số dư hoặc số tiền giao dịch.
- Trường `timestamp` và các trường `createdAt`/`updatedAt`/`linkedAt`/`unlinkedAt` do backend dùng `LocalDateTime`. Giá trị JSON không kèm múi giờ; client không nên mặc định hậu tố `Z` là UTC.
- Pha 1 lưu thông tin tài khoản ngân hàng liên kết để mô phỏng nạp/rút; chưa tích hợp với hệ thống ngân hàng thật.

### Phản hồi thành công

HTTP status trùng với `status` trong body. `data` có dạng đối tượng hoặc mảng tùy endpoint; khi không có dữ liệu, trường `data` có thể bị lược bỏ.

```json
{
  "timestamp": "2026-10-28T10:00:00",
  "status": 200,
  "message": "Success",
  "data": {}
}
```

### Phản hồi lỗi

Lỗi do `GlobalExceptionHandler` xử lý dùng các trường dưới đây; `errors` chỉ xuất hiện khi có chi tiết lỗi validation. Phản hồi `401`/`403` do lớp Spring Security tạo trực tiếp có thể chỉ gồm `status`, `error` và `message`.

```json
{
  "timestamp": "2026-10-28T10:00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/v1/transactions/transfer",
  "errors": {
    "amount": "Amount must be greater than 0"
  }
}
```

Baseline hiện tại **không trả trường `success` hoặc `errorCode`** trong body. Nếu frontend cần mã lỗi nghiệp vụ ổn định, đó là thay đổi hợp đồng API cho pha sau.

## 2. Danh mục endpoint

| Method | Endpoint | Xác thực | Thành công | Lỗi được khai báo trong OpenAPI |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/auth/register` | Không | `201` | `400`, `409` |
| `POST` | `/api/v1/auth/login` | Không | `200` | `400`, `401` |
| `GET` | `/api/v1/wallets/me` | JWT | `200` | `401`, `404` |
| `POST` | `/api/v1/linked-banks` | JWT | `201` | `400`, `401`, `404` |
| `GET` | `/api/v1/linked-banks` | JWT | `200` | `401`, `404` |
| `DELETE` | `/api/v1/linked-banks/{id}` | JWT | `200` | `401`, `404` |
| `POST` | `/api/v1/transactions/topup` | JWT | `200` | `400`, `401`, `404` |
| `POST` | `/api/v1/transactions/withdraw` | JWT | `200` | `400`, `401`, `404` |
| `POST` | `/api/v1/transactions/transfer` | JWT | `200` | `400`, `401`, `404` |
| `GET` | `/api/v1/transactions` | JWT | `200` | `401`, `404` |

OpenAPI cũng khai báo `500` cho các endpoint trên. Khi xảy ra xung đột cập nhật ví, `GlobalExceptionHandler` có thể trả `409 Conflict`; **OpenAPI JSON hiện chưa khai báo `409` cho các endpoint giao dịch**. Đây là điểm cần bổ sung vào đặc tả máy đọc được khi nhóm cập nhật OpenAPI.

## 3. Xác thực và ví

### `POST /api/v1/auth/register`

Tạo người dùng với vai trò `ROLE_USER` và một ví số dư ban đầu bằng 0. Body gồm bốn trường bắt buộc:

| Trường | Ràng buộc trong request DTO |
| --- | --- |
| `username` | Chuỗi không rỗng, tối đa 50 ký tự |
| `password` | Chuỗi không rỗng, từ 6 đến 100 ký tự |
| `fullName` | Chuỗi không rỗng, tối đa 100 ký tự |
| `email` | Email hợp lệ, không rỗng, tối đa 100 ký tự |

```json
{
  "username": "nguyenvana",
  "password": "Password123@",
  "fullName": "Nguyễn Văn A",
  "email": "vana@example.com"
}
```

Trả `201 Created` với `data` là `UserResponse`: `id`, `username`, `fullName`, `email`, `role`, `createdAt`. Phản hồi đăng ký **không chứa đối tượng wallet**; muốn xem ví, client đăng nhập rồi gọi `GET /wallets/me`. Dữ liệu không hợp lệ trả `400`; username hoặc email trùng trả `409`.

### `POST /api/v1/auth/login`

Body gồm `username` và `password`, đều là chuỗi không rỗng. Đăng nhập thành công trả `200 OK` với `data` là `AuthResponse`:

```json
{
  "timestamp": "2026-10-28T10:00:00",
  "status": 200,
  "message": "Login successful",
  "data": {
    "accessToken": "<JWT>",
    "tokenType": "Bearer",
    "expiresIn": 86400000
  }
}
```

`expiresIn` tính bằng **mili giây**. Phản hồi không nhúng thông tin user. Dữ liệu thiếu/sai định dạng trả `400`; thông tin đăng nhập sai trả `401`.

### `GET /api/v1/wallets/me`

Không có body hoặc query parameter. Trả `200 OK` với `data` là `WalletResponse` gồm `id`, `userId`, `walletNumber`, `balance`, `status`, `createdAt`, `updatedAt`. Ví không tồn tại trả `404`.

## 4. Tài khoản ngân hàng liên kết

### `POST /api/v1/linked-banks`

Body gồm các trường bắt buộc:

| Trường | Ràng buộc trong request DTO |
| --- | --- |
| `bankCode` | Chuỗi không rỗng, tối đa 20 ký tự |
| `bankName` | Chuỗi không rỗng, tối đa 100 ký tự |
| `accountNumber` | Chuỗi không rỗng, tối đa 50 ký tự |
| `holderName` | Chuỗi không rỗng, tối đa 100 ký tự |

```json
{
  "bankCode": "VCB",
  "bankName": "Vietcombank",
  "accountNumber": "0123456789",
  "holderName": "NGUYEN VAN A"
}
```

Trả `201 Created` với `data` là `LinkedBankResponse`: `id`, `walletId`, `bankCode`, `bankName`, `accountNumberMasked`, `holderName`, `status`, `linkedAt`, `unlinkedAt`. DTO hiện không bắt buộc tài khoản chỉ gồm chữ số hoặc tên chủ tài khoản viết hoa. Dữ liệu không hợp lệ trả `400`; không tìm thấy ví trả `404`.

### `GET /api/v1/linked-banks`

Trả `200 OK` với `data` là **mảng `LinkedBankResponse`** của các liên kết đang `ACTIVE`; mảng có thể rỗng. Không có phân trang hoặc query parameter. Không tìm thấy ví trả `404`.

### `DELETE /api/v1/linked-banks/{id}`

`id` là ID kiểu số nguyên của liên kết ngân hàng. Endpoint hủy liên kết bằng cách đổi trạng thái sang `UNLINKED`. Trả `200 OK` với message `Bank account unlinked successfully`; **không trả đối tượng liên kết đã cập nhật** trong `data`. ID không tồn tại hoặc không thuộc ví của người dùng trả `404` theo xử lý hiện tại.

## 5. Giao dịch

Cả ba endpoint giao dịch đều dùng `description` tùy chọn (tối đa 255 ký tự) và `amount` bắt buộc, kiểu số nguyên, tối thiểu 1. `referenceCode` được **server tự sinh** khi tạo bản ghi giao dịch; client không gửi `referenceCode` và baseline hiện không cung cấp idempotency key do client quản lý.

Phản hồi thành công của ba endpoint đều là `200 OK` với `data` là `TransactionResponse`:

| Trường trong `data` | Ý nghĩa |
| --- | --- |
| `id`, `referenceCode`, `type` | ID, mã tham chiếu server sinh, loại `TOPUP`/`WITHDRAW`/`TRANSFER` |
| `sourceWalletId`, `targetWalletId` | ID ví nguồn/đích; phía không áp dụng có thể là `null` |
| `linkedBankAccountId` | ID liên kết ngân hàng; `null` khi chuyển tiền nội bộ |
| `amount`, `description`, `createdAt` | Số tiền, mô tả và thời điểm tạo |

Phản hồi này **không có `newBalance`, `sourceWalletNumber` hoặc `targetWalletNumber`**. Muốn lấy số dư mới, client gọi `GET /api/v1/wallets/me`.

### `POST /api/v1/transactions/topup`

```json
{
  "linkedBankAccountId": 1,
  "amount": 100000,
  "description": "Nap tien"
}
```

`linkedBankAccountId` bắt buộc và phải thuộc ví người gọi. Endpoint cộng số dư và tạo bản ghi `TOPUP`. Ví hoặc liên kết ngân hàng không tồn tại trả `404`; dữ liệu không hợp lệ hoặc ví không hoạt động trả `400`.

### `POST /api/v1/transactions/withdraw`

```json
{
  "linkedBankAccountId": 1,
  "amount": 50000,
  "description": "Rut tien"
}
```

`linkedBankAccountId` bắt buộc và phải thuộc ví người gọi. Endpoint kiểm tra số dư, trừ tiền và tạo bản ghi `WITHDRAW`. Thiếu tiền, ví không hoạt động hoặc dữ liệu không hợp lệ trả `400`; ví/liên kết không tồn tại trả `404`.

### `POST /api/v1/transactions/transfer`

```json
{
  "targetWalletNumber": "WAL9876543210",
  "amount": 20000,
  "description": "Chuyen tien"
}
```

`targetWalletNumber` bắt buộc, không rỗng. Endpoint trừ ví gửi, cộng ví nhận và ghi bản ghi `TRANSFER` trong một transaction. Chuyển vào chính ví mình, thiếu tiền, ví không hoạt động hoặc dữ liệu không hợp lệ trả `400`; không tìm thấy ví trả `404`. Xung đột cập nhật đồng thời có thể trả `409` như đã nêu ở mục 2.

### `GET /api/v1/transactions`

Trả `200 OK` với `data` là **mảng `TransactionResponse`** của giao dịch có ví hiện tại ở phía nguồn hoặc đích, sắp xếp `createdAt` giảm dần. Endpoint hiện **không nhận `page`/`size`, không trả `content`/`totalPages` và không có trường `direction`**. Không tìm thấy ví trả `404`.

## 6. Tài liệu API

- Khi chạy backend theo cấu hình mặc định: `GET /v3/api-docs` và giao diện `/swagger-ui.html`.
- Profile `dev` cấu hình đường dẫn JSON thành `/api-docs`.
- Bản OpenAPI JSON đã lưu trong repo: [`swaggerAPI/api-docs.json`](swaggerAPI/api-docs.json).
