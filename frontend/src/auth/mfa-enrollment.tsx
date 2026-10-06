import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import qrcode from 'qrcode-generator';
import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { api } from '@/api/client';
import { ErrorState, LoadingState } from '@/components/feedback/states';
import { Form, TextField } from '@/components/form/fields';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { Button } from '@/components/ui/button';
import { t } from '@/i18n';

/** The otpauth URI as a QR code image (a data: URL, allowed by the SPA's CSP img-src). */
function QrCode({ value }: { value: string }) {
  const src = useMemo(() => {
    const qr = qrcode(0, 'M');
    qr.addData(value);
    qr.make();
    return qr.createDataURL(5, 8);
  }, [value]);
  return <img src={src} alt={t('auth.enrollSecret')} className="mx-auto rounded-md border bg-white" width={200} height={200} />;
}

export function RecoveryCodes({ codes, onDone }: { codes: string[]; onDone: () => void }) {
  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <h2 className="font-semibold">{t('auth.recoveryCodesTitle')}</h2>
        <p className="text-sm text-muted-foreground">{t('auth.recoveryCodesText')}</p>
      </div>
      <ul className="grid grid-cols-2 gap-2 rounded-lg border bg-muted/40 p-3 font-mono text-sm" aria-label={t('auth.recoveryCodesTitle')}>
        {codes.map((code) => (
          <li key={code}>{code}</li>
        ))}
      </ul>
      <div className="flex gap-2">
        <Button variant="outline" onClick={() => void navigator.clipboard?.writeText(codes.join('\n'))}>
          {t('common.copy')}
        </Button>
        <Button className="flex-1" onClick={onDone}>
          {t('auth.recoveryCodesSaved')}
        </Button>
      </div>
    </div>
  );
}

const schema = z.object({ code: z.string().trim().min(6, t('forms.required')) });

/** TOTP enrollment: setup → scan → confirm with a code → recovery codes. */
export function MfaEnrollment({ onDone }: { onDone: () => void }) {
  const setup = useMutation({ mutationFn: () => api.post('/api/v1/me/mfa/totp/setup', {}) });
  const [codes, setCodes] = useState<string[] | null>(null);
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), defaultValues: { code: '' } });
  const { submit, ...problem } = useSubmit(form, async ({ code }) => {
    const result = await api.post('/api/v1/me/mfa/totp/confirm', {}, { body: { code: code.replace(/\s/g, '') } });
    setCodes(result.recoveryCodes ?? []);
  });

  const { mutate } = setup;
  useEffect(() => {
    mutate();
  }, [mutate]);

  if (codes) return <RecoveryCodes codes={codes} onDone={onDone} />;
  if (setup.isError) return <ErrorState error={setup.error} onRetry={() => setup.mutate()} />;
  if (!setup.data) return <LoadingState />;
  return (
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">{t('auth.enrollText')}</p>
      {setup.data.otpauthUri ? <QrCode value={setup.data.otpauthUri} /> : null}
      <div className="space-y-1 text-center">
        <div className="text-xs text-muted-foreground">{t('auth.enrollSecret')}</div>
        <code className="block rounded bg-muted px-2 py-1 font-mono text-sm break-all">{setup.data.secret}</code>
      </div>
      <Form form={form} onSubmit={submit}>
        <TextField name="code" label={t('auth.mfaCode')} autoComplete="one-time-code" maxLength={8} required />
        <FormProblem {...problem} />
        <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
          {t('auth.enrollConfirm')}
        </Button>
      </Form>
    </div>
  );
}
