package dev.hapm.manager.security;

import dev.hapm.manager.HapmProperties;
import dev.hapm.manager.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Cria o usuário administrador inicial (senha de HAPM_ADMIN_PASSWORD ou aleatória exibida no log). */
@Component
public class Bootstrap implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);
  private final Store store;
  private final HapmProperties props;
  private final PasswordEncoder encoder;

  public Bootstrap(Store store, HapmProperties props, PasswordEncoder encoder) {
    this.store = store;
    this.props = props;
    this.encoder = encoder;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (store.countUsers() > 0) return;
    var pwd = props.adminPassword();
    boolean generated = pwd == null || pwd.isBlank();
    if (generated) pwd = Store.randomSecret(9);
    store.createUser(props.adminUser(), encoder.encode(pwd));
    if (generated) log.warn("usuário inicial criado: {} / {}  (troque a senha!)", props.adminUser(), pwd);
    else log.info("usuário inicial {} criado", props.adminUser());
  }
}
