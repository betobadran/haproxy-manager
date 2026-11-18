package dev.hapm.manager.service;

import dev.hapm.core.model.ClusterSettings;
import dev.hapm.manager.store.Store.ClusterRow;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

/**
 * Credenciais e clientes AWS do manager, por cluster.
 *
 * <ul>
 *   <li>sem role configurada: cadeia padrão do SDK (role da EC2/ECS, IRSA no EKS, SSO, perfil, variáveis de ambiente) —
 *       nenhuma chave fica no HAPM;</li>
 *   <li>com role: {@code sts:AssumeRole} (ExternalId opcional) com credenciais temporárias renovadas automaticamente;
 *       a sessão se chama {@code hapm-<usuário>} (ações da tela) ou {@code hapm-<cluster>} (rotinas), visível no CloudTrail.</li>
 * </ul>
 * Endpoints podem ser sobrescritos (hapm.aws.*-endpoint) para testes ou VPC endpoints.
 */
@Service
public class AwsClients {
  private final String stsEndpoint;
  private final String acmEndpoint;
  private final String route53Endpoint;
  private final AwsCredentialsProvider base;
  private final Map<String, StsAssumeRoleCredentialsProvider> assumed = new ConcurrentHashMap<>();

  public AwsClients(@Value("${hapm.aws.sts-endpoint:}") String stsEndpoint, @Value("${hapm.aws.acm-endpoint:}") String acmEndpoint,
                    @Value("${hapm.aws.route53-endpoint:}") String route53Endpoint) {
    this.stsEndpoint = stsEndpoint;
    this.acmEndpoint = acmEndpoint;
    this.route53Endpoint = route53Endpoint;
    this.base = DefaultCredentialsProvider.builder().build();
  }

  public record Access(String region, String roleArn, String externalId, int sessionMinutes) {}

  public static Access access(ClusterRow c) {
    var a = c.settings().awsAccess();
    if (a == null) a = new ClusterSettings.AwsAccess(null, null, null, null);
    var region = a.region() != null && !a.region().isBlank() ? a.region()
        : c.settings().aws() != null && c.settings().aws().region() != null && !c.settings().aws().region().isBlank() ? c.settings().aws().region() : "us-east-1";
    return new Access(region, a.roleArn() == null ? "" : a.roleArn(), a.externalId() == null ? "" : a.externalId(),
        a.sessionMinutes() == null ? 60 : a.sessionMinutes());
  }

  /** Nome de sessão aceito pelo STS: [\w+=,.@-], 2 a 64 caracteres. */
  static String sessionName(String who) {
    var s = ("hapm-" + (who == null || who.isBlank() ? "rotina" : who)).replaceAll("[^A-Za-z0-9+=,.@_-]", "_");
    return s.length() > 64 ? s.substring(0, 64) : s;
  }

  private <B extends software.amazon.awssdk.awscore.client.builder.AwsClientBuilder<B, ?>> B endpoint(B b, String ep) {
    if (ep != null && !ep.isBlank()) b.endpointOverride(URI.create(ep));
    return b;
  }

  StsClient sts(String region, AwsCredentialsProvider creds) {
    return endpoint(StsClient.builder().region(Region.of(region)).credentialsProvider(creds), stsEndpoint).build();
  }

  /** Credenciais do cluster; {@code who} = usuário do HAPM (ou null para rotinas automáticas). */
  public AwsCredentialsProvider credentials(ClusterRow c, String who) {
    var a = access(c);
    if (a.roleArn().isEmpty()) return base;
    var session = sessionName(who != null ? who : c.name());
    var key = String.join("|", c.id(), a.roleArn(), a.externalId(), session, String.valueOf(a.sessionMinutes()));
    return assumed.computeIfAbsent(key, k -> StsAssumeRoleCredentialsProvider.builder()
        .stsClient(sts(a.region(), base))
        .refreshRequest(r -> {
          r.roleArn(a.roleArn()).roleSessionName(session).durationSeconds(a.sessionMinutes() * 60);
          if (!a.externalId().isEmpty()) r.externalId(a.externalId());
        })
        .staleTime(Duration.ofMinutes(2))
        .build());
  }

  /** Configuração mudou (role, ExternalId): descarta as credenciais em cache do cluster. */
  public void forget(String clusterId) {
    assumed.keySet().removeIf(k -> k.startsWith(clusterId + "|"));
  }

  public AcmClient acm(ClusterRow c, String who) {
    return endpoint(AcmClient.builder().region(Region.of(access(c).region())).credentialsProvider(credentials(c, who)), acmEndpoint).build();
  }

  public Route53Client route53(ClusterRow c, String who) {
    return endpoint(Route53Client.builder().region(Region.AWS_GLOBAL).credentialsProvider(credentials(c, who)), route53Endpoint).build();
  }

  // ------------------------------------------------------------------ teste de acesso (botão na tela)
  public record Check(String status, String message) {}

  public record TestResult(boolean ok, String identity, String mode, List<Check> checks) {}

  static String reason(Exception e) {
    if (e instanceof AwsServiceException s) {
      var code = s.awsErrorDetails() == null ? null : s.awsErrorDetails().errorCode();
      return (code == null ? "" : code + ": ") + (s.awsErrorDetails() == null ? s.getMessage() : s.awsErrorDetails().errorMessage());
    }
    if (e instanceof SdkClientException) return "sem credenciais ou sem acesso à AWS: " + e.getMessage();
    return e.getMessage();
  }

  /** Quem sou eu na AWS e o que consigo fazer (somente leitura). */
  public TestResult test(ClusterRow c, String who) {
    var a = access(c);
    var checks = new ArrayList<Check>();
    String identity = null;
    var mode = a.roleArn().isEmpty() ? "cadeia padrão do SDK (sem chaves no HAPM)" : "AssumeRole " + a.roleArn() + (a.externalId().isEmpty() ? "" : " (com ExternalId)");
    try (var sts = sts(a.region(), credentials(c, who))) {
      identity = sts.getCallerIdentity().arn();
      checks.add(new Check("OK", "identidade: " + identity));
    } catch (Exception e) {
      checks.add(new Check("FAIL", "não foi possível obter credenciais: " + reason(e)));
      return new TestResult(false, null, mode, checks);
    }
    try (var acm = acm(c, who)) {
      var n = acm.listCertificates(r -> r.maxItems(10)).certificateSummaryList().size();
      checks.add(new Check("OK", "ACM (" + a.region() + "): leitura permitida (" + n + " certificado(s) visíveis)"));
    } catch (Exception e) {
      checks.add(new Check("WARN", "ACM: " + reason(e) + " — necessário para certificados do ACM"));
    }
    try (var r53 = route53(c, who)) {
      var n = r53.listHostedZones(r -> r.maxItems("20")).hostedZones().size();
      checks.add(new Check("OK", "Route 53: leitura permitida (" + n + " zona(s) visíveis)"));
    } catch (Exception e) {
      checks.add(new Check("WARN", "Route 53: " + reason(e) + " — necessário para validar certificados ACM por DNS automaticamente"));
    }
    return new TestResult(checks.stream().noneMatch(x -> x.status().equals("FAIL")), identity, mode, checks);
  }
}
