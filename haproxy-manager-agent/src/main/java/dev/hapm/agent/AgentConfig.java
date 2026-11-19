package dev.hapm.agent;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/** Configuração do agente via variáveis de ambiente (arquivo /etc/hapm/agent.env no systemd). */
public record AgentConfig(
    String version,
    String managerUrl,
    String joinToken,
    String nodeName,
    String nodeAddress,
    int pollIntervalSec,
    String haproxyBin,
    Path haproxyDir,
    String reloadMode,
    String reloadCommand,
    Path masterSocket,
    int statsPort,
    String healthUrl,
    int keepReleases,
    String keepalivedBin,
    Path keepalivedDir,
    String keepalivedReloadCommand,
    Path vrrpStateFile,
    Path stateDir,
    int metricsPort,
    String metricsHost,
    Path libDir) {

  public static final String VERSION = "0.1.0";

  public static AgentConfig fromEnv(Map<String, String> env) {
    var g = (java.util.function.BiFunction<String, String, String>) (k, d) -> {
      var v = env.get(k);
      return v == null || v.isBlank() ? d : v.strip();
    };
    var iface = g.apply("HAPM_NODE_INTERFACE", "");
    var statsPort = Integer.parseInt(g.apply("HAPM_STATS_PORT", "8405"));
    return new AgentConfig(
        VERSION,
        g.apply("HAPM_MANAGER_URL", "http://127.0.0.1:8080").replaceAll("/$", ""),
        g.apply("HAPM_JOIN_TOKEN", ""),
        g.apply("HAPM_NODE_NAME", hostname()),
        g.apply("HAPM_NODE_ADDRESS", detectAddress(iface)),
        Integer.parseInt(g.apply("HAPM_POLL_INTERVAL", "5")),
        g.apply("HAPM_HAPROXY_BIN", "haproxy"),
        Path.of(g.apply("HAPM_HAPROXY_DIR", "/etc/haproxy/hapm")),
        g.apply("HAPM_RELOAD_MODE", "systemd"),
        g.apply("HAPM_RELOAD_COMMAND", "systemctl reload-or-restart haproxy"),
        Path.of(g.apply("HAPM_MASTER_SOCKET", "/run/haproxy-master.sock")),
        statsPort,
        g.apply("HAPM_HEALTH_URL", "http://127.0.0.1:" + statsPort + "/healthz"),
        Integer.parseInt(g.apply("HAPM_KEEP_RELEASES", "10")),
        g.apply("HAPM_KEEPALIVED_BIN", "keepalived"),
        Path.of(g.apply("HAPM_KEEPALIVED_DIR", "/etc/keepalived")),
        g.apply("HAPM_KEEPALIVED_RELOAD_COMMAND", "systemctl reload-or-restart keepalived"),
        Path.of(g.apply("HAPM_VRRP_STATE_FILE", "/var/lib/hapm/vrrp_state")),
        Path.of(g.apply("HAPM_STATE_DIR", "/var/lib/hapm")),
        Integer.parseInt(g.apply("HAPM_METRICS_PORT", "9101")),
        g.apply("HAPM_METRICS_HOST", "0.0.0.0"),
        Path.of(g.apply("HAPM_LIB_DIR", "/usr/local/lib/hapm")));
  }

  static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName().split("\\.")[0];
    } catch (Exception e) {
      var h = System.getenv("HOSTNAME");
      return h == null ? "node" : h;
    }
  }

  /** Primeiro IPv4 não-loopback (da interface indicada, se houver). */
  static String detectAddress(String iface) {
    try {
      for (var ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (ni.isLoopback() || !ni.isUp()) continue;
        if (!iface.isEmpty() && !ni.getName().equals(iface)) continue;
        for (var a : Collections.list(ni.getInetAddresses())) if (a instanceof Inet4Address) return a.getHostAddress();
      }
    } catch (Exception ignored) {
      // sem rede
    }
    return null;
  }
}
