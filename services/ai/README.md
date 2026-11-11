# Dịch vụ AI (FastAPI)

Phần AI của M1 (Safety Gate, UC-07) và M4 (RAG, gọi LLM, kiểm tra đầu ra, UC-06). Chỉ Spring Boot gọi tới, trong mạng nội bộ, kèm header `X-Service-Key`.

```powershell
cd services/ai
python -m pip install -r requirements.txt
Copy-Item .env.example .env      # điền AI_SERVICE_KEY giống backend/.env
python -m uvicorn app.main:app --host 127.0.0.1 --port 8001
python -m pytest
```

| File            | Nội dung                                                                  |
| --------------- | ------------------------------------------------------------------------- |
| `app/main.py`   | Route: `GET /healthz`, `POST /v1/safety/evaluate`, `POST /v1/chat/answer` |
| `app/safety.py` | Safety Gate; chưa có quy tắc được duyệt thì trả `UNAVAILABLE`             |
| `app/llm.py`    | Adapter LLM tương thích OpenAI (OpenAI, Gemini)                           |
| `app/config.py` | Đọc và kiểm tra biến môi trường                                           |

Quy tắc: bộ quy tắc quyết định mức độ cần chú ý, LLM chỉ diễn đạt; chỉ dùng nguồn đã duyệt; không nhận họ tên, email; lỗi không lộ nội dung người dùng hay phản hồi của nhà cung cấp. Lỗi trả theo dạng `{"error": {code, message, requestId}}` giống backend.
