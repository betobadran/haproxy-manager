package dev.hapm.manager.security;

import dev.hapm.manager.HapmProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator;
import org.springframework.stereotype.Component;

/** Autenticação por bind no LDAP (OpenLDAP, 389-DS, FreeIPA...) ou no Active Directory. */
@Component
public class LdapLogin {
  private static final Logger log = LoggerFactory.getLogger(LdapLogin.class);

  public record Result(String username, String displayName, List<String> groups) {}

  private final HapmProperties.Ldap cfg;
  private BindAuthenticator bind;
  private DefaultLdapAuthoritiesPopulator groups;
  private ActiveDirectoryLdapAuthenticationProvider ad;

  public LdapLogin(HapmProperties props) {
    this.cfg = props.authOrDefault().ldapOrDefault();
    if (!cfg.enabled()) return;
    if (notBlank(cfg.activeDirectoryDomain())) {
      ad = new ActiveDirectoryLdapAuthenticationProvider(cfg.activeDirectoryDomain(), cfg.url(), notBlank(cfg.baseDn()) ? cfg.baseDn() : null);
      ad.setConvertSubErrorCodesToExceptions(true);
      return;
    }
    var cs = new DefaultSpringSecurityContextSource(List.of(cfg.url()), cfg.baseDn());
    if (notBlank(cfg.managerDn())) {
      cs.setUserDn(cfg.managerDn());
      cs.setPassword(cfg.managerPassword());
    }
    cs.afterPropertiesSet();
    bind = new BindAuthenticator(cs);
    if (notBlank(cfg.userDnPattern())) bind.setUserDnPatterns(new String[] {cfg.userDnPattern()});
    else bind.setUserSearch(new FilterBasedLdapUserSearch(nz(cfg.userSearchBase()), cfg.userSearchFilter(), cs));
    groups = new DefaultLdapAuthoritiesPopulator(cs, nz(cfg.groupSearchBase()));
    groups.setGroupSearchFilter(notBlank(cfg.groupSearchFilter()) ? cfg.groupSearchFilter() : "(member={0})");
    groups.setGroupRoleAttribute(notBlank(cfg.groupRoleAttribute()) ? cfg.groupRoleAttribute() : "cn");
    groups.setRolePrefix("");
    groups.setConvertToUpperCase(false);
    groups.setSearchSubtree(true);
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }

  public boolean enabled() {
    return cfg.enabled();
  }

  /** Vazio = credenciais inválidas (ou LDAP indisponível, registrado no log). */
  public Optional<Result> authenticate(String username, String password) {
    if (!cfg.enabled() || username == null || username.isBlank() || password == null || password.isEmpty()) return Optional.empty();
    var token = new UsernamePasswordAuthenticationToken(username, password);
    try {
      if (ad != null) {
        var auth = ad.authenticate(token);
        var names = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        return Optional.of(new Result(username, username, names));
      }
      var ctx = bind.authenticate(token);
      var names = new ArrayList<String>();
      for (var a : groups.getGrantedAuthorities(ctx, username)) names.add(a.getAuthority());
      var dnAttr = notBlank(cfg.displayNameAttribute()) ? cfg.displayNameAttribute() : "cn";
      var display = ctx.getStringAttribute(dnAttr);
      return Optional.of(new Result(username, display == null ? username : display, names));
    } catch (BadCredentialsException e) {
      return Optional.empty();
    } catch (AuthenticationException | org.springframework.ldap.NamingException e) {
      log.warn("falha no LDAP para {}: {}", username, e.getMessage());
      return Optional.empty();
    }
  }
}
