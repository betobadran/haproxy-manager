package dev.hapm.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Acesso do manager à AWS: cadeia padrão do SDK, AssumeRole com ExternalId e sessão com o usuário, falhas claras. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"hapm.admin-password=admin-pass-123"})
class AwsAccessTest {
  static final FakeAws AWS;

  static {
    try {
      AWS = new FakeAws();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    // credenciais "da máquina" para a cadeia padrão do SDK (em produção: role da EC2/ECS, IRSA, SSO...)
    System.setProperty("aws.accessKeyId", "AKIAMAQUINA");
    System.setProperty("aws.secretAccessKey", "segredo-da-maquina");
    System.setProperty("aws.disableEc2Metadata", "true");
  }

  @org.springframework.test.context.DynamicPropertySource
  static void props(org.springframework.test.context.DynamicPropertyRegistry r) {
    TestDb.register(r, "awsaccess");
    r.add("hapm.aws.sts-endpoint", AWS::url);
    r.add("hapm.aws.acm-endpoint", AWS::url);
    r.add("hapm.aws.route53-endpoint", AWS::url);
  }

  @AfterAll
  static void stop() {
    AWS.close();
  }

  @LocalServerPort int port;

  String cluster(ApiClient admin, String name, Map<String, Object> awsAccess) {
    var r = admin.call("POST", "/api/clusters", Map.of("name", name, "settings", Map.of("mode", "aws-nlb", "awsAccess", awsAccess)));
    assertThat(r.status()).as(r.text()).isEqualTo(201);
    return r.body().get("id").asString();
  }

  @Test
  void defaultChainAndAssumeRole() {
    var admin = new ApiClient(port).login("admin", "admin-pass-123");
    admin.call("POST", "/api/users", Map.of("username", "otto", "password", "senha-forte-123", "roles", List.of("OPERATOR")));
    var otto = admin.login("otto", "senha-forte-123");

    // validação
    var bad = admin.call("POST", "/api/clusters", Map.of("name", "ruim", "settings", Map.of("mode", "aws-nlb",
        "awsAccess", Map.of("roleArn", "arn:aws:iam::12:role/x", "region", "brasil"))));
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.text()).contains("awsAccess.roleArn").contains("awsAccess.region");

    // sem role: cadeia padrão do SDK, nada de STS AssumeRole
    var c1 = cluster(admin, "padrao", Map.of("region", "sa-east-1"));
    var r = admin.call("POST", "/api/clusters/" + c1 + "/aws/test", null);
    assertThat(r.status()).as(r.text()).isEqualTo(200);
    assertThat(r.body().get("ok").asBoolean()).isTrue();
    assertThat(r.body().get("identity").asString()).isEqualTo("arn:aws:iam::111111111111:user/manager");
    assertThat(AWS.calls).noneMatch(c -> "AssumeRole".equals(c.action()));

    // com role + ExternalId: credenciais temporárias, sessão com o nome do usuário (CloudTrail)
    var c2 = cluster(admin, "outra-conta", Map.of("region", "sa-east-1", "roleArn", "arn:aws:iam::123456789012:role/hapm", "externalId", "hapm-ext-1",
        "sessionMinutes", 30));
    r = admin.call("POST", "/api/clusters/" + c2 + "/aws/test", null);
    assertThat(r.body().get("ok").asBoolean()).as(r.text()).isTrue();
    assertThat(r.body().get("identity").asString()).isEqualTo("arn:aws:sts::123456789012:assumed-role/hapm/hapm-admin");
    var assume = AWS.calls.stream().filter(c -> "AssumeRole".equals(c.action())).findFirst().orElseThrow();
    assertThat(assume.params()).containsEntry("RoleArn", "arn:aws:iam::123456789012:role/hapm").containsEntry("ExternalId", "hapm-ext-1")
        .containsEntry("RoleSessionName", "hapm-admin").containsEntry("DurationSeconds", "1800");
    assertThat(assume.sessionToken()).isNull(); // o próprio AssumeRole usa as credenciais da máquina
    // ACM e Route 53 chamados com a sessão da role assumida
    assertThat(AWS.calls).anyMatch(c -> c.service().equals("acm") && "sessao-hapm-admin".equals(c.sessionToken()));
    assertThat(AWS.calls).anyMatch(c -> c.service().equals("route53") && "sessao-hapm-admin".equals(c.sessionToken()));

    // trust policy errada: falha explicada, sem derrubar nada
    var c3 = cluster(admin, "sem-trust", Map.of("region", "sa-east-1", "roleArn", "arn:aws:iam::123456789012:role/sem-trust"));
    r = admin.call("POST", "/api/clusters/" + c3 + "/aws/test", null);
    assertThat(r.body().get("ok").asBoolean()).isFalse();
    assertThat(r.body().get("checks").toString()).contains("AccessDenied");

    // permissão faltando no ACM vira aviso
    AWS.denyAcm = true;
    r = admin.call("POST", "/api/clusters/" + c1 + "/aws/test", null);
    AWS.denyAcm = false;
    assertThat(r.body().get("checks").toString()).contains("WARN").contains("AccessDeniedException");

    // só ADMIN testa / vê
    assertThat(otto.call("POST", "/api/clusters/" + c1 + "/aws/test", null).status()).isEqualTo(403);
  }
}
