from __future__ import annotations

import asyncio
import copy
import hashlib
import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import pytest
from fastapi.testclient import TestClient

from mebot_service.app import create_app
from mebot_service.config import Settings
from mebot_service.engine import Engine, Scheduler, next_allowed_time
from mebot_service.models import FeedModule, FeedRequest, ScheduleConfig, canonical_url, public_url
from mebot_service.provider import ProviderFailure, parse_feed, response_sources
from mebot_service.storage import BudgetExceeded, Store


TOKEN = "service-test-token-with-adequate-length-123456"
API_KEY = "provider-test-key-not-a-real-credential"
ARTICLE = "https://example.org/news/a-useful-release"
AUTH = {"Authorization": "Bearer " + TOKEN}


def model_response(*, url=ARTICLE, metadata=True, title="A useful release", summary="The release adds a documented capability.", citations=None, publication="2026-10-20T10:00:00Z"):
    item = {
        "title": title, "summary": summary, "why": "It relates to your approved interest.",
        "sourceName": "Example", "sourceUrl": url, "publishedAt": publication,
        "citations": citations if citations is not None else [{"title": "Release", "url": url}],
        "deadline": "", "relevance": 85,
    }
    output = []
    if metadata:
        output.append({"type": "web_search_call", "action": {"type": "search", "sources": [{"type": "url", "url": url, "title": "Original release"}]}})
    output.append({"type": "message", "content": [{"type": "output_text", "text": json.dumps({"items": [item]}), "annotations": []}]})
    return {"status": "completed", "output": output}


def feed_request(**updates):
    body = {
        "model": "gpt-6-luna", "name": "Test person", "region": "Tanzania", "timezone": "Africa/Dar_es_Salaam",
        "modules": [{"id": "news", "kind": "news", "enabled": True}],
        "context": [], "language": "en", "limit": 8,
    }
    body.update(updates)
    return body


@pytest.fixture
def build(tmp_path):
    apps = []

    def make(handler=None, **config):
        seen = []

        def transport(request):
            seen.append(request)
            return handler(request) if handler else httpx.Response(200, json=model_response())

        settings = Settings(service_token=TOKEN, openai_api_key=API_KEY, database_path=str(tmp_path / f"{len(apps)}.sqlite3"), **config)
        http = httpx.AsyncClient(transport=httpx.MockTransport(transport), follow_redirects=False)
        app = create_app(settings, client=http)
        apps.append(app)
        return TestClient(app, raise_server_exceptions=False), app, seen

    return make


@pytest.mark.parametrize("path,method", [("/health", "get"), ("/v1/status", "get"), ("/v1/feed/latest", "get"), ("/v1/schedules", "get"), ("/v1/feed", "post"), ("/v1/ask", "post"), ("/v1/search", "post")])
def test_authentication_required(build, path, method):
    client, _, seen = build()
    with client:
        response = getattr(client, method)(path, json={} if method == "post" else None) if method == "post" else client.get(path)
        assert response.status_code == 401
        assert response.json() == {"error": "unauthorized"}
        assert not seen


def test_health_is_authenticated_and_safe(build):
    client, _, _ = build()
    with client:
        response = client.get("/health", headers=AUTH)
        assert response.json() == {"ok": True, "version": "0.6.1"}
        assert API_KEY not in response.text
        assert client.get("/docs", headers=AUTH).status_code == 404


def test_feed_contract_and_real_provider_request_shape(build):
    client, app, seen = build()
    with client:
        response = client.post("/v1/feed", json=feed_request(), headers=AUTH)
        assert response.status_code == 200, response.text
        item = response.json()["items"][0]
        assert item["sourceUrl"] == ARTICLE
        assert item["moduleId"] == "news"
        assert item["aiGenerated"] is True
        assert item["citations"][0]["url"] == ARTICLE
        upstream = json.loads(seen[0].content)
        assert upstream["store"] is False
        assert upstream["model"] == "gpt-6-luna"
        assert upstream["text"]["format"]["type"] == "json_schema"
        assert upstream["tool_choice"] == "required"
        assert upstream["max_tool_calls"] == 3
        assert upstream["include"] == ["web_search_call.action.sources"]
        assert seen[0].headers["authorization"] == "Bearer " + API_KEY
        assert "Test person" not in seen[0].content.decode()
        assert app.state.store.usage() == 1
        assert client.get("/v1/feed/latest", headers=AUTH).json()["items"][0]["id"] == item["id"]


