package dev.hapm.agent;

import java.time.Instant;

/** Log simples em stdout/stderr (o journald adiciona contexto no systemd). */
final class Log {
  private Log() {}

  static void info(String msg) {
    System.out.println(Instant.now() + " INFO " + msg);
  }

  static void warn(String msg) {
    System.err.println(Instant.now() + " WARN " + msg);
  }

  static void error(String msg) {
    System.err.println(Instant.now() + " ERROR " + msg);
  }
}
