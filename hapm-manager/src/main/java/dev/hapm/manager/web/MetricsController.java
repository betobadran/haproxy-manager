package dev.hapm.manager.web;

import dev.hapm.core.model.Frontend;
import dev.hapm.manager.service.DeployService;
import dev.hapm.manager.service.MetricsService;
import dev.hapm.manager.store.Store;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Métricas ao vivo de tráfego do cluster (alimentadas pelos heartbeats dos agentes). */
@RestController
public class MetricsController {
  private final Store store;
  private final MetricsService metrics;

  public MetricsController(Store store, MetricsService metrics) {
    this.store = store;
    this.metrics = metrics;
  }

  @GetMapping("/api/clusters/{cid}/metrics")
  public Map<String, Object> metrics(@PathVariable String cid, @RequestParam(defaultValue = "15") int minutes,
                                     @RequestParam(required = false) String node) {
    store.getCluster(cid).orElseThrow(() -> Http.notFound("cluster não encontrado"));
    var modes = new HashMap<String, String>();
    for (var f : store.listObjects(cid, DeployService.FRONTEND, Frontend.class)) modes.put(f.name(), f.mode());
    return metrics.view(cid, Math.max(1, Math.min(minutes, 60)), node, modes);
  }
}
