import { useEffect } from 'react';
import { isTyping } from './useGoShortcuts';

/**
 * `?` 로 키보드 단축키 시트를 연다(H10 도움말 진입점).
 * 입력 중이거나 모달(발급 키·복구 코드·위험 확인 alertdialog 포함)이 열려 있으면 무시한다 —
 * 비밀값 다이얼로그 위에 다른 다이얼로그를 띄우지 않는다.
 */
export function useHelpShortcut(onOpen: () => void) {
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key !== '?' || e.metaKey || e.ctrlKey || e.altKey || e.repeat) return;
      if (isTyping(e.target)) return;
      if (document.querySelector('[aria-modal="true"]')) return;
      e.preventDefault();
      onOpen();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onOpen]);
}
