import { useEffect, type KeyboardEvent, type RefObject } from 'react';

export const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * 모달 포커스 관리(Dialog · CommandPalette 공용). open 이 되면 initialFocusRef(없으면 첫 포커스 가능 요소)로 옮기고,
 * 닫히면 열기 전 위치로 되돌린다. 반환값은 컨테이너 onKeyDown 에 거는 Tab 순환 핸들러.
 */
export function useFocusTrap(open: boolean, boxRef: RefObject<HTMLElement>, initialFocusRef?: RefObject<HTMLElement>) {
  useEffect(() => {
    if (!open) return;
    const prev = document.activeElement as HTMLElement | null;
    const target = initialFocusRef?.current ?? boxRef.current?.querySelector<HTMLElement>(FOCUSABLE);
    target?.focus();
    return () => { prev?.focus?.(); };
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  return function onKeyDown(e: KeyboardEvent<HTMLElement>) {
    if (e.key !== 'Tab' || !boxRef.current) return;
    const els = Array.from(boxRef.current.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (els.length === 0) return;
    const first = els[0], last = els[els.length - 1];
    if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
  };
}