@pytest.mark.parametrize("topic", ["", "PostgreSQL releases for maintainers"])
def test_module_starts_with_a_useful_goal_and_only_approved_context(build, topic):
    client, _, seen = build()
    with client:
        response = client.post("/v1/feed", json=feed_request(
            modules=[{"id": "technology", "kind": "technology", "query": topic}],
            context=[
                {"id": "technical", "text": "Practical database engineering", "scopes": ["technology"]},
                {"id": "private-study", "text": "A separate education plan", "scopes": ["scholarships"]},
            ]), headers=AUTH)
        assert response.status_code == 200, response.text
        upstream = json.loads(seen[0].content)
        payload = json.loads(upstream["input"][1]["content"])
        assert payload["discoveryGoal"]
        if topic:
            assert payload["discoveryGoal"] == topic
        else:
            assert "release" in payload["discoveryGoal"]
        assert payload["module"]["query"] == topic
        assert "Practical database engineering" in json.dumps(payload)
        assert "A separate education plan" not in json.dumps(payload)


def test_each_module_only_receives_its_scoped_context(build):
    client, _, seen = build()
    context = [
        {"id": "a", "text": "Private software interest", "scopes": ["news"]},
        {"id": "b", "text": "Career eligibility", "scopes": ["opportunities"]},
        {"id": "c", "text": "Unrelated personal detail", "scopes": ["horoscope"]},
    ]
    modules = [{"id": "news", "kind": "news"}, {"id": "opportunities", "kind": "opportunities"}]
    with client:
        response = client.post("/v1/feed", json=feed_request(modules=modules, context=context), headers=AUTH)
        assert response.status_code == 200
        assert len(seen) == 2
        first = json.loads(json.loads(seen[0].content)["input"][1]["content"])
        second = json.loads(json.loads(seen[1].content)["input"][1]["content"])
        assert [e["text"] for e in first["context"]] == ["Private software interest"]
        assert [e["text"] for e in second["context"]] == ["Career eligibility"]
        assert "Unrelated personal detail" not in seen[0].content.decode() + seen[1].content.decode()
        # Identical events from two modules become one feed item.
        assert len(response.json()["items"]) == 1


def test_repeated_refresh_reuses_cache_without_spending(build):
    client, app, seen = build(daily_request_limit=1)
    with client:
        first = client.post("/v1/feed", json=feed_request(), headers=AUTH)
        second = client.post("/v1/feed", json=feed_request(), headers=AUTH)
        assert first.json() == second.json()
        assert len(seen) == 1
        assert app.state.store.usage() == 1
        changed = feed_request(region="Kenya")
        third = client.post("/v1/feed", json=changed, headers=AUTH)
        assert third.status_code == 429
        assert third.json() == {"error": "limit_reached"}
        assert len(seen) == 1


def test_fake_links_written_by_model_are_not_source_evidence(build):
    client, _, _ = build(lambda request: httpx.Response(200, json=model_response(metadata=False)))
    with client:
        response = client.post("/v1/feed", json=feed_request(), headers=AUTH)
        assert response.status_code == 502
        assert response.json() == {"error": "invalid_response"}


def test_failed_refresh_retains_previous_feed(build):
    calls = 0

    def handler(request):
        nonlocal calls
        calls += 1
        return httpx.Response(200, json=model_response()) if calls == 1 else httpx.Response(500, text="secret upstream diagnostic " + API_KEY)

    client, _, _ = build(handler, cache_seconds=0)
    with client:
        first = client.post("/v1/feed", json=feed_request(), headers=AUTH).json()
        failure = client.post("/v1/feed", json=feed_request(region="Other"), headers=AUTH)
        assert failure.status_code == 502
        assert API_KEY not in failure.text
        assert client.get("/v1/feed/latest", headers=AUTH).json() == first


@pytest.mark.parametrize("status,expected", [(401, 502), (429, 429), (500, 502), (302, 502)])
def test_provider_errors_are_sanitized_and_redirects_not_followed(build, status, expected):
    client, _, seen = build(lambda request: httpx.Response(status, text=API_KEY, headers={"Location": "https://untrusted.example/token"}))
    with client:
        response = client.post("/v1/feed", json=feed_request(), headers=AUTH)
        assert response.status_code == expected
        assert API_KEY not in response.text
        assert len(seen) == 1


def test_invalid_and_oversized_input_does_not_reach_provider(build):
    client, _, seen = build()
    with client:
        invalid = feed_request(context=[{"id": "context", "text": "sk-proj-" + "x" * 40}])
        response = client.post("/v1/feed", json=invalid, headers=AUTH)
        assert response.status_code == 422
        assert response.json() == {"error": "invalid_input"}
        response = client.post("/v1/feed", content=b"x" * 200_000, headers=AUTH)
        assert response.status_code == 413
        assert not seen


def test_empty_module_selection_does_not_call_provider(build):
    client, _, seen = build()
    with client:
        response = client.post("/v1/feed", json=feed_request(modules=[{"id": "news", "kind": "news", "enabled": False}]), headers=AUTH)
        assert response.json() == {"items": []}
        assert not seen


