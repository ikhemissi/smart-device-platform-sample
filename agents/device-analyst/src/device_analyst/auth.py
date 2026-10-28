"""Entra ID access tokens, validated with the same rules as the Java services (platform-common SdpTokens): signature
from the tenant's keys, v2 issuer, platform API audience, `roles` app roles where DeviceData.Write implies Read."""

import asyncio
from collections.abc import Callable
from dataclasses import dataclass

import jwt
from fastapi import Request

from .problems import ProblemError

READ_ROLE = "DeviceData.Read"
WRITE_ROLE = "DeviceData.Write"


@dataclass(frozen=True)
class User:
    object_id: str
    name: str | None
    roles: frozenset[str]
    token: str

    @property
    def can_read(self) -> bool:
        return READ_ROLE in self.roles or WRITE_ROLE in self.roles


KeyResolver = Callable[[str], object]


class TokenValidator:
    def __init__(self, tenant_id: str, audiences: list[str], key_resolver: KeyResolver | None = None):
        self._issuer = f"https://login.microsoftonline.com/{tenant_id}/v2.0"
        self._audiences = audiences
        if key_resolver is None:
            jwks = jwt.PyJWKClient(
                f"https://login.microsoftonline.com/{tenant_id}/discovery/v2.0/keys", cache_keys=True, lifespan=3600
            )

            def key_resolver(token: str) -> object:
                return jwks.get_signing_key_from_jwt(token).key

        self._key_resolver = key_resolver

    async def validate(self, token: str) -> User:
        try:
            key = await asyncio.to_thread(self._key_resolver, token)
            claims = jwt.decode(
                token,
                key=key,
                algorithms=["RS256"],
                audience=self._audiences,
                issuer=self._issuer,
                options={"require": ["exp", "iss", "aud", "oid"]},
                leeway=60,
            )
        except (jwt.PyJWTError, KeyError, ValueError) as e:
            raise unauthorized(f"Invalid access token: {e}") from e
        return User(
            object_id=claims["oid"],
            name=claims.get("name") or claims.get("preferred_username"),
            roles=frozenset(claims.get("roles") or []),
            token=token,
        )


def unauthorized(detail: str) -> ProblemError:
    return ProblemError(401, "Unauthorized", detail, headers={"WWW-Authenticate": 'Bearer error="invalid_token"'})


async def reader(request: Request) -> User:
    """FastAPI dependency: the signed-in user, who must hold the Read role (403 otherwise)."""
    header = request.headers.get("authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise ProblemError(401, "Unauthorized", "A bearer token is required.", headers={"WWW-Authenticate": "Bearer"})
    user = await request.app.state.token_validator.validate(token.strip())
    if not user.can_read:
        raise ProblemError(403, "Forbidden", f"The {READ_ROLE} role is required.")
    return user
