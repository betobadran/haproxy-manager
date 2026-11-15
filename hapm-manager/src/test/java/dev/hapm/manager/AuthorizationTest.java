package dev.hapm.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Papéis (VIEWER/OPERATOR/APPROVER/ADMIN) e fluxo de aprovação com quatro olhos. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "hapm.admin-password=admin-pass-123"})
class AuthorizationTest {
  @org.springframework.test.context.DynamicPropertySource
  static void db(org.springframework.test.context.DynamicPropertyRegistry r) {
    TestDb.register(r, "authz");
  }

  @LocalServerPort int port;
  ApiClient admin;

  @BeforeEach
  void users() {
    admin = new ApiClient(port).login("admin", "admin-pass-123");
    for (var u : List.of(
        Map.of("username", "vera", "roles", List.of("VIEWER")),
        Map.of("username", "otto", "roles", List.of("OPERATOR")),
        Map.of("username", "olga", "roles", List.of("OPERATOR", "APPROVER")),
        Map.of("username", "ana", "roles", List.of("APPROVER")))) {
      var body = new java.util.HashMap<String, Object>(u);
      body.put("password", "senha-forte-123");
      var r = admin.call("POST", "/api/users", body);
      assertThat(r.status()).as(r.text()).isIn(201, 409);
    }
  }

  ApiClient as(String user) {
    return admin.login(user, "senha-forte-123");
  }

  String cluster(String name, boolean approval) {
    var r = admin.call("POST", "/api/clusters", Map.of("name", name, "settings", Map.of("mode", "aws-nlb",
        "approval", Map.of("required", approval, "allowSelfApproval", false))));
    assertThat(r.status()).as(r.text()).isEqualTo(201);
    var cid = r.body().get("id").asString();
    assertThat(admin.call("POST", "/api/clusters/" + cid + "/backends", Map.of("name", "app",
        "servers", List.of(Map.of("name", "s1", "address", "10.0.0.1", "port", 8080)))).status()).isEqualTo(201);
    return cid;
  }

  Map<String, Object> frontend(int port) {
    return Map.of("name", "web" + port, "binds", List.of(Map.of("address", "*", "port", port)), "defaultBackend", "app");
  }

  @Test
  void rolesAreEnforced() {
    var cid = cluster("papeis", false);
    var vera = as("vera");
    var otto = as("otto");
    var ana = as("ana");
    // VIEWER: lê, não altera, não vê o token de join
    assertThat(vera.call("GET", "/api/clusters/" + cid, null).body().get("joinToken").isNull()).isTrue();
    assertThat(admin.call("GET", "/api/clusters/" + cid, null).body().get("joinToken").asString()).isNotBlank();
    assertThat(vera.call("POST", "/api/clusters/" + cid + "/frontends", frontend(80)).status()).isEqualTo(403);
    assertThat(vera.call("POST", "/api/clusters/" + cid + "/deployments", Map.of()).status()).isEqualTo(403);
    assertThat(vera.call("GET", "/api/me", null).body().get("roles").toString()).contains("VIEWER");
    // OPERATOR: edita e publica (sem aprovação neste cluster), mas não administra
    assertThat(otto.call("POST", "/api/clusters/" + cid + "/frontends", frontend(80)).status()).isEqualTo(201);
    assertThat(otto.call("POST", "/api/clusters/" + cid + "/deployments", Map.of()).status()).isEqualTo(201);
    assertThat(otto.call("PUT", "/api/clusters/" + cid, Map.of("name", "x")).status()).isEqualTo(403);
    assertThat(otto.call("POST", "/api/clusters/" + cid + "/rotate-token", null).status()).isEqualTo(403);
    assertThat(otto.call("GET", "/api/users", null).status()).isEqualTo(403);
    // APPROVER sem OPERATOR não edita
    assertThat(ana.call("POST", "/api/clusters/" + cid + "/frontends", frontend(81)).status()).isEqualTo(403);
    // sem token
    assertThat(new ApiClient(port).call("GET", "/api/clusters", null).status()).isEqualTo(401);
  }

