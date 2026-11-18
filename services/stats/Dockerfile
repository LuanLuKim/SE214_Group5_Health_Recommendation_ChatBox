# Build từ thư mục gốc repo: docker build -f services/stats/Dockerfile .
FROM python:3.12-slim
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY services/stats/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt gunicorn==23.0.0
COPY services/stats .
USER app
EXPOSE 8002
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=6 CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8002/healthz', timeout=3)" || exit 1
CMD ["gunicorn", "stats_service.wsgi", "--bind", "0.0.0.0:8002", "--workers", "2"]
