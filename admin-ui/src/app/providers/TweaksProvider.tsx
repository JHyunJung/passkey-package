import * as React from 'react';

export type Tweaks = {
  theme: 'light' | 'dark';
  density: 'compact' | 'comfortable';
  accent: 'indigo' | 'violet' | 'blue' | 'teal' | 'amber';
  tablestyle: 'lines' | 'striped' | 'borderless';
  sidebar: 'labels' | 'icons';
};

const STORAGE_KEY = 'passkey-admin:tweaks';

const DEFAULTS: Tweaks = {
  theme: 'light',
  density: 'compact',
  accent: 'indigo',
  tablestyle: 'lines',
  sidebar: 'labels',
};

function readInitial(): Tweaks {
  if (typeof window === 'undefined') return DEFAULTS;
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored) {
      return { ...DEFAULTS, ...(JSON.parse(stored) as Partial<Tweaks>) };
    }
  } catch {
    /* ignore */
  }
  const prefersDark = window.matchMedia?.('(prefers-color-scheme: dark)').matches;
  return { ...DEFAULTS, theme: prefersDark ? 'dark' : 'light' };
}

function applyToDocument(t: Tweaks) {
  const r = document.documentElement;
  r.setAttribute('data-theme', t.theme);
  r.setAttribute('data-density', t.density);
  r.setAttribute('data-accent', t.accent);
  r.setAttribute('data-tablestyle', t.tablestyle);
  r.setAttribute('data-sidebar', t.sidebar);
}

type Ctx = {
  tweaks: Tweaks;
  setTweak: <K extends keyof Tweaks>(key: K, value: Tweaks[K]) => void;
  reset: () => void;
};

const TweaksContext = React.createContext<Ctx | null>(null);

export function TweaksProvider({ children }: { children: React.ReactNode }) {
  const [tweaks, setTweaks] = React.useState<Tweaks>(readInitial);

  // useLayoutEffect: DOM attribute를 first paint 전에 적용 → 테마 flash 방지
  React.useLayoutEffect(() => {
    applyToDocument(tweaks);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(tweaks));
    } catch {
      /* storage 차단 환경 무시 */
    }
  }, [tweaks]);

  const setTweak = React.useCallback<Ctx['setTweak']>(
    (key, value) => setTweaks((prev) => ({ ...prev, [key]: value })),
    []
  );
  const reset = React.useCallback(() => setTweaks(DEFAULTS), []);

  return <TweaksContext.Provider value={{ tweaks, setTweak, reset }}>{children}</TweaksContext.Provider>;
}

export function useTweaks() {
  const ctx = React.useContext(TweaksContext);
  if (!ctx) throw new Error('useTweaks must be used within TweaksProvider');
  return ctx;
}
