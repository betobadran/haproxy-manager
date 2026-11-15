package dev.hapm.manager;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hapm")
public record HapmProperties(
    String adminUser,
    String adminPassword,
    String secret,
    String acmeDirectory,
    String haproxyBin,
    int nodeOfflineAfterSec,
    int deploymentTimeoutSec,
    int certRenewBeforeDays,
    Auth auth) {

  public Auth authOrDefault() {
    return auth == null ? new Auth(null, null, null, null, null, null) : auth;
  }

  /**
   * Autenticação. roleMapping: papel -> grupos do LDAP/IdP (ex.: ADMIN: [hapm-admins]).
   * defaultRoles: papéis dados a qualquer usuário externo autenticado (ex.: [VIEWER]); vazio = sem papel, login negado.
   */
  public record Auth(Local local, Ldap ldap, Oidc oidc, Map<String, List<String>> roleMapping, List<String> defaultRoles, Integer sessionHours) {
    public Local localOrDefault() {
      return local == null ? new Local(true) : local;
    }

    public Ldap ldapOrDefault() {
      return ldap == null ? new Ldap(false, null, null, null, null, null, null, null, null, null, null, null, null) : ldap;
    }

    public Oidc oidcOrDefault() {
      return oidc == null ? new Oidc(false, null, null, null, null) : oidc;
    }

    public int sessionHoursOrDefault() {
      return sessionHours == null ? 12 : sessionHours;
    }
  }

  public record Local(boolean enabled) {}

  /**
   * LDAP genérico (bind direto por userDnPattern ou busca por userSearchFilter) ou Active Directory
   * (preencha activeDirectoryDomain: o login usa user@dominio e os grupos vêm de memberOf).
   */
  public record Ldap(
      boolean enabled,
      String url,
      String baseDn,
      String userDnPattern,
      String userSearchBase,
      String userSearchFilter,
      String managerDn,
      String managerPassword,
      String groupSearchBase,
      String groupSearchFilter,
      String groupRoleAttribute,
      String displayNameAttribute,
      String activeDirectoryDomain) {}

  /**
   * OIDC: a conexão com o IdP usa as propriedades padrão do Spring
   * (spring.security.oauth2.client.registration.{registrationId}.* e provider.{registrationId}.issuer-uri).
   * groupsClaim aceita caminho com pontos (ex.: realm_access.roles no Keycloak).
   */
  public record Oidc(boolean enabled, String registrationId, String label, String usernameClaim, String groupsClaim) {}
}
