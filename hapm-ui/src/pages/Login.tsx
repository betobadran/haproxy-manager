import { useState } from 'react';
import { api, auth } from '../api';
import { useToast } from '../ui';

export function Login() {
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const toast = useToast();
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
  return (
    <form className="panel login" onSubmit={submit}>
      <h3>HAPM · entrar</h3>
      <label className="f"><span>Usuário</span><input value={username} autoComplete="username" onChange={(e) => setUsername(e.target.value)} /></label>
      <label className="f"><span>Senha</span><input type="password" autoFocus autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>
      <button className="primary" type="submit">Entrar</button>
    </form>
  );
}
