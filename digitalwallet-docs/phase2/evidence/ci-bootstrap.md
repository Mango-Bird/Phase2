# Evidence — khởi tạo CI

Ngày: 17/11/2026. Người thực hiện: A qua Codex. Người quyết định merge: A.

## Baseline C00 (phần test, chưa phải benchmark)

- Base ứng dụng: fbea90b; tài liệu/quy trình kế thừa tới a14cdab.
- Windows, Temurin JDK 21.0.12.1, Gradle wrapper 9.8.1.
- Lệnh: `digitalwallet-system/gradlew.bat test --no-daemon`.
- Kết quả trước sửa: 111 tests, 13 failures. 8 lỗi context do local không có MySQL; 5 lỗi hành vi tái hiện bằng test hiện có.
- Docker CLI có sẵn nhưng Docker Desktop Linux daemon chưa chạy. Chưa đo baseline tải và chưa có CPU/RAM/load generator độc lập; C00 toàn phần chưa hoàn tất.

## Sửa tối thiểu để khôi phục gate hiện có

- Trả 400 cho body thiếu/JSON sai và path parameter sai kiểu thay vì để generic handler trả 500 (3 test).
- Topup/withdraw từ chối bank link không ACTIVE trước khi ghi dữ liệu (2 test).
- Không thay expectation hoặc disable test. Chưa giải quyết race unlink/payment; việc đó vẫn thuộc C05.

## CI

- Workflow `.github/workflows/ci.yml`: Ubuntu, Java 21, MySQL 8.4 service riêng; import SQL Phase 1, Hibernate validate, `clean build` toàn bộ test.
- Tên check: `Backend build and test`. Không dùng path filter; docs-only vẫn chạy check.
- XML/HTML được lưu artifact cả khi test fail. Credential CI chỉ dùng DB tạm của job.
- Kết quả GitHub và cấu hình bảo vệ branch: đang chờ chạy/xác minh, chưa đánh PASS.

## Phạm vi và giới hạn

Đã soạn 4 ADR đề xuất cho C01, chờ A nghiệm thu. Chỉ triển khai CI bootstrap của C02; chưa hoàn thành migration, Testcontainers, kiểm thử race DB, các module đích, benchmark hoặc bảo đảm chỉ A merge qua cấu hình quyền GitHub. Không merge PR trong phiên triển khai này.
