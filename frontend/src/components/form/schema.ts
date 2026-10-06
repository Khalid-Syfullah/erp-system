// Shape validation shared by all forms (required, formats, lengths). Business rules stay on the server:
// its 422 field errors are attached to the same fields (applyServerErrors).
import { z } from 'zod';
import { t } from '@/i18n';
import { isDecimalString } from '@/lib/format';

const required = () => t('forms.required');

export const zf = {
  /** Required trimmed text. */
  text: (max = 255) => z.string().trim().min(1, required()).max(max, t('forms.tooLong', { max })),
  /** Optional text; '' means "not set". */
  optionalText: (max = 255) => z.string().trim().max(max, t('forms.tooLong', { max })),
  email: () => z.string().trim().min(1, required()).email(t('forms.invalidEmail')),
  optionalEmail: () =>
    z
      .string()
      .trim()
      .refine((v) => v === '' || z.string().email().safeParse(v).success, t('forms.invalidEmail')),
  /** A required reference (UUID or code). */
  id: () => z.string({ error: required() }).nullable().refine((v) => !!v, required()),
  optionalId: () => z.string().nullable(),
  /** A required ISO date. */
  date: () => z.string({ error: required() }).nullable().refine((v) => !!v, required()),
  optionalDate: () => z.string().nullable(),
  /** A required decimal string. */
  decimal: () =>
    z
      .string({ error: required() })
      .nullable()
      .refine((v) => v !== null && v !== '', required())
      .refine((v) => v === null || v === '' || isDecimalString(v), t('forms.invalidDecimal')),
  optionalDecimal: () =>
    z
      .string()
      .nullable()
      .refine((v) => v === null || v === '' || isDecimalString(v), t('forms.invalidDecimal')),
  integer: () => z.number({ error: required() }).int(),
  optionalInteger: () => z.number().int().nullable(),
  bool: () => z.boolean(),
};

/** Drops empty optional values from a create body ('' and null become absent). */
export function compact<T extends Record<string, unknown>>(values: T): Partial<T> {
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(values)) {
    if (value === '' || value === null || value === undefined) continue;
    out[key] = value;
  }
  return out as Partial<T>;
}

/**
 * A JSON merge patch (RFC 7396) of the changed fields: unchanged fields are left out, cleared fields
 * are sent as null.
 */
export function mergePatch<T extends Record<string, unknown>>(initial: Partial<T>, values: T): Record<string, unknown> {
  const patch: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(values)) {
    const before = initial[key as keyof T] ?? null;
    const after = value === '' || value === undefined ? null : value;
    if (JSON.stringify(before) !== JSON.stringify(after)) patch[key] = after;
  }
  return patch;
}
