// Alertas (expiração de certificado, falhas ACME) e configuração dos canais de notificação.
import { useEffect, useState } from 'react';
import { api } from '../api';
import { useAction, useLoad, useToast } from '../ui';
import { useFmt, useT } from '../prefs';
import { usePerm } from '../session';

const LEVEL: Record<string, [string, string, string]> = {
  critical: ['crítico', 'b-err', '●'],
  warning: ['atenção', 'b-warn', '▲'],
  info: ['info', 'b-acc', 'ℹ'],
};

export function LevelBadge({ level }: { level: string }) {
  const t = useT();
  const [label, c, icon] = LEVEL[level] ?? LEVEL.info;
  return <span className={`badge ${c}`}><span aria-hidden="true">{icon}</span> {t(label)}</span>;
}

const CHANNEL: Record<string, string> = { email: 'E-mail', teams: 'Teams', webhook: 'Webhook' };

function Deliveries({ list }: { list: any[] }) {
  const t = useT();
  if (!list?.length) return <span className="muted">{t('só na tela')}</span>;
  return (
    <span className="row" style={{ gap: 4 }}>
      {list.map((d) => (
        <span key={d.channel} className={`badge ${d.ok ? 'b-ok' : 'b-err'}`} title={d.error ?? ''}>
          {d.ok ? '✓' : '✗'} {CHANNEL[d.channel] ?? d.channel}
        </span>
      ))}
    </span>
  );
}

/** Número de alertas não reconhecidos (sidebar e banner). */
export function useUnacked(intervalMs = 30000) {
  const [n, setN] = useState(0);
  useEffect(() => {
    let alive = true;
    const load = () => api<{ unacked: number }>('GET', '/notifications/count').then((r) => alive && setN(r.unacked), () => {});
    load();
    const id = setInterval(load, intervalMs);
    window.addEventListener('hapm:alerts', load);
    return () => {
      alive = false;
      clearInterval(id);
      window.removeEventListener('hapm:alerts', load);
    };
  }, [intervalMs]);
  return n;
}

/** Banner na visão geral do cluster com os alertas pendentes dele. */
export function ClusterAlerts({ cid }: { cid: string }) {
  const t = useT();
  const { data } = useLoad<any[]>(() => api('GET', `/notifications?unacked=true&cluster=${cid}&limit=5`), [cid], 30000);
  if (!data?.length) return null;
  const critical = data.some((n) => n.level === 'critical');
  return (
    <div className={`banner ${critical ? 'banner-err' : 'banner-warn'}`} role="status">
      <b>{t('{n} alerta(s) pendente(s)', { n: data.length })}</b>
      <ul>{data.map((n) => <li key={n.id}><LevelBadge level={n.level} /> {n.title}</li>)}</ul>
      <a href="#/alerts">{t('ver alertas')}</a>
    </div>
  );
}

