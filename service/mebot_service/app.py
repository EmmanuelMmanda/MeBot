from __future__ import annotations

import asyncio
import contextlib
import hmac
import json
import re
import time
from contextlib import asynccontextmanager

import httpx
from fastapi import Depends, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from . import __version__
from .config import Settings
from .engine import Engine, Scheduler
from .models import AskRequest, AskResponse, FeedRequest, FeedResponse, ScheduleConfig, SearchRequest
from .provider import OpenAIProvider, Provider, ProviderFailure
from .realtime import (MAX_AUDIO_BYTES, MAX_CALL_DURATION_SECONDS,
                       PROVIDER_MAX_CALL_DURATION_SECONDS, RealtimeCallReaper,
                       RealtimeCallEndRequest, RealtimeCallRequest, RealtimeCreateUncertain,
                       create_realtime_call,
                       owner_principal_hash, realtime_capability,
                       request_body_hash, transcribe_recording)
from .storage import BudgetExceeded, RealtimeCallConflict, Store


class BodyLimitMiddleware:
    def __init__(self, app: ASGIApp, limit: int = 196_608):
        self.app = app
        self.limit = limit

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        path = scope.get("path")
        method = scope.get("method")
        if path == "/v1/transcriptions" and method == "POST":
            limit = MAX_AUDIO_BYTES
        elif path == "/v1/realtime/calls" and method == "POST":
            limit = 1_100_000  # bounded JSON plus one final gathered SDP
        else:
            limit = self.limit
        raw_headers = dict(scope.get("headers", []))
        content_length = raw_headers.get(b"content-length")
        if content_length:
            try:
                if int(content_length) > limit:
                    response = JSONResponse({"error": "invalid_input"}, status_code=413)
                    await response(scope, receive, send)
                    return
            except ValueError:
                response = JSONResponse({"error": "invalid_input"}, status_code=400)
                await response(scope, receive, send)
                return
        body = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            if len(body) + len(chunk) > limit:
                response = JSONResponse({"error": "invalid_input"}, status_code=413)
                await response(scope, receive, send)
                return
            body.extend(chunk)
            if not message.get("more_body", False):
                break
        replayed = False

        async def replay() -> dict:
            nonlocal replayed
            if not replayed:
                replayed = True
                return {"type": "http.request", "body": bytes(body), "more_body": False}
            return await receive()

        await self.app(scope, replay, send)


class PrivateErrorsMiddleware:
    """Keep unforeseen exceptions out of HTTP bodies and server traceback logs."""
    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        started = False

        async def guarded_send(message: dict) -> None:
            nonlocal started
            if message["type"] == "http.response.start":
                started = True
            await send(message)

        try:
            await self.app(scope, receive, guarded_send)
        except Exception:
            if started:
                # These endpoints never stream. A broken transport must not expose
                # the original exception, which could include private input values.
                raise RuntimeError("operation_error") from None
            await JSONResponse({"error": "operation_error"}, status_code=500)(scope, receive, send)


