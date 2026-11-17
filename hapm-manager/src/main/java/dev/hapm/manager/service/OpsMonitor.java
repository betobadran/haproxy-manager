package dev.hapm.manager.service;

import dev.hapm.core.model.AgentProtocol.Traffic;
import dev.hapm.manager.HapmProperties;
import dev.hapm.manager.store.Store;
import dev.hapm.manager.store.Store.ClusterRow;
import dev.hapm.manager.store.Store.NodeRow;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Observa o estado dos clusters e registra a linha do tempo (failover, nós offline/online, split-brain, servidores
 * DOWN, publicações com falha), gerando alertas nos canais configurados (tela, e-mail, Teams, webhook).
 *
 * <p>O "estado atual" de cada assunto (nó, servidor, VRRP do cluster) é o último evento gravado para ele, então
 * reiniciar o manager não repete alertas já enviados.
 */
@Service
public class OpsMonitor {
  private static final Logger log = LoggerFactory.getLogger(OpsMonitor.class);
  static final int KEEP_EVENTS = 2000;

  private final Store store;
  private final DeployService deploy;
  private final NotificationService notifications;
  private final HapmProperties props;

  /** Cache do último evento por cluster+assunto (evita consulta ao banco a cada heartbeat). */
  private final Map<String, String> lastKind = new ConcurrentHashMap<>();
  /** Visão de cada nó sobre os servidores: cluster -> nó -> "backend/servidor" -> estado. */
  private final Map<String, Map<String, Map<String, String>>> serverViews = new ConcurrentHashMap<>();
  /** Condição de VRRP observada nas últimas verificações (exige 2 seguidas para alertar). */
  private final Map<String, String> pendingVrrp = new ConcurrentHashMap<>();

  public record DeploymentFailed(String clusterId, int version, String error) {}

  public OpsMonitor(Store store, DeployService deploy, NotificationService notifications, HapmProperties props) {
    this.store = store;
    this.deploy = deploy;
    this.notifications = notifications;
    this.props = props;
  }

  // ------------------------------------------------------------------ estado por assunto
  private String last(String clusterId, String subject) {
    return lastKind.computeIfAbsent(clusterId + "|" + subject, k -> store.lastEvent(clusterId, subject).map(Store.EventRow::kind).orElse(""));
  }

  private void event(String clusterId, String node, String subject, String kind, String detail, Map<String, ?> data) {
    store.addEvent(clusterId, node, subject, kind, detail, data);
    lastKind.put(clusterId + "|" + subject, kind);
  }

  private static String dur(long ms) {
    var d = Duration.ofMillis(ms);
    if (d.toHours() > 0) return d.toHours() + " h " + d.toMinutesPart() + " min";
    if (d.toMinutes() > 0) return d.toMinutes() + " min " + d.toSecondsPart() + " s";
    return d.toSeconds() + " s";
  }

  private static boolean usesVrrp(ClusterRow c) {
    return !"aws-nlb".equals(c.settings().mode());
  }

  // ------------------------------------------------------------------ heartbeat
  /** Chamado a cada heartbeat, com o nó como estava ANTES (vazio = primeiro contato) e como ficou. */
  public void onHeartbeat(ClusterRow c, Optional<NodeRow> before, NodeRow now, Traffic traffic) {
    try {
      var ops = notifications.ops();
      var subject = "node:" + now.name();
      if (before.isEmpty()) {
        event(c.id(), now.name(), subject, "node-joined", "nó registrado (" + (now.address() == null ? "?" : now.address()) + ")", null);
      } else if ("node-offline".equals(last(c.id(), subject))) {
        var b = before.get();
        var away = b.lastSeen() == null ? 0 : System.currentTimeMillis() - b.lastSeen();
        event(c.id(), now.name(), subject, "node-online", "voltou após " + dur(away) + " sem contato", null);
        if (ops.nodeOffline()) {
          notifications.notify("info", "node-online", c.id(), "Nó " + now.name() + " voltou (cluster " + c.name() + ")",
              "O agente voltou a responder após " + dur(away) + " sem contato.", Map.of("cluster", c.name(), "node", now.name()), null);
        }
      }
      var prevState = before.map(NodeRow::vrrpState).orElse(null);
      var state = now.vrrpState();
      if (usesVrrp(c) && state != null && !state.equals(prevState)) onVrrpChange(c, now, prevState, state, ops);
      if (traffic != null && traffic.servers() != null) onServers(c, now.name(), traffic, ops);
    } catch (Exception e) {
      log.warn("monitor: erro processando heartbeat de {}: {}", now.name(), e.getMessage());
    }
  }

