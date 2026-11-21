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

Nhận xét theo ngưỡng cấu hình được qua biến môi trường `STATS_SLEEP_LOW_HOURS`, `STATS_SLEEP_LOW_DAYS`, `STATS_SLEEP_WINDOW_DAYS`, `STATS_WEIGHT_CHANGE_KG_WEEK`, `STATS_WEIGHT_CHANGE_KG_MONTH` (xem `.env.example`). Mặc định hiện tại (ngủ dưới 7 giờ ở 5/7 ngày, cân nặng đổi từ 2 kg/tuần hoặc 4 kg/tháng) chỉ là giá trị tạm thời để nhóm chốt. Nhận xét không phải chẩn đoán.
