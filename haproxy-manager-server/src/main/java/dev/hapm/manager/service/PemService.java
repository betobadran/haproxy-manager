package dev.hapm.manager.service;

import dev.hapm.core.model.Certificate;
import dev.hapm.core.validate.ValidationException;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.springframework.stereotype.Service;

/** Valida PEM (cert + cadeia + chave), confere se a chave corresponde ao certificado e extrai metadados. */
@Service
public class PemService {
  private static final Pattern CERT = Pattern.compile("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----");
  private static final Pattern KEY = Pattern.compile("-----BEGIN ([A-Z ]*)PRIVATE KEY-----[\\s\\S]+?-----END \\1PRIVATE KEY-----");

  public record Parsed(String pem, Certificate.Meta meta) {}

  public Parsed parse(String pem) {
    var text = pem == null ? "" : pem.replace("\r", "");
    var certs = new ArrayList<String>();
    var m = CERT.matcher(text);
    while (m.find()) certs.add(m.group());
    var km = KEY.matcher(text);
    if (certs.isEmpty()) throw new ValidationException("pem: nenhum certificado encontrado");
    if (!km.find()) throw new ValidationException("pem: chave privada não encontrada (cole certificado + cadeia + chave)");
    var keyPem = km.group();
    try {
      var x509 = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certs.getFirst().getBytes(StandardCharsets.US_ASCII)));
      var key = privateKey(keyPem);
      if (!matches(key, x509)) throw new ValidationException("pem: a chave privada não corresponde ao certificado");
      var domains = new ArrayList<String>();
      if (x509.getSubjectAlternativeNames() != null)
        for (List<?> san : x509.getSubjectAlternativeNames()) if (Integer.valueOf(2).equals(san.get(0))) domains.add(String.valueOf(san.get(1)));
      var cnm = Pattern.compile("CN=([^,]+)").matcher(x509.getSubjectX500Principal().getName());
      var cn = cnm.find() ? cnm.group(1) : null;
      if (domains.isEmpty() && cn != null) domains.add(cn);
      var fp = HexFormat.ofDelimiter(":").withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(x509.getEncoded()));
      var meta = new Certificate.Meta(cn, domains, x509.getIssuerX500Principal().getName(), x509.getNotBefore().toInstant().toString(),
          x509.getNotAfter().toInstant().toString(), fp, certs.size());
      return new Parsed(String.join("\n", certs) + "\n" + keyPem + "\n", meta);
    } catch (ValidationException e) {
      throw e;
    } catch (Exception e) {
      throw new ValidationException("pem: inválido (" + e.getMessage() + ")");
    }
  }

  private static PrivateKey privateKey(String pem) throws Exception {
    try (var parser = new PEMParser(new StringReader(pem))) {
      var obj = parser.readObject();
      var conv = new JcaPEMKeyConverter();
      if (obj instanceof PEMEncryptedKeyPair || obj instanceof PKCS8EncryptedPrivateKeyInfo)
        throw new ValidationException("pem: chave privada criptografada não é suportada");
      if (obj instanceof PEMKeyPair kp) return conv.getKeyPair(kp).getPrivate();
      if (obj instanceof PrivateKeyInfo pki) return conv.getPrivateKey(pki);
      throw new ValidationException("pem: formato de chave não reconhecido");
    }
  }

  /** Assina um desafio com a chave e verifica com o certificado (funciona para RSA, EC e Ed25519). */
  private static boolean matches(PrivateKey key, X509Certificate cert) throws Exception {
    var alg = switch (key.getAlgorithm()) {
      case "RSA" -> "SHA256withRSA";
      case "EC", "ECDSA" -> "SHA256withECDSA";
      case "Ed25519", "EdDSA" -> "Ed25519";
      default -> throw new ValidationException("pem: algoritmo de chave não suportado: " + key.getAlgorithm());
    };
    var data = "hapm-key-check".getBytes(StandardCharsets.US_ASCII);
    var s = Signature.getInstance(alg);
    s.initSign(key);
    s.update(data);
    var sig = s.sign();
    var v = Signature.getInstance(alg);
    v.initVerify(cert.getPublicKey());
    v.update(data);
    try {
      return v.verify(sig);
    } catch (Exception e) {
      return false;
    }
  }
}
