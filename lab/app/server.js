// backend de exemplo: responde com o nome do container, caminho e headers de proxy
const http = require('node:http');
const os = require('node:os');
http.createServer((req, res) => {
  if (req.url === '/health') return res.end('ok');
  res.setHeader('content-type', 'application/json');
  res.end(JSON.stringify({ backend: process.env.NAME || os.hostname(), path: req.url, xff: req.headers['x-forwarded-for'], proto: req.headers['x-forwarded-proto'] }) + '\n');
}).listen(8080);
