package dev.hapm.manager.web;

import dev.hapm.manager.service.SigningKeys;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Informações do manager úteis na instalação dos nós. */
@RestController
public class SystemController {
  private final SigningKeys signing;
  @org.springframework.beans.factory.annotation.Value("${hapm.agent-port:0}") int agentPort;

  public SystemController(SigningKeys signing) {
    this.signing = signing;
  }

  /** Chave pública com que o manager assina os bundles (fixe nos nós com install-node.sh --manager-key). */
  @GetMapping("/api/system/signing-key")
  public Map<String, String> signingKey() {
    return Map.of("publicKey", signing.publicKey(), "fingerprint", signing.fingerprint(), "algorithm", "Ed25519", "agentPort", String.valueOf(agentPort));
  }
}
