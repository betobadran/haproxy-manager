package dev.hapm.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Scale-in: lê o estado do lifecycle no IMDS (simulado, IMDSv2) e conclui o hook pelo AWS CLI (simulado). */
class AwsLifecycleTest {
  @Test
  void detectsTerminationAndCompletesHook() throws Exception {
    var state = new AtomicReference<>("InService");
    var imds = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    imds.createContext("/latest/api/token", ex -> {
      var ok = "PUT".equals(ex.getRequestMethod());
      var b = "tok".getBytes();
      ex.sendResponseHeaders(ok ? 200 : 405, ok ? b.length : -1);
      if (ok) ex.getResponseBody().write(b);
      ex.close();
    });
    imds.createContext("/latest/meta-data/", ex -> {
      var path = ex.getRequestURI().getPath();
      var authed = "tok".equals(ex.getRequestHeaders().getFirst("X-aws-ec2-metadata-token"));
      var body = !authed ? null : path.endsWith("target-lifecycle-state") ? state.get() : path.endsWith("instance-id") ? "i-0abc" : null;
      if (body == null) {
        ex.sendResponseHeaders(404, -1);
      } else {
        var b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
      }
      ex.close();
    });
    imds.start();
    try {
      var dir = Files.createTempDirectory("hapm-asg");
      var log = dir.resolve("aws.log");
      var cli = dir.resolve("aws");
      Files.writeString(cli, "#!/bin/sh\necho \"$@\" >> " + log + "\n");
      cli.toFile().setExecutable(true);
      var lc = new AwsLifecycle("http://127.0.0.1:" + imds.getAddress().getPort(), "hook-x", "asg-y", new SystemOps(), cli.toString());
      assertThat(lc.terminating()).isFalse();
      state.set("Terminated");
      assertThat(lc.terminating()).isTrue();
      // AWS CLI falso: confere os argumentos do complete-lifecycle-action
      assertThat(lc.complete()).isTrue();
      assertThat(Files.readString(log)).isEqualTo("autoscaling complete-lifecycle-action --lifecycle-hook-name hook-x --auto-scaling-group-name asg-y "
          + "--lifecycle-action-result CONTINUE --instance-id i-0abc\n");
      // IMDS fora do ar nunca é interpretado como "saindo"
      imds.stop(0);
      assertThat(lc.terminating()).isFalse();
    } finally {
      imds.stop(0);
    }
  }
}
