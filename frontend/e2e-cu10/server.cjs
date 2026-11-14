// Test-only static server and same-origin proxy to the real random-port Spring Boot server.
const http=require('http'), fs=require('fs'), path=require('path');
const root=path.resolve(__dirname,'../dist/browser');
const types={'.html':'text/html','.js':'text/javascript','.css':'text/css','.json':'application/json','.svg':'image/svg+xml','.png':'image/png','.woff2':'font/woff2'};
http.createServer((req,res)=>{
  if(req.url.startsWith('/api/')) {
    const proxy=http.request({hostname:'127.0.0.1',port:process.env.CU10_BACKEND_PORT,path:req.url,method:req.method,headers:req.headers},up=>{res.writeHead(up.statusCode,up.headers);up.pipe(res);});
    proxy.on('error',()=>{res.writeHead(502);res.end();});req.pipe(proxy);return;
  }
  const relative=decodeURIComponent(req.url.split('?')[0]);
  let file=path.resolve(root,'.'+relative);if(!file.startsWith(root+path.sep))file=path.join(root,'index.html');
  if(!fs.existsSync(file)||fs.statSync(file).isDirectory())file=path.join(root,'index.html');
  res.setHeader('Content-Type',types[path.extname(file)]||'application/octet-stream');fs.createReadStream(file).pipe(res);
}).listen(14310,'127.0.0.1');
