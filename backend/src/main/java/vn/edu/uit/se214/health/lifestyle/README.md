# M6 Lộ trình sinh hoạt & nhắc nhở

Use case: **UC-12, UC-13, UC-14**. Phụ trách: Lữ Kim Luân (`LuanLuKim`).

## Vị trí

- Backend (Spring Boot): `backend/src/main/java/vn/edu/uit/se214/health/lifestyle/`, bắt đầu từ `LifestyleController.java`
- Frontend: `apps/web/src/modules/lifestyle/`
- Migration: `database/migrations/` (V006__lifestyle.sql, V008__lifestyle_reminders.sql)
- Kiểu dữ liệu cho frontend: `packages/contracts/src/index.ts`
- Kiến trúc và công nghệ: `docs/TECH_STACK.md`, `docs/MODULES.md`

## Giao diện công khai cho module khác

(chưa có)

## Hiện trạng và ranh giới

`ProgressCalculator` (UC-13: tiến độ ngày, chuỗi ngày theo giờ Việt Nam) đã có kèm test. Lộ trình chỉ chọn từ mẫu đã duyệt (UC-17), không để AI tự sinh thực đơn hay giáo án. Nhắc nhở (UC-14) chạy bằng `@Scheduled` trong module này, ghi `reminder_deliveries` trước khi gửi để không gửi trùng.

## Cách mở rộng

- `*Controller` chỉ đọc HTTP và trả `ApiResponse`; nghiệp vụ trong `*Service`; SQL trong `*Repository` (dùng `JdbcClient`, luôn truyền tham số, không nối chuỗi giá trị người dùng).
- Module khác chỉ dùng kiểu trong package `api` (hoặc interface công khai) của module này, không gọi repository hay bảng của nhau.
- Route riêng tư được bảo vệ bởi Spring Security filter chain. Lỗi trả cùng JSON envelope và request ID; không ghi body, hồ sơ, prompt hay token vào log.
- Thay đổi cơ sở dữ liệu: thêm migration mới `VNNN__<module>_<mô-tả>.sql`, không sửa migration đã áp dụng.
- Test đặt ở `backend/src/test/java/.../lifestyle/`; test cần cơ sở dữ liệu dùng `support/TestDatabase` (PostgreSQL nhúng, không cần Docker). Chạy `mvn -f backend/pom.xml verify`.
