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


class Store:
    def __init__(self, path: str):
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS usage(day TEXT PRIMARY KEY, count INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS items(id TEXT PRIMARY KEY, payload TEXT NOT NULL, fetched INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS cache(key TEXT PRIMARY KEY, payload TEXT NOT NULL, created REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS schedules(
                    id TEXT PRIMARY KEY, payload TEXT NOT NULL, enabled INTEGER NOT NULL,
                    next_run REAL NOT NULL, lease_until REAL NOT NULL DEFAULT 0,
                    last_run REAL, last_status TEXT, revision INTEGER NOT NULL DEFAULT 1
                );
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

    def usage(self) -> int:
        with self.connect() as db:
            row = db.execute("SELECT count FROM usage WHERE day=?", (datetime.now(timezone.utc).date().isoformat(),)).fetchone()
            return int(row["count"]) if row else 0

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
