import { useId, type ReactNode } from 'react';
import {
  FormProvider,
  useController,
  useFormContext,
  type FieldValues,
  type Path,
  type SubmitHandler,
  type UseFormReturn,
} from 'react-hook-form';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { EntityPicker } from '@/components/data/entity';
import type { EntitySource } from '@/components/data/entities';
import type { Filters } from '@/api/list';
import { t } from '@/i18n';
import { cn } from '@/lib/utils';
import { DateInput, DecimalInput } from './inputs';

/** A form with react-hook-form context; native validation is off so messages are consistent. */
export function Form<T extends FieldValues>({
  form,
  onSubmit,
  children,
  className,
  id,
}: {
  form: UseFormReturn<T>;
  onSubmit: SubmitHandler<T>;
  children: ReactNode;
  className?: string;
  id?: string;
}) {
  return (
    <FormProvider {...form}>
      <form id={id} noValidate onSubmit={form.handleSubmit(onSubmit)} className={cn('space-y-4', className)}>
        {children}
      </form>
    </FormProvider>
  );
}

/** Label, control, hint and error of one field, wired with aria attributes. */
export function FieldShell({
  id,
  label,
  required,
  hint,
  error,
  className,
  children,
}: {
  id: string;
  label: ReactNode;
  required?: boolean;
  hint?: ReactNode;
  error?: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <div className={cn('min-w-0 space-y-1.5', className)}>
      <Label htmlFor={id} id={`${id}-label`}>
        {label}
        {required ? (
          <span className="text-destructive" aria-hidden>
            {' '}
            *
          </span>
        ) : null}
      </Label>
      {children}
      {hint && !error ? (
        <p id={`${id}-hint`} className="text-xs text-muted-foreground">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={`${id}-error`} className="text-xs text-destructive" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

interface FieldProps<T extends FieldValues> {
  name: Path<T>;
  label: ReactNode;
  required?: boolean;
  hint?: ReactNode;
  disabled?: boolean;
  className?: string;
}

function useField<T extends FieldValues>(name: Path<T>) {
  const { control } = useFormContext<T>();
  const controller = useController({ control, name });
  const id = useId();
  const error = controller.fieldState.error?.message;
  const describedBy = error ? `${id}-error` : `${id}-hint`;
  return { ...controller, id, error, aria: { 'aria-invalid': error ? true : undefined, 'aria-describedby': describedBy } };
}

export function TextField<T extends FieldValues>({
  type = 'text',
  autoComplete,
  maxLength,
  placeholder,
  ...props
}: FieldProps<T> & { type?: 'text' | 'email' | 'password' | 'tel' | 'url'; autoComplete?: string; maxLength?: number; placeholder?: string }) {
  const { field, id, error, aria } = useField<T>(props.name);
  return (
    <FieldShell id={id} {...props} error={error}>
      <Input
        id={id}
        type={type}
        autoComplete={autoComplete}
        maxLength={maxLength}
        placeholder={placeholder}
        disabled={props.disabled}
        aria-required={props.required || undefined}
        {...aria}
        {...field}
        value={field.value ?? ''}
      />
    </FieldShell>
  );
}

export function TextareaField<T extends FieldValues>({ rows = 3, maxLength, ...props }: FieldProps<T> & { rows?: number; maxLength?: number }) {
  const { field, id, error, aria } = useField<T>(props.name);
  return (
    <FieldShell id={id} {...props} error={error}>
      <Textarea id={id} rows={rows} maxLength={maxLength} disabled={props.disabled} {...aria} {...field} value={field.value ?? ''} />
    </FieldShell>
  );
}

export function IntegerField<T extends FieldValues>({ min, max, ...props }: FieldProps<T> & { min?: number; max?: number }) {
  const { field, id, error, aria } = useField<T>(props.name);
  return (
    <FieldShell id={id} {...props} error={error}>
      <Input
        id={id}
        type="text"
        inputMode="numeric"
        disabled={props.disabled}
        {...aria}
        name={field.name}
        ref={field.ref}
        onBlur={field.onBlur}
        value={field.value === null || field.value === undefined ? '' : String(field.value)}
        onChange={(e) => {
          const text = e.target.value.trim();
          if (text === '') return field.onChange(null);
          const n = Number.parseInt(text, 10);
          field.onChange(Number.isNaN(n) ? text : Math.min(max ?? n, Math.max(min ?? n, n)));
        }}
        className="text-right tabular"
      />
    </FieldShell>
  );
}

export function DecimalField<T extends FieldValues>({
  suffix,
  displayScale,
  ...props
}: FieldProps<T> & { suffix?: string; displayScale?: number }) {
  const { field, id, error, aria } = useField<T>(props.name);
  return (
    <FieldShell id={id} {...props} error={error}>
      <DecimalInput
        id={id}
        ref={field.ref}
        name={field.name}
        value={field.value}
        onChange={field.onChange}
        onBlur={field.onBlur}
        suffix={suffix}
        displayScale={displayScale}
        disabled={props.disabled}
        {...aria}
      />
    </FieldShell>
  );
}

/** A currency amount: a decimal field showing the currency and its minor units. */
export function MoneyField<T extends FieldValues>({ currency, ...props }: FieldProps<T> & { currency?: string | null }) {
  return <DecimalField {...props} suffix={currency ?? undefined} displayScale={2} />;
}

export function DateField<T extends FieldValues>({ min, max, ...props }: FieldProps<T> & { min?: string; max?: string }) {
  const { field, id, error, aria } = useField<T>(props.name);
  return (
    <FieldShell id={id} {...props} error={error}>
      <DateInput
        id={id}
        ref={field.ref}
        name={field.name}
        value={field.value}
        onChange={field.onChange}
        onBlur={field.onBlur}
        min={min}
        max={max}
        disabled={props.disabled}
        {...aria}
      />
    </FieldShell>
  );
}

export interface Option {
  value: string;
  label: string;
}

const NONE = '__none__';

export function SelectField<T extends FieldValues>({
  options,
  allowEmpty,
  placeholder,
  ...props
}: FieldProps<T> & { options: Option[]; allowEmpty?: boolean; placeholder?: string }) {
  const { field, id, error, aria } = useField<T>(props.name);
  return (
    <FieldShell id={id} {...props} error={error}>
      <Select
        value={field.value ? String(field.value) : allowEmpty ? NONE : ''}
        onValueChange={(v) => field.onChange(v === NONE ? null : v)}
        disabled={props.disabled}
      >
        <SelectTrigger id={id} ref={field.ref} className="w-full" {...aria}>
          <SelectValue placeholder={placeholder ?? t('common.select')} />
        </SelectTrigger>
        <SelectContent>
          {allowEmpty ? <SelectItem value={NONE}>{t('common.none')}</SelectItem> : null}
          {options.map((o) => (
            <SelectItem key={o.value} value={o.value}>
              {o.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </FieldShell>
  );
}

export function EntityField<T extends FieldValues, E>({
  source,
  filters,
  filter,
  onSelect,
  placeholder,
  ...props
}: FieldProps<T> & {
  source: EntitySource<E>;
  filters?: Filters;
  filter?: (item: E) => boolean;
  onSelect?: (item: E | undefined) => void;
  placeholder?: string;
}) {
  const { field, id, error, aria } = useField<T>(props.name);
  return (
    <FieldShell id={id} {...props} error={error}>
      <EntityPicker
        id={id}
        source={source}
        value={field.value}
        onChange={(value, item) => {
          field.onChange(value);
          onSelect?.(item);
        }}
        filters={filters}
        filter={filter}
        placeholder={placeholder}
        disabled={props.disabled}
        invalid={!!error}
        clearable={!props.required}
        aria-describedby={aria['aria-describedby']}
      />
    </FieldShell>
  );
}

export function CheckboxField<T extends FieldValues>({ name, label, hint, disabled, className }: FieldProps<T>) {
  const { field, id, error, aria } = useField<T>(name);
  return (
    <div className={cn('space-y-1', className)}>
      <div className="flex items-center gap-2">
        <Checkbox
          id={id}
          ref={field.ref}
          checked={!!field.value}
          onCheckedChange={(checked) => field.onChange(checked === true)}
          disabled={disabled}
          {...aria}
        />
        <Label htmlFor={id} className="font-normal">
          {label}
        </Label>
      </div>
      {hint && !error ? <p id={`${id}-hint`} className="pl-6 text-xs text-muted-foreground">{hint}</p> : null}
      {error ? <p id={`${id}-error`} className="pl-6 text-xs text-destructive" role="alert">{error}</p> : null}
    </div>
  );
}

export function SwitchField<T extends FieldValues>({ name, label, hint, disabled, className }: FieldProps<T>) {
  const { field, id, error, aria } = useField<T>(name);
  return (
    <div className={cn('flex items-start justify-between gap-4 rounded-lg border p-3', className)}>
      <div className="space-y-0.5">
        <Label htmlFor={id}>{label}</Label>
        {hint ? <p id={`${id}-hint`} className="text-xs text-muted-foreground">{hint}</p> : null}
        {error ? <p id={`${id}-error`} className="text-xs text-destructive" role="alert">{error}</p> : null}
      </div>
      <Switch id={id} ref={field.ref} checked={!!field.value} onCheckedChange={field.onChange} disabled={disabled} {...aria} />
    </div>
  );
}

/** A responsive grid of fields. */
export function FieldGrid({ children, columns = 2 }: { children: ReactNode; columns?: 1 | 2 | 3 | 4 }) {
  const grid = { 1: '', 2: 'sm:grid-cols-2', 3: 'sm:grid-cols-2 lg:grid-cols-3', 4: 'sm:grid-cols-2 lg:grid-cols-4' }[columns];
  return <div className={cn('grid grid-cols-1 gap-4', grid)}>{children}</div>;
}
