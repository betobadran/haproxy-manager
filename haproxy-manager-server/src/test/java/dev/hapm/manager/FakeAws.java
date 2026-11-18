package dev.hapm.manager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AWS simulada para testes, falando os protocolos reais que o SDK usa: STS (query/XML), ACM (JSON 1.1) e
 * Route 53 (REST/XML). O ACM só emite o certificado quando o registro CNAME de validação aparece no Route 53,
 * e exporta a chave privada cifrada com a passphrase (PKCS#8), como o serviço real.
 */
public class FakeAws implements AutoCloseable {
  static final JsonMapper JSON = JsonMapper.builder().build();

  public record Call(String service, String action, String sessionToken, Map<String, String> params) {}

  public final List<Call> calls = new CopyOnWriteArrayList<>();
  public final Map<String, Cert> certs = new ConcurrentHashMap<>();
  public final Map<String, String> records = new ConcurrentHashMap<>(); // nome -> valor (CNAMEs no Route 53)
  public volatile boolean denyAcm;
  final HttpServer server;
  final KeyPair caKey;

  public static final class Cert {
    public String arn;
    public List<String> domains;
    public String status = "PENDING_VALIDATION";
    public boolean exportable;
    public Instant notAfter;
    public KeyPair key;
    public String pem;
    public String validationName;
    public String validationValue;
  }

  public FakeAws() throws Exception {
    var g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    caKey = g.generateKeyPair();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  static void send(HttpExchange ex, int code, String type, String body) throws java.io.IOException {
    var b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", type);
    ex.getResponseHeaders().add("x-amzn-RequestId", UUID.randomUUID().toString());
    ex.sendResponseHeaders(code, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  static Map<String, String> form(String body) {
    var m = new LinkedHashMap<String, String>();
    for (var kv : body.split("&")) {
      int i = kv.indexOf('=');
      if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
    }
    return m;
  }

  void handle(HttpExchange ex) throws java.io.IOException {
    try {
      var body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      var token = ex.getRequestHeaders().getFirst("X-Amz-Security-Token");
      var target = ex.getRequestHeaders().getFirst("X-Amz-Target");
      var path = ex.getRequestURI().getPath();
      if (target != null && target.startsWith("CertificateManager.")) acm(ex, target.substring(19), JSON.readTree(body.isEmpty() ? "{}" : body), token);
      else if (path.startsWith("/2013-04-01/")) route53(ex, path, ex.getRequestURI().getRawQuery(), body, token);
      else sts(ex, form(body), token);
    } catch (Exception e) {
      System.err.println("FakeAws: " + e);
      e.printStackTrace();
      send(ex, 500, "text/plain", e.toString());
    }
  }

  // ------------------------------------------------------------------ STS
  void sts(HttpExchange ex, Map<String, String> p, String token) throws java.io.IOException {
    var action = p.get("Action");
    calls.add(new Call("sts", action, token, p));
    var ns = "xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"";
    if ("AssumeRole".equals(action)) {
      var session = p.get("RoleSessionName");
      if (p.get("RoleArn").endsWith("/sem-trust")) {
        send(ex, 403, "text/xml", "<ErrorResponse " + ns + "><Error><Type>Sender</Type><Code>AccessDenied</Code><Message>User is not authorized to perform: sts:AssumeRole</Message></Error><RequestId>1</RequestId></ErrorResponse>");
        return;
      }
      var exp = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
      send(ex, 200, "text/xml", "<AssumeRoleResponse " + ns + "><AssumeRoleResult><Credentials><AccessKeyId>ASIAFAKE</AccessKeyId>"
          + "<SecretAccessKey>segredo</SecretAccessKey><SessionToken>sessao-" + session + "</SessionToken><Expiration>" + exp + "</Expiration></Credentials>"
          + "<AssumedRoleUser><Arn>arn:aws:sts::123456789012:assumed-role/hapm/" + session + "</Arn><AssumedRoleId>AROAFAKE:" + session
          + "</AssumedRoleId></AssumedRoleUser></AssumeRoleResult><ResponseMetadata><RequestId>1</RequestId></ResponseMetadata></AssumeRoleResponse>");
    } else if ("GetCallerIdentity".equals(action)) {
      var arn = token != null && token.startsWith("sessao-") ? "arn:aws:sts::123456789012:assumed-role/hapm/" + token.substring(7)
          : "arn:aws:iam::111111111111:user/manager";
      send(ex, 200, "text/xml", "<GetCallerIdentityResponse " + ns + "><GetCallerIdentityResult><Arn>" + arn + "</Arn><UserId>X</UserId>"
          + "<Account>123456789012</Account></GetCallerIdentityResult><ResponseMetadata><RequestId>1</RequestId></ResponseMetadata></GetCallerIdentityResponse>");
    } else {
      send(ex, 400, "text/xml", "<ErrorResponse " + ns + "><Error><Code>InvalidAction</Code><Message>" + action + "</Message></Error></ErrorResponse>");
    }
  }

  // ------------------------------------------------------------------ ACM
  void acmError(HttpExchange ex, int code, String type, String msg) throws java.io.IOException {
    send(ex, code, "application/x-amz-json-1.1", JSON.writeValueAsString(Map.of("__type", type, "message", msg)));
  }

  void acm(HttpExchange ex, String action, JsonNode in, String token) throws Exception {
    calls.add(new Call("acm", action, token, Map.of("body", in.toString())));
    if (denyAcm) {
      acmError(ex, 400, "AccessDeniedException", "not authorized to perform acm:" + action);
      return;
    }
    switch (action) {
      case "ListCertificates" -> {
        var list = new ArrayList<Map<String, Object>>();
        for (var c : certs.values()) list.add(Map.of("CertificateArn", c.arn, "DomainName", c.domains.get(0)));
        send(ex, 200, "application/x-amz-json-1.1", JSON.writeValueAsString(Map.of("CertificateSummaryList", list)));
      }
      case "RequestCertificate" -> {
        var c = new Cert();
        c.arn = "arn:aws:acm:sa-east-1:123456789012:certificate/" + UUID.randomUUID();
        c.domains = new ArrayList<>();
        c.domains.add(in.path("DomainName").asString());
        for (var s : in.path("SubjectAlternativeNames")) if (!c.domains.contains(s.asString())) c.domains.add(s.asString());
        c.exportable = "ENABLED".equals(in.path("Options").path("Export").asString(""));
        var base = c.domains.get(0).replaceFirst("^\\*\\.", "");
        c.validationName = "_" + Integer.toHexString(c.arn.hashCode()) + "." + base + ".";
        c.validationValue = "_" + Integer.toHexString(base.hashCode()) + ".acm-validations.aws.";
        certs.put(c.arn, c);
        send(ex, 200, "application/x-amz-json-1.1", JSON.writeValueAsString(Map.of("CertificateArn", c.arn)));
      }
      case "DescribeCertificate" -> {
        var c = certs.get(in.path("CertificateArn").asString());
        if (c == null) {
          acmError(ex, 400, "ResourceNotFoundException", "not found");
          return;
        }
        if (c.status.equals("PENDING_VALIDATION") && c.validationValue.equals(records.get(c.validationName))) issue(c, Instant.now().plus(395, ChronoUnit.DAYS));
        var dvo = new ArrayList<Map<String, Object>>();
        for (var d : c.domains) {
          dvo.add(Map.of("DomainName", d, "ValidationDomain", d, "ValidationMethod", "DNS",
              "ValidationStatus", c.status.equals("ISSUED") ? "SUCCESS" : "PENDING_VALIDATION",
              "ResourceRecord", Map.of("Name", c.validationName, "Type", "CNAME", "Value", c.validationValue)));
        }
        var m = new LinkedHashMap<String, Object>();
        m.put("CertificateArn", c.arn);
        m.put("DomainName", c.domains.get(0));
        m.put("SubjectAlternativeNames", c.domains);
        m.put("Status", c.status);
        m.put("Type", "AMAZON_ISSUED");
        m.put("DomainValidationOptions", dvo);
        m.put("Options", Map.of("Export", c.exportable ? "ENABLED" : "DISABLED"));
        if (c.notAfter != null) m.put("NotAfter", c.notAfter.getEpochSecond());
        send(ex, 200, "application/x-amz-json-1.1", JSON.writeValueAsString(Map.of("Certificate", m)));
      }
      case "ExportCertificate" -> {
        var c = certs.get(in.path("CertificateArn").asString());
        if (c == null || !c.status.equals("ISSUED")) {
          acmError(ex, 400, "RequestInProgressException", "certificate not issued");
          return;
        }
        if (!c.exportable) {
          acmError(ex, 400, "ValidationException", "Certificate is not exportable");
          return;
        }
        var pass = new String(Base64.getDecoder().decode(in.path("Passphrase").asString()), StandardCharsets.UTF_8);
        var enc = new JceOpenSSLPKCS8EncryptorBuilder(org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_aes256_CBC).setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider()).setPassword(pass.toCharArray()).build();
        var sw = new StringWriter();
        try (var w = new JcaPEMWriter(sw)) {
          w.writeObject(new JcaPKCS8Generator(c.key.getPrivate(), enc));
        }
        send(ex, 200, "application/x-amz-json-1.1", JSON.writeValueAsString(Map.of("Certificate", c.pem, "CertificateChain", caPem(), "PrivateKey", sw.toString())));
      }
      default -> acmError(ex, 400, "UnknownOperationException", action);
    }
  }

  /** Emite (ou renova) o certificado com uma nova chave e a validade informada. */
  public void issue(Cert c, Instant notAfter) throws Exception {
    var g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    c.key = g.generateKeyPair();
    c.notAfter = notAfter.truncatedTo(ChronoUnit.SECONDS);
    var names = c.domains.stream().map(d -> new GeneralName(GeneralName.dNSName, d)).toArray(GeneralName[]::new);
    var b = new JcaX509v3CertificateBuilder(new X500Name("CN=Fake Amazon RSA 2048 M01"), BigInteger.valueOf(System.nanoTime()),
        Date.from(Instant.now().minus(1, ChronoUnit.DAYS)), Date.from(c.notAfter), new X500Name("CN=" + c.domains.get(0)), c.key.getPublic())
        .addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
    var cert = new JcaX509CertificateConverter().getCertificate(b.build(new JcaContentSignerBuilder("SHA256withRSA").build(caKey.getPrivate())));
    var sw = new StringWriter();
    try (var w = new JcaPEMWriter(sw)) {
      w.writeObject(cert);
    }
    c.pem = sw.toString();
    c.status = "ISSUED";
  }

  String caPem() throws Exception {
    var b = new JcaX509v3CertificateBuilder(new X500Name("CN=Fake Amazon RSA 2048 M01"), BigInteger.ONE, Date.from(Instant.now().minus(1, ChronoUnit.DAYS)),
        Date.from(Instant.now().plus(3650, ChronoUnit.DAYS)), new X500Name("CN=Fake Amazon RSA 2048 M01"), caKey.getPublic());
    var cert = new JcaX509CertificateConverter().getCertificate(b.build(new JcaContentSignerBuilder("SHA256withRSA").build(caKey.getPrivate())));
    var sw = new StringWriter();
    try (var w = new JcaPEMWriter(sw)) {
      w.writeObject(cert);
    }
    return sw.toString();
  }

  // ------------------------------------------------------------------ Route 53
  static final Pattern NAME = Pattern.compile("<Name>([^<]+)</Name>");
  static final Pattern VALUE = Pattern.compile("<Value>([^<]+)</Value>");
  static final String R53 = "xmlns=\"https://route53.amazonaws.com/doc/2013-04-01/\"";
  public final Map<String, String> zones = new LinkedHashMap<>(Map.of("example.com.", "Z0FAKEEXAMPLE"));

  void route53(HttpExchange ex, String path, String query, String body, String token) throws java.io.IOException {
    calls.add(new Call("route53", ex.getRequestMethod() + " " + path, token, Map.of("query", String.valueOf(query))));
    if (path.endsWith("/hostedzone") || path.endsWith("/hostedzonesbyname")) {
      var sb = new StringBuilder();
      for (var z : zones.entrySet()) {
        sb.append("<HostedZone><Id>/hostedzone/").append(z.getValue()).append("</Id><Name>").append(z.getKey())
            .append("</Name><CallerReference>x</CallerReference><Config><PrivateZone>false</PrivateZone></Config><ResourceRecordSetCount>2</ResourceRecordSetCount></HostedZone>");
      }
      var root = path.endsWith("/hostedzone") ? "ListHostedZonesResponse" : "ListHostedZonesByNameResponse";
      send(ex, 200, "text/xml", "<?xml version=\"1.0\"?><" + root + " " + R53 + "><HostedZones>" + sb + "</HostedZones><IsTruncated>false</IsTruncated><MaxItems>100</MaxItems></" + root + ">");
    } else if (path.endsWith("/rrset") || path.endsWith("/rrset/")) {
      var names = NAME.matcher(body);
      var values = VALUE.matcher(body);
      while (names.find() && values.find()) records.put(names.group(1), values.group(1));
      send(ex, 200, "text/xml", "<?xml version=\"1.0\"?><ChangeResourceRecordSetsResponse " + R53 + "><ChangeInfo><Id>/change/C1</Id><Status>PENDING</Status>"
          + "<SubmittedAt>" + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "</SubmittedAt></ChangeInfo></ChangeResourceRecordSetsResponse>");
    } else {
      send(ex, 404, "text/xml", "<ErrorResponse " + R53 + "><Error><Code>NoSuchHostedZone</Code><Message>" + path + "</Message></Error></ErrorResponse>");
    }
  }
}
