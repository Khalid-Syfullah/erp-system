import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useForm, type DefaultValues, type FieldValues, type Resolver } from 'react-hook-form';
import type { z } from 'zod';
import { companyKey, newIdempotencyKey } from '@/api/hooks';
import { useOptionalCompany } from '@/auth/company';
import { notify } from '@/components/feedback/notify';
import { Form } from '@/components/form/fields';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { Button } from '@/components/ui/button';
import { t } from '@/i18n';
import { Modal } from './modal';

export interface FormDialogProps<V extends FieldValues> {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  description?: ReactNode;
  schema: z.ZodType<V, V>;
  defaults: V;
  submitLabel?: string;
  destructive?: boolean;
  success?: string;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  /** Runs the action; `idempotencyKey` stays the same while the dialog is open (retries replay). */
  onSubmit: (values: V, idempotencyKey: string) => Promise<unknown>;
  onDone?: (result: unknown) => void;
  children: ReactNode;
}

/** A dialog collecting the inputs of an action (terminate, reject with reason, mark paid …). */
export function FormDialog<V extends FieldValues>(props: FormDialogProps<V>) {
  return (
    <Modal open={props.open} onOpenChange={props.onOpenChange} title={props.title} description={props.description} size={props.size}>
      {props.open ? <FormDialogBody {...props} /> : null}
    </Modal>
  );
}

function FormDialogBody<V extends FieldValues>({
  onOpenChange,
  schema,
  defaults,
  submitLabel,
  destructive,
  success,
  onSubmit,
  onDone,
  children,
}: FormDialogProps<V>) {
  const company = useOptionalCompany();
  const queryClient = useQueryClient();
  const [key] = useState(newIdempotencyKey);
  const form = useForm<V>({
    resolver: zodResolver(schema as never) as unknown as Resolver<V>,
    defaultValues: defaults as DefaultValues<V>,
  });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    const result = await onSubmit(values, key);
    await queryClient.invalidateQueries({ queryKey: company ? companyKey(company.companyId) : ['global'] });
    if (success) notify.success(success);
    onOpenChange(false);
    onDone?.(result);
  });
  return (
    <Form form={form} onSubmit={submit}>
      {children}
      <FormProblem {...problem} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={form.formState.isSubmitting}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" variant={destructive ? 'destructive' : 'default'} disabled={form.formState.isSubmitting}>
          {form.formState.isSubmitting ? t('common.saving') : (submitLabel ?? t('common.save'))}
        </Button>
      </div>
    </Form>
  );
}
