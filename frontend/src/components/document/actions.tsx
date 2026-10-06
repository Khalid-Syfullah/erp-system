import { useQueryClient } from '@tanstack/react-query';
import { MoreHorizontal } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import type { CompanyApi } from '@/api/client';
import { isApiError } from '@/api/errors';
import { companyKey, newIdempotencyKey } from '@/api/hooks';
import { useOptionalCompany } from '@/auth/company';
import { notify } from '@/components/feedback/notify';
import { ConfirmDialog } from '@/components/overlay/confirm-dialog';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { t } from '@/i18n';

export interface ActionContext {
  /** One key per user intent; a retry from the same dialog reuses it (API.md §10). */
  idempotencyKey: string;
  reason: string;
}

export interface DocAction<D> {
  id: string;
  label: string;
  /** The states in which the documented state machine allows the action (PRODUCT_SPEC.md G-4). */
  when?: (doc: D) => boolean;
  /** Permissions the action needs (all of them); without them it is not offered. */
  permissions?: string[];
  variant?: 'default' | 'outline' | 'destructive' | 'secondary';
  primary?: boolean;
  confirm?: {
    title: string;
    description?: ReactNode;
    reason?: 'optional' | 'required';
    reasonLabel?: string;
    destructive?: boolean;
    confirmLabel?: string;
    body?: (doc: D) => ReactNode;
  };
  /** Opens custom UI instead of running directly (e.g. a dialog with more inputs). */
  open?: (doc: D) => void;
  run?: (api: CompanyApi, doc: D, context: ActionContext) => Promise<unknown>;
  success?: string;
  onSuccess?: (result: unknown, doc: D) => void;
}

/**
 * The state actions of a document or record: only the actions valid in its state and permitted to the
 * user are offered (UX only: the server checks both again). Primary actions are buttons; the others
 * are in the "More" menu.
 */
export function DocumentActions<D>({ doc, actions }: { doc: D; actions: DocAction<D>[] }) {
  const company = useOptionalCompany();
  const api = company?.api as CompanyApi;
  const canAll = company?.canAll ?? (() => true);
  // Outside a company (system administration) the cached state is global.
  const scopeKey = company ? companyKey(company.companyId) : ['global'];
  const queryClient = useQueryClient();
  const [active, setActive] = useState<{ action: DocAction<D>; key: string } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const available = actions.filter((a) => (!a.when || a.when(doc)) && canAll(...(a.permissions ?? [])));
  if (available.length === 0) return null;

  const execute = async (action: DocAction<D>, context: ActionContext) => {
    const result = await action.run!(api, doc, context);
    await queryClient.invalidateQueries({ queryKey: scopeKey });
    if (action.success) notify.success(action.success);
    action.onSuccess?.(result, doc);
  };

  const trigger = async (action: DocAction<D>) => {
    if (action.open) return action.open(doc);
    if (action.confirm) return setActive({ action, key: newIdempotencyKey() });
    setBusy(action.id);
    try {
      await execute(action, { idempotencyKey: newIdempotencyKey(), reason: '' });
    } catch (error) {
      notify.error(error);
      if (isApiError(error) && error.isConflict) await queryClient.invalidateQueries({ queryKey: scopeKey });
    } finally {
      setBusy(null);
    }
  };

  const primary = available.filter((a) => a.primary);
  const secondary = available.filter((a) => !a.primary);

  return (
    <>
      {primary.map((action) => (
        <Button
          key={action.id}
          variant={action.variant ?? 'default'}
          onClick={() => void trigger(action)}
          disabled={busy !== null}
        >
          {busy === action.id ? t('common.saving') : action.label}
        </Button>
      ))}
      {secondary.length > 0 ? (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" disabled={busy !== null}>
              <MoreHorizontal aria-hidden />
              {t('common.more')}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {secondary.map((action) => (
              <DropdownMenuItem
                key={action.id}
                variant={action.variant === 'destructive' ? 'destructive' : 'default'}
                onSelect={() => void trigger(action)}
              >
                {action.label}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      ) : null}
      {active?.action.confirm ? (
        <ConfirmDialog
          open
          onOpenChange={(open) => !open && setActive(null)}
          title={active.action.confirm.title}
          description={active.action.confirm.description}
          reason={active.action.confirm.reason}
          reasonLabel={active.action.confirm.reasonLabel}
          destructive={active.action.confirm.destructive ?? active.action.variant === 'destructive'}
          confirmLabel={active.action.confirm.confirmLabel ?? active.action.label}
          onConfirm={(reason) => execute(active.action, { idempotencyKey: active.key, reason })}
        >
          {active.action.confirm.body?.(doc)}
        </ConfirmDialog>
      ) : null}
    </>
  );
}

/** `when` helper: the document is in one of the states. */
export function inStatus<D extends { status?: string | null }>(...statuses: string[]) {
  return (doc: D) => !!doc.status && statuses.includes(doc.status);
}
