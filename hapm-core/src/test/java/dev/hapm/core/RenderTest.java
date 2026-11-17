package dev.hapm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.hapm.core.model.Bundle;
import dev.hapm.core.model.Cluster;
import dev.hapm.core.model.ClusterSettings;
import dev.hapm.core.model.Frontend;
import dev.hapm.core.model.NodeInfo;
import dev.hapm.core.model.Snapshot;
import dev.hapm.core.render.BundleBuilder;
import dev.hapm.core.render.HaproxyRenderer;
import dev.hapm.core.render.KeepalivedRenderer;
import dev.hapm.core.render.RenderException;
import dev.hapm.core.validate.Normalizer;
import dev.hapm.core.validate.ValidationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RenderTest {

  @Test
  void rendersHaproxyWithRulesAcmeStatsAndTls() throws Exception {
    var cfg = HaproxyRenderer.render(Fixtures.snapshot(), 7, "agora");
    assertThat(cfg)
        .contains("frontend web\n")
        .contains("bind :443 ssl crt-list @HAPM_RELEASE_DIR@/certs.list alpn h2,http/1.1")
        .contains("acl r1_c1 req.hdr(host),field(1,:) -i api.example.com")
        .contains("acl r1_c1 req.hdr(host),field(1,:) -i -m end .api.example.com")
        .contains("use_backend api if r1_c1 r1_c2")
        .contains("http-request deny deny_status 403 if r2_c1 !r2_c2")
        .contains("use_backend hapm_acme if hapm_acme")
        .contains("http-request deny deny_status 403 if !hapm_allowed !hapm_acme")
        .contains("http-request redirect scheme https code 301 if !{ ssl_fc } !hapm_acme")
        .contains("http-request use-service prometheus-exporter if { path /metrics }")
        .contains("resolvers sysdns")
        .contains("cookie HAPM_SRV insert")
        .contains("stats auth admin:s3cret")
        .contains("tcp-request content reject if r1_c1");
  }

  @Test
  void rejectsConfigInjection() {
    var base = Fixtures.web();
    assertThatThrownBy(() -> Normalizer.frontend(withName(base, "web\nlisten x"))).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> Normalizer.frontend(withName(base, "hapm_stats"))).hasMessageContaining("reservado");
    assertThatThrownBy(() -> Normalizer.frontend(withExtra(base, "http-request deny\nbackend evil"))).hasMessageContaining("seções");
    var badRule = new Frontend.Rule(null, List.of(Fixtures.cond("path_beg", "/x\n  bind :1")), "deny", null, null, null, null, null, null, null, null);
    assertThatThrownBy(() -> Normalizer.frontend(withRules(base, List.of(badRule)))).isInstanceOf(ValidationException.class);
    var badHeader = new Frontend.Rule(null, List.of(), "set_header", null, null, null, null, null, "X", "a\" \nfoo", null);
    assertThatThrownBy(() -> Normalizer.frontend(withRules(base, List.of(badHeader)))).hasMessageContaining("aspas");
    assertThatThrownBy(() -> Normalizer.backend(new dev.hapm.core.model.Backend("b", null, null, null,
        List.of(new dev.hapm.core.model.Backend.Server("s", "1.2.3.4 check\nlisten", 80, null, null, null, null)), null, null, null, null, null, null, null)))
        .hasMessageContaining("endereço inválido");
  }

  @Test
  void detectsBrokenReferencesAndPortConflicts() throws Exception {
    var s = Fixtures.snapshot();
    var fes = new ArrayList<>(s.frontends());
    fes.set(0, withDefaultBackend(fes.get(0), "nao-existe"));
    assertThatThrownBy(() -> HaproxyRenderer.render(replaceFrontends(s, fes), 1, "")).isInstanceOf(RenderException.class).hasMessageContaining("não existe");

    var fes2 = new ArrayList<>(s.frontends());
    fes2.add(Normalizer.frontend(new Frontend("other", null, null, null, List.of(new Frontend.Bind("*", 80, false)), "app", null, null, null, null, null, null, null, null, null, null, null)));
    assertThatThrownBy(() -> HaproxyRenderer.render(replaceFrontends(s, fes2), 1, "")).hasMessageContaining("porta 80");

    var noCerts = new Snapshot(s.cluster(), s.frontends(), s.backends(), List.of(), s.nodes());
    assertThatThrownBy(() -> HaproxyRenderer.render(noCerts, 1, "")).hasMessageContaining("certificados");

    var fes3 = new ArrayList<>(s.frontends());
    fes3.set(1, withDefaultBackend(fes3.get(1), "app"));
    assertThatThrownBy(() -> HaproxyRenderer.render(replaceFrontends(s, fes3), 1, "")).hasMessageContaining("não pode usar backend");
  }

  @Test
  void keepalivedUnicastPriorityAndVips() {
    var c = Fixtures.cluster("/tmp/x.sock");
    var conf = KeepalivedRenderer.render(c, Fixtures.nodes(), Fixtures.nodes().get(1), 3);
    assertThat(conf).contains("priority 140").contains("unicast_src_ip 192.168.10.12").containsPattern("unicast_peer \\{\\n\\s+192.168.10.11\\n")
        .contains("192.168.10.100/24 dev eth0").contains("virtual_router_id 51");
  }

  @Test
  void awsEipRequiresParamsAndAddsPrivateVip() {
    assertThatThrownBy(() -> Normalizer.cluster("a", new ClusterSettings("aws-eip", null, null, null, null, null, null), null)).hasMessageContaining("aws-eip");
    var n = Normalizer.cluster("a", new ClusterSettings("aws-eip", null, null, null, null, new ClusterSettings.Aws("us-east-1", "eipalloc-0abc", "10.0.1.50"), null), null);
    var c = new Cluster("x", n.name(), n.settings());
    var nodes = List.of(new NodeInfo("n1", "10.0.1.10", null), new NodeInfo("n2", "10.0.1.11", null));
    assertThat(KeepalivedRenderer.render(c, nodes, nodes.get(0), 1)).contains("10.0.1.50/32 dev eth0").contains("unicast_peer");
    assertThat(KeepalivedRenderer.failoverEnv(c)).contains("HAPM_EIP_ALLOCATION_ID=eipalloc-0abc").contains("HAPM_PRIVATE_VIP=10.0.1.50");
  }

  @Test
  void awsCrossSubnetOverlayIpViaRouteTables() {
    var aws = new ClusterSettings.Aws("sa-east-1", null, null, "192.168.250.10/32", List.of("rtb-0aaa1111bbbb2222c rtb-0ccc3333dddd4444e"), null);
    var n = Normalizer.cluster("x", new ClusterSettings("aws-eip", null, null, null, null, aws, null), null);
    assertThat(n.settings().aws().routeVip()).isEqualTo("192.168.250.10");
    assertThat(n.settings().aws().routeTableIds()).containsExactly("rtb-0aaa1111bbbb2222c", "rtb-0ccc3333dddd4444e");
    assertThat(n.settings().aws().manageSourceDestCheck()).isTrue();
    var c = new Cluster("x", n.name(), n.settings());
    var eni = Normalizer.nodeSettings(new dev.hapm.core.model.NodeSettings(null, "ens5", null, null, "eni-0ddd3333eeee4444f"));
    var nodes = List.of(new NodeInfo("a", "10.0.1.10", eni), new NodeInfo("b", "10.0.2.10", null));
    assertThat(KeepalivedRenderer.render(c, nodes, nodes.get(0), 1)).contains("192.168.250.10/32 dev ens5").contains("unicast_peer");
    assertThat(KeepalivedRenderer.failoverEnv(c, nodes.get(0))).contains("HAPM_ROUTE_VIP=192.168.250.10")
        .contains("HAPM_ROUTE_TABLES=rtb-0aaa1111bbbb2222c,rtb-0ccc3333dddd4444e").contains("HAPM_ENI_ID=eni-0ddd3333eeee4444f")
        .contains("HAPM_IFACE=ens5").contains("HAPM_MANAGE_SRC_DST_CHECK=1");
    // o outro nó usa a ENI da própria interface (descoberta via IMDS)
    assertThat(KeepalivedRenderer.failoverEnv(c, nodes.get(1))).doesNotContain("HAPM_ENI_ID").contains("HAPM_IFACE=eth0");
  }

  @Test
  void awsFailoverValidation() {
    var noTables = new ClusterSettings.Aws("sa-east-1", null, null, "192.168.250.10", null, null);
    assertThatThrownBy(() -> Normalizer.cluster("x", new ClusterSettings("aws-eip", null, null, null, null, noTables, null), null))
        .hasMessageContaining("tabelas de rotas");
    var badRtb = new ClusterSettings.Aws("sa-east-1", null, null, "192.168.250.10", List.of("rtb-xyz"), null);
    assertThatThrownBy(() -> Normalizer.cluster("x", new ClusterSettings("aws-eip", null, null, null, null, badRtb, null), null))
        .hasMessageContaining("rtb-xyz");
    var nothing = new ClusterSettings.Aws("sa-east-1", null, null, null, null, null);
    assertThatThrownBy(() -> Normalizer.cluster("x", new ClusterSettings("aws-eip", null, null, null, null, nothing, null), null))
        .hasMessageContaining("ao menos um");
    assertThatThrownBy(() -> Normalizer.nodeSettings(new dev.hapm.core.model.NodeSettings(null, null, null, null, "eni-bad")))
        .hasMessageContaining("ENI");
  }

  @Test
  void bundleDigestIsStable() throws Exception {
    var s = Fixtures.snapshot();
    var b = BundleBuilder.build(s, HaproxyRenderer.render(s, 1, ""), 1, s.nodes().get(0));
    assertThat(b.verify()).isTrue();
    assertThat(b.keepalived()).containsKey("keepalived.conf");
    var tampered = new Bundle(b.version(), b.cluster(), java.util.Map.of("haproxy.cfg", "x"), b.keepalived(), b.sha256());
    assertThat(tampered.verify()).isFalse();
  }

  @Test
  void haproxyAcceptsGeneratedConfig() throws Exception {
    assumeTrue(Fixtures.hasBin("haproxy"), "haproxy não instalado");
    var s = Fixtures.snapshot();
    var b = BundleBuilder.build(s, HaproxyRenderer.render(s, 1, ""), 1, s.nodes().get(0));
    var dir = Files.createTempDirectory("hapm-check");
    for (var e : b.files().entrySet()) {
      var p = dir.resolve(e.getKey());
      Files.createDirectories(p.getParent());
      Files.writeString(p, e.getValue().replace(HaproxyRenderer.RELEASE_DIR, dir.toString()));
    }
    var proc = new ProcessBuilder("haproxy", "-c", "-f", dir.resolve("haproxy.cfg").toString()).redirectErrorStream(true).start();
    var out = new String(proc.getInputStream().readAllBytes());
    assertThat(proc.waitFor()).as(out).isZero();
  }

  @Test
  void keepalivedAcceptsGeneratedConfig() throws Exception {
    assumeTrue(Fixtures.hasBin("keepalived"), "keepalived não instalado");
    var scripts = Path.of("..", "hapm-agent", "src", "main", "dist", "scripts").toAbsolutePath().normalize().toString();
    var conf = KeepalivedRenderer.render(Fixtures.cluster("/tmp/x.sock"), Fixtures.nodes(), Fixtures.nodes().get(0), 1).replace(KeepalivedRenderer.LIB_DIR, scripts);
    var f = Files.createTempFile("keepalived", ".conf");
    Files.writeString(f, conf);
    var proc = new ProcessBuilder("keepalived", "-t", "-f", f.toString(), "-l", "-n").redirectErrorStream(true).start();
    var out = new String(proc.getInputStream().readAllBytes());
    assertThat(proc.waitFor()).as(out).isZero();
  }

  // ---- helpers para "alterar" records
  static Frontend withName(Frontend f, String n) {
    return new Frontend(n, f.enabled(), f.description(), f.mode(), f.binds(), f.defaultBackend(), f.redirectToHttps(), f.hsts(), f.http2(), f.forwardFor(), f.acme(), f.allowCidrs(), f.rateLimit(), f.maxconn(), f.timeoutClient(), f.rules(), f.extra());
  }

  static Frontend withExtra(Frontend f, String e) {
    return new Frontend(f.name(), f.enabled(), f.description(), f.mode(), f.binds(), f.defaultBackend(), f.redirectToHttps(), f.hsts(), f.http2(), f.forwardFor(), f.acme(), f.allowCidrs(), f.rateLimit(), f.maxconn(), f.timeoutClient(), f.rules(), e);
  }

  static Frontend withRules(Frontend f, List<Frontend.Rule> r) {
    return new Frontend(f.name(), f.enabled(), f.description(), f.mode(), f.binds(), f.defaultBackend(), f.redirectToHttps(), f.hsts(), f.http2(), f.forwardFor(), f.acme(), f.allowCidrs(), f.rateLimit(), f.maxconn(), f.timeoutClient(), r, f.extra());
  }

  static Frontend withDefaultBackend(Frontend f, String b) {
    return new Frontend(f.name(), f.enabled(), f.description(), f.mode(), f.binds(), b, f.redirectToHttps(), f.hsts(), f.http2(), f.forwardFor(), f.acme(), f.allowCidrs(), f.rateLimit(), f.maxconn(), f.timeoutClient(), f.rules(), f.extra());
  }

  static Snapshot replaceFrontends(Snapshot s, List<Frontend> fes) {
    return new Snapshot(s.cluster(), fes, s.backends(), s.certificates(), s.nodes());
  }
}
