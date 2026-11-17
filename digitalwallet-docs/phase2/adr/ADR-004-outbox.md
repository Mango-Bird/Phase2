# ADR-004 — Outbox và notification inbox

Trạng thái: **DECIDED — C01-A candidate**, chờ A review/merge để trở thành ACCEPTED. Ngày: 17/11/2026.

Contract chi tiết và có ưu tiên khi đọc: [architecture-contracts.v1](../contracts/architecture-contracts-v1.md). Đây là quyết định thiết kế, chưa phải hành vi runtime đã triển khai. B không cần review PR của A.

Ghi payment.completed.v1 trong transaction tiền. Worker poll MySQL theo batch, claim có lease/token và transaction ngắn. Worker chết thì lease hết hạn được thu hồi; token cũ không được ghi đè claim mới. Consumer xử lý at-least-once; inbox unique theo event/recipient/channel.

Trong phạm vi inbox cùng DB, insert inbox và mark event done cùng transaction xử lý. Retry có backoff, attempts hữu hạn và DEAD/replay có audit. Không yêu cầu notification ordering để tính balance. Không đưa token, bank account đầy đủ hoặc thông tin nhạy cảm vào payload/log.

Ưu điểm: không cần broker, phục hồi sau crash và tách lỗi notification khỏi Payment. Đánh đổi: polling/cleanup/lease cần vận hành; inbox eventual consistency. Không cam kết exactly-once email/SMS. C06–C07 phải thử crash ở claim/insert/mark-done, nhiều worker và replay poison event.

## Chốt C01-A

Event DTO dùng platform.api.events, có source/target user IDs từ wallet snapshots để Notification không gọi Wallet khi consume. TOPUP→target, WITHDRAW→source, TRANSFER→SENT/RECEIVED. Batch 50, lease 30s, poll 1s, tối đa 5 attempts; exact lifecycle/uniqueness/payload ở mục 7–8 contract v1. Không gọi provider ngoài trong inbox transaction.
