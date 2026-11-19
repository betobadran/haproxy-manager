package dev.hapm.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hapm.core.model.Bundle;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignaturesTest {
  @Test
  void signsAndDetectsTampering() {
    var kp = Signatures.generate();
    var b = new Bundle(3, "prod", Map.of("haproxy.cfg", "global\n"), Map.of("keepalived.conf", "x"), Bundle.digest(3, Map.of("haproxy.cfg", "global\n"), Map.of("keepalived.conf", "x")));
    var signed = b.withSignature(Signatures.sign(kp.getPrivate(), b));
    var pub = Signatures.decodePublic(Signatures.encodePublic(kp.getPublic()));
    assertThat(Signatures.verify(pub, signed)).isTrue();
    // conteúdo, versão ou cluster alterados invalidam a assinatura
    assertThat(Signatures.verify(pub, new Bundle(3, "prod", Map.of("haproxy.cfg", "global\n    daemon\n"), signed.keepalived(), signed.sha256(), signed.signature()))).isFalse();
    assertThat(Signatures.verify(pub, new Bundle(4, "prod", signed.files(), signed.keepalived(), signed.sha256(), signed.signature()))).isFalse();
    assertThat(Signatures.verify(pub, new Bundle(3, "outro", signed.files(), signed.keepalived(), signed.sha256(), signed.signature()))).isFalse();
    // outra chave, ou sem assinatura
    assertThat(Signatures.verify(Signatures.generate().getPublic(), signed)).isFalse();
    assertThat(Signatures.verify(pub, b)).isFalse();
    assertThat(Signatures.fingerprint(Signatures.encodePublic(kp.getPublic()))).startsWith("SHA256:").hasSize(7 + 47);
  }
}
