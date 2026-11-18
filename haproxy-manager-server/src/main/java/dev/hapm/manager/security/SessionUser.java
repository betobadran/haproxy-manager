package dev.hapm.manager.security;

import java.security.Principal;
import java.util.Set;

/** Usuário autenticado (vem do token de sessão). */
public record SessionUser(String username, String displayName, String source, Set<Role> roles) implements Principal {
  @Override
  public String getName() {
    return username;
  }

  public boolean has(Role r) {
    return Role.expand(roles).contains(r);
  }
}
