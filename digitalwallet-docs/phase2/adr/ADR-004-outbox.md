# ADR-004 — Outbox và notification inbox

Trạng thái: đề xuất, chờ A nghiệm thu. Ngày: 17/11/2026.

Ghi payment.completed.v1 trong transaction tiền. Worker poll MySQL theo batch, claim có lease/token và transaction ngắn. Worker chết thì lease hết hạn được thu hồi; token cũ không được ghi đè claim mới. Consumer xử lý at-least-once; inbox unique theo event/recipient/channel.

Trong phạm vi inbox cùng DB, insert inbox và mark event done cùng transaction xử lý. Retry có backoff, attempts hữu hạn và DEAD/replay có audit. Không yêu cầu notification ordering để tính balance. Không đưa token, bank account đầy đủ hoặc thông tin nhạy cảm vào payload/log.

Ưu điểm: không cần broker, phục hồi sau crash và tách lỗi notification khỏi Payment. Đánh đổi: polling/cleanup/lease cần vận hành; inbox eventual consistency. Không cam kết exactly-once email/SMS. C06–C07 phải thử crash ở claim/insert/mark-done, nhiều worker và replay poison event.
