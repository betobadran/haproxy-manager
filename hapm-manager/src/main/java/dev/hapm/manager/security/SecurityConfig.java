package dev.hapm.manager.security;

import dev.hapm.manager.HapmProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autorização por papel (a hierarquia é expandida ao montar as authorities):
 * <pre>
 *   VIEWER    GET em toda a /api (o token de join só aparece para ADMIN)
 *   OPERATOR  edita listeners/backends/certificados, prioridade de nós, publica/solicita publicação, rollback
 *   APPROVER  aprova/rejeita pedidos de publicação
 *   ADMIN     clusters, tokens, remoção de nós, usuários, publicação emergencial
 * </pre>
 * Regras finas (quatro olhos, cancelar só o próprio pedido, emergência) ficam no PublishService.
 */
@Configuration
public class SecurityConfig {
  private static final String A = "ROLE_ADMIN";
  private static final String O = "ROLE_OPERATOR";
  private static final String P = "ROLE_APPROVER";
  private static final String V = "ROLE_VIEWER";

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder(); // bcrypt
  }

  /** Usuários ficam na tabela própria (AuthService); desativa o usuário padrão do Spring. */
  @Bean
  UserDetailsService noDefaultUsers() {
    return username -> {
      throw new UsernameNotFoundException(username);
    };
  }

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, TokenService tokens, HapmProperties props, ObjectProvider<ClientRegistrationRepository> oidcClients,
                                  OidcLoginHandler oidcHandler) throws Exception {
    var oidcEnabled = props.authOrDefault().oidcOrDefault().enabled() && oidcClients.getIfAvailable() != null;
    http.csrf(c -> c.disable()) // API usa Bearer token (sem cookie de sessão), portanto sem CSRF
        // sessão HTTP só é criada durante o fluxo OAuth2 (state/nonce) e descartada no callback
        .sessionManagement(s -> s.sessionCreationPolicy(oidcEnabled ? SessionCreationPolicy.IF_REQUIRED : SessionCreationPolicy.STATELESS))
        .httpBasic(b -> b.disable())
        .formLogin(f -> f.disable())
        .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), r -> true))
        .authorizeHttpRequests(a -> a
            .requestMatchers("/api/auth/**").permitAll()
            .requestMatchers("/api/me", "/api/me/**").authenticated()
            .requestMatchers("/api/users", "/api/users/**").hasAuthority(A)
            .requestMatchers(HttpMethod.POST, "/api/notifications/*/ack").hasAuthority(V)
            .requestMatchers("/api/notifications/settings", "/api/notifications/test/*", "/api/notifications/check").hasAuthority(A)
            // clusters, tokens e nós (estrutura): ADMIN
            .requestMatchers(HttpMethod.POST, "/api/clusters").hasAuthority(A)
            .requestMatchers(HttpMethod.GET, "/api/clusters/*/export").hasAuthority(A)
            .requestMatchers(HttpMethod.POST, "/api/clusters/import", "/api/clusters/*/import", "/api/clusters/*/import-haproxy").hasAuthority(A)
            .requestMatchers(HttpMethod.PUT, "/api/clusters/*").hasAuthority(A)
            .requestMatchers(HttpMethod.DELETE, "/api/clusters/*").hasAuthority(A)
            .requestMatchers(HttpMethod.POST, "/api/clusters/*/rotate-token", "/api/clusters/*/nodes").hasAuthority(A)
            .requestMatchers(HttpMethod.DELETE, "/api/clusters/*/nodes/*").hasAuthority(A)
            // aprovações
            .requestMatchers(HttpMethod.POST, "/api/clusters/*/requests/*/approve", "/api/clusters/*/requests/*/reject").hasAuthority(P)
            .requestMatchers(HttpMethod.POST, "/api/clusters/*/requests/*/cancel").hasAuthority(O)
            // conteúdo e publicação: OPERATOR
            .requestMatchers(HttpMethod.PUT, "/api/clusters/*/nodes/*").hasAuthority(O)
            .requestMatchers(HttpMethod.POST, "/api/clusters/*/**").hasAuthority(O)
            .requestMatchers(HttpMethod.PUT, "/api/clusters/*/**").hasAuthority(O)
            .requestMatchers(HttpMethod.DELETE, "/api/clusters/*/**").hasAuthority(O)
            .requestMatchers(HttpMethod.GET, "/api/**").hasAuthority(V)
            .requestMatchers("/api/**").denyAll()
            .anyRequest().permitAll())
        .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:")))
        .addFilterBefore(new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
            var h = req.getHeader("Authorization");
            if (h != null && h.startsWith("Bearer ") && req.getRequestURI().startsWith("/api/")) {
              tokens.verify(h.substring(7)).ifPresent(user -> SecurityContextHolder.getContext().setAuthentication(
                  new UsernamePasswordAuthenticationToken(user, null,
                      Role.expand(user.roles()).stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r.name())).toList())));
            }
            chain.doFilter(req, res);
          }
        }, UsernamePasswordAuthenticationFilter.class);
    if (oidcEnabled) http.oauth2Login(o -> o.successHandler(oidcHandler).failureHandler(oidcHandler));
    return http.build();
  }
}
