import { Download, Paperclip, Trash2, Upload } from 'lucide-react';
import { useRef, useState, type ReactNode } from 'react';
import { DateTimeText } from '@/components/common/values';
import { Section } from '@/components/common/page';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { ConfirmDialog } from '@/components/overlay/confirm-dialog';
import { Button } from '@/components/ui/button';
import { t } from '@/i18n';
import { formatDecimal } from '@/lib/format';

export interface AttachmentItem {
  id: string;
  name: string;
  contentType?: string;
  size?: number;
  createdAt?: string;
  description?: ReactNode;
}

export interface AttachmentsPanelProps {
  title?: string;
  items: AttachmentItem[] | undefined;
  isLoading?: boolean;
  error?: unknown;
  onRetry?: () => void;
  canUpload?: boolean;
  canDelete?: boolean;
  /** Extra inputs collected before an upload (e.g. a document type). */
  uploadForm?: (file: File, done: () => void) => ReactNode;
  onUpload?: (file: File) => Promise<unknown>;
  onDownload: (item: AttachmentItem) => Promise<void>;
  onDelete?: (item: AttachmentItem) => Promise<unknown>;
  accept?: string;
}

function readableSize(bytes: number | undefined): string {
  if (bytes === undefined) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${formatDecimal(String(Math.round(bytes / 102.4) / 10))} KB`;
  return `${formatDecimal(String(Math.round(bytes / 104857.6) / 10))} MB`;
}

/**
 * Files of a record: list, upload, download (served with Content-Disposition: attachment) and removal.
 * The storage API is passed in, so the panel serves every owner of files.
 */
export function AttachmentsPanel({
  title,
  items,
  isLoading,
  error,
  onRetry,
  canUpload,
  canDelete,
  uploadForm,
  onUpload,
  onDownload,
  onDelete,
  accept,
}: AttachmentsPanelProps) {
  const input = useRef<HTMLInputElement>(null);
  const [pending, setPending] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [removing, setRemoving] = useState<AttachmentItem | null>(null);

  const chosen = async (file: File | undefined) => {
    if (!file) return;
    if (uploadForm) return setPending(file);
    if (!onUpload) return;
    setBusy(true);
    try {
      await onUpload(file);
      notify.success(t('attachments.uploaded'));
    } catch (e) {
      notify.error(e);
    } finally {
      setBusy(false);
      if (input.current) input.current.value = '';
    }
  };

  return (
    <Section
      title={
        <span className="inline-flex items-center gap-1.5">
          <Paperclip className="size-4" aria-hidden />
          {title ?? t('attachments.title')}
        </span>
      }
      actions={
        canUpload ? (
          <>
            <input
              ref={input}
              type="file"
              className="sr-only"
              accept={accept}
              tabIndex={-1}
              aria-hidden
              onChange={(e) => void chosen(e.target.files?.[0])}
            />
            <Button size="sm" variant="outline" onClick={() => input.current?.click()} disabled={busy}>
              <Upload aria-hidden />
              {t('attachments.upload')}
            </Button>
          </>
        ) : null
      }
      bodyClassName="p-0"
    >
      {pending && uploadForm ? (
        <div className="border-b p-4">
          {uploadForm(pending, () => {
            setPending(null);
            if (input.current) input.current.value = '';
          })}
        </div>
      ) : null}
      {isLoading ? (
        <LoadingState />
      ) : error ? (
        <ErrorState error={error} onRetry={onRetry} />
      ) : !items || items.length === 0 ? (
        <EmptyState title={t('attachments.empty')} className="py-6" />
      ) : (
        <ul className="divide-y">
          {items.map((item) => (
            <li key={item.id} className="flex flex-wrap items-center gap-3 px-4 py-2 text-sm">
              <div className="min-w-0 flex-1">
                <div className="truncate font-medium">{item.name}</div>
                <div className="text-xs text-muted-foreground">
                  {[item.contentType, readableSize(item.size)].filter(Boolean).join(' · ')}
                  {item.createdAt ? (
                    <>
                      {' · '}
                      <DateTimeText value={item.createdAt} />
                    </>
                  ) : null}
                </div>
                {item.description ? <div className="text-xs">{item.description}</div> : null}
              </div>
              <Button size="icon-sm" variant="ghost" onClick={() => void onDownload(item).catch(notify.error)} aria-label={`${t('common.download')} ${item.name}`}>
                <Download aria-hidden />
              </Button>
              {canDelete && onDelete ? (
                <Button size="icon-sm" variant="ghost" onClick={() => setRemoving(item)} aria-label={`${t('common.remove')} ${item.name}`}>
                  <Trash2 aria-hidden />
                </Button>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      {removing && onDelete ? (
        <ConfirmDialog
          open
          onOpenChange={(open) => !open && setRemoving(null)}
          title={t('attachments.removeConfirm')}
          description={removing.name}
          destructive
          confirmLabel={t('common.remove')}
          onConfirm={() => onDelete(removing)}
        />
      ) : null}
    </Section>
  );
}
