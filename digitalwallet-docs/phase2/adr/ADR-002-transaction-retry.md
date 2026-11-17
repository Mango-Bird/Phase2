# ADR-002 — Transaction và retry

Trạng thái: **DECIDED — C01-A candidate**, chờ A review/merge để trở thành ACCEPTED. Ngày: 17/11/2026.

Contract chi tiết và có ưu tiên khi đọc: [architecture-contracts.v1](../contracts/architecture-contracts-v1.md). Đây là quyết định thiết kế, chưa phải hành vi runtime đã triển khai. B không cần review PR của A.

Payment execution service sở hữu transaction. Debit, credit, history, idempotency success và outbox cùng transaction manager/datasource. Wallet/Banking tham gia transaction hiện tại; không dùng REQUIRES_NEW cho bất kỳ tác động tiền nào.

Giữ optimistic locking làm mặc định ban đầu. Retry facade ngoài execution proxy, tối đa 3 attempts tổng cộng; mỗi attempt đọc lại trong transaction mới, backoff có jitter. Chỉ retry optimistic conflict/deadlock xác định được; không retry thiếu tiền, trạng thái ví hoặc input sai. Đặc tả lock timeout riêng trước khi cho phép retry. Không dùng khóa JVM làm guarantee nhiều instance.

Unlink và payment dùng liên kết phải được tuần tự hóa ở cùng bank-link row. Phương án pessimistic wallet locking chỉ thay mặc định khi benchmark có bằng chứng và khóa theo id tăng dần. Không gọi dịch vụ ngoài trong transaction giữ khóa.

Đánh đổi: retry tăng latency; contention cao có thể cần chiến lược khóa khác. C05 phải chứng minh rollback ở các fault point, không số dư âm, A↔B và hai instance; đây chưa phải hành vi đã được xác minh trên code hiện tại.

## Chốt C01-A

Các write API dùng MANDATORY; facade không transactional gọi executor qua proxy; apply wallet theo id tăng dần. Retry delays 25–50/50–100ms, tối đa 3 attempts. Bank lock timeout trả RESOURCE_BUSY, không retry; claim-key timeout trả REQUEST_IN_PROGRESS. Session lock wait mục tiêu 2 giây, không đổi global DB và chưa là SLA HTTP. Chi tiết validation/HTTP ở mục 4–5 contract v1.
