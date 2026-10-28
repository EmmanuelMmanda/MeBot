from __future__ import annotations

import json
import os
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterator

from .models import FeedItem


class BudgetExceeded(Exception):
    pass


class RealtimeCallConflict(Exception):
    """A call request id was already used or this principal has an active call."""

    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


MAX_REALTIME_END_TOMBSTONES_PER_PRINCIPAL = 256


class Store:
    def __init__(self, path: str):
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS usage(day TEXT PRIMARY KEY, count INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS feature_usage(
                    day TEXT NOT NULL, feature TEXT NOT NULL, count INTEGER NOT NULL,
                    PRIMARY KEY(day, feature)
                );
                CREATE TABLE IF NOT EXISTS items(id TEXT PRIMARY KEY, payload TEXT NOT NULL, fetched INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS cache(key TEXT PRIMARY KEY, payload TEXT NOT NULL, created REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS schedules(
                    id TEXT PRIMARY KEY, payload TEXT NOT NULL, enabled INTEGER NOT NULL,
                    next_run REAL NOT NULL, lease_until REAL NOT NULL DEFAULT 0,
                    last_run REAL, last_status TEXT, revision INTEGER NOT NULL DEFAULT 1
                );
                CREATE TABLE IF NOT EXISTS realtime_calls(
                    owner_hash TEXT NOT NULL,
                    request_id TEXT NOT NULL,
                    request_hash TEXT NOT NULL,
                    state TEXT NOT NULL,
                    upstream_call_id TEXT,
                    created_at REAL NOT NULL,
                    create_deadline REAL NOT NULL,
                    expires_at REAL,
                    next_attempt_at REAL NOT NULL DEFAULT 0,
                    lease_until REAL NOT NULL DEFAULT 0,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(owner_hash, request_id),
                    UNIQUE(upstream_call_id)
                );
                CREATE INDEX IF NOT EXISTS realtime_calls_due
                    ON realtime_calls(state, next_attempt_at, lease_until);
            """)
        os.chmod(path, 0o600)

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        db = sqlite3.connect(self.path, timeout=20)
        db.row_factory = sqlite3.Row
        try:
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def reserve_call(self, limit: int, day: str | None = None) -> int:
        day = day or datetime.now(timezone.utc).date().isoformat()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT count FROM usage WHERE day=?", (day,)).fetchone()
            count = int(row["count"]) if row else 0
            if count >= limit:
                raise BudgetExceeded()
            db.execute("INSERT INTO usage(day,count) VALUES(?,?) ON CONFLICT(day) DO UPDATE SET count=excluded.count", (day, count + 1))
            return count + 1

    def reserve_limited_call(self, feature: str, feature_limit: int, total_limit: int,
                             day: str | None = None) -> int:
        """Atomically enforce both the shared provider budget and a feature-specific cap."""
        if feature not in {"realtime_session", "transcription"} or feature_limit < 1 or total_limit < 1:
            raise ValueError("Invalid feature budget")
        day = day or datetime.now(timezone.utc).date().isoformat()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            overall = db.execute("SELECT count FROM usage WHERE day=?", (day,)).fetchone()
            feature_row = db.execute("SELECT count FROM feature_usage WHERE day=? AND feature=?", (day, feature)).fetchone()
            total_count = int(overall["count"]) if overall else 0
            feature_count = int(feature_row["count"]) if feature_row else 0
            if total_count >= total_limit or feature_count >= feature_limit:
                raise BudgetExceeded()
            total_count += 1
            feature_count += 1
            db.execute("INSERT INTO usage(day,count) VALUES(?,?) ON CONFLICT(day) DO UPDATE SET count=excluded.count", (day, total_count))
            db.execute("INSERT INTO feature_usage(day,feature,count) VALUES(?,?,?) ON CONFLICT(day,feature) DO UPDATE SET count=excluded.count",
                       (day, feature, feature_count))
            return feature_count

    def usage(self) -> int:
        with self.connect() as db:
            row = db.execute("SELECT count FROM usage WHERE day=?", (datetime.now(timezone.utc).date().isoformat(),)).fetchone()
            return int(row["count"]) if row else 0

    def feature_usage(self, feature: str, day: str | None = None) -> int:
        if feature not in {"realtime_session", "transcription"}:
            raise ValueError("Unknown feature budget")
        day = day or datetime.now(timezone.utc).date().isoformat()
        with self.connect() as db:
            row = db.execute("SELECT count FROM feature_usage WHERE day=? AND feature=?", (day, feature)).fetchone()
            return int(row["count"]) if row else 0

    @staticmethod
    def _reserve_limited_call_in_db(db: sqlite3.Connection, feature: str,
                                    feature_limit: int, total_limit: int,
                                    day: str) -> int:
        overall = db.execute("SELECT count FROM usage WHERE day=?", (day,)).fetchone()
        feature_row = db.execute("SELECT count FROM feature_usage WHERE day=? AND feature=?", (day, feature)).fetchone()
        total_count = int(overall["count"]) if overall else 0
        feature_count = int(feature_row["count"]) if feature_row else 0
        if total_count >= total_limit or feature_count >= feature_limit:
            raise BudgetExceeded()
        total_count += 1
        feature_count += 1
        db.execute("INSERT INTO usage(day,count) VALUES(?,?) ON CONFLICT(day) DO UPDATE SET count=excluded.count", (day, total_count))
        db.execute("INSERT INTO feature_usage(day,feature,count) VALUES(?,?,?) ON CONFLICT(day,feature) DO UPDATE SET count=excluded.count",
                   (day, feature, feature_count))
        return feature_count

    def begin_realtime_call(self, owner_hash: str, request_id: str, request_hash: str,
                            *, feature_limit: int, total_limit: int, max_active: int,
                            now: float, create_deadline: float) -> None:
        """Atomically reserve budget/idempotency/active-call capacity before upstream create."""
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            existing = db.execute("SELECT request_hash,state FROM realtime_calls WHERE owner_hash=? AND request_id=?",
                                  (owner_hash, request_id)).fetchone()
            if existing:
                if existing["request_hash"] not in {"", request_hash}:
                    raise RealtimeCallConflict("call_request_id_conflict")
                raise RealtimeCallConflict("call_request_already_used")
            tombstones = db.execute(
                "SELECT COUNT(*) AS n FROM realtime_calls WHERE owner_hash=? AND state='ended' "
                "AND request_hash='' AND created_at>=?", (owner_hash, now - 86400),
            ).fetchone()["n"]
            if int(tombstones) >= MAX_REALTIME_END_TOMBSTONES_PER_PRINCIPAL:
                raise RealtimeCallConflict("call_end_limit_reached")
            active = db.execute(
                "SELECT COUNT(*) AS n FROM realtime_calls WHERE owner_hash=? AND state IN "
                "('starting','cancel_requested','active','termination_pending','creation_unknown')",
                (owner_hash,),
            ).fetchone()["n"]
            if int(active) >= max_active:
                raise RealtimeCallConflict("active_call_limit_reached")
            self._reserve_limited_call_in_db(
                db, "realtime_session", feature_limit, total_limit,
                datetime.fromtimestamp(now, timezone.utc).date().isoformat(),
            )
            db.execute(
                "INSERT INTO realtime_calls(owner_hash,request_id,request_hash,state,created_at,create_deadline) "
                "VALUES(?,?,?,'starting',?,?)",
                (owner_hash, request_id, request_hash, now, create_deadline),
            )

    def finish_realtime_call_create(self, owner_hash: str, request_id: str,
                                    upstream_call_id: str, *, now: float,
                                    expires_at: float) -> bool:
        """Attach trusted upstream call ID; return False if cancellation already won."""
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT state FROM realtime_calls WHERE owner_hash=? AND request_id=?",
                             (owner_hash, request_id)).fetchone()
            if not row:
                return False
            cancelled = row["state"] != "starting"
            state = "termination_pending" if cancelled else "active"
            db.execute(
                "UPDATE realtime_calls SET state=?,upstream_call_id=?,expires_at=?,next_attempt_at=?,lease_until=0 "
                "WHERE owner_hash=? AND request_id=?",
                (state, upstream_call_id, expires_at, now if cancelled else expires_at, owner_hash, request_id),
            )
            return not cancelled

    def fail_realtime_call_create(self, owner_hash: str, request_id: str) -> None:
        with self.connect() as db:
            db.execute(
                "UPDATE realtime_calls SET state='failed',upstream_call_id=NULL,lease_until=0 "
                "WHERE owner_hash=? AND request_id=? AND state IN ('starting','cancel_requested')",
                (owner_hash, request_id),
            )

    def mark_realtime_call_create_unknown(self, owner_hash: str, request_id: str, *, now: float,
                                          provider_max_duration_seconds: float) -> None:
        """Fail closed when an upstream create may have succeeded but its response was lost."""
        with self.connect() as db:
            db.execute(
                "UPDATE realtime_calls SET state='creation_unknown',expires_at=created_at+?,"
                "next_attempt_at=created_at+?,lease_until=0 WHERE owner_hash=? AND request_id=? "
                "AND state IN ('starting','cancel_requested') AND upstream_call_id IS NULL",
                (provider_max_duration_seconds, provider_max_duration_seconds, owner_hash, request_id),
            )

    def request_realtime_call_end(self, owner_hash: str, request_id: str, *, now: float) -> str:
        """Idempotently request termination, creating an owner-scoped pre-cancel tombstone if needed."""
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT state,upstream_call_id FROM realtime_calls WHERE owner_hash=? AND request_id=?",
                             (owner_hash, request_id)).fetchone()
            if not row:
                db.execute(
                    "DELETE FROM realtime_calls WHERE owner_hash=? AND state='ended' AND request_hash='' AND created_at<?",
                    (owner_hash, now - 86400),
                )
                tombstones = db.execute(
                    "SELECT COUNT(*) AS n FROM realtime_calls WHERE owner_hash=? AND state='ended' "
                    "AND request_hash='' AND created_at>=?", (owner_hash, now - 86400),
                ).fetchone()["n"]
                if int(tombstones) >= MAX_REALTIME_END_TOMBSTONES_PER_PRINCIPAL:
                    raise RealtimeCallConflict("call_end_limit_reached")
                db.execute(
                    "INSERT INTO realtime_calls(owner_hash,request_id,request_hash,state,created_at,create_deadline) "
                    "VALUES(?,?,'','ended',?,?)",
                    (owner_hash, request_id, now, now),
                )
                return "already_ended"
            state = row["state"]
            if state in {"ended", "failed", "cancel_requested"}:
                return "already_ended" if state in {"ended", "failed"} else "scheduled"
            if state == "starting":
                db.execute("UPDATE realtime_calls SET state='cancel_requested' WHERE owner_hash=? AND request_id=?",
                           (owner_hash, request_id))
                return "scheduled"
            if state == "active":
                db.execute("UPDATE realtime_calls SET state='termination_pending',next_attempt_at=?,lease_until=0 WHERE owner_hash=? AND request_id=?",
                           (now, owner_hash, request_id))
            return "scheduled"

    def claim_due_realtime_call(self, now: float, *, lease_seconds: float = 30.0,
                                create_uncertainty_seconds: float = 210.0,
                                provider_max_duration_seconds: float = 3600.0) -> dict | None:
        """Durably claim one expiry/termination; unknown creates fail closed until provider max TTL."""
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute(
                "UPDATE realtime_calls SET state='termination_pending',next_attempt_at=MIN(next_attempt_at,?) "
                "WHERE state='active' AND expires_at<=?", (now, now),
            )
            db.execute(
                "UPDATE realtime_calls SET state='creation_unknown',expires_at=created_at+?,next_attempt_at=created_at+? "
                "WHERE state IN ('starting','cancel_requested') AND request_hash<>'' AND create_deadline<=? AND upstream_call_id IS NULL",
                (provider_max_duration_seconds, provider_max_duration_seconds, now),
            )
            db.execute(
                "UPDATE realtime_calls SET state='failed' WHERE state='creation_unknown' AND expires_at<=?",
                (now,),
            )
            row = db.execute(
                "SELECT owner_hash,request_id,upstream_call_id,attempts FROM realtime_calls "
                "WHERE state='termination_pending' AND upstream_call_id IS NOT NULL "
                "AND next_attempt_at<=? AND lease_until<=? ORDER BY next_attempt_at,created_at LIMIT 1",
                (now, now),
            ).fetchone()
            if not row:
                # Retain a bounded idempotency window; don't prune live/unknown calls.
                db.execute("DELETE FROM realtime_calls WHERE state IN ('ended','failed') AND created_at<?", (now - 86400,))
                db.execute("DELETE FROM realtime_calls WHERE state='cancel_requested' AND upstream_call_id IS NULL AND created_at<?", (now - 3600,))
                return None
            db.execute(
                "UPDATE realtime_calls SET lease_until=?,attempts=attempts+1 WHERE owner_hash=? AND request_id=? AND state='termination_pending'",
                (now + lease_seconds, row["owner_hash"], row["request_id"]),
            )
            return dict(row)

    def finish_realtime_call_end(self, owner_hash: str, request_id: str, *, now: float,
                                 success: bool, retry_after: float = 0.0) -> None:
        with self.connect() as db:
            if success:
                db.execute(
                    "UPDATE realtime_calls SET state='ended',upstream_call_id=NULL,expires_at=NULL,lease_until=0 "
                    "WHERE owner_hash=? AND request_id=? AND state='termination_pending'",
                    (owner_hash, request_id),
                )
            else:
                db.execute(
                    "UPDATE realtime_calls SET state='termination_pending',next_attempt_at=?,lease_until=0 "
                    "WHERE owner_hash=? AND request_id=? AND state='termination_pending'",
                    (now + max(1.0, retry_after), owner_hash, request_id),
                )

    def active_realtime_call_count(self, owner_hash: str | None = None) -> int:
        query = "SELECT COUNT(*) AS n FROM realtime_calls WHERE state IN ('starting','cancel_requested','active','termination_pending','creation_unknown')"
        params: tuple[object, ...] = ()
        if owner_hash is not None:
            query += " AND owner_hash=?"
            params = (owner_hash,)
        with self.connect() as db:
            row = db.execute(query, params).fetchone()
            return int(row["n"])

    def cached(self, key: str, after: float) -> list[FeedItem] | None:
        with self.connect() as db:
            row = db.execute("SELECT payload FROM cache WHERE key=? AND created>=?", (key, after)).fetchone()
            return [FeedItem.model_validate(x) for x in json.loads(row["payload"])] if row else None

    def save_feed(self, key: str, items: list[FeedItem], now: float) -> int:
        fresh = 0
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            for item in items:
                if not db.execute("SELECT 1 FROM items WHERE id=?", (item.id,)).fetchone():
                    fresh += 1
                db.execute("INSERT INTO items(id,payload,fetched) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload, fetched=excluded.fetched", (item.id, item.model_dump_json(), item.fetchedAtMillis))
            db.execute("INSERT INTO cache(key,payload,created) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET payload=excluded.payload,created=excluded.created", (key, json.dumps([i.model_dump() for i in items]), now))
            db.execute("DELETE FROM cache WHERE created < ?", (now - 86400,))
            db.execute("DELETE FROM items WHERE id NOT IN (SELECT id FROM items ORDER BY fetched DESC LIMIT 500)")
        return fresh

    def latest(self, limit: int = 50) -> list[FeedItem]:
        with self.connect() as db:
            return [FeedItem.model_validate_json(row["payload"]) for row in db.execute("SELECT payload FROM items ORDER BY fetched DESC LIMIT ?", (limit,))]

    def put_schedule(self, schedule_id: str, payload: str, enabled: bool, next_run: float) -> None:
        with self.connect() as db:
            db.execute("INSERT INTO schedules(id,payload,enabled,next_run) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload, enabled=excluded.enabled, next_run=excluded.next_run, lease_until=0, revision=schedules.revision+1", (schedule_id, payload, int(enabled), next_run))

    def schedules(self) -> list[dict]:
        with self.connect() as db:
            return [dict(r) for r in db.execute("SELECT id,enabled,next_run,last_run,last_status FROM schedules ORDER BY id")]

    def delete_schedule(self, schedule_id: str) -> None:
        with self.connect() as db:
            db.execute("DELETE FROM schedules WHERE id=?", (schedule_id,))

    def claim_due(self, now: float) -> dict | None:
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM schedules WHERE enabled=1 AND next_run<=? AND lease_until<=? ORDER BY next_run LIMIT 1", (now, now)).fetchone()
            if not row:
                return None
            db.execute("UPDATE schedules SET lease_until=? WHERE id=?", (now + 1800, row["id"]))
            return dict(row)

    def finish_schedule(self, schedule_id: str, revision: int, next_run: float, now: float, status: str) -> None:
        with self.connect() as db:
            # An edited/deleted job is never resurrected by an old in-flight callback.
            db.execute("UPDATE schedules SET next_run=?,last_run=?,last_status=?,lease_until=0 WHERE id=? AND revision=?", (next_run, now, status, schedule_id, revision))

    def schedule_current(self, schedule_id: str, revision: int) -> bool:
        with self.connect() as db:
            return db.execute("SELECT 1 FROM schedules WHERE id=? AND revision=? AND enabled=1", (schedule_id, revision)).fetchone() is not None
