from __future__ import annotations

import copy

import pytest
from pydantic import ValidationError

from mebot_service.models import AskRequest, FeedItem


PUBLIC_URL = "https://example.org/research/original-source"


def sourced_item(**updates):
    item = {
        "id": "source-item", "moduleId": "technology", "kind": "technology",
        "title": "A documented update", "summary": "A concise source-backed summary.",
        "sourceName": "Example publisher", "sourceUrl": PUBLIC_URL,
    }
    item.update(updates)
    return item


def original_read(**updates):
    item = {
        "id": "original-read-1", "moduleId": "technology", "kind": "technology",
        "title": "A substantial original read", "summary": "A short, accurate preview of the reading.",
        "contentType": "original_read", "acquisition": "original_read", "authorship": "ai",
        "aiGenerated": True, "sourceName": "", "sourceUrl": "", "publishedAtMillis": 0,
        "fetchedAtMillis": 1_798_000_000_000, "deadline": "", "citations": [],
        "sections": [
            {"heading": "The core idea", "body": "This section explains the concept and its context. " * 12},
            {"heading": "How to think about it", "body": "This section considers a useful example and trade-offs. " * 12},
        ],
        "furtherReading": [{"title": "A related public reference", "url": PUBLIC_URL}],
    }
    item.update(updates)
    return item


def test_old_feed_items_default_to_unknown_provenance_without_breaking_decode():
    decoded = FeedItem.model_validate(sourced_item())
    assert decoded.acquisition == "legacy_unknown"
    assert decoded.authorship == "legacy_unknown"
    assert decoded.sections == []
    assert decoded.furtherReading == []
    assert decoded.model_dump()["contentType"] == "article"


@pytest.mark.parametrize("acquisition", ["publisher_rss", "public_api"])
def test_direct_publisher_and_public_api_items_need_attribution_not_ai_citations(acquisition):
    parsed = FeedItem.model_validate(sourced_item(acquisition=acquisition, authorship="publisher", citations=[]))
    assert parsed.sourceUrl == PUBLIC_URL
    assert parsed.citations == []


@pytest.mark.parametrize("acquisition", ["ai_web_grounded", "ai_source_grounded"])
def test_ai_grounded_evidence_cannot_be_replaced_by_further_reading(acquisition):
    valid = FeedItem.model_validate(sourced_item(
        acquisition=acquisition, authorship="ai", citations=[{"title": "Evidence", "url": PUBLIC_URL}],
    ))
    assert valid.citations[0].url == PUBLIC_URL
    with pytest.raises(ValidationError):
        FeedItem.model_validate(sourced_item(
            acquisition=acquisition, authorship="ai", citations=[],
            furtherReading=[{"title": "Not claim evidence", "url": PUBLIC_URL}],
        ))
    with pytest.raises(ValidationError):
        FeedItem.model_validate(sourced_item(
            acquisition=acquisition, authorship="ai", citations=[], furtherReading=[],
        ))


def test_unknown_future_provenance_codes_are_tolerated_only_with_normal_source_shape():
    parsed = FeedItem.model_validate(sourced_item(acquisition="future_adapter_v2", authorship="future_editor"))
    assert parsed.acquisition == "future_adapter_v2"
    assert parsed.authorship == "future_editor"
    with pytest.raises(ValidationError):
        FeedItem.model_validate(sourced_item(sourceUrl="", acquisition="legacy_unknown"))
    with pytest.raises(ValidationError):
        AskRequest.model_validate({
            "question": "Explain this item.",
            "item": sourced_item(sourceUrl="", acquisition="legacy_unknown"),
        })


def test_original_read_is_a_substantial_source_less_ai_authored_item_with_separate_optional_reading():
    parsed = FeedItem.model_validate(original_read())
    assert parsed.contentType == "original_read"
    assert sum(len(section.body.strip()) for section in parsed.sections) >= 1000
    assert parsed.citations == []
    assert parsed.furtherReading[0].url == PUBLIC_URL


@pytest.mark.parametrize("change", [
    {"contentType": "article"},
    {"acquisition": "legacy_unknown"},
    {"authorship": "publisher"},
    {"aiGenerated": False},
    {"sourceUrl": PUBLIC_URL},
    {"sourceName": "Publisher"},
    {"publishedAtMillis": 1_798_000_000_000},
    {"deadline": "2026-11-01"},
    {"summary": ""},
    {"kind": "weather"},
    {"kind": "horoscope"},
    {"sections": [{"heading": "Only one", "body": "x" * 1200}]},
    {"sections": [{"heading": "Short", "body": "x" * 179}, {"heading": "Long", "body": "x" * 900}]},
    {"sections": [{"heading": "A", "body": "x" * 6000}, {"heading": "B", "body": "x" * 6000}, {"heading": "C", "body": "x" * 1}]},
    {"furtherReading": [{"title": "Private", "url": "http://127.0.0.1/private"}]},
])
def test_malformed_original_read_is_rejected_without_a_legacy_blank_source_bypass(change):
    item = original_read()
    item.update(copy.deepcopy(change))
    with pytest.raises(ValidationError):
        FeedItem.model_validate(item)


def test_original_read_fields_remain_bounded_and_reject_control_characters():
    item = original_read()
    item["sections"][0]["heading"] = "Bad\x01heading"
    with pytest.raises(ValidationError):
        FeedItem.model_validate(item)

    item = original_read()
    item["sections"][0]["body"] = ("Safe text. " * 30) + "\x00"
    with pytest.raises(ValidationError):
        FeedItem.model_validate(item)

    item = original_read()
    item["sections"].append({"heading": "Extra", "body": "valid body " * 50})
    item["sections"].append({"heading": "Another", "body": "valid body " * 50})
    item["sections"].append({"heading": "Too many", "body": "valid body " * 50})
    with pytest.raises(ValidationError):
        FeedItem.model_validate(item)

