import { act, renderHook } from '@testing-library/react';
import { useForm } from 'react-hook-form';
import { describe, expect, it } from 'vitest';
import { ApiError } from '@/api/errors';
import { applyServerErrors } from './server-errors';
import { compact, mergePatch } from './schema';

describe('server field errors', () => {
  it('attaches pointers to fields and returns the rest', () => {
    const { result } = renderHook(() =>
      useForm({ defaultValues: { name: '', lines: [{ quantity: '1' }] } }),
    );
    const error = new ApiError({
      status: 422,
      code: 'VALIDATION_FAILED',
      errors: [
        { pointer: '/name', code: 'NOT_BLANK', message: 'must not be blank' },
        { pointer: '/lines/0/quantity', code: 'POSITIVE', message: 'must be positive' },
        { pointer: '/unknownField', code: 'X', message: 'other' },
      ],
    });
    let unmapped: ReturnType<typeof applyServerErrors> = [];
    act(() => {
      unmapped = applyServerErrors(result.current, error);
    });
    expect(result.current.getFieldState('name').error?.message).toBe('must not be blank');
    expect(result.current.getFieldState('lines.0.quantity').error?.message).toBe('must be positive');
    expect(unmapped.map((p) => p.pointer)).toEqual(['/unknownField']);
  });
});

describe('request bodies', () => {
  it('builds merge patches of changed fields, clearing emptied ones', () => {
    expect(mergePatch({ name: 'A', city: 'X', code: 'C' }, { name: 'B', city: '', code: 'C' })).toEqual({ name: 'B', city: null });
  });

  it('drops empty optional values from create bodies', () => {
    expect(compact({ code: 'A', city: '', parentId: null, active: false })).toEqual({ code: 'A', active: false });
  });
});
