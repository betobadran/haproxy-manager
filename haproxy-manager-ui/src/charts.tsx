// Gráfico de séries temporais em SVG (sem dependências): linhas ou áreas empilhadas, eixo único,
// cruz de leitura com tooltip de todas as séries, legenda com valor atual e visão em tabela.
import { useEffect, useMemo, useRef, useState } from 'react';
import { useFmt, useT } from './prefs';

export type Series = { key: string; label: string; color: string; values: number[] };

/** Cores categóricas validadas (CVD/contraste) nos temas claro e escuro: var(--s1)..var(--s8). */
export const slot = (i: number) => `var(--s${(i % 8) + 1})`;

export function fmtNum(v: number) {
  const a = Math.abs(v);
  if (a >= 1e9) return `${(v / 1e9).toFixed(1)}G`;
  if (a >= 1e6) return `${(v / 1e6).toFixed(1)}M`;
  if (a >= 1e3) return `${(v / 1e3).toFixed(1)}k`;
  if (a >= 100 || a === 0) return v.toFixed(0);
  if (a >= 10) return v.toFixed(1);
  return v.toFixed(2);
}

export function fmtBytes(v: number) {
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let x = Math.abs(v);
  while (x >= 1024 && i < u.length - 1) {
    x /= 1024;
    i++;
  }
  return `${x >= 100 || i === 0 ? x.toFixed(0) : x.toFixed(1)} ${u[i]}`;
}

function niceMax(v: number) {
  if (v <= 0) return 1;
  const p = 10 ** Math.floor(Math.log10(v));
  const n = v / p;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * p;
}

function useWidth() {
  const ref = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(600);
  useEffect(() => {
    if (!ref.current) return;
    const ro = new ResizeObserver((e) => setW(Math.max(260, Math.floor(e[0].contentRect.width))));
    ro.observe(ref.current);
    return () => ro.disconnect();
  }, []);
  return { ref, w };
}

export function TimeChart({ title, ts, series, stacked = false, format = fmtNum, unit = '', height = 220, minMax = 0 }: {
  title: string; ts: number[]; series: Series[]; stacked?: boolean; format?: (v: number) => string; unit?: string; height?: number;
  /** escala mínima do eixo (ex.: 1 para contagens, evitando marcas fracionárias com valores baixos) */
  minMax?: number;
}) {
  const t = useT();
  const fmt = useFmt();
  const { ref, w } = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  const [table, setTable] = useState(false);
  const M = { l: 52, r: 12, t: 10, b: 26 };
  const iw = w - M.l - M.r;
  const ih = height - M.t - M.b;
  const n = ts.length;

  // empilhamento (na ordem das séries) ou valores diretos
  const layers = useMemo(() => {
    const base = new Array(n).fill(0);
    return series.map((s) => {
      const lo = [...base];
      const hi = s.values.map((v, i) => (stacked ? (base[i] += v || 0) : v || 0));
      return { ...s, lo, hi };
    });
  }, [series, stacked, n]);
  const yMax = niceMax(Math.max(minMax, ...layers.flatMap((l) => l.hi)));
  const x = (i: number) => M.l + (n <= 1 ? iw / 2 : (i / (n - 1)) * iw);
  const y = (v: number) => M.t + ih - (v / yMax) * ih;
  const yTicks = [0, 0.25, 0.5, 0.75, 1].map((f) => f * yMax);
  const xTicks = n > 1 ? [0, 0.25, 0.5, 0.75, 1].map((f) => Math.round(f * (n - 1))) : [0];
  const latest = n ? series.map((s) => s.values[n - 1] ?? 0) : [];

  const onMove = (e: React.MouseEvent<SVGRectElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    const px = e.clientX - r.left;
    setHover(n <= 1 ? 0 : Math.max(0, Math.min(n - 1, Math.round((px / r.width) * (n - 1)))));
  };

  return (
    <div className="panel chart">
      <div className="chart-head">
        <h3>{title}{unit && <span className="muted"> · {unit}</span>}</h3>
        <button className="small" onClick={() => setTable((v) => !v)} aria-pressed={table}>{table ? t('Gráfico') : t('Tabela')}</button>
      </div>
      {series.length > 1 && (
        <div className="legend">
          {series.map((s, i) => (
            <span key={s.key}><i style={{ background: s.color }} />{s.label} <b>{n ? format(latest[i]) : '—'}</b></span>
          ))}
        </div>
      )}
      {table ? (
        <div className="chart-table">
          <table>
            <thead><tr><th>{t('Horário')}</th>{series.map((s) => <th key={s.key}>{s.label}</th>)}</tr></thead>
            <tbody>
              {ts.map((_, k) => n - 1 - k).slice(0, 30).map((i) => (
                <tr key={ts[i]}><td>{fmt.time(ts[i], true)}</td>{series.map((s) => <td key={s.key}>{format(s.values[i] ?? 0)}</td>)}</tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div ref={ref} className="chart-box" style={{ height }}>
          {n === 0 ? <div className="chart-empty muted">{t('Aguardando dados dos agentes...')}</div> : (
            <svg width={w} height={height} role="img" aria-label={title}>
              {yTicks.map((v) => (
                <g key={v}>
                  <line className="grid" x1={M.l} x2={w - M.r} y1={y(v)} y2={y(v)} />
                  <text className="axis" x={M.l - 6} y={y(v)} dy="0.32em" textAnchor="end">{format(v)}</text>
                </g>
              ))}
              {xTicks.map((i) => (
                <text key={i} className="axis" x={x(i)} y={height - 8} textAnchor={i === 0 ? 'start' : i === n - 1 ? 'end' : 'middle'}>{fmt.time(ts[i])}</text>
              ))}
              {stacked
                ? layers.map((l) => (
                  <path key={l.key} className="area" fill={l.color}
                    d={`M${l.hi.map((v, i) => `${x(i)},${y(v)}`).join('L')}L${l.lo.map((v, i) => `${x(i)},${y(v)}`).reverse().join('L')}Z`} />
                ))
                : layers.map((l) => (
                  <path key={l.key} className="line" stroke={l.color} d={`M${l.hi.map((v, i) => `${x(i)},${y(v)}`).join('L')}`} />
                ))}
              {hover != null && (
                <g>
                  <line className="crosshair" x1={x(hover)} x2={x(hover)} y1={M.t} y2={M.t + ih} />
                  {layers.map((l) => <circle key={l.key} cx={x(hover)} cy={y(l.hi[hover])} r={4} fill={l.color} className="dot" />)}
                </g>
              )}
              <rect x={M.l} y={M.t} width={iw} height={ih} fill="transparent" onMouseMove={onMove} onMouseLeave={() => setHover(null)} />
            </svg>
          )}
          {hover != null && n > 0 && (
            <div className="tooltip" style={{ left: Math.min(x(hover) + 12, w - 200), top: M.t }}>
              <div className="muted">{fmt.date(ts[hover])}</div>
              {series.map((s) => (
                <div key={s.key} className="tt-row"><i style={{ background: s.color }} /><b>{format(s.values[hover] ?? 0)}</b> {s.label}</div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
