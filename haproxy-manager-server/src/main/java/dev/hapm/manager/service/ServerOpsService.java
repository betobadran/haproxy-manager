package dev.hapm.manager.service;

import dev.hapm.core.model.AgentProtocol.Runtime;
import dev.hapm.core.model.AgentProtocol.RuntimeReport;
import dev.hapm.core.model.AgentProtocol.ServerOverride;
import dev.hapm.core.model.Backend;
import dev.hapm.manager.store.Store;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Operação de servidores sem publicar: drenar, pôr em manutenção, voltar ao normal e ajustar peso. O manager guarda
 * o estado desejado; cada agente o aplica pela runtime API do HAProxy (e reaplica após reloads).
 */
@Service
public class ServerOpsService {
  public static final Set<String> STATES = Set.of("drain", "maint", "ready");

  private final Store store;
  /** último resultado de cada nó: cluster -> nó -> relatório */
  private final Map<String, Map<String, RuntimeReport>> reports = new ConcurrentHashMap<>();

  public ServerOpsService(Store store) {
    this.store = store;
  }

  /** O que vai na resposta do heartbeat. */
  public Runtime runtimeFor(Store.ClusterRow c) {
    var h = c.settings().haproxy();
    var sock = h == null || h.statsSocket() == null ? "/run/haproxy/admin.sock" : h.statsSocket();
    var list = store.listOverrides(c.id()).stream()
        .map(o -> new ServerOverride(o.backend(), o.server(), "ready".equals(o.state()) ? null : o.state(), o.weight())).toList();
    return new Runtime(sock, list);
  }

  public void report(String clusterId, String node, RuntimeReport r) {
    if (r != null) reports.computeIfAbsent(clusterId, k -> new ConcurrentHashMap<>()).put(node, r);
  }

  public Map<String, RuntimeReport> reports(String clusterId) {
    return reports.getOrDefault(clusterId, Map.of());
  }

  /** state: drain | maint | ready; weight: 0-256 ou null (peso da configuração). ready + sem peso remove o override. */
  public void set(String clusterId, String backend, String server, String state, Integer weight, String reason, String user) {
    if (state == null) state = "ready";
    if (!STATES.contains(state)) throw new IllegalArgumentException("estado deve ser drain, maint ou ready");
    if (weight != null && (weight < 0 || weight > 256)) throw new IllegalArgumentException("peso deve estar entre 0 e 256");
    if (reason != null && reason.length() > 500) reason = reason.substring(0, 500);
    var be = store.getObjectByName(clusterId, DeployService.BACKEND, backend)
        .map(o -> store.fromJson(o.data(), Backend.class))
        .orElseThrow(() -> new IllegalArgumentException("backend " + backend + " não existe"));
    if (be.servers() == null || be.servers().stream().noneMatch(s -> server.equals(s.name()))) {
      throw new IllegalArgumentException("servidor " + server + " não existe no backend " + backend);
    }
    boolean clear = "ready".equals(state) && weight == null;
    store.putOverride(clusterId, backend, server, clear ? null : state, weight, reason, user);
    var detail = new LinkedHashMap<String, Object>();
    detail.put("state", state);
    detail.put("weight", weight);
    if (reason != null && !reason.isBlank()) detail.put("reason", reason);
    store.audit(user, clusterId, "server.runtime", backend + "/" + server, detail);
    var what = switch (state) {
      case "drain" -> "drenando (sem novas conexões)";
      case "maint" -> "em manutenção";
      default -> "pronto";
    };
    store.addEvent(clusterId, null, "runtime:" + backend + "/" + server, "server-state",
        backend + "/" + server + " → " + what + (weight != null ? ", peso " + weight : "") + " por " + user
            + (reason != null && !reason.isBlank() ? " (" + reason + ")" : ""), detail);
  }

  public List<Store.OverrideRow> overrides(String clusterId) {
    return store.listOverrides(clusterId);
  }
}
