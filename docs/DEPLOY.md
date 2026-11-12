# Triển khai

Tài liệu này mô tả cách chạy hệ thống từ image đã build sẵn, trên máy demo hoặc một server có Docker. Chạy để phát triển trên máy cá nhân: xem [SETUP.md](SETUP.md).

## Thành phần

| Thành phần       | Image                                    | Cổng trong mạng nội bộ | Mở ra ngoài            |
| ---------------- | ---------------------------------------- | ---------------------- | ---------------------- |
| Backend          | `ghcr.io/luanlukim/se214-health-backend` | 4000                   | Có (qua reverse proxy) |
| Dịch vụ AI       | `ghcr.io/luanlukim/se214-health-ai`      | 8001                   | Không                  |
| Dịch vụ thống kê | `ghcr.io/luanlukim/se214-health-stats`   | 8002                   | Không                  |
| PostgreSQL       | `postgres:17-alpine`                     | 5432                   | Không                  |
| Frontend         | Bản build tĩnh `apps/web/dist`           | –                      | Có (hosting tĩnh)      |

Workflow CD (`.github/workflows/cd.yml`) build ba image mỗi lần merge vào `main` và gắn hai tag: `latest` và mã commit đầy đủ. Ba package đang ở chế độ public nên kéo về không cần đăng nhập. Bản build frontend là artifact `web-dist-<mã commit>` của cùng lượt chạy (giữ 14 ngày).

