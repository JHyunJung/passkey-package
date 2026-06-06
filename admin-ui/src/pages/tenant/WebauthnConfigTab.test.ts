import { describe, it, expect } from 'vitest';
import { validateOrigin } from './WebauthnConfigTab';

// origin host 는 rpId 와 같거나 rpId 의 서브도메인이어야 한다(WebAuthn 스펙).
const RP = 'dev-passkey.crosscert.com';

describe('validateOrigin', () => {
  it('rpId 자기 자신을 허용하고 https origin 으로 정규화한다', () => {
    const r = validateOrigin('https://dev-passkey.crosscert.com', RP);
    expect(r).toEqual({ ok: true, value: 'https://dev-passkey.crosscert.com' });
  });

  it('스킴 없이 host 만 입력하면 https:// 를 가정한다', () => {
    const r = validateOrigin('dev-passkey.crosscert.com', RP);
    expect(r).toEqual({ ok: true, value: 'https://dev-passkey.crosscert.com' });
  });

  it('서브도메인을 허용한다', () => {
    const r = validateOrigin('sub.dev-passkey.crosscert.com', RP);
    expect(r).toEqual({ ok: true, value: 'https://sub.dev-passkey.crosscert.com' });
  });

  it('여러 단계 서브도메인도 허용한다', () => {
    const r = validateOrigin('a.b.dev-passkey.crosscert.com', RP);
    expect(r.ok).toBe(true);
  });

  it('port 가 있어도 host 가 맞으면 허용하고 port 를 보존한다', () => {
    const r = validateOrigin('https://dev-passkey.crosscert.com:8443', RP);
    expect(r).toEqual({ ok: true, value: 'https://dev-passkey.crosscert.com:8443' });
  });

  it('경로·쿼리는 제거하고 scheme://host 로 정규화한다', () => {
    const r = validateOrigin('https://sub.dev-passkey.crosscert.com/login?x=1', RP);
    expect(r).toEqual({ ok: true, value: 'https://sub.dev-passkey.crosscert.com' });
  });

  it('rpId 와 비슷하지만 다른 host 는 거부한다 (de-passkey ≠ dev-passkey)', () => {
    const r = validateOrigin('de-passkey.crosscert.com', RP);
    expect(r.ok).toBe(false);
  });

  it('상위 도메인은 거부한다 (rpId 보다 넓음)', () => {
    const r = validateOrigin('crosscert.com', RP);
    expect(r.ok).toBe(false);
  });

  it('rpId 를 부분 문자열로만 포함하는 host(접미사 위장)는 거부한다', () => {
    // 'evil-dev-passkey.crosscert.com' 는 '.dev-passkey.crosscert.com' 로 끝나지 않는다.
    const r = validateOrigin('evil-dev-passkey.crosscert.com', RP);
    expect(r.ok).toBe(false);
  });

  it('rpId 를 다른 도메인의 서브로 위장한 host 는 거부한다', () => {
    const r = validateOrigin('dev-passkey.crosscert.com.attacker.com', RP);
    expect(r.ok).toBe(false);
  });

  it('빈 입력은 거부한다', () => {
    expect(validateOrigin('   ', RP).ok).toBe(false);
  });

  it('대소문자를 구분하지 않는다', () => {
    const r = validateOrigin('SUB.Dev-Passkey.Crosscert.COM', RP);
    expect(r.ok).toBe(true);
  });
});
