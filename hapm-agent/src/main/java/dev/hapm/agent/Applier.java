package dev.hapm.agent;

import dev.hapm.core.model.Bundle;
import dev.hapm.core.render.HaproxyRenderer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Aplica uma versão: grava a release, valida com {@code haproxy -c}, troca o symlink "current" de forma
 * atômica, recarrega e faz rollback local se o reload/health-check falhar.
 *
 * <pre>
 *   /etc/haproxy/hapm/
 *     current -> releases/v12            (o systemd/haproxy usa current/haproxy.cfg)
 *     releases/v11/{haproxy.cfg,certs.list,certs/*.pem}
 *     releases/v12/...
 * </pre>
 */
public class Applier {
  private static final Pattern VERSION_DIR = Pattern.compile("^v(\\d+)$");

  public record Result(int version, long durationMs, boolean keepalivedChanged) {}

  public record CertInfo(String name, String cn, long notAfterEpochSec) {}

  private final AgentConfig cfg;
  private final SystemOps sys;
  private final Path releases;
  private final Path current;

  public Applier(AgentConfig cfg, SystemOps sys) {
    this.cfg = cfg;
    this.sys = sys;
    this.releases = cfg.haproxyDir().resolve("releases");
    this.current = cfg.haproxyDir().resolve("current");
  }

  public static String bootstrapConfig(int statsPort) {
    return String.join("\n",
        "# Configuração inicial do HAPM (nenhuma versão publicada ainda)",
        "global",
        "    maxconn 4096",
        "defaults",
        "    mode http",
        "    timeout connect 5s",
        "    timeout client 30s",
        "    timeout server 30s",
        "frontend hapm_stats",
        "    bind :" + statsPort,
        "    http-request use-service prometheus-exporter if { path /metrics }",
        "    monitor-uri /healthz",
        "");
  }

  public Path currentRelease() {
    try {
      return Files.isSymbolicLink(current) ? Files.readSymbolicLink(current) : null;
    } catch (IOException e) {
      return null;
    }
  }

  public int appliedVersion() {
    var r = currentRelease();
    if (r == null) return 0;
    var m = VERSION_DIR.matcher(r.getFileName().toString());
    return m.matches() ? Integer.parseInt(m.group(1)) : 0;
  }

  /** Garante uma config mínima para o haproxy subir antes do primeiro deploy. */
  public boolean ensureBootstrap() throws IOException {
    if (currentRelease() != null) return false;
    var dir = releases.resolve("v0");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("haproxy.cfg"), bootstrapConfig(cfg.statsPort()));
    swap(dir);
    Log.info("release bootstrap v0 criada");
    return true;
  }

  void swap(Path target) throws IOException {
    var tmp = cfg.haproxyDir().resolve("current.tmp-" + ProcessHandle.current().pid());
    Files.deleteIfExists(tmp);
    var base = cfg.haproxyDir().toAbsolutePath().normalize();
    Files.createSymbolicLink(tmp, base.relativize(target.toAbsolutePath().normalize()));
    try {
      Files.move(tmp, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); // rename(2) é atômico
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, current, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void write(Path p, String content, String perms) throws IOException {
    Files.createDirectories(p.getParent());
    Files.writeString(p, content, StandardCharsets.UTF_8);
    try {
      Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
    } catch (UnsupportedOperationException ignored) {
      // FS sem POSIX
    }
  }

  private static void deleteTree(Path p) throws IOException {
    if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
    try (Stream<Path> w = Files.walk(p)) {
      for (var f : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
    }
  }

  public Result apply(Bundle bundle) throws Exception {
    long started = System.currentTimeMillis();
    if (!bundle.verify()) throw new IllegalStateException("checksum do bundle não confere");
    var dir = releases.resolve("v" + bundle.version()).toAbsolutePath().normalize();
    var staging = dir.resolveSibling(dir.getFileName() + ".staging");
    deleteTree(staging);
    Files.createDirectories(staging);

    // 1) grava no staging com caminhos apontando para o staging e valida
    for (var e : bundle.files().entrySet()) {
      var target = staging.resolve(e.getKey()).normalize();
      if (!target.startsWith(staging) || target.equals(staging)) throw new IllegalStateException("caminho inválido no bundle: " + e.getKey());
      write(target, e.getValue().replace(HaproxyRenderer.RELEASE_DIR, staging.toString()), e.getKey().endsWith(".pem") ? "rw-------" : "rw-r-----");
    }
    var chk = sys.haproxyCheck(cfg.haproxyBin(), staging.resolve("haproxy.cfg"));
    if (!chk.ok()) {
      deleteTree(staging);
      throw new IllegalStateException("haproxy -c falhou: " + chk.output().replace(staging.toString(), "<release>"));
    }
    // 2) caminhos definitivos e move para releases/vN
    for (var e : bundle.files().entrySet()) {
      if (e.getKey().endsWith(".pem")) continue;
      var f = staging.resolve(e.getKey());
      Files.writeString(f, Files.readString(f).replace(staging.toString(), dir.toString()));
    }
    deleteTree(dir);
    Files.move(staging, dir);

    // 3) keepalived antes: se falhar, o haproxy segue intacto
    boolean kaChanged = bundle.keepalived() != null && applyKeepalived(bundle.keepalived());

    // 4) troca atômica + reload; rollback local se reload/health falhar
    var previous = currentRelease();
    swap(dir);
    var r = sys.reloadHaproxy(cfg);
    if (r.ok()) r = sys.waitHealthy(cfg.healthUrl(), 15_000);
    if (!r.ok()) {
      Log.error("reload falhou, voltando para " + previous + ": " + r.output());
      if (previous != null) {
        swap(cfg.haproxyDir().resolve(previous));
        sys.reloadHaproxy(cfg);
      }
      throw new IllegalStateException("reload do haproxy falhou (rollback para " + previous + "): " + r.output());
    }
    prune(dir, previous);
    return new Result(bundle.version(), System.currentTimeMillis() - started, kaChanged);
  }

  boolean applyKeepalived(Map<String, String> files) throws IOException {
    var kdir = cfg.keepalivedDir();
    var conf = kdir.resolve("keepalived.conf");
    var envFile = kdir.resolve("hapm-failover.env");
    var prev = Files.exists(conf) ? Files.readString(conf) : null;
    var prevEnv = Files.exists(envFile) ? Files.readString(envFile) : null;
    if (Objects.equals(prev, files.get("keepalived.conf")) && Objects.equals(prevEnv, files.get("hapm-failover.env"))) return false;
    Files.createDirectories(kdir);
    var tmp = kdir.resolve(".keepalived.conf.hapm-" + ProcessHandle.current().pid());
    write(tmp, files.get("keepalived.conf"), "rw-r-----");
    var chk = sys.keepalivedCheck(cfg.keepalivedBin(), tmp);
    if (!chk.ok()) {
      Files.deleteIfExists(tmp);
      throw new IllegalStateException("keepalived -t falhou: " + chk.output());
    }
    if (files.get("hapm-failover.env") != null) write(envFile, files.get("hapm-failover.env"), "rw-r-----");
    Files.move(tmp, conf, StandardCopyOption.REPLACE_EXISTING);
    var r = sys.sh(cfg.keepalivedReloadCommand());
    if (!r.ok()) {
      if (prev != null) {
        Files.writeString(conf, prev);
        sys.sh(cfg.keepalivedReloadCommand());
      }
      throw new IllegalStateException("reload do keepalived falhou: " + r.output());
    }
    return true;
  }

  void prune(Path currentDir, Path previous) throws IOException {
    var keep = new HashSet<String>();
    keep.add(currentDir.getFileName().toString());
    if (previous != null) keep.add(previous.getFileName().toString());
    List<Path> all;
    try (var s = Files.list(releases)) {
      all = s.toList();
    }
    var versions = new ArrayList<>(all.stream().filter(p -> VERSION_DIR.matcher(p.getFileName().toString()).matches()).toList());
    versions.sort(Comparator.comparingInt((Path p) -> Integer.parseInt(p.getFileName().toString().substring(1))).reversed());
    for (int i = cfg.keepReleases(); i < versions.size(); i++) {
      if (!keep.contains(versions.get(i).getFileName().toString())) deleteTree(versions.get(i));
    }
    for (var p : all) if (p.getFileName().toString().endsWith(".staging")) deleteTree(p);
  }

  /** Expiração dos certificados da release atual (para métricas). */
  public List<CertInfo> certificates() {
    var dir = current.resolve("certs");
    var out = new ArrayList<CertInfo>();
    if (!Files.isDirectory(dir)) return out;
    try (var files = Files.list(dir)) {
      var cf = CertificateFactory.getInstance("X.509");
      for (var f : files.filter(p -> p.toString().endsWith(".pem")).sorted().toList()) {
        try {
          var pem = Files.readString(f);
          int a = pem.indexOf("-----BEGIN CERTIFICATE-----");
          int b = pem.indexOf("-----END CERTIFICATE-----");
          if (a < 0 || b < 0) continue;
          var x = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(pem.substring(a, b + 25).getBytes(StandardCharsets.US_ASCII)));
          var cn = Pattern.compile("CN=([^,]+)").matcher(x.getSubjectX500Principal().getName());
          out.add(new CertInfo(f.getFileName().toString().replaceAll("\\.pem$", ""), cn.find() ? cn.group(1) : "", x.getNotAfter().toInstant().getEpochSecond()));
        } catch (Exception ignored) {
          // arquivo inválido
        }
      }
    } catch (Exception ignored) {
      // sem certificados
    }
    return out;
  }
}
