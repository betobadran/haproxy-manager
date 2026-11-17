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
  @org.springframework.beans.factory.annotation.Autowired private dev.hapm.manager.service.SigningKeys signing;
  private static final java.security.SecureRandom RNG = new java.security.SecureRandom();

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

  static String sha256(String s) {
    try {
      return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Credencial própria do nó: depois que o nó recebe uma, só quem a apresenta fala por ele (o token de join, que é
   * do cluster, deixa de bastar para se passar por um nó existente). Agentes antigos (sem o cabeçalho) seguem só com o join.
   */
  private void checkNodeToken(String nodeId, String nodeName, String presented) {
    var hash = store.getNodeTokenHash(nodeId);
    if (hash.isEmpty()) return;
    if (presented == null || presented.equals("request") || !TokenService.constantTimeEquals(hash.get(), sha256(presented))) {
      throw Http.status(HttpStatus.UNAUTHORIZED, "credencial do nó " + nodeName + " inválida: se o agente perdeu o estado, remova o nó no manager para registrá-lo de novo");
    }
  }

  @PostMapping("/heartbeat")
  public HeartbeatResponse heartbeat(@RequestHeader(value = "Authorization", required = false) String authz,
                                     @RequestHeader(value = AgentProtocol.NODE_TOKEN_HEADER, required = false) String nodeToken, @RequestBody Heartbeat b) {
    var cluster = auth(authz);
    if (b.name() == null || !Checks.NAME.matcher(b.name()).matches()) throw Http.status(HttpStatus.BAD_REQUEST, "nome de nó inválido");
    if (b.address() != null && !Checks.isIp(b.address())) throw Http.status(HttpStatus.BAD_REQUEST, "endereço inválido");
    var la = b.lastApply();
    var hb = new Heartbeat(b.name(), b.address(), cut(b.agentVersion(), 32), cut(b.haproxyVersion(), 64),
        b.vrrpState() != null && VRRP.contains(b.vrrpState()) ? b.vrrpState() : null,
        b.appliedVersion() == null ? 0 : b.appliedVersion(),
        la == null ? null : new LastApply(la.version(), Boolean.TRUE.equals(la.ok()), cut(la.error(), 4000), cut(la.at(), 40)));
    var before = store.getNodeByName(cluster.id(), hb.name());
    before.ifPresent(n -> checkNodeToken(n.id(), n.name(), nodeToken));
    if (before.isEmpty() && cluster.settings().nodes() != null && !cluster.settings().nodes().joinAllowed() && !Boolean.TRUE.equals(b.leaving())) {
      throw Http.status(HttpStatus.FORBIDDEN, "registro de nós novos fechado neste cluster (Configurações › Nós)");
    }
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
    // primeira credencial do nó: entregue uma única vez, guardada só como hash
    String issued = null;
    if ("request".equals(nodeToken) && store.getNodeTokenHash(node.id()).isEmpty()) {
      var raw = new byte[32];
      RNG.nextBytes(raw);
      issued = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
      store.setNodeTokenHash(node.id(), sha256(issued));
      store.audit(null, cluster.id(), "node.credential.issued", node.name(), null);
    }
    return new HeartbeatResponse(deploy.desiredFor(cluster.id(), store.getNode(node.id()).orElseThrow()), 5, serverOps.runtimeFor(cluster), issued,
        signing.publicKey());
  }

  @GetMapping("/bundle/{version}")
  public Bundle bundle(@RequestHeader(value = "Authorization", required = false) String authz,
                       @RequestHeader(value = AgentProtocol.NODE_HEADER, required = false) String nodeName,
                       @RequestHeader(value = AgentProtocol.NODE_TOKEN_HEADER, required = false) String nodeToken, @PathVariable int version) {
    var cluster = auth(authz);
    var node = store.getNodeByName(cluster.id(), nodeName == null ? "" : nodeName)
        .orElseThrow(() -> Http.notFound("nó desconhecido; envie heartbeat primeiro"));
    checkNodeToken(node.id(), node.name(), nodeToken);
    var dep = store.getDeploymentByVersion(cluster.id(), version).orElseThrow(() -> Http.notFound("versão não encontrada"));
    // assinado (Ed25519): o agente só aplica o que o manager assinou, mesmo que alguém se ponha no meio do caminho
    return signing.sign(BundleBuilder.build(deploy.parseSnapshot(dep), dep.haproxyCfg(), dep.version(), new NodeInfo(node.name(), node.address(), node.settings())));
  }
}
