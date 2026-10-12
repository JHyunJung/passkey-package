import { useEffect } from 'react';
import type { AppRoute } from '@/appRoute';

export const GO_SHORTCUTS = { tenants: 'G T', activity: 'G A', 'audit-chain': 'G C', settings: 'G S' } as const;
const KEY_TO_ROUTE: Record<string, AppRoute> = { t: { name: 'tenants' }, a: { name: 'activity' }, c: { name: 'audit-chain' }, s: { name: 'settings' } };

export function isTyping(t: EventTarget | null): boolean {
  const el = t as HTMLElement | null;
  if (!el || !el.tagName) return false;
  return /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) || el.isContentEditable || !!el.closest?.('[contenteditable]:not([contenteditable="false"])');
}

export function useGoShortcuts(onNavigate: (r: AppRoute) => void, enabled: boolean) {
  useEffect(() => {
    if (!enabled) return;
    let armed = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    function onKey(e: KeyboardEvent) {
      if (e.repeat) return;
      if (e.metaKey || e.ctrlKey || e.altKey || isTyping(e.target) || document.querySelector('[aria-modal="true"]')) { armed = false; return; }
      const k = e.key.toLowerCase();
      if (armed && KEY_TO_ROUTE[k]) { e.preventDefault(); armed = false; clearTimeout(timer); onNavigate(KEY_TO_ROUTE[k]); return; }
      if (k === 'g') { armed = true; clearTimeout(timer); timer = setTimeout(() => { armed = false; }, 1000); return; }
      armed = false;
    }
    window.addEventListener('keydown', onKey);
    return () => { window.removeEventListener('keydown', onKey); clearTimeout(timer); };
  }, [onNavigate, enabled]);
}
