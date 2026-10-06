import { isApiError, type FieldProblem } from '@/api/errors';
import { t, tryT } from '@/i18n';

/** The user-facing message of a failed call: the catalog's text for the code, else the server's detail. */
export function problemMessage(error: unknown): string {
  if (isApiError(error)) {
    return tryT(`errors.${error.code}`) ?? error.problem.detail ?? error.problem.title ?? error.code;
  }
  if (error instanceof Error && error.message) return error.message;
  return t('states.errorTitle');
}

/** Field errors that a form could not attach to one of its inputs, as readable lines. */
export function fieldProblemLines(errors: FieldProblem[]): string[] {
  return errors.map((e) => {
    const where = e.pointer ? e.pointer.replace(/^\//, '').replace(/\//g, ' › ') : e.parameter;
    return where ? `${where}: ${e.message}` : e.message;
  });
}
