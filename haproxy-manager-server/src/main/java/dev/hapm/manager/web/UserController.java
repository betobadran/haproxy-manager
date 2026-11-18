package dev.hapm.manager.web;

import dev.hapm.core.validate.Checks;
import dev.hapm.manager.security.Role;
import dev.hapm.manager.security.SessionUser;
import dev.hapm.manager.store.Store;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Usuários (ADMIN). Contas locais são criadas aqui; usuários LDAP/OIDC aparecem após o 1º login
 * e seus papéis vêm sempre dos grupos (não são editáveis aqui).
 */
@RestController
@RequestMapping("/api/users")
public class UserController {
  public record UserRequest(String username, String displayName, List<String> roles, String password) {}

  private final Store store;
  private final PasswordEncoder encoder;

  public UserController(Store store, PasswordEncoder encoder) {
    this.store = store;
    this.encoder = encoder;
  }

  private static TreeSet<String> roles(List<String> in) {
    var out = new TreeSet<String>();
    if (in != null) for (var r : in) out.add(Role.parse(r).orElseThrow(() -> Http.status(HttpStatus.BAD_REQUEST, "papel inválido: " + r)).name());
    if (out.isEmpty()) throw Http.status(HttpStatus.BAD_REQUEST, "informe ao menos um papel");
    return out;
  }

  private static void checkPassword(String p) {
    if (p == null || p.length() < 10) throw Http.status(HttpStatus.BAD_REQUEST, "senha deve ter ao menos 10 caracteres");
  }

  private static Map<String, Object> view(Store.UserRow u) {
    var m = new LinkedHashMap<String, Object>();
    m.put("username", u.username());
    m.put("displayName", u.displayName());
    m.put("source", u.source());
    m.put("roles", u.roles());
    m.put("lastLogin", u.lastLogin());
    m.put("createdAt", u.createdAt());
    return m;
  }

  @GetMapping
  public List<Map<String, Object>> list() {
    return store.listUsers().stream().map(UserController::view).toList();
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> create(@RequestBody UserRequest req, @AuthenticationPrincipal SessionUser admin) {
    if (req.username() == null || !Checks.NAME.matcher(req.username()).matches()) throw Http.status(HttpStatus.BAD_REQUEST, "nome de usuário inválido");
    if (store.getUser(req.username()).isPresent()) throw Http.status(HttpStatus.CONFLICT, "usuário já existe");
    checkPassword(req.password());
    var r = roles(req.roles());
    store.createLocalUser(req.username(), encoder.encode(req.password()), req.displayName(), r);
    store.audit(admin.username(), null, "user.create", req.username(), Map.of("roles", r.toString()));
    return ResponseEntity.status(HttpStatus.CREATED).body(view(store.getUser(req.username()).orElseThrow()));
  }

  @PutMapping("/{username}")
  public Map<String, Object> update(@PathVariable String username, @RequestBody UserRequest req, @AuthenticationPrincipal SessionUser admin) {
    var u = store.getUser(username).orElseThrow(() -> Http.notFound("usuário não encontrado"));
    if (!"local".equals(u.source())) throw Http.status(HttpStatus.BAD_REQUEST, "papéis de usuários " + u.source() + " vêm dos grupos do diretório");
    var r = roles(req.roles());
    if (u.roles().contains("ADMIN") && !r.contains("ADMIN") && store.countLocalAdmins() <= 1)
      throw Http.status(HttpStatus.CONFLICT, "não é possível remover o último ADMIN local");
    store.updateLocalUser(username, req.displayName(), r);
    if (req.password() != null && !req.password().isEmpty()) {
      checkPassword(req.password());
      store.setPassword(username, encoder.encode(req.password()));
    }
    store.audit(admin.username(), null, "user.update", username, Map.of("roles", r.toString(), "passwordReset", req.password() != null && !req.password().isEmpty()));
    return view(store.getUser(username).orElseThrow());
  }

  @DeleteMapping("/{username}")
  public Map<String, Boolean> delete(@PathVariable String username, @AuthenticationPrincipal SessionUser admin) {
    var u = store.getUser(username).orElseThrow(() -> Http.notFound("usuário não encontrado"));
    if (username.equals(admin.username())) throw Http.status(HttpStatus.CONFLICT, "não é possível excluir a si mesmo");
    if ("local".equals(u.source()) && u.roles().contains("ADMIN") && store.countLocalAdmins() <= 1)
      throw Http.status(HttpStatus.CONFLICT, "não é possível excluir o último ADMIN local");
    store.deleteUser(username);
    store.audit(admin.username(), null, "user.delete", username, null);
    return Map.of("ok", true);
  }
}
