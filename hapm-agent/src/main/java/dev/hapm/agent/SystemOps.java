package dev.hapm.agent;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Integração com o sistema: processos, HAProxy (master CLI), keepalived. */
public class SystemOps {

  public record Result(boolean ok, String output, boolean missing) {
    static Result ok(String out) {
      return new Result(true, out, false);
    }

    static Result fail(String out) {
      return new Result(false, out, false);
    }
  }

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  public Result run(List<String> cmd, long timeoutSec) {
    try {
      var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      var out = new StringBuilder();
      var reader = Thread.ofVirtual().start(() -> {
        try {
          out.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
          // processo encerrado
        }
      });
      if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return Result.fail("timeout executando " + cmd.getFirst());
      }
      reader.join(2000);
      return new Result(p.exitValue() == 0, out.toString().strip(), false);
    } catch (IOException e) {
      return new Result(false, e.getMessage(), true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Result.fail("interrompido");
    }
  }

  public Result sh(String command) {
    return run(List.of("/bin/sh", "-c", command), 60);
  }

  public String haproxyVersion(String bin) {
    var r = run(List.of(bin, "-v"), 10);
    if (!r.ok()) return null;
    var m = Pattern.compile("version (\\S+)").matcher(r.output());
    return m.find() ? m.group(1) : null;
  }

  public Result haproxyCheck(String bin, Path cfg) {
    var r = run(List.of(bin, "-c", "-f", cfg.toString()), 30);
    if (r.missing()) throw new IllegalStateException("binário " + bin + " não encontrado");
    return r;
  }

  /** Envia "reload" ao master CLI (HAProxy master-worker). HAProxy >= 2.7 responde "Success=1|0". */
  public Result masterSocketReload(Path socket) {
    try (var ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      ch.connect(UnixDomainSocketAddress.of(socket));
      ch.write(ByteBuffer.wrap("reload\n".getBytes(StandardCharsets.US_ASCII)));
      // o canal é bloqueante: um "watchdog" fecha a conexão se o HAProxy não responder
      var watchdog = Thread.ofVirtual().start(() -> {
        try {
          Thread.sleep(20_000);
          ch.close();
        } catch (InterruptedException | IOException ignored) {
          // terminou antes
        }
      });
      var out = new StringBuilder();
      var buf = ByteBuffer.allocate(8192);
      try {
        int n;
        while ((n = ch.read(buf.clear())) >= 0) out.append(new String(buf.array(), 0, n, StandardCharsets.UTF_8));
      } catch (IOException e) {
        return Result.fail("timeout no master socket");
      } finally {
        watchdog.interrupt();
      }
      // HAProxy >= 2.7 responde "Success=1|0"; versões antigas apenas fecham a conexão
      var m = Pattern.compile("Success=(\\d)").matcher(out);
      return new Result(!m.find() || m.group(1).equals("1"), out.toString().strip(), false);
    } catch (IOException e) {
      return Result.fail("master socket " + socket + ": " + e.getMessage());
    }
  }

  public Result reloadHaproxy(AgentConfig cfg) {
    if ("master-socket".equals(cfg.reloadMode())) return masterSocketReload(cfg.masterSocket());
    return sh(cfg.reloadCommand());
  }

  public boolean isHealthy(String url) {
    try {
      var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build();
      return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() / 100 == 2;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  public Result waitHealthy(String url, long timeoutMs) {
    long end = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < end) {
      if (isHealthy(url)) return Result.ok("");
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return Result.fail("health-check " + url + " falhou");
  }

  /** Procura um processo pelo nome lendo /proc (sem depender de pidof). */
  public boolean processRunning(String name) {
    try (var dirs = Files.list(Path.of("/proc"))) {
      return dirs.filter(p -> p.getFileName().toString().matches("\\d+")).anyMatch(p -> {
        try {
          return Files.readString(p.resolve("comm")).strip().equals(name);
        } catch (IOException e) {
          return false;
        }
      });
    } catch (IOException e) {
      return false;
    }
  }

  public Result keepalivedCheck(String bin, Path file) {
    var r = run(List.of(bin, "-t", "-f", file.toString(), "-l", "-n"), 30);
    if (r.missing()) return Result.ok("keepalived não instalado; validação ignorada");
    return r;
  }
}
