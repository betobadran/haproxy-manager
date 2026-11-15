import { useEffect, useState, type ReactNode } from 'react';
import { api, auth } from './api';
import { useLoad } from './ui';
import { MeCtx, usePerm, type Me } from './session';
import { Login } from './pages/Login';
import { Home } from './pages/Home';
import { ClusterPage } from './pages/Cluster';
import { Audit, Account } from './pages/System';
import { Users } from './pages/Users';

function useHash() {
  const [hash, setHash] = useState(location.hash);
  useEffect(() => {
    const on = () => setHash(location.hash);
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return hash.replace(/^#\/?/, '').split('?')[0].split('/').filter(Boolean);
}

const ROLE_LABEL: Record<string, string> = { VIEWER: 'leitura', OPERATOR: 'operador', APPROVER: 'aprovador', ADMIN: 'admin' };

function Layout({ active, children }: { active: string; children: ReactNode }) {
  const { data: clusters } = useLoad<any[]>(() => api('GET', '/clusters'), [active], 15000);
  const { me, isAdmin } = usePerm();
  const logout = () => {
    auth.set(null);
    location.hash = '#/login';
  };
  return (
    <div className="layout">
      <nav className="side">
        <h1>HAPM</h1>
        <div className="sub">HAProxy HA Manager</div>
        <a href="#/" className={active === 'home' ? 'active' : ''}>Clusters</a>
        <div className="section">Clusters</div>
        {(clusters ?? []).map((c) => (
          <a key={c.id} href={`#/c/${c.id}`} className={active === c.id ? 'active' : ''}>{c.name}</a>
        ))}
        <div className="section">Sistema</div>
        <a href="#/audit" className={active === 'audit' ? 'active' : ''}>Auditoria</a>
        {isAdmin && <a href="#/users" className={active === 'users' ? 'active' : ''}>Usuários</a>}
        <a href="#/account" className={active === 'account' ? 'active' : ''}>Conta</a>
        <a href="#/login" onClick={logout}>Sair</a>
        {me && (
          <div className="whoami">
            <b>{me.displayName}</b>
            <div>{me.username} · {me.source}</div>
            <div>{me.roles.map((r) => ROLE_LABEL[r] ?? r).join(', ')}</div>
          </div>
        )}
      </nav>
      <main className="main">{children}</main>
    </div>
  );
}

export function App() {
  const parts = useHash();
  const [me, setMe] = useState<Me | null>(null);
  const token = auth.token;
  useEffect(() => {
    if (token) api<Me>('GET', '/me').then(setMe, () => setMe(null));
  }, [token]);

  if (parts[0] === 'login') return <Login callback={parts[1] === 'callback'} />;
  if (!token) {
    location.hash = '#/login';
    return null;
  }
  if (!me) return <p className="muted" style={{ padding: 20 }}>carregando...</p>;
  let page: ReactNode;
  let active = 'home';
  if (parts[0] === 'c' && parts[1]) {
    active = parts[1];
    page = <ClusterPage key={parts[1]} cid={parts[1]} tab={parts[2] ?? ''} sub={parts[3]} />;
  } else if (parts[0] === 'audit') {
    active = 'audit';
    page = <Audit />;
  } else if (parts[0] === 'users') {
    active = 'users';
    page = <Users />;
  } else if (parts[0] === 'account') {
    active = 'account';
    page = <Account />;
  } else page = <Home />;
  return (
    <MeCtx.Provider value={me}>
      <Layout active={active}>{page}</Layout>
    </MeCtx.Provider>
  );
}
