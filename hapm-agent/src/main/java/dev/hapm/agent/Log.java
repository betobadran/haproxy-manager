package dev.hapm.agent;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Log simples em stdout/stderr (o journald adiciona contexto no systemd). */
final class Log {
  private Log() {}

  // UTF-8 explícito: em locale POSIX (comum em systemd/containers) o JDK usaria ASCII
  private static final PrintStream OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
  private static final PrintStream ERR = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

  static void info(String msg) {
    OUT.println(Instant.now() + " INFO " + msg);
  }

  static void warn(String msg) {
    ERR.println(Instant.now() + " WARN " + msg);
  }

  static void error(String msg) {
    ERR.println(Instant.now() + " ERROR " + msg);
  }
}
