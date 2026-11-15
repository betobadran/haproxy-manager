package dev.hapm.manager.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * - /api/** exige "Authorization: Bearer <token de sessão>" (sem cookies, portanto sem CSRF);
 * - /agent/** usa o token de join do cluster (validado no controller);
 * - UI estática, desafio ACME, /metrics e health são públicos.
 */
@Configuration
public class SecurityConfig {

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder(); // bcrypt
  }

  /** Usuários ficam na tabela própria (AuthController); desativa o usuário padrão do Spring. */
  @Bean
  org.springframework.security.core.userdetails.UserDetailsService noDefaultUsers() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(username);
    };
  }

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, TokenService tokens) throws Exception {
    http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(b -> b.disable())
        .formLogin(f -> f.disable())
        .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .authorizeHttpRequests(a -> a
            .requestMatchers("/api/auth/login").permitAll()
            .requestMatchers("/api/**").authenticated()
            .anyRequest().permitAll())
        .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:")))
        .addFilterBefore(new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
            var h = req.getHeader("Authorization");
            if (h != null && h.startsWith("Bearer ") && req.getRequestURI().startsWith("/api/")) {
              tokens.verify(h.substring(7)).ifPresent(user -> SecurityContextHolder.getContext().setAuthentication(
                  new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))));
            }
            chain.doFilter(req, res);
          }
        }, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
