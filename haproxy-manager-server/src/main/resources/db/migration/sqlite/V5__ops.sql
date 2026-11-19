CREATE TABLE node_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts TEXT NOT NULL,
  cluster_id TEXT NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
  node_name TEXT,
  subject TEXT,
  kind TEXT NOT NULL,
  detail TEXT NOT NULL,
  data TEXT
);
CREATE INDEX node_events_cluster_idx ON node_events (cluster_id, id);
CREATE INDEX node_events_subject_idx ON node_events (cluster_id, subject, id);

CREATE TABLE server_overrides (
  cluster_id TEXT NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
  backend TEXT NOT NULL,
  server TEXT NOT NULL,
  state TEXT,
  weight INTEGER,
  reason TEXT,
  updated_by TEXT,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (cluster_id, backend, server)
);
