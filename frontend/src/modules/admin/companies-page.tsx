import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { api, type Schemas } from '@/api/client';
import { meQuery } from '@/auth/session';
import { PageHeader } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { DateTimeText } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { notify } from '@/components/feedback/notify';
import { FieldGrid, Form, IntegerField, TextField } from '@/components/form/fields';
import { compact, zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { Modal } from '@/components/overlay/modal';
import { Button } from '@/components/ui/button';
import { t } from '@/i18n';
import { useInvalidateGlobal } from './use-global';

type Company = Schemas['CompanyCompanyResponse'];

const schema = z.object({
  code: zf.text(20),
  legalName: zf.text(200),
  displayName: zf.text(100),
  countryCode: zf.text(2),
  baseCurrency: zf.text(3),
  timezone: zf.text(64),
  fiscalYearStartMonth: zf.integer(),
  taxRegistrationNo: zf.optionalText(50),
  registrationNo: zf.optionalText(50),
  addressLine1: zf.optionalText(200),
  addressLine2: zf.optionalText(200),
  city: zf.optionalText(100),
  region: zf.optionalText(100),
  postalCode: zf.optionalText(20),
});

/** All companies, including inactive ones; new companies are seeded with the standard chart (org.company.created). */
export function AdminCompaniesPage() {
  const [creating, setCreating] = useState(false);
  return (
    <div className="space-y-4">
      <PageHeader title={t('admin.companies')} />
      <DataTable<Company>
        id="admin-companies"
        fetchPage={(_, query, signal) => api.get('/api/v1/admin/companies', {}, { query, signal })}
        rowKey={(c) => c.id!}
        defaultSort="code"
        filters={[{ kind: 'enum', key: 'status', label: t('common.status'), values: ['ACTIVE', 'INACTIVE'] }]}
        columns={[
          { id: 'code', header: t('common.code'), sortKey: 'code', cell: (c) => <span className="font-mono text-xs">{c.code}</span> },
          { id: 'name', header: t('common.name'), sortKey: 'displayName', cell: (c) => c.displayName },
          { id: 'legal', header: t('admin.legalName'), hideBelow: 'md', cell: (c) => c.legalName },
          { id: 'country', header: t('admin.country'), hideBelow: 'sm', cell: (c) => c.countryCode },
          { id: 'currency', header: t('admin.baseCurrency'), cell: (c) => c.baseCurrency },
          { id: 'status', header: t('common.status'), cell: (c) => <StatusBadge status={c.status} /> },
          { id: 'created', header: t('common.createdAt'), sortKey: 'createdAt', hideBelow: 'lg', cell: (c) => <DateTimeText value={c.createdAt} /> },
        ]}
        toolbar={
          <Button onClick={() => setCreating(true)}>
            <Plus aria-hidden />
            {t('admin.newCompany')}
          </Button>
        }
      />
      {creating ? <CreateCompanyModal onClose={() => setCreating(false)} /> : null}
    </div>
  );
}

function CreateCompanyModal({ onClose }: { onClose: () => void }) {
  const invalidate = useInvalidateGlobal();
  const queryClient = useQueryClient();
  const form = useForm<z.infer<typeof schema>>({
    resolver: zodResolver(schema),
    defaultValues: {
      code: '', legalName: '', displayName: '', countryCode: '', baseCurrency: '', timezone: 'UTC', fiscalYearStartMonth: 1,
      taxRegistrationNo: '', registrationNo: '', addressLine1: '', addressLine2: '', city: '', region: '', postalCode: '',
    },
  });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    await api.post('/api/v1/companies', {}, { body: compact(values) as Schemas['CreateCompanyRequest'] });
    await invalidate();
    await queryClient.invalidateQueries({ queryKey: meQuery.queryKey });
    notify.success(t('common.created'));
    onClose();
  });
  return (
    <Modal open onOpenChange={(open) => !open && onClose()} title={t('admin.newCompany')} size="lg">
      <Form form={form} onSubmit={submit}>
        <FieldGrid>
          <TextField name="code" label={t('common.code')} required maxLength={20} />
          <TextField name="displayName" label={t('common.name')} required />
        </FieldGrid>
        <TextField name="legalName" label={t('admin.legalName')} required />
        <FieldGrid columns={3}>
          <TextField name="countryCode" label={t('admin.country')} required maxLength={2} />
          <TextField name="baseCurrency" label={t('admin.baseCurrency')} required maxLength={3} />
          <IntegerField name="fiscalYearStartMonth" label={t('admin.fiscalYearStartMonth')} min={1} max={12} required />
        </FieldGrid>
        <FieldGrid>
          <TextField name="timezone" label={t('admin.timezone')} required />
          <TextField name="taxRegistrationNo" label={t('admin.taxRegistrationNo')} />
        </FieldGrid>
        <FieldGrid>
          <TextField name="addressLine1" label={t('admin.address1')} />
          <TextField name="addressLine2" label={t('admin.address2')} />
          <TextField name="city" label={t('admin.city')} />
          <TextField name="postalCode" label={t('admin.postalCode')} />
        </FieldGrid>
        <FormProblem {...problem} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={form.formState.isSubmitting}>
            {t('common.create')}
          </Button>
        </div>
      </Form>
    </Modal>
  );
}
