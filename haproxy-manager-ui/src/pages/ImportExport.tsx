// Exportar/importar clusters (JSON/YAML) e importar um haproxy.cfg existente. Somente ADMIN.
import { useState } from 'react';
import { ApiError, auth } from '../api';
import { initialLang } from '../prefs';
import { useT } from '../prefs';
import { useToast } from '../ui';

type Report = { dryRun: boolean; ok: boolean; clusterId?: string; created: string[]; updated: string[]; deleted: string[]; warnings: string[]; errors: string[] };

async function rawCall(method: string, path: string, body?: string, contentType = 'text/plain'): Promise<Response> {
  return fetch(`/api${path}`, {
    method,
    headers: { ...(auth.token ? { authorization: `Bearer ${auth.token}` } : {}), 'content-type': contentType, 'accept-language': initialLang() },
    body,
  });
}

async function postDoc(path: string, text: string): Promise<Report> {
  const r = await rawCall('POST', path, text, text.trim().startsWith('{') ? 'application/json' : 'application/yaml');
  const j = await r.json().catch(() => ({}));
  if (r.status === 422 || r.ok) return j as Report;
  throw new ApiError(j.error || `HTTP ${r.status}`, r.status);
}

function ReportView({ r }: { r: Report }) {
  const t = useT();
  const list = (title: string, items: string[], cls = '') => items.length > 0 && (
    <div><b>{t(title)} ({items.length})</b><ul className={cls}>{items.map((x, i) => <li key={i}>{x}</li>)}</ul></div>
  );
  return (
    <div className={`banner ${r.ok ? (r.warnings.length ? 'banner-warn' : 'banner-ok') : 'banner-err'}`}>
      <b>{!r.ok ? t('Importação recusada: nada foi alterado') : r.dryRun ? t('Simulação: nada foi gravado') : t('Importado')}</b>
      {list('Erros', r.errors)}
      {list('Criados', r.created)}
      {list('Atualizados', r.updated)}
      {list('Removidos', r.deleted)}
      {list('Avisos', r.warnings, 'muted')}
    </div>
  );
}

function readFile(onText: (s: string) => void) {
  return (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (!f) return;
    f.text().then(onText);
    e.target.value = '';
  };
}

