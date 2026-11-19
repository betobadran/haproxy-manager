package dev.hapm.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Scale-in na AWS: com um lifecycle hook de término no Auto Scaling Group, o IMDS passa a informar
 * {@code autoscaling/target-lifecycle-state = Terminated}. O agente então avisa o manager (heartbeat com
 * leaving=true), que remove o nó, e opcionalmente conclui o hook para a instância terminar sem esperar o timeout.
 */
public class AwsLifecycle {
  private final String imds;
  private final String hookName;
  private final String asgName;
  private final SystemOps sys;
  private final String awsCli;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

  public AwsLifecycle(String imds, String hookName, String asgName, SystemOps sys) {
    this(imds, hookName, asgName, sys, System.getenv().getOrDefault("HAPM_AWS_CLI", "aws"));
  }

  public AwsLifecycle(String imds, String hookName, String asgName, SystemOps sys, String awsCli) {
    this.awsCli = awsCli;
    this.imds = imds == null || imds.isBlank() ? "http://169.254.169.254" : imds.replaceAll("/$", "");
    this.hookName = hookName;
    this.asgName = asgName;
    this.sys = sys;
  }

  public static AwsLifecycle fromEnv(SystemOps sys) {
    if (!"1".equals(System.getenv("HAPM_AWS_LIFECYCLE"))) return null;
    return new AwsLifecycle(System.getenv("HAPM_IMDS_URL"), System.getenv("HAPM_ASG_HOOK_NAME"), System.getenv("HAPM_ASG_NAME"), sys);
  }

  private String token() throws Exception {
    var r = http.send(HttpRequest.newBuilder(URI.create(imds + "/latest/api/token")).timeout(Duration.ofSeconds(2))
        .header("X-aws-ec2-metadata-token-ttl-seconds", "60").PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() != 200) throw new IllegalStateException("IMDS token HTTP " + r.statusCode());
    return r.body();
  }

  private String meta(String token, String path) throws Exception {
    var r = http.send(HttpRequest.newBuilder(URI.create(imds + "/latest/meta-data/" + path)).timeout(Duration.ofSeconds(2))
        .header("X-aws-ec2-metadata-token", token).GET().build(), HttpResponse.BodyHandlers.ofString());
    return r.statusCode() == 200 ? r.body().strip() : null;
  }

  /** true quando o Auto Scaling decidiu encerrar esta instância. */
  public boolean terminating() {
    try {
      return "Terminated".equals(meta(token(), "autoscaling/target-lifecycle-state"));
    } catch (Exception e) {
      return false; // IMDS inacessível: nunca conclui que está saindo
    }
  }

  /** Conclui o lifecycle hook (CONTINUE) se nome do hook e do ASG foram configurados. */
  public boolean complete() {
    if (hookName == null || hookName.isBlank() || asgName == null || asgName.isBlank()) return false;
    try {
      var id = meta(token(), "instance-id");
      var r = sys.run(List.of(awsCli, "autoscaling", "complete-lifecycle-action",
          "--lifecycle-hook-name", hookName, "--auto-scaling-group-name", asgName, "--lifecycle-action-result", "CONTINUE", "--instance-id", id), 30);
      if (r.ok()) Log.info("lifecycle hook concluído: a instância pode terminar");
      else Log.warn("não foi possível concluir o lifecycle hook (a instância termina no timeout do hook): " + r.output());
      return r.ok();
    } catch (Exception e) {
      Log.warn("lifecycle hook: " + e.getMessage());
      return false;
    }
  }
}
