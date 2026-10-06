import { randomUUID } from 'node:crypto';
import { Session } from './erp-api';
import { storage } from './fixtures';
import type { SeedState } from './seed';

export function apiAs(user: 'alice' | 'bob' | 'erin', seed: SeedState): Session {
  return Session.fromStorage(storage(user), seed.companyId);
}

/** A posted customer invoice for CUST1 (order → confirm → delivery → invoice), through the API. */
export async function postedInvoice(seed: SeedState, quantity = '2'): Promise<{ id: string; number: string; total: string }> {
  const alice = apiAs('alice', seed);
  const ids = seed.ids;
  let order = await alice.post('/sales-orders', {
    customerId: ids.partnerCUST1,
    warehouseId: ids.warehouse,
    lines: [{ variantId: ids.variantWIDGET, quantity, uomId: ids.uomEA }],
  });
  order = await alice.post(`/sales-orders/${order.id}/confirm`, {}, { ifMatch: order.version, idempotencyKey: randomUUID() });
  const delivery = await alice.post('/deliveries', { salesOrderId: order.id });
  await alice.post(`/deliveries/${delivery.id}/post`, undefined, { ifMatch: delivery.version, idempotencyKey: randomUUID() });
  const invoice = await alice.post('/invoices/from-order', { salesOrderId: order.id });
  const posted = await alice.post(`/invoices/${invoice.id}/post`, undefined, { ifMatch: invoice.version, idempotencyKey: randomUUID() });
  return { id: posted.id ?? invoice.id, number: posted.number ?? invoice.number, total: posted.total ?? invoice.total };
}
