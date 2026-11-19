package dev.hapm.manager;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpServer;
import dev.hapm.core.model.Certificate;
import dev.hapm.manager.service.DeployService;
import dev.hapm.manager.service.NotificationService;
import dev.hapm.manager.store.Store;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Alertas de expiração de certificado: tela, e-mail (GreenMail), Teams e webhook (servidor HTTP local), dedupe e papéis. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"hapm.admin-password=admin-pass-123"})
class NotificationTest {
  @org.springframework.test.context.DynamicPropertySource
  static void db(org.springframework.test.context.DynamicPropertyRegistry r) {
    TestDb.register(r, "notify");
  }

  @RegisterExtension
  static GreenMailExtension mail = new GreenMailExtension(ServerSetupTest.SMTP);

  @LocalServerPort int port;
  @Autowired Store store;
  @Autowired NotificationService notifications;

  HttpServer hooks;
  final Map<String, String> bodies = new ConcurrentHashMap<>();
  final Map<String, String> signatures = new ConcurrentHashMap<>();

  @BeforeEach
  void startHooks() throws Exception {
    hooks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    for (var path : List.of("/teams", "/hook")) {
      hooks.createContext(path, ex -> {
        bodies.put(path, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        var sig = ex.getRequestHeaders().getFirst("X-HAPM-Signature");
        if (sig != null) signatures.put(path, sig);
        ex.sendResponseHeaders(path.equals("/teams") ? 202 : 200, -1);
        ex.close();
      });
    }
    hooks.start();
  }

  @AfterEach
  void stopHooks() {
    hooks.stop(0);
  }

  @Test
  void expiringCertificateIsAlertedOnEveryChannelOnce() throws Exception {
    var admin = new ApiClient(port).login("admin", "admin-pass-123");
    var r = admin.call("POST", "/api/users", Map.of("username", "vera", "password", "senha-forte-123", "roles", List.of("VIEWER")));
    assertThat(r.status()).as(r.text()).isIn(201, 409);
    var vera = admin.login("vera", "senha-forte-123");

    r = admin.call("POST", "/api/clusters", Map.of("name", "alertas", "settings", Map.of("mode", "aws-nlb")));
    assertThat(r.status()).as(r.text()).isEqualTo(201);
    var cid = r.body().get("id").asString();
    var notAfter = Instant.now().plus(Duration.ofDays(5)).plusSeconds(3600).toString();
    store.createObject(cid, DeployService.CERTIFICATE, "site", new Certificate("site", "upload", "PEM",
        new Certificate.Meta("site.example.com", List.of("site.example.com"), "CA", null, notAfter, "ff", 1), null));
    // certificado longe de vencer: não alerta
    store.createObject(cid, DeployService.CERTIFICATE, "longe", new Certificate("longe", "upload", "PEM",
        new Certificate.Meta("x.example.com", List.of("x.example.com"), "CA", null, Instant.now().plus(Duration.ofDays(200)).toString(), "ee", 1), null));

    // canais (Teams e webhook apontando para o servidor local; a API só aceita https para o Teams, por isso direto no store)
    var base = "http://127.0.0.1:" + hooks.getAddress().getPort();
    store.setKv(NotificationService.SETTINGS_KEY, store.toJson(new NotificationService.Settings(List.of(30, 14, 7, 1),
        new NotificationService.Email(true, "127.0.0.1", ServerSetupTest.SMTP.getPort(), "", "", "hapm@example.com", List.of("ops@example.com"), false, false),
        new NotificationService.Teams(true, base + "/teams"),
        new NotificationService.Webhook(true, base + "/hook", "s3gredo"))));

    // VIEWER não configura nem dispara a verificação
    assertThat(vera.call("GET", "/api/notifications/settings", null).status()).isEqualTo(403);
    assertThat(vera.call("POST", "/api/notifications/check", null).status()).isEqualTo(403);
    assertThat(vera.call("POST", "/api/notifications/test/email", null).status()).isEqualTo(403);

    r = admin.call("POST", "/api/notifications/check", null);
    assertThat(r.status()).as(r.text()).isEqualTo(200);
    assertThat(r.body().get("created").asInt()).isEqualTo(1);

    // e-mail
    assertThat(mail.waitForIncomingEmail(5000, 1)).isTrue();
    var msg = mail.getReceivedMessages()[0];
    assertThat(msg.getSubject()).contains("CRÍTICO").contains("site").contains("5 dia");
    // Teams (Adaptive Card) e webhook assinado
    assertThat(bodies.get("/teams")).contains("AdaptiveCard").contains("site");
    var hook = bodies.get("/hook");
    assertThat(hook).contains("\"kind\":\"cert-expiring\"").contains("\"daysLeft\":5");
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("s3gredo".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    assertThat(signatures.get("/hook")).isEqualTo("sha256=" + HexFormat.of().formatHex(mac.doFinal(hook.getBytes(StandardCharsets.UTF_8))));

    // tela: aparece na lista, com as entregas registradas
    var list = vera.call("GET", "/api/notifications?unacked=true", null).body();
    assertThat(list.size()).isEqualTo(1);
    var n = list.get(0);
    assertThat(n.get("level").asString()).isEqualTo("critical");
    assertThat(n.get("deliveries").size()).isEqualTo(3);
    n.get("deliveries").forEach(d -> assertThat(d.get("ok").asBoolean()).as(d.toString()).isTrue());
    assertThat(vera.call("GET", "/api/notifications/count", null).body().get("unacked").asInt()).isEqualTo(1);

    // nova verificação não repete o mesmo alerta
    assertThat(admin.call("POST", "/api/notifications/check", null).body().get("created").asInt()).isZero();
    assertThat(mail.getReceivedMessages()).hasSize(1);

    // reconhecimento (qualquer papel)
    assertThat(vera.call("POST", "/api/notifications/" + n.get("id").asLong() + "/ack", null).body().get("ok").asBoolean()).isTrue();
    assertThat(vera.call("GET", "/api/notifications/count", null).body().get("unacked").asInt()).isZero();

    // segredos mascarados na API e preservados ao salvar com a máscara
    var s = admin.call("GET", "/api/notifications/settings", null).body();
    assertThat(s.get("webhook").get("secret").asString()).isEqualTo(NotificationService.MASK);
    assertThat(admin.call("PUT", "/api/notifications/settings", Map.of("thresholds", List.of(10, 3), "teams", Map.of("enabled", false),
        "webhook", Map.of("enabled", true, "url", base + "/hook", "secret", NotificationService.MASK))).status()).isEqualTo(200);
    assertThat(notifications.settings().webhook().secret()).isEqualTo("s3gredo");
    assertThat(notifications.settings().thresholds()).containsExactly(10, 3);
    // validação
    assertThat(admin.call("PUT", "/api/notifications/settings", Map.of("thresholds", List.of(0))).status()).isEqualTo(400);
    assertThat(admin.call("PUT", "/api/notifications/settings", Map.of("teams", Map.of("enabled", true, "webhookUrl", "http://x"))).status()).isEqualTo(400);

    // botão "Enviar teste"
    r = admin.call("POST", "/api/notifications/test/webhook", null);
    assertThat(r.body().get("ok").asBoolean()).as(r.text()).isTrue();
    assertThat(bodies.get("/hook")).contains("\"kind\":\"test\"");
  }
}
