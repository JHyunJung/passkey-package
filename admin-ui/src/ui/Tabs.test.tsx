import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useState } from 'react';
import { Tabs, type TabItem } from './Tabs';

const ITEMS: TabItem[] = [
  { id: 'a', label: '개요' },
  { id: 'b', label: 'AAGUID 정책', badge: { text: '확인 필요 2', reason: 'MDS 미확인 AAGUID 2건' } },
  { id: 'c', label: '퍼널' },
];

function Host({ onChange }: { onChange?: (id: string) => void }) {
  const [active, setActive] = useState('a');
  return <Tabs ariaLabel="Tenant 탭" items={ITEMS} active={active} onChange={(id) => { setActive(id); onChange?.(id); }} />;
}

describe('Tabs', () => {
  it('선택된 탭만 tabIndex=0 (로빙 탭 순서)', () => {
    render(<Host />);
    expect(screen.getByRole('tab', { name: '개요' })).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('tab', { name: /AAGUID/ })).toHaveAttribute('tabindex', '-1');
    expect(screen.getByRole('tab', { name: '퍼널' })).toHaveAttribute('tabindex', '-1');
  });

  it('→ 는 다음, ← 는 이전 탭으로 포커스·선택을 옮기고 양 끝에서 되돌아간다', () => {
    const onChange = vi.fn();
    render(<Host onChange={onChange} />);
    const first = screen.getByRole('tab', { name: '개요' });
    first.focus();
    fireEvent.keyDown(first, { key: 'ArrowRight' });
    const second = screen.getByRole('tab', { name: /AAGUID/ });
    expect(onChange).toHaveBeenLastCalledWith('b');
    expect(document.activeElement).toBe(second);
    expect(second).toHaveAttribute('aria-selected', 'true');

    fireEvent.keyDown(second, { key: 'ArrowLeft' });
    expect(onChange).toHaveBeenLastCalledWith('a');
    fireEvent.keyDown(screen.getByRole('tab', { name: '개요' }), { key: 'ArrowLeft' });
    expect(onChange).toHaveBeenLastCalledWith('c');
    expect(document.activeElement).toBe(screen.getByRole('tab', { name: '퍼널' }));
    fireEvent.keyDown(screen.getByRole('tab', { name: '퍼널' }), { key: 'ArrowRight' });
    expect(onChange).toHaveBeenLastCalledWith('a');
  });

  it('Home/End 는 처음/마지막 탭, 다른 키는 무시', () => {
    const onChange = vi.fn();
    render(<Host onChange={onChange} />);
    const first = screen.getByRole('tab', { name: '개요' });
    fireEvent.keyDown(first, { key: 'End' });
    expect(onChange).toHaveBeenLastCalledWith('c');
    fireEvent.keyDown(screen.getByRole('tab', { name: '퍼널' }), { key: 'Home' });
    expect(onChange).toHaveBeenLastCalledWith('a');
    onChange.mockClear();
    fireEvent.keyDown(screen.getByRole('tab', { name: '개요' }), { key: 'a' });
    expect(onChange).not.toHaveBeenCalled();
  });

  it('경고는 색 점 대신 "확인 필요 N" 라벨, 사유는 title 툴팁', () => {
    render(<Host />);
    const badge = screen.getByText('확인 필요 2');
    expect(badge).toHaveClass('badge', 'badge--warning', 'tabs__badge');
    expect(badge).toHaveAttribute('title', 'MDS 미확인 AAGUID 2건');
    expect(screen.getByRole('tab', { name: /AAGUID/ })).toHaveAttribute('title', 'MDS 미확인 AAGUID 2건');
    expect(document.querySelector('.tabs__dot')).toBeNull();
  });
});
