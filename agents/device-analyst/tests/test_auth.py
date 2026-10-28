"""Task 3.1: JWT validation and roles (same rules as the Java services)."""

import time

import pytest

from .conftest import ALICE, auth, client_for, make_app, token


@pytest.fixture
def app(store):
    return make_app(store)


async def test_health_needs_no_token(app):
    async with client_for(app) as client:
        assert (await client.get("/health/live")).status_code == 200
        assert (await client.get("/health/ready")).json() == {"status": "UP"}


async def test_missing_token_is_401_problem(app):
    async with client_for(app) as client:
        response = await client.post("/api/v1/agent/conversations")
    assert response.status_code == 401
    assert response.headers["content-type"] == "application/problem+json"
    assert response.headers["www-authenticate"].startswith("Bearer")


@pytest.mark.parametrize(
    "overrides",
    [
        {"aud": "api://someone-else"},
        {"iss": "https://login.microsoftonline.com/other-tenant/v2.0"},
        {"exp": int(time.time()) - 3600},
        {"oid": None},
    ],
)
async def test_invalid_tokens_are_401(app, overrides):
    bad = token(**({"oid": ALICE} | overrides))
    async with client_for(app) as client:
        response = await client.post("/api/v1/agent/conversations", headers={"Authorization": f"Bearer {bad}"})
    assert response.status_code == 401


async def test_tampered_token_is_401(app):
    header, payload, signature = token().split(".")
    async with client_for(app) as client:
        response = await client.post(
            "/api/v1/agent/conversations", headers={"Authorization": f"Bearer {header}.{payload}.{signature[::-1]}"}
        )
    assert response.status_code == 401


async def test_user_without_permission_is_403(app):
    """Scenario: a user without DeviceData.Read calls the conversation API."""
    async with client_for(app) as client:
        for request in (
            client.post("/api/v1/agent/conversations", headers=auth(roles=())),
            client.get("/api/v1/agent/conversations/x", headers=auth(roles=("Something.Else",))),
        ):
            response = await request
            assert response.status_code == 403
            assert response.json()["detail"] == "The DeviceData.Read role is required."


async def test_writer_can_read_and_any_audience_identifier_is_accepted(app):
    async with client_for(app) as client:
        assert (
            await client.post("/api/v1/agent/conversations", headers=auth(roles=("DeviceData.Write",)))
        ).status_code == 201
        other = token(aud="other-audience")
        assert (
            await client.post("/api/v1/agent/conversations", headers={"Authorization": f"Bearer {other}"})
        ).status_code == 201
