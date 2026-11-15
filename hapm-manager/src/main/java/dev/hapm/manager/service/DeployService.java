package dev.hapm.manager.service;

import dev.hapm.core.model.AgentProtocol.Desired;
import dev.hapm.core.model.Backend;
import dev.hapm.core.model.Certificate;
import dev.hapm.core.model.Cluster;
import dev.hapm.core.model.Frontend;
import dev.hapm.core.model.NodeInfo;
import dev.hapm.core.model.Snapshot;
import dev.hapm.core.render.HaproxyRenderer;
import dev.hapm.manager.HapmProperties;
import dev.hapm.manager.store.Store;
import dev.hapm.manager.store.Store.DeploymentRow;
import dev.hapm.manager.store.Store.NodeRow;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Orquestração de publicações: versões imutáveis, rollout seguro (nós BACKUP antes do MASTER),
 * detecção de falha e retorno automático de todos os nós para a última versão boa.
 */
@Service
public class DeployService {
  private static final Logger log = LoggerFactory.getLogger(DeployService.class);

  public static final String FRONTEND = "frontend";
  public static final String BACKEND = "backend";
  public static final String CERTIFICATE = "certificate";

  private final Store store;
  private final HapmProperties props;

  public DeployService(Store store, HapmProperties props) {
    this.store = store;
    this.props = props;
  }

  public boolean isOnline(NodeRow n) {
    return n.lastSeen() != null && System.currentTimeMillis() - n.lastSeen() < props.nodeOfflineAfterSec() * 1000L
        && (n.settings() == null || !n.settings().isDisabled());
  }

  public Snapshot snapshot(String clusterId) {
    var c = store.getCluster(clusterId).orElseThrow();
    return new Snapshot(
        new Cluster(c.id(), c.name(), c.settings()),
        store.listObjects(clusterId, FRONTEND, Frontend.class),
        store.listObjects(clusterId, BACKEND, Backend.class),
        store.listObjects(clusterId, CERTIFICATE, Certificate.class).stream().filter(Certificate::usable).toList(),
        store.listNodes(clusterId).stream().map(n -> new NodeInfo(n.name(), n.address(), n.settings())).toList());
  }

  public Snapshot parseSnapshot(DeploymentRow d) {
    return store.fromJson(d.snapshotJson(), Snapshot.class);
  }

  public record Preview(Snapshot snapshot, int version, String haproxyCfg, Optional<DeploymentRow> current) {}

  /** Renderiza sem gravar. Lança RenderException se houver problemas no cluster. */
  public Preview preview(String clusterId) {
    var snap = snapshot(clusterId);
    int version = store.nextVersion(clusterId);
    return new Preview(snap, version, HaproxyRenderer.render(snap, version, Instant.now().toString()), store.latestDeployment(clusterId));
  }

  public DeploymentRow create(String clusterId, String note, String user, Snapshot override) {
    return create(clusterId, note, user, override, null, null);
  }

  public DeploymentRow create(String clusterId, String note, String user, Snapshot override, String approvedBy, Long requestId) {
    var snap = override != null ? override : snapshot(clusterId);
    int version = store.nextVersion(clusterId);
    var cfg = HaproxyRenderer.render(snap, version, Instant.now().toString()); // valida antes de gravar
    var dep = store.createDeployment(clusterId, version, snap, cfg, note, user, approvedBy, requestId);
    // pedidos pendentes foram feitos sobre a versão anterior: ficam obsoletos
    if (requestId == null) store.supersedePendingRequests(clusterId);
    var detail = new java.util.HashMap<String, Object>();
    if (note != null) detail.put("note", note);
    if (approvedBy != null) detail.put("approvedBy", approvedBy);
    store.audit(user, clusterId, "deployment.create", "v" + version, detail.isEmpty() ? null : detail);
    evaluate(clusterId);
    return store.getDeployment(clusterId, dep.id()).orElseThrow();
  }

  /** Nova versão com a configuração de tráfego antiga, mantendo nós e certificados atuais. */
  public Optional<DeploymentRow> rollback(String clusterId, long deploymentId, String user) {
    return store.getDeployment(clusterId, deploymentId).map(old -> {
      var cur = snapshot(clusterId);
      var snap = parseSnapshot(old).withNodesAndCertificates(cur.nodes(), cur.certificates());
      return create(clusterId, "rollback para v" + old.version(), user, snap);
    });
  }

  /**
   * Republica a última versão boa trocando só os certificados (renovação ACME). Não carrega edições de
   * listeners/backends ainda não publicadas/aprovadas, por isso dispensa aprovação.
   */
  public Optional<DeploymentRow> refreshCertificates(String clusterId, String note, String user) {
    return store.latestSucceeded(clusterId).map(good -> {
      var cur = snapshot(clusterId);
      var snap = parseSnapshot(good).withNodesAndCertificates(cur.nodes(), cur.certificates());
      var version = store.nextVersion(clusterId);
      var cfg = HaproxyRenderer.render(snap, version, Instant.now().toString());
      var dep = store.createDeployment(clusterId, version, snap, cfg, note, user, null, null);
      store.audit(user, clusterId, "deployment.cert-refresh", "v" + version, Map.of("base", "v" + good.version()));
      evaluate(clusterId);
      return dep;
    });
  }

