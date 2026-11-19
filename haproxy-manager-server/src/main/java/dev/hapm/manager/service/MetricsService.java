package dev.hapm.manager.service;

import dev.hapm.core.model.AgentProtocol.AwsCheck;
import dev.hapm.core.model.AgentProtocol.Traffic;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Métricas "ao vivo" para a tela do manager: os agentes enviam os contadores do HAProxy a cada heartbeat;
 * aqui viram taxas por segundo e ficam 1 h em memória por nó. Histórico longo fica no Prometheus/Grafana.
 */
@Service
public class MetricsService {
  static final long KEEP_MS = 60 * 60_000L;
  static final long BUCKET_MS = 10_000L;
  static final long STALE_MS = 60_000L;

  /** Taxas (por segundo) de um listener num instante; sessions é instantâneo. */
  public record FeRate(double req, double conn, double sessions, double bytesIn, double bytesOut,
                       double r1, double r2, double r3, double r4, double r5, double rOther, double denied) {
    static final FeRate ZERO = new FeRate(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    FeRate plus(FeRate o, double w) {
      return new FeRate(req + o.req * w, conn + o.conn * w, sessions + o.sessions * w, bytesIn + o.bytesIn * w, bytesOut + o.bytesOut * w,
          r1 + o.r1 * w, r2 + o.r2 * w, r3 + o.r3 * w, r4 + o.r4 * w, r5 + o.r5 * w, rOther + o.rOther * w, denied + o.denied * w);
    }
  }

  record Point(long ts, Map<String, FeRate> fe) {}

  static final class NodeSeries {
    Traffic last;
    long lastAt;
    final ArrayDeque<Point> points = new ArrayDeque<>();
  }

  private final Map<String, Map<String, NodeSeries>> data = new ConcurrentHashMap<>();
  private final Map<String, Map<String, AwsCheck>> aws = new ConcurrentHashMap<>();

  /** Contador que diminuiu = processo novo após reload: o valor atual é o que entrou desde então. */
  static double rate(double cur, double prev, double dt) {
    return Math.max(0, cur >= prev ? cur - prev : cur) / dt;
  }

  public void record(String clusterId, String node, Traffic t) {
    if (t == null || t.frontends() == null) return;
    var ns = data.computeIfAbsent(clusterId, k -> new ConcurrentHashMap<>()).computeIfAbsent(node, k -> new NodeSeries());
    synchronized (ns) {
      var prev = ns.last;
      if (prev != null && t.ts() > prev.ts() && t.ts() - prev.ts() < 120_000) {
        double dt = (t.ts() - prev.ts()) / 1000.0;
        var prevFe = new HashMap<String, dev.hapm.core.model.AgentProtocol.FrontendStat>();
        for (var f : prev.frontends()) prevFe.put(f.name(), f);
        var fe = new LinkedHashMap<String, FeRate>();
        for (var c : t.frontends()) {
          var p = prevFe.get(c.name());
          if (p == null) continue;
          fe.put(c.name(), new FeRate(rate(c.requests(), p.requests(), dt), rate(c.sessionsTotal(), p.sessionsTotal(), dt), c.sessions(),
              rate(c.bytesIn(), p.bytesIn(), dt), rate(c.bytesOut(), p.bytesOut(), dt), rate(c.r1xx(), p.r1xx(), dt), rate(c.r2xx(), p.r2xx(), dt),
              rate(c.r3xx(), p.r3xx(), dt), rate(c.r4xx(), p.r4xx(), dt), rate(c.r5xx(), p.r5xx(), dt), rate(c.rOther(), p.rOther(), dt),
              rate(c.denied(), p.denied(), dt)));
        }
        ns.points.addLast(new Point(t.ts(), fe));
        while (!ns.points.isEmpty() && ns.points.peekFirst().ts() < t.ts() - KEEP_MS) ns.points.removeFirst();
      }
      ns.last = t;
      ns.lastAt = System.currentTimeMillis();
    }
  }

  public void recordAws(String clusterId, String node, AwsCheck check) {
    if (check != null) aws.computeIfAbsent(clusterId, k -> new ConcurrentHashMap<>()).put(node, check);
  }

  public AwsCheck awsCheck(String clusterId, String node) {
    return aws.getOrDefault(clusterId, Map.of()).get(node);
  }

  public void forget(String clusterId) {
    data.remove(clusterId);
    aws.remove(clusterId);
  }

  /**
   * Visão agregada do cluster (ou de um nó): séries alinhadas em baldes de 10 s (média por nó dentro do balde,
   * soma entre nós), valores atuais e o estado dos backends/servidores.
   * modes: modo de cada listener (http/tcp) — em TCP a "taxa de requisições" é a de conexões.
   */
  public Map<String, Object> view(String clusterId, int minutes, String nodeFilter, Map<String, String> modes) {
    var now = System.currentTimeMillis();
    var since = now - minutes * 60_000L;
    var nodes = new TreeMap<>(data.getOrDefault(clusterId, Map.of()));
    var nodeInfo = new ArrayList<Map<String, Object>>();
    var buckets = new TreeMap<Long, Map<String, FeRate>>();
    var feNames = new TreeSet<String>();
    var latest = new LinkedHashMap<String, Traffic>();
    for (var e : nodes.entrySet()) {
      var ns = e.getValue();
      List<Point> pts;
      Traffic last;
      long lastAt;
      synchronized (ns) {
        pts = new ArrayList<>(ns.points);
        last = ns.last;
        lastAt = ns.lastAt;
      }
      boolean fresh = now - lastAt < STALE_MS;
      nodeInfo.add(Map.of("name", e.getKey(), "lastSampleAt", lastAt, "fresh", fresh));
      if (nodeFilter != null && !nodeFilter.isBlank() && !nodeFilter.equals(e.getKey())) continue;
      if (fresh && last != null) latest.put(e.getKey(), last);
      // média por balde neste nó
      var perBucket = new TreeMap<Long, List<Point>>();
      for (var p : pts) if (p.ts() >= since) perBucket.computeIfAbsent(p.ts() / BUCKET_MS * BUCKET_MS, k -> new ArrayList<>()).add(p);
      for (var b : perBucket.entrySet()) {
        var sum = buckets.computeIfAbsent(b.getKey(), k -> new HashMap<>());
        double w = 1.0 / b.getValue().size();
        for (var p : b.getValue())
          for (var f : p.fe().entrySet()) {
            feNames.add(f.getKey());
            sum.merge(f.getKey(), FeRate.ZERO.plus(f.getValue(), w), (a, c) -> a.plus(c, 1));
          }
      }
    }
    // séries
    var ts = new ArrayList<Long>(buckets.keySet());
    var requests = new LinkedHashMap<String, List<Double>>();
    var sessions = new LinkedHashMap<String, List<Double>>();
    var classes = new LinkedHashMap<String, List<Double>>();
    for (var c : List.of("2xx", "3xx", "4xx", "5xx")) classes.put(c, new ArrayList<>());
    var bytesIn = new ArrayList<Double>();
    var bytesOut = new ArrayList<Double>();
    var denied = new ArrayList<Double>();
    for (var fe : feNames) {
      requests.put(fe, new ArrayList<>());
      sessions.put(fe, new ArrayList<>());
    }
    for (var b : buckets.values()) {
      double bi = 0, bo = 0, de = 0, c2 = 0, c3 = 0, c4 = 0, c5 = 0;
      for (var fe : feNames) {
        var r = b.getOrDefault(fe, FeRate.ZERO);
        boolean tcp = "tcp".equals(modes.get(fe));
        requests.get(fe).add(round(tcp ? r.conn() : r.req()));
        sessions.get(fe).add(round(r.sessions()));
        bi += r.bytesIn();
        bo += r.bytesOut();
        de += r.denied();
        c2 += r.r2() + r.r1();
        c3 += r.r3();
        c4 += r.r4();
        c5 += r.r5();
      }
      bytesIn.add(round(bi));
      bytesOut.add(round(bo));
      denied.add(round(de));
      classes.get("2xx").add(round(c2));
      classes.get("3xx").add(round(c3));
      classes.get("4xx").add(round(c4));
      classes.get("5xx").add(round(c5));
    }
    // atuais: média do último minuto
    double reqNow = 0, resp = 0, r5 = 0;
    var lastMinute = buckets.tailMap(now - 60_000L);
    if (!lastMinute.isEmpty()) {
      for (var b : lastMinute.values())
        for (var e : b.entrySet()) {
          var r = e.getValue();
          reqNow += "tcp".equals(modes.get(e.getKey())) ? r.conn() : r.req();
          resp += r.r1() + r.r2() + r.r3() + r.r4() + r.r5() + r.rOther();
          r5 += r.r5();
        }
      reqNow /= lastMinute.size();
    }
    double sessNow = 0, connNow = 0;
    for (var t : latest.values()) {
      for (var f : t.frontends()) sessNow += f.sessions();
      if (t.connections() != null) connNow += t.connections();
    }
    // backends e servidores (amostra mais recente de cada nó)
    var be = new TreeMap<String, Map<String, Object>>();
    var srv = new TreeMap<String, Map<String, Object>>();
    for (var e : latest.entrySet()) {
      for (var b : e.getValue().backends()) {
        var m = be.computeIfAbsent(b.name(), k -> {
          var x = new LinkedHashMap<String, Object>();
          x.put("name", k);
          x.put("nodesUp", 0);
          x.put("nodes", 0);
          x.put("active", Integer.MAX_VALUE);
          x.put("backup", Integer.MAX_VALUE);
          x.put("responseTimeMs", 0.0);
          x.put("queue", 0.0);
          x.put("sessions", 0.0);
          return x;
        });
        m.put("nodes", (int) m.get("nodes") + 1);
        if (b.up()) m.put("nodesUp", (int) m.get("nodesUp") + 1);
        m.put("active", Math.min((int) m.get("active"), b.active()));
        m.put("backup", Math.min((int) m.get("backup"), b.backup()));
        m.put("responseTimeMs", Math.max((double) m.get("responseTimeMs"), round(b.responseTime() * 1000)));
        m.put("queue", (double) m.get("queue") + b.queue());
        m.put("sessions", (double) m.get("sessions") + b.sessions());
      }
      for (var s : e.getValue().servers()) {
        @SuppressWarnings("unchecked")
        var states = (Map<String, String>) srv.computeIfAbsent(s.backend() + "/" + s.name(), k -> {
          var x = new LinkedHashMap<String, Object>();
          x.put("backend", s.backend());
          x.put("server", s.name());
          x.put("states", new TreeMap<String, String>());
          return x;
        }).get("states");
        states.put(e.getKey(), s.state());
      }
    }
    long backendsUp = be.values().stream().filter(m -> m.get("nodesUp").equals(m.get("nodes"))).count();
    var current = new LinkedHashMap<String, Object>();
    current.put("requestsPerSec", round(reqNow));
    current.put("errorRatePct", resp > 0 ? round(100 * r5 / resp) : 0.0);
    current.put("sessions", round(sessNow));
    current.put("connections", round(connNow));
    current.put("backendsUp", backendsUp);
    current.put("backendsTotal", be.size());

    var out = new LinkedHashMap<String, Object>();
    out.put("nodes", nodeInfo);
    out.put("frontends", feNames);
    out.put("bucketSeconds", BUCKET_MS / 1000);
    out.put("ts", ts);
    out.put("requests", requests);
    out.put("sessions", sessions);
    out.put("responses", classes);
    out.put("bytesIn", bytesIn);
    out.put("bytesOut", bytesOut);
    out.put("denied", denied);
    out.put("current", current);
    out.put("backends", be.values());
    out.put("servers", srv.values());
    return out;
  }

  static double round(double v) {
    return Math.round(v * 100) / 100.0;
  }
}
