"""Default guidance and explicitly scoped module settings reach real provider payloads."""
from __future__ import annotations

import json
from typing import get_args

import httpx
import pytest
from fastapi.testclient import TestClient

from mebot_service.app import create_app
from mebot_service.config import Settings
from mebot_service.models import ModuleKind
from mebot_service.provider import MODULE_GUIDANCE


AUTH = {"Authorization": "Bearer synthetic-default-context-service-token"}


@pytest.fixture
def service(tmp_path):
    seen = []

    def transport(request):
        seen.append(json.loads(request.content))
        return httpx.Response(200, json={"status": "completed", "output": [{
            "type": "message", "content": [{"type": "output_text", "text": json.dumps({"answer": "A completed explanation.", "citations": []})}],
        }]})

    settings = Settings(service_token=AUTH["Authorization"].removeprefix("Bearer "),
                        openai_api_key="synthetic-provider-value", database_path=str(tmp_path / "service.sqlite3"))
    app = create_app(settings, client=httpx.AsyncClient(transport=httpx.MockTransport(transport)))
    with TestClient(app, raise_server_exceptions=False) as client:
        yield client, app, seen


@pytest.mark.parametrize("kind", get_args(ModuleKind))
def test_every_module_has_server_owned_detail_guidance_and_confirmed_scopes(service, kind):
    client, _, seen = service
    unrelated = "music" if kind != "music" else "news"
    request = {
        "question": "Explain this for me", "moduleKind": kind,
        "module": {"id": "selected-" + kind, "kind": kind, "query": "A selected focus"},
        "context": [
            {"id": "approved", "text": "A confirmed matching interest", "scopes": [kind]},
            {"id": "explicit-id", "text": "An explicitly scoped module note", "scopes": ["selected-" + kind]},
            {"id": "unconfirmed", "text": "An unconfirmed private note", "scopes": [kind], "confirmed": False},
            {"id": "unrelated", "text": "An unrelated private note", "scopes": [unrelated]},
        ],
    }
    response = client.post("/v1/ask", headers=AUTH, json=request)
    assert response.status_code == 200, response.text
    assert len(seen) == 1
    payload = json.loads(seen[0]["input"][1]["content"])
    assert payload["defaultContext"] == MODULE_GUIDANCE[kind]
    assert MODULE_GUIDANCE[kind] in seen[0]["input"][0]["content"]
    assert payload["module"]["query"] == "A selected focus"
    assert [note["text"] for note in payload["context"]] == ["A confirmed matching interest", "An explicitly scoped module note"]
    assert "An unconfirmed private note" not in json.dumps(payload)
    assert "An unrelated private note" not in json.dumps(payload)
    assert ("tools" in seen[0]) is (kind != "horoscope")


def test_selected_preferences_and_language_reach_detail_without_becoming_system_instructions(service):
    client, _, seen = service
    query = "Choose a practical viewing example"
    response = client.post("/v1/ask", headers=AUTH, json={
        "question": "Explain this", "language": "en",
        "module": {"id": "viewing", "kind": "entertainment", "query": query,
                   "preferences": {"mood": "tense", "type": "series", "language": "sw"}},
    })
    assert response.status_code == 200, response.text
    payload = json.loads(seen[0]["input"][1]["content"])
    assert payload["language"] == "Kiswahili"
    assert payload["module"]["preferences"] == {"mood": "tense", "type": "series", "language": "sw"}
    assert payload["discoveryGoal"] == query
    assert query not in seen[0]["input"][0]["content"]


@pytest.mark.parametrize("updates", [
    {"moduleKind": "news"},
    {"moduleKind": ""},
    {"item": {"id": "item", "moduleId": "wrong-id", "kind": "technology", "title": "Title", "summary": "Summary", "sourceUrl": "https://example.org/item"}},
    {"item": {"id": "item", "moduleId": "technical", "kind": "music", "title": "Title", "summary": "Summary", "sourceUrl": "https://example.org/item"}},
    {"module": {"id": "technical", "kind": "technology", "preferences": {"arbitrary": "value"}}},
    {"defaultContext": "Client text pretending to be trusted module instructions"},
])
def test_mismatched_or_unvalidated_module_context_is_rejected_before_provider_work(service, updates):
    client, app, seen = service
    request = {"question": "Explain this", "module": {"id": "technical", "kind": "technology"}, **updates}
    response = client.post("/v1/ask", headers=AUTH, json=request)
    assert response.status_code == 422 and response.json() == {"error": "invalid_input"}
    assert seen == [] and app.state.store.usage() == 0


def test_general_workspace_remains_unscoped_and_old_requests_keep_their_news_default(service):
    client, _, seen = service
    context = [{"id": "private-news", "text": "A confirmed news interest", "scopes": ["news"]}]
    assert client.post("/v1/ask", headers=AUTH, json={"question": "General question", "moduleKind": "", "context": context}).status_code == 200
    general = json.loads(seen[0]["input"][1]["content"])
    assert general["context"] == [] and general["defaultContext"] == ""
    assert client.post("/v1/ask", headers=AUTH, json={"question": "Legacy question", "context": context}).status_code == 200
    legacy = json.loads(seen[1]["input"][1]["content"])
    assert legacy["context"][0]["text"] == "A confirmed news interest"
    assert legacy["defaultContext"] == MODULE_GUIDANCE["news"]
