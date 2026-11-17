import { useState } from 'react';
import { api } from '../api';
import { Fields } from '../form';
import { useAction, useLoad, useToast } from '../ui';
import { useFmt, useT } from '../prefs';

const ROLE_OPTS: [string, string][] = [['VIEWER', 'VIEWER (leitura)'], ['OPERATOR', 'OPERATOR (edita e publica/solicita)'], ['APPROVER', 'APPROVER (aprova publicações)'], ['ADMIN', 'ADMIN (tudo)']];

function RolePicker({ value, onChange }: { value: string[]; onChange: (v: string[]) => void }) {
  const t = useT();
  return (
    <div className="row" style={{ marginBottom: 10 }}>
      {ROLE_OPTS.map(([r, l]) => (
        <label key={r} className="cb">
          <input type="checkbox" checked={value.includes(r)} onChange={(e) => onChange(e.target.checked ? [...value, r] : value.filter((x) => x !== r))} /> {t(l)}
        </label>
      ))}
    </div>
  );
}

export function Users() {
  const t = useT();
  const fmt = useFmt();
  const { data: users, reload } = useLoad<any[]>(() => api('GET', '/users'), []);
  const [form, setForm] = useState<any>({});
  const [roles, setRoles] = useState<string[]>(['VIEWER']);
  const [edit, setEdit] = useState<any>(null);
  const run = useAction();
  const toast = useToast();
  const create = run(async () => {
    await api('POST', '/users', { ...form, roles });
    toast(t('usuário criado'));
    setForm({});
    reload();
  });
  const save = run(async () => {
    await api('PUT', `/users/${edit.username}`, { displayName: edit.displayName, roles: edit.roles, password: edit.password || null });
    toast(t('salvo'));
    setEdit(null);
    reload();
  });
  const del = (u: any) => run(async () => {
    if (!confirm(t('Excluir {name}?', { name: u.username }))) return;
    await api('DELETE', `/users/${u.username}`);
    reload();
  });
  if (!users) return <p className="muted">{t('carregando...')}</p>;
  return (
    <>
      <div className="top"><h2>{t('Usuários')}</h2></div>
      <div className="panel">
        <p className="muted">{t('Usuários LDAP/SSO aparecem após o primeiro login; os papéis deles vêm dos grupos do diretório (configurados em hapm.auth.role-mapping). Contas locais servem para acesso de emergência ou ambientes sem diretório.')}</p>
        <table>
          <thead><tr><th>{t('Usuário')}</th><th>{t('Nome')}</th><th>{t('Origem')}</th><th>{t('Papéis')}</th><th>{t('Último login')}</th><th /></tr></thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.username}>
                <td><b>{u.username}</b></td><td>{u.displayName || ''}</td>
                <td><span className="badge b-muted">{u.source}</span></td>
                <td>{(u.roles ?? []).join(', ')}</td>
                <td>{fmt.date(u.lastLogin)}</td>
                <td><div className="actions">
                  {u.source === 'local' && <button className="small" onClick={() => setEdit({ ...u, roles: [...u.roles], password: '' })}>{t('editar')}</button>}
                  <button className="small danger" onClick={del(u)}>{t('excluir')}</button>
                </div></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {edit && (
        <div className="panel">
          <h3>{t('Editar {name}', { name: edit.username })}</h3>
          <label className="f"><span>{t('Nome')}</span><input value={edit.displayName ?? ''} onChange={(e) => setEdit({ ...edit, displayName: e.target.value })} /></label>
          <RolePicker value={edit.roles} onChange={(r) => setEdit({ ...edit, roles: r })} />
          <label className="f"><span>{t('Nova senha (opcional, mín. 10)')}</span><input type="password" value={edit.password} onChange={(e) => setEdit({ ...edit, password: e.target.value })} /></label>
          <div className="actions"><button className="primary" onClick={save}>{t('Salvar')}</button><button onClick={() => setEdit(null)}>{t('Cancelar')}</button></div>
        </div>
      )}
      <details className="panel">
        <summary><b>{t('Novo usuário local')}</b></summary>
        <Fields rows={[[{ key: 'username', label: 'Usuário' }, { key: 'displayName', label: 'Nome' }]]} value={form} onChange={setForm} ctx={{}} />
        <label className="f"><span>{t('Senha inicial (mín. 10)')}</span><input type="password" value={form.password ?? ''} onChange={(e) => setForm({ ...form, password: e.target.value })} /></label>
        <RolePicker value={roles} onChange={setRoles} />
        <button className="primary" onClick={create}>{t('Criar')}</button>
      </details>
    </>
  );
}
