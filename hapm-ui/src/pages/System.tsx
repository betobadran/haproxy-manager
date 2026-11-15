import { useState } from 'react';
import { api } from '../api';
import { fmtDate, useAction, useLoad, useToast } from '../ui';

export function Audit() {
  const { data: rows } = useLoad<any[]>(() => api('GET', '/audit'), []);
  return (
    <>
      <div className="top"><h2>Auditoria</h2></div>
      <div className="panel">
        <table>
          <thead><tr><th>Quando</th><th>Usuário</th><th>Ação</th><th>Alvo</th><th>Detalhe</th></tr></thead>
          <tbody>
            {(rows ?? []).map((r) => (
              <tr key={r.id}>
                <td>{fmtDate(r.ts)}</td><td>{r.username || '—'}</td><td><code>{r.action}</code></td><td>{r.target || ''}</td>
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
  const [current, setCurrent] = useState('');
  const [password, setPassword] = useState('');
  const run = useAction();
  const toast = useToast();
  const save = run(async () => {
    await api('POST', '/me/password', { current, password });
    toast('senha alterada');
  });
  return (
    <>
      <div className="top"><h2>Conta</h2></div>
      <div className="panel" style={{ maxWidth: 420 }}>
        <h3>Trocar senha</h3>
        <label className="f"><span>Senha atual</span><input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} /></label>
        <label className="f"><span>Nova senha (mín. 8)</span><input type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>
        <button className="primary" onClick={save}>Salvar</button>
      </div>
    </>
  );
}
