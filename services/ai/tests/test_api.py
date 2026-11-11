from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.safety import CARE_LEVELS, evaluate

KEY = {"X-Service-Key": "synthetic-service-key"}


def client(key: str = "synthetic-service-key") -> TestClient:
    return TestClient(create_app(Settings(service_key=key)))


def test_healthz_is_public_and_has_request_id():
    response = client().get("/healthz")
    assert response.status_code == 200
    assert response.json()["data"]["status"] == "ok"
    assert response.headers["X-Request-ID"]
    assert response.headers["X-Content-Type-Options"] == "nosniff"


def test_internal_routes_require_the_service_key():
    c = client()
    for headers in ({}, {"X-Service-Key": "wrong"}):
        response = c.post("/v1/safety/evaluate", json={"text": "synthetic"}, headers=headers)
        assert response.status_code == 401
        assert response.json()["error"]["code"] == "UNAUTHORIZED"
        assert response.json()["error"]["requestId"] == response.headers["X-Request-ID"]


def test_missing_service_key_configuration_fails_closed():
    response = client(key="").post("/v1/safety/evaluate", json={"text": "synthetic"}, headers=KEY)
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "SERVICE_KEY_NOT_CONFIGURED"


def test_unconfigured_safety_gate_returns_no_care_direction():
    response = client().post("/v1/safety/evaluate", json={"text": "synthetic"}, headers=KEY)
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["status"] == "UNAVAILABLE"
    assert data["careLevel"] is None
    assert evaluate("synthetic").careLevel is None
    assert len(CARE_LEVELS) == 5 and "OUT_OF_SCOPE" not in CARE_LEVELS


def test_invalid_input_is_rejected_without_echoing_it():
    c = client()
    for body in ({"text": ""}, {"text": "private-test-sentinel", "extra": 1}, {"text": "x" * 4001}):
        response = c.post("/v1/safety/evaluate", json=body, headers=KEY)
        assert response.status_code == 400
        assert "private-test-sentinel" not in response.text


def test_chat_answer_is_not_implemented_and_unknown_paths_are_json_404():
    c = client()
    assert c.post("/v1/chat/answer", json={}, headers=KEY).status_code == 501
    missing = c.get("/missing")
    assert missing.status_code == 404
    assert missing.json()["error"]["code"] == "NOT_FOUND"
