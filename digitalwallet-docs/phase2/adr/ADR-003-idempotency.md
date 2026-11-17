# ADR-003 — Idempotency đồng bộ

Trạng thái: đề xuất, chờ A nghiệm thu. Ngày: 17/11/2026.

Dùng unique DB theo actor/operation/key, hash request chuẩn hóa và response snapshot. Key không thay thế transaction reference. Claim, money writes và success response commit nguyên tử. Request lỗi rollback không cache business failure; lần thử sau cùng key có thể thành công khi trạng thái nghiệp vụ đổi.

Cùng key/payload replay business status/body đã lưu. Khác payload trả 409 IDEMPOTENCY_KEY_REUSED. In-flight chờ có giới hạn hoặc trả REQUEST_IN_PROGRESS retryable; xử lý unique conflict trong transaction mới sau rollback. Không dựa cache hay bộ nhớ tiến trình.

Phase 2 giữ toàn bộ key, chưa cleanup. Header bắt buộc là thay đổi API có chủ đích ở C04; cập nhật OpenAPI và k6 cùng lần sửa. Định dạng hash, null/description normalization và giới hạn header phải chốt trong contract C04 trước code.

C04 nghiệm thu duplicate burst 100 requests, hai instance, mất response sau commit rồi restart/retry; chỉ một business effect. Không đòi mọi response in-flight đều 200 và không hứa exactly-once transport.
