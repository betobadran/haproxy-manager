package dev.hapm.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Ajustes por nó feitos no manager (prioridade VRRP, interface, etc.). */
public record NodeSettings(Integer priority, @JsonProperty("interface") String iface, String unicastAddress, Boolean disabled, String awsEniId) {

  public static final NodeSettings EMPTY = new NodeSettings(null, null, null, false, null);

  public NodeSettings(Integer priority, String iface, String unicastAddress, Boolean disabled) {
    this(priority, iface, unicastAddress, disabled, null);
  }

  public boolean isDisabled() {
    return Boolean.TRUE.equals(disabled);
  }
}
