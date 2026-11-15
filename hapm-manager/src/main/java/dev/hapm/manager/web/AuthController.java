package dev.hapm.manager.web;

import dev.hapm.manager.HapmProperties;
import dev.hapm.manager.security.AuthService;
import dev.hapm.manager.security.Role;
import dev.hapm.manager.security.SessionUser;
import dev.hapm.manager.security.TokenService;
import dev.hapm.manager.store.Store;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {
  public record Login(String username, String password) {}

  public record Exchange(String code) {}

  public record PasswordChange(String current, String password) {}

  private record Attempts(long first, int count) {}

  private final Store store;
  private final PasswordEncoder encoder;
  private final TokenService tokens;
  private final AuthService auth;
  private final HapmProperties.Auth cfg;
  private final boolean oidcAvailable;
  /** limitador simples contra força bruta: 10 falhas / 5 min por IP. */
  private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

  public AuthController(Store store, PasswordEncoder encoder, TokenService tokens, AuthService auth, HapmProperties props,
                        ObjectProvider<ClientRegistrationRepository> oidcClients) {
    this.store = store;
    this.encoder = encoder;
    this.tokens = tokens;
    this.auth = auth;
    this.cfg = props.authOrDefault();
    this.oidcAvailable = cfg.oidcOrDefault().enabled() && oidcClients.getIfAvailable() != null;
  }

  /** O que a tela de login deve oferecer. */
  @GetMapping("/auth/providers")
  public Map<String, Object> providers() {
    var m = new LinkedHashMap<String, Object>();
    m.put("password", cfg.localOrDefault().enabled() || cfg.ldapOrDefault().enabled());
    m.put("ldap", cfg.ldapOrDefault().enabled());
    m.put("oidc", oidcAvailable ? Map.of("label", cfg.oidcOrDefault().label() == null ? "Entrar com SSO" : cfg.oidcOrDefault().label(),
        "url", "/oauth2/authorization/" + cfg.oidcOrDefault().registrationId()) : null);
    return m;
  }

  @PostMapping("/auth/login")
  public Map<String, Object> login(@RequestBody Login body, HttpServletRequest req) throws InterruptedException {
    var ip = req.getRemoteAddr();
    var now = System.currentTimeMillis();
    var a = attempts.get(ip);
    if (a != null && now - a.first() < 300_000 && a.count() >= 10) throw Http.status(HttpStatus.TOO_MANY_REQUESTS, "muitas tentativas; aguarde alguns minutos");
    var outcome = auth.loginPassword(body.username(), body.password());
    if (outcome instanceof AuthService.Denied d) {
      attempts.put(ip, a != null && now - a.first() < 300_000 ? new Attempts(a.first(), a.count() + 1) : new Attempts(now, 1));
      store.audit(body.username(), null, "auth.login.failed", null, Map.of("reason", d.reason()));
      Thread.sleep(500);
      throw Http.status(HttpStatus.UNAUTHORIZED, d.reason());
    }
    var user = ((AuthService.Ok) outcome).user();
    attempts.remove(ip);
    store.audit(user.username(), null, "auth.login", user.source(), Map.of("roles", user.roles().toString()));
    return session(user);
  }

  /** Troca o código de uso único do callback OIDC pelo token de sessão. */
  @PostMapping("/auth/exchange")
  public Map<String, Object> exchange(@RequestBody Exchange body) {
    var token = auth.redeemOneTimeCode(body.code()).orElseThrow(() -> Http.status(HttpStatus.UNAUTHORIZED, "código inválido ou expirado; entre novamente"));
    var user = tokens.verify(token).orElseThrow(() -> Http.status(HttpStatus.UNAUTHORIZED, "sessão inválida"));
    return session(user, token);
  }

  private Map<String, Object> session(SessionUser user) {
    return session(user, tokens.sign(user));
  }

  private static Map<String, Object> session(SessionUser user, String token) {
    var m = describe(user);
    m.put("token", token);
    return m;
  }

  static Map<String, Object> describe(SessionUser user) {
    var m = new LinkedHashMap<String, Object>();
    m.put("username", user.username());
    m.put("displayName", user.displayName());
    m.put("source", user.source());
    m.put("roles", user.roles().stream().map(Role::name).sorted().toList());
    m.put("effectiveRoles", Role.expand(user.roles()).stream().map(Role::name).sorted().toList());
    return m;
  }

  @GetMapping("/me")
  public Map<String, Object> me(@AuthenticationPrincipal SessionUser user) {
    return describe(user);
  }

  @PostMapping("/me/password")
  public Map<String, Boolean> changePassword(@RequestBody PasswordChange body, @AuthenticationPrincipal SessionUser user) {
    if (!"local".equals(user.source())) throw Http.status(HttpStatus.BAD_REQUEST, "senha de usuário " + user.source() + " é gerida no diretório corporativo");
    var u = store.getUser(user.username()).orElseThrow();
    if (body.current() == null || !encoder.matches(body.current(), u.passwordHash())) throw Http.status(HttpStatus.BAD_REQUEST, "senha atual incorreta");
    if (body.password() == null || body.password().length() < 10) throw Http.status(HttpStatus.BAD_REQUEST, "nova senha deve ter ao menos 10 caracteres");
    store.setPassword(user.username(), encoder.encode(body.password()));
    store.audit(user.username(), null, "auth.password.changed", null, null);
    return Map.of("ok", true);
  }
}
