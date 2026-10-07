import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { activateLanguage, hasPendingProfileLanguage, savedLanguage } from '@/i18n';
import { jsonResponse } from '@/test/render';
import { finishingSignIn, LanguageSwitcher, syncProfileLanguage } from './language';

const user = { id: 'u1', email: 'erin@erp.local', displayName: 'Erin', locale: undefined as string | undefined, version: 4 };

describe('language preference', () => {
  const fetchMock = vi.fn<typeof fetch>();
  const reload = vi.fn();
  const assign = vi.fn();
  beforeEach(() => {
    localStorage.clear();
    activateLanguage('bn');
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('location', { ...window.location, reload, assign });
    document.cookie = 'XSRF-TOKEN=token-1; path=/';
  });
  afterEach(() => {
    fetchMock.mockReset();
    reload.mockReset();
    assign.mockReset();
    vi.unstubAllGlobals();
    localStorage.setItem('erp.language', 'en');
    activateLanguage('en');
  });

  function renderSwitcher(signedIn: boolean) {
    const client = new QueryClient();
    if (signedIn) client.setQueryData(['me'], { user, companies: [] });
    return render(
      <QueryClientProvider client={client}>
        <LanguageSwitcher />
      </QueryClientProvider>,
    );
  }

  it('offers বাংলা | English, saves an explicit choice and reloads in it', async () => {
    renderSwitcher(false);
    expect(screen.getByRole('button', { name: 'বাংলা' })).toHaveAttribute('aria-pressed', 'true');
    await userEvent.click(screen.getByRole('button', { name: 'English' }));
    expect(savedLanguage()).toBe('en');
    // Chosen before sign-in: the profile adopts it at the next sign-in.
    expect(hasPendingProfileLanguage()).toBe(true);
    expect(reload).toHaveBeenCalledOnce();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('saves the choice in the signed-in user profile', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { ...user, locale: 'en', version: 5 }));
    renderSwitcher(true);
    await userEvent.click(screen.getByRole('button', { name: 'English' }));
    await waitFor(() => expect(reload).toHaveBeenCalledOnce());
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(String(url)).toBe('/api/v1/me');
    expect(init?.method).toBe('PATCH');
    expect(JSON.parse(String(init?.body))).toEqual({ locale: 'en' });
    expect(new Headers(init?.headers).get('If-Match')).toBe('W/"4"');
    expect(hasPendingProfileLanguage()).toBe(false);
  });

  it('follows the profile after sign-in, and writes a choice made before it', async () => {
    // The profile's saved choice wins over this browser's default.
    expect(await syncProfileLanguage({ ...user, locale: 'en-GB' })).toBe(true);
    expect(savedLanguage()).toBe('en');
    expect(reload).toHaveBeenCalledOnce();

    // No saved choice anywhere: Bangla stays, nothing is written.
    localStorage.clear();
    reload.mockReset();
    expect(await syncProfileLanguage(user)).toBe(false);
    expect(reload).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();

    // Chosen on the sign-in page: written to the profile.
    localStorage.setItem('erp.language', 'bn');
    localStorage.setItem('erp.language.pending', '1');
    fetchMock.mockResolvedValue(jsonResponse(200, { ...user, locale: 'bn-BD', version: 5 }));
    expect(await syncProfileLanguage({ ...user, locale: 'en' })).toBe(false);
    expect(JSON.parse(String(fetchMock.mock.calls[0]![1]?.body))).toEqual({ locale: 'bn-BD' });
    expect(hasPendingProfileLanguage()).toBe(false);
  });

  it('opens the page after sign-in in the profile language, not the sign-in page again', async () => {
    localStorage.setItem('erp.language', 'bn');
    expect(await finishingSignIn('/c/1', () => syncProfileLanguage({ ...user, locale: 'en' }))).toBe(true);
    expect(assign).toHaveBeenCalledWith('/c/1');
    expect(reload).not.toHaveBeenCalled();

    // Same language: the sign-in page navigates on its own.
    expect(await finishingSignIn('/c/1', () => syncProfileLanguage({ ...user, locale: 'bn-BD' }))).toBe(false);
    expect(assign).toHaveBeenCalledOnce();
  });
});
