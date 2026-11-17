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

(chưa có). Module này dùng `accounts.api.ConsentQuery`, `accounts.api.AccountContactQuery` (M5) và `health.api.HealthProfileQuery` (M3).

## Hiện trạng và ranh giới

UC-13 đã chạy ở `DATA_MODE=postgres`: `LifestyleService` + `LifestyleRepository` (nối trong `LifestylePersistenceConfig`), route `GET /plan`, `PUT`/`DELETE /completions/{activityId}/{date}` (chi tiết ở `docs/API.md`). `ProgressCalculator` tính tiến độ ngày và streak; "hôm nay" theo múi giờ `app.lifestyle.zone` (`LIFESTYLE_ZONE`). Quy ước: đánh dấu được từ ngày bắt đầu lộ trình tới hôm nay, chỉ bỏ đánh dấu được hôm nay; mọi hoạt động trong `templates.activities` là hằng ngày. Ở chế độ mock, service không tồn tại và route trả 503. Lộ trình chỉ chọn từ mẫu đã duyệt (UC-17), không để AI tự sinh thực đơn hay giáo án.

UC-12 (`LifestylePlanService`): `GET /templates`, `GET /templates/{id}`, `PUT /plan`. Lịch sử (`LifestyleService.pastPlans`, SCRUM-163): `GET /plans/history` trả các lộ trình đã kết thúc kèm số liệu, đọc từ `completions` hoặc từ cột tóm tắt trên `plans` (V019) khi job dọn dữ liệu đã xóa dữ liệu chi tiết; tối đa `app.lifestyle.history-limit`.

- **Lọc mẫu:** theo mục tiêu, dị ứng (đọc qua `health.api.HealthProfileQuery`) và yêu cầu hồ sơ (`requires_profile`).
- **Tiên điều kiện:** consent dữ liệu sức khỏe (đọc qua `accounts.api.ConsentQuery`). Hai interface này tạm có bản cài đặt đọc bảng (`JdbcHealthProfileQuery`, `JdbcConsentQuery`) trong module của M3 và M5, do hai module đó tiếp quản.
- **Đổi lộ trình:** chạy trong một transaction, đặt `ended_on` cho lộ trình cũ rồi thêm lộ trình mới. Index một phần `plans_one_active_per_user` bảo đảm mỗi người chỉ một lộ trình đang dùng. Cùng transaction đó tạm tắt các nhắc UC-14 đang bật có `activity_id` không còn trong mẫu mới (so theo mã hoạt động), ghi `disabled_reason = 'PLAN_SWITCH'` (V018); nhắc độc lập và lịch sử gửi giữ nguyên, `AppliedPlan.remindersTurnedOff` trả số nhắc đã tắt. Nhắc tạm tắt không tính vào `max-per-user`. Khi chọn lại một mẫu có hoạt động của nhắc tạm tắt, `AppliedPlan.restorableReminderIds` trả các nhắc đó nhưng không tự bật lại. Bật lại thì xóa lý do và kiểm tra giới hạn; bật lại nhắc của hoạt động không còn trong lộ trình đang dùng cần `activityId` mới hoặc `null`.
- **UC-13:** chỉ đọc lộ trình đang dùng (`ended_on IS NULL`).
- **Thông tin xem trước** (`summary`, `duration_days`, `not_suitable_for`, `requires_profile`) là cột của `lifestyle.templates`. UC-15 (M2) nhập các cột này khi soạn mẫu.

UC-14 (`LifestyleReminderService`, `ReminderRepository`): `GET`/`POST /reminders`, `PATCH`/`DELETE /reminders/{id}`.

- **Kiểm tra đầu vào (A1):** giờ 24h `HH:mm`, nội dung 1–200 ký tự, ngày áp dụng ISO 1–7, múi giờ IANA (mặc định `app.lifestyle.zone`), kênh. `activity_id` không có FK nên service kiểm tra nó thuộc lộ trình đang dùng.
- **Kênh (A2):** nhắc đang bật phải có kênh khả dụng. `EmailReminderChannel` dùng SMTP của M5 (`SMTP_*`, `MAIL_FROM`) và đọc email qua `accounts.api.AccountContactQuery` (bản tạm `JdbcAccountContactQuery` trong module M5, M5 tiếp quản). Web Push chưa có nên trả `REMINDER_CHANNEL_UNAVAILABLE`.
- **Gửi (`ReminderDispatcher`, chạy bởi `ReminderScheduler` mỗi `app.lifestyle.reminders.poll`):** với mỗi nhắc đang bật, tính lần gửi theo `timezone` và `days_of_week` của nhắc (`ReminderSchedule`), giữ chỗ bằng `INSERT … ON CONFLICT DO NOTHING` vào `reminder_deliveries`, chỉ lần chạy giữ được chỗ mới gửi. Ghi `SENT` hoặc `FAILED` kèm mã lỗi; lỗi được thử lại trên cùng dòng tới `max-attempts`. Không gửi lần có giờ trước lúc lưu nhắc, không gửi bù quá `window`. Ghi `SENT` thất bại thì dòng giữ `PENDING` và không thử lại, nên không gửi trùng. Lỗi ở một lần thử lại không dừng cả lượt chạy. Log chỉ có id và mã lỗi.
- **Consent:** tạo, bật lại và sửa nhắc cần consent xử lý dữ liệu sức khỏe (`accounts.api.ConsentQuery`). Xem danh sách, tắt (body chỉ có `{"enabled": false}`) và xóa không cần consent, để người đã rút consent vẫn dừng được nhắc. Worker đọc consent một lần cho mỗi người trong một lượt chạy, trước khi giữ chỗ; không còn consent thì không gửi và không ghi dòng gửi, kể cả lần thử lại.
- **Cấu hình:** `app.lifestyle.reminders.*` (`REMINDERS_ENABLED`, `REMINDERS_POLL`, `REMINDERS_WINDOW`, `REMINDERS_MAX_ATTEMPTS`, `REMINDERS_MAX_PER_USER`). `REMINDERS_ENABLED=false` chỉ tắt worker.

## Cách mở rộng

- `*Controller` chỉ đọc HTTP và trả `ApiResponse`; nghiệp vụ trong `*Service`; SQL trong `*Repository` (dùng `JdbcClient`, luôn truyền tham số, không nối chuỗi giá trị người dùng).
- Module khác chỉ dùng kiểu trong package `api` (hoặc interface công khai) của module này, không gọi repository hay bảng của nhau.
- Route riêng tư được bảo vệ bởi Spring Security filter chain. Lỗi trả cùng JSON envelope và request ID; không ghi body, hồ sơ, prompt hay token vào log.
- Thay đổi cơ sở dữ liệu: thêm migration mới `VNNN__<module>_<mô-tả>.sql`, không sửa migration đã áp dụng.
- Test đặt ở `backend/src/test/java/.../lifestyle/`; test cần cơ sở dữ liệu dùng `support/TestDatabase` (PostgreSQL nhúng, không cần Docker). Chạy `mvn -f backend/pom.xml verify`.
