# Database baseline

Một PostgreSQL instance, sáu schema cùng tên module. Không cần sáu database hoặc sáu server.

| Migration           | Schema    | Bảng nền tảng                                                       |
| ------------------- | --------- | ------------------------------------------------------------------- |
| `001_safety.sql`    | safety    | rules                                                               |
| `002_knowledge.sql` | knowledge | articles, facilities, review_events                                 |
| `003_health.sql`    | health    | profiles, daily_metrics, usage_events                               |
| `004_chat.sql`      | chat      | sessions, messages, feedback                                        |
| `005_accounts.sql`  | accounts  | users, user_roles, consents; thêm ownership FK cho các schema trước |
| `006_lifestyle.sql` | lifestyle | templates, plans, completions, reminders                            |

Đây là schema khởi đầu, chưa đủ để tuyên bố hoàn thành SRS. Khi triển khai nghiệp vụ, cần versioning/audit và reviewer metadata đầy đủ; thông tin bản quyền và ngày nguồn; session/token/OAuth/email state; dữ liệu eligibility/allergy phù hợp; lịch retry/delivery idempotency; chính sách retention và deletion cho feedback/backup.

Không suy luận quy tắc/plan được duyệt chỉ từ sự có mặt trong bảng. `review_events.subject_id` là tham chiếu đa loại ở mức ứng dụng; service phải kiểm tra loại và đối tượng. Feedback review snapshot không chứa danh tính trực tiếp, nhưng linkage xóa riêng tư phải được thiết kế trước khi bật dữ liệu thật. Seed có một plan nháp chỉ để kiểm tra liên kết FK, không phải plan được phép áp dụng qua API.

## Migration

Chạy `npm run db:migrate` ở root. Runner sử dụng transaction, advisory lock và `public.schema_migrations` để chỉ chạy file chưa áp dụng. Tên file tăng liên tục. Mỗi module giữ prefix module trong tên file mới, ví dụ `007_accounts_sessions.sql`.

Không sửa migration đã được team áp dụng vào database chung. Thêm migration mới và mô tả ảnh hưởng cross-module trong PR. Tất cả ID ứng dụng là UUID; timestamp dùng timezone. Bảng daily metrics có khóa chính `(user_id, recorded_on)` và completions có khóa `(plan_id, activity_id, completed_on)` để chống dữ liệu trùng.

## Synthetic seed

Chạy `npm run db:seed` sau migration. File seed dùng UUID cố định, `ON CONFLICT DO NOTHING`, không xóa hay cập nhật dữ liệu sẵn có. Chạy lại không nhân đôi bản ghi. Runner từ chối khi `NODE_ENV=production`.

Tài khoản `@example.invalid` không có mật khẩu, không đăng nhập được. URL `example.invalid` và địa chỉ cơ sở là hư cấu. Quy tắc y tế nháp không được kích hoạt. Nhắc nhở seed đang tắt. Không có dữ liệu bệnh nhân thật hoặc yêu cầu gửi tới dịch vụ ngoài.

Mặc định mock mode dùng public fixture tại `apps/api/src/modules/knowledge/synthetic.ts`, cùng ID với SQL seed. Private fixtures trong database không có public API để xem. Chọn `DATA_MODE=postgres` để thử read-only API với database đã seed.

## Verification

`npm test` chạy toàn bộ SQL trên PGlite/PostgreSQL nhúng, seed hai lần và kiểm tra FK, dữ liệu không trùng, draft filtering và điều kiện dữ liệu. Docker connectivity, backup/restore và quyền DB triển khai thật vẫn phải kiểm tra ở môi trường tương ứng. Bản khung chưa có automated backup/restore.
