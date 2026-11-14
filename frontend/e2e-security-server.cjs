'use strict';
// Servir el build real sin transformaciones Vite que invaliden sus hashes SRI.
// Las APIs de estas suites se interceptan en sus specs; nunca se reenvían a otra base.
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'dist/browser');
const policy=fs.readFileSync(path.join(__dirname,'nginx.conf'),'utf8').match(/add_header Content-Security-Policy "([^"]+)" always;/)[1];
const types={'.html':'text/html','.js':'application/javascript','.css':'text/css','.json':'application/json','.png':'image/png','.svg':'image/svg+xml','.woff2':'font/woff2','.ttf':'font/ttf','.ico':'image/x-icon'};
if(!fs.existsSync(path.join(root,'index.html')))throw new Error('Ejecutar npm run build antes de las pruebas.');
http.createServer((req,res)=>{
 const nonce=crypto.randomBytes(16).toString('hex');
 res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('X-Frame-Options','DENY');res.setHeader('Content-Security-Policy',policy.replaceAll('$request_id',nonce));
 const url=new URL(req.url,'http://localhost');if(url.pathname.startsWith('/api/')){res.writeHead(404,{'Content-Type':'application/json'});res.end('{"message":"API no simulada por la prueba"}');return;}
 let file=path.resolve(root,'.'+decodeURIComponent(url.pathname));if(!file.startsWith(root+path.sep)||!fs.existsSync(file)||fs.statSync(file).isDirectory())file=path.join(root,'index.html');
 res.setHeader('Content-Type',types[path.extname(file)]||'application/octet-stream');
 if(file.endsWith('.html')){res.setHeader('Cache-Control','no-store');res.end(fs.readFileSync(file,'utf8').replaceAll('__CSP_NONCE__',nonce));}else fs.createReadStream(file).pipe(res);
}).listen(Number(process.env.SECURITY_E2E_PORT||45981),'127.0.0.1');
