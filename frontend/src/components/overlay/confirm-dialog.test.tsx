import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/errors';
import { ConfirmDialog } from './confirm-dialog';

describe('ConfirmDialog', () => {
  it('requires a reason and shows the server problem without closing', async () => {
    const onConfirm = vi.fn().mockRejectedValue(new ApiError({ status: 409, code: 'INVALID_STATE', detail: 'Cannot cancel' }));
    const onOpenChange = vi.fn();
    const user = userEvent.setup();
    render(<ConfirmDialog open onOpenChange={onOpenChange} title="Cancel order?" reason="required" confirmLabel="Cancel order" onConfirm={onConfirm} />);

    const confirm = screen.getByRole('button', { name: 'Cancel order' });
    expect(confirm).toBeDisabled();
    await user.type(screen.getByLabelText(/reason/i), 'Customer withdrew');
    await user.click(confirm);

    expect(onConfirm).toHaveBeenCalledWith('Customer withdrew');
    expect(await screen.findByRole('alert')).toHaveTextContent(/not possible in the record’s current state/i);
    expect(onOpenChange).not.toHaveBeenCalledWith(false);
  });
});
