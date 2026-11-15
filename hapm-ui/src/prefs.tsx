// Preferências do usuário neste navegador: idioma e tema (light | auto | dark).
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { EN } from './i18n.en';

export type Lang = 'pt-BR' | 'en-US';
export type Theme = 'light' | 'auto' | 'dark';

function read(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}
function write(key: string, v: string) {
  try {
    localStorage.setItem(key, v);
  } catch {
    /* navegação privada etc. */
  }
}

export function initialLang(): Lang {
  const s = read('hapm.lang');
  if (s === 'pt-BR' || s === 'en-US') return s;
  return (navigator.language || 'pt-BR').toLowerCase().startsWith('pt') ? 'pt-BR' : 'en-US';
}
export function initialTheme(): Theme {
  const s = read('hapm.theme');
  return s === 'light' || s === 'dark' ? s : 'auto';
}
export function applyTheme(t: Theme) {
  document.documentElement.dataset.theme = t;
}

type Vars = Record<string, string | number | null | undefined>;
export type T = (pt: string, vars?: Vars) => string;

/**
 * Mensagens de status geradas pelo servidor (algumas gravadas no banco) que aparecem na operação normal.
 * Traduzidas por padrão; qualquer outra mensagem do servidor é exibida como veio.
 */
const SERVER_EN: [RegExp, string][] = [
  [/^nenhum nó online; será aplicado quando os nós conectarem$/, 'no nodes online; it will be applied when nodes connect'],
  [/^aguardando (.+) aplicar v(\d+)$/, 'waiting for $1 to apply v$2'],
  [/^v(\d+) falhou; mantendo última versão boa$/, 'v$1 failed; keeping the last good version'],
  [/^timeout aguardando: (.*)$/, 'timeout waiting for: $1'],
  [/^haproxy -c falhou: /, 'haproxy -c failed: '],
  [/^reload do haproxy falhou \(rollback para (.+?)\): /, 'haproxy reload failed (rolled back to $1): '],
  [/^\[EMERGÊNCIA\] /, '[EMERGENCY] '],
  [/^rollback para v(\d+)/, 'rollback to v$1'],
  [/ \(pedido #(\d+)\)$/, ' (request #$1)'],
];
export function translateServer(lang: Lang, msg?: string | null) {
  if (!msg || lang !== 'en-US') return msg ?? '';
  let out = msg;
  for (const [re, en] of SERVER_EN) out = out.replace(re, en);
  return out;
}

type Prefs = { lang: Lang; setLang: (l: Lang) => void; theme: Theme; setTheme: (t: Theme) => void; t: T; ts: (msg?: string | null) => string; locale: string };
const Ctx = createContext<Prefs | null>(null);

/** Textos em pt-BR são a chave; o dicionário en-US traduz. Placeholders: {nome}. */
export function translate(lang: Lang, pt: string, vars?: Vars) {
  let s = lang === 'en-US' ? EN[pt] ?? pt : pt;
  if (vars) for (const [k, v] of Object.entries(vars)) s = s.split(`{${k}}`).join(String(v ?? ''));
  return s;
}

export function PrefsProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(initialLang);
  const [theme, setThemeState] = useState<Theme>(initialTheme);
  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);
  useEffect(() => applyTheme(theme), [theme]);
  const setLang = useCallback((l: Lang) => {
    write('hapm.lang', l);
    setLangState(l);
  }, []);
  const setTheme = useCallback((t: Theme) => {
    write('hapm.theme', t);
    setThemeState(t);
  }, []);
  const value = useMemo<Prefs>(() => ({ lang, setLang, theme, setTheme, t: (pt, vars) => translate(lang, pt, vars), ts: (m) => translateServer(lang, m), locale: lang }), [lang, theme, setLang, setTheme]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function usePrefs() {
  const p = useContext(Ctx);
  if (!p) throw new Error('PrefsProvider ausente');
  return p;
}
export const useT = () => usePrefs().t;
/** Traduz mensagens de status conhecidas vindas do servidor. */
export const useTs = () => usePrefs().ts;

/** Formatação dependente do idioma. */
export function useFmt() {
  const { t, locale } = usePrefs();
  return {
    date: (s?: string | null) => (s ? new Date(s).toLocaleString(locale) : '—'),
    ago: (ms?: number | null) => {
      if (!ms) return t('nunca');
      const s = Math.round((Date.now() - ms) / 1000);
      return s < 60 ? t('{n}s atrás', { n: s }) : s < 3600 ? t('{n}min atrás', { n: Math.round(s / 60) }) : t('{n}h atrás', { n: Math.round(s / 3600) });
    },
  };
}

/** Seletores de tema e idioma (barra lateral e tela de login). */
export function PrefsSwitcher() {
  const { lang, setLang, theme, setTheme, t } = usePrefs();
  const themes: [Theme, string, string][] = [['light', '☀', t('Claro')], ['auto', '◐', t('Automático')], ['dark', '☾', t('Escuro')]];
  return (
    <div className="prefs">
      <div className="seg" role="radiogroup" aria-label={t('Tema')}>
        {themes.map(([v, icon, label]) => (
          <button key={v} type="button" role="radio" aria-checked={theme === v} title={label} className={theme === v ? 'on' : ''} onClick={() => setTheme(v)}>
            <span aria-hidden>{icon}</span> {label}
          </button>
        ))}
      </div>
      <div className="seg" role="radiogroup" aria-label={t('Idioma')}>
        {(['pt-BR', 'en-US'] as Lang[]).map((l) => (
          <button key={l} type="button" role="radio" aria-checked={lang === l} className={lang === l ? 'on' : ''} onClick={() => setLang(l)}>{l}</button>
        ))}
      </div>
    </div>
  );
}
