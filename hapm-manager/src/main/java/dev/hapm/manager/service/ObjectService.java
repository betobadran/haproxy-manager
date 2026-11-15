package dev.hapm.manager.service;

import dev.hapm.core.model.Backend;
import dev.hapm.core.model.Certificate;
import dev.hapm.core.model.Frontend;
import dev.hapm.core.validate.Normalizer;
import dev.hapm.manager.store.Store;
import dev.hapm.manager.store.Store.ObjectRow;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** CRUD de listeners (frontends), backends e certificados, com validação e auditoria. */
@Service
public class ObjectService {

  public enum Kind {
    frontends(DeployService.FRONTEND, Frontend.class),
    backends(DeployService.BACKEND, Backend.class),
    certificates(DeployService.CERTIFICATE, Certificate.class);

    public final String kind;
    public final Class<?> type;

    Kind(String kind, Class<?> type) {
      this.kind = kind;
      this.type = type;
    }

    public static Kind parse(String s) {
      try {
        return Kind.valueOf(s);
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "tipo desconhecido");
      }
    }
  }

  private final Store store;
  private final JsonMapper json;
  private final PemService pem;
  private final CertService certs;

  public ObjectService(Store store, JsonMapper json, PemService pem, CertService certs) {
    this.store = store;
    this.json = json;
    this.pem = pem;
    this.certs = certs;
  }

  /** Visão pública: dados + metadados; a chave privada dos certificados nunca sai do manager. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> view(ObjectRow o) {
    var data = json.readValue(o.data(), LinkedHashMap.class);
    var out = new LinkedHashMap<String, Object>();
    out.put("id", o.id());
    out.putAll(data);
    if (o.kind().equals(DeployService.CERTIFICATE)) {
      out.put("hasKey", data.get("pem") != null);
      out.remove("pem");
    }
    out.put("createdAt", o.createdAt());
    out.put("updatedAt", o.updatedAt());
    return out;
  }

  private Object normalize(Kind k, Map<String, Object> body, ObjectRow existing) {
    return switch (k) {
      case frontends -> Normalizer.frontend(json.convertValue(body, Frontend.class));
      case backends -> Normalizer.backend(json.convertValue(body, Backend.class));
      case certificates -> {
        var in = json.convertValue(body, Certificate.class);
        var prev = existing == null ? null : store.fromJson(existing.data(), Certificate.class);
        var header = Normalizer.certificateHeader(in, prev);
        var pemText = in.pem() != null && !in.pem().isBlank() ? in.pem() : prev == null ? null : prev.pem();
        if (pemText != null) {
          var parsed = pem.parse(pemText);
          yield header.withPem(parsed.pem(), parsed.meta());
        }
        if (header.source().equals("upload")) throw new dev.hapm.core.validate.ValidationException("pem: obrigatório");
        yield new Certificate(header.name(), header.source(), null, prev == null ? null : prev.meta(), header.acme());
      }
    };
  }

  private static String nameOf(Object o) {
    return switch (o) {
      case Frontend f -> f.name();
      case Backend b -> b.name();
      case Certificate c -> c.name();
      default -> throw new IllegalArgumentException();
    };
  }

  public ObjectRow create(String clusterId, Kind k, Map<String, Object> body, String user) {
    var data = normalize(k, body, null);
    var name = nameOf(data);
    if (store.getObjectByName(clusterId, k.kind, name).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, k.kind + " \"" + name + "\" já existe");
    var row = store.createObject(clusterId, k.kind, name, data);
    store.audit(user, clusterId, k.kind + ".create", name, null);
    if (data instanceof Certificate c && "acme".equals(c.source()) && !c.usable()) certs.issue(clusterId, row.id(), user);
    return store.getObject(clusterId, k.kind, row.id()).orElseThrow();
  }

  public ObjectRow update(String clusterId, Kind k, String id, Map<String, Object> body, String user) {
    var cur = store.getObject(clusterId, k.kind, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "não encontrado"));
    var data = normalize(k, body, cur);
    var name = nameOf(data);
    var other = store.getObjectByName(clusterId, k.kind, name);
    if (other.isPresent() && !other.get().id().equals(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, k.kind + " \"" + name + "\" já existe");
    var row = store.updateObject(clusterId, k.kind, id, name, data);
    store.audit(user, clusterId, k.kind + ".update", name, null);
    return row;
  }

  public void delete(String clusterId, Kind k, String id, String user) {
    var cur = store.getObject(clusterId, k.kind, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "não encontrado"));
    if (k == Kind.backends) {
      var users = store.listObjects(clusterId, DeployService.FRONTEND, Frontend.class).stream()
          .filter(f -> cur.name().equals(f.defaultBackend()) || f.rules().stream().anyMatch(r -> cur.name().equals(r.backend())))
          .map(Frontend::name).toList();
      if (!users.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "backend em uso por: " + String.join(", ", users));
    }
    store.deleteObject(clusterId, k.kind, id);
    store.audit(user, clusterId, k.kind + ".delete", cur.name(), null);
  }
}
