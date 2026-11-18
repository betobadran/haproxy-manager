package dev.hapm.manager.web;

import dev.hapm.manager.security.SessionUser;
import dev.hapm.manager.service.ServerOpsService;
import dev.hapm.manager.store.Store;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Drenar / manutenção / peso de servidores sem publicar (runtime API do HAProxy, aplicada pelos agentes). */
@RestController
@RequestMapping("/api/clusters/{cid}/runtime")
public class RuntimeController {
  private final Store store;
  private final ServerOpsService ops;

  public RuntimeController(Store store, ServerOpsService ops) {
    this.store = store;
    this.ops = ops;
  }

  public record ServerChange(String state, Integer weight, String reason) {}

  @GetMapping
  public Map<String, Object> get(@PathVariable String cid) {
    store.getCluster(cid).orElseThrow(() -> Http.notFound("cluster não encontrado"));
    var m = new LinkedHashMap<String, Object>();
    m.put("overrides", ops.overrides(cid));
    m.put("nodes", ops.reports(cid));
    return m;
  }

  @PutMapping("/servers/{backend}/{server}")
  public Map<String, Object> set(@PathVariable String cid, @PathVariable String backend, @PathVariable String server, @RequestBody ServerChange in,
                                 @AuthenticationPrincipal SessionUser user) {
    store.getCluster(cid).orElseThrow(() -> Http.notFound("cluster não encontrado"));
    try {
      ops.set(cid, backend, server, in.state(), in.weight(), in.reason(), user.username());
    } catch (IllegalArgumentException e) {
      throw Http.status(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    return get(cid);
  }
}
