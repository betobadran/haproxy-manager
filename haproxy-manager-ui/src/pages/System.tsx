import { useEffect, useMemo, useState } from 'react';
import { api } from '../api';
import { useAction, useLoad, useToast } from '../ui';
import { roleLabel, usePerm } from '../session';
import { browserZone, PrefsSwitcher, useFmt, usePrefs, useT, zoneOffset, type DateFormat, type TimeFormat } from '../prefs';

export function Audit() {
  const t = useT();
  const fmt = useFmt();
  const { data: rows } = useLoad<any[]>(() => api('GET', '/audit'), []);
  return (
    <>
      <div className="top"><h2>{t('Auditoria')}</h2></div>
      <div className="panel">
        <table>
          <thead><tr><th>{t('Quando')} <span className="muted">({fmt.zone})</span></th><th>{t('Usuário')}</th><th>{t('Ação')}</th><th>{t('Alvo')}</th><th>{t('Detalhe')}</th></tr></thead>
          <tbody>
            {(rows ?? []).map((r) => (
              <tr key={r.id}>
                <td>{fmt.date(r.ts)}</td><td>{r.username || '—'}</td><td><code>{r.action}</code></td><td>{r.target || ''}</td>
                <td className="muted" style={{ fontSize: 12 }}>{(r.detail || '').slice(0, 200)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

/** Formato de data, de hora e fuso horário (salvos no servidor, por usuário). */
function DisplayPrefs() {
  const t = useT();
  const { dateFormat, timeFormat, timeZone, setDisplay, zone } = usePrefs();
  const fmt = useFmt();
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const i = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(i);
  }, []);
  const zones = useMemo(() => {
    try {
      return (Intl as any).supportedValuesOf('timeZone') as string[];
    } catch {
      return ['UTC', 'America/Sao_Paulo', 'America/New_York', 'Europe/London', 'Europe/Lisbon'];
    }
  }, []);
  const [zoneInput, setZoneInput] = useState(timeZone === 'browser' ? '' : timeZone);
  const zoneValid = zoneInput === '' || zoneInput === 'UTC' || zones.includes(zoneInput);
  return (
    <div className="panel" style={{ maxWidth: 620 }}>
      <h3>{t('Preferências')}</h3>
      <div className="f"><span className="lbl">{t('Tema e idioma')}</span><div className="prefs-inline"><PrefsSwitcher /></div></div>
      <div className="row">
        <label className="f"><span>{t('Formato de data')}</span>
          <select value={dateFormat} onChange={(e) => setDisplay({ dateFormat: e.target.value as DateFormat })}>
            <option value="auto">{t('Automático (pelo idioma)')}</option>
            <option value="DD/MM/YYYY">DD/MM/YYYY · 31/12/2026</option>
            <option value="MM/DD/YYYY">MM/DD/YYYY · 12/31/2026</option>
            <option value="YYYY-MM-DD">YYYY-MM-DD · 2026-12-31 (ISO)</option>
            <option value="DD.MM.YYYY">DD.MM.YYYY · 31.12.2026</option>
          </select></label>
        <label className="f"><span>{t('Formato de hora')}</span>
          <select value={timeFormat} onChange={(e) => setDisplay({ timeFormat: e.target.value as TimeFormat })}>
            <option value="auto">{t('Automático (pelo idioma)')}</option>
            <option value="24h">{t('24 horas · 21:30')}</option>
            <option value="12h">{t('12 horas · 9:30 PM')}</option>
          </select></label>
      </div>
      <label className="f"><span>{t('Fuso horário')}</span>
        <div className="row" style={{ alignItems: 'center' }}>
          <input list="hapm-zones" placeholder={t('Fuso do navegador ({zone})', { zone: browserZone() })} value={zoneInput}
            style={zoneValid ? undefined : { borderColor: 'var(--err)' }}
            onChange={(e) => {
              const v = e.target.value.trim();
              setZoneInput(v);
              if (v === '') setDisplay({ timeZone: 'browser' });
              else if (v === 'UTC' || zones.includes(v)) setDisplay({ timeZone: v });
            }} />
          <button type="button" style={{ flex: '0 0 auto' }} onClick={() => { setZoneInput(''); setDisplay({ timeZone: 'browser' }); }}>{t('Usar o do navegador')}</button>
          <button type="button" style={{ flex: '0 0 auto' }} onClick={() => { setZoneInput('UTC'); setDisplay({ timeZone: 'UTC' }); }}>UTC</button>
        </div>
        <datalist id="hapm-zones">{zones.map((z) => <option key={z} value={z} />)}</datalist>
      </label>
      {!zoneValid && <p className="err">{t('Fuso horário desconhecido')}</p>}
      <p className="muted">{t('Agora')}: <b>{fmt.date(now)}</b> · {zone} ({zoneOffset(zone, now)})</p>
      <p className="muted">{t('Vale para todas as telas e gráficos. Fica salvo na sua conta e acompanha você em qualquer navegador.')}</p>
    </div>
  );
}

export function Account() {
  const t = useT();
  const [current, setCurrent] = useState('');
  const [password, setPassword] = useState('');
  const run = useAction();
  const toast = useToast();
  const { me } = usePerm();
  const save = run(async () => {
    await api('POST', '/me/password', { current, password });
    toast(t('senha alterada'));
  });
  if (me && me.source !== 'local')
    return (
      <>
        <div className="top"><h2>{t('Conta')}</h2></div>
        <DisplayPrefs />
        <div className="panel" style={{ maxWidth: 520 }}>
          <p><b>{me.displayName}</b> ({me.username}) — {t('autenticado via')} <b>{me.source.toUpperCase()}</b>.</p>
          <p>{t('Papéis')}: {me.roles.map((r) => roleLabel(t, r)).join(', ')}</p>
          <p className="muted">{t('Senha e grupos são geridos no diretório corporativo. Mudanças de grupo valem no próximo login.')}</p>
        </div>
      </>
    );
  return (
    <>
      <div className="top"><h2>{t('Conta')}</h2></div>
      <DisplayPrefs />
      <div className="panel" style={{ maxWidth: 420 }}>
        <h3>{t('Trocar senha')}</h3>
        <label className="f"><span>{t('Senha atual')}</span><input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} /></label>
        <label className="f"><span>{t('Nova senha (mín. 10)')}</span><input type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>
        <button className="primary" onClick={save}>{t('Salvar')}</button>
      </div>
    </>
  );
}
