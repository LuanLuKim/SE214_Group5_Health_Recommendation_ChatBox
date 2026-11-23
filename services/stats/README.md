# Dịch vụ thống kê (Django)

Phần thống kê của M3: xu hướng chỉ số tuần/tháng (UC-05) và thống kê hệ thống cho Quản trị viên (UC-19). Chỉ Spring Boot gọi tới, trong mạng nội bộ, kèm header `X-Service-Key`.

```powershell
cd services/stats
python -m pip install -r requirements.txt
Copy-Item .env.example .env      # điền STATS_SERVICE_KEY giống backend/.env
python manage.py runserver 127.0.0.1:8002
python manage.py test
```

Route: `GET /healthz`, `POST /v1/metrics/trends`, `GET /v1/system/statistics` (route cuối đang trả 501).

`POST /v1/metrics/trends` (UC-05, cần `X-Service-Key`): Spring Boot gửi các ngày đã ghi của chính người dùng trong khoảng xem và nhận lại thống kê và nhận xét. Dịch vụ không có cơ sở dữ liệu, không lưu và không log nội dung gửi lên.

```json
{"period": "week", "from": "2026-10-22", "to": "2026-10-28",
 "points": [{"date": "2026-10-26", "weightKg": 65.0, "sleepHours": 7.0}]}
```

Trả `{"data": {"status": "OK|NO_DATA", "summary": {"weight": {...}, "sleep": {...}}, "insights": [{"code", "severity", "symbol", "message"}], ...}}`. `period` là `week` (7 ngày) hoặc `month` (30 ngày), tối đa 31 ngày và 31 điểm. Lỗi: 400 `INVALID_JSON`, 400 `TRENDS_INVALID` (thông báo nêu tên trường, không lặp lại giá trị).

Nhận xét theo ngưỡng cấu hình được qua biến môi trường (đơn vị và ý nghĩa từng biến ở `.env.example`). Nhận xét chỉ là thông tin tham khảo, không phải chẩn đoán.

| Biến | Mặc định | Nguồn tham chiếu |
| --- | --- | --- |
| `STATS_SLEEP_LOW_HOURS` | 7 giờ | Khuyến nghị đồng thuận của AASM và SRS (2015): người trưởng thành 18–60 tuổi nên ngủ từ 7 giờ trở lên mỗi đêm một cách đều đặn. Watson NF và cộng sự, *Recommended Amount of Sleep for a Healthy Adult*, Sleep 2015;38(6):843–844, doi:10.5665/sleep.4716. |
| `STATS_SLEEP_LOW_DAYS` | 5 ngày | Ngưỡng nhóm chọn (ví dụ "5/7 ngày" trong đặc tả UC-05). Khuyến nghị AASM/SRS nói "đều đặn" nhưng không nêu số ngày. |
| `STATS_SLEEP_WINDOW_DAYS` | 7 ngày | Ngưỡng nhóm chọn, đi cùng ngưỡng 5 ngày ở trên. |
| `STATS_WEIGHT_CHANGE_KG_WEEK` | 2 kg | Ngưỡng nhóm chọn. Để so sánh, CDC khuyến nghị giảm cân từ từ, khoảng 1–2 pound (0,45–0,9 kg) mỗi tuần; đây là hướng dẫn cho người chủ động giảm cân, không phải tiêu chuẩn chẩn đoán và không nói về tăng cân. |
| `STATS_WEIGHT_CHANGE_KG_MONTH` | 4 kg | Ngưỡng nhóm chọn, bằng hai lần ngưỡng theo tuần. |

Các ngưỡng "nhóm chọn" là một bộ số dùng chung cho báo cáo và kiểm thử; muốn đổi thì đổi biến môi trường rồi cập nhật bảng này.
Route:

- `GET /healthz`.
- `POST /v1/system/statistics` (UC-19, `stats_api/system_stats.py`): nhận `{from, to, days: [{date, newAccounts, visits, chatInteractions}]}` do Spring Boot đếm từ `health.usage_events`, trả mọi ngày trong khoảng (ngày không có sự kiện là 0), tổng, nhãn và đơn vị từng chuỗi, `hasData` và thông báo khi không có dữ liệu. Sai dữ liệu: 400 `STATISTICS_INVALID` (thông báo nêu tên trường, không lặp lại giá trị). Khoảng rộng nhất `STATS_SYSTEM_MAX_DAYS` ngày (mặc định 366, giữ bằng `HEALTH_STATS_MAX_DAYS` của backend).

Không cấu hình database: Spring Boot đọc dữ liệu và chỉ gửi số liệu tổng hợp mà từng thống kê cần; dịch vụ này không lưu và không ghi log nội dung nhận được, không bao giờ nhận dữ liệu sức khỏe của từng người cho Quản trị viên.
