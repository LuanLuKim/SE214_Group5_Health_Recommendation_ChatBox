"""Settings for the statistics service (M3: UC-05 trends, UC-19 system statistics).

Secrets come from environment variables or services/stats/.env. No database is configured:
UC-05 trends are computed from the days Spring Boot sends (stateless, nothing stored). M3 adds a
read-only PostgreSQL connection (aggregate data only) when the UC-19 system statistics are built.
"""

import os
import secrets
from decimal import Decimal, InvalidOperation
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent


def _dotenv(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    return {**values, **os.environ}


ENV = _dotenv(BASE_DIR / ".env")

SECRET_KEY = ENV.get("DJANGO_SECRET_KEY") or secrets.token_urlsafe(32)
DEBUG = ENV.get("DJANGO_DEBUG", "false") == "true"
ALLOWED_HOSTS = [
    h.strip() for h in ENV.get("DJANGO_ALLOWED_HOSTS", "127.0.0.1,localhost,testserver").split(",") if h.strip()
]
STATS_SERVICE_KEY = ENV.get("STATS_SERVICE_KEY", "")


def _decimal(name: str, default: str) -> Decimal:
    try:
        return Decimal(ENV.get(name) or default)
    except InvalidOperation:
        return Decimal(default)


# UC-05 ngưỡng tham chiếu cho nhận xét xu hướng (cấu hình được, không phải chẩn đoán).
# Mặc định tạm thời, nhóm cần chốt lại với nguồn tham chiếu trước khi phát hành.
STATS_SLEEP_LOW_HOURS = _decimal("STATS_SLEEP_LOW_HOURS", "7")
STATS_SLEEP_LOW_DAYS = int(_decimal("STATS_SLEEP_LOW_DAYS", "5"))
STATS_SLEEP_WINDOW_DAYS = int(_decimal("STATS_SLEEP_WINDOW_DAYS", "7"))
STATS_WEIGHT_CHANGE_KG_WEEK = _decimal("STATS_WEIGHT_CHANGE_KG_WEEK", "2")
STATS_WEIGHT_CHANGE_KG_MONTH = _decimal("STATS_WEIGHT_CHANGE_KG_MONTH", "4")

INSTALLED_APPS = ["stats_api"]
MIDDLEWARE = ["stats_service.middleware.RequestContextMiddleware"]
ROOT_URLCONF = "stats_service.urls"
WSGI_APPLICATION = "stats_service.wsgi.application"
DATABASES: dict = {}

LANGUAGE_CODE = "vi"
TIME_ZONE = "Asia/Ho_Chi_Minh"
USE_TZ = True
DATA_UPLOAD_MAX_MEMORY_SIZE = 32 * 1024
