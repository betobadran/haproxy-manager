package dev.hapm.manager;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hapm.core.model.Certificate;
import dev.hapm.manager.service.AcmService;
import dev.hapm.manager.service.DeployService;
import dev.hapm.manager.store.Store;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Certificados do ACM: pedido exportável, validação DNS no Route 53, exportação, renovação, ARN existente e falhas. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"hapm.admin-password=admin-pass-123"})
class AcmTest {
  static final FakeAws AWS;

  static {
    try {
      AWS = new FakeAws();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    System.setProperty("aws.accessKeyId", "AKIAMAQUINA");
    System.setProperty("aws.secretAccessKey", "segredo-da-maquina");
    System.setProperty("aws.disableEc2Metadata", "true");
  }

  @org.springframework.test.context.DynamicPropertySource
  static void props(org.springframework.test.context.DynamicPropertyRegistry r) {
    TestDb.register(r, "acm");
    r.add("hapm.aws.sts-endpoint", AWS::url);
    r.add("hapm.aws.acm-endpoint", AWS::url);
    r.add("hapm.aws.route53-endpoint", AWS::url);
  }

  @AfterAll
  static void stop() {
    AWS.close();
  }

  @LocalServerPort int port;
  @Autowired Store store;
  @Autowired AcmService acm;

  Certificate cert(String cid, String id) {
    return store.fromJson(store.getObject(cid, DeployService.CERTIFICATE, id).orElseThrow().data(), Certificate.class);
  }

  Certificate waitFor(String cid, String id, Predicate<Certificate> p) throws Exception {
    for (int i = 0; i < 100; i++) {
      var c = cert(cid, id);
      if (p.test(c)) return c;
      Thread.sleep(100);
    }
    throw new AssertionError("timeout: " + cert(cid, id).aws());
  }

  @Test
  void acmLifecycle() throws Exception {
    var admin = new ApiClient(port).login("admin", "admin-pass-123");
    var r = admin.call("POST", "/api/clusters", Map.of("name", "acm", "settings", Map.of("mode", "aws-nlb", "awsAccess", Map.of("region", "sa-east-1"),
        "haproxy", Map.of("maxconn", 2000))));
    var cid = r.body().get("id").asString();
    admin.call("POST", "/api/clusters/" + cid + "/backends", Map.of("name", "app", "servers", List.of(Map.of("name", "s1", "address", "10.0.0.1", "port", 8080))));
    admin.call("POST", "/api/clusters/" + cid + "/frontends", Map.of("name", "https", "binds", List.of(Map.of("address", "*", "port", 443, "ssl", true)),
        "defaultBackend", "app"));

    // 1) pedido novo, exportável, validação automática no Route 53
    r = admin.call("POST", "/api/clusters/" + cid + "/certificates", Map.of("name", "loja", "source", "acm",
        "aws", Map.of("domains", List.of("loja.example.com", "www.loja.example.com"))));
    assertThat(r.status()).as(r.text()).isEqualTo(201);
    var id = r.body().get("id").asString();
    var c = waitFor(cid, id, x -> x.aws() != null && "pending-validation".equals(x.aws().status()));
    assertThat(c.aws().arn()).startsWith("arn:aws:acm:sa-east-1:");
    assertThat(c.aws().records()).hasSize(1);
    var req = AWS.calls.stream().filter(x -> "RequestCertificate".equals(x.action())).findFirst().orElseThrow().params().get("body");
    assertThat(req).contains("\"Export\":\"ENABLED\"").contains("\"ValidationMethod\":\"DNS\"").contains("www.loja.example.com").contains("IdempotencyToken");
    // CNAME criado na zona example.com (descoberta pelo nome)
    assertThat(AWS.calls).anyMatch(x -> x.service().equals("route53") && x.action().startsWith("POST /2013-04-01/hostedzone/Z0FAKEEXAMPLE/rrset"));
    assertThat(AWS.records).containsKey(c.aws().records().get(0).name());

    // 2) ACM validou -> exporta, decifra a chave, guarda; a configuração passa no haproxy -c
    acm.step(cid, id, "admin");
    c = cert(cid, id);
    assertThat(c.aws().status()).isEqualTo("issued");
    assertThat(c.usable()).isTrue();
    assertThat(c.pem()).contains("PRIVATE KEY").doesNotContain("ENCRYPTED");
    assertThat(c.meta().domains()).contains("loja.example.com", "www.loja.example.com");
    var view = admin.call("GET", "/api/clusters/" + cid + "/certificates/" + id, null).body();
    assertThat(view.has("pem")).isFalse(); // a chave nunca sai pela API
    assertThat(view.get("hasKey").asBoolean()).isTrue();
    var prev = admin.call("GET", "/api/clusters/" + cid + "/preview", null).body();
    assertThat(prev.get("ok").asBoolean()).as(prev.toString()).isTrue();
    if (prev.get("check").has("ok")) assertThat(prev.get("check").get("ok").asBoolean()).as(prev.get("check").toString()).isTrue();

    // 3) renovação feita pelo ACM: nova validade -> exporta de novo + alerta informativo
    var before = c.meta().notAfter();
    assertThat(acm.renewIfNeeded(cid, id)).isFalse();
    AWS.issue(AWS.certs.get(c.aws().arn()), Instant.now().plus(500, ChronoUnit.DAYS));
    assertThat(acm.renewIfNeeded(cid, id)).isTrue();
    assertThat(cert(cid, id).meta().notAfter()).isNotEqualTo(before);
    assertThat(store.listNotifications(false, cid, 10)).anyMatch(n -> n.kind().equals("acm-renewed"));

    // 4) validação manual: registros na tela, nada no Route 53
    var recordsBefore = AWS.records.size();
    r = admin.call("POST", "/api/clusters/" + cid + "/certificates", Map.of("name", "manual", "source", "acm",
        "aws", Map.of("domains", List.of("api.outra.com.br"), "validation", "manual")));
    var manual = waitFor(cid, r.body().get("id").asString(), x -> x.aws() != null && "pending-validation".equals(x.aws().status()));
    assertThat(manual.aws().records().get(0).name()).endsWith("outra.com.br.");
    assertThat(AWS.records).hasSize(recordsBefore);

    // 5) sem zona no Route 53 para o domínio: erro explicado, registro disponível para criar à mão
    r = admin.call("POST", "/api/clusters/" + cid + "/certificates", Map.of("name", "semzona", "source", "acm", "aws", Map.of("domains", List.of("x.outra.org"))));
    var semZona = waitFor(cid, r.body().get("id").asString(), x -> x.aws() != null && x.aws().error() != null);
    assertThat(semZona.aws().error()).contains("nenhuma zona pública");
    assertThat(semZona.aws().records()).isNotEmpty();

    // 6) ARN existente e exportável: exporta direto
    var existing = new FakeAws.Cert();
    existing.arn = "arn:aws:acm:sa-east-1:123456789012:certificate/11111111-2222-3333-4444-555555555555";
    existing.domains = List.of("legado.example.com");
    existing.exportable = true;
    existing.validationName = "_x.legado.example.com.";
    existing.validationValue = "_y.acm-validations.aws.";
    AWS.issue(existing, Instant.now().plus(200, ChronoUnit.DAYS));
    AWS.certs.put(existing.arn, existing);
    // sem a tag hapm-cluster o HAProxy Manager não exporta (outro certificado qualquer da conta)
    r = admin.call("POST", "/api/clusters/" + cid + "/certificates", Map.of("name", "semtag", "source", "acm", "aws", Map.of("arn", existing.arn)));
    var semTag = waitFor(cid, r.body().get("id").asString(), x -> x.aws() != null && "error".equals(x.aws().status()));
    assertThat(semTag.aws().error()).contains("tag hapm-cluster");
    assertThat(AWS.calls).noneMatch(k -> k.action().equals("ExportCertificate") && k.params().get("body").contains(existing.arn));
    existing.tags.put("hapm-cluster", "acm");
    r = admin.call("POST", "/api/clusters/" + cid + "/certificates", Map.of("name", "legado", "source", "acm", "aws", Map.of("arn", existing.arn)));
    var legado = waitFor(cid, r.body().get("id").asString(), x -> x.aws() != null && "issued".equals(x.aws().status()));
    assertThat(legado.meta().domains()).contains("legado.example.com");

    // 7) ARN de certificado NÃO exportável (pedido antes de jun/2025 ou sem Export=ENABLED): erro claro + alerta
    var old = new FakeAws.Cert();
    old.arn = "arn:aws:acm:sa-east-1:123456789012:certificate/99999999-2222-3333-4444-555555555555";
    old.domains = List.of("antigo.example.com");
    old.validationName = "_x.antigo.example.com.";
    old.validationValue = "_y.acm-validations.aws.";
    AWS.issue(old, Instant.now().plus(200, ChronoUnit.DAYS));
    old.tags.put("hapm-cluster", "acm");
    AWS.certs.put(old.arn, old);
    r = admin.call("POST", "/api/clusters/" + cid + "/certificates", Map.of("name", "antigo", "source", "acm", "aws", Map.of("arn", old.arn)));
    var antigo = waitFor(cid, r.body().get("id").asString(), x -> x.aws() != null && "error".equals(x.aws().status()));
    assertThat(antigo.aws().error()).contains("not exportable");
    assertThat(store.listNotifications(false, cid, 20)).anyMatch(n -> n.kind().equals("acm-failed"));

    // validação de entrada
    assertThat(admin.call("POST", "/api/clusters/" + cid + "/certificates", Map.of("name", "ruim", "source", "acm", "aws", Map.of("arn", "arn:qualquer"))).status())
        .isEqualTo(400);
    assertThat(admin.call("POST", "/api/clusters/" + cid + "/certificates", Map.of("name", "vazio", "source", "acm", "aws", Map.of())).status()).isEqualTo(400);
  }
}
