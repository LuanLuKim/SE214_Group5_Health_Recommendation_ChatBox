import json

from django.test import SimpleTestCase, override_settings

KEY = {"HTTP_X_SERVICE_KEY": "synthetic-service-key"}
PATH = "/v1/system/statistics"


@override_settings(STATS_SERVICE_KEY="synthetic-service-key", STATS_SYSTEM_MAX_DAYS=31)
class SystemStatisticsTest(SimpleTestCase):
    """UC-19: per-day counts in, a filled series, totals and labels out."""

    def post(self, payload, **headers):
        body = payload if isinstance(payload, str) else json.dumps(payload)
        return self.client.post(PATH, data=body, content_type="application/json", **{**KEY, **headers})

    def error_code(self, response):
        return response.json()["error"]["code"]

    def test_fills_every_day_of_the_range_and_sums_the_totals(self):
        response = self.post(
            {
                "from": "2026-11-01",
                "to": "2026-11-03",
                "days": [
                    {"date": "2026-11-01", "newAccounts": 2, "visits": 5, "chatInteractions": 7},
                    {"date": "2026-11-03", "visits": 1},
                ],
            }
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()["data"]
        self.assertEqual(data["from"], "2026-11-01")
        self.assertEqual(data["to"], "2026-11-03")
        self.assertTrue(data["hasData"])
        self.assertIsNone(data["message"])
        self.assertEqual(data["totals"], {"newAccounts": 2, "visits": 6, "chatInteractions": 7})
        self.assertEqual(
            data["series"],
            [
                {"date": "2026-11-01", "newAccounts": 2, "visits": 5, "chatInteractions": 7},
                {"date": "2026-11-02", "newAccounts": 0, "visits": 0, "chatInteractions": 0},
                {"date": "2026-11-03", "newAccounts": 0, "visits": 1, "chatInteractions": 0},
            ],
        )
        self.assertEqual([m["key"] for m in data["metrics"]], ["newAccounts", "visits", "chatInteractions"])
        self.assertTrue(all(m["label"] and m["unit"] for m in data["metrics"]))

    def test_no_event_gives_an_empty_chart_and_a_message(self):
        data = self.post({"from": "2026-11-01", "to": "2026-11-02", "days": []}).json()["data"]
        self.assertFalse(data["hasData"])
        self.assertEqual(data["message"], "Không có dữ liệu trong khoảng thời gian đã chọn.")
        self.assertEqual(len(data["series"]), 2)
        self.assertEqual(data["totals"], {"newAccounts": 0, "visits": 0, "chatInteractions": 0})

    def test_rejects_a_reversed_or_too_wide_range(self):
        for payload in (
            {"from": "2026-11-05", "to": "2026-11-01", "days": []},
            {"from": "2026-10-01", "to": "2026-11-05", "days": []},
        ):
            response = self.post(payload)
            self.assertEqual(response.status_code, 400)
            self.assertEqual(self.error_code(response), "STATISTICS_INVALID")

    def test_rejects_malformed_dates_and_counts_without_echoing_them(self):
        bad = [
            {"from": "21/11/2026", "to": "2026-11-21", "days": []},
            {"from": "2026-02-30", "to": "2026-03-01", "days": []},
            {"from": "2026-11-01", "to": "2026-11-02", "days": [{"date": "2026-11-03", "visits": 1}]},
            {"from": "2026-11-01", "to": "2026-11-02", "days": [{"date": "2026-11-01"}, {"date": "2026-11-01"}]},
            {"from": "2026-11-01", "to": "2026-11-02", "days": [{"date": "2026-11-01", "visits": -1}]},
            {"from": "2026-11-01", "to": "2026-11-02", "days": [{"date": "2026-11-01", "visits": 1.5}]},
            {"from": "2026-11-01", "to": "2026-11-02", "days": [{"date": "2026-11-01", "visits": True}]},
            {"from": "2026-11-01", "to": "2026-11-02", "days": "secret-value"},
            ["not", "an", "object"],
        ]
        for payload in bad:
            response = self.post(payload)
            self.assertEqual(response.status_code, 400, payload)
            self.assertEqual(self.error_code(response), "STATISTICS_INVALID")
            self.assertNotIn("secret-value", response.content.decode())

    def test_rejects_invalid_json(self):
        for body in ("{", '{"from": NaN}'):
            response = self.post(body)
            self.assertEqual(response.status_code, 400)
            self.assertEqual(self.error_code(response), "INVALID_JSON")

    def test_needs_the_service_key(self):
        response = self.post({"from": "2026-11-01", "to": "2026-11-01", "days": []}, HTTP_X_SERVICE_KEY="wrong")
        self.assertEqual(response.status_code, 401)
