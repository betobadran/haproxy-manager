package dev.hapm.manager.service;

import dev.hapm.core.model.Snapshot;
import dev.hapm.core.render.HaproxyRenderer;
import dev.hapm.manager.security.Role;
import dev.hapm.manager.security.SessionUser;
import dev.hapm.manager.store.Store;
import dev.hapm.manager.store.Store.DeploymentRow;
import dev.hapm.manager.store.Store.RequestRow;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Publicação com governança. Se o cluster exige aprovação:
 * <ul>
 *   <li>OPERATOR (ou ADMIN) cria um <b>pedido</b> com o snapshot e o haproxy.cfg exatos daquele momento;</li>
 *   <li>APPROVER aprova (outra pessoa, se allowSelfApproval=false) e o que foi revisado é publicado, sem alterações;</li>
 *   <li>se outra versão foi publicada depois do pedido, ele fica obsoleto (aprovar publicaria um estado velho);</li>
 *   <li>ADMIN pode fazer publicação <b>emergencial</b> (break-glass) com justificativa obrigatória, auditada.</li>
 * </ul>
 */
@Service
public class PublishService {

  public sealed interface Result {}

  public record Published(DeploymentRow deployment) implements Result {}

  public record Requested(RequestRow request) implements Result {}

  private final Store store;
  private final DeployService deploy;

  public PublishService(Store store, DeployService deploy) {
    this.store = store;
    this.deploy = deploy;
  }

  private static ResponseStatusException err(HttpStatus s, String m) {
    return new ResponseStatusException(s, m);
  }

  private int currentVersion(String clusterId) {
    return store.latestDeployment(clusterId).map(DeploymentRow::version).orElse(0);
  }

  /** Publica o estado atual (ou o snapshot informado, no caso de rollback). */
  public Result publish(String clusterId, String note, SessionUser user, boolean emergency, Snapshot override) {
    var cluster = store.getCluster(clusterId).orElseThrow();
    if (!cluster.settings().approvalRequired()) return new Published(deploy.create(clusterId, note, user.username(), override));
    if (emergency) {
      if (!user.has(Role.ADMIN)) throw err(HttpStatus.FORBIDDEN, "publicação emergencial exige papel ADMIN");
      if (note == null || note.strip().length() < 10) throw err(HttpStatus.BAD_REQUEST, "publicação emergencial exige justificativa (mín. 10 caracteres)");
      var d = deploy.create(clusterId, "[EMERGÊNCIA] " + note.strip(), user.username(), override);
      store.audit(user.username(), clusterId, "deployment.emergency", "v" + d.version(), Map.of("justificativa", note.strip()));
      return new Published(d);
    }
    var snap = override != null ? override : deploy.snapshot(clusterId);
    var cfg = HaproxyRenderer.render(snap, currentVersion(clusterId) + 1, Instant.now().toString()); // valida já no pedido
    var r = store.createRequest(clusterId, currentVersion(clusterId), note, user.username(), snap, cfg);
    store.audit(user.username(), clusterId, "request.create", "#" + r.id(), note == null ? null : Map.of("note", note));
    return new Requested(r);
  }

  private RequestRow pending(String clusterId, long id) {
    var r = store.getRequest(clusterId, id).orElseThrow(() -> err(HttpStatus.NOT_FOUND, "pedido não encontrado"));
    if (!r.status().equals("pending")) throw err(HttpStatus.CONFLICT, "pedido já está " + r.status());
    return r;
  }

  public DeploymentRow approve(String clusterId, long id, SessionUser approver, String comment) {
    var cluster = store.getCluster(clusterId).orElseThrow();
    var r = pending(clusterId, id);
    var self = r.requestedBy().equals(approver.username());
    if (self && !Boolean.TRUE.equals(cluster.settings().approval().allowSelfApproval()))
      throw err(HttpStatus.FORBIDDEN, "quatro olhos: o pedido precisa ser aprovado por outra pessoa");
    if (r.baseVersion() != currentVersion(clusterId)) {
      store.decideRequest(id, "superseded", approver.username(), "outra versão foi publicada depois deste pedido", null);
      throw err(HttpStatus.CONFLICT, "pedido desatualizado: a v" + currentVersion(clusterId) + " foi publicada depois dele; crie um novo pedido");
    }
    if (!store.decideRequest(id, "approved", approver.username(), comment, null)) throw err(HttpStatus.CONFLICT, "pedido já foi decidido");
    var snap = store.fromJson(r.snapshotJson(), Snapshot.class);
    var note = (r.note() == null ? "" : r.note()) + " (pedido #" + r.id() + ")";
    var d = deploy.create(clusterId, note.strip(), r.requestedBy(), snap, approver.username(), r.id());
    store.linkRequestDeployment(id, d.id());
    store.audit(approver.username(), clusterId, "request.approve", "#" + r.id(), Map.of("deployment", "v" + d.version(), "selfApproval", self));
    return d;
  }

  public RequestRow reject(String clusterId, long id, SessionUser approver, String comment) {
    pending(clusterId, id);
    if (comment == null || comment.isBlank()) throw err(HttpStatus.BAD_REQUEST, "informe o motivo da rejeição");
    if (!store.decideRequest(id, "rejected", approver.username(), comment, null)) throw err(HttpStatus.CONFLICT, "pedido já foi decidido");
    store.audit(approver.username(), clusterId, "request.reject", "#" + id, Map.of("motivo", comment));
    return store.getRequest(clusterId, id).orElseThrow();
  }

  public RequestRow cancel(String clusterId, long id, SessionUser user) {
    var r = pending(clusterId, id);
    if (!r.requestedBy().equals(user.username()) && !user.has(Role.ADMIN)) throw err(HttpStatus.FORBIDDEN, "só quem pediu (ou um ADMIN) pode cancelar");
    store.decideRequest(id, "cancelled", user.username(), null, null);
    store.audit(user.username(), clusterId, "request.cancel", "#" + id, null);
    return store.getRequest(clusterId, id).orElseThrow();
  }
}
