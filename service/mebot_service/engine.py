from __future__ import annotations

import asyncio
import hashlib
import json
import math
import time
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import httpx

from .config import Settings
from .models import AskRequest, AskResponse, FeedItem, FeedRequest, ScheduleConfig, SearchRequest
from .provider import Provider, ProviderFailure
from .storage import BudgetExceeded, Store


class Engine:
    def __init__(self, settings: Settings, store: Store, provider: Provider):
        self.settings = settings
        self.store = store
        self.provider = provider
        self._refresh_lock = asyncio.Lock()
        self._ask_slots = asyncio.Semaphore(2)

    def check_model(self, request: FeedRequest | AskRequest | SearchRequest) -> None:
        if "model" in request.model_fields_set and request.model != self.settings.model:
            raise ProviderFailure("model_mismatch", 400)

    async def refresh(self, request: FeedRequest) -> tuple[list[FeedItem], int]:
        self.check_model(request)
        data = request.model_dump(mode="json")
        data.pop("name", None)  # Names are unnecessary for retrieval or relevance.
        data["model"] = self.settings.model
        for entry in data["context"]:
            entry["scopes"] = sorted(entry["scopes"])
        key = hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()
        async with self._refresh_lock:
            cached = self.store.cached(key, time.time() - self.settings.cache_seconds)
            if cached is not None:
                return cached, 0
            enabled = [m for m in request.modules if m.enabled]
            if not enabled:
                return [], 0
            per_module_limit = max(1, min(4, math.ceil(request.limit / len(enabled))))
            items: dict[str, FeedItem] = {}
            # Each module gets a separate provider request with its own context scope.
            # A failed refresh does not replace the stored previous successful feed.
            for module in enabled:
                for item in await self.provider.feed(request, module, per_module_limit):
                    previous = items.get(item.id)
                    if previous is None or item.relevance > previous.relevance:
                        items[item.id] = item
            result = sorted(items.values(), key=lambda item: (item.kind != "horoscope", item.relevance, item.publishedAtMillis), reverse=True)[:request.limit]
            new_count = self.store.save_feed(key, result, time.time())
            return result, new_count

    async def ask(self, request: AskRequest) -> AskResponse:
        self.check_model(request)
        async with self._ask_slots:
            return await self.provider.ask(request)

    async def search(self, request: SearchRequest) -> list[FeedItem]:
        self.check_model(request)
        # Search is explicit, bounded work and must not replace the cached daily
        # briefing. The provider reserves one call against the same daily budget.
        async with self._ask_slots:
            return await self.provider.search(request)


def next_allowed_time(config: ScheduleConfig, now: float) -> float:
    """Return now outside quiet hours, or the next local quiet-hours end."""
    if config.quietStartHour == config.quietEndHour:
        return now
    local = datetime.fromtimestamp(now, ZoneInfo(config.timezone or "UTC"))
    start, end = config.quietStartHour, config.quietEndHour
    quiet = start <= local.hour < end if start < end else local.hour >= start or local.hour < end
    if not quiet:
        return now
    resume = local.replace(hour=end, minute=0, second=0, microsecond=0)
    if resume <= local:
        resume += timedelta(days=1)
    return resume.timestamp()


class Scheduler:
    def __init__(self, settings: Settings, store: Store, engine: Engine, client: httpx.AsyncClient):
        self.settings = settings
        self.store = store
        self.engine = engine
        self.client = client

    async def tick(self, now: float | None = None) -> bool:
        now = time.time() if now is None else now
        job = self.store.claim_due(now)
        if job is None:
            return False
        config = ScheduleConfig.model_validate_json(job["payload"])
        allowed = next_allowed_time(config, now)
        if allowed > now:
            self.store.finish_schedule(job["id"], job["revision"], allowed, now, "quiet_hours")
            return True
        status = "updated"
        try:
            items, new_count = await self.engine.refresh(config.request)
            if not self.store.schedule_current(job["id"], job["revision"]):
                return True
            if new_count and self.settings.webhook_url:
                status = await self._notify(new_count)
        except BudgetExceeded:
            status = "limit_reached"
        except ProviderFailure as exc:
            status = exc.code
        except asyncio.CancelledError:
            raise
        except Exception:
            # Store only a stable code; never exception bodies, prompts or credentials.
            status = "operation_error"
        self.store.finish_schedule(job["id"], job["revision"], now + config.intervalMinutes * 60, now, status)
        return True

    async def _notify(self, count: int) -> str:
        headers = {"Content-Type": "application/json"}
        if self.settings.webhook_token:
            headers["Authorization"] = "Bearer " + self.settings.webhook_token
        try:
            # No private context or article text is sent to a notification endpoint.
            response = await self.client.post(
                self.settings.webhook_url,
                json={"event": "mebot.feed.ready", "newItemCount": count},
                headers=headers, timeout=10,
            )
            return "updated" if 200 <= response.status_code < 300 else "delivery_error"
        except httpx.HTTPError:
            return "delivery_error"

    async def run(self) -> None:
        while True:
            try:
                await self.tick()
            except asyncio.CancelledError:
                raise
            except Exception:
                # A damaged schedule or temporary database error must not kill the loop.
                pass
            await asyncio.sleep(self.settings.schedule_poll_seconds)
