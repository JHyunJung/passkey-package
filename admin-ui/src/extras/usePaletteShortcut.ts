import { useEffect } from 'react';

/**
 * ⌘K / Ctrl+K 로 커맨드 팔레트를 토글한다.
 * aria-modal alertdialog(발급 키·복구 코드·위험 확인)가 열려 있으면 팔레트를 열지 않는다 —
 * 팔레트에서 이동하면 다이얼로그의 비밀값이 사라질 수 있다.
 */
export function usePaletteShortcut(onToggle: () => void) {
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (!(e.metaKey || e.ctrlKey) || e.key.toLowerCase() !== 'k') return;
      e.preventDefault();
      if (document.querySelector('[role="alertdialog"][aria-modal="true"]')) return;
      onToggle();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onToggle]);
}
