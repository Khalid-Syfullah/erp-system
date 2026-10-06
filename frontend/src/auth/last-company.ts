import { readStored, writeStored } from '@/lib/storage';

const KEY = 'erp.lastCompany';

export function lastCompanyId(): string | null {
  return readStored(KEY);
}

export function rememberCompany(companyId: string): void {
  writeStored(KEY, companyId);
}
