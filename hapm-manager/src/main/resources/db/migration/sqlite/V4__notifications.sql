CREATE TABLE notifications (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts TEXT NOT NULL,
  level TEXT NOT NULL,
  kind TEXT NOT NULL,
  cluster_id TEXT REFERENCES clusters(id) ON DELETE CASCADE,
  title TEXT NOT NULL,
  message TEXT NOT NULL,
  data TEXT,
  dedupe_key TEXT UNIQUE,
  deliveries TEXT,
  acknowledged_at TEXT,
  acknowledged_by TEXT
);
CREATE INDEX notifications_ack_idx ON notifications (acknowledged_at, id);
