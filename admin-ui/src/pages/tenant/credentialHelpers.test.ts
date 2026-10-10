// admin-ui/src/pages/tenant/credentialHelpers.test.ts
import { describe, it, expect } from 'vitest';
import type { Credential } from '@/api/designTypes';
import { applyClientFilter, hasClientFilter, authenticatorLabel } from './credentialHelpers';

const c = (id: string, p: Partial<Credential> = {}): Credential => ({
  credentialId: id, externalUserId: `u-${id}`, nickname: null, authenticatorName: null, status: 'ACTIVE',
  aaguid: null, transports: [], signatureCounter: 0, lastUsedAt: null, createdAt: '2026-06-01T00:00:00Z', attestationFormat: null, ...p,
});

describe('credentialHelpers', () => {
  const items = [
    c('a', { aaguid: 'EA9B8D66-4D01-1D21-3CE4-B6B48CB575D4' }),
    c('b', { aaguid: 'fbfc3007-154e-4ecc-8c0b-6e020557d7bd', status: 'REVOKED' }),
    c('c'),
  ];
  it('hasClientFilter', () => {
    expect(hasClientFilter({ aaguid: '  ', status: 'ALL' })).toBe(false);
    expect(hasClientFilter({ aaguid: 'ea9b', status: 'ALL' })).toBe(true);
    expect(hasClientFilter({ aaguid: '', status: 'REVOKED' })).toBe(true);
  });
  it('aaguid 는 하이픈·대소문자 무시 부분 일치, status 는 정확히', () => {
    expect(applyClientFilter(items, { aaguid: 'ea9b8d664d01', status: 'ALL' }).map((x) => x.credentialId)).toEqual(['a']);
    expect(applyClientFilter(items, { aaguid: '', status: 'REVOKED' }).map((x) => x.credentialId)).toEqual(['b']);
    expect(applyClientFilter(items, { aaguid: 'fbfc', status: 'ACTIVE' })).toEqual([]);
    expect(applyClientFilter(items, { aaguid: '', status: 'ALL' })).toHaveLength(3);
  });
  it('authenticatorLabel: MDS 이름 → aaguid 끝 8자 → unknown', () => {
    expect(authenticatorLabel(c('x', { authenticatorName: 'iCloud Keychain', aaguid: 'fbfc3007-154e-4ecc-8c0b-6e020557d7bd' }))).toBe('iCloud Keychain');
    expect(authenticatorLabel(c('x', { aaguid: 'fbfc3007-154e-4ecc-8c0b-6e020557d7bd' }))).toBe('0557d7bd');
    expect(authenticatorLabel(c('x'))).toBe('unknown');
  });
});
