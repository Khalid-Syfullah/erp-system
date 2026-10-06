import { forwardRef, useEffect, useState, type ComponentProps } from 'react';
import { Input } from '@/components/ui/input';
import { decimalForInput, formatDecimal, isDecimalString, parseDecimalInput } from '@/lib/format';
import { cn } from '@/lib/utils';

type BaseInputProps = Omit<ComponentProps<'input'>, 'value' | 'onChange' | 'type' | 'defaultValue'>;

export interface DateInputProps extends BaseInputProps {
  value: string | null | undefined;
  onChange: (value: string | null) => void;
}

/**
 * A date picker: the browser's native date control (keyboard entry, a calendar on every platform,
 * tablets included) with ISO YYYY-MM-DD values, the API's date format.
 */
export const DateInput = forwardRef<HTMLInputElement, DateInputProps>(function DateInput(
  { value, onChange, className, ...props },
  ref,
) {
  return (
    <Input
      ref={ref}
      type="date"
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value || null)}
      className={cn('w-full min-w-36', className)}
      {...props}
    />
  );
});

export interface DecimalInputProps extends BaseInputProps {
  /** A canonical decimal string ("1234.50"), null when empty, or the raw text when it is not a number. */
  value: string | null | undefined;
  onChange: (value: string | null) => void;
  /** Shown after the number (a currency code or a unit). */
  suffix?: string;
  /** Fraction digits shown when not editing (amounts: the currency's minor units). */
  displayScale?: number;
}

/**
 * A decimal or currency input. It accepts the user's locale format ("1.234,50"), never converts to a
 * binary float, and hands the form the API's canonical decimal string. Invalid text is passed on as is,
 * so validation can flag it.
 */
export const DecimalInput = forwardRef<HTMLInputElement, DecimalInputProps>(function DecimalInput(
  { value, onChange, suffix, displayScale, className, onBlur, onFocus, ...props },
  ref,
) {
  const [focused, setFocused] = useState(false);
  const [text, setText] = useState(() => display(value, displayScale));

  useEffect(() => {
    if (!focused) setText(display(value, displayScale));
  }, [value, focused, displayScale]);

  return (
    <div className={cn('relative', className)}>
      <Input
        ref={ref}
        type="text"
        inputMode="decimal"
        autoComplete="off"
        value={text}
        onFocus={(e) => {
          setFocused(true);
          setText(isDecimalString(value) ? decimalForInput(value) : (value ?? ''));
          onFocus?.(e);
        }}
        onChange={(e) => {
          setText(e.target.value);
          const parsed = parseDecimalInput(e.target.value);
          onChange(e.target.value.trim() === '' ? null : (parsed ?? e.target.value));
        }}
        onBlur={(e) => {
          setFocused(false);
          onBlur?.(e);
        }}
        className={cn('text-right tabular', suffix && 'pr-12')}
        {...props}
      />
      {suffix ? (
        <span className="pointer-events-none absolute inset-y-0 right-2.5 flex items-center text-xs text-muted-foreground">
          {suffix}
        </span>
      ) : null}
    </div>
  );
});

function display(value: string | null | undefined, scale?: number): string {
  if (value === null || value === undefined) return '';
  if (!isDecimalString(value)) return value;
  return formatDecimal(value, scale === undefined ? {} : { minimumFractionDigits: scale, maximumFractionDigits: Math.max(scale, 6) });
}
