package dev.hapm.manager.web;

import dev.hapm.core.util.LineDiff;
import dev.hapm.manager.service.PublishService;
import dev.hapm.manager.security.SessionUser;
import dev.hapm.manager.store.Store;
import dev.hapm.manager.store.Store.DeploymentRow;
import dev.hapm.manager.store.Store.RequestRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Pedidos de publicação (clusters com aprovação obrigatória). */
@RestController
@RequestMapping("/api/clusters/{cid}/requests")
public class RequestController {
  public record Decision(String comment) {}

  private final Store store;
  private final PublishService publish;

  public RequestController(Store store, PublishService publish) {
    this.store = store;
    this.publish = publish;
  }

  static Map<String, Object> view(RequestRow r) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", r.id());
    m.put("status", r.status());
    m.put("baseVersion", r.baseVersion());
    m.put("note", r.note());
    m.put("requestedBy", r.requestedBy());
    m.put("requestedAt", r.requestedAt());
    m.put("decidedBy", r.decidedBy());
    m.put("decidedAt", r.decidedAt());
    m.put("decisionNote", r.decisionNote());
    m.put("deploymentId", r.deploymentId());
    return m;
  }

  private void cluster(String cid) {
    store.getCluster(cid).orElseThrow(() -> Http.notFound("cluster não encontrado"));
  }

  @GetMapping
  public List<Map<String, Object>> list(@PathVariable String cid, @RequestParam(defaultValue = "50") int limit) {
    cluster(cid);
    return store.listRequests(cid, Math.min(limit, 500)).stream().map(RequestController::view).toList();
  }

  /** Detalhe com o diff exato do que será publicado em relação à versão base do pedido. */
  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable String cid, @PathVariable long id) {
    cluster(cid);
    var r = store.getRequest(cid, id).orElseThrow(() -> Http.notFound("pedido não encontrado"));
    var m = view(r);
    var base = store.getDeploymentByVersion(cid, r.baseVersion()).map(DeploymentRow::haproxyCfg).orElse("");
    var strip = (java.util.function.UnaryOperator<String>) s -> s.replaceAll("(?m)^# cluster: .*$", "");
    m.put("haproxyCfg", r.haproxyCfg());
    m.put("diff", LineDiff.diff(strip.apply(base), strip.apply(r.haproxyCfg()), 3));
    m.put("currentVersion", store.latestDeployment(cid).map(DeploymentRow::version).orElse(0));
    return m;
  }

  @PostMapping("/{id}/approve")
  public Map<String, Object> approve(@PathVariable String cid, @PathVariable long id, @RequestBody(required = false) Decision d,
                                     @AuthenticationPrincipal SessionUser user) {
    cluster(cid);
    var dep = publish.approve(cid, id, user, d == null ? null : d.comment());
    return Map.of("request", view(store.getRequest(cid, id).orElseThrow()), "deployment", DeploymentController.view(dep));
  }

  @PostMapping("/{id}/reject")
  public Map<String, Object> reject(@PathVariable String cid, @PathVariable long id, @RequestBody(required = false) Decision d,
                                    @AuthenticationPrincipal SessionUser user) {
    cluster(cid);
    return view(publish.reject(cid, id, user, d == null ? null : d.comment()));
  }

  @PostMapping("/{id}/cancel")
  public Map<String, Object> cancel(@PathVariable String cid, @PathVariable long id, @AuthenticationPrincipal SessionUser user) {
    cluster(cid);
    return view(publish.cancel(cid, id, user));
  }
}
