from __future__ import annotations

import json
import sqlite3
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from mebot_service.app import create_app
from mebot_service.config import Settings
from mebot_service.realtime import RealtimeCallRequest, build_realtime_instructions
from mebot_service.storage import BudgetExceeded, RealtimeCallConflict, Store

TOKEN = "service-test-token-with-adequate-length-123456"
API_KEY = "fake-provider-key-never-used-for-network"
AUTH = {"Authorization": "Bearer " + TOKEN}
FAKE_AUDIO = b"test m4a bytes only; not real audio"
FINAL_SDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=candidate:1 1 UDP 1 192.0.2.1 5000 typ host\r\n"
ANSWER_SDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
CALL_ID = "rtc_1234567890"


@pytest.fixture
def build(tmp_path):
    def make(*, realtime_model="", transcription_model="", handler=None, **settings_overrides):
        sent: list[httpx.Request] = []

        def transport(request: httpx.Request) -> httpx.Response:
            sent.append(request)
            if handler is not None:
                return handler(request)
            if request.url.path == "/v1/realtime/calls":
                return httpx.Response(201, text=ANSWER_SDP, headers={"Location": "/v1/realtime/calls/" + CALL_ID})
            if request.url.path.endswith("/hangup"):
                return httpx.Response(204)
            return httpx.Response(200, json={"text": "An editable transcript."})

        database_path = settings_overrides.pop("database_path", str(tmp_path / "service.sqlite3"))
        settings = Settings(
            service_token=TOKEN,
            openai_api_key=API_KEY,
            database_path=database_path,
            realtime_model=realtime_model,
            transcription_model=transcription_model,
            **settings_overrides,
        )
        http = httpx.AsyncClient(transport=httpx.MockTransport(transport), follow_redirects=False)
        client = TestClient(create_app(settings, client=http), raise_server_exceptions=False)
        return client, sent

    return make


def call_body(request_id: str | None = None, **changes) -> dict:
    body = {
        "requestId": request_id or str(uuid.uuid4()),
        "sdp": FINAL_SDP,
        "moduleKind": "technology",
        "moduleId": "module-tech",
        "moduleTitle": "Technology",
        "moduleQuery": "Useful verified software releases",
        "language": "en",
        "context": [
            {"id": "about-you-global", "text": "I prefer practical answers.", "category": "profile", "scopes": [], "confirmed": True, "global": True},
            {"id": "tech-note", "text": "I maintain Python services.", "scopes": ["technology"], "confirmed": True},
            {"id": "scholarship-note", "text": "I need a scholarship.", "scopes": ["scholarships"], "confirmed": True},
            {"id": "unconfirmed", "text": "Do not send me.", "scopes": ["technology"], "confirmed": False},
        ],
        "item": {
            "moduleKind": "technology", "moduleId": "module-tech", "title": "A selected release",
            "summary": "The release notes say it improves service reliability.",
            "contentType": "article", "acquisition": "publisher_rss", "authorship": "publisher",
            "sourceName": "Official project", "sourceUrl": "https://example.org/release",
            "publishedAtMillis": 1_800_000_000_000,
            "citationReferences": [{"title": "Release notes", "url": "https://example.org/release"}],
            "furtherReading": [{"title": "Optional related page", "url": "https://example.org/more"}],
        },
        "history": [{"role": "user", "text": "What changed?"}],
    }
    body.update(changes)
    return body


def test_voice_and_transcription_are_disabled_by_default_and_authenticated(build):
    client, sent = build()
    with client:
        capability = client.get("/v1/realtime/capability", headers=AUTH)
        assert capability.status_code == 200
        assert capability.json() == {
            "realtimeVoice": {"available": False, "reason": "realtime_not_enabled"},
            "recordingTranscription": {"available": False, "reason": "transcription_not_enabled"},
        }
        assert client.post("/v1/realtime/calls", json=call_body(), headers=AUTH).status_code == 503
        assert client.post("/v1/transcriptions", content=FAKE_AUDIO,
                           headers={**AUTH, "Content-Type": "audio/mp4"}).status_code == 503
        assert client.get("/v1/realtime/capability").json() == {"error": "unauthorized"}
        assert client.post("/v1/realtime/session", json={}, headers=AUTH).status_code == 404
        assert not sent


