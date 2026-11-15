import { useEffect, useState, type ReactNode } from 'react';
import { api, auth } from './api';
import { useLoad } from './ui';
import { Login } from './pages/Login';
import { Home } from './pages/Home';
import { ClusterPage } from './pages/Cluster';
import { Audit, Account } from './pages/System';

function useHash() {
  const [hash, setHash] = useState(location.hash);
  useEffect(() => {
    const on = () => setHash(location.hash);
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return hash.replace(/^#\/?/, '').split('/').filter(Boolean);
}

function Layout({ active, children }: { active: string; children: ReactNode }) {
  const { data: clusters } = useLoad<any[]>(() => api('GET', '/clusters'), [active], 15000);
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
        <a href="#/account" className={active === 'account' ? 'active' : ''}>Conta</a>
        <a href="#/login" onClick={logout}>Sair</a>
      </nav>
      <main className="main">{children}</main>
    </div>
  );
}

export function App() {
  const parts = useHash();
  if (!auth.token || parts[0] === 'login') return <Login />;
  if (parts[0] === 'c' && parts[1])
    return (
      <Layout active={parts[1]}>
        <ClusterPage key={parts[1]} cid={parts[1]} tab={parts[2] ?? ''} sub={parts[3]} />
      </Layout>
    );
  if (parts[0] === 'audit') return <Layout active="audit"><Audit /></Layout>;
  if (parts[0] === 'account') return <Layout active="account"><Account /></Layout>;
  return <Layout active="home"><Home /></Layout>;
}
