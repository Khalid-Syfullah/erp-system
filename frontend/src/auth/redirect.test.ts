import { describe, expect, it } from 'vitest';
import { safeRedirect } from './redirect';

describe('safeRedirect', () => {
  it('follows paths of the application', () => {
    expect(safeRedirect('/c/1/sales/orders?status=OPEN#top')).toBe('/c/1/sales/orders?status=OPEN#top');
  });

  it.each([
    ['an absolute URL', 'https://evil.example/'],
    ['a protocol-relative URL', '//evil.example'],
    ['a backslash path', '/\\evil.example'],
    ['a tab that browsers strip', '/\t/evil.example'],
    ['a newline that browsers strip', '/\n/evil.example'],
    ['a backslash later in the path', '/c/1\\..\\x'],
    ['a relative path', 'c/1'],
    ['no string', { href: '/' }],
  ])('goes home instead of %s', (_, target) => {
    expect(safeRedirect(target)).toBe('/');
  });
});