def test_server_mediated_call_uses_official_endpoint_scoped_context_and_trusted_ref(build):
    client, sent = build(realtime_model="gpt-realtime-2.1")
    call = call_body()
    with client:
        response = client.post("/v1/realtime/calls", json=call, headers=AUTH)
        assert response.status_code == 200, response.text
        assert response.json() == {"answerSdp": ANSWER_SDP, "callReference": call["requestId"]}
        assert len(sent) == 1
        request = sent[0]
        assert request.url == "https://api.openai.com/v1/realtime/calls"
        assert request.headers["authorization"] == "Bearer " + API_KEY
        assert "multipart/form-data" in request.headers["content-type"]
        wire = request.content.decode("latin-1")
        assert FINAL_SDP in wire
        session_part = wire.split('name="session"', 1)[1].split("\r\n\r\n", 1)[1].split("\r\n--", 1)[0]
        payload = json.loads(session_part)
        assert payload["type"] == "realtime"
        assert payload["model"] == "gpt-realtime-2.1"
        assert payload["output_modalities"] == ["audio"]
        assert payload["max_output_tokens"] == 1200
        assert payload["audio"]["input"]["turn_detection"]["type"] == "server_vad"
        prompt = payload["instructions"]
        assert "I prefer practical answers." in prompt and "I maintain Python services." in prompt
        assert "Release notes" in prompt and "https://example.org/release" in prompt
        assert "Optional related page" in prompt
        assert "What changed?" in prompt and "Useful verified software releases" in prompt
        assert "I need a scholarship." not in prompt and "Do not send me." not in prompt
        assert "NOT factual evidence" in prompt
        assert "NOT been independently verified" in prompt
        assert API_KEY not in response.text and "I maintain Python services" not in response.text

        # Only hashes and opaque upstream ID are retained; no SDP, private context, or API key.
        with sqlite3.connect(client.app.state.settings.database_path) as db:
            row = db.execute("SELECT owner_hash,request_hash,state,upstream_call_id FROM realtime_calls").fetchone()
        assert row[0] != TOKEN and len(row[0]) == 64
        assert row[1] != FINAL_SDP and len(row[1]) == 64
        assert row[2] == "active" and row[3] == CALL_ID
        raw_db = open(client.app.state.settings.database_path, "rb").read()
        assert FINAL_SDP.encode() not in raw_db
        assert b"I maintain Python services" not in raw_db
        assert API_KEY.encode() not in raw_db

        cap = client.get("/v1/realtime/capability", headers=AUTH).json()
        assert cap["realtimeVoice"] == {"available": False, "reason": "active_call_limit_reached"}
        ended = client.post("/v1/realtime/calls/end", json={"callReference": call["requestId"]}, headers=AUTH)
        assert ended.status_code == 200 and ended.json() == {"ok": True}
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline and not any(req.url.path.endswith("/hangup") for req in sent):
            time.sleep(0.05)
        hangup = next(req for req in sent if req.url.path.endswith("/hangup"))
        assert hangup.url == f"https://api.openai.com/v1/realtime/calls/{CALL_ID}/hangup"
        assert hangup.headers["authorization"] == "Bearer " + API_KEY
        assert client.post("/v1/realtime/calls/end", json={"callReference": call["requestId"]}, headers=AUTH).status_code == 200


