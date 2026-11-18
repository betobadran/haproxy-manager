package dev.hapm.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.hapm.core.model.AgentProtocol.Runtime;
import dev.hapm.core.model.AgentProtocol.ServerOverride;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Drain / manutenção / peso pela runtime API de um HAProxy real. */
class RuntimeOpsTest {
  Process haproxy;
  Path dir;

  @AfterEach
  void stop() throws Exception {
    if (haproxy != null) {
      haproxy.destroy();
      haproxy.waitFor();
    }
  }

  static int freePort() throws Exception {
    try (var ss = new java.net.ServerSocket(0)) {
      return ss.getLocalPort();
    }
  }

  Path start() throws Exception {
    assumeTrue(new SystemOps().run(List.of("haproxy", "-v"), 10).ok(), "haproxy não instalado");
    dir = Files.createTempDirectory(Path.of("/tmp"), "hrt");
    var sock = dir.resolve("a.sock");
    var cfg = dir.resolve("h.cfg");
    Files.writeString(cfg, """
        global
            maxconn 100
            stats socket %s mode 600 level admin
        defaults
            mode http
            timeout connect 1s
            timeout client 1s
            timeout server 1s
        listen dummy
            bind 127.0.0.1:%d
        backend web
            server web1 127.0.0.1:1 weight 100
            server web2 127.0.0.1:2 weight 100
            server web3 127.0.0.1:3 weight 100 disabled
        """.formatted(sock, freePort()));
    haproxy = new ProcessBuilder("haproxy", "-db", "-f", cfg.toString()).redirectErrorStream(true).start();
    for (int i = 0; i < 50 && !Files.exists(sock); i++) Thread.sleep(100);
    assertThat(sock).exists();
    return sock;
  }

  @Test
  void reconcilesDrainMaintAndWeight() throws Exception {
    var sock = start();
    var ops = new RuntimeOps(null);
    var r = ops.reconcile(new Runtime(sock.toString(), List.of(
        new ServerOverride("web", "web1", null, 30), new ServerOverride("web", "web2", "drain", null))));
    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.commands()).isEqualTo(2);
    var st = RuntimeOps.parseState(RuntimeOps.command(sock, "show servers state"));
    assertThat(st.get("web/web1").uweight()).isEqualTo(30);
    assertThat(RuntimeOps.current(st.get("web/web2").admin())).isEqualTo("drain");
    // web3 está "disabled" na configuração: continua em manutenção (o agente não mexe sem override)
    assertThat(st.get("web/web3").admin() & RuntimeOps.CMAINT).isNotZero();
    assertThat(RuntimeOps.current(st.get("web/web3").admin())).isEqualTo("maint");

    // idempotente: nada a fazer na segunda vez
    assertThat(ops.reconcile(new Runtime(sock.toString(), List.of(
        new ServerOverride("web", "web1", null, 30), new ServerOverride("web", "web2", "drain", null)))).commands()).isZero();

    // troca para manutenção e depois remove tudo: volta ao estado/peso da configuração
    ops.reconcile(new Runtime(sock.toString(), List.of(new ServerOverride("web", "web1", null, 30), new ServerOverride("web", "web2", "maint", null))));
    assertThat(RuntimeOps.current(RuntimeOps.parseState(RuntimeOps.command(sock, "show servers state")).get("web/web2").admin())).isEqualTo("maint");
    r = ops.reconcile(new Runtime(sock.toString(), List.of()));
    assertThat(r.ok()).isTrue();
    st = RuntimeOps.parseState(RuntimeOps.command(sock, "show servers state"));
    assertThat(st.get("web/web1").uweight()).isEqualTo(100);
    assertThat(RuntimeOps.current(st.get("web/web2").admin())).isEqualTo("ready");

    // servidor inexistente vira erro reportado ao manager
    r = ops.reconcile(new Runtime(sock.toString(), List.of(new ServerOverride("web", "nope", "drain", null))));
    assertThat(r.ok()).isFalse();
    assertThat(r.error()).contains("web/nope");
  }
}
