# ADR-003 — Idempotency đồng bộ

Trạng thái: **ACCEPTED — thiết kế C01-A đã merge tại PR #5**. C04-A triển khai runtime theo contract riêng, chưa được coi là merge/release cho tới khi A quyết định. Ngày: 17/11/2026.

Contract chi tiết và có ưu tiên khi đọc: [architecture-contracts.v1](../contracts/architecture-contracts-v1.md). Đây là quyết định thiết kế, chưa phải hành vi runtime đã triển khai. B không cần review PR của A.

Dùng unique DB theo actor/operation/key, hash request chuẩn hóa và response snapshot. Key không thay thế transaction reference. Claim, money writes và success response commit nguyên tử. Request lỗi rollback không cache business failure; lần thử sau cùng key có thể thành công khi trạng thái nghiệp vụ đổi.

Cùng key/payload replay business status/body đã lưu. Khác payload trả 409 IDEMPOTENCY_KEY_REUSED. In-flight chờ có giới hạn hoặc trả REQUEST_IN_PROGRESS retryable; xử lý unique conflict trong transaction mới sau rollback. Không dựa cache hay bộ nhớ tiến trình.

Phase 2 giữ toàn bộ key, chưa cleanup. Header bắt buộc là thay đổi API có chủ đích ở C04; cập nhật OpenAPI và k6 cùng lần sửa. Định dạng canonical array, SHA-256, key ASCII case-sensitive 1–64 ký tự và null/empty semantics đã chốt ở mục 6 contract v1; C04 triển khai và test theo các fixtures chung.

C04 nghiệm thu duplicate burst 100 requests, hai instance, mất response sau commit rồi restart/retry; chỉ một business effect. Không đòi mọi response in-flight đều 200 và không hứa exactly-once transport.

## Chốt C01-A

PROCESSING không commit riêng; chỉ success COMPLETED được lưu bền cùng tiền. Same-key mismatch áp dụng với key đã commit; transaction thất bại rollback cả key. Response snapshot bao gồm timestamp success, replay không kiểm tra lại trạng thái ví/bank đã đổi. JSON unknown/duplicate fields và amount coercion bị từ chối ở C04. Fixtures: [idempotency vectors v1](../contracts/fixtures/idempotency-v1.json).
