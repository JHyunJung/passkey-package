import { describe, it, expect } from 'vitest';
import { describeError, ActionBlockedError } from './describeError';
import { eulReul, iGa } from './josa';
import { ApiError } from '@/api/types';

describe('describeError', () => {
  it('5xx 는 서버 일시 오류', () => {
    const i = describeError(new ApiError(503, 'C999', 'HTTP 503'));
    expect(i.title).toBe('서버가 잠시 응답하지 않습니다');
    expect(i.httpStatus).toBe(503);
  });
  it('fetch 실패(TypeError)는 네트워크 오류', () => {
    expect(describeError(new TypeError('Failed to fetch')).title).toBe('네트워크에 연결할 수 없습니다');
  });
  it('403 은 권한 없음, 404 는 찾을 수 없음', () => {
    expect(describeError(new ApiError(403, 'A003', 'Forbidden')).title).toBe('권한이 없습니다');
    expect(describeError(new ApiError(404, 'C404', 'Not found')).title).toBe('찾을 수 없습니다');
  });
  it('429 는 요청 과다 — 잠시 후 다시 시도', () => {
    expect(describeError(new ApiError(429, 'error', 'error'))).toMatchObject({ title: '요청이 너무 많습니다', detail: '잠시 후 다시 시도하세요.' });
  });
  it('그 밖의 ApiError 는 서버 메시지를 detail 로', () => {
    const i = describeError(new ApiError(400, 'V001', 'slug 형식이 올바르지 않습니다'));
    expect(i.title).toBe('요청을 처리하지 못했습니다');
    expect(i.detail).toBe('slug 형식이 올바르지 않습니다');
    expect(i.code).toBe('V001');
  });
});

describe('ActionBlockedError', () => {
  it('지금 실행하지 않은 이유를 쉬운 문구 그대로 옮긴다(알 수 없는 오류가 아니다)', () => {
    expect(describeError(new ActionBlockedError('목록을 새로 고친 뒤 다시 시도하세요.'))).toEqual({ title: '지금은 실행할 수 없습니다', detail: '목록을 새로 고친 뒤 다시 시도하세요.' });
  });
});

describe('josa', () => {
  it('받침 유무로 을/를, 이/가', () => {
    expect(eulReul('목록')).toBe('을');
    expect(eulReul('정보')).toBe('를');
    expect(iGa('키')).toBe('가');
    expect(iGa('목록')).toBe('이');
  });
  it('한글이 아니면 을/이 기본', () => {
    expect(eulReul('Credential')).toBe('을');
    expect(iGa('API')).toBe('이');
  });
});
