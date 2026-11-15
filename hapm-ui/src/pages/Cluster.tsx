import { useEffect, useState } from 'react';
import { api } from '../api';
import { Fields, withDefaults } from '../form';
import { backendSchema, clusterSchema, frontendSchema } from '../schemas';
import { ago, Badge, daysLeft, fmtDate, useAction, useLoad, useToast } from '../ui';

const TABS: [string, string][] = [
  ['', 'Visão geral'], ['frontends', 'Listeners'], ['backends', 'Backends'], ['certificates', 'Certificados'],
  ['publish', 'Publicar'], ['history', 'Histórico'], ['settings', 'Configurações'],
];

export function ClusterPage({ cid, tab, sub }: { cid: string; tab: string; sub?: string }) {
  const { data: cluster, reload } = useLoad<any>(() => api('GET', `/clusters/${cid}`), [cid]);
  if (!cluster) return <p className="muted">carregando...</p>;
  return (
    <>
      <div className="top">
        <h2>{cluster.name}</h2>
        <span className="badge b-muted">{cluster.settings.mode}</span>
      </div>
      <div className="tabs">
        {TABS.map(([k, l]) => (
          <a key={k} href={`#/c/${cid}${k ? `/${k}` : ''}`} className={tab === k ? 'active' : ''}>{l}</a>
        ))}
      </div>
      {tab === '' && <Overview cluster={cluster} />}
      {(tab === 'frontends' || tab === 'backends') && <Objects key={tab + (sub ?? '')} cluster={cluster} kind={tab} sub={sub} />}
      {tab === 'certificates' && <Certificates cluster={cluster} />}
      {tab === 'publish' && <Publish cluster={cluster} />}
      {tab === 'history' && <History cluster={cluster} />}
      {tab === 'settings' && <Settings cluster={cluster} onSaved={reload} />}
    </>
  );
}

