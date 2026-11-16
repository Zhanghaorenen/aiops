import asyncio
import json
import os
from pathlib import Path
from urllib.parse import urlsplit
import jwt
from mcp.server.auth.provider import AccessToken
from mcp.server.auth.middleware.auth_context import get_access_token

SCOPE = "aiops.read"


def https_env(name):
    value = os.environ[name]
    parsed = urlsplit(value)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.fragment
        or parsed.query
    ):
        raise ValueError(name + " requires a trusted HTTPS URL")
    return value


class Verifier:
    """Resource server verifies an access token issued specifically for this MCP endpoint."""

    def __init__(self, issuer, resource, jwks):
        self.issuer, self.resource = issuer, resource
        self.keys = jwt.PyJWKClient(jwks, timeout=4)

    async def verify_token(self, token):
        try:
            key = await asyncio.to_thread(self.keys.get_signing_key_from_jwt, token)
            claims = jwt.decode(
                token,
                key.key,
                algorithms=["RS256", "ES256"],
                audience=self.resource,
                issuer=self.issuer,
                options={"require": ["exp", "iss", "aud", "sub"]},
            )
            if not isinstance(claims.get("client_id"), str) or not isinstance(
                claims.get("scope"), str
            ):
                return None
            return AccessToken(
                token=token,
                client_id=claims["client_id"],
                subject=claims["sub"],
                scopes=claims["scope"].split(),
                expires_at=claims["exp"],
                resource=self.resource,
            )
        except (jwt.PyJWTError, ValueError, TypeError):
            return None


def credentials(remote):
    if not remote:
        return os.environ["AIOPS_MCP_USER_JWT"], None
    caller = get_access_token()
    if caller is None or SCOPE not in caller.scopes or not caller.subject:
        raise PermissionError("MCP_AUTH_REQUIRED")
    # Provisioned by the operator, outside the repository. Never accept a delegated user ID from tool input.
    entry = json.loads(
        Path(os.environ["AIOPS_MCP_DELEGATIONS"]).read_text(encoding="utf-8")
    ).get(caller.subject)
    if not entry or type(entry.get("user_id")) is not int:
        raise PermissionError("MCP_NO_DELEGATION")
    return entry["user_jwt"], entry["user_id"]
