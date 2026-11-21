# Dịch vụ thống kê (Django)

Phần thống kê của M3: xu hướng chỉ số tuần/tháng (UC-05) và thống kê hệ thống cho Quản trị viên (UC-19). Chỉ Spring Boot gọi tới, trong mạng nội bộ, kèm header `X-Service-Key`.

```powershell
cd services/stats
python -m pip install -r requirements.txt
Copy-Item .env.example .env      # điền STATS_SERVICE_KEY giống backend/.env
python manage.py runserver 127.0.0.1:8002
python manage.py test
```

Route:

- `GET /healthz`.
- `GET /v1/metrics/trends` (UC-05, đang trả 501).
- `POST /v1/system/statistics` (UC-19, `stats_api/system_stats.py`): nhận `{from, to, days: [{date, newAccounts, visits, chatInteractions}]}` do Spring Boot đếm từ `health.usage_events`, trả mọi ngày trong khoảng (ngày không có sự kiện là 0), tổng, nhãn và đơn vị từng chuỗi, `hasData` và thông báo khi không có dữ liệu. Sai dữ liệu: 400 `STATISTICS_INVALID` (thông báo nêu tên trường, không lặp lại giá trị). Khoảng rộng nhất `STATS_SYSTEM_MAX_DAYS` ngày (mặc định 366, giữ bằng `HEALTH_STATS_MAX_DAYS` của backend).

Không cấu hình database: Spring Boot đọc dữ liệu và chỉ gửi số liệu tổng hợp mà từng thống kê cần; dịch vụ này không lưu và không ghi log nội dung nhận được, không bao giờ nhận dữ liệu sức khỏe của từng người cho Quản trị viên.
