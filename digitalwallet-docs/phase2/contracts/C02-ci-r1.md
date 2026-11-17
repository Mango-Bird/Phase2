# Contract C02-CI-r1 — Bootstrap GitHub CI

Owner: A (thực hiện qua Codex). Người nghiệm thu và merge: A.
Phạm vi: phần CI của C02; không bao gồm Flyway/Liquibase, migration dữ liệu, Testcontainers, benchmark hoặc refactor module.

## Đầu vào và quyết định

- Java 21, Gradle wrapper 9.8.1, Spring Boot 4.0.8 như repository.
- Test hiện có gồm unit, WebMvc mock persistence, security context và contextLoads cần MySQL. Context load không thay thế concurrency/atomicity integration tests.
- Một job có tên ổn định `Backend build and test`; chạy trên mọi push và PR vào main, kể cả docs-only.
- MySQL 8.4 biệt lập, schema Phase 1, Hibernate validate; không dùng DB hay secret deployment.
- Chạy `bash ./gradlew clean build --no-daemon --stacktrace`, không bỏ test, không continue-on-error.
- Upload test XML/HTML kể cả khi build fail; upload JAR chỉ khi thành công.

## Checklist nghiệm thu

- [ ] Required check PASS trên candidate SHA cuối cùng và có run URL.
- [ ] Test XML có số lượng test/failure thực tế; không dùng skipped tests để che lỗi.
- [ ] Build tạo executable JAR.
- [x] Main yêu cầu PR và check `Backend build and test`; đã đọc lại effective rules của hai ruleset active, xem [runbook](../runbooks/github-governance.md).
- [ ] A có thể tự nghiệm thu PR của A; không cần B approve.
- [ ] Không merge trong phiên này; A quyết định merge sau khi review.

## Phục hồi và giới hạn

Revert workflow trong PR nếu cấu hình sai, không tắt test để lấy PASS. Cấu hình bảo vệ main thay đổi qua GitHub API phải được ghi lại. Bảo vệ require PR/check chưa tự đồng nghĩa chỉ A có quyền merge; giới hạn theo gói GitHub/quyền repository phải được báo đúng.

Kết quả kỹ thuật candidate `4df5e38`: GitHub push và PR CI SUCCESS, 111 tests PASS, không skip; artifact JAR đã tạo. Sau khi chủ repo xác nhận, main protection đã được áp dụng bằng hai ruleset độc lập; sự kiện bị chặn trước đó giữ trong evidence. Chủ repo đã merge PR #2 và yêu cầu reset lịch sử; root mới và cấu hình quyền có trong runbook. Không đánh hoàn tất toàn bộ C02 (migration còn lại). Xem [evidence](../evidence/ci-bootstrap.md) và CI của head SHA mới nhất trước mỗi lần merge.
