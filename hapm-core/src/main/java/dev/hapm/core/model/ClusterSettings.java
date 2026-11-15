package dev.hapm.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Configurações de um cluster. Campos nulos na entrada recebem padrão no {@code Normalizer}. */
public record ClusterSettings(
    String mode,
    String rollout,
    Boolean autoDeployOnCertRenew,
    Haproxy haproxy,
    Keepalived keepalived,
    Aws aws,
    Acme acme) {

  public static final String ONPREM_KEEPALIVED = "onprem-keepalived";
  public static final String AWS_NLB = "aws-nlb";
  public static final String AWS_EIP = "aws-eip";

  public boolean usesKeepalived() {
    return ONPREM_KEEPALIVED.equals(mode) || AWS_EIP.equals(mode);
  }

  public record Haproxy(
      Integer maxconn,
      Integer nbthread,
      String log,
      String user,
      String statsSocket,
      Integer statsPort,
      String statsUser,
      String statsPassword,
      String sslMinVer,
      String defaultCertificate,
      Timeouts timeouts,
      String globalExtra,
      String defaultsExtra) {}

  public record Timeouts(
      String connect, String client, String server, String httpRequest, String httpKeepAlive, String queue, String tunnel) {}

  public record Keepalived(
      @JsonProperty("interface") String iface,
      Integer routerId,
      List<String> vips,
      String authPass,
      Integer advertInt,
      Boolean unicast,
      Boolean preempt,
      Integer checkInterval,
      Integer checkFall,
      Integer checkRise) {}

  public record Aws(String region, String eipAllocationId, String privateVip) {}

  /** backend = host:porta onde o HAProxy alcança o manager para responder o desafio HTTP-01. */
  public record Acme(String email, String backend) {}
}
