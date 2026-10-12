import { useEffect, type KeyboardEvent, type RefObject } from 'react';

export const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** 실제로 Tab 이 닿는 요소만: tabindex=-1 · hidden · aria-hidden 부분 트리와 비활성 요소는 제외. */
function tabbables(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
    (el) => !el.matches('[tabindex="-1"], :disabled') && !el.closest('[hidden], [aria-hidden="true"]'),
  );
}

/**
 * 모달 포커스 관리(Dialog · CommandPalette 공용). open 이 되면 initialFocusRef(없으면 첫 포커스 가능 요소)로 옮기고,
 * 닫히면 열기 전 위치로 되돌린다. 반환값은 컨테이너 onKeyDown 에 거는 Tab 순환 핸들러.
 */
export function useFocusTrap(open: boolean, boxRef: RefObject<HTMLElement>, initialFocusRef?: RefObject<HTMLElement>) {
  useEffect(() => {
    if (!open) return;
    const prev = document.activeElement as HTMLElement | null;
    const target = initialFocusRef?.current ?? (boxRef.current ? tabbables(boxRef.current)[0] : undefined);
    target?.focus();
    return () => { prev?.focus?.(); };
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  return function onKeyDown(e: KeyboardEvent<HTMLElement>) {
    if (e.key !== 'Tab' || !boxRef.current) return;
    const els = tabbables(boxRef.current);
    if (els.length === 0) { e.preventDefault(); return; } // 갈 곳이 없으면 컨테이너에 머문다
    const first = els[0], last = els[els.length - 1];
    if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
  };
}
