package dev.hapm.manager.web;

import dev.hapm.manager.service.NotificationService;
import dev.hapm.manager.service.NotificationService.Settings;
import dev.hapm.manager.security.SessionUser;
import dev.hapm.manager.store.Store;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Alertas (tela) e configuração dos canais de notificação. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
  private final Store store;
  private final NotificationService notifications;

  public NotificationController(Store store, NotificationService notifications) {
    this.store = store;
    this.notifications = notifications;
  }

  private Map<String, Object> view(Store.NotificationRow n) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", n.id());
    m.put("ts", n.ts());
    m.put("level", n.level());
    m.put("kind", n.kind());
    m.put("clusterId", n.clusterId());
    m.put("title", n.title());
    m.put("message", n.message());
    m.put("data", n.data() == null ? null : store.fromJson(n.data(), Map.class));
    m.put("deliveries", n.deliveries() == null ? List.of() : store.fromJson(n.deliveries(), List.class));
    m.put("acknowledgedAt", n.acknowledgedAt());
    m.put("acknowledgedBy", n.acknowledgedBy());
    return m;
  }

  @GetMapping
  public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean unacked, @RequestParam(required = false) String cluster,
                                        @RequestParam(defaultValue = "200") int limit) {
    return store.listNotifications(unacked, cluster, Math.min(limit, 1000)).stream().map(this::view).toList();
  }

  @GetMapping("/count")
  public Map<String, Long> count() {
    return Map.of("unacked", store.countUnacked());
  }

  @PostMapping("/{id}/ack")
  public Map<String, Boolean> ack(@PathVariable long id, @AuthenticationPrincipal SessionUser user) {
    var ok = store.acknowledge(id, user.username());
    if (ok) store.audit(user.username(), null, "notification.ack", "#" + id, null);
    return Map.of("ok", ok);
  }

  @GetMapping("/settings")
  public Settings settings() {
    return notifications.masked();
  }

  @PutMapping("/settings")
  public Settings save(@RequestBody Settings in, @AuthenticationPrincipal SessionUser user) {
    try {
      notifications.save(in);
    } catch (IllegalArgumentException e) {
      throw Http.status(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    store.audit(user.username(), null, "notification.settings.update", null, null);
    return notifications.masked();
  }

  @PostMapping("/test/{channel}")
  public NotificationService.Delivery test(@PathVariable String channel) {
    try {
      return notifications.test(channel);
    } catch (IllegalArgumentException e) {
      throw Http.status(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /** Verifica os certificados agora (sem esperar a rotina de hora em hora). */
  @PostMapping("/check")
  public Map<String, Object> check() {
    return Map.of("created", notifications.checkCertificates().size());
  }
}
