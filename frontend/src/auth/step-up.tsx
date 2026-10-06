import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { api, setAuthHandlers } from '@/api/client';
import { ProblemAlert } from '@/components/feedback/states';
import { Modal } from '@/components/overlay/modal';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { t } from '@/i18n';

/**
 * Step-up re-authentication (SECURITY.md §3.3). When a call answers 403 REAUTHENTICATION_REQUIRED, the
 * API client asks this dialog for the password, confirms it with POST /me/reauthenticate and retries
 * the call once (with the same Idempotency-Key).
 */
export function StepUpProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const pending = useRef<((confirmed: boolean) => void) | null>(null);
  const passwordId = useId();

  useEffect(
    () =>
      setAuthHandlers({
        onStepUp: () =>
          new Promise<boolean>((resolve) => {
            pending.current?.(false);
            pending.current = resolve;
            setPassword('');
            setError(null);
            setOpen(true);
          }),
      }),
    [],
  );

  const finish = (confirmed: boolean) => {
    pending.current?.(confirmed);
    pending.current = null;
    setOpen(false);
    setPassword('');
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post('/api/v1/me/reauthenticate', {}, { body: { password } });
      finish(true);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      {children}
      <Modal
        open={open}
        onOpenChange={(next) => {
          if (!next && !busy) finish(false);
        }}
        title={t('auth.stepUpTitle')}
        description={t('auth.stepUpText')}
        size="sm"
      >
        <form onSubmit={submit} className="space-y-3" noValidate>
          <div className="space-y-1.5">
            <Label htmlFor={passwordId}>{t('auth.password')}</Label>
            <Input
              id={passwordId}
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoFocus
              required
            />
          </div>
          <ProblemAlert error={error} />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" onClick={() => finish(false)} disabled={busy}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={busy || password === ''}>
              {t('auth.stepUpConfirm')}
            </Button>
          </div>
        </form>
      </Modal>
    </>
  );
}
