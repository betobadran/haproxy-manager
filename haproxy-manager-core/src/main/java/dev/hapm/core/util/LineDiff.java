package dev.hapm.core.util;

import java.util.ArrayList;
import java.util.List;

/** Diff de linhas (LCS) compacto, suficiente para arquivos de configuração. */
public final class LineDiff {
  private LineDiff() {}

  /** op: ' ' igual, '+' adicionada, '-' removida, '@' trecho omitido. */
  public record Line(String op, String line) {}

  public static List<Line> diff(String a, String b, int context) {
    var A = a.split("\n", -1);
    var B = b.split("\n", -1);
    int n = A.length, m = B.length;
    if ((long) n * m > 25_000_000L) return List.of(new Line("!", "arquivo grande demais para diff"));
    var dp = new int[n + 1][m + 1];
    for (int i = n - 1; i >= 0; i--)
      for (int j = m - 1; j >= 0; j--) dp[i][j] = A[i].equals(B[j]) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    var ops = new ArrayList<Line>();
    int i = 0, j = 0;
    while (i < n && j < m) {
      if (A[i].equals(B[j])) {
        ops.add(new Line(" ", A[i++]));
        j++;
      } else if (dp[i + 1][j] >= dp[i][j + 1]) ops.add(new Line("-", A[i++]));
      else ops.add(new Line("+", B[j++]));
    }
    while (i < n) ops.add(new Line("-", A[i++]));
    while (j < m) ops.add(new Line("+", B[j++]));
    var keep = new boolean[ops.size()];
    for (int k = 0; k < ops.size(); k++)
      if (!ops.get(k).op().equals(" ")) for (int x = Math.max(0, k - context); x <= Math.min(ops.size() - 1, k + context); x++) keep[x] = true;
    var out = new ArrayList<Line>();
    boolean skipped = false;
    for (int k = 0; k < ops.size(); k++) {
      if (keep[k]) {
        out.add(ops.get(k));
        skipped = false;
      } else if (!skipped) {
        out.add(new Line("@", "..."));
        skipped = true;
      }
    }
    return out;
  }
}
