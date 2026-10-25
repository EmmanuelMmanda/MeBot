"""Actual HTTP request contract; upstream MockTransport only, no live account calls."""
import json

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from mebot_service.app import create_app
from mebot_service.config import Settings
from mebot_service.models import ContextEntry, FeedRequest
from mebot_service.provider import scoped_context

TOKEN = 'synthetic-global-profile-service-085'
PROFILE = {'id': 'about-you-global', 'text': 'GLOBAL approved biography', 'category': 'profile', 'scopes': [], 'global': True}
NOTES = [{'id': 'learning-note', 'text': 'SCOPED learning only', 'scopes': ['learning']},
         {'id': 'private', 'text': 'PRIVATE music only', 'scopes': ['music']},
         {'id': 'guess', 'text': 'UNCONFIRMED guessed note', 'scopes': ['learning'], 'confirmed': False}]


@pytest.mark.parametrize('route', ('feed', 'search', 'module-chat', 'general-chat', 'routed-general-chat'))
def test_global_profile_reaches_all_actual_provider_payloads_without_note_leak(tmp_path, route):
    seen = []
    url = 'https://example.org/original'

    def transport(request):
        seen.append(json.loads(request.content))
        data = {'answer': 'A completed explanation.', 'citations': []} if route.endswith('chat') else {'items': [
            {'moduleId': 'custom-learning', 'title': 'Original learning resource', 'summary': 'Useful steps.', 'why': 'Matches the request.',
             'sourceUrl': url, 'sourceName': 'Example', 'publishedAt': '', 'deadline': '', 'citations': [], 'relevance': 80, 'contentType': 'article'}]}
        return httpx.Response(200, json={'status': 'completed', 'output': [
            {'type': 'web_search_call', 'action': {'sources': [{'url': url, 'title': 'Original'}]}},
            {'type': 'message', 'content': [{'type': 'output_text', 'text': json.dumps(data)}]}]})

    settings = Settings(service_token=TOKEN, openai_api_key='synthetic-provider-key', database_path=str(tmp_path / 'db.sqlite3'))
    app = create_app(settings, client=httpx.AsyncClient(transport=httpx.MockTransport(transport)))
    module = {'id': 'custom-learning', 'kind': 'learning', 'query': 'Custom future learning interest'}
    request = {'context': [*NOTES, PROFILE]}
    if route in ('feed', 'search'):
        request.update(modules=[module], timezone='UTC', limit=8)
        if route == 'search':
            request['query'] = 'Find useful resources'
            request['modules'].append({'id': 'second-new-module', 'kind': 'news'})
        path = '/v1/' + route
    else:
        request.update(question='Explain this idea', moduleKind='')
        if route == 'routed-general-chat':
            request.update(contextKinds=['learning'])
        if route == 'module-chat':
            request.update(module=module, moduleKind='learning', contextKinds=['music'])  # explicit module wins
        path = '/v1/ask'
    with TestClient(app) as client:
        response = client.post(path, headers={'Authorization': 'Bearer ' + TOKEN}, json=request)
        assert response.status_code == 200, response.text
    payload = json.loads(seen[0]['input'][1]['content'])
    texts = [entry['text'] for entry in payload['context']]
    assert texts[0] == PROFILE['text']
    assert ('SCOPED learning only' in texts) == (route != 'general-chat')
    assert 'PRIVATE music only' not in json.dumps(payload)
    assert 'UNCONFIRMED' not in json.dumps(payload)
    if route == 'search':
        assert payload['modules'][0]['contextIds'] == ['about-you-global', 'learning-note']
        assert payload['modules'][1]['contextIds'] == ['about-you-global']


@pytest.mark.parametrize('changes', ({'id': 'ordinary-note'}, {'category': 'interest'}, {'scopes': ['learning']}))
def test_arbitrary_notes_cannot_promote_themselves_to_global(changes):
    with pytest.raises(ValidationError):
        ContextEntry.model_validate({**PROFILE, **changes})


def test_unconfirmed_global_is_never_sent_and_legacy_notes_stay_scoped():
    draft = ContextEntry.model_validate({**PROFILE, 'confirmed': False})
    legacy = ContextEntry.model_validate(NOTES[0])
    assert not legacy.global_
    assert scoped_context([draft, legacy], '') == []
    assert [entry['text'] for entry in scoped_context([draft, legacy], 'learning')] == ['SCOPED learning only']


def test_global_priority_bounded_and_schedule_serialization_roundtrips():
    notes = [ContextEntry(id=f'n{i}', text=f'Note {i}', scopes={'learning'}) for i in range(63)]
    global_entry = ContextEntry.model_validate(PROFILE)
    selected = scoped_context([*notes, global_entry], 'learning', 'new-custom-id')
    assert len(selected) == 32 and selected[0]['text'] == PROFILE['text']
    request = FeedRequest(modules=[{'id': 'disabled-then-enabled', 'kind': 'learning'}], context=[global_entry])
    restored = FeedRequest.model_validate_json(request.model_dump_json())
    assert restored.context[0].global_ and restored.context[0].scopes == set()


def test_general_routing_only_accepts_known_kinds():
    from mebot_service.models import AskRequest
    with pytest.raises(ValidationError):
        AskRequest(question='Hello', moduleKind='', contextKinds=['arbitrary-note-id'])
