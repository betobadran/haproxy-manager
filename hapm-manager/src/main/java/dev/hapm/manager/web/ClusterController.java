package dev.hapm.manager.web;

import dev.hapm.core.model.ClusterSettings;
import dev.hapm.core.model.NodeInfo;
import dev.hapm.core.model.NodeSettings;
import dev.hapm.core.render.KeepalivedRenderer;
import dev.hapm.core.validate.Checks;
import dev.hapm.core.validate.Normalizer;
import dev.hapm.core.validate.ValidationException;
import dev.hapm.manager.service.DeployService;
import dev.hapm.manager.store.Store;
import dev.hapm.manager.store.Store.ClusterRow;
import dev.hapm.manager.store.Store.NodeRow;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clusters")
public class ClusterController {
  public record ClusterRequest(String name, ClusterSettings settings) {}

  public record NodeRequest(String name, String address, NodeSettings settings) {}

  private final Store store;
  private final DeployService deploy;

  public ClusterController(Store store, DeployService deploy) {
    this.store = store;
    this.deploy = deploy;
  }

  static String joinToken(ClusterRow c) {
    return c.id() + "." + c.joinSecret();
  }

  ClusterRow cluster(String id) {
    return store.getCluster(id).orElseThrow(() -> Http.notFound("cluster não encontrado"));
  }

  Map<String, Object> summary(ClusterRow c) {
    var nodes = store.listNodes(c.id());
    var online = nodes.stream().filter(deploy::isOnline).toList();
    var latest = store.latestDeployment(c.id());
    var m = new LinkedHashMap<String, Object>();
    m.put("id", c.id());
    m.put("name", c.name());
    m.put("mode", c.settings().mode());
    m.put("nodes", nodes.size());
    m.put("online", online.size());
    m.put("masters", online.stream().filter(n -> "MASTER".equals(n.vrrpState())).map(NodeRow::name).toList());
    m.put("latestDeployment", latest.map(d -> Map.of("version", d.version(), "status", d.status(), "createdAt", d.createdAt())).orElse(null));
    return m;
  }

