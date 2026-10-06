import { Plus, Trash2 } from 'lucide-react';
import type { ReactNode } from 'react';
import {
  useController,
  useFieldArray,
  useFormContext,
  type ArrayPath,
  type FieldArray,
  type FieldValues,
} from 'react-hook-form';
import { EntityPicker } from '@/components/data/entity';
import type { EntitySource } from '@/components/data/entities';
import type { Filters } from '@/api/list';
import { DateInput, DecimalInput } from '@/components/form/inputs';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { t } from '@/i18n';
import { cn } from '@/lib/utils';

export interface LineColumn {
  key: string;
  header: string;
  className?: string;
  align?: 'right';
  render: (index: number) => ReactNode;
}

/**
 * The line grid of a draft document: add, edit and remove lines. Amounts are not computed here: the
 * server prices and totals the document when it is saved (PRODUCT_SPEC.md G-7).
 */
export function LinesEditor<T extends FieldValues>({
  name,
  columns,
  newLine,
  disabled,
  caption,
  minLines = 1,
  canAdd = true,
}: {
  name: ArrayPath<T>;
  columns: LineColumn[];
  newLine: () => FieldArray<T, ArrayPath<T>>;
  disabled?: boolean;
  caption?: string;
  minLines?: number;
  canAdd?: boolean;
}) {
  const { control, formState } = useFormContext<T>();
  const { fields, append, remove } = useFieldArray({ control, name });
  const arrayError = (formState.errors as Record<string, { message?: string; root?: { message?: string } }>)[name];
  return (
    <div className="space-y-2">
      <div className="overflow-x-auto rounded-lg border">
        <Table>
          {caption ? <caption className="sr-only">{caption}</caption> : null}
          <TableHeader>
            <TableRow>
              <TableHead className="w-10">#</TableHead>
              {columns.map((c) => (
                <TableHead key={c.key} className={cn(c.align === 'right' && 'text-right', c.className)}>
                  {c.header}
                </TableHead>
              ))}
              <TableHead className="w-10">
                <span className="sr-only">{t('common.actions')}</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {fields.map((field, index) => (
              <TableRow key={field.id} className="align-top">
                <TableCell className="pt-3 text-muted-foreground">{index + 1}</TableCell>
                {columns.map((c) => (
                  <TableCell key={c.key} className={cn('min-w-28', c.className)}>
                    {c.render(index)}
                  </TableCell>
                ))}
                <TableCell>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => remove(index)}
                    disabled={disabled || fields.length <= minLines}
                    aria-label={`${t('common.remove')} ${index + 1}`}
                  >
                    <Trash2 aria-hidden />
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {arrayError?.message || arrayError?.root?.message ? (
        <p className="text-xs text-destructive" role="alert">
          {arrayError.message ?? arrayError.root?.message}
        </p>
      ) : null}
      {canAdd ? (
        <Button type="button" variant="outline" size="sm" onClick={() => append(newLine())} disabled={disabled}>
          <Plus aria-hidden />
          {t('common.addLine')}
        </Button>
      ) : null}
    </div>
  );
}

function CellError({ message }: { message?: string }) {
  return message ? (
    <p className="mt-1 text-xs text-destructive" role="alert">
      {message}
    </p>
  ) : null;
}

/** A line cell picking a referenced record. */
export function LineEntity<E>({
  name,
  label,
  source,
  filters,
  filter,
  onSelect,
  disabled,
}: {
  name: string;
  label: string;
  source: EntitySource<E>;
  filters?: Filters;
  filter?: (item: E) => boolean;
  onSelect?: (item: E | undefined) => void;
  disabled?: boolean;
}) {
  const { control } = useFormContext();
  const { field, fieldState } = useController({ control, name });
  return (
    <>
      <EntityPicker
        source={source}
        value={field.value}
        onChange={(value, item) => {
          field.onChange(value);
          onSelect?.(item);
        }}
        filters={filters}
        filter={filter}
        invalid={!!fieldState.error}
        disabled={disabled}
        clearable={false}
        aria-label={label}
        className="min-w-44"
      />
      <CellError message={fieldState.error?.message} />
    </>
  );
}

export function LineDecimal({ name, label, suffix, disabled }: { name: string; label: string; suffix?: string; disabled?: boolean }) {
  const { control } = useFormContext();
  const { field, fieldState } = useController({ control, name });
  return (
    <>
      <DecimalInput
        ref={field.ref}
        name={field.name}
        value={field.value}
        onChange={field.onChange}
        onBlur={field.onBlur}
        suffix={suffix}
        disabled={disabled}
        aria-label={label}
        aria-invalid={fieldState.error ? true : undefined}
        className="min-w-24"
      />
      <CellError message={fieldState.error?.message} />
    </>
  );
}

export function LineText({ name, label, disabled }: { name: string; label: string; disabled?: boolean }) {
  const { control } = useFormContext();
  const { field, fieldState } = useController({ control, name });
  return (
    <>
      <Input {...field} value={field.value ?? ''} aria-label={label} aria-invalid={fieldState.error ? true : undefined} disabled={disabled} />
      <CellError message={fieldState.error?.message} />
    </>
  );
}

export function LineDate({ name, label, disabled }: { name: string; label: string; disabled?: boolean }) {
  const { control } = useFormContext();
  const { field, fieldState } = useController({ control, name });
  return (
    <>
      <DateInput ref={field.ref} value={field.value} onChange={field.onChange} aria-label={label} disabled={disabled} aria-invalid={fieldState.error ? true : undefined} />
      <CellError message={fieldState.error?.message} />
    </>
  );
}
