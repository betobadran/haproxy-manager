package dev.hapm.manager.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Busca de logs de acesso do HAProxy no Loki. Os nós enviam syslog → OTel Collector (nó) → gateway → Loki (OTLP);
 * cada linha é logfmt ("client=... fe=... status=..."). O manager monta a consulta LogQL a partir dos filtros da tela.
 */
@Service
public class LogsService {
  /** valores de filtro: sem aspas, barras invertidas, chaves ou quebras de linha (não dá para "escapar" da consulta) */
  static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9_.:/@%+=&?*~,-]{1,200}$");
  static final Pattern STATUS = Pattern.compile("^([1-5])(xx|\\d\\d)$");

  private final String url;
  private final String tenant;
  private final String token;
  private final JsonMapper json;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public LogsService(@Value("${hapm.loki.url:}") String url, @Value("${hapm.loki.tenant:}") String tenant, @Value("${hapm.loki.token:}") String token,
                     JsonMapper json) {
    this.url = url == null ? "" : url.replaceAll("/+$", "");
    this.tenant = tenant;
    this.token = token;
    this.json = json;
  }

  public boolean configured() {
    return !url.isEmpty();
  }

  public record Filters(int minutes, String node, String frontend, String backend, String status, String client, String path, String host, String text,
                        int limit) {}

  public record Entry(long ts, String node, String line, Map<String, String> fields) {}

  static String check(String name, String v) {
    if (v == null || v.isBlank()) return null;
    v = v.strip();
    if (!SAFE.matcher(v).matches()) throw new IllegalArgumentException(name + ": use apenas letras, números e . : / - _ (sem aspas ou espaços)");
    return v;
  }

  /** Monta a consulta LogQL. Filtros de linha (rápidos) primeiro; o parse logfmt só para o que precisa de igualdade exata. */
  static String logql(String cluster, Filters f) {
    var q = new StringBuilder("{service_name=\"hapm-haproxy\"} | hapm_cluster=\"").append(cluster).append('"');
    var node = check("nó", f.node());
    if (node != null) q.append(" | hapm_node=\"").append(node).append('"');
    var fe = check("listener", f.frontend());
    if (fe != null) q.append(" |= \"fe=").append(fe).append(' ').append('"');
    var be = check("backend", f.backend());
    if (be != null) q.append(" |= \"be=").append(be).append(' ').append('"');
    var client = check("cliente", f.client());
    if (client != null) q.append(" |= \"client=").append(client).append('"');
    var path = check("caminho", f.path());
    if (path != null) q.append(" |= \"path=\\\"").append(path).append('"');
    var host = check("host", f.host());
    if (host != null) q.append(" |= \"host=\\\"").append(host.toLowerCase()).append('"');
    var text = check("texto", f.text());
    if (text != null) q.append(" |= \"").append(text).append('"');
    if (f.status() != null && !f.status().isBlank()) {
      var m = STATUS.matcher(f.status().strip().toLowerCase());
      if (!m.matches()) throw new IllegalArgumentException("status: use 2xx..5xx ou um código (ex.: 503)");
      q.append(" | logfmt | status=~\"").append(m.group(2).equals("xx") ? m.group(1) + "[0-9][0-9]" : m.group(1) + m.group(2)).append('"');
    }
    return q.toString();
  }

  /** Interpreta uma linha logfmt (chave=valor, valores entre aspas com \" e \\ escapados). */
  public static Map<String, String> logfmt(String line) {
    var out = new LinkedHashMap<String, String>();
    int i = 0, n = line.length();
    while (i < n) {
      while (i < n && line.charAt(i) == ' ') i++;
      int ks = i;
      while (i < n && line.charAt(i) != '=' && line.charAt(i) != ' ') i++;
      var key = line.substring(ks, i);
      if (i >= n || line.charAt(i) != '=') {
        if (!key.isEmpty()) out.put(key, "");
        continue;
      }
      i++;
      var v = new StringBuilder();
      if (i < n && line.charAt(i) == '"') {
        i++;
        while (i < n && line.charAt(i) != '"') {
          if (line.charAt(i) == '\\' && i + 1 < n) i++;
          v.append(line.charAt(i++));
        }
        i++;
      } else {
        while (i < n && line.charAt(i) != ' ') v.append(line.charAt(i++));
      }
      if (!key.isEmpty()) out.put(key, v.toString());
    }
    return out;
  }

  public List<Entry> query(String clusterName, Filters f) throws Exception {
    if (!configured()) throw new IllegalStateException("Loki não configurado (HAPM_LOKI_URL)");
    var q = logql(clusterName, f);
    long end = System.currentTimeMillis() * 1_000_000L;
    long start = end - Math.max(1, Math.min(f.minutes(), 7 * 24 * 60)) * 60_000_000_000L;
    var limit = Math.max(1, Math.min(f.limit(), 2000));
    var uri = url + "/loki/api/v1/query_range?query=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&start=" + start + "&end=" + end + "&limit=" + limit
        + "&direction=backward";
    var b = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(30)).GET();
    if (tenant != null && !tenant.isBlank()) b.header("X-Scope-OrgID", tenant);
    if (token != null && !token.isBlank()) b.header("Authorization", "Bearer " + token);
    var res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() != 200) {
      throw new IllegalStateException("Loki respondeu HTTP " + res.statusCode() + ": " + res.body().substring(0, Math.min(300, res.body().length())));
    }
    var out = new ArrayList<Entry>();
    for (JsonNode s : json.readTree(res.body()).path("data").path("result")) {
      var labels = s.path("stream");
      var node = labels.path("hapm_node").asString("");
      for (JsonNode v : s.path("values")) {
        var line = v.get(1).asString();
        out.add(new Entry(Long.parseLong(v.get(0).asString()) / 1_000_000L, node, line, logfmt(line)));
      }
    }
    out.sort(Comparator.comparingLong(Entry::ts).reversed());
    return out.size() > limit ? out.subList(0, limit) : out;
  }

  public String describe(String clusterName, Filters f) {
    return logql(clusterName, f);
  }
}
