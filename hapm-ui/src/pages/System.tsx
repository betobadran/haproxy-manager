import { useState } from 'react';
import { api } from '../api';
import { useAction, useLoad, useToast } from '../ui';
import { roleLabel, usePerm } from '../session';
import { useFmt, useT } from '../prefs';

export function Audit() {
  const t = useT();
  const fmt = useFmt();
  const { data: rows } = useLoad<any[]>(() => api('GET', '/audit'), []);
  return (
    <>
      <div className="top"><h2>{t('Auditoria')}</h2></div>
      <div className="panel">
        <table>
          <thead><tr><th>{t('Quando')}</th><th>{t('Usuário')}</th><th>{t('Ação')}</th><th>{t('Alvo')}</th><th>{t('Detalhe')}</th></tr></thead>
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
      <div className="panel" style={{ maxWidth: 420 }}>
        <h3>{t('Trocar senha')}</h3>
        <label className="f"><span>{t('Senha atual')}</span><input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} /></label>
        <label className="f"><span>{t('Nova senha (mín. 10)')}</span><input type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>
        <button className="primary" onClick={save}>{t('Salvar')}</button>
      </div>
    </>
  );
}
