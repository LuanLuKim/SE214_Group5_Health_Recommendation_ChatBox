# Làm việc theo module

1. Clone repo, chạy theo README và tìm module trong `docs/MODULES.md`.
2. Tạo branch `feat/m1-safety-rules`, `feat/m2-review` hoặc tên tương ứng M1–M6. Không dùng chung một feature branch cho sáu người.
3. Làm frontend/backend trong cùng hai thư mục module. Giữ thay đổi shared contract ở mức nhỏ và thông báo module bị ảnh hưởng trong PR.
4. Thêm migration mới thay vì sửa migration team đã áp dụng. Không tạo bản sao database/schema riêng cho mỗi người trong source.
5. Chạy `npm run format`, `npm run check`; mở PR vào `main` bằng mẫu có sẵn.

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

PR cần mô tả điều gì chạy được, các nhánh lỗi/quyền đã kiểm thử, contract/migration ảnh hưởng module nào và phần còn thiếu. CI đang được cung cấp dưới dạng mẫu tại `docs/ci/baseline-checks.yml.example`, chưa tự chạy trên GitHub. Khi bật CI, kết quả xanh chỉ xác nhận check của bản khung; không thay thế 120 ca đánh giá AI và kiểm thử SRS cuối kỳ.
