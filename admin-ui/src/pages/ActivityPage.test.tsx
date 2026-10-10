// admin-ui/src/pages/ActivityPage.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, fireEvent, within, waitFor } from '@testing-library/react';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import { ToastHost } from '@/ui';
import type { ActivityEvent, ActivityView, ActivityDetailView } from '@/api/types';

vi.mock('@/api/activity', () => ({ activityApi: { fetch: vi.fn(), fetchDetail: vi.fn() } }));
vi.mock('@/api/tenants', () => ({ tenantsApi: { list: vi.fn() } }));

import { activityApi } from '@/api/activity';
import { tenantsApi } from '@/api/tenants';
import ActivityPage from './ActivityPage';

const NOW = new Date('2026-10-11T03:00:00Z'); // KST 2026-10-11 12:00

function ev(id: string, createdAt: string, over: Partial<ActivityEvent> = {}): ActivityEvent {
  return {
    id, action: 'TENANT_UPDATE', actorEmail: 'op@x.com', targetType: 'TENANT', targetId: 'tid-0000-1111-2222',
    tenantId: 't1', tenantSlug: 'acme', createdAt, category: 'ops', ...over,
  };
}
const view = (feed: ActivityEvent[]): ActivityView => ({
  kpi: { events24h: 3, ops24h: 2, security24h: 1, p95Ms: null },
  top5: [{ tenantId: 't1', slug: 'acme', count: 3 }],
  feed,
});
const FEED = [
  ev('e1', '2026-10-11T02:48:00Z'),                                           // 오늘 11:48
  ev('e2', '2026-10-10T09:00:00Z', { action: 'ADMIN_LOGIN_FAILED', category: 'security', tenantId: null, tenantSlug: null }), // 어제 18:00
  ev('e3', '2026-10-08T01:00:00Z', { action: 'API_KEY_ISSUE' }),             // 2026-10-08 10:00
];
const PAYLOAD = JSON.stringify({
  before: { webauthnTimeoutMs: 60000, allowedOrigins: ['https://acme.com'] },
  after: { webauthnTimeoutMs: 120000, allowedOrigins: ['https://acme.com', 'https://b.acme.com'] },
  changedFields: ['allowedOrigins', 'webauthnTimeoutMs'],
});
function detail(over: Partial<ActivityDetailView> = {}): ActivityDetailView {
  return {
    id: 'e1', action: 'TENANT_UPDATE', actorId: '1', actorEmail: 'op@x.com', targetType: 'TENANT',
    targetId: 'tid-0000-1111-2222', tenantId: 't1', tenantSlug: 'acme', createdAt: '2026-10-11T02:48:00Z',
    category: 'ops', payload: PAYLOAD, ...over,
  };
}

function renderPage() {
  return renderWithDataRouter(<ToastHost><ActivityPage /></ToastHost>, { initialPath: '/activity' });
}

async function openFirstDetail() {
  const today = await screen.findByRole('group', { name: '오늘' });
  fireEvent.click(within(today).getByRole('button', { name: '상세 열기' }));
  return screen.findByRole('region', { name: '이벤트 상세' });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] }); // setInterval(5초 폴링)은 실제 타이머 유지
  vi.setSystemTime(NOW);
  vi.mocked(tenantsApi.list).mockResolvedValue([]);
  vi.mocked(activityApi.fetch).mockResolvedValue(view(FEED));
  vi.mocked(activityApi.fetchDetail).mockResolvedValue(detail());
});
afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe('ActivityPage V2', () => {
  it('피드를 KST 날짜(오늘/어제/YYYY-MM-DD)로 묶는다', async () => {
    renderPage();
    const today = await screen.findByRole('group', { name: '오늘' });
    expect(within(today).getByText('11:48')).toBeInTheDocument();
    expect(within(screen.getByRole('group', { name: '어제' })).getByText('18:00')).toBeInTheDocument();
    expect(within(screen.getByRole('group', { name: '2026-10-08' })).getByText('10:00')).toBeInTheDocument();
  });

  it('상세 패널 5개 섹션과 TENANT_UPDATE before/after diff, 선택 행 강조', async () => {
    renderPage();
    const panel = await openFirstDetail();
    expect(activityApi.fetchDetail).toHaveBeenCalledWith('e1');
    expect(await within(panel).findByRole('group', { name: '언제' })).toHaveTextContent('2026-10-11 11:48:00');
    expect(within(panel).getByText('2026-10-11T02:48:00Z UTC')).toBeInTheDocument();
    expect(within(panel).getByRole('group', { name: '누가' })).toHaveTextContent('op@x.com');
    expect(within(panel).getByRole('group', { name: '테넌트 · 대상' })).toHaveTextContent('TENANT');
    const diff = within(panel).getByRole('group', { name: '어떻게 바뀜' });
    expect(within(diff).getByText('60000')).toBeInTheDocument();
    expect(within(diff).getByText('120000')).toBeInTheDocument();
    expect(within(diff).getByText('+ https://b.acme.com')).toBeInTheDocument();
    const raw = within(panel).getByRole('group', { name: '원본 JSON' });
    expect(within(raw).getByRole('button', { name: '복사' })).toBeInTheDocument();
    expect(screen.getByText('11:48').closest('[aria-current]')).toHaveAttribute('aria-current', 'true');
  });

  it('actorIp 가 없으면 IP 줄을 그리지 않는다', async () => {
    renderPage();
    const panel = await openFirstDetail();
    await within(panel).findByRole('group', { name: '누가' });
    expect(within(panel).queryByText(/^IP /)).toBeNull();
  });

  it('actorIp 가 있으면 IP 줄을 보인다', async () => {
    vi.mocked(activityApi.fetchDetail).mockResolvedValue(detail({ actorIp: '203.0.113.9' }));
    renderPage();
    const panel = await openFirstDetail();
    expect(await within(panel).findByText('IP 203.0.113.9')).toBeInTheDocument();
  });

  it('diff 필드가 있으면 payload before/after 대신 그것을 쓴다', async () => {
    vi.mocked(activityApi.fetchDetail).mockResolvedValue(detail({ diff: [{ field: 'rpName', from: 'Old', to: 'New' }] }));
    renderPage();
    const panel = await openFirstDetail();
    const diff = await within(panel).findByRole('group', { name: '어떻게 바뀜' });
    expect(within(diff).getByText('rpName')).toBeInTheDocument();
    expect(within(diff).queryByText('webauthnTimeoutMs')).toBeNull();
  });

  it('보안 실패만 스위치 = category=security, 카테고리 세그먼트와 같은 상태', async () => {
    renderPage();
    await screen.findByRole('group', { name: '오늘' });
    fireEvent.click(screen.getByRole('switch', { name: '보안 실패만' }));
    await waitFor(() =>
      expect(activityApi.fetch).toHaveBeenLastCalledWith(null, 'security', undefined, undefined),
    );
    expect(screen.getByRole('button', { name: '보안' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('카테고리: 보안')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '운영' }));
    expect(screen.getByRole('switch', { name: '보안 실패만' })).not.toBeChecked();
  });

  it('첫 로드 실패 시 불러오기 실패 상태', async () => {
    vi.mocked(activityApi.fetch).mockRejectedValue(new Error('down'));
    renderPage();
    expect(await screen.findByText('Activity 데이터를 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '재시도' })).toBeInTheDocument();
  });
});
