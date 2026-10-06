import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement, ReactNode } from 'react';
import { CompanyProvider } from '@/auth/company';
import type { CompanyAccess } from '@/auth/session';

export const testCompany: CompanyAccess = {
  id: 'c1',
  code: 'DEMO',
  displayName: 'Demo',
  permissions: ['org.branch.read', 'org.branch.manage'],
  branchScope: [],
};

/** Renders inside a fresh query client and a company context with the given permissions. */
export function renderInCompany(ui: ReactElement, company: Partial<CompanyAccess> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <CompanyProvider company={{ ...testCompany, ...company }}>{children}</CompanyProvider>
    </QueryClientProvider>
  );
  return { client, ...render(ui, { wrapper }) };
}

export function jsonResponse(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}
