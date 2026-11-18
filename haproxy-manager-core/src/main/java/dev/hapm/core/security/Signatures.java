package dev.hapm.core.security;

import dev.hapm.core.model.Bundle;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/** Assinatura Ed25519 dos bundles: o manager assina, o agente confere com a chave pública fixada no nó. */
public final class Signatures {
  private Signatures() {}

  public static KeyPair generate() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String encodePublic(PublicKey k) {
    return Base64.getEncoder().encodeToString(k.getEncoded());
  }

  public static String encodePrivate(PrivateKey k) {
    return Base64.getEncoder().encodeToString(k.getEncoded());
  }

  public static PublicKey decodePublic(String b64) {
    try {
      return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64.strip())));
    } catch (Exception e) {
      throw new IllegalArgumentException("chave pública Ed25519 inválida", e);
    }
  }

  public static PrivateKey decodePrivate(String b64) {
    try {
      return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64.strip())));
    } catch (Exception e) {
      throw new IllegalArgumentException("chave privada Ed25519 inválida", e);
    }
  }

  /** Impressão digital curta para conferência visual: SHA-256 da chave pública, 16 bytes em hex com ':'. */
  public static String fingerprint(String publicB64) {
    try {
      var h = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(publicB64.strip()));
      var hex = HexFormat.ofDelimiter(":").formatHex(h, 0, 16);
      return "SHA256:" + hex;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String sign(PrivateKey key, Bundle b) {
    try {
      var s = Signature.getInstance("Ed25519");
      s.initSign(key);
      s.update(b.signingPayload());
      return Base64.getEncoder().encodeToString(s.sign());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static boolean verify(PublicKey key, Bundle b) {
    if (b.signature() == null || b.signature().isBlank()) return false;
    try {
      var s = Signature.getInstance("Ed25519");
      s.initVerify(key);
      s.update(b.signingPayload());
      return s.verify(Base64.getDecoder().decode(b.signature()));
    } catch (Exception e) {
      return false;
    }
  }
}
