package dev.hapm.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import dev.hapm.agent.AgentConfig;
import dev.hapm.agent.AgentMain;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ponta a ponta: manager (Spring Boot) + agente Java + HAProxy real em master-worker + backend HTTP.
 * Cobre publicação, bloqueio de config inválida, falha no nó com rollback automático, rollback manual e métricas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "hapm.admin-password=admin-pass", "hapm.deployment-timeout-sec=60"})
class EndToEndTest {
  @LocalServerPort int port;

  final HttpClient http = HttpClient.newHttpClient();
  final JsonMapper json = JsonMapper.builder().build();
  String token;
  Process haproxy;
  AgentMain agent;
  Thread agentThread;
  HttpServer backend;

  @AfterEach
  void cleanup() {
    if (agent != null) agent.stop();
    if (agentThread != null) agentThread.interrupt();
    if (haproxy != null) haproxy.destroyForcibly();
    if (backend != null) backend.stop(0);
  }

  static boolean hasHaproxy() {
    try {
      return new ProcessBuilder("sh", "-c", "command -v haproxy").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  static int freePort() throws Exception {
    try (var s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  record Res(int status, JsonNode body, String text) {}

  Res call(String method, String path, Object body) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30));
    if (token != null) b.header("Authorization", "Bearer " + token);
    if (body != null) b.header("Content-Type", "application/json");
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    JsonNode node = null;
    try {
      node = json.readTree(r.body());
    } catch (Exception ignored) {
      // texto
    }
    return new Res(r.statusCode(), node, r.body());
  }

  HttpResponse<String> get(String url) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
  }