  private void onVrrpChange(ClusterRow c, NodeRow node, String from, String to, NotificationService.Ops ops) {
    var detail = (from == null ? "?" : from) + " → " + to;
    if (!"MASTER".equals(to)) {
      event(c.id(), node.name(), "vrrp:" + node.name(), "vrrp", node.name() + ": " + detail, Map.of("from", String.valueOf(from), "to", to));
      return;
    }
    // quem era o MASTER antes (último evento "failover"/"vrrp-master" do cluster)
    var prevMaster = store.lastEvent(c.id(), "cluster:master").map(e -> e.nodeName()).orElse(null);
    if (prevMaster != null && !prevMaster.equals(node.name())) {
      var data = new LinkedHashMap<String, Object>();
      data.put("cluster", c.name());
      data.put("from", prevMaster);
      data.put("to", node.name());
      event(c.id(), node.name(), "cluster:master", "failover", "failover: " + node.name() + " assumiu o VIP (antes: " + prevMaster + ")", data);
      if (ops.failover()) {
        notifications.notify("warning", "failover", c.id(), "Failover no cluster " + c.name() + ": " + node.name() + " assumiu o VIP",
            "O VIP passou de " + prevMaster + " para " + node.name() + " (" + detail + "). Verifique por que " + prevMaster + " deixou de ser MASTER.", data, null);
      }
    } else {
      event(c.id(), node.name(), "cluster:master", "vrrp-master", node.name() + " é o MASTER (" + detail + ")", Map.of("to", node.name()));
    }
  }

  private void onServers(ClusterRow c, String nodeName, Traffic t, NotificationService.Ops ops) {
    var view = new HashMap<String, String>();
    for (var s : t.servers()) view.put(s.backend() + "/" + s.name(), s.state());
    var byNode = serverViews.computeIfAbsent(c.id(), k -> new ConcurrentHashMap<>());
    byNode.put(nodeName, view);
    var online = new HashSet<String>();
    for (var n : store.listNodes(c.id())) if (deploy.isOnline(n)) online.add(n.name());
    online.add(nodeName);
    byNode.keySet().retainAll(online);

    var activeByBackend = new HashMap<String, Integer>();
    if (t.backends() != null) for (var b : t.backends()) activeByBackend.put(b.name(), b.active());
    var keys = new HashSet<String>();
    for (var v : byNode.values()) keys.addAll(v.keySet());
    for (var key : keys) {
      Set<String> downOn = new java.util.TreeSet<>();
      for (var e : byNode.entrySet()) if ("DOWN".equals(e.getValue().get(key))) downOn.add(e.getKey());
      var subject = "server:" + key;
      var was = last(c.id(), subject);
      var backend = key.substring(0, key.indexOf('/'));
      if (!downOn.isEmpty() && !"server-down".equals(was)) {
        var none = Integer.valueOf(0).equals(activeByBackend.get(backend));
        var data = Map.of("cluster", c.name(), "server", key, "nodes", String.join(", ", downOn));
        event(c.id(), String.join(", ", downOn), subject, "server-down", "servidor " + key + " DOWN (health-check) em " + String.join(", ", downOn), data);
        if (ops.serverDown()) {
          notifications.notify(none ? "critical" : "warning", "server-down", c.id(),
              (none ? "Backend " + backend + " SEM servidores: " : "Servidor ") + key + " DOWN (cluster " + c.name() + ")",
              "O health-check do HAProxy marcou " + key + " como DOWN em " + String.join(", ", downOn) + "."
                  + (none ? " O backend " + backend + " ficou sem servidores ativos." : " Os demais servidores do backend seguem atendendo."), data, null);
        }
      } else if (downOn.isEmpty() && "server-down".equals(was)) {
        event(c.id(), null, subject, "server-up", "servidor " + key + " voltou (UP)", Map.of("server", key));
        if (ops.serverDown()) {
          notifications.notify("info", "server-up", c.id(), "Servidor " + key + " voltou (cluster " + c.name() + ")",
              "O health-check voltou a marcar " + key + " como UP.", Map.of("cluster", c.name(), "server", key), null);
        }
      }
    }
  }

