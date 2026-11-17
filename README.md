# Digital Wallet — Phase 2

Cải tiến kiến trúc backend ví điện tử theo hướng **Modular Monolith kết hợp Transactional Outbox**, tập trung vào khả năng bảo trì, độ tin cậy, khả năng phục hồi, khả năng quan sát và hiệu năng.

Repository hiện chứa mã nguồn kế thừa từ Phase 1 và kế hoạch Phase 2. Kiến trúc đích chưa được triển khai. Nạp/rút tiền hiện là mô phỏng nội bộ.

## Kế hoạch triển khai

- [Lộ trình, checklist, phân công hai người và contract nghiệm thu](digitalwallet-docs/phase2_implementation_plan.md).
- [Phân việc A/B và contract từng ticket — bản v2](digitalwallet-docs/phase2/contracts/work-orders-v2.md).
- [Mẫu contract cho mỗi PR](digitalwallet-docs/phase2/contracts/PR-CONTRACT-TEMPLATE.md).
- [Khảo sát mã nguồn và vấn đề hiện trạng](digitalwallet-docs/phase2_assessment.md).

Lộ trình tham khảo gồm 6 tuần, 13 đợt sửa C00–C12. A (chủ project) vừa code phần giao dịch/domain, vừa review/nghiệm thu và duy nhất có quyền merge. B phụ trách nền tảng/kiểm chứng, không cần review PR của A. Mọi PR phải có GitHub CI PASS trên commit mới nhất trước khi A merge. Mỗi đợt sửa cần có bằng chứng nghiệm thu theo contract.

## Cấu trúc repository

| Thư mục | Nội dung |
|---|---|
| `digitalwallet-system` | Backend Java 21, Spring Boot, JPA và JWT |
| `digitalwallet-database` | Schema kế thừa làm đầu vào migration |
| `digitalwallet-docs` | Kế hoạch Phase 2 và tài liệu baseline |
| `digitalwallet-stress-test` | Bộ công cụ k6 và script benchmark kế thừa |

## Chạy ứng dụng hiện tại

Yêu cầu Docker và Docker Compose. Tại thư mục gốc, tạo `.env` từ `.env.example`, điền cấu hình phù hợp rồi khởi động:

```powershell
Copy-Item .env.example .env
# Điền cấu hình trong .env trước khi chạy lệnh sau.
docker compose up -d --build
```

Swagger: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html).

```powershell
docker compose logs -f app
docker compose down
```

Chạy bộ test backend với JDK 21:

```powershell
cd digitalwallet-system
.\gradlew.bat test
```

## Tài liệu tham chiếu

Các tài liệu dưới đây mô tả nền Phase 1 để kiểm tra tương thích và so sánh trước–sau; chúng không đại diện cho kiến trúc Phase 2 đã triển khai.

- [Kiến trúc hiện tại](digitalwallet-docs/architecture.md).
- [Nghiệp vụ](digitalwallet-docs/business.md), [API contract](digitalwallet-docs/apicontract.md) và [database](digitalwallet-docs/dbanalysis.md).
- [Hướng dẫn bộ công cụ stress test](digitalwallet-stress-test/README.md).
- [Báo cáo tải baseline](digitalwallet-docs/stress-test/Report.md).
