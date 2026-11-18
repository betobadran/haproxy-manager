// Gera grafana/dashboards/hapm-overview.json.  Uso: node build-dashboard.mjs
import fs from 'node:fs';

const DS = { type: 'prometheus', uid: 'prometheus' };
const F = 'hapm_cluster=~"$cluster",hapm_node=~"$node"';
const FC = 'hapm_cluster=~"$cluster"';
const USER = 'proxy!~"hapm_.*"';
let id = 1;
let y = 0;
const panels = [];

const target = (expr, legend = '', extra = {}) => ({ datasource: DS, expr, legendFormat: legend, ...extra });

function row(title) {
  panels.push({ id: id++, type: 'row', title, collapsed: false, gridPos: { h: 1, w: 24, x: 0, y }, panels: [] });
  y += 1;
}
let x = 0;
let rowH = 0;
function panel(p, w, h) {
  if (x + w > 24) {
    x = 0;
    y += rowH;
    rowH = 0;
  }
  (p.targets || []).forEach((t, i) => (t.refId = String.fromCharCode(65 + i)));
  panels.push({ id: id++, datasource: DS, gridPos: { h, w, x, y }, ...p });
  x += w;
  rowH = Math.max(rowH, h);
}
function endRow() {
  y += rowH;
  x = 0;
  rowH = 0;
}

const steps = (...s) => ({ mode: 'absolute', steps: s.map(([color, value]) => ({ color, value })) });
const stat = (title, expr, opts = {}) => ({
  type: 'stat', title, targets: [target(expr, opts.legend || '', opts.target)],
  options: { reduceOptions: { calcs: ['lastNotNull'], values: false }, colorMode: opts.colorMode || 'background', textMode: opts.textMode || 'auto', graphMode: 'none' },
  fieldConfig: { defaults: { unit: opts.unit || 'none', thresholds: opts.thresholds || steps(['green', null]), mappings: opts.mappings || [] }, overrides: [] },
  description: opts.description,
});
const ts = (title, targets, unit = 'short', opts = {}) => ({
  type: 'timeseries', title, targets: targets.map(([e, l]) => target(e, l)),
  fieldConfig: { defaults: { unit, custom: { fillOpacity: opts.fill ?? 10, stacking: { mode: opts.stack ? 'normal' : 'none' }, lineWidth: 1 } }, overrides: opts.overrides || [] },
  options: { legend: { displayMode: 'table', placement: 'right', calcs: ['mean', 'max', 'lastNotNull'] }, tooltip: { mode: 'multi', sort: 'desc' } },
  description: opts.description,
});

// ------------------------------------------------------------ Alta disponibilidade
row('Alta disponibilidade');
panel(stat('MASTER atual (detém o VIP)', `hapm_vrrp_is_master{${FC}} == 1`, { legend: '{{hapm_node}}', textMode: 'name', colorMode: 'value', target: { instant: true },
  description: 'Nó com hapm_vrrp_is_master=1. Vazio = nenhum MASTER (VIP sem dono).' }), 6, 4);
panel(stat('Qtde de MASTERs', `sum(hapm_vrrp_is_master{${FC}}) or vector(0)`, {
  thresholds: steps(['red', null], ['green', 1], ['red', 2]), description: '1 = saudável; 0 = sem VIP; >1 = split-brain' }), 3, 4);
panel(stat('Nós online (manager)', `sum(hapm_node_online{${FC}})`, { thresholds: steps(['red', null], ['orange', 1], ['green', 2]) }), 3, 4);
panel(stat('HAProxy respondendo', `sum(hapm_haproxy_up{${F}})`, { thresholds: steps(['red', null], ['green', 1]) }), 3, 4);
panel(stat('Versão desejada', `max(hapm_cluster_desired_version{${FC}})`, { colorMode: 'value' }), 3, 4);
panel(stat('Última publicação', `max(hapm_cluster_deployment_failed{${FC}})`, {
  mappings: [{ type: 'value', options: { 0: { text: 'OK', color: 'green' }, 1: { text: 'FALHOU', color: 'red' } } }] }), 3, 4);
