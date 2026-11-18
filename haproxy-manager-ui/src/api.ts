import { initialLang, translate } from './prefs';

// Cliente da API do manager. O token de sessão fica no localStorage e vai como Bearer.
let token: string | null = localStorage.getItem('hapm.token');

export const auth = {
  get token() {
    return token;
  },
  set(t: string | null) {
    token = t;
    if (t) localStorage.setItem('hapm.token', t);
    else localStorage.removeItem('hapm.token');
  },
};

export class ApiError extends Error {
  constructor(message: string, public status: number) {
    super(message);
  }
}

export async function api<T = any>(method: string, path: string, body?: unknown): Promise<T> {
  const r = await fetch(`/api${path}`, {
    method,
    headers: {
      ...(body !== undefined ? { 'content-type': 'application/json' } : {}),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      'accept-language': initialLang(),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (r.status === 401 && path !== '/auth/login') {
    auth.set(null);
    location.hash = '#/login';
    throw new ApiError(translate(initialLang(), 'sessão expirada'), 401);
  }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new ApiError(j.errors?.join('\n') || j.error || `HTTP ${r.status}`, r.status);
  return j as T;
}
