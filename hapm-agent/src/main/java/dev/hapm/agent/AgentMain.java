package dev.hapm.agent;

import com.sun.net.httpserver.HttpServer;
import dev.hapm.core.model.AgentProtocol.Heartbeat;
import dev.hapm.core.model.AgentProtocol.HeartbeatResponse;
import dev.hapm.core.model.AgentProtocol.LastApply;
import dev.hapm.core.model.Bundle;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** HAPM agent: heartbeat -> manager, baixa e aplica a versão desejada, expõe /metrics. */
public class AgentMain {

  /** Estado persistido em {stateDir}/agent-state.json. */
  public record State(LastApply lastApply, boolean usesKeepalived, long applySuccess, long applyFailure) {}

  static final JsonMapper JSON = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private final AgentConfig cfg;
  private final SystemOps sys;
  private final Applier applier;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final TrafficCollector traffic;

  private volatile State state = new State(null, false, 0, 0);
  private volatile Integer desiredVersion;
  private volatile boolean heartbeatOk;
  private volatile String haproxyVersion;
  private volatile boolean stop;
  /** último preflight de failover AWS (modo aws-eip), enviado no heartbeat */
  volatile dev.hapm.core.model.AgentProtocol.AwsCheck awsCheck;

  public AgentMain(AgentConfig cfg) {
    this.cfg = cfg;
    this.sys = new SystemOps();
    this.applier = new Applier(cfg, sys);
    this.traffic = new TrafficCollector("http://127.0.0.1:" + cfg.statsPort() + "/metrics");
  }

  public static void main(String[] args) throws Exception {
    var cfg = AgentConfig.fromEnv(System.getenv());
    var agent = new AgentMain(cfg);
    if (List.of(args).contains("--bootstrap")) {
      Files.createDirectories(cfg.haproxyDir());
      Log.info(agent.applier.ensureBootstrap() ? "bootstrap criado" : "bootstrap já existia");
      return;
    }
    if (cfg.joinToken().isEmpty()) {
      Log.error("HAPM_JOIN_TOKEN não definido");
      System.exit(2);
    }
    Runtime.getRuntime().addShutdownHook(new Thread(() -> agent.stop = true));
    agent.run();
  }

  public void run() throws Exception {
    Files.createDirectories(cfg.haproxyDir());
    applier.ensureBootstrap();
    loadState();
    haproxyVersion = sys.haproxyVersion(cfg.haproxyBin());
    Log.info("agente " + cfg.version() + " nó=" + cfg.nodeName() + " endereço=" + cfg.nodeAddress() + " manager=" + cfg.managerUrl() + " haproxy=" + haproxyVersion);
    var server = startMetricsServer();
    try {
      while (!stop) {
        double wait;
        try {
          wait = tick();
        } catch (Exception e) {
          Log.error("erro no ciclo: " + e);
          wait = cfg.pollIntervalSec();
        }
        Thread.sleep((long) (wait * 1000));
      }
    } finally {
      server.stop(0);
    }
  }

  public void stop() {
    stop = true;
  }

  // ------------------------------------------------------------------ estado
  private void loadState() {
    try {
      var f = cfg.stateDir().resolve("agent-state.json");
      if (Files.exists(f)) state = JSON.readValue(Files.readString(f), State.class);
    } catch (Exception e) {
      Log.warn("estado local ilegível, ignorando: " + e.getMessage());
    }
  }

  private void saveState() throws IOException {
    Files.createDirectories(cfg.stateDir());
    Files.writeString(cfg.stateDir().resolve("agent-state.json"), JSON.writeValueAsString(state));
  }

  String vrrpState() {
    if (!state.usesKeepalived()) return null;
    if (!sys.processRunning("keepalived")) return "STOP";
    try {
      var s = Files.readString(cfg.vrrpStateFile()).strip().toUpperCase();
      return List.of("MASTER", "BACKUP", "FAULT", "STOP").contains(s) ? s : "UNKNOWN";
    } catch (IOException e) {
      return "UNKNOWN";
    }
  }