  static <T> T waitFor(Supplier<T> fn, long timeoutMs, Supplier<String> label) throws Exception {
    long end = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < end) {
      try {
        var v = fn.get();
        if (v != null && !Boolean.FALSE.equals(v)) return v;
      } catch (Exception ignored) {
        // tenta de novo
      }
      Thread.sleep(300);
    }
    throw new AssertionError("timeout esperando " + label.get());
  }

  @Test
  void fullFlow() throws Exception {
    assumeTrue(hasHaproxy(), "haproxy não instalado");
    var tmp = Files.createTempDirectory("hapm-e2e");

    backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    backend.createContext("/", ex -> {
      var b = ("ola de " + ex.getRequestURI()).getBytes();
      ex.sendResponseHeaders(200, b.length);
      ex.getResponseBody().write(b);
      ex.close();
    });
    backend.start();
    int bePort = backend.getAddress().getPort();

    token = call("POST", "/api/auth/login", Map.of("username", "admin", "password", "admin-pass")).body().get("token").asString();
    assertThat(token).isNotBlank();

    int statsPort = freePort();
    int fePort = freePort();
    var c = call("POST", "/api/clusters", Map.of("name", "e2e", "settings", Map.of("mode", "aws-nlb",
        "haproxy", Map.of("maxconn", 2000, "user", "", "log", "stdout format raw local0", "statsPort", statsPort, "statsSocket", tmp.resolve("admin.sock").toString()))));
    assertThat(c.status()).as(c.text()).isEqualTo(201);
    var cid = c.body().get("id").asString();

    // nó: bootstrap + haproxy master-worker + agente
    var masterSock = tmp.resolve("m.sock");
    var env = new HashMap<String, String>();
    env.put("HAPM_MANAGER_URL", "http://127.0.0.1:" + port);
    env.put("HAPM_JOIN_TOKEN", c.body().get("joinToken").asString());
    env.put("HAPM_NODE_NAME", "lb1");
    env.put("HAPM_NODE_ADDRESS", "127.0.0.1");
    env.put("HAPM_HAPROXY_DIR", tmp.resolve("haproxy").toString());
    env.put("HAPM_STATE_DIR", tmp.resolve("state").toString());
    env.put("HAPM_RELOAD_MODE", "master-socket");
    env.put("HAPM_MASTER_SOCKET", masterSock.toString());
    env.put("HAPM_STATS_PORT", String.valueOf(statsPort));
    int metricsPort = freePort();
    env.put("HAPM_METRICS_PORT", String.valueOf(metricsPort));
    env.put("HAPM_METRICS_HOST", "127.0.0.1");
    env.put("HAPM_POLL_INTERVAL", "1");
    var cfg = AgentConfig.fromEnv(env);
    Files.createDirectories(cfg.haproxyDir());
    agent = new AgentMain(cfg);
    new dev.hapm.agent.Applier(cfg, new dev.hapm.agent.SystemOps()).ensureBootstrap();
    haproxy = new ProcessBuilder("haproxy", "-W", "-S", masterSock.toString(), "-f", cfg.haproxyDir().resolve("current/haproxy.cfg").toString())
        .redirectErrorStream(true).redirectOutput(tmp.resolve("haproxy.log").toFile()).start();
    waitFor(() -> uncheckedStatus("http://127.0.0.1:" + statsPort + "/healthz") == 200, 10_000, () -> "haproxy bootstrap");
    agentThread = Thread.ofPlatform().daemon().start(() -> {
      try {
        agent.run();
      } catch (Exception ignored) {
        // encerrado
      }
    });
    waitFor(() -> nodes(cid).stream().anyMatch(n -> n.get("online").asBoolean()), 15_000, () -> "registro do nó");

    // objetos
    assertThat(call("POST", "/api/clusters/" + cid + "/backends", Map.of("name", "app",
        "servers", List.of(Map.of("name", "s1", "address", "127.0.0.1", "port", bePort)), "healthCheck", Map.of("type", "http", "path", "/health"))).status()).isEqualTo(201);
    var fe = call("POST", "/api/clusters/" + cid + "/frontends", Map.of("name", "web", "binds", List.of(Map.of("address", "127.0.0.1", "port", fePort)), "defaultBackend", "app",
        "rules", List.of(Map.of("conditions", List.of(Map.of("type", "path_beg", "value", "/bloqueado")), "action", "deny", "status", 403))));
    assertThat(fe.status()).as(fe.text()).isEqualTo(201);
    var feId = fe.body().get("id").asString();

    var preview = call("GET", "/api/clusters/" + cid + "/preview", null);
    assertThat(preview.body().get("ok").asBoolean()).as(preview.text()).isTrue();
    assertThat(preview.body().get("check").get("ok").asBoolean()).isTrue();

    // v1
    var d1 = call("POST", "/api/clusters/" + cid + "/deployments", Map.of("note", "primeira versão"));
    assertThat(d1.status()).as(d1.text()).isEqualTo(201);
    var d1Id = d1.body().get("id").asLong();
    waitFor(() -> "succeeded".equals(depStatus(cid, d1Id)), 30_000, () -> "v1 aplicada: " + nodes(cid));
    var r1 = get("http://127.0.0.1:" + fePort + "/ok");
    assertThat(r1.statusCode()).isEqualTo(200);
    assertThat(r1.body()).contains("ola de /ok");
    assertThat(get("http://127.0.0.1:" + fePort + "/bloqueado").statusCode()).isEqualTo(403);

    // config inválida: o manager recusa com haproxy -c
    var feBad = new HashMap<String, Object>(json.convertValue(fe.body(), Map.class));
    feBad.put("extra", "diretiva-que-nao-existe 123");
    assertThat(call("PUT", "/api/clusters/" + cid + "/frontends/" + feId, feBad).status()).isEqualTo(200);
    assertThat(call("POST", "/api/clusters/" + cid + "/deployments", Map.of()).status()).isEqualTo(422);

    // forçando (validate=false) o agente recusa, a publicação falha e o tráfego segue na v1
    var d2 = call("POST", "/api/clusters/" + cid + "/deployments", Map.of("validate", false, "note", "quebrado"));
    assertThat(d2.status()).isEqualTo(201);
    var d2Id = d2.body().get("id").asLong();
    waitFor(() -> "failed".equals(depStatus(cid, d2Id)), 30_000, () -> "falha da v2");
    assertThat(call("GET", "/api/clusters/" + cid + "/deployments/" + d2Id, null).body().get("error").asString()).contains("haproxy -c falhou");
    assertThat(get("http://127.0.0.1:" + fePort + "/ok").statusCode()).isEqualTo(200);
    var n = nodes(cid).getFirst();
    assertThat(n.get("appliedVersion").asInt()).isEqualTo(1);
    assertThat(n.get("desired").get("version").asInt()).isEqualTo(1);

    // rollback manual para a v1 gera a v3
    feBad.put("extra", "");
    call("PUT", "/api/clusters/" + cid + "/frontends/" + feId, feBad);
    var d3 = call("POST", "/api/clusters/" + cid + "/deployments/" + d1Id + "/rollback", null);
    assertThat(d3.status()).isEqualTo(201);
    assertThat(d3.body().get("version").asInt()).isEqualTo(3);
    var d3Id = d3.body().get("id").asLong();
    waitFor(() -> "succeeded".equals(depStatus(cid, d3Id)), 30_000, () -> "v3 aplicada");

    // métricas: agente, HAProxy e manager
    assertThat(get("http://127.0.0.1:" + metricsPort + "/metrics").body()).contains("hapm_config_applied_version 3").contains("hapm_haproxy_up 1");
    assertThat(get("http://127.0.0.1:" + statsPort + "/metrics").body()).contains("haproxy_frontend_http_requests_total{proxy=\"web\"}");
    assertThat(get("http://127.0.0.1:" + port + "/metrics").body()).contains("hapm_node_applied_version{hapm_cluster=\"e2e\",hapm_node=\"lb1\"} 3");

    // auditoria
    var actions = call("GET", "/api/audit", null).body().valueStream().map(a -> a.get("action").asString()).toList();
    assertThat(actions).contains("deployment.create", "deployment.failed", "deployment.succeeded", "frontend.update");
  }

  int uncheckedStatus(String url) {
    try {
      return get(url).statusCode();
    } catch (Exception e) {
      return -1;
    }
  }

  List<JsonNode> nodes(String cid) {
    try {
      return call("GET", "/api/clusters/" + cid + "/nodes", null).body().valueStream().toList();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  String depStatus(String cid, long id) {
    try {
      return call("GET", "/api/clusters/" + cid + "/deployments/" + id, null).body().get("status").asString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
