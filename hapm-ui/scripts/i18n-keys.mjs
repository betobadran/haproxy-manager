// Extrai os textos traduzíveis (pt-BR) do código da UI e confere se o dicionário en-US cobre todos.
// Roda no build (npm run build): texto novo sem tradução quebra o build.
import fs from 'node:fs';
import path from 'node:path';

const SRC = path.resolve(import.meta.dirname, '../src');
const files = [];
(function walk(d) {
  for (const f of fs.readdirSync(d)) {
    const p = path.join(d, f);
    if (fs.statSync(p).isDirectory()) walk(p);
    else if (/\.(ts|tsx)$/.test(f) && !f.startsWith('i18n.')) files.push(p);
  }
})(SRC);

const keys = new Set();
// ignora classes CSS (b-ok...) capturadas pelas tuplas de badges
const add = (k) => k && /[A-Za-zÀ-ú]/.test(k) && !/^b-[a-z]+$/.test(k) && keys.add(k.replace(/\\'/g, "'"));
const STR = String.raw`'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)"`;
for (const f of files) {
  const src = fs.readFileSync(f, 'utf8');
  for (const m of src.matchAll(new RegExp(String.raw`\bt\(\s*(?:${STR})`, 'g'))) add(m[1] ?? m[2]);
  for (const m of src.matchAll(new RegExp(String.raw`\b(?:label|addLabel|help):\s*(?:${STR})`, 'g'))) add(m[1] ?? m[2]);
  // tuplas [valor, rótulo] usadas em options, abas, colunas e badges
  for (const m of src.matchAll(/\[\s*'[^']*'\s*,\s*'((?:[^'\\]|\\.)*)'\s*(?:,\s*'[^']*'\s*)?\]/g)) add(m[1]);
  for (const m of src.matchAll(/:\s*\[\s*'((?:[^'\\]|\\.)*)'\s*,\s*'b-[a-z]+'\s*\]/g)) add(m[1]);
  for (const m of src.matchAll(/(?:const cols = [^\n]*)/g)) for (const s of m[0].matchAll(/'([^']+)'/g)) add(s[1]);
  for (const m of src.matchAll(/ROLE_LABEL[^=]*=\s*\{([^}]*)\}/g)) for (const s of m[1].matchAll(/:\s*'([^']+)'/g)) add(s[1]);
}

// lê as chaves do dicionário sem executar TypeScript
const dict = fs.readFileSync(path.join(SRC, 'i18n.en.ts'), 'utf8');
const EN = {};
for (const m of dict.matchAll(/^\s*(?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)")\s*:/gm)) EN[(m[1] ?? m[2]).replace(/\\'/g, "'")] = true;
const missing = [...keys].filter((k) => !(k in EN)).sort();
if (process.argv.includes('--list')) {
  for (const k of [...keys].sort()) console.log(k);
} else if (missing.length) {
  console.error(`i18n: ${missing.length} texto(s) sem tradução en-US em src/i18n.en.ts:`);
  for (const k of missing) console.error(`  ${JSON.stringify(k)}`);
  process.exit(1);
} else {
  console.log(`i18n: ${keys.size} textos, todos traduzidos para en-US`);
}