  // ------------------------------------------------------------------ comunicação com o manager
  private String call(String method, String path, String body) throws IOException, InterruptedException {
    var req = HttpRequest.newBuilder(URI.create(cfg.managerUrl() + path))
        .timeout(Duration.ofSeconds(15))
        .header("Authorization", "Bearer " + cfg.joinToken())
        .header("X-HAPM-Node", cfg.nodeName())
        .header("Content-Type", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        .build();
    var res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (res.statusCode() / 100 != 2) {
      var b = res.body();
      throw new IOException(method + " " + path + ": HTTP " + res.statusCode() + " " + b.substring(0, Math.min(300, b.length())));
    }
    return res.body();
  }

  private volatile long lastPreflight;

  /**
   * Modo aws-eip: valida periodicamente (e após cada aplicação) se o failover de IP vai funcionar —
   * permissões IAM via --dry-run, ENI, subnet, tabelas de rotas — e manda o resultado ao manager.
   */
  void maybePreflight(boolean force) {
    var env = cfg.keepalivedDir().resolve("hapm-failover.env");
    try {
      if (!Files.exists(env) || !Files.readString(env).contains("HAPM_MODE=aws-eip")) {
        awsCheck = null;
        return;
      }
    } catch (IOException e) {
      return;
    }
    if (!force && System.currentTimeMillis() - lastPreflight < 300_000) return;
    lastPreflight = System.currentTimeMillis();
    var r = sys.run(List.of(cfg.libDir().resolve("keepalived-notify.sh").toString(), "--preflight"), 90);
    var lines = r.output().lines().filter(l -> l.matches("^(OK|WARN|FAIL)\t.*")).toList();
    awsCheck = new dev.hapm.core.model.AgentProtocol.AwsCheck(Instant.now().toString(), r.ok() && lines.stream().noneMatch(l -> l.startsWith("FAIL")),
        lines.isEmpty() ? List.of("FAIL\t" + r.output().lines().reduce((a, b) -> b).orElse("preflight sem saída")) : lines);
    if (!awsCheck.ok()) Log.warn("preflight AWS com problemas: " + String.join(" | ", awsCheck.lines()));
  }

  /** Um ciclo: heartbeat e, se preciso, aplica a versão desejada. Retorna segundos até o próximo. */
  double tick() throws Exception {
    maybePreflight(false);
    HeartbeatResponse res;
    try {
      var hb = new Heartbeat(cfg.nodeName(), cfg.nodeAddress(), cfg.version(), haproxyVersion, vrrpState(), applier.appliedVersion(), state.lastApply(),
          traffic.collect(), awsCheck);
      res = JSON.readValue(call("POST", "/agent/v1/heartbeat", JSON.writeValueAsString(hb)), HeartbeatResponse.class);
      heartbeatOk = true;
    } catch (IOException e) {
      heartbeatOk = false;
      Log.warn("heartbeat falhou: " + e.getMessage());
      return cfg.pollIntervalSec();
    }
    int poll = res.pollIntervalSec() == null ? cfg.pollIntervalSec() : Math.min(res.pollIntervalSec(), cfg.pollIntervalSec());
    var d = res.desired();
    desiredVersion = d == null ? null : d.version();
    int applied = applier.appliedVersion();
    if (d == null || d.version() == null || d.version() == applied) return poll;
    if (Boolean.TRUE.equals(d.hold())) {
      Log.info("v" + d.version() + " pendente: " + d.reason());
      return 2;
    }
    var last = state.lastApply();
    // não tenta de novo uma versão que já falhou localmente
    if (last != null && d.version().equals(last.version()) && !Boolean.TRUE.equals(last.ok())) return poll;

    Log.info("aplicando v" + d.version() + " (atual v" + applied + ")");
    try {
      var bundle = JSON.readValue(call("GET", "/agent/v1/bundle/" + d.version(), null), Bundle.class);
      state = new State(state.lastApply(), bundle.keepalived() != null, state.applySuccess(), state.applyFailure());
      var r = applier.apply(bundle);
      state = new State(new LastApply(d.version(), true, null, Instant.now().toString()), state.usesKeepalived(), state.applySuccess() + 1, state.applyFailure());
      Log.info("v" + d.version() + " aplicada em " + r.durationMs() + "ms" + (r.keepalivedChanged() ? " (keepalived recarregado)" : ""));
      if (r.keepalivedChanged()) maybePreflight(true);
    } catch (Exception e) {
      state = new State(new LastApply(d.version(), false, e.getMessage(), Instant.now().toString()), state.usesKeepalived(), state.applySuccess(), state.applyFailure() + 1);
      Log.error("falha ao aplicar v" + d.version() + ": " + e.getMessage());
    }
    saveState();
    return 0.2; // reporta o resultado logo em seguida
  }

  // ------------------------------------------------------------------ métricas Prometheus
  String metrics() {
    var sb = new StringBuilder();
    var vs = vrrpState();
    var st = state;
    gauge(sb, "hapm_agent_info", "Informações do agente", "hapm_agent_info{version=\"" + cfg.version() + "\",haproxy_version=\"" + (haproxyVersion == null ? "" : haproxyVersion) + "\"} 1");
    gauge(sb, "hapm_agent_heartbeat_ok", "Último heartbeat ao manager teve sucesso", "hapm_agent_heartbeat_ok " + (heartbeatOk ? 1 : 0));
    gauge(sb, "hapm_haproxy_up", "HAProxy responde ao health-check local", "hapm_haproxy_up " + (sys.isHealthy(cfg.healthUrl()) ? 1 : 0));
    gauge(sb, "hapm_config_applied_version", "Versão de configuração ativa", "hapm_config_applied_version " + applier.appliedVersion());
    gauge(sb, "hapm_config_desired_version", "Versão desejada informada pelo manager", "hapm_config_desired_version " + (desiredVersion == null ? 0 : desiredVersion));
    var last = st.lastApply();
    gauge(sb, "hapm_config_last_apply_success", "Última aplicação teve sucesso", "hapm_config_last_apply_success " + (last == null || Boolean.TRUE.equals(last.ok()) ? 1 : 0));
    if (last != null && last.at() != null)
      gauge(sb, "hapm_config_last_apply_timestamp_seconds", "Momento da última aplicação", "hapm_config_last_apply_timestamp_seconds " + Instant.parse(last.at()).getEpochSecond());
    sb.append("# HELP hapm_config_apply_total Aplicações por resultado\n# TYPE hapm_config_apply_total counter\n")
        .append("hapm_config_apply_total{result=\"success\"} ").append(st.applySuccess()).append('\n')
        .append("hapm_config_apply_total{result=\"failure\"} ").append(st.applyFailure()).append('\n');
    if (st.usesKeepalived()) {
      var lines = List.of("MASTER", "BACKUP", "FAULT", "STOP", "UNKNOWN").stream().map(s -> "hapm_vrrp_state{state=\"" + s + "\"} " + (s.equals(vs) ? 1 : 0)).toArray(String[]::new);
      gauge(sb, "hapm_vrrp_state", "Estado VRRP do keepalived", lines);
      gauge(sb, "hapm_vrrp_is_master", "Este nó é o MASTER (detém o VIP)", "hapm_vrrp_is_master " + ("MASTER".equals(vs) ? 1 : 0));
      gauge(sb, "hapm_keepalived_running", "Processo keepalived em execução", "hapm_keepalived_running " + ("STOP".equals(vs) ? 0 : 1));
    }
    var certs = applier.certificates().stream()
        .map(c -> "hapm_certificate_not_after_timestamp_seconds{certificate=\"" + c.name() + "\",cn=\"" + c.cn().replaceAll("[\"\\\\\\n]", "") + "\"} " + c.notAfterEpochSec())
        .toArray(String[]::new);
    gauge(sb, "hapm_certificate_not_after_timestamp_seconds", "Expiração dos certificados em uso", certs);
    return sb.toString();
  }

  private static void gauge(StringBuilder sb, String name, String help, String... lines) {
    sb.append("# HELP ").append(name).append(' ').append(help).append('\n').append("# TYPE ").append(name).append(" gauge\n");
    for (var l : lines) sb.append(l).append('\n');
  }

  private HttpServer startMetricsServer() throws IOException {
    var server = HttpServer.create(new InetSocketAddress(cfg.metricsHost(), cfg.metricsPort()), 0);
    server.createContext("/metrics", ex -> {
      byte[] body;
      int code = 200;
      try {
        body = metrics().getBytes(StandardCharsets.UTF_8);
      } catch (Exception e) {
        body = e.toString().getBytes(StandardCharsets.UTF_8);
        code = 500;
      }
      ex.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4");
      ex.sendResponseHeaders(code, body.length);
      try (var os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    server.createContext("/healthz", ex -> {
      ex.sendResponseHeaders(200, 2);
      try (var os = ex.getResponseBody()) {
        os.write("ok".getBytes(StandardCharsets.US_ASCII));
      }
    });
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    Log.info("métricas em http://" + cfg.metricsHost() + ":" + cfg.metricsPort() + "/metrics");
    return server;
  }
}
