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

  /** Long anulável portável (o driver do SQLite não suporta getObject(col, Long.class) com NULL). */
  static Long nullableLong(ResultSet rs, String col) throws SQLException {
    long v = rs.getLong(col);
    return rs.wasNull() ? null : v;
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
      String snapshotJson, String haproxyCfg, String approvedBy, Long requestId) {}

  public record UserRow(String username, String passwordHash, String source, String displayName, java.util.Set<String> roles, String lastLogin, String createdAt) {}

  public record RequestRow(
      long id, String clusterId, int baseVersion, String status, String note, String requestedBy, String requestedAt, String decidedBy,
      String decidedAt, String decisionNote, Long deploymentId, String snapshotJson, String haproxyCfg) {}

  public record AuditRow(long id, String ts, String username, String clusterId, String action, String target, String detail) {}

  // ------------------------------------------------------------------ kv
  public Optional<String> getKv(String k) {
    return jdbc.sql("SELECT v FROM kv WHERE k = ?").param(k).query(String.class).optional();
  }

  public void setKv(String k, String v) {
    if (jdbc.sql("UPDATE kv SET v = ? WHERE k = ?").params(v, k).update() == 0) jdbc.sql("INSERT INTO kv(k, v) VALUES (?, ?)").params(k, v).update();
  }

  public void deleteKv(String k) {
    jdbc.sql("DELETE FROM kv WHERE k = ?").param(k).update();
  }

  // ------------------------------------------------------------------ usuários
  public long countUsers() {
    return jdbc.sql("SELECT COUNT(*) FROM users").query(Long.class).single();
  }

  private UserRow user(ResultSet rs, int i) throws SQLException {
    var roles = new java.util.TreeSet<String>();
    for (var r : String.valueOf(rs.getString("roles")).split(",")) if (!r.isBlank()) roles.add(r.strip());
    return new UserRow(rs.getString("username"), rs.getString("password_hash"), rs.getString("source"), rs.getString("display_name"), roles,
        rs.getString("last_login"), rs.getString("created_at"));
  }

  public Optional<UserRow> getUser(String username) {
    return jdbc.sql("SELECT * FROM users WHERE username = ?").param(username).query(this::user).optional();
  }

  public List<UserRow> listUsers() {
    return jdbc.sql("SELECT * FROM users ORDER BY source, username").query(this::user).list();
  }

  public void createUser(String username, String hash) {
    createLocalUser(username, hash, null, java.util.Set.of("ADMIN"));
  }

  public void createLocalUser(String username, String hash, String displayName, java.util.Set<String> roles) {
    jdbc.sql("INSERT INTO users(username, password_hash, source, display_name, roles, created_at) VALUES (?, ?, 'local', ?, ?, ?)")
        .params(username, hash, displayName, String.join(",", roles), now()).update();
  }

  public void updateLocalUser(String username, String displayName, java.util.Set<String> roles) {
    jdbc.sql("UPDATE users SET display_name = ?, roles = ? WHERE username = ? AND source = 'local'").params(displayName, String.join(",", roles), username).update();
  }

  /** Registra/atualiza um usuário externo (LDAP/OIDC) a cada login: nome e papéis vigentes, para auditoria e listagem. */
  public void upsertExternalUser(String username, String source, String displayName, java.util.Set<String> roles) {
    int n = jdbc.sql("UPDATE users SET source = ?, display_name = ?, roles = ?, last_login = ? WHERE username = ? AND source <> 'local'")
        .params(source, displayName, String.join(",", roles), now(), username).update();
    if (n == 0)
      jdbc.sql("INSERT INTO users(username, password_hash, source, display_name, roles, last_login, created_at) VALUES (?, NULL, ?, ?, ?, ?, ?)")
          .params(username, source, displayName, String.join(",", roles), now(), now()).update();
  }

  public Optional<String> getUserPrefs(String username) {
    return jdbc.sql("SELECT prefs FROM users WHERE username = ?").param(username).query((rs, i) -> rs.getString(1)).optional().filter(java.util.Objects::nonNull);
  }

  public void setUserPrefs(String username, String prefsJson) {
    jdbc.sql("UPDATE users SET prefs = ? WHERE username = ?").params(prefsJson, username).update();
  }

  public void touchLogin(String username) {
    jdbc.sql("UPDATE users SET last_login = ? WHERE username = ?").params(now(), username).update();
  }

  public void setPassword(String username, String hash) {
    jdbc.sql("UPDATE users SET password_hash = ? WHERE username = ?").params(hash, username).update();
  }

  public void deleteUser(String username) {
    jdbc.sql("DELETE FROM users WHERE username = ?").param(username).update();
  }

  public long countLocalAdmins() {
    return jdbc.sql("SELECT COUNT(*) FROM users WHERE source = 'local' AND roles LIKE '%ADMIN%'").query(Long.class).single();
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
        rs.getString("haproxy_cfg"), rs.getString("approved_by"), nullableLong(rs, "request_id"));
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
    return createDeployment(clusterId, version, snapshot, haproxyCfg, note, createdBy, null, null);
  }

  @Transactional
  public DeploymentRow createDeployment(String clusterId, int version, Snapshot snapshot, String haproxyCfg, String note, String createdBy,
                                        String approvedBy, Long requestId) {
    jdbc.sql("UPDATE deployments SET status = 'superseded', finished_at = ? WHERE cluster_id = ? AND status = 'in_progress'").params(now(), clusterId).update();
    jdbc.sql("""
        INSERT INTO deployments(cluster_id, version, status, note, created_by, created_at, snapshot, haproxy_cfg, approved_by, request_id)
        VALUES (?, ?, 'in_progress', ?, ?, ?, ?, ?, ?, ?)""")
        .params(clusterId, version, note, createdBy, now(), toJson(snapshot), haproxyCfg, approvedBy, requestId).update();
    return getDeploymentByVersion(clusterId, version).orElseThrow();
  }

  public void setDeploymentStatus(long id, String status, String error) {
    jdbc.sql("UPDATE deployments SET status = ?, error = ?, finished_at = ? WHERE id = ?")
        .params(status, error, "in_progress".equals(status) ? null : now(), id).update();
  }

  // ------------------------------------------------------------------ pedidos de publicação
  private RequestRow request(ResultSet rs, int i) throws SQLException {
    return new RequestRow(rs.getLong("id"), rs.getString("cluster_id"), rs.getInt("base_version"), rs.getString("status"), rs.getString("note"),
        rs.getString("requested_by"), rs.getString("requested_at"), rs.getString("decided_by"), rs.getString("decided_at"), rs.getString("decision_note"),
        nullableLong(rs, "deployment_id"), rs.getString("snapshot"), rs.getString("haproxy_cfg"));
  }

  /** Cria um pedido; pedidos pendentes anteriores do cluster ficam obsoletos (baseados em estado antigo). */
  @Transactional
  public RequestRow createRequest(String clusterId, int baseVersion, String note, String requestedBy, Snapshot snapshot, String haproxyCfg) {
    jdbc.sql("UPDATE publish_requests SET status = 'superseded', decided_at = ? WHERE cluster_id = ? AND status = 'pending'").params(now(), clusterId).update();
    jdbc.sql("""
        INSERT INTO publish_requests(cluster_id, base_version, status, note, requested_by, requested_at, snapshot, haproxy_cfg)
        VALUES (?, ?, 'pending', ?, ?, ?, ?, ?)""")
        .params(clusterId, baseVersion, note, requestedBy, now(), toJson(snapshot), haproxyCfg).update();
    return jdbc.sql("SELECT * FROM publish_requests WHERE cluster_id = ? ORDER BY id DESC LIMIT 1").param(clusterId).query(this::request).single();
  }

  public Optional<RequestRow> getRequest(String clusterId, long id) {
    return jdbc.sql("SELECT * FROM publish_requests WHERE cluster_id = ? AND id = ?").params(clusterId, id).query(this::request).optional();
  }

  public List<RequestRow> listRequests(String clusterId, int limit) {
    return jdbc.sql("SELECT * FROM publish_requests WHERE cluster_id = ? ORDER BY id DESC LIMIT ?").params(clusterId, limit).query(this::request).list();
  }

  public long countPendingRequests(String clusterId) {
    return jdbc.sql("SELECT COUNT(*) FROM publish_requests WHERE cluster_id = ? AND status = 'pending'").param(clusterId).query(Long.class).single();
  }

  /** Muda o status apenas se ainda estiver pendente (evita aprovação dupla concorrente). */
  public boolean decideRequest(long id, String status, String decidedBy, String decisionNote, Long deploymentId) {
    return jdbc.sql("UPDATE publish_requests SET status = ?, decided_by = ?, decided_at = ?, decision_note = ?, deployment_id = ? WHERE id = ? AND status = 'pending'")
        .params(status, decidedBy, now(), decisionNote, deploymentId, id).update() == 1;
  }

  public void linkRequestDeployment(long id, long deploymentId) {
    jdbc.sql("UPDATE publish_requests SET deployment_id = ? WHERE id = ?").params(deploymentId, id).update();
  }

  public void supersedePendingRequests(String clusterId) {
    jdbc.sql("UPDATE publish_requests SET status = 'superseded', decided_at = ? WHERE cluster_id = ? AND status = 'pending'").params(now(), clusterId).update();
  }

  // ------------------------------------------------------------------ notificações (alertas)
  public record NotificationRow(long id, String ts, String level, String kind, String clusterId, String title, String message, String data,
                                String deliveries, String acknowledgedAt, String acknowledgedBy) {}

  private NotificationRow notification(ResultSet rs, int i) throws SQLException {
    return new NotificationRow(rs.getLong("id"), rs.getString("ts"), rs.getString("level"), rs.getString("kind"), rs.getString("cluster_id"),
        rs.getString("title"), rs.getString("message"), rs.getString("data"), rs.getString("deliveries"), rs.getString("acknowledged_at"),
        rs.getString("acknowledged_by"));
  }

  /** Cria a notificação se a chave de deduplicação ainda não existe. Vazio = já notificado antes. */
  @Transactional
  public Optional<NotificationRow> createNotification(String level, String kind, String clusterId, String title, String message, String data, String dedupeKey) {
    if (dedupeKey != null && jdbc.sql("SELECT COUNT(*) FROM notifications WHERE dedupe_key = ?").param(dedupeKey).query(Long.class).single() > 0) return Optional.empty();
    jdbc.sql("INSERT INTO notifications(ts, level, kind, cluster_id, title, message, data, dedupe_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
        .params(now(), level, kind, clusterId, title, message, data, dedupeKey).update();
    return jdbc.sql("SELECT * FROM notifications ORDER BY id DESC LIMIT 1").query(this::notification).optional();
  }

  public void setDeliveries(long id, String deliveriesJson) {
    jdbc.sql("UPDATE notifications SET deliveries = ? WHERE id = ?").params(deliveriesJson, id).update();
  }

  public List<NotificationRow> listNotifications(boolean unackedOnly, String clusterId, int limit) {
    var sql = "SELECT * FROM notifications WHERE 1 = 1" + (unackedOnly ? " AND acknowledged_at IS NULL" : "") + (clusterId != null ? " AND cluster_id = ?" : "")
        + " ORDER BY id DESC LIMIT ?";
    var q = jdbc.sql(sql);
    if (clusterId != null) q = q.param(clusterId);
    return q.param(limit).query(this::notification).list();
  }

  public long countUnacked() {
    return jdbc.sql("SELECT COUNT(*) FROM notifications WHERE acknowledged_at IS NULL").query(Long.class).single();
  }

  public boolean acknowledge(long id, String user) {
    return jdbc.sql("UPDATE notifications SET acknowledged_at = ?, acknowledged_by = ? WHERE id = ? AND acknowledged_at IS NULL").params(now(), user, id).update() == 1;
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
