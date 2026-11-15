package dev.hapm.manager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cliente HTTP mínimo para os testes de API. */
public class ApiClient {
  public record Res(int status, JsonNode body, String text) {}

  static final JsonMapper JSON = JsonMapper.builder().build();
  final HttpClient http = HttpClient.newHttpClient();
  final String base;
  String token;

  ApiClient(int port) {
    this.base = "http://127.0.0.1:" + port;
  }

  Res call(String method, String path, Object body) {
    try {
      var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
      if (token != null) b.header("Authorization", "Bearer " + token);
      if (body != null) b.header("Content-Type", "application/json");
      b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
      var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      JsonNode node = null;
      try {
        node = JSON.readTree(r.body());
      } catch (Exception ignored) {
        // texto
      }
      return new Res(r.statusCode(), node, r.body());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  ApiClient login(String user, String password) {
    var c = new ApiClient(Integer.parseInt(base.substring(base.lastIndexOf(':') + 1)));
    var r = c.call("POST", "/api/auth/login", java.util.Map.of("username", user, "password", password));
    if (r.status() != 200) throw new AssertionError("login " + user + ": " + r.text());
    c.token = r.body().get("token").asString();
    return c;
  }
}
