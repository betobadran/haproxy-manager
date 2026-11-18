package dev.hapm.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Exportar/importar cluster (JSON/YAML, dry-run, merge/replace, atomicidade) e importar haproxy.cfg. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"hapm.admin-password=admin-pass-123"})
class ExportImportTest {
  @org.springframework.test.context.DynamicPropertySource
  static void db(org.springframework.test.context.DynamicPropertyRegistry r) {
    TestDb.register(r, "export");
  }

  @LocalServerPort int port;

  @Test
  void exportImportRoundTrip() {
    var admin = new ApiClient(port).login("admin", "admin-pass-123");
    admin.call("POST", "/api/users", Map.of("username", "vera", "password", "senha-forte-123", "roles", List.of("VIEWER")));
    var vera = admin.login("vera", "senha-forte-123");

    var c = admin.call("POST", "/api/clusters", Map.of("name", "origem", "settings", Map.of("mode", "onprem-keepalived",
        "keepalived", Map.of("vips", List.of("10.0.0.100/24"), "authPass", "s3gr3do"))));
    var cid = c.body().get("id").asString();
    assertThat(admin.call("POST", "/api/clusters/" + cid + "/backends", Map.of("name", "app", "balance", "leastconn",
        "servers", List.of(Map.of("name", "s1", "address", "10.0.0.1", "port", 8080), Map.of("name", "s2", "address", "10.0.0.2", "port", 8080, "backup", true)))).status())
        .isEqualTo(201);
    assertThat(admin.call("POST", "/api/clusters/" + cid + "/frontends", Map.of("name", "web", "binds", List.of(Map.of("address", "*", "port", 80)),
        "defaultBackend", "app", "rules", List.of(Map.of("conditions", List.of(Map.of("type", "path_beg", "value", List.of("/admin"))), "action", "deny", "status", 403))))
        .status()).isEqualTo(201);

    // só ADMIN exporta/importa
    assertThat(vera.call("GET", "/api/clusters/" + cid + "/export", null).status()).isEqualTo(403);
    assertThat(vera.raw("POST", "/api/clusters/import", "{}", "application/json").status()).isEqualTo(403);

    var yaml = admin.call("GET", "/api/clusters/" + cid + "/export?format=yaml", null).text();
    assertThat(yaml).contains("hapmExport: 1").contains("frontends:").contains("leastconn").doesNotContain("s3gr3do");
    assertThat(admin.call("GET", "/api/clusters/" + cid + "/export?secrets=true", null).text()).contains("s3gr3do");

    // dry-run não grava nada
    var dry = admin.raw("POST", "/api/clusters/import?name=copia&dryRun=true", yaml, "application/yaml");
    assertThat(dry.status()).as(dry.text()).isEqualTo(200);
    assertThat(dry.body().get("created").toString()).contains("listener/web").contains("backend/app");
    assertThat(admin.call("GET", "/api/clusters", null).body().valueStream().map(x -> x.get("name").asString()).toList()).doesNotContain("copia");

    // importa de verdade: mesmo conteúdo
    var imp = admin.raw("POST", "/api/clusters/import?name=copia", yaml, "application/yaml");
    assertThat(imp.status()).as(imp.text()).isEqualTo(201);
    var copia = imp.body().get("clusterId").asString();
    var be = admin.call("GET", "/api/clusters/" + copia + "/backends", null).body().get(0);
    assertThat(be.get("balance").asString()).isEqualTo("leastconn");
    assertThat(be.get("servers").get(1).get("backup").asBoolean()).isTrue();
    var fe = admin.call("GET", "/api/clusters/" + copia + "/frontends", null).body().get(0);
    assertThat(fe.get("rules").get(0).get("action").asString()).isEqualTo("deny");
    assertThat(admin.call("GET", "/api/clusters/" + copia + "/preview", null).body().get("ok").asBoolean()).isTrue();

    // atômico: um erro no meio desfaz tudo
    var bad = """
        {"hapmExport":1,"backends":[{"name":"novo","servers":[{"name":"a","address":"10.0.0.9","port":80}]}],
         "frontends":[{"name":"quebrado","binds":[{"address":"*","port":81}],"defaultBackend":"nao-existe"}]}""";
    var r = admin.raw("POST", "/api/clusters/" + copia + "/import", bad, "application/json");
    assertThat(r.status()).as(r.text()).isEqualTo(422);
    assertThat(r.body().get("errors").toString()).contains("quebrado");
    assertThat(admin.call("GET", "/api/clusters/" + copia + "/backends", null).body().valueStream().map(x -> x.get("name").asString()).toList())
        .containsExactly("app");

    // replace remove o que não está no documento
    var onlyBackend = """
        {"hapmExport":1,"backends":[{"name":"app","servers":[{"name":"s1","address":"10.0.0.1","port":8080}]}]}""";
    r = admin.raw("POST", "/api/clusters/" + copia + "/import?mode=replace", onlyBackend, "application/json");
    assertThat(r.status()).as(r.text()).isEqualTo(201);
    assertThat(r.body().get("deleted").toString()).contains("listener/web");
    assertThat(admin.call("GET", "/api/clusters/" + copia + "/frontends", null).body().size()).isZero();

    // haproxy.cfg existente
    var cfg = """
        defaults
            timeout client 33s
        frontend site
            bind :8088
            acl api hdr(host) -i api.example.com
            use_backend api if api
            default_backend pool
        backend pool
            option httpchk GET /health
            server w1 10.0.1.1:80 check
        backend api
            server a1 10.0.2.1:9000 check
        """;
    var h = admin.raw("POST", "/api/clusters/" + cid + "/import-haproxy?dryRun=true", cfg, "text/plain");
    assertThat(h.status()).as(h.text()).isEqualTo(200);
    h = admin.raw("POST", "/api/clusters/" + cid + "/import-haproxy", cfg, "text/plain");
    assertThat(h.status()).as(h.text()).isEqualTo(201);
    assertThat(h.body().get("created").toString()).contains("listener/site").contains("backend/pool").contains("backend/api");
    var prev = admin.call("GET", "/api/clusters/" + cid + "/preview", null).body();
    assertThat(prev.get("ok").asBoolean()).as(prev.toString()).isTrue();
    assertThat(prev.get("haproxyCfg").asString()).contains("timeout client 33s").contains("use_backend api if");
    if (prev.get("check") != null && !prev.get("check").isNull() && prev.get("check").has("ok")) {
      assertThat(prev.get("check").get("ok").asBoolean()).as(prev.get("check").toString()).isTrue();
    }
  }
}