  /** Ordem do rollout: nós não-MASTER primeiro (canário), MASTER por último. */
  public List<NodeRow> rolloutOrder(List<NodeRow> nodes) {
    return nodes.stream().filter(this::isOnline)
        .sorted(Comparator.comparingInt((NodeRow n) -> "MASTER".equals(n.vrrpState()) ? 1 : 0).thenComparing(NodeRow::name))
        .toList();
  }

  /** Qual versão o nó deve ter agora e se deve aguardar. */
  public Desired desiredFor(String clusterId, NodeRow node) {
    var latest = store.latestDeployment(clusterId);
    if (latest.isEmpty()) return Desired.of(null);
    var dep = latest.get();
    if (dep.status().equals("failed")) {
      var good = store.latestSucceeded(clusterId);
      return new Desired(good.map(DeploymentRow::version).orElse(null), null, "v" + dep.version() + " falhou; mantendo última versão boa");
    }
    if (node.appliedVersion() == dep.version() || dep.status().equals("succeeded")) return Desired.of(dep.version());

    var cluster = store.getCluster(clusterId).orElseThrow();
    var order = rolloutOrder(store.listNodes(clusterId));
    int idx = 0;
    while (idx < order.size() && !order.get(idx).id().equals(node.id())) idx++;
    NodeRow blocker = null;
    if ("parallel".equals(cluster.settings().rollout())) {
      if ("MASTER".equals(node.vrrpState()))
        blocker = order.stream().filter(n -> !"MASTER".equals(n.vrrpState()) && pending(n, node, dep)).findFirst().orElse(null);
    } else {
      blocker = order.subList(0, Math.min(idx, order.size())).stream().filter(n -> pending(n, node, dep)).findFirst().orElse(null);
    }
    if (blocker != null) return new Desired(dep.version(), true, "aguardando " + blocker.name() + " aplicar v" + dep.version());
    return Desired.of(dep.version());
  }

  private static boolean pending(NodeRow n, NodeRow self, DeploymentRow dep) {
    return !n.id().equals(self.id()) && n.appliedVersion() != dep.version();
  }

  /** Recalcula o status do deployment em andamento. */
  public synchronized Optional<DeploymentRow> evaluate(String clusterId) {
    var latest = store.latestDeployment(clusterId);
    if (latest.isEmpty() || !latest.get().status().equals("in_progress")) return latest;
    var dep = latest.get();
    var online = store.listNodes(clusterId).stream().filter(this::isOnline).toList();
    var failed = online.stream()
        .filter(n -> n.lastApply() != null && Integer.valueOf(dep.version()).equals(n.lastApply().version()) && !Boolean.TRUE.equals(n.lastApply().ok()))
        .findFirst();
    if (failed.isPresent()) {
      var error = failed.get().name() + ": " + (failed.get().lastApply().error() == null ? "erro desconhecido" : failed.get().lastApply().error());
      store.setDeploymentStatus(dep.id(), "failed", error);
      store.audit(null, clusterId, "deployment.failed", "v" + dep.version(), Map.of("error", error));
      log.warn("deployment v{} do cluster {} falhou; nós voltarão para a última versão boa: {}", dep.version(), clusterId, error);
    } else if (online.stream().allMatch(n -> n.appliedVersion() == dep.version())) {
      store.setDeploymentStatus(dep.id(), "succeeded", online.isEmpty() ? "nenhum nó online; será aplicado quando os nós conectarem" : null);
      store.audit(null, clusterId, "deployment.succeeded", "v" + dep.version(), null);
    } else if (Instant.now().isAfter(Instant.parse(dep.createdAt()).plusSeconds(props.deploymentTimeoutSec()))) {
      var stuck = online.stream().filter(n -> n.appliedVersion() != dep.version()).map(NodeRow::name).toList();
      store.setDeploymentStatus(dep.id(), "failed", "timeout aguardando: " + String.join(", ", stuck));
      store.audit(null, clusterId, "deployment.failed", "v" + dep.version(), Map.of("error", "timeout", "stuck", stuck));
    }
    return store.getDeployment(clusterId, dep.id());
  }

  @Scheduled(fixedDelay = 10_000)
  public void evaluateAll() {
    for (var c : store.listClusters()) {
      try {
        evaluate(c.id());
      } catch (Exception e) {
        log.error("erro avaliando deployments do cluster {}", c.name(), e);
      }
    }
  }
}
