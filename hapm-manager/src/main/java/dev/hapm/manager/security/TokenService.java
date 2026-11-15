package dev.hapm.manager.security;

import dev.hapm.manager.HapmProperties;
import dev.hapm.manager.store.Store;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/** Token de sessão "usuario|expiração" assinado com HMAC-SHA256 (stateless: funciona com vários managers). */
@Service
public class TokenService {
  private static final long TTL_SEC = 12 * 3600;
  private final byte[] secret;

  public TokenService(HapmProperties props, Store store) {
    var s = props.secret();
    if (s == null || s.isBlank()) {
      s = store.getKv("session.secret").orElseGet(() -> {
        var gen = Store.randomSecret(32);
        store.setKv("session.secret", gen);
        return gen;
      });
    }
    this.secret = s.getBytes(StandardCharsets.UTF_8);
  }

  private String hmac(String body) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String sign(String username) {
    var body = Base64.getUrlEncoder().withoutPadding().encodeToString((username + "|" + (Instant.now().getEpochSecond() + TTL_SEC)).getBytes(StandardCharsets.UTF_8));
    return body + "." + hmac(body);
  }

  public Optional<String> verify(String token) {
    if (token == null) return Optional.empty();
    int dot = token.indexOf('.');
    if (dot < 0) return Optional.empty();
    var body = token.substring(0, dot);
    if (!MessageDigest.isEqual(hmac(body).getBytes(StandardCharsets.US_ASCII), token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII))) return Optional.empty();
    try {
      var parts = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8).split("\\|");
      if (Long.parseLong(parts[1]) < Instant.now().getEpochSecond()) return Optional.empty();
      return Optional.of(parts[0]);
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  public static boolean constantTimeEquals(String a, String b) {
    return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
