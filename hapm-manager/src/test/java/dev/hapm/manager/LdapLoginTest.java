package dev.hapm.manager;

import static org.assertj.core.api.Assertions.assertThat;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldif.LDIFReader;
import java.io.StringReader;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Login via LDAP real (UnboundID em memória): bind, busca de grupos e mapeamento para papéis. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "hapm.admin-password=admin-pass-123",
    "hapm.auth.ldap.enabled=true",
    "hapm.auth.ldap.base-dn=dc=exemplo,dc=com",
    "hapm.auth.ldap.user-dn-pattern=",
    "hapm.auth.ldap.user-search-base=ou=people",
    "hapm.auth.ldap.user-search-filter=(uid={0})",
    "hapm.auth.ldap.group-search-base=ou=groups",
    "hapm.auth.ldap.group-search-filter=(member={0})",
    "hapm.auth.ldap.display-name-attribute=cn",
    "hapm.auth.role-mapping.OPERATOR=sre,hapm-operators",
    "hapm.auth.role-mapping.APPROVER=change-managers"})
class LdapLoginTest {
  @org.springframework.test.context.DynamicPropertySource
  static void db(org.springframework.test.context.DynamicPropertyRegistry r) {
    TestDb.register(r, "ldap");
  }

  static final InMemoryDirectoryServer LDAP;

  static {
    try {
      var cfg = new InMemoryDirectoryServerConfig("dc=exemplo,dc=com");
      cfg.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", 0));
      cfg.setSchema(null);
      LDAP = new InMemoryDirectoryServer(cfg);
      LDAP.importFromLDIF(true, new LDIFReader(new java.io.BufferedReader(new StringReader("""
          dn: dc=exemplo,dc=com
          objectClass: domain
          dc: exemplo

          dn: ou=people,dc=exemplo,dc=com
          objectClass: organizationalUnit
          ou: people

          dn: ou=groups,dc=exemplo,dc=com
          objectClass: organizationalUnit
          ou: groups

          dn: uid=alice,ou=people,dc=exemplo,dc=com
          objectClass: inetOrgPerson
          uid: alice
          cn: Alice Souza
          sn: Souza
          userPassword: alice-123

          dn: uid=bruno,ou=people,dc=exemplo,dc=com
          objectClass: inetOrgPerson
          uid: bruno
          cn: Bruno Lima
          sn: Lima
          userPassword: bruno-123

          dn: uid=carol,ou=people,dc=exemplo,dc=com
          objectClass: inetOrgPerson
          uid: carol
          cn: Carol Dias
          sn: Dias
          userPassword: carol-123

          dn: cn=sre,ou=groups,dc=exemplo,dc=com
          objectClass: groupOfNames
          cn: sre
          member: uid=alice,ou=people,dc=exemplo,dc=com

          dn: cn=Change-Managers,ou=groups,dc=exemplo,dc=com
          objectClass: groupOfNames
          cn: Change-Managers
          member: uid=bruno,ou=people,dc=exemplo,dc=com
          """))));
      LDAP.startListening();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void ldapUrl(DynamicPropertyRegistry r) {
    r.add("hapm.auth.ldap.url", () -> "ldap://127.0.0.1:" + LDAP.getListenPort());
  }

  @AfterAll
  static void stop() {
    LDAP.shutDown(true);
  }

  @LocalServerPort int port;

  @Test
  void ldapUsersGetRolesFromGroups() {
    var anon = new ApiClient(port);
    assertThat(anon.call("GET", "/api/auth/providers", null).body().get("ldap").asBoolean()).isTrue();

    var alice = anon.call("POST", "/api/auth/login", Map.of("username", "alice", "password", "alice-123"));
    assertThat(alice.status()).as(alice.text()).isEqualTo(200);
    assertThat(alice.body().get("roles").toString()).isEqualTo("[\"OPERATOR\"]");
    assertThat(alice.body().get("displayName").asString()).isEqualTo("Alice Souza");
    assertThat(alice.body().get("source").asString()).isEqualTo("ldap");

    // nome do grupo com maiúsculas diferentes do mapeamento
    var bruno = anon.call("POST", "/api/auth/login", Map.of("username", "bruno", "password", "bruno-123"));
    assertThat(bruno.body().get("roles").toString()).isEqualTo("[\"APPROVER\"]");

    assertThat(anon.call("POST", "/api/auth/login", Map.of("username", "alice", "password", "errada")).status()).isEqualTo(401);
    var carol = anon.call("POST", "/api/auth/login", Map.of("username", "carol", "password", "carol-123"));
    assertThat(carol.status()).isEqualTo(401);
    assertThat(carol.text()).contains("sem papel");

    // o admin local continua funcionando (break-glass) e enxerga os usuários LDAP
    var admin = anon.login("admin", "admin-pass-123");
    var users = admin.call("GET", "/api/users", null).body();
    assertThat(users.valueStream().filter(u -> u.get("username").asString().equals("alice")).findFirst().orElseThrow()
        .get("source").asString()).isEqualTo("ldap");
    // papéis de usuários LDAP não são editáveis no HAPM
    assertThat(admin.call("PUT", "/api/users/alice", Map.of("roles", java.util.List.of("ADMIN"))).status()).isEqualTo(400);
  }
}
