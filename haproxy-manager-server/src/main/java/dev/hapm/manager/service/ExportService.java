package dev.hapm.manager.service;

import dev.hapm.core.importer.HaproxyCfgImporter;
import dev.hapm.core.model.Backend;
import dev.hapm.core.model.Certificate;
import dev.hapm.core.model.ClusterSettings;
import dev.hapm.core.model.Frontend;
import dev.hapm.core.validate.Normalizer;
import dev.hapm.core.validate.ValidationException;
import dev.hapm.manager.store.Store;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exporta um cluster (configurações, listeners, backends, certificados) e importa de volta — no mesmo manager ou em
 * outro, para backup, promoção entre ambientes ou versionamento em git. Também importa um haproxy.cfg existente.
 * Toda importação passa pela mesma validação do cadastro manual e é atômica: ou entra tudo, ou nada.
 */
@Service
public class ExportService {
  public static final int FORMAT = 1;

  private final Store store;
  private final ObjectService objects;
  private final JsonMapper json;
  private final TransactionTemplate tx;
  private final DeployService deploy;

  public ExportService(Store store, ObjectService objects, JsonMapper json, PlatformTransactionManager tm, DeployService deploy) {
    this.deploy = deploy;
    this.store = store;
    this.objects = objects;
    this.json = json;
    this.tx = new TransactionTemplate(tm);
  }

  /** Documento de exportação. Sem {@code secrets}, senhas e chaves privadas ficam de fora. */
  public record ClusterExport(Integer hapmExport, String exportedAt, String name, ClusterSettings settings, List<Backend> backends,
                              List<Frontend> frontends, List<Certificate> certificates) {}

  public record Report(boolean dryRun, boolean ok, String clusterId, List<String> created, List<String> updated, List<String> deleted,
                       List<String> warnings, List<String> errors) {}

  // ------------------------------------------------------------------ exportação
  public ClusterExport export(String clusterId, boolean secrets) {
    var c = store.getCluster(clusterId).orElseThrow();
    var s = c.settings();
    if (!secrets) {
      var k = s.keepalived();
      var h = s.haproxy();
      s = new ClusterSettings(s.mode(), s.rollout(), s.autoDeployOnCertRenew(),
          h == null ? null : new ClusterSettings.Haproxy(h.maxconn(), h.nbthread(), h.log(), h.user(), h.statsSocket(), h.statsPort(), h.statsUser(), null,
              h.sslMinVer(), h.defaultCertificate(), h.timeouts(), h.globalExtra(), h.defaultsExtra()),
          k == null ? null : new ClusterSettings.Keepalived(k.iface(), k.routerId(), k.vips(), null, k.advertInt(), k.unicast(), k.preempt(),
              k.checkInterval(), k.checkFall(), k.checkRise()),
          s.aws(), s.acme(), s.approval(), s.nodes(), s.logs(), s.awsAccess());
    }
    var certs = store.listObjects(clusterId, DeployService.CERTIFICATE, Certificate.class).stream()
        .map(x -> secrets ? x : x.withoutPem()).toList();
    return new ClusterExport(FORMAT, Instant.now().toString(), c.name(), s,
        store.listObjects(clusterId, DeployService.BACKEND, Backend.class),
        store.listObjects(clusterId, DeployService.FRONTEND, Frontend.class), certs);
  }

