# Dịch vụ AI (FastAPI)

Phần AI của M1 (Safety Gate, UC-07) và M4 (RAG, gọi LLM, kiểm tra đầu ra, UC-06). Chỉ Spring Boot gọi tới, trong mạng nội bộ, kèm header `X-Service-Key`.

```powershell
cd services/ai
python -m pip install -r requirements.txt
if (-not (Test-Path ../../.env)) { Copy-Item ../../.env.example ../../.env }
python -m uvicorn app.main:app --host 127.0.0.1 --port 8001
python -m pytest -c ../../tests/services/ai/pytest.ini ../../tests/services/ai
```

| File            | Nội dung                                                                  |
| --------------- | ------------------------------------------------------------------------- |
| `app/main.py`   | Route: `GET /healthz`, `POST /v1/safety/evaluate`, `POST /v1/chat/answer` |
| `app/safety.py` | Safety Gate; chưa có quy tắc được duyệt thì trả `UNAVAILABLE`             |
| `app/llm.py`    | Adapter LLM tương thích OpenAI (OpenAI, Gemini)                           |
| `app/config.py` | Đọc và kiểm tra biến môi trường                                           |

Quy tắc: bộ quy tắc quyết định mức độ cần chú ý, LLM chỉ diễn đạt; chỉ dùng nguồn đã duyệt; không nhận họ tên, email; lỗi không lộ nội dung người dùng hay phản hồi của nhà cung cấp. Lỗi trả theo dạng `{"error": {code, message, requestId}}` giống backend.

## Chatbox qua router LLM local

Điền trong `.env` ở gốc repo (không phải `services/ai/.env`):

- `AI_LLM_BASE_URL`: base URL OpenAI-compatible của router LLM local, không kèm `/chat/completions`.
- `AI_LLM_MODEL`: model ID hoặc alias chính xác mà router cung cấp.
- `AI_LLM_API_KEY`: key riêng của router nếu router yêu cầu xác thực; nếu không, để trống.
- `AI_LLM_ENABLED=true`: chỉ bật sau khi điền URL và model. Mặc định tắt, không có provider fallback.

AI chạy trực tiếp trên Windows dùng `http://127.0.0.1:<port>/v1` (thay `<port>` bằng port thật).
AI chạy trong Docker Desktop còn router chạy trên Windows dùng
`http://host.docker.internal:<port>/v1`; `127.0.0.1` trong container là chính container.
Router phải cho phép kết nối từ Docker; không mở router ra mạng công khai chỉ để kết nối thử.
Trên Linux, hostname Docker này có thể cần cấu hình host-gateway riêng.

HTTP chỉ được chấp nhận cho `localhost`, `127.0.0.1`, `[::1]`, `host.docker.internal`.
Endpoint khác bắt buộc HTTPS và có API key. Local không có key thì không gửi header Authorization.
Không tắt kiểm tra chứng chỉ HTTPS. HTTP local không mã hóa và không phù hợp để mở ra Internet.

`TRANSLATE_LLM_*` chỉ dành cho module dịch offline (endpoint/model/key dịch riêng);
`REVIEW_LLM_*` dành cho review offline. Chatbox không đọc hoặc fallback sang hai nhóm này.
Không chép key dịch vào `AI_LLM_API_KEY`; không đặt key trong `VITE_*`.
