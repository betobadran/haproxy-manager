package dev.hapm.manager.service;

import dev.hapm.core.model.Certificate;
import dev.hapm.manager.store.Store;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.acm.model.CertificateDetail;
import software.amazon.awssdk.services.acm.model.CertificateStatus;
import software.amazon.awssdk.services.acm.model.ValidationMethod;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.HostedZone;
import software.amazon.awssdk.services.route53.model.RRType;

/**
 * Certificados do AWS Certificate Manager (públicos exportáveis, desde jun/2025).
 *
 * <ol>
 *   <li>pede o certificado com {@code Export=ENABLED} e validação por DNS (ou usa um ARN exportável existente);</li>
 *   <li>cria os CNAMEs de validação no Route 53 (zona descoberta pelo nome, ou informada) — ou mostra os registros
 *       para criação manual;</li>
 *   <li>quando o ACM emite, exporta (chave privada cifrada com uma passphrase aleatória, decifrada aqui), guarda como
 *       qualquer certificado e publica;</li>
 *   <li>o ACM renova sozinho; o HAPM percebe a nova validade, exporta de novo e publica.</li>
 * </ol>
 * Atenção: cada emissão/renovação exportável é cobrada pela AWS.
 */
@Service
public class AcmService {
  private static final Logger log = LoggerFactory.getLogger(AcmService.class);
  private static final SecureRandom RNG = new SecureRandom();

  private final Store store;
  private final AwsClients aws;
  private final PemService pem;
  private final DeployService deploy;
  private final NotificationService notifications;
  private final Set<String> running = ConcurrentHashMap.newKeySet();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public AcmService(Store store, AwsClients aws, PemService pem, DeployService deploy, NotificationService notifications) {
    this.store = store;
    this.aws = aws;
    this.pem = pem;
    this.deploy = deploy;
    this.notifications = notifications;
  }

  private Certificate load(String cid, String certId) {
    return store.fromJson(store.getObject(cid, DeployService.CERTIFICATE, certId).orElseThrow().data(), Certificate.class);
  }

  private void save(String cid, String certId, Certificate c) {
    store.updateObject(cid, DeployService.CERTIFICATE, certId, c.name(), c);
  }

  private Certificate.AwsInfo with(Certificate.AwsInfo a, String arn, String status, String error, List<Certificate.DnsRecord> records, String exportedAt) {
    return new Certificate.AwsInfo(arn != null ? arn : a.arn(), a.domains(), a.validation(), a.hostedZoneId(), status, error,
        records != null ? records : a.records(), exportedAt != null ? exportedAt : a.exportedAt(), Instant.now().toString());
  }

  /** Dispara pedido/importação em background (botão "emitir" e criação do certificado). */
  public boolean start(String cid, String certId, String user) {
    var key = cid + "/" + certId;
    if (!running.add(key)) return false;
    executor.submit(() -> {
      try {
        step(cid, certId, user);
      } catch (Exception e) {
        fail(cid, certId, user, e);
      } finally {
        running.remove(key);
      }
    });
    return true;
  }

  void fail(String cid, String certId, String user, Exception e) {
    var msg = e instanceof software.amazon.awssdk.awscore.exception.AwsServiceException || e instanceof software.amazon.awssdk.core.exception.SdkClientException
        ? AwsClients.reason(e) : String.valueOf(e.getMessage());
    log.warn("ACM: certificado {} falhou: {}", certId, msg);
    try {
      var c = load(cid, certId);
      save(cid, certId, c.withAws(with(c.aws(), null, "error", msg, null, null)));
      store.audit(user, cid, "certificate.acm.error", c.name(), Map.of("error", msg));
      notifications.notify("critical", "acm-failed", cid, "Certificado ACM " + c.name() + ": falha", msg, Map.of("certificate", c.name()), null);
    } catch (Exception ignored) {
      // certificado removido no meio do caminho
    }
  }

