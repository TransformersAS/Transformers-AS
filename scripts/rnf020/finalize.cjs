'use strict';
const fs=require('node:fs');const {correlated,writeReport}=require('./evidence.cjs');
function verify(events,backing){
 for(const e of events){
  const messages=backing.messages.filter(m=>m.message_id===e.message_id && m.text===e.token && m.conversation_id===e.conversation_id && m.sender_id===(e.direction==='buyer_to_seller'?1:2));
  e.persisted=messages.length===1;
  const rows=messages.flatMap(m=>backing.notifications.filter(n=>n.event_key===`conversation-message-${m.message_id}`).map(n=>({...m,...n})));
  e.correlation_verified=correlated(e,rows);
  if(!e.correlation_verified){e.notification_verified=false;if(!e.errors.includes('NOTIFICATION_DB_CORRELATION_FAILED'))e.errors.push('NOTIFICATION_DB_CORRELATION_FAILED');}
  if(!e.persisted){e.functional_error=true;if(!e.errors.includes('MESSAGE_PERSISTENCE_FAILED'))e.errors.push('MESSAGE_PERSISTENCE_FAILED');}
 }
 return events;
}
function finalize(directory){
 const read=(name,fallback)=>{const path=directory+'/'+name;return fs.existsSync(path)?JSON.parse(fs.readFileSync(path)):fallback;};
 const metadata=read('metadata.json',{}),backing=read('backing.json',{messages:[],notifications:[]});
 const smoke=read('results/smoke-raw.json',{events:[],complete:false}),raw=read('results/measurement-raw.json',{events:[],complete:false});
 verify(smoke.events,backing);verify(raw.events,backing);
 metadata.complete=raw.complete && metadata.browser_exit_code===0 && metadata.cleanup?.passed===true;
 metadata.browser_version=raw.browser_version||smoke.browser_version;metadata.clock=raw.clock||smoke.clock;
 metadata.preflight=raw.preflight||smoke.preflight;metadata.problems=[...new Set([...(metadata.problems||[]),...smoke.problems||[],...raw.problems||[]])];
 fs.writeFileSync(directory+'/metadata.json',JSON.stringify(metadata,null,2)+'\n');
 const doc=writeReport(directory,metadata,raw.events,smoke.events);
 return {doc,smoke_ok:smoke.complete && smoke.events.length===10 && smoke.events.every(e=>e.persisted && e.correlation_verified && e.message_verified && e.notification_verified && !e.functional_error)};
}
module.exports={verify,finalize};
if(require.main===module){const result=finalize(process.argv[2]);console.log(JSON.stringify({state:result.doc.state,smoke_ok:result.smoke_ok}));}
