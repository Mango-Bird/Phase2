# ADR-001 — Ranh giới Modular Monolith

Trạng thái: **DECIDED — C01-A candidate**, chờ A review/merge để trở thành ACCEPTED. Ngày: 17/11/2026.

Contract chi tiết và có ưu tiên khi đọc: [architecture-contracts.v1](../contracts/architecture-contracts-v1.md). Đây là quyết định thiết kế, chưa phải hành vi runtime đã triển khai. B không cần review PR của A.

## Bối cảnh

Code hiện tách theo tầng toàn ứng dụng. Phase 2 cần thay đổi module độc lập và bảo vệ dữ liệu tiền khi có cạnh tranh; chưa có yêu cầu triển khai microservices.

## Quyết định thiết kế

Giữ một ứng dụng và MySQL; module Identity, Wallet, Banking, Payment, Notification, Reconciliation theo bảng ownership trong kế hoạch. Module chỉ gọi public Java API, không import repository/entity của module khác. Domain không import Spring/JPA; application được phép dùng Spring transaction/service để giảm phạm vi refactor.

Payment phụ thuộc Wallet/Banking API. Wallet không phụ thuộc Identity. Luồng register-and-create-wallet nằm ở composition/orchestration layer gọi Identity và Wallet, trong cùng transaction; Identity không gọi ngược Wallet. Không đặt nghiệp vụ chung vào platform. ArchUnit ở C03 kiểm tra graph và import; hiện chưa triển khai các ranh giới này.

## Đánh đổi và nghiệm thu

Có thể dùng một DB transaction nhưng không scale/deploy module độc lập. Không kỳ vọng đổi package tự tăng throughput. C03 giữ API Phase 1 và phải có architecture tests cùng regression tests; dependency cycle hoặc import internals phải fail build.

## Chốt C01-A

Onboarding là package composition use case riêng, không đặt trong Identity/Wallet hoặc platform. Platform worker gọi EventHandlerPort qua wiring, không import Notification. Dependency/API/ownership chính xác ở mục 2–3 contract v1; bảng module trong kế hoạch phải đọc cùng bổ sung Onboarding này.