endRow();
panel({
  type: 'state-timeline', title: 'Estado VRRP por nó', targets: [target(`hapm_vrrp_is_master{${F}}`, '{{hapm_node}}')],
  fieldConfig: { defaults: { mappings: [{ type: 'value', options: { 0: { text: 'BACKUP', color: 'blue' }, 1: { text: 'MASTER', color: 'green' } } }], thresholds: steps(['blue', null]) }, overrides: [] },
  options: { showValue: 'auto', mergeValues: true, rowHeight: 0.8 },
  description: 'Transições de MASTER/BACKUP (failovers) ao longo do tempo.',
}, 12, 7);
panel({
  type: 'table', title: 'Nós: versão aplicada e estado',
  targets: [
    target(`max by (hapm_node) (hapm_config_applied_version{${F}})`, '', { format: 'table', instant: true }),
    target(`max by (hapm_node) (hapm_config_last_apply_success{${F}})`, '', { format: 'table', instant: true }),
    target(`max by (hapm_node) (hapm_vrrp_is_master{${F}})`, '', { format: 'table', instant: true }),
    target(`max by (hapm_node) (hapm_haproxy_up{${F}})`, '', { format: 'table', instant: true }),
  ],
  transformations: [{ id: 'merge' }, { id: 'organize', options: { excludeByName: { Time: true },
    renameByName: { hapm_node: 'Nó', 'Value #A': 'Versão', 'Value #B': 'Última aplicação OK', 'Value #C': 'MASTER', 'Value #D': 'HAProxy up' } } }],
  fieldConfig: { defaults: { custom: { cellOptions: { type: 'color-text' } }, thresholds: steps(['green', null]) }, overrides: [] },
}, 12, 7);
endRow();

// ------------------------------------------------------------ Tráfego
row('Tráfego (listeners)');
const RI = '$__rate_interval';
panel(ts('Requisições/s por listener', [[`sum by (proxy) (rate(haproxy_frontend_http_requests_total{${F},${USER}}[${RI}]))`, '{{proxy}}']], 'reqps'), 12, 8);
panel(ts('Respostas/s por classe', [[`sum by (code) (rate(haproxy_frontend_http_responses_total{${F},${USER}}[${RI}]))`, '{{code}}']], 'reqps', {
  stack: true, overrides: [['5xx', 'red'], ['4xx', 'orange'], ['2xx', 'green'], ['3xx', 'blue']].map(([n, c]) => ({ matcher: { id: 'byName', options: n }, properties: [{ id: 'color', value: { mode: 'fixed', fixedColor: c } }] })) }), 12, 8);
endRow();
panel(stat('Taxa de 5xx (5m)', `100 * sum(rate(haproxy_frontend_http_responses_total{${F},${USER},code="5xx"}[5m])) / clamp_min(sum(rate(haproxy_frontend_http_responses_total{${F},${USER}}[5m])), 0.001)`,
  { unit: 'percent', thresholds: steps(['green', null], ['orange', 1], ['red', 5]) }), 4, 6);
panel(stat('Conexões atuais', `sum(haproxy_process_current_connections{${F}})`, { colorMode: 'value' }), 4, 6);
panel(ts('Tráfego por nó (req/s) — onde está o VIP', [[`sum by (hapm_node) (rate(haproxy_frontend_http_requests_total{${F},${USER}}[${RI}]))`, '{{hapm_node}}']], 'reqps'), 8, 6);
panel(ts('Bytes/s', [[`sum by (proxy) (rate(haproxy_frontend_bytes_in_total{${F},${USER}}[${RI}]))`, 'in {{proxy}}'], [`-sum by (proxy) (rate(haproxy_frontend_bytes_out_total{${F},${USER}}[${RI}]))`, 'out {{proxy}}']], 'Bps'), 8, 6);
endRow();
panel(ts('Sessões ativas por listener', [[`sum by (proxy) (haproxy_frontend_current_sessions{${F},${USER}})`, '{{proxy}}']]), 12, 7);
panel(ts('Negadas/s (allowlist, rate limit, regras deny)', [[`sum by (proxy) (rate(haproxy_frontend_requests_denied_total{${F},${USER}}[${RI}]))`, '{{proxy}}']], 'reqps'), 12, 7);
endRow();

