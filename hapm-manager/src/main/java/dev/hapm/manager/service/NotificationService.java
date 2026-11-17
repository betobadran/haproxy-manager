package dev.hapm.manager.service;

import dev.hapm.core.model.Certificate;
import dev.hapm.manager.store.Store;
import dev.hapm.manager.store.Store.NotificationRow;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Alertas de expiração de certificado (e falhas de emissão ACME): aparecem na tela e são enviados por e-mail,
 * Microsoft Teams e webhook genérico. Cada limite (ex.: 30, 14, 7, 1 dias e "expirado") gera UM alerta por
 * certificado — renovar o certificado (nova data de expiração) reinicia o ciclo.
 */
@Service
public class NotificationService {
  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
  public static final String SETTINGS_KEY = "notify.settings";
  public static final String MASK = "********";

  public record Email(Boolean enabled, String host, Integer port, String username, String password, String from, List<String> to,
                      Boolean starttls, Boolean ssl) {}

  public record Teams(Boolean enabled, String webhookUrl) {}

  public record Webhook(Boolean enabled, String url, String secret) {}

  /** Alertas operacionais (além de certificados). offlineAfterSec: tempo sem heartbeat para alertar nó offline. */
  public record Ops(Boolean failover, Boolean nodeOffline, Integer offlineAfterSec, Boolean splitBrain, Boolean deployFailed, Boolean serverDown) {
    public static Ops defaults() {
      return new Ops(true, true, 60, true, true, true);
    }

    Ops normalized() {
      var d = defaults();
      return new Ops(failover == null ? d.failover : failover, nodeOffline == null ? d.nodeOffline : nodeOffline,
          offlineAfterSec == null ? d.offlineAfterSec : offlineAfterSec, splitBrain == null ? d.splitBrain : splitBrain,
          deployFailed == null ? d.deployFailed : deployFailed, serverDown == null ? d.serverDown : serverDown);
    }
  }

  public record Settings(List<Integer> thresholds, Email email, Teams teams, Webhook webhook, Ops ops) {
    public Settings(List<Integer> thresholds, Email email, Teams teams, Webhook webhook) {
      this(thresholds, email, teams, webhook, Ops.defaults());
    }

    public static Settings defaults() {
      return new Settings(List.of(30, 14, 7, 1), new Email(false, "", 587, "", "", "", List.of(), true, false),
          new Teams(false, ""), new Webhook(false, "", ""), Ops.defaults());
    }
  }

  public record Delivery(String channel, boolean ok, String error, String at) {}

  private final Store store;
  private final java.util.concurrent.ExecutorService sender = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public NotificationService(Store store) {
    this.store = store;
  }

  // ------------------------------------------------------------------ configuração
  public Settings settings() {
    var s = store.getKv(SETTINGS_KEY).map(j -> store.fromJson(j, Settings.class)).orElseGet(Settings::defaults);
    return new Settings(s.thresholds(), s.email(), s.teams(), s.webhook(), s.ops() == null ? Ops.defaults() : s.ops().normalized());
  }

  private static String keep(String incoming, String current) {
    return incoming == null || incoming.equals(MASK) ? current : incoming.strip();
  }

  private static boolean on(Boolean b) {
    return Boolean.TRUE.equals(b);
  }

