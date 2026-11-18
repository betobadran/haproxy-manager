package dev.hapm.manager.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Separação de rede entre control plane e data plane: com {@code hapm.agent-port} (HAPM_AGENT_PORT), o manager abre
 * uma segunda porta só para os nós — ela atende apenas {@code /agent/**}, o desafio ACME e o health — e a porta
 * principal (interface/API) deixa de atender os agentes. Assim o firewall libera para a rede dos nós só a porta dos
 * agentes, e a interface fica restrita à rede de administração.
 */
@Configuration
public class AgentPortConfig {
  @Value("${hapm.agent-port:0}") int agentPort;

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> agentConnector() {
    return f -> {
      if (agentPort <= 0) return;
      var c = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
      c.setPort(agentPort);
      f.addAdditionalConnectors(c);
    };
  }

  static boolean agentPath(String p) {
    return p.startsWith("/agent/") || p.startsWith("/.well-known/acme-challenge/") || p.equals("/actuator/health");
  }

  @Component
  @Order(Ordered.HIGHEST_PRECEDENCE)
  static class PortFilter extends OncePerRequestFilter {
    @Value("${hapm.agent-port:0}") int agentPort;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
      if (agentPort > 0) {
        var path = req.getRequestURI();
        boolean onAgentPort = req.getLocalPort() == agentPort;
        if (onAgentPort && !agentPath(path)) {
          res.sendError(404); // a interface/API não existe na porta dos nós
          return;
        }
        if (!onAgentPort && path.startsWith("/agent/")) {
          res.sendError(404); // agentes só pela porta própria
          return;
        }
      }
      chain.doFilter(req, res);
    }
  }
}
