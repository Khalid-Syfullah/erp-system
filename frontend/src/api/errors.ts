// RFC 9457 problem documents (API.md §6). Clients branch on `code`, never on `title` or `detail`.

export interface FieldProblem {
  pointer?: string;
  parameter?: string;
  code: string;
  message: string;
  meta?: Record<string, unknown>;
}

export interface Problem {
  type?: string;
  title?: string;
  status: number;
  code: string;
  detail?: string;
  instance?: string;
  requestId?: string;
  errors?: FieldProblem[];
}

/** A failed API call: the server's problem document, or a synthetic one for network failures. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title ?? problem.code);
    this.name = 'ApiError';
    this.status = problem.status;
    this.code = problem.code;
    this.problem = problem;
  }

  get fieldErrors(): FieldProblem[] {
    return this.problem.errors ?? [];
  }

  /** 412 and 409 VERSION_CONFLICT mean the same to a client: reload, then retry (API.md §9). */
  get isConflict(): boolean {
    return this.status === 412 || this.code === 'VERSION_CONFLICT';
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

export function hasCode(error: unknown, ...codes: string[]): error is ApiError {
  return isApiError(error) && codes.includes(error.code);
}

/** Network failures and unparseable responses become problems too, so screens handle one shape. */
export function syntheticProblem(status: number, code: string, detail?: string): Problem {
  return { status, code, title: code, detail };
}

/**
 * Converts a JSON pointer into the request body (`/lines/0/quantity`) into a react-hook-form field
 * path (`lines.0.quantity`). The empty pointer addresses the whole body.
 */
export function pointerToPath(pointer: string): string {
  return pointer
    .split('/')
    .slice(1)
    .map((segment) => segment.replace(/~1/g, '/').replace(/~0/g, '~'))
    .join('.');
}
