import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import type { Me } from '@/api/types';

const overview = vi.fn();
vi.mock('@/api/auditChainMonitor', () => ({ auditChainMonitorApi: { overview: (...a: unknown[]) => overview(...a) } }));
vi.mock('@/api/license', () => ({ licenseApi: { get: vi.fn().mockResolvedValue({ deploymentMode: 'saas' }) } }));

import { Sidebar } from './Sidebar';

const platform: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const rp: Me = { email: 'b@x.com', role: 'RP_ADMIN', tenantIds: ['tid-123'], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const okOverview = (tampered = 0) => ({
  verifiedAt: new Date().toISOString(), windowHours: 24, bucketSizeMinutes: 60,
  totals: { tenantsIntact: 5 - tampered, tenantsTotal: 5, tenantsTampered: tampered, verifiedRows: 1234, verificationMs: 10 },
  tenants: [],
});

let release: (() => void) | null = null;
beforeEach(() => overview.mockReset());
afterEach(async () => { vi.useRealTimers(); await act(async () => { release?.(); }); release = null; });

describe('Sidebar V3', () => {
  it('PLATFORM: 플랫폼/관리 그룹과 4개 항목, 활성 aria-current, 첫 조회 중에는 "검증 중…" + 경과 초', async () => {
    vi.useFakeTimers();
    // 영영 끝나지 않는 Promise 는 vitest 워커 종료를 막으므로, 테스트가 끝나면 풀어 준다.
    overview.mockReturnValue(new Promise<undefined>((resolve) => { release = () => resolve(undefined); }));
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={() => {}} />);
    expect(screen.getByText('플랫폼')).toBeInTheDocument();
    expect(screen.getByText('관리')).toBeInTheDocument();
    for (const l of ['Tenants', '활동', '감사 체인', '설정']) expect(screen.getByRole('button', { name: new RegExp(l) })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Tenants/ })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByText('검증 중…')).toBeInTheDocument();
    expect(screen.getByText('0초째 확인 중')).toBeInTheDocument();
    act(() => { vi.advanceTimersByTime(3000); });
    expect(screen.getByText('3초째 확인 중')).toBeInTheDocument();
    // 환경 배지는 서버 profile 을 읽는 클라이언트 API 가 없으므로 하드코딩하지 않는다.
    expect(screen.queryByText('prod')).toBeNull();
    await act(async () => {}); // 라이선스 조회 응답을 act 안에서 흘려보낸다
  });

  it('RP_ADMIN: 설정만, 체인 카드 없음, 체인 조회도 안 함', async () => {
    render(<Sidebar me={rp} currentRoute={{ name: 'settings' }} onNavigate={() => {}} />);
    expect(screen.queryByText('Tenants')).toBeNull();
    expect(screen.queryByText('플랫폼')).toBeNull();
    expect(screen.getByRole('button', { name: /설정/ })).toBeInTheDocument();
    expect(screen.queryByText(/AUDIT CHAIN|검증 중/)).toBeNull();
    expect(overview).not.toHaveBeenCalled();
    await act(async () => {});
  });

  it('정상: AUDIT CHAIN OK + 마지막 검증 · 행 수, 메뉴 배지 없음', async () => {
    overview.mockResolvedValue(okOverview(0));
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={() => {}} />);
    expect(await screen.findByText('AUDIT CHAIN OK')).toBeInTheDocument();
    expect(screen.getByText('마지막 검증 · 방금 · 1,234 행')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /감사 체인/ })).not.toHaveTextContent('위변조');
  });

  it('위변조가 있으면 감사 체인 항목에 "위변조 N" 배지와 빨간 카드, 카드를 누르면 감사 체인으로', async () => {
    overview.mockResolvedValue(okOverview(1));
    const onNavigate = vi.fn();
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={onNavigate} />);
    expect(await screen.findByText('위변조 의심 · 1 tenant')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /감사 체인/ })).toHaveTextContent('위변조 1');
    fireEvent.click(screen.getByText('위변조 의심 · 1 tenant'));
    expect(onNavigate).toHaveBeenCalledWith({ name: 'audit-chain' });
  });

  it('조회가 실패하면 "검증 상태 확인 불가" + 다시 시도, 다시 시도가 성공하면 정상', async () => {
    overview.mockRejectedValueOnce(new Error('503')).mockResolvedValueOnce(okOverview(0));
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={() => {}} />);
    expect(await screen.findByText('검증 상태 확인 불가')).toBeInTheDocument();
    expect(screen.getByText('서버 응답이 없습니다')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(screen.getByText('검증 중…')).toBeInTheDocument();
    expect(await screen.findByText('AUDIT CHAIN OK')).toBeInTheDocument();
    expect(overview).toHaveBeenCalledTimes(2);
  });

  it('성공 뒤 30초 폴링이 실패하면 마지막 확인 시각(KST)을 보여 준다', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-11T01:02:00Z'));
    overview.mockResolvedValueOnce(okOverview(0)).mockRejectedValueOnce(new Error('503'));
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={() => {}} />);
    await act(async () => {});
    expect(screen.getByText('AUDIT CHAIN OK')).toBeInTheDocument();
    await act(async () => { vi.advanceTimersByTime(30_000); });
    expect(screen.getByText('검증 상태 확인 불가')).toBeInTheDocument();
    expect(screen.getByText('서버 응답이 없습니다 · 10:02 마지막 확인')).toBeInTheDocument();
  });

  it('응답이 폴링 주기보다 늦어도 진행 중인 조회를 덮어쓰지 않고, 도착하면 결과를 보여 준다', async () => {
    vi.useFakeTimers();
    let resolveFirst: (v: ReturnType<typeof okOverview>) => void = () => {};
    overview.mockReturnValueOnce(new Promise((r) => { resolveFirst = r; }));
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={() => {}} />);
    await act(async () => { vi.advanceTimersByTime(65_000); });
    expect(overview).toHaveBeenCalledTimes(1);
    await act(async () => { resolveFirst(okOverview(0)); });
    expect(screen.getByText('AUDIT CHAIN OK')).toBeInTheDocument();
  });

  it('다시 시도가 진행 중인 폴링 조회를 대체하면, 늦게 도착한 옛 응답은 무시한다', async () => {
    vi.useFakeTimers();
    let resolvePoll: (v: ReturnType<typeof okOverview>) => void = () => {};
    let resolveRetry: (v: ReturnType<typeof okOverview>) => void = () => {};
    overview
      .mockRejectedValueOnce(new Error('503'))
      .mockReturnValueOnce(new Promise((r) => { resolvePoll = r; }))
      .mockReturnValueOnce(new Promise((r) => { resolveRetry = r; }));
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={() => {}} />);
    await act(async () => {});
    expect(screen.getByText('검증 상태 확인 불가')).toBeInTheDocument();
    await act(async () => { vi.advanceTimersByTime(30_000); }); // 폴링 조회 시작(진행 중)
    expect(overview).toHaveBeenCalledTimes(2);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' })); // 대체 조회
    expect(overview).toHaveBeenCalledTimes(3);
    await act(async () => { resolveRetry(okOverview(1)); });
    expect(screen.getByText('위변조 의심 · 1 tenant')).toBeInTheDocument();
    await act(async () => { resolvePoll(okOverview(0)); }); // 옛 응답이 늦게 도착
    expect(screen.getByText('위변조 의심 · 1 tenant')).toBeInTheDocument();
    expect(screen.queryByText('AUDIT CHAIN OK')).toBeNull();
  });
});
