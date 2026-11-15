package dev.hapm.core.render;

import dev.hapm.core.model.Cluster;
import dev.hapm.core.model.ClusterSettings;
import dev.hapm.core.model.NodeInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** keepalived.conf por nó. VRRP unicast por padrão: funciona onde multicast é bloqueado (e na AWS). */
public final class KeepalivedRenderer {
  private KeepalivedRenderer() {}

  public static final String LIB_DIR = "/usr/local/lib/hapm";

  /** Prioridade explícita do nó ou automática (ordem alfabética: 150, 140, 130...). */
  public static int priority(List<NodeInfo> nodes, NodeInfo node) {
    var p = node.settingsOrEmpty().priority();
    if (p != null) return p;
    var sorted = nodes.stream().filter(n -> !n.settingsOrEmpty().isDisabled()).sorted(Comparator.comparing(NodeInfo::name)).toList();
    int i = 0;
    for (; i < sorted.size(); i++) if (sorted.get(i).name().equals(node.name())) break;
    if (i == sorted.size()) i = 0;
    return Math.max(1, 150 - i * 10);
  }

  public static String render(Cluster cluster, List<NodeInfo> nodes, NodeInfo node, int version) {
    var s = cluster.settings();
    var k = s.keepalived();
    var nodeIface = node.settingsOrEmpty().iface();
    var iface = nodeIface != null && !nodeIface.isEmpty() ? nodeIface : k.iface();
    var vips = new ArrayList<>(k.vips());
    if (ClusterSettings.AWS_EIP.equals(s.mode()) && !s.aws().privateVip().isEmpty()) vips.add(s.aws().privateVip() + "/32");
    var self = node.vrrpAddress();
    var peers = nodes.stream().filter(n -> !n.name().equals(node.name()) && !n.settingsOrEmpty().isDisabled())
        .map(NodeInfo::vrrpAddress).filter(Objects::nonNull).toList();
    boolean unicast = ClusterSettings.AWS_EIP.equals(s.mode()) || k.unicast(); // AWS não tem multicast

    var L = new ArrayList<String>();
    L.add("# Gerado pelo HAPM (HAProxy HA Manager) - NÃO EDITE MANUALMENTE");
    L.add("# cluster: " + cluster.name() + "   nó: " + node.name() + "   versão: " + version);
    L.add("global_defs {");
    L.add("    router_id " + node.name().replaceAll("[^A-Za-z0-9_.-]", "_"));
    L.add("    enable_script_security");
    L.add("    script_user root");
    L.add("    vrrp_garp_master_refresh 60");
    L.add("}");
    L.add("");
    L.add("vrrp_script chk_haproxy {");
    L.add("    script \"" + LIB_DIR + "/check-haproxy.sh " + s.haproxy().statsPort() + "\"");
    L.add("    interval " + k.checkInterval());
    L.add("    timeout " + (k.checkInterval() + 1));
    L.add("    fall " + k.checkFall());
    L.add("    rise " + k.checkRise());
    L.add("}");
    L.add("");
    L.add("vrrp_instance VI_" + k.routerId() + " {");
    L.add("    state BACKUP");
    L.add("    interface " + iface);
    L.add("    virtual_router_id " + k.routerId());
    L.add("    priority " + priority(nodes, node));
    L.add("    advert_int " + k.advertInt());
    if (!k.preempt()) L.add("    nopreempt");
    if (unicast && self != null) {
      L.add("    unicast_src_ip " + self);
      L.add("    unicast_peer {");
      for (var p : peers) L.add("        " + p);
      L.add("    }");
    }
    L.add("    authentication {");
    L.add("        auth_type PASS");
    L.add("        auth_pass " + k.authPass());
    L.add("    }");
    if (!vips.isEmpty()) {
      L.add("    virtual_ipaddress {");
      for (var v : vips) L.add("        " + (v.contains("/") ? v : v + "/32") + " dev " + iface);
      L.add("    }");
    }
    L.add("    track_script {");
    L.add("        chk_haproxy");
    L.add("    }");
    L.add("    notify \"" + LIB_DIR + "/keepalived-notify.sh\"");
    L.add("}");
    L.add("");
    return String.join("\n", L);
  }

  /** Variáveis usadas pelo keepalived-notify.sh para mover EIP / IP privado no modo aws-eip. */
  public static String failoverEnv(Cluster cluster) {
    var s = cluster.settings();
    var L = new ArrayList<String>();
    L.add("# Gerado pelo HAPM - usado por keepalived-notify.sh no modo aws-eip");
    L.add("HAPM_MODE=" + s.mode());
    if (ClusterSettings.AWS_EIP.equals(s.mode())) {
      L.add("HAPM_AWS_REGION=" + s.aws().region());
      if (!s.aws().eipAllocationId().isEmpty()) L.add("HAPM_EIP_ALLOCATION_ID=" + s.aws().eipAllocationId());
      if (!s.aws().privateVip().isEmpty()) L.add("HAPM_PRIVATE_VIP=" + s.aws().privateVip());
    }
    return String.join("\n", L) + "\n";
  }
}
