# Contract <Cxx-A/B>-r<N> — <kết quả cần đạt>

> Copy file này thành contract riêng của ticket. Đây là mẫu chưa nghiệm thu; không tick thay các PR khác.

## 1. Danh tính và quyền

- Ticket / QA scenario:
- Owner duy nhất: A hoặc B.
- Người nghiệm thu và merge: A — Mango-Bird.
- Chế độ: A tự kiểm tra / A review PR B. B không review hoặc ký PR A.
- PR URL / branch / base SHA / candidate head SHA:
- Contract revision / dependency đã merge (PR + merge SHA):

## 2. Contract đầu vào/đầu ra

| Thành phần | Trước thay đổi | Sau thay đổi / tương thích |
|---|---|---|
| Java public API | ... | ... |
| HTTP path/body/status/error codes | ... | ... |
| Schema / migration version | ... | ... |
| Event / schemaVersion / ordering | ... | ... |
| Transaction / retry boundary | ... | ... |
| Config / required-check name | ... | ... |

- Vấn đề tái hiện và kết quả quan sát được:
- File/module owner được thay đổi:
- Ngoài scope / phần chưa thực hiện:
- Invariants phải giữ:
- Cách rollback/roll-forward, ảnh hưởng dữ liệu, điều kiện không thể rollback app:

## 3. Ma trận nghiệm thu

| Case ID | Given (seed/config) | When (request/lỗi/concurrency) | Then (HTTP + trạng thái DB/event) | Lệnh/test | Evidence | Kết quả |
|---|---|---|---|---|---|---|
| TC01 | ... | ... | ... | ... | ... | NOT RUN |
| TC02 | ... | ... | ... | ... | ... | NOT RUN |

Mỗi case ghi PASS/FAIL/NOT RUN/BLOCKED; SKIPPED hoặc chỉ có contextLoads không thay cho case DB concurrency. Tài liệu-only ghi rõ không đổi runtime, kiểm tra link/nội dung và vẫn chạy required GitHub CI. Benchmark chỉ bắt buộc khi ticket có mục tiêu hiệu năng hoặc ảnh hưởng đường chạy cần so sánh.

## 4. Checklist owner — trước bàn giao

- [ ] Contract đầu vào được A chốt; dependency đã merge, branch từ main mới.
- [ ] Không sửa ngoài scope; API/schema/event và docs khớp code.
- [ ] Local checks phù hợp đã chạy, lệnh/kết quả được ghi; phần chưa chạy có lý do.
- [ ] Regression liên quan có evidence; không disable test hoặc đổi expectation chỉ để CI xanh.
- [ ] Migration/rollback và invariant được thử nếu áp dụng; không áp dụng phải ghi lý do cụ thể.
- [ ] GitHub `Backend build and test` SUCCESS trên head mới nhất, không pending/failure/cancelled/missing.
- [ ] Run URL + event push/PR + head SHA khớp PR; có XML/HTML/JAR khi cần.
- [ ] Không chứa secret/PII trong code, log, dump hoặc artifact.

Owner bàn giao: tên / thời điểm / trạng thái READY FOR A hoặc BLOCKED:

## 5. Evidence CI

- Candidate head SHA:
- PR check run URL / push run URL:
- Required check / conclusion:
- Tests / failures / errors / skipped (nếu có):
- Artifact và thời hạn lưu:
- DB/JDK/Gradle/config/seed nếu ảnh hưởng kết quả:
- Hạn chế và rủi ro còn lại:

Không commit riêng chỉ để điền SHA của chính file contract: trường candidate cuối có thể ghi trong PR body/comment của A để tránh vòng lặp commit → SHA mới → sửa file → SHA mới. Evidence cũ trong file phải gắn rõ SHA cũ; trước merge luôn kiểm tra head thực tế.

## 6. Quyết định của A — trước merge

- [ ] A tự kiểm tra PR A / A review PR B; đánh giá các case rủi ro và diff cuối.
- [ ] Required CI PASS trên head mới nhất; branch cập nhật theo main.
- [ ] Resolve conversations; không còn blocker; không đổi CI/ruleset để né gate.
- [ ] Có quyết định ACCEPTED hoặc REQUEST CHANGES gắn đúng candidate SHA trên PR.

Quyết định A / ngày / candidate SHA / lý do:

## 7. Tích hợp — ghi trên PR sau merge

- Người merge: A.
- Merge SHA / URL:
- CI trên main / kết quả:
- Nếu main FAIL: tạm dừng ticket phụ thuộc, link PR sửa/revert, owner và quyết định A.

`Bypass rules and merge` của A chỉ vượt hạn chế người cập nhật main; ruleset PR+CI độc lập không có bypass. B không tự merge. Không yêu cầu A self-approve bằng nút GitHub; quyết định tự nghiệm thu ghi trên PR là đủ cho quy trình nhóm khi CI đã đạt.
