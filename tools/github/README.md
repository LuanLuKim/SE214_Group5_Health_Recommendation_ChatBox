# Đồng bộ GitHub theo kế hoạch Jira

- `plan.json`: 6 milestone (theo sprint), nhãn, 11 issue đã xong (kèm nhánh, PR, commit, người làm) và 40 issue chưa xong (kèm điều kiện kiểm thử).
- `ruleset-main.json`: nhánh main cần PR, **1 reviewer**, CI `check` và `data-tools` xanh mới merge được. Chủ repo (admin) được bỏ qua yêu cầu reviewer ở chế độ pull request để tự merge khi làm một mình.
- `sync.mjs`: sao lưu issue đang mở, xóa chúng (issue đã đóng được giữ), tạo milestone, nhãn, issue, đóng issue đã xong và áp ruleset.

```powershell
gh auth login
node tools/github/sync.mjs            # chạy thử, không đổi gì
node tools/github/sync.mjs --apply    # thực hiện
```

Điền `people.tai.login` trong `plan.json` (tên GitHub của Tài) trước khi chạy để gán đúng người làm PR #22. Bản sao lưu nằm trong `tools/github/backup/`.
