package dev.hapm.manager.service;

import dev.hapm.core.model.Certificate;
import dev.hapm.manager.HapmProperties;
import dev.hapm.manager.store.Store;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Emissão/renovação ACME (Let's Encrypt) via HTTP-01.
 * Todos os nós encaminham /.well-known/acme-challenge/* ao backend "hapm_acme" (o manager),
 * então o desafio funciona qualquer que seja o nó que detém o VIP / recebe do NLB.
 */
@Service
public class CertService {
  private static final Logger log = LoggerFactory.getLogger(CertService.class);

  private final Store store;
  private final HapmProperties props;
  private final DeployService deploy;
  private final PemService pem;
  private final Set<String> running = ConcurrentHashMap.newKeySet();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public CertService(Store store, HapmProperties props, DeployService deploy, PemService pem) {
    this.store = store;
    this.props = props;
    this.deploy = deploy;
    this.pem = pem;
  }

  /** Dispara a emissão em background; o andamento fica em certificate.acme.status. */
  public boolean issue(String clusterId, String certId, String user) {
    var key = clusterId + "/" + certId;
    if (!running.add(key)) return false;
    setStatus(clusterId, certId, "pending", null);
    executor.submit(() -> {
      try {
        doIssue(clusterId, certId, user);
      } catch (Exception e) {
        log.error("falha ACME no certificado {}", certId, e);
        setStatus(clusterId, certId, "error", e.getMessage());
        store.audit(user, clusterId, "certificate.acme.error", certId, Map.of("error", String.valueOf(e.getMessage())));
      } finally {
        running.remove(key);
      }
    });
    return true;
  }

  private Certificate load(String clusterId, String certId) {
    return store.fromJson(store.getObject(clusterId, DeployService.CERTIFICATE, certId).orElseThrow().data(), Certificate.class);
  }

  private void save(String clusterId, String certId, Certificate c) {
    store.updateObject(clusterId, DeployService.CERTIFICATE, certId, c.name(), c);
  }

  void setStatus(String clusterId, String certId, String status, String error) {
    var c = load(clusterId, certId);
    var a = c.acme();
    save(clusterId, certId, c.withAcme(new Certificate.AcmeInfo(a.domains(), a.email(), status, error, a.issuedAt(), Instant.now().toString())));
  }

  private KeyPair accountKey() throws Exception {
    var stored = store.getKv("acme.accountKey");
    if (stored.isPresent()) return KeyPairUtils.readKeyPair(new StringReader(stored.get()));
    var kp = KeyPairUtils.createKeyPair(2048);
    var w = new StringWriter();
    KeyPairUtils.writeKeyPair(kp, w);
    store.setKv("acme.accountKey", w.toString());
    return kp;
  }

  private void doIssue(String clusterId, String certId, String user) throws Exception {
    var cluster = store.getCluster(clusterId).orElseThrow();
    var cert = load(clusterId, certId);
    if (cluster.settings().acme().backend().isEmpty())
      throw new IllegalStateException("configure \"acme.backend\" (host:porta do manager visto pelo HAProxy) no cluster");
    var session = new Session(props.acmeDirectory());
    var email = !cert.acme().email().isEmpty() ? cert.acme().email() : cluster.settings().acme().email();
    var builder = new AccountBuilder().agreeToTermsOfService().useKeyPair(accountKey());
    if (email != null && !email.isEmpty()) builder.addEmail(email);
    var account = builder.create(session);

    var order = account.newOrder().domains(cert.acme().domains()).create();
    for (var auth : order.getAuthorizations()) {
      if (auth.getStatus() == Status.VALID) continue;
      var ch = auth.findChallenge(Http01Challenge.class).orElseThrow(() -> new IllegalStateException("servidor ACME não ofereceu HTTP-01"));
      store.putChallenge(ch.getToken(), ch.getAuthorization());
      try {
        ch.trigger();
        var st = ch.waitForCompletion(Duration.ofSeconds(90));
        if (st != Status.VALID) throw new IllegalStateException("desafio para " + auth.getIdentifier().getDomain() + " terminou como " + st + ch.getError().map(p -> ": " + p).orElse(""));
      } finally {
        store.deleteChallenge(ch.getToken());
      }
    }
    var domainKey = KeyPairUtils.createKeyPair(2048);
    order.execute(domainKey);
    var st = order.waitForCompletion(Duration.ofSeconds(90));
    if (st != Status.VALID) throw new IllegalStateException("pedido terminou como " + st);
    var certPem = new StringWriter();
    order.getCertificate().writeCertificate(certPem);
    var keyPem = new StringWriter();
    KeyPairUtils.writeKeyPair(domainKey, keyPem);

    var parsed = pem.parse(certPem + "\n" + keyPem);
    var a = load(clusterId, certId).acme();
    var now = Instant.now().toString();
    var updated = cert.withPem(parsed.pem(), parsed.meta()).withAcme(new Certificate.AcmeInfo(a.domains(), a.email(), "issued", null, now, now));
    save(clusterId, certId, updated);
    store.audit(user, clusterId, "certificate.acme.issued", cert.name(), Map.of("notAfter", parsed.meta().notAfter()));
    log.info("certificado ACME {} emitido (expira {})", cert.name(), parsed.meta().notAfter());
    if (cluster.settings().autoDeployOnCertRenew()) {
      try {
        deploy.refreshCertificates(clusterId, "certificado " + cert.name() + " emitido/renovado", user == null ? "acme" : user);
      } catch (Exception e) {
        log.warn("publicação automática após emissão falhou: {}", e.getMessage());
      }
    }
  }

  /** Renova certificados ACME que expiram em menos de N dias (a cada 12h, começando 1 min após subir). */
  @Scheduled(initialDelay = 60_000, fixedDelay = 12 * 3600_000)
  public void renewDue() {
    var limit = Instant.now().plus(Duration.ofDays(props.certRenewBeforeDays()));
    for (var cluster : store.listClusters()) {
      for (var row : store.listObjects(cluster.id(), DeployService.CERTIFICATE)) {
        var c = store.fromJson(row.data(), Certificate.class);
        if (!"acme".equals(c.source()) || (c.acme() != null && "pending".equals(c.acme().status()))) continue;
        var exp = c.meta() == null ? Instant.EPOCH : Instant.parse(c.meta().notAfter());
        if (exp.isBefore(limit)) {
          log.info("renovando certificado ACME {}", c.name());
          issue(cluster.id(), row.id(), "acme-renew");
        }
      }
    }
  }
}