  @Test
  void approvalFlowWithFourEyes() {
    var cid = cluster("aprovacao", true);
    var olga = as("olga");
    var ana = as("ana");
    var otto = as("otto");
    assertThat(olga.call("POST", "/api/clusters/" + cid + "/frontends", frontend(80)).status()).isEqualTo(201);

    // publicar vira pedido (202), nada vai para os nós ainda
    var req = olga.call("POST", "/api/clusters/" + cid + "/deployments", Map.of("note", "novo listener"));
    assertThat(req.status()).as(req.text()).isEqualTo(202);
    assertThat(req.body().get("type").asString()).isEqualTo("request");
    var rid = req.body().get("id").asLong();
    assertThat(admin.call("GET", "/api/clusters/" + cid + "/deployments", null).body().size()).isZero();
    assertThat(admin.call("GET", "/api/clusters/" + cid + "/requests/" + rid, null).body().get("diff").toString()).contains("frontend web80");

    // quem pediu não aprova (mesmo tendo APPROVER); OPERATOR sem APPROVER também não
    assertThat(olga.call("POST", "/api/clusters/" + cid + "/requests/" + rid + "/approve", Map.of()).status()).isEqualTo(403);
    assertThat(otto.call("POST", "/api/clusters/" + cid + "/requests/" + rid + "/approve", Map.of()).status()).isEqualTo(403);

    // outra pessoa aprova -> publica exatamente o que foi pedido
    var ok = ana.call("POST", "/api/clusters/" + cid + "/requests/" + rid + "/approve", Map.of("comment", "ok"));
    assertThat(ok.status()).as(ok.text()).isEqualTo(200);
    assertThat(ok.body().get("deployment").get("approvedBy").asString()).isEqualTo("ana");
    assertThat(ok.body().get("deployment").get("createdBy").asString()).isEqualTo("olga");
    assertThat(ana.call("POST", "/api/clusters/" + cid + "/requests/" + rid + "/approve", Map.of()).status()).isEqualTo(409);

    // rejeição exige motivo
    assertThat(olga.call("POST", "/api/clusters/" + cid + "/frontends", frontend(81)).status()).isEqualTo(201);
    var r2 = olga.call("POST", "/api/clusters/" + cid + "/deployments", Map.of()).body().get("id").asLong();
    assertThat(ana.call("POST", "/api/clusters/" + cid + "/requests/" + r2 + "/reject", Map.of()).status()).isEqualTo(400);
    assertThat(ana.call("POST", "/api/clusters/" + cid + "/requests/" + r2 + "/reject", Map.of("comment", "falta janela de mudança")).body()
        .get("status").asString()).isEqualTo("rejected");

    // emergência: só ADMIN, com justificativa; torna pedidos pendentes obsoletos
    var r3 = olga.call("POST", "/api/clusters/" + cid + "/deployments", Map.of()).body().get("id").asLong();
    assertThat(olga.call("POST", "/api/clusters/" + cid + "/deployments", Map.of("emergency", true, "note", "incidente grave em produção")).status()).isEqualTo(403);
    assertThat(admin.call("POST", "/api/clusters/" + cid + "/deployments", Map.of("emergency", true, "note", "curto")).status()).isEqualTo(400);
    var em = admin.call("POST", "/api/clusters/" + cid + "/deployments", Map.of("emergency", true, "note", "incidente INC-123 em produção"));
    assertThat(em.status()).as(em.text()).isEqualTo(201);
    assertThat(em.body().get("note").asString()).startsWith("[EMERGÊNCIA]");
    assertThat(ana.call("GET", "/api/clusters/" + cid + "/requests/" + r3, null).body().get("status").asString()).isEqualTo("superseded");
    assertThat(ana.call("POST", "/api/clusters/" + cid + "/requests/" + r3 + "/approve", Map.of()).status()).isEqualTo(409);

    // rollback também passa por aprovação
    var deps = admin.call("GET", "/api/clusters/" + cid + "/deployments", null).body();
    var first = deps.get(deps.size() - 1).get("id").asLong();
    assertThat(olga.call("POST", "/api/clusters/" + cid + "/deployments/" + first + "/rollback", Map.of()).status()).isEqualTo(202);

    var actions = admin.call("GET", "/api/audit", null).body().valueStream().map(a -> a.get("action").asString()).toList();
    assertThat(actions).contains("request.create", "request.approve", "request.reject", "deployment.emergency");
  }

  @Test
  void userManagementGuards() {
    assertThat(admin.call("POST", "/api/users", Map.of("username", "fraca", "password", "123", "roles", List.of("VIEWER"))).status()).isEqualTo(400);
    assertThat(admin.call("POST", "/api/users", Map.of("username", "semrole", "password", "senha-forte-123", "roles", List.of())).status()).isEqualTo(400);
    assertThat(admin.call("DELETE", "/api/users/admin", null).status()).isEqualTo(409);
    assertThat(admin.call("PUT", "/api/users/admin", Map.of("roles", List.of("VIEWER"))).status()).isEqualTo(409);
    assertThat(admin.call("GET", "/api/users", null).body().valueStream().map(u -> u.get("username").asString()).toList()).contains("admin", "vera");
  }
}
