package dev.hapm.manager.web;

import dev.hapm.manager.security.TokenService;
import dev.hapm.manager.store.Store;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {
  public record Login(String username, String password) {}

  public record PasswordChange(String current, String password) {}

  private record Attempts(long first, int count) {}

  private final Store store;
  private final PasswordEncoder encoder;
  private final TokenService tokens;
  /** limitador simples contra força bruta: 10 falhas / 5 min por IP. */
  private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

  public AuthController(Store store, PasswordEncoder encoder, TokenService tokens) {
    this.store = store;
    this.encoder = encoder;
    this.tokens = tokens;
  }

  @PostMapping("/auth/login")
  public Map<String, String> login(@RequestBody Login body, HttpServletRequest req) throws InterruptedException {
    var ip = req.getRemoteAddr();
    var now = System.currentTimeMillis();
    var a = attempts.get(ip);
    if (a != null && now - a.first() < 300_000 && a.count() >= 10) throw Http.status(HttpStatus.TOO_MANY_REQUESTS, "muitas tentativas; aguarde alguns minutos");
    var user = body.username() == null ? null : store.getUser(body.username()).orElse(null);
    if (user == null || body.password() == null || !encoder.matches(body.password(), user.passwordHash())) {
      attempts.put(ip, a != null && now - a.first() < 300_000 ? new Attempts(a.first(), a.count() + 1) : new Attempts(now, 1));
      store.audit(body.username(), null, "auth.login.failed", null, null);
      Thread.sleep(500);
      throw Http.status(HttpStatus.UNAUTHORIZED, "usuário ou senha inválidos");
    }
    attempts.remove(ip);
    store.audit(user.username(), null, "auth.login", null, null);
    return Map.of("token", tokens.sign(user.username()), "username", user.username());
  }

  @GetMapping("/me")
  public Map<String, String> me(Principal p) {
    return Map.of("username", p.getName());
  }

  @PostMapping("/me/password")
  public Map<String, Boolean> changePassword(@RequestBody PasswordChange body, Principal p) {
    var user = store.getUser(p.getName()).orElseThrow();
    if (body.current() == null || !encoder.matches(body.current(), user.passwordHash())) throw Http.status(HttpStatus.BAD_REQUEST, "senha atual incorreta");
    if (body.password() == null || body.password().length() < 8) throw Http.status(HttpStatus.BAD_REQUEST, "nova senha deve ter ao menos 8 caracteres");
    store.setPassword(p.getName(), encoder.encode(body.password()));
    store.audit(p.getName(), null, "auth.password.changed", null, null);
    return Map.of("ok", true);
  }
}
