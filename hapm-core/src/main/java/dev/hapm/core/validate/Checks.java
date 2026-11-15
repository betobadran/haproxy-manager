package dev.hapm.core.validate;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validadores básicos. Regra de ouro: nenhum valor livre pode conter quebra de linha, aspas ou '#',
 * senão seria possível injetar diretivas arbitrárias no haproxy.cfg / keepalived.conf.
 */
public final class Checks {
  private Checks() {}

  public static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,62}$");
  public static final Pattern HOSTNAME =
      Pattern.compile("^(?=.{1,253}$)([A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9])?\\.)*[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$");
  public static final Pattern DOMAIN =
      Pattern.compile("^(\\*\\.)?([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$|^(\\*\\.)?[A-Za-z0-9-]+$");
  public static final Pattern TOKEN = Pattern.compile("^[^\\s\"'\\\\#]+$");
  public static final Pattern DURATION = Pattern.compile("^\\d+(us|ms|s|m|h|d)?$");
  public static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9!$%&*+.^_`|~-]+$");
  public static final Pattern SAFE_LINE = Pattern.compile("^[^\\r\\n\"\\\\]*$");
  public static final Pattern IFACE = Pattern.compile("^[A-Za-z0-9_.:-]{1,15}$");
  private static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
  private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]+$");
  private static final Pattern SECTION = Pattern.compile(
      "^(global|defaults|frontend|backend|listen|resolvers|peers|userlist|program|cache|ring|http-errors|mailers|vrrp_|virtual_server|global_defs)\\b.*");

  /** 4 = IPv4, 6 = IPv6, 0 = não é IP. Nunca faz DNS. */
  public static int ipVersion(String s) {
    if (s == null || s.isEmpty()) return 0;
    if (IPV4.matcher(s).matches()) return 4;
    if (s.contains(":") && IPV6_CHARS.matcher(s).matches()) {
      try {
        // com ':' o JDK trata como literal IPv6 e não consulta DNS
        return InetAddress.getByName(s) instanceof java.net.Inet6Address ? 6 : 0;
      } catch (java.net.UnknownHostException e) {
        return 0;
      }
    }
    return 0;
  }

  public static boolean isIp(String s) {
    return ipVersion(s) != 0;
  }

  public static boolean isCidr(String v) {
    var parts = v.split("/", -1);
    if (parts.length > 2) return false;
    int fam = ipVersion(parts[0]);
    if (fam == 0) return false;
    if (parts.length == 1) return true;
    try {
      int bits = Integer.parseInt(parts[1]);
      return bits >= 0 && bits <= (fam == 4 ? 32 : 128);
    } catch (NumberFormatException e) {
      return false;
    }
  }

  // ------------------------------------------------------------ contexto de erros
  public static final class Ctx {
    private final List<String> errors = new ArrayList<>();

    public void err(String path, String msg) {
      errors.add(path + ": " + msg);
    }

    public void done() {
      if (!errors.isEmpty()) throw new ValidationException(errors);
    }

    public String str(String v, String def) {
      return v == null ? def : v.strip();
    }

    public String str(String v) {
      return str(v, "");
    }

    public boolean bool(Boolean v, boolean def) {
      return v == null ? def : v;
    }

    public Integer integer(String path, Integer v, int min, int max, Integer def) {
      if (v == null) return def;
      if (v < min || v > max) {
        err(path, "deve ser inteiro entre " + min + " e " + max);
        return def;
      }
      return v;
    }

    public String name(String path, String v, boolean reserved) {
      var s = str(v);
      if (!NAME.matcher(s).matches()) err(path, "nome inválido (use letras, números, \"_\", \"-\", \".\"; máx. 63)");
      else if (reserved && s.startsWith("hapm_")) err(path, "prefixo \"hapm_\" é reservado");
      return s;
    }

    public String name(String path, String v) {
      return name(path, v, true);
    }

    public String oneOf(String path, String v, Collection<String> options, String def) {
      var s = v == null || v.isBlank() ? def : v.strip();
      if (!options.contains(s)) err(path, "deve ser um de: " + String.join(", ", options));
      return s;
    }

    public String duration(String path, String v, String def) {
      var s = str(v);
      if (s.isEmpty()) return def;
      if (!DURATION.matcher(s).matches()) err(path, "duração inválida (ex.: 500ms, 5s, 1m)");
      return s;
    }

    /** Lista de tokens; cada item pode conter vários valores separados por espaço/vírgula. */
    public List<String> tokens(String path, List<String> v) {
      var out = new ArrayList<String>();
      if (v == null) return out;
      for (var item : v) {
        if (item == null) continue;
        for (var t : item.strip().split("[\\s,]+")) {
          if (t.isEmpty()) continue;
          if (!TOKEN.matcher(t).matches()) err(path, "valor \"" + t + "\" contém caracteres não permitidos (espaço, aspas, \\ ou #)");
          out.add(t);
        }
      }
      return out;
    }

    public List<String> cidrs(String path, List<String> v) {
      var list = tokens(path, v);
      for (var c : list) if (!isCidr(c)) err(path, "\"" + c + "\" não é IP/CIDR válido");
      return list;
    }

    public String safeLine(String path, String v, String def) {
      var s = str(v, def);
      if (!SAFE_LINE.matcher(s).matches()) err(path, "não pode conter quebras de linha, aspas duplas ou \\");
      return s;
    }

    public String safeLine(String path, String v) {
      return safeLine(path, v, "");
    }

    public String address(String path, String v, boolean allowWildcard) {
      var s = str(v);
      if (allowWildcard && (s.isEmpty() || s.equals("*"))) return "*";
      if (isIp(s) || HOSTNAME.matcher(s).matches()) return s;
      err(path, "endereço inválido \"" + s + "\"");
      return s;
    }

    public Integer port(String path, Integer v) {
      if (v == null) {
        err(path, "obrigatório");
        return null;
      }
      return integer(path, v, 1, 65535, null);
    }

    /** Bloco "avançado" com linhas cruas: permitido, mas sem abrir novas seções. */
    public String rawBlock(String path, String v) {
      var s = str(v).replace("\r", "");
      if (s.isEmpty()) return "";
      var lines = s.split("\n");
      for (int i = 0; i < lines.length; i++) {
        if (SECTION.matcher(lines[i].strip()).matches()) err(path + "[linha " + (i + 1) + "]", "não é permitido abrir novas seções no bloco avançado");
      }
      return s;
    }
  }
}
