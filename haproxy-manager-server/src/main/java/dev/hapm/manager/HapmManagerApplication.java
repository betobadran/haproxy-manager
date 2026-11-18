package dev.hapm.manager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class HapmManagerApplication {
  public static void main(String[] args) throws java.io.IOException {
    // SQLite cria o arquivo, mas não o diretório
    var dbUrl = System.getenv("HAPM_DB_URL");
    if (dbUrl == null || dbUrl.isBlank()) java.nio.file.Files.createDirectories(java.nio.file.Path.of(System.getenv().getOrDefault("HAPM_DATA_DIR", "./data")));
    SpringApplication.run(HapmManagerApplication.class, args);
  }
}
