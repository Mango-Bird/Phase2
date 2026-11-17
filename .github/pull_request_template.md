## Thay đổi và contract

- Ticket / contract:
- Contract revision / link: dùng `digitalwallet-docs/phase2/contracts/PR-CONTRACT-TEMPLATE.md`.
- Owner: A / B
- Người nghiệm thu và merge: A
- Chế độ: A tự kiểm tra / A review PR của B
- Thay đổi API / schema / event / transaction boundary:
- Dependency đã merge / base SHA:

## Bằng chứng

- [ ] Checks local phù hợp đã chạy; ghi lệnh và kết quả bên dưới.
- [ ] GitHub CI PASS trên head SHA mới nhất (điền run URL và SHA).
- [ ] Contract có bằng chứng thực tế, không đánh PASS cho phần NOT RUN.
- [ ] Đã mô tả tương thích và rollback/roll-forward nếu có.
- [ ] A đã nghiệm thu; B không cần review PR của A.
- [ ] Branch cập nhật theo main; conversations đã resolve; không còn blocker.

CI run / SHA:

Test counts / artifact / giới hạn của evidence:

Lệnh và kết quả kiểm thử:

Giới hạn / phần chưa chạy:

Phương án phục hồi:

Quyết định A: ACCEPTED / REQUEST CHANGES / CHƯA NGHIỆM THU — candidate SHA và lý do:

Sau merge (A ghi trên PR): merge SHA / main CI / ticket sửa nếu có lỗi:

Chỉ A thực hiện merge. Không auto-merge hoặc bypass CI. Nút `Bypass rules and merge` của A chỉ vượt rule hạn chế người cập nhật main; required CI vẫn bắt buộc. Không cần B review PR A. CI xanh chưa tự đồng nghĩa A đã nghiệm thu.
