package dev.hapm.manager.service;

import dev.hapm.core.model.Bundle;
import dev.hapm.core.security.Signatures;
import dev.hapm.manager.store.Store;
import java.security.PrivateKey;
import org.springframework.stereotype.Service;

/**
 * Par de chaves Ed25519 do manager para assinar os bundles. Gerado na primeira vez e guardado no banco (com
 * vários managers atrás de um balanceador, todos leem o mesmo par). A chave pública vai para os nós no
 * install-node.sh (--manager-key) ou, na falta dela, é fixada no primeiro contato.
 */
@Service
public class SigningKeys {
  static final String PRIV = "bundle.signing.private";
  static final String PUB = "bundle.signing.public";

  private final Store store;
  private volatile PrivateKey priv;
  private volatile String pub;

  public SigningKeys(Store store) {
    this.store = store;
  }

  private synchronized void load() {
    if (priv != null) return;
    var p = store.getKv(PRIV);
    if (p.isEmpty()) {
      var kp = Signatures.generate();
      store.setKv(PUB, Signatures.encodePublic(kp.getPublic()));
      store.setKv(PRIV, Signatures.encodePrivate(kp.getPrivate()));
      p = store.getKv(PRIV);
    }
    priv = Signatures.decodePrivate(p.orElseThrow());
    pub = store.getKv(PUB).orElseThrow();
  }

  public String publicKey() {
    load();
    return pub;
  }

  public String fingerprint() {
    return Signatures.fingerprint(publicKey());
  }

  public Bundle sign(Bundle b) {
    load();
    return b.withSignature(Signatures.sign(priv, b));
  }
}