def test_general_voice_sends_only_global_profile_and_original_read_keeps_provenance():
    request = RealtimeCallRequest.model_validate({
        "requestId": str(uuid.uuid4()), "sdp": FINAL_SDP,
        "context": [
            {"id": "about-you-global", "text": "Confirmed profile.", "category": "profile", "scopes": [], "confirmed": True, "global": True},
            {"id": "news-note", "text": "Only relevant to news.", "scopes": ["news"], "confirmed": True},
        ],
    })
    prompt = build_realtime_instructions(request)
    assert "Confirmed profile." in prompt and "Only relevant to news." not in prompt

    original = call_body(moduleKind="learning", moduleId="module-read", item={
        "moduleKind": "learning", "moduleId": "module-read", "title": "AI-authored explainer",
        "summary": "Evergreen explanation.", "contentType": "original_read", "acquisition": "original_read",
        "authorship": "ai", "sourceName": "", "sourceUrl": "", "publishedAtMillis": 0,
        "sections": [
            {"heading": "Background", "body": "A" * 600},
            {"heading": "Main idea", "body": "B" * 600},
        ],
        "citationReferences": [], "furtherReading": [{"title": "Optional next", "url": "https://example.org/next"}],
    })
    parsed = RealtimeCallRequest.model_validate(original)
    prompt = build_realtime_instructions(parsed)
    assert "AI-authored explainer" in prompt and "Background" in prompt and "Optional next" in prompt
    assert "callerProvidedCitationReferences" in prompt


def test_realtime_instruction_builder_without_evidence_refuses_web_access_or_current_claims():
    request = RealtimeCallRequest.model_validate({
        "requestId": str(uuid.uuid4()),
        "sdp": FINAL_SDP,
        "moduleKind": "technology",
        "moduleId": "module-tech",
        "moduleTitle": "Technology",
        "moduleQuery": "current releases",
    })
    prompt = build_realtime_instructions(request)
    assert "There is no web-search or source-verification tool." in prompt
    assert "Citation references are caller-provided and have NOT been independently verified" in prompt
    assert "If no relevant dated evidence exists, say you cannot verify" in prompt
    assert "instead of inventing current news, dates, prices" in prompt
    assert '"sourceUrl"' not in prompt


def test_scope_conflict_local_citation_and_invalid_original_read_are_rejected_before_upstream(build):
    client, sent = build(realtime_model="gpt-realtime-2.1")
    with client:
        conflict = client.post("/v1/realtime/calls", json=call_body(moduleKind="scholarships"), headers=AUTH)
        bad_citation = client.post("/v1/realtime/calls", json=call_body(item={
            "moduleKind": "technology", "moduleId": "x",
            "citationReferences": [{"url": "http://localhost/private"}],
        }), headers=AUTH)
        bad_original = client.post("/v1/realtime/calls", json=call_body(item={
            "moduleKind": "learning", "moduleId": "read", "contentType": "original_read",
            "acquisition": "original_read", "authorship": "ai", "sections": [],
        }), headers=AUTH)
        assert conflict.status_code == 422
        assert bad_citation.status_code == 422
        assert bad_original.status_code == 422
        assert not sent


def test_create_conflict_provider_failure_and_no_unmetered_upstream(build, tmp_path):
    client, sent = build(realtime_model="gpt-realtime-2.1", daily_request_limit=1,
                         transcription_model="gpt-4o-mini-transcribe",
                         handler=lambda request: httpx.Response(201, text=ANSWER_SDP, headers={"Location": "/v1/realtime/calls/" + CALL_ID}) if request.url.path.endswith("/calls") and not request.url.path.endswith("/hangup") else httpx.Response(204))
    first = call_body()
    with client:
        assert client.post("/v1/realtime/calls", json=first, headers=AUTH).status_code == 200
        duplicate = client.post("/v1/realtime/calls", json=first, headers=AUTH)
        assert duplicate.status_code == 409 and duplicate.json() == {"error": "call_request_already_used"}
        # The normal shared daily limit gates another independently billable feature.
        before = len(sent)
        response = client.post("/v1/transcriptions", content=FAKE_AUDIO,
                               headers={**AUTH, "Content-Type": "audio/mp4"})
        assert response.status_code == 429 and response.json() == {"error": "limit_reached"}
        assert len(sent) == before
        assert client.post("/v1/realtime/calls/end", json={"callReference": first["requestId"]}, headers=AUTH).status_code == 200

    failure_client, failure_sent = build(realtime_model="gpt-realtime-2.1", database_path=str(Path(tmp_path) / "provider-failure.sqlite3"),
        handler=lambda request: httpx.Response(401, text=API_KEY))
    with failure_client:
        failed = failure_client.post("/v1/realtime/calls", json=call_body(), headers=AUTH)
        assert failed.status_code == 502 and failed.json() == {"error": "provider_error"}
        assert API_KEY not in failed.text and "v=0" not in failed.text
        assert len(failure_sent) == 1
        with sqlite3.connect(failure_client.app.state.settings.database_path) as db:
            state = db.execute("SELECT state FROM realtime_calls").fetchone()[0]
        assert state == "failed"


