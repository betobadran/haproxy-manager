package dev.hapm.core.importer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hapm.core.model.Frontend;
import org.junit.jupiter.api.Test;

class HaproxyCfgImporterTest {
  static final String CFG = """
      global
          log /dev/log local0
          maxconn 20000
          user haproxy
          daemon
          stats socket /run/haproxy/admin.sock mode 660 level admin
          ssl-default-bind-options ssl-min-ver TLSv1.2 no-tls-tickets
          tune.bufsize 32768

      defaults
          mode http
          log global
          option httplog
          timeout connect 4s
          timeout client 40s
          timeout server 45s
          errorfile 503 /etc/haproxy/errors/503.http

      frontend www
          bind :80
          bind :443 ssl crt /etc/haproxy/certs/site.pem alpn h2,http/1.1
          option forwardfor
          http-request redirect scheme https code 301 unless { ssl_fc }
          http-response set-header Strict-Transport-Security "max-age=63072000"
          acl is_api hdr(host) -i api.example.com
          acl is_api hdr(host) -i api2.example.com
          acl admin path_beg /admin
          acl internal src 10.0.0.0/8 192.168.0.0/16
          http-request deny deny_status 403 if admin !internal
          http-request set-header X-App "loja virtual"
          use_backend api if is_api
          use_backend static if { path_beg /static /img } || { path_end .css }
          default_backend web

      backend web
          balance roundrobin
          option httpchk GET /health
          http-check expect status 200
          cookie SRV insert indirect nocache
          default-server inter 3s fall 2 rise 3
          server web1 10.0.1.11:8080 check cookie w1 weight 50
          server web2 10.0.1.12:8080 check cookie w2 backup
          server web@3 10.0.1.13:8080 check disabled

      backend api
          balance leastconn
          http-check send meth HEAD uri /ready hdr Host api.example.com
          server api1 10.0.2.21:9000 check ssl verify none
          http-reuse safe

      backend static
          server s1 10.0.3.1:80

      listen postgres
          mode tcp
          bind 10.0.0.5:5432
          timeout client 30m
          balance first
          option tcp-check
          server pg1 10.0.3.31:5432 check
          server pg2 10.0.3.32:5432 check backup

      listen stats
          bind :9000
          stats enable
          stats uri /

      userlist admins
          user admin password x
      """;

  @Test
  void convertsCommonConfig() {
    var r = HaproxyCfgImporter.convert(CFG);
    // global/defaults
    assertThat(r.haproxy().maxconn()).isEqualTo(20000);
    assertThat(r.haproxy().sslMinVer()).isEqualTo("TLSv1.2");
    assertThat(r.haproxy().timeouts().client()).isEqualTo("40s");
    assertThat(r.haproxy().timeouts().server()).isEqualTo("45s");

    var www = r.frontends().stream().filter(f -> f.name().equals("www")).findFirst().orElseThrow();
    assertThat(www.binds()).extracting(Frontend.Bind::port).containsExactly(80, 443);
    assertThat(www.binds().get(1).ssl()).isTrue();
    assertThat(www.http2()).isTrue();
    assertThat(www.redirectToHttps()).isTrue();
    assertThat(www.hsts()).isTrue();
    assertThat(www.forwardFor()).isTrue();
    assertThat(www.defaultBackend()).isEqualTo("web");
    // deny com AND + negação; use_backend com ACL repetida (OU de valores)
    var deny = www.rules().stream().filter(x -> x.action().equals("deny")).findFirst().orElseThrow();
    assertThat(deny.conditions()).hasSize(2);
    assertThat(deny.conditions().get(1).type()).isEqualTo("src");
    assertThat(deny.conditions().get(1).negate()).isTrue();
    var api = www.rules().stream().filter(x -> "api".equals(x.backend())).findFirst().orElseThrow();
    assertThat(api.conditions().get(0).value()).containsExactly("api.example.com", "api2.example.com");
    assertThat(www.rules()).anyMatch(x -> x.action().equals("set_header") && x.value().equals("loja virtual"));
    // OU entre condições não tem equivalente: vira aviso/avançado, nunca some calado
    assertThat(www.extra()).contains("use_backend static if");

    var web = r.backends().stream().filter(b -> b.name().equals("web")).findFirst().orElseThrow();
    assertThat(web.healthCheck().type()).isEqualTo("http");
    assertThat(web.healthCheck().path()).isEqualTo("/health");
    assertThat(web.healthCheck().expectStatus()).isEqualTo("200");
    assertThat(web.healthCheck().interval()).isEqualTo("3s");
    assertThat(web.sticky().cookie()).isEqualTo("SRV");
    assertThat(web.servers()).hasSize(3);
    assertThat(web.servers().get(0).weight()).isEqualTo(50);
    assertThat(web.servers().get(1).backup()).isTrue();
    assertThat(web.servers().get(2).name()).isEqualTo("web-3");
    assertThat(web.servers().get(2).disabled()).isTrue();

    var apiBe = r.backends().stream().filter(b -> b.name().equals("api")).findFirst().orElseThrow();
    assertThat(apiBe.balance()).isEqualTo("leastconn");
    assertThat(apiBe.healthCheck().method()).isEqualTo("HEAD");
    assertThat(apiBe.healthCheck().host()).isEqualTo("api.example.com");
    assertThat(apiBe.serverSsl().enabled()).isTrue();
    assertThat(apiBe.extra()).contains("http-reuse safe");

    var static_ = r.backends().stream().filter(b -> b.name().equals("static")).findFirst().orElseThrow();
    assertThat(static_.healthCheck().type()).isEqualTo("none");

    // listen -> listener + backend
    var pg = r.frontends().stream().filter(f -> f.name().equals("postgres")).findFirst().orElseThrow();
    assertThat(pg.mode()).isEqualTo("tcp");
    assertThat(pg.defaultBackend()).isEqualTo("postgres-be");
    assertThat(pg.timeoutClient()).isEqualTo("30m");
    var pgBe = r.backends().stream().filter(b -> b.name().equals("postgres-be")).findFirst().orElseThrow();
    assertThat(pgBe.mode()).isEqualTo("tcp");
    assertThat(pgBe.balance()).isEqualTo("first");
    assertThat(pgBe.healthCheck().type()).isEqualTo("tcp");
    assertThat(r.frontends()).noneMatch(f -> f.name().equals("stats"));

    assertThat(r.certificatesToUpload()).containsExactly("/etc/haproxy/certs/site.pem");
    assertThat(String.join("\n", r.warnings()))
        .contains("tune.bufsize").contains("errorfile").contains("userlist").contains("web@3").contains("site.pem");
  }
}
