package dev.hapm.manager;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hapm.core.model.AgentProtocol;
import dev.hapm.core.model.Bundle;
import dev.hapm.core.security.Signatures;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

/** Porta exclusiva dos agentes, credencial por nó, registro fechado e bundles assinados. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"hapm.admin-password=admin-pass-123"})
class DataPlaneSecurityTest {
  static final int AGENT_PORT;

  static {
    try (var s = new ServerSocket(0)) {
      AGENT_PORT = s.getLocalPort();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @org.springframework.test.context.DynamicPropertySource
  static void props(org.springframework.test.context.DynamicPropertyRegistry r) {
    TestDb.register(r, "dataplane");
    r.add("hapm.agent-port", () -> AGENT_PORT);
  }

  @LocalServerPort int port;
  final HttpClient http = HttpClient.newHttpClient();
  final JsonMapper json = JsonMapper.builder().build();

  HttpResponse<String> req(int p, String method, String path, String join, String nodeToken, Object body) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + p + path)).header("Content-Type", "application/json").header(AgentProtocol.NODE_HEADER, "lb1");
    if (join != null) b.header("Authorization", "Bearer " + join);
    if (nodeToken != null) b.header(AgentProtocol.NODE_TOKEN_HEADER, nodeToken);
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void separatesPortsAndAuthenticatesNodes() throws Exception {
    // interface/API não existe na porta dos agentes; agentes não falam pela porta da interface
    assertThat(req(AGENT_PORT, "GET", "/", null, null, null).statusCode()).isEqualTo(404);
    assertThat(req(AGENT_PORT, "POST", "/api/auth/login", null, null, Map.of("username", "admin", "password", "admin-pass-123")).statusCode()).isEqualTo(404);
    assertThat(req(AGENT_PORT, "GET", "/actuator/health", null, null, null).statusCode()).isEqualTo(200);
    assertThat(req(port, "POST", "/agent/v1/heartbeat", "x.y", null, Map.of("name", "lb1")).statusCode()).isEqualTo(404);

    var admin = new ApiClient(port).login("admin", "admin-pass-123");
    var c = admin.call("POST", "/api/clusters", Map.of("name", "seg", "settings", Map.of("mode", "aws-nlb")));
    var cid = c.body().get("id").asString();
    var join = c.body().get("joinToken").asString();
    admin.call("POST", "/api/clusters/" + cid + "/backends", Map.of("name", "app", "servers", List.of(Map.of("name", "s1", "address", "10.0.0.1", "port", 80))));
    admin.call("POST", "/api/clusters/" + cid + "/frontends", Map.of("name", "web", "binds", List.of(Map.of("address", "*", "port", 80)), "defaultBackend", "app"));
    var hb = Map.of("name", "lb1", "agentVersion", "t", "appliedVersion", 0);

    // 1º contato pedindo credencial: recebe uma (só desta vez) e a chave pública de assinatura
    var r = req(AGENT_PORT, "POST", "/agent/v1/heartbeat", join, "request", hb);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    var res = json.readTree(r.body());
    var nodeToken = res.get("nodeToken").asString();
    var signingKey = res.get("signingKey").asString();
    assertThat(nodeToken).hasSizeGreaterThan(30);
    r = req(AGENT_PORT, "POST", "/agent/v1/heartbeat", join, nodeToken, hb);
    assertThat(json.readTree(r.body()).get("nodeToken").isNull()).isTrue();

    // quem só tem o token de join não se passa pelo nó
    assertThat(req(AGENT_PORT, "POST", "/agent/v1/heartbeat", join, null, hb).statusCode()).isEqualTo(401);
    assertThat(req(AGENT_PORT, "POST", "/agent/v1/heartbeat", join, "request", hb).statusCode()).isEqualTo(401);
    assertThat(req(AGENT_PORT, "POST", "/agent/v1/heartbeat", join, "chute", hb).body()).contains("credencial do nó lb1 inválida");

    // bundle assinado com a chave anunciada
    var d = admin.call("POST", "/api/clusters/" + cid + "/deployments", Map.of());
    assertThat(d.status()).as(d.text()).isEqualTo(201);
    assertThat(req(AGENT_PORT, "GET", "/agent/v1/bundle/1", join, null, null).statusCode()).isEqualTo(401);
    r = req(AGENT_PORT, "GET", "/agent/v1/bundle/1", join, nodeToken, null);
    assertThat(r.statusCode()).isEqualTo(200);
    var bundle = json.readValue(r.body(), Bundle.class);
    assertThat(Signatures.verify(Signatures.decodePublic(signingKey), bundle)).isTrue();

    // registro fechado: nós novos recusados, os existentes seguem
    var cur = admin.call("GET", "/api/clusters/" + cid, null).body();
    var settings = json.convertValue(cur.get("settings"), Map.class);
    settings.put("nodes", Map.of("allowJoin", false));
    assertThat(admin.call("PUT", "/api/clusters/" + cid, Map.of("settings", settings)).status()).isEqualTo(200);
    assertThat(req(AGENT_PORT, "POST", "/agent/v1/heartbeat", join, "request", Map.of("name", "lb9", "agentVersion", "t")).statusCode()).isEqualTo(403);
    assertThat(req(AGENT_PORT, "POST", "/agent/v1/heartbeat", join, nodeToken, hb).statusCode()).isEqualTo(200);

    // removendo o nó, ele pode se registrar de novo (agente que perdeu o estado)
    settings.put("nodes", Map.of("allowJoin", true));
    admin.call("PUT", "/api/clusters/" + cid, Map.of("settings", settings));
    var nodeId = admin.call("GET", "/api/clusters/" + cid + "/nodes", null).body().get(0).get("id").asString();
    admin.call("DELETE", "/api/clusters/" + cid + "/nodes/" + nodeId, null);
    r = req(AGENT_PORT, "POST", "/agent/v1/heartbeat", join, "request", hb);
    assertThat(json.readTree(r.body()).get("nodeToken").asString()).isNotEqualTo(nodeToken);
    // a chave pública também aparece para o admin conferir/fixar na instalação
    var key = admin.call("GET", "/api/system/signing-key", null).body();
    assertThat(key.get("publicKey").asString()).isEqualTo(signingKey);
    assertThat(key.get("fingerprint").asString()).startsWith("SHA256:");
  }
}
