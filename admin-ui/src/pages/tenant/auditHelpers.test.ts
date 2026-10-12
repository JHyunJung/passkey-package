import { describe, it, expect } from 'vitest';
import { windowRange, isFailure, eventTone, payloadDiff, diffValue, dayLabel, WINDOWS, eventGroup, EVENT_GROUPS } from './auditHelpers';

describe('auditHelpers', () => {
  it('windowRange: now 기준 hours 시간 전 ~ now', () => {
    const now = Date.parse('2026-10-11T03:00:00Z');
    expect(windowRange(WINDOWS['24h'].hours, now)).toEqual({ from: '2026-10-10T03:00:00.000Z', to: '2026-10-11T03:00:00.000Z' });
    expect(WINDOWS['7d'].hours).toBe(168);
    expect(WINDOWS['30d'].hours).toBe(720);
  });

  it('isFailure: result 가 있으면 우선, 없으면 실패 목록·이름 규칙', () => {
    expect(isFailure({ eventType: 'ATTESTATION_TRUST_FAILED' })).toBe(true);
    expect(isFailure({ eventType: 'SIGNATURE_COUNTER_REGRESSION' })).toBe(true);
    expect(isFailure({ eventType: 'AUTH_FAIL' })).toBe(true);
    expect(isFailure({ eventType: 'WEBAUTHN_CONFIG_UPDATED' })).toBe(false);
    expect(isFailure({ eventType: 'CREDENTIAL_REVOKED' })).toBe(false);
    expect(isFailure({ eventType: 'CUSTOM_CHECK', result: 'FAILURE' })).toBe(true);
    expect(isFailure({ eventType: 'RETRY_FAILED', result: 'SUCCESS' })).toBe(false);
  });

  it('eventTone', () => {
    expect(eventTone({ eventType: 'AUTH_FAIL' })).toBe('bad');
    expect(eventTone({ eventType: 'WEBAUTHN_CONFIG_UPDATED' })).toBe('warn');
    expect(eventTone({ eventType: 'API_KEY_ROTATED' })).toBe('warn');
    expect(eventTone({ eventType: 'AUTH_SUCCESS' })).toBe('info');
    expect(eventTone({ eventType: 'MDS_SYNC' })).toBe('neutral');
  });

  it('payloadDiff: {field:{from,to}} 와 {before,after} 만 diff 로 본다', () => {
    expect(payloadDiff({ timeoutMs: { from: 60000, to: 120000 }, note: 'x' })).toEqual([{ field: 'timeoutMs', from: 60000, to: 120000 }]);
    expect(payloadDiff({ before: { a: 1, b: 2 }, after: { a: 1, b: 3, c: true } })).toEqual([
      { field: 'b', from: 2, to: 3 },
      { field: 'c', from: undefined, to: true },
    ]);
    expect(payloadDiff({ reason: 'x' })).toEqual([]);
    expect(payloadDiff(null)).toEqual([]);
  });

  it('diffValue', () => {
    expect(diffValue('a')).toBe('a');
    expect(diffValue(60000)).toBe('60000');
    expect(diffValue(['x'])).toBe('["x"]');
    expect(diffValue(undefined)).toBe('—');
  });

  it('dayLabel: KST 기준 오늘/어제/날짜', () => {
    const now = Date.parse('2026-10-11T03:00:00Z'); // KST 12:00
    expect(dayLabel('2026-10-11T00:30:00Z', now)).toBe('오늘');
    expect(dayLabel('2026-10-10T14:59:00Z', now)).toBe('어제');   // KST 10-10 23:59
    expect(dayLabel('2026-10-10T15:00:00Z', now)).toBe('오늘');   // KST 10-11 00:00
    expect(dayLabel('2026-10-08T05:00:00Z', now)).toBe('2026-10-08');
  });
  it('eventGroup: 인증 · 등록 · 설정 변경 · 키 관리, 그 밖은 기타', () => {
    expect(eventGroup('AUTH_SUCCESS')).toBe('auth');
    expect(eventGroup('AUTH_FAIL')).toBe('auth');
    expect(eventGroup('AUTHENTICATION_FINISH_OK')).toBe('auth');
    expect(eventGroup('CREDENTIAL_AUTHENTICATED')).toBe('auth');
    expect(eventGroup('SIGNATURE_COUNTER_REGRESSION')).toBe('auth');
    expect(eventGroup('REGISTRATION_FINISH_OK')).toBe('registration');
    expect(eventGroup('CREDENTIAL_REGISTERED')).toBe('registration');
    expect(eventGroup('ATTESTATION_TRUST_FAILED')).toBe('registration');
    expect(eventGroup('WEBAUTHN_CONFIG_UPDATED')).toBe('config');
    expect(eventGroup('AAGUID_POLICY_UPDATED')).toBe('config');
    expect(eventGroup('TENANT_SUSPEND')).toBe('config');
    expect(eventGroup('API_KEY_ROTATED')).toBe('keys');
    expect(eventGroup('CREDENTIAL_REVOKE')).toBe('keys');
    expect(eventGroup('CREDENTIAL_REVOKED')).toBe('keys');
    // 어느 그룹에도 안 맞는 이벤트도 필터로 볼 수 있어야 한다 → 기타
    expect(eventGroup('MDS_BLOB_SYNC')).toBe('other');
    expect(EVENT_GROUPS.map((g) => g.label)).toEqual(['인증', '등록', '설정 변경', '키 관리', '기타']);
  });
});
