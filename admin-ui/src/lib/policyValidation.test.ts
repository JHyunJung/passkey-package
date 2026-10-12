import { describe, it, expect } from 'vitest';
import { validateOrigin, timeoutError, ORIGIN_FORMAT_ERROR, ORIGIN_DUPLICATE_ERROR, TIMEOUT_ERROR } from './policyValidation';

describe('validateOrigin', () => {
  it('https://호스트[:포트] 는 통과', () => {
    expect(validateOrigin('https://admin.example.com', [])).toBeNull();
    expect(validateOrigin('https://admin.example.com:8443', [])).toBeNull();
    expect(validateOrigin('  https://a.example.com  ', [])).toBeNull();
  });
  it('스킴 누락 · 경로 · 끝 슬래시 · 와일드카드 · 잘못된 포트는 형식 오류', () => {
    for (const v of ['admin.example.com', 'https://admin.example.com/login', 'https://admin.example.com/', 'https://*.example.com', 'ftp://x.com', 'https://', 'https://a.com:99999', 'https://a.com:0']) {
      expect(validateOrigin(v, [])).toBe(ORIGIN_FORMAT_ERROR);
    }
  });
  it('http 는 localhost · 127.0.0.1 만 허용', () => {
    expect(validateOrigin('http://localhost:5173', [])).toBeNull();
    expect(validateOrigin('http://127.0.0.1:8080', [])).toBeNull();
    expect(validateOrigin('http://admin.example.com', [])).toBe(ORIGIN_FORMAT_ERROR);
  });
  it('이미 목록에 있으면 중복 오류', () => {
    expect(validateOrigin('https://a.example.com', ['https://a.example.com'])).toBe(ORIGIN_DUPLICATE_ERROR);
  });
  it('형식 오류 문구는 예시를 담는다', () => {
    expect(ORIGIN_FORMAT_ERROR).toBe('형식이 올바르지 않습니다. https:// 로 시작하고 경로 없이 입력하세요. 예: https://admin.example.com');
  });
});

describe('timeoutError', () => {
  it('1–1440 정수만 통과', () => {
    for (const v of ['1', '30', '1440', ' 45 ']) expect(timeoutError(v)).toBeNull();
    for (const v of ['0', '1441', '', '-5', '1.5', 'abc']) expect(timeoutError(v)).toBe(TIMEOUT_ERROR);
  });
  it('오류 문구', () => {
    expect(TIMEOUT_ERROR).toBe('1–1440 사이의 분을 입력하세요. 비워 둘 수 없습니다.');
  });
});
