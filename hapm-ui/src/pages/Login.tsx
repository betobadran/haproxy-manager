import { useEffect, useState } from 'react';
import { api, auth } from '../api';
import { useToast } from '../ui';

type Providers = { password: boolean; ldap: boolean; oidc: { label: string; url: string } | null };

function hashParams() {
  const q = location.hash.split('?')[1] ?? '';
  return new URLSearchParams(q);
}

export function Login({ callback }: { callback?: boolean }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [providers, setProviders] = useState<Providers | null>(null);
  const toast = useToast();

  useEffect(() => {
    api<Providers>('GET', '/auth/providers').then(setProviders, () => setProviders({ password: true, ldap: false, oidc: null }));
    const err = hashParams().get('error');
    if (err) toast(err, true);
  }, [toast]);

  // retorno do SSO: troca o código de uso único pelo token de sessão
  useEffect(() => {
    if (!callback) return;
    const code = hashParams().get('code');
    api<{ token: string }>('POST', '/auth/exchange', { code }).then(
      (r) => {
        auth.set(r.token);
        location.hash = '#/';
      },
      (e) => {
        toast(e.message, true);
        location.hash = '#/login';
      },
    );
  }, [callback, toast]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const r = await api<{ token: string }>('POST', '/auth/login', { username, password });
      auth.set(r.token);
      location.hash = '#/';
    } catch (err: any) {
      toast(err.message, true);
    }
  };
  if (callback) return <p className="panel login">Concluindo login...</p>;
  return (
    <div className="panel login">
      <h3>HAPM · entrar</h3>
      {providers?.oidc && (
        <>
          <a className="btn primary" style={{ display: 'block', textAlign: 'center', marginBottom: 14 }} href={providers.oidc.url}>{providers.oidc.label}</a>
          {providers.password && <div className="muted" style={{ textAlign: 'center', marginBottom: 10 }}>ou</div>}
        </>
      )}
      {providers?.password !== false && (
        <form onSubmit={submit}>
          <label className="f"><span>{providers?.ldap ? 'Usuário (rede corporativa ou local)' : 'Usuário'}</span>
            <input value={username} autoFocus autoComplete="username" onChange={(e) => setUsername(e.target.value)} /></label>
          <label className="f"><span>Senha</span>
            <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>
          <button className={providers?.oidc ? '' : 'primary'} type="submit">Entrar</button>
        </form>
      )}
    </div>
  );
}
