# Làm việc theo module

1. Clone repo, chạy `npm install` theo README (lệnh này cũng bật git hooks của nhóm) và tìm module trong `docs/MODULES.md`.
2. Nhận việc qua issue: mỗi việc là một issue trên GitHub, đã gán cho bạn. Không tự tạo nhánh (xem mục dưới).
3. Làm frontend/backend trong cùng hai thư mục module. Giữ thay đổi shared contract ở mức nhỏ và thông báo module bị ảnh hưởng trong PR.
4. Thêm migration mới thay vì sửa migration team đã áp dụng. Không tạo bản sao database/schema riêng cho mỗi người trong source.
5. Chạy `npm run format`, `npm run check` trước khi push; hoàn thiện PR nháp đã được tạo sẵn rồi bấm **Ready for review**.

## Issue → nhánh → PR (bắt buộc)

```text
Trưởng nhóm gán issue #41  ->  GitHub tạo nhánh 41-<mô-tả> + comment hướng dẫn
Bạn: git switch 41-...  ->  commit  ->  git push  ->  GitHub tạo PR nháp "Closes #41"
CI + branch-policy xanh, 1 reviewer duyệt  ->  merge  ->  issue #41 tự đóng
```

- **Tên nhánh:** `<số-issue>-<mô-tả>`, ví dụ `41-m1-uc07-safety-gate`. Nhánh được tạo tự động khi issue được gán và hiện ở mục **Development** của issue. Xem nhanh bằng `gh issue develop --list 41`.
- **Bắt đầu làm:** `git fetch origin && git switch 41-m1-uc07-safety-gate`. Không commit trên `main` hay trên nhánh tự đặt tên.
- **Commit:** hook `commit-msg` tự thêm `Refs: #41` nếu message chưa nhắc issue. Viết message theo dạng `feat(safety): …`, `fix(lifestyle): …`.
- **Push:** lần push đầu tiên tự tạo PR nháp vào `main`, có `Closes #41`, nhãn, milestone và người làm lấy từ issue. Nếu PR chỉ xong một phần issue, đổi `Closes` thành `Refs`.
- **Chưa có issue cho việc đang làm:** nhờ trưởng nhóm tạo và gán issue trước, rồi làm trên nhánh của issue đó.
- **Đã lỡ sửa ở nhánh khác:** `git stash`, `git switch <nhánh-issue>`, `git stash pop`.

Ba lớp kiểm tra giữ quy trình này:

| Lớp                                                        | Chặn gì                                                                                                                                |
| ---------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| Git hooks trong `.githooks/` (bật bởi `npm install`)       | Commit trên nhánh không phải nhánh issue; push lên `main` hoặc nhánh sai mẫu                                                           |
| Ruleset GitHub "Nhánh phải gắn với issue"                  | Tạo hoặc đẩy lên nhánh không có dạng `<số>-…` (chỉ admin được bỏ qua)                                                                  |
| Check `branch-policy` (`.github/workflows/issue-flow.yml`) | Nhánh trỏ tới issue không tồn tại/đã đóng, người push chưa được giao issue, PR không nhắc issue. Là check bắt buộc để merge vào `main` |

## Quy ước mã

- TypeScript strict. `Page.tsx` là điểm vào màn hình; component/hook riêng nằm trong module.
- Backend: router kiểm tra HTTP; service chứa nghiệp vụ; repository chứa truy vấn. Prepared parameters cho SQL, không nối giá trị người dùng vào query.
- Contract dùng chung ở `packages/contracts`, không import backend vào frontend hoặc ngược lại.
- Dùng lỗi chuẩn và request ID; không ghi request body, hồ sơ, prompt, câu trả lời hoặc token vào log.
- Auth là ranh giới server. Không dùng header giả hay vai trò ở localStorage để mở API riêng tư.
- Vai trò CONTENT_EXPERT không đồng nghĩa ADMIN. ADMIN không xem nội dung feedback, chat và hồ sơ của người khác.
- Rule quyết định care level, LLM chỉ diễn đạt. Chưa có quy tắc thì unavailable, không tự trả low risk.
- Tất cả fixture phải ghi rõ synthetic; không sao chép dữ liệu sức khỏe người thật vào test.

## File riêng tư

Chỉ commit `.env.example` với key trống. `.env`, `node_modules`, build output, database volume, backup và kho crawl lớn không được commit. Không đưa GitHub PAT hoặc LLM key vào source, script, README hay URL remote. Xác thực Git bằng Git Credential Manager hoặc công cụ đăng nhập local của bạn.

## Hoàn thành một phần việc

PR cần mô tả điều gì chạy được, các nhánh lỗi/quyền đã kiểm thử, contract/migration ảnh hưởng module nào và phần còn thiếu.

Merge vào `main` cần: 1 reviewer duyệt, check `check` (typecheck, test, build), `data-tools` (test crawler/chuẩn hóa) và `branch-policy` xanh. CI chạy trên mọi nhánh được push. Kết quả xanh chỉ xác nhận check tự động; không thay thế 120 ca đánh giá AI và kiểm thử SRS cuối kỳ.
