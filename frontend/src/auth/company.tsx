import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { companyApi, type CompanyApi } from '@/api/client';
import { permissionsOf, type Permissions } from './permissions';
import type { CompanyAccess } from './session';

export interface CompanyContextValue extends Permissions {
  companyId: string;
  company: CompanyAccess;
  api: CompanyApi;
  /** The branches the user is restricted to, or null for all branches. */
  branchScope: string[] | null;
}

const CompanyContext = createContext<CompanyContextValue | null>(null);

export function CompanyProvider({ company, children }: { company: CompanyAccess; children: ReactNode }) {
  const value = useMemo<CompanyContextValue>(() => {
    const id = company.id!;
    return {
      companyId: id,
      company,
      api: companyApi(id),
      branchScope: company.branchScope && company.branchScope.length > 0 ? [...company.branchScope] : null,
      ...permissionsOf(company),
    };
  }, [company]);
  return <CompanyContext.Provider value={value}>{children}</CompanyContext.Provider>;
}

/** The active company, its typed API and the user's permissions in it. */
export function useCompany(): CompanyContextValue {
  const value = useContext(CompanyContext);
  if (!value) throw new Error('useCompany() outside a company route');
  return value;
}

export function useOptionalCompany(): CompanyContextValue | null {
  return useContext(CompanyContext);
}
