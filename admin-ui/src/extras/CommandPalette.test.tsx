import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { CommandPalette } from './CommandPalette';
import type { Me } from '@/api/types';

const me: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };

describe('CommandPalette V2', () => {
  it('이동/액션 그룹과 단축키 표시, 결과 없음 상태', () => {
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={() => {}} onAction={() => {}} />);
    expect(screen.getAllByText('이동').some((e) => e.classList.contains('t-over'))).toBe(true);
    expect(screen.getByText('액션')).toBeInTheDocument();
    expect(screen.getByText('G T')).toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox', { name: '검색' }), { target: { value: 'zzzzzz' } });
    expect(screen.getByText('일치하는 결과가 없습니다.')).toBeInTheDocument();
  });
  it('Enter 로 선택 항목 실행', () => {
    const onNavigate = vi.fn();
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={onNavigate} onAction={() => {}} />);
    fireEvent.keyDown(screen.getByRole('textbox', { name: '검색' }), { key: 'Enter' });
    expect(onNavigate).toHaveBeenCalledWith({ name: 'tenants' });
  });
  it('동작하지 않는 액션(Hash chain 즉시 검증·Role 전환 데모)은 없다', () => {
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={() => {}} onAction={() => {}} />);
    expect(screen.queryByText('Hash chain 즉시 검증')).toBeNull();
    expect(screen.queryByText('Role 전환 (데모)')).toBeNull();
    expect(screen.getByText('로그아웃')).toBeInTheDocument();
  });
});
