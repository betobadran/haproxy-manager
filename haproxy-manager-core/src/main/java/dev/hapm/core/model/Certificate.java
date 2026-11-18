package dev.hapm.core.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.List;

/**
 * Certificado TLS. {@code pem} contém certificado + cadeia + chave privada.
 * source: "upload" (PEM enviado), "acme" (Let's Encrypt HTTP-01) ou "acm" (AWS Certificate Manager, exportável).
 */
public record Certificate(String name, String source, String pem, Meta meta, AcmeInfo acme, AwsInfo aws) {

  public Certificate(String name, String source, String pem, Meta meta, AcmeInfo acme) {
    this(name, source, pem, meta, acme, null);
  }

  public boolean usable() {
    return pem != null && !pem.isBlank();
  }

  public Certificate withPem(String newPem, Meta newMeta) {
    return new Certificate(name, source, newPem, newMeta, acme, aws);
  }

  public Certificate withAcme(AcmeInfo newAcme) {
    return new Certificate(name, source, pem, meta, newAcme, aws);
  }

  public Certificate withAws(AwsInfo newAws) {
    return new Certificate(name, source, pem, meta, acme, newAws);
  }

  public Certificate withoutPem() {
    return new Certificate(name, source, null, meta, acme, aws);
  }

  public record Meta(
      String commonName, List<String> domains, String issuer, String notBefore, String notAfter, String fingerprint256, Integer chainLength) {}

  public record AcmeInfo(@JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> domains, String email, String status, String error, String issuedAt, String updatedAt) {}

  /** Registro DNS de validação pedido pelo ACM. */
  public record DnsRecord(String name, String type, String value) {}

  /**
   * Certificado do ACM. arn vazio = o HAPM pede um novo (exportável, validação por DNS); arn preenchido = usa um
   * certificado exportável existente. validation: "route53" (o HAPM cria os registros) ou "manual" (a tela mostra os
   * registros para criar). status: requested | pending-validation | issued | error.
   */
  public record AwsInfo(String arn, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> domains, String validation,
                        String hostedZoneId, String status, String error, List<DnsRecord> records, String exportedAt, String updatedAt) {}
}
