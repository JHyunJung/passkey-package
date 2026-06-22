import { describe, it, expect } from 'vitest';
import { statusLabel, STATUS_LABELS, AAGUID_MODE_LABELS } from './labels';

describe('statusLabel', () => {
  it('maps known status enums to Korean', () => {
    expect(statusLabel('ACTIVE')).toBe('활성');
    expect(statusLabel('SUSPENDED')).toBe('정지');
    expect(statusLabel('INTACT')).toBe('정상');
    expect(statusLabel('TAMPERED')).toBe('위변조');
    expect(statusLabel('OPEN')).toBe('처리중');
    expect(statusLabel('RESOLVED')).toBe('해결');
    expect(statusLabel('SUCCESS')).toBe('성공');
    expect(statusLabel('FAILED')).toBe('실패');
  });

  it('returns the original value for unmapped status (safe fallback)', () => {
    expect(statusLabel('UNKNOWN_FUTURE')).toBe('UNKNOWN_FUTURE');
  });

  it('covers all spec-defined status enums', () => {
    const expected = {
      ACTIVE: '활성', SUSPENDED: '정지', REVOKED: '회수', EXPIRED: '만료',
      PENDING: '대기', INTACT: '정상', TAMPERED: '위변조', OPEN: '처리중',
      RESOLVED: '해결', SUCCESS: '성공', FAILED: '실패', ROTATED: '교체됨',
      SYNCED: '동기화됨', SKIPPED: '건너뜀',
    };
    expect(STATUS_LABELS).toEqual(expected);
  });

  it('maps AAGUID policy modes to Korean', () => {
    expect(AAGUID_MODE_LABELS).toEqual({
      ANY: '전체 허용', ALLOWLIST: '허용 목록', DENYLIST: '차단 목록',
    });
  });
});
