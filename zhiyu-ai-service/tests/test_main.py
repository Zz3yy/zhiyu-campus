from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_risk_rule_detects_high_risk() -> None:
    response = client.post("/internal/ai/risk/analyze", json={"text": "我想自杀"})
    assert response.status_code == 200
    assert response.json()["risk_level"] == "L3"
