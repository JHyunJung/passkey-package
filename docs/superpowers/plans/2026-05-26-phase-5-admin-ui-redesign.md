# Phase 5 — Admin UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 admin-ui 8 페이지를 Linear/Vercel 풍 OKLCH 디자인 시스템 (`docs/design-package/project/src/tokens.css`, 470줄)으로 재작성. Cmd-K 검색 + 30분 idle 모달 추가. 백엔드 변경 0.

**Architecture:** `docs/design-package/` (Claude Design handoff bundle)에서 tokens.css 그대로 + 디자인 mock JSX들을 TypeScript로 포팅. 페이지마다 기존 client.ts API 호출 로직 보존, markup + className만 교체. Light theme 고정 (Tweaks panel 제외). ApiError catch → Toast 표시 통합.

**Tech Stack:** React 18.3 + TypeScript + Vite 5 + react-router-dom 6. Pretendard (jsdelivr CDN) + Geist (Google Fonts CDN). 외부 UI 라이브러리 0 (디자인 mock과 동일하게 pure React + CSS).

---

## File Inventory

**admin-ui — 신규 인프라:**
- Create `admin-ui/src/styles/tokens.css` (copy from design-package, 470줄)
- Modify `admin-ui/index.html` (font CDN preconnect + stylesheet, lang ko)
- Modify `admin-ui/src/main.tsx` (import tokens.css)

**admin-ui — 공통 컴포넌트:**
- Create `admin-ui/src/components/Icons.tsx`
- Create `admin-ui/src/components/Dialog.tsx`
- Create `admin-ui/src/components/Toast.tsx`
- Create `admin-ui/src/components/Sidebar.tsx`
- Create `admin-ui/src/components/Header.tsx`
- Modify `admin-ui/src/components/Layout.tsx` (REWRITE)
- Create `admin-ui/src/components/CommandPalette.tsx`
- Create `admin-ui/src/components/IdleTimeout.tsx`

**admin-ui — 페이지 (REWRITE):**
- Modify `admin-ui/src/pages/Login.tsx`
- Modify `admin-ui/src/pages/TenantList.tsx`
- Modify `admin-ui/src/pages/TenantCreate.tsx`
- Modify `admin-ui/src/pages/ApiKeyList.tsx`
- Modify `admin-ui/src/pages/ApiKeyCreateModal.tsx`
- Modify `admin-ui/src/pages/AuditLog.tsx`
- Modify `admin-ui/src/pages/MdsStatus.tsx`
- Modify `admin-ui/src/pages/KeyManagement.tsx`

**admin-ui — 기존 보존:**
- Modify `admin-ui/src/App.tsx` (route + ToastProvider + CommandPalette + IdleTimeout 추가)
- Modify `admin-ui/src/api/client.ts` (선택적 onApiError 콜백 추가 — Toast 통합)

**Docs:**
- Add `docs/design-package/` (Claude Design handoff bundle, 영구 commit)

---

## Task 1: tokens.css + 폰트 + main.tsx wiring

**Files:**
- Create `admin-ui/src/styles/tokens.css` (copy from `docs/design-package/project/src/tokens.css`)
- Modify `admin-ui/index.html`
- Modify `admin-ui/src/main.tsx`

- [ ] **Step 1: Copy tokens.css**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/phase-5-admin-ui-redesign
mkdir -p admin-ui/src/styles
cp docs/design-package/project/src/tokens.css admin-ui/src/styles/tokens.css
```

- [ ] **Step 2: Update `admin-ui/index.html`**

Open the existing file and replace the `<head>` with:

```html
<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Crosscert Passkey Admin</title>

    <link rel="preconnect" href="https://cdn.jsdelivr.net" crossorigin />
    <link rel="preconnect" href="https://fonts.googleapis.com" crossorigin />
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
    <link rel="stylesheet" as="style" crossorigin
          href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/variable/pretendardvariable-dynamic-subset.min.css" />
    <link rel="stylesheet"
          href="https://fonts.googleapis.com/css2?family=Geist:wght@400;500;600;700&family=Geist+Mono:wght@400;500;600&display=swap" />
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

(Preserve the existing `<script type="module">` line; only the head + html lang changes.)

- [ ] **Step 3: Update `admin-ui/src/main.tsx`**

Add the import at the top:

```tsx
import './styles/tokens.css';
```

(Keep existing imports + render call.)

- [ ] **Step 4: Build sanity**

```bash
cd admin-ui && npm run build && cd ..
./gradlew :admin-app:bootJar 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL both. Bundle size in stdout ~+10KB (tokens.css).

- [ ] **Step 5: Codex review**

```bash
git add admin-ui/src/styles/tokens.css admin-ui/index.html admin-ui/src/main.tsx
cat > /tmp/codex-p5-t1.txt <<'PROMPT'
Code review for Phase 5 T1 — admin-ui tokens.css + font wiring.

Copies the 470-line OKLCH design system from
docs/design-package/project/src/tokens.css into admin-ui/src/styles/.
Wires Pretendard (Korean) + Geist (Latin/digits) via CDN preconnect
+ stylesheet links. Imports tokens.css in main.tsx for global apply.

This is the foundation for all subsequent page rewrites. After this
task: no visual change yet (no className applied), but tokens are
available everywhere.

Review:
1. OKLCH browser support — modern browsers only (Chrome 111+, Safari
   16.4+, Firefox 113+). Operator environment assumed.
2. CDN dependencies — corporate firewalls may block jsdelivr or
   googlefonts. tokens.css fallback chain (system fonts) catches.
3. Bundle size impact — tokens.css ~10KB raw, gzip ~3KB.
4. data-theme="dark" / data-density / data-sidebar / data-tablestyle
   variants exist in tokens.css but no JS toggles them in Phase 5
   (Light + comfortable + label sidebar + bordered table fixed).
5. lang="ko" — primary user language is Korean.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p5-t1.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p5-t1-full.txt
codex exec -s read-only "$(cat /tmp/codex-p5-t1-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p5-t1-out.txt 2>&1
tail -200 /tmp/codex-p5-t1-out.txt
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(admin-ui): tokens.css OKLCH design system + CDN fonts (Phase 5 T1)"
```

---

## Task 2: Icons.tsx — 30 SVG icons

**Files:**
- Create `admin-ui/src/components/Icons.tsx`

- [ ] **Step 1: Read the design package icons source**

```bash
cat docs/design-package/project/src/icons.jsx | head -100
```

Identify the 30 most commonly used icon definitions (those referenced from `shell.jsx`, `pages-1.jsx`, `pages-2.jsx`).

- [ ] **Step 2: Write `Icons.tsx`**

Create the file with TypeScript types and all 30 icons. Structure:

```tsx
type IconProps = { size?: number; className?: string };

const base = (size: number) => ({
  width: size,
  height: size,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
});

export function Search({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.5-3.5" />
    </svg>
  );
}

// ...repeat for Plus, Check, X, Alert, Building, Key, Receipt, Shield,
// Activity, Cog, User, Logout, Hash, Fingerprint, Globe, Spinner, Copy,
// ChevronDown, ChevronRight, Filter, Download, Trash, Refresh, Lock,
// EyeOff, Eye, Info, ExternalLink, BrandMark

export function BrandMark({ size = 26 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32">
      <rect width="32" height="32" rx="8" fill="#4f46e5" />
      <text x="16" y="22" textAnchor="middle" fontSize="18"
            fontWeight="700" fill="white" fontFamily="Geist, sans-serif">P</text>
    </svg>
  );
}
```

Copy SVG path data verbatim from `docs/design-package/project/src/icons.jsx`. Adapt React.createElement style to JSX.

- [ ] **Step 3: Build sanity**

```bash
cd admin-ui && npm run build && cd ..
```

Expected: no TypeScript errors.

- [ ] **Step 4: Codex review**

```bash
git add admin-ui/src/components/Icons.tsx
cat > /tmp/codex-p5-t2.txt <<'PROMPT'
Code review for Phase 5 T2 — Icons.tsx (30 SVG icons).

30 inline-SVG React components ported from
docs/design-package/project/src/icons.jsx. Each is
({size?, className?}) => JSX.Element. Stroke-based outline style,
currentColor stroke for theme adaptation.

Review:
1. SVG path data accuracy vs source mock (random spot-check 3 icons).
2. BrandMark hardcoded color #4f46e5 — matches design system v2 indigo
   (intentional, per design-package/chats/chat2.md).
3. accessibility: stroke icons without aria-label rely on parent
   button/link semantic. Acceptable for decorative use.
4. Bundle size — ~3KB raw per 10 icons inline. Total ~9KB for 30.
5. No external icon library dependency.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p5-t2.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p5-t2-full.txt
codex exec -s read-only "$(cat /tmp/codex-p5-t2-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p5-t2-out.txt 2>&1
tail -200 /tmp/codex-p5-t2-out.txt
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(admin-ui): Icons.tsx — 30 inline SVG components (Phase 5 T2)"
```

---

## Task 3: Dialog.tsx + Toast.tsx — modal + notification primitives

**Files:**
- Create `admin-ui/src/components/Dialog.tsx`
- Create `admin-ui/src/components/Toast.tsx`

- [ ] **Step 1: Write `Dialog.tsx`**

```tsx
import { useEffect, type ReactNode } from 'react';

interface Props {
  open: boolean;
  onClose: () => void;
  title: string;
  sub?: string;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
  closeOnScrim?: boolean;
}

export default function Dialog({
  open, onClose, title, sub, children, footer, wide, closeOnScrim = true,
}: Props) {
  useEffect(() => {
    if (!open) return;
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div
      className="scrim"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && closeOnScrim) onClose();
      }}
    >
      <div className={`dialog${wide ? ' dialog--wide' : ''}`} role="dialog" aria-modal="true">
        <div className="dialog__head">
          <h3 className="dialog__title">{title}</h3>
          {sub && <div className="dialog__sub">{sub}</div>}
        </div>
        <div className="dialog__body">{children}</div>
        {footer && <div className="dialog__foot">{footer}</div>}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Write `Toast.tsx`**

