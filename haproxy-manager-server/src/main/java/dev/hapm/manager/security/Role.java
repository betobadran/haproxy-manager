package dev.hapm.manager.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Papéis do HAProxy Manager.
 * <ul>
 *   <li>VIEWER: só leitura</li>
 *   <li>OPERATOR: edita listeners/backends/certificados e publica (ou solicita publicação quando o cluster exige aprovação)</li>
 *   <li>APPROVER: aprova ou rejeita pedidos de publicação</li>
 *   <li>ADMIN: tudo, inclusive clusters, nós, tokens de join, usuários e publicação emergencial</li>
 * </ul>
 * OPERATOR e APPROVER são independentes (separação de funções); quem precisa dos dois recebe os dois.
 */
public enum Role {
  VIEWER, OPERATOR, APPROVER, ADMIN;

  public static Optional<Role> parse(String s) {
    try {
      return Optional.of(Role.valueOf(s.strip().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException | NullPointerException e) {
      return Optional.empty();
    }
  }

  /** Aplica a hierarquia: ADMIN inclui todos; OPERATOR e APPROVER incluem VIEWER. */
  public static Set<Role> expand(Collection<Role> roles) {
    var out = EnumSet.noneOf(Role.class);
    out.addAll(roles);
    if (out.contains(ADMIN)) out.addAll(EnumSet.allOf(Role.class));
    if (out.contains(OPERATOR) || out.contains(APPROVER)) out.add(VIEWER);
    return out;
  }
}
