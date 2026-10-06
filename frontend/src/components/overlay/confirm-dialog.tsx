import { useId, useState, type ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { ProblemAlert } from '@/components/feedback/states';
import { t } from '@/i18n';
import { Modal } from './modal';

export interface ConfirmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  description?: ReactNode;
  confirmLabel?: string;
  destructive?: boolean;
  /** Ask for a reason: `required` blocks confirming without one. */
  reason?: 'optional' | 'required';
  reasonLabel?: string;
  children?: ReactNode;
  /** Runs the action; the dialog stays open and shows the problem if it fails. */
  onConfirm: (reason: string) => Promise<unknown> | unknown;
}

/** Confirmation of a consequential action, with an optional reason and the server's answer inline. */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  destructive,
  reason,
  reasonLabel,
  children,
  onConfirm,
}: ConfirmDialogProps) {
  const [text, setText] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const reasonId = useId();

  // Each opening starts with an empty reason and no problem (state adjusted during render).
  const [wasOpen, setWasOpen] = useState(open);
  if (open !== wasOpen) {
    setWasOpen(open);
    if (open) {
      setText('');
      setError(null);
    }
  }

  const blocked = reason === 'required' && text.trim() === '';
  const confirm = async () => {
    setBusy(true);
    setError(null);
    try {
      await onConfirm(text.trim());
      onOpenChange(false);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      onOpenChange={(next) => !busy && onOpenChange(next)}
      title={title}
      description={description}
      footer={
        <>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
            {t('common.cancel')}
          </Button>
          <Button variant={destructive ? 'destructive' : 'default'} onClick={confirm} disabled={busy || blocked}>
            {busy ? t('common.saving') : (confirmLabel ?? t('common.confirm'))}
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        {children}
        {reason ? (
          <div className="space-y-1.5">
            <Label htmlFor={reasonId}>
              {reasonLabel ?? t('common.reason')}
              {reason === 'optional' ? <span className="text-muted-foreground"> ({t('common.optional')})</span> : null}
            </Label>
            <Textarea
              id={reasonId}
              value={text}
              onChange={(e) => setText(e.target.value)}
              maxLength={500}
              aria-required={reason === 'required'}
            />
          </div>
        ) : null}
        <ProblemAlert error={error} />
      </div>
    </Modal>
  );
}
