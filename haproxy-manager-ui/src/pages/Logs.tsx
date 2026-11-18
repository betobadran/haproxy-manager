// Busca de logs de acesso (HAProxy → OTel → Loki), com filtros e modo "ao vivo".
import { useEffect, useState } from 'react';
import { api } from '../api';
import { useFmt, useT } from '../prefs';
import { usePerm } from '../session';

const MINUTES = [5, 15, 60, 360, 1440];
const FILTERS: [string, string, string][] = [
  ['frontend', 'Listener', 'web'], ['backend', 'Backend', 'app'], ['status', 'Status', '5xx ou 503'], ['client', 'IP do cliente', '10.0.0.'],
  ['host', 'Host', 'api.exemplo.com'], ['path', 'Caminho', '/api/'], ['q', 'Texto', 'term=SC'],
];

function statusClass(s?: string) {
  const n = Number(s);
  if (n >= 500) return 'b-err';
  if (n >= 400) return 'b-warn';
  if (n >= 300) return 'b-acc';
  if (n >= 200) return 'b-ok';
  return 'b-muted';
}

export function LogsTab({ cluster }: { cluster: any }) {
  const t = useT();
  const fmt = useFmt();
  const { canEdit } = usePerm();
  const [f, setF] = useState<Record<string, string>>({});
  const [minutes, setMinutes] = useState(15);
  const [live, setLive] = useState(false);
  const [data, setData] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<number | null>(null);

  const load = async () => {
    const qs = new URLSearchParams({ minutes: String(minutes), limit: '300' });
    for (const [k, v] of Object.entries(f)) if (v?.trim()) qs.set(k, v.trim());
    try {
      setData(await api('GET', `/clusters/${cluster.id}/logs?${qs}`));
      setError(null);
    } catch (e: any) {
      setError(e.message);
    }
  };
  useEffect(() => {
    if (canEdit) void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cluster.id, minutes]);
  useEffect(() => {
    if (!live) return;
    const id = setInterval(() => void load(), 5000);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live, f, minutes]);

  if (!canEdit) return <p className="muted">{t('Logs de acesso contêm IPs de clientes: visíveis para OPERATOR, APPROVER e ADMIN.')}</p>;
  if (data && !data.configured) {
    return (
      <div className="panel">
        <h3>{t('Logs de acesso')}</h3>
        <p>{t('A busca usa o Loki. Configure HAPM_LOKI_URL no manager (ex.: http://loki:3100) e habilite os logs em Configurações › Logs de acesso.')}</p>
        <p className="muted">{t('Caminho dos logs: HAProxy → OTel Collector do nó (syslog) → gateway → Loki e, opcionalmente, SIEM. Detalhes em docs/logs.md.')}</p>
      </div>
    );
  }
  const entries: any[] = data?.entries ?? [];
  return (
    <>
      {data && !data.enabled && <div className="banner banner-warn">{t('Os logs de acesso estão desligados neste cluster: habilite em Configurações › Logs de acesso e publique.')}</div>}
      <div className="panel">
        <div className="row">
          {FILTERS.map(([k, label, ph]) => (
            <label key={k} className="f"><span>{t(label)}</span>
              <input value={f[k] ?? ''} placeholder={ph} onChange={(e) => setF({ ...f, [k]: e.target.value })} onKeyDown={(e) => e.key === 'Enter' && void load()} />
            </label>
          ))}
        </div>
        <div className="filters">
          <div className="seg-light" role="radiogroup" aria-label={t('Janela de tempo')}>
            {MINUTES.map((m) => (
              <button key={m} role="radio" aria-checked={minutes === m} className={minutes === m ? 'on' : ''} onClick={() => setMinutes(m)}>
                {m < 60 ? t('{n} min', { n: m }) : t('{n} h', { n: m / 60 })}
              </button>
            ))}
          </div>
          <button className="primary" onClick={() => void load()}>{t('Buscar')}</button>
          <label className="cb" style={{ marginBottom: 0 }}><input type="checkbox" checked={live} onChange={(e) => setLive(e.target.checked)} /> {t('ao vivo (5 s)')}</label>
          <span className="muted">{t('{n} linha(s), mais recentes primeiro', { n: entries.length })}</span>
        </div>
        {error && <p className="err">{error}</p>}
        {data?.query && <details><summary className="muted">LogQL</summary><pre>{data.query}</pre></details>}
      </div>
      <div className="panel">
        {!entries.length ? <p className="muted">{t('Nenhum log no período com esses filtros.')}</p> : (
          <div className="logs-wrap"><table className="logs">
            <thead><tr><th>{t('Horário')}</th><th>{t('Nó')}</th><th>{t('Cliente')}</th><th>Listener</th><th>{t('Requisição')}</th><th>Status</th><th>{t('Tempo')}</th><th>Backend/servidor</th></tr></thead>
            <tbody>
              {entries.map((e, i) => {
                const x = e.fields ?? {};
                const tcp = !x.method;
                return [
                  <tr key={i} onClick={() => setOpen(open === i ? null : i)} style={{ cursor: 'pointer' }}>
                    <td style={{ whiteSpace: 'nowrap' }}>{fmt.time(e.ts, true)}</td>
                    <td>{e.node || '—'}</td>
                    <td>{x.client}</td>
                    <td>{x.fe}</td>
                    <td className="mono">{tcp ? <span className="muted">TCP · {x.bytes_in}↑ {x.bytes_out}↓</span> : <><b>{x.method}</b> {x.host}{x.path}</>}</td>
                    <td>{tcp ? <span className="badge b-muted">{x.term}</span> : <span className={`badge ${statusClass(x.status)}`}>{x.status}</span>}</td>
                    <td>{(tcp ? x.t_total : x.t_total) ?? '—'} ms</td>
                    <td>{x.be}/{x.srv}</td>
                  </tr>,
                  open === i && <tr key={`${i}-d`}><td colSpan={8}><pre>{e.line}</pre></td></tr>,
                ];
              })}
            </tbody>
          </table></div>
        )}
      </div>
    </>
  );
}
