package dev.hapm.core.model;

/** Mensagens trocadas entre agente e manager (modelo pull: só o agente inicia conexões). */
public final class AgentProtocol {
  private AgentProtocol() {}

  public static final String NODE_HEADER = "X-HAPM-Node";

  public record LastApply(Integer version, Boolean ok, String error, String at) {}

  public record Heartbeat(
      String name, String address, String agentVersion, String haproxyVersion, String vrrpState, Integer appliedVersion, LastApply lastApply) {}

  /** version nula = nada a aplicar; hold = aguarde (rollout ordenado). */
  public record Desired(Integer version, Boolean hold, String reason) {
    public static Desired of(Integer v) {
      return new Desired(v, null, null);
    }
  }

  public record HeartbeatResponse(Desired desired, Integer pollIntervalSec) {}
}