def test_realtime_daily_feature_cap_still_applies_after_completed_hangup(build):
    client, sent = build(realtime_model="gpt-realtime-2.1", daily_request_limit=5,
                         realtime_daily_session_limit=1)
    with client:
        first = client.post("/v1/realtime/calls", json=call_body(), headers=AUTH)
        assert first.status_code == 200, first.text
        request_id = first.json()["callReference"]
        assert client.post("/v1/realtime/calls/end", json={"callReference": request_id},
                           headers=AUTH).status_code == 200

        # A completed/hung-up call frees the one-active-call slot but must not refund the
        # feature's daily billable-session reservation.
        database = client.app.state.settings.database_path
        deadline = time.monotonic() + 3
        state = None
        while time.monotonic() < deadline:
            with sqlite3.connect(database) as db:
                row = db.execute("SELECT state FROM realtime_calls WHERE request_id=?", (request_id,)).fetchone()
            state = row[0] if row else None
            if state == "ended":
                break
            time.sleep(0.05)
        assert state == "ended"
        calls_before_retry = sum(request.url.path == "/v1/realtime/calls" for request in sent)
        retry = client.post("/v1/realtime/calls", json=call_body(), headers=AUTH)
        assert retry.status_code == 429 and retry.json() == {"error": "limit_reached"}
        assert sum(request.url.path == "/v1/realtime/calls" for request in sent) == calls_before_retry == 1


def test_transcription_feature_daily_cap_blocks_after_first_upload(build):
    client, sent = build(transcription_model="gpt-4o-mini-transcribe", daily_request_limit=5,
                         transcription_daily_request_limit=1)
    with client:
        first = client.post("/v1/transcriptions", content=FAKE_AUDIO,
                            headers={**AUTH, "Content-Type": "audio/mp4"})
        assert first.status_code == 200 and first.json() == {"text": "An editable transcript."}
        retry = client.post("/v1/transcriptions", content=FAKE_AUDIO,
                            headers={**AUTH, "Content-Type": "audio/mp4"})
        assert retry.status_code == 429 and retry.json() == {"error": "limit_reached"}
        assert len(sent) == 1


def test_transcription_first_also_consumes_shared_budget_before_realtime_provider_call(build):
    client, sent = build(realtime_model="gpt-realtime-2.1",
                         transcription_model="gpt-4o-mini-transcribe", daily_request_limit=1)
    with client:
        transcript = client.post("/v1/transcriptions", content=FAKE_AUDIO,
                                 headers={**AUTH, "Content-Type": "audio/mp4"})
        assert transcript.status_code == 200
        capability = client.get("/v1/realtime/capability", headers=AUTH).json()
        assert capability["realtimeVoice"] == {"available": False, "reason": "daily_limit_reached"}
        before_retry = sum(request.url.path == "/v1/realtime/calls" for request in sent)
        voice = client.post("/v1/realtime/calls", json=call_body(), headers=AUTH)
        assert voice.status_code == 429 and voice.json() == {"error": "limit_reached"}
        assert sum(request.url.path == "/v1/realtime/calls" for request in sent) == before_retry == 0