  /** Salva preservando segredos não alterados (a UI envia "********" no lugar deles). */
  public Settings save(Settings in) {
    var cur = settings();
    var e = in.email() == null ? cur.email() : in.email();
    var t = in.teams() == null ? cur.teams() : in.teams();
    var w = in.webhook() == null ? cur.webhook() : in.webhook();
    var th = new TreeSet<Integer>();
    for (var x : in.thresholds() == null ? cur.thresholds() : in.thresholds()) {
      if (x == null || x < 1 || x > 365) throw new IllegalArgumentException("limites devem estar entre 1 e 365 dias");
      th.add(x);
    }
    var email = new Email(on(e.enabled()), nz(e.host()), e.port() == null ? 587 : e.port(), nz(e.username()), keep(e.password(), cur.email().password()),
        nz(e.from()), e.to() == null ? List.of() : e.to().stream().map(String::strip).filter(s -> !s.isEmpty()).toList(), on(e.starttls()), on(e.ssl()));
    if (email.enabled()) {
      if (email.host().isEmpty() || email.from().isEmpty() || email.to().isEmpty()) throw new IllegalArgumentException("e-mail: informe servidor SMTP, remetente e destinatários");
      for (var addr : email.to()) if (!addr.matches("^[^\\s@]+@[^\\s@]+$")) throw new IllegalArgumentException("e-mail inválido: " + addr);
    }
    var teams = new Teams(on(t.enabled()), keep(t.webhookUrl(), cur.teams().webhookUrl()));
    if (teams.enabled() && !teams.webhookUrl().startsWith("https://")) throw new IllegalArgumentException("Teams: informe a URL HTTPS do webhook (Workflows)");
    var hook = new Webhook(on(w.enabled()), nz(w.url()), keep(w.secret(), cur.webhook().secret()));
    if (hook.enabled() && !hook.url().matches("^https?://\\S+$")) throw new IllegalArgumentException("webhook: URL inválida");
    var ops = in.ops() == null ? cur.ops() : in.ops().normalized();
    if (ops.offlineAfterSec() < 15 || ops.offlineAfterSec() > 3600) throw new IllegalArgumentException("tempo para alertar nó offline deve estar entre 15 e 3600 s");
    var s = new Settings(new ArrayList<>(th.descendingSet()), email, teams, hook, ops);
    store.setKv(SETTINGS_KEY, store.toJson(s));
    return s;
  }

  private static String nz(String s) {
    return s == null ? "" : s.strip();
  }

  /** Para a API: segredos substituídos por "********". */
  public Settings masked() {
    var s = settings();
    return new Settings(s.thresholds(),
        new Email(s.email().enabled(), s.email().host(), s.email().port(), s.email().username(), s.email().password().isEmpty() ? "" : MASK,
            s.email().from(), s.email().to(), s.email().starttls(), s.email().ssl()),
        new Teams(s.teams().enabled(), s.teams().webhookUrl().isEmpty() ? "" : MASK),
        new Webhook(s.webhook().enabled(), s.webhook().url(), s.webhook().secret().isEmpty() ? "" : MASK), s.ops());
  }

  // ------------------------------------------------------------------ verificação de certificados
  /** De hora em hora (e 1 min após subir): gera alertas para certificados perto de expirar ou expirados. */
  @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
  public void scheduledCheck() {
    try {
      checkCertificates();
    } catch (Exception e) {
      log.error("falha verificando certificados", e);
    }
  }

  /** Retorna os alertas criados nesta verificação. */
  public List<NotificationRow> checkCertificates() {
    var s = settings();
    var created = new ArrayList<NotificationRow>();
    var now = Instant.now();
    for (var c : store.listClusters()) {
      for (var row : store.listObjects(c.id(), DeployService.CERTIFICATE)) {
        var cert = store.fromJson(row.data(), Certificate.class);
        if (cert.meta() == null || cert.meta().notAfter() == null) continue;
        var notAfter = Instant.parse(cert.meta().notAfter());
        long days = Math.floorDiv(Duration.between(now, notAfter).toSeconds(), 86_400);
        String bucket = null;
        if (days < 0) bucket = "expired";
        else for (var t : s.thresholds().stream().sorted().toList()) if (days < t) { bucket = String.valueOf(t); break; }
        if (bucket == null) continue;
        boolean expired = bucket.equals("expired");
        var level = expired || days < 7 ? "critical" : "warning";
        var domains = cert.meta().domains() == null ? "" : String.join(", ", cert.meta().domains());
        var title = expired
            ? "Certificado " + cert.name() + " EXPIRADO (cluster " + c.name() + ")"
            : "Certificado " + cert.name() + " expira em " + days + " dia(s) (cluster " + c.name() + ")";
        var msg = (expired ? "Expirou em " : "Expira em ") + cert.meta().notAfter() + ". Domínios: " + domains + ". Origem: "
            + ("acme".equals(cert.source()) ? "ACME (renovação automática: verifique se ela está falhando)"
                : "acm".equals(cert.source()) ? "AWS ACM (o ACM renova e o HAPM reexporta: verifique a validação DNS no ACM e o acesso à AWS)"
                : "upload manual (envie o novo certificado e publique)") + ".";
        var data = new LinkedHashMap<String, Object>();
        data.put("cluster", c.name());
        data.put("certificate", cert.name());
        data.put("domains", cert.meta().domains());
        data.put("notAfter", cert.meta().notAfter());
        data.put("daysLeft", days);
        data.put("source", cert.source());
        var key = "cert:" + c.id() + ":" + cert.name() + ":" + cert.meta().notAfter() + ":" + bucket;
        store.createNotification(level, expired ? "cert-expired" : "cert-expiring", c.id(), title, msg, store.toJson(data), key).ifPresent(n -> {
          deliver(n, s);
          created.add(n);
        });
      }
    }
    return created;
  }

