// Permission-aware UI (UX only): hide navigation and actions the user cannot use. The server stays the
// authority (403/404), so a stale permission set only means a button that answers with an error.
import type { CompanyAccess } from './session';

export type Permission = string;

export interface Permissions {
  can: (permission: Permission) => boolean;
  canAll: (...permissions: Permission[]) => boolean;
  canAny: (...permissions: Permission[]) => boolean;
}

export function permissionsOf(company: CompanyAccess | undefined): Permissions {
  const granted = new Set(company?.permissions ?? []);
  const can = (permission: Permission) => granted.has(permission);
  return {
    can,
    canAll: (...permissions) => permissions.every(can),
    canAny: (...permissions) => permissions.length === 0 || permissions.some(can),
  };
}
