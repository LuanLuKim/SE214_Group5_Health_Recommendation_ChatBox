"""UC-05: weekly and monthly trends of one person's weight and sleep.

Pure functions, no database and no state: Spring Boot reads the person's own days and sends only
dates, weights and sleep hours. Nothing is stored or logged here (NFR-08). The comments are
non-diagnostic and use configurable reference thresholds (settings STATS_*).
"""

import re
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP

from django.conf import settings

PERIOD_DAYS = {"week": 7, "month": 30}
MAX_POINTS = 31
WEIGHT_RANGE = (Decimal(20), Decimal(300))
SLEEP_RANGE = (Decimal(0), Decimal(24))
_ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_DISCLAIMER = "Đây chỉ là thông tin tham khảo, không phải chẩn đoán."
WARNING_SYMBOL = "⚠"
INFO_SYMBOL = "ℹ"


class InvalidTrendRequest(ValueError):
    """The request is malformed. The message names fields, never their values."""


def _to_date(value, field: str) -> date:
    if not isinstance(value, str) or not _ISO_DATE.match(value):
        raise InvalidTrendRequest(f"Trường {field} phải là ngày theo định dạng yyyy-MM-dd.")
    try:
        return date.fromisoformat(value)
    except ValueError:
        raise InvalidTrendRequest(f"Trường {field} phải là ngày theo định dạng yyyy-MM-dd.") from None


def _to_number(value, field: str, low: Decimal, high: Decimal):
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, Decimal)):
        raise InvalidTrendRequest(f"Trường {field} phải là số.")
    number = Decimal(value)
    if number < low or number > high:
        raise InvalidTrendRequest(f"Trường {field} nằm ngoài khoảng cho phép ({low} đến {high}).")
    return number


def parse_request(payload) -> tuple[str, date, date, list[dict]]:
    if not isinstance(payload, dict):
        raise InvalidTrendRequest("Dữ liệu phải là một đối tượng JSON.")
    period = payload.get("period")
    if period not in PERIOD_DAYS:
        raise InvalidTrendRequest("Trường period phải là week hoặc month.")
    start = _to_date(payload.get("from"), "from")
    end = _to_date(payload.get("to"), "to")
    if start > end or (end - start).days + 1 > MAX_POINTS:
        raise InvalidTrendRequest("Khoảng from đến to không hợp lệ (tối đa 31 ngày).")
    raw_points = payload.get("points")
    if not isinstance(raw_points, list) or len(raw_points) > MAX_POINTS:
        raise InvalidTrendRequest("Trường points phải là danh sách tối đa 31 ngày.")

    points, seen = [], set()
    for item in raw_points:
        if not isinstance(item, dict):
            raise InvalidTrendRequest("Mỗi phần tử của points phải là một đối tượng.")
        day = _to_date(item.get("date"), "points.date")
        if day < start or day > end:
            raise InvalidTrendRequest("Có ngày trong points nằm ngoài khoảng from đến to.")
        if day in seen:
            raise InvalidTrendRequest("Có ngày bị lặp trong points.")
        seen.add(day)
        weight = _to_number(item.get("weightKg"), "points.weightKg", *WEIGHT_RANGE)
        sleep = _to_number(item.get("sleepHours"), "points.sleepHours", *SLEEP_RANGE)
        if weight is None and sleep is None:
            raise InvalidTrendRequest("Mỗi ngày trong points cần ít nhất weightKg hoặc sleepHours.")
        points.append({"date": day, "weight": weight, "sleep": sleep})
    points.sort(key=lambda p: p["date"])
    return period, start, end, points


def _round1(value: Decimal) -> Decimal:
    return value.quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)


def _num(value: Decimal) -> float:
    return float(_round1(value))


def _vi(value: Decimal) -> str:
    """Vietnamese decimal comma, one decimal place."""
    return f"{_round1(value):.1f}".replace(".", ",")


def _dmy(day: date) -> str:
    return day.strftime("%d/%m/%Y")


def _stat(values: list[Decimal], unit: str, with_change: bool) -> dict | None:
    if not values:
        return None
    stat = {
        "unit": unit,
        "count": len(values),
        "average": _num(sum(values) / len(values)),
        "min": _num(min(values)),
        "max": _num(max(values)),
    }
    if with_change:
        stat.update({"first": _num(values[0]), "last": _num(values[-1]), "change": _num(values[-1] - values[0])})
    return stat


def _weight_threshold(period: str) -> Decimal:
    return settings.STATS_WEIGHT_CHANGE_KG_WEEK if period == "week" else settings.STATS_WEIGHT_CHANGE_KG_MONTH


def compute_trends(period: str, start: date, end: date, points: list[dict]) -> dict:
    base = {
        "period": period,
        "from": start.isoformat(),
        "to": end.isoformat(),
        "units": {"weightKg": "kg", "sleepHours": "giờ"},
    }
    if not points:  # A4: no trend comment without data
        return {**base, "status": "NO_DATA", "summary": None, "insights": [], "message": "Chưa có dữ liệu trong khoảng này."}

    weights = [p["weight"] for p in points if p["weight"] is not None]
    sleeps = [p["sleep"] for p in points if p["sleep"] is not None]
    summary = {"weight": _stat(weights, "kg", True), "sleep": _stat(sleeps, "giờ", False)}

    insights = []
    # Sleep: N or more of the most recent W days under the reference hours (default 5 of 7 under 7 h).
    window_days = settings.STATS_SLEEP_WINDOW_DAYS
    window_start = end - timedelta(days=window_days - 1)
    low = [p for p in points if p["sleep"] is not None and p["date"] >= window_start and p["sleep"] < settings.STATS_SLEEP_LOW_HOURS]
    if len(low) >= settings.STATS_SLEEP_LOW_DAYS:
        insights.append({
            "code": "SLEEP_LOW",
            "severity": "WARNING",
            "symbol": WARNING_SYMBOL,
            "message": (
                f"Lưu ý: {len(low)}/{window_days} ngày gần nhất ngủ dưới {_vi(settings.STATS_SLEEP_LOW_HOURS)} giờ. "
                + _DISCLAIMER
            ),
        })
    # Weight: change between the first and last record in the window at or above the reference kg.
    if len(weights) >= 2:
        change = weights[-1] - weights[0]
        if abs(change) >= _weight_threshold(period):
            direction = "tăng" if change > 0 else "giảm"
            insights.append({
                "code": "WEIGHT_CHANGE",
                "severity": "WARNING",
                "symbol": WARNING_SYMBOL,
                "message": (
                    f"Lưu ý: cân nặng {direction} {_vi(abs(change))} kg trong khoảng {_dmy(start)} đến {_dmy(end)}. "
                    + _DISCLAIMER
                ),
            })
    if not insights:
        insights.append({
            "code": "STABLE",
            "severity": "INFO",
            "symbol": INFO_SYMBOL,
            "message": "Chưa thấy chỉ số nào vượt ngưỡng tham chiếu trong khoảng này. " + _DISCLAIMER,
        })
    return {**base, "status": "OK", "summary": summary, "insights": insights, "message": None}
