import { useEffect, useState, type ReactNode } from 'react';
import { api, auth } from './api';
import { useLoad } from './ui';
import { MeCtx, roleLabel, usePerm, type Me } from './session';
import { PrefsSwitcher, usePrefs, useT } from './prefs';
import { Login } from './pages/Login';
import { Home } from './pages/Home';
import { ClusterPage } from './pages/Cluster';
import { Audit, Account } from './pages/System';
import { Users } from './pages/Users';
import { Alerts, NotifySettings, useUnacked } from './pages/Alerts';

function useHash() {
  const [hash, setHash] = useState(location.hash);
  useEffect(() => {
    const on = () => setHash(location.hash);
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return hash.replace(/^#\/?/, '').split('?')[0].split('/').filter(Boolean);
}


function Layout({ active, children }: { active: string; children: ReactNode }) {
  const { data: clusters } = useLoad<any[]>(() => api('GET', '/clusters'), [active], 15000);
  const { me, isAdmin } = usePerm();
  const t = useT();
  const unacked = useUnacked();
  const logout = () => {
    auth.set(null);
    location.hash = '#/login';
  };
  return (
    <div className="layout">
      <nav className="side">
        <h1>HAProxy Manager</h1>
        <div className="sub">HAProxy + Keepalived</div>
        <a href="#/" className={active === 'home' ? 'active' : ''}>{t('Clusters')}</a>
        <div className="section">{t('Clusters')}</div>
        {(clusters ?? []).map((c) => (
          <a key={c.id} href={`#/c/${c.id}`} className={active === c.id ? 'active' : ''}>{c.name}</a>
        ))}
        <div className="section">{t('Sistema')}</div>
        <a href="#/alerts" className={active === 'alerts' ? 'active' : ''}>
          {t('Alertas')}{unacked > 0 && <span className="count" aria-label={t('{n} alerta(s) pendente(s)', { n: unacked })}>{unacked}</span>}
        </a>
        {isAdmin && <a href="#/notifications" className={active === 'notifications' ? 'active' : ''}>{t('Canais de notificação')}</a>}
        <a href="#/audit" className={active === 'audit' ? 'active' : ''}>{t('Auditoria')}</a>
        {isAdmin && <a href="#/users" className={active === 'users' ? 'active' : ''}>{t('Usuários')}</a>}
        <a href="#/account" className={active === 'account' ? 'active' : ''}>{t('Conta')}</a>
        <a href="#/login" onClick={logout}>{t('Sair')}</a>
        {me && (
          <div className="whoami">
            <b>{me.displayName}</b>
            <div>{me.username} · {me.source}</div>
            <div>{me.roles.map((r) => roleLabel(t, r)).join(', ')}</div>
          </div>
        )}
        <PrefsSwitcher />
      </nav>
      <main className="main">{children}</main>
    </div>
  );
}

function Loading() {
  const t = useT();
  return <p className="muted" style={{ padding: 20 }}>{t('carregando...')}</p>;
}

export function App() {
  const parts = useHash();
  const [me, setMe] = useState<Me | null>(null);
  const { attachServer } = usePrefs();
  const token = auth.token;
  useEffect(() => {
    if (!token) {
      attachServer(null, null);
      return;
    }
    api<Me>('GET', '/me').then((m) => {
      // preferências salvas no servidor valem em qualquer navegador; mudanças são salvas de volta
      attachServer(m.prefs ?? null, (p) => void api('PUT', '/me/prefs', p).catch(() => {}));
      setMe(m);
    }, () => setMe(null));
  }, [token, attachServer]);

  if (parts[0] === 'login') return <Login callback={parts[1] === 'callback'} />;
  if (!token) {
    location.hash = '#/login';
    return null;
  }
  if (!me) return <Loading />;
  let page: ReactNode;
  let active = 'home';
  if (parts[0] === 'c' && parts[1]) {
    active = parts[1];
    page = <ClusterPage key={parts[1]} cid={parts[1]} tab={parts[2] ?? ''} sub={parts[3]} />;
  } else if (parts[0] === 'alerts') {
    active = 'alerts';
    page = <Alerts />;
  } else if (parts[0] === 'notifications') {
    active = 'notifications';
    page = <NotifySettings />;
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
