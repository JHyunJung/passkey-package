import { describe, it, expect } from 'vitest';
import { apkKeyHashFromFingerprint, isApkKeyHashOrigin, APK_KEY_HASH_PREFIX } from './apkKeyHash';

describe('apkKeyHashFromFingerprint', () => {
  // 32바이트 0x00 → SHA-256 자리 더미. base64url(32x 0x00) = "AAAA...A" (43 'A').
  const ALL_ZERO_HEX = '00:'.repeat(31) + '00'; // 32 bytes, colon-separated
  const EXPECTED_ZERO = APK_KEY_HASH_PREFIX + 'A'.repeat(43);

  it('콜론 구분 64 hex 를 apk-key-hash 로 변환한다', () => {
    const r = apkKeyHashFromFingerprint(ALL_ZERO_HEX);
    expect(r).toEqual({ ok: true, value: EXPECTED_ZERO });
  });

  it('콜론 없는 64 hex 도 허용한다', () => {
    const r = apkKeyHashFromFingerprint('00'.repeat(32));
    expect(r).toEqual({ ok: true, value: EXPECTED_ZERO });
  });

  it('대문자/소문자 hex 를 모두 허용한다', () => {
    const upper = apkKeyHashFromFingerprint('AB'.repeat(32));
    const lower = apkKeyHashFromFingerprint('ab'.repeat(32));
    expect(upper.ok).toBe(true);
    expect(upper).toEqual(lower);
  });

  it('변환 결과는 항상 43자 base64url 이다', () => {
    const r = apkKeyHashFromFingerprint('1F'.repeat(32));
    expect(r.ok).toBe(true);
    if (r.ok) {
      const body = r.value.slice(APK_KEY_HASH_PREFIX.length);
      expect(body).toMatch(/^[A-Za-z0-9_-]{43}$/);
    }
  });

  it('hex 길이가 64 가 아니면 거부한다', () => {
    expect(apkKeyHashFromFingerprint('00'.repeat(20)).ok).toBe(false); // 40 hex
    expect(apkKeyHashFromFingerprint('00'.repeat(40)).ok).toBe(false); // 80 hex
  });

  it('hex 가 아닌 문자가 있으면 거부한다', () => {
    expect(apkKeyHashFromFingerprint('ZZ'.repeat(32)).ok).toBe(false);
  });

  it('빈 입력은 거부한다', () => {
    expect(apkKeyHashFromFingerprint('   ').ok).toBe(false);
  });
});

describe('isApkKeyHashOrigin', () => {
  it('android prefix 면 true', () => {
    expect(isApkKeyHashOrigin(APK_KEY_HASH_PREFIX + 'A'.repeat(43))).toBe(true);
  });
  it('https origin 이면 false', () => {
    expect(isApkKeyHashOrigin('https://example.com')).toBe(false);
  });
});
