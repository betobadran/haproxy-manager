package dev.hapm.core.model;

public record NodeInfo(String name, String address, NodeSettings settings) {

  public NodeSettings settingsOrEmpty() {
    return settings == null ? NodeSettings.EMPTY : settings;
  }

  /** IP usado no VRRP unicast. */
  public String vrrpAddress() {
    var s = settingsOrEmpty();
    return s.unicastAddress() != null && !s.unicastAddress().isBlank() ? s.unicastAddress() : address;
  }
}
