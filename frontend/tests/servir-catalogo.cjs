// Servidor local de los archivos compilados para Playwright. Sin proxy a la BD.
const http = require('node:http');
const { readFile } = require('node:fs/promises');
const { resolve, sep, extname } = require('node:path');
const { gzipSync } = require('node:zlib');

const root = resolve(__dirname, '../dist/browser');
const mime = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon' };

http.createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, 'http://127.0.0.1').pathname);
    const file = resolve(root, '.' + (pathname === '/' ? '/index.html' : pathname));
    if (!file.startsWith(root + sep)) { response.writeHead(403).end(); return; }
    let content = await readFile(file);
    const headers = { 'Content-Type': mime[extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' };
    if (content.length > 1024 && /\bgzip\b/.test(request.headers['accept-encoding'] || '')) {
      content = gzipSync(content);
      headers['Content-Encoding'] = 'gzip';
      headers.Vary = 'Accept-Encoding';
    }
    headers['Content-Length'] = content.length;
    response.writeHead(200, headers).end(content);
  } catch { response.writeHead(404).end(); }
}).listen(4401, '127.0.0.1', () => console.log('Catalogo de prueba: http://127.0.0.1:4401'));
