package dev.hapm.agent;

import dev.hapm.core.model.AgentProtocol.BackendStat;
import dev.hapm.core.model.AgentProtocol.FrontendStat;
import dev.hapm.core.model.AgentProtocol.ServerStat;
import dev.hapm.core.model.AgentProtocol.Traffic;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Lê o exporter Prometheus nativo do HAProxy local e resume o que a tela de métricas do manager precisa. */
public class TrafficCollector {
  private static final Pattern LINE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{(.*)})?\\s+(\\S+)");
  private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final String url;

  public TrafficCollector(String url) {
    this.url = url;
  }

  public Traffic collect() {
    try {
      var res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
      return res.statusCode() == 200 ? parse(res.body(), System.currentTimeMillis()) : null;
    } catch (Exception e) {
      return null; // métricas são best-effort: nunca atrapalham o heartbeat
    }
  }

  private static final class Fe {
    double requests, sessionsTotal, sessions, bytesIn, bytesOut, r1, r2, r3, r4, r5, rOther, denied;
  }

  private static final class Be {
    boolean up;
    int active, backup;
    double responseTime, queue, sessions, r5xx, responses;
  }

  /** Converte o texto do exporter em uma amostra. Ignora os proxies internos do HAPM (hapm_*). */
  static Traffic parse(String text, long ts) {
    var fes = new TreeMap<String, Fe>();
    var bes = new TreeMap<String, Be>();
    var servers = new LinkedHashMap<String, ServerStat>();
    Double conns = null;
    for (var raw : text.split("\n")) {
      if (raw.isEmpty() || raw.charAt(0) == '#' || !raw.startsWith("haproxy_")) continue;
      var m = LINE.matcher(raw);
      if (!m.find()) continue;
      var name = m.group(1);
      double v;
      try {
        v = Double.parseDouble(m.group(3));
      } catch (NumberFormatException e) {
        continue;
      }
      if (Double.isNaN(v)) v = 0;
      Map<String, String> l = new HashMap<>();
      if (m.group(2) != null) {
        var lm = LABEL.matcher(m.group(2));
        while (lm.find()) l.put(lm.group(1), lm.group(2));
      }
      if (name.equals("haproxy_process_current_connections")) {
        conns = v;
        continue;
      }
      var proxy = l.get("proxy");
      if (proxy == null || proxy.startsWith("hapm_")) continue;
      if (name.startsWith("haproxy_frontend_")) {
        var f = fes.computeIfAbsent(proxy, k -> new Fe());
        switch (name) {
          case "haproxy_frontend_http_requests_total" -> f.requests = v;
          case "haproxy_frontend_sessions_total" -> f.sessionsTotal = v;
          case "haproxy_frontend_current_sessions" -> f.sessions = v;
          case "haproxy_frontend_bytes_in_total" -> f.bytesIn = v;
          case "haproxy_frontend_bytes_out_total" -> f.bytesOut = v;
          case "haproxy_frontend_requests_denied_total" -> f.denied = v;
          case "haproxy_frontend_http_responses_total" -> {
            switch (String.valueOf(l.get("code"))) {
              case "1xx" -> f.r1 = v;
              case "2xx" -> f.r2 = v;
              case "3xx" -> f.r3 = v;
              case "4xx" -> f.r4 = v;
              case "5xx" -> f.r5 = v;
              default -> f.rOther += v;
            }
          }
          default -> {}
        }
      } else if (name.startsWith("haproxy_backend_")) {
        var b = bes.computeIfAbsent(proxy, k -> new Be());
        switch (name) {
          case "haproxy_backend_status" -> { if ("UP".equals(l.get("state")) && v == 1) b.up = true; }
          case "haproxy_backend_active_servers" -> b.active = (int) v;
          case "haproxy_backend_backup_servers" -> b.backup = (int) v;
          case "haproxy_backend_response_time_average_seconds" -> b.responseTime = v;
          case "haproxy_backend_current_queue" -> b.queue = v;
          case "haproxy_backend_current_sessions" -> b.sessions = v;
          case "haproxy_backend_http_responses_total" -> {
            b.responses += v;
            if ("5xx".equals(l.get("code"))) b.r5xx = v;
          }
          default -> {}
        }
      } else if (name.equals("haproxy_server_status") && v == 1) {
        var server = l.get("server");
        if (server != null) servers.put(proxy + "/" + server, new ServerStat(proxy, server, l.getOrDefault("state", "?")));
      }
    }
    var fl = new ArrayList<FrontendStat>();
    fes.forEach((n, f) -> fl.add(new FrontendStat(n, f.requests, f.sessionsTotal, f.sessions, f.bytesIn, f.bytesOut, f.r1, f.r2, f.r3, f.r4, f.r5, f.rOther, f.denied)));
    var bl = new ArrayList<BackendStat>();
    bes.forEach((n, b) -> bl.add(new BackendStat(n, b.up, b.active, b.backup, b.responseTime, b.queue, b.sessions, b.r5xx, b.responses)));
    return new Traffic(ts, conns, fl, bl, new ArrayList<>(servers.values()));
  }
}
