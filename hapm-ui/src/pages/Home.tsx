import { useState } from 'react';
import { api } from '../api';
import { Fields, withDefaults } from '../form';
import { clusterSchema } from '../schemas';
import { Badge, useAction, useLoad, useToast } from '../ui';
import { usePerm } from '../session';

export function Home() {
  const { data: clusters } = useLoad<any[]>(() => api('GET', '/clusters'), [], 10000);
  const [form, setForm] = useState<any>({ settings: {} });
  const run = useAction();
  const toast = useToast();
  const create = run(async () => {
    const c = await api('POST', '/clusters', withDefaults(clusterSchema, form));
    toast('cluster criado');
    location.hash = `#/c/${c.id}`;
  });
  const { isAdmin } = usePerm();
  if (!clusters) return <p className="muted">carregando...</p>;
  return (
    <>
      <div className="top"><h2>Clusters</h2></div>
      <div className="panel">
        {clusters.length ? (
          <table>
            <thead><tr><th>Nome</th><th>Topologia</th><th>Nós online</th><th>MASTER</th><th>Última publicação</th></tr></thead>
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
        ) : <p className="muted">Nenhum cluster ainda. Crie o primeiro abaixo.</p>}
      </div>
      {isAdmin && <details className="panel" open={!clusters.length}>
        <summary><b>Novo cluster</b></summary>
        <Fields rows={clusterSchema} value={form} onChange={setForm} ctx={{}} />
        <button className="primary" onClick={create}>Criar cluster</button>
      </details>}
    </>
  );
}
