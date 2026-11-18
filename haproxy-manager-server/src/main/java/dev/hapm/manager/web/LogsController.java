package dev.hapm.manager.web;

import dev.hapm.manager.service.LogsService;
import dev.hapm.manager.store.Store;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Busca de logs de acesso (Loki). Contém IPs de clientes: exige OPERATOR. */
@RestController
public class LogsController {
  private final Store store;
  private final LogsService logs;

  public LogsController(Store store, LogsService logs) {
    this.store = store;
    this.logs = logs;
  }

  @GetMapping("/api/clusters/{cid}/logs")
  public Map<String, Object> search(@PathVariable String cid, @RequestParam(defaultValue = "15") int minutes, @RequestParam(required = false) String node,
                                    @RequestParam(required = false) String frontend, @RequestParam(required = false) String backend,
                                    @RequestParam(required = false) String status, @RequestParam(required = false) String client,
                                    @RequestParam(required = false) String path, @RequestParam(required = false) String host,
                                    @RequestParam(required = false) String q, @RequestParam(defaultValue = "200") int limit) {
    var c = store.getCluster(cid).orElseThrow(() -> Http.notFound("cluster não encontrado"));
    var out = new LinkedHashMap<String, Object>();
    out.put("configured", logs.configured());
    out.put("enabled", c.settings().logs() != null && c.settings().logs().on());
    if (!logs.configured()) return out;
    var f = new LogsService.Filters(minutes, node, frontend, backend, status, client, path, host, q, limit);
    try {
      out.put("query", logs.describe(c.name(), f));
      out.put("entries", logs.query(c.name(), f));
    } catch (IllegalArgumentException e) {
      throw Http.status(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      throw Http.status(HttpStatus.BAD_GATEWAY, "falha consultando o Loki: " + e.getMessage());
    }
    return out;
  }
}