  /** Alertas operacionais habilitados (failover, nó offline...). */
  public Ops ops() {
    return settings().ops();
  }

  /** Alerta avulso (ex.: falha de emissão ACME). */
  public void notify(String level, String kind, String clusterId, String title, String message, Map<String, ?> data, String dedupeKey) {
    // entrega em background: quem chama (ex.: heartbeat de agente) não espera SMTP/HTTP
    store.createNotification(level, kind, clusterId, title, message, data == null ? null : store.toJson(data), dedupeKey)
        .ifPresent(n -> sender.submit(() -> {
          try {
            deliver(n, settings());
          } catch (Exception e) {
            log.warn("falha entregando alerta #{}: {}", n.id(), e.getMessage());
          }
        }));
  }

  // ------------------------------------------------------------------ entrega
  public List<Delivery> deliver(NotificationRow n, Settings s) {
    var out = new ArrayList<Delivery>();
    if (on(s.email().enabled())) out.add(attempt("email", () -> sendEmail(s.email(), n)));
    if (on(s.teams().enabled())) out.add(attempt("teams", () -> sendTeams(s.teams(), n)));
    if (on(s.webhook().enabled())) out.add(attempt("webhook", () -> sendWebhook(s.webhook(), n)));
    if (n.id() > 0) store.setDeliveries(n.id(), store.toJson(out));
    for (var d : out) if (!d.ok()) log.warn("alerta #{} não entregue via {}: {}", n.id(), d.channel(), d.error());
    return out;
  }

  /** Envia uma mensagem de teste a um canal (botão "Enviar teste" na tela). */
  public Delivery test(String channel) {
    var s = settings();
    var n = new NotificationRow(0, Instant.now().toString(), "info", "test", null, "Teste de alerta do HAPM",
        "Se você recebeu esta mensagem, o canal " + channel + " está configurado corretamente.", null, null, null, null);
    return switch (channel) {
      case "email" -> attempt("email", () -> sendEmail(s.email(), n));
      case "teams" -> attempt("teams", () -> sendTeams(s.teams(), n));
      case "webhook" -> attempt("webhook", () -> sendWebhook(s.webhook(), n));
      default -> throw new IllegalArgumentException("canal desconhecido: " + channel);
    };
  }

  private interface Send {
    void run() throws Exception;
  }

  private static Delivery attempt(String channel, Send send) {
    try {
      send.run();
      return new Delivery(channel, true, null, Instant.now().toString());
    } catch (Exception e) {
      return new Delivery(channel, false, String.valueOf(e.getMessage()), Instant.now().toString());
    }
  }

  private static String levelLabel(String level) {
    return switch (level) {
      case "critical" -> "CRÍTICO";
      case "warning" -> "ATENÇÃO";
      default -> "INFO";
    };
  }

