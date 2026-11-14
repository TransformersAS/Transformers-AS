// Test-only HTTP fixtures + production Angular assets, bound exclusively to loopback.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, extname } from 'node:path';
import { generateKeyPairSync } from 'node:crypto';
const root=resolve('dist/browser');const port=Number(process.env.PUSH_TEST_PORT || 4321);
const {publicKey}=generateKeyPairSync('ec',{namedCurve:'prime256v1'});const key=publicKey.export({format:'jwk'});
const publicVapid=Buffer.concat([Buffer.from([4]),Buffer.from(key.x,'base64url'),Buffer.from(key.y,'base64url')]).toString('base64url');
let logged=true, registered=false, subscriptions=0;
createServer(async(req,res)=>{
  const path=new URL(req.url,'http://localhost').pathname;
  const json=(body,status=200)=>{res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(body));};
  if(path==='/__test/reset'){logged=true;registered=false;subscriptions=0;return json({});}
  if(path==='/__test/stats') return json({registered,subscriptions});
  if(path==='/api/auth/me') return json(logged?{accountId:1,email:'buyer@example.test',roles:['COMPRADOR'],activeRole:'COMPRADOR'}:{},logged?200:401);
  if(path==='/api/auth/csrf') return json({headerName:'X-CSRF-TOKEN',token:'csrf'});
  if(path==='/api/auth/logout'){logged=false;registered=false;res.writeHead(204);return res.end();}
  if(path==='/api/push/config') return json({enabled:true,publicKey:publicVapid});
  if(path==='/api/push/status') return json({registered});
  if(path==='/api/push/subscriptions'){
    if(req.method==='POST'){registered=true;subscriptions++;return json({id:'browser-subscription'});}
    if(req.method==='DELETE'){registered=false;return json({});}
  }
  if(path==='/api/notifications') return json({items:[],hasMore:false});
  if(path==='/api/cart') return json({items:[],total:0});
  if(path==='/api/recommendations') return json({products:[],strategy:'test'});
  if(path.startsWith('/api/')) return json([]);
  let file=resolve(root,'.'+path);if(!file.startsWith(root)) {res.writeHead(404);return res.end();}
  if(path==='/') file=resolve(root,'index.html');
  try{
    const content=await readFile(file);
    const mime={'.js':'text/javascript','.html':'text/html','.json':'application/json','.css':'text/css'}[extname(file)] || 'application/octet-stream';
    res.writeHead(200,{'Content-Type':mime,'Cache-Control':'no-cache'});res.end(content);
  }catch{res.writeHead(404);res.end();}
}).listen(port,'127.0.0.1');
