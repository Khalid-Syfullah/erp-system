import { useState } from 'react';

/**
 * The one-time token of an invitation or password-reset link. The backend sends it in the URL
 * fragment (`/accept-invitation#token=…`), which browsers never send to servers or put in Referer headers.
 */
export function useFragmentToken(): string {
  const [token] = useState(() => {
    const fromHash = new URLSearchParams(window.location.hash.replace(/^#/, '')).get('token');
    const fromQuery = new URLSearchParams(window.location.search).get('token');
    const value = fromHash ?? fromQuery ?? '';
    if (fromHash) window.history.replaceState(null, '', window.location.pathname);
    return value;
  });
  return token;
}