def test_capability_reflects_global_daily_budget_used_by_text_provider(build):
    text_response = {
        "status": "completed",
        "output": [{"type": "message", "content": [{
            "type": "output_text",
            "text": json.dumps({"answer": "A bounded test answer.", "citations": []}),
        }]}],
    }
    client, sent = build(realtime_model="gpt-realtime-2.1",
                         transcription_model="gpt-4o-mini-transcribe", daily_request_limit=1,
                         handler=lambda request: httpx.Response(200, json=text_response))
    with client:
        ask = client.post("/v1/ask", json={"question": "Give a brief reflection.",
                                            "moduleKind": "horoscope"}, headers=AUTH)
        assert ask.status_code == 200, ask.text
        assert client.app.state.store.usage() == 1
        before_capability = len(sent)
        capability = client.get("/v1/realtime/capability", headers=AUTH)
        assert capability.status_code == 200
        assert capability.json() == {
            "realtimeVoice": {"available": False, "reason": "daily_limit_reached"},
            "recordingTranscription": {"available": False, "reason": "daily_limit_reached"},
        }
        assert len(sent) == before_capability == 1


def test_location_injection_is_rejected_and_create_remains_unknown_without_hangup_to_attacker(build, tmp_path):
    for location in (
        "https://attacker.example/v1/realtime/calls/rtc_1234567890",
        "https://api.openai.com.attacker.example/v1/realtime/calls/rtc_1234567890",
        "//attacker.example/v1/realtime/calls/rtc_1234567890",
        "/v1/realtime/calls/rtc_bad/path",
    ):
        client, sent = build(realtime_model="gpt-realtime-2.1", database_path=str(Path(tmp_path) / f"location-{uuid.uuid4()}.sqlite3"),
            handler=lambda request, location=location: httpx.Response(201, text=ANSWER_SDP, headers={"Location": location}))
        with client:
            response = client.post("/v1/realtime/calls", json=call_body(), headers=AUTH)
            assert response.status_code == 502
            assert all(req.url.host == "api.openai.com" for req in sent)
            with sqlite3.connect(client.app.state.settings.database_path) as db:
                state = db.execute("SELECT state FROM realtime_calls").fetchone()[0]
                call_id = db.execute("SELECT upstream_call_id FROM realtime_calls").fetchone()[0]
            assert state == "creation_unknown" and call_id is None


def test_explicit_transcription_and_validation_are_bounded(build):
    client, sent = build(transcription_model="gpt-4o-mini-transcribe")
    with client:
        response = client.post("/v1/transcriptions", content=FAKE_AUDIO,
                               headers={**AUTH, "Content-Type": "audio/mp4"})
        assert response.status_code == 200 and response.json() == {"text": "An editable transcript."}
        request = sent[0]
        assert request.url == "https://api.openai.com/v1/audio/transcriptions"
        assert request.headers["authorization"] == "Bearer " + API_KEY
        assert b"gpt-4o-mini-transcribe" in request.content
        assert b'filename="mebot-recording.m4a"' in request.content
        assert FAKE_AUDIO in request.content
        assert API_KEY not in response.text
        raw_database = Path(client.app.state.settings.database_path).read_bytes()
        assert FAKE_AUDIO not in raw_database
        assert b"An editable transcript." not in raw_database

    bad_client, bad_sent = build(transcription_model="gpt-4o-mini-transcribe")
    with bad_client:
        wrong_type = bad_client.post("/v1/transcriptions", content=FAKE_AUDIO,
                                     headers={**AUTH, "Content-Type": "application/octet-stream"})
        oversized = bad_client.post("/v1/transcriptions", content=b"x" * (2 * 1024 * 1024 + 1),
                                    headers={**AUTH, "Content-Type": "audio/mp4"})
        assert wrong_type.status_code == 422 and oversized.status_code == 413
        assert not bad_sent


def test_end_before_create_tombstone_is_idempotent_and_owner_scoped(build):
    client, sent = build(realtime_model="gpt-realtime-2.1")
    request_id = str(uuid.uuid4())
    with client:
        assert client.post("/v1/realtime/calls/end", json={"callReference": request_id}, headers=AUTH).status_code == 200
        late = client.post("/v1/realtime/calls", json=call_body(request_id), headers=AUTH)
        assert late.status_code == 409 and late.json() == {"error": "call_request_already_used"}
        assert not sent
        with sqlite3.connect(client.app.state.settings.database_path) as db:
            owner_hash, state = db.execute("SELECT owner_hash,state FROM realtime_calls").fetchone()
        assert owner_hash != TOKEN and state == "ended"


