"""Read-only client of the platform API (through API Management) acting as the signed-in user: every request carries
the caller's own bearer token, so the assistant sees exactly what the user may see (ADR-0010). The token lives in a
request-scoped context variable and is never stored or logged."""

from contextvars import ContextVar
from typing import Any
from urllib.parse import quote

import httpx

caller_token: ContextVar[str | None] = ContextVar("caller_token", default=None)


class ApiError(Exception):
    """A platform API failure, phrased for the model, with its kind for telemetry: not_found, not_permitted,
    invalid_request, unavailable, rate_limited or failed."""

    def __init__(self, message: str, kind: str = "failed"):
        super().__init__(message)
        self.kind = kind


class PlatformApi:
    def __init__(self, client: httpx.AsyncClient):
        self._client = client

    async def get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        token = caller_token.get()
        if token is None:
            raise ApiError("not permitted: no signed-in user", "not_permitted")
        query = {k: v for k, v in (params or {}).items() if v is not None}
        try:
            response = await self._client.get(path, params=query, headers={"Authorization": f"Bearer {token}"})
        except httpx.HTTPError as e:
            raise ApiError(f"the platform API is unavailable ({type(e).__name__})", "unavailable") from e
        if response.status_code == 200:
            return response.json()
        raise ApiError(*_describe(response))


def segment(value: str) -> str:
    return quote(value, safe="")


def _describe(response: httpx.Response) -> tuple[str, str]:
    detail = ""
    try:
        body = response.json()
        detail = body.get("detail") or ""
        errors = body.get("errors")
        if errors:
            detail = "; ".join(f"{field} {', '.join(messages)}" for field, messages in errors.items())
    except ValueError:
        pass
    match response.status_code:
        case 400:
            return f"invalid request: {detail or 'rejected by the API'}", "invalid_request"
        case 401 | 403:
            return "not permitted: the user may not read this data", "not_permitted"
        case 404:
            return f"not found: {detail or 'no such resource'}", "not_found"
        case 429:
            return "rate limited: too many requests, try again in a minute", "rate_limited"
        case 502 | 503 | 504:
            return f"the platform API is unavailable (HTTP {response.status_code})", "unavailable"
        case _:
            return f"the platform API failed (HTTP {response.status_code})", "failed"
