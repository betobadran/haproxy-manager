package dev.hapm.manager;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hapm.core.model.AgentProtocol.BackendStat;
import dev.hapm.core.model.AgentProtocol.FrontendStat;
import dev.hapm.core.model.AgentProtocol.ServerStat;
import dev.hapm.core.model.AgentProtocol.Traffic;
import dev.hapm.manager.service.MetricsService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsServiceTest {
  static FrontendStat fe(String name, double req, double conns, double r2, double r5) {
    return new FrontendStat(name, req, conns, 3, req * 100, req * 1000, 0, r2, 0, 0, r5, 0, 0);
  }

  static Traffic sample(long ts, double req, double r5, boolean backendUp) {
    return new Traffic(ts, 5.0, List.of(fe("web", req, 0, req - r5, r5), fe("pg", 0, req / 2, 0, 0)),
        List.of(new BackendStat("app", backendUp, backendUp ? 2 : 0, 0, 0.05, 0, 1, r5, req)), List.of(new ServerStat("app", "s1", backendUp ? "UP" : "DOWN")));
  }

  @Test
  @SuppressWarnings("unchecked")
  void ratesResetsAndAggregation() {
    var svc = new MetricsService();
    long now = System.currentTimeMillis();
    // lb1: 100 req/s, 10% 5xx
    svc.record("c", "lb1", sample(now - 20_000, 1000, 100, true));
    svc.record("c", "lb1", sample(now - 10_000, 2000, 200, true));
    // reload do HAProxy: contador volta a zero -> a taxa usa o valor novo (300 em 10 s = 30/s), nunca negativa
    svc.record("c", "lb1", sample(now, 300, 0, true));
    // lb2: 50 req/s, backend DOWN
    svc.record("c", "lb2", sample(now - 10_000, 0, 0, false));
    svc.record("c", "lb2", sample(now, 500, 0, false));

    var v = svc.view("c", 15, null, Map.of("web", "http", "pg", "tcp"));
    var req = (Map<String, List<Double>>) v.get("requests");
    assertThat(req.get("web")).allMatch(x -> x >= 0);
    assertThat(req.get("web")).contains(100.0);             // lb1 no balde do meio
    assertThat(req.get("web").getLast()).isEqualTo(80.0);    // 30 (lb1 pós-reload) + 50 (lb2)
    assertThat(req.get("pg").getLast()).isEqualTo(40.0);     // TCP usa conexões/s: 15 + 25
    var resp = (Map<String, List<Double>>) v.get("responses");
    assertThat(resp.get("5xx")).contains(10.0);
    var cur = (Map<String, Object>) v.get("current");
    assertThat(cur.get("backendsUp")).isEqualTo(0L);          // DOWN em um dos nós
    assertThat(cur.get("backendsTotal")).isEqualTo(1);
    assertThat(cur.get("connections")).isEqualTo(10.0);
    var backends = (java.util.Collection<Map<String, Object>>) v.get("backends");
    assertThat(backends.iterator().next()).containsEntry("nodesUp", 1).containsEntry("nodes", 2);

    // filtro por nó
    var only1 = (Map<String, List<Double>>) svc.view("c", 15, "lb1", Map.of()).get("requests");
    assertThat(only1.get("web").getLast()).isEqualTo(30.0);
  }
}