  Map<String, Object> view(ClusterRow c, java.security.Principal p) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", c.id());
    m.put("name", c.name());
    m.put("settings", c.settings());
    // o token de join dá acesso às configs (inclui chaves TLS): só ADMIN vê
    var admin = p instanceof org.springframework.security.core.Authentication a
        && a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_ADMIN"));
    m.put("joinToken", admin ? joinToken(c) : null);
    m.put("pendingRequests", store.countPendingRequests(c.id()));
    m.put("createdAt", c.createdAt());
    m.put("updatedAt", c.updatedAt());
    m.put("summary", summary(c));
    return m;
  }

  @GetMapping
  public List<Map<String, Object>> list() {
    return store.listClusters().stream().map(this::summary).toList();
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> create(@RequestBody ClusterRequest req, Principal p) {
    var n = Normalizer.cluster(req.name(), req.settings(), null);
    if (store.listClusters().stream().anyMatch(c -> c.name().equals(n.name()))) throw Http.status(HttpStatus.CONFLICT, "já existe um cluster com esse nome");
    var c = store.createCluster(n.name(), n.settings());
    store.audit(p.getName(), c.id(), "cluster.create", c.name(), null);
    return ResponseEntity.status(HttpStatus.CREATED).body(view(c, p));
  }

  @GetMapping("/{cid}")
  public Map<String, Object> get(@PathVariable String cid, Principal p) {
    return view(cluster(cid), p);
  }

  @PutMapping("/{cid}")
  public Map<String, Object> update(@PathVariable String cid, @RequestBody ClusterRequest req, Principal p) {
    var c = cluster(cid);
    var n = Normalizer.cluster(req.name() != null ? req.name() : c.name(), req.settings(), c.settings());
    var u = store.updateCluster(cid, n.name(), n.settings());
    store.audit(p.getName(), cid, "cluster.update", u.name(), null);
    return view(u, p);
  }

  @PostMapping("/{cid}/rotate-token")
  public Map<String, String> rotate(@PathVariable String cid, Principal p) {
    cluster(cid);
    var u = store.rotateJoinSecret(cid);
    store.audit(p.getName(), cid, "cluster.token.rotate", null, null);
    return Map.of("joinToken", joinToken(u));
  }

  @DeleteMapping("/{cid}")
  public Map<String, Boolean> delete(@PathVariable String cid, Principal p) {
    var c = cluster(cid);
    store.deleteCluster(cid);
    store.audit(p.getName(), null, "cluster.delete", c.name(), null);
    return Map.of("ok", true);
  }

  // ------------------------------------------------------------------ nós
  @GetMapping("/{cid}/nodes")
  public List<Map<String, Object>> nodes(@PathVariable String cid) {
    var c = cluster(cid);
    var nodes = store.listNodes(cid);
    var infos = nodes.stream().map(n -> new NodeInfo(n.name(), n.address(), n.settings())).toList();
    return nodes.stream().map(n -> {
      var m = new LinkedHashMap<String, Object>();
      m.put("id", n.id());
      m.put("name", n.name());
      m.put("address", n.address());
      m.put("settings", n.settings());
      m.put("agentVersion", n.agentVersion());
      m.put("haproxyVersion", n.haproxyVersion());
      m.put("lastSeen", n.lastSeen());
      m.put("vrrpState", n.vrrpState());
      m.put("appliedVersion", n.appliedVersion());
      m.put("lastApply", n.lastApply());
      m.put("createdAt", n.createdAt());
      m.put("online", deploy.isOnline(n));
      m.put("priority", c.settings().usesKeepalived() ? KeepalivedRenderer.priority(infos, new NodeInfo(n.name(), n.address(), n.settings())) : null);
      m.put("desired", deploy.desiredFor(cid, n));
      return (Map<String, Object>) m;
    }).toList();
  }

  @PostMapping("/{cid}/nodes")
  public ResponseEntity<NodeRow> createNode(@PathVariable String cid, @RequestBody NodeRequest req, Principal p) {
    cluster(cid);
    if (req.name() == null || !Checks.NAME.matcher(req.name()).matches()) throw new ValidationException("name: nome inválido");
    if (req.address() != null && !Checks.isIp(req.address())) throw new ValidationException("address: IP inválido");
    if (store.getNodeByName(cid, req.name()).isPresent()) throw Http.status(HttpStatus.CONFLICT, "nó já existe");
    var n = store.createNode(cid, req.name(), req.address(), Normalizer.nodeSettings(req.settings()));
    store.audit(p.getName(), cid, "node.create", n.name(), null);
    return ResponseEntity.status(HttpStatus.CREATED).body(n);
  }

  @PutMapping("/{cid}/nodes/{nid}")
  public NodeRow updateNode(@PathVariable String cid, @PathVariable String nid, @RequestBody NodeRequest req, Principal p) {
    var n = store.getNode(nid).filter(x -> x.clusterId().equals(cid)).orElseThrow(() -> Http.notFound("nó não encontrado"));
    var s = Normalizer.nodeSettings(req.settings());
    var u = store.updateNodeSettings(n.id(), s);
    store.audit(p.getName(), cid, "node.update", n.name(), null);
    return u;
  }

  @DeleteMapping("/{cid}/nodes/{nid}")
  public Map<String, Boolean> deleteNode(@PathVariable String cid, @PathVariable String nid, Principal p) {
    var n = store.getNode(nid).filter(x -> x.clusterId().equals(cid)).orElseThrow(() -> Http.notFound("nó não encontrado"));
    store.deleteNode(n.id());
    store.audit(p.getName(), cid, "node.delete", n.name(), null);
    return Map.of("ok", true);
  }
}
