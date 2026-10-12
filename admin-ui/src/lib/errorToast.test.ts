import { describe, it, expect } from 'vitest';
import { errorToast } from './errorToast';
import { ApiError, RequestTimeoutError } from '@/api/types';

describe('errorToast', () => {
  it('4xx 서버 원문은 본문에 넣지 않고 detail 에만 둔다', () => {
    const t = errorToast(new ApiError(400, 'C001', 'rpId must not be blank', undefined, 'tr-1'), '저장하지 못했습니다');
    expect(t).toMatchObject({ kind: 'err', title: '저장하지 못했습니다', message: '요청을 처리하지 못했습니다', traceId: 'tr-1' });
    expect(t.message).not.toContain('rpId');
    expect(t.detail).toBe('HTTP 400 · [C001] rpId must not be blank');
  });

  it('5xx·네트워크·시간 초과는 쉬운 설명까지 본문에 둔다', () => {
    expect(errorToast(new ApiError(503, 'C999', 'HTTP 503'), 'X').message).toBe('서버가 잠시 응답하지 않습니다. 저장된 데이터는 그대로 있습니다. 잠시 후 다시 시도하세요.');
    expect(errorToast(new TypeError('Failed to fetch'), 'X')).toMatchObject({ message: '네트워크에 연결할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도하세요.', detail: 'Failed to fetch' });
    expect(errorToast(new RequestTimeoutError(30_000), 'X').message).toMatch(/^서버가 잠시 응답하지 않습니다\./);
  });

  it('알 수 없는 오류의 원문(Error.message)도 detail 에만', () => {
    const t = errorToast(new Error('[C500] boom'), 'X');
    expect(t.message).toBe('알 수 없는 오류가 발생했습니다');
    expect(t.detail).toBe('[C500] boom');
  });

  it('message 를 직접 주면 그 문구를 쓴다(원문은 그대로 detail)', () => {
    const t = errorToast(new ApiError(409, 'C409', 'conflict'), '되돌리지 못했습니다', '목록에서 다시 시도하세요.');
    expect(t.message).toBe('목록에서 다시 시도하세요.');
    expect(t.detail).toBe('HTTP 409 · [C409] conflict');
  });
});
