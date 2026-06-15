import { describe, it, expect } from 'vitest';
import { actionLabel, eventSentence } from './activityLabels';

describe('actionLabel', () => {
  it('매핑된 액션은 한글 라벨', () => {
    expect(actionLabel('API_KEY_ISSUE')).toBe('API 키 발급');
    expect(actionLabel('WEBAUTHN_CONFIG_UPDATED')).toBe('설정 변경');
    expect(actionLabel('ADMIN_LOGIN_FAILED')).toBe('로그인 실패');
  });
  it('매핑 없는 액션은 원문 fallback', () => {
    expect(actionLabel('SOME_UNKNOWN_ACTION')).toBe('SOME_UNKNOWN_ACTION');
  });
});

describe('eventSentence', () => {
  it('행위자·테넌트·대상을 문장으로', () => {
    const s = eventSentence({
      action: 'API_KEY_ISSUE',
      actorEmail: 'admin@acme.com',
      tenantSlug: 'acme',
      targetType: 'API_KEY',
      targetId: 'pk_abcdef123456',
    });
    expect(s).toContain('admin@acme.com');
    expect(s).toContain('acme');
    expect(s).toContain('API 키 발급');
  });
  it('행위자 없으면 system', () => {
    const s = eventSentence({
      action: 'ADMIN_LOGIN_FAILED',
      actorEmail: '',
      tenantSlug: null,
      targetType: null,
      targetId: null,
    });
    expect(s).toContain('로그인 실패');
  });
});