def test_horoscope_stays_creative_and_has_no_fictional_sources(build):
    client, _, seen = build()
    with client:
        body = feed_request(modules=[{"id": "horoscope", "kind": "horoscope", "zodiacSign": "Aries"}], context=[{"id": "work", "text": "Private work", "scopes": ["news"]}])
        response = client.post("/v1/feed", json=body, headers=AUTH)
        assert response.status_code == 200
        item = response.json()["items"][0]
        assert item["kind"] == "horoscope"
        assert item["sourceUrl"] == "" and item["citations"] == [] and item["deadline"] == ""
        upstream = json.loads(seen[0].content)
        assert "tools" not in upstream
        assert "Private work" not in seen[0].content.decode()


def test_horoscope_without_sign_skips_provider(build):
    client, _, seen = build()
    with client:
        response = client.post("/v1/feed", json=feed_request(modules=[{"id": "horoscope", "kind": "horoscope"}]), headers=AUTH)
        assert response.json() == {"items": []}
        assert not seen


def test_ask_returns_source_links(build):
    def handler(request):
        response = model_response()
        response["output"][-1]["content"][0]["text"] = json.dumps({"answer": "The change may simplify your deployment. That is an interpretation of the source.", "citations": [{"title": "Release", "url": ARTICLE}]})
        return httpx.Response(200, json=response)

    client, _, _ = build(handler)
    with client:
        response = client.post("/v1/ask", headers=AUTH, json={"question": "What does this mean for my work?", "context": []})
        assert response.status_code == 200
        assert response.json()["citations"][0]["url"] == ARTICLE


@pytest.mark.parametrize("summary", ["", "s" * 5000])
def test_ask_accepts_android_article_bounds(build, summary):
    def handler(request):
        response = model_response()
        response["output"][-1]["content"][0]["text"] = json.dumps({"answer": "A concise answer.", "citations": []})
        return httpx.Response(200, json=response)

    client, _, seen = build(handler)
    item = {
        "id": "imported-item", "moduleId": "news", "title": "t" * 300,
        "summary": summary, "why": "w" * 1200, "sourceName": "s" * 200,
        "sourceUrl": ARTICLE, "kind": "news", "aiGenerated": False,
        "citations": [{"title": "c" * 300, "url": ARTICLE}] * 10,
    }
    with client:
        response = client.post("/v1/ask", headers=AUTH, json={"question": "Explain this article.", "item": item})
        assert response.status_code == 200, response.text
        assert len(seen) == 1


def test_general_question_retains_android_news_scope(build):
    def handler(request):
        response = model_response()
        response["output"][-1]["content"][0]["text"] = json.dumps({"answer": "A concise answer.", "citations": []})
        return httpx.Response(200, json=response)

    client, _, seen = build(handler)
    with client:
        response = client.post("/v1/ask", headers=AUTH, json={
            "question": "What matters today?",
            "context": [{"id": "interest", "text": "Relevant approved topic", "scopes": ["news"]}],
        })
        assert response.status_code == 200
        assert "Relevant approved topic" in seen[0].content.decode()


def test_unexpected_errors_do_not_escape_with_private_details(build):
    class BrokenProvider:
        async def feed(self, *args):
            raise RuntimeError("Private diagnostic " + API_KEY)

    _, app, _ = build()
    app.state.engine.provider = BrokenProvider()
    # Normal ASGI test mode rethrows unhandled exceptions. This must not escape to
    # Uvicorn, where its traceback could include context or a provider credential.
    with TestClient(app, raise_server_exceptions=True) as client:
        response = client.post("/v1/feed", headers=AUTH, json=feed_request())
        assert response.status_code == 500
        assert response.json() == {"error": "operation_error"}
        assert API_KEY not in response.text


def test_future_publication_is_not_reported_as_published():
    items = parse_feed(model_response(publication="2999-01-01T00:00:00Z"), FeedModule(id="news"), 8)
    assert items[0].publishedAtMillis == 0


def test_response_annotations_are_supported():
    response = model_response(metadata=False)
    response["output"][0]["content"][0]["annotations"] = [{"type": "url_citation", "url": ARTICLE, "title": "Source"}]
    assert len(parse_feed(response, FeedModule(id="news"), 8)) == 1


@pytest.mark.parametrize("url", ["file:///etc/passwd", "http://127.0.0.1/", "https://localhost/a", "https://user:password@example.org/a", "javascript:alert(1)", "https://example.org/a\nsecret", "https://example.internal/a"])
def test_unsafe_source_urls_rejected(url):
    with pytest.raises(ValueError):
        public_url(url)


