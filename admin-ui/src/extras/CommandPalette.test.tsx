import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import type { Me } from '@/api/types';

vi.mock('@/api/activeTenant', () => ({ activeTenantApi: { get: vi.fn(), switch: vi.fn() } }));

import { activeTenantApi } from '@/api/activeTenant';
import { CommandPalette } from './CommandPalette';

const me: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const rp: Me = { ...me, role: 'RP_ADMIN', tenantIds: ['t1', 't2'] };
const ACTIVE = { activeTenantId: 't2', allowedTenantIds: ['t1', 't2'], allowedTenants: [{ id: 't1', name: 'Acme' }, { id: 't2', name: 'Globex' }] };

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(activeTenantApi.get).mockResolvedValue(ACTIVE);
});

describe('CommandPalette V2', () => {
  it('이동/액션 그룹과 단축키 표시, 결과 없음 상태', () => {
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={() => {}} onAction={() => {}} />);
    expect(screen.getAllByText('이동').some((e) => e.classList.contains('t-over'))).toBe(true);
    expect(screen.getByText('액션')).toBeInTheDocument();
    expect(screen.getByText('G T')).toBeInTheDocument();
    fireEvent.change(screen.getByRole('combobox', { name: '검색' }), { target: { value: 'zzzzzz' } });
    expect(screen.getByText('“zzzzzz”와 일치하는 tenant · 화면이 없습니다.')).toBeInTheDocument();
  });
  it('Enter 로 선택 항목 실행', () => {
    const onNavigate = vi.fn();
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={onNavigate} onAction={() => {}} />);
    fireEvent.keyDown(screen.getByRole('combobox', { name: '검색' }), { key: 'Enter' });
    expect(onNavigate).toHaveBeenCalledWith({ name: 'tenants' });
  });
  it('동작하지 않는 액션(Hash chain 즉시 검증·Role 전환 데모)은 없다', () => {
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={() => {}} onAction={() => {}} />);
    expect(screen.queryByText('Hash chain 즉시 검증')).toBeNull();
    expect(screen.queryByText('Role 전환 (데모)')).toBeNull();
    expect(screen.getByText('로그아웃')).toBeInTheDocument();
  });
});

describe('CommandPalette V3', () => {
  it('검색 안내는 실제 기능에 맞춘다(credential·audit ID 검색을 약속하지 않음)', () => {
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={() => {}} onAction={() => {}} />);
    expect(screen.getByRole('combobox', { name: '검색' })).toHaveAttribute('placeholder', 'tenant 이름 · slug, 화면, 동작 검색…');
    fireEvent.change(screen.getByRole('combobox', { name: '검색' }), { target: { value: 'zzzzzz' } });
    expect(screen.getByText('credential · 감사 이벤트 ID는 해당 화면의 검색에서 찾으세요.')).toBeInTheDocument();
  });

  it('열리면 검색칸에 포커스, 닫히면 열기 전 위치로', () => {
    function Host({ open }: { open: boolean }) {
      return <><button>바깥</button><CommandPalette open={open} onClose={() => {}} me={me} onNavigate={() => {}} onAction={() => {}} /></>;
    }
    const { rerender } = render(<Host open={false} />);
    const outside = screen.getByRole('button', { name: '바깥' });
    outside.focus();
    rerender(<Host open />);
    expect(document.activeElement).toBe(screen.getByRole('combobox', { name: '검색' }));
    rerender(<Host open={false} />);
    expect(document.activeElement).toBe(outside);
  });

  it('Tab 은 팔레트 안에서 순환한다: 검색칸이 유일한 탭 정지점이라 Tab 은 막히고 검색칸에 머문다', () => {
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={() => {}} onAction={() => {}} />);
    const input = screen.getByRole('combobox', { name: '검색' });
    expect(input).toHaveFocus();
    expect(fireEvent.keyDown(input, { key: 'Tab' })).toBe(false);
    expect(fireEvent.keyDown(input, { key: 'Tab', shiftKey: true })).toBe(false);
    expect(input).toHaveFocus();
    // 항목 버튼은 탭으로 닿지 않는다
    expect(screen.getByRole('option', { name: /로그아웃/ })).toHaveAttribute('tabindex', '-1');
    // 마우스로 항목에 포커스가 가 있어도 Tab 은 팔레트 밖으로 새지 않고 검색칸으로 돌아온다
    const logout = screen.getByRole('option', { name: /로그아웃/ });
    logout.focus();
    expect(fireEvent.keyDown(logout, { key: 'Tab' })).toBe(false);
    expect(input).toHaveFocus();
  });

  it('PLATFORM: "신규 tenant 생성"은 다이얼로그 열기 동작을 요청한다', () => {
    const onAction = vi.fn();
    const onClose = vi.fn();
    render(<CommandPalette open onClose={onClose} me={me} onNavigate={() => {}} onAction={onAction} />);
    const btn = screen.getByRole('option', { name: /신규 tenant 생성/ });
    expect(btn).toHaveTextContent('다이얼로그 열기');
    fireEvent.click(btn);
    expect(onAction).toHaveBeenCalledWith('new-tenant');
    expect(onClose).toHaveBeenCalled();
  });

  it('RP 관리자는 첫 tenant 가 아니라 활성 tenant 기준, 그룹 라벨 "현재 Tenant · [이름]"', async () => {
    const onNavigate = vi.fn();
    render(<CommandPalette open onClose={() => {}} me={rp} onNavigate={onNavigate} onAction={() => {}} />);
    expect(await screen.findByText('현재 Tenant · Globex')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('option', { name: /^개요/ }));
    expect(onNavigate).toHaveBeenCalledWith({ name: 'tenant', tenantId: 't2', tab: 'overview' });
  });

  it('활성 tenant 조회 실패면 첫 tenant, 라벨 "현재 Tenant"', async () => {
    vi.mocked(activeTenantApi.get).mockRejectedValue(new Error('x'));
    const onNavigate = vi.fn();
    render(<CommandPalette open onClose={() => {}} me={rp} onNavigate={onNavigate} onAction={() => {}} />);
    expect(await screen.findByText('현재 Tenant')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('option', { name: /^개요/ }));
    expect(onNavigate).toHaveBeenCalledWith({ name: 'tenant', tenantId: 't1', tab: 'overview' });
  });

  it('PLATFORM 은 활성 tenant 를 조회하지 않는다', () => {
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={() => {}} onAction={() => {}} />);
    expect(activeTenantApi.get).not.toHaveBeenCalled();
  });

  it('화살표 이동과 처음 선택은 비활성 항목을 건너뛴다', async () => {
    const onAction = vi.fn();
    render(<CommandPalette open onClose={() => {}} me={rp} onNavigate={() => {}} onAction={onAction} />);
    await screen.findByText('현재 Tenant · Globex');
    const input = screen.getByRole('combobox', { name: '검색' });
    fireEvent.change(input, { target: { value: '액션' } });
    expect(screen.getByRole('option', { name: /신규 tenant 생성/ })).toBeDisabled();
    expect(screen.getByRole('option', { name: /신규 tenant 생성/ })).toHaveTextContent('권한 없음');
    expect(screen.getByRole('option', { name: /로그아웃/ })).toHaveAttribute('aria-selected', 'true');
    fireEvent.keyDown(input, { key: 'ArrowUp' });
    expect(screen.getByRole('option', { name: /로그아웃/ })).toHaveAttribute('aria-selected', 'true');
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onAction).toHaveBeenCalledWith('logout');
    expect(onAction).not.toHaveBeenCalledWith('new-tenant');
  });
});

