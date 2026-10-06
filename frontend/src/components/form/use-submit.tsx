import { useState } from 'react';
import type { FieldValues, UseFormReturn } from 'react-hook-form';
import { isApiError, type FieldProblem } from '@/api/errors';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { fieldProblemLines, problemMessage } from '@/components/feedback/problem';
import { t } from '@/i18n';
import { applyServerErrors } from './server-errors';
import { AlertTriangle } from 'lucide-react';

export interface SubmitState {
  error: unknown;
  unmapped: FieldProblem[];
}

/**
 * Runs a form's server call: field errors from the problem document land on their fields, the rest is
 * shown above the form (FormProblem). Validation stays the server's (G-7); the form only checks shapes.
 */
export function useSubmit<T extends FieldValues, R>(
  form: UseFormReturn<T>,
  run: (values: T) => Promise<R>,
  options: { fieldMap?: Record<string, string>; onSuccess?: (result: R, values: T) => void } = {},
) {
  const [state, setState] = useState<SubmitState>({ error: null, unmapped: [] });
  const submit = async (values: T) => {
    setState({ error: null, unmapped: [] });
    try {
      const result = await run(values);
      options.onSuccess?.(result, values);
    } catch (error) {
      const unmapped = applyServerErrors(form, error, options.fieldMap);
      setState({ error, unmapped });
    }
  };
  return { submit, ...state, reset: () => setState({ error: null, unmapped: [] }) };
}

/** The form-level part of a failed submission: its message and the field errors no input shows. */
export function FormProblem({ error, unmapped }: SubmitState) {
  if (!error) return null;
  const mappedAll = isApiError(error) && error.fieldErrors.length > 0 && unmapped.length === 0;
  const lines = fieldProblemLines(unmapped);
  const requestId = isApiError(error) ? error.problem.requestId : undefined;
  return (
    <Alert variant="destructive">
      <AlertTriangle aria-hidden />
      <AlertTitle>{mappedAll ? t('forms.fixErrors') : problemMessage(error)}</AlertTitle>
      {lines.length > 0 || requestId ? (
        <AlertDescription>
          {lines.length > 0 ? (
            <ul className="list-disc pl-4">
              {lines.map((line, i) => (
                <li key={i}>{line}</li>
              ))}
            </ul>
          ) : null}
          {isApiError(error) && error.isConflict ? <p>{t('conflict.text')}</p> : null}
          {requestId ? <p className="text-xs">{t('states.requestId', { id: requestId })}</p> : null}
        </AlertDescription>
      ) : null}
    </Alert>
  );
}
