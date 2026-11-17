import { useMemo, useState } from 'react';
import { api } from '../api';
import { fmtBytes, fmtNum, slot, TimeChart, type Series } from '../charts';
import { useLoad } from '../ui';
import { useFmt, useT } from '../prefs';

const WINDOWS = [5, 15, 60];
// classes HTTP em ordem de empilhamento, com cores validadas como sequência adjacente
const CLASS_COLOR: Record<string, string> = { '2xx': 'var(--s1)', '3xx': 'var(--s3)', '4xx': 'var(--s7)', '5xx': 'var(--s8)' };
const MAX_SERIES = 8;

/** Estado com ícone + rótulo (cor nunca sozinha). */
function Status({ level, label }: { level: 'good' | 'warn' | 'crit'; label: string }) {
  return <span className={`status st-${level}`}>{label}</span>;
}

export function MetricsTab({ cluster }: { cluster: any }) {
  const t = useT();
  const fmt = useFmt();
  const [minutes, setMinutes] = useState(15);
  const [node, setNode] = useState('');
  const { data: m, error } = useLoad<any>(
    () => api('GET', `/clusters/${cluster.id}/metrics?minutes=${minutes}${node ? `&node=${encodeURIComponent(node)}` : ''}`), [cluster.id, minutes, node], 5000);
  // cor segue o listener (ordem alfabética de todos os listeners do cluster), não a posição na série filtrada
  const { data: fes } = useLoad<any[]>(() => api('GET', `/clusters/${cluster.id}/frontends`), [cluster.id]);
  const colorOf = useMemo(() => {
    const names = (fes ?? []).map((f) => f.name).sort();
    return (name: string) => (names.indexOf(name) >= 0 && names.indexOf(name) < MAX_SERIES - 1 ? slot(names.indexOf(name)) : slot(MAX_SERIES - 1));
  }, [fes]);

  /** No máximo 8 séries: o restante é somado em "Outros". */
  const perListener = (data: Record<string, number[]>): Series[] => {
    const names = Object.keys(data).sort();
    const keep = names.length > MAX_SERIES ? names.slice(0, MAX_SERIES - 1) : names;
    const out: Series[] = keep.map((k) => ({ key: k, label: k, color: colorOf(k), values: data[k] }));
    if (names.length > MAX_SERIES) {
      const rest = names.slice(MAX_SERIES - 1);
      out.push({ key: '__other', label: t('Outros'), color: slot(MAX_SERIES - 1), values: data[rest[0]].map((_, i) => rest.reduce((a, k) => a + (data[k][i] || 0), 0)) });
    }
    return out;
  };

  if (!m) return error ? <p className="err">{error}</p> : <p className="muted">{t('carregando...')}</p>;
  const c = m.current;
  const errLevel = c.errorRatePct >= 5 ? 'crit' : c.errorRatePct >= 1 ? 'warn' : 'good';
  const nodeStatesOf = (states: Record<string, string>) => Object.keys(states ?? {}).filter((n) => !node || n === node);
  const beLevel = c.backendsTotal === 0 ? 'good' : c.backendsUp === c.backendsTotal ? 'good' : c.backendsUp === 0 ? 'crit' : 'warn';
  // servidores DOWN em algum nó (o backend pode seguir UP com os demais)
  const serversDown = (m.servers as any[]).filter((s) => nodeStatesOf(s.states).some((n) => s.states[n] && s.states[n] !== 'UP')).length;
  const beTile = beLevel !== 'good' ? beLevel : serversDown > 0 ? 'warn' : 'good';
  const nodeStates = (states: Record<string, string>) => m.nodes.filter((n: any) => !node || n.name === node).map((n: any) => n.name);
  const stLevel = (s?: string) => (s === 'UP' ? 'good' : s === 'MAINT' || s === 'DRAIN' || s === 'NOLB' ? 'warn' : 'crit');

  return (
    <>
      <div className="filters">
        <div className="seg-light" role="radiogroup" aria-label={t('Janela de tempo')}>
          {WINDOWS.map((w) => (
            <button key={w} role="radio" aria-checked={minutes === w} className={minutes === w ? 'on' : ''} onClick={() => setMinutes(w)}>
              {w < 60 ? t('{n} min', { n: w }) : t('1 h')}
            </button>
          ))}
        </div>
        <select value={node} onChange={(e) => setNode(e.target.value)} aria-label={t('Nó')}>
          <option value="">{t('Todos os nós')}</option>
          {m.nodes.map((n: any) => <option key={n.name} value={n.name}>{n.name}{n.fresh ? '' : ` (${t('sem dados')})`}</option>)}
        </select>
        <span className="muted">{t('Atualiza a cada 5 s · fuso {zone}', { zone: fmt.zone })}</span>
      </div>

      <div className="grid" style={{ marginBottom: 16 }}>
        <div className="stat"><div className="k">{t('Requisições/s (último minuto)')}</div><div className="hero">{fmtNum(c.requestsPerSec)}</div></div>
        <div className="stat"><div className="k">{t('Erros 5xx')}</div><div className="hero">{c.errorRatePct.toFixed(c.errorRatePct < 10 ? 2 : 1)}%</div>
          <Status level={errLevel} label={errLevel === 'good' ? t('normal') : errLevel === 'warn' ? t('atenção') : t('crítico')} /></div>
        <div className="stat"><div className="k">{t('Sessões ativas')}</div><div className="hero">{fmtNum(c.sessions)}</div></div>
        <div className="stat"><div className="k">{t('Backends saudáveis')}</div><div className="hero">{c.backendsUp}/{c.backendsTotal}</div>
          <Status level={beTile} label={beLevel === 'crit' ? t('indisponível') : beLevel === 'warn' ? t('parcial') : serversDown > 0 ? t('{n} servidor(es) DOWN', { n: serversDown }) : t('todos UP')} /></div>
      </div>

      <div className="charts">
        <TimeChart title={t('Requisições/s por listener')} unit={t('TCP: conexões/s')} ts={m.ts} series={perListener(m.requests)} minMax={1} />
        <TimeChart title={t('Respostas/s por classe')} ts={m.ts} stacked
          series={['2xx', '3xx', '4xx', '5xx'].map((k) => ({ key: k, label: k, color: CLASS_COLOR[k], values: m.responses[k] }))} />
        <TimeChart title={t('Sessões ativas por listener')} ts={m.ts} series={perListener(m.sessions)} minMax={4} />
        <TimeChart title={t('Tráfego')} unit={t('bytes/s')} ts={m.ts} format={fmtBytes}
          series={[{ key: 'in', label: t('Entrada'), color: 'var(--s1)', values: m.bytesIn }, { key: 'out', label: t('Saída'), color: 'var(--s2)', values: m.bytesOut }]} />
      </div>

      <div className="panel" style={{ marginTop: 16 }}>
        <h3>Backends</h3>
        {m.backends.length ? (
          <table>
            <thead><tr><th>Backend</th><th>{t('Estado')}</th><th>{t('Servidores ativos')}</th><th>{t('Backup')}</th><th>{t('Tempo médio de resposta')}</th><th>{t('Fila')}</th><th>{t('Sessões')}</th></tr></thead>
            <tbody>
              {m.backends.map((b: any) => {
                const level = b.nodesUp === b.nodes ? 'good' : b.nodesUp === 0 ? 'crit' : 'warn';
                return (
                  <tr key={b.name}>
                    <td><b>{b.name}</b></td>
                    <td><Status level={level} label={level === 'good' ? 'UP' : level === 'crit' ? 'DOWN' : t('UP em {up} de {n} nós', { up: b.nodesUp, n: b.nodes })} /></td>
                    <td>{b.active}</td><td>{b.backup}</td>
                    <td>{m.backendModes?.[b.name] === 'tcp' ? <span className="muted" title={t('TCP não mede tempo de resposta HTTP')}>—</span> : `${fmtNum(b.responseTimeMs)} ms`}</td><td>{fmtNum(b.queue)}</td><td>{fmtNum(b.sessions)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        ) : <p className="muted">{t('Aguardando dados dos agentes...')}</p>}
      </div>

      {m.servers.length > 0 && (
        <div className="panel">
          <h3>{t('Servidores')}</h3>
          <table>
            <thead><tr><th>Backend</th><th>{t('Servidor')}</th>{nodeStates({}).map((n: string) => <th key={n}>{n}</th>)}</tr></thead>
            <tbody>
              {m.servers.map((s: any) => (
                <tr key={`${s.backend}/${s.server}`}>
                  <td>{s.backend}</td><td><b>{s.server}</b></td>
                  {nodeStates(s.states).map((n: string) => <td key={n}>{s.states[n] ? <Status level={stLevel(s.states[n])} label={s.states[n]} /> : '—'}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="muted">{t('Métricas ao vivo da última hora, enviadas pelos agentes. Para histórico longo e alertas, use o Grafana/Prometheus.')}</p>
    </>
  );
}
