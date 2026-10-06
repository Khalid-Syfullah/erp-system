// Generates src/api/schema.d.ts from the backend's OpenAPI document (API.md §18).
// The backend build writes it to backend/build/openapi/openapi.json (OpenApiContractTest).
// Test-only probe endpoints (/api/v1/_test/...) are left out. With --check, compares instead of writing.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import openapiTS, { astToString } from 'openapi-typescript';

const source = resolve(import.meta.dirname, '../../backend/build/openapi/openapi.json');
const target = resolve(import.meta.dirname, '../src/api/schema.d.ts');
const check = process.argv.includes('--check');

if (!existsSync(source)) {
  console.error(`OpenAPI document not found: ${source}\nRun ./gradlew build (or the OpenApiContractTest) in backend/ first.`);
  process.exit(1);
}
const document = JSON.parse(readFileSync(source, 'utf8'));
for (const path of Object.keys(document.paths)) {
  if (path.startsWith('/api/v1/_test/')) delete document.paths[path];
}
const header = '/* Generated from the backend OpenAPI document by scripts/generate-api.mjs. Do not edit. */\n\n';
const output = header + astToString(await openapiTS(document, { alphabetize: true }));
if (check) {
  const committed = existsSync(target) ? readFileSync(target, 'utf8') : '';
  if (committed !== output) {
    console.error('src/api/schema.d.ts is stale: run npm run api:generate');
    process.exit(1);
  }
  console.log('src/api/schema.d.ts is up to date');
} else {
  writeFileSync(target, output);
  console.log(`Wrote ${target}`);
}
