package dev.hapm.manager;

import java.nio.file.Files;
import java.sql.DriverManager;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Banco isolado por classe de teste. Padrão: SQLite em arquivo temporário.
 * Com -Dhapm.test.pg=jdbc:postgresql://host:5432/postgres (+ -Dhapm.test.pg.user / .password) a mesma suíte
 * roda contra PostgreSQL, criando um banco novo por classe.
 */
public final class TestDb {
  private TestDb() {}

  public static void register(DynamicPropertyRegistry r, String name) {
    var pg = System.getProperty("hapm.test.pg");
    if (pg == null || pg.isBlank()) {
      try {
        var dir = Files.createTempDirectory("hapm-test-" + name);
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + dir.resolve("hapm.db") + "?foreign_keys=true&journal_mode=WAL&busy_timeout=10000&transaction_mode=IMMEDIATE");
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      return;
    }
    var user = System.getProperty("hapm.test.pg.user", "postgres");
    var pass = System.getProperty("hapm.test.pg.password", "");
    var db = "hapm_t_" + name.toLowerCase();
    try (var c = DriverManager.getConnection(pg, user, pass); var st = c.createStatement()) {
      st.execute("DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
      st.execute("CREATE DATABASE " + db);
    } catch (Exception e) {
      throw new IllegalStateException("não foi possível criar o banco de teste " + db, e);
    }
    r.add("spring.datasource.url", () -> pg.replaceAll("/[^/?]*(\\?.*)?$", "/" + db));
    r.add("spring.datasource.username", () -> user);
    r.add("spring.datasource.password", () -> pass);
    // cada classe de teste mantém o próprio contexto em cache: pool pequeno para não estourar max_connections
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
    r.add("spring.datasource.hikari.minimum-idle", () -> "0");
  }
}
