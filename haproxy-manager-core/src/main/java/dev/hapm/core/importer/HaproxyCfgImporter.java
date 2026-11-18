package dev.hapm.core.importer;

import dev.hapm.core.model.Backend;
import dev.hapm.core.model.ClusterSettings;
import dev.hapm.core.model.Frontend;
import dev.hapm.core.validate.Checks;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converte um haproxy.cfg existente para o modelo do HAPM (listeners, backends, regras, timeouts). O que não tem
 * equivalente vai para o bloco "avançado" (extra) da seção, quando seguro, ou é listado em {@code warnings}.
 * Nada é aplicado aqui: o resultado passa pela mesma validação de qualquer cadastro.
 */
public final class HaproxyCfgImporter {
  private HaproxyCfgImporter() {}

  public record Result(ClusterSettings.Haproxy haproxy, List<Frontend> frontends, List<Backend> backends, List<String> warnings,
                       List<String> certificatesToUpload) {}

  // ------------------------------------------------------------------ tokens
  static List<String> tokens(String line) {
    var out = new ArrayList<String>();
    var cur = new StringBuilder();
    boolean q = false, any = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '\\' && i + 1 < line.length()) {
        cur.append(line.charAt(++i));
        any = true;
      } else if (ch == '"') {
        q = !q;
        any = true;
      } else if (ch == '#' && !q) {
        break;
      } else if (Character.isWhitespace(ch) && !q) {
        if (any) out.add(cur.toString());
        cur.setLength(0);
        any = false;
      } else {
        cur.append(ch);
        any = true;
      }
    }
    if (any) out.add(cur.toString());
    return out;
  }

  record Section(String type, String name, List<String> lines, int lineNo) {}

  static List<Section> sections(String cfg) {
    var out = new ArrayList<Section>();
    Section cur = null;
    int n = 0;
    for (var raw : cfg.split("\r?\n")) {
      n++;
      var t = tokens(raw);
      if (t.isEmpty()) continue;
      var kw = t.get(0).toLowerCase(Locale.ROOT);
      if (Set.of("global", "defaults", "frontend", "backend", "listen", "resolvers", "peers", "userlist", "program", "cache", "ring",
          "http-errors", "mailers").contains(kw) && !Character.isWhitespace(raw.charAt(0))) {
        cur = new Section(kw, t.size() > 1 ? t.get(1) : "", new ArrayList<>(), n);
        out.add(cur);
      } else if (cur != null) {
        cur.lines().add(raw.strip());
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ contexto da conversão
  static final class Ctx {
    final List<String> warnings = new ArrayList<>();
    final Set<String> certs = new java.util.LinkedHashSet<>();
    final Map<String, String> renamed = new LinkedHashMap<>();

    void warn(String where, String msg) {
      warnings.add(where + ": " + msg);
    }
  }

  static String safeName(String name, Ctx ctx, String where) {
    if (Checks.NAME.matcher(name).matches()) return name;
    var n = name.replaceAll("[^A-Za-z0-9_.-]", "-").replaceAll("^[^A-Za-z0-9]+", "");
    if (n.isEmpty()) n = "imp";
    if (n.length() > 63) n = n.substring(0, 63);
    ctx.renamed.put(name, n);
    ctx.warn(where, "nome \"" + name + "\" ajustado para \"" + n + "\"");
    return n;
  }

  /** linhas que podem ir para o bloco avançado (sem aspas/barra invertida: regra anti-injeção do HAPM) */
  static void extra(List<String> extra, String line, Ctx ctx, String where) {
    if (Checks.SAFE_LINE.matcher(line).matches() && !line.isBlank()) {
      extra.add(line);
      ctx.warn(where, "mantido no bloco avançado: " + line);
    } else {
      ctx.warn(where, "NÃO importado (contém aspas ou barra invertida; recadastre manualmente): " + line);
    }
  }

  // ------------------------------------------------------------------ ACLs
  /** Converte o critério de uma ACL para uma condição do HAPM; null se não houver equivalente. */
  static Frontend.Condition condition(List<String> t, boolean negate) {
    if (t.isEmpty()) return null;
    var crit = t.get(0);
    var vals = new ArrayList<String>();
    String matchMode = null;
    for (int i = 1; i < t.size(); i++) {
      var v = t.get(i);
      if (v.equals("-i")) continue;
      if (v.equals("-m") && i + 1 < t.size()) {
        matchMode = t.get(++i);
        continue;
      }
      if (v.startsWith("-")) return null; // -f arquivo, -n, etc.
      vals.add(v);
    }
    if (vals.isEmpty()) return null;
    var c = crit.toLowerCase(Locale.ROOT);
    String type = null, name = null;
    if (c.matches("(req\\.)?hdr\\(host\\)(,field\\(1,:\\))?") || c.equals("req.hdr(host),host_only")) {
      type = "host";
      if ("end".equals(matchMode)) vals.replaceAll(v -> v.startsWith(".") ? "*" + v : v);
      else if (matchMode != null && !"str".equals(matchMode)) return null;
    } else if (c.matches("hdr_end\\(host\\)")) {
      type = "host";
      vals.replaceAll(v -> v.startsWith(".") ? "*" + v : v);
      if (vals.stream().anyMatch(v -> !v.startsWith("*."))) return null;
    } else if (c.matches("hdr_dom\\(host\\)")) {
      type = "host";
      var expanded = new ArrayList<String>();
      for (var v : vals) {
        expanded.add(v);
        expanded.add("*." + v);
      }
      vals = expanded;
    } else if (c.equals("path_beg") || (c.equals("path") && "beg".equals(matchMode))) {
      type = "path_beg";
    } else if (c.equals("path_end") || (c.equals("path") && "end".equals(matchMode))) {
      type = "path_end";
    } else if (c.equals("path_reg") || (c.equals("path") && "reg".equals(matchMode))) {
      type = "path_reg";
    } else if (c.equals("path") && matchMode == null) {
      type = "path";
    } else if (c.equals("src")) {
      type = "src";
    } else if (c.equals("method")) {
      type = "method";
    } else if (c.matches("req[._]ssl_sni|ssl_fc_sni")) {
      type = "sni";
    } else if (c.matches("(req\\.)?hdr\\(([A-Za-z0-9-]+)\\)") && matchMode == null) {
      type = "header";
      name = c.replaceAll("^(req\\.)?hdr\\(|\\)$", "");
    }
    if (type == null) return null;
    return new Frontend.Condition(type, name, vals, negate ? true : null);
  }

  /** Condição "if/unless ..." -> lista AND de condições; null se não for convertível (OR, ACL desconhecida...). */
  static List<Frontend.Condition> expr(List<String> t, int from, Map<String, List<String>> acls, Ctx ctx) {
    if (from >= t.size()) return List.of();
    var kw = t.get(from);
    if (!kw.equals("if") && !kw.equals("unless")) return null;
    boolean unless = kw.equals("unless");
    var out = new ArrayList<Frontend.Condition>();
    for (int i = from + 1; i < t.size(); i++) {
      var tok = t.get(i);
      if (tok.equals("||") || tok.equalsIgnoreCase("or")) return null;
      boolean neg = false;
      if (tok.startsWith("!")) {
        neg = true;
        tok = tok.substring(1);
        if (tok.isEmpty() && i + 1 < t.size()) tok = t.get(++i);
      }
      List<String> def;
      if (tok.equals("{")) {
        def = new ArrayList<>();
        while (++i < t.size() && !t.get(i).equals("}")) def.add(t.get(i));
      } else {
        def = acls.get(tok);
        if (def == null) return null;
      }
      var c = condition(def, neg != unless);
      if (c == null) return null;
      out.add(c);
    }
    if (unless && out.size() > 1) return null; // "unless A B" = NÃO(A E B): não é uma lista E
    return out;
  }

  // ------------------------------------------------------------------ frontend
  static Frontend frontend(String name, List<String> lines, String forcedBackend, Ctx ctx, String where) {
    String mode = null, defaultBackend = forcedBackend, description = null, timeoutClient = null;
    Integer maxconn = null;
    boolean redirect = false, hsts = false, http2 = false, forwardFor = false;
    var binds = new ArrayList<Frontend.Bind>();
    var rules = new ArrayList<Frontend.Rule>();
    var extra = new ArrayList<String>();
    var acls = new LinkedHashMap<String, List<String>>();
    var badAcls = new HashSet<String>();
    // 1ª passada: ACLs nomeadas (definições repetidas = OU entre valores, só se o critério for o mesmo)
    for (var l : lines) {
      var t = tokens(l);
      if (t.size() >= 3 && t.get(0).equals("acl")) {
        var def = new ArrayList<>(t.subList(2, t.size()));
        var prev = acls.get(t.get(1));
        if (prev == null) acls.put(t.get(1), def);
        else if (prev.get(0).equals(def.get(0))) prev.addAll(def.subList(1, def.size()));
        else badAcls.add(t.get(1));
      }
    }
    badAcls.forEach(acls::remove);
    for (var l : lines) {
      var t = tokens(l);
      if (t.isEmpty()) continue;
      var kw = t.get(0);
      switch (kw) {
        case "acl" -> {
          if (badAcls.contains(t.get(1))) ctx.warn(where, "ACL \"" + t.get(1) + "\" com critérios diferentes não convertida");
        }
        case "mode" -> mode = t.size() > 1 ? t.get(1) : null;
        case "description" -> description = String.join(" ", t.subList(1, t.size()));
        case "maxconn" -> maxconn = parseInt(t, 1);
        case "default_backend" -> defaultBackend = t.size() > 1 ? t.get(1) : null;
        case "bind" -> {
          for (var addr : t.get(1).split(",")) {
            var b = bind(addr, t, ctx, where);
            if (b != null) binds.add(b);
          }
          if (t.contains("alpn")) {
            var a = t.get(t.indexOf("alpn") + 1);
            if (a.contains("h2")) http2 = true;
          }
        }
        case "option" -> {
          var o = t.size() > 1 ? t.get(1) : "";
          if (o.equals("forwardfor")) forwardFor = true;
          else if (!Set.of("httplog", "tcplog", "dontlognull", "http-server-close", "http-keep-alive").contains(o)) extra(extra, l, ctx, where);
        }
        case "timeout" -> {
          if (t.size() > 2 && t.get(1).equals("client")) timeoutClient = t.get(2);
          else extra(extra, l, ctx, where);
        }
        case "use_backend" -> {
          var cond = t.size() > 2 ? expr(t, 2, acls, ctx) : List.<Frontend.Condition>of();
          if (cond == null || cond.isEmpty()) extra(extra, l, ctx, where);
          else rules.add(new Frontend.Rule(null, cond, "use_backend", t.get(1), null, null, null, null, null, null, null));
        }
        case "redirect" -> {
          if (t.size() > 2 && t.get(1).equals("scheme") && t.get(2).equals("https")) redirect = true;
          else extra(extra, l, ctx, where);
        }
        case "http-request" -> httpRequest(t, l, acls, rules, extra, ctx, where);
        case "http-response" -> {
          if (t.size() > 3 && t.get(1).equals("set-header") && t.get(2).equalsIgnoreCase("Strict-Transport-Security")) hsts = true;
          else if (t.size() > 3 && t.get(1).equals("set-header") && t.size() == 4) {
            rules.add(new Frontend.Rule(null, List.of(), "set_header", null, null, null, null, null, t.get(2), t.get(3), "response"));
          } else extra(extra, l, ctx, where);
        }
        case "log", "capture", "stick-table", "tcp-request", "http-after-response", "errorfile", "monitor-uri", "unique-id-format",
             "unique-id-header", "compression", "filter", "declare" -> extra(extra, l, ctx, where);
        default -> extra(extra, l, ctx, where);
      }
    }
    // "http-request redirect scheme https" vira a opção "Redirecionar HTTP→HTTPS" do listener
    if (rules.removeIf(r -> "redirect".equals(r.action()) && "scheme".equals(r.redirectType()) && "https".equals(r.url()))) redirect = true;
    var m = mode == null ? "http" : mode;
    return new Frontend(name, true, description, m, binds, defaultBackend, redirect ? true : null, hsts ? true : null, "http".equals(m) ? http2 : null,
        forwardFor, false, null, null, maxconn, timeoutClient, rules, extra.isEmpty() ? null : String.join("\n", extra));
  }

  static void httpRequest(List<String> t, String l, Map<String, List<String>> acls, List<Frontend.Rule> rules, List<String> extra, Ctx ctx,
                          String where) {
    if (t.size() < 2) {
      extra(extra, l, ctx, where);
      return;
    }
    var act = t.get(1);
    if (act.equals("redirect") && t.size() > 3 && t.get(2).equals("scheme") && t.get(3).equals("https")) {
      // normalmente "unless { ssl_fc }": é a opção "Redirecionar HTTP→HTTPS" do listener
      rules.add(new Frontend.Rule(null, List.of(), "redirect", null, "scheme", "https", null, null, null, null, null));
      return;
    }
    int condAt = Math.max(t.indexOf("if"), t.indexOf("unless"));
    var cond = condAt < 0 ? List.<Frontend.Condition>of() : expr(t, condAt, acls, ctx);
    if (cond == null) {
      extra(extra, l, ctx, where);
      return;
    }
    var args = t.subList(2, condAt < 0 ? t.size() : condAt);
    switch (act) {
      case "deny" -> {
        Integer status = 403;
        int i = args.indexOf("deny_status");
        if (i >= 0 && i + 1 < args.size()) status = Integer.parseInt(args.get(i + 1));
        rules.add(new Frontend.Rule(null, cond, "deny", null, null, null, null, status, null, null, null));
      }
      case "redirect" -> {
        if (args.size() >= 2 && args.get(0).equals("scheme") && args.get(1).equals("https")) {
          // tratado como "Redirecionar HTTP→HTTPS" do listener
          rules.add(new Frontend.Rule(null, List.of(), "redirect", null, "scheme", "https", null, null, null, null, null));
        } else if (args.size() >= 2 && Set.of("location", "prefix", "scheme").contains(args.get(0))) {
          int ci = args.indexOf("code");
          rules.add(new Frontend.Rule(null, cond, "redirect", null, args.get(0), args.get(1), ci >= 0 && ci + 1 < args.size() ? args.get(ci + 1) : "302",
              null, null, null, null));
        } else extra(extra, l, ctx, where);
      }
      case "set-header" -> {
        if (args.size() == 2 && args.get(0).equalsIgnoreCase("X-Forwarded-Proto")) return; // o HAPM já gera com "X-Forwarded-For"
        if (args.size() == 2) rules.add(new Frontend.Rule(null, cond, "set_header", null, null, null, null, null, args.get(0), args.get(1), "request"));
        else extra(extra, l, ctx, where);
      }
      case "del-header" -> {
        if (args.size() == 1) rules.add(new Frontend.Rule(null, cond, "del_header", null, null, null, null, null, args.get(0), null, "request"));
        else extra(extra, l, ctx, where);
      }
      default -> extra(extra, l, ctx, where);
    }
  }

  static Frontend.Bind bind(String addr, List<String> t, Ctx ctx, String where) {
    var a = addr.replaceFirst("^ipv[46]@", "");
    int colon = a.lastIndexOf(':');
    if (colon < 0) {
      ctx.warn(where, "bind sem porta ignorado: " + addr);
      return null;
    }
    var host = a.substring(0, colon).replaceAll("^\\[|\\]$", "");
    var portStr = a.substring(colon + 1);
    if (!portStr.matches("\\d+")) {
      ctx.warn(where, "bind com faixa de portas não suportado: " + addr);
      return null;
    }
    if (host.isEmpty() || host.equals("0.0.0.0")) host = "*";
    boolean ssl = t.contains("ssl");
    if (ssl) {
      int i = t.indexOf("crt");
      if (i >= 0 && i + 1 < t.size()) ctx.certs.add(t.get(i + 1));
      int j = t.indexOf("crt-list");
      if (j >= 0 && j + 1 < t.size()) ctx.certs.add(t.get(j + 1) + " (crt-list)");
    }
    return new Frontend.Bind(host, Integer.parseInt(portStr), ssl ? true : null);
  }

  // ------------------------------------------------------------------ backend
  static Backend backend(String name, List<String> lines, Ctx ctx, String where) {
    String mode = null, balance = null, timeoutServer = null, timeoutConnect = null, description = null;
    String hcType = null, hcPath = null, hcMethod = null, hcHost = null, hcExpect = null, hcInter = null;
    Integer hcFall = null, hcRise = null;
    boolean anyCheck = false, sslToServers = false, sni = false, compression = false;
    String verify = null, cookie = null;
    var servers = new ArrayList<Backend.Server>();
    var extra = new ArrayList<String>();
    for (var l : lines) {
      var t = tokens(l);
      if (t.isEmpty()) continue;
      switch (t.get(0)) {
        case "mode" -> mode = t.size() > 1 ? t.get(1) : null;
        case "description" -> description = String.join(" ", t.subList(1, t.size()));
        case "balance" -> {
          var b = t.size() > 1 ? t.get(1) : "";
          if (Set.of("roundrobin", "leastconn", "source", "first", "uri", "random").contains(b) && t.size() == 2) balance = b;
          else extra(extra, l, ctx, where);
        }
        case "timeout" -> {
          if (t.size() > 2 && t.get(1).equals("server")) timeoutServer = t.get(2);
          else if (t.size() > 2 && t.get(1).equals("connect")) timeoutConnect = t.get(2);
          else extra(extra, l, ctx, where);
        }
        case "option" -> {
          var o = t.size() > 1 ? t.get(1) : "";
          if (o.equals("httpchk")) {
            hcType = "http";
            var rest = t.subList(2, t.size());
            if (rest.size() == 1) hcPath = rest.get(0);
            else if (rest.size() >= 2) {
              hcMethod = rest.get(0);
              hcPath = rest.get(1);
            }
          } else if (o.equals("tcp-check")) {
            hcType = "tcp";
          } else if (!Set.of("httplog", "tcplog", "redispatch", "forwardfor").contains(o)) {
            extra(extra, l, ctx, where);
          } else if (o.equals("forwardfor")) {
            ctx.warn(where, "\"option forwardfor\" no backend: ative X-Forwarded-For no listener");
          }
        }
        case "http-check" -> {
          if (t.size() > 1 && t.get(1).equals("send")) {
            hcType = "http";
            for (int i = 2; i + 1 < t.size(); i++) {
              if (t.get(i).equals("meth")) hcMethod = t.get(i + 1);
              if (t.get(i).equals("uri")) hcPath = t.get(i + 1);
              if (t.get(i).equals("hdr") && i + 2 < t.size() && t.get(i + 1).equalsIgnoreCase("host")) hcHost = t.get(i + 2);
            }
          } else if (t.size() > 3 && t.get(1).equals("expect") && t.get(2).equals("status")) {
            hcExpect = t.get(3);
          } else extra(extra, l, ctx, where);
        }
        case "default-server" -> {
          for (int i = 1; i < t.size(); i++) {
            switch (t.get(i)) {
              case "inter" -> hcInter = i + 1 < t.size() ? t.get(++i) : null;
              case "fall" -> hcFall = parseInt(t, ++i);
              case "rise" -> hcRise = parseInt(t, ++i);
              case "check" -> anyCheck = true;
              default -> ctx.warn(where, "default-server: opção \"" + t.get(i) + "\" ignorada");
            }
          }
        }
        case "cookie" -> {
          if (t.size() > 2 && t.contains("insert")) cookie = t.get(1);
          else extra(extra, l, ctx, where);
        }
        case "compression" -> {
          if (t.size() > 2 && t.get(1).equals("algo") && t.contains("gzip")) compression = true;
          else if (!(t.size() > 1 && t.get(1).equals("type"))) extra(extra, l, ctx, where);
        }
        case "server" -> {
          if (t.size() < 3) {
            ctx.warn(where, "server incompleto ignorado: " + l);
            continue;
          }
          var sname = safeName(t.get(1), ctx, where);
          var addr = t.get(2);
          Integer port = null;
          int colon = addr.lastIndexOf(':');
          if (colon > 0 && addr.substring(colon + 1).matches("\\d+")) {
            port = Integer.parseInt(addr.substring(colon + 1));
            addr = addr.substring(0, colon).replaceAll("^\\[|\\]$", "");
          } else {
            port = "tcp".equals(mode) ? null : 80;
            ctx.warn(where, "servidor " + sname + " sem porta: usando " + (port == null ? "(defina a porta)" : port));
          }
          Integer weight = null, maxconn = null;
          boolean backup = false, disabled = false;
          for (int i = 3; i < t.size(); i++) {
            switch (t.get(i)) {
              case "weight" -> weight = parseInt(t, ++i);
              case "maxconn" -> maxconn = parseInt(t, ++i);
              case "backup" -> backup = true;
              case "disabled" -> disabled = true;
              case "check" -> anyCheck = true;
              case "ssl" -> sslToServers = true;
              case "verify" -> verify = i + 1 < t.size() ? t.get(++i) : null;
              case "sni" -> {
                sni = true;
                i++;
              }
              case "inter" -> hcInter = i + 1 < t.size() ? t.get(++i) : null;
              case "fall" -> hcFall = parseInt(t, ++i);
              case "rise" -> hcRise = parseInt(t, ++i);
              case "cookie", "ca-file", "crt", "port", "addr" -> i++; // o HAPM gera / não suportado
              case "resolvers", "init-addr", "resolve-prefer", "agent-port", "agent-inter", "slowstart", "on-marked-down", "observe",
                   "error-limit", "proto", "send-proxy", "send-proxy-v2", "check-ssl", "alpn", "pool-max-conn", "pool-purge-delay" -> {
                ctx.warn(where, "servidor " + sname + ": opção \"" + t.get(i) + "\" ignorada");
                if (!Set.of("send-proxy", "send-proxy-v2", "check-ssl").contains(t.get(i))) i++;
              }
              default -> ctx.warn(where, "servidor " + sname + ": opção \"" + t.get(i) + "\" ignorada");
            }
          }
          servers.add(new Backend.Server(sname, addr, port, weight, backup ? true : null, maxconn, disabled ? true : null));
        }
        default -> extra(extra, l, ctx, where);
      }
    }
    if (hcType == null) hcType = anyCheck ? "tcp" : "none";
    var hc = new Backend.HealthCheck(hcType, hcPath, hcMethod, hcHost, hcExpect, hcInter, hcFall, hcRise);
    return new Backend(name, description, mode == null ? "http" : mode, balance, servers, hc,
        cookie == null ? null : new Backend.Sticky(true, cookie),
        sslToServers ? new Backend.ServerSsl(true, "required".equals(verify) ? "required" : "none", sni) : null,
        compression ? true : null, timeoutServer, timeoutConnect, extra.isEmpty() ? null : String.join("\n", extra));
  }

  static Integer parseInt(List<String> t, int i) {
    try {
      return i < t.size() ? Integer.valueOf(t.get(i)) : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // ------------------------------------------------------------------ listen: separa frontend e backend
  static final Set<String> BACKEND_KW = Set.of("server", "balance", "default-server", "cookie", "http-check", "compression");

  // ------------------------------------------------------------------ principal
  public static Result convert(String cfg) {
    var ctx = new Ctx();
    var fes = new ArrayList<Frontend>();
    var bes = new ArrayList<Backend>();
    Integer maxconn = null, nbthread = null;
    String log = null, user = null, statsSocket = null, sslMinVer = null;
    String tConnect = null, tClient = null, tServer = null, tReq = null, tKa = null, tQueue = null, tTunnel = null;
    var usedNames = new HashSet<String>();
    for (var s : sections(cfg)) {
      var where = s.type() + (s.name().isEmpty() ? "" : " " + s.name()) + " (linha " + s.lineNo() + ")";
      switch (s.type()) {
        case "global" -> {
          for (var l : s.lines()) {
            var t = tokens(l);
            if (t.isEmpty()) continue;
            switch (t.get(0)) {
              case "maxconn" -> maxconn = parseInt(t, 1);
              case "nbthread" -> nbthread = parseInt(t, 1);
              case "user" -> user = t.size() > 1 ? t.get(1) : null;
              case "log" -> {
                if (log == null) log = String.join(" ", t.subList(1, t.size()));
              }
              case "stats" -> {
                if (t.size() > 2 && t.get(1).equals("socket")) statsSocket = t.get(2);
              }
              case "ssl-default-bind-options" -> {
                int i = t.indexOf("ssl-min-ver");
                if (i >= 0 && i + 1 < t.size()) sslMinVer = t.get(i + 1);
              }
              case "group", "daemon", "chroot", "pidfile", "master-worker", "ca-base", "crt-base", "ssl-default-bind-ciphers",
                   "ssl-default-bind-ciphersuites", "ssl-default-server-options", "ssl-default-server-ciphers", "tune.ssl.default-dh-param" -> {
                // o HAPM gera estes valores
              }
              default -> ctx.warn(where, "ignorado (seção global é gerada pelo HAPM; use \"Linhas extras em global\" se necessário): " + l);
            }
          }
        }
        case "defaults" -> {
          for (var l : s.lines()) {
            var t = tokens(l);
            if (t.size() > 2 && t.get(0).equals("timeout")) {
              var v = t.get(2);
              switch (t.get(1)) {
                case "connect" -> tConnect = v;
                case "client" -> tClient = v;
                case "server" -> tServer = v;
                case "http-request" -> tReq = v;
                case "http-keep-alive" -> tKa = v;
                case "queue" -> tQueue = v;
                case "tunnel" -> tTunnel = v;
                default -> ctx.warn(where, "timeout ignorado: " + l);
              }
            } else if (!t.isEmpty() && !Set.of("mode", "log", "option", "retries").contains(t.get(0))) {
              ctx.warn(where, "ignorado (defaults é gerado pelo HAPM; use \"Linhas extras em defaults\"): " + l);
            }
          }
        }
        case "frontend" -> {
          var n = unique(safeName(s.name(), ctx, where), usedNames);
          fes.add(frontend(n, s.lines(), null, ctx, where));
        }
        case "backend" -> {
          var n = unique(safeName(s.name(), ctx, where), usedNames);
          bes.add(backend(n, s.lines(), ctx, where));
        }
        case "listen" -> {
          var fname = unique(safeName(s.name(), ctx, where), usedNames);
          var bname = unique(fname + "-be", usedNames);
          var feLines = new ArrayList<String>();
          var beLines = new ArrayList<String>();
          for (var l : s.lines()) {
            var t = tokens(l);
            if (t.isEmpty()) continue;
            boolean be = BACKEND_KW.contains(t.get(0)) || (t.get(0).equals("option") && t.size() > 1 && Set.of("httpchk", "tcp-check").contains(t.get(1)))
                || (t.get(0).equals("timeout") && t.size() > 1 && Set.of("server", "connect").contains(t.get(1)));
            if (t.get(0).equals("mode")) {
              feLines.add(l);
              beLines.add(l);
            } else (be ? beLines : feLines).add(l);
          }
          if (s.name().equals("stats") || feLines.stream().anyMatch(l -> l.startsWith("stats "))) {
            ctx.warn(where, "seção de estatísticas ignorada (o HAPM expõe stats/métricas na porta configurada)");
            usedNames.remove(fname);
            usedNames.remove(bname);
            continue;
          }
          fes.add(frontend(fname, feLines, bname, ctx, where));
          bes.add(backend(bname, beLines, ctx, where));
          ctx.warn(where, "listen dividido em listener \"" + fname + "\" e backend \"" + bname + "\"");
        }
        default -> ctx.warn(where, "seção \"" + s.type() + "\" não suportada: recadastre manualmente");
      }
    }
    // referências a backends renomeados
    if (!ctx.renamed.isEmpty()) {
      var fixed = new ArrayList<Frontend>();
      for (var f : fes) {
        var rules = f.rules() == null ? null : f.rules().stream().map(r -> r.backend() == null ? r
            : new Frontend.Rule(r.description(), r.conditions(), r.action(), ctx.renamed.getOrDefault(r.backend(), r.backend()), r.redirectType(), r.url(),
                r.code(), r.status(), r.header(), r.value(), r.direction())).toList();
        fixed.add(new Frontend(f.name(), f.enabled(), f.description(), f.mode(), f.binds(),
            f.defaultBackend() == null ? null : ctx.renamed.getOrDefault(f.defaultBackend(), f.defaultBackend()), f.redirectToHttps(), f.hsts(), f.http2(),
            f.forwardFor(), f.acme(), f.allowCidrs(), f.rateLimit(), f.maxconn(), f.timeoutClient(), rules, f.extra()));
      }
      fes = fixed;
    }
    for (var c : ctx.certs) ctx.warn("certificados", "envie o PEM de " + c + " na aba Certificados (arquivos do servidor não são importados)");
    var timeouts = new ClusterSettings.Timeouts(tConnect, tClient, tServer, tReq, tKa, tQueue, tTunnel);
    var haproxy = new ClusterSettings.Haproxy(maxconn, nbthread, log, user, statsSocket, null, null, null, sslMinVer, null, timeouts, null, null);
    return new Result(haproxy, fes, bes, ctx.warnings, List.copyOf(ctx.certs));
  }

  static String unique(String name, Set<String> used) {
    var n = name;
    int i = 2;
    while (!used.add(n)) n = name + "-" + i++;
    return n;
  }
}
