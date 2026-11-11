# Dịch vụ thống kê (Django)

Phần thống kê của M3: xu hướng chỉ số tuần/tháng (UC-05) và thống kê hệ thống cho Quản trị viên (UC-19). Chỉ Spring Boot gọi tới, trong mạng nội bộ, kèm header `X-Service-Key`.

```powershell
cd services/stats
python -m pip install -r requirements.txt
Copy-Item .env.example .env      # điền STATS_SERVICE_KEY giống backend/.env
python manage.py runserver 127.0.0.1:8002
python manage.py test
```

Route: `GET /healthz`, `GET /v1/metrics/trends`, `GET /v1/system/statistics` (hai route sau đang trả 501). Chưa cấu hình database; khi làm thống kê đầu tiên, M3 thêm kết nối PostgreSQL chỉ đọc và chỉ dùng số liệu tổng hợp, không trả dữ liệu sức khỏe của từng người cho Quản trị viên.
