import { useState, useEffect, useCallback } from 'react';
import { shade, withAlpha } from './tweaks-utils';

export type Tweaks = {
  theme: 'light' | 'dark';
  density: 'compact' | 'comfortable';
  tableStyle: 'lines' | 'striped' | 'borderless';
  sidebarMode: 'labels' | 'icons' | 'collapsed';
  accent: string;
};

const STORAGE_KEY = 'passkey-admin:tweaks';

const DEFAULTS: Tweaks = {
  theme: 'light',
  density: 'compact',
  tableStyle: 'lines',
  sidebarMode: 'labels',
  accent: '#4f46e5',
};

function readInitial(initial?: Partial<Tweaks>): Tweaks {
  if (typeof window === 'undefined') return { ...DEFAULTS, ...initial };
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored) return { ...DEFAULTS, ...JSON.parse(stored), ...initial };
  } catch {
    /* ignore */
  }
  return { ...DEFAULTS, ...initial };
}

export function useTweaks(initial?: Partial<Tweaks>): [Tweaks, <K extends keyof Tweaks>(key: K, value: Tweaks[K]) => void] {
  const [t, setT] = useState<Tweaks>(() => readInitial(initial));

  useEffect(() => {
    const root = document.documentElement;
    root.setAttribute('data-theme', t.theme);
    root.setAttribute('data-density', t.density);
    root.setAttribute('data-tablestyle', t.tableStyle);
    root.setAttribute('data-sidebar', t.sidebarMode);
    root.style.setProperty('--accent', t.accent);
    root.style.setProperty('--accent-hover', shade(t.accent, -10));
    root.style.setProperty('--accent-press', shade(t.accent, -20));
    root.style.setProperty('--accent-soft', withAlpha(t.accent, 0.12));
    root.style.setProperty('--accent-soft-2', withAlpha(t.accent, 0.22));
    root.style.setProperty('--focus-ring', `0 0 0 3px ${withAlpha(t.accent, 0.22)}`);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(t));
    } catch {
      /* ignore */
    }
  }, [t]);

  const set = useCallback(<K extends keyof Tweaks>(key: K, value: Tweaks[K]) => {
    setT((prev) => ({ ...prev, [key]: value }));
  }, []);

  return [t, set];
}
