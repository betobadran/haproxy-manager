package dev.hapm.core.model;

/** Mensagens trocadas entre agente e manager (modelo pull: só o agente inicia conexões). */
public final class AgentProtocol {
  private AgentProtocol() {}

  public static final String NODE_HEADER = "X-HAPM-Node";

  public record LastApply(Integer version, Boolean ok, String error, String at) {}

  public record Heartbeat(
      String name, String address, String agentVersion, String haproxyVersion, String vrrpState, Integer appliedVersion, LastApply lastApply,
      Traffic traffic, AwsCheck aws) {

    public Heartbeat(String name, String address, String agentVersion, String haproxyVersion, String vrrpState, Integer appliedVersion, LastApply lastApply) {
      this(name, address, agentVersion, haproxyVersion, vrrpState, appliedVersion, lastApply, null, null);
    }
  }

  /**
   * Amostra dos contadores do HAProxy (lidos do exporter Prometheus local). Contadores são acumulados;
   * o manager calcula as taxas entre amostras consecutivas.
   */
  public record Traffic(long ts, Double connections, java.util.List<FrontendStat> frontends, java.util.List<BackendStat> backends,
                        java.util.List<ServerStat> servers) {}

  public record FrontendStat(String name, double requests, double sessionsTotal, double sessions, double bytesIn, double bytesOut,
                             double r1xx, double r2xx, double r3xx, double r4xx, double r5xx, double rOther, double denied) {}

  public record BackendStat(String name, boolean up, int active, int backup, double responseTime, double queue, double sessions,
                            double r5xx, double responses) {}

  public record ServerStat(String backend, String name, String state) {}

  /** Resultado do preflight de failover na AWS (permissões IAM, ENI, rotas...). */
  public record AwsCheck(String at, boolean ok, java.util.List<String> lines) {}

  /** version nula = nada a aplicar; hold = aguarde (rollout ordenado). */
  public record Desired(Integer version, Boolean hold, String reason) {
    public static Desired of(Integer v) {
      return new Desired(v, null, null);
    }
  }

  public record HeartbeatResponse(Desired desired, Integer pollIntervalSec) {}
}
