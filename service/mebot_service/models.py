from __future__ import annotations

import ipaddress
import re
from datetime import datetime, timezone
from typing import Annotated, Literal
from urllib.parse import urlsplit, urlunsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


ModuleKind = Literal["news", "opportunities", "watch", "horoscope", "technology", "security", "scholarships", "business", "science", "learning", "entertainment", "music", "events", "travel", "weather", "github", "media", "food", "farming", "family", "sport", "home", "wellbeing"]
ContentType = Literal["article", "video", "podcast", "repository", "course", "event", "opportunity", "weather", "entertainment", "original_read"]
Identifier = Annotated[str, Field(min_length=1, max_length=100)]

# Mirrors the Android ModulePreferences registry. Values select content; they
# never grant access to another module's context or an external account.
MODULE_PREFERENCES: dict[str, dict[str, tuple[str, ...] | int]] = {
    "news": {"coverage": ("balanced", "local", "world"), "location": 160},
    "technology": {"focus": ("all", "ai", "software", "infrastructure", "hardware")},
    "security": {"focus": ("all", "software", "cloud", "networks"), "urgency": ("all", "critical")},
    "business": {"focus": ("all", "regional", "operations", "markets"), "location": 160},
    "science": {"focus": ("all", "ai", "health", "climate", "space")},
    "opportunities": {"type": ("all", "jobs", "contracts", "grants"), "workplace": ("any", "local", "remote"), "location": 160},
    "scholarships": {"level": ("any", "masters", "phd"), "funding": ("any", "full")},
    "learning": {"level": ("any", "beginner", "intermediate", "advanced"), "format": ("any", "guides", "courses", "videos"), "cost": ("any", "free")},
    "github": {"focus": ("all", "tools", "libraries", "self_hosted"), "code_language": ("any", "python", "javascript", "php", "go", "rust")},
    "weather": {"location": 160},
    "entertainment": {"type": ("both", "films", "series"), "mood": ("any", "tense", "thoughtful", "light")},
    "music": {"focus": ("mixed", "listening", "practice"), "format": ("any", "performances", "lessons")},
    "events": {"attendance": ("any", "nearby", "online"), "window": ("month", "week"), "location": 160},
    "travel": {"focus": ("all", "weekend", "outdoors", "culture"), "cost": ("any", "low_cost"), "location": 160},
    "horoscope": {"focus": ("general", "work", "relationships", "reflection"), "tone": ("calm", "playful")},
    "media": {"focus": ("all", "explainers", "demonstrations", "interviews", "news"), "duration": ("short", "quick", "any")},
    "watch": {"format": ("any", "articles", "videos")},
}
for _kind in ("food", "farming", "family", "sport", "home", "wellbeing"):
    MODULE_PREFERENCES[_kind] = {"format": ("any", "articles", "videos"), "location": 160}
for _kind, _preferences in MODULE_PREFERENCES.items():
    if _kind != "weather":
        _preferences["language"] = ("auto", "en", "sw")


def has_control_characters(value: str) -> bool:
    return any(ord(character) < 32 or 127 <= ord(character) <= 159 for character in value)


def now_millis() -> int:
    return int(datetime.now(timezone.utc).timestamp() * 1000)


def public_url(value: str) -> str:
    """Validate links without fetching or resolving arbitrary user destinations."""
    if len(value) > 2048 or any(ord(c) < 33 for c in value) or "\\" in value:
        raise ValueError("Invalid source URL")
    parsed = urlsplit(value)
    host = (parsed.hostname or "").lower().rstrip(".")
    if parsed.scheme not in {"https", "http"} or not host or parsed.username or parsed.password:
        raise ValueError("Source URL must be public HTTP(S)")
    if parsed.port not in {None, 80, 443}:
        raise ValueError("Unsupported source URL port")
    if host == "localhost" or host.endswith((".localhost", ".local", ".internal")) or "." not in host:
        raise ValueError("Local source URLs are not accepted")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        address = None
    if address and not address.is_global:
        raise ValueError("Private source URLs are not accepted")
    return value


def canonical_url(value: str) -> str:
    public_url(value)
    parsed = urlsplit(value)
    # Match Android's canonicalUrl exactly: preserve existing percent escapes and
    # non-tracking query bytes. Decoding/re-encoding can change signed URL identity.
    query = "&".join(part for part in parsed.query.split("&")
                     if not part.partition("=")[0].lower().startswith("utm_")
                     and part.partition("=")[0].lower() not in {"fbclid", "gclid"})
    netloc = (parsed.hostname or "").lower()
    if parsed.port is not None:
        netloc += f":{parsed.port}"
    return urlunsplit((parsed.scheme.lower(), netloc, parsed.path.rstrip("/") or "/", query, ""))


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class FeedSource(StrictModel):
    name: str = Field(min_length=1, max_length=100)
    url: str = Field(max_length=2048)
    _valid_url = field_validator("url")(public_url)


