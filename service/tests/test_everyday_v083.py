"""Everyday topics work through validation, discovery and scoped provider payloads.

All upstream traffic uses MockTransport; no live model or account is accessed.
"""
import json
from typing import get_args

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from mebot_service.app import create_app
from mebot_service.config import Settings
from mebot_service.models import FeedModule, ModuleKind
from mebot_service.provider import MODULE_GUIDANCE

KINDS = ("food", "farming", "family", "sport", "home", "wellbeing")
TOKEN = "synthetic-everyday-service-token-083"


@pytest.mark.parametrize("kind", KINDS)
def test_preferences_match_android_and_reject_unknown_values(kind):
    assert kind in get_args(ModuleKind)
    module = FeedModule(id=kind, kind=kind, preferences={"format": "videos", "location": "Example region", "language": "sw"})
    assert module.preferences["language"] == "sw"
    for bad in ({"format": "executable"}, {"unknown": "value"}, {"location": "x" * 161}, {"language": "unknown"}):
        with pytest.raises(ValidationError):
            FeedModule(id=kind, kind=kind, preferences=bad)


@pytest.mark.parametrize("kind", KINDS)
@pytest.mark.parametrize("path", ("/v1/feed", "/v1/search"))
def test_discovery_roundtrip_keeps_kind_preferences_and_scopes(tmp_path, kind, path):
    seen = []
    url = "https://example.org/original-resource"
    module_id = "chosen-" + kind

    def transport(request):
        seen.append(json.loads(request.content))
        item = {"moduleId": module_id, "title": "A useful original resource", "summary": "Verified practical steps.",
                "why": "Matches the selected interest.", "sourceUrl": url, "sourceName": "Example", "publishedAt": "",
                "deadline": "", "citations": [], "relevance": 80, "contentType": "article"}
        return httpx.Response(200, json={"status": "completed", "output": [
            {"type": "web_search_call", "action": {"sources": [{"url": url, "title": "Original source"}]}},
            {"type": "message", "content": [{"type": "output_text", "text": json.dumps({"items": [item]})}]},
        ]})

    settings = Settings(service_token=TOKEN, openai_api_key="synthetic-upstream-key", database_path=str(tmp_path / "db.sqlite3"))
    app = create_app(settings, client=httpx.AsyncClient(transport=httpx.MockTransport(transport)))
    request = {"modules": [{"id": module_id, "kind": kind, "preferences": {"format": "articles", "location": "Example region", "language": "sw"}}],
               "context": [{"id": "yes", "text": "Selected interest", "scopes": [module_id]},
                           {"id": "no", "text": "Private unrelated note", "scopes": ["news"]}],
               "language": "en", "timezone": "UTC", "limit": 8}
    if path.endswith("search"):
        request["query"] = "Practical resources"
    with TestClient(app) as client:
        response = client.post(path, headers={"Authorization": "Bearer " + TOKEN}, json=request)
        assert response.status_code == 200, response.text
        item = response.json()["items"][0]
        assert (item["kind"], item["moduleId"]) == (kind, module_id)
    assert len(seen) == 1
    payload = json.loads(seen[0]["input"][1]["content"])
    assert "Private unrelated note" not in json.dumps(payload)
    assert "Selected interest" in json.dumps(payload)
    selected = payload["modules"][0] if path.endswith("search") else payload["module"]
    assert selected["preferences"]["format"] == "articles"
    assert MODULE_GUIDANCE[kind] in json.dumps(seen[0], ensure_ascii=False)
    assert seen[0]["tools"][0]["type"] == "web_search"
