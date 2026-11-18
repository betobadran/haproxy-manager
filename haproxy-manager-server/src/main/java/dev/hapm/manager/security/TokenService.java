package dev.hapm.manager.security;

import dev.hapm.manager.HapmProperties;
import dev.hapm.manager.store.Store;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Token de sessão stateless (JSON assinado com HMAC-SHA256): funciona com vários managers atrás de um
 * balanceador desde que compartilhem HAPM_SECRET. Papéis vindos do LDAP/IdP valem até o token expirar.
 */
@Service
public class TokenService {
  private record Payload(String u, String n, String s, List<String> r, long e) {}

  private final byte[] secret;
  private final long ttlSec;
  private final JsonMapper json;

  public TokenService(HapmProperties props, Store store, JsonMapper json) {
    this.json = json;
    this.ttlSec = props.authOrDefault().sessionHoursOrDefault() * 3600L;
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

  public String sign(SessionUser user) {
    var p = new Payload(user.username(), user.displayName(), user.source(), user.roles().stream().map(Enum::name).sorted().toList(),
        Instant.now().getEpochSecond() + ttlSec);
    var body = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(p));
    return body + "." + hmac(body);
  }

  public Optional<SessionUser> verify(String token) {
    if (token == null) return Optional.empty();
    int dot = token.indexOf('.');
    if (dot < 0) return Optional.empty();
    var body = token.substring(0, dot);
    if (!MessageDigest.isEqual(hmac(body).getBytes(StandardCharsets.US_ASCII), token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII))) return Optional.empty();
    try {
      var p = json.readValue(Base64.getUrlDecoder().decode(body), Payload.class);
      if (p.e() < Instant.now().getEpochSecond()) return Optional.empty();
      var roles = EnumSet.noneOf(Role.class);
      for (var r : p.r()) Role.parse(r).ifPresent(roles::add);
      return Optional.of(new SessionUser(p.u(), p.n(), p.s(), roles));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  public static boolean constantTimeEquals(String a, String b) {
    return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