class FeedModule(StrictModel):
    id: Identifier
    kind: ModuleKind = "watch"
    title: str = Field(default="", max_length=100)
    query: str = Field(default="", max_length=1000)
    enabled: bool = True
    sources: list[FeedSource] = Field(default_factory=list, max_length=12)
    alerts: bool = False
    zodiacSign: str = Field(default="", max_length=40)
    preferences: dict[Annotated[str, Field(min_length=1, max_length=40)], Annotated[str, Field(max_length=160)]] = Field(default_factory=dict, max_length=4)

    @field_validator("preferences", mode="before")
    @classmethod
    def plain_preferences(cls, value):
        if isinstance(value, dict) and any(isinstance(entry, str) and has_control_characters(entry) for entry in value.values()):
            raise ValueError("Invalid module preference")
        return value

    @model_validator(mode="after")
    def configured_preferences(self) -> "FeedModule":
        schema = MODULE_PREFERENCES[self.kind]
        for key, value in self.preferences.items():
            definition = schema.get(key)
            if definition is None or has_control_characters(value):
                raise ValueError("Unknown or invalid module preference")
            if isinstance(definition, int):
                if len(value) > definition:
                    raise ValueError("Module preference is too long")
            elif value not in definition:
                raise ValueError("Unsupported module preference")
        return self


class ContextEntry(StrictModel):
    # Alias matches Android JSON; field-name population preserves stored schedule round trips.
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True, populate_by_name=True)
    global_: bool = Field(default=False, alias="global")
    id: Identifier
    text: str = Field(min_length=1, max_length=1500)
    category: str = Field(default="interest", max_length=50)
    scopes: set[Identifier] = Field(default_factory=lambda: {"news", "opportunities", "watch"}, max_length=32)
    confirmed: bool = True
    origin: str = Field(default="manual", max_length=100)
    updatedAtMillis: int = Field(default_factory=now_millis, ge=0, le=32503680000000)

    @model_validator(mode="after")
    def dedicated_global_profile(self) -> "ContextEntry":
        if self.global_ and (self.id != "about-you-global" or self.category != "profile" or self.scopes):
            raise ValueError("Only the dedicated About you profile can have global scope")
        return self

    @field_validator("text")
    @classmethod
    def no_obvious_credentials(cls, value: str) -> str:
        if re.search(r"sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{20,}|-----BEGIN [A-Z ]*PRIVATE KEY-----", value):
            raise ValueError("Credentials do not belong in personal context")
        return value


class FeedCitation(StrictModel):
    title: str = Field(default="", max_length=300)
    url: str = Field(max_length=2048)
    _valid_url = field_validator("url")(public_url)

    @field_validator("title")
    @classmethod
    def plain_title(cls, value: str) -> str:
        if has_control_characters(value):
            raise ValueError("Invalid citation title")
        return value


class FeedContentSection(StrictModel):
    heading: str = Field(min_length=1, max_length=120)
    body: str = Field(min_length=1, max_length=6000)

    @field_validator("heading")
    @classmethod
    def plain_heading(cls, value: str) -> str:
        if has_control_characters(value):
            raise ValueError("Invalid section heading")
        return value

    @field_validator("body")
    @classmethod
    def plain_body(cls, value: str) -> str:
        if any((ord(char) < 32 and char not in "\n\r\t") or 127 <= ord(char) <= 159 for char in value):
            raise ValueError("Invalid section body")
        return value


class FeedMedia(StrictModel):
    url: str = Field(max_length=2048)
    sourceUrl: str = Field(max_length=2048)
    credit: str = Field(default="", max_length=200)

    @field_validator("url", "sourceUrl")
    @classmethod
    def public_https(cls, value: str) -> str:
        public_url(value)
        if urlsplit(value).scheme != "https":
            raise ValueError("Media must use HTTPS")
        return value


class FeedWeather(StrictModel):
    temperatureC: float = Field(ge=-100, le=70, allow_inf_nan=False)
    feelsLikeC: float = Field(ge=-120, le=85, allow_inf_nan=False)
    precipitationChance: int = Field(ge=0, le=100)
    weatherCode: int = Field(ge=0, le=99)
    location: str = Field(min_length=1, max_length=160)


