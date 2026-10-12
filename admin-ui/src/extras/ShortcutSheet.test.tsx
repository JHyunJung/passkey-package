import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useState } from 'react';
import { ShortcutSheet, shortcutRows } from './ShortcutSheet';
import { useHelpShortcut } from './useHelpShortcut';

// App.tsx 의 AuthenticatedApp 과 같은 조합: 훅이 시트를 연다.
function Harness({ platform = true }: { platform?: boolean }) {
  const [open, setOpen] = useState(false);
  useHelpShortcut(() => setOpen(true));
  return (
    <>
      <input aria-label="검색어" />
      <ShortcutSheet open={open} onClose={() => setOpen(false)} platform={platform} />
    </>
  );
}

describe('ShortcutSheet', () => {
  it('? 키로 열리고, 닫기 버튼으로 닫힌다', () => {
    render(<Harness />);
    fireEvent.keyDown(window, { key: '?' });
    const dlg = screen.getByRole('dialog', { name: '키보드 단축키' });
    expect(dlg).toHaveTextContent('명령 팔레트 열기');
    expect(dlg).toHaveTextContent('Tenants 목록으로 이동');
    expect(dlg).toHaveTextContent('이 단축키 목록');
    expect(dlg).toHaveTextContent('다이얼로그 · 메뉴 닫기');
    expect(dlg).toHaveTextContent('입력란에 포커스가 있으면 G · ? 단축키는 동작하지 않습니다.');
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '닫기' }));
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('입력란에서 ? 를 치면 열리지 않는다', () => {
    render(<Harness />);
    fireEvent.keyDown(screen.getByLabelText('검색어'), { key: '?' });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('alertdialog(비밀값·위험 확인)가 열려 있으면 열리지 않는다', () => {
    render(<><Harness /><div role="alertdialog" aria-modal="true" aria-label="발급된 키" /></>);
    fireEvent.keyDown(window, { key: '?' });
    expect(screen.queryByRole('dialog', { name: '키보드 단축키' })).toBeNull();
  });

  it('Ctrl·⌘·Alt 와 함께 누르면 무시', () => {
    render(<Harness />);
    fireEvent.keyDown(window, { key: '?', ctrlKey: true });
    fireEvent.keyDown(window, { key: '?', metaKey: true });
    fireEvent.keyDown(window, { key: '?', altKey: true });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('RP 관리자에게는 G 이동 단축키를 보여 주지 않는다', () => {
    const rows = shortcutRows({ mac: false, platform: false });
    expect(rows.map((r) => r.keys)).not.toContain('G T');
    expect(rows[0]).toMatchObject({ keys: 'Ctrl K', desc: '명령 팔레트 열기' });
  });

  it('OS 에 맞는 팔레트 단축키를 먼저, 다른 OS 는 보조 표기', () => {
    expect(shortcutRows({ mac: true, platform: true })[0]).toMatchObject({ keys: '⌘K', alt: 'Windows · Ctrl K' });
    expect(shortcutRows({ mac: false, platform: true })[0]).toMatchObject({ keys: 'Ctrl K', alt: 'Mac · ⌘K' });
    expect(shortcutRows({ mac: true, platform: true }).map((r) => r.keys)).toEqual(expect.arrayContaining(['G T', 'G A', 'G C', 'G S']));
  });
});
