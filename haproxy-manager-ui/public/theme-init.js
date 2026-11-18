// Aplica o tema salvo antes da primeira pintura (evita piscar). Arquivo externo por causa do CSP.
try { document.documentElement.dataset.theme = localStorage.getItem('hapm.theme') || 'auto'; } catch (e) { document.documentElement.dataset.theme = 'auto'; }