Mỗi image có sẵn healthcheck gọi `/healthz`; `deploy/compose.yaml` dựa vào healthcheck này để khởi động theo thứ tự, nên không chạy được với image build trước khi có healthcheck (trước issue #98). Backend tự chạy migration (Flyway) khi khởi động.

## Chạy bằng Docker Compose

Cần Docker Engine có plugin Compose. Chỉ cần hai file `deploy/compose.yaml` và `deploy/.env.example`, không cần cả repo.

```bash
cp deploy/.env.example deploy/.env        # điền giá trị theo bảng dưới
docker compose -f deploy/compose.yaml pull
docker compose -f deploy/compose.yaml up -d --wait
```

`--wait` chỉ trả về khi cả bốn container healthy. Thiếu một biến bắt buộc thì Compose dừng và nêu tên biến.

### Biến trong `deploy/.env`

| Biến                                                      | Bắt buộc | Ý nghĩa                                                                                                       |
| --------------------------------------------------------- | -------- | ------------------------------------------------------------------------------------------------------------- |
| `IMAGE_PREFIX`                                            | Có       | Tiền tố tên image do CD đẩy lên, đã điền sẵn trong file mẫu. Chỉ sửa khi repo đổi chủ hoặc dùng registry khác |
| `IMAGE_TAG`                                               |          | `latest` hoặc mã commit đầy đủ trên `main`. Dùng mã commit để cố định phiên bản                               |
| `DATABASE_USER`, `DATABASE_PASSWORD`                      | Có       | Tài khoản PostgreSQL. Không dùng lại `local_dev_only`                                                         |
| `WEB_ORIGIN`                                              | Có       | Địa chỉ frontend được phép gọi API (CORS), ví dụ `https://demo.example.vn`, không có `/` ở cuối               |
| `AI_SERVICE_KEY`, `STATS_SERVICE_KEY`                     | Có       | Khóa giữa backend và từng dịch vụ Python. Chuỗi ngẫu nhiên, mỗi khóa một giá trị                              |
| `DJANGO_SECRET_KEY`                                       | Có       | Khóa bí mật của Django                                                                                        |
| `BACKEND_BIND`, `BACKEND_PORT`                            |          | Mặc định `127.0.0.1:4000`. Đặt `BACKEND_BIND=0.0.0.0` chỉ khi demo trong mạng nội bộ không có reverse proxy   |
| `FORWARD_HEADERS`                                         |          | `native` khi có reverse proxy phía trước, `none` khi không có                                                 |
| `APP_ENV`, `SEED_SYNTHETIC`                               |          | Mặc định `production` và `false`. Máy demo cần dữ liệu mẫu: `APP_ENV=demo`, `SEED_SYNTHETIC=true`             |
| `LLM_ENABLED`, `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL` |          | Chỉ dịch vụ AI đọc. Giữ `LLM_ENABLED=false` cho tới khi chat (M4) được nối                                    |

Tạo chuỗi ngẫu nhiên: `openssl rand -hex 32`. Bí mật chỉ nằm trong `deploy/.env` trên máy chạy (Git bỏ qua file này) hoặc trong kho secret của nền tảng. Không đưa vào commit, issue, chat.

`FORWARD_HEADERS=native` làm backend lấy IP người dùng từ `X-Forwarded-For` do proxy gửi, và chỉ tin proxy ở dải địa chỉ nội bộ. Nếu có proxy mà để `none`, giới hạn 120 request/phút tính chung mọi người dùng vào IP của proxy. Không có proxy thì để `none`, vì máy trong cùng mạng nội bộ có thể tự gửi header này để né giới hạn.

`APP_ENV=production` cùng `SEED_SYNTHETIC=true` làm backend dừng ngay khi khởi động: dữ liệu giả lập không được vào môi trường thật.

## Reverse proxy và HTTPS

Backend chỉ nói HTTP. Đặt một reverse proxy (Caddy, Nginx, hoặc proxy của nền tảng) phía trước để có HTTPS và chuyển tiếp tới `127.0.0.1:4000`. Proxy phải gửi `X-Forwarded-For`. Không mở cổng 5432, 8001, 8002 ra ngoài.

## Frontend

Frontend là trang tĩnh. Build với địa chỉ backend rồi đưa thư mục `apps/web/dist` lên hosting tĩnh (GitHub Pages, Netlify, Vercel, hoặc chính reverse proxy):

```bash
VITE_API_ORIGIN=https://api.example.vn npm run build
```

`VITE_API_ORIGIN` nằm trong bundle công khai, không được chứa bí mật. Nếu proxy phục vụ frontend và chuyển `/api`, `/healthz` tới backend trên cùng một tên miền thì để trống `VITE_API_ORIGIN`. Địa chỉ frontend phải khớp `WEB_ORIGIN` của backend.

## Kiểm tra sau khi chạy

```bash
docker compose -f deploy/compose.yaml ps                       # bốn container healthy
curl -fsS http://127.0.0.1:4000/healthz                        # {"data":{"status":"ok",…}}
curl -fsS "http://127.0.0.1:4000/api/v1/knowledge/articles"    # meta.status = DATABASE_RESULTS
```

Qua tên miền công khai: mở frontend, tìm một bài viết, kiểm tra trình duyệt không báo lỗi CORS. Ghi kết quả vào task Jira của lần triển khai.

## Cập nhật và quay lại bản trước

```bash
# Cập nhật: sửa IMAGE_TAG trong deploy/.env (hoặc giữ latest), rồi
docker compose -f deploy/compose.yaml pull
docker compose -f deploy/compose.yaml up -d --wait

# Quay lại: đặt IMAGE_TAG về mã commit của bản chạy tốt gần nhất, rồi chạy lại hai lệnh trên
```

Migration chỉ đi tới, không tự lùi. Nếu bản mới đã áp dụng migration mà bản cũ không tương thích, khôi phục database từ bản sao lưu trước khi quay lại.

## Sao lưu dữ liệu

```bash
docker compose -f deploy/compose.yaml exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' > backup.sql
```

Dữ liệu nằm trong volume `postgres_data`. Lệnh `docker compose down` giữ volume; thêm `--volumes` sẽ **xóa toàn bộ dữ liệu**.

## Xem log và xử lý sự cố

```bash
docker compose -f deploy/compose.yaml logs --tail 100 backend
```

- Lỗi 500 hoặc 503 trả cho người dùng có `requestId`; tìm dòng log backend có cùng `requestId` để biết lớp ngoại lệ và vị trí trong code.
- Backend khởi động lại liên tục: xem log. Thường do sai mật khẩu database, Flyway báo checksum khác (đã sửa một migration cũ), hoặc `SEED_SYNTHETIC=true` cùng `APP_ENV=production`.
- Frontend báo lỗi CORS: `WEB_ORIGIN` chưa khớp chính xác địa chỉ frontend (giao thức, tên miền, cổng).
- Mọi người dùng cùng bị 429: có reverse proxy nhưng `FORWARD_HEADERS` chưa là `native`.

## Kiểm tra tự động

Job CI `compose-smoke` chạy trên mỗi lần push, gồm hai phần:

- Dựng cả stack bằng `compose.yaml` (profile `full`): build ba image, chờ healthcheck, gọi API qua backend, gọi hai dịch vụ Python từ trong mạng nội bộ.
- Chạy `deploy/compose.yaml` theo đúng các bước ở trên với ba image vừa build: kiểm tra file `.env` chưa điền bí mật bị từ chối, điền `deploy/.env` từ `deploy/.env.example`, chờ bốn container healthy, tìm kiếm trả dữ liệu, khóa dịch vụ khớp giữa backend và hai dịch vụ Python, chỉ backend mở cổng ra máy chủ.

## Chưa có

- Nơi chạy cố định (server, tên miền, HTTPS) và workflow tự triển khai: nhóm chọn nền tảng rồi bổ sung.
- Tài khoản mẫu cho bốn vai trò: chờ module tài khoản (M5).
- Dữ liệu NHS/WHO đã crawl chỉ dùng nội bộ; không đưa nội dung chưa duyệt lên bản demo công khai.
