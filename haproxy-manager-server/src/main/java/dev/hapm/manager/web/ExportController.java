package dev.hapm.manager.web;

import dev.hapm.manager.security.SessionUser;
import dev.hapm.manager.service.ExportService;
import dev.hapm.manager.service.ExportService.ClusterExport;
import dev.hapm.manager.store.Store;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Exportar/importar clusters (JSON ou YAML) e importar um haproxy.cfg existente. Somente ADMIN. */
@RestController
public class ExportController {
  private final Store store;
  private final ExportService exports;
  private final JsonMapper json;
  private final YAMLMapper yaml = YAMLMapper.builder().build();

  public ExportController(Store store, ExportService exports, JsonMapper json) {
    this.store = store;
    this.exports = exports;
    this.json = json;
  }

  @GetMapping("/api/clusters/{cid}/export")
  public ResponseEntity<byte[]> export(@PathVariable String cid, @RequestParam(defaultValue = "json") String format,
                                       @RequestParam(defaultValue = "false") boolean secrets, @AuthenticationPrincipal SessionUser user) {
    var c = store.getCluster(cid).orElseThrow(() -> Http.notFound("cluster não encontrado"));
    var doc = exports.export(cid, secrets);
    boolean y = format.equalsIgnoreCase("yaml") || format.equalsIgnoreCase("yml");
    var body = y ? yaml.writeValueAsString(doc) : json.rebuild().enable(SerializationFeature.INDENT_OUTPUT).build().writeValueAsString(doc);
    store.audit(user.username(), cid, "cluster.export", c.name(), java.util.Map.of("secrets", secrets, "format", y ? "yaml" : "json"));
    var file = "hapm-" + c.name() + (y ? ".yaml" : ".json");
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
        .contentType(y ? MediaType.parseMediaType("application/yaml") : MediaType.APPLICATION_JSON)
        .body(body.getBytes(StandardCharsets.UTF_8));
  }

  private ClusterExport parse(String text) {
    var t = text == null ? "" : text.strip();
    if (t.isEmpty()) throw Http.status(HttpStatus.BAD_REQUEST, "documento vazio");
    try {
      return t.startsWith("{") ? json.readValue(t, ClusterExport.class) : yaml.readValue(t, ClusterExport.class);
    } catch (Exception e) {
      throw Http.status(HttpStatus.BAD_REQUEST, "documento inválido (JSON ou YAML do HAPM): " + e.getMessage().lines().findFirst().orElse(""));
    }
  }

  private static ResponseEntity<ExportService.Report> result(ExportService.Report r) {
    return ResponseEntity.status(r.ok() ? (r.dryRun() ? HttpStatus.OK : HttpStatus.CREATED) : HttpStatus.UNPROCESSABLE_ENTITY).body(r);
  }

  /** Cria um cluster novo a partir de uma exportação. */
  @PostMapping(path = "/api/clusters/import", consumes = MediaType.ALL_VALUE)
  public ResponseEntity<ExportService.Report> importNew(@RequestBody String body, @RequestParam(required = false) String name,
                                                        @RequestParam(defaultValue = "false") boolean dryRun, @AuthenticationPrincipal SessionUser user) {
    return result(exports.importDoc(null, name, parse(body), false, true, dryRun, user.username(), null));
  }

  /** Importa para um cluster existente: merge (padrão) ou replace (remove o que não está no documento). */
  @PostMapping(path = "/api/clusters/{cid}/import", consumes = MediaType.ALL_VALUE)
  public ResponseEntity<ExportService.Report> importInto(@PathVariable String cid, @RequestBody String body,
                                                         @RequestParam(defaultValue = "merge") String mode, @RequestParam(defaultValue = "false") boolean settings,
                                                         @RequestParam(defaultValue = "false") boolean dryRun, @AuthenticationPrincipal SessionUser user) {
    store.getCluster(cid).orElseThrow(() -> Http.notFound("cluster não encontrado"));
    if (!mode.equals("merge") && !mode.equals("replace")) throw Http.status(HttpStatus.BAD_REQUEST, "mode deve ser merge ou replace");
    return result(exports.importDoc(cid, null, parse(body), mode.equals("replace"), settings, dryRun, user.username(), null));
  }

  /** Converte um haproxy.cfg existente em listeners/backends deste cluster (sempre merge). */
  @PostMapping(path = "/api/clusters/{cid}/import-haproxy", consumes = MediaType.ALL_VALUE)
  public ResponseEntity<ExportService.Report> importHaproxy(@PathVariable String cid, @RequestBody String cfg,
                                                            @RequestParam(defaultValue = "true") boolean settings,
                                                            @RequestParam(defaultValue = "false") boolean dryRun, @AuthenticationPrincipal SessionUser user) {
    store.getCluster(cid).orElseThrow(() -> Http.notFound("cluster não encontrado"));
    if (cfg == null || cfg.length() > 2_000_000) throw Http.status(HttpStatus.BAD_REQUEST, "arquivo vazio ou maior que 2 MB");
    return result(exports.importHaproxyCfg(cid, cfg, settings, dryRun, user.username()));
  }
}
