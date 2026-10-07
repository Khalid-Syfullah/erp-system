import { isApiError, type FieldProblem } from '@/api/errors';
import { t, tryOwn, tryT } from '@/i18n';

/** The user-facing message of a failed call: the catalog's text for the code, else the server's detail. */
export function problemMessage(error: unknown): string {
  if (isApiError(error)) {
    return tryT(`errors.${error.code}`) ?? error.problem.detail ?? error.problem.title ?? error.code;
  }
  if (error instanceof Error && error.message) return error.message;
  return t('states.errorTitle');
}

/**
 * The message of a field error: in Bangla the catalog's text for its code (the server words its
 * messages in English), else — and always in English — the server's message, which is more specific.
 */
export function fieldMessage(error: FieldProblem): string {
  return tryOwn(`fieldErrors.${error.code}`) ?? error.message;
}

/** Field errors that a form could not attach to one of its inputs, as readable lines. */
export function fieldProblemLines(errors: FieldProblem[]): string[] {
  return errors.map((e) => {
    const where = e.pointer ? e.pointer.replace(/^\//, '').replace(/\//g, ' › ') : e.parameter;
    return where ? `${where}: ${fieldMessage(e)}` : fieldMessage(e);
  });
}
