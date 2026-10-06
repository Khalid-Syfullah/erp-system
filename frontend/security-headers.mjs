// The SPA's HTTP security headers (SECURITY.md §10.1). One source for `vite preview`, the nginx
// configuration of the web image (infra/docker/nginx/spa.conf, checked by security-headers.test.ts)
// and the end-to-end header check (e2e/security-headers.spec.ts).

export const contentSecurityPolicy = [
  "default-src 'self'",
  "script-src 'self'",
  // Radix UI positions popovers with inline style attributes (SECURITY.md §10.1, revisited in Phase 12).
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob:",
  "font-src 'self'",
  "connect-src 'self'",
  "frame-ancestors 'none'",
  "base-uri 'none'",
  "form-action 'self'",
  "object-src 'none'",
  'upgrade-insecure-requests',
].join('; ');

/** Headers of every HTML response. */
export const securityHeaders = {
  'Content-Security-Policy': contentSecurityPolicy,
  'Strict-Transport-Security': 'max-age=63072000; includeSubDomains',
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=()',
  'Cross-Origin-Opener-Policy': 'same-origin',
};

/** index.html is never cached; hashed assets are immutable. */
export const cacheHeaders = {
  html: 'no-cache',
  assets: 'public, max-age=31536000, immutable',
};