  void sendEmail(Email e, NotificationRow n) {
    if (e.host() == null || e.host().isBlank()) throw new IllegalStateException("servidor SMTP não configurado");
    var sender = new JavaMailSenderImpl();
    sender.setHost(e.host());
    sender.setPort(e.port() == null ? 587 : e.port());
    if (e.username() != null && !e.username().isBlank()) {
      sender.setUsername(e.username());
      sender.setPassword(e.password());
    }
    var props = new Properties();
    props.put("mail.smtp.auth", String.valueOf(e.username() != null && !e.username().isBlank()));
    props.put("mail.smtp.starttls.enable", String.valueOf(on(e.starttls())));
    props.put("mail.smtp.ssl.enable", String.valueOf(on(e.ssl())));
    props.put("mail.smtp.connectiontimeout", "10000");
    props.put("mail.smtp.timeout", "10000");
    props.put("mail.smtp.writetimeout", "10000");
    sender.setJavaMailProperties(props);
    var m = new SimpleMailMessage();
    m.setFrom(e.from());
    m.setTo(e.to().toArray(String[]::new));
    m.setSubject("[HAPM " + levelLabel(n.level()) + "] " + n.title());
    m.setText(n.title() + "\n\n" + n.message() + "\n\nData: " + n.ts() + "\n-- \nHAPM - HAProxy HA Manager");
    sender.send(m);
  }

  /** Microsoft Teams via webhook do Workflows (Power Automate): mensagem com Adaptive Card. */
  void sendTeams(Teams t, NotificationRow n) throws Exception {
    if (t.webhookUrl() == null || t.webhookUrl().isBlank()) throw new IllegalStateException("URL do webhook do Teams não configurada");
    var color = switch (n.level()) {
      case "critical" -> "Attention";
      case "warning" -> "Warning";
      default -> "Default";
    };
    var card = Map.of(
        "$schema", "http://adaptivecards.io/schemas/adaptive-card.json", "type", "AdaptiveCard", "version", "1.4",
        "body", List.of(
            Map.of("type", "TextBlock", "text", "HAPM · " + levelLabel(n.level()), "weight", "Bolder", "color", color, "size", "Small"),
            Map.of("type", "TextBlock", "text", n.title(), "weight", "Bolder", "size", "Medium", "wrap", true),
            Map.of("type", "TextBlock", "text", n.message(), "wrap", true),
            Map.of("type", "TextBlock", "text", n.ts(), "isSubtle", true, "size", "Small")));
    var payload = Map.of("type", "message", "attachments",
        List.of(Map.of("contentType", "application/vnd.microsoft.card.adaptive", "contentUrl", "", "content", card)));
    post(t.webhookUrl(), store.toJson(payload), Map.of());
  }

  /** Webhook genérico: JSON com assinatura HMAC-SHA256 do corpo em X-HAPM-Signature (se houver segredo). */
  void sendWebhook(Webhook w, NotificationRow n) throws Exception {
    if (w.url() == null || w.url().isBlank()) throw new IllegalStateException("URL do webhook não configurada");
    var body = new LinkedHashMap<String, Object>();
    body.put("id", n.id());
    body.put("ts", n.ts());
    body.put("level", n.level());
    body.put("kind", n.kind());
    body.put("title", n.title());
    body.put("message", n.message());
    body.put("data", n.data() == null ? null : store.fromJson(n.data(), Map.class));
    var json = store.toJson(body);
    var headers = new LinkedHashMap<String, String>();
    headers.put("X-HAPM-Event", n.kind());
    if (w.secret() != null && !w.secret().isBlank()) {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(w.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      headers.put("X-HAPM-Signature", "sha256=" + HexFormat.of().formatHex(mac.doFinal(json.getBytes(StandardCharsets.UTF_8))));
    }
    post(w.url(), json, headers);
  }

  private void post(String url, String json, Map<String, String> headers) throws Exception {
    var b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    headers.forEach(b::header);
    var res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + res.statusCode() + ": " + res.body().substring(0, Math.min(200, res.body().length())));
  }
}
