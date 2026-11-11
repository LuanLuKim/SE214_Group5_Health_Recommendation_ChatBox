# M4 Hội thoại với trợ lý AI

Use case: **UC-06, UC-08, UC-09**. Phụ trách: Đặng Việt Thành (`Vncnt-uit`).

## Vị trí

- Backend (Spring Boot): `backend/src/main/java/vn/edu/uit/se214/health/chat/`, bắt đầu từ `ChatController.java`
- Frontend: `apps/web/src/modules/chat/`
- Migration: `database/migrations/` (V004__chat.sql)
- Kiểu dữ liệu cho frontend: `packages/contracts/src/index.ts`
- Kiến trúc và công nghệ: `docs/TECH_STACK.md`, `docs/MODULES.md`

## Giao diện công khai cho module khác

(chưa có)

## Hiện trạng và ranh giới

Lịch sử hội thoại và phản hồi ở đây. Trả lời câu hỏi chạy trong `services/ai` (FastAPI): Safety Gate trước, nguồn đã duyệt của M2, gọi LLM, kiểm tra đầu ra, streaming SSE. Gọi FastAPI qua `PythonServiceClient` có `@Qualifier("aiService")`, chỉ gửi dữ liệu tối thiểu (không họ tên, email). Phản hồi "chưa hữu ích" gửi sang hàng đợi UC-17 dưới dạng ẩn danh.

## Cách mở rộng

- `*Controller` chỉ đọc HTTP và trả `ApiResponse`; nghiệp vụ trong `*Service`; SQL trong `*Repository` (dùng `JdbcClient`, luôn truyền tham số, không nối chuỗi giá trị người dùng).
- Module khác chỉ dùng kiểu trong package `api` (hoặc interface công khai) của module này, không gọi repository hay bảng của nhau.
- Route riêng tư đánh dấu `@RequiresSession`. Lỗi trả qua `ApiException` (mã và thông điệp tiếng Việt an toàn); không ghi body, hồ sơ, prompt hay token vào log.
- Thay đổi cơ sở dữ liệu: thêm migration mới `VNNN__<module>_<mô-tả>.sql`, không sửa migration đã áp dụng.
- Test đặt ở `backend/src/test/java/.../chat/`; test cần cơ sở dữ liệu dùng `support/TestDatabase` (PostgreSQL nhúng, không cần Docker). Chạy `mvn -f backend/pom.xml verify`.
