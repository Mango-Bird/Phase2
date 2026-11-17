# GitHub governance và lịch sử Phase 2

Ngày cấu hình: 17/11/2026. Repository: Mango-Bird/Phase2. A: tài khoản Mango-Bird (User ID 185587037).

## Quyền merge và CI

Đã đọc lại cấu hình qua GitHub API sau khi áp dụng. Hai ruleset active cùng áp dụng cho `refs/heads/main`:

| Ruleset | ID | Quy tắc |
|---|---|---|
| main-required-pr-and-ci | 27444067 | Bắt buộc PR, resolve review conversations, CI `Backend build and test` từ GitHub Actions app 15368, strict/up-to-date; chặn xóa và force push; không có bypass actor |
| main-only-Mango-Bird-can-merge | 27444068 | Restrict updates; chỉ User Mango-Bird có bypass cho riêng rule cập nhật và chỉ qua PR |

Quyền bypass rule cập nhật không bypass ruleset CI độc lập. Không yêu cầu approval của người khác cho PR của A. A tự nghiệm thu phần mình và review PR của B theo contract. B có thể được cấp write để push feature branch và mở PR, nhưng không được cập nhật main theo ruleset. Không cấp token của A cho B hoặc automation.

Lúc cấu hình, collaborator API chỉ trả về Mango-Bird có quyền admin. Chưa thử đăng nhập bằng tài khoản B; bằng chứng quyền hiện tại là cấu hình actor cụ thể và effective rules API, không tuyên bố đã test thực tế bằng credential B.

Admin vẫn có thể sửa/xóa chính ruleset bằng quyền quản trị. Đó là quyền quản trị cấu hình, không phải quyền bypass CI khi merge dưới các rule hiện tại.

## Quy trình làm việc sau cấu hình

1. Branch từ main mới, commit và push feature branch.
2. Mở PR, ghi contract, test evidence và CI run URL.
3. CI phải PASS trên head mới nhất; cập nhật branch nếu main đã thay đổi.
4. A tự kiểm tra PR của A hoặc review PR của B, resolve conversations rồi merge qua GitHub.
5. Không force-push main, không bỏ ruleset để né check, không merge branch thuộc lịch sử cũ vào main mới.

## Reset lịch sử theo yêu cầu chủ repository

- Người dùng yêu cầu bỏ commit và contributor cũ. Nội dung main sau khi đã merge PR #1/#2 được giữ nguyên byte-for-byte theo Git tree.
- Root mới: `e0af6240f975c1549a07adf3e46c91f7ce4eb128`, tác giả/committer Mango-Bird. Tại thời điểm reset main có đúng một commit.
- Hai branch đã merge `codex/phase2-ci-bootstrap` và `codex/phase2-review-workflow` đã xóa trên remote để không giữ lịch sử cũ qua branch thường.
- Giữ nguyên LICENSE và thông báo bản quyền. Reset lịch sử không biến mã kế thừa thành mã tự viết mới.
- Backup local đã verify: `.git/phase2-before-history-reset.bundle`; không upload backup lên GitHub. Local backup branch: `codex/history-backup-before-reset`.
- PR cũ và CI run cũ vẫn là hồ sơ hoạt động. GitHub có thể giữ commit qua PR refs/cache; không tuyên bố đã xóa vĩnh viễn mọi Git object hoặc dữ liệu contributor cache.
- GitHub contributors có thể chưa cập nhật ngay; không xóa repo để cố làm mới thống kê.

## Đồng bộ máy khác

Ưu tiên clone lại repo vào thư mục mới. Máy có công việc chưa commit cần sao lưu/cherry-pick thay đổi sang branch từ main mới; không pull/merge lịch sử cũ trở lại. Không push `--all` hoặc `--mirror` từ clone cũ.

## Kiểm tra cấu hình

Qua Settings → Rules → Rulesets, kiểm tra cả hai ruleset active và không thêm bypass vào ruleset CI. Có thể kiểm tra API `GET /repos/Mango-Bird/Phase2/rules/branches/main` và đọc từng ruleset bằng ID ở trên. Trước khi merge luôn xem check trên PR hiện tại, không dùng evidence của SHA cũ.
