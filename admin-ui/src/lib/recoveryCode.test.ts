import { describe, it, expect } from 'vitest';
import { formatRecoveryCode } from './recoveryCode';

describe('formatRecoveryCode', () => {
  it('uppercases and inserts a dash after 4 chars', () => {
    expect(formatRecoveryCode('ab3f2k7m')).toBe('AB3F-2K7M');
  });

  it('keeps short input without a dash', () => {
    expect(formatRecoveryCode('ab3')).toBe('AB3');
    expect(formatRecoveryCode('ab3f')).toBe('AB3F');
  });

  it('inserts dash as soon as the 5th char arrives', () => {
    expect(formatRecoveryCode('ab3f2')).toBe('AB3F-2');
  });

  it('drops disallowed chars (0,1,I,O, symbols, spaces) and existing dashes', () => {
    expect(formatRecoveryCode('ab3f-2k7m')).toBe('AB3F-2K7M');
    expect(formatRecoveryCode('AB3F 2K7M')).toBe('AB3F-2K7M');
    expect(formatRecoveryCode('a@b#3$f2k7m')).toBe('AB3F-2K7M');
    expect(formatRecoveryCode('o0i1ab3f2')).toBe('AB3F-2');
  });

  it('caps at 8 alphabet chars (XXXX-XXXX)', () => {
    expect(formatRecoveryCode('ab3f2k7mEXTRA')).toBe('AB3F-2K7M');
  });

  it('returns empty string for empty/all-invalid input', () => {
    expect(formatRecoveryCode('')).toBe('');
    expect(formatRecoveryCode('0011oo')).toBe('');
  });

  it('does not synthesize alphabet chars via unicode case-folding (ß→SS)', () => {
    expect(formatRecoveryCode('ßßßß')).toBe('');
  });
});
