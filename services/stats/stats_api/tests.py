from django.test import SimpleTestCase, override_settings

KEY = {"HTTP_X_SERVICE_KEY": "synthetic-service-key"}


@override_settings(STATS_SERVICE_KEY="synthetic-service-key")
class StatsApiTest(SimpleTestCase):
    def test_healthz_is_public_and_has_request_id(self):
        response = self.client.get("/healthz")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["data"]["status"], "ok")
        self.assertTrue(response["X-Request-ID"])
        self.assertEqual(response["X-Content-Type-Options"], "nosniff")

    def test_internal_routes_require_the_service_key(self):
        for path in ("/v1/metrics/trends", "/v1/system/statistics"):
            for headers in ({}, {"HTTP_X_SERVICE_KEY": "wrong"}):
                response = self.client.get(path, **headers)
                self.assertEqual(response.status_code, 401, path)
                body = response.json()["error"]
                self.assertEqual(body["code"], "UNAUTHORIZED")
                self.assertEqual(body["requestId"], response["X-Request-ID"])

    def test_statistics_are_not_implemented_yet(self):
        self.assertEqual(self.client.get("/v1/metrics/trends", **KEY).status_code, 501)
        self.assertEqual(self.client.get("/v1/system/statistics", **KEY).status_code, 501)

    @override_settings(STATS_SERVICE_KEY="")
    def test_missing_service_key_configuration_fails_closed(self):
        response = self.client.get("/v1/system/statistics", **KEY)
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json()["error"]["code"], "SERVICE_KEY_NOT_CONFIGURED")

    def test_unknown_paths_are_json_404(self):
        response = self.client.get("/missing")
        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.json()["error"]["code"], "NOT_FOUND")
