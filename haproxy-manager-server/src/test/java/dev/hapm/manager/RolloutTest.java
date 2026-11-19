package dev.hapm.manager;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hapm.core.model.AgentProtocol.Heartbeat;
import dev.hapm.core.model.AgentProtocol.LastApply;
import dev.hapm.core.model.ClusterSettings;
import dev.hapm.core.validate.Normalizer;
import dev.hapm.manager.service.DeployService;
import dev.hapm.manager.store.Store;
import dev.hapm.manager.store.Store.NodeRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RolloutTest {
  @org.springframework.test.context.DynamicPropertySource
  static void db(org.springframework.test.context.DynamicPropertyRegistry r) {
    TestDb.register(r, "rollout");
  }

  @Autowired Store store;
  @Autowired DeployService deploy;

  String cluster(String name, String rollout) {
    var n = Normalizer.cluster(name, new ClusterSettings(null, rollout, null, null,
        new ClusterSettings.Keepalived(null, null, List.of("10.0.0.100/24"), null, null, null, null, null, null, null), null, null), null);
    return store.createCluster(n.name(), n.settings()).id();
  }

  NodeRow hb(String cid, String name, String state, int applied, LastApply last) {
    return store.upsertHeartbeat(cid, new Heartbeat(name, "10.0.0.1", "t", null, state, applied, last));
  }

  NodeRow hb(String cid, String name, String state, int applied) {
    return hb(cid, name, state, applied, null);
  }

  @Test
  void sequentialBackupsFirstMasterLast() {
    var c = cluster("seq", "sequential");
    var m = hb(c, "lb-a", "MASTER", 0);
    var b1 = hb(c, "lb-b", "BACKUP", 0);
    var b2 = hb(c, "lb-c", "BACKUP", 0);
    assertThat(deploy.create(c, null, "t", null).status()).isEqualTo("in_progress");
    assertThat(deploy.desiredFor(c, b1).hold()).isNull();
    assertThat(deploy.desiredFor(c, b2).hold()).isTrue();
    assertThat(deploy.desiredFor(c, m).hold()).isTrue();
    hb(c, "lb-b", "BACKUP", 1);
    assertThat(deploy.desiredFor(c, store.getNode(b2.id()).orElseThrow()).hold()).isNull();
    assertThat(deploy.desiredFor(c, m).hold()).isTrue();
    hb(c, "lb-c", "BACKUP", 1);
    assertThat(deploy.desiredFor(c, m).hold()).isNull();
    hb(c, "lb-a", "MASTER", 1);
    assertThat(deploy.evaluate(c).orElseThrow().status()).isEqualTo("succeeded");
  }

  @Test
  void parallelOnlyMasterWaits() {
    var c = cluster("par", "parallel");
    var m = hb(c, "lb-a", "MASTER", 0);
    var b1 = hb(c, "lb-b", "BACKUP", 0);
    var b2 = hb(c, "lb-c", "BACKUP", 0);
    deploy.create(c, null, "t", null);
    assertThat(deploy.desiredFor(c, b1).hold()).isNull();
    assertThat(deploy.desiredFor(c, b2).hold()).isNull();
    assertThat(deploy.desiredFor(c, m).hold()).isTrue();
  }

  @Test
  void canaryFailureProtectsMasterAndRevertsToLastGood() {
    var c = cluster("canary", "sequential");
    hb(c, "lb-a", "MASTER", 0);
    hb(c, "lb-b", "BACKUP", 0);
    deploy.create(c, null, "t", null);
    hb(c, "lb-b", "BACKUP", 1);
    var m = hb(c, "lb-a", "MASTER", 1);
    assertThat(deploy.evaluate(c).orElseThrow().status()).isEqualTo("succeeded");
    deploy.create(c, null, "t", null);
    hb(c, "lb-b", "BACKUP", 1, new LastApply(2, false, "haproxy -c falhou", null));
    var d2 = deploy.evaluate(c).orElseThrow();
    assertThat(d2.status()).isEqualTo("failed");
    assertThat(d2.error()).contains("lb-b");
    assertThat(deploy.desiredFor(c, m).version()).isEqualTo(1);
  }

  @Test
  void clusterWithoutNodesSucceedsAndLateNodesCatchUp() {
    var c = cluster("empty", "sequential");
    assertThat(deploy.create(c, null, "t", null).status()).isEqualTo("succeeded");
    var n = hb(c, "novo", "BACKUP", 0);
    assertThat(deploy.desiredFor(c, n).version()).isEqualTo(1);
  }
}