  /** Avança o ciclo de um certificado: pedir → validar (DNS) → exportar. Idempotente. */
  public synchronized void step(String cid, String certId, String user) throws Exception {
    var cluster = store.getCluster(cid).orElseThrow();
    var c = load(cid, certId);
    var a = c.aws();
    try (var acm = aws.acm(cluster, user)) {
      var arn = a.arn();
      if (arn == null || arn.isEmpty()) {
        var token = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((cid + "/" + certId).getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        var domains = a.domains();
        var certName = c.name();
        var sans = domains.size() > 1 ? domains : List.<String>of();
        arn = acm.requestCertificate(r -> r.domainName(domains.get(0)).subjectAlternativeNames(sans.isEmpty() ? null : sans)
            .validationMethod(ValidationMethod.DNS).idempotencyToken(token).options(o -> o.export("ENABLED"))
            .tags(t -> t.key("hapm-cluster").value(cluster.name()), t -> t.key("hapm-certificate").value(certName))).certificateArn();
        save(cid, certId, c.withAws(with(a, arn, "requested", null, null, null)));
        store.audit(user, cid, "certificate.acm.requested", c.name(), Map.of("arn", arn, "domains", a.domains()));
        log.info("ACM: certificado {} pedido ({})", c.name(), arn);
        c = load(cid, certId);
        a = c.aws();
      }
      var finalArn = arn;
      var d = acm.describeCertificate(r -> r.certificateArn(finalArn)).certificate();
      if (d.status() == CertificateStatus.ISSUED) {
        export(cluster, certId, c, d, user);
        return;
      }
      if (d.status() != CertificateStatus.PENDING_VALIDATION) {
        throw new IllegalStateException("certificado no ACM com status " + d.statusAsString()
            + (d.failureReasonAsString() == null ? "" : " (" + d.failureReasonAsString() + ")"));
      }
      var records = records(d);
      var status = "pending-validation";
      String error = null;
      if (!records.isEmpty() && "route53".equals(a.validation())) {
        try {
          upsertRecords(cluster, a.hostedZoneId(), records, user);
        } catch (Exception e) {
          error = "Route 53: " + AwsClients.reason(e) + " — crie os registros manualmente ou ajuste as permissões";
        }
      }
      save(cid, certId, c.withAws(with(a, arn, status, error, records, null)));
    }
  }

  static List<Certificate.DnsRecord> records(CertificateDetail d) {
    var out = new ArrayList<Certificate.DnsRecord>();
    if (d.domainValidationOptions() == null) return out;
    for (var o : d.domainValidationOptions()) {
      var r = o.resourceRecord();
      if (r == null) continue; // o ACM leva alguns segundos para gerar
      var rec = new Certificate.DnsRecord(r.name(), r.typeAsString(), r.value());
      if (!out.contains(rec)) out.add(rec);
    }
    return out;
  }

  /** Cria/atualiza os CNAMEs de validação na zona pública que contém cada nome. */
  void upsertRecords(Store.ClusterRow cluster, String zoneId, List<Certificate.DnsRecord> records, String user) {
    try (var r53 = aws.route53(cluster, user)) {
      var zones = new ArrayList<HostedZone>();
      if (zoneId == null || zoneId.isEmpty()) {
        r53.listHostedZonesPaginator(r -> r.maxItems("100")).hostedZones().forEach(zones::add);
      }
      var byZone = new LinkedHashMap<String, List<Certificate.DnsRecord>>();
      for (var rec : records) {
        var z = zoneId != null && !zoneId.isEmpty() ? zoneId : zones.stream()
            .filter(h -> h.config() == null || !Boolean.TRUE.equals(h.config().privateZone()))
            .filter(h -> rec.name().endsWith("." + h.name()) || rec.name().equals(h.name()))
            .max(java.util.Comparator.comparingInt(h -> h.name().length()))
            .map(h -> h.id().replaceFirst("^/hostedzone/", ""))
            .orElseThrow(() -> new IllegalStateException("nenhuma zona pública do Route 53 contém " + rec.name()));
        byZone.computeIfAbsent(z, k -> new ArrayList<>()).add(rec);
      }
      for (var e : byZone.entrySet()) {
        r53.changeResourceRecordSets(r -> r.hostedZoneId(e.getKey()).changeBatch(b -> b.comment("HAPM: validação de certificado ACM")
            .changes(e.getValue().stream().map(rec -> software.amazon.awssdk.services.route53.model.Change.builder().action(ChangeAction.UPSERT)
                .resourceRecordSet(s -> s.name(rec.name()).type(RRType.fromValue(rec.type())).ttl(300L).resourceRecords(v -> v.value(rec.value())))
                .build()).toList())));
        log.info("Route 53: {} registro(s) de validação ACM na zona {}", e.getValue().size(), e.getKey());
      }
    }
  }

  /** Exporta do ACM, decifra a chave, guarda e (se configurado) publica. */
  void export(Store.ClusterRow cluster, String certId, Certificate c, CertificateDetail d, String user) throws Exception {
    var pass = new byte[24];
    RNG.nextBytes(pass);
    var passphrase = Base64.getUrlEncoder().withoutPadding().encodeToString(pass);
    String certPem, chain, keyPem;
    try (var acm = aws.acm(cluster, user)) {
      var r = acm.exportCertificate(x -> x.certificateArn(d.certificateArn()).passphrase(SdkBytes.fromString(passphrase, StandardCharsets.UTF_8)));
      certPem = r.certificate();
      chain = r.certificateChain();
      keyPem = decrypt(r.privateKey(), passphrase);
    }
    var parsed = pem.parse(certPem.strip() + "\n" + (chain == null ? "" : chain.strip() + "\n") + keyPem);
    var renewed = c.meta() != null && c.meta().notAfter() != null && !c.meta().notAfter().equals(parsed.meta().notAfter());
    var now = Instant.now().toString();
    save(cluster.id(), certId, c.withPem(parsed.pem(), parsed.meta()).withAws(with(c.aws(), d.certificateArn(), "issued", null, null, now)));
    store.audit(user, cluster.id(), renewed ? "certificate.acm.renewed" : "certificate.acm.exported", c.name(),
        Map.of("arn", d.certificateArn(), "notAfter", parsed.meta().notAfter()));
    log.info("ACM: certificado {} exportado (expira {})", c.name(), parsed.meta().notAfter());
    if (cluster.settings().autoDeployOnCertRenew()) {
      try {
        deploy.refreshCertificates(cluster.id(), "certificado ACM " + c.name() + (renewed ? " renovado" : " emitido"), user == null ? "acm" : user);
      } catch (Exception e) {
        log.warn("publicação automática após exportar do ACM falhou: {}", e.getMessage());
      }
    }
  }

  /** Chave do ExportCertificate: PKCS#8 cifrada (ENCRYPTED PRIVATE KEY) com a passphrase. */
  static String decrypt(String encryptedPem, String passphrase) throws Exception {
    try (var p = new PEMParser(new StringReader(encryptedPem))) {
      var o = p.readObject();
      if (!(o instanceof PKCS8EncryptedPrivateKeyInfo enc)) throw new IllegalStateException("chave exportada em formato inesperado");
      var info = enc.decryptPrivateKeyInfo(new JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
          .build(passphrase.toCharArray()));
      var sw = new StringWriter();
      try (var w = new JcaPEMWriter(sw)) {
        w.writeObject(new org.bouncycastle.openssl.jcajce.JcaPKCS8Generator(
            new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter().getPrivateKey(info), null));
      }
      return sw.toString();
    }
  }

  // ------------------------------------------------------------------ rotinas
  /** A cada minuto: certificados aguardando validação/emissão no ACM. */
  @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
  public void pollPending() {
    forEachAcm((cid, rowId, c) -> {
      var st = c.aws() == null ? null : c.aws().status();
      if ("requested".equals(st) || "pending-validation".equals(st)) start(cid, rowId, null);
    });
  }

  /** A cada 6 h: o ACM renovou? (nova validade) — exporta de novo e publica. */
  @Scheduled(initialDelay = 120_000, fixedDelay = 6 * 3600_000)
  public void checkRenewals() {
    forEachAcm((cid, rowId, c) -> {
      if (c.aws() == null || !"issued".equals(c.aws().status()) || c.aws().arn() == null || c.meta() == null) return;
      try {
        renewIfNeeded(cid, rowId);
      } catch (Exception e) {
        fail(cid, rowId, null, e);
      }
    });
  }

  /** Exporta de novo se o ACM tem uma validade diferente da que está publicada. Retorna true se renovou. */
  public boolean renewIfNeeded(String cid, String certId) throws Exception {
    var cluster = store.getCluster(cid).orElseThrow();
    var c = load(cid, certId);
    try (var acm = aws.acm(cluster, null)) {
      var d = acm.describeCertificate(r -> r.certificateArn(c.aws().arn())).certificate();
      if (d.status() != CertificateStatus.ISSUED || d.notAfter() == null) return false;
      var current = Instant.parse(c.meta().notAfter());
      if (Duration.between(current, d.notAfter()).abs().toHours() < 24) return false;
      log.info("ACM renovou o certificado {} ({} -> {}); exportando de novo", c.name(), current, d.notAfter());
      export(cluster, certId, c, d, null);
      notifications.notify("info", "acm-renewed", cid, "Certificado " + c.name() + " renovado pelo ACM",
          "Nova validade: " + d.notAfter() + ". O HAPM exportou o certificado renovado" + (cluster.settings().autoDeployOnCertRenew() ? " e publicou." : "; publique para aplicar."),
          Map.of("certificate", c.name(), "notAfter", d.notAfter().toString()), "acm-renewed:" + cid + ":" + c.name() + ":" + d.notAfter());
      return true;
    }
  }

  interface CertVisitor {
    void visit(String clusterId, String rowId, Certificate c);
  }

  private void forEachAcm(CertVisitor v) {
    for (var cl : store.listClusters()) {
      for (var row : store.listObjects(cl.id(), DeployService.CERTIFICATE)) {
        try {
          var c = store.fromJson(row.data(), Certificate.class);
          if ("acm".equals(c.source())) v.visit(cl.id(), row.id(), c);
        } catch (Exception e) {
          log.warn("ACM: erro verificando certificado {}: {}", row.name(), e.getMessage());
        }
      }
    }
  }
}
