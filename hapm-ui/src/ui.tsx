import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';

// ------------------------------------------------------------------ toasts
type Toast = { id: number; msg: string; err: boolean };
const ToastCtx = createContext<(msg: string, err?: boolean) => void>(() => {});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<Toast[]>([]);
  const seq = useRef(0);
  const push = useCallback((msg: string, err = false) => {
    const id = ++seq.current;
    setItems((x) => [...x, { id, msg, err }]);
    setTimeout(() => setItems((x) => x.filter((t) => t.id !== id)), err ? 8000 : 3500);
  }, []);
  return (
    <ToastCtx.Provider value={push}>
      {children}
      {items.map((t, i) => (
        <div key={t.id} className={`toast${t.err ? ' err' : ''}`} style={{ bottom: 18 + i * 64 }}>{t.msg}</div>
      ))}
    </ToastCtx.Provider>
  );
}
export const useToast = () => useContext(ToastCtx);

/** Executa uma ação assíncrona mostrando erros como toast. */
export function useAction() {
  const toast = useToast();
  return useCallback(
    (fn: () => Promise<unknown>) => async () => {
      try {
        await fn();
      } catch (e: any) {
        toast(e.message, true);
      }
    },
    [toast],
  );
}

/** Carrega dados (e recarrega a cada `intervalMs`, se informado). */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[], intervalMs?: number) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let alive = true;
    const run = () => fn().then((d) => alive && (setData(d), setError(null)), (e) => alive && setError(e.message));
    run();
    const t = intervalMs ? setInterval(run, intervalMs) : undefined;
    return () => {
      alive = false;
      if (t) clearInterval(t);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);
  return { data, error, reload: () => setTick((x) => x + 1) };
}

// ------------------------------------------------------------------ badges e formatação
const STATUS: Record<string, [string, string]> = {
  succeeded: ['sucesso', 'b-ok'], failed: ['falhou', 'b-err'], in_progress: ['em andamento', 'b-acc'], superseded: ['substituído', 'b-muted'],
  pending: ['emitindo', 'b-acc'], issued: ['emitido', 'b-ok'], error: ['erro', 'b-err'],
  MASTER: ['MASTER', 'b-ok'], BACKUP: ['BACKUP', 'b-acc'], FAULT: ['FAULT', 'b-err'], STOP: ['STOP', 'b-err'], UNKNOWN: ['?', 'b-muted'],
};
export function Badge({ s, text }: { s?: string | null; text?: string }) {
  const [t, c] = STATUS[s ?? ''] || [s ?? '—', 'b-muted'];
  return <span className={`badge ${c}`}>{text ?? t}</span>;
}

export const ago = (ms?: number | null) => {
  if (!ms) return 'nunca';
  const s = Math.round((Date.now() - ms) / 1000);
  return s < 60 ? `${s}s atrás` : s < 3600 ? `${Math.round(s / 60)}min atrás` : `${Math.round(s / 3600)}h atrás`;
};
export const fmtDate = (s?: string | null) => (s ? new Date(s).toLocaleString('pt-BR') : '—');
export const daysLeft = (s?: string | null) => (s ? Math.floor((Date.parse(s) - Date.now()) / 86400000) : null);
