from __future__ import annotations

import json
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


class ApiError(RuntimeError):
    """A non-retryable response from the backend."""


class TransientApiError(ConnectionError):
    """A backend response that may succeed when retried."""


class HttpClient:
    def __init__(self, base_url: str, timeout_seconds: float = 10.0):
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds

    def post(self, endpoint: str, data: dict, token: str | None = None) -> dict:
        path = endpoint if endpoint.startswith("/") else f"/{endpoint}"
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = Request(
            f"{self.base_url}{path}",
            data=json.dumps(data).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        try:
            with urlopen(request, timeout=self.timeout_seconds) as response:
                raw = response.read()
        except HTTPError as exc:
            message = exc.read().decode("utf-8", errors="replace")[:300]
            if exc.code >= 500 or exc.code == 429:
                raise TransientApiError(f"Backend returned HTTP {exc.code}") from exc
            raise ApiError(f"Backend rejected {path}: HTTP {exc.code} {message}") from exc
        except URLError as exc:
            raise TransientApiError(str(exc.reason)) from exc

        if not raw:
            return {}
        try:
            result = json.loads(raw)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ApiError(f"Backend returned invalid JSON for {path}") from exc
        if not isinstance(result, dict):
            raise ApiError(f"Backend returned an unexpected response for {path}")
        return result
