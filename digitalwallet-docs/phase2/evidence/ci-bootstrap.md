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
- Local sau sửa: 103 tests không cần MySQL PASS, 0 failures/errors/skipped; chạy bằng `gradlew.bat test --no-daemon --tests '*application.service.*' --tests '*adapter.in.web.*' --tests '*JwtAuthenticationFilterTest'`.
- Candidate code SHA: `4df5e3898acf078b9e387b7ed3146ebf86c8a38f`.
- [PR #2](https://github.com/Mango-Bird/Phase2/pull/2), chưa merge.
- [GitHub PR run 44049796729](https://github.com/Mango-Bird/Phase2/actions/runs/44049796729): SUCCESS; artifact XML có 13 suites, **111 tests, 0 failures, 0 errors, 0 skipped**; `bootJar` và `build` thành công.
- [GitHub push run 44049700678](https://github.com/Mango-Bird/Phase2/actions/runs/44049700678): SUCCESS.
- GitHub runner dùng Temurin 21.0.13, MySQL service 8.4 và Gradle 9.8.1. Artifact test HTML/XML và JAR có trong run, retention lần lượt 14/7 ngày.
- Đây là bằng chứng cho code SHA trên. Commit cập nhật evidence tiếp theo phải có check riêng trên SHA mới trước khi merge; không dùng run cũ thay cho head mới.

## Cấu hình bảo vệ main — BLOCKED

Trước phiên này repo không có workflow runs hoặc rulesets. Thao tác yêu cầu PR + required CI cho main đã bị automatic approval review từ chối trước khi thực thi: đề xuất không yêu cầu approval người khác để A tự nghiệm thu, nhưng chưa cưỡng chế được chỉ A merge. Không thay đổi protection và không tìm cách vượt chặn bằng cơ chế khác. Cần A xác nhận cấu hình quyền phù hợp trước khi áp dụng; CI đã chạy không đồng nghĩa CI đã là required check.

Trạng thái bàn giao: workflow/build/test PASS trên candidate đã nêu; branch protection BLOCKED; A chưa ký nghiệm thu và PR chưa merge.

## Phạm vi và giới hạn

Đã soạn 4 ADR đề xuất cho C01, chờ A nghiệm thu. Chỉ triển khai CI bootstrap của C02; chưa hoàn thành migration, Testcontainers, kiểm thử race DB, các module đích, benchmark hoặc bảo đảm chỉ A merge qua cấu hình quyền GitHub. Không merge PR trong phiên triển khai này.
