# ADR-002 — Transaction và retry

Trạng thái: **ACCEPTED — thiết kế C01-A đã merge tại PR #5**. C05-A triển khai runtime theo contract riêng, chưa tự thay quyết định nghiệm thu/merge của A. Ngày: 17/11/2026.

Contract chi tiết và có ưu tiên khi đọc: [architecture-contracts.v1](../contracts/architecture-contracts-v1.md). Đây là quyết định thiết kế, chưa phải hành vi runtime đã triển khai. B không cần review PR của A.

Payment execution service sở hữu transaction. Debit, credit, history, idempotency success và outbox cùng transaction manager/datasource. Wallet/Banking tham gia transaction hiện tại; không dùng REQUIRES_NEW cho bất kỳ tác động tiền nào.

Giữ optimistic locking làm mặc định ban đầu. Retry facade ngoài execution proxy, tối đa 3 attempts tổng cộng; mỗi attempt đọc lại trong transaction mới, backoff có jitter. Chỉ retry optimistic conflict/deadlock xác định được; không retry thiếu tiền, trạng thái ví hoặc input sai. Đặc tả lock timeout riêng trước khi cho phép retry. Không dùng khóa JVM làm guarantee nhiều instance.

Unlink và payment dùng liên kết phải được tuần tự hóa ở cùng bank-link row. Phương án pessimistic wallet locking chỉ thay mặc định khi benchmark có bằng chứng và khóa theo id tăng dần. Không gọi dịch vụ ngoài trong transaction giữ khóa.

Đánh đổi: retry tăng latency; contention cao có thể cần chiến lược khóa khác. C05 phải chứng minh rollback ở các fault point, không số dư âm, A↔B và hai instance; đây chưa phải hành vi đã được xác minh trên code hiện tại.

## Chốt C01-A

Các write API dùng MANDATORY; facade không transactional gọi executor qua proxy; apply wallet theo id tăng dần. Retry delays 25–50/50–100ms, tối đa 3 attempts. Bank lock timeout trả RESOURCE_BUSY, không retry; claim-key timeout trả REQUEST_IN_PROGRESS. Session lock wait mục tiêu 2 giây, không đổi global DB và chưa là SLA HTTP. Chi tiết validation/HTTP ở mục 4–5 contract v1.


## Revision triển khai C05-A

- Wallet vẫn optimistic; flush từng owner write theo ID tăng dần. Bank-link có row lock theo contract C01, không có JVM mutex.
- Facade NEVER gọi executor REQUIRED tối đa 3 attempts. Payment và bank writes dùng READ_COMMITTED; datasource default/read-only snapshot không bị đổi toàn cục. Open-in-view tắt để không reuse persistence context giữa preflight và retry. Mỗi attempt đọc lại data trong transaction/EntityManager mới.
- Chỉ optimistic conflict hoặc MySQL vendor 1213 + SQLState 40001 được retry. Connector/J có thể báo lock timeout 1205 với SQLState 40001; vendor code vẫn quyết định đây là RESOURCE_BUSY/REQUEST_IN_PROGRESS, không retry ngầm.
- BankEligibilityApi thay read-only BankAccountQueryApi của C03/C04. Payment/unlink cùng scoped bank-row lock và current refresh; giữ lock tới transaction hoàn tất.
- Proof/rủi ro/rollback và acceptance matrix: contracts/C05-A-r1.md, evidence C05-A và PR candidate. Không có benchmark claim hoặc chuyển Wallet sang pessimistic strategy.