  // ------------------------------------------------------------------ importação
  /**
   * @param clusterId  cluster de destino; null cria um novo (nome do documento ou {@code newName})
   * @param replace    true: remove listeners/backends/certificados que não estão no documento
   * @param settings   true: aplica também as configurações do cluster do documento
   */
  public Report importDoc(String clusterId, String newName, ClusterExport doc, boolean replace, boolean settings, boolean dryRun, String user,
                          List<String> preWarnings) {
    var created = new ArrayList<String>();
    var updated = new ArrayList<String>();
    var deleted = new ArrayList<String>();
    var warnings = new ArrayList<String>(preWarnings == null ? List.of() : preWarnings);
    var errors = new ArrayList<String>();
    var cid = new String[] {clusterId};
    try {
      tx.executeWithoutResult(status -> {
        try {
          if (cid[0] == null) {
            var name = newName != null && !newName.isBlank() ? newName : doc.name();
            if (name == null || name.isBlank()) throw new ValidationException("nome do cluster: informe ?name= ou \"name\" no documento");
            var n = Normalizer.cluster(name, doc.settings(), null);
            if (store.listClusters().stream().anyMatch(c -> c.name().equals(n.name()))) throw new ValidationException("já existe um cluster chamado " + n.name());
            cid[0] = store.createCluster(n.name(), n.settings()).id();
            created.add("cluster/" + n.name());
          } else if (settings && doc.settings() != null) {
            var c = store.getCluster(cid[0]).orElseThrow();
            var n = Normalizer.cluster(c.name(), mergeSettings(c.settings(), doc.settings()), c.settings());
            store.updateCluster(cid[0], c.name(), n.settings());
            updated.add("cluster/" + c.name() + " (configurações)");
          }
          var id = cid[0];
          var docCerts = doc.certificates() == null ? List.<Certificate>of() : doc.certificates();
          var docBes = doc.backends() == null ? List.<Backend>of() : doc.backends();
          var docFes = doc.frontends() == null ? List.<Frontend>of() : doc.frontends();

          for (var cert : docCerts) {
            var existing = store.getObjectByName(id, DeployService.CERTIFICATE, cert.name());
            boolean noPem = cert.pem() == null || cert.pem().isBlank();
            if (noPem && "upload".equals(cert.source()) && existing.isEmpty()) {
              warnings.add("certificado " + cert.name() + ": exportado sem chave privada; envie o PEM depois");
              continue;
            }
            if (noPem && existing.isPresent()) continue; // mantém o PEM que já existe
            if (noPem && dryRun) {
              created.add("certificado/" + cert.name() + " (seria emitido via ACME)");
              continue;
            }
            upsert(id, ObjectService.Kind.certificates, cert.name(), cert, user, created, updated, "certificado/");
          }
          for (var b : docBes) upsert(id, ObjectService.Kind.backends, b.name(), b, user, created, updated, "backend/");
          for (var f : docFes) upsert(id, ObjectService.Kind.frontends, f.name(), f, user, created, updated, "listener/");
          // remoções por último: listeners antes dos backends que eles usavam
          if (replace) {
            var keepFe = docFes.stream().map(Frontend::name).toList();
            var keepBe = docBes.stream().map(Backend::name).toList();
            var keepCert = docCerts.stream().map(Certificate::name).toList();
            for (var o : store.listObjects(id, DeployService.FRONTEND)) {
              if (!keepFe.contains(o.name())) {
                objects.delete(id, ObjectService.Kind.frontends, o.id(), user);
                deleted.add("listener/" + o.name());
              }
            }
            for (var o : store.listObjects(id, DeployService.BACKEND)) {
              if (!keepBe.contains(o.name())) {
                objects.delete(id, ObjectService.Kind.backends, o.id(), user);
                deleted.add("backend/" + o.name());
              }
            }
            for (var o : store.listObjects(id, DeployService.CERTIFICATE)) {
              if (!keepCert.contains(o.name())) {
                objects.delete(id, ObjectService.Kind.certificates, o.id(), user);
                deleted.add("certificado/" + o.name());
              }
            }
          }
          // consistência do cluster inteiro (referências a backends, portas, modos): erro desfaz a importação;
          // falta de certificado é só aviso (o PEM pode ser enviado depois, antes de publicar)
          for (var e : dev.hapm.core.render.HaproxyRenderer.crossValidate(deploy.snapshot(id))) {
            if (e.contains("não há certificados") || e.startsWith("certificado padrão")) warnings.add(e + " (envie o certificado antes de publicar)");
            else throw new ValidationException(e);
          }
          if (dryRun) status.setRollbackOnly();
          else store.audit(user, id, "cluster.import", store.getCluster(id).map(Store.ClusterRow::name).orElse(id),
              Map.of("created", created.size(), "updated", updated.size(), "deleted", deleted.size(), "replace", replace));
        } catch (ValidationException | ResponseStatusException | IllegalArgumentException e) {
          errors.add(e instanceof ResponseStatusException r ? r.getReason() : e.getMessage());
          status.setRollbackOnly();
        }
      });
    } catch (RuntimeException e) {
      errors.add(e.getMessage());
    }
    boolean ok = errors.isEmpty();
    return new Report(dryRun, ok, ok && !dryRun ? cid[0] : clusterId, created, updated, deleted, warnings, errors);
  }

