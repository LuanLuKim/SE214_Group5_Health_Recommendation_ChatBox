# Database baseline

Một PostgreSQL instance, sáu schema cùng tên module. Không cần sáu database hoặc sáu server.

| Migration                               | Schema    | Bảng nền tảng                                                                   |
| --------------------------------------- | --------- | ------------------------------------------------------------------------------- |
| `V001__safety.sql`                      | safety    | rules                                                                           |
| `V002__knowledge.sql`                   | knowledge | articles, facilities, review_events                                             |
| `V003__health.sql`                      | health    | profiles, daily_metrics, usage_events                                           |
| `V004__chat.sql`                        | chat      | sessions, messages, feedback                                                    |
| `V005__accounts.sql`                    | accounts  | users, user_roles, consents; thêm ownership FK cho các schema trước             |
| `V006__lifestyle.sql`                   | lifestyle | templates, plans, completions, reminders                                        |
| `V007__safety_drafts.sql`               | safety    | thêm nguồn, trường bắt buộc, chuyên khoa cho rules                              |
| `V008__lifestyle_reminders.sql`         | lifestyle | reminders: ngày áp dụng, activity_id; reminder_deliveries chống gửi trùng       |
| `V009__safety_rule_activation.sql`      | safety    | bật trạng thái kích hoạt cho quy tắc đã duyệt                                   |
| `V010__accounts_registration.sql`       | accounts  | chuẩn hóa unique email; consent xử lý dữ liệu/không chẩn đoán; OAuth identities |
| `V011__accounts_email_verification.sql` | accounts  | đăng ký email chờ OTP; lưu challenge đã băm, hạn dùng và số lần thử             |

Đây là schema khởi đầu, chưa đủ để tuyên bố hoàn thành SRS. Khi triển khai nghiệp vụ, cần versioning/audit và reviewer metadata đầy đủ; thông tin bản quyền và ngày nguồn; session/token/OAuth/email state; dữ liệu eligibility/allergy phù hợp; lịch retry/delivery idempotency; chính sách retention và deletion cho feedback/backup.

Không suy luận quy tắc/plan được duyệt chỉ từ sự có mặt trong bảng. `review_events.subject_id` là tham chiếu đa loại ở mức ứng dụng; service phải kiểm tra loại và đối tượng. Feedback review snapshot không chứa danh tính trực tiếp, nhưng linkage xóa riêng tư phải được thiết kế trước khi bật dữ liệu thật. Seed có một plan nháp chỉ để kiểm tra liên kết FK, không phải plan được phép áp dụng qua API.

## Migration

Migration chạy bằng Flyway khi backend Spring Boot khởi động với `DATA_MODE=postgres` (bảng lịch sử `public.flyway_schema_history`, mỗi file trong một transaction, chỉ chạy file chưa áp dụng). Tên file theo mẫu `VNNN__<module>_<mô-tả>.sql`, số tăng liên tục, ví dụ `V009__accounts_sessions.sql`. Database đã migrate bằng runner Node cũ (`public.schema_migrations`) cần tạo lại: `npm run db:down`, xóa volume cục bộ của chính bạn, rồi chạy lại.

Không sửa migration đã được team áp dụng vào database chung. Thêm migration mới và mô tả ảnh hưởng cross-module trong PR. Tất cả ID ứng dụng là UUID; timestamp dùng timezone. Bảng daily metrics có khóa chính `(user_id, recorded_on)` và completions có khóa `(plan_id, activity_id, completed_on)` để chống dữ liệu trùng.

## Synthetic seed

Đặt `SEED_SYNTHETIC=true` khi chạy backend để nạp seed ngay sau migration (`system/SyntheticSeedRunner`). File seed dùng UUID cố định, `ON CONFLICT DO NOTHING`, không xóa hay cập nhật dữ liệu sẵn có. Chạy lại không nhân đôi bản ghi. Runner từ chối khi `APP_ENV=production`.

Tài khoản `@example.invalid` không có mật khẩu, không đăng nhập được. URL `example.invalid` và địa chỉ cơ sở là hư cấu. Quy tắc y tế nháp không được kích hoạt. Nhắc nhở seed đang tắt. Không có dữ liệu bệnh nhân thật hoặc yêu cầu gửi tới dịch vụ ngoài.

Mặc định mock mode dùng public fixture tại `backend/src/main/java/vn/edu/uit/se214/health/knowledge/SyntheticKnowledgeSearch.java`, cùng ID với SQL seed. Private fixtures trong database không có public API để xem. Chọn `DATA_MODE=postgres` để thử read-only API với database đã seed.

## Verification

`mvn -f backend/pom.xml verify` chạy toàn bộ migration trên PostgreSQL nhúng (không cần Docker), seed hai lần và kiểm tra FK, dữ liệu không trùng, draft filtering và điều kiện dữ liệu; job CI `infra-smoke` chạy lại trên PostgreSQL 17 thật. Docker connectivity, backup/restore và quyền DB triển khai thật vẫn phải kiểm tra ở môi trường tương ứng. Bản khung chưa có automated backup/restore.
