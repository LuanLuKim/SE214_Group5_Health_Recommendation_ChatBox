# M2 Tra cứu & quản lý nội dung

Use case: **UC-10, UC-11, UC-15, UC-17**. Phụ trách: Trường Giang (`Ziang05`).

## Vị trí

- Backend (Spring Boot): `backend/src/main/java/vn/edu/uit/se214/health/knowledge/`, bắt đầu từ `KnowledgeController.java`
- Frontend: `apps/web/src/modules/knowledge/`
- Migration: `database/migrations/` (V002__knowledge.sql)
- Kiểu dữ liệu cho frontend: `packages/contracts/src/index.ts`
- Kiến trúc và công nghệ: `docs/TECH_STACK.md`, `docs/MODULES.md`

## Giao diện công khai cho module khác

`api/KnowledgeSearch` (tra cứu, M4 dùng để lấy nguồn), `review/ReviewSubjectHandler` (hàng đợi duyệt UC-17)

## Hiện trạng và ranh giới

Tra cứu công khai chỉ trả nội dung `APPROVED` có `verified_at`. `SyntheticKnowledgeSearch` dùng khi `DATA_MODE=mock`, `JdbcKnowledgeSearch` khi `DATA_MODE=postgres`.

UC-17 (phần M2 và code dùng chung): một hàng đợi, một bản ghi quyết định (`knowledge.review_events`), một API `/api/v1/knowledge/reviews`, một màn hình Quản trị nội dung. Sau khi lưu quyết định, gọi `ReviewSubjectHandler` của module sở hữu nội dung. Phần M2 còn có kiểm định kết quả của agent và xử lý phản hồi ẩn danh.

## Cách mở rộng

- `*Controller` chỉ đọc HTTP và trả `ApiResponse`; nghiệp vụ trong `*Service`; SQL trong `*Repository` (dùng `JdbcClient`, luôn truyền tham số, không nối chuỗi giá trị người dùng).
- Module khác chỉ dùng kiểu trong package `api` (hoặc interface công khai) của module này, không gọi repository hay bảng của nhau.
- Route riêng tư được bảo vệ bởi Spring Security filter chain. Lỗi trả cùng JSON envelope và request ID; không ghi body, hồ sơ, prompt hay token vào log.
- Thay đổi cơ sở dữ liệu: thêm migration mới `VNNN__<module>_<mô-tả>.sql`, không sửa migration đã áp dụng.
- Test đặt ở `backend/src/test/java/.../knowledge/`; test cần cơ sở dữ liệu dùng `support/TestDatabase` (PostgreSQL nhúng, không cần Docker). Chạy `mvn -f backend/pom.xml verify`.
