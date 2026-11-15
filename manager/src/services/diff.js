// Diff de linhas (LCS) compacto, suficiente para arquivos de configuração.
export function lineDiff(a = '', b = '', context = 3) {
  const A = a.split('\n');
  const B = b.split('\n');
  const n = A.length;
  const m = B.length;
  if (n * m > 25_000_000) return [{ op: '!', line: 'arquivo grande demais para diff' }];
  const dp = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) dp[i][j] = A[i] === B[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
  const ops = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (A[i] === B[j]) ops.push({ op: ' ', line: A[i++] }), j++;
    else if (dp[i + 1][j] >= dp[i][j + 1]) ops.push({ op: '-', line: A[i++] });
    else ops.push({ op: '+', line: B[j++] });
  }
  while (i < n) ops.push({ op: '-', line: A[i++] });
  while (j < m) ops.push({ op: '+', line: B[j++] });
  // reduz para trechos com contexto
  const keep = new Array(ops.length).fill(false);
  ops.forEach((o, k) => {
    if (o.op !== ' ') for (let x = Math.max(0, k - context); x <= Math.min(ops.length - 1, k + context); x++) keep[x] = true;
  });
  const out = [];
  let skipped = false;
  ops.forEach((o, k) => {
    if (keep[k]) {
      out.push(o);
      skipped = false;
    } else if (!skipped) {
      out.push({ op: '@', line: '...' });
      skipped = true;
    }
  });
  return out;
}
