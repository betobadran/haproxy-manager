package dev.hapm.manager.security;

import dev.hapm.manager.HapmProperties;
import dev.hapm.manager.store.Store;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Login por usuário/senha (local ou LDAP) e emissão de sessão para logins OIDC. */
@Service
public class AuthService {

  /** Resultado de uma tentativa de login. */
  public sealed interface Outcome {}

  public record Ok(SessionUser user) implements Outcome {}

  public record Denied(String reason) implements Outcome {}

  private final Store store;
  private final PasswordEncoder encoder;
  private final LdapLogin ldap;
  private final RoleMapper mapper;
  private final HapmProperties.Auth cfg;

  public AuthService(Store store, PasswordEncoder encoder, LdapLogin ldap, RoleMapper mapper, HapmProperties props) {
    this.store = store;
    this.encoder = encoder;
    this.ldap = ldap;
    this.mapper = mapper;
    this.cfg = props.authOrDefault();
  }

  static Set<Role> parseRoles(Set<String> names) {
    var out = EnumSet.noneOf(Role.class);
    for (var n : names) Role.parse(n).ifPresent(out::add);
    return out;
  }

  /**
   * Usuário local tem precedência: se existir conta local com esse nome, só a senha local vale
   * (evita que um homônimo no LDAP herde os papéis da conta local, e vice-versa).
   */
  public Outcome loginPassword(String username, String password) {
    if (username == null || password == null) return new Denied("usuário ou senha inválidos");
    var local = store.getUser(username).filter(u -> "local".equals(u.source()));
    if (local.isPresent()) {
      var u = local.get();
      if (!cfg.localOrDefault().enabled()) return new Denied("login local desabilitado; use o login corporativo");
      if (u.passwordHash() == null || !encoder.matches(password, u.passwordHash())) return new Denied("usuário ou senha inválidos");
      var roles = parseRoles(u.roles());
      if (roles.isEmpty()) return new Denied("usuário sem papel atribuído");
      store.touchLogin(username);
      return new Ok(new SessionUser(username, u.displayName() == null ? username : u.displayName(), "local", roles));
    }
    if (ldap.enabled()) {
      var r = ldap.authenticate(username, password);
      if (r.isEmpty()) return new Denied("usuário ou senha inválidos");
      return external(r.get().username(), r.get().displayName(), "ldap", r.get().groups());
    }
    return new Denied("usuário ou senha inválidos");
  }

  /** Usuário autenticado externamente (LDAP/OIDC): papéis vêm dos grupos. */
  public Outcome external(String username, String displayName, String source, java.util.Collection<String> groups) {
    var roles = mapper.map(groups);
    if (roles.isEmpty()) return new Denied("autenticado, mas sem papel no HAProxy Manager (peça inclusão em um dos grupos mapeados)");
    var names = new java.util.TreeSet<String>();
    roles.forEach(r -> names.add(r.name()));
    store.upsertExternalUser(username, source, displayName, names);
    return new Ok(new SessionUser(username, displayName == null ? username : displayName, source, roles));
  }

  // ------------------------------------------------------------------ código de uso único (callback OIDC -> UI)
  /**
   * Após o login OIDC o navegador volta para a UI com um código de uso único (60 s) em vez do token,
   * que é trocado por POST. Fica no banco para funcionar com vários managers.
   */
  public String issueOneTimeCode(String sessionToken) {
    var code = Store.randomSecret(24);
    store.setKv("login.code." + code, (Instant.now().getEpochSecond() + 60) + "|" + sessionToken);
    return code;
  }

  public Optional<String> redeemOneTimeCode(String code) {
    if (code == null || !code.matches("^[A-Za-z0-9_-]{20,64}$")) return Optional.empty();
    var key = "login.code." + code;
    var v = store.getKv(key);
    store.deleteKv(key);
    if (v.isEmpty()) return Optional.empty();
    var bar = v.get().indexOf('|');
    if (Long.parseLong(v.get().substring(0, bar)) < Instant.now().getEpochSecond()) return Optional.empty();
    return Optional.of(v.get().substring(bar + 1));
  }
}