```tsx
import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import { Check, X, Alert } from './Icons';

type ToastKind = 'ok' | 'err' | 'warn';
interface Toast {
  id: string;
  kind: ToastKind;
  title: string;
  message?: string;
  traceId?: string;
  duration?: number;
}
type PushFn = (t: Omit<Toast, 'id'>) => void;

const ToastCtx = createContext<PushFn | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const push: PushFn = useCallback((t) => {
    const id = Math.random().toString(36).slice(2);
    setToasts((arr) => [...arr, { id, ...t }]);
    setTimeout(() => setToasts((arr) => arr.filter((x) => x.id !== id)), t.duration ?? 4200);
  }, []);

  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="toast-rack">
        {toasts.map((t) => (
          <div key={t.id} className="toast" role="status">
            <div className={`toast__icon toast__icon--${t.kind}`}>
              {t.kind === 'err' ? <X size={11} /> : t.kind === 'warn' ? <Alert size={11} /> : <Check size={11} />}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="toast__title">{t.title}</div>
              {t.message && <div className="toast__sub">{t.message}</div>}
              {t.traceId && <div className="toast__trace">traceId · {t.traceId}</div>}
            </div>
            <button
              className="btn btn--ghost btn--xs"
              onClick={() => setToasts((a) => a.filter((x) => x.id !== t.id))}
              aria-label="닫기"
            >
              <X size={12} />
            </button>
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

export function useToast(): PushFn {
  const ctx = useContext(ToastCtx);
  if (!ctx) throw new Error('useToast must be used within ToastProvider');
  return ctx;
}
```

- [ ] **Step 3: Build sanity**

```bash
cd admin-ui && npm run build && cd ..
```

- [ ] **Step 4: Codex review**

```bash
git add admin-ui/src/components/Dialog.tsx admin-ui/src/components/Toast.tsx
cat > /tmp/codex-p5-t3.txt <<'PROMPT'
Code review for Phase 5 T3 — Dialog + Toast primitives.

Dialog: scrim click to close (optional), Escape key, role=dialog,
aria-modal. CSS via tokens.css .scrim/.dialog/.dialog__*. Spring
overshoot animation per tokens.

Toast: context+provider pattern. ToastProvider wraps app, useToast()
hook returns push fn. 4.2s auto-dismiss. Icon by kind (ok/err/warn).
traceId shown when present (Phase 4 ApiError integration target).

Review:
1. Dialog Escape key listener cleanup — useEffect return removes it.
2. Toast id collision risk — Math.random().slice(2) — 11+ char base36.
   Acceptable for SPA scale (max ~10 concurrent toasts).
3. Toast 'closed' callback — uses array filter; if Toast removed via
   timeout AND user click race-condition, both setState calls remain
   idempotent (filter on missing id is no-op). Safe.
4. ToastProvider must wrap App in T5 (Layout) or App.tsx — verified
   in T5 plan.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p5-t3.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p5-t3-full.txt
codex exec -s read-only "$(cat /tmp/codex-p5-t3-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p5-t3-out.txt 2>&1
tail -200 /tmp/codex-p5-t3-out.txt
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(admin-ui): Dialog + Toast primitives + ToastProvider (Phase 5 T3)"
```

---

## Task 4: Sidebar.tsx + Header.tsx

**Files:**
- Create `admin-ui/src/components/Sidebar.tsx`
- Create `admin-ui/src/components/Header.tsx`

- [ ] **Step 1: Write `Sidebar.tsx`**

```tsx
import { NavLink } from 'react-router-dom';
import { BrandMark, Building, Key, Receipt, Activity } from './Icons';

const NAV = [
  { to: '/tenants',  label: 'Tenants', icon: Building },
  { to: '/api-keys', label: 'API Keys', icon: Key },
  { to: '/keys',     label: 'Signing Keys', icon: Key },
  { to: '/mds',      label: 'MDS', icon: Activity },
  { to: '/audit',    label: 'Audit Log', icon: Receipt },
];

export default function Sidebar() {
  return (
    <aside style={{
      gridArea: 'sidebar',
      background: 'var(--surface)',
      borderRight: '1px solid var(--border)',
      display: 'flex',
      flexDirection: 'column',
      position: 'sticky',
      top: 0,
      height: '100vh',
      overflow: 'hidden',
    }}>
      <div style={{
        padding: '16px 18px',
        borderBottom: '1px solid var(--border)',
        display: 'flex',
        alignItems: 'center',
        gap: 10,
      }}>
        <BrandMark size={26} />
        <div className="stack-1" style={{ minWidth: 0 }}>
          <div style={{ fontWeight: 600, fontSize: 13, lineHeight: 1.2, letterSpacing: '-0.01em' }}>Passkey Admin</div>
          <div style={{ fontSize: 11, color: 'var(--text-mute)', lineHeight: 1.2 }}>Crosscert · prod</div>
        </div>
      </div>
      <nav style={{ flex: 1, padding: '8px', display: 'flex', flexDirection: 'column', gap: 2 }}>
        {NAV.map(({ to, label, icon: Icon }) => (
          <NavLink
            key={to}
            to={to}
            style={({ isActive }) => ({
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              padding: '8px 12px',
              borderRadius: 'var(--radius)',
              color: isActive ? 'var(--accent)' : 'var(--text-soft)',
              background: isActive ? 'var(--accent-soft)' : 'transparent',
              textDecoration: 'none',
              fontSize: 13,
              fontWeight: 500,
              transition: 'background var(--dur) var(--ease-out), color var(--dur) var(--ease-out)',
            })}
          >
            <Icon size={16} />
            {label}
          </NavLink>
        ))}
      </nav>
    </aside>
  );
}
```

- [ ] **Step 2: Write `Header.tsx`**

```tsx
import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { Me } from '../api/types';
import { Search } from './Icons';

interface Props {
  onOpenPalette: () => void;
}

const PAGE_TITLES: Record<string, string> = {
  '/tenants': 'Tenants',
  '/tenants/new': 'Tenants',
  '/api-keys': 'API Keys',
  '/keys': 'Signing Keys',
  '/mds': 'MDS Status',
  '/audit': 'Audit Log',
  '/login': 'Sign in',
};

export default function Header({ onOpenPalette }: Props) {
  const loc = useLocation();
  const nav = useNavigate();
  const [me, setMe] = useState<Me | null>(null);

  useEffect(() => {
    api.get<Me>('/admin/api/me').then(setMe).catch(() => setMe(null));
  }, []);

  const title = PAGE_TITLES[loc.pathname] ?? loc.pathname.replace(/^\//, '');

  async function logout() {
    const csrf = document.cookie.match(/XSRF-TOKEN=([^;]+)/)?.[1];
    await fetch('/admin/logout', {
      method: 'POST',
      credentials: 'include',
      headers: csrf ? { 'X-XSRF-TOKEN': decodeURIComponent(csrf) } : {},
    });
    nav('/login');
  }

  return (
    <header style={{
      height: 56,
      display: 'flex',
      alignItems: 'center',
      padding: '0 24px',
      gap: 16,
      borderBottom: '1px solid var(--border-subtle)',
      background: 'var(--surface)',
      position: 'sticky',
      top: 0,
      zIndex: 5,
    }}>
      <div style={{ fontWeight: 600, fontSize: 14, color: 'var(--text)' }}>{title}</div>

      <button
        onClick={onOpenPalette}
        className="row"
        style={{
          marginLeft: 'auto',
          padding: '6px 10px',
          border: '1px solid var(--border)',
          borderRadius: 'var(--radius)',
          background: 'var(--surface-2)',
          color: 'var(--text-mute)',
          fontSize: 12,
          minWidth: 260,
          gap: 8,
        }}
      >
        <Search size={14} />
        <span style={{ flex: 1, textAlign: 'left' }}>검색…</span>
        <span className="kbd">⌘K</span>
      </button>

      {me && (
        <div className="row" style={{ gap: 8 }}>
          <div className="stack-1" style={{ textAlign: 'right' }}>
            <div style={{ fontSize: 13, fontWeight: 500 }}>{me.email}</div>
            <div style={{ fontSize: 11, color: 'var(--text-mute)' }}>{me.role}</div>
          </div>
          <button className="btn btn--ghost btn--sm" onClick={logout}>로그아웃</button>
        </div>
      )}
    </header>
  );
}
```

- [ ] **Step 3: Build sanity**

```bash
cd admin-ui && npm run build && cd ..
```

- [ ] **Step 4: Codex review**

```bash
git add admin-ui/src/components/Sidebar.tsx admin-ui/src/components/Header.tsx
cat > /tmp/codex-p5-t4.txt <<'PROMPT'
Code review for Phase 5 T4 — Sidebar + Header.

Sidebar: 5 NavLink items with active-state styling via NavLink's
isActive callback. BrandMark + branded header. Fixed 232px width
via tokens --sidebar-w.

Header: page title from current route, ⌘K trigger button (shows kbd
shortcut), user info + logout. Fetches /admin/api/me on mount.

Review:
1. NavLink active style uses accent color + accent-soft bg — clear
   visual cue.
2. Header logout uses CSRF token (matches client.ts loginForm pattern).
3. /admin/api/me fetch silently falls back to null on failure
   (e.g., unauthenticated /login page). Header still renders without
   user info.
4. Sidebar style uses inline + tokens — acceptable hybrid (variables
   from CSS, structural style inline).
5. Header z-index 5 — must be below dialog scrim (z-index 60 in tokens).

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p5-t4.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p5-t4-full.txt
codex exec -s read-only "$(cat /tmp/codex-p5-t4-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p5-t4-out.txt 2>&1
tail -200 /tmp/codex-p5-t4-out.txt
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(admin-ui): Sidebar + Header components (Phase 5 T4)"
```

