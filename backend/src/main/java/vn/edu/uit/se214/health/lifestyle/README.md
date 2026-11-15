# M6 Lộ trình sinh hoạt & nhắc nhở

Use case: **UC-12, UC-13, UC-14**. Phụ trách: Lữ Kim Luân (`LuanLuKim`).

## Vị trí

- Backend (Spring Boot): `backend/src/main/java/vn/edu/uit/se214/health/lifestyle/`, bắt đầu từ `LifestyleController.java`
- Frontend: `apps/web/src/modules/lifestyle/`
- Migration: `database/migrations/` (V006__lifestyle.sql, V008__lifestyle_reminders.sql, V013__lifestyle_plan_choice.sql)
- Seed dev: `database/seeds/003_lifestyle_templates.sql` (mẫu `[TEST]` đã duyệt cho UC-12)
- Kiểu dữ liệu cho frontend: `packages/contracts/src/index.ts`
- Kiến trúc và công nghệ: `docs/TECH_STACK.md`, `docs/MODULES.md`

## Giao diện công khai cho module khác

(chưa có)

## Hiện trạng và ranh giới

UC-13 đã chạy ở `DATA_MODE=postgres`: `LifestyleService` + `LifestyleRepository` (nối trong `LifestylePersistenceConfig`), route `GET /plan`, `PUT`/`DELETE /completions/{activityId}/{date}` (chi tiết ở `docs/API.md`). `ProgressCalculator` tính tiến độ ngày và streak; "hôm nay" theo múi giờ `app.lifestyle.zone` (`LIFESTYLE_ZONE`). Quy ước: đánh dấu được từ ngày bắt đầu lộ trình tới hôm nay, chỉ bỏ đánh dấu được hôm nay; mọi hoạt động trong `templates.activities` là hằng ngày. Ở chế độ mock, service không tồn tại và route trả 503. Lộ trình chỉ chọn từ mẫu đã duyệt (UC-17), không để AI tự sinh thực đơn hay giáo án.

UC-12 (`LifestylePlanService`): `GET /templates`, `GET /templates/{id}`, `PUT /plan`.

- **Lọc mẫu:** theo mục tiêu, dị ứng (đọc qua `health.api.HealthProfileQuery`) và yêu cầu hồ sơ (`requires_profile`).
- **Tiên điều kiện:** consent dữ liệu sức khỏe (đọc qua `accounts.api.ConsentQuery`). Hai interface này tạm có bản cài đặt đọc bảng (`JdbcHealthProfileQuery`, `JdbcConsentQuery`) trong module của M3 và M5, do hai module đó tiếp quản.
- **Đổi lộ trình:** chạy trong một transaction, đặt `ended_on` cho lộ trình cũ rồi thêm lộ trình mới. Index một phần `plans_one_active_per_user` bảo đảm mỗi người chỉ một lộ trình đang dùng.
- **UC-13:** chỉ đọc lộ trình đang dùng (`ended_on IS NULL`).
- **Thông tin xem trước** (`summary`, `duration_days`, `not_suitable_for`, `requires_profile`) là cột của `lifestyle.templates`. UC-15 (M2) nhập các cột này khi soạn mẫu.

Nhắc nhở (UC-14) chạy bằng `@Scheduled` trong module này, ghi `reminder_deliveries` trước khi gửi để không gửi trùng.

## Cách mở rộng

- `*Controller` chỉ đọc HTTP và trả `ApiResponse`; nghiệp vụ trong `*Service`; SQL trong `*Repository` (dùng `JdbcClient`, luôn truyền tham số, không nối chuỗi giá trị người dùng).
- Module khác chỉ dùng kiểu trong package `api` (hoặc interface công khai) của module này, không gọi repository hay bảng của nhau.
- Route riêng tư được bảo vệ bởi Spring Security filter chain. Lỗi trả cùng JSON envelope và request ID; không ghi body, hồ sơ, prompt hay token vào log.
- Thay đổi cơ sở dữ liệu: thêm migration mới `VNNN__<module>_<mô-tả>.sql`, không sửa migration đã áp dụng.
- Test đặt ở `backend/src/test/java/.../lifestyle/`; test cần cơ sở dữ liệu dùng `support/TestDatabase` (PostgreSQL nhúng, không cần Docker). Chạy `mvn -f backend/pom.xml verify`.
