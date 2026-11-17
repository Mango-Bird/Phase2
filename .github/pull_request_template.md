## Thay đổi và contract

- Ticket / contract:
- Owner: A / B
- Người nghiệm thu và merge: A
- Chế độ: A tự kiểm tra / A review PR của B
- Thay đổi API / schema / event / transaction boundary:

## Bằng chứng

- [ ] Checks local phù hợp đã chạy; ghi lệnh và kết quả bên dưới.
- [ ] GitHub CI PASS trên head SHA mới nhất (điền run URL và SHA).
- [ ] Contract có bằng chứng thực tế, không đánh PASS cho phần NOT RUN.
- [ ] Đã mô tả tương thích và rollback/roll-forward nếu có.
- [ ] A đã nghiệm thu; B không cần review PR của A.

CI run / SHA:

Lệnh và kết quả kiểm thử:

Giới hạn / phần chưa chạy:

Phương án phục hồi:

Chỉ A thực hiện merge. Không auto-merge hoặc bypass CI.
