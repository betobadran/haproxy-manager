package dev.hapm.manager.web;

import dev.hapm.core.model.NodeInfo;
import dev.hapm.core.render.BundleBuilder;
import dev.hapm.core.render.RenderException;
import dev.hapm.core.util.LineDiff;
import dev.hapm.manager.service.DeployService;
import dev.hapm.manager.service.HaproxyChecker;
import dev.hapm.manager.store.Store;
import dev.hapm.manager.store.Store.DeploymentRow;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DeploymentController {
  public record PublishRequest(String note, Boolean validate) {}

  private final Store store;
  private final DeployService deploy;
  private final HaproxyChecker checker;

  public DeploymentController(Store store, DeployService deploy, HaproxyChecker checker) {
    this.store = store;
    this.deploy = deploy;
    this.checker = checker;
  }

  private void cluster(String cid) {
    store.getCluster(cid).orElseThrow(() -> Http.notFound("cluster não encontrado"));
  }

  static Map<String, Object> view(DeploymentRow d) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", d.id());
    m.put("version", d.version());
    m.put("status", d.status());
    m.put("error", d.error());
    m.put("note", d.note());
    m.put("createdBy", d.createdBy());
    m.put("createdAt", d.createdAt());
    m.put("finishedAt", d.finishedAt());
    return m;
  }

  private static String stripHeader(String cfg) {
    return cfg.replaceAll("(?m)^# cluster: .*$", "");
  }

  @GetMapping("/clusters/{cid}/preview")
  public Map<String, Object> preview(@PathVariable String cid) {
    cluster(cid);
    DeployService.Preview p;
    try {
      p = deploy.preview(cid);
    } catch (RenderException e) {
      return Map.of("ok", false, "errors", e.getErrors());
    }
    var bundle = BundleBuilder.build(p.snapshot(), p.haproxyCfg(), p.version(), new NodeInfo("__preview__", null, null));
    var check = checker.check(bundle);
    var keepalived = new LinkedHashMap<String, String>();
    if (p.snapshot().cluster().settings().usesKeepalived())
      for (var n : p.snapshot().nodes()) keepalived.put(n.name(), BundleBuilder.build(p.snapshot(), p.haproxyCfg(), p.version(), n).keepalived().get("keepalived.conf"));
    var m = new LinkedHashMap<String, Object>();
    m.put("ok", !check.available() || check.ok());
    m.put("version", p.version());
    m.put("haproxyCfg", p.haproxyCfg());
    m.put("keepalived", keepalived);
    m.put("check", check);
    m.put("currentVersion", p.current().map(DeploymentRow::version).orElse(null));
    m.put("diff", p.current().map(c -> LineDiff.diff(stripHeader(c.haproxyCfg()), stripHeader(p.haproxyCfg()), 3)).orElse(null));
    m.put("changed", p.current().map(c -> !stripHeader(c.haproxyCfg()).equals(stripHeader(p.haproxyCfg()))).orElse(true));
    return m;
  }

  @GetMapping("/clusters/{cid}/deployments")
  public List<Map<String, Object>> list(@PathVariable String cid, @RequestParam(defaultValue = "50") int limit) {
    cluster(cid);
    return store.listDeployments(cid, Math.min(limit, 500)).stream().map(DeploymentController::view).toList();
  }

  @GetMapping("/clusters/{cid}/deployments/{did}")
  public Map<String, Object> get(@PathVariable String cid, @PathVariable long did) {
    var d = store.getDeployment(cid, did).orElseThrow(() -> Http.notFound("não encontrado"));
    var m = view(d);
    m.put("haproxyCfg", d.haproxyCfg());
    m.put("nodes", store.listNodes(cid).stream().map(n -> {
      var x = new LinkedHashMap<String, Object>();
      x.put("name", n.name());
      x.put("online", deploy.isOnline(n));
      x.put("appliedVersion", n.appliedVersion());
      x.put("lastApply", n.lastApply());
      x.put("vrrpState", n.vrrpState());
      return x;
    }).toList());
    return m;
  }

  @PostMapping("/clusters/{cid}/deployments")
  public ResponseEntity<Map<String, Object>> publish(@PathVariable String cid, @RequestBody(required = false) PublishRequest req, Principal p) {
    cluster(cid);
    var r = req == null ? new PublishRequest(null, true) : req;
    if (!Boolean.FALSE.equals(r.validate())) {
      var pv = deploy.preview(cid);
      var check = checker.check(BundleBuilder.build(pv.snapshot(), pv.haproxyCfg(), pv.version(), new NodeInfo("__check__", null, null)));
      if (check.available() && !check.ok())
        return ResponseEntity.unprocessableContent().body(Map.of("error", "haproxy -c falhou", "errors", List.of(check.output())));
    }
    var d = deploy.create(cid, r.note(), p.getName(), null);
    return ResponseEntity.status(HttpStatus.CREATED).body(view(d));
  }

  @PostMapping("/clusters/{cid}/deployments/{did}/rollback")
  public ResponseEntity<Map<String, Object>> rollback(@PathVariable String cid, @PathVariable long did, Principal p) {
    cluster(cid);
    var d = deploy.rollback(cid, did, p.getName()).orElseThrow(() -> Http.notFound("não encontrado"));
    return ResponseEntity.status(HttpStatus.CREATED).body(view(d));
  }

  @GetMapping("/audit")
  public List<Store.AuditRow> audit(@RequestParam(defaultValue = "200") int limit, @RequestParam(required = false) String cluster) {
    return store.listAudit(Math.min(limit, 1000), cluster);
  }
}
