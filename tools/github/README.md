# Đồng bộ GitHub theo kế hoạch Jira

- `plan.json`: 6 milestone (theo sprint), nhãn, 11 issue đã xong (kèm nhánh, PR, commit, người làm) và 40 issue chưa xong (kèm điều kiện kiểm thử).
- `ruleset-main.json`: nhánh main cần PR, **1 reviewer** và sáu check xanh mới merge được: `check` (frontend), `backend` (Maven), `services` (FastAPI, Django), `data-tools`, `infra-smoke` (PostgreSQL thật + ba service), `branch-policy`. Chủ repo (admin) được bỏ qua yêu cầu reviewer ở chế độ pull request để tự merge khi làm một mình.
- `sync.mjs`: sao lưu issue đang mở, xóa chúng (issue đã đóng được giữ), tạo milestone, nhãn, issue, đóng issue đã xong và áp ruleset.

```powershell
gh auth login
node tools/github/sync.mjs            # chạy thử, không đổi gì
node tools/github/sync.mjs --apply    # thực hiện
```

## Quy trình issue → nhánh → PR

- `../../.github/workflows/issue-flow.yml`: gán issue thì tạo nhánh `<số>-<mô-tả>` gắn với issue và comment hướng dẫn; push thì kiểm tra `branch-policy` và tạo PR nháp `Closes #<số>`.
- `ruleset-branches.json`: chặn tạo/đẩy nhánh không có dạng `<số>-…` (trừ admin).
- `../../.githooks/` + `install-hooks.mjs`: hook local, bật tự động qua `npm install`.

Áp ruleset (chỉ admin, chạy một lần hoặc sau khi sửa file JSON):

```powershell
gh api repos/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/rulesets --input tools/github/ruleset-branches.json
# Sau khi sửa ruleset-main.json (ví dụ thêm job CI mới vào check bắt buộc), cập nhật ruleset main:
gh api -X PUT repos/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/rulesets/25446752 --input tools/github/ruleset-main.json
```

Workflow cần quyền tạo PR bằng `GITHUB_TOKEN`: Settings → Actions → General → "Allow GitHub Actions to create and approve pull requests".

Điền `people.tai.login` trong `plan.json` (tên GitHub của Tài) trước khi chạy để gán đúng người làm PR #22. Bản sao lưu nằm trong `tools/github/backup/`.
