"""UC-19: system statistics for an administrator.

Pure functions, no database and no state. Spring Boot counts health.usage_events per calendar
day and sends only dates and counts; this module fills the days without events, sums the totals
and labels the series. No user, email, chat message or health value ever reaches it (M-11).
"""

import re
from datetime import date, timedelta

from django.conf import settings

COUNT_FIELDS = ("newAccounts", "visits", "chatInteractions")
METRICS = [
    {"key": "newAccounts", "label": "Tài khoản mới", "unit": "tài khoản"},
    {"key": "visits", "label": "Lượt truy cập", "unit": "lượt"},
    {"key": "chatInteractions", "label": "Lượt tương tác chatbot", "unit": "lượt"},
]
EMPTY_MESSAGE = "Không có dữ liệu trong khoảng thời gian đã chọn."
_ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


class InvalidStatisticsRequest(ValueError):
    """The request is malformed. The message names fields, never their values."""


def _to_date(value, field: str) -> date:
    if not isinstance(value, str) or not _ISO_DATE.match(value):
        raise InvalidStatisticsRequest(f"Trường {field} phải là ngày theo định dạng yyyy-MM-dd.")
    try:
        return date.fromisoformat(value)
    except ValueError:
        raise InvalidStatisticsRequest(f"Trường {field} phải là ngày theo định dạng yyyy-MM-dd.") from None


def _to_count(value, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise InvalidStatisticsRequest(f"Trường {field} phải là số nguyên không âm.")
    return value


def parse_request(payload) -> tuple[date, date, dict[date, dict[str, int]]]:
    """Validate {from, to, days: [{date, newAccounts, visits, chatInteractions}]}."""
    if not isinstance(payload, dict):
        raise InvalidStatisticsRequest("Dữ liệu phải là một đối tượng JSON.")
    start = _to_date(payload.get("from"), "from")
    end = _to_date(payload.get("to"), "to")
    if start > end:
        raise InvalidStatisticsRequest("Ngày bắt đầu không được sau ngày kết thúc.")
    span = (end - start).days + 1
    if span > settings.STATS_SYSTEM_MAX_DAYS:
        raise InvalidStatisticsRequest(f"Khoảng thời gian tối đa là {settings.STATS_SYSTEM_MAX_DAYS} ngày.")

    rows = payload.get("days", [])
    if not isinstance(rows, list) or len(rows) > span:
        raise InvalidStatisticsRequest("Trường days phải là danh sách, mỗi ngày tối đa một phần tử.")
    days: dict[date, dict[str, int]] = {}
    for row in rows:
        if not isinstance(row, dict):
            raise InvalidStatisticsRequest("Mỗi phần tử của days phải là một đối tượng.")
        day = _to_date(row.get("date"), "days[].date")
        if day < start or day > end:
            raise InvalidStatisticsRequest("Ngày trong days phải nằm trong khoảng from đến to.")
        if day in days:
            raise InvalidStatisticsRequest("Mỗi ngày chỉ được xuất hiện một lần trong days.")
        days[day] = {field: _to_count(row.get(field, 0), f"days[].{field}") for field in COUNT_FIELDS}
    return start, end, days


def compute_statistics(start: date, end: date, days: dict[date, dict[str, int]]) -> dict:
    """Every day of the range in order (zero when no event), totals, labels and the A3 message."""
    series = []
    totals = dict.fromkeys(COUNT_FIELDS, 0)
    day = start
    while day <= end:
        counts = days.get(day, dict.fromkeys(COUNT_FIELDS, 0))
        series.append({"date": day.isoformat(), **counts})
        for field in COUNT_FIELDS:
            totals[field] += counts[field]
        day += timedelta(days=1)
    has_data = any(totals.values())
    return {
        "from": start.isoformat(),
        "to": end.isoformat(),
        "hasData": has_data,
        "totals": totals,
        "series": series,
        "metrics": METRICS,
        "message": None if has_data else EMPTY_MESSAGE,
    }
