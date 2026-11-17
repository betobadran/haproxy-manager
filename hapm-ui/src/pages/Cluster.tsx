import { useEffect, useState } from 'react';
import { api } from '../api';
import { Fields, withDefaults } from '../form';
import { backendSchema, clusterSchema, frontendSchema } from '../schemas';
import { Badge, daysLeft, useAction, useLoad, useToast } from '../ui';
import { usePerm } from '../session';
import { useFmt, useT, useTs } from '../prefs';
import { ClusterAlerts } from './Alerts';
import { MetricsTab } from './Metrics';

const TABS: [string, string][] = [
  ['', 'Visão geral'], ['metrics', 'Métricas'], ['frontends', 'Listeners'], ['backends', 'Backends'], ['certificates', 'Certificados'],
  ['publish', 'Publicar'], ['approvals', 'Aprovações'], ['history', 'Histórico'], ['settings', 'Configurações'],
];

function Loading() {
  const t = useT();
  return <p className="muted">{t('carregando...')}</p>;
}

export function ClusterPage({ cid, tab, sub }: { cid: string; tab: string; sub?: string }) {
  const t = useT();
  const { data: cluster, reload } = useLoad<any>(() => api('GET', `/clusters/${cid}`), [cid]);
  if (!cluster) return <Loading />;
  return (
    <>
      <div className="top">
        <h2>{cluster.name}</h2>
        <span className="badge b-muted">{cluster.settings.mode}</span>
        {cluster.settings.approval?.required && <span className="badge b-warn">{t('publicação com aprovação')}</span>}
      </div>
      <div className="tabs">
        {TABS.filter(([k]) => k !== 'approvals' || cluster.settings.approval?.required || cluster.pendingRequests > 0).map(([k, l]) => (
          <a key={k} href={`#/c/${cid}${k ? `/${k}` : ''}`} className={tab === k ? 'active' : ''}>
            {t(l)}{k === 'approvals' && cluster.pendingRequests > 0 && <span className="count">{cluster.pendingRequests}</span>}
          </a>
        ))}
      </div>
      {tab === '' && <Overview cluster={cluster} />}
      {tab === 'metrics' && <MetricsTab cluster={cluster} />}
      {(tab === 'frontends' || tab === 'backends') && <Objects key={tab + (sub ?? '')} cluster={cluster} kind={tab} sub={sub} />}
      {tab === 'certificates' && <Certificates cluster={cluster} />}
      {tab === 'publish' && <Publish cluster={cluster} />}
      {tab === 'approvals' && <Approvals key={sub ?? ''} cluster={cluster} sub={sub} onChange={reload} />}
      {tab === 'history' && <History cluster={cluster} />}
      {tab === 'settings' && <Settings cluster={cluster} onSaved={reload} />}
    </>
  );
}

function Diff({ lines, max = 520 }: { lines: any[]; max?: number }) {
  return (
    <div className="diff panel" style={{ padding: 8, maxHeight: max, overflow: 'auto' }}>
      {lines.map((d: any, i: number) => (
        <div key={i} className={d.op === '+' ? 'add' : d.op === '-' ? 'del' : d.op === '@' ? 'gap' : ''}>{`${d.op === '@' ? '' : d.op} ${d.line}`}</div>
      ))}
    </div>
  );
}

