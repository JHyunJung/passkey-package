import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import type { Me } from '@/api/types';

// 실제 API 클라이언트(시간 제한 포함)를 쓰고 fetch 만 바꾼다. 라이선스 조회만 고정.
vi.mock('@/api/license', () => ({ licenseApi: { get: vi.fn().mockResolvedValue({ deploymentMode: 'saas' }) } }));

import { Sidebar } from './Sidebar';

const platform: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const okOverview = {
  verifiedAt: new Date().toISOString(), windowHours: 24, bucketSizeMinutes: 60,
  totals: { tenantsIntact: 5, tenantsTotal: 5, tenantsTampered: 0, verifiedRows: 10, verificationMs: 10 },
  tenants: [],
};

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

describe('Sidebar — 멈춘 체인 조회', () => {
  it('응답 없는 조회는 시간 제한 뒤 중단되고, 다음 폴링이 다시 조회해 회복한다', async () => {
    vi.useFakeTimers();
    let calls = 0;
    const fetchMock = vi.fn((_url: string, init?: RequestInit) => {
      calls++;
      if (calls === 1) {
        // 첫 조회는 응답하지 않는다(abort 되면 그때 reject).
        return new Promise<Response>((_res, rej) => {
          init?.signal?.addEventListener('abort', () => rej(new DOMException('aborted', 'AbortError')));
        });
      }
      return Promise.resolve(new Response(JSON.stringify(okOverview), { status: 200 }));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={() => {}} />);
    expect(screen.getByText('검증 중…')).toBeInTheDocument();

    // 응답이 없는 동안 폴링은 진행 중인 조회를 기다린다(아직 1회).
    await act(async () => { await vi.advanceTimersByTimeAsync(119_999); });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(screen.getByText('검증 중…')).toBeInTheDocument();

    // 체인 개요 조회의 긴 시간 제한(120초)이 지나면 요청이 중단되고, 진행 중 표시가 풀려
    // 같은 시각의 폴링이 새로 조회해 회복한다 — 영원히 "확인 중"에 머물지 않는다.
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect((fetchMock.mock.calls[0][1] as RequestInit).signal?.aborted).toBe(true);
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
    expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('AUDIT CHAIN OK')).toBeInTheDocument();
  });
});
