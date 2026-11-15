package dev.hapm.core.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.List;

/** Certificado TLS. {@code pem} contém certificado + cadeia + chave privada. */
public record Certificate(String name, String source, String pem, Meta meta, AcmeInfo acme) {

  public boolean usable() {
    return pem != null && !pem.isBlank();
  }

  public Certificate withPem(String newPem, Meta newMeta) {
    return new Certificate(name, source, newPem, newMeta, acme);
  }

  public Certificate withAcme(AcmeInfo newAcme) {
    return new Certificate(name, source, pem, meta, newAcme);
  }

  public Certificate withoutPem() {
    return new Certificate(name, source, null, meta, acme);
  }

  public record Meta(
      String commonName, List<String> domains, String issuer, String notBefore, String notAfter, String fingerprint256, Integer chainLength) {}

  public record AcmeInfo(@JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> domains, String email, String status, String error, String issuedAt, String updatedAt) {}
}