---

## Task 5: Layout.tsx REWRITE + App.tsx provider wiring

**Files:**
- Modify `admin-ui/src/components/Layout.tsx`
- Modify `admin-ui/src/App.tsx`

- [ ] **Step 1: Rewrite `Layout.tsx`**

```tsx
import { useState } from 'react';
import { Outlet } from 'react-router-dom';
import Sidebar from './Sidebar';
import Header from './Header';
import CommandPalette from './CommandPalette';

export default function Layout() {
  const [paletteOpen, setPaletteOpen] = useState(false);
  return (
    <div className="app" style={{ gridTemplateAreas: '"sidebar content"' }}>
      <Sidebar />
      <div className="content" style={{ gridArea: 'content' }}>
        <Header onOpenPalette={() => setPaletteOpen(true)} />
        <main className="page">
          <Outlet />
        </main>
      </div>
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} />
    </div>
  );
}
```

- [ ] **Step 2: Update `App.tsx`**

Add `ToastProvider` wrapper + `IdleTimeout` mount:

```tsx
import { Routes, Route, Navigate } from 'react-router-dom';
import Login from './pages/Login';
import Layout from './components/Layout';
import TenantList from './pages/TenantList';
import TenantCreate from './pages/TenantCreate';
import ApiKeyList from './pages/ApiKeyList';
import AuditLog from './pages/AuditLog';
import MdsStatus from './pages/MdsStatus';
import KeyManagement from './pages/KeyManagement';
import { ToastProvider } from './components/Toast';
import IdleTimeout from './components/IdleTimeout';

export default function App() {
  return (
    <ToastProvider>
      <IdleTimeout />
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route element={<Layout />}>
          <Route path="/tenants" element={<TenantList />} />
          <Route path="/tenants/new" element={<TenantCreate />} />
          <Route path="/api-keys" element={<ApiKeyList />} />
          <Route path="/audit" element={<AuditLog />} />
          <Route path="/mds" element={<MdsStatus />} />
          <Route path="/keys" element={<KeyManagement />} />
        </Route>
        <Route path="*" element={<Navigate to="/tenants" replace />} />
      </Routes>
    </ToastProvider>
  );
}
```

Note: `CommandPalette` and `IdleTimeout` files don't exist yet at this point. Create empty stub components or temporarily comment them out and revisit in T12/T13. **Recommended:** create minimal stubs:

```tsx
// admin-ui/src/components/CommandPalette.tsx (T5 stub, full impl in T12)
interface Props { open: boolean; onClose: () => void; }
export default function CommandPalette({ open, onClose }: Props) {
  if (!open) return null;
  return null;  // T12 fills this in
}

// admin-ui/src/components/IdleTimeout.tsx (T5 stub, full impl in T13)
export default function IdleTimeout() { return null; }
```

This keeps the build green through T5–T11 page rewrites.

- [ ] **Step 3: Build sanity**

```bash
cd admin-ui && npm run build && cd ..
./gradlew :admin-app:bootJar 2>&1 | tail -5
```

- [ ] **Step 4: Codex review**

```bash
git add admin-ui/src/components/Layout.tsx \
        admin-ui/src/App.tsx \
        admin-ui/src/components/CommandPalette.tsx \
        admin-ui/src/components/IdleTimeout.tsx
cat > /tmp/codex-p5-t5.txt <<'PROMPT'
Code review for Phase 5 T5 — Layout REWRITE + App provider wiring.

Layout: grid app shell with Sidebar + content (Header + main) + the
CommandPalette modal anchored at app level. paletteOpen state lifted
here.

App: wraps Routes in ToastProvider + mounts IdleTimeout once at root.
CommandPalette and IdleTimeout are stubs in T5 — full impl lands in
T12/T13.

Review:
1. ToastProvider must wrap routes so all pages access useToast().
2. IdleTimeout mounted outside Routes — always active (Login page
   also resets idle counter, but that's harmless since no auth yet).
3. CommandPalette state lives in Layout (only available to authed
   pages). Logged-out users on /login never see ⌘K. Acceptable.
4. Stub CommandPalette returns null — no visual regression vs no-op.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p5-t5.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p5-t5-full.txt
codex exec -s read-only "$(cat /tmp/codex-p5-t5-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p5-t5-out.txt 2>&1
tail -200 /tmp/codex-p5-t5-out.txt
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(admin-ui): Layout REWRITE + ToastProvider/IdleTimeout wiring (Phase 5 T5)"
```

---

## Task 6: Login.tsx REWRITE

**Files:**
- Modify `admin-ui/src/pages/Login.tsx`

- [ ] **Step 1: Reference**

Read `docs/design-package/project/src/pages-1.jsx` lines 1-80 (the LoginPage component). Note: split hero (left gradient + KPI mock) + right form.

- [ ] **Step 2: Rewrite `Login.tsx`**

```tsx
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { BrandMark } from '../components/Icons';

export default function Login() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const nav = useNavigate();

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await fetch('/admin/api/me', { credentials: 'include' }).catch(() => null);
      const ok = await api.loginForm(email, password);
      if (ok) nav('/tenants');
      else setError('이메일 또는 비밀번호가 올바르지 않습니다.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{
      display: 'grid',
      gridTemplateColumns: '1fr 480px',
      minHeight: '100vh',
      background: 'var(--bg)',
    }}>
      {/* Hero */}
      <div style={{
        background: 'linear-gradient(135deg, oklch(0.42 0.18 268) 0%, oklch(0.30 0.13 268) 100%)',
        color: 'white',
        padding: '64px 56px',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
      }}>
        <div className="row" style={{ gap: 12 }}>
          <BrandMark size={32} />
          <div style={{ fontSize: 18, fontWeight: 600, letterSpacing: '-0.01em' }}>Crosscert Passkey</div>
        </div>
        <div className="stack-3">
          <div style={{ fontSize: 12, color: 'rgba(255,255,255,0.7)', letterSpacing: '0.1em', textTransform: 'uppercase' }}>
            v1.0 · multi-tenant FIDO2 server
          </div>
          <h1 style={{ fontSize: 34, fontWeight: 600, lineHeight: 1.2, margin: 0, letterSpacing: '-0.022em' }}>
            패스키 인증을<br/>운영하는 콘솔.
          </h1>
          <p style={{ fontSize: 14, color: 'rgba(255,255,255,0.78)', lineHeight: 1.6, maxWidth: 380 }}>
            tenant 온보딩, API key 회수, credential 폐기, audit hash chain 검증까지 한 곳에서.
          </p>
        </div>
        <div className="row" style={{ gap: 24, fontSize: 12, color: 'rgba(255,255,255,0.65)' }}>
          <span>© 2026 Crosscert</span>
          <span>본 콘솔 접근은 모두 audit log에 기록됩니다.</span>
        </div>
      </div>

      {/* Form */}
      <div style={{ background: 'var(--surface)', padding: '64px 48px', display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
        <h2 style={{ fontSize: 22, fontWeight: 600, marginTop: 0, marginBottom: 6, letterSpacing: '-0.01em' }}>관리자 로그인</h2>
        <p style={{ fontSize: 13, color: 'var(--text-mute)', marginTop: 0, marginBottom: 28 }}>
          Crosscert Passkey 콘솔에 접근하려면 운영자 계정으로 로그인하세요.
        </p>

        <form onSubmit={submit} className="stack-4">
          <div>
            <label className="label">이메일</label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              autoComplete="email"
              className="input"
              placeholder="user@crosscert.com"
            />
          </div>
          <div>
            <label className="label">비밀번호</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              autoComplete="current-password"
              className="input"
              placeholder="••••••••"
            />
          </div>
          <button type="submit" className="btn btn--primary btn--lg" disabled={busy}>
            {busy ? '로그인 중…' : '로그인'}
          </button>
          {error && <div className="banner banner--danger">{error}</div>}
        </form>

        <div className="banner banner--info" style={{ marginTop: 28 }}>
          <div className="banner__body">
            30분 동안 활동이 없으면 자동 로그아웃됩니다. 모든 mutation은 audit chain에 기록됩니다.
          </div>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Build sanity**

```bash
cd admin-ui && npm run build && cd ..
```

- [ ] **Step 4: Codex review**

```bash
git add admin-ui/src/pages/Login.tsx
cat > /tmp/codex-p5-t6.txt <<'PROMPT'
Code review for Phase 5 T6 — Login REWRITE.

Split layout: left hero (indigo gradient + brand + tagline + footer),
right form (label/input/button using tokens.css classes). Error shows
as danger banner. Info banner explains idle policy.

Existing loginForm() call preserved verbatim. /admin/api/me prefetch
preserved (CSRF cookie seeding).

Review:
1. Hero gradient uses oklch directly (matches design tokens accent
   shade). No hardcoded hex (per chat2 v1→v2 cleanup).
2. busy state disables button + shows "로그인 중…" — prevents double
   submit.
3. Form uses native HTML validation (required, type=email,
   autoComplete) — no client-side regex.
4. Banner danger color from tokens — matches global error visual
   language.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p5-t6.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p5-t6-full.txt
codex exec -s read-only "$(cat /tmp/codex-p5-t6-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p5-t6-out.txt 2>&1
tail -200 /tmp/codex-p5-t6-out.txt
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(admin-ui): Login REWRITE — split hero + form (Phase 5 T6)"
```

---

## Task 7: TenantList.tsx + TenantCreate.tsx REWRITE

**Files:**
- Modify `admin-ui/src/pages/TenantList.tsx`
- Modify `admin-ui/src/pages/TenantCreate.tsx`

- [ ] **Step 1: Read current files + design mock**

```bash
cat admin-ui/src/pages/TenantList.tsx admin-ui/src/pages/TenantCreate.tsx
sed -n '80,250p' docs/design-package/project/src/pages-2.jsx
```

- [ ] **Step 2: Rewrite `TenantList.tsx`**

```tsx
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { TenantView } from '../api/types';
import { useToast } from '../components/Toast';
import { Plus, Search, Building } from '../components/Icons';