  /** Remove o nó (scale-in na AWS ou remoção automática de nó offline) e registra na linha do tempo. */
  public void onNodeLeft(ClusterRow c, NodeRow n, String why) {
    store.deleteNode(n.id());
    store.audit(null, c.id(), "node.remove", n.name(), Map.of("reason", why));
    event(c.id(), n.name(), "node:" + n.name(), "node-left", "nó removido: " + why, null);
    var views = serverViews.get(c.id());
    if (views != null) views.remove(n.name());
    if (notifications.ops().nodeOffline()) {
      notifications.notify("info", "node-left", c.id(), "Nó " + n.name() + " removido do cluster " + c.name(), "Motivo: " + why + ".",
          Map.of("cluster", c.name(), "node", n.name()), null);
    }
  }

  // ------------------------------------------------------------------ verificações periódicas
  @Scheduled(initialDelay = 20_000, fixedDelay = 10_000)
  public void periodic() {
    for (var c : store.listClusters()) {
      try {
        check(c);
      } catch (Exception e) {
        log.warn("monitor: erro verificando cluster {}: {}", c.name(), e.getMessage());
      }
    }
  }

  public void check(ClusterRow c) {
    var ops = notifications.ops();
    var nodes = store.listNodes(c.id());
    long now = System.currentTimeMillis();
    for (var n : nodes) {
      if (n.settings() != null && n.settings().isDisabled()) continue;
      var subject = "node:" + n.name();
      var silent = n.lastSeen() == null ? Long.MAX_VALUE : now - n.lastSeen();
      var autoRemove = c.settings().nodes() == null ? null : c.settings().nodes().autoRemoveOfflineMinutes();
      if (autoRemove != null && autoRemove > 0 && silent >= autoRemove * 60_000L) {
        onNodeLeft(c, n, "offline há mais de " + autoRemove + " min (remoção automática configurada no cluster)");
        continue;
      }
      if (silent >= ops.offlineAfterSec() * 1000L && !"node-offline".equals(last(c.id(), subject))) {
        boolean wasMaster = "MASTER".equals(n.vrrpState());
        var remaining = nodes.stream().filter(x -> x != n && deploy.isOnline(x)).count();
        event(c.id(), n.name(), subject, "node-offline", "sem heartbeat há " + dur(silent) + (wasMaster ? " (era o MASTER)" : ""), null);
        if (ops.nodeOffline()) {
          notifications.notify(remaining == 0 ? "critical" : "warning", "node-offline", c.id(),
              "Nó " + n.name() + " offline (cluster " + c.name() + ")" + (remaining == 0 ? ": nenhum nó online" : ""),
              "O agente de " + n.name() + " não envia heartbeat há " + dur(silent) + ". Nós online restantes: " + remaining + "."
                  + (wasMaster ? " Ele era o MASTER: confira se o VIP passou para outro nó." : ""),
              Map.of("cluster", c.name(), "node", n.name(), "onlineRemaining", remaining), null);
        }
      }
    }
    if (usesVrrp(c)) checkVrrp(c, nodes, ops);
  }

