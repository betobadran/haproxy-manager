// Preferências do usuário neste navegador: idioma e tema (light | auto | dark).
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
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

export type DateFormat = 'auto' | 'DD/MM/YYYY' | 'MM/DD/YYYY' | 'YYYY-MM-DD' | 'DD.MM.YYYY';
export type TimeFormat = 'auto' | '24h' | '12h';
/** 'browser' = fuso do navegador; senão um IANA (America/Sao_Paulo, UTC...). */
export type ServerPrefs = { lang?: Lang | null; theme?: Theme | null; dateFormat?: DateFormat | null; timeFormat?: TimeFormat | null; timeZone?: string | null };

type Prefs = {
  lang: Lang; setLang: (l: Lang) => void;
  theme: Theme; setTheme: (t: Theme) => void;
  dateFormat: DateFormat; timeFormat: TimeFormat; timeZone: string;
  setDisplay: (p: { dateFormat?: DateFormat; timeFormat?: TimeFormat; timeZone?: string }) => void;
  /** Aplica preferências vindas do servidor (login) e registra quem as salva remotamente. */
  attachServer: (prefs: ServerPrefs | null, save: ((p: ServerPrefs) => void) | null) => void;
  t: T; ts: (msg?: string | null) => string; locale: string;
  /** Fuso efetivo (IANA) */
  zone: string;
};
const Ctx = createContext<Prefs | null>(null);

/** Textos em pt-BR são a chave; o dicionário en-US traduz. Placeholders: {nome}. */
export function translate(lang: Lang, pt: string, vars?: Vars) {
  let s = lang === 'en-US' ? EN[pt] ?? pt : pt;
  if (vars) for (const [k, v] of Object.entries(vars)) s = s.split(`{${k}}`).join(String(v ?? ''));
  return s;
}

export const browserZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

export function PrefsProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(initialLang);
  const [theme, setThemeState] = useState<Theme>(initialTheme);
  const [dateFormat, setDateFormat] = useState<DateFormat>(() => (read('hapm.dateFormat') as DateFormat) || 'auto');
  const [timeFormat, setTimeFormat] = useState<TimeFormat>(() => (read('hapm.timeFormat') as TimeFormat) || 'auto');
  const [timeZone, setTimeZone] = useState<string>(() => read('hapm.timeZone') || 'browser');
  const [saver, setSaver] = useState<{ fn: ((p: ServerPrefs) => void) | null }>({ fn: null });
  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);
  useEffect(() => applyTheme(theme), [theme]);

  const current = (o: Partial<ServerPrefs> = {}): ServerPrefs => ({ lang, theme, dateFormat, timeFormat, timeZone, ...o });
  const push = (o: Partial<ServerPrefs>) => saver.fn?.(current(o));
  const setLang = (l: Lang) => {
    write('hapm.lang', l);
    setLangState(l);
    push({ lang: l });
  };
  const setTheme = (t: Theme) => {
    write('hapm.theme', t);
    setThemeState(t);
    push({ theme: t });
  };
  const setDisplay = (p: { dateFormat?: DateFormat; timeFormat?: TimeFormat; timeZone?: string }) => {
    if (p.dateFormat) { write('hapm.dateFormat', p.dateFormat); setDateFormat(p.dateFormat); }
    if (p.timeFormat) { write('hapm.timeFormat', p.timeFormat); setTimeFormat(p.timeFormat); }
    if (p.timeZone) { write('hapm.timeZone', p.timeZone); setTimeZone(p.timeZone); }
    push(p);
  };
  const attachServer = useCallback((prefs: ServerPrefs | null, save: ((p: ServerPrefs) => void) | null) => {
    if (prefs) {
      if (prefs.lang) { write('hapm.lang', prefs.lang); setLangState(prefs.lang); }
      if (prefs.theme) { write('hapm.theme', prefs.theme); setThemeState(prefs.theme); }
      if (prefs.dateFormat) { write('hapm.dateFormat', prefs.dateFormat); setDateFormat(prefs.dateFormat); }
      if (prefs.timeFormat) { write('hapm.timeFormat', prefs.timeFormat); setTimeFormat(prefs.timeFormat); }
      if (prefs.timeZone) { write('hapm.timeZone', prefs.timeZone); setTimeZone(prefs.timeZone); }
    }
    setSaver({ fn: save });
  }, []);
  const zone = timeZone === 'browser' ? browserZone() : timeZone;
  const value: Prefs = {
    lang, setLang, theme, setTheme, dateFormat, timeFormat, timeZone, setDisplay, attachServer, zone,
    t: (pt, vars) => translate(lang, pt, vars), ts: (m) => translateServer(lang, m), locale: lang,
  };
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

/** Partes de data/hora no fuso escolhido. */
function parts(d: Date, zone: string, h12: boolean) {
  const f = new Intl.DateTimeFormat('en-US', {
    timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: h12,
  });
  const o: Record<string, string> = {};
  for (const p of f.formatToParts(d)) o[p.type] = p.value;
  if (!h12 && o.hour === '24') o.hour = '00';
  return o;
}

/** Deslocamento do fuso em relação ao UTC, ex.: "UTC-03:00". */
export function zoneOffset(zone: string, d = new Date()) {
  const p = parts(d, zone, false);
  const asUtc = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second);
  const min = Math.round((asUtc - Math.floor(d.getTime() / 1000) * 1000) / 60000);
  const sign = min < 0 ? '-' : '+';
  const a = Math.abs(min);
  return `UTC${sign}${String(Math.floor(a / 60)).padStart(2, '0')}:${String(a % 60).padStart(2, '0')}`;
}

/** Formatação de data/hora conforme as preferências do usuário (formato e fuso). */
export function useFmt() {
  const { t, lang, dateFormat, timeFormat, zone } = usePrefs();
  const df: Exclude<DateFormat, 'auto'> = dateFormat !== 'auto' ? dateFormat : lang === 'en-US' ? 'MM/DD/YYYY' : 'DD/MM/YYYY';
  const h12 = timeFormat === '12h' || (timeFormat === 'auto' && lang === 'en-US');
  const datePart = (p: Record<string, string>) =>
    df === 'DD/MM/YYYY' ? `${p.day}/${p.month}/${p.year}` : df === 'MM/DD/YYYY' ? `${p.month}/${p.day}/${p.year}`
      : df === 'DD.MM.YYYY' ? `${p.day}.${p.month}.${p.year}` : `${p.year}-${p.month}-${p.day}`;
  const timePart = (p: Record<string, string>, secs: boolean) =>
    `${p.hour}:${p.minute}${secs ? `:${p.second}` : ''}${h12 ? ` ${p.dayPeriod}` : ''}`;
  const toDate = (v: string | number | Date) => (v instanceof Date ? v : new Date(v));
  return {
    zone,
    /** data e hora completas */
    date: (v?: string | number | Date | null) => {
      if (v == null || v === '') return '—';
      const p = parts(toDate(v), zone, h12);
      return `${datePart(p)} ${timePart(p, true)}`;
    },
    /** só a hora (eixos de gráficos) */
    time: (v: string | number | Date, secs = false) => timePart(parts(toDate(v), zone, h12), secs),
    /** só a data */
    day: (v: string | number | Date) => datePart(parts(toDate(v), zone, h12)),
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
