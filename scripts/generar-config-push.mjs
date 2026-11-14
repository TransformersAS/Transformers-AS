// Run from the repository root: node scripts/generar-config-push.mjs
// Uses Node's built-in crypto. Secrets are written to an ignored file, never printed.
import { generateKeyPairSync, randomBytes } from 'node:crypto';
import { writeFileSync, existsSync } from 'node:fs';
if (existsSync('.env.push')) throw new Error('.env.push ya existe. Se conserva para no invalidar las suscripciones.');
const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const pub=publicKey.export({format:'jwk'}), priv=privateKey.export({format:'jwk'});
const encoded=Buffer.concat([Buffer.from([4]),Buffer.from(pub.x,'base64url'),Buffer.from(pub.y,'base64url')]).toString('base64url');
writeFileSync('.env.push',[
  `WEB_PUSH_PUBLIC_KEY=${encoded}`,`WEB_PUSH_PRIVATE_KEY=${priv.d}`,
  'WEB_PUSH_SUBJECT=mailto:admin@example.com', 'RABBITMQ_USER=marketplace_push',
  `RABBITMQ_PASSWORD=${randomBytes(32).toString('base64url')}`,''
].join('\n'),{flag:'wx',mode:0o600});
console.log('Configuración creada en .env.push. No subas este archivo a Git ni regeneres las claves en cada arranque.');
