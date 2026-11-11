# Build từ thư mục gốc repo: docker build -f services/ai/Dockerfile .
FROM python:3.12-slim
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY services/ai/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY services/ai/app app
USER app
EXPOSE 8001
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
