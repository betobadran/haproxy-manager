package dev.hapm.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * O que o agente baixa e aplica: arquivos da release do HAProxy (haproxy.cfg, certs.list, certs/*.pem)
 * e, quando o cluster usa VRRP, os arquivos do keepalived.
 */
public record Bundle(Integer version, String cluster, Map<String, String> files, Map<String, String> keepalived, String sha256) {

  /** Hash canônico (independente da ordem do JSON) usado para checar integridade no agente. */
  public static String digest(int version, Map<String, String> files, Map<String, String> keepalived) {
    try {
      var md = MessageDigest.getInstance("SHA-256");
      md.update(("v" + version + "\n").getBytes(StandardCharsets.UTF_8));
      for (var group : new Map[] {files, keepalived == null ? Map.of() : keepalived}) {
        @SuppressWarnings("unchecked")
        var sorted = new TreeMap<String, String>((Map<String, String>) group);
        for (var e : sorted.entrySet()) {
          md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
          md.update((byte) 0);
          md.update(e.getValue().getBytes(StandardCharsets.UTF_8));
          md.update((byte) 0);
        }
        md.update((byte) 1);
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public boolean verify() {
    return sha256 == null || sha256.equals(digest(version, files, keepalived));
  }
}