export default function TenantList() {
  const [tenants, setTenants] = useState<TenantView[]>([]);
  const [q, setQ] = useState('');
  const [loading, setLoading] = useState(true);
  const toast = useToast();
  const nav = useNavigate();

  useEffect(() => {
    api.get<TenantView[]>('/admin/api/tenants')
      .then((r) => { setTenants(r); setLoading(false); })
      .catch((e: ApiError) => {
        setLoading(false);
        toast({ kind: 'err', title: '테넌트 목록을 가져오지 못했습니다', message: e.serverMessage, traceId: e.traceId });
      });
  }, [toast]);

  const filtered = useMemo(
    () => tenants.filter((t) =>
      !q || t.id.toLowerCase().includes(q.toLowerCase())
         || (t.displayName ?? '').toLowerCase().includes(q.toLowerCase())
    ),
    [tenants, q]
  );

  const total = tenants.length;
  const activeCount = tenants.filter((t) => t.status === 'ACTIVE').length;

  return (
    <div className="stack-6">
      <div className="page__head">
        <div>
          <h1 className="page__title">Tenants</h1>
          <div className="page__sub">RP 회사별 격리된 Passkey 환경. 모든 데이터가 tenant_id로 row-level 분리됩니다.</div>
        </div>
        <button className="btn btn--primary" onClick={() => nav('/tenants/new')}>
          <Plus size={14} /> 신규 tenant
        </button>
      </div>

      <div className="grid-4">
        <Metric label="활성 TENANT" value={String(activeCount)} sub={`전체 ${total}건`} />
        <Metric label="전체 TENANT" value={String(total)} />
        <Metric label="조회 결과" value={String(filtered.length)} sub="현재 필터 기준" />
        <Metric label="환경" value="prod" sub="Crosscert · multi-tenant" />
      </div>

      <div className="card">
        <div className="card__head">
          <div className="row" style={{ flex: 1, gap: 10 }}>
            <Search size={14} className="muted" />
            <input
              className="input"
              placeholder="tenant id · name 검색"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              style={{ flex: 1, maxWidth: 360 }}
            />
          </div>
          <div className="row" style={{ gap: 8 }}>
            <span className="muted" style={{ fontSize: 12 }}>{filtered.length} / {total}건</span>
          </div>
        </div>
        {loading ? (
          <div className="card__body"><Skeleton /></div>
        ) : filtered.length === 0 ? (
          <div className="empty">
            <div className="empty__art"><Building size={20} /></div>
            <div className="empty__title">{q ? '검색 결과 없음' : '등록된 tenant 없음'}</div>
            <div className="empty__sub">{q ? '검색어를 바꿔보세요.' : '신규 tenant를 추가해 시작하세요.'}</div>
          </div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>TENANT</th>
                <th>RP ID</th>
                <th>RP NAME</th>
                <th>STATUS</th>
                <th>CREATED</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((t) => (
                <tr key={t.id}>
                  <td>
                    <div style={{ fontWeight: 500 }}>{t.displayName ?? t.id}</div>
                    <div className="mono muted">{t.id}</div>
                  </td>
                  <td className="mono">{t.rpId}</td>
                  <td>{t.rpName}</td>
                  <td><span className={`badge badge--${t.status === 'ACTIVE' ? 'success' : 'warning'} badge--dot`}>{t.status}</span></td>
                  <td className="mono muted">{t.createdAt?.slice(0, 10)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

function Metric({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="card" style={{ padding: 'var(--card-pad)' }}>
      <div className="metric-label">{label}</div>
      <div className="metric-value">{value}</div>
      {sub && <div className="metric-delta">{sub}</div>}
    </div>
  );
}

function Skeleton() {
  return (
    <div className="stack-2">
      {[0, 1, 2, 3].map((i) => <div key={i} className="skeleton" style={{ height: 36 }} />)}
    </div>
  );
}
```

- [ ] **Step 3: Rewrite `TenantCreate.tsx`**

```tsx
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { TenantView } from '../api/types';
import { useToast } from '../components/Toast';

export default function TenantCreate() {
  const nav = useNavigate();
  const toast = useToast();
  const [id, setId] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [rpId, setRpId] = useState('');
  const [rpName, setRpName] = useState('');
  const [allowedOriginsJson, setAllowedOriginsJson] = useState('[]');
  const [attestationPolicyJson, setAttestationPolicyJson] = useState(
    '{"acceptedFormats":["none","packed"],"requireUserVerification":true,"mdsRequired":false}'
  );
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await api.post<TenantView>('/admin/api/tenants', {
        id, displayName, rpId, rpName, allowedOriginsJson, attestationPolicyJson,
      });
      toast({ kind: 'ok', title: 'Tenant 생성됨', message: id });
      nav('/tenants');
    } catch (err) {
      const e = err as ApiError;
      toast({ kind: 'err', title: '생성 실패', message: e.serverMessage, traceId: e.traceId });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="stack-6" style={{ maxWidth: 720 }}>
      <div className="page__head">
        <div>
          <h1 className="page__title">신규 Tenant</h1>
          <div className="page__sub">RP 회사 단위의 격리 환경을 생성합니다.</div>
        </div>
      </div>
      <form onSubmit={submit} className="card">
        <div className="card__body stack-4">
          <Field label="Tenant ID" hint="고유 식별자. 영문 소문자 + 숫자 + 하이픈.">
            <input className="input mono" value={id} onChange={(e) => setId(e.target.value)} required pattern="^[a-z0-9][a-z0-9-]{1,62}$" />
          </Field>
          <Field label="표시 이름">
            <input className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} required />
          </Field>
          <div className="grid-2">
            <Field label="RP ID" hint="WebAuthn rpId. e.g. acme.example.com">
              <input className="input mono" value={rpId} onChange={(e) => setRpId(e.target.value)} required />
            </Field>
            <Field label="RP 이름">
              <input className="input" value={rpName} onChange={(e) => setRpName(e.target.value)} required />
            </Field>
          </div>
          <Field label="허용 origin (JSON 배열)">
            <textarea className="input mono" value={allowedOriginsJson} onChange={(e) => setAllowedOriginsJson(e.target.value)} rows={2} required />
          </Field>
          <Field label="Attestation 정책 (JSON)">
            <textarea className="input mono" value={attestationPolicyJson} onChange={(e) => setAttestationPolicyJson(e.target.value)} rows={3} required />
          </Field>
        </div>
        <div className="dialog__foot" style={{ borderRadius: '0 0 var(--radius-lg) var(--radius-lg)' }}>
          <button type="button" className="btn btn--outline" onClick={() => nav('/tenants')}>취소</button>
          <button type="submit" className="btn btn--primary" disabled={busy}>{busy ? '생성 중…' : '생성'}</button>
        </div>
      </form>
    </div>
  );
}

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="label">{label}</label>
      {children}
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}
```

- [ ] **Step 4: Build sanity**

```bash
cd admin-ui && npm run build && cd ..
```

- [ ] **Step 5: Codex review**

```bash
git add admin-ui/src/pages/TenantList.tsx admin-ui/src/pages/TenantCreate.tsx
cat > /tmp/codex-p5-t7.txt <<'PROMPT'
Code review for Phase 5 T7 — TenantList + TenantCreate REWRITE.

TenantList: KPI grid (4) + search input + filtered table card +
empty/loading states + skeleton.

TenantCreate: form card mirroring TenantList visual style. Cancel
nav back. ApiError catch → Toast (serverMessage + traceId).

Review:
1. Page__head pattern consistent across pages — title + sub + action.
2. Skeleton/empty/error states all visible — no silent failures.
3. ApiError serverMessage shown directly to operator (not raw exception
   stack). traceId in toast helps log correlation.
4. Form field validation uses native HTML (pattern, required).
5. JSON fields are raw textarea — operator must format correctly;
   server validation returns ApiError(C001) with fieldErrors on bad
   JSON.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p5-t7.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p5-t7-full.txt
codex exec -s read-only "$(cat /tmp/codex-p5-t7-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p5-t7-out.txt 2>&1
tail -200 /tmp/codex-p5-t7-out.txt
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(admin-ui): TenantList + TenantCreate REWRITE (Phase 5 T7)"
```

---

## Task 8: ApiKeyList.tsx + ApiKeyCreateModal.tsx REWRITE

**Files:**
- Modify `admin-ui/src/pages/ApiKeyList.tsx`
- Modify `admin-ui/src/pages/ApiKeyCreateModal.tsx`

- [ ] **Step 1: Read current + design mock (pages-3.jsx)**

- [ ] **Step 2: Rewrite ApiKeyList.tsx**

Pattern: page_head + tenant selector dropdown + table card + Issue button → opens ApiKeyCreateModal.

```tsx
import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { ApiKeyView, TenantView } from '../api/types';
import { useToast } from '../components/Toast';
import ApiKeyCreateModal from './ApiKeyCreateModal';
import { Plus, Key, Trash } from '../components/Icons';

export default function ApiKeyList() {
  const [tenants, setTenants] = useState<TenantView[]>([]);
  const [tenantId, setTenantId] = useState<string>('');
  const [keys, setKeys] = useState<ApiKeyView[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const toast = useToast();

  useEffect(() => {
    api.get<TenantView[]>('/admin/api/tenants').then((r) => {
      setTenants(r);
      if (r.length > 0 && !tenantId) setTenantId(r[0].id);
    });
  }, []);

  useEffect(() => {
    if (!tenantId) return;
    setLoading(true);
    api.get<ApiKeyView[]>(`/admin/api/api-keys?tenantId=${encodeURIComponent(tenantId)}`)
      .then((r) => { setKeys(r); setLoading(false); })
      .catch((e: ApiError) => {
        setLoading(false);
        toast({ kind: 'err', title: 'API 키 목록 실패', message: e.serverMessage, traceId: e.traceId });
      });
  }, [tenantId, toast]);

  function refresh() {
    if (!tenantId) return;
    api.get<ApiKeyView[]>(`/admin/api/api-keys?tenantId=${encodeURIComponent(tenantId)}`).then(setKeys);
  }

  async function revoke(id: number) {
    if (!confirm('이 API 키를 회수합니다. 즉시 사용 불가가 됩니다.')) return;
    try {
      await api.delete(`/admin/api/api-keys/${id}`);
      toast({ kind: 'ok', title: 'API 키 회수됨' });
      refresh();
    } catch (err) {
      const e = err as ApiError;
      toast({ kind: 'err', title: '회수 실패', message: e.serverMessage, traceId: e.traceId });
    }
  }

  return (
    <div className="stack-6">
      <div className="page__head">
        <div>
          <h1 className="page__title">API Keys</h1>
          <div className="page__sub">tenant별 발급된 RP API key 관리.</div>
        </div>
        <button className="btn btn--primary" onClick={() => setOpen(true)} disabled={!tenantId}>
          <Plus size={14} /> 키 발급
        </button>
      </div>

      <div className="card">
        <div className="card__head">
          <div className="row" style={{ gap: 12 }}>
            <span className="muted" style={{ fontSize: 12 }}>TENANT</span>
            <select
              className="input mono"
              value={tenantId}
              onChange={(e) => setTenantId(e.target.value)}
              style={{ width: 280 }}
            >
              {tenants.map((t) => <option key={t.id} value={t.id}>{t.displayName ?? t.id}</option>)}
            </select>
          </div>
          <span className="muted" style={{ fontSize: 12 }}>{keys.length}건</span>
        </div>
        {loading ? (
          <div className="card__body"><div className="skeleton" style={{ height: 100 }} /></div>
        ) : keys.length === 0 ? (
          <div className="empty">
            <div className="empty__art"><Key size={20} /></div>
            <div className="empty__title">발급된 API 키 없음</div>
            <div className="empty__sub">우측 상단 "키 발급" 버튼으로 시작하세요.</div>
          </div>
        ) : (
          <table className="table">
            <thead>
              <tr><th>이름</th><th>PREFIX</th><th>STATUS</th><th>CREATED</th><th>EXPIRES</th><th></th></tr>
            </thead>
            <tbody>
              {keys.map((k) => (
                <tr key={k.id}>
                  <td>{k.name}</td>
                  <td className="mono">{k.prefix}…</td>
                  <td><span className={`badge badge--${k.revokedAt ? 'danger' : 'success'} badge--dot`}>{k.revokedAt ? 'REVOKED' : 'ACTIVE'}</span></td>
                  <td className="mono muted">{k.createdAt?.slice(0, 10)}</td>
                  <td className="mono muted">{k.expiresAt?.slice(0, 10) ?? '-'}</td>
                  <td>
                    {!k.revokedAt && (
                      <button className="btn btn--ghost btn--xs" onClick={() => revoke(k.id)} title="회수">
                        <Trash size={12} />
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {open && tenantId && (
        <ApiKeyCreateModal
          tenantId={tenantId}
          onClose={() => setOpen(false)}
          onIssued={() => { setOpen(false); refresh(); }}
        />
      )}
    </div>
  );
}
```

- [ ] **Step 3: Rewrite ApiKeyCreateModal.tsx**

Critical: **one-time plaintext display** with copy-strong UX (scrim non-dismissible while plaintext visible).

```tsx
import { useState, type FormEvent } from 'react';
import { api, ApiError } from '../api/client';
import type { ApiKeyCreateResponse } from '../api/types';
import { useToast } from '../components/Toast';
import Dialog from '../components/Dialog';
import { Copy, Alert } from '../components/Icons';

interface Props {
  tenantId: string;
  onClose: () => void;
  onIssued: () => void;
}

export default function ApiKeyCreateModal({ tenantId, onClose, onIssued }: Props) {
  const [name, setName] = useState('');
  const [scopesJson, setScopesJson] = useState('[]');
  const [busy, setBusy] = useState(false);
  const [issued, setIssued] = useState<ApiKeyCreateResponse | null>(null);
  const [copied, setCopied] = useState(false);
  const toast = useToast();

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      const resp = await api.post<ApiKeyCreateResponse>('/admin/api/api-keys', {
        tenantId, name, scopesJson,
      });
      setIssued(resp);
    } catch (err) {
      const e = err as ApiError;
      toast({ kind: 'err', title: '발급 실패', message: e.serverMessage, traceId: e.traceId });
    } finally {
      setBusy(false);
    }
  }

  async function copyPlaintext() {
    if (!issued) return;
    await navigator.clipboard.writeText(issued.plainText);
    setCopied(true);
    toast({ kind: 'ok', title: '클립보드에 복사됨' });
  }

  if (issued) {
    return (
      <Dialog
        open
        onClose={() => { onIssued(); }}
        closeOnScrim={false}
        title="API 키 발급 완료"
        sub="이 plaintext는 지금 한 번만 표시됩니다. 닫으면 영구 소실됩니다."
        wide
        footer={
          <>
            <button className="btn btn--outline" onClick={copyPlaintext}>
              <Copy size={14} /> 복사
            </button>
            <button className="btn btn--primary" onClick={() => { onIssued(); }} disabled={!copied}>
              {copied ? '복사함, 닫기' : '먼저 복사하세요'}
            </button>
          </>
        }
      >
        <div className="banner banner--warning" style={{ marginBottom: 16 }}>
          <Alert size={16} className="banner__icon" />
          <div>
            <div className="banner__title">한 번만 노출되는 plaintext</div>
            <div className="banner__body">서버는 hash만 저장합니다. 복사 후 안전한 곳에 보관하세요.</div>
          </div>
        </div>
        <div className="label">PREFIX</div>
        <div className="mono" style={{ marginBottom: 12 }}>{issued.prefix}</div>
        <div className="label">PLAINTEXT (1회성)</div>
        <code
          className="mono"
          style={{
            display: 'block',
            padding: 12,
            background: 'var(--surface-sunk)',
            borderRadius: 'var(--radius)',
            wordBreak: 'break-all',
            fontSize: 12,
          }}
        >{issued.plainText}</code>
      </Dialog>
    );
  }

  return (
    <Dialog
      open
      onClose={onClose}
      title="API 키 발급"
      sub={`tenant ${tenantId}에 새 API key를 발급합니다.`}
      footer={
        <>
          <button className="btn btn--outline" onClick={onClose}>취소</button>
          <button form="apikey-create-form" type="submit" className="btn btn--primary" disabled={busy}>
            {busy ? '발급 중…' : '발급'}
          </button>
        </>
      }
    >
      <form id="apikey-create-form" onSubmit={submit} className="stack-4">
        <div>
          <label className="label">키 이름</label>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} required placeholder="primary, staging-tester, …" />
        </div>
        <div>
          <label className="label">scopes (JSON 배열)</label>
          <textarea className="input mono" value={scopesJson} onChange={(e) => setScopesJson(e.target.value)} rows={2} />
          <div className="hint">비워두면 모든 scope. 예: ["registration", "authentication"]</div>
        </div>
      </form>
    </Dialog>
  );
}
```

- [ ] **Step 4: Build sanity**

```bash
cd admin-ui && npm run build && cd ..
```

- [ ] **Step 5: Codex review**

```bash
git add admin-ui/src/pages/ApiKeyList.tsx admin-ui/src/pages/ApiKeyCreateModal.tsx
cat > /tmp/codex-p5-t8.txt <<'PROMPT'
Code review for Phase 5 T8 — ApiKeyList + ApiKeyCreateModal REWRITE.

ApiKeyList: tenant dropdown + table card + skeleton/empty/error states.
Revoke confirms with native confirm() and posts DELETE.

ApiKeyCreateModal: 2-stage dialog. Stage 1: form. Stage 2: plaintext
displayed ONCE — close button disabled until user clicks copy
(prevents accidental loss). closeOnScrim=false during stage 2.

Review:
1. Plaintext lifecycle — never re-fetched, only shown once. Close
   button gated on copy = strongly encourages save.
2. navigator.clipboard.writeText requires HTTPS (or localhost) — fine
   for admin console behind TLS.
3. Stage 2 has no Escape close (Dialog default Escape still fires onClose
   → onIssued — which is intentional close + refresh). User loses
   plaintext if they Escape without copying. Acceptable trade-off:
   warning banner explains.
4. Revoke uses native confirm() — accessible, no extra dialog.
5. Background refresh after issue/revoke updates the list.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p5-t8.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p5-t8-full.txt
codex exec -s read-only "$(cat /tmp/codex-p5-t8-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p5-t8-out.txt 2>&1
tail -200 /tmp/codex-p5-t8-out.txt
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(admin-ui): ApiKeyList + ApiKeyCreateModal REWRITE (Phase 5 T8)"
```

---

## Task 9: AuditLog.tsx REWRITE

**Files:**
- Modify `admin-ui/src/pages/AuditLog.tsx`

- [ ] **Step 1: Rewrite**

```tsx
import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { AuditLogView } from '../api/types';
import { useToast } from '../components/Toast';
import { Receipt, Refresh, Shield } from '../components/Icons';

interface VerifyResult { ok: boolean; checked?: number; brokenAt?: number; message?: string; }

export default function AuditLog() {
  const [rows, setRows] = useState<AuditLogView[]>([]);
  const [action, setAction] = useState('');
  const [actorId, setActorId] = useState('');
  const [resourceType, setResourceType] = useState('');
  const [verify, setVerify] = useState<VerifyResult | null>(null);
  const [verifying, setVerifying] = useState(false);
  const [loading, setLoading] = useState(true);
  const toast = useToast();

  function load() {
    setLoading(true);
    const params = new URLSearchParams();
    if (action) params.set('action', action);
    if (actorId) params.set('actorId', actorId);
    if (resourceType) params.set('resourceType', resourceType);
    api.get<AuditLogView[]>(`/admin/api/audit?${params.toString()}`)
      .then((r) => { setRows(r); setLoading(false); })
      .catch((e: ApiError) => {
        setLoading(false);
        toast({ kind: 'err', title: '감사 로그 실패', message: e.serverMessage, traceId: e.traceId });
      });
  }

  useEffect(load, []);

  async function runVerify() {
    setVerifying(true);
    try {
      const r = await api.get<VerifyResult>('/admin/api/audit/verify');
      setVerify(r);
      toast({ kind: r.ok ? 'ok' : 'err', title: r.ok ? 'Chain OK' : 'Chain BROKEN', message: r.message });
    } catch (err) {
      const e = err as ApiError;
      toast({ kind: 'err', title: '검증 실패', message: e.serverMessage, traceId: e.traceId });
    } finally {
      setVerifying(false);
    }
  }

  return (
    <div className="stack-6">
      <div className="page__head">
        <div>
          <h1 className="page__title">Audit Log</h1>
          <div className="page__sub">모든 mutation은 hash chain으로 연결되어 위변조 검출 가능.</div>
        </div>
        <button className="btn btn--outline" onClick={runVerify} disabled={verifying}>
          <Shield size={14} /> {verifying ? '검증 중…' : 'Chain 검증'}
        </button>
      </div>

      {verify && (
        <div className={`banner banner--${verify.ok ? 'success' : 'danger'}`}>
          <Shield size={16} className="banner__icon" />
          <div>
            <div className="banner__title">{verify.ok ? `Chain 무결 · ${verify.checked ?? 0}개 검증됨` : 'Chain 위변조 감지'}</div>
            {verify.message && <div className="banner__body">{verify.message}</div>}
            {verify.brokenAt != null && <div className="banner__body mono">brokenAt: {verify.brokenAt}</div>}
          </div>
        </div>
      )}

      <div className="card">
        <div className="card__head">
          <div className="row" style={{ gap: 8, flex: 1 }}>
            <input className="input" placeholder="action" value={action} onChange={(e) => setAction(e.target.value)} style={{ maxWidth: 180 }} />
            <input className="input" placeholder="actorId" value={actorId} onChange={(e) => setActorId(e.target.value)} style={{ maxWidth: 120 }} />
            <input className="input" placeholder="resourceType" value={resourceType} onChange={(e) => setResourceType(e.target.value)} style={{ maxWidth: 180 }} />
            <button className="btn btn--outline" onClick={load}>
              <Refresh size={14} /> 적용
            </button>
          </div>
          <span className="muted" style={{ fontSize: 12 }}>{rows.length}건</span>
        </div>
        {loading ? (
          <div className="card__body"><div className="skeleton" style={{ height: 200 }} /></div>
        ) : rows.length === 0 ? (
          <div className="empty">
            <div className="empty__art"><Receipt size={20} /></div>
            <div className="empty__title">로그 없음</div>
            <div className="empty__sub">필터를 바꾸거나 액션을 발생시키세요.</div>
          </div>
        ) : (
          <table className="table">
            <thead>
              <tr><th>#</th><th>ACTION</th><th>ACTOR</th><th>RESOURCE</th><th>AT</th></tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td className="mono muted">{r.id}</td>
                  <td><span className="badge badge--accent">{r.action}</span></td>
                  <td className="mono">{r.actorId}</td>
                  <td className="mono muted">{r.resourceType}/{r.resourceId}</td>
                  <td className="mono muted">{r.createdAt?.slice(0, 19).replace('T', ' ')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Build sanity + Codex review + Commit**

(Pattern same as previous tasks.)

```bash
cd admin-ui && npm run build && cd ..
git add admin-ui/src/pages/AuditLog.tsx
# codex review with similar prompt focused on AuditLog
cat > /tmp/codex-p5-t9.txt <<'PROMPT'
Code review for Phase 5 T9 — AuditLog REWRITE.

3-input filter (action, actorId, resourceType) + Chain verify button.
Verify result rendered as banner (success or danger) with brokenAt
detail.

Review:
1. Filter rebuild on Apply button (not auto on type) — avoids
   filter-storm requests on every keystroke.
2. Verify result persistent until next click — operator can re-read.
3. Empty + loading + error states all visible.
4. Date trimming with slice(0,19).replace('T',' ') for compact display.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p5-t9.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p5-t9-full.txt
codex exec -s read-only "$(cat /tmp/codex-p5-t9-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p5-t9-out.txt 2>&1
tail -200 /tmp/codex-p5-t9-out.txt
git commit -m "feat(admin-ui): AuditLog REWRITE — filter + chain verify banner (Phase 5 T9)"
```

---

## Task 10: MdsStatus.tsx REWRITE

**Files:**
- Modify `admin-ui/src/pages/MdsStatus.tsx`

- [ ] **Step 1: Rewrite**

```tsx
import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { MdsStatusView, SyncResult } from '../api/types';
import { useToast } from '../components/Toast';
import { Refresh, Activity } from '../components/Icons';

export default function MdsStatus() {
  const [status, setStatus] = useState<MdsStatusView | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [last, setLast] = useState<SyncResult | null>(null);
  const toast = useToast();

  function refresh() {
    api.get<MdsStatusView>('/admin/api/mds/status').then(setStatus);
  }

  useEffect(refresh, []);

  async function sync() {
    setSyncing(true);
    try {
      const r = await api.post<SyncResult>('/admin/api/mds/sync', {});
      setLast(r);
      toast({ kind: r.status === 'SYNCED' ? 'ok' : 'warn', title: `Sync ${r.status}`, message: r.error });
      refresh();
    } catch (err) {
      const e = err as ApiError;
      toast({ kind: 'err', title: 'Sync 실패', message: e.serverMessage, traceId: e.traceId });
    } finally {
      setSyncing(false);
    }
  }

  return (
    <div className="stack-6">
      <div className="page__head">
        <div>
          <h1 className="page__title">MDS Status</h1>
          <div className="page__sub">FIDO Alliance Metadata Service BLOB 동기화 상태.</div>
        </div>
        <button className="btn btn--primary" onClick={sync} disabled={syncing}>
          <Refresh size={14} /> {syncing ? '동기화 중…' : '지금 동기화'}
        </button>
      </div>

      <div className="grid-3">
        <Metric label="VERSION" value={status?.version != null ? String(status.version) : '—'} sub="현재 BLOB" />
        <Metric label="NEXT UPDATE" value={status?.nextUpdate ?? '—'} sub="MDS 권고 다음 갱신" />
        <Metric label="LAST FETCHED" value={status?.fetchedAt?.slice(0, 19).replace('T', ' ') ?? '—'} sub="UTC" />
      </div>

      {last && (
        <div className={`banner banner--${last.status === 'SYNCED' ? 'success' : last.status === 'SKIPPED' ? 'info' : 'danger'}`}>
          <Activity size={16} className="banner__icon" />
          <div>
            <div className="banner__title">Last sync: {last.status} {last.version != null && `· v${last.version}`}</div>
            {last.error && <div className="banner__body">{last.error}</div>}
          </div>
        </div>
      )}
    </div>
  );
}

function Metric({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="card" style={{ padding: 'var(--card-pad)' }}>
      <div className="metric-label">{label}</div>
      <div className="metric-value" style={{ fontSize: 20 }}>{value}</div>
      {sub && <div className="metric-delta">{sub}</div>}
    </div>
  );
}
```

- [ ] **Step 2: Build + codex review + commit** (same pattern, codex prompt focused on MdsStatus).

```bash
cd admin-ui && npm run build && cd ..
git add admin-ui/src/pages/MdsStatus.tsx
# codex review with brief
git commit -m "feat(admin-ui): MdsStatus REWRITE — KPI + sync result banner (Phase 5 T10)"
```

---

## Task 11: KeyManagement.tsx REWRITE

**Files:**
- Modify `admin-ui/src/pages/KeyManagement.tsx`

- [ ] **Step 1: Rewrite**

```tsx
import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { KeyList, RotateResponse, SigningKeyView } from '../api/types';
import { useToast } from '../components/Toast';
import Dialog from '../components/Dialog';
import { Refresh, Key } from '../components/Icons';

export default function KeyManagement() {
  const [keys, setKeys] = useState<SigningKeyView[]>([]);
  const [loading, setLoading] = useState(true);
  const [confirm, setConfirm] = useState(false);
  const [rotating, setRotating] = useState(false);
  const [last, setLast] = useState<RotateResponse | null>(null);
  const toast = useToast();

  function refresh() {
    setLoading(true);
    api.get<KeyList>('/admin/api/keys')
      .then((r) => { setKeys(r.keys); setLoading(false); })
      .catch(() => setLoading(false));
  }

  useEffect(refresh, []);

  async function rotate() {
    setRotating(true);
    try {
      const r = await api.post<RotateResponse>('/admin/api/keys/rotate', {});
      setLast(r);
      toast({ kind: 'ok', title: '키 회전됨', message: `${r.oldKid} → ${r.newKid}` });
      setConfirm(false);
      refresh();
    } catch (err) {
      const e = err as ApiError;
      toast({ kind: 'err', title: '회전 실패', message: e.serverMessage, traceId: e.traceId });
    } finally {
      setRotating(false);
    }
  }

  const active = keys.filter((k) => k.status === 'ACTIVE').length;
  const rotated = keys.filter((k) => k.status === 'ROTATED').length;
  const revoked = keys.filter((k) => k.status === 'REVOKED').length;

  return (
    <div className="stack-6">
      <div className="page__head">
        <div>
          <h1 className="page__title">Signing Keys</h1>
          <div className="page__sub">ID Token 서명 키 생애 주기 (ACTIVE → ROTATED → REVOKED, 30분 grace).</div>
        </div>
        <button className="btn btn--primary" onClick={() => setConfirm(true)}>
          <Refresh size={14} /> 지금 회전
        </button>
      </div>

      <div className="grid-3">
        <Metric label="ACTIVE" value={String(active)} sub="현재 서명 중" />
        <Metric label="ROTATED" value={String(rotated)} sub="grace window" />
        <Metric label="REVOKED" value={String(revoked)} sub="JWKS에서 제외" />
      </div>

      {last && (
        <div className="banner banner--success">
          <Key size={16} className="banner__icon" />
          <div>
            <div className="banner__title">키 회전 완료</div>
            <div className="banner__body mono">old: {last.oldKid} → new: {last.newKid}</div>
          </div>
        </div>
      )}

      <div className="card">
        <div className="card__head">
          <h2 className="card__title">모든 키</h2>
          <span className="muted" style={{ fontSize: 12 }}>{keys.length}건</span>
        </div>
        {loading ? (
          <div className="card__body"><div className="skeleton" style={{ height: 100 }} /></div>
        ) : (
          <table className="table">
            <thead>
              <tr><th>KID</th><th>ALG</th><th>STATUS</th><th>CREATED</th><th>ROTATED</th><th>REVOKED</th></tr>
            </thead>
            <tbody>
              {keys.map((k) => (
                <tr key={k.id}>
                  <td className="mono">{k.kid.slice(0, 16)}…</td>
                  <td>{k.alg}</td>
                  <td><span className={`badge badge--${k.status === 'ACTIVE' ? 'success' : k.status === 'ROTATED' ? 'warning' : 'danger'} badge--dot`}>{k.status}</span></td>
                  <td className="mono muted">{k.createdAt?.slice(0, 19).replace('T', ' ')}</td>
                  <td className="mono muted">{k.rotatedAt?.slice(0, 19).replace('T', ' ') ?? '-'}</td>
                  <td className="mono muted">{k.revokedAt?.slice(0, 19).replace('T', ' ') ?? '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <Dialog
        open={confirm}
        onClose={() => setConfirm(false)}
        title="서명 키 회전"
        sub="새 ACTIVE 키 생성, 기존 ACTIVE는 ROTATED(30분 grace) 후 REVOKED 처리됩니다."
        footer={
          <>
            <button className="btn btn--outline" onClick={() => setConfirm(false)}>취소</button>
            <button className="btn btn--danger" onClick={rotate} disabled={rotating}>
              {rotating ? '회전 중…' : '회전 실행'}
            </button>
          </>
        }
      >
        <div className="banner banner--warning">
          이 작업은 즉시 실행됩니다. RP가 캐시한 JWKS는 grace window 동안 ROTATED 키도 검증 가능.
        </div>
      </Dialog>
    </div>
  );
}

function Metric({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="card" style={{ padding: 'var(--card-pad)' }}>
      <div className="metric-label">{label}</div>
      <div className="metric-value">{value}</div>
      {sub && <div className="metric-delta">{sub}</div>}
    </div>
  );
}
```

- [ ] **Step 2: Build + codex review + commit**

```bash
cd admin-ui && npm run build && cd ..
git add admin-ui/src/pages/KeyManagement.tsx
git commit -m "feat(admin-ui): KeyManagement REWRITE — confirm dialog + KPI + table (Phase 5 T11)"
```

---

## Task 12: CommandPalette.tsx full implementation

**Files:**
- Modify `admin-ui/src/components/CommandPalette.tsx`

- [ ] **Step 1: Replace stub with full implementation**

```tsx
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Dialog from './Dialog';
import { Search, ChevronRight } from './Icons';

interface Props { open: boolean; onClose: () => void; }

const ITEMS = [
  { label: 'Tenants',     to: '/tenants' },
  { label: '신규 Tenant', to: '/tenants/new' },
  { label: 'API Keys',    to: '/api-keys' },
  { label: 'Signing Keys', to: '/keys' },
  { label: 'MDS Status',  to: '/mds' },
  { label: 'Audit Log',   to: '/audit' },
];

export default function CommandPalette({ open, onClose }: Props) {
  const nav = useNavigate();
  const [q, setQ] = useState('');
  const [idx, setIdx] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) {
      setQ('');
      setIdx(0);
      setTimeout(() => inputRef.current?.focus(), 0);
    }
  }, [open]);

  const filtered = useMemo(
    () => ITEMS.filter((i) => !q || i.label.toLowerCase().includes(q.toLowerCase())),
    [q]
  );

  useEffect(() => { setIdx(0); }, [q]);

  function go(to: string) {
    onClose();
    nav(to);
  }

  function onKey(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setIdx((i) => Math.min(i + 1, filtered.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setIdx((i) => Math.max(i - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (filtered[idx]) go(filtered[idx].to);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} title="검색" wide>
      <div className="row" style={{ gap: 10, marginBottom: 12 }}>
        <Search size={16} className="muted" />
        <input
          ref={inputRef}
          className="input"
          placeholder="페이지 검색…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={onKey}
          style={{ flex: 1 }}
        />
      </div>
      <div className="stack-1">
        {filtered.length === 0 && <div className="muted" style={{ padding: '20px 0', textAlign: 'center' }}>일치하는 항목 없음</div>}
        {filtered.map((item, i) => (
          <button
            key={item.to}
            className="row"
            onClick={() => go(item.to)}
            onMouseEnter={() => setIdx(i)}
            style={{
              width: '100%',
              padding: '8px 12px',
              border: 0,
              background: i === idx ? 'var(--accent-soft)' : 'transparent',
              color: i === idx ? 'var(--accent)' : 'var(--text)',
              borderRadius: 'var(--radius)',
              fontSize: 13,
              textAlign: 'left',
              cursor: 'pointer',
            }}
          >
            <span style={{ flex: 1 }}>{item.label}</span>
            <span className="mono muted" style={{ fontSize: 11 }}>{item.to}</span>
            <ChevronRight size={12} />
          </button>
        ))}
      </div>
    </Dialog>
  );
}
```

- [ ] **Step 2: Add global ⌘K shortcut in Layout.tsx**

Modify `Layout.tsx` to listen for Cmd+K/Ctrl+K:

```tsx
useEffect(() => {
  const k = (e: KeyboardEvent) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      setPaletteOpen(true);
    }
  };
  window.addEventListener('keydown', k);
  return () => window.removeEventListener('keydown', k);
}, []);
```

- [ ] **Step 3: Build + codex review + commit**

```bash
cd admin-ui && npm run build && cd ..
git add admin-ui/src/components/CommandPalette.tsx admin-ui/src/components/Layout.tsx
git commit -m "feat(admin-ui): CommandPalette ⌘K full impl + global shortcut (Phase 5 T12)"
```

---

## Task 13: IdleTimeout.tsx full implementation

**Files:**
- Modify `admin-ui/src/components/IdleTimeout.tsx`

- [ ] **Step 1: Replace stub**

```tsx
import { useEffect, useRef, useState } from 'react';
import Dialog from './Dialog';

const IDLE_MS = 30 * 60 * 1000;    // 30 min
const COUNTDOWN_S = 60;

export default function IdleTimeout() {
  const [warn, setWarn] = useState(false);
  const [secs, setSecs] = useState(COUNTDOWN_S);
  const lastActive = useRef(Date.now());
  const tickRef = useRef<number | null>(null);

  useEffect(() => {
    const bump = () => { lastActive.current = Date.now(); };
    const events: (keyof WindowEventMap)[] = ['mousemove', 'keydown', 'scroll', 'click'];
    events.forEach((e) => window.addEventListener(e, bump));

    const check = window.setInterval(() => {
      if (!warn && Date.now() - lastActive.current > IDLE_MS) {
        setWarn(true);
        setSecs(COUNTDOWN_S);
      }
    }, 10_000);

    return () => {
      events.forEach((e) => window.removeEventListener(e, bump));
      window.clearInterval(check);
    };
  }, [warn]);

  useEffect(() => {
    if (!warn) return;
    tickRef.current = window.setInterval(() => {
      setSecs((s) => {
        if (s <= 1) {
          logout();
          return 0;
        }
        return s - 1;
      });
    }, 1000);
    return () => {
      if (tickRef.current) window.clearInterval(tickRef.current);
    };
  }, [warn]);

  function logout() {
    const csrf = document.cookie.match(/XSRF-TOKEN=([^;]+)/)?.[1];
    fetch('/admin/logout', {
      method: 'POST',
      credentials: 'include',
      headers: csrf ? { 'X-XSRF-TOKEN': decodeURIComponent(csrf) } : {},
    }).finally(() => {
      window.location.href = '/admin/login';
    });
  }

  function stay() {
    lastActive.current = Date.now();
    setWarn(false);
  }

  return (
    <Dialog
      open={warn}
      onClose={stay}
      closeOnScrim={false}
      title="자동 로그아웃 대기"
      sub={`${secs}초 후 보안을 위해 자동 로그아웃됩니다.`}
      footer={
        <>
          <button className="btn btn--outline" onClick={logout}>지금 로그아웃</button>
          <button className="btn btn--primary" onClick={stay}>계속 사용</button>
        </>
      }
    >
      <div className="banner banner--warning">
        30분 동안 활동이 감지되지 않았습니다. "계속 사용"을 누르면 세션이 갱신됩니다.
      </div>
    </Dialog>
  );
}
```

- [ ] **Step 2: Build + codex review + commit**

```bash
cd admin-ui && npm run build && cd ..
git add admin-ui/src/components/IdleTimeout.tsx
git commit -m "feat(admin-ui): IdleTimeout 30min + 60s countdown modal (Phase 5 T13)"
```

---

## Task 14: ApiError → Toast 통합 (client.ts onApiError hook)

**Files:**
- Modify `admin-ui/src/api/client.ts`

- [ ] **Step 1: Add optional global onApiError callback**

Each page already does `.catch((e: ApiError) => toast(...))`. T14 adds an OPTIONAL global handler for fire-and-forget calls that don't catch. Pattern: a `setApiErrorHandler(fn)` exported from client.ts, called once from `App.tsx` providing a function that uses `useToast()`.

But useToast() is a hook — can't call outside render. Compromise: `client.ts` exports an event-emitter style handler:

```ts
// at top of client.ts:
let globalApiErrorHandler: ((e: ApiError) => void) | null = null;
export function setApiErrorHandler(fn: ((e: ApiError) => void) | null) {
  globalApiErrorHandler = fn;
}

// inside the !envelope.success branch, BEFORE throw:
const err = new ApiError(
  res.status, envelope.code ?? 'C999', envelope.message ?? 'Unknown error',
  envelope.error?.fieldErrors, envelope.traceId
);
try { globalApiErrorHandler?.(err); } catch {}
throw err;
```

This is OPT-IN: if `App.tsx` calls `setApiErrorHandler(toast => apiErr => toast(...))`, every uncaught ApiError shows a toast. Pages can still locally `.catch()` to customize.

Update `App.tsx` (inside `ToastProvider`'s render):

```tsx
import { useEffect } from 'react';
import { setApiErrorHandler } from './api/client';
import { useToast } from './components/Toast';

function ApiErrorBridge() {
  const toast = useToast();
  useEffect(() => {
    setApiErrorHandler((e) => toast({
      kind: 'err',
      title: e.serverMessage,
      message: `[${e.code}] HTTP ${e.httpStatus}`,
      traceId: e.traceId,
    }));
    return () => setApiErrorHandler(null);
  }, [toast]);
  return null;
}

// inside <ToastProvider>:
<ApiErrorBridge />
<IdleTimeout />
<Routes>...
```

This means: pages that DON'T catch ApiError will automatically show a toast. Pages that DO catch (most of them) skip the bridge — but wait, the bridge fires BEFORE throw, so it fires regardless. Need refinement.

**Final design:** Bridge is opt-out, not opt-in. Pages that catch should NOT also see the toast. Simplest fix: bridge fires only for "uncaught" errors via window.addEventListener('unhandledrejection'). Pages catching .catch() prevent unhandledrejection. Pages not catching trigger it.

```tsx
function ApiErrorBridge() {
  const toast = useToast();
  useEffect(() => {
    const h = (e: PromiseRejectionEvent) => {
      const r = e.reason;
      if (r instanceof ApiError) {
        toast({ kind: 'err', title: r.serverMessage, message: `[${r.code}]`, traceId: r.traceId });
        e.preventDefault();  // suppress browser console error
      }
    };
    window.addEventListener('unhandledrejection', h);
    return () => window.removeEventListener('unhandledrejection', h);
  }, [toast]);
  return null;
}
```

Then drop the `setApiErrorHandler` complexity from client.ts. Skip the client.ts modification in this task.

- [ ] **Step 2: Add `ApiErrorBridge` inside `App.tsx`**

(As above.)

- [ ] **Step 3: Build + codex review + commit**

```bash
cd admin-ui && npm run build && cd ..
git add admin-ui/src/App.tsx
git commit -m "feat(admin-ui): ApiErrorBridge — uncaught ApiError → Toast (Phase 5 T14)"
```

---

## Task 15: Commit `docs/design-package/` for reference

**Files:**
- Add `docs/design-package/**`

- [ ] **Step 1: Stage and commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/phase-5-admin-ui-redesign
git add docs/design-package/
git commit -m "docs: Claude Design handoff bundle for Phase 5 reference"
```

(No codex review for pure doc artifacts.)

---

## Task 16: DoD verify + tag

**Files:** none (verification only)

- [ ] **Step 1: Full build**

```bash
./gradlew clean build 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL. 150 backend tests still green (Phase 4 baseline + 0 new tests).

- [ ] **Step 2: Boot apps + manual smoke**

```bash
docker compose ps  # confirm Oracle + Redis up
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local' > /tmp/admin.log 2>&1 &
./gradlew :passkey-app:bootRun --args='--spring.profiles.active=local' > /tmp/passkey.log 2>&1 &
until grep -qE "Started AdminApplication|APPLICATION FAILED" /tmp/admin.log; do sleep 5; done
until grep -qE "Started PasskeyApplication|APPLICATION FAILED" /tmp/passkey.log; do sleep 5; done
```

- [ ] **Step 3: Playwright smoke** (each page screenshot)

```bash
# Use Playwright MCP if available, else manual browser
# Expected: each page renders with OKLCH design, no console errors
```

Visit `/admin/`, login, navigate Tenants → API Keys → Keys → MDS → Audit. Verify:
- Login page split hero visible
- Sidebar 5 nav items with active highlight
- ⌘K opens palette, ↑↓ navigation works
- Toast appears on intentional error (e.g., revoke API key 999999)
- Rotate key → confirm dialog → success/conflict toast
- API key issue → 2-stage dialog → copy required to close

- [ ] **Step 4: Stop apps + tag**

```bash
pkill -f "AdminApplication|PasskeyApplication"
git tag -a phase-5-admin-ui-redesign-complete -m "$(cat <<'EOF'
Phase 5 (admin UI redesign) complete.

Acceptance:
- 150 Phase 0-4 backend tests still green (no backend change)
- admin-ui builds clean (Vite + tsc strict)
- 8 pages rewritten in OKLCH design system
- Cmd-K command palette + 30-minute idle modal added
- ApiErrorBridge — uncaught ApiError auto-toasted with traceId
- Playwright smoke confirmed 6 pages render correctly

Phase 5 surface:
- admin-ui/src/styles/tokens.css — Linear/Vercel-style OKLCH v2
  (Light fixed, dark variant preserved for future toggle)
- admin-ui/src/components: Icons (30 SVG), Dialog, Toast (ToastProvider
  + useToast), Sidebar (5 nav), Header (page title + ⌘K + user menu),
  CommandPalette, IdleTimeout
- admin-ui/src/pages: Login (split hero), TenantList (KPI + table),
  TenantCreate (form card), ApiKeyList, ApiKeyCreateModal (2-stage with
  copy-gated close), AuditLog (filter + verify banner), MdsStatus
  (KPI + sync banner), KeyManagement (KPI + confirm dialog)
- App.tsx: ToastProvider + ApiErrorBridge + IdleTimeout + Routes
- Fonts: Pretendard (jsdelivr CDN) + Geist (Google Fonts CDN)

No backend changes. Phase 4 envelope + ApiError pattern unchanged.

Followups deferred to Phase 6+:
- Activity feed page (cross-tenant)
- Audit Chain Monitor (per-tenant integrity board)
- Settings 4 subtabs (admin user CRUD, MDS, system, policy)
- Role-based UI (PLATFORM_OPERATOR vs RP_ADMIN)
- Tweaks panel (theme/density/sidebar toggles)
- npm-bundled fonts (CDN → @fontsource)
- admin-ui Vitest unit tests
- i18n (한국어/영어 토글)
EOF
)"

git tag -l "phase-*"
```

Expected: includes `phase-5-admin-ui-redesign-complete`.

---

## Self-Review

### Spec coverage
- §1 Goal — all pages rewritten in OKLCH design + Cmd-K + Idle modal: T1–T13.
- §2 Architecture decisions: all locked in (tokens, fonts, theme fixed, no Tweaks).
- §3.1 tokens.css → T1.
- §3.2 index.html → T1.
- §3.3 Icons → T2.
- §3.4 Layout → T5.
- §3.5 Sidebar → T4.
- §3.6 Header → T4.
- §3.7 Dialog → T3.
- §3.8 Toast → T3.
- §3.9 CommandPalette → T12.
- §3.10 IdleTimeout → T13.
- §3.11 8 pages → T6, T7, T8, T9, T10, T11.
- §3.12 backend unchanged — verified by 150 backend tests green.
- §5 Migration order — T1 (tokens) → T2 (icons) → T3 (modal/toast) → T4 (sidebar/header) → T5 (layout) → T6-T11 (pages) → T12 (cmd-k) → T13 (idle) → T14 (api bridge) → T15 (docs) → T16 (DoD).
- §6 Testing — backend regression, Playwright smoke, manual DoD checklist.
- §7 Risks — addressed inline per task.
- §8 Out-of-scope — none implemented.
- §9 16 tasks delivered.

### Placeholder scan
- No "TBD", "implement later", "fill in" — all steps have concrete code or specific instructions.
- "Pattern same as previous tasks" in T9/T10/T11 (codex review + commit boilerplate) refers to a pattern fully shown in T7/T8 — acceptable for repeated mechanical steps.

### Type consistency
- `ApiError` (TypeScript class) imported in every page that catches errors — T11/T14 (Phase 4 contract).
- `useToast()` hook signature — defined T3, consumed everywhere.
- `Dialog` Props (`open, onClose, title, sub?, children, footer?, wide?, closeOnScrim?`) — defined T3, consumed in T8, T11, T12, T13.
- `Icons.<Name>` shape (`{size?, className?} => JSX`) — defined T2, consumed everywhere.
- Sidebar nav `to` paths match App.tsx routes (T4 + T5 + T12 ITEMS list) — `/tenants`, `/tenants/new`, `/api-keys`, `/keys`, `/mds`, `/audit`.
- API endpoint signatures preserved (Phase 4 envelope contract).
