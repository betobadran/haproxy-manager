package dev.hapm.manager.security;

import dev.hapm.manager.HapmProperties;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Converte grupos do LDAP/AD/IdP em papéis do HAPM conforme hapm.auth.role-mapping. */
@Component
public class RoleMapper {
  private static final Pattern FIRST_CN = Pattern.compile("^\\s*cn=([^,]+)", Pattern.CASE_INSENSITIVE);
  private final HapmProperties.Auth auth;

  public RoleMapper(HapmProperties props) {
    this.auth = props.authOrDefault();
  }

  /** Aceita "hapm-admins", "CN=hapm-admins,OU=Grupos,DC=corp" ou "/hapm-admins" (Keycloak groups), sem diferenciar maiúsculas. */
  static Set<String> normalize(Collection<String> groups) {
    var out = new HashSet<String>();
    for (var g : groups) {
      if (g == null || g.isBlank()) continue;
      var v = g.strip();
      out.add(v.toLowerCase(Locale.ROOT));
      var m = FIRST_CN.matcher(v);
      if (m.find()) out.add(m.group(1).strip().toLowerCase(Locale.ROOT));
      if (v.startsWith("/")) out.add(v.substring(v.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT));
    }
    return out;
  }

  public Set<Role> map(Collection<String> groups) {
    var have = normalize(groups);
    var roles = EnumSet.noneOf(Role.class);
    if (auth.roleMapping() != null) {
      for (var e : auth.roleMapping().entrySet()) {
        var role = Role.parse(e.getKey());
        if (role.isEmpty() || e.getValue() == null) continue;
        for (var g : e.getValue()) {
          if (g != null && !g.isBlank() && have.contains(g.strip().toLowerCase(Locale.ROOT))) roles.add(role.get());
        }
      }
    }
    if (auth.defaultRoles() != null) for (var r : auth.defaultRoles()) Role.parse(r).ifPresent(roles::add);
    return roles;
  }
}
