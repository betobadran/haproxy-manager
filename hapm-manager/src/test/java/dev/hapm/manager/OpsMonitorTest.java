package dev.hapm.manager;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hapm.core.model.AgentProtocol.BackendStat;
import dev.hapm.core.model.AgentProtocol.Heartbeat;
import dev.hapm.core.model.AgentProtocol.LastApply;
import dev.hapm.core.model.AgentProtocol.ServerStat;
import dev.hapm.core.model.AgentProtocol.Traffic;
import dev.hapm.core.model.ClusterSettings;
import dev.hapm.core.validate.Normalizer;
import dev.hapm.manager.service.DeployService;
import dev.hapm.manager.service.OpsMonitor;
import dev.hapm.manager.store.Store;
import dev.hapm.manager.web.AgentController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Linha do tempo e alertas operacionais: failover, servidor DOWN/UP, nó offline/online, split-brain, publicação com falha. */
@SpringBootTest
class OpsMonitorTest {
  @org.springframework.test.context.DynamicPropertySource
  static void db(org.springframework.test.context.DynamicPropertyRegistry r) {
    TestDb.register(r, "ops");
  }

  @Autowired Store store;
  @Autowired DeployService deploy;
  @Autowired OpsMonitor ops;
  @Autowired AgentController agent;
  @Autowired JdbcClient jdbc;

  String cid;
  String token;

  static int seq;

  void cluster() {
    var n = Normalizer.cluster("ops" + (seq++), new ClusterSettings(null, null, null, null,
        new ClusterSettings.Keepalived(null, null, List.of("10.0.0.100/24"), null, null, null, null, null, null, null), null, null), null);
    var c = store.createCluster(n.name(), n.settings());
    cid = c.id();
    token = "Bearer " + cid + "." + c.joinSecret();
  }

  void hb(String name, String state) {
    hb(name, state, null, 0, null);
  }

  void hb(String name, String state, String web2, int applied, LastApply last) {
    Traffic t = web2 == null ? null : new Traffic(System.currentTimeMillis(), 0.0, List.of(),
        List.of(new BackendStat("web", true, "DOWN".equals(web2) ? 1 : 2, 0, 0, 0, 0, 0, 0)),
        List.of(new ServerStat("web", "web1", "UP"), new ServerStat("web", "web2", web2)));
    agent.heartbeat(token, null, new Heartbeat(name, null, "t", null, state, applied, last, t, null));
  }

  List<String> kinds() {
    return store.listNotifications(false, cid, 100).stream().map(Store.NotificationRow::kind).toList();
  }

  List<String> events() {
    return store.listEvents(cid, 100).stream().map(Store.EventRow::kind).toList();
  }

  @Test
  void timelineAndAlerts() {
    cluster();
    var c = store.getCluster(cid).orElseThrow();
    hb("lb1", "MASTER");
    hb("lb2", "BACKUP");
    assertThat(events()).contains("node-joined", "vrrp-master");
    assertThat(kinds()).isEmpty();

    // failover: lb1 cai para FAULT, lb2 assume
    hb("lb1", "FAULT");
    hb("lb2", "MASTER");
    assertThat(kinds()).containsExactly("failover");
    var f = store.listNotifications(false, cid, 1).get(0);
    assertThat(f.title()).contains("lb2 assumiu o VIP");
    assertThat(f.message()).contains("de lb1 para lb2");
    hb("lb1", "BACKUP");

    // servidor DOWN (uma vez só) e volta
    hb("lb2", "MASTER", "DOWN", 0, null);
    hb("lb2", "MASTER", "DOWN", 0, null);
    assertThat(kinds()).containsExactly("server-down", "failover");
    hb("lb2", "MASTER", "UP", 0, null);
    assertThat(kinds()).containsExactly("server-up", "server-down", "failover");

    // nó offline: um alerta só, mesmo após "reiniciar" o monitor; volta ao receber heartbeat
    jdbc.sql("UPDATE nodes SET last_seen = ? WHERE cluster_id = ? AND name = 'lb1'").params(System.currentTimeMillis() - 120_000, cid).update();
    ops.check(c);
    ops.check(c);
    ops.reset();
    ops.check(c);
    assertThat(kinds().stream().filter("node-offline"::equals).count()).isEqualTo(1);
    hb("lb1", "BACKUP");
    assertThat(kinds().get(0)).isEqualTo("node-online");

    // split-brain exige 2 verificações seguidas; depois normaliza
    hb("lb1", "MASTER");
    ops.check(c);
    assertThat(kinds()).doesNotContain("split-brain");
    ops.check(c);
    ops.check(c);
    assertThat(kinds().stream().filter("split-brain"::equals).count()).isEqualTo(1);
    hb("lb1", "BACKUP");
    ops.check(c);
    ops.check(c);
    assertThat(kinds().get(0)).isEqualTo("vrrp-ok");

    // publicação com falha
    deploy.create(cid, null, "t", null);
    hb("lb1", "BACKUP", null, 0, new LastApply(1, false, "haproxy -c falhou", null));
    deploy.evaluate(cid);
    assertThat(kinds().get(0)).isEqualTo("deploy-failed");
    assertThat(events()).contains("failover", "server-down", "server-up", "node-offline", "node-online", "split-brain", "vrrp-ok", "deploy-failed");
  }

  @Test
  void scaleInAndAutoRemove() {
    cluster();
    hb("lb1", "BACKUP");
    hb("lb2", "BACKUP");
    // AWS scale-in: o agente avisa que está saindo -> nó removido e não volta a ser registrado
    agent.heartbeat(token, null, new Heartbeat("lb2", null, "t", null, "BACKUP", 0, null, null, null, null, true));
    assertThat(store.getNodeByName(cid, "lb2")).isEmpty();
    agent.heartbeat(token, null, new Heartbeat("lb2", null, "t", null, "BACKUP", 0, null, null, null, null, true));
    assertThat(store.getNodeByName(cid, "lb2")).isEmpty();
    assertThat(events()).contains("node-left");
    assertThat(kinds()).contains("node-left");

    // remoção automática de nó offline (configuração do cluster)
    var c = store.getCluster(cid).orElseThrow();
    var s = c.settings();
    var n = Normalizer.cluster(c.name(), new ClusterSettings(s.mode(), s.rollout(), s.autoDeployOnCertRenew(), s.haproxy(), s.keepalived(), s.aws(), s.acme(),
        s.approval(), new ClusterSettings.Nodes(10)), s);
    store.updateCluster(cid, c.name(), n.settings());
    jdbc.sql("UPDATE nodes SET last_seen = ? WHERE cluster_id = ? AND name = 'lb1'").params(System.currentTimeMillis() - 5 * 60_000, cid).update();
    ops.check(store.getCluster(cid).orElseThrow());
    assertThat(store.getNodeByName(cid, "lb1")).isPresent(); // 5 min < 10 min
    jdbc.sql("UPDATE nodes SET last_seen = ? WHERE cluster_id = ? AND name = 'lb1'").params(System.currentTimeMillis() - 11 * 60_000, cid).update();
    ops.check(store.getCluster(cid).orElseThrow());
    assertThat(store.getNodeByName(cid, "lb1")).isEmpty();
  }
}
