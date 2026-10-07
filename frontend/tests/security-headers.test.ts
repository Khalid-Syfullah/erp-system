// @vitest-environment node
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { cacheHeaders, securityHeaders } from '../security-headers.mjs';

// The nginx configuration of the web image must send the same headers as `vite preview`.
describe('SPA security headers (SECURITY.md §10.1)', () => {
  const nginx = readFileSync(resolve(import.meta.dirname, '../../infra/docker/nginx/spa.conf'), 'utf8');

  it('are all set by nginx for the application', () => {
    const csp = securityHeaders['Content-Security-Policy'];
    for (const [name, value] of Object.entries(securityHeaders)) {
      if (name === 'Content-Security-Policy') continue;
      expect(nginx).toContain(`add_header ${name} "${value}" always;`);
    }
    // The CSP's last directive comes from a map: the full policy for every host but localhost (ADR-044).
    expect(csp.endsWith('; upgrade-insecure-requests')).toBe(true);
    const base = csp.slice(0, -'; upgrade-insecure-requests'.length);
    expect(nginx).toContain(`add_header Content-Security-Policy "${base}$csp_upgrade_insecure_requests" always;`);
    const map = /map \$host \$csp_upgrade_insecure_requests \{([^}]*)\}/.exec(nginx)?.[1] ?? '';
    const entries = map.trim().split('\n').map((line) => line.trim());
    expect(entries).toEqual(['localhost "";', '127.0.0.1 "";', 'default "; upgrade-insecure-requests";']);
    expect(nginx).toContain(`add_header Cache-Control "${cacheHeaders.html}" always;`);
    expect(nginx).toContain(`add_header Cache-Control "${cacheHeaders.assets}" always;`);
  });

  it('allow scripts from the origin only', () => {
    expect(securityHeaders['Content-Security-Policy']).toContain("script-src 'self';");
    expect(securityHeaders['Content-Security-Policy']).not.toContain('unsafe-eval');
    expect(securityHeaders['Content-Security-Policy']).toContain("frame-ancestors 'none'");
  });
});
