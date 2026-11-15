import { useState } from 'react';
import { api } from '../api';
import { Fields } from '../form';
import { fmtDate, useAction, useLoad, useToast } from '../ui';

const ROLE_OPTS: [string, string][] = [['VIEWER', 'VIEWER (leitura)'], ['OPERATOR', 'OPERATOR (edita e publica/solicita)'], ['APPROVER', 'APPROVER (aprova publicações)'], ['ADMIN', 'ADMIN (tudo)']];

function RolePicker({ value, onChange }: { value: string[]; onChange: (v: string[]) => void }) {
  return (
    <div className="row" style={{ marginBottom: 10 }}>
      {ROLE_OPTS.map(([r, l]) => (
        <label key={r} className="cb">
          <input type="checkbox" checked={value.includes(r)} onChange={(e) => onChange(e.target.checked ? [...value, r] : value.filter((x) => x !== r))} /> {l}
        </label>
      ))}
    </div>
  );
}

export function Users() {
  const { data: users, reload } = useLoad<any[]>(() => api('GET', '/users'), []);
  const [form, setForm] = useState<any>({});
  const [roles, setRoles] = useState<string[]>(['VIEWER']);
  const [edit, setEdit] = useState<any>(null);
  const run = useAction();
  const toast = useToast();
  const create = run(async () => {
    await api('POST', '/users', { ...form, roles });
    toast('usuário criado');
    setForm({});
    reload();
  });
  const save = run(async () => {
    await api('PUT', `/users/${edit.username}`, { displayName: edit.displayName, roles: edit.roles, password: edit.password || null });
    toast('salvo');
    setEdit(null);
    reload();
  });
  const del = (u: any) => run(async () => {
    if (!confirm(`Excluir ${u.username}?`)) return;
    await api('DELETE', `/users/${u.username}`);
    reload();
  });
  if (!users) return <p className="muted">carregando...</p>;
  return (
    <>
      <div className="top"><h2>Usuários</h2></div>
      <div className="panel">
        <p className="muted">Usuários LDAP/SSO aparecem após o primeiro login; os papéis deles vêm dos grupos do diretório (configurados em <code>hapm.auth.role-mapping</code>). Contas locais servem para acesso de emergência ou ambientes sem diretório.</p>
        <table>
          <thead><tr><th>Usuário</th><th>Nome</th><th>Origem</th><th>Papéis</th><th>Último login</th><th /></tr></thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.username}>
                <td><b>{u.username}</b></td><td>{u.displayName || ''}</td>
                <td><span className="badge b-muted">{u.source}</span></td>
                <td>{(u.roles ?? []).join(', ')}</td>
                <td>{fmtDate(u.lastLogin)}</td>
                <td><div className="actions">
                  {u.source === 'local' && <button className="small" onClick={() => setEdit({ ...u, roles: [...u.roles], password: '' })}>editar</button>}
                  <button className="small danger" onClick={del(u)}>excluir</button>
                </div></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {edit && (
        <div className="panel">
          <h3>Editar {edit.username}</h3>
          <label className="f"><span>Nome</span><input value={edit.displayName ?? ''} onChange={(e) => setEdit({ ...edit, displayName: e.target.value })} /></label>
          <RolePicker value={edit.roles} onChange={(r) => setEdit({ ...edit, roles: r })} />
          <label className="f"><span>Nova senha (opcional, mín. 10)</span><input type="password" value={edit.password} onChange={(e) => setEdit({ ...edit, password: e.target.value })} /></label>
          <div className="actions"><button className="primary" onClick={save}>Salvar</button><button onClick={() => setEdit(null)}>Cancelar</button></div>
        </div>
      )}
      <details className="panel">
        <summary><b>Novo usuário local</b></summary>
        <Fields rows={[[{ key: 'username', label: 'Usuário' }, { key: 'displayName', label: 'Nome' }]]} value={form} onChange={setForm} ctx={{}} />
        <label className="f"><span>Senha inicial (mín. 10)</span><input type="password" value={form.password ?? ''} onChange={(e) => setForm({ ...form, password: e.target.value })} /></label>
        <RolePicker value={roles} onChange={setRoles} />
        <button className="primary" onClick={create}>Criar</button>
      </details>
    </>
  );
}
