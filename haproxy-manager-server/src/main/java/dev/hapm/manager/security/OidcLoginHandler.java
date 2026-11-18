package dev.hapm.manager.security;

import dev.hapm.manager.HapmProperties;
import dev.hapm.manager.store.Store;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/** Conclusão do login OIDC: grupos do token -> papéis -> sessão do HAProxy Manager -> volta para a UI com código de uso único. */
@Component
public class OidcLoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {
  private final AuthService auth;
  private final TokenService tokens;
  private final Store store;
  private final HapmProperties.Oidc cfg;

  public OidcLoginHandler(AuthService auth, TokenService tokens, Store store, HapmProperties props) {
    this.auth = auth;
    this.tokens = tokens;
    this.store = store;
    this.cfg = props.authOrDefault().oidcOrDefault();
  }

  /** Lê uma claim por caminho com pontos (ex.: realm_access.roles) e devolve como lista de strings. */
  @SuppressWarnings("unchecked")
  static List<String> claimList(Map<String, Object> claims, String path) {
    Object cur = claims;
    for (var part : path.split("\\.")) {
      if (!(cur instanceof Map<?, ?> m)) return List.of();
      cur = ((Map<String, Object>) m).get(part);
    }
    if (cur instanceof Collection<?> c) {
      var out = new ArrayList<String>();
      for (var o : c) if (o != null) out.add(String.valueOf(o));
      return out;
    }
    if (cur instanceof String s) return List.of(s.split("[,\\s]+"));
    return List.of();
  }

  private static String str(Object o) {
    return o == null ? null : String.valueOf(o);
  }

  @Override
  public void onAuthenticationSuccess(HttpServletRequest req, HttpServletResponse res, Authentication authentication) throws IOException {
    var user = (OAuth2User) authentication.getPrincipal();
    var claims = user.getAttributes();
    var usernameClaim = cfg.usernameClaim() == null || cfg.usernameClaim().isBlank() ? "preferred_username" : cfg.usernameClaim();
    var username = str(claims.get(usernameClaim));
    if (username == null) username = str(claims.get("email"));
    if (username == null) username = user.getName();
    var display = str(claims.getOrDefault("name", username));
    var groups = claimList(claims, cfg.groupsClaim() == null || cfg.groupsClaim().isBlank() ? "groups" : cfg.groupsClaim());
    var outcome = auth.external(username, display, "oidc", groups);
    var session = req.getSession(false);
    if (session != null) session.invalidate(); // a sessão HTTP só existiu durante o fluxo OAuth2
    if (outcome instanceof AuthService.Denied d) {
      store.audit(username, null, "auth.login.denied", "oidc", Map.of("reason", d.reason()));
      res.sendRedirect("/#/login?error=" + URLEncoder.encode(d.reason(), StandardCharsets.UTF_8));
      return;
    }
    var ok = (AuthService.Ok) outcome;
    store.audit(username, null, "auth.login", "oidc", Map.of("roles", ok.user().roles().toString()));
    res.sendRedirect("/#/login/callback?code=" + auth.issueOneTimeCode(tokens.sign(ok.user())));
  }

  @Override
  public void onAuthenticationFailure(HttpServletRequest req, HttpServletResponse res, AuthenticationException e) throws IOException {
    res.sendRedirect("/#/login?error=" + URLEncoder.encode("falha no login SSO: " + e.getMessage(), StandardCharsets.UTF_8));
  }
}
