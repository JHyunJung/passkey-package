import { describe, it, expect } from 'vitest';
import { formatAaguid } from './AaguidPolicyTab';

describe('formatAaguid', () => {
  it('하이픈 없는 32 hex 를 UUID 형식으로 변환한다', () => {
    expect(formatAaguid('ea9b8d664d011d213ce4b6b48cb575d4')).toBe('ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4');
  });

  it('8자까지는 하이픈을 넣지 않는다', () => {
    expect(formatAaguid('ea9b8d66')).toBe('ea9b8d66');
  });

  it('9번째 글자부터 첫 하이픈을 삽입한다', () => {
    expect(formatAaguid('ea9b8d664')).toBe('ea9b8d66-4');
  });

  it('대문자를 소문자로 정규화한다', () => {
    expect(formatAaguid('EA9B8D66-4D01')).toBe('ea9b8d66-4d01');
  });

  it('이미 하이픈이 있어도 중복 없이 재포맷한다', () => {
    expect(formatAaguid('ea9b8d66-4d01-1d21')).toBe('ea9b8d66-4d01-1d21');
  });

  it('16진수가 아닌 문자는 제거한다', () => {
    expect(formatAaguid('ea9b!@#8d66xyz4d01')).toBe('ea9b8d66-4d01');
  });

  it('32 hex 를 넘는 입력은 잘라낸다', () => {
    expect(formatAaguid('ea9b8d664d011d213ce4b6b48cb575d4EXTRA')).toBe('ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4');
  });

  it('빈 문자열은 빈 문자열을 반환한다', () => {
    expect(formatAaguid('')).toBe('');
  });

  it('하이픈만 입력하면 빈 문자열이 된다', () => {
    expect(formatAaguid('----')).toBe('');
  });

  it('공백을 포함해도 hex 만 남긴다', () => {
    expect(formatAaguid('  ea9b 8d66  ')).toBe('ea9b8d66');
  });

  it('각 세그먼트 경계(8/12/16/20)에서 하이픈을 넣는다', () => {
    expect(formatAaguid('ea9b8d664d01')).toBe('ea9b8d66-4d01');       // 12 → seg2 끝
    expect(formatAaguid('ea9b8d664d011d21')).toBe('ea9b8d66-4d01-1d21'); // 16 → seg3 끝
    expect(formatAaguid('ea9b8d664d011d213ce4')).toBe('ea9b8d66-4d01-1d21-3ce4'); // 20 → seg4 끝
  });
});