export function Alerts() {
  const t = useT();
  const fmt = useFmt();
  const [all, setAll] = useState(false);
  const { data, reload } = useLoad<any[]>(() => api('GET', `/notifications?unacked=${!all}`), [all], 30000);
  const { data: clusters } = useLoad<any[]>(() => api('GET', '/clusters'), []);
  const { isAdmin } = usePerm();
  const run = useAction();
  const toast = useToast();
  const names = Object.fromEntries((clusters ?? []).map((c) => [c.id, c.name]));
  const ack = (n: any) => run(async () => {
    await api('POST', `/notifications/${n.id}/ack`);
    window.dispatchEvent(new Event('hapm:alerts'));
    reload();
  });
  const check = run(async () => {
    const r = await api<{ created: number }>('POST', '/notifications/check');
    toast(t('{n} novo(s) alerta(s)', { n: r.created }));
    window.dispatchEvent(new Event('hapm:alerts'));
    reload();
  });
  return (
    <>
      <div className="top">
        <h2>{t('Alertas')}</h2>
        <div className="actions">
          <label className="cb"><input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} /> {t('mostrar reconhecidos')}</label>
          {isAdmin && <button onClick={check}>{t('Verificar certificados agora')}</button>}
          {isAdmin && <a className="btn" href="#/notifications">{t('Canais de notificação')}</a>}
        </div>
      </div>
      <div className="panel">
        <p className="muted">{t('Certificados são verificados a cada hora; cada limite (ex.: 30, 14, 7 e 1 dia) gera um alerta uma única vez, enviado aos canais ativos.')}</p>
        {!data ? <p className="muted">{t('carregando...')}</p> : !data.length ? <p className="muted">{t('Nenhum alerta pendente.')}</p> : (
          <table>
            <thead><tr><th>{t('Nível')}</th><th>{t('Horário')}</th><th>{t('Cluster')}</th><th>{t('Alerta')}</th><th>{t('Entregas')}</th><th /></tr></thead>
            <tbody>
              {data.map((n) => (
                <tr key={n.id} className={n.acknowledgedAt ? 'muted' : ''}>
                  <td><LevelBadge level={n.level} /></td>
                  <td>{fmt.date(n.ts)}</td>
                  <td>{n.clusterId ? <a href={`#/c/${n.clusterId}/certificates`}>{names[n.clusterId] ?? n.clusterId}</a> : '—'}</td>
                  <td><b>{n.title}</b><div className="muted">{n.message}</div></td>
                  <td><Deliveries list={n.deliveries} /></td>
                  <td>
                    {n.acknowledgedAt
                      ? <span className="muted">{t('reconhecido por {u}', { u: n.acknowledgedBy })}<br />{fmt.date(n.acknowledgedAt)}</span>
                      : <button className="small" onClick={ack(n)}>{t('reconhecer')}</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}

const MASK = '********';

export function NotifySettings() {
  const t = useT();
  const { data, reload } = useLoad<any>(() => api('GET', '/notifications/settings'), []);
  const [s, setS] = useState<any>(null);
  const run = useAction();
  const toast = useToast();
  useEffect(() => {
    if (data) setS({ ...data, thresholdsText: (data.thresholds ?? []).join(', '), email: { ...data.email, toText: (data.email.to ?? []).join(', ') } });
  }, [data]);
  if (!s) return <p className="muted">{t('carregando...')}</p>;
  const set = (group: string, key: string, v: any) => setS({ ...s, [group]: { ...s[group], [key]: v } });
  const body = () => ({
    thresholds: String(s.thresholdsText).split(/[,\s]+/).filter(Boolean).map(Number),
    email: { ...s.email, to: String(s.email.toText).split(/[,;\s]+/).filter(Boolean), port: Number(s.email.port) || 587 },
    teams: s.teams,
    webhook: s.webhook,
  });
  const save = run(async () => {
    await api('PUT', '/notifications/settings', body());
    toast(t('salvo'));
    reload();
  });
  const test = (channel: string) => run(async () => {
    await api('PUT', '/notifications/settings', body());
    const r = await api<any>('POST', `/notifications/test/${channel}`);
    if (r.ok) toast(t('teste enviado via {c}', { c: CHANNEL[channel] }));
    else throw new Error(`${CHANNEL[channel]}: ${r.error}`);
    reload();
  });
  const secret = (group: string, key: string, label: string) => (
    <label className="f"><span>{t(label)}</span>
      <input type="password" autoComplete="new-password" value={s[group][key] ?? ''} placeholder={t('(não definido)')}
        onFocus={(e) => e.target.value === MASK && set(group, key, '')} onChange={(e) => set(group, key, e.target.value)} />
    </label>
  );
  const toggle = (group: string) => (
    <label className="cb"><input type="checkbox" checked={!!s[group].enabled} onChange={(e) => set(group, 'enabled', e.target.checked)} /> {t('ativo')}</label>
  );
  return (
    <>
      <div className="top"><h2>{t('Canais de notificação')}</h2><div className="actions"><a className="btn" href="#/alerts">{t('Alertas')}</a></div></div>
      <div className="panel">
        <h3>{t('Quando alertar')}</h3>
        <label className="f"><span>{t('Dias antes da expiração (separados por vírgula)')}</span>
          <input value={s.thresholdsText} onChange={(e) => setS({ ...s, thresholdsText: e.target.value })} />
        </label>
        <p className="muted">{t('Alertas aparecem sempre na tela; abaixo, os canais de envio. Menos de 7 dias ou expirado = crítico.')}</p>
      </div>
      <div className="panel">
        <div className="chart-head"><h3>{t('E-mail (SMTP)')}</h3>{toggle('email')}</div>
        <div className="row">
          <label className="f"><span>{t('Servidor SMTP')}</span><input value={s.email.host ?? ''} onChange={(e) => set('email', 'host', e.target.value)} /></label>
          <label className="f" style={{ maxWidth: 110 }}><span>{t('Porta')}</span><input value={s.email.port ?? 587} onChange={(e) => set('email', 'port', e.target.value)} /></label>
        </div>
        <div className="row">
          <label className="f"><span>{t('Usuário (opcional)')}</span><input value={s.email.username ?? ''} onChange={(e) => set('email', 'username', e.target.value)} /></label>
          {secret('email', 'password', 'Senha')}
        </div>
        <div className="row">
          <label className="f"><span>{t('Remetente')}</span><input value={s.email.from ?? ''} onChange={(e) => set('email', 'from', e.target.value)} /></label>
          <label className="f"><span>{t('Destinatários (separados por vírgula)')}</span><input value={s.email.toText ?? ''} onChange={(e) => set('email', 'toText', e.target.value)} /></label>
        </div>
        <div className="row">
          <label className="cb"><input type="checkbox" checked={!!s.email.starttls} onChange={(e) => set('email', 'starttls', e.target.checked)} /> STARTTLS</label>
          <label className="cb"><input type="checkbox" checked={!!s.email.ssl} onChange={(e) => set('email', 'ssl', e.target.checked)} /> {t('SSL/TLS direto (porta 465)')}</label>
        </div>
        <button onClick={test('email')}>{t('Enviar teste')}</button>
      </div>
      <div className="panel">
        <div className="chart-head"><h3>Microsoft Teams</h3>{toggle('teams')}</div>
        <p className="muted">{t('No canal do Teams: Workflows › “Postar em um canal quando uma solicitação de webhook for recebida”. Cole a URL gerada.')}</p>
        {secret('teams', 'webhookUrl', 'URL do webhook (Workflows)')}
        <button onClick={test('teams')}>{t('Enviar teste')}</button>
      </div>
      <div className="panel">
        <div className="chart-head"><h3>Webhook</h3>{toggle('webhook')}</div>
        <p className="muted">{t('POST JSON com os cabeçalhos X-HAPM-Event e X-HAPM-Signature (sha256=HMAC do corpo com o segredo).')}</p>
        <label className="f"><span>URL</span><input value={s.webhook.url ?? ''} onChange={(e) => set('webhook', 'url', e.target.value)} /></label>
        {secret('webhook', 'secret', 'Segredo HMAC (opcional)')}
        <button onClick={test('webhook')}>{t('Enviar teste')}</button>
      </div>
      <div className="actions"><button className="primary" onClick={save}>{t('Salvar')}</button></div>
    </>
  );
}
