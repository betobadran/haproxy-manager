package dev.hapm.core.model;

import com.fasterxml.jackson.annotation.JsonFormat;
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
    Acme acme,
    Approval approval,
    Nodes nodes,
    Logs logs,
    AwsAccess awsAccess) {

  /** Compatibilidade: configurações sem política de aprovação explícita. */
  public ClusterSettings(String mode, String rollout, Boolean autoDeployOnCertRenew, Haproxy haproxy, Keepalived keepalived, Aws aws, Acme acme) {
    this(mode, rollout, autoDeployOnCertRenew, haproxy, keepalived, aws, acme, null, null, null, null);
  }

  public ClusterSettings(String mode, String rollout, Boolean autoDeployOnCertRenew, Haproxy haproxy, Keepalived keepalived, Aws aws, Acme acme,
                         Approval approval) {
    this(mode, rollout, autoDeployOnCertRenew, haproxy, keepalived, aws, acme, approval, null, null, null);
  }

  public ClusterSettings(String mode, String rollout, Boolean autoDeployOnCertRenew, Haproxy haproxy, Keepalived keepalived, Aws aws, Acme acme,
                         Approval approval, Nodes nodes) {
    this(mode, rollout, autoDeployOnCertRenew, haproxy, keepalived, aws, acme, approval, nodes, null, null);
  }

  public ClusterSettings(String mode, String rollout, Boolean autoDeployOnCertRenew, Haproxy haproxy, Keepalived keepalived, Aws aws, Acme acme,
                         Approval approval, Nodes nodes, Logs logs) {
    this(mode, rollout, autoDeployOnCertRenew, haproxy, keepalived, aws, acme, approval, nodes, logs, null);
  }

  public boolean approvalRequired() {
    return approval != null && Boolean.TRUE.equals(approval.required());
  }

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
      @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> vips,
      String authPass,
      Integer advertInt,
      Boolean unicast,
      Boolean preempt,
      Integer checkInterval,
      Integer checkFall,
      Integer checkRise) {}

  /**
   * Failover de IP na AWS (modo aws-eip). Combine conforme a topologia:
   * <ul>
   *   <li>privateVip: IP privado secundário movido entre ENIs — nós na MESMA subnet;</li>
   *   <li>routeVip + routeTableIds: IP de "overlay" (fora do CIDR da VPC) cuja rota /32 aponta para a ENI do MASTER —
   *       funciona com nós em subnets/AZs DIFERENTES (exige source/dest check desligado nas ENIs);</li>
   *   <li>eipAllocationId: Elastic IP público que acompanha o MASTER (entre AZs também).</li>
   * </ul>
   */
  public record Aws(String region, String eipAllocationId, String privateVip, String routeVip,
                    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> routeTableIds,
                    Boolean manageSourceDestCheck, String failoverRoleArn, String failoverExternalId) {
    public Aws(String region, String eipAllocationId, String privateVip) {
      this(region, eipAllocationId, privateVip, null, null, null, null, null);
    }

    public Aws(String region, String eipAllocationId, String privateVip, String routeVip, List<String> routeTableIds, Boolean manageSourceDestCheck) {
      this(region, eipAllocationId, privateVip, routeVip, routeTableIds, manageSourceDestCheck, null, null);
    }
  }

  /**
   * Acesso do MANAGER à AWS (certificados ACM, Route 53). Sem roleArn usa a cadeia padrão do SDK (role da EC2/ECS,
   * IRSA, SSO, variáveis de ambiente); com roleArn assume essa role via STS (outra conta, com ExternalId), em sessões
   * nomeadas com o usuário do HAPM (rastreáveis no CloudTrail).
   */
  public record AwsAccess(String region, String roleArn, String externalId, Integer sessionMinutes) {
    public boolean assumesRole() {
      return roleArn != null && !roleArn.isBlank();
    }
  }

  /** backend = host:porta onde o HAProxy alcança o manager para responder o desafio HTTP-01. */
  public record Acme(String email, String backend) {}

  /**
   * Governança de publicação: com {@code required}, mudanças só vão para os nós depois que um usuário com
   * papel APPROVER aprovar o pedido. {@code allowSelfApproval=false} exige outra pessoa (quatro olhos).
   */
  public record Approval(Boolean required, Boolean allowSelfApproval) {}

  /** Ciclo de vida dos nós. autoRemoveOfflineMinutes: remove do cluster nós sem heartbeat há N minutos (null/0 = nunca). */
  public record Nodes(Integer autoRemoveOfflineMinutes) {}

  /**
   * Logs de acesso estruturados (logfmt) via syslog RFC 5424.
   * collector: OTel Collector local do nó (host:porta UDP, padrão 127.0.0.1:5514) que encaminha para Loki/SIEM;
   * siem: destinos syslog UDP adicionais (host:porta), enviados direto pelo HAProxy; userAgent: inclui o User-Agent.
   */
  public record Logs(Boolean enabled, String collector, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> siem,
                     Boolean userAgent) {
    public boolean on() {
      return Boolean.TRUE.equals(enabled);
    }
  }
}
