import { describe, it, expect } from 'vitest';
import { isTransientError } from './transientError';
import { ApiError } from '@/api/types';

describe('isTransientError', () => {
  it('fetch 실패(TypeError)와 5xx 는 일시 오류', () => {
    expect(isTransientError(new TypeError('Failed to fetch'))).toBe(true);
    expect(isTransientError(new ApiError(500, 'C999', 'HTTP 500'))).toBe(true);
    expect(isTransientError(new ApiError(503, 'C999', 'HTTP 503'))).toBe(true);
  });
  it('4xx 와 일반 Error 는 일시 오류가 아니다', () => {
    expect(isTransientError(new ApiError(400, 'C400', 'bad'))).toBe(false);
    expect(isTransientError(new ApiError(404, 'C404', 'nf'))).toBe(false);
    expect(isTransientError(new Error('boom'))).toBe(false);
    expect(isTransientError(null)).toBe(false);
  });
});