/** Painel na aba Configurações do cluster. */
export function ClusterImportExport({ cluster, onChange }: { cluster: any; onChange?: () => void }) {
  const t = useT();
  const toast = useToast();
  const [secrets, setSecrets] = useState(false);
  const [doc, setDoc] = useState('');
  const [mode, setMode] = useState('merge');
  const [withSettings, setWithSettings] = useState(false);
  const [cfg, setCfg] = useState('');
  const [report, setReport] = useState<Report | null>(null);
  const [busy, setBusy] = useState(false);

  const download = async (format: string) => {
    const r = await rawCall('GET', `/clusters/${cluster.id}/export?format=${format}&secrets=${secrets}`);
    if (!r.ok) return toast(`HTTP ${r.status}`);
    const blob = await r.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `hapm-${cluster.name}.${format === 'yaml' ? 'yaml' : 'json'}`;
    a.click();
    URL.revokeObjectURL(a.href);
  };

  const run = (fn: () => Promise<Report>, apply: boolean) => async () => {
    if (apply && mode === 'replace' && !confirm(t('Modo substituir: listeners, backends e certificados que não estão no documento serão REMOVIDOS. Continuar?'))) return;
    setBusy(true);
    try {
      const r = await fn();
      setReport(r);
      if (r.ok && !r.dryRun) onChange?.();
    } catch (e: any) {
      toast(e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="panel">
      <h3>{t('Exportar / importar')}</h3>
      <p className="muted">{t('Backup, promoção entre ambientes (homologação → produção) ou versionamento em git. A importação valida tudo e é atômica: ou entra tudo, ou nada. Depois de importar, revise e publique.')}</p>
      <div className="row" style={{ alignItems: 'center' }}>
        <button onClick={() => download('yaml')}>{t('Exportar YAML')}</button>
        <button onClick={() => download('json')}>{t('Exportar JSON')}</button>
        <label className="cb" style={{ marginBottom: 0 }}><input type="checkbox" checked={secrets} onChange={(e) => setSecrets(e.target.checked)} /> {t('incluir segredos (chaves privadas, senhas VRRP/stats)')}</label>
      </div>

      <h4>{t('Importar exportação do HAPM')}</h4>
      <textarea className="mono" rows={6} value={doc} onChange={(e) => setDoc(e.target.value)} placeholder={t('Cole aqui o YAML/JSON exportado, ou escolha um arquivo')} />
      <div className="row" style={{ alignItems: 'center' }}>
        <input type="file" accept=".yaml,.yml,.json" onChange={readFile(setDoc)} />
        <select value={mode} onChange={(e) => setMode(e.target.value)} aria-label={t('Modo')}>
          <option value="merge">{t('mesclar (cria/atualiza, não remove)')}</option>
          <option value="replace">{t('substituir (remove o que não está no documento)')}</option>
        </select>
        <label className="cb" style={{ marginBottom: 0 }}><input type="checkbox" checked={withSettings} onChange={(e) => setWithSettings(e.target.checked)} /> {t('aplicar também as configurações do cluster')}</label>
      </div>
      <div className="actions">
        <button disabled={!doc.trim() || busy} onClick={run(() => postDoc(`/clusters/${cluster.id}/import?mode=${mode}&settings=${withSettings}&dryRun=true`, doc), false)}>{t('Simular')}</button>
        <button className="primary" disabled={!doc.trim() || busy} onClick={run(() => postDoc(`/clusters/${cluster.id}/import?mode=${mode}&settings=${withSettings}`, doc), true)}>{t('Importar')}</button>
      </div>

      <h4>{t('Importar haproxy.cfg existente')}</h4>
      <p className="muted">{t('Converte frontends, backends, listen, ACLs e regras para o modelo do HAPM (sempre mesclando). O que não tiver equivalente vai para o bloco avançado ou aparece nos avisos — nada some sem aviso.')}</p>
      <textarea className="mono" rows={6} value={cfg} onChange={(e) => setCfg(e.target.value)} placeholder="frontend www&#10;    bind :80&#10;    default_backend web&#10;backend web&#10;    server w1 10.0.0.1:8080 check" />
      <div className="row" style={{ alignItems: 'center' }}>
        <input type="file" accept=".cfg,.conf,.txt" onChange={readFile(setCfg)} />
      </div>
      <div className="actions">
        <button disabled={!cfg.trim() || busy} onClick={run(() => postDoc(`/clusters/${cluster.id}/import-haproxy?dryRun=true`, cfg), false)}>{t('Simular')}</button>
        <button className="primary" disabled={!cfg.trim() || busy} onClick={run(() => postDoc(`/clusters/${cluster.id}/import-haproxy`, cfg), false)}>{t('Importar')}</button>
      </div>
      {report && <ReportView r={report} />}
    </div>
  );
}

/** Painel na página inicial: cria um cluster novo a partir de uma exportação. */
export function ImportNewCluster({ onDone }: { onDone: () => void }) {
  const t = useT();
  const toast = useToast();
  const [doc, setDoc] = useState('');
  const [name, setName] = useState('');
  const [report, setReport] = useState<Report | null>(null);
  const go = (dry: boolean) => async () => {
    try {
      const r = await postDoc(`/clusters/import?dryRun=${dry}${name ? `&name=${encodeURIComponent(name)}` : ''}`, doc);
      setReport(r);
      if (r.ok && !r.dryRun) {
        onDone();
        if (r.clusterId) location.hash = `#/c/${r.clusterId}`;
      }
    } catch (e: any) {
      toast(e.message);
    }
  };
  return (
    <details className="panel">
      <summary><b>{t('Importar cluster (de uma exportação do HAPM)')}</b></summary>
      <label className="f"><span>{t('Nome do novo cluster (vazio = o do documento)')}</span><input value={name} onChange={(e) => setName(e.target.value)} /></label>
      <textarea className="mono" rows={6} value={doc} onChange={(e) => setDoc(e.target.value)} placeholder={t('Cole aqui o YAML/JSON exportado, ou escolha um arquivo')} />
      <div className="row" style={{ alignItems: 'center' }}><input type="file" accept=".yaml,.yml,.json" onChange={readFile(setDoc)} /></div>
      <div className="actions">
        <button disabled={!doc.trim()} onClick={go(true)}>{t('Simular')}</button>
        <button className="primary" disabled={!doc.trim()} onClick={go(false)}>{t('Importar')}</button>
      </div>
      {report && <ReportView r={report} />}
    </details>
  );
}
