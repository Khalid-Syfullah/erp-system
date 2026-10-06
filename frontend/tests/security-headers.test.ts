// @vitest-environment node
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { cacheHeaders, securityHeaders } from '../security-headers.mjs';

// The nginx configuration of the web image must send the same headers as `vite preview`.
describe('SPA security headers (SECURITY.md §10.1)', () => {
  const nginx = readFileSync(resolve(import.meta.dirname, '../../infra/docker/nginx/spa.conf'), 'utf8');

  it('are all set by nginx for the application', () => {
    for (const [name, value] of Object.entries(securityHeaders)) {
      expect(nginx).toContain(`add_header ${name} "${value}" always;`);
    }
    expect(nginx).toContain(`add_header Cache-Control "${cacheHeaders.html}" always;`);
    expect(nginx).toContain(`add_header Cache-Control "${cacheHeaders.assets}" always;`);
  });

  it('allow scripts from the origin only', () => {
    expect(securityHeaders['Content-Security-Policy']).toContain("script-src 'self';");
    expect(securityHeaders['Content-Security-Policy']).not.toContain('unsafe-eval');
    expect(securityHeaders['Content-Security-Policy']).toContain("frame-ancestors 'none'");
  });
});
