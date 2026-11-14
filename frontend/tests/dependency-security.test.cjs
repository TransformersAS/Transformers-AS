const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs/promises'),os=require('node:os'),path=require('node:path');
const {createRequire}=require('node:module');
const root=process.env.DEPENDENCY_PROJECT||path.resolve(__dirname,'..');
const req=createRequire(path.join(root,'package.json'));
async function temp(t){const dir=await fs.mkdtemp(path.join(os.tmpdir(),'rnf-deps-'));t.after(()=>fs.rm(dir,{recursive:true,force:true}));return dir;}
test('Capacitor extracts a genuine tar template with its installed tar',async t=>{
 const dir=await temp(t);await fs.mkdir(path.join(dir,'src'));await fs.writeFile(path.join(dir,'src','template.txt'),'fixture');
 const capRequire=createRequire(req.resolve('@capacitor/cli/package.json'));const tar=capRequire('tar');
 const file=path.join(dir,'template.tgz');await tar.c({gzip:true,file,cwd:path.join(dir,'src')},['template.txt']);
 await capRequire('./dist/util/template.js').extractTemplate(file,path.join(dir,'out'));assert.equal(await fs.readFile(path.join(dir,'out','template.txt'),'utf8'),'fixture');
});
test('cacache writes, enumerates and reads content after glob replacement',async t=>{
 const dir=await temp(t),cache=req('cacache');await cache.put(dir,'fixture-key','fixture');const entries=await cache.ls(dir);assert(entries['fixture-key']);assert.equal((await cache.get(dir,'fixture-key')).data.toString(),'fixture');
});
test('ignore-walk preserves inclusion and exclusion patterns',async t=>{
 const dir=await temp(t);await fs.writeFile(path.join(dir,'.npmignore'),'secret.txt\n');await fs.writeFile(path.join(dir,'public.txt'),'public');await fs.writeFile(path.join(dir,'secret.txt'),'private');const files=await req('ignore-walk')({path:dir,ignoreFiles:['.npmignore']});assert(files.includes('public.txt'));assert(!files.includes('secret.txt'));
});
test('TUF delegated role keeps path boundaries after minimatch replacement',()=>{
 const {DelegatedRole}=req('@tufjs/models/dist/role.js');const r=new DelegatedRole({keyIDs:['fixture'],threshold:1,name:'test',terminating:false,paths:['packages/*.tgz']});assert(r.isDelegatedPath('packages/a.tgz'));assert(!r.isDelegatedPath('private/a.tgz'));assert(!r.isDelegatedPath('packages/nested/a.tgz'));
});
test('SockJS creates real transport IDs and exchanges WebSocket messages', {timeout:10000},async t=>{
 const http=require('node:http');const sock=req('sockjs');const WebSocket=req('ws');const server=http.createServer();t.after(()=>new Promise(r=>server.close(r)));
 const s=sock.createServer({log:()=>{}});s.on('connection',c=>{assert(c.id);c.on('data',d=>c.write(d));});s.installHandlers(server,{prefix:'/echo'});await new Promise(r=>server.listen(0,'127.0.0.1',r));
 await new Promise((resolve,reject)=>{const ws=new WebSocket(`ws://127.0.0.1:${server.address().port}/echo/000/fixture/websocket`);t.after(()=>ws.terminate());ws.on('error',reject);ws.on('message',data=>{const msg=data.toString();if(msg==='o')ws.send(JSON.stringify(['fixture']));else if(msg.startsWith('a')){try{assert.deepEqual(JSON.parse(msg.slice(1)),['fixture']);ws.close();resolve();}catch(e){reject(e)}}});});
});
