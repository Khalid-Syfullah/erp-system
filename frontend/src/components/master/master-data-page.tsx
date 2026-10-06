import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { Pencil, Plus } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { useForm, type DefaultValues, type FieldValues, type Resolver } from 'react-hook-form';
import type { z } from 'zod';
import type { CompanyApi } from '@/api/client';
import { companyKey } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { PageHeader } from '@/components/common/page';
import { DataTable, type Column, type DataTableProps } from '@/components/data/data-table';
import { DocumentActions, type DocAction } from '@/components/document/actions';
import { notify } from '@/components/feedback/notify';
import { Form } from '@/components/form/fields';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { Drawer } from '@/components/overlay/drawer';
import { Button } from '@/components/ui/button';
import { t } from '@/i18n';

export interface MasterFormConfig<T, V extends FieldValues> {
  schema: z.ZodType<V, V>;
  values: (row?: T) => V;
  fields: (mode: 'create' | 'edit', row?: T) => ReactNode;
  create?: (api: CompanyApi, values: V) => Promise<unknown>;
  update?: (api: CompanyApi, row: T, values: V, initial: V) => Promise<unknown>;
  fieldMap?: Record<string, string>;
  createTitle: string;
  editTitle: (row: T) => string;
  wide?: boolean;
  /** Whether a row can be edited (e.g. system records cannot). */
  editable?: (row: T) => boolean;
}

export interface MasterDataPageProps<T, V extends FieldValues> {
  title: string;
  description?: ReactNode;
  table: Omit<DataTableProps<T>, 'toolbar' | 'emptyAction'>;
  managePermission?: string;
  form?: MasterFormConfig<T, V>;
  rowActions?: DocAction<T>[];
  headerActions?: ReactNode;
}

/**
 * The master-data pattern: a searchable list, a drawer form to create and edit records, and the
 * record's actions (activate, deactivate …). Inactive records stay listed: master data is deactivated,
 * never deleted once referenced (API.md §4).
 */
export function MasterDataPage<T, V extends FieldValues>({
  title,
  description,
  table,
  managePermission,
  form,
  rowActions = [],
  headerActions,
}: MasterDataPageProps<T, V>) {
  const { can } = useCompany();
  const canManage = !managePermission || can(managePermission);
  const [editing, setEditing] = useState<{ mode: 'create' | 'edit'; row?: T } | null>(null);

  const columns: Column<T>[] = [
    ...table.columns,
    ...(canManage && (form?.update || rowActions.length > 0)
      ? [
          {
            id: '__actions',
            header: <span className="sr-only">{t('common.actions')}</span>,
            align: 'right' as const,
            cell: (row: T) => (
              <div className="flex justify-end gap-1">
                {form?.update && (!form.editable || form.editable(row)) ? (
                  <Button variant="ghost" size="sm" onClick={() => setEditing({ mode: 'edit', row })}>
                    <Pencil aria-hidden />
                    {t('common.edit')}
                  </Button>
                ) : null}
                <DocumentActions doc={row} actions={rowActions} />
              </div>
            ),
          },
        ]
      : []),
  ];

  const create =
    canManage && form?.create ? (
      <Button onClick={() => setEditing({ mode: 'create' })}>
        <Plus aria-hidden />
        {t('common.new')}
      </Button>
    ) : null;

  return (
    <div className="space-y-4">
      <PageHeader title={title} description={description} actions={headerActions} />
      <DataTable {...table} columns={columns} toolbar={create} emptyAction={create} />
      {form && editing ? (
        <MasterFormDrawer
          config={form}
          mode={editing.mode}
          row={editing.row}
          onClose={() => setEditing(null)}
        />
      ) : null}
    </div>
  );
}

function MasterFormDrawer<T, V extends FieldValues>({
  config,
  mode,
  row,
  onClose,
}: {
  config: MasterFormConfig<T, V>;
  mode: 'create' | 'edit';
  row?: T;
  onClose: () => void;
}) {
  const { api, companyId } = useCompany();
  const queryClient = useQueryClient();
  const [initial] = useState(() => config.values(row));
  const form = useForm<V>({
    resolver: zodResolver(config.schema as never) as unknown as Resolver<V>,
    defaultValues: initial as DefaultValues<V>,
  });
  const { submit, ...problem } = useSubmit(
    form,
    async (values) => {
      if (mode === 'create') await config.create!(api, values);
      else await config.update!(api, row!, values, initial);
    },
    {
      fieldMap: config.fieldMap,
      onSuccess: async () => {
        await queryClient.invalidateQueries({ queryKey: companyKey(companyId) });
        notify.success(mode === 'create' ? t('common.created') : t('common.saved'));
        onClose();
      },
    },
  );
  const formId = `master-form-${mode}`;
  return (
    <Drawer
      open
      onOpenChange={(open) => !open && onClose()}
      title={mode === 'create' ? config.createTitle : config.editTitle(row!)}
      wide={config.wide}
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" form={formId} disabled={form.formState.isSubmitting}>
            {form.formState.isSubmitting ? t('common.saving') : t('common.save')}
          </Button>
        </div>
      }
    >
      <Form form={form} onSubmit={submit} id={formId}>
        {config.fields(mode, row)}
        <FormProblem {...problem} />
      </Form>
    </Drawer>
  );
}

/** Activate/deactivate actions of a master record with an `isActive` flag (or a status). */
export function activationActions<T extends { version?: number }>(
  isActive: (row: T) => boolean,
  path: (row: T) => { activate: (api: CompanyApi, row: T) => Promise<unknown>; deactivate: (api: CompanyApi, row: T) => Promise<unknown> },
  permission: string,
): DocAction<T>[] {
  return [
    {
      id: 'deactivate',
      label: t('common.deactivate'),
      when: (row) => isActive(row),
      permissions: [permission],
      run: (api, row) => path(row).deactivate(api, row),
      success: t('common.saved'),
    },
    {
      id: 'activate',
      label: t('common.activate'),
      when: (row) => !isActive(row),
      permissions: [permission],
      run: (api, row) => path(row).activate(api, row),
      success: t('common.saved'),
    },
  ];
}