class FeedItem(StrictModel):
    id: Identifier
    moduleId: Identifier
    title: str = Field(min_length=1, max_length=300)
    summary: str = Field(max_length=5000)
    why: str = Field(default="", max_length=1200)
    sourceName: str = Field(default="", max_length=200)
    sourceUrl: str = Field(default="", max_length=2048)
    publishedAtMillis: int = Field(default=0, ge=0, le=32503680000000)
    fetchedAtMillis: int = Field(default_factory=now_millis, ge=0, le=32503680000000)
    kind: ModuleKind = "news"
    aiGenerated: bool = True
    citations: list[FeedCitation] = Field(default_factory=list, max_length=10)
    deadline: str = Field(default="", max_length=100)
    relevance: int = Field(default=0, ge=0, le=100)
    media: FeedMedia | None = None
    contentType: ContentType = "article"
    weather: FeedWeather | None = None
    keyPoints: list[Annotated[str, Field(min_length=1, max_length=400)]] = Field(default_factory=list, max_length=4)
    nextStep: str = Field(default="", max_length=800)
    caveat: str = Field(default="", max_length=600)
    # New provenance fields are migration-safe: old feed data remains legacy/unknown.
    # They are not evidence by themselves; app acquisition parsers establish provenance.
    acquisition: str = Field(default="legacy_unknown", min_length=1, max_length=40)
    authorship: str = Field(default="legacy_unknown", min_length=1, max_length=40)
    sections: list[FeedContentSection] = Field(default_factory=list, max_length=8)
    furtherReading: list[FeedCitation] = Field(default_factory=list, max_length=5)

    @field_validator("sourceUrl")
    @classmethod
    def source_may_be_empty(cls, value: str) -> str:
        return public_url(value) if value else value

    @field_validator("acquisition", "authorship")
    @classmethod
    def plain_provenance_code(cls, value: str) -> str:
        if has_control_characters(value):
            raise ValueError("Invalid provenance code")
        return value

    @model_validator(mode="after")
    def typed_content_contract(self) -> "FeedItem":
        if self.weather is not None and (self.kind != "weather" or self.contentType != "weather"):
            raise ValueError("Weather data belongs to weather items")
        if self.contentType == "original_read":
            if (
                self.acquisition != "original_read" or self.authorship != "ai" or not self.aiGenerated or
                self.kind in {"weather", "horoscope"} or self.deadline or self.sourceUrl or self.sourceName or
                not self.summary or self.publishedAtMillis != 0 or self.fetchedAtMillis < 1 or self.media is not None or self.weather is not None or
                len(self.sections) not in {2, 3, 4}
            ):
                raise ValueError("Invalid Original Read provenance or metadata")
            section_body_lengths = [len(section.body.strip()) for section in self.sections]
            if any(length < 180 for length in section_body_lengths) or not 1000 <= sum(section_body_lengths) <= 12000:
                raise ValueError("Original Read must contain a substantial bounded body")
        else:
            if self.acquisition == "original_read":
                raise ValueError("Original Read provenance requires original_read content")
            # Preserve construction of pre-v0.9.8 model instances that omitted both
            # optional fields, but do not let an explicitly unknown acquisition or
            # blank source field become a source-less factual item in a request.
            if self.kind != "horoscope" and not self.sourceUrl and (
                "sourceUrl" in self.model_fields_set or "acquisition" in self.model_fields_set
            ):
                raise ValueError("Sourced feed item requires a public source URL")
            if self.acquisition in {"publisher_rss", "public_api"} and not self.sourceName:
                raise ValueError("Public acquisition requires a source name")
            if self.acquisition in {"ai_web_grounded", "ai_source_grounded"} and (
                not self.aiGenerated or not self.sourceName or not self.sourceUrl or not self.citations
            ):
                # Further-reading links do not establish evidence for either AI acquisition path.
                raise ValueError("AI-grounded items require direct evidence citations")
        return self


class GeneratedFeedItem(FeedItem):
    """Generation stays concise; imported Android articles may be longer or empty."""
    title: str = Field(min_length=1, max_length=200)
    summary: str = Field(min_length=1, max_length=1800)
    why: str = Field(default="", max_length=500)


class RecentItem(StrictModel):
    moduleId: Identifier
    title: str = Field(max_length=300)
    sourceUrl: str = Field(default="", max_length=2048)

    @field_validator("sourceUrl")
    @classmethod
    def source_may_be_empty(cls, value: str) -> str:
        return public_url(value) if value else value


class FeedRequest(StrictModel):
    model: str = Field(default="gpt-6-luna", max_length=100)
    name: str = Field(default="", max_length=80)
    region: str = Field(default="", max_length=120)
    timezone: str = Field(default="", max_length=100)
    modules: list[FeedModule] = Field(min_length=1, max_length=32)
    context: list[ContextEntry] = Field(default_factory=list, max_length=64)
    language: Literal["en", "sw", "system"] = "en"
    limit: int = Field(default=8, ge=1, le=12)
    recentlyShown: list[RecentItem] = Field(default_factory=list, max_length=64)

    @field_validator("timezone")
    @classmethod
    def valid_zone(cls, value: str) -> str:
        if value:
            try:
                ZoneInfo(value)
            except ZoneInfoNotFoundError as exc:
                raise ValueError("Unknown time zone") from exc
        return value

    @model_validator(mode="after")
    def unique_modules(self) -> "FeedRequest":
        if len({m.id for m in self.modules}) != len(self.modules):
            raise ValueError("Module IDs must be unique")
        return self


