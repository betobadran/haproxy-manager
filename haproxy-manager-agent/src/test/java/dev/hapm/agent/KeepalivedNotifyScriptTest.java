package dev.hapm.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Executa o keepalived-notify.sh real contra um AWS CLI falso (registra as chamadas e simula --dry-run)
 * e um IMDS falso (IMDSv2), cobrindo failover na mesma subnet, entre subnets (tabela de rotas) e o preflight.
 */
class KeepalivedNotifyScriptTest {
  static final Path SCRIPT = Path.of("src/main/dist/scripts/keepalived-notify.sh").toAbsolutePath();

  @TempDir Path tmp;
  HttpServer imds;
  Path calls;

  /** AWS CLI falso: grava cada chamada e responde conforme a operação. FAKE_DENY=<operação> nega permissão. */
  static final String FAKE_AWS = """
      #!/bin/bash
      echo "$*${AWS_SESSION_TOKEN:+ [sessao=$AWS_SESSION_TOKEN]}" >> "$FAKE_CALLS"
      op="$2"
      if [[ " $* " == *" --dry-run "* ]]; then
        if [[ -n "${FAKE_DENY:-}" && "$op" == "$FAKE_DENY" ]]; then echo "An error occurred (UnauthorizedOperation) when calling the X operation" >&2; exit 254; fi
        echo "An error occurred (DryRunOperation) when calling the X operation: Request would have succeeded, but DryRun flag is set." >&2; exit 254
      fi
      case "$op" in
        describe-network-interfaces) printf 'subnet-aaa\\t%s\\tvpc-111\\n' "${FAKE_SRCDST:-True}" ;;
        describe-subnets) echo "${FAKE_SUBNET_CIDR:-10.0.1.0/24}" ;;
        describe-vpcs) echo "10.0.0.0/16" ;;
        describe-route-tables) echo "vpc-111" ;;
        describe-addresses) echo "eipalloc-0abc" ;;
        assume-role) [[ -n "${FAKE_DENY_ASSUME:-}" ]] && { echo "AccessDenied" >&2; exit 254; }; printf 'ASIAFAKE\tsegredo\ttoken-temporario\n' ;;
        replace-route) [[ -n "${FAKE_NO_ROUTE:-}" ]] && { echo "InvalidRoute.NotFound" >&2; exit 254; } ;;
      esac
      exit 0
      """;

