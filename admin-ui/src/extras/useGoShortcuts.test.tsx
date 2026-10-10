import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, fireEvent, act } from '@testing-library/react';
import { useGoShortcuts } from './useGoShortcuts';

function Host({ onNavigate, enabled = true }: { onNavigate: (r: unknown) => void; enabled?: boolean }) {
  useGoShortcuts(onNavigate as never, enabled);
  return <div><input aria-label="검색" /><textarea aria-label="메모" /></div>;
}

describe('useGoShortcuts', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('g → t 는 tenants 로', () => {
    const nav = vi.fn();
    render(<Host onNavigate={nav} />);
    fireEvent.keyDown(document.body, { key: 'g' });
    fireEvent.keyDown(document.body, { key: 't' });
    expect(nav).toHaveBeenCalledWith({ name: 'tenants' });
  });
  it('1초가 지나면 체인이 끊긴다', () => {
    const nav = vi.fn();
    render(<Host onNavigate={nav} />);
    fireEvent.keyDown(document.body, { key: 'g' });
    act(() => { vi.advanceTimersByTime(1100); });
    fireEvent.keyDown(document.body, { key: 'a' });
    expect(nav).not.toHaveBeenCalled();
  });
  it('ignores G-chord while typing (input/textarea)', () => {
    const nav = vi.fn();
    const { getByLabelText } = render(<Host onNavigate={nav} />);
    fireEvent.keyDown(getByLabelText('검색'), { key: 'g' });
    fireEvent.keyDown(getByLabelText('검색'), { key: 's' });
    fireEvent.keyDown(getByLabelText('메모'), { key: 'g' });
    fireEvent.keyDown(getByLabelText('메모'), { key: 'c' });
    expect(nav).not.toHaveBeenCalled();
  });
  it('모달이 열려 있으면 무시', () => {
    const nav = vi.fn();
    render(<><Host onNavigate={nav} /><div aria-modal="true" /></>);
    fireEvent.keyDown(document.body, { key: 'g' });
    fireEvent.keyDown(document.body, { key: 's' });
    expect(nav).not.toHaveBeenCalled();
  });
  it('수정키 동반·disabled 면 무시', () => {
    const nav = vi.fn();
    const { rerender } = render(<Host onNavigate={nav} />);
    fireEvent.keyDown(document.body, { key: 'g', metaKey: true });
    fireEvent.keyDown(document.body, { key: 't' });
    rerender(<Host onNavigate={nav} enabled={false} />);
    fireEvent.keyDown(document.body, { key: 'g' });
    fireEvent.keyDown(document.body, { key: 't' });
    expect(nav).not.toHaveBeenCalled();
  });
});