  private void checkVrrp(ClusterRow c, java.util.List<NodeRow> nodes, NotificationService.Ops ops) {
    var online = nodes.stream().filter(deploy::isOnline).toList();
    if (online.isEmpty()) {
      pendingVrrp.remove(c.id());
      return; // sem nós online: o alerta de nó offline cobre
    }
    var masters = online.stream().filter(n -> "MASTER".equals(n.vrrpState())).map(NodeRow::name).sorted().toList();
    var cond = masters.size() > 1 ? "split-brain" : masters.isEmpty() ? "no-master" : "vrrp-ok";
    // exige a mesma condição em 2 verificações seguidas (~10 s): a troca normal de MASTER é mais rápida que isso
    var prev = pendingVrrp.put(c.id(), cond);
    if (!cond.equals(prev)) return;
    var was = last(c.id(), "cluster:vrrp");
    if (cond.equals(was) || (cond.equals("vrrp-ok") && was.isEmpty())) return;
    switch (cond) {
      case "split-brain" -> {
        event(c.id(), String.join(", ", masters), "cluster:vrrp", cond, "SPLIT-BRAIN: " + String.join(" e ", masters) + " estão como MASTER",
            Map.of("masters", masters));
        if (ops.splitBrain()) {
          notifications.notify("critical", "split-brain", c.id(), "Split-brain no cluster " + c.name() + ": " + String.join(" e ", masters) + " como MASTER",
              "Mais de um nó detém o VIP ao mesmo tempo. Causa comum: os nós não se enxergam (firewall bloqueando VRRP/protocolo 112 ou unicast). "
                  + "Tráfego pode estar indo para nós diferentes.", Map.of("cluster", c.name(), "masters", masters), null);
        }
      }
      case "no-master" -> {
        event(c.id(), null, "cluster:vrrp", cond, "nenhum nó online é MASTER: o VIP está sem dono", null);
        if (ops.splitBrain()) {
          notifications.notify("critical", "no-master", c.id(), "Cluster " + c.name() + " sem MASTER: VIP sem dono",
              "Há nós online, mas nenhum está como MASTER (todos BACKUP/FAULT). O VIP pode estar fora do ar. "
                  + "Verifique o HAProxy (o check do keepalived pode estar falhando em todos) e o keepalived.", Map.of("cluster", c.name()), null);
        }
      }
      default -> {
        event(c.id(), masters.get(0), "cluster:vrrp", "vrrp-ok", "VRRP normalizado: " + masters.get(0) + " é o único MASTER", null);
        if (ops.splitBrain()) {
          notifications.notify("info", "vrrp-ok", c.id(), "VRRP normalizado no cluster " + c.name(),
              masters.get(0) + " é o único MASTER novamente.", Map.of("cluster", c.name(), "master", masters.get(0)), null);
        }
      }
    }
  }

  @Scheduled(initialDelay = 300_000, fixedDelay = 3_600_000)
  public void prune() {
    for (var c : store.listClusters()) store.pruneEvents(c.id(), KEEP_EVENTS);
  }

  // ------------------------------------------------------------------ publicações
  @EventListener
  public void onDeploymentFailed(DeploymentFailed e) {
    try {
      var c = store.getCluster(e.clusterId()).orElse(null);
      if (c == null) return;
      var good = store.latestSucceeded(c.id()).map(d -> "v" + d.version()).orElse("nenhuma");
      event(c.id(), null, "cluster:deploy", "deploy-failed", "publicação v" + e.version() + " falhou; nós voltam para " + good, Map.of("error", e.error()));
      if (notifications.ops().deployFailed()) {
        notifications.notify("critical", "deploy-failed", c.id(), "Publicação v" + e.version() + " falhou (cluster " + c.name() + ")",
            "Erro: " + e.error() + ". Os nós voltam automaticamente para a última versão boa (" + good + ").",
            Map.of("cluster", c.name(), "version", e.version(), "error", e.error(), "rollbackTo", good), "deploy-failed:" + c.id() + ":" + e.version());
      }
    } catch (Exception ex) {
      log.warn("monitor: erro registrando falha de publicação: {}", ex.getMessage());
    }
  }

  /** Para testes: esquece caches em memória. */
  public void reset() {
    lastKind.clear();
    serverViews.clear();
    pendingVrrp.clear();
  }

  HapmProperties props() {
    return props;
  }
}
