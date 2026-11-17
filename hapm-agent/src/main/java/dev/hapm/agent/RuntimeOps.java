package dev.hapm.agent;

import dev.hapm.core.model.AgentProtocol.Runtime;
import dev.hapm.core.model.AgentProtocol.RuntimeReport;
import dev.hapm.core.model.AgentProtocol.ServerOverride;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aplica o estado operacional dos servidores (drain / maint / pronto / peso) pela runtime API do HAProxy,
 * sem publicar configuração. É uma reconciliação: a cada heartbeat compara o desejado (enviado pelo manager)
 * com o real ({@code show servers state}) e só envia o que difere — por isso sobrevive a reloads, que voltam
 * os servidores ao estado da configuração.
 */
public class RuntimeOps {
  /** Flags de srv_admin_state: manutenção e drenagem forçadas pela runtime API. */
  static final int FMAINT = 0x01;
  static final int FDRAIN = 0x08;
  /** manutenção vinda da configuração ("disabled"): sem override, o agente não mexe */
  static final int CMAINT = 0x04;

  private final String socketOverride;
  /** servidores cujo peso foi alterado por nós (para restaurar o peso da configuração quando o override sair) */
  private final Set<String> weighted = new HashSet<>();

  public RuntimeOps(String socketOverride) {
    this.socketOverride = socketOverride == null || socketOverride.isBlank() ? null : socketOverride;
  }

  record ServerState(String backend, String server, int admin, int uweight, int iweight) {}

  /** Envia um comando à CLI do HAProxy (modo não interativo: a conexão fecha após a resposta). */
  static String command(Path socket, String cmd) throws IOException {
    try (var ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      ch.connect(UnixDomainSocketAddress.of(socket));
      ch.write(ByteBuffer.wrap((cmd + "\n").getBytes(StandardCharsets.US_ASCII)));
      var out = new StringBuilder();
      var buf = ByteBuffer.allocate(16384);
      var watchdog = Thread.ofVirtual().start(() -> {
        try {
          Thread.sleep(5000);
          ch.close();
        } catch (InterruptedException | IOException ignored) {
          // respondeu a tempo
        }
      });
      try {
        int n;
        while ((n = ch.read(buf.clear())) >= 0) out.append(new String(buf.array(), 0, n, StandardCharsets.UTF_8));
      } finally {
        watchdog.interrupt();
      }
      return out.toString();
    }
  }

  /** Interpreta "show servers state" pelos nomes das colunas do cabeçalho (o formato ganha colunas entre versões). */
  static Map<String, ServerState> parseState(String out) {
    var res = new HashMap<String, ServerState>();
    String[] cols = null;
    for (var line : out.split("\n")) {
      line = line.strip();
      if (line.startsWith("# ")) {
        cols = line.substring(2).split(" ");
        continue;
      }
      if (cols == null || line.isEmpty() || line.startsWith("#")) continue;
      var f = line.split(" ");
      var idx = new HashMap<String, Integer>();
      for (int i = 0; i < cols.length; i++) idx.put(cols[i], i);
      try {
        var be = f[idx.get("be_name")];
        var srv = f[idx.get("srv_name")];
        res.put(be + "/" + srv, new ServerState(be, srv, Integer.parseInt(f[idx.get("srv_admin_state")]),
            Integer.parseInt(f[idx.get("srv_uweight")]), Integer.parseInt(f[idx.get("srv_iweight")])));
      } catch (RuntimeException e) {
        // linha fora do formato esperado: ignora
      }
    }
    return res;
  }

  static String current(int admin) {
    if ((admin & FMAINT) != 0) return "maint";
    if ((admin & FDRAIN) != 0) return "drain";
    return "ready";
  }

  /** Comandos necessários para levar o estado real ao desejado. */
  List<String> plan(Map<String, ServerState> actual, List<ServerOverride> desired, List<String> errors) {
    var want = new HashMap<String, ServerOverride>();
    for (var o : desired) want.put(o.backend() + "/" + o.server(), o);
    for (var k : want.keySet()) if (!actual.containsKey(k)) errors.add("servidor " + k + " não existe no HAProxy deste nó");
    var cmds = new ArrayList<String>();
    var nowWeighted = new HashSet<String>();
    for (var e : actual.entrySet()) {
      var k = e.getKey();
      var s = e.getValue();
      var o = want.get(k);
      var target = o == null || o.state() == null ? "ready" : o.state();
      boolean configMaint = (s.admin() & CMAINT) != 0;
      if (!(o == null && configMaint) && !target.equals(current(s.admin()))) cmds.add("set server " + k + " state " + target);
      Integer w = null;
      if (o != null && o.weight() != null) {
        w = o.weight();
        nowWeighted.add(k);
      } else if (weighted.contains(k)) {
        w = s.iweight(); // override de peso removido: volta ao peso da configuração
      }
      if (w != null && w != s.uweight()) cmds.add("set server " + k + " weight " + w);
    }
    weighted.clear();
    weighted.addAll(nowWeighted);
    return cmds;
  }

  public RuntimeReport reconcile(Runtime rt) {
    if (rt == null) return null;
    var sock = socketOverride != null ? socketOverride : rt.adminSocket();
    var desired = rt.servers() == null ? List.<ServerOverride>of() : rt.servers();
    if (sock == null || !Files.exists(Path.of(sock))) {
      return desired.isEmpty() && weighted.isEmpty() ? null
          : new RuntimeReport(false, "socket de administração do HAProxy não encontrado: " + sock, Instant.now().toString(), 0);
    }
    var errors = new ArrayList<String>();
    int sent = 0;
    try {
      var actual = parseState(command(Path.of(sock), "show servers state"));
      for (var c : plan(actual, desired, errors)) {
        var out = command(Path.of(sock), c).strip();
        sent++;
        if (!out.isEmpty()) errors.add(c + ": " + out);
        else Log.info("runtime API: " + c);
      }
    } catch (IOException e) {
      errors.add("runtime API (" + sock + "): " + e.getMessage());
    }
    if (!errors.isEmpty()) Log.warn("runtime API: " + String.join(" | ", errors));
    return new RuntimeReport(errors.isEmpty(), errors.isEmpty() ? null : String.join("; ", errors), Instant.now().toString(), sent);
  }
}
