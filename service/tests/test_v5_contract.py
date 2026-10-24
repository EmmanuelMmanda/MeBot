"""New mobile module/media contracts, with no paid provider calls."""
import json

import pytest
from pydantic import ValidationError

from mebot_service.models import AskRequest, FeedItem, FeedModule, FeedRequest
from mebot_service.provider import parse_feed


def sourced_response(kind="video"):
    url = "https://example.org/official-lesson"
    item = {"title": "An official lesson", "summary": "Practice a documented technique.", "sourceUrl": url,
            "sourceName": "Ignored model label", "publishedAt": "", "deadline": "1999-01-01", "why": "",
            "relevance": 80, "citations": [], "contentType": kind,
            "media": {"url": "https://example.org/invented.jpg", "sourceUrl": url}}
    return {"status": "completed", "output": [
        {"type": "web_search_call", "action": {"sources": [{"url": url, "title": "Official lesson"}]}},
        {"type": "message", "content": [{"type": "output_text", "text": json.dumps({"items": [item]})}]},
    ]}


@pytest.mark.parametrize("kind", ["news", "opportunities", "watch", "horoscope", "technology", "security", "scholarships", "business", "science", "learning", "entertainment", "music", "events", "travel", "weather", "github"])
def test_every_mobile_module_is_a_valid_service_request(kind):
    request = FeedRequest(modules=[FeedModule(id=kind, kind=kind)], recentlyShown=[{"moduleId": kind, "title": "Already shown", "sourceUrl": "https://example.org/previous"}])
    assert request.modules[0].kind == kind


def test_media_and_weather_from_android_survive_ask_validation():
    item = {"id": "weather-1", "moduleId": "weather", "kind": "weather", "contentType": "weather", "title": "Example City", "summary": "", "sourceUrl": "https://open-meteo.com/en/docs",
            "weather": {"temperatureC": 24.5, "feelsLikeC": 25.1, "precipitationChance": 20, "weatherCode": 3, "location": "Example City"},
            "media": {"url": "https://images.example.org/forecast.png", "sourceUrl": "https://open-meteo.com/en/docs", "credit": "Open-Meteo"}}
    request = AskRequest(question="Explain this forecast", item=item)
    assert request.item.weather.temperatureC == 24.5
    assert request.item.media.credit == "Open-Meteo"


def test_model_cannot_invent_media_and_expired_deadlines_are_not_displayed():
    item = parse_feed(sourced_response(), FeedModule(id="music", kind="music"), 1)[0]
    assert item.contentType == "video"
    assert item.media is None
    assert item.deadline == ""
    assert item.sourceName == "example.org"


@pytest.mark.parametrize("url", ["http://images.example.org/a.jpg", "https://localhost/a.jpg", "https://username:secret@images.example.org/a.jpg"])
def test_media_provenance_rejects_unsafe_urls(url):
    with pytest.raises(ValidationError):
        FeedItem(id="1", moduleId="news", title="A story", summary="A detail", sourceUrl="https://example.org/story", media={"url": url, "sourceUrl": "https://example.org/story"})


def test_forecast_data_cannot_be_disguised_as_a_news_item():
    with pytest.raises(ValidationError):
        FeedItem(id="1", moduleId="news", title="A story", summary="A detail", sourceUrl="https://example.org/story",
                 weather={"temperatureC": 24.5, "feelsLikeC": 25.1, "precipitationChance": 20, "weatherCode": 3, "location": "Example City"})