  @BeforeEach
  void setup() throws Exception {
    assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "bash indisponível");
    calls = tmp.resolve("calls.log");
    var aws = tmp.resolve("aws");
    Files.writeString(aws, FAKE_AWS);
    Files.setPosixFilePermissions(aws, PosixFilePermissions.fromString("rwxr-xr-x"));
    imds = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    imds.createContext("/", ex -> {
      var path = ex.getRequestURI().getPath();
      String body = path.equals("/latest/api/token") ? "tok"
          : path.equals("/latest/meta-data/mac") ? "0a:00:00:00:00:01"
          : path.equals("/latest/meta-data/network/interfaces/macs/0a:00:00:00:00:01/interface-id") ? "eni-0aaa1111bbbb2222c" : null;
      var bytes = (body == null ? "not found" : body).getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
      ex.getResponseBody().write(bytes);
      ex.close();
    });
    imds.start();
  }

  @AfterEach
  void stop() {
    if (imds != null) imds.stop(0);
  }

  record Run(int code, String out) {}

  Run run(String failoverEnv, Map<String, String> extra, String... args) throws Exception {
    var env = tmp.resolve("hapm-failover.env");
    Files.writeString(env, failoverEnv);
    var cmd = new java.util.ArrayList<String>(List.of("/bin/bash", SCRIPT.toString()));
    cmd.addAll(List.of(args));
    var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    var e = pb.environment();
    e.put("HAPM_FAILOVER_ENV", env.toString());
    e.put("HAPM_VRRP_STATE_FILE", tmp.resolve("state/vrrp_state").toString());
    e.put("HAPM_IMDS_URL", "http://127.0.0.1:" + imds.getAddress().getPort());
    e.put("HAPM_AWS_CLI", tmp.resolve("aws").toString());
    e.put("HAPM_SYSFS_NET", tmp.resolve("no-sysfs").toString()); // força descoberta da ENI pelo IMDS
    e.put("FAKE_CALLS", calls.toString());
    e.putAll(extra);
    var p = pb.start();
    var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new Run(p.waitFor(), out);
  }

  List<String> calls() throws Exception {
    return Files.exists(calls) ? Files.readAllLines(calls) : List.of();
  }

  static final String SAME_SUBNET = """
      HAPM_MODE=aws-eip
      HAPM_AWS_REGION=sa-east-1
      HAPM_IFACE=eth0
      HAPM_PRIVATE_VIP=10.0.1.50
      HAPM_EIP_ALLOCATION_ID=eipalloc-0abc
      """;

  static final String CROSS_SUBNET = """
      HAPM_MODE=aws-eip
      HAPM_AWS_REGION=sa-east-1
      HAPM_IFACE=eth0
      HAPM_ENI_ID=eni-0ddd3333eeee4444f
      HAPM_ROUTE_VIP=192.168.250.10
      HAPM_ROUTE_TABLES=rtb-0aaa1111bbbb2222c,rtb-0ccc3333dddd4444e
      HAPM_MANAGE_SRC_DST_CHECK=1
      """;

  @Test
  void sameSubnetMovesSecondaryIpAndEipToThisEni() throws Exception {
    var r = run(SAME_SUBNET, Map.of(), "INSTANCE", "VI_51", "MASTER", "150");
    assertThat(r.code()).as(r.out()).isZero();
    assertThat(Files.readString(tmp.resolve("state/vrrp_state")).strip()).isEqualTo("MASTER");
    assertThat(calls()).containsExactly(
        "ec2 assign-private-ip-addresses --network-interface-id eni-0aaa1111bbbb2222c --private-ip-addresses 10.0.1.50 --allow-reassignment --region sa-east-1",
        "ec2 associate-address --allocation-id eipalloc-0abc --network-interface-id eni-0aaa1111bbbb2222c --private-ip-address 10.0.1.50 --allow-reassociation --region sa-east-1");
  }

  @Test
  void assumesFailoverRoleWhenConfigured() throws Exception {
    var env = SAME_SUBNET + "HAPM_AWS_ROLE_ARN=arn:aws:iam::123456789012:role/rede-failover\nHAPM_AWS_EXTERNAL_ID=hapm-123\n";
    var r = run(env, Map.of(), "INSTANCE", "VI_51", "MASTER", "150");
    assertThat(r.code()).as(r.out()).isZero();
    var c = calls();
    assertThat(c.get(0)).startsWith("sts assume-role --role-arn arn:aws:iam::123456789012:role/rede-failover").contains("--external-id hapm-123");
    // as chamadas EC2 seguintes usam as credenciais temporárias da role
    assertThat(c.stream().filter(l -> l.startsWith("ec2 ")).toList()).isNotEmpty().allMatch(l -> l.endsWith("[sessao=token-temporario]"));
    // preflight acusa trust policy errada
    var p = run(env, Map.of("FAKE_DENY_ASSUME", "1"), "--preflight");
    assertThat(p.code()).isNotZero();
    assertThat(p.out()).contains("não foi possível assumir a role");
  }

  @Test
  void crossSubnetPointsRoutesToThisEni() throws Exception {
    var r = run(CROSS_SUBNET, Map.of(), "INSTANCE", "VI_51", "MASTER", "140");
    assertThat(r.code()).as(r.out()).isZero();
    assertThat(calls()).containsExactly(
        "ec2 modify-network-interface-attribute --network-interface-id eni-0ddd3333eeee4444f --no-source-dest-check --region sa-east-1",
        "ec2 replace-route --route-table-id rtb-0aaa1111bbbb2222c --destination-cidr-block 192.168.250.10/32 --network-interface-id eni-0ddd3333eeee4444f --region sa-east-1",
        "ec2 replace-route --route-table-id rtb-0ccc3333dddd4444e --destination-cidr-block 192.168.250.10/32 --network-interface-id eni-0ddd3333eeee4444f --region sa-east-1");
  }

  @Test
  void createsRouteWhenItDoesNotExistYet() throws Exception {
    var r = run(CROSS_SUBNET.replace(",rtb-0ccc3333dddd4444e", ""), Map.of("FAKE_NO_ROUTE", "1"), "INSTANCE", "VI_51", "MASTER", "140");
    assertThat(r.code()).as(r.out()).isZero();
    assertThat(calls()).anyMatch(c -> c.startsWith("ec2 create-route --route-table-id rtb-0aaa1111bbbb2222c --destination-cidr-block 192.168.250.10/32"));
  }

  @Test
  void backupDoesNotTouchAws() throws Exception {
    var r = run(SAME_SUBNET, Map.of(), "INSTANCE", "VI_51", "BACKUP", "140");
    assertThat(r.code()).isZero();
    assertThat(calls()).isEmpty();
    assertThat(Files.readString(tmp.resolve("state/vrrp_state")).strip()).isEqualTo("BACKUP");
  }

  @Test
  void preflightPassesWithPermissionsAndCorrectSubnet() throws Exception {
    var r = run(SAME_SUBNET, Map.of(), "--preflight");
    assertThat(r.code()).as(r.out()).isZero();
    assertThat(r.out()).contains("OK\tENI eni-0aaa1111bbbb2222c (subnet subnet-aaa, VPC vpc-111)")
        .contains("OK\tIP secundário 10.0.1.50 pertence à subnet subnet-aaa (10.0.1.0/24)")
        .contains("OK\tpermissão IAM: mover IP secundário").contains("OK\tpermissão IAM: associar Elastic IP").doesNotContain("FAIL");
    // preflight não altera nada: só describe-* e --dry-run
    assertThat(calls()).allMatch(c -> c.contains(" describe-") || c.contains("--dry-run"));
  }

  @Test
  void preflightDetectsSecondaryIpInAnotherSubnet() throws Exception {
    var r = run(SAME_SUBNET, Map.of("FAKE_SUBNET_CIDR", "10.0.2.0/24"), "--preflight");
    assertThat(r.code()).isNotZero();
    assertThat(r.out()).contains("FAIL\tIP secundário 10.0.1.50 está fora da subnet").contains("use o IP de overlay");
  }

  @Test
  void preflightDetectsMissingPermissionAndOverlayInsideVpc() throws Exception {
    var env = CROSS_SUBNET.replace("192.168.250.10", "10.0.9.10");
    var r = run(env, Map.of("FAKE_DENY", "replace-route"), "--preflight");
    assertThat(r.code()).isNotZero();
    assertThat(r.out()).contains("FAIL\tIP de overlay 10.0.9.10 está dentro do CIDR da VPC (10.0.0.0/16)")
        .contains("FAIL\tsem permissão IAM para atualizar rota em rtb-0aaa1111bbbb2222c (ec2:ReplaceRoute)")
        .contains("WARN\tsource/dest check ligado");
  }

  @Test
  void preflightAcceptsDisabledSourceDestCheck() throws Exception {
    var r = run(CROSS_SUBNET, new HashMap<>(Map.of("FAKE_SRCDST", "False")), "--preflight");
    assertThat(r.code()).as(r.out()).isZero();
    assertThat(r.out()).contains("OK\tsource/dest check desligado na ENI").contains("OK\ttabela de rotas rtb-0ccc3333dddd4444e encontrada na VPC vpc-111");
  }
}
