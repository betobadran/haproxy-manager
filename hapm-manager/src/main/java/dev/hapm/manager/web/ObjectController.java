package dev.hapm.manager.web;

import dev.hapm.core.model.Certificate;
import dev.hapm.manager.service.CertService;
import dev.hapm.manager.service.DeployService;
import dev.hapm.manager.service.ObjectService;
import dev.hapm.manager.service.ObjectService.Kind;
import dev.hapm.manager.store.Store;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/clusters/{cid}/{frontends|backends|certificates} */
@RestController
@RequestMapping("/api/clusters/{cid}")
public class ObjectController {
  private final Store store;
  private final ObjectService objects;
  private final CertService certs;

  public ObjectController(Store store, ObjectService objects, CertService certs) {
    this.store = store;
    this.objects = objects;
    this.certs = certs;
  }

  private void cluster(String cid) {
    store.getCluster(cid).orElseThrow(() -> Http.notFound("cluster não encontrado"));
  }

  @GetMapping("/{kind:frontends|backends|certificates}")
  public List<Map<String, Object>> list(@PathVariable String cid, @PathVariable String kind) {
    cluster(cid);
    return store.listObjects(cid, Kind.parse(kind).kind).stream().map(objects::view).toList();
  }

  @GetMapping("/{kind:frontends|backends|certificates}/{oid}")
  public Map<String, Object> get(@PathVariable String cid, @PathVariable String kind, @PathVariable String oid) {
    cluster(cid);
    return objects.view(store.getObject(cid, Kind.parse(kind).kind, oid).orElseThrow(() -> Http.notFound("não encontrado")));
  }

  @PostMapping("/{kind:frontends|backends|certificates}")
  public ResponseEntity<Map<String, Object>> create(@PathVariable String cid, @PathVariable String kind, @RequestBody Map<String, Object> body, Principal p) {
    cluster(cid);
    return ResponseEntity.status(HttpStatus.CREATED).body(objects.view(objects.create(cid, Kind.parse(kind), body, p.getName())));
  }

  @PutMapping("/{kind:frontends|backends|certificates}/{oid}")
  public Map<String, Object> update(@PathVariable String cid, @PathVariable String kind, @PathVariable String oid, @RequestBody Map<String, Object> body, Principal p) {
    cluster(cid);
    return objects.view(objects.update(cid, Kind.parse(kind), oid, body, p.getName()));
  }

  @DeleteMapping("/{kind:frontends|backends|certificates}/{oid}")
  public Map<String, Boolean> delete(@PathVariable String cid, @PathVariable String kind, @PathVariable String oid, Principal p) {
    cluster(cid);
    objects.delete(cid, Kind.parse(kind), oid, p.getName());
    return Map.of("ok", true);
  }

  @PostMapping("/certificates/{oid}/issue")
  public Map<String, Boolean> issue(@PathVariable String cid, @PathVariable String oid, Principal p) {
    cluster(cid);
    var row = store.getObject(cid, DeployService.CERTIFICATE, oid).orElseThrow(() -> Http.notFound("não encontrado"));
    if (!"acme".equals(store.fromJson(row.data(), Certificate.class).source())) throw Http.status(HttpStatus.BAD_REQUEST, "certificado não é ACME");
    return Map.of("started", certs.issue(cid, oid, p.getName()));
  }
}
