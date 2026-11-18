package dev.hapm.core;

import dev.hapm.core.model.Backend;
import dev.hapm.core.model.Certificate;
import dev.hapm.core.model.Cluster;
import dev.hapm.core.model.ClusterSettings;
import dev.hapm.core.model.Frontend;
import dev.hapm.core.model.Frontend.Bind;
import dev.hapm.core.model.Frontend.Condition;
import dev.hapm.core.model.Frontend.Rule;
import dev.hapm.core.model.NodeInfo;
import dev.hapm.core.model.NodeSettings;
import dev.hapm.core.model.Snapshot;
import dev.hapm.core.validate.Normalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class Fixtures {
  private Fixtures() {}

  public static boolean hasBin(String bin) {
    try {
      return new ProcessBuilder("sh", "-c", "command -v " + bin).start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /** Certificado autoassinado (cert + chave) gerado com openssl. */
  public static String selfSigned(String cn) throws Exception {
    var dir = Files.createTempDirectory("hapm-cert");
    var p = new ProcessBuilder("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "30", "-subj", "/CN=" + cn,
        "-addext", "subjectAltName=DNS:" + cn + ",DNS:www." + cn, "-keyout", dir + "/k.pem", "-out", dir + "/c.pem")
        .redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    p.waitFor(30, TimeUnit.SECONDS);
    var pem = Files.readString(dir.resolve("c.pem")) + Files.readString(dir.resolve("k.pem"));
    Files.walk(dir).sorted((a, b) -> b.compareTo(a)).map(Path::toFile).forEach(java.io.File::delete);
    return pem;
  }

  public static Rule rule(String desc, List<Condition> conds, String action, String backend) {
    return new Rule(desc, conds, action, backend, null, null, null, null, null, null, null);
  }

  public static Condition cond(String type, String value) {
    return new Condition(type, null, List.of(value), false);
  }

  public static Frontend web() {
    return Normalizer.frontend(new Frontend("web", null, null, "http",
        List.of(new Bind("*", 80, false), new Bind("*", 443, true)), "app", true, true, null, null, null,
        List.of("0.0.0.0/0 ::/0"), new Frontend.RateLimit(true, 200, "10s"), null, null,
        List.of(
            rule("API", List.of(cond("host", "api.example.com *.api.example.com"), cond("path_beg", "/v1 /v2")), "use_backend", "api"),
            new Rule(null, List.of(cond("path_reg", "^/admin(/.*)?$"), new Condition("src", null, List.of("10.0.0.0/8"), true)), "deny", null, null, null, null, 403, null, null, null),
            new Rule(null, List.of(cond("host", "old.example.com")), "redirect", null, "prefix", "https://new.example.com", "301", null, null, null, null),
            new Rule(null, List.of(new Condition("header", "X-Debug", List.of("1"), false)), "set_header", null, null, null, null, null, "X-Trace", "%[uuid]", null),
            new Rule(null, List.of(), "del_header", null, null, null, null, null, "Server", null, "response")),
        null));
  }

  public static Frontend tcp() {
    return Normalizer.frontend(new Frontend("postgres", null, null, "tcp", List.of(new Bind("*", 5432, false)), "pg", null, null, null, null, null, null, null, null, null,
        List.of(new Rule(null, List.of(new Condition("src", null, List.of("192.168.0.0/16"), false)), "deny", null, null, null, null, null, null, null, null)), null));
  }

  public static List<Backend> backends() {
    return List.of(
        Normalizer.backend(new Backend("app", null, "http", "roundrobin",
            List.of(new Backend.Server("a1", "10.0.1.10", 8080, null, null, null, null), new Backend.Server("a2", "10.0.1.11", 8080, null, true, null, null)),
            new Backend.HealthCheck("http", "/health", null, null, "200-399", null, null, null), new Backend.Sticky(true, null), null, true, null, null, null)),
        Normalizer.backend(new Backend("api", null, "http", "leastconn", List.of(new Backend.Server("api1", "api-1.internal.local", 443, null, null, null, null)),
            new Backend.HealthCheck("http", "/ready", null, null, "200", null, null, null), null, new Backend.ServerSsl(true, "none", null), null, null, null, null)),
        Normalizer.backend(new Backend("pg", null, "tcp", null, List.of(new Backend.Server("pg1", "10.0.2.10", 5432, null, null, null, null)),
            new Backend.HealthCheck("tcp", null, null, null, null, null, null, null), null, null, null, null, null, null)));
  }

  public static Cluster cluster(String statsSocket) {
    var in = new ClusterSettings(ClusterSettings.ONPREM_KEEPALIVED, null, null,
        new ClusterSettings.Haproxy(null, null, "stdout format raw local0", "", statsSocket, null, "admin", "s3cret", null, null, null, null, null),
        new ClusterSettings.Keepalived("eth0", null, List.of("192.168.10.100/24"), "abc12345", null, null, null, null, null, null),
        null, new ClusterSettings.Acme("ops@example.com", "manager.internal:8080"));
    var n = Normalizer.cluster("prod", in, null);
    return new Cluster("c1", n.name(), n.settings());
  }

  public static List<NodeInfo> nodes() {
    return List.of(new NodeInfo("lb1", "192.168.10.11", NodeSettings.EMPTY), new NodeInfo("lb2", "192.168.10.12", NodeSettings.EMPTY));
  }

  public static Snapshot snapshot() throws Exception {
    var cert = new Certificate("example", "upload", selfSigned("example.com"), null, null);
    return new Snapshot(cluster("/tmp/hapm-test-admin.sock"), List.of(web(), tcp()), backends(), List.of(cert), nodes());
  }
}
