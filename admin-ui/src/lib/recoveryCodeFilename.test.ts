import { describe, it, expect } from 'vitest';
import { recoveryCodesFilename } from './recoveryCodeFilename';

const DATE = new Date(2026, 5, 10); // 2026-06-10 local (month is 0-based)

describe('recoveryCodesFilename', () => {
  it('uses email local part + YYYYMMDD', () => {
    expect(recoveryCodesFilename('alice@corp.com', DATE))
      .toBe('passkey-admin-recovery-codes-alice-20260610.txt');
  });

  it('sanitizes account: lowercases, replaces unsafe chars, trims dashes', () => {
    expect(recoveryCodesFilename('Bob.Smith+tag@x.io', DATE))
      .toBe('passkey-admin-recovery-codes-bob.smith-tag-20260610.txt');
  });

  it('collapses runs of dashes and trims leading/trailing dashes', () => {
    expect(recoveryCodesFilename('--a  b--@x', DATE))
      .toBe('passkey-admin-recovery-codes-a-b-20260610.txt');
  });

  it('falls back to no account segment when email is empty', () => {
    expect(recoveryCodesFilename('', DATE))
      .toBe('passkey-admin-recovery-codes-20260610.txt');
  });

  it('falls back when sanitized local part is empty', () => {
    expect(recoveryCodesFilename('@@@@@@@', DATE))
      .toBe('passkey-admin-recovery-codes-20260610.txt');
  });

  it('zero-pads month and day', () => {
    expect(recoveryCodesFilename('a@x', new Date(2026, 0, 3)))
      .toBe('passkey-admin-recovery-codes-a-20260103.txt');
  });
});
