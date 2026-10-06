import { zodResolver } from '@hookform/resolvers/zod';
import { Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { PageHeader, Section } from '@/components/common/page';
import { DateText } from '@/components/common/values';
import { EntityName, useEntityIndex } from '@/components/data/entity';
import { entities } from '@/components/data/entities';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { DateField, EntityField, FieldGrid, Form, TextField } from '@/components/form/fields';
import { zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { ConfirmDialog } from '@/components/overlay/confirm-dialog';
import { Modal } from '@/components/overlay/modal';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { t } from '@/i18n';

type Assignment = Schemas['AuthAssignmentResponse'];

const schema = z.object({
  userEmail: zf.email(),
  roleId: zf.id(),
  branchIds: z.array(z.string()),
  validFrom: zf.optionalDate(),
  validTo: zf.optionalDate(),
});

/**
 * Company administrators assign the roles they hold to users of the company (API.md §17.3). The server
 * refuses privilege escalation and changes to one's own assignments.
 */
export function CompanyUsersPage() {
  const { api } = useCompany();
  const query = useCompanyQuery(['role-assignments'], (c, signal) => c.get('/role-assignments', null, { signal }));
  const branches = useEntityIndex(entities.branch);
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<Assignment | null>(null);
  const form = useForm<z.infer<typeof schema>>({
    resolver: zodResolver(schema),
    defaultValues: { userEmail: '', roleId: null, branchIds: [], validFrom: null, validTo: null },
  });
  const { submit, ...problem } = useSubmit(form, async (v) => {
    await api.post('/role-assignments', null, {
      body: {
        userEmail: v.userEmail,
        roleId: v.roleId!,
        branchIds: v.branchIds.length > 0 ? v.branchIds : undefined,
        validFrom: v.validFrom ?? undefined,
        validTo: v.validTo ?? undefined,
      },
    });
    form.reset();
    setAdding(false);
    await query.refetch();
  });
  const rows = query.data?.data ?? [];
  const sorted = [...rows].sort((a, b) => (a.userEmail ?? '').localeCompare(b.userEmail ?? ''));
  return (
    <div className="space-y-4">
      <PageHeader
        title={t('admin.companyUsers')}
        description={t('admin.companyUsersText')}
        actions={
          <Button onClick={() => setAdding(true)}>
            <Plus aria-hidden />
            {t('admin.assign')}
          </Button>
        }
      />
      <Section bodyClassName="p-0">
        {query.isLoading ? (
          <LoadingState />
        ) : query.isError ? (
          <ErrorState error={query.error} onRetry={() => query.refetch()} />
        ) : sorted.length === 0 ? (
          <EmptyState />
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('admin.user')}</TableHead>
                  <TableHead>{t('admin.role')}</TableHead>
                  <TableHead>{t('admin.branches')}</TableHead>
                  <TableHead className="hidden md:table-cell">{t('admin.validFrom')}</TableHead>
                  <TableHead className="hidden md:table-cell">{t('admin.validTo')}</TableHead>
                  <TableHead>
                    <span className="sr-only">{t('common.actions')}</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {sorted.map((a) => (
                  <TableRow key={a.id}>
                    <TableCell>
                      <div className="font-medium">{a.userDisplayName}</div>
                      <div className="text-xs text-muted-foreground">{a.userEmail}</div>
                    </TableCell>
                    <TableCell>
                      <EntityName source={entities.companyRole} id={a.roleId} fallback={a.roleCode} />
                    </TableCell>
                    <TableCell>
                      {a.branchIds && a.branchIds.length > 0
                        ? a.branchIds.map((id) => branches.index.get(id)?.code ?? id.slice(-6)).join(', ')
                        : t('admin.allBranches')}
                    </TableCell>
                    <TableCell className="hidden md:table-cell">
                      <DateText value={a.validFrom} />
                    </TableCell>
                    <TableCell className="hidden md:table-cell">
                      <DateText value={a.validTo} />
                    </TableCell>
                    <TableCell className="text-right">
                      <Button variant="ghost" size="icon-sm" onClick={() => setRemoving(a)} aria-label={`${t('admin.removeAssignment')} ${a.userEmail} ${a.roleCode}`}>
                        <Trash2 aria-hidden />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </Section>
      <Modal open={adding} onOpenChange={setAdding} title={t('admin.assign')}>
        <Form form={form} onSubmit={submit}>
          <TextField name="userEmail" label={t('admin.userOrEmail')} type="email" required />
          <EntityField name="roleId" label={t('admin.role')} source={entities.companyRole} required />
          <fieldset className="space-y-1.5">
            <legend className="text-sm font-medium">{t('admin.branches')}</legend>
            <p className="text-xs text-muted-foreground">{t('admin.allBranches')}</p>
            <Controller
              control={form.control}
              name="branchIds"
              render={({ field }) => (
                <div className="grid gap-1 sm:grid-cols-2">
                  {branches.items.map((b) => (
                    <label key={b.id} className="flex items-center gap-2 text-sm">
                      <Checkbox
                        checked={field.value.includes(b.id!)}
                        onCheckedChange={(checked) =>
                          field.onChange(checked === true ? [...field.value, b.id!] : field.value.filter((x) => x !== b.id))
                        }
                      />
                      {b.code} — {b.name}
                    </label>
                  ))}
                </div>
              )}
            />
          </fieldset>
          <FieldGrid>
            <DateField name="validFrom" label={t('admin.validFrom')} />
            <DateField name="validTo" label={t('admin.validTo')} />
          </FieldGrid>
          <FormProblem {...problem} />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" onClick={() => setAdding(false)}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {t('admin.assign')}
            </Button>
          </div>
        </Form>
      </Modal>
      <ConfirmDialog
        open={removing !== null}
        onOpenChange={(open) => !open && setRemoving(null)}
        title={t('admin.removeAssignmentConfirm')}
        description={removing ? `${removing.userEmail} · ${removing.roleCode}` : undefined}
        destructive
        confirmLabel={t('admin.removeAssignment')}
        onConfirm={async () => {
          await api.delete('/role-assignments/{assignmentId}', { assignmentId: removing!.id! });
          await query.refetch();
        }}
      />
    </div>
  );
}
