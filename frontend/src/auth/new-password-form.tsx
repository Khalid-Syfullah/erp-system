import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { Form, TextField } from '@/components/form/fields';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { Button } from '@/components/ui/button';
import { t } from '@/i18n';

const schema = z
  .object({ password: z.string().min(1, t('forms.required')), confirm: z.string().min(1, t('forms.required')) })
  .refine((v) => v.password === v.confirm, { path: ['confirm'], message: t('auth.passwordsDiffer') });

/** Choosing a password (reset and invitation). The password policy is the server's (SECURITY.md §3.2). */
export function NewPasswordForm({ submitLabel, onSubmit }: { submitLabel: string; onSubmit: (password: string) => Promise<void> }) {
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), defaultValues: { password: '', confirm: '' } });
  const { submit, ...problem } = useSubmit(form, (values) => onSubmit(values.password), {
    fieldMap: { newPassword: 'password' },
  });
  return (
    <Form form={form} onSubmit={submit}>
      <TextField name="password" label={t('auth.newPassword')} type="password" autoComplete="new-password" hint={t('auth.passwordRules')} required />
      <TextField name="confirm" label={t('auth.confirmPassword')} type="password" autoComplete="new-password" required />
      <FormProblem {...problem} />
      <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
        {submitLabel}
      </Button>
    </Form>
  );
}