describe('CommandPalette 도움말', () => {
  it('"키보드 단축키" 항목(도움말 그룹, 힌트 ?)은 shortcuts 동작을 요청하고 닫는다', () => {
    const onAction = vi.fn();
    const onClose = vi.fn();
    render(<CommandPalette open onClose={onClose} me={me} onNavigate={() => {}} onAction={onAction} />);
    expect(screen.getByText('도움말')).toBeInTheDocument();
    const btn = screen.getByRole('option', { name: /키보드 단축키/ });
    expect(btn).toHaveTextContent('?');
    fireEvent.click(btn);
    expect(onAction).toHaveBeenCalledWith('shortcuts');
    expect(onClose).toHaveBeenCalled();
  });
});

describe('CommandPalette fix round 1', () => {
  const flush = () => act(async () => { await new Promise((r) => setTimeout(r, 0)); });

  it('활성 tenant 조회가 성공한 뒤 닫고 다시 열었을 때 조회가 실패하면 이전 tenant 라벨·이동이 남지 않는다', async () => {
    const onNavigate = vi.fn();
    const { rerender } = render(<CommandPalette open onClose={() => {}} me={rp} onNavigate={onNavigate} onAction={() => {}} />);
    expect(await screen.findByText('현재 Tenant · Globex')).toBeInTheDocument();
    rerender(<CommandPalette open={false} onClose={() => {}} me={rp} onNavigate={onNavigate} onAction={() => {}} />);
    vi.mocked(activeTenantApi.get).mockRejectedValue(new Error('x'));
    rerender(<CommandPalette open onClose={() => {}} me={rp} onNavigate={onNavigate} onAction={() => {}} />);
    await flush();
    expect(screen.getByText('현재 Tenant')).toBeInTheDocument();
    expect(screen.queryByText(/Globex/)).toBeNull();
    fireEvent.click(screen.getByRole('option', { name: /^개요/ }));
    expect(onNavigate).toHaveBeenCalledWith({ name: 'tenant', tenantId: 't1', tab: 'overview' });
  });

  it('콤보박스 ARIA: aria-expanded·aria-controls·aria-activedescendant 가 선택 항목을 가리키고 비활성은 aria-disabled', async () => {
    render(<CommandPalette open onClose={() => {}} me={rp} onNavigate={() => {}} onAction={() => {}} />);
    await screen.findByText('현재 Tenant · Globex');
    const input = screen.getByRole('combobox', { name: '검색' });
    expect(input).toHaveAttribute('aria-expanded', 'true');
    const list = screen.getByRole('listbox');
    expect(input).toHaveAttribute('aria-controls', list.id);
    const first = screen.getAllByRole('option')[0];
    expect(input).toHaveAttribute('aria-activedescendant', first.id);
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    const second = screen.getAllByRole('option')[1];
    expect(second).toHaveAttribute('aria-selected', 'true');
    expect(input).toHaveAttribute('aria-activedescendant', second.id);
    expect(screen.getByRole('option', { name: /신규 tenant 생성/ })).toHaveAttribute('aria-disabled', 'true');
  });

  it('항목 버튼에 포커스가 있을 때 Enter 는 선택 인덱스의 다른 명령을 실행하지 않는다', () => {
    const onNavigate = vi.fn();
    const onAction = vi.fn();
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={onNavigate} onAction={onAction} />);
    const logout = screen.getByRole('option', { name: /로그아웃/ });
    logout.focus();
    fireEvent.keyDown(logout, { key: 'Enter' });
    expect(onNavigate).not.toHaveBeenCalled();
    expect(onAction).not.toHaveBeenCalled();
  });
});
