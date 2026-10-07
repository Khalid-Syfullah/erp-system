import type { FieldValues, Path, UseFormReturn } from 'react-hook-form';
import { isApiError, pointerToPath, type FieldProblem } from '@/api/errors';
import { fieldMessage } from '@/components/feedback/problem';

function hasPath(values: unknown, path: string): boolean {
  if (path === '') return false;
  let node: unknown = values;
  for (const part of path.split('.')) {
    if (node === null || typeof node !== 'object' || !(part in (node as Record<string, unknown>))) return false;
    node = (node as Record<string, unknown>)[part];
  }
  return true;
}

/**
 * Attaches the problem's field errors (JSON pointers into the request body) to the form's fields and
 * returns those no field shows, for the form-level problem alert. `fieldMap` renames body fields that
 * the form models differently (e.g. `/initialAssignment/branchId` → `branchId`).
 */
export function applyServerErrors<T extends FieldValues>(
  form: UseFormReturn<T>,
  error: unknown,
  fieldMap: Record<string, string> = {},
): FieldProblem[] {
  if (!isApiError(error)) return [];
  const values = form.getValues();
  const unmapped: FieldProblem[] = [];
  let focused = false;
  for (const problem of error.fieldErrors) {
    if (!problem.pointer) {
      unmapped.push(problem);
      continue;
    }
    const raw = pointerToPath(problem.pointer);
    const path = fieldMap[raw] ?? raw;
    if (hasPath(values, path)) {
      form.setError(path as Path<T>, { type: 'server', message: fieldMessage(problem) }, { shouldFocus: !focused });
      focused = true;
    } else {
      unmapped.push(problem);
    }
  }
  return unmapped;
}