// ------------------------------------------------------------------ visão geral
function Overview({ cluster }: { cluster: any }) {
  const { data, reload } = useLoad<[any[], any[]]>(
    () => Promise.all([api('GET', `/clusters/${cluster.id}/nodes`), api('GET', `/clusters/${cluster.id}/deployments?limit=1`)]),
    [cluster.id], 5000);
  const run = useAction();
  const toast = useToast();
  if (!data) return <p className="muted">carregando...</p>;
  const [nodes, deps] = data;
  const online = nodes.filter((n) => n.online);
  const masters = online.filter((n) => n.vrrpState === 'MASTER');
  const keep = cluster.settings.mode !== 'aws-nlb';
  const latest = deps[0];
  const vips = [...(cluster.settings.keepalived?.vips ?? []), cluster.settings.aws?.privateVip].filter(Boolean);

  const editPrio = (n: any) => run(async () => {
    const v = prompt(`Prioridade VRRP de ${n.name} (1-254, vazio = automática)`, n.settings?.priority ?? '');
    if (v === null) return;
    await api('PUT', `/clusters/${cluster.id}/nodes/${n.id}`, { settings: { ...n.settings, priority: v === '' ? null : Number(v) } });
    toast('prioridade salva — publique para aplicar no keepalived');
    reload();
  });
  const del = (n: any) => run(async () => {
    if (!confirm(`Remover o nó ${n.name}? (o agente o registrará de novo se continuar rodando)`)) return;
    await api('DELETE', `/clusters/${cluster.id}/nodes/${n.id}`);
    reload();
  });

  return (
    <>
      <div className="grid" style={{ marginBottom: 16 }}>
        <div className="stat"><div className="k">Nós online</div><div className="v">{online.length}/{nodes.length}</div></div>
        {keep && (
          <div className="stat">
            <div className="k">MASTER (detém o VIP)</div>
            <div className={`v ${masters.length === 1 ? '' : 'b-err'}`}>
              {masters.length === 1 ? masters[0].name : masters.length ? `SPLIT-BRAIN: ${masters.map((m) => m.name).join(', ')}` : 'nenhum'}
            </div>
          </div>
        )}
        {keep && <div className="stat"><div className="k">VIPs</div><div className="v" style={{ fontSize: 15 }}>{vips.join(', ') || '—'}</div></div>}
        <div className="stat"><div className="k">Versão publicada</div><div className="v">{latest ? <>v{latest.version} <Badge s={latest.status} /></> : '—'}</div></div>
      </div>
      {latest?.error && <div className="panel err">Última publicação: {latest.error}</div>}
      <div className="panel">
        <h3>Nós</h3>
        {nodes.length ? (
          <table>
            <thead>
              <tr>
                <th>Nó</th><th>Endereço</th><th>Estado</th>{keep && <th>VRRP</th>}{keep && <th>Prioridade</th>}
                <th>Versão</th><th>Última aplicação</th><th>HAProxy</th><th />
              </tr>
            </thead>
            <tbody>
              {nodes.map((n) => (
                <tr key={n.id}>
                  <td><b>{n.name}</b></td>
                  <td>{n.address || '—'}</td>
                  <td>{n.online ? <span className="badge b-ok">online</span> : <span className="badge b-err">offline ({ago(n.lastSeen)})</span>}</td>
                  {keep && <td><Badge s={n.vrrpState || 'UNKNOWN'} /></td>}
                  {keep && <td>{n.priority ?? '—'} <button className="small" onClick={editPrio(n)}>editar</button></td>}
                  <td>v{n.appliedVersion}{n.desired?.hold && <div className="muted">{n.desired.reason}</div>}</td>
                  <td>
                    {n.lastApply ? <><Badge s={n.lastApply.ok ? 'succeeded' : 'failed'} /> v{n.lastApply.version}</> : '—'}
                    {n.lastApply?.error && <div className="err" style={{ fontSize: 12, maxWidth: 420 }}>{n.lastApply.error.slice(0, 400)}</div>}
                  </td>
                  <td className="muted">{n.haproxyVersion || '—'}</td>
                  <td><button className="small danger" onClick={del(n)}>remover</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <p className="muted">Nenhum nó registrado. Instale o agente nos servidores HAProxy (instruções abaixo).</p>}
      </div>
      <details className="panel" open={!nodes.length}>
        <summary><b>Adicionar nós (instalar o agente)</b></summary>
        <p>Em cada servidor, configure <code>/etc/hapm/agent.env</code> (ou use o playbook Ansible / user-data do Terraform):</p>
        <pre>{`HAPM_MANAGER_URL=${location.origin}\nHAPM_JOIN_TOKEN=${cluster.joinToken}\nHAPM_NODE_NAME=$(hostname -s)\nHAPM_NODE_INTERFACE=${cluster.settings.keepalived?.interface ?? 'eth0'}`}</pre>
        <p className="muted">O agente se registra sozinho no primeiro heartbeat. Trate o token como segredo (pode ser rotacionado em Configurações).</p>
      </details>
    </>
  );
}

// ------------------------------------------------------------------ listeners / backends
function Objects({ cluster, kind, sub }: { cluster: any; kind: string; sub?: string }) {
  const isFe = kind === 'frontends';
  const label = isFe ? 'listener' : 'backend';
  const { data } = useLoad<[any[], any[]]>(
    () => Promise.all([api('GET', `/clusters/${cluster.id}/${kind}`), api('GET', `/clusters/${cluster.id}/backends`)]), [cluster.id, kind]);
  const [form, setForm] = useState<any>(null);
  const run = useAction();
  const toast = useToast();
  const schema = isFe ? frontendSchema : backendSchema;
  useEffect(() => {
    if (!data || !sub) return;
    setForm(sub === 'new' ? { mode: 'http' } : data[0].find((i) => i.id === sub) ?? null);
  }, [data, sub]);
  if (!data) return <p className="muted">carregando...</p>;
  const [items, backends] = data;
  const back = () => (location.hash = `#/c/${cluster.id}/${kind}`);

  if (sub) {
    if (!form) return <p>não encontrado</p>;
    const save = run(async () => {
      const body = withDefaults(schema, form);
      if (sub === 'new') await api('POST', `/clusters/${cluster.id}/${kind}`, body);
      else await api('PUT', `/clusters/${cluster.id}/${kind}/${sub}`, body);
      toast('salvo — vá em "Publicar" para aplicar nos nós');
      back();
    });
    const del = run(async () => {
      if (!confirm(`Excluir ${label} ${form.name}?`)) return;
      await api('DELETE', `/clusters/${cluster.id}/${kind}/${sub}`);
      back();
    });
    return (
      <div className="panel">
        <h3>{sub === 'new' ? `Novo ${label}` : `${label}: ${form.name}`}</h3>
        <Fields rows={schema} value={form} onChange={setForm} ctx={{ backends }} />
        <div className="actions">
          <button className="primary" onClick={save}>Salvar</button>
          <a className="btn" href={`#/c/${cluster.id}/${kind}`}>Cancelar</a>
          {sub !== 'new' && <button className="danger" onClick={del}>Excluir</button>}
        </div>
      </div>
    );
  }
  const cols = isFe ? ['Nome', 'Modo', 'Binds', 'Regras', 'Backend padrão'] : ['Nome', 'Modo', 'Balance', 'Servidores', 'Check'];
  const summary = (i: any) => isFe
    ? [i.binds.map((b: any) => `${b.address}:${b.port}${b.ssl ? ' (TLS)' : ''}`).join(', '), `${i.rules.length} regra(s)`, i.defaultBackend || '—']
    : [i.balance, i.servers.map((s: any) => `${s.address}:${s.port}${s.backup ? ' (backup)' : ''}`).join(', '), i.healthCheck.type];
  return (
    <div className="panel">
      <div className="top">
        <h3 style={{ margin: 0 }}>{isFe ? 'Listeners (frontends)' : 'Backends'}</h3>
        <a className="btn" href={`#/c/${cluster.id}/${kind}/new`}>+ Novo {label}</a>
      </div>
      {items.length ? (
        <table>
          <thead><tr>{cols.map((c) => <th key={c}>{c}</th>)}</tr></thead>
          <tbody>
            {items.map((i) => (
              <tr key={i.id}>
                <td><a href={`#/c/${cluster.id}/${kind}/${i.id}`}>{i.name}</a>{i.enabled === false && <span className="muted"> (inativo)</span>}</td>
                <td>{i.mode}</td>
                {summary(i).map((s, j) => <td key={j}>{s}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      ) : <p className="muted">{isFe ? 'Nenhum listener. Crie backends primeiro e depois um listener apontando para eles.' : 'Nenhum backend.'}</p>}
    </div>
  );
}

// ------------------------------------------------------------------ certificados
function Certificates({ cluster }: { cluster: any }) {
  const { data: certs, reload } = useLoad<any[]>(() => api('GET', `/clusters/${cluster.id}/certificates`), [cluster.id], 4000);
  const [upload, setUpload] = useState<any>({});
  const [acme, setAcme] = useState<any>({});
  const run = useAction();
  const toast = useToast();
  const doUpload = run(async () => {
    await api('POST', `/clusters/${cluster.id}/certificates`, { ...upload, source: 'upload' });
    toast('certificado salvo — publique para aplicar');
    setUpload({});
    reload();
  });
  const doAcme = run(async () => {
    await api('POST', `/clusters/${cluster.id}/certificates`, { name: acme.name, source: 'acme', acme: { domains: acme.domains, email: acme.email } });
    toast('emissão iniciada — acompanhe o status');
    reload();
  });
  const issue = (c: any) => run(async () => {
    await api('POST', `/clusters/${cluster.id}/certificates/${c.id}/issue`);
    toast('emissão iniciada');
    reload();
  });
  const del = (c: any) => run(async () => {
    if (!confirm(`Excluir certificado ${c.name}?`)) return;
    await api('DELETE', `/clusters/${cluster.id}/certificates/${c.id}`);
    reload();
  });
  if (!certs) return <p className="muted">carregando...</p>;
  return (
    <>
      <div className="panel">
        <h3>Certificados</h3>
        {certs.length ? (
          <table>
            <thead><tr><th>Nome</th><th>Domínios</th><th>Expira</th><th>Origem</th><th>Status</th><th /></tr></thead>
            <tbody>
              {certs.map((c) => {
                const d = daysLeft(c.meta?.notAfter);
                return (
                  <tr key={c.id}>
                    <td><b>{c.name}</b></td>
                    <td>{(c.meta?.domains ?? c.acme?.domains ?? []).join(', ')}</td>
                    <td>{d == null ? '—' : <span className={d < 14 ? 'b-err' : d < 30 ? 'b-warn' : ''}>{fmtDate(c.meta.notAfter)} ({d} dias)</span>}</td>
                    <td>{c.source === 'acme' ? 'ACME' : 'upload'}</td>
                    <td>
                      {c.source === 'acme' ? <Badge s={c.acme?.status ?? 'pending'} /> : c.hasKey ? <Badge s="succeeded" text="ok" /> : '—'}
                      {c.acme?.error && <div className="err" style={{ fontSize: 12 }}>{c.acme.error}</div>}
                    </td>
                    <td>
                      <div className="actions">
                        {c.source === 'acme' && <button className="small" onClick={issue(c)}>emitir/renovar</button>}
                        <button className="small danger" onClick={del(c)}>excluir</button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        ) : <p className="muted">Nenhum certificado.</p>}
        <p className="muted">Todos os certificados vão para um crt-list: o HAProxy escolhe pelo SNI. O certificado padrão (cliente sem SNI) é definido em Configurações.</p>
      </div>
      <details className="panel">
        <summary><b>Emitir com Let's Encrypt (HTTP-01)</b></summary>
        <p className="muted">Requer "ACME → manager visto pelo HAProxy" em Configurações, um listener HTTP na porta 80 com ACME ativo e a configuração já publicada.</p>
        <Fields rows={[[{ key: 'name', label: 'Nome' }, { key: 'email', label: 'E-mail (opcional)' }],
          { key: 'domains', label: 'Domínios (o DNS deve apontar para o VIP/NLB)', type: 'tokens', placeholder: 'app.exemplo.com www.exemplo.com' }]}
          value={acme} onChange={setAcme} ctx={{}} />
        <button className="primary" onClick={doAcme}>Emitir</button>
      </details>
      <details className="panel">
        <summary><b>Enviar certificado (PEM)</b></summary>
        <Fields rows={[{ key: 'name', label: 'Nome' }, { key: 'pem', label: 'PEM (certificado + cadeia + chave privada)', type: 'textarea', mono: true, rows: 8 }]}
          value={upload} onChange={setUpload} ctx={{}} />
        <button className="primary" onClick={doUpload}>Salvar</button>
      </details>
    </>
  );
}

// ------------------------------------------------------------------ publicar
function Publish({ cluster }: { cluster: any }) {
  const { data: p } = useLoad<any>(() => api('GET', `/clusters/${cluster.id}/preview`), [cluster.id]);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const run = useAction();
  const toast = useToast();
  if (!p) return <p className="muted">Gerando configuração...</p>;
  if (p.errors)
    return (
      <div className="panel">
        <h3 className="b-err">A configuração tem problemas</h3>
        <ul>{p.errors.map((e: string) => <li key={e} className="err">{e}</li>)}</ul>
      </div>
    );
  const publish = run(async () => {
    setBusy(true);
    try {
      const d = await api('POST', `/clusters/${cluster.id}/deployments`, { note });
      toast(`v${d.version} publicada — acompanhando rollout`);
      location.hash = `#/c/${cluster.id}`;
    } finally {
      setBusy(false);
    }
  });
  const blocked = p.check.available && !p.check.ok;
  return (
    <>
      <div className="panel">
        <h3>Publicar v{p.version}</h3>
        <p>
          {p.check.available
            ? p.check.ok ? <><Badge s="succeeded" /> haproxy -c: configuração válida</> : <><Badge s="failed" /> haproxy -c falhou</>
            : <span className="muted">haproxy não instalado no manager: a validação acontece em cada nó antes do reload.</span>}
        </p>
        {blocked && <pre className="err">{p.check.output}</pre>}
        {!p.changed && <p className="muted">Nada mudou em relação à v{p.currentVersion} (publicar mesmo assim reaplica certificados/keepalived).</p>}
        <div className="row">
          <input placeholder="o que mudou? (opcional)" value={note} onChange={(e) => setNote(e.target.value)} />
          <button className="primary" style={{ flex: '0 0 auto' }} disabled={blocked || busy} onClick={publish}>Publicar</button>
        </div>
        <p className="muted">
          {cluster.settings.mode === 'aws-nlb'
            ? 'Rollout: um nó por vez; cada nó valida, recarrega sem derrubar conexões e faz rollback sozinho se falhar.'
            : 'Rollout: nós BACKUP primeiro (canário) e o MASTER por último. Se algum nó falhar, todos voltam para a última versão boa.'}
        </p>
      </div>
      {p.diff && p.changed && (
        <>
          <h3>Diferenças em relação à v{p.currentVersion}</h3>
          <div className="diff panel" style={{ padding: 8, maxHeight: 520, overflow: 'auto' }}>
            {p.diff.map((d: any, i: number) => (
              <div key={i} className={d.op === '+' ? 'add' : d.op === '-' ? 'del' : d.op === '@' ? 'gap' : ''}>{`${d.op === '@' ? '' : d.op} ${d.line}`}</div>
            ))}
          </div>
        </>
      )}
      <details className="panel"><summary>haproxy.cfg completo</summary><pre>{p.haproxyCfg}</pre></details>
      {Object.keys(p.keepalived).length > 0 && (
        <details className="panel">
          <summary>keepalived.conf por nó</summary>
          {Object.entries(p.keepalived).map(([n, conf]) => (
            <div key={n}><h4>{n}</h4><pre>{conf as string}</pre></div>
          ))}
        </details>
      )}
    </>
  );
}

// ------------------------------------------------------------------ histórico
function History({ cluster }: { cluster: any }) {
  const { data: deps } = useLoad<any[]>(() => api('GET', `/clusters/${cluster.id}/deployments`), [cluster.id], 5000);
  const [shown, setShown] = useState<any>(null);
  const run = useAction();
  const toast = useToast();
  const rollback = (d: any) => run(async () => {
    if (!confirm(`Criar uma nova versão com a configuração da v${d.version}?`)) return;
    const n = await api('POST', `/clusters/${cluster.id}/deployments/${d.id}/rollback`);
    toast(`rollback publicado como v${n.version}`);
    location.hash = `#/c/${cluster.id}`;
  });
  const show = (d: any) => run(async () => setShown(await api('GET', `/clusters/${cluster.id}/deployments/${d.id}`)));
  if (!deps) return <p className="muted">carregando...</p>;
  return (
    <>
      <div className="panel">
        <h3>Histórico de publicações</h3>
        {deps.length ? (
          <table>
            <thead><tr><th>Versão</th><th>Status</th><th>Nota</th><th>Por</th><th>Quando</th><th /></tr></thead>
            <tbody>
              {deps.map((d) => (
                <tr key={d.id}>
                  <td><b>v{d.version}</b></td>
                  <td><Badge s={d.status} />{d.error && <div className="err" style={{ fontSize: 12 }}>{d.error.slice(0, 300)}</div>}</td>
                  <td>{d.note || ''}</td>
                  <td>{d.createdBy || ''}</td>
                  <td>{fmtDate(d.createdAt)}</td>
                  <td>
                    <div className="actions">
                      <button className="small" onClick={show(d)}>ver config</button>
                      <button className="small" onClick={rollback(d)}>rollback</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <p className="muted">Nada publicado ainda.</p>}
      </div>
      {shown && (
        <div className="panel">
          <div className="top"><h3 style={{ margin: 0 }}>haproxy.cfg da v{shown.version}</h3><button className="small" onClick={() => setShown(null)}>fechar</button></div>
          <pre>{shown.haproxyCfg}</pre>
        </div>
      )}
    </>
  );
}

// ------------------------------------------------------------------ configurações
function Settings({ cluster, onSaved }: { cluster: any; onSaved: () => void }) {
  const [form, setForm] = useState<any>({ name: cluster.name, settings: cluster.settings });
  const run = useAction();
  const toast = useToast();
  const save = run(async () => {
    await api('PUT', `/clusters/${cluster.id}`, withDefaults(clusterSchema, form));
    toast('salvo — publique para aplicar');
    onSaved();
  });
  const rotate = run(async () => {
    if (!confirm('Rotacionar o token? Todos os agentes precisarão do novo token.')) return;
    const r = await api('POST', `/clusters/${cluster.id}/rotate-token`);
    prompt('Novo token de join:', r.joinToken);
  });
  const del = run(async () => {
    if (prompt(`Digite o nome do cluster (${cluster.name}) para excluir:`) !== cluster.name) return;
    await api('DELETE', `/clusters/${cluster.id}`);
    location.hash = '#/';
  });
  return (
    <>
      <div className="panel">
        <Fields rows={clusterSchema} value={form} onChange={setForm} ctx={{}} />
        <div className="actions"><button className="primary" onClick={save}>Salvar</button></div>
      </div>
      <div className="panel">
        <h3>Zona de perigo</h3>
        <div className="actions">
          <button onClick={rotate}>Rotacionar token de join</button>
          <button className="danger" onClick={del}>Excluir cluster</button>
        </div>
      </div>
    </>
  );
}
