/**
 * Only same-app paths are followed after sign-in (no open redirects). Browsers strip tabs and
 * newlines from URLs and treat `\` like `/`, so `/\t/evil.example` or `/\evil.example` would become
 * the protocol-relative `//evil.example`: such characters are refused anywhere in the path.
 */
export function safeRedirect(target: unknown): string {
  return typeof target === 'string' &&
    target.startsWith('/') &&
    !target.startsWith('//') &&
    // eslint-disable-next-line no-control-regex
    !/[\u0000-\u001f\u007f\\]/.test(target)
    ? target
    : '/';
}
