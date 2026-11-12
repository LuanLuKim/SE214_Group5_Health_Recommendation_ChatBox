"""FastAPI entry point. Run: uvicorn app.main:app --host 127.0.0.1 --port 8001 (from services/ai)."""

from __future__ import annotations

import hmac
import uuid
from pathlib import Path

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import safety
from .config import Settings, load_settings, read_dotenv


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        self.status, self.code, self.message = status, code, message


def _error(request: Request, status: int, code: str, message: str) -> JSONResponse:
    request_id = getattr(request.state, "request_id", "")
    return JSONResponse(
        status_code=status,
        content={"error": {"code": code, "message": message, "requestId": request_id}},
    )


class EvaluateRequest(BaseModel):
    model_config = {"extra": "forbid"}
    text: str | None = Field(default=None, min_length=1, max_length=4000)
    facts: list[str] = Field(default_factory=list, max_length=100)
    rules: list[safety.WarningRule] = Field(default_factory=list, max_length=100)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or load_settings(read_dotenv(Path(__file__).resolve().parent.parent / ".env"))
    app = FastAPI(title="health-assistant-ai", version="0.1.0")

    @app.middleware("http")
    async def request_context(request: Request, call_next):
        request.state.request_id = str(uuid.uuid4())
        response = await call_next(request)
        response.headers["X-Request-ID"] = request.state.request_id
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        return response

    @app.exception_handler(ApiError)
    async def api_error(request: Request, error: ApiError):
        return _error(request, error.status, error.code, error.message)

    @app.exception_handler(RequestValidationError)
    async def invalid(request: Request, _error_: RequestValidationError):
        # Validation details may echo user text; never return them.
        return _error(request, 400, "INVALID_REQUEST", "Dữ liệu yêu cầu không hợp lệ.")

    @app.exception_handler(StarletteHTTPException)
    async def http_error(request: Request, error: StarletteHTTPException):
        if error.status_code == 404:
            return _error(request, 404, "NOT_FOUND", "Không tìm thấy đường dẫn.")
        if error.status_code == 405:
            return _error(request, 405, "METHOD_NOT_ALLOWED", "Phương thức không được hỗ trợ.")
        return _error(request, error.status_code, "HTTP_ERROR", "Không thể xử lý yêu cầu.")

    @app.exception_handler(Exception)
    async def internal(request: Request, _exc: Exception):
        return _error(request, 500, "INTERNAL_ERROR", "Không thể xử lý yêu cầu. Vui lòng thử lại.")

    def require_service_key(request: Request) -> None:
        """Only Spring Boot may call /v1, with the shared key in X-Service-Key."""
        if not settings.service_key:
            raise ApiError(503, "SERVICE_KEY_NOT_CONFIGURED", "Dịch vụ AI chưa được cấu hình khóa dịch vụ.")
        supplied = request.headers.get("X-Service-Key", "")
        if not hmac.compare_digest(supplied.encode(), settings.service_key.encode()):
            raise ApiError(401, "UNAUTHORIZED", "Khóa dịch vụ không hợp lệ.")

    @app.get("/healthz")
    def healthz():
        return {"data": {"status": "ok", "service": "health-assistant-ai", "mode": "scaffold"}}

    @app.post("/v1/safety/evaluate", dependencies=[Depends(require_service_key)])
    def evaluate(body: EvaluateRequest):
        return {"data": safety.evaluate_facts(set(body.facts), body.rules).model_dump()}

    @app.post("/v1/chat/answer", dependencies=[Depends(require_service_key)])
    def answer():
        # M4: Safety Gate first, then approved RAG sources, LLM, output checks, SSE streaming.
        raise ApiError(501, "NOT_IMPLEMENTED", "Chức năng này đang chờ triển khai.")

    return app


app = create_app()