  @SuppressWarnings("unchecked")
  private void upsert(String cid, ObjectService.Kind k, String name, Object data, String user, List<String> created, List<String> updated, String label) {
    var body = (Map<String, Object>) json.convertValue(data, LinkedHashMap.class);
    body.values().removeIf(java.util.Objects::isNull);
    try {
      var existing = store.getObjectByName(cid, k.kind, name);
      if (existing.isPresent()) {
        objects.update(cid, k, existing.get().id(), body, user);
        updated.add(label + name);
      } else {
        objects.create(cid, k, body, user);
        created.add(label + name);
      }
    } catch (ValidationException e) {
      throw new ValidationException(label + name + ": " + e.getMessage());
    } catch (ResponseStatusException e) {
      throw new ValidationException(label + name + ": " + e.getReason());
    }
  }

  /** Campos não nulos do documento sobrescrevem as configurações atuais (bloco a bloco). */
  static ClusterSettings mergeSettings(ClusterSettings cur, ClusterSettings in) {
    var h = in.haproxy() == null ? cur.haproxy() : mergeHaproxy(cur.haproxy(), in.haproxy());
    return new ClusterSettings(nz(in.mode(), cur.mode()), nz(in.rollout(), cur.rollout()), nz(in.autoDeployOnCertRenew(), cur.autoDeployOnCertRenew()), h,
        nz(in.keepalived(), cur.keepalived()), nz(in.aws(), cur.aws()), nz(in.acme(), cur.acme()), nz(in.approval(), cur.approval()), nz(in.nodes(), cur.nodes()),
        nz(in.logs(), cur.logs()), nz(in.awsAccess(), cur.awsAccess()));
  }

  static ClusterSettings.Haproxy mergeHaproxy(ClusterSettings.Haproxy c, ClusterSettings.Haproxy i) {
    if (c == null) return i;
    var ct = c.timeouts();
    var it = i.timeouts();
    var t = it == null ? ct : ct == null ? it : new ClusterSettings.Timeouts(nz(it.connect(), ct.connect()), nz(it.client(), ct.client()), nz(it.server(), ct.server()),
        nz(it.httpRequest(), ct.httpRequest()), nz(it.httpKeepAlive(), ct.httpKeepAlive()), nz(it.queue(), ct.queue()), nz(it.tunnel(), ct.tunnel()));
    return new ClusterSettings.Haproxy(nz(i.maxconn(), c.maxconn()), nz(i.nbthread(), c.nbthread()), nz(i.log(), c.log()), nz(i.user(), c.user()),
        nz(i.statsSocket(), c.statsSocket()), nz(i.statsPort(), c.statsPort()), nz(i.statsUser(), c.statsUser()), nz(i.statsPassword(), c.statsPassword()),
        nz(i.sslMinVer(), c.sslMinVer()), nz(i.defaultCertificate(), c.defaultCertificate()), t, nz(i.globalExtra(), c.globalExtra()),
        nz(i.defaultsExtra(), c.defaultsExtra()));
  }

  private static <T> T nz(T a, T b) {
    return a != null ? a : b;
  }

  // ------------------------------------------------------------------ haproxy.cfg
  public Report importHaproxyCfg(String clusterId, String cfg, boolean settings, boolean dryRun, String user) {
    var r = HaproxyCfgImporter.convert(cfg);
    // a seção global do arquivo não deve trocar o socket/usuário que o HAPM usa nos nós: só limites, log e timeouts
    var h = r.haproxy();
    var haproxy = new ClusterSettings.Haproxy(h.maxconn(), h.nbthread(), null, null, null, null, null, null, h.sslMinVer(), null, h.timeouts(), null, null);
    var doc = new ClusterExport(FORMAT, Instant.now().toString(), null,
        new ClusterSettings(null, null, null, haproxy, null, null, null, null, null), r.backends(), r.frontends(), List.of());
    return importDoc(clusterId, null, doc, false, settings, dryRun, user, r.warnings());
  }
}
