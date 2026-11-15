package dev.hapm.core.validate;

import static dev.hapm.core.validate.Checks.HEADER_NAME;
import static dev.hapm.core.validate.Checks.HOSTNAME;
import static dev.hapm.core.validate.Checks.IFACE;
import static dev.hapm.core.validate.Checks.NAME;
import static dev.hapm.core.validate.Checks.TOKEN;

import dev.hapm.core.model.Backend;
import dev.hapm.core.model.Certificate;
import dev.hapm.core.model.ClusterSettings;
import dev.hapm.core.model.Frontend;
import dev.hapm.core.model.NodeSettings;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Valida e completa com padrões tudo o que vira configuração. Recebe objetos "soltos" (vindos do JSON
 * da API, com campos nulos) e devolve objetos completos ou lança {@link ValidationException}.
 */
public final class Normalizer {
  private Normalizer() {}

  public static final List<String> MODES = List.of(ClusterSettings.ONPREM_KEEPALIVED, ClusterSettings.AWS_NLB, ClusterSettings.AWS_EIP);
  private static final List<String> HTTP_COND = List.of("host", "path", "path_beg", "path_end", "path_reg", "method", "src", "header");
  private static final List<String> TCP_COND = List.of("sni", "src");
  private static final List<String> HTTP_ACTIONS = List.of("use_backend", "redirect", "deny", "set_header", "del_header");
  private static final List<String> TCP_ACTIONS = List.of("use_backend", "deny");
  private static final Pattern REGION = Pattern.compile("^[a-z]{2}(-[a-z]+)+-\\d$");
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@\"'<>]+@[^\\s@\"'<>]+$");

  private static <T> T or(T v, T def) {
    return v == null ? def : v;
  }

  private static <T> List<T> list(List<T> v) {
    return v == null ? List.of() : v.stream().filter(Objects::nonNull).toList();
  }

  public static String randomAuthPass() {
    var b = new byte[6];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b).substring(0, 8);
  }

  // ------------------------------------------------------------------ cluster
  public record ClusterInput(String name, ClusterSettings settings) {}

  public static ClusterInput cluster(String name, ClusterSettings in, ClusterSettings previous) {
    var c = new Checks.Ctx();
    var s = or(in, new ClusterSettings(null, null, null, null, null, null, null));
    var h = or(s.haproxy(), new ClusterSettings.Haproxy(null, null, null, null, null, null, null, null, null, null, null, null, null));
    var t = or(h.timeouts(), new ClusterSettings.Timeouts(null, null, null, null, null, null, null));
    var k = or(s.keepalived(), new ClusterSettings.Keepalived(null, null, null, null, null, null, null, null, null, null));
    var a = or(s.aws(), new ClusterSettings.Aws(null, null, null));
    var acme = or(s.acme(), new ClusterSettings.Acme(null, null));
    var prevPass = previous != null && previous.keepalived() != null ? previous.keepalived().authPass() : null;

    var outName = c.name("name", name, false);
    var mode = c.oneOf("settings.mode", s.mode(), MODES, ClusterSettings.ONPREM_KEEPALIVED);
    var haproxy = new ClusterSettings.Haproxy(
        c.integer("haproxy.maxconn", h.maxconn(), 100, 10_000_000, 50000),
        c.integer("haproxy.nbthread", h.nbthread(), 1, 256, null),
        c.safeLine("haproxy.log", h.log(), "/dev/log local0"),
        h.user() != null && h.user().isBlank() ? "" : c.name("haproxy.user", or(h.user(), "haproxy"), false),
        c.safeLine("haproxy.statsSocket", h.statsSocket(), "/run/haproxy/admin.sock"),
        c.integer("haproxy.statsPort", h.statsPort(), 1, 65535, 8405),
        c.str(h.statsUser()),
        c.str(h.statsPassword()),
        c.oneOf("haproxy.sslMinVer", h.sslMinVer(), List.of("TLSv1.2", "TLSv1.3"), "TLSv1.2"),
        c.str(h.defaultCertificate()),
        new ClusterSettings.Timeouts(
            c.duration("timeouts.connect", t.connect(), "5s"),
            c.duration("timeouts.client", t.client(), "50s"),
            c.duration("timeouts.server", t.server(), "50s"),
            c.duration("timeouts.httpRequest", t.httpRequest(), "10s"),
            c.duration("timeouts.httpKeepAlive", t.httpKeepAlive(), "10s"),
            c.duration("timeouts.queue", t.queue(), "30s"),
            c.duration("timeouts.tunnel", t.tunnel(), "1h")),
        c.rawBlock("haproxy.globalExtra", h.globalExtra()),
        c.rawBlock("haproxy.defaultsExtra", h.defaultsExtra()));
    var keepalived = new ClusterSettings.Keepalived(
        c.str(k.iface(), "eth0"),
        c.integer("keepalived.routerId", k.routerId(), 1, 255, 51),
        c.cidrs("keepalived.vips", k.vips()),
        c.str(k.authPass(), prevPass != null ? prevPass : randomAuthPass()),
        c.integer("keepalived.advertInt", k.advertInt(), 1, 255, 1),
        c.bool(k.unicast(), true),
        c.bool(k.preempt(), true),
        c.integer("keepalived.checkInterval", k.checkInterval(), 1, 60, 2),
        c.integer("keepalived.checkFall", k.checkFall(), 1, 20, 2),
        c.integer("keepalived.checkRise", k.checkRise(), 1, 20, 2));
    var aws = new ClusterSettings.Aws(c.str(a.region()), c.str(a.eipAllocationId()), c.str(a.privateVip()));
    var outAcme = new ClusterSettings.Acme(c.str(acme.email()), c.str(acme.backend()));

    if (!IFACE.matcher(keepalived.iface()).matches()) c.err("keepalived.interface", "interface inválida");
    if (keepalived.authPass().isEmpty() || keepalived.authPass().isBlank() || !keepalived.authPass().matches("^[A-Za-z0-9_.@%+=-]{1,8}$"))
      c.err("keepalived.authPass", "até 8 caracteres alfanuméricos");
    if (!haproxy.statsUser().isEmpty() && !haproxy.statsUser().matches("^[A-Za-z0-9_.-]+$")) c.err("haproxy.statsUser", "inválido");
    if (!haproxy.statsPassword().isEmpty() && !TOKEN.matcher(haproxy.statsPassword()).matches()) c.err("haproxy.statsPassword", "sem espaços, aspas, \\ ou #");
    if (!haproxy.defaultCertificate().isEmpty() && !NAME.matcher(haproxy.defaultCertificate()).matches()) c.err("haproxy.defaultCertificate", "nome inválido");
    if (!aws.region().isEmpty() && !REGION.matcher(aws.region()).matches()) c.err("aws.region", "região inválida (ex.: us-east-1)");
    if (!aws.eipAllocationId().isEmpty() && !aws.eipAllocationId().matches("^eipalloc-[0-9a-f]+$")) c.err("aws.eipAllocationId", "formato eipalloc-xxxx");
    if (!aws.privateVip().isEmpty() && Checks.ipVersion(aws.privateVip()) != 4) c.err("aws.privateVip", "IPv4 inválido");
    if (!outAcme.backend().isEmpty()) {
      var m = Pattern.compile("^(.+):(\\d+)$").matcher(outAcme.backend());
      if (!m.matches() || !(Checks.isIp(m.group(1)) || HOSTNAME.matcher(m.group(1)).matches())) c.err("acme.backend", "formato host:porta");
    }
    if (!outAcme.email().isEmpty() && !EMAIL.matcher(outAcme.email()).matches()) c.err("acme.email", "e-mail inválido");
    if (ClusterSettings.AWS_EIP.equals(mode)) {
      if (aws.eipAllocationId().isEmpty() && aws.privateVip().isEmpty()) c.err("aws", "modo aws-eip exige eipAllocationId e/ou privateVip");
      if (aws.region().isEmpty()) c.err("aws.region", "obrigatório no modo aws-eip");
    }
    c.done();
    var settings = new ClusterSettings(
        mode,
        c.oneOf("settings.rollout", s.rollout(), List.of("sequential", "parallel"), "sequential"),
        c.bool(s.autoDeployOnCertRenew(), true),
        haproxy, keepalived, aws, outAcme);
    return new ClusterInput(outName, settings);
  }

  // ------------------------------------------------------------------ nó
  public static NodeSettings nodeSettings(NodeSettings in) {
    var c = new Checks.Ctx();
    var i = or(in, NodeSettings.EMPTY);
    var out = new NodeSettings(
        c.integer("priority", i.priority(), 1, 254, null), c.str(i.iface()), c.str(i.unicastAddress()), c.bool(i.disabled(), false));
    if (!out.iface().isEmpty() && !IFACE.matcher(out.iface()).matches()) c.err("interface", "interface inválida");
    if (!out.unicastAddress().isEmpty() && !Checks.isIp(out.unicastAddress())) c.err("unicastAddress", "IP inválido");
    c.done();
    return out;
  }

  // ------------------------------------------------------------------ frontend
  private static Frontend.Condition condition(Checks.Ctx c, String path, Frontend.Condition in, boolean http) {
    var type = c.oneOf(path + ".type", in.type(), http ? HTTP_COND : TCP_COND, http ? "host" : "sni");
    var negate = c.bool(in.negate(), false);
    if (type.equals("path_reg")) {
      var v = String.join(" ", list(in.value())).strip();
      if (v.isEmpty() || v.matches("(?s).*[\\r\\n'].*")) c.err(path + ".value", "regex obrigatória, sem aspas simples ou quebra de linha");
      return new Frontend.Condition(type, null, List.of(v), negate);
    }
    var values = type.equals("src") ? c.cidrs(path + ".value", in.value()) : c.tokens(path + ".value", in.value());
    if (values.isEmpty()) c.err(path + ".value", "obrigatório");
    if (type.equals("host") || type.equals("sni")) {
      values = values.stream().map(String::toLowerCase).toList();
      for (var v : values) if (!Checks.DOMAIN.matcher(v).matches()) c.err(path + ".value", "domínio inválido \"" + v + "\"");
    }
    if (type.equals("path") || type.equals("path_beg"))
      for (var v : values) if (!v.startsWith("/")) c.err(path + ".value", "caminho deve começar com \"/\" (\"" + v + "\")");
    if (type.equals("method")) values = values.stream().map(String::toUpperCase).toList();
    String name = null;
    if (type.equals("header")) {
      name = c.str(in.name());
      if (!HEADER_NAME.matcher(name).matches()) c.err(path + ".name", "nome de header inválido");
    }
    return new Frontend.Condition(type, name, values, negate);
  }

  private static Frontend.Rule rule(Checks.Ctx c, String path, Frontend.Rule in, boolean http) {
    var conds = new ArrayList<Frontend.Condition>();
    var src = list(in.conditions());
    for (int j = 0; j < src.size(); j++) conds.add(condition(c, path + ".conditions[" + j + "]", src.get(j), http));
    var action = c.oneOf(path + ".action", in.action(), http ? HTTP_ACTIONS : TCP_ACTIONS, "use_backend");
    if (conds.isEmpty() && action.equals("use_backend")) c.err(path + ".conditions", "use_backend sem condição: use o backend padrão");
    String backend = null, redirectType = null, url = null, code = null, header = null, value = null, direction = null;
    Integer status = null;
    switch (action) {
      case "use_backend" -> backend = c.name(path + ".backend", in.backend());
      case "redirect" -> {
        redirectType = c.oneOf(path + ".redirectType", in.redirectType(), List.of("location", "prefix", "scheme"), "location");
        url = c.str(in.url());
        if (redirectType.equals("scheme")) {
          if (!List.of("http", "https").contains(url)) c.err(path + ".url", "para scheme use http ou https");
        } else if (!TOKEN.matcher(url).matches()) c.err(path + ".url", "URL obrigatória, sem espaços/aspas");
        code = c.oneOf(path + ".code", in.code(), List.of("301", "302", "303", "307", "308"), "302");
      }
      case "deny" -> status = c.integer(path + ".status", in.status(), 200, 599, 403);
      case "set_header", "del_header" -> {
        header = c.str(in.header());
        if (!HEADER_NAME.matcher(header).matches()) c.err(path + ".header", "nome de header inválido");
        if (action.equals("set_header")) value = c.safeLine(path + ".value", in.value());
        direction = c.oneOf(path + ".direction", in.direction(), List.of("request", "response"), "request");
      }
      default -> {}
    }
    return new Frontend.Rule(c.safeLine(path + ".description", in.description()), conds, action, backend, redirectType, url, code, status, header, value, direction);
  }

  public static Frontend frontend(Frontend in) {
    var c = new Checks.Ctx();
    var mode = c.oneOf("mode", in.mode(), List.of("http", "tcp"), "http");
    var http = mode.equals("http");
    var binds = new ArrayList<Frontend.Bind>();
    var srcBinds = list(in.binds());
    for (int j = 0; j < srcBinds.size(); j++) {
      var b = srcBinds.get(j);
      binds.add(new Frontend.Bind(c.address("binds[" + j + "].address", b.address(), true), c.port("binds[" + j + "].port", b.port()), c.bool(b.ssl(), false)));
    }
    var rl = or(in.rateLimit(), new Frontend.RateLimit(null, null, null));
    var rules = new ArrayList<Frontend.Rule>();
    var srcRules = list(in.rules());
    for (int j = 0; j < srcRules.size(); j++) rules.add(rule(c, "rules[" + j + "]", srcRules.get(j), http));
    var out = new Frontend(
        c.name("name", in.name()),
        c.bool(in.enabled(), true),
        c.safeLine("description", in.description()),
        mode,
        binds,
        c.str(in.defaultBackend()),
        http && c.bool(in.redirectToHttps(), false),
        http && c.bool(in.hsts(), false),
        c.bool(in.http2(), true),
        http && c.bool(in.forwardFor(), true),
        http && c.bool(in.acme(), true),
        c.cidrs("allowCidrs", in.allowCidrs()),
        new Frontend.RateLimit(c.bool(rl.enabled(), false), c.integer("rateLimit.requests", rl.requests(), 1, 1_000_000, 100), c.duration("rateLimit.period", rl.period(), "10s")),
        c.integer("maxconn", in.maxconn(), 1, 10_000_000, null),
        c.duration("timeoutClient", in.timeoutClient(), null),
        rules,
        c.rawBlock("extra", in.extra()));
    if (binds.isEmpty()) c.err("binds", "pelo menos um bind é obrigatório");
    if (!out.defaultBackend().isEmpty() && !NAME.matcher(out.defaultBackend()).matches()) c.err("defaultBackend", "nome inválido");
    c.done();
    return out;
  }

  // ------------------------------------------------------------------ backend
  public static Backend backend(Backend in) {
    var c = new Checks.Ctx();
    var mode = c.oneOf("mode", in.mode(), List.of("http", "tcp"), "http");
    var hc = or(in.healthCheck(), new Backend.HealthCheck(null, null, null, null, null, null, null, null));
    var st = or(in.sticky(), new Backend.Sticky(null, null));
    var ssl = or(in.serverSsl(), new Backend.ServerSsl(null, null, null));
    var servers = new ArrayList<Backend.Server>();
    var src = list(in.servers());
    for (int j = 0; j < src.size(); j++) {
      var s = src.get(j);
      var p = "servers[" + j + "]";
      servers.add(new Backend.Server(
          c.name(p + ".name", s.name() == null || s.name().isBlank() ? "srv" + (j + 1) : s.name()),
          c.address(p + ".address", s.address(), false),
          c.port(p + ".port", s.port()),
          c.integer(p + ".weight", s.weight(), 0, 256, 100),
          c.bool(s.backup(), false),
          c.integer(p + ".maxconn", s.maxconn(), 1, 10_000_000, null),
          c.bool(s.disabled(), false)));
    }
    var health = new Backend.HealthCheck(
        c.oneOf("healthCheck.type", hc.type(), List.of("none", "tcp", "http"), mode.equals("http") ? "http" : "tcp"),
        c.str(hc.path(), "/"),
        c.oneOf("healthCheck.method", hc.method(), List.of("GET", "HEAD", "OPTIONS"), "GET"),
        c.str(hc.host()),
        c.str(hc.expectStatus(), "200-399"),
        c.duration("healthCheck.interval", hc.interval(), "2s"),
        c.integer("healthCheck.fall", hc.fall(), 1, 100, 3),
        c.integer("healthCheck.rise", hc.rise(), 1, 100, 2));
    var out = new Backend(
        c.name("name", in.name()),
        c.safeLine("description", in.description()),
        mode,
        c.oneOf("balance", in.balance(), List.of("roundrobin", "leastconn", "source", "first", "uri", "random"), "roundrobin"),
        servers,
        health,
        new Backend.Sticky(mode.equals("http") && c.bool(st.enabled(), false), c.str(st.cookie(), "HAPM_SRV")),
        new Backend.ServerSsl(c.bool(ssl.enabled(), false), c.oneOf("serverSsl.verify", ssl.verify(), List.of("none", "required"), "none"), c.bool(ssl.sni(), true)),
        mode.equals("http") && c.bool(in.compression(), false),
        c.duration("timeoutServer", in.timeoutServer(), null),
        c.duration("timeoutConnect", in.timeoutConnect(), null),
        c.rawBlock("extra", in.extra()));
    var seen = new HashSet<String>();
    for (var s : servers) if (!seen.add(s.name())) c.err("servers", "nome de servidor duplicado \"" + s.name() + "\"");
    if (health.type().equals("http")) {
      if (!health.path().matches("^/[^\\s\"'\\\\#]*$")) c.err("healthCheck.path", "caminho inválido");
      if (!health.host().isEmpty() && !HOSTNAME.matcher(health.host()).matches()) c.err("healthCheck.host", "host inválido");
      if (!health.expectStatus().matches("^\\d{3}(-\\d{3})?$")) c.err("healthCheck.expectStatus", "use \"200\" ou faixa \"200-399\"");
    }
    if (!out.sticky().cookie().matches("^[A-Za-z0-9_-]{1,32}$")) c.err("sticky.cookie", "nome de cookie inválido");
    c.done();
    return out;
  }

  // ------------------------------------------------------------------ certificado (o PEM é validado no manager)
  public static Certificate certificateHeader(Certificate in, Certificate existing) {
    var c = new Checks.Ctx();
    var name = c.name("name", in.name() != null ? in.name() : existing != null ? existing.name() : null);
    var source = c.oneOf("source", in.source() != null ? in.source() : existing != null ? existing.source() : null, List.of("upload", "acme"), "upload");
    Certificate.AcmeInfo acme = null;
    if (source.equals("acme")) {
      var a = in.acme() != null ? in.acme() : existing != null ? existing.acme() : null;
      var domains = c.tokens("acme.domains", a == null ? null : a.domains()).stream().map(String::toLowerCase).toList();
      if (domains.isEmpty()) c.err("acme.domains", "informe pelo menos um domínio");
      for (var d : domains) if (!Checks.DOMAIN.matcher(d).matches() || d.startsWith("*.")) c.err("acme.domains", "domínio inválido para HTTP-01: \"" + d + "\"");
      var prev = existing != null ? existing.acme() : null;
      acme = new Certificate.AcmeInfo(domains, c.str(a == null ? null : a.email()),
          prev == null ? null : prev.status(), prev == null ? null : prev.error(), prev == null ? null : prev.issuedAt(), prev == null ? null : prev.updatedAt());
    }
    c.done();
    return new Certificate(name, source, null, null, acme);
  }
}
