-- SQLite (instalação local / um único manager). Mesmo modelo das migrações PostgreSQL.
CREATE TABLE users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE kv (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);

CREATE TABLE clusters (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  settings TEXT NOT NULL,
  join_secret TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE nodes (
  id TEXT PRIMARY KEY,
  cluster_id TEXT NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  address TEXT,
  settings TEXT NOT NULL,
  agent_version TEXT,
  haproxy_version TEXT,
  last_seen INTEGER,
  vrrp_state TEXT,
  applied_version INTEGER NOT NULL DEFAULT 0,
  last_apply TEXT,
  created_at TEXT NOT NULL,
  UNIQUE (cluster_id, name)
);

CREATE TABLE objects (
  id TEXT PRIMARY KEY,
  cluster_id TEXT NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  data TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE (cluster_id, kind, name)
);

CREATE TABLE deployments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  cluster_id TEXT NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
  version INTEGER NOT NULL,
  status TEXT NOT NULL,
  error TEXT,
  note TEXT,
  created_by TEXT,
  created_at TEXT NOT NULL,
  finished_at TEXT,
  snapshot TEXT NOT NULL,
  haproxy_cfg TEXT NOT NULL,
  UNIQUE (cluster_id, version)
);

CREATE TABLE acme_challenges (
  token TEXT PRIMARY KEY,
  key_authorization TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts TEXT NOT NULL,
  username TEXT,
  cluster_id TEXT,
  action TEXT NOT NULL,
  target TEXT,
  detail TEXT
);
CREATE INDEX audit_cluster_idx ON audit (cluster_id, id);
