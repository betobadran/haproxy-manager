package dev.hapm.core.render;

import dev.hapm.core.model.ClusterSettings;

import dev.hapm.core.model.Backend;
import dev.hapm.core.model.Certificate;
import dev.hapm.core.model.Cluster;
import dev.hapm.core.model.Frontend;
import dev.hapm.core.model.Snapshot;
import dev.hapm.core.validate.Checks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Gera o haproxy.cfg. O agente troca {@link #RELEASE_DIR} pelo diretório absoluto da release. */
public final class HaproxyRenderer {
  private HaproxyRenderer() {}

  public static final String RELEASE_DIR = "@HAPM_RELEASE_DIR@";
  public static final String CRT_LIST = RELEASE_DIR + "/certs.list";
  public static final String STATS_FRONTEND = "hapm_stats";
  public static final String ACME_BACKEND = "hapm_acme";

  private static String ind(String s) {
    return "    " + s;
  }

  /** Checagens que dependem do cluster inteiro. */
  public static List<String> crossValidate(Snapshot s) {
    var errors = new ArrayList<String>();
    var backends = new HashMap<String, Backend>();
    for (var b : s.backends()) backends.put(b.name(), b);
    var feNames = new HashSet<String>();
    for (var f : s.frontends()) feNames.add(f.name());
    for (var b : s.backends()) if (feNames.contains(b.name())) errors.add("nome \"" + b.name() + "\" usado em frontend e backend");
    boolean hasCerts = s.certificates().stream().anyMatch(Certificate::usable);
    int statsPort = s.cluster().settings().haproxy().statsPort();
    var ports = new HashMap<Integer, Map<String, String>>(); // porta -> endereço -> frontend
    for (var f : enabled(s.frontends())) {
      var refs = new ArrayList<String>();
      if (!f.defaultBackend().isEmpty()) refs.add(f.defaultBackend());
      for (var r : f.rules()) if ("use_backend".equals(r.action())) refs.add(r.backend());
      for (var r : refs) {
        var b = backends.get(r);
        if (b == null) errors.add("frontend " + f.name() + ": backend \"" + r + "\" não existe");
        else if (!b.mode().equals(f.mode())) errors.add("frontend " + f.name() + " (" + f.mode() + ") não pode usar backend " + b.name() + " (" + b.mode() + ")");
      }
      for (var b : f.binds()) {
        var byAddr = ports.computeIfAbsent(b.port(), p -> new HashMap<>());
        String clash = byAddr.get(b.address());
        if (clash == null && b.address().equals("*") && !byAddr.isEmpty()) clash = byAddr.values().iterator().next();
        if (clash == null && !b.address().equals("*")) clash = byAddr.get("*");
        if (clash != null && !clash.equals(f.name())) errors.add("porta " + b.port() + " usada por " + clash + " e " + f.name());
        byAddr.put(b.address(), f.name());
        if (b.port() == statsPort) errors.add("frontend " + f.name() + ": porta " + b.port() + " reservada para stats/métricas");
        if (b.ssl() && !hasCerts) errors.add("frontend " + f.name() + ": bind SSL na porta " + b.port() + " mas não há certificados emitidos");
      }
    }
    var dc = s.cluster().settings().haproxy().defaultCertificate();
    if (!dc.isEmpty() && s.certificates().stream().noneMatch(c -> c.usable() && c.name().equals(dc)))
      errors.add("certificado padrão \"" + dc + "\" não existe ou não foi emitido");
    return errors;
  }

  private static List<Frontend> enabled(List<Frontend> fs) {
    return fs.stream().filter(f -> !Boolean.FALSE.equals(f.enabled())).sorted(Comparator.comparing(Frontend::name)).toList();
  }

  /** Ordem do crt-list: o certificado padrão primeiro (usado quando o cliente não envia SNI). */
  public static List<Certificate> orderCertificates(Cluster cluster, List<Certificate> certs) {
    var usable = new ArrayList<>(certs.stream().filter(Certificate::usable).sorted(Comparator.comparing(Certificate::name)).toList());
    var dc = cluster.settings().haproxy().defaultCertificate();
    for (int i = 0; i < usable.size(); i++) {
      if (usable.get(i).name().equals(dc)) {
        usable.addFirst(usable.remove(i));
        break;
      }
    }
    return usable;
  }

  public static String crtList(Cluster cluster, List<Certificate> certs) {
    var sb = new StringBuilder();
    for (var c : orderCertificates(cluster, certs)) sb.append(RELEASE_DIR).append("/certs/").append(c.name()).append(".pem\n");
    return sb.toString();
  }

  private static String bindLine(Frontend.Bind b, Frontend f) {
    var addr = b.address().equals("*") ? "" : b.address();
    var line = new StringBuilder("bind ").append(addr).append(':').append(b.port());
    if (addr.equals("::")) line.append(" v4v6");
    if (b.ssl()) {
      line.append(" ssl crt-list ").append(CRT_LIST);
      if (f.isHttp()) line.append(f.http2() ? " alpn h2,http/1.1" : " alpn http/1.1");
    }
    return line.toString();
  }

  private static List<String> hostAcls(String acl, String fetch, List<String> values) {
    var exact = values.stream().filter(v -> !v.startsWith("*.")).toList();
    var wild = values.stream().filter(v -> v.startsWith("*.")).map(v -> v.substring(1)).toList();
    var out = new ArrayList<String>();
    if (!exact.isEmpty()) out.add("acl " + acl + " " + fetch + " -i " + String.join(" ", exact));
    if (!wild.isEmpty()) out.add("acl " + acl + " " + fetch + " -i -m end " + String.join(" ", wild));
    return out;
  }

  private static List<String> conditionAcls(String acl, Frontend.Condition c, Frontend f) {
    var v = String.join(" ", c.value());
    return switch (c.type()) {
      case "host" -> hostAcls(acl, "req.hdr(host),field(1,:)", c.value());
      case "sni" -> hostAcls(acl, f.binds().stream().anyMatch(Frontend.Bind::ssl) ? "ssl_fc_sni" : "req.ssl_sni", c.value());
      case "path", "path_beg", "path_end", "method", "src" -> List.of("acl " + acl + " " + c.type() + " " + v);
      case "path_reg" -> List.of("acl " + acl + " path_reg '" + c.value().getFirst() + "'");
      case "header" -> List.of("acl " + acl + " req.hdr(" + c.name() + ") -m str " + v);
      default -> throw new IllegalArgumentException("condição desconhecida " + c.type());
    };
  }

  /** Formato logfmt (chave=valor) dos logs de acesso: legível, e o Loki ("| logfmt") e o OTel interpretam sem regex. */
  static final String HTTP_LOG_FORMAT = "client=%ci:%cp fe=%ft be=%b srv=%s method=%HM host=\\\"%[var(txn.hapm_host)]\\\" path=%{+Q}HP status=%ST "
      + "bytes_in=%U bytes_out=%B t_req=%TR t_wait=%Tw t_conn=%Tc t_resp=%Tr t_total=%Ta term=%tsc retries=%rc conns=%ac tls=%sslv";
  static final String TCP_LOG_FORMAT = "client=%ci:%cp fe=%ft be=%b srv=%s bytes_in=%U bytes_out=%B t_wait=%Tw t_conn=%Tc t_total=%Tt term=%ts "
      + "retries=%rc conns=%ac";

  static void accessLog(List<String> L, Frontend f, ClusterSettings.Logs logs) {
    if (logs == null || !logs.on()) return;
    if (f.isHttp()) {
      L.add(ind("http-request set-var(txn.hapm_host) req.hdr(host),field(1,:),lower,json(utf8s)"));
      var fmt = HTTP_LOG_FORMAT;
      if (!Boolean.FALSE.equals(logs.userAgent())) {
        L.add(ind("http-request set-var(txn.hapm_ua) req.fhdr(user-agent),json(utf8s)"));
        fmt += " ua=\\\"%[var(txn.hapm_ua)]\\\"";
      }
      L.add(ind("log-format \"" + fmt + "\""));
    } else {
      L.add(ind("log-format \"" + TCP_LOG_FORMAT + "\""));
    }
  }

  private static List<String> frontend(Frontend f, String acmeBackend) {
    return frontend(f, acmeBackend, null);
  }

  private static List<String> frontend(Frontend f, String acmeBackend, ClusterSettings.Logs logs) {
    var L = new ArrayList<String>();
    boolean http = f.isHttp();
    boolean hasSsl = f.binds().stream().anyMatch(Frontend.Bind::ssl);
    L.add("frontend " + f.name());
    if (!f.description().isEmpty()) L.add(ind("description " + f.description()));
    L.add(ind("mode " + f.mode()));
    for (var b : f.binds()) L.add(ind(bindLine(b, f)));
    if (f.maxconn() != null) L.add(ind("maxconn " + f.maxconn()));
    if (f.timeoutClient() != null) L.add(ind("timeout client " + f.timeoutClient()));
    L.add(ind(http ? "option httplog" : "option tcplog"));
    accessLog(L, f, logs);

    boolean useAcme = http && f.acme() && acmeBackend != null;
    if (useAcme) L.add(ind("acl hapm_acme path_beg /.well-known/acme-challenge/"));

    // allowlist de origem (o desafio ACME continua liberado para o Let's Encrypt validar)
    if (!f.allowCidrs().isEmpty()) {
      L.add(ind("acl hapm_allowed src " + String.join(" ", f.allowCidrs())));
      L.add(ind(http ? "http-request deny deny_status 403 if !hapm_allowed" + (useAcme ? " !hapm_acme" : "") : "tcp-request connection reject if !hapm_allowed"));
    }
    // rate limit por IP de origem
    var rl = f.rateLimit();
    if (rl.enabled()) {
      if (http) {
        L.add(ind("stick-table type ipv6 size 1m expire 2m store http_req_rate(" + rl.period() + ")"));
        L.add(ind("http-request track-sc0 src"));
        L.add(ind("http-request deny deny_status 429 if { sc_http_req_rate(0) gt " + rl.requests() + " }"));
      } else {
        L.add(ind("stick-table type ipv6 size 1m expire 2m store conn_rate(" + rl.period() + ")"));
        L.add(ind("tcp-request connection track-sc0 src"));
        L.add(ind("tcp-request connection reject if { sc_conn_rate(0) gt " + rl.requests() + " }"));
      }
    }
    if (http) {
      if (f.forwardFor()) L.add(ind("option forwardfor"));
      L.add(ind("http-request set-header X-Forwarded-Proto https if { ssl_fc }"));
      L.add(ind("http-request set-header X-Forwarded-Proto http if !{ ssl_fc }"));
      if (f.redirectToHttps()) L.add(ind("http-request redirect scheme https code 301 if !{ ssl_fc }" + (useAcme ? " !hapm_acme" : "")));
      if (f.hsts()) L.add(ind("http-response set-header Strict-Transport-Security \"max-age=63072000; includeSubDomains\" if { ssl_fc }"));
    }
    boolean needsInspect = !http && !hasSsl && f.rules().stream().anyMatch(r -> r.conditions().stream().anyMatch(c -> c.type().equals("sni")));
    if (needsInspect) {
      L.add(ind("tcp-request inspect-delay 5s"));
      L.add(ind("tcp-request content accept if { req_ssl_hello_type 1 }"));
    }
    var actions = new ArrayList<String>();
    var routes = new ArrayList<String>();
    for (int i = 0; i < f.rules().size(); i++) {
      var r = f.rules().get(i);
      var refs = new ArrayList<String>();
      if (!r.description().isEmpty()) L.add(ind("# regra " + (i + 1) + ": " + r.description()));
      for (int j = 0; j < r.conditions().size(); j++) {
        var c = r.conditions().get(j);
        var acl = "r" + (i + 1) + "_c" + (j + 1);
        for (var a : conditionAcls(acl, c, f)) L.add(ind(a));
        refs.add(c.negate() ? "!" + acl : acl);
      }
      var cond = refs.isEmpty() ? "" : " if " + String.join(" ", refs);
      switch (r.action()) {
        case "use_backend" -> routes.add("use_backend " + r.backend() + cond);
        case "deny" -> actions.add(http ? "http-request deny deny_status " + r.status() + cond : "tcp-request content reject" + cond);
        case "redirect" -> actions.add("http-request redirect " + r.redirectType() + " " + r.url() + " code " + r.code() + cond);
        case "set_header" -> actions.add(("response".equals(r.direction()) ? "http-response" : "http-request") + " set-header " + r.header() + " \"" + r.value() + "\"" + cond);
        case "del_header" -> actions.add(("response".equals(r.direction()) ? "http-response" : "http-request") + " del-header " + r.header() + cond);
        default -> {}
      }
    }
    actions.forEach(a -> L.add(ind(a)));
    if (useAcme) L.add(ind("use_backend " + ACME_BACKEND + " if hapm_acme"));
    routes.forEach(r -> L.add(ind(r)));
    if (!f.defaultBackend().isEmpty()) L.add(ind("default_backend " + f.defaultBackend()));
    extra(L, f.extra());
    return L;
  }

  private static void extra(List<String> L, String extra) {
    if (extra == null || extra.isBlank()) return;
    L.add(ind("# --- bloco avançado"));
    for (var line : extra.split("\n")) if (!line.isBlank()) L.add(ind(line.strip()));
  }

  private static List<String> backend(Backend b) {
    var L = new ArrayList<String>();
    var hc = b.healthCheck();
    L.add("backend " + b.name());
    if (!b.description().isEmpty()) L.add(ind("description " + b.description()));
    L.add(ind("mode " + b.mode()));
    L.add(ind("balance " + b.balance()));
    if (b.timeoutServer() != null) L.add(ind("timeout server " + b.timeoutServer()));
    if (b.timeoutConnect() != null) L.add(ind("timeout connect " + b.timeoutConnect()));
    if (hc.type().equals("http")) {
      L.add(ind("option httpchk"));
      L.add(ind("http-check send meth " + hc.method() + " uri " + hc.path() + " ver HTTP/1.1 hdr Host " + (hc.host().isEmpty() ? "localhost" : hc.host())));
      L.add(ind("http-check expect status " + hc.expectStatus()));
    }
    if (b.compression()) {
      L.add(ind("compression algo gzip"));
      L.add(ind("compression type text/html text/plain text/css text/javascript application/javascript application/json image/svg+xml"));
    }
    if (b.sticky().enabled()) L.add(ind("cookie " + b.sticky().cookie() + " insert indirect nocache httponly"));
    var ds = new ArrayList<String>();
    if (!hc.type().equals("none")) ds.add("check inter " + hc.interval() + " fall " + hc.fall() + " rise " + hc.rise());
    if (b.serverSsl().enabled()) {
      ds.add("required".equals(b.serverSsl().verify()) ? "ssl verify required ca-file @system-ca" : "ssl verify none");
      if (b.serverSsl().sni() && b.mode().equals("http")) ds.add("sni req.hdr(host),field(1,:)");
    }
    if (!ds.isEmpty()) L.add(ind("default-server " + String.join(" ", ds)));
    for (var s : b.servers()) {
      var line = new StringBuilder("server ").append(s.name()).append(' ').append(s.address()).append(':').append(s.port()).append(" weight ").append(s.weight());
      if (s.backup()) line.append(" backup");
      if (s.maxconn() != null) line.append(" maxconn ").append(s.maxconn());
      if (b.sticky().enabled()) line.append(" cookie ").append(s.name());
      if (s.disabled()) line.append(" disabled");
      L.add(ind(line.toString()));
    }
    extra(L, b.extra());
    return L;
  }

  public static String render(Snapshot s, int version, String generatedAt) {
    var errors = crossValidate(s);
    if (!errors.isEmpty()) throw new RenderException(errors);
    var cs = s.cluster().settings();
    var h = cs.haproxy();
    var t = h.timeouts();
    var acmeBackend = cs.acme() == null || cs.acme().backend().isEmpty() ? null : cs.acme().backend();
    var fes = enabled(s.frontends());
    var bes = s.backends().stream().sorted(Comparator.comparing(Backend::name)).toList();
    boolean needsResolvers = Stream.concat(
            bes.stream().flatMap(b -> b.servers().stream().map(Backend.Server::address)),
            acmeBackend == null ? Stream.empty() : Stream.of(acmeBackend.replaceAll(":\\d+$", "")))
        .anyMatch(a -> !Checks.isIp(a));

    var L = new ArrayList<String>();
    L.add("# ==========================================================================");
    L.add("# Gerado pelo HAPM (HAProxy HA Manager) - NÃO EDITE MANUALMENTE");
    L.add("# cluster: " + s.cluster().name() + "   versão: " + version + "   gerado em: " + generatedAt);
    L.add("# ==========================================================================");
    L.add("global");
    L.add(ind("log " + h.log()));
    var logs = s.cluster().settings().logs();
    if (logs != null && logs.on()) {
      // logs de acesso: OTel Collector local (-> Loki/SIEM) e destinos syslog adicionais, em RFC 5424 com o hostname do nó
      L.add(ind("log-send-hostname"));
      L.add(ind("log " + logs.collector() + " len 8192 format rfc5424 local1 info"));
      for (var target : logs.siem()) L.add(ind("log " + target + " len 8192 format rfc5424 local1 info"));
    }
    L.add(ind("maxconn " + h.maxconn()));
    if (h.nbthread() != null) L.add(ind("nbthread " + h.nbthread()));
    if (!h.user().isEmpty()) {
      L.add(ind("user " + h.user()));
      L.add(ind("group " + h.user()));
    }
    L.add(ind("stats socket " + h.statsSocket() + " mode 660 level admin expose-fd listeners"));
    L.add(ind("stats timeout 30s"));
    L.add(ind("ssl-default-bind-ciphersuites TLS_AES_128_GCM_SHA256:TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256"));
    L.add(ind("ssl-default-bind-ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305"));
    L.add(ind("ssl-default-bind-options ssl-min-ver " + h.sslMinVer() + " no-tls-tickets"));
    L.add(ind("ssl-default-server-options ssl-min-ver TLSv1.2"));
    extraRaw(L, h.globalExtra());
    L.add("");
    if (needsResolvers) {
      L.add("resolvers sysdns");
      L.add(ind("parse-resolv-conf"));
      L.add(ind("hold valid 10s"));
      L.add(ind("accepted_payload_size 8192"));
      L.add("");
    }
    L.add("defaults");
    L.add(ind("mode http"));
    L.add(ind("log global"));
    L.add(ind("option dontlognull"));
    L.add(ind("option redispatch"));
    L.add(ind("retries 3"));
    L.add(ind("timeout connect " + t.connect()));
    L.add(ind("timeout client " + t.client()));
    L.add(ind("timeout server " + t.server()));
    L.add(ind("timeout http-request " + t.httpRequest()));
    L.add(ind("timeout http-keep-alive " + t.httpKeepAlive()));
    L.add(ind("timeout queue " + t.queue()));
    L.add(ind("timeout tunnel " + t.tunnel()));
    L.add(ind("default-server init-addr last,libc,none" + (needsResolvers ? " resolvers sysdns" : "")));
    extraRaw(L, h.defaultsExtra());
    L.add("");
    // frontend interno: métricas Prometheus, health-check (keepalived/NLB) e página de stats
    L.add("frontend " + STATS_FRONTEND);
    L.add(ind("mode http"));
    L.add(ind("bind :" + h.statsPort()));
    L.add(ind("no log"));
    L.add(ind("http-request use-service prometheus-exporter if { path /metrics }"));
    L.add(ind("monitor-uri /healthz"));
    L.add(ind("stats enable"));
    L.add(ind("stats uri /stats"));
    L.add(ind("stats refresh 10s"));
    if (!h.statsUser().isEmpty() && !h.statsPassword().isEmpty()) L.add(ind("stats auth " + h.statsUser() + ":" + h.statsPassword()));
    L.add("");
    for (var f : fes) {
      L.addAll(frontend(f, acmeBackend, s.cluster().settings().logs()));
      L.add("");
    }
    if (acmeBackend != null) {
      L.add("backend " + ACME_BACKEND);
      L.add(ind("mode http"));
      L.add(ind("server manager " + acmeBackend));
      L.add("");
    }
    for (var b : bes) {
      L.addAll(backend(b));
      L.add("");
    }
    return String.join("\n", L);
  }

  private static void extraRaw(List<String> L, String extra) {
    if (extra == null) return;
    for (var line : extra.split("\n")) if (!line.isBlank()) L.add(ind(line.strip()));
  }
}
