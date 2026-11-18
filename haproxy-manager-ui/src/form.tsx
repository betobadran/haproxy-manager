// Formulários dirigidos por schema: o mesmo componente desenha listeners, backends e configurações.
import type { ReactNode } from 'react';
import { useT } from './prefs';

export type Opt = string | [string, string];
export type FieldDef = {
  key: string;
  label?: string;
  type?: 'text' | 'number' | 'select' | 'checkbox' | 'textarea' | 'tokens' | 'group' | 'list';
  options?: Opt[] | ((ctx: any) => Opt[]);
  default?: any;
  placeholder?: string;
  help?: string;
  mono?: boolean;
  rows?: number;
  fields?: Row[];
  addLabel?: string;
  newItem?: () => any;
  showIf?: (v: any) => boolean;
};
export type Row = FieldDef | FieldDef[];

const defaultOf = (d: FieldDef) => (typeof d.default === 'function' ? d.default() : d.default);

function Field({ def, value, onChange, ctx }: { def: FieldDef; value: any; onChange: (v: any) => void; ctx: any }) {
  const t = useT();
  const v = value === undefined || value === null ? defaultOf(def) : value;
  const label = def.label ? t(def.label) : undefined;
  let node: ReactNode;
  switch (def.type ?? 'text') {
    case 'checkbox':
      node = (
        <label className="cb">
          <input type="checkbox" checked={!!v} onChange={(e) => onChange(e.target.checked)} /> {label}
        </label>
      );
      break;
    case 'select': {
      const opts = typeof def.options === 'function' ? def.options(ctx) : def.options ?? [];
      node = (
        <label className="f">
          <span>{label}</span>
          <select value={v ?? ''} onChange={(e) => onChange(e.target.value)}>
            {opts.map((o) => {
              const [val, lab] = Array.isArray(o) ? o : [o, o];
              return <option key={val} value={val}>{t(lab)}</option>;
            })}
          </select>
        </label>
      );
      break;
    }
    case 'textarea':
      node = (
        <label className="f">
          <span>{label}</span>
          <textarea className={def.mono ? 'mono' : ''} rows={def.rows ?? 4} placeholder={def.placeholder} value={v ?? ''} onChange={(e) => onChange(e.target.value)} />
        </label>
      );
      break;
    case 'tokens':
      node = (
        <label className="f">
          <span>{label}</span>
          <input placeholder={def.placeholder} value={Array.isArray(v) ? v.join(' ') : v ?? ''}
            onChange={(e) => onChange(e.target.value.split(/[\s,]+/).filter(Boolean).length ? e.target.value.split(/[\s,]+/) : [])} />
        </label>
      );
      break;
    case 'number':
      node = (
        <label className="f">
          <span>{label}</span>
          <input type="number" placeholder={def.placeholder} value={v ?? ''} onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))} />
        </label>
      );
      break;
    case 'group':
      node = (
        <fieldset>
          <legend>{label}</legend>
          <Fields rows={def.fields ?? []} value={v ?? {}} onChange={onChange} ctx={ctx} />
        </fieldset>
      );
      break;
    case 'list': {
      const items: any[] = Array.isArray(v) ? v : [];
      node = (
        <fieldset>
          <legend>{label}</legend>
          {items.map((item, i) => (
            <div className="item" key={i}>
              <button type="button" className="small danger rm" onClick={() => onChange(items.filter((_, j) => j !== i))}>{t('remover')}</button>
              <Fields rows={def.fields ?? []} value={item ?? {}} onChange={(nv) => onChange(items.map((x, j) => (j === i ? nv : x)))} ctx={ctx} />
            </div>
          ))}
          <button type="button" className="small" onClick={() => onChange([...items, def.newItem ? def.newItem() : {}])}>+ {t(def.addLabel ?? 'adicionar')}</button>
        </fieldset>
      );
      break;
    }
    default:
      node = (
        <label className="f">
          <span>{label}</span>
          <input placeholder={def.placeholder} value={v ?? ''} onChange={(e) => onChange(e.target.value)} />
        </label>
      );
  }
  return (
    <>
      {node}
      {def.help && <div className="muted" style={{ fontSize: 12, marginTop: -6, marginBottom: 8 }}>{t(def.help)}</div>}
    </>
  );
}

/** Aplica os padrões do schema num objeto (para que os campos "default" sejam enviados). */
export function withDefaults(rows: Row[], value: any): any {
  const out = { ...(value ?? {}) };
  for (const d of rows.flat()) {
    if (out[d.key] === undefined && d.default !== undefined) out[d.key] = defaultOf(d);
    if (d.type === 'group') out[d.key] = withDefaults(d.fields ?? [], out[d.key]);
    if (d.type === 'list' && Array.isArray(out[d.key])) out[d.key] = out[d.key].map((x: any) => withDefaults(d.fields ?? [], x));
  }
  return out;
}

export function Fields({ rows, value, onChange, ctx }: { rows: Row[]; value: any; onChange: (v: any) => void; ctx: any }) {
  const val = value ?? {};
  const set = (key: string) => (nv: any) => onChange({ ...val, [key]: nv });
  const visible = (d: FieldDef) => !d.showIf || d.showIf(withDefaults(rows, val));
  return (
    <div>
      {rows.map((row, i) => {
        const defs = (Array.isArray(row) ? row : [row]).filter(visible);
        if (!defs.length) return null;
        const fields = defs.map((d) => <Field key={d.key} def={d} value={val[d.key]} onChange={set(d.key)} ctx={ctx} />);
        return defs.length > 1 ? <div className="row" key={i}>{fields.map((f, j) => <div key={j}>{f}</div>)}</div> : <div key={i}>{fields}</div>;
      })}
    </div>
  );
}