// ------------------------------------------------------------------ visão geral
function Overview({ cluster }: { cluster: any }) {
  const t = useT();
  const ts = useTs();
  const fmt = useFmt();
  const { data, reload } = useLoad<[any[], any[]]>(
    () => Promise.all([api('GET', `/clusters/${cluster.id}/nodes`), api('GET', `/clusters/${cluster.id}/deployments?limit=1`)]),
    [cluster.id], 5000);
  const run = useAction();
  const toast = useToast();
  const { canEdit, isAdmin } = usePerm();
  if (!data) return <Loading />;
  const [nodes, deps] = data;
  const online = nodes.filter((n) => n.online);
  const masters = online.filter((n) => n.vrrpState === 'MASTER');
  const keep = cluster.settings.mode !== 'aws-nlb';
  const latest = deps[0];
  const aws = cluster.settings.mode === 'aws-eip' ? cluster.settings.aws ?? {} : null;
  const vips = [...(cluster.settings.keepalived?.vips ?? []), aws?.privateVip, aws?.routeVip].filter(Boolean);

  const editPrio = (n: any) => run(async () => {
    const v = prompt(t('Prioridade VRRP de {name} (1-254, vazio = automática)', { name: n.name }), n.settings?.priority ?? '');
    if (v === null) return;
    await api('PUT', `/clusters/${cluster.id}/nodes/${n.id}`, { settings: { ...n.settings, priority: v === '' ? null : Number(v) } });
    toast(t('prioridade salva — publique para aplicar no keepalived'));
    reload();
  });
  const editEni = (n: any) => run(async () => {
    const v = prompt(t('ENI de {name} que recebe os IPs no failover (vazio = ENI da interface do VRRP, descoberta automaticamente)', { name: n.name }), n.settings?.awsEniId ?? '');
    if (v === null) return;
    await api('PUT', `/clusters/${cluster.id}/nodes/${n.id}`, { settings: { ...n.settings, awsEniId: v.trim() } });
    toast(t('ENI salva — publique para aplicar no keepalived'));
    reload();
  });
  const del = (n: any) => run(async () => {
    if (!confirm(t('Remover o nó {name}? (o agente o registrará de novo se continuar rodando)', { name: n.name }))) return;
    await api('DELETE', `/clusters/${cluster.id}/nodes/${n.id}`);
    reload();
  });

  return (
    <>
      <ClusterAlerts cid={cluster.id} />
      <div className="grid" style={{ marginBottom: 16 }}>
        <div className="stat"><div className="k">{t('Nós online')}</div><div className="v">{online.length}/{nodes.length}</div></div>
        {keep && (
          <div className="stat">
            <div className="k">{t('MASTER (detém o VIP)')}</div>
            <div className={`v ${masters.length === 1 ? '' : 'b-err'}`}>
              {masters.length === 1 ? masters[0].name : masters.length ? `SPLIT-BRAIN: ${masters.map((m) => m.name).join(', ')}` : t('nenhum')}
            </div>
          </div>
        )}
        {keep && <div className="stat"><div className="k">VIPs</div><div className="v" style={{ fontSize: 15 }}>{vips.join(', ') || '—'}</div></div>}
        <div className="stat"><div className="k">{t('Versão publicada')}</div><div className="v">{latest ? <>v{latest.version} <Badge s={latest.status} /></> : '—'}</div></div>
      </div>
      {aws && (
        <div className="panel">
          <h3>{t('Failover de IP na AWS')}</h3>
          <ul className="checks">
            {aws.privateVip && <li>{t('IP secundário {ip} movido entre as ENIs dos nós (nós na mesma subnet)', { ip: aws.privateVip })}</li>}
            {aws.routeVip && <li>{t('IP de overlay {ip}: rota /32 nas tabelas {rtbs} aponta para a ENI do MASTER (nós em subnets diferentes)', { ip: aws.routeVip, rtbs: (aws.routeTableIds ?? []).join(', ') })}</li>}
            {aws.eipAllocationId && <li>{t('Elastic IP {id} associado ao MASTER', { id: aws.eipAllocationId })}</li>}
          </ul>
          <p className="muted">{t('Cada nó valida periodicamente permissões IAM (--dry-run), ENI, subnet e tabelas de rotas; o resultado aparece na coluna "Failover AWS".')}</p>
        </div>
      )}
      {latest?.error && <div className={`panel ${latest.status === 'failed' ? 'err' : 'muted'}`}>{t('Última publicação')}: {ts(latest.error)}</div>}
      <div className="panel">
        <h3>{t('Nós')}</h3>
        {nodes.length ? (
          <table>
            <thead>
              <tr>
                <th>{t('Nó')}</th><th>{t('Endereço')}</th><th>{t('Estado')}</th>{keep && <th>VRRP</th>}{keep && <th>{t('Prioridade')}</th>}
                <th>{t('Versão')}</th><th>{t('Última aplicação')}</th>{aws && <th>{t('Failover AWS')}</th>}<th>HAProxy</th><th />
              </tr>
            </thead>
            <tbody>
              {nodes.map((n) => (
                <tr key={n.id}>
                  <td><b>{n.name}</b></td>
                  <td>{n.address || '—'}</td>
                  <td>{n.online ? <span className="badge b-ok">online</span> : <span className="badge b-err">offline ({fmt.ago(n.lastSeen)})</span>}</td>
                  {keep && <td><Badge s={n.vrrpState || 'UNKNOWN'} /></td>}
                  {keep && <td>{n.priority ?? '—'} {canEdit && <button className="small" onClick={editPrio(n)}>{t('editar')}</button>}</td>}
                  <td>v{n.appliedVersion}{n.desired?.hold && <div className="muted">{ts(n.desired.reason)}</div>}</td>
                  <td>
                    {n.lastApply ? <><Badge s={n.lastApply.ok ? 'succeeded' : 'failed'} /> v{n.lastApply.version}</> : '—'}
                    {n.lastApply?.error && <div className="err" style={{ fontSize: 12, maxWidth: 420 }}>{ts(n.lastApply.error).slice(0, 400)}</div>}
                  </td>
                  {aws && (
                    <td>
                      <div className="muted" style={{ fontSize: 12 }}>ENI: {n.settings?.awsEniId || t('automática')} {canEdit && <button className="small" onClick={editEni(n)}>{t('editar')}</button>}</div>
                      {n.aws ? (
                        <details>
                          <summary><Badge s={n.aws.ok ? 'succeeded' : 'failed'} text={n.aws.ok ? 'preflight ok' : 'preflight com problemas'} /></summary>
                          <ul className="checks">
                            {n.aws.lines.map((l: string, i: number) => {
                              const [lvl, ...msg] = l.split('\t');
                              return <li key={i} className={lvl === 'FAIL' ? 'err' : lvl === 'WARN' ? 'b-warn' : ''}><b>{lvl}</b> {msg.join(' ')}</li>;
                            })}
                          </ul>
                          <div className="muted" style={{ fontSize: 11 }}>{fmt.date(n.aws.at)}</div>
                        </details>
                      ) : <span className="muted">{t('aguardando preflight')}</span>}
                    </td>
                  )}
                  <td className="muted">{n.haproxyVersion || '—'}</td>
                  <td>{isAdmin && <button className="small danger" onClick={del(n)}>{t('remover')}</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <p className="muted">{t('Nenhum nó registrado. Instale o agente nos servidores HAProxy (instruções abaixo).')}</p>}
      </div>
      {isAdmin && <details className="panel" open={!nodes.length}>
        <summary><b>{t('Adicionar nós (instalar o agente)')}</b></summary>
        <p>{t('Com o bundle de instalação (deploy/bash), em cada servidor:')}</p>
        <pre>{`HAPM_JOIN_TOKEN='${cluster.joinToken}' \\\n  sudo -E ./install-node.sh --manager-url ${location.origin} --interface ${cluster.settings.keepalived?.interface ?? 'eth0'}${cluster.settings.mode === 'aws-nlb' ? ' --no-keepalived' : ''}`}</pre>
        <p className="muted">{t('Ou use deploy/bash/deploy.sh para instalar em vários servidores via SSH. O agente se registra sozinho no primeiro heartbeat. Trate o token como segredo (pode ser rotacionado em Configurações).')}</p>
      </details>}
    </>
  );
}

// ------------------------------------------------------------------ listeners / backends
function Objects({ cluster, kind, sub }: { cluster: any; kind: string; sub?: string }) {
  const t = useT();
  const isFe = kind === 'frontends';
  const label = isFe ? 'listener' : 'backend';
  const { data } = useLoad<[any[], any[]]>(
    () => Promise.all([api('GET', `/clusters/${cluster.id}/${kind}`), api('GET', `/clusters/${cluster.id}/backends`)]), [cluster.id, kind]);
  const [form, setForm] = useState<any>(null);
  const run = useAction();
  const toast = useToast();
  const schema = isFe ? frontendSchema : backendSchema;
  const { canEdit } = usePerm();
  useEffect(() => {
    if (!data || !sub) return;
    setForm(sub === 'new' ? { mode: 'http' } : data[0].find((i) => i.id === sub) ?? null);
  }, [data, sub]);
  if (!data) return <Loading />;
  const [items, backends] = data;
  const back = () => (location.hash = `#/c/${cluster.id}/${kind}`);

  if (sub) {
    if (!form) return <p>{t('não encontrado')}</p>;
    const save = run(async () => {
      const body = withDefaults(schema, form);
      if (sub === 'new') await api('POST', `/clusters/${cluster.id}/${kind}`, body);
      else await api('PUT', `/clusters/${cluster.id}/${kind}/${sub}`, body);
      toast(t('salvo — vá em "Publicar" para aplicar nos nós'));
      back();
    });
    const del = run(async () => {
      if (!confirm(t('Excluir {kind} {name}?', { kind: label, name: form.name }))) return;
      await api('DELETE', `/clusters/${cluster.id}/${kind}/${sub}`);
      back();
    });
    return (
      <div className="panel">
        <h3>{sub === 'new' ? (isFe ? t('Novo listener') : t('Novo backend')) : `${label}: ${form.name}`}</h3>
        <Fields rows={schema} value={form} onChange={setForm} ctx={{ backends }} />
        <div className="actions">
          {canEdit && <button className="primary" onClick={save}>{t('Salvar')}</button>}
          <a className="btn" href={`#/c/${cluster.id}/${kind}`}>{canEdit ? t('Cancelar') : t('Voltar')}</a>
          {canEdit && sub !== 'new' && <button className="danger" onClick={del}>{t('Excluir')}</button>}
        </div>
      </div>
    );
  }
  const cols = isFe ? ['Nome', 'Modo', 'Binds', 'Regras', 'Backend padrão'] : ['Nome', 'Modo', 'Balanceamento', 'Servidores', 'Check'];
  const summary = (i: any) => isFe
    ? [i.binds.map((b: any) => `${b.address}:${b.port}${b.ssl ? ' (TLS)' : ''}`).join(', '), t('{n} regra(s)', { n: i.rules.length }), i.defaultBackend || '—']
    : [i.balance, i.servers.map((s: any) => `${s.address}:${s.port}${s.backup ? ' (backup)' : ''}`).join(', '), i.healthCheck.type];
  return (
    <div className="panel">
      <div className="top">
        <h3 style={{ margin: 0 }}>{isFe ? t('Listeners (frontends)') : 'Backends'}</h3>
        {canEdit && <a className="btn" href={`#/c/${cluster.id}/${kind}/new`}>+ {(isFe ? t('Novo listener') : t('Novo backend'))}</a>}
      </div>
      {items.length ? (
        <table>
          <thead><tr>{cols.map((c) => <th key={c}>{t(c)}</th>)}</tr></thead>
          <tbody>
            {items.map((i) => (
              <tr key={i.id}>
                <td><a href={`#/c/${cluster.id}/${kind}/${i.id}`}>{i.name}</a>{i.enabled === false && <span className="muted"> ({t('inativo')})</span>}</td>
                <td>{i.mode}</td>
                {summary(i).map((s, j) => <td key={j}>{s}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      ) : <p className="muted">{isFe ? t('Nenhum listener. Crie backends primeiro e depois um listener apontando para eles.') : t('Nenhum backend.')}</p>}
    </div>
  );
}

// ------------------------------------------------------------------ certificados
function Certificates({ cluster }: { cluster: any }) {
  const t = useT();
  const fmt = useFmt();
  const { data: certs, reload } = useLoad<any[]>(() => api('GET', `/clusters/${cluster.id}/certificates`), [cluster.id], 4000);
  const [upload, setUpload] = useState<any>({});
  const [acme, setAcme] = useState<any>({});
  const run = useAction();
  const toast = useToast();
  const { canEdit } = usePerm();
  const doUpload = run(async () => {
    await api('POST', `/clusters/${cluster.id}/certificates`, { ...upload, source: 'upload' });
    toast(t('certificado salvo — publique para aplicar'));
    setUpload({});
    reload();
  });
  const doAcme = run(async () => {
    await api('POST', `/clusters/${cluster.id}/certificates`, { name: acme.name, source: 'acme', acme: { domains: acme.domains, email: acme.email } });
    toast(t('emissão iniciada — acompanhe o status'));
    reload();
  });
  const issue = (c: any) => run(async () => {
    await api('POST', `/clusters/${cluster.id}/certificates/${c.id}/issue`);
    toast(t('emissão iniciada'));
    reload();
  });
  const del = (c: any) => run(async () => {
    if (!confirm(t('Excluir certificado {name}?', { name: c.name }))) return;
    await api('DELETE', `/clusters/${cluster.id}/certificates/${c.id}`);
    reload();
  });
  if (!certs) return <Loading />;
  return (
    <>
      <div className="panel">
        <h3>{t('Certificados')}</h3>
        {certs.length ? (
          <table>
            <thead><tr><th>{t('Nome')}</th><th>{t('Domínios')}</th><th>{t('Expira')}</th><th>{t('Origem')}</th><th>Status</th><th /></tr></thead>
            <tbody>
              {certs.map((c) => {
                const d = daysLeft(c.meta?.notAfter);
                return (
                  <tr key={c.id}>
                    <td><b>{c.name}</b></td>
                    <td>{(c.meta?.domains ?? c.acme?.domains ?? []).join(', ')}</td>
                    <td>{d == null ? '—' : <span className={d < 14 ? 'b-err' : d < 30 ? 'b-warn' : ''}>{fmt.date(c.meta.notAfter)} ({t('{n} dias', { n: d })})</span>}</td>
                    <td>{c.source === 'acme' ? 'ACME' : 'upload'}</td>
                    <td>
                      {c.source === 'acme' ? <Badge s={c.acme?.status ?? 'pending'} /> : c.hasKey ? <Badge s="succeeded" text="ok" /> : '—'}
                      {c.acme?.error && <div className="err" style={{ fontSize: 12 }}>{c.acme.error}</div>}
                    </td>
                    <td>
                      {canEdit && <div className="actions">
                        {c.source === 'acme' && <button className="small" onClick={issue(c)}>{t('emitir/renovar')}</button>}
                        <button className="small danger" onClick={del(c)}>{t('excluir')}</button>
                      </div>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        ) : <p className="muted">{t('Nenhum certificado.')}</p>}
        <p className="muted">{t('Todos os certificados vão para um crt-list: o HAProxy escolhe pelo SNI. O certificado padrão (cliente sem SNI) é definido em Configurações.')}</p>
      </div>
      {canEdit && <><details className="panel">
        <summary><b>{t("Emitir com Let's Encrypt (HTTP-01)")}</b></summary>
        <p className="muted">{t('Requer "ACME → manager visto pelo HAProxy" em Configurações, um listener HTTP na porta 80 com ACME ativo e a configuração já publicada.')}</p>
        <Fields rows={[[{ key: 'name', label: 'Nome' }, { key: 'email', label: 'E-mail (opcional)' }],
          { key: 'domains', label: 'Domínios (o DNS deve apontar para o VIP/NLB)', type: 'tokens', placeholder: 'app.example.com www.example.com' }]}
          value={acme} onChange={setAcme} ctx={{}} />
        <button className="primary" onClick={doAcme}>{t('Emitir')}</button>
      </details>
      <details className="panel">
        <summary><b>{t('Enviar certificado (PEM)')}</b></summary>
        <Fields rows={[{ key: 'name', label: 'Nome' }, { key: 'pem', label: 'PEM (certificado + cadeia + chave privada)', type: 'textarea', mono: true, rows: 8 }]}
          value={upload} onChange={setUpload} ctx={{}} />
        <button className="primary" onClick={doUpload}>{t('Salvar')}</button>
      </details></>}
    </>
  );
}

// ------------------------------------------------------------------ publicar
function Publish({ cluster }: { cluster: any }) {
  const t = useT();
  const { data: p } = useLoad<any>(() => api('GET', `/clusters/${cluster.id}/preview`), [cluster.id]);
  const { canEdit, isAdmin } = usePerm();
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const run = useAction();
  const toast = useToast();
  if (!p) return <p className="muted">{t('Gerando configuração...')}</p>;
  if (p.errors)
    return (
      <div className="panel">
        <h3 className="b-err">{t('A configuração tem problemas')}</h3>
        <ul>{p.errors.map((e: string) => <li key={e} className="err">{e}</li>)}</ul>
      </div>
    );
  const needsApproval = !!cluster.settings.approval?.required;
  const send = (emergency: boolean) => run(async () => {
    let n = note;
    if (emergency) {
      const j = prompt(t('Publicação EMERGENCIAL (sem aprovação, auditada). Justificativa (incidente/chamado):'), note);
      if (!j) return;
      n = j;
    }
    setBusy(true);
    try {
      const d = await api('POST', `/clusters/${cluster.id}/deployments`, { note: n, emergency });
      if (d.type === 'request') {
        toast(t('pedido #{id} criado — aguardando aprovação', { id: d.id }));
        location.hash = `#/c/${cluster.id}/approvals/${d.id}`;
      } else {
        toast(t('v{v} publicada — acompanhando rollout', { v: d.version }));
        location.hash = `#/c/${cluster.id}`;
      }
    } finally {
      setBusy(false);
    }
  });
  const blocked = p.check.available && !p.check.ok;
  return (
    <>
      <div className="panel">
        <h3>{t('Publicar v{v}', { v: p.version })}</h3>
        <p>
          {p.check.available
            ? p.check.ok ? <><Badge s="succeeded" /> {t('haproxy -c: configuração válida')}</> : <><Badge s="failed" /> {t('haproxy -c falhou')}</>
            : <span className="muted">{t('haproxy não instalado no manager: a validação acontece em cada nó antes do reload.')}</span>}
        </p>
        {blocked && <pre className="err">{p.check.output}</pre>}
        {!p.changed && <p className="muted">{t('Nada mudou em relação à v{v} (publicar mesmo assim reaplica certificados/keepalived).', { v: p.currentVersion })}</p>}
        {needsApproval && <p className="notice panel" style={{ padding: '8px 12px' }}>{t('Este cluster exige aprovação: a publicação vira um pedido que outra pessoa com papel APPROVER precisa aprovar. O que for aprovado é exatamente o que você está vendo aqui.')}</p>}
        {canEdit ? (
          <div className="row">
            <input placeholder={needsApproval ? t('o que muda e por quê (vai para o aprovador)') : t('o que mudou? (opcional)')} value={note} onChange={(e) => setNote(e.target.value)} />
            <button className="primary" style={{ flex: '0 0 auto' }} disabled={blocked || busy} onClick={send(false)}>{needsApproval ? t('Solicitar aprovação') : t('Publicar')}</button>
            {needsApproval && isAdmin && <button className="danger" style={{ flex: '0 0 auto' }} disabled={blocked || busy} onClick={send(true)}>{t('Emergencial')}</button>}
          </div>
        ) : <p className="muted">{t('Seu papel permite apenas visualizar.')}</p>}
        <p className="muted">
          {cluster.settings.mode === 'aws-nlb'
            ? t('Rollout: um nó por vez; cada nó valida, recarrega sem derrubar conexões e faz rollback sozinho se falhar.')
            : t('Rollout: nós BACKUP primeiro (canário) e o MASTER por último. Se algum nó falhar, todos voltam para a última versão boa.')}
        </p>
      </div>
      {p.diff && p.changed && (
        <>
          <h3>{t('Diferenças em relação à v{v}', { v: p.currentVersion })}</h3>
          <Diff lines={p.diff} />
        </>
      )}
      <details className="panel"><summary>{t('haproxy.cfg completo')}</summary><pre>{p.haproxyCfg}</pre></details>
      {Object.keys(p.keepalived).length > 0 && (
        <details className="panel">
          <summary>{t('keepalived.conf por nó')}</summary>
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
  const t = useT();
  const ts = useTs();
  const fmt = useFmt();
  const { data: deps } = useLoad<any[]>(() => api('GET', `/clusters/${cluster.id}/deployments`), [cluster.id], 5000);
  const [shown, setShown] = useState<any>(null);
  const run = useAction();
  const toast = useToast();
  const { canEdit } = usePerm();
  const rollback = (d: any) => run(async () => {
    if (!confirm(t('Criar uma nova versão com a configuração da v{v}?', { v: d.version }))) return;
    const n = await api('POST', `/clusters/${cluster.id}/deployments/${d.id}/rollback`, {});
    if (n.type === 'request') {
      toast(t('pedido de rollback #{id} criado — aguardando aprovação', { id: n.id }));
      location.hash = `#/c/${cluster.id}/approvals/${n.id}`;
    } else {
      toast(t('rollback publicado como v{v}', { v: n.version }));
      location.hash = `#/c/${cluster.id}`;
    }
  });
  const show = (d: any) => run(async () => setShown(await api('GET', `/clusters/${cluster.id}/deployments/${d.id}`)));
  if (!deps) return <Loading />;
  return (
    <>
      <div className="panel">
        <h3>{t('Histórico de publicações')}</h3>
        {deps.length ? (
          <table>
            <thead><tr><th>{t('Versão')}</th><th>Status</th><th>{t('Nota')}</th><th>{t('Por')}</th><th>{t('Aprovado por')}</th><th>{t('Quando')}</th><th /></tr></thead>
            <tbody>
              {deps.map((d) => (
                <tr key={d.id}>
                  <td><b>v{d.version}</b></td>
                  <td><Badge s={d.status} />{d.error && <div className={d.status === 'failed' ? 'err' : 'muted'} style={{ fontSize: 12 }}>{ts(d.error).slice(0, 300)}</div>}</td>
                  <td>{ts(d.note)}</td>
                  <td>{d.createdBy || ''}</td>
                  <td>{d.approvedBy || '—'}</td>
                  <td>{fmt.date(d.createdAt)}</td>
                  <td>
                    <div className="actions">
                      <button className="small" onClick={show(d)}>{t('ver config')}</button>
                      {canEdit && <button className="small" onClick={rollback(d)}>rollback</button>}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <p className="muted">{t('Nada publicado ainda.')}</p>}
      </div>
      {shown && (
        <div className="panel">
          <div className="top"><h3 style={{ margin: 0 }}>{t('haproxy.cfg da v{v}', { v: shown.version })}</h3><button className="small" onClick={() => setShown(null)}>{t('fechar')}</button></div>
          <pre>{shown.haproxyCfg}</pre>
        </div>
      )}
    </>
  );
}

// ------------------------------------------------------------------ configurações
function Settings({ cluster, onSaved }: { cluster: any; onSaved: () => void }) {
  const t = useT();
  const [form, setForm] = useState<any>({ name: cluster.name, settings: cluster.settings });
  const run = useAction();
  const toast = useToast();
  const { isAdmin } = usePerm();
  const save = run(async () => {
    await api('PUT', `/clusters/${cluster.id}`, withDefaults(clusterSchema, form));
    toast(t('salvo — publique para aplicar'));
    onSaved();
  });
  const rotate = run(async () => {
    if (!confirm(t('Rotacionar o token? Todos os agentes precisarão do novo token.'))) return;
    const r = await api('POST', `/clusters/${cluster.id}/rotate-token`);
    prompt(t('Novo token de join:'), r.joinToken);
  });
  const del = run(async () => {
    if (prompt(t('Digite o nome do cluster ({name}) para excluir:', { name: cluster.name })) !== cluster.name) return;
    await api('DELETE', `/clusters/${cluster.id}`);
    location.hash = '#/';
  });
  return (
    <>
      <div className="panel">
        <Fields rows={clusterSchema} value={form} onChange={setForm} ctx={{}} />
        {isAdmin ? <div className="actions"><button className="primary" onClick={save}>{t('Salvar')}</button></div> : <p className="muted">{t('Somente ADMIN altera as configurações do cluster.')}</p>}
      </div>
      {isAdmin && <div className="panel">
        <h3>{t('Zona de perigo')}</h3>
        <div className="actions">
          <button onClick={rotate}>{t('Rotacionar token de join')}</button>
          <button className="danger" onClick={del}>{t('Excluir cluster')}</button>
        </div>
      </div>}
    </>
  );
}

// ------------------------------------------------------------------ aprovações
const REQ: Record<string, [string, string]> = {
  pending: ['aguardando aprovação', 'b-warn'], approved: ['aprovado', 'b-ok'], rejected: ['rejeitado', 'b-err'],
  cancelled: ['cancelado', 'b-muted'], superseded: ['obsoleto', 'b-muted'],
};
function ReqBadge({ s }: { s: string }) {
  const t = useT();
  return <span className={`badge ${REQ[s]?.[1] ?? 'b-muted'}`}>{t(REQ[s]?.[0] ?? s)}</span>;
}

function Approvals({ cluster, sub, onChange }: { cluster: any; sub?: string; onChange: () => void }) {
  const t = useT();
  const fmt = useFmt();
  const { data: list, reload } = useLoad<any[]>(() => api('GET', `/clusters/${cluster.id}/requests`), [cluster.id], 5000);
  const { data: req, reload: reloadReq } = useLoad<any>(() => (sub ? api('GET', `/clusters/${cluster.id}/requests/${sub}`) : Promise.resolve(null)), [cluster.id, sub]);
  const [comment, setComment] = useState('');
  const run = useAction();
  const toast = useToast();
  const { me, canApprove, isAdmin } = usePerm();
  const after = () => {
    reload();
    reloadReq();
    onChange();
  };
  const approve = run(async () => {
    const r = await api('POST', `/clusters/${cluster.id}/requests/${sub}/approve`, { comment });
    toast(t('aprovado — publicando v{v}', { v: r.deployment.version }));
    location.hash = `#/c/${cluster.id}`;
    onChange();
  });
  const reject = run(async () => {
    await api('POST', `/clusters/${cluster.id}/requests/${sub}/reject`, { comment });
    toast(t('pedido rejeitado'));
    after();
  });
  const cancel = run(async () => {
    await api('POST', `/clusters/${cluster.id}/requests/${sub}/cancel`);
    toast(t('pedido cancelado'));
    after();
  });
  if (!list) return <Loading />;
  const selfApproval = !!cluster.settings.approval?.allowSelfApproval;
  return (
    <>
      {req && (
        <div className="panel">
          <div className="top">
            <h3 style={{ margin: 0 }}>{t('Pedido #{id}', { id: req.id })}</h3>
            <ReqBadge s={req.status} />
            <a className="btn" href={`#/c/${cluster.id}/approvals`}>{t('voltar')}</a>
          </div>
          <p>
            {t('Solicitado por {user} em {date} sobre a v{v}', { user: req.requestedBy, date: fmt.date(req.requestedAt), v: req.baseVersion })}
            {req.currentVersion !== req.baseVersion && req.status === 'pending' &&
              <span className="b-err"> — {t('atenção: a versão atual já é v{v}, este pedido está desatualizado', { v: req.currentVersion })}</span>}
          </p>
          {req.note && <p><b>{t('Motivo')}:</b> {req.note}</p>}
          {req.decidedBy && <p><b>{t(REQ[req.status]?.[0] ?? req.status)}</b> {t('por {user} em {date}', { user: req.decidedBy, date: fmt.date(req.decidedAt) })}{req.decisionNote ? ` — "${req.decisionNote}"` : ''}</p>}
          <h4>{t('O que muda em relação à v{v}', { v: req.baseVersion })}</h4>
          {!req.diff.some((d: any) => d.op === '+' || d.op === '-') && <p className="notice panel" style={{ padding: '8px 12px' }}>{t('Nenhuma alteração no haproxy.cfg (republica certificados/keepalived).')}</p>}
          <Diff lines={req.diff} max={480} />
          <details><summary>{t('haproxy.cfg completo do pedido')}</summary><pre>{req.haproxyCfg}</pre></details>
          {req.status === 'pending' && (
            <>
              <label className="f" style={{ marginTop: 12 }}><span>{t('Comentário (obrigatório para rejeitar)')}</span>
                <input value={comment} onChange={(e) => setComment(e.target.value)} /></label>
              <div className="actions">
                {canApprove && (req.requestedBy !== me?.username || selfApproval)
                  ? <><button className="primary" onClick={approve}>{t('Aprovar e publicar')}</button><button className="danger" onClick={reject}>{t('Rejeitar')}</button></>
                  : canApprove && <span className="muted">{t('Quatro olhos: você não pode aprovar o próprio pedido.')}</span>}
                {(req.requestedBy === me?.username || isAdmin) && <button onClick={cancel}>{t('Cancelar pedido')}</button>}
              </div>
            </>
          )}
        </div>
      )}
      <div className="panel">
        <h3>{t('Pedidos de publicação')}</h3>
        {list.length ? (
          <table>
            <thead><tr><th>#</th><th>Status</th><th>{t('Motivo')}</th><th>{t('Solicitante')}</th><th>{t('Quando')}</th><th>{t('Decisão')}</th></tr></thead>
            <tbody>
              {list.map((r) => (
                <tr key={r.id}>
                  <td><a href={`#/c/${cluster.id}/approvals/${r.id}`}>#{r.id}</a></td>
                  <td><ReqBadge s={r.status} /></td>
                  <td>{r.note || ''}</td>
                  <td>{r.requestedBy}</td>
                  <td>{fmt.date(r.requestedAt)}</td>
                  <td>{r.decidedBy ? `${r.decidedBy}${r.decisionNote ? `: ${r.decisionNote}` : ''}` : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <p className="muted">{t('Nenhum pedido.')}</p>}
      </div>
    </>
  );
}
