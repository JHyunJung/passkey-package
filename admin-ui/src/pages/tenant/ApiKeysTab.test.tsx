import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, fireEvent, within, act } from '@testing-library/react';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import { ToastHost } from '@/ui';
import type { ApiKey, Tenant } from '@/api/designTypes';

const api = vi.hoisted(() => ({ list: vi.fn(), create: vi.fn(), rotate: vi.fn(), revoke: vi.fn() }));
vi.mock('@/api/apiKeys', () => ({ apiKeysApi: api }));

const links = vi.hoisted(() => ({ rpGuide: 'https://docs.example/rp', apiDocs: '', support: '' }));
vi.mock('@/config/links', () => ({ HELP_LINKS: links }));
import { ApiError } from '@/api/types';
import ApiKeysTab from './ApiKeysTab';

const tenant: Tenant = {
  id: 't1', name: 'Acme', slug: 'acme', rpId: 'acme.com', status: 'ACTIVE', credentials: 0, apiKeys: 3,
  lastEventAt: null, createdAt: '2026-01-01T00:00:00Z', userVerification: 'PREFERRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const DAY = 86_400_000;
const iso = (days: number) => new Date(Date.now() + days * DAY).toISOString();
const key = (p: Partial<ApiKey>): ApiKey => ({
  id: 'k', prefix: 'pk_live_ab', name: 'prod', status: 'ACTIVE', createdAt: iso(-10), lastUsedAt: null,
  scopes: ['registration'], expiresAt: null, ...p,
});
const renderTab = (onChanged?: () => void) => renderWithDataRouter(<ToastHost><ApiKeysTab tenant={tenant} onChanged={onChanged} /></ToastHost>, { initialPath: '/tenants/t1?tab=apikeys' });

describe('ApiKeysTab V2', () => {
  beforeEach(() => { vi.clearAllMocks(); links.rpGuide = 'https://docs.example/rp'; });
  afterEach(() => { links.rpGuide = 'https://docs.example/rp'; });

  it('⋯ 메뉴는 ACTIVE 키에만 있고, 회수는 위험 구획에서 열리며 취소에 기본 포커스', async () => {
    api.list.mockResolvedValue([
      key({ id: 'a', name: 'prod' }),
      key({ id: 'r', name: 'old', status: 'REVOKED' }),
      key({ id: 'e', name: 'exp', status: 'EXPIRED', expiresAt: iso(-1) }),
    ]);
    renderTab();
    expect(await screen.findByText('prod')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'prod 키 액션' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'old 키 액션' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'exp 키 액션' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'prod 키 액션' }));
    expect(screen.getByRole('menuitem', { name: '회전' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: '접두사 복사' })).toBeInTheDocument();
    expect(screen.getByText('위험')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('menuitem', { name: '회수' }));

    const dlg = screen.getByRole('alertdialog', { name: 'API 키를 회수할까요?' });
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    expect(within(dlg).getByRole('button', { name: '회수' })).toHaveClass('btn--danger-solid');
  });

  it('키 접두사 뒤 keySuffix 는 값이 있을 때만 붙는다', async () => {
    api.list.mockResolvedValue([
      key({ id: 'a', name: 'with', keySuffix: 'a1c9' }),
      key({ id: 'b', name: 'without', prefix: 'pk_live_cd' }),
    ]);
    renderTab();
    expect(await screen.findByText('pk_live_ab…a1c9')).toBeInTheDocument();
    expect(screen.getByText('pk_live_cd…')).toBeInTheDocument();
  });

  it('30일 이내 만료 ACTIVE 키는 남은 일수를 경고로 표시, KPI 에 rotation 초과 키 수', async () => {
    api.list.mockResolvedValue([
      key({ id: 'a', name: 'soon', expiresAt: iso(10) }),
      key({ id: 'b', name: 'stale', createdAt: iso(-100) }),
      key({ id: 'c', name: 'gone', status: 'REVOKED', createdAt: iso(-200) }),
    ]);
    renderTab();
    expect(await screen.findByText(/· 10일 남음/)).toBeInTheDocument();
    expect(screen.getByText('초과 키 1개')).toHaveClass('kpi__hint--warn');
    expect(screen.getByText('활성 2 · 만료 0 · 회수 1')).toBeInTheDocument();
  });

  it('발급 완료 다이얼로그: Escape·스크림 무시, 체크 전 닫기 비활성', async () => {
    api.list.mockResolvedValue([]);
    api.create.mockResolvedValue({ key: key({ id: 'n1', name: 'staging' }), plaintext: 'pk_live_ab.secret' });
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '새 키 발급' }));
    fireEvent.change(screen.getByLabelText('용도 (이름)'), { target: { value: 'staging' } });
    fireEvent.click(screen.getByRole('button', { name: '발급' }));

    const dlg = await screen.findByRole('alertdialog', { name: /새 API key가 발급되었습니다/ });
    expect(api.create).toHaveBeenCalledWith('t1', 'staging', ['registration', 'authentication'], 24);
    expect(within(dlg).getByText('pk_live_ab.secret')).toBeInTheDocument();
    const close = within(dlg).getByRole('button', { name: /닫기 \(영구 소실\)/ });
    expect(close).toBeDisabled();

    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.mouseDown(document.querySelector('.scrim')!);
    expect(screen.getByRole('alertdialog', { name: /새 API key가 발급되었습니다/ })).toBeInTheDocument();

    fireEvent.click(within(dlg).getByRole('checkbox', { name: '안전한 장소에 복사했습니다.' }));
    expect(close).toBeEnabled();
    expect(close).toHaveTextContent(/^닫기 \(영구 소실\)$/);
    fireEvent.click(close);
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  it('회전: 24시간 겹침 안내 후 실행하면 새 키와 이전 키 만료 시간을 보여준다', async () => {
    api.list.mockResolvedValue([key({ id: 'a', name: 'prod' })]);
    api.rotate.mockResolvedValue({ id: 'a2', plaintextKey: 'pk_live_ab.new', prefix: 'pk_live_ab', scopes: ['registration'], oldKeyExpiresAt: iso(1) });
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: 'prod 키 액션' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '회전' }));
    const rot = screen.getByRole('alertdialog', { name: 'API 키를 회전할까요?' });
    expect(within(rot).getByText('24시간 함께 유효')).toBeInTheDocument();
    expect(document.activeElement).toBe(within(rot).getByRole('button', { name: '취소' }));
    await act(async () => { fireEvent.click(within(rot).getByRole('button', { name: '회전' })); });
    const issued = await screen.findByRole('alertdialog', { name: /새 API key가 발급되었습니다/ });
    expect(api.rotate).toHaveBeenCalledWith('a');
    expect(within(issued).getByText(/이전 키는 24시간 후/)).toBeInTheDocument();
  });

  it('발급 다이얼로그가 열린 동안 이동·새로고침을 막는다(평문 키 소실 방지)', async () => {
    api.list.mockResolvedValue([]);
    api.create.mockResolvedValue({ key: key({ id: 'n1', name: 'staging' }), plaintext: 'pk_live_ab.secret' });
    const { router } = renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '새 키 발급' }));
    fireEvent.change(screen.getByLabelText('용도 (이름)'), { target: { value: 'staging' } });
    fireEvent.click(screen.getByRole('button', { name: '발급' }));
    await screen.findByRole('alertdialog', { name: /새 API key가 발급되었습니다/ });

    const ev = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(ev);
    expect(ev.defaultPrevented).toBe(true);

    await act(async () => { await router.navigate('/tenants'); });
    expect(screen.getByRole('alertdialog', { name: '키를 아직 저장하지 않았습니다' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/tenants/t1');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '머무르기' })); });
    expect(screen.getByText('pk_live_ab.secret')).toBeInTheDocument();
  });

  it('회전 다이얼로그는 새 키의 id 를 보이고, 만료는 새 키를 다시 읽은 값(이전 키 만료일 아님)', async () => {
    const oldKey = key({ id: 'a', name: 'prod', expiresAt: iso(5) });
    const newKey = key({ id: 'a2', name: 'prod', expiresAt: '2028-10-11T00:00:00Z' });
    api.list.mockResolvedValueOnce([oldKey]).mockResolvedValue([oldKey, newKey]);
    api.rotate.mockResolvedValue({ id: 'a2', plaintextKey: 'pk_live_ab.new', prefix: 'pk_live_ab', scopes: ['registration'], oldKeyExpiresAt: iso(1) });
    const onChanged = vi.fn();
    renderTab(onChanged);
    fireEvent.click(await screen.findByRole('button', { name: 'prod 키 액션' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '회전' }));
    await act(async () => { fireEvent.click(within(screen.getByRole('alertdialog', { name: 'API 키를 회전할까요?' })).getByRole('button', { name: '회전' })); });
    const issued = await screen.findByRole('alertdialog', { name: /새 API key가 발급되었습니다/ });
    expect(within(issued).getByText('a2')).toBeInTheDocument();
    expect(within(issued).queryByText(/^a$/)).toBeNull();
    expect(await within(issued).findByText('2028-10-11')).toBeInTheDocument();
    expect(onChanged).toHaveBeenCalled();
  });

  it('발급 버튼 연타는 키를 한 번만 발급한다', async () => {
    api.list.mockResolvedValue([]);
    let resolve: (v: unknown) => void = () => {};
    api.create.mockReturnValue(new Promise((r) => { resolve = r; }));
    const onChanged = vi.fn();
    renderTab(onChanged);
    fireEvent.click(await screen.findByRole('button', { name: '새 키 발급' }));
    fireEvent.change(screen.getByLabelText('용도 (이름)'), { target: { value: 'staging' } });
    const issue = screen.getByRole('button', { name: '발급' });
    fireEvent.click(issue);
    fireEvent.click(issue);
    expect(api.create).toHaveBeenCalledTimes(1);
    await act(async () => { resolve({ key: key({ id: 'n1', name: 'staging' }), plaintext: 'pk_live_ab.secret' }); });
    expect(await screen.findByRole('alertdialog', { name: /새 API key가 발급되었습니다/ })).toBeInTheDocument();
    expect(onChanged).toHaveBeenCalled();
  });

  it('회수·회전 확인 버튼도 처리 중에는 다시 누를 수 없다', async () => {
    api.list.mockResolvedValue([key({ id: 'a', name: 'prod' })]);
    api.revoke.mockReturnValue(new Promise(() => {}));
    api.rotate.mockReturnValue(new Promise(() => {}));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: 'prod 키 액션' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '회수' }));
    const revokeBtn = within(screen.getByRole('alertdialog', { name: 'API 키를 회수할까요?' })).getByRole('button', { name: '회수' });
    fireEvent.click(revokeBtn);
    fireEvent.click(revokeBtn);
    expect(api.revoke).toHaveBeenCalledTimes(1);
    expect(revokeBtn).toBeDisabled();
    expect(revokeBtn).toHaveTextContent('회수 중…');
  });
  it('로드 실패: "키 없음"이 아니라 LoadError, 다시 시도', async () => {
    api.list.mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503')).mockResolvedValueOnce([key({ id: 'a', name: 'prod' })]);
    renderTab();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('API 키 목록을 불러오지 못했습니다');
    expect(screen.queryByText('아직 발급한 API 키가 없습니다')).toBeNull();
    expect(screen.queryByText('API key 로드 실패')).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 시도' })); });
    expect(await screen.findByText('prod')).toBeInTheDocument();
  });

  it('로딩 중에는 Skeleton', () => {
    api.list.mockReturnValue(new Promise(() => {}));
    renderTab();
    expect(screen.getByRole('status', { name: 'API 키 불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText('불러오는 중…')).toBeNull();
  });

  it('빈 상태: 첫 API 키 발급 + RP 연동 가이드', async () => {
    api.list.mockResolvedValue([]);
    renderTab();
    expect(await screen.findByText('아직 발급한 API 키가 없습니다')).toBeInTheDocument();
    expect(screen.getByText('RP 서버가 패스키 등록·인증 API를 호출하려면 키가 필요합니다.')).toBeInTheDocument();
    const guides = screen.getAllByRole('link', { name: /RP 연동 가이드/ });
    expect(guides.length).toBeGreaterThanOrEqual(1);
    for (const g of guides) expect(g).toHaveAttribute('href', 'https://docs.example/rp');
    fireEvent.click(screen.getByRole('button', { name: '첫 API 키 발급' }));
    expect(screen.getByRole('dialog', { name: '새 API 키 발급' })).toBeInTheDocument();
  });

  it('발급 실패: 다이얼로그 안 인라인 경고, 이름·권한·만료 입력 유지, 토스트 없음 → 다시 발급 성공', async () => {
    api.list.mockResolvedValue([]);
    api.create
      .mockRejectedValueOnce(new ApiError(409, 'K409', '키 개수 한도 초과'))
      .mockResolvedValueOnce({ key: key({ id: 'n1', name: 'staging' }), plaintext: 'pk_live_ab.secret' });
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '새 키 발급' }));
    const dlg = screen.getByRole('dialog', { name: '새 API 키 발급' });
    fireEvent.change(within(dlg).getByLabelText('용도 (이름)'), { target: { value: 'staging' } });
    fireEvent.click(within(dlg).getByRole('checkbox', { name: /authentication/ }));
    fireEvent.click(within(dlg).getByRole('button', { name: '12개월' }));
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '발급' })); });
    const alert = within(dlg).getByRole('alert');
    expect(alert).toHaveTextContent('API 키를 발급하지 못했습니다.');
    expect(alert).toHaveTextContent('키 개수 한도 초과');
    expect(alert).toHaveTextContent('입력한 내용은 그대로 있습니다.');
    expect(within(dlg).getByLabelText('용도 (이름)')).toHaveValue('staging');
    expect(within(dlg).getByRole('checkbox', { name: /authentication/ })).not.toBeChecked();
    expect(within(dlg).getByRole('button', { name: '12개월' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByText('발급 실패')).toBeNull();

    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '발급' })); });
    expect(api.create).toHaveBeenLastCalledWith('t1', 'staging', ['registration'], 12);
    expect(await screen.findByRole('alertdialog', { name: /새 API key가 발급되었습니다/ })).toBeInTheDocument();
  });

  it('발급 중에는 "발급 중…" 비활성이고 입력을 고칠 수 없다', async () => {
    api.list.mockResolvedValue([]);
    api.create.mockReturnValue(new Promise(() => {}));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '새 키 발급' }));
    fireEvent.change(screen.getByLabelText('용도 (이름)'), { target: { value: 'staging' } });
    fireEvent.click(screen.getByRole('button', { name: '발급' }));
    expect(screen.getByRole('button', { name: '발급 중…' })).toBeDisabled();
    expect(screen.getByLabelText('용도 (이름)')).toBeDisabled();
    expect(screen.getByRole('checkbox', { name: /authentication/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: '12개월' })).toBeDisabled();
  });

  it('만료 열은 글로 된 배지: 정상 · 만료 임박 · 무기한 · 만료됨', async () => {
    api.list.mockResolvedValue([
      key({ id: 'a', name: 'ok', expiresAt: iso(100) }),
      key({ id: 'b', name: 'soon', expiresAt: iso(10) }),
      key({ id: 'c', name: 'forever', expiresAt: null }),
      key({ id: 'd', name: 'gone', status: 'EXPIRED', expiresAt: iso(-1) }),
    ]);
    renderTab();
    expect(await screen.findByText('정상')).toHaveClass('badge--success');
    expect(screen.getByText('만료 임박 · 10일 남음')).toHaveClass('badge--warning');
    expect(screen.getByText('무기한')).toHaveClass('badge');
    expect(screen.getByText('만료일 없음')).toBeInTheDocument();
    expect(screen.getByText('만료됨')).toHaveClass('badge--danger');
  });

  it('권한 범위는 한국어 라벨이고 원문은 title 툴팁', async () => {
    api.list.mockResolvedValue([key({ id: 'a', name: 'prod', scopes: ['registration', 'authentication'] })]);
    renderTab();
    await screen.findByText('prod');
    expect(screen.getByText('패스키 등록')).toHaveAttribute('title', 'registration');
    expect(screen.getByText('패스키 인증')).toHaveAttribute('title', 'authentication');
  });

  it('RP 가이드 링크가 비어 있으면 다음 단계 링크를 렌더하지 않는다', async () => {
    links.rpGuide = '';
    api.list.mockResolvedValue([]);
    renderTab();
    expect(await screen.findByText('아직 발급한 API 키가 없습니다')).toBeInTheDocument();
    await act(async () => { await new Promise((r) => setTimeout(r, 0)); });
    expect(screen.queryByRole('link', { name: /RP 연동 가이드/ })).toBeNull();
  });

  it('회수: L2 영향 요약(입력 확인 없음), 성공 토스트 "회수했습니다"', async () => {
    api.list.mockResolvedValue([key({ id: 'a', name: 'prod', lastUsedAt: null })]);
    api.revoke.mockResolvedValue(undefined);
    const onChanged = vi.fn();
    renderTab(onChanged);
    fireEvent.click(await screen.findByRole('button', { name: 'prod 키 액션' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '회수' }));
    const dlg = screen.getByRole('alertdialog', { name: 'API 키를 회수할까요?' });
    expect(within(dlg).getByText(/즉시 거부됩니다\(401\)/)).toBeInTheDocument();
    expect(within(dlg).getByText('사용 안 함')).toBeInTheDocument();
    expect(within(dlg).queryByRole('textbox')).toBeNull();
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '회수' })); });
    expect(api.revoke).toHaveBeenCalledWith('a');
    expect(screen.queryByRole('alertdialog', { name: 'API 키를 회수할까요?' })).toBeNull();
    expect(await screen.findByText('API 키 “prod”을 회수했습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '되돌리기' })).toBeNull();
    expect(onChanged).toHaveBeenCalled();
  });

  it('회수 실패는 다이얼로그 안 인라인 오류, 닫히지 않는다', async () => {
    api.list.mockResolvedValue([key({ id: 'a', name: 'prod' })]);
    api.revoke.mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: 'prod 키 액션' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '회수' }));
    const dlg = screen.getByRole('alertdialog', { name: 'API 키를 회수할까요?' });
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '회수' })); });
    expect(within(dlg).getByText('회수하지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByText('회수 실패')).toBeNull();
  });

  it('회전: L2 — 유예 기간 안내, 실패는 인라인', async () => {
    api.list.mockResolvedValue([key({ id: 'a', name: 'prod' })]);
    api.rotate.mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: 'prod 키 액션' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '회전' }));
    const dlg = screen.getByRole('alertdialog', { name: 'API 키를 회전할까요?' });
    expect(within(dlg).getByText(/유예 기간 안에 RP 서버 설정을 새 키로 바꾸세요/)).toBeInTheDocument();
    expect(within(dlg).getByRole('button', { name: '회전' })).toHaveClass('btn--primary');
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '회전' })); });
    expect(within(dlg).getByText('회전하지 못했습니다')).toBeInTheDocument();
  });

  it('발급 실패 경고는 마침표가 겹치지 않고, 상세 뒤에 문장이 끝난다', async () => {
    api.list.mockResolvedValue([]);
    api.create.mockRejectedValue(new ApiError(409, 'K409', '키 개수 한도 초과.'));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '새 키 발급' }));
    fireEvent.change(screen.getByLabelText('용도 (이름)'), { target: { value: 'x' } });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '발급' })); });
    const alert = within(screen.getByRole('dialog', { name: '새 API 키 발급' })).getByRole('alert');
    expect(alert.textContent).toContain('키 개수 한도 초과. 입력한 내용은');
    expect(alert.textContent).not.toMatch(/\.\./);
  });

  it('발급이 진행 중이면 회수·회전은 동시에 실행되지 않는다(공용 가드)', async () => {
    api.list.mockResolvedValue([key({ id: 'a', name: 'prod' })]);
    api.create.mockReturnValue(new Promise(() => {}));
    api.revoke.mockResolvedValue(undefined);
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: 'prod 키 액션' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '회수' }));
    // 발급을 먼저 시작해 가드를 점유한다.
    fireEvent.click(screen.getByRole('button', { name: '새 키 발급' }));
    fireEvent.change(screen.getByLabelText('용도 (이름)'), { target: { value: 'x' } });
    fireEvent.click(screen.getByRole('button', { name: '발급' }));
    const dlg = screen.getByRole('alertdialog', { name: 'API 키를 회수할까요?' });
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '회수' })); });
    expect(api.revoke).not.toHaveBeenCalled();
    expect(within(dlg).getByText(/다른 작업이 진행 중/)).toBeInTheDocument();
  });
});