// ------------------------------------------------------------ Backends
row('Backends');
panel(ts('Servidores ativos por backend', [[`max by (proxy) (haproxy_backend_active_servers{${F},${USER}})`, '{{proxy}}']], 'short', { fill: 0 }), 8, 8);
panel(ts('Tempo médio de resposta', [[`max by (proxy) (haproxy_backend_response_time_average_seconds{${F},${USER}})`, '{{proxy}}']], 's', { fill: 0 }), 8, 8);
panel(ts('Fila (requisições aguardando servidor)', [[`sum by (proxy) (haproxy_backend_current_queue{${F},${USER}})`, '{{proxy}}']], 'short', { fill: 0 }), 8, 8);
endRow();
panel({
  type: 'table', title: 'Servidores fora (DOWN/MAINT)',
  targets: [target(`max by (proxy, server, state) (haproxy_server_status{${F},${USER},state=~"DOWN|MAINT|DRAIN"}) == 1`, '', { format: 'table', instant: true })],
  transformations: [{ id: 'organize', options: { excludeByName: { Time: true, Value: true }, renameByName: { proxy: 'Backend', server: 'Servidor', state: 'Estado' } } }],
}, 12, 7);
panel(ts('Falhas de health-check/s', [[`sum by (proxy, server) (rate(haproxy_server_check_failures_total{${F},${USER}}[${RI}]))`, '{{proxy}}/{{server}}']], 'short', { fill: 0 }), 12, 7);
endRow();

// ------------------------------------------------------------ Certificados
row('Certificados');
panel({
  type: 'bargauge', title: 'Dias até expirar',
  targets: [target(`min by (certificate) ((hapm_certificate_not_after_timestamp_seconds{${FC}} - time()) / 86400)`, '{{certificate}}', { instant: true })],
  fieldConfig: { defaults: { unit: 'd', min: 0, max: 90, thresholds: steps(['red', null], ['orange', 14], ['green', 30]) }, overrides: [] },
  options: { orientation: 'horizontal', displayMode: 'gradient', reduceOptions: { calcs: ['lastNotNull'] } },
}, 24, 6);
endRow();

// ------------------------------------------------------------ Hosts
row('Hosts');
panel(ts('CPU (%)', [[`100 * (1 - avg by (hapm_node) (system_cpu_utilization_ratio{${F},state="idle"}))`, '{{hapm_node}}']], 'percent', { fill: 0 }), 8, 7);
panel(ts('Memória usada (%)', [[`100 * sum by (hapm_node) (system_memory_utilization_ratio{${F},state="used"})`, '{{hapm_node}}']], 'percent', { fill: 0 }), 8, 7);
panel(ts('Load 1m', [[`max by (hapm_node) (system_cpu_load_average_1m{${F}})`, '{{hapm_node}}']], 'short', { fill: 0 }), 8, 7);
endRow();

const varQuery = (name, label, query) => ({
  name, label, type: 'query', datasource: DS, query: { query, refId: name }, definition: query, refresh: 2, includeAll: true, multi: true, current: { text: 'All', value: '$__all' }, allValue: '.*', sort: 1,
});

const dashboard = {
  uid: 'hapm-overview',
  title: 'HAProxy Manager · HAProxy alta disponibilidade',
  tags: ['haproxy', 'keepalived', 'hapm'],
  timezone: 'browser',
  schemaVersion: 39,
  version: 1,
  refresh: '30s',
  time: { from: 'now-6h', to: 'now' },
  templating: { list: [
    varQuery('cluster', 'Cluster', 'label_values(hapm_agent_info, hapm_cluster)'),
    varQuery('node', 'Nó', 'label_values(hapm_agent_info{hapm_cluster=~"$cluster"}, hapm_node)'),
  ] },
  annotations: { list: [{ builtIn: 1, datasource: { type: 'grafana', uid: '-- Grafana --' }, enable: true, hide: true, name: 'Annotations & Alerts', type: 'dashboard' },
    { name: 'Publicações', datasource: DS, enable: true, iconColor: 'purple', expr: `changes(hapm_cluster_desired_version{${FC}}[1m]) > 0`, step: '60s', titleFormat: 'Nova versão publicada', textFormat: '{{hapm_cluster}}' }] },
  panels,
};

const out = new URL('./dashboards/hapm-overview.json', import.meta.url);
fs.writeFileSync(out, JSON.stringify(dashboard, null, 2) + '\n');
console.log(`gerado ${out.pathname} (${panels.length} painéis)`);
