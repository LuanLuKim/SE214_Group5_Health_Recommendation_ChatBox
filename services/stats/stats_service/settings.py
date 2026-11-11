"""Settings for the statistics service (M3: UC-05 trends, UC-19 system statistics).

Secrets come from environment variables or services/stats/.env. No database is configured yet:
M3 adds a read-only PostgreSQL connection (aggregate data only) when the first statistic is built.
"""

import os
import secrets
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

INSTALLED_APPS = ["stats_api"]
MIDDLEWARE = ["stats_service.middleware.RequestContextMiddleware"]
ROOT_URLCONF = "stats_service.urls"
WSGI_APPLICATION = "stats_service.wsgi.application"
DATABASES: dict = {}

LANGUAGE_CODE = "vi"
TIME_ZONE = "Asia/Ho_Chi_Minh"
USE_TZ = True
DATA_UPLOAD_MAX_MEMORY_SIZE = 32 * 1024
