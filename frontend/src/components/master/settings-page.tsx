import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, type ReactNode } from 'react';
import { useForm, type DefaultValues, type FieldValues, type Resolver } from 'react-hook-form';
import type { z } from 'zod';
import type { CompanyApi } from '@/api/client';
import { companyKey, useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { PageHeader, Section } from '@/components/common/page';
import { ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { Form } from '@/components/form/fields';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { Button } from '@/components/ui/button';
import { t } from '@/i18n';

export interface SettingsPageProps<D, V extends FieldValues> {
  title: string;
  description?: ReactNode;
  queryKey: string;
  load: (api: CompanyApi, signal: AbortSignal) => Promise<{ data: D; version: number | null }>;
  schema: z.ZodType<V, V>;
  values: (data: D) => V;
  fields: (data: D) => ReactNode;
  save: (api: CompanyApi, values: V, version: number | null, data: D) => Promise<unknown>;
  canEdit?: boolean;
}

/** A company settings singleton: read with its version (ETag), replaced with PUT and If-Match. */
export function SettingsPage<D, V extends FieldValues>(props: SettingsPageProps<D, V>) {
  const query = useCompanyQuery(['settings', props.queryKey], props.load);
  return (
    <div className="max-w-3xl space-y-4">
      <PageHeader title={props.title} description={props.description} />
      {query.isLoading ? (
        <LoadingState />
      ) : query.isError || !query.data ? (
        <ErrorState error={query.error} onRetry={() => query.refetch()} />
      ) : (
        <SettingsForm {...props} data={query.data.data} version={query.data.version} />
      )}
    </div>
  );
}

function SettingsForm<D, V extends FieldValues>({
  data,
  version,
  schema,
  values,
  fields,
  save,
  canEdit = true,
}: SettingsPageProps<D, V> & { data: D; version: number | null }) {
  const { api, companyId } = useCompany();
  const queryClient = useQueryClient();
  const form = useForm<V>({
    resolver: zodResolver(schema as never) as unknown as Resolver<V>,
    defaultValues: values(data) as DefaultValues<V>,
  });
  // A reload (after saving, or a newer version) replaces the form's values; `values` is a mapping only.
  const toValues = useRef(values);
  useEffect(() => form.reset(toValues.current(data) as DefaultValues<V>), [data, form]);
  const { submit, ...problem } = useSubmit(form, async (v) => {
    await save(api, v, version, data);
    await queryClient.invalidateQueries({ queryKey: companyKey(companyId) });
    notify.success(t('common.saved'));
  });
  return (
    <Section>
      <Form form={form} onSubmit={submit}>
        <fieldset disabled={!canEdit} className="space-y-4">
          {fields(data)}
        </fieldset>
        <FormProblem {...problem} />
        {canEdit ? (
          <div className="flex gap-2">
            <Button type="submit" disabled={form.formState.isSubmitting || !form.formState.isDirty}>
              {form.formState.isSubmitting ? t('common.saving') : t('common.save')}
            </Button>
            <Button type="button" variant="outline" onClick={() => form.reset()} disabled={!form.formState.isDirty}>
              {t('common.reset')}
            </Button>
          </div>
        ) : null}
      </Form>
    </Section>
  );
}
