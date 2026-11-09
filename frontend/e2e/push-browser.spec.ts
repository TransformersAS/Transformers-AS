import { test, expect } from '@playwright/test';

test.beforeEach(async({page,request,context})=>{
  await request.get('/__test/reset');await context.grantPermissions(['notifications']);
  await page.route(/^https:\/\/(?:fonts\.googleapis\.com|fonts\.gstatic\.com|images\.unsplash\.com)\//,route=>route.abort());
  // Only the browser subscription endpoint is controlled; the Angular worker and permission APIs are real.
  await page.addInitScript(()=>{
    let saved:any=null;
    const sub:any={endpoint:'https://fcm.googleapis.com/fcm/send/browser-test',expirationTime:null,
      unsubscribe:async()=>{saved=null;return true;},
      toJSON:()=>({endpoint:'https://fcm.googleapis.com/fcm/send/browser-test',keys:{p256dh:'test-key',auth:'test-auth'}})};
    PushManager.prototype.subscribe=async()=>{saved=sub;return sub;};
    PushManager.prototype.getSubscription=async()=>saved;
  });
});
async function openInbox(page:any) {
  await page.goto('/',{waitUntil:'domcontentloaded'});
  await page.getByRole('button',{name:'Abrir notificaciones',exact:true}).click();
  await expect(page.getByRole('button',{name:'Activar notificaciones del navegador',exact:true})).toBeVisible();
}
test('explicit consent registers and can disable the browser subscription',async({page,request})=>{
  await openInbox(page);
  await page.getByRole('button',{name:'Activar notificaciones del navegador',exact:true}).click();
  await expect(page.getByRole('dialog',{name:'Notificaciones',exact:true}).getByRole('status').filter({hasText:'activadas'})).toContainText('activadas');
  expect((await (await request.get('/__test/stats')).json()).registered).toBe(true);
  await page.getByRole('button',{name:'Desactivar notificaciones del navegador',exact:true}).click();
  await expect(page.getByRole('dialog',{name:'Notificaciones',exact:true}).getByRole('status').filter({hasText:'desactivadas'})).toContainText('desactivadas');
  expect((await (await request.get('/__test/stats')).json()).registered).toBe(false);
});
test('denied permission does not register anything on the backend',async({page,request})=>{
  await page.addInitScript(()=>{Notification.requestPermission=async()=> 'denied';});
  await openInbox(page);await page.getByRole('button',{name:'Activar notificaciones del navegador',exact:true}).click();
  await expect(page.getByRole('dialog',{name:'Notificaciones',exact:true}).getByRole('status').filter({hasText:'No autorizaste'})).toContainText('No autorizaste');
  expect((await (await request.get('/__test/stats')).json()).subscriptions).toBe(0);
});
test('production service worker displays a received Web Push payload',async({page,context})=>{
  const cdp=await context.newCDPSession(page);let registrationId='';
  cdp.on('ServiceWorker.workerRegistrationUpdated',event=>{
    const registration=event.registrations.find((r:any)=>!r.isDeleted);if(registration) registrationId=registration.registrationId;
  });
  await cdp.send('ServiceWorker.enable');await openInbox(page);
  await page.getByRole('button',{name:'Activar notificaciones del navegador',exact:true}).click();
  await expect(page.getByRole('dialog',{name:'Notificaciones',exact:true}).getByRole('status').filter({hasText:'activadas'})).toContainText('activadas');
  await expect.poll(()=>registrationId).not.toBe('');
  await cdp.send('ServiceWorker.deliverPushMessage',{origin:new URL(page.url()).origin,registrationId,
    data:JSON.stringify({notification:{title:'Marketplace',body:'Tienes una nueva notificación.',tag:'push-browser-test',data:{onActionClick:{default:{operation:'openWindow',url:'/?notificaciones=1'}}}}})});
  await expect.poll(()=>page.evaluate(async()=>{
    const registration=await navigator.serviceWorker.ready;
    return (await registration.getNotifications({tag:'push-browser-test'})).map(n=>n.title);
  })).toContain('Marketplace');
});
test('push click link opens the authenticated inbox',async({page})=>{
  await page.goto('/?notificaciones=1',{waitUntil:'domcontentloaded'});
  await expect(page.getByRole('dialog',{name:'Notificaciones',exact:true})).toBeVisible();
  await expect(page).not.toHaveURL(/notificaciones=1/);
});
