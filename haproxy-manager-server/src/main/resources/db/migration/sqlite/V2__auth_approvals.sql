-- SQLite não altera NOT NULL de coluna existente: a tabela users é recriada (ainda só tem o admin inicial)
CREATE TABLE users_new (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT,
  created_at TEXT NOT NULL,
  source TEXT NOT NULL DEFAULT 'local',
  display_name TEXT,
  roles TEXT NOT NULL DEFAULT 'ADMIN',
  last_login TEXT
);
INSERT INTO users_new (id, username, password_hash, created_at) SELECT id, username, password_hash, created_at FROM users;
DROP TABLE users;
ALTER TABLE users_new RENAME TO users;

CREATE TABLE publish_requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  cluster_id TEXT NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
  base_version INTEGER NOT NULL,
  status TEXT NOT NULL,
  note TEXT,
  requested_by TEXT NOT NULL,
  requested_at TEXT NOT NULL,
  decided_by TEXT,
  decided_at TEXT,
  decision_note TEXT,
  deployment_id INTEGER,
  snapshot TEXT NOT NULL,
  haproxy_cfg TEXT NOT NULL
);
CREATE INDEX publish_requests_cluster_idx ON publish_requests (cluster_id, status);

ALTER TABLE deployments ADD COLUMN approved_by TEXT;
ALTER TABLE deployments ADD COLUMN request_id INTEGER;
