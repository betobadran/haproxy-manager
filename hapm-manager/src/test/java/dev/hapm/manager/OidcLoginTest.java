package dev.hapm.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import no.nav.security.mock.oauth2.MockOAuth2Server;
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Fluxo OIDC authorization-code completo contra um IdP falso (mock-oauth2-server). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "hapm.admin-password=admin-pass-123",
    "hapm.auth.oidc.enabled=true",
    "hapm.auth.oidc.groups-claim=realm_access.roles",
    "hapm.auth.role-mapping.APPROVER=hapm-approvers",
    "spring.security.oauth2.client.registration.hapm.client-id=hapm",
    "spring.security.oauth2.client.registration.hapm.client-secret=segredo",
    "spring.security.oauth2.client.registration.hapm.scope=openid,profile,email"})
class OidcLoginTest {
  @org.springframework.test.context.DynamicPropertySource
  static void db(org.springframework.test.context.DynamicPropertyRegistry r) {
    TestDb.register(r, "oidc");
  }

  static final MockOAuth2Server IDP = new MockOAuth2Server();

  static {
    IDP.start();
  }

  @DynamicPropertySource
  static void issuer(DynamicPropertyRegistry r) {
    r.add("spring.security.oauth2.client.provider.hapm.issuer-uri", () -> IDP.issuerUrl("default").toString());
  }

  @AfterAll
  static void stop() {
    IDP.shutdown();
  }

  @LocalServerPort int port;

  /** Segue os redirects (com cookies) até a UI e devolve o fragmento #/login/... */
  String browserLogin() throws Exception {
    var http = HttpClient.newBuilder().cookieHandler(new CookieManager()).followRedirects(HttpClient.Redirect.NEVER).build();
    var url = URI.create("http://127.0.0.1:" + port + "/oauth2/authorization/hapm");
    for (int i = 0; i < 10; i++) {
      var res = http.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.discarding());
      var loc = res.headers().firstValue("Location").orElse(null);
      if (loc == null) throw new AssertionError("sem redirect em " + url + " (HTTP " + res.statusCode() + ")");
      if (loc.contains("#/login")) return URLDecoder.decode(loc.substring(loc.indexOf('#')), StandardCharsets.UTF_8);
      url = url.resolve(loc);
    }
    throw new AssertionError("redirects demais");
  }

  @Test
  void ssoLoginMapsGroupsAndExchangesOneTimeCode() throws Exception {
    var api = new ApiClient(port);
    var providers = api.call("GET", "/api/auth/providers", null).body();
    assertThat(providers.get("oidc").get("url").asString()).isEqualTo("/oauth2/authorization/hapm");

    IDP.enqueueCallback(new DefaultOAuth2TokenCallback("default", "u-123", "JWT", List.of("hapm"),
        Map.of("preferred_username", "maria", "name", "Maria Silva", "realm_access", Map.of("roles", List.of("offline_access", "hapm-approvers"))), 3600));
    var frag = browserLogin();
    assertThat(frag).startsWith("#/login/callback?code=");
    var code = frag.substring(frag.indexOf("code=") + 5);

    var session = api.call("POST", "/api/auth/exchange", Map.of("code", code));
    assertThat(session.status()).as(session.text()).isEqualTo(200);
    assertThat(session.body().get("username").asString()).isEqualTo("maria");
    assertThat(session.body().get("displayName").asString()).isEqualTo("Maria Silva");
    assertThat(session.body().get("roles").toString()).isEqualTo("[\"APPROVER\"]");
    // o código é de uso único
    assertThat(api.call("POST", "/api/auth/exchange", Map.of("code", code)).status()).isEqualTo(401);
    // e o token funciona na API
    api.token = session.body().get("token").asString();
    assertThat(api.call("GET", "/api/me", null).body().get("source").asString()).isEqualTo("oidc");

    // usuário sem grupo mapeado volta para o login com erro
    IDP.enqueueCallback(new DefaultOAuth2TokenCallback("default", "u-456", "JWT", List.of("hapm"),
        Map.of("preferred_username", "jose", "realm_access", Map.of("roles", List.of("outro"))), 3600));
    assertThat(browserLogin()).startsWith("#/login?error=").contains("sem papel");
  }
}
