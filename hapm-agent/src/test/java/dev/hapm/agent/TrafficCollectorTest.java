package dev.hapm.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrafficCollectorTest {
  static final String SAMPLE = """
      # HELP haproxy_process_current_connections Number of active sessions
      # TYPE haproxy_process_current_connections gauge
      haproxy_process_current_connections 7
      haproxy_frontend_http_requests_total{proxy="hapm_stats"} 999
      haproxy_frontend_http_requests_total{proxy="web"} 120
      haproxy_frontend_current_sessions{proxy="web"} 3
      haproxy_frontend_bytes_in_total{proxy="web"} 1000
      haproxy_frontend_bytes_out_total{proxy="web"} 5000
      haproxy_frontend_http_responses_total{proxy="web",code="2xx"} 100
      haproxy_frontend_http_responses_total{proxy="web",code="5xx"} 4
      haproxy_frontend_http_responses_total{proxy="web",code="other"} 1
      haproxy_frontend_requests_denied_total{proxy="web"} 2
      haproxy_frontend_sessions_total{proxy="pg"} 40
      haproxy_backend_status{proxy="app",state="DOWN"} 0
      haproxy_backend_status{proxy="app",state="UP"} 1
      haproxy_backend_active_servers{proxy="app"} 2
      haproxy_backend_response_time_average_seconds{proxy="app"} 0.042
      haproxy_backend_http_responses_total{proxy="app",code="2xx"} 90
      haproxy_backend_http_responses_total{proxy="app",code="5xx"} 4
      haproxy_server_status{proxy="app",server="s1",state="UP"} 1
      haproxy_server_status{proxy="app",server="s2",state="UP"} 0
      haproxy_server_status{proxy="app",server="s2",state="MAINT"} 1
      haproxy_backend_status{proxy="hapm_acme",state="UP"} 1
      """;

  @Test
  void parsesExporterIgnoringInternalProxies() {
    var t = TrafficCollector.parse(SAMPLE, 1000);
    assertThat(t.connections()).isEqualTo(7);
    assertThat(t.frontends()).extracting("name").containsExactly("pg", "web");
    var web = t.frontends().get(1);
    assertThat(web.requests()).isEqualTo(120);
    assertThat(web.r2xx()).isEqualTo(100);
    assertThat(web.r5xx()).isEqualTo(4);
    assertThat(web.rOther()).isEqualTo(1);
    assertThat(web.denied()).isEqualTo(2);
    assertThat(t.frontends().get(0).sessionsTotal()).isEqualTo(40);
    assertThat(t.backends()).hasSize(1);
    var app = t.backends().getFirst();
    assertThat(app.up()).isTrue();
    assertThat(app.active()).isEqualTo(2);
    assertThat(app.responseTime()).isEqualTo(0.042);
    assertThat(app.responses()).isEqualTo(94);
    assertThat(t.servers()).extracting("name", "state").containsExactly(org.assertj.core.groups.Tuple.tuple("s1", "UP"), org.assertj.core.groups.Tuple.tuple("s2", "MAINT"));
  }
}
