import { describe, it, expect, vi } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import { usePaletteShortcut } from './usePaletteShortcut';

function Host({ onToggle }: { onToggle: () => void }) {
  usePaletteShortcut(onToggle);
  return null;
}

describe('usePaletteShortcut', () => {
  it('⌘K / Ctrl+K 로 팔레트를 토글한다', () => {
    const onToggle = vi.fn();
    render(<Host onToggle={onToggle} />);
    fireEvent.keyDown(window, { key: 'k', metaKey: true });
    fireEvent.keyDown(window, { key: 'K', ctrlKey: true });
    expect(onToggle).toHaveBeenCalledTimes(2);
  });

  it('aria-modal alertdialog(비밀값·확인 다이얼로그)가 열려 있으면 열지 않는다', () => {
    const onToggle = vi.fn();
    render(<><Host onToggle={onToggle} /><div role="alertdialog" aria-modal="true" /></>);
    fireEvent.keyDown(window, { key: 'k', metaKey: true });
    expect(onToggle).not.toHaveBeenCalled();
  });
});
