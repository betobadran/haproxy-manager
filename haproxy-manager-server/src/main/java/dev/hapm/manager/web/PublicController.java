package dev.hapm.manager.web;

import dev.hapm.core.model.Certificate;
import dev.hapm.manager.service.DeployService;
import dev.hapm.manager.store.Store;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints públicos: desafio ACME HTTP-01 e métricas Prometheus do control plane. */
@RestController
public class PublicController {
  private final Store store;
  private final DeployService deploy;

  public PublicController(Store store, DeployService deploy) {
    this.store = store;
    this.deploy = deploy;
  }

  @GetMapping("/.well-known/acme-challenge/{token}")
  public ResponseEntity<String> acme(@PathVariable String token) {
    return store.getChallenge(token).map(v -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(v)).orElse(ResponseEntity.notFound().build());
  }

  private static String esc(String v) {
    return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }

  @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4")
  public String metrics() {
    var info = new StringBuilder("# TYPE hapm_manager_info gauge\nhapm_manager_info{version=\"0.1.0\"} 1\n");
    var online = new StringBuilder("# TYPE hapm_node_online gauge\n");
    var applied = new StringBuilder("# TYPE hapm_node_applied_version gauge\n");
    var master = new StringBuilder("# TYPE hapm_node_vrrp_master gauge\n");
    var seen = new StringBuilder("# TYPE hapm_node_last_seen_timestamp_seconds gauge\n");
    var desired = new StringBuilder("# TYPE hapm_cluster_desired_version gauge\n");
    var failed = new StringBuilder("# TYPE hapm_cluster_deployment_failed gauge\n");
    var progress = new StringBuilder("# TYPE hapm_cluster_deployment_in_progress gauge\n");
    var certs = new StringBuilder("# TYPE hapm_certificate_not_after_timestamp_seconds gauge\n");
    for (var c : store.listClusters()) {
      var cl = esc(c.name());
      store.latestDeployment(c.id()).ifPresent(d -> {
        desired.append("hapm_cluster_desired_version{hapm_cluster=\"").append(cl).append("\"} ").append(d.version()).append('\n');
        failed.append("hapm_cluster_deployment_failed{hapm_cluster=\"").append(cl).append("\"} ").append(d.status().equals("failed") ? 1 : 0).append('\n');
        progress.append("hapm_cluster_deployment_in_progress{hapm_cluster=\"").append(cl).append("\"} ").append(d.status().equals("in_progress") ? 1 : 0).append('\n');
      });
      for (var n : store.listNodes(c.id())) {
        var lbl = "{hapm_cluster=\"" + cl + "\",hapm_node=\"" + esc(n.name()) + "\"} ";
        online.append("hapm_node_online").append(lbl).append(deploy.isOnline(n) ? 1 : 0).append('\n');
        applied.append("hapm_node_applied_version").append(lbl).append(n.appliedVersion()).append('\n');
        master.append("hapm_node_vrrp_master").append(lbl).append("MASTER".equals(n.vrrpState()) ? 1 : 0).append('\n');
        if (n.lastSeen() != null) seen.append("hapm_node_last_seen_timestamp_seconds").append(lbl).append(n.lastSeen() / 1000).append('\n');
      }
      for (var row : store.listObjects(c.id(), DeployService.CERTIFICATE)) {
        var cert = store.fromJson(row.data(), Certificate.class);
        if (cert.meta() != null && cert.meta().notAfter() != null)
          certs.append("hapm_certificate_not_after_timestamp_seconds{hapm_cluster=\"").append(cl).append("\",certificate=\"").append(esc(cert.name()))
              .append("\",source=\"").append(cert.source()).append("\"} ").append(Instant.parse(cert.meta().notAfter()).getEpochSecond()).append('\n');
      }
    }
    return info.append(online).append(applied).append(master).append(seen).append(desired).append(failed).append(progress).append(certs).toString();
  }
}