def create_app(settings: Settings | None = None, *, client: httpx.AsyncClient | None = None, provider: Provider | None = None) -> FastAPI:
    settings = (settings or Settings.from_env()).validate()
    settings.prepare_storage()
    store = Store(settings.database_path)
    http = client or httpx.AsyncClient(follow_redirects=False, trust_env=False, limits=httpx.Limits(max_connections=8, max_keepalive_connections=4))
    engine = Engine(settings, store, provider or OpenAIProvider(settings, store, http))
    scheduler = Scheduler(settings, store, engine, http)
    realtime_reaper = RealtimeCallReaper(settings, store, http)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        task = asyncio.create_task(scheduler.run()) if settings.scheduler_enabled else None
        call_reaper_task = asyncio.create_task(realtime_reaper.run())
        yield
        call_reaper_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await call_reaper_task
        if task:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
        await http.aclose()

    app = FastAPI(title="MeBot personal service", version=__version__, docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.add_middleware(BodyLimitMiddleware)
    app.add_middleware(PrivateErrorsMiddleware)
    app.state.settings = settings
    app.state.store = store
    app.state.engine = engine
    app.state.scheduler = scheduler
    app.state.realtime_reaper = realtime_reaper

    async def authorized(authorization: str | None = Header(default=None)) -> str:
        prefix, _, token = (authorization or "").partition(" ")
        if prefix.lower() != "bearer" or not hmac.compare_digest(token.encode(), settings.service_token.encode()):
            raise ProviderFailure("unauthorized", 401)
        # The present service has one shared configured bearer principal, not individual
        # app-user identity. Store only a stable digest; never accept a claimed owner id.
        return owner_principal_hash(settings.service_token)

    auth = [Depends(authorized)]

    @app.exception_handler(ProviderFailure)
    async def provider_error(_: Request, exc: ProviderFailure):
        headers = {"WWW-Authenticate": "Bearer"} if exc.status == 401 else {}
        return JSONResponse({"error": exc.code}, status_code=exc.status, headers=headers)

    @app.exception_handler(BudgetExceeded)
    async def budget_error(_: Request, __: BudgetExceeded):
        return JSONResponse({"error": "limit_reached"}, status_code=429)

    @app.exception_handler(RealtimeCallConflict)
    async def realtime_call_conflict(_: Request, exc: RealtimeCallConflict):
        return JSONResponse({"error": exc.code}, status_code=409)

    @app.exception_handler(RequestValidationError)
    async def validation_error(_: Request, __: RequestValidationError):
        return JSONResponse({"error": "invalid_input"}, status_code=422)

    @app.get("/health", dependencies=auth)
    async def health():
        return {"ok": True, "version": __version__}

    @app.post("/v1/feed", dependencies=auth, response_model=FeedResponse)
    async def feed(request: FeedRequest):
        items, _ = await engine.refresh(request)
        return FeedResponse(items=items)

    @app.get("/v1/feed/latest", dependencies=auth, response_model=FeedResponse)
    async def latest():
        return FeedResponse(items=store.latest())

    @app.post("/v1/ask", dependencies=auth, response_model=AskResponse)
    async def ask(request: AskRequest):
        return await engine.ask(request)

    @app.post("/v1/search", dependencies=auth, response_model=FeedResponse)
    async def search(request: SearchRequest):
        return FeedResponse(items=await engine.search(request))

    @app.get("/v1/realtime/capability", dependencies=auth)
    async def voice_capability(principal: str = Depends(authorized)):
        return realtime_capability(settings, store.feature_usage("realtime_session"),
                                   store.feature_usage("transcription"), store.usage(),
                                   store.active_realtime_call_count(principal))

    @app.post("/v1/realtime/calls", dependencies=auth)
    async def realtime_call(request: RealtimeCallRequest, principal: str = Depends(authorized)):
        # The service owns provider credentials, call IDs, hangup, expiry and retries.
        if not settings.realtime_model or settings.realtime_daily_session_limit < 1:
            raise ProviderFailure("realtime_unavailable", 503)
        now = time.time()
        store.begin_realtime_call(
            principal, request.requestId, request_body_hash(request),
            feature_limit=settings.realtime_daily_session_limit,
            total_limit=settings.daily_request_limit,
            max_active=1,
            now=now,
            create_deadline=now + settings.timeout_seconds + 10,
        )
        try:
            answer_sdp, upstream_call_id = await create_realtime_call(settings, http, request)
        except RealtimeCreateUncertain:
            store.mark_realtime_call_create_unknown(
                principal, request.requestId, now=time.time(),
                provider_max_duration_seconds=PROVIDER_MAX_CALL_DURATION_SECONDS,
            )
            raise ProviderFailure("provider_error", 502) from None
        except ProviderFailure:
            store.fail_realtime_call_create(principal, request.requestId)
            raise
        active = store.finish_realtime_call_create(
            principal, request.requestId, upstream_call_id, now=time.time(),
            expires_at=time.time() + MAX_CALL_DURATION_SECONDS,
        )
        if not active:
            # Cancellation won while the create request was in flight; the durable
            # reaper will hang up without giving stale SDP to the mobile client.
            return JSONResponse({"error": "call_ended"}, status_code=409)
        return {"answerSdp": answer_sdp, "callReference": request.requestId}

    @app.post("/v1/realtime/calls/end", dependencies=auth)
    async def realtime_call_end(request: RealtimeCallEndRequest, principal: str = Depends(authorized)):
        # Keep the public shape narrow; arbitrary provider URLs/IDs are never accepted.
        store.request_realtime_call_end(principal, request.callReference, now=time.time())
        return {"ok": True}

    @app.post("/v1/transcriptions", dependencies=auth)
    async def transcriptions(request: Request):
        content_type = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
        audio = await request.body()
        if not settings.transcription_model or settings.transcription_daily_request_limit < 1:
            raise ProviderFailure("transcription_unavailable", 503)
        if content_type not in {"audio/mp4", "audio/m4a"} or not audio or len(audio) > MAX_AUDIO_BYTES:
            raise ProviderFailure("invalid_input", 422)
        store.reserve_limited_call("transcription", settings.transcription_daily_request_limit,
                                   settings.daily_request_limit)
        return await transcribe_recording(settings, http, audio, content_type)

    @app.get("/v1/status", dependencies=auth)
    async def status():
        return {"version": __version__, "model": settings.model, "requestsToday": store.usage(), "dailyRequestLimit": settings.daily_request_limit, "budgetTimezone": "UTC", "schedulerEnabled": settings.scheduler_enabled}

    @app.get("/v1/schedules", dependencies=auth)
    async def schedules():
        return {"schedules": store.schedules()}

    @app.put("/v1/schedules/{schedule_id}", dependencies=auth)
    async def put_schedule(schedule_id: str, config: ScheduleConfig):
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", schedule_id):
            return JSONResponse({"error": "invalid_input"}, status_code=422)
        engine.check_model(config.request)
        if len(store.schedules()) >= 8 and not any(s["id"] == schedule_id for s in store.schedules()):
            return JSONResponse({"error": "limit_reached"}, status_code=429)
        next_run = time.time() + config.intervalMinutes * 60
        # Preserve a client's omitted model instead of serializing a default as
        # an explicit selection that could conflict with the configured model.
        store.put_schedule(schedule_id, config.model_dump_json(exclude_unset=True), config.enabled, next_run)
        return {"id": schedule_id, "enabled": config.enabled, "nextRun": next_run, "schedulerEnabled": settings.scheduler_enabled}

    @app.delete("/v1/schedules/{schedule_id}", dependencies=auth)
    async def delete_schedule(schedule_id: str):
        store.delete_schedule(schedule_id)
        return {"ok": True}

    return app


def main() -> None:
    import uvicorn
    # Operator-provided proxy supplies public HTTPS; default listener stays local.
    uvicorn.run("mebot_service.app:create_app", factory=True, host="127.0.0.1", port=8787, access_log=False)


if __name__ == "__main__":
    main()
