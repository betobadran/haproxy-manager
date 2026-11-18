package dev.hapm.core.model;

import java.util.List;

public record Backend(
    String name,
    String description,
    String mode,
    String balance,
    List<Server> servers,
    HealthCheck healthCheck,
    Sticky sticky,
    ServerSsl serverSsl,
    Boolean compression,
    String timeoutServer,
    String timeoutConnect,
    String extra) {

  public record Server(String name, String address, Integer port, Integer weight, Boolean backup, Integer maxconn, Boolean disabled) {}

  public record HealthCheck(String type, String path, String method, String host, String expectStatus, String interval, Integer fall, Integer rise) {}

  public record Sticky(Boolean enabled, String cookie) {}

  public record ServerSsl(Boolean enabled, String verify, Boolean sni) {}
}
