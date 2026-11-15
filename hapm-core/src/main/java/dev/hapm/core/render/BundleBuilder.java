package dev.hapm.core.render;

import dev.hapm.core.model.Bundle;
import dev.hapm.core.model.NodeInfo;
import dev.hapm.core.model.Snapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Monta o bundle que um nó baixa: haproxy.cfg + certificados + keepalived.conf do próprio nó. */
public final class BundleBuilder {
  private BundleBuilder() {}

  public static Bundle build(Snapshot snap, String haproxyCfg, int version, NodeInfo node) {
    var files = new LinkedHashMap<String, String>();
    files.put("haproxy.cfg", haproxyCfg);
    files.put("certs.list", HaproxyRenderer.crtList(snap.cluster(), snap.certificates()));
    for (var c : HaproxyRenderer.orderCertificates(snap.cluster(), snap.certificates())) files.put("certs/" + c.name() + ".pem", c.pem());

    LinkedHashMap<String, String> keepalived = null;
    if (snap.cluster().settings().usesKeepalived()) {
      // nó que entrou depois da publicação (ex.: scale-out) usa os próprios dados
      var nodes = new ArrayList<>(snap.nodes());
      var self = nodes.stream().filter(n -> n.name().equals(node.name())).findFirst().orElse(null);
      if (self == null) {
        self = node;
        nodes.add(node);
      } else if (node.address() != null) {
        self = new NodeInfo(self.name(), node.address(), self.settings());
      }
      keepalived = new LinkedHashMap<>();
      keepalived.put("keepalived.conf", KeepalivedRenderer.render(snap.cluster(), nodes, self, version));
      keepalived.put("hapm-failover.env", KeepalivedRenderer.failoverEnv(snap.cluster()));
    }
    return new Bundle(version, snap.cluster().name(), files, keepalived, Bundle.digest(version, files, keepalived));
  }
}