def test_canonical_event_urls_deduplicate_tracking_variants():
    assert canonical_url(ARTICLE + "?utm_source=feed#top") == canonical_url(ARTICLE)


def test_canonical_identity_matches_android_without_reescaping():
    url = "https://EXAMPLE.org:443/a%2Fb///?q=x%20y&ref=%2f&flag&UTM_source=feed#section"
    expected = "https://example.org:443/a%2Fb?q=x%20y&ref=%2f&flag"
    assert canonical_url(url) == expected
    item = parse_feed(model_response(url=url), FeedModule(id="news"), 1)[0]
    assert item.id == hashlib.sha256(expected.encode()).hexdigest()[:32]


def test_generation_keeps_concise_bounds_even_though_imported_articles_can_be_long():
    with pytest.raises(ProviderFailure):
        parse_feed(model_response(summary="s" * 1801), FeedModule(id="news"), 1)


def test_budget_reservations_are_atomic(tmp_path):
    store = Store(str(tmp_path / "concurrent.sqlite3"))

    def reserve(_):
        try:
            store.reserve_call(4)
            return True
        except BudgetExceeded:
            return False

    with ThreadPoolExecutor(max_workers=8) as pool:
        assert sum(pool.map(reserve, range(20))) == 4
    assert store.usage() == 4


def test_schedule_is_durable_and_auth_required(build):
    client, app, _ = build()
    body = {"intervalMinutes": 360, "request": feed_request()}
    with client:
        assert client.put("/v1/schedules/morning", json=body).status_code == 401
        response = client.put("/v1/schedules/morning", json=body, headers=AUTH)
        assert response.status_code == 200
        assert response.json()["schedulerEnabled"] is False
        reopened = Store(app.state.settings.database_path)
        assert reopened.schedules()[0]["id"] == "morning"
        assert client.delete("/v1/schedules/morning", headers=AUTH).json() == {"ok": True}
        assert reopened.schedules() == []


def test_schedule_quiet_hours_resolve_in_user_timezone():
    config = ScheduleConfig(request=feed_request(), timezone="Africa/Dar_es_Salaam")
    now = datetime(2026, 10, 23, 1, 0, tzinfo=timezone.utc).timestamp()
    expected = datetime(2026, 10, 23, 4, 0, tzinfo=timezone.utc).timestamp()
    assert next_allowed_time(config, now) == expected
    daytime = datetime(2026, 10, 23, 8, 0, tzinfo=timezone.utc).timestamp()
    assert next_allowed_time(config, daytime) == daytime


def test_schedule_worker_runs_and_stale_callback_cannot_restore_deleted_job(build):
    client, app, _ = build()
    config = ScheduleConfig(request=feed_request(), quietStartHour=0, quietEndHour=0)
    now = time.time()
    app.state.store.put_schedule("morning", config.model_dump_json(), True, now - 1)
    with client:
        assert asyncio.run(app.state.scheduler.tick(now)) is True
        rows = app.state.store.schedules()
        assert rows[0]["last_status"] == "updated"
        assert len(app.state.store.latest()) == 1
        app.state.store.put_schedule("delete", config.model_dump_json(), True, now - 1)
        job = app.state.store.claim_due(now)
        app.state.store.delete_schedule("delete")
        app.state.store.finish_schedule("delete", job["revision"], now + 600, now, "updated")
        assert all(row["id"] != "delete" for row in app.state.store.schedules())


def test_editing_schedule_invalidates_previous_revision(tmp_path):
    store = Store(str(tmp_path / "revision.sqlite3"))
    config = ScheduleConfig(request=feed_request())
    store.put_schedule("a", config.model_dump_json(), True, 1)
    job = store.claim_due(2)
    store.put_schedule("a", config.model_dump_json(), False, 100)
    store.finish_schedule("a", job["revision"], 999, 2, "updated")
    record = store.schedules()[0]
    assert record["enabled"] == 0 and record["next_run"] == 100 and record["last_status"] is None


def test_real_http_adapter_against_local_fake_upstream(tmp_path):
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            requests.append((self.path, json.loads(self.rfile.read(int(self.headers["Content-Length"])))) )
            body = json.dumps(model_response()).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    settings = Settings(service_token=TOKEN, openai_api_key=API_KEY, database_path=str(tmp_path / "wire.sqlite3"), upstream_url=f"http://127.0.0.1:{server.server_port}/v1", allow_insecure_upstream=True)
    try:
        with TestClient(create_app(settings)) as client:
            response = client.post("/v1/feed", json=feed_request(), headers=AUTH)
            assert response.status_code == 200
            assert response.json()["items"][0]["sourceUrl"] == ARTICLE
            assert requests[0][0] == "/v1/responses"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
