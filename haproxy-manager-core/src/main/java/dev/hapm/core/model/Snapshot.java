package dev.hapm.core.model;

import java.util.List;

/** Estado imutável de um cluster no momento da publicação (permite rollback exato). */
public record Snapshot(Cluster cluster, List<Frontend> frontends, List<Backend> backends, List<Certificate> certificates, List<NodeInfo> nodes) {

  public Snapshot withNodesAndCertificates(List<NodeInfo> newNodes, List<Certificate> newCerts) {
    return new Snapshot(cluster, frontends, backends, newCerts, newNodes);
  }
}