class FeedResponse(StrictModel):
    items: list[FeedItem]


class SearchRequest(StrictModel):
    model: str = Field(default="gpt-6-luna", max_length=100)
    query: str = Field(min_length=1, max_length=2000)
    region: str = Field(default="", max_length=120)
    timezone: str = Field(default="", max_length=100)
    modules: list[FeedModule] = Field(min_length=1, max_length=32)
    context: list[ContextEntry] = Field(default_factory=list, max_length=64)
    language: Literal["en", "sw", "system"] = "en"
    limit: int = Field(default=8, ge=1, le=8)

    @field_validator("timezone")
    @classmethod
    def valid_zone(cls, value: str) -> str:
        return FeedRequest.valid_zone(value)

    @model_validator(mode="after")
    def searchable_modules(self) -> "SearchRequest":
        if len({module.id for module in self.modules}) != len(self.modules):
            raise ValueError("Module IDs must be unique")
        if any(not module.enabled or module.kind in {"horoscope", "weather"} for module in self.modules):
            raise ValueError("Search requires enabled factual modules")
        return self


class AskTurn(StrictModel):
    role: Literal["user", "assistant"]
    text: str = Field(min_length=1, max_length=24000)


class AskAttachment(StrictModel):
    name: str = Field(min_length=1, max_length=160)
    text: str = Field(min_length=1, max_length=24000)

    @field_validator("name", mode="before")
    @classmethod
    def plain_name(cls, value):
        if isinstance(value, str) and has_control_characters(value):
            raise ValueError("Invalid attachment name")
        return value

    @field_validator("text")
    @classmethod
    def plain_text(cls, value: str) -> str:
        if "\x00" in value:
            raise ValueError("Attachment must be text")
        return value


class AskRequest(StrictModel):
    model: str = Field(default="gpt-6-luna", max_length=100)
    question: str = Field(min_length=1, max_length=2000)
    item: FeedItem | None = None
    context: list[ContextEntry] = Field(default_factory=list, max_length=64)
    language: Literal["en", "sw", "system"] = "en"
    history: list[AskTurn] = Field(default_factory=list, max_length=16)
    attachments: list[AskAttachment] = Field(default_factory=list, max_length=3)
    # Missing retains the legacy news scope. An explicit empty value is the
    # general workspace: global profile plus only explicitly routed known kinds.
    moduleKind: ModuleKind | Literal[""] | None = None
    contextKinds: set[ModuleKind] = Field(default_factory=set, max_length=24)
    # Optional v0.6.1 selection data. Editorial instructions remain server-owned.
    module: FeedModule | None = None

    @model_validator(mode="after")
    def bounded_conversation(self) -> "AskRequest":
        if sum(len(turn.text) for turn in self.history) > 36000:
            raise ValueError("Conversation history is too long")
        if sum(len(attachment.text) for attachment in self.attachments) > 24000:
            raise ValueError("Attachments are too long")
        if self.item is not None and self.moduleKind not in {None, "", self.item.kind}:
            raise ValueError("Conversation scope does not match its item")
        if self.item is not None and self.item.contentType != "original_read" and self.item.kind != "horoscope" and not self.item.sourceUrl:
            # A source-less legacy item may still be decoded from old local data,
            # but it cannot bypass public-source requirements at the ask boundary.
            raise ValueError("Factual item follow-up requires a public source URL")
        if self.module is not None:
            if self.moduleKind not in {None, self.module.kind}:
                raise ValueError("Conversation scope does not match its module")
            if self.item is not None and (self.item.kind != self.module.kind or self.item.moduleId != self.module.id):
                raise ValueError("Conversation module does not match its item")
        return self


class AskResponse(StrictModel):
    answer: str = Field(min_length=1, max_length=10000)
    citations: list[FeedCitation] = Field(default_factory=list, max_length=12)


class ScheduleConfig(StrictModel):
    enabled: bool = True
    intervalMinutes: int = Field(default=360, ge=60, le=10080)
    timezone: str = Field(default="UTC", max_length=100)
    quietStartHour: int = Field(default=22, ge=0, le=23)
    quietEndHour: int = Field(default=7, ge=0, le=23)
    request: FeedRequest

    @field_validator("timezone")
    @classmethod
    def valid_zone(cls, value: str) -> str:
        return FeedRequest.valid_zone(value)
