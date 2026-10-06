import { enumLabel } from '@/i18n';
import { cn } from '@/lib/utils';

export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';

const SUCCESS = new Set([
  'POSTED', 'APPROVED', 'CONFIRMED', 'ACTIVE', 'PAID', 'SETTLED', 'DELIVERED', 'RECEIVED', 'SUCCEEDED',
  'COMPLETED', 'PASSED', 'INVOICED', 'BILLED', 'ACCEPTED', 'PRESENT', 'BALANCED', 'OK', 'PASS',
]);
const WARNING = new Set([
  'SUBMITTED', 'PENDING', 'PARTIALLY_RECEIVED', 'PARTIALLY_DELIVERED', 'PARTIALLY_INVOICED', 'PARTIALLY_BILLED',
  'PARTIALLY_SETTLED', 'SOFT_CLOSED', 'CALCULATING', 'CALCULATED', 'RUNNING', 'QUEUED', 'IN_PROGRESS', 'SENT',
  'EXCEPTION', 'ON_HOLD', 'WARN', 'WARNING', 'INVITED', 'HALF_DAY', 'LATE',
]);
const DANGER = new Set([
  'REJECTED', 'CANCELLED', 'VOIDED', 'BLOCKED', 'FAILED', 'TERMINATED', 'LOCKED', 'DISABLED', 'EXPIRED',
  'REVERSED', 'BLOCK', 'ABSENT', 'OVERDUE',
]);
const INFO = new Set(['OPEN', 'OVERRIDDEN', 'LEAVE', 'HOLIDAY', 'REMOTE', 'IN_TRANSIT']);

export function toneOf(status: string | null | undefined): Tone {
  if (!status) return 'neutral';
  if (SUCCESS.has(status)) return 'success';
  if (WARNING.has(status)) return 'warning';
  if (DANGER.has(status)) return 'danger';
  if (INFO.has(status)) return 'info';
  return 'neutral';
}

const tones: Record<Tone, string> = {
  neutral: 'bg-status-neutral-bg text-status-neutral-fg',
  info: 'bg-status-info-bg text-status-info-fg',
  success: 'bg-status-success-bg text-status-success-fg',
  warning: 'bg-status-warning-bg text-status-warning-fg',
  danger: 'bg-status-danger-bg text-status-danger-fg',
};

/** A document or record state. The label carries the meaning; colour only reinforces it. */
export function StatusBadge({
  status,
  label,
  tone,
  className,
}: {
  status: string | null | undefined;
  label?: string;
  tone?: Tone;
  className?: string;
}) {
  if (!status && !label) return null;
  return (
    <span
      className={cn(
        'inline-flex h-5 items-center rounded-full px-2 text-xs font-medium whitespace-nowrap',
        tones[tone ?? toneOf(status)],
        className,
      )}
    >
      {label ?? enumLabel(status)}
    </span>
  );
}

export function BooleanBadge({ value, yes, no }: { value: boolean | null | undefined; yes: string; no: string }) {
  return <StatusBadge status={value ? 'ACTIVE' : 'INACTIVE'} label={value ? yes : no} tone={value ? 'success' : 'neutral'} />;
}
