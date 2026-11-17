package dev.hapm.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.hapm.manager.service.LogsService;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Consulta LogQL gerada, proteção contra injeção nos filtros e leitura da resposta do Loki (simulado). */
class LogsServiceTest {
  static LogsService.Filters f(String status, String text) {
    return new LogsService.Filters(15, null, "web", null, status, null, "/api", null, text, 100);
  }

  @Test
  void parsesLogfmtWithEscapes() {
    var m = LogsService.logfmt("client=1.2.3.4:5 host=\"a\\\"b.com\" path=\"/x y\" status=200 ua=\"Mozilla \\\"t\\\" x\\\\y\" tls=-");
    assertThat(m).containsEntry("client", "1.2.3.4:5").containsEntry("host", "a\"b.com").containsEntry("path", "/x y")
        .containsEntry("status", "200").containsEntry("ua", "Mozilla \"t\" x\\y").containsEntry("tls", "-");
  }

  @Test
  void queriesLokiAndRejectsInjection() throws Exception {
    var seen = new AtomicReference<String>();
    var tenant = new AtomicReference<String>();
    var loki = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    loki.createContext("/loki/api/v1/query_range", ex -> {
      seen.set(URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
      tenant.set(ex.getRequestHeaders().getFirst("X-Scope-OrgID"));
      var body = """
          {"status":"success","data":{"resultType":"streams","result":[
            {"stream":{"hapm_node":"lb1","hapm_cluster":"prod"},"values":[["1700000000000000000","client=1.1.1.1:1 fe=web status=503 path=\\"/api/x\\""]]},
            {"stream":{"hapm_node":"lb2","hapm_cluster":"prod"},"values":[["1700000001000000000","client=2.2.2.2:2 fe=web status=502 path=\\"/api/y\\""]]}
          ]}}""".getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });
    loki.start();
    try {
      var svc = new LogsService("http://127.0.0.1:" + loki.getAddress().getPort() + "/", "equipe-a", "", JsonMapper.builder().build());
      var res = svc.query("prod", f("5xx", null));
      assertThat(seen.get()).contains("{service_name=\"hapm-haproxy\"} | hapm_cluster=\"prod\" |= \"fe=web \" |= \"path=\\\"/api\"")
          .contains("| logfmt | status=~\"5[0-9][0-9]\"").contains("direction=backward");
      assertThat(tenant.get()).isEqualTo("equipe-a");
      assertThat(res).hasSize(2);
      assertThat(res.get(0).node()).isEqualTo("lb2"); // mais recente primeiro
      assertThat(res.get(0).fields()).containsEntry("status", "502").containsEntry("path", "/api/y");
      assertThat(res.get(1).ts()).isEqualTo(1_700_000_000_000L);

      // nada de aspas, chaves ou espaços nos filtros: não dá para sair da string LogQL
      assertThatThrownBy(() -> svc.query("prod", f(null, "x\" } |~ \".*"))).hasMessageContaining("texto");
      assertThatThrownBy(() -> svc.query("prod", f("abc", null))).hasMessageContaining("status");
    } finally {
      loki.stop(0);
    }
    assertThat(new LogsService("", "", "", JsonMapper.builder().build()).configured()).isFalse();
  }
}
