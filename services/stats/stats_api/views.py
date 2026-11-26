import hmac
import json
from decimal import Decimal
from functools import wraps

from django.conf import settings
from django.http import JsonResponse
from django.views.decorators.http import require_GET, require_POST

from .trends import InvalidTrendRequest, compute_trends, parse_request
from .system_stats import InvalidStatisticsRequest, compute_statistics
from .system_stats import parse_request as parse_statistics_request


def error(request, status: int, code: str, message: str) -> JsonResponse:
    request_id = getattr(request, "request_id", "")
    return JsonResponse(
        {"error": {"code": code, "message": message, "requestId": request_id}},
        status=status,
        json_dumps_params={"ensure_ascii": False},
    )


def require_service_key(view):
    """Only Spring Boot may call /v1, with the shared key in X-Service-Key."""

    @wraps(view)
    def wrapper(request, *args, **kwargs):
        expected = settings.STATS_SERVICE_KEY
        if not expected:
            return error(request, 503, "SERVICE_KEY_NOT_CONFIGURED", "Dịch vụ thống kê chưa được cấu hình khóa dịch vụ.")
        supplied = request.headers.get("X-Service-Key", "")
        if not hmac.compare_digest(supplied.encode(), expected.encode()):
            return error(request, 401, "UNAUTHORIZED", "Khóa dịch vụ không hợp lệ.")
        return view(request, *args, **kwargs)

    return wrapper


@require_GET
def healthz(request):
    return JsonResponse({"data": {"status": "ok", "service": "health-assistant-stats", "mode": "scaffold"}})


@require_service_key
@require_POST
def metric_trends(request):
    """UC-05. Spring Boot posts one person's days in a window; the body is never stored or logged."""
    try:
        payload = json.loads(request.body.decode("utf-8"), parse_float=Decimal, parse_constant=_reject_constant)
    except (UnicodeDecodeError, ValueError):
        return error(request, 400, "INVALID_JSON", "Nội dung gửi lên không phải JSON hợp lệ.")
    try:
        period, start, end, points = parse_request(payload)
    except InvalidTrendRequest as exc:
        return error(request, 400, "TRENDS_INVALID", str(exc))
    return JsonResponse({"data": compute_trends(period, start, end, points)}, json_dumps_params={"ensure_ascii": False})


def _reject_constant(name):
    raise ValueError(name)


@require_service_key
@require_POST
def system_statistics(request):
    """UC-19. Spring Boot posts per-day counts of aggregate events; nothing is stored or logged."""
    try:
        payload = json.loads(request.body.decode("utf-8"), parse_constant=_reject_constant)
    except (UnicodeDecodeError, ValueError):
        return error(request, 400, "INVALID_JSON", "Nội dung gửi lên không phải JSON hợp lệ.")
    try:
        start, end, days = parse_statistics_request(payload)
    except InvalidStatisticsRequest as exc:
        return error(request, 400, "STATISTICS_INVALID", str(exc))
    return JsonResponse({"data": compute_statistics(start, end, days)}, json_dumps_params={"ensure_ascii": False})


def not_found(request, exception=None):
    return error(request, 404, "NOT_FOUND", "Không tìm thấy đường dẫn.")


def server_error(request):
    return error(request, 500, "INTERNAL_ERROR", "Không thể xử lý yêu cầu. Vui lòng thử lại.")
