import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StatusBadge, toneOf } from './status-badge';

describe('StatusBadge', () => {
  it('labels known and unknown states', () => {
    render(
      <>
        <StatusBadge status="PARTIALLY_DELIVERED" />
        <StatusBadge status="SOMETHING_NEW" />
      </>,
    );
    expect(screen.getByText('Partially delivered')).toBeInTheDocument();
    expect(screen.getByText('Something new')).toBeInTheDocument();
  });

  it('maps states to tones', () => {
    expect(toneOf('POSTED')).toBe('success');
    expect(toneOf('CANCELLED')).toBe('danger');
    expect(toneOf('SUBMITTED')).toBe('warning');
    expect(toneOf('DRAFT')).toBe('neutral');
  });
});
