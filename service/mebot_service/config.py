from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit


def _bool(name: str, default: bool = False) -> bool:
    return os.getenv(name, str(default)).strip().lower() in {"true", "1", "yes"}


@dataclass(frozen=True, repr=False)
class Settings:
    service_token: str
    openai_api_key: str
    database_path: str = "./data/mebot.sqlite3"
    model: str = "gpt-6-luna"
    upstream_url: str = "https://api.openai.com/v1"
    daily_request_limit: int = 48
    timeout_seconds: float = 75
    cache_seconds: int = 300
    scheduler_enabled: bool = False
    schedule_poll_seconds: float = 30
    allow_insecure_upstream: bool = False
    webhook_url: str = ""
    webhook_token: str = ""

    def validate(self) -> "Settings":
        if len(self.service_token) < 32 or any(c.isspace() for c in self.service_token):
            raise ValueError("MEBOT_SERVICE_TOKEN must contain at least 32 non-whitespace characters")
        if not self.openai_api_key.strip():
            raise ValueError("OPENAI_API_KEY is required")
        if not self.model or len(self.model) > 100:
            raise ValueError("MEBOT_MODEL is invalid")
        parsed = urlsplit(self.upstream_url)
        permitted_test_url = self.allow_insecure_upstream and parsed.hostname in {"localhost", "127.0.0.1", "::1"}
        if (parsed.scheme != "https" and not permitted_test_url) or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("MEBOT_UPSTREAM_URL must be an HTTPS URL without credentials, query or fragment")
        if not 1 <= self.daily_request_limit <= 10000:
            raise ValueError("MEBOT_DAILY_REQUEST_LIMIT must be between 1 and 10000")
        if not 5 <= self.timeout_seconds <= 180 or not 0 <= self.cache_seconds <= 3600:
            raise ValueError("Invalid timeout or cache setting")
        if self.webhook_url:
            webhook = urlsplit(self.webhook_url)
            if webhook.scheme != "https" or not webhook.hostname or webhook.username or webhook.password or webhook.fragment:
                raise ValueError("MEBOT_WEBHOOK_URL must be HTTPS and contain no credentials or fragment")
        return self

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            service_token=os.getenv("MEBOT_SERVICE_TOKEN", ""),
            openai_api_key=os.getenv("OPENAI_API_KEY", ""),
            database_path=os.getenv("MEBOT_DATABASE_PATH", "./data/mebot.sqlite3"),
            model=os.getenv("MEBOT_MODEL", "gpt-6-luna"),
            upstream_url=os.getenv("MEBOT_UPSTREAM_URL", "https://api.openai.com/v1").rstrip("/"),
            daily_request_limit=int(os.getenv("MEBOT_DAILY_REQUEST_LIMIT", "48")),
            timeout_seconds=float(os.getenv("MEBOT_TIMEOUT_SECONDS", "75")),
            cache_seconds=int(os.getenv("MEBOT_CACHE_SECONDS", "300")),
            scheduler_enabled=_bool("MEBOT_SCHEDULER_ENABLED"),
            webhook_url=os.getenv("MEBOT_WEBHOOK_URL", ""),
            webhook_token=os.getenv("MEBOT_WEBHOOK_TOKEN", ""),
        ).validate()

    def prepare_storage(self) -> None:
        path = Path(self.database_path)
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        # Do not chmod an operator's pre-existing parent directory.
