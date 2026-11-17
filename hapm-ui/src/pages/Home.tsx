import { useState } from 'react';
import { api } from '../api';
import { Fields, withDefaults } from '../form';
import { clusterSchema } from '../schemas';
import { Badge, useAction, useLoad, useToast } from '../ui';
import { usePerm } from '../session';
import { useT } from '../prefs';

export function Home() {
  const t = useT();
  const { data: clusters } = useLoad<any[]>(() => api('GET', '/clusters'), [], 10000);
  const [form, setForm] = useState<any>({ settings: {} });
  const run = useAction();
  const toast = useToast();
  const { isAdmin } = usePerm();
  const create = run(async () => {
    const c = await api('POST', '/clusters', withDefaults(clusterSchema, form));
    toast(t('cluster criado'));
    location.hash = `#/c/${c.id}`;
  });
  if (!clusters) return <p className="muted">{t('carregando...')}</p>;
  return (
    <>
      <div className="top"><h2>{t('Clusters')}</h2></div>
      <div className="panel">
        {clusters.length ? (
          <table>
            <thead><tr><th>{t('Nome')}</th><th>{t('Topologia')}</th><th>{t('Nós online')}</th><th>MASTER</th><th>{t('Última publicação')}</th></tr></thead>
            <tbody>
              {clusters.map((c) => (
                <tr key={c.id}>
                  <td><a href={`#/c/${c.id}`}>{c.name}</a></td>
                  <td>{c.mode}</td>
                  <td>{c.online}/{c.nodes}</td>
                  <td>{c.masters.join(', ') || '—'}</td>
                  <td>{c.latestDeployment ? <>v{c.latestDeployment.version} <Badge s={c.latestDeployment.status} /></> : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <p className="muted">{t('Nenhum cluster ainda. Crie o primeiro abaixo.')}</p>}
      </div>
      {isAdmin && <details className="panel" open={!clusters.length}>
        <summary><b>{t('Novo cluster')}</b></summary>
        <Fields rows={clusterSchema} value={form} onChange={setForm} ctx={{}} />
        <button className="primary" onClick={create}>{t('Criar cluster')}</button>
      </details>}
    </>
  );
}
