package dev.hapm.manager.service;

import dev.hapm.core.model.Bundle;
import dev.hapm.core.render.HaproxyRenderer;
import dev.hapm.manager.HapmProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** Validação opcional com o binário real (`haproxy -c`) no manager, antes de publicar. */
@Service
public class HaproxyChecker {

  public record Check(boolean available, boolean ok, String output) {}

  private final HapmProperties props;

  public HaproxyChecker(HapmProperties props) {
    this.props = props;
  }

  public Check check(Bundle bundle) {
    Path dir = null;
    try {
      dir = Files.createTempDirectory("hapm-check-");
      for (var e : bundle.files().entrySet()) {
        var p = dir.resolve(e.getKey()).normalize();
        if (!p.startsWith(dir)) continue;
        Files.createDirectories(p.getParent());
        Files.writeString(p, e.getValue().replace(HaproxyRenderer.RELEASE_DIR, dir.toString()));
      }
      Process proc;
      try {
        proc = new ProcessBuilder(props.haproxyBin(), "-c", "-f", dir.resolve("haproxy.cfg").toString()).redirectErrorStream(true).start();
      } catch (IOException e) {
        return new Check(false, true, "haproxy não instalado no manager; a validação ocorre em cada nó");
      }
      var out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!proc.waitFor(30, TimeUnit.SECONDS)) proc.destroyForcibly();
      return new Check(true, proc.exitValue() == 0, out.replace(dir.toString(), HaproxyRenderer.RELEASE_DIR).strip());
    } catch (Exception e) {
      return new Check(true, false, "erro validando: " + e.getMessage());
    } finally {
      if (dir != null) {
        try (var w = Files.walk(dir)) {
          w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
          // temporário
        }
      }
    }
  }
}
