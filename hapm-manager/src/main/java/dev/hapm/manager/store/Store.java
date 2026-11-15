package dev.hapm.manager.store;

import dev.hapm.core.model.AgentProtocol.Heartbeat;
import dev.hapm.core.model.AgentProtocol.LastApply;
import dev.hapm.core.model.ClusterSettings;
import dev.hapm.core.model.NodeSettings;
import dev.hapm.core.model.Snapshot;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Persistência (JDBC puro, SQL portável H2/PostgreSQL). Documentos de configuração ficam em JSON. */
@Repository
public class Store {
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public Store(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  static String now() {
    return Instant.now().toString();
  }

  public static String randomSecret(int bytes) {
    var b = new byte[bytes];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public String toJson(Object o) {
    return json.writeValueAsString(o);
  }

  public <T> T fromJson(String s, Class<T> type) {
    return s == null ? null : json.readValue(s, type);
  }

  // ------------------------------------------------------------------ records
  public record ClusterRow(String id, String name, ClusterSettings settings, String joinSecret, String createdAt, String updatedAt) {}

  public record NodeRow(
      String id, String clusterId, String name, String address, NodeSettings settings, String agentVersion, String haproxyVersion,
      Long lastSeen, String vrrpState, int appliedVersion, LastApply lastApply, String createdAt) {}

  public record ObjectRow(String id, String clusterId, String kind, String name, String data, String createdAt, String updatedAt) {}

  public record DeploymentRow(
      long id, String clusterId, int version, String status, String error, String note, String createdBy, String createdAt, String finishedAt,
      String snapshotJson, String haproxyCfg) {}

  public record UserRow(String username, String passwordHash) {}

  public record AuditRow(long id, String ts, String username, String clusterId, String action, String target, String detail) {}

  // ------------------------------------------------------------------ kv
  public Optional<String> getKv(String k) {
    return jdbc.sql("SELECT v FROM kv WHERE k = ?").param(k).query(String.class).optional();
  }

  public void setKv(String k, String v) {
    if (jdbc.sql("UPDATE kv SET v = ? WHERE k = ?").params(v, k).update() == 0) jdbc.sql("INSERT INTO kv(k, v) VALUES (?, ?)").params(k, v).update();
  }

  // ------------------------------------------------------------------ usuários
  public long countUsers() {
    return jdbc.sql("SELECT COUNT(*) FROM users").query(Long.class).single();
  }

  public Optional<UserRow> getUser(String username) {
    return jdbc.sql("SELECT username, password_hash FROM users WHERE username = ?").param(username)
        .query((rs, i) -> new UserRow(rs.getString(1), rs.getString(2))).optional();
  }

  public void createUser(String username, String hash) {
    jdbc.sql("INSERT INTO users(username, password_hash, created_at) VALUES (?, ?, ?)").params(username, hash, now()).update();
  }

  public void setPassword(String username, String hash) {
    jdbc.sql("UPDATE users SET password_hash = ? WHERE username = ?").params(hash, username).update();
  }

  // ------------------------------------------------------------------ clusters
  private ClusterRow cluster(ResultSet rs, int i) throws SQLException {
    return new ClusterRow(rs.getString("id"), rs.getString("name"), fromJson(rs.getString("settings"), ClusterSettings.class),
        rs.getString("join_secret"), rs.getString("created_at"), rs.getString("updated_at"));
  }

  public List<ClusterRow> listClusters() {
    return jdbc.sql("SELECT * FROM clusters ORDER BY name").query(this::cluster).list();
  }

  public Optional<ClusterRow> getCluster(String id) {
    return jdbc.sql("SELECT * FROM clusters WHERE id = ?").param(id).query(this::cluster).optional();
  }

  public ClusterRow createCluster(String name, ClusterSettings settings) {
    var id = UUID.randomUUID().toString();
    var t = now();
    jdbc.sql("INSERT INTO clusters(id, name, settings, join_secret, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")
        .params(id, name, toJson(settings), randomSecret(24), t, t).update();
    return getCluster(id).orElseThrow();
  }

  public ClusterRow updateCluster(String id, String name, ClusterSettings settings) {
    jdbc.sql("UPDATE clusters SET name = ?, settings = ?, updated_at = ? WHERE id = ?").params(name, toJson(settings), now(), id).update();
    return getCluster(id).orElseThrow();
  }

  public ClusterRow rotateJoinSecret(String id) {
    jdbc.sql("UPDATE clusters SET join_secret = ?, updated_at = ? WHERE id = ?").params(randomSecret(24), now(), id).update();
    return getCluster(id).orElseThrow();
  }

  public void deleteCluster(String id) {
    jdbc.sql("DELETE FROM clusters WHERE id = ?").param(id).update();
  }

  // ------------------------------------------------------------------ nós
  private NodeRow node(ResultSet rs, int i) throws SQLException {
    long ls = rs.getLong("last_seen");
    Long lastSeen = rs.wasNull() ? null : ls; // wasNull() precisa vir logo após o getLong
    return new NodeRow(rs.getString("id"), rs.getString("cluster_id"), rs.getString("name"), rs.getString("address"),
        fromJson(rs.getString("settings"), NodeSettings.class), rs.getString("agent_version"), rs.getString("haproxy_version"),
        lastSeen, rs.getString("vrrp_state"), rs.getInt("applied_version"), fromJson(rs.getString("last_apply"), LastApply.class),
        rs.getString("created_at"));
  }

  public List<NodeRow> listNodes(String clusterId) {
    return jdbc.sql("SELECT * FROM nodes WHERE cluster_id = ? ORDER BY name").param(clusterId).query(this::node).list();
  }

  public Optional<NodeRow> getNode(String id) {
    return jdbc.sql("SELECT * FROM nodes WHERE id = ?").param(id).query(this::node).optional();
  }

  public Optional<NodeRow> getNodeByName(String clusterId, String name) {
    return jdbc.sql("SELECT * FROM nodes WHERE cluster_id = ? AND name = ?").params(clusterId, name).query(this::node).optional();
  }

  public NodeRow createNode(String clusterId, String name, String address, NodeSettings settings) {
    var id = UUID.randomUUID().toString();
    jdbc.sql("INSERT INTO nodes(id, cluster_id, name, address, settings, applied_version, created_at) VALUES (?, ?, ?, ?, ?, 0, ?)")
        .params(id, clusterId, name, address, toJson(settings), now()).update();
    return getNode(id).orElseThrow();
  }

  @Transactional
  public NodeRow upsertHeartbeat(String clusterId, Heartbeat hb) {
    var existing = getNodeByName(clusterId, hb.name());
    var id = existing.map(NodeRow::id).orElseGet(() -> createNode(clusterId, hb.name(), hb.address(), NodeSettings.EMPTY).id());
    jdbc.sql("""
        UPDATE nodes SET address = COALESCE(?, address), agent_version = ?, haproxy_version = ?, last_seen = ?, vrrp_state = ?,
               applied_version = ?, last_apply = ? WHERE id = ?""")
        .params(hb.address(), hb.agentVersion(), hb.haproxyVersion(), System.currentTimeMillis(), hb.vrrpState(),
            hb.appliedVersion() == null ? 0 : hb.appliedVersion(), hb.lastApply() == null ? null : toJson(hb.lastApply()), id)
        .update();
    return getNode(id).orElseThrow();
  }

  public NodeRow updateNodeSettings(String id, NodeSettings settings) {
    jdbc.sql("UPDATE nodes SET settings = ? WHERE id = ?").params(toJson(settings), id).update();
    return getNode(id).orElseThrow();
  }

  public void deleteNode(String id) {
    jdbc.sql("DELETE FROM nodes WHERE id = ?").param(id).update();
  }

  // ------------------------------------------------------------------ objetos (frontend | backend | certificate)
  private ObjectRow object(ResultSet rs, int i) throws SQLException {
    return new ObjectRow(rs.getString("id"), rs.getString("cluster_id"), rs.getString("kind"), rs.getString("name"), rs.getString("data"),
        rs.getString("created_at"), rs.getString("updated_at"));
  }

  public List<ObjectRow> listObjects(String clusterId, String kind) {
    return jdbc.sql("SELECT * FROM objects WHERE cluster_id = ? AND kind = ? ORDER BY name").params(clusterId, kind).query(this::object).list();
  }

  public <T> List<T> listObjects(String clusterId, String kind, Class<T> type) {
    return listObjects(clusterId, kind).stream().map(o -> fromJson(o.data(), type)).toList();
  }

  public Optional<ObjectRow> getObject(String clusterId, String kind, String id) {
    return jdbc.sql("SELECT * FROM objects WHERE cluster_id = ? AND kind = ? AND id = ?").params(clusterId, kind, id).query(this::object).optional();
  }

  public Optional<ObjectRow> getObjectByName(String clusterId, String kind, String name) {
    return jdbc.sql("SELECT * FROM objects WHERE cluster_id = ? AND kind = ? AND name = ?").params(clusterId, kind, name).query(this::object).optional();
  }

  public ObjectRow createObject(String clusterId, String kind, String name, Object data) {
    var id = UUID.randomUUID().toString();
    var t = now();
    jdbc.sql("INSERT INTO objects(id, cluster_id, kind, name, data, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
        .params(id, clusterId, kind, name, toJson(data), t, t).update();
    return getObject(clusterId, kind, id).orElseThrow();
  }

  public ObjectRow updateObject(String clusterId, String kind, String id, String name, Object data) {
    jdbc.sql("UPDATE objects SET name = ?, data = ?, updated_at = ? WHERE id = ? AND cluster_id = ? AND kind = ?")
        .params(name, toJson(data), now(), id, clusterId, kind).update();
    return getObject(clusterId, kind, id).orElseThrow();
  }

  public void deleteObject(String clusterId, String kind, String id) {
    jdbc.sql("DELETE FROM objects WHERE id = ? AND cluster_id = ? AND kind = ?").params(id, clusterId, kind).update();
  }

  // ------------------------------------------------------------------ deployments
  private DeploymentRow deployment(ResultSet rs, int i) throws SQLException {
    return new DeploymentRow(rs.getLong("id"), rs.getString("cluster_id"), rs.getInt("version"), rs.getString("status"), rs.getString("error"),
        rs.getString("note"), rs.getString("created_by"), rs.getString("created_at"), rs.getString("finished_at"), rs.getString("snapshot"),
        rs.getString("haproxy_cfg"));
  }

  public List<DeploymentRow> listDeployments(String clusterId, int limit) {
    return jdbc.sql("SELECT * FROM deployments WHERE cluster_id = ? ORDER BY version DESC LIMIT ?").params(clusterId, limit).query(this::deployment).list();
  }

  public Optional<DeploymentRow> getDeployment(String clusterId, long id) {
    return jdbc.sql("SELECT * FROM deployments WHERE cluster_id = ? AND id = ?").params(clusterId, id).query(this::deployment).optional();
  }

  public Optional<DeploymentRow> getDeploymentByVersion(String clusterId, int version) {
    return jdbc.sql("SELECT * FROM deployments WHERE cluster_id = ? AND version = ?").params(clusterId, version).query(this::deployment).optional();
  }

  public Optional<DeploymentRow> latestDeployment(String clusterId) {
    return jdbc.sql("SELECT * FROM deployments WHERE cluster_id = ? ORDER BY version DESC LIMIT 1").param(clusterId).query(this::deployment).optional();
  }

  public Optional<DeploymentRow> latestSucceeded(String clusterId) {
    return jdbc.sql("SELECT * FROM deployments WHERE cluster_id = ? AND status = 'succeeded' ORDER BY version DESC LIMIT 1").param(clusterId)
        .query(this::deployment).optional();
  }

  public int nextVersion(String clusterId) {
    return jdbc.sql("SELECT COALESCE(MAX(version), 0) FROM deployments WHERE cluster_id = ?").param(clusterId).query(Integer.class).single() + 1;
  }

  /** Cria um deployment; o que estiver em andamento é marcado como substituído. */
  @Transactional
  public DeploymentRow createDeployment(String clusterId, int version, Snapshot snapshot, String haproxyCfg, String note, String createdBy) {
    jdbc.sql("UPDATE deployments SET status = 'superseded', finished_at = ? WHERE cluster_id = ? AND status = 'in_progress'").params(now(), clusterId).update();
    jdbc.sql("""
        INSERT INTO deployments(cluster_id, version, status, note, created_by, created_at, snapshot, haproxy_cfg)
        VALUES (?, ?, 'in_progress', ?, ?, ?, ?, ?)""")
        .params(clusterId, version, note, createdBy, now(), toJson(snapshot), haproxyCfg).update();
    return getDeploymentByVersion(clusterId, version).orElseThrow();
  }

  public void setDeploymentStatus(long id, String status, String error) {
    jdbc.sql("UPDATE deployments SET status = ?, error = ?, finished_at = ? WHERE id = ?")
        .params(status, error, "in_progress".equals(status) ? null : now(), id).update();
  }

  // ------------------------------------------------------------------ ACME
  public void putChallenge(String token, String keyAuth) {
    deleteChallenge(token);
    jdbc.sql("INSERT INTO acme_challenges(token, key_authorization, created_at) VALUES (?, ?, ?)").params(token, keyAuth, now()).update();
  }

  public Optional<String> getChallenge(String token) {
    return jdbc.sql("SELECT key_authorization FROM acme_challenges WHERE token = ?").param(token).query(String.class).optional();
  }

  public void deleteChallenge(String token) {
    jdbc.sql("DELETE FROM acme_challenges WHERE token = ?").param(token).update();
  }

  // ------------------------------------------------------------------ auditoria
  public void audit(String username, String clusterId, String action, String target, Map<String, ?> detail) {
    jdbc.sql("INSERT INTO audit(ts, username, cluster_id, action, target, detail) VALUES (?, ?, ?, ?, ?, ?)")
        .params(now(), username, clusterId, action, target, detail == null ? null : toJson(detail)).update();
  }

  public List<AuditRow> listAudit(int limit, String clusterId) {
    var q = clusterId == null
        ? jdbc.sql("SELECT * FROM audit ORDER BY id DESC LIMIT ?").param(limit)
        : jdbc.sql("SELECT * FROM audit WHERE cluster_id = ? ORDER BY id DESC LIMIT ?").params(clusterId, limit);
    return q.query((rs, i) -> new AuditRow(rs.getLong("id"), rs.getString("ts"), rs.getString("username"), rs.getString("cluster_id"),
        rs.getString("action"), rs.getString("target"), rs.getString("detail"))).list();
  }
}
