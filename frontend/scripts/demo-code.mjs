// Prints the current two-step verification code of a seeded demo user (local demo only):
//   npm run demo:code alice        (alice | bob | demo | admin; erin has no two-step verification)
// The secrets come from e2e/.state/seed.json, written by the demo seed. A code is valid for the rest
// of its 30-second window and is accepted once.
import { createHmac } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const user = process.argv[2] ?? 'alice';
const state = JSON.parse(readFileSync(resolve(import.meta.dirname, '../e2e/.state/seed.json'), 'utf8'));
const credentials = user === 'admin' ? state.admin : state.users?.[user];
if (!credentials) {
  console.error(`Unknown demo user "${user}". Use alice, bob, demo, erin or admin.`);
  process.exit(1);
}
if (!credentials.totpSecret) {
  console.log(`${credentials.email} has no two-step verification: sign in with the password only.`);
  process.exit(0);
}

const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
let bits = '';
for (const c of credentials.totpSecret.replace(/=+$/, '').toUpperCase()) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));

// A code about to expire is hard to type in time: wait for the next one instead.
const remaining = () => 30 - (Math.floor(Date.now() / 1000) % 30);
if (remaining() < 8) {
  console.log(`Waiting ${remaining()} seconds for a fresh code…`);
  await new Promise((resolve) => setTimeout(resolve, remaining() * 1000 + 200));
}

const seconds = Math.floor(Date.now() / 1000);
const counter = Buffer.alloc(8);
counter.writeBigUInt64BE(BigInt(Math.floor(seconds / 30)));
const hash = createHmac('sha1', key).update(counter).digest();
const offset = hash[hash.length - 1] & 15;
const code = (hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000;
console.log(`${credentials.email}: ${String(code).padStart(6, '0')}  (valid for ${30 - (seconds % 30)} more seconds)`);
