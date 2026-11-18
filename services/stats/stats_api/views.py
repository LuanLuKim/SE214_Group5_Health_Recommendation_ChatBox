import hmac
from functools import wraps

from django.conf import settings
from django.http import JsonResponse
from django.views.decorators.http import require_GET


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


@require_GET
@require_service_key
def metric_trends(request):
    return error(request, 501, "NOT_IMPLEMENTED", "Chức năng này đang chờ triển khai.")


@require_GET
@require_service_key
def system_statistics(request):
    return error(request, 501, "NOT_IMPLEMENTED", "Chức năng này đang chờ triển khai.")


def not_found(request, exception=None):
    return error(request, 404, "NOT_FOUND", "Không tìm thấy đường dẫn.")


def server_error(request):
    return error(request, 500, "INTERNAL_ERROR", "Không thể xử lý yêu cầu. Vui lòng thử lại.")
