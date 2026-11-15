package dev.hapm.core.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.List;

/** Listener (frontend do HAProxy). */
public record Frontend(
    String name,
    Boolean enabled,
    String description,
    String mode,
    List<Bind> binds,
    String defaultBackend,
    Boolean redirectToHttps,
    Boolean hsts,
    Boolean http2,
    Boolean forwardFor,
    Boolean acme,
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> allowCidrs,
    RateLimit rateLimit,
    Integer maxconn,
    String timeoutClient,
    List<Rule> rules,
    String extra) {

  public boolean isHttp() {
    return "http".equals(mode);
  }

  public record Bind(String address, Integer port, Boolean ssl) {}

  public record RateLimit(Boolean enabled, Integer requests, String period) {}

  /** Regra: todas as condições (AND) disparam a ação. */
  public record Rule(
      String description,
      List<Condition> conditions,
      String action,
      String backend,
      String redirectType,
      String url,
      String code,
      Integer status,
      String header,
      String value,
      String direction) {}

  public record Condition(
      String type,
      String name,
      @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> value,
      Boolean negate) {}
}
