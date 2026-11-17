package dev.hapm.manager.web;

import dev.hapm.core.model.AgentProtocol;
import dev.hapm.core.model.AgentProtocol.Heartbeat;
import dev.hapm.core.model.AgentProtocol.Desired;
import dev.hapm.core.model.AgentProtocol.HeartbeatResponse;
import dev.hapm.core.model.AgentProtocol.LastApply;
import dev.hapm.core.model.Bundle;
import dev.hapm.core.model.NodeInfo;
import dev.hapm.core.render.BundleBuilder;
import dev.hapm.core.validate.Checks;
import dev.hapm.manager.security.TokenService;
import dev.hapm.manager.service.DeployService;
import dev.hapm.manager.store.Store;
import dev.hapm.manager.store.Store.ClusterRow;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API dos agentes (modelo pull: o agente só precisa de saída HTTP(S) até o manager). */
@RestController
@RequestMapping("/agent/v1")
public class AgentController {
  private static final Set<String> VRRP = Set.of("MASTER", "BACKUP", "FAULT", "STOP", "UNKNOWN");

  private final Store store;
  private final DeployService deploy;
  private final dev.hapm.manager.service.MetricsService metrics;
  private final dev.hapm.manager.service.OpsMonitor ops;
  private final dev.hapm.manager.service.ServerOpsService serverOps;

  public AgentController(Store store, DeployService deploy, dev.hapm.manager.service.MetricsService metrics, dev.hapm.manager.service.OpsMonitor ops,
                         dev.hapm.manager.service.ServerOpsService serverOps) {
    this.serverOps = serverOps;
    this.store = store;
    this.deploy = deploy;
    this.metrics = metrics;
    this.ops = ops;
  }

  /** Token de join "clusterId.segredo". */
  private ClusterRow auth(String header) {
    var t = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
    int dot = t.indexOf('.');
    if (dot > 0) {
      var c = store.getCluster(t.substring(0, dot));
      if (c.isPresent() && TokenService.constantTimeEquals(c.get().joinSecret(), t.substring(dot + 1))) return c.get();
    }
    throw Http.status(HttpStatus.UNAUTHORIZED, "token de join inválido");
  }

  private static String cut(String s, int max) {
    return s == null ? null : s.length() > max ? s.substring(0, max) : s;
  }

  @PostMapping("/heartbeat")
  public HeartbeatResponse heartbeat(@RequestHeader(value = "Authorization", required = false) String authz, @RequestBody Heartbeat b) {
    var cluster = auth(authz);
    if (b.name() == null || !Checks.NAME.matcher(b.name()).matches()) throw Http.status(HttpStatus.BAD_REQUEST, "nome de nó inválido");
    if (b.address() != null && !Checks.isIp(b.address())) throw Http.status(HttpStatus.BAD_REQUEST, "endereço inválido");
    var la = b.lastApply();
    var hb = new Heartbeat(b.name(), b.address(), cut(b.agentVersion(), 32), cut(b.haproxyVersion(), 64),
        b.vrrpState() != null && VRRP.contains(b.vrrpState()) ? b.vrrpState() : null,
        b.appliedVersion() == null ? 0 : b.appliedVersion(),
        la == null ? null : new LastApply(la.version(), Boolean.TRUE.equals(la.ok()), cut(la.error(), 4000), cut(la.at(), 40)));
    var before = store.getNodeByName(cluster.id(), hb.name());
    if (Boolean.TRUE.equals(b.leaving())) {
      // scale-in (AWS): a instância está sendo encerrada; remove o nó e não registra de novo
      before.ifPresent(n -> ops.onNodeLeft(cluster, n, "instância encerrada pelo Auto Scaling (lifecycle hook)"));
      return new HeartbeatResponse(Desired.of(null), 30, null);
    }
    var node = store.upsertHeartbeat(cluster.id(), hb);
    ops.onHeartbeat(cluster, before, node, b.traffic());
    metrics.record(cluster.id(), node.name(), b.traffic());
    metrics.recordAws(cluster.id(), node.name(), b.aws());
    deploy.evaluate(cluster.id());
    var rt = b.runtime();
    serverOps.report(cluster.id(), node.name(), rt == null ? null
        : new AgentProtocol.RuntimeReport(rt.ok(), cut(rt.error(), 2000), cut(rt.at(), 40), rt.commands()));
    return new HeartbeatResponse(deploy.desiredFor(cluster.id(), store.getNode(node.id()).orElseThrow()), 5, serverOps.runtimeFor(cluster));
  }

  @GetMapping("/bundle/{version}")
  public Bundle bundle(@RequestHeader(value = "Authorization", required = false) String authz,
                       @RequestHeader(value = AgentProtocol.NODE_HEADER, required = false) String nodeName, @PathVariable int version) {
    var cluster = auth(authz);
    var node = store.getNodeByName(cluster.id(), nodeName == null ? "" : nodeName)
        .orElseThrow(() -> Http.notFound("nó desconhecido; envie heartbeat primeiro"));
    var dep = store.getDeploymentByVersion(cluster.id(), version).orElseThrow(() -> Http.notFound("versão não encontrada"));
    return BundleBuilder.build(deploy.parseSnapshot(dep), dep.haproxyCfg(), dep.version(), new NodeInfo(node.name(), node.address(), node.settings()));
  }
}
