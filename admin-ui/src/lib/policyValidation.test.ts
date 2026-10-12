import { describe, it, expect } from 'vitest';
import {
  validateOrigin, normalizeOrigin, timeoutError,
  ORIGIN_FORMAT_ERROR, ORIGIN_DUPLICATE_ERROR, ORIGIN_DEFAULT_PORT_ERROR, ORIGIN_LENGTH_ERROR, ORIGIN_COUNT_ERROR, TIMEOUT_ERROR,
} from './policyValidation';

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
  it('대소문자만 다른 중복도 중복 오류', () => {
    expect(validateOrigin('https://A.Example.com', ['https://a.example.com'])).toBe(ORIGIN_DUPLICATE_ERROR);
    expect(validateOrigin('https://a.example.com', ['https://A.EXAMPLE.com'])).toBe(ORIGIN_DUPLICATE_ERROR);
  });
  it('대문자 스킴·호스트는 통과하고 normalizeOrigin 이 앞뒤 공백 제거 + 소문자로 맞춘다', () => {
    expect(validateOrigin('HTTPS://Admin.Example.COM:8443', [])).toBeNull();
    expect(normalizeOrigin('  HTTPS://Admin.Example.COM:8443 ')).toBe('https://admin.example.com:8443');
  });
  it('기본 포트(https :443, http :80)를 명시하면 오류', () => {
    expect(validateOrigin('https://a.example.com:443', [])).toBe(ORIGIN_DEFAULT_PORT_ERROR);
    expect(validateOrigin('http://localhost:80', [])).toBe(ORIGIN_DEFAULT_PORT_ERROR);
    expect(validateOrigin('https://a.example.com:80', [])).toBeNull();
    expect(validateOrigin('http://localhost:443', [])).toBeNull();
  });
  it('0 으로 시작하는 포트 · 범위 밖 포트 · 빈 포트는 형식 오류', () => {
    for (const v of ['https://a.com:08443', 'https://a.com:0443', 'https://a.com:65536', 'https://a.com:', 'https://a.com:00']) {
      expect(validateOrigin(v, [])).toBe(ORIGIN_FORMAT_ERROR);
    }
    expect(validateOrigin('https://a.com:65535', [])).toBeNull();
    expect(validateOrigin('https://a.com:1', [])).toBeNull();
  });
  it('IPv6 리터럴 · IDN(유니코드) 호스트는 형식 오류, 퓨니코드는 통과', () => {
    expect(validateOrigin('https://[::1]:8443', [])).toBe(ORIGIN_FORMAT_ERROR);
    expect(validateOrigin('http://[::1]:8443', [])).toBe(ORIGIN_FORMAT_ERROR);
    expect(validateOrigin('https://한글.example.com', [])).toBe(ORIGIN_FORMAT_ERROR);
    expect(validateOrigin('https://xn--bj0bj06e.example.com', [])).toBeNull();
  });
  it('백엔드 한도: origin 256자 이하, 목록 64개 이하', () => {
    const long = `https://${'a'.repeat(240)}.example.com`; // 8 + 240 + 12 = 260
    expect(validateOrigin(long, [])).toBe(ORIGIN_LENGTH_ERROR);
    const ok = `https://${'a'.repeat(50)}.example.com`;
    expect(validateOrigin(ok, [])).toBeNull();
    const full = Array.from({ length: 64 }, (_, i) => `https://h${i}.example.com`);
    expect(validateOrigin('https://new.example.com', full)).toBe(ORIGIN_COUNT_ERROR);
    expect(validateOrigin('https://new.example.com', full.slice(0, 63))).toBeNull();
    expect(validateOrigin(full[0], full)).toBe(ORIGIN_DUPLICATE_ERROR);
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