def test_lifespan_reaper_expires_durable_call_and_retries_hangup(build):
    hangup_attempts = 0

    def provider(request: httpx.Request) -> httpx.Response:
        nonlocal hangup_attempts
        if request.url.path == "/v1/realtime/calls":
            return httpx.Response(201, text=ANSWER_SDP,
                                  headers={"Location": "/v1/realtime/calls/" + CALL_ID})
        if request.url.path.endswith("/hangup"):
            hangup_attempts += 1
            return httpx.Response(503 if hangup_attempts == 1 else 204)
        return httpx.Response(500)

    client, sent = build(realtime_model="gpt-realtime-2.1", handler=provider)
    with client:
        response = client.post("/v1/realtime/calls", json=call_body(), headers=AUTH)
        assert response.status_code == 200
        request_id = response.json()["callReference"]
        assert client.app.state.realtime_reaper is not None
        # Simulate a durable expiry after a service restart/deployment. The normal
        # lifespan worker must discover it from SQLite and retry a transient failure.
        with sqlite3.connect(client.app.state.settings.database_path) as db:
            db.execute("UPDATE realtime_calls SET expires_at=? WHERE request_id=?", (time.time() - 1, request_id))
            db.commit()
        deadline = time.monotonic() + 8
        state = None
        while time.monotonic() < deadline:
            with sqlite3.connect(client.app.state.settings.database_path) as db:
                row = db.execute("SELECT state,attempts FROM realtime_calls WHERE request_id=?", (request_id,)).fetchone()
            state = row
            if row and row[0] == "ended":
                break
            time.sleep(0.05)
        assert state is not None and state[0] == "ended"
        assert hangup_attempts >= 2
        hangups = [request for request in sent if request.url.path.endswith("/hangup")]
        assert len(hangups) >= 2
        assert all(request.url.host == "api.openai.com" for request in hangups)


def test_pre_cancel_tombstones_are_owner_scoped_and_bounded(tmp_path):
    from mebot_service.storage import MAX_REALTIME_END_TOMBSTONES_PER_PRINCIPAL

    store = Store(str(tmp_path / "tombstones.sqlite3"))
    now = time.time()
    owner = "a" * 64
    other_owner = "b" * 64
    for index in range(MAX_REALTIME_END_TOMBSTONES_PER_PRINCIPAL):
        store.request_realtime_call_end(owner, str(uuid.uuid5(uuid.NAMESPACE_URL, f"tombstone:{index}")), now=now)
    with pytest.raises(RealtimeCallConflict) as error:
        store.request_realtime_call_end(owner, str(uuid.uuid4()), now=now)
    assert error.value.code == "call_end_limit_reached"
    with pytest.raises(RealtimeCallConflict) as create_error:
        store.begin_realtime_call(owner, str(uuid.uuid4()), "hash", feature_limit=5,
                                  total_limit=100, max_active=1, now=now, create_deadline=now + 10)
    assert create_error.value.code == "call_end_limit_reached"
    # One principal's untrusted tombstone flood cannot block another authenticated principal.
    store.begin_realtime_call(other_owner, str(uuid.uuid4()), "hash", feature_limit=5,
                              total_limit=100, max_active=1, now=now, create_deadline=now + 10)
    assert store.active_realtime_call_count(other_owner) == 1


def test_realtime_feature_budget_reservation_is_atomic_under_concurrent_starts(tmp_path):
    store = Store(str(tmp_path / "atomic-budget.sqlite3"))
    owner = "c" * 64
    now = time.time()
    barrier = threading.Barrier(2)

    def reserve(index: int) -> str:
        barrier.wait(timeout=3)
        try:
            store.begin_realtime_call(
                owner, str(uuid.uuid4()), f"request-hash-{index}",
                feature_limit=1, total_limit=10, max_active=2,
                now=now, create_deadline=now + 30,
            )
            return "reserved"
        except BudgetExceeded:
            return "limited"

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(reserve, (1, 2)))
    assert sorted(results) == ["limited", "reserved"]
    assert store.feature_usage("realtime_session") == 1
    assert store.usage() == 1
    assert store.active_realtime_call_count(owner) == 1
