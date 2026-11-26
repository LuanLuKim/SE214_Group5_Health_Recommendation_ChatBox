import json
from pathlib import Path

from django.conf import settings
from django.test import SimpleTestCase, override_settings

KEY = {"X-Service-Key": "synthetic-service-key"}
URL = "/v1/metrics/trends"


def day(n):
    return f"2026-10-{n:02d}"


def point(n, weight=None, sleep=None):
    return {"date": day(n), "weightKg": weight, "sleepHours": sleep}


def body(points, period="week", start=22, end=28):
    return {"period": period, "from": day(start), "to": day(end), "points": points}


@override_settings(STATS_SERVICE_KEY="synthetic-service-key")
class TrendsTest(SimpleTestCase):
    def post(self, payload, raw=None):
        data = raw if raw is not None else json.dumps(payload)
        return self.client.post(URL, data=data, content_type="application/json", headers=KEY)

    def insight_codes(self, response):
        return [i["code"] for i in response.json()["data"]["insights"]]

    # --- routing and access ---
    def test_trends_need_the_service_key_before_anything_else(self):
        self.assertEqual(self.client.post(URL, data="{}", content_type="application/json").status_code, 401)
        self.assertEqual(self.client.get(URL).status_code, 401)

    def test_only_post_is_allowed_with_a_key(self):
        self.assertEqual(self.client.get(URL, headers=KEY).status_code, 405)

    # --- A4: no data, no trend comment ---
    def test_no_points_gives_no_data_and_no_comment(self):
        data = self.post(body([])).json()["data"]
        self.assertEqual(data["status"], "NO_DATA")
        self.assertIsNone(data["summary"])
        self.assertEqual(data["insights"], [])
        self.assertIn("Chưa có dữ liệu", data["message"])

    # --- summary with units ---
    def test_summary_has_average_min_max_and_the_weight_change(self):
        data = self.post(body([point(26, 65, 7), point(27, 65.2, 7.5), point(28, 65.1, 6.5)])).json()["data"]
        self.assertEqual(data["status"], "OK")
        self.assertEqual(data["units"], {"weightKg": "kg", "sleepHours": "giờ"})
        weight, sleep = data["summary"]["weight"], data["summary"]["sleep"]
        self.assertEqual(weight["unit"], "kg")
        self.assertEqual((weight["count"], weight["average"], weight["min"], weight["max"]), (3, 65.1, 65.0, 65.2))
        self.assertEqual((weight["first"], weight["last"], weight["change"]), (65.0, 65.1, 0.1))
        self.assertEqual(sleep["unit"], "giờ")
        self.assertEqual((sleep["average"], sleep["min"], sleep["max"]), (7.0, 6.5, 7.5))

    def test_a_metric_that_is_never_recorded_has_no_summary(self):
        data = self.post(body([point(26, None, 7)])).json()["data"]
        self.assertIsNone(data["summary"]["weight"])
        self.assertIsNotNone(data["summary"]["sleep"])

    def test_averages_round_half_up_not_half_even(self):
        data = self.post(body([point(26, 65.0), point(27, 65.1)])).json()["data"]
        self.assertEqual(data["summary"]["weight"]["average"], 65.1)  # 65.05

    # --- sleep comment: 5 of 7 days under 7 hours ---
    def test_five_of_seven_short_nights_warn_with_text_and_symbol(self):
        sleeps = [6, 6, 6.5, 5, 6.9, 7.5, 8]
        response = self.post(body([point(22 + i, sleep=h) for i, h in enumerate(sleeps)]))
        insight = response.json()["data"]["insights"][0]
        self.assertEqual((insight["code"], insight["severity"], insight["symbol"]), ("SLEEP_LOW", "WARNING", "⚠"))
        self.assertIn("5/7 ngày", insight["message"])
        self.assertIn("không phải chẩn đoán", insight["message"])

    def test_four_short_nights_do_not_warn(self):
        sleeps = [6, 6, 6.5, 5, 7, 7.5, 8]
        response = self.post(body([point(22 + i, sleep=h) for i, h in enumerate(sleeps)]))
        self.assertEqual(self.insight_codes(response), ["STABLE"])

    def test_only_the_last_seven_days_of_a_month_count(self):
        early = [point(n, sleep=5) for n in range(1, 8)]  # short nights long before the end
        late = [point(n, sleep=8) for n in range(22, 29)]
        response = self.post(body(early + late, period="month", start=1, end=28))
        self.assertEqual(self.insight_codes(response), ["STABLE"])

    @override_settings(STATS_SLEEP_LOW_DAYS=3)
    def test_the_threshold_is_configurable(self):
        response = self.post(body([point(26, sleep=6), point(27, sleep=6), point(28, sleep=6)]))
        self.assertEqual(self.insight_codes(response), ["SLEEP_LOW"])

    # --- weight comment ---
    def test_a_weight_gain_over_the_weekly_threshold_warns(self):
        response = self.post(body([point(22, 70), point(28, 72.3)]))
        insight = response.json()["data"]["insights"][0]
        self.assertEqual(insight["code"], "WEIGHT_CHANGE")
        self.assertIn("tăng 2,3 kg", insight["message"])
        self.assertIn("22/10/2026 đến 28/10/2026", insight["message"])

    def test_a_change_exactly_at_the_threshold_warns(self):
        response = self.post(body([point(22, 70), point(28, 72)]))
        self.assertEqual(self.insight_codes(response), ["WEIGHT_CHANGE"])

    def test_a_change_just_under_the_threshold_does_not_warn(self):
        response = self.post(body([point(22, 70), point(28, 71.9)]))
        self.assertEqual(self.insight_codes(response), ["STABLE"])

    def test_a_weight_loss_is_reported_as_giam(self):
        response = self.post(body([point(22, 72.3), point(28, 70)]))
        self.assertIn("giảm 2,3 kg", response.json()["data"]["insights"][0]["message"])

    def test_the_same_change_is_under_the_monthly_threshold(self):
        response = self.post(body([point(22, 70), point(28, 72.3)], period="month", start=1, end=28))
        self.assertEqual(self.insight_codes(response), ["STABLE"])

    def test_one_weight_record_gives_no_trend(self):
        self.assertEqual(self.insight_codes(self.post(body([point(28, 70)]))), ["STABLE"])

    # --- validation: the message names the field, never the value ---
    def test_invalid_requests_are_rejected_without_echoing_values(self):
        cases = [
            {"period": "year", "from": day(22), "to": day(28), "points": []},
            {"period": "week", "from": "22/10/2026", "to": day(28), "points": []},
            {"period": "week", "from": day(28), "to": day(22), "points": []},
            {"period": "week", "from": "2026-01-01", "to": day(28), "points": []},
            body([point(21, 70)]),  # before the window
            body([point(26, 70), point(26, 71)]),  # same day twice
            body([point(26)]),  # no metric at all
            body([point(26, 19)]),
            body([point(26, 301)]),
            body([point(26, sleep=-1)]),
            body([point(26, sleep=25)]),
            body([{"date": day(26), "weightKg": "70"}]),
            body([{"date": day(26), "weightKg": True}]),
            body([point(1 + i % 28, 70) for i in range(32)]),
            {"period": "week", "from": day(22), "to": day(28)},
            [],
        ]
        for payload in cases:
            response = self.post(payload)
            self.assertEqual(response.status_code, 400, payload)
            self.assertEqual(response.json()["error"]["code"], "TRENDS_INVALID")
        secret = self.post(body([point(26, 98765)]))
        self.assertNotIn("98765", secret.json()["error"]["message"])

    def test_broken_json_and_non_finite_numbers_are_rejected(self):
        for raw in ("{not json", '{"period": "week", "from": NaN}', "\xff"):
            response = self.post(None, raw=raw)
            self.assertEqual(response.status_code, 400, raw)
            self.assertEqual(response.json()["error"]["code"], "INVALID_JSON")

    # --- every threshold variable is documented where the README says ---
    def test_every_threshold_variable_is_listed_in_env_example_and_readme(self):
        names = [n for n in dir(settings) if n.startswith(("STATS_SLEEP_", "STATS_WEIGHT_"))]
        self.assertEqual(len(names), 5)
        base = Path(settings.BASE_DIR)
        env = (settings.ROOT / ".env.example").read_text(encoding="utf-8")
        readme = (base / "README.md").read_text(encoding="utf-8")
        for name in names:
            self.assertRegex(env, rf"(?m)^{name}=", name)
            self.assertIn(f"`{name}`", readme, name)
