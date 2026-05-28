# Phase E1 — Admin UI Redesign: 디자인 시스템 + 셸 + Login

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** admin-ui 의 모든 src/ 를 폐기하고 디자인 패키지 (`docs/design-package/project/src/`) 의 tokens.css + icons.jsx + shell.jsx + extras.jsx + tweaks-panel.jsx + app.jsx + pages-1.jsx 의 LoginPage 까지 TypeScript 로 1:1 포팅. Phase A~D 의 shadcn/Tailwind 코드는 통째로 제거. 이 phase 끝나면 로그인까지 작동하고 셸 (사이드바/헤더/Tweaks/CommandPalette/Idle) 이 디자인 스크린샷과 픽셀 일치.

**Architecture:** 디자인 jsx 파일을 함수명·prop·JSX 구조·className·inline style 그대로 보존하며 .tsx 로 옮긴다. TypeScript 타입은 느슨하게 시작 (`any` 허용, strict 일부 완화). `window.MOCK` 같은 글로벌 참조는 import 로 교체. `Icons.Foo` 글로벌은 named import 로. shadcn 없음, Tailwind 없음, raw CSS class (`.btn .card .table .dialog` 등) + inline style.

**Tech Stack:** Vite 5 + React 18 + TypeScript 5 + react-router-dom 6 (라우터 어댑터). 그 외 의존성 없음. (cmdk 도 미사용 — CommandPalette 는 디자인 자체 구현)

---

## Conventions

- **Working directory base**: `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-ui-redesign-e1`
- **admin-ui dir**: 위 base 의 `admin-ui/`
- **빌드**: `cd admin-ui && npm run build`
- **dev server**: `cd admin-ui && npm run dev` (port 5173)
- **디자인 소스**: `docs/design-package/project/src/*.jsx` 와 `*.css`
- **각 Task 의 commit step 직전에 `codex review` 실행** — issue 발견 시 같은 task 안에서 fix 후 다시 review, 그 후 commit. codex 실행 불가하면 보고에 명시하고 skip
- **테스트 최소화 정책**: 자동 테스트 0. brower smoke 만 (Phase 끝)
- commit prefix:
  - 의존성/설정/삭제: `chore(admin-ui): ... (Phase E1.N)`
  - 컴포넌트 신규: `feat(admin-ui): ... (Phase E1.N)`
- 한국어 주석 OK
- TypeScript: `noImplicitAny: false` 허용 (디자인 jsx 의 느슨한 타입 그대로 가져오기 위해)

## File Structure

이 phase 가 종료된 후 admin-ui/src/ 모양:
```
admin-ui/src/
├── main.tsx
├── App.tsx
├── styles/
│   ├── tokens.css
│   └── globals.css
├── icons/
│   └── Icons.tsx
├── shell/
│   ├── Dialog.tsx
│   ├── ToastHost.tsx
│   ├── Sidebar.tsx
│   ├── Header.tsx
│   ├── Breadcrumb.tsx
│   ├── EmptyState.tsx
│   ├── StatusBadge.tsx
│   └── CopyBtn.tsx
├── extras/
│   ├── CommandPalette.tsx
│   └── IdleSessionModal.tsx
├── tweaks/
│   ├── TweaksPanel.tsx
│   ├── useTweaks.ts
│   └── tweaks-utils.ts
├── pages/
│   └── LoginPage.tsx
├── api/
│   ├── client.ts           # 기존 보존
│   └── types.ts            # 기존 보존 (Phase E2 에서 디자인 타입 추가)
├── me/
│   └── MeContext.tsx       # 기존 보존
└── lib/
    ├── cn.ts
    └── formatDateTime.ts   # 기존 보존
```

---

## Task 1: 기존 src/ 통째 삭제 + 의존성 정리 + 빌드 통과

**Files:**
- Delete: `admin-ui/src/` 전체 (단, 다음 3 파일은 작업 디렉토리에 임시 backup 후 task 마지막에 복원: `api/client.ts`, `api/types.ts`, `me/MeContext.tsx`, `lib/formatDateTime.ts`)
- Modify: `admin-ui/package.json` (shadcn/Tailwind/Radix deps 제거)
- Delete: `admin-ui/tailwind.config.ts`
- Delete: `admin-ui/postcss.config.js`
- Delete: `admin-ui/components.json`
- Create: `admin-ui/src/main.tsx` (임시 stub)

- [ ] **Step 1.1: 보존할 4 파일 backup**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-ui-redesign-e1
mkdir -p /tmp/admin-ui-preserve
cp admin-ui/src/api/client.ts /tmp/admin-ui-preserve/
cp admin-ui/src/api/types.ts /tmp/admin-ui-preserve/
cp admin-ui/src/me/MeContext.tsx /tmp/admin-ui-preserve/
cp admin-ui/src/lib/formatDateTime.ts /tmp/admin-ui-preserve/
ls /tmp/admin-ui-preserve/
```

확인: 4 파일 모두 존재.

- [ ] **Step 1.2: admin-ui/src/ 통째 삭제**

```bash
git rm -rf admin-ui/src/
```

- [ ] **Step 1.3: Tailwind/shadcn 설정 파일 삭제**

```bash
git rm admin-ui/tailwind.config.ts admin-ui/postcss.config.js admin-ui/components.json
```

- [ ] **Step 1.4: package.json 정리**

`admin-ui/package.json` 을 다음과 같이 교체:

```json
{
  "name": "admin-ui",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-router-dom": "^6.26.2"
  },
  "devDependencies": {
    "@types/react": "^18.3.3",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.1",
    "typescript": "^5.4.5",
    "vite": "^5.4.6"
  }
}
```

- [ ] **Step 1.5: node_modules 재설치**

```bash
cd admin-ui
rm -rf node_modules package-lock.json
npm install
```

확인: 0 에러. `node_modules/@radix-ui` 같은 디렉토리 없음.

- [ ] **Step 1.6: 보존 파일 복원**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-ui-redesign-e1
mkdir -p admin-ui/src/api admin-ui/src/me admin-ui/src/lib
cp /tmp/admin-ui-preserve/client.ts admin-ui/src/api/
cp /tmp/admin-ui-preserve/types.ts admin-ui/src/api/
cp /tmp/admin-ui-preserve/MeContext.tsx admin-ui/src/me/
cp /tmp/admin-ui-preserve/formatDateTime.ts admin-ui/src/lib/
```

- [ ] **Step 1.7: 임시 main.tsx stub 생성**

`admin-ui/src/main.tsx`:
```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <div style={{ padding: 40, fontFamily: 'system-ui' }}>
      <h1>admin-ui redesign in progress (Phase E1)</h1>
    </div>
  </React.StrictMode>
);
```

- [ ] **Step 1.8: tsconfig.json 정리 — noImplicitAny: false**

`admin-ui/tsconfig.json` Read 후 `compilerOptions` 에 `"noImplicitAny": false` 추가. 기존 `"strict": true` 는 유지 (다른 strict 옵션은 그대로).

- [ ] **Step 1.9: 빌드 통과 확인**

Run: `cd admin-ui && npm run build`
Expected: 0 에러, `dist/` 생성.

- [ ] **Step 1.10: codex review**

Run: `codex review`. issue 발견 시 같은 Task 안에서 fix. codex 실행 불가하면 보고에 "skip" 명시.

- [ ] **Step 1.11: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-ui-redesign-e1
git add -A
git commit -m "chore(admin-ui): src 폐기 + Tailwind/shadcn 의존성 제거 + stub main.tsx (Phase E1.1)"
```

---

## Task 2: tokens.css + globals.css + icons + lib/cn.ts

**Files:**
- Create: `admin-ui/src/styles/tokens.css` (디자인 패키지 복사)
- Create: `admin-ui/src/styles/globals.css`
- Create: `admin-ui/src/icons/Icons.tsx`
- Create: `admin-ui/src/lib/cn.ts`
- Modify: `admin-ui/src/main.tsx` (styles import)

- [ ] **Step 2.1: tokens.css 복사**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-ui-redesign-e1
mkdir -p admin-ui/src/styles
cp docs/design-package/project/src/tokens.css admin-ui/src/styles/tokens.css
```

확인: `admin-ui/src/styles/tokens.css` 파일이 존재하고 `:root` 정의가 있음.

- [ ] **Step 2.2: globals.css 생성**

`admin-ui/src/styles/globals.css`:
```css
@import './tokens.css';

* { box-sizing: border-box; }

html, body, #root { margin: 0; padding: 0; height: 100%; }

body {
  background: var(--bg);
  color: var(--text);
  font-family: var(--font);
  font-size: 14px;
  font-feature-settings: "cv11", "ss01", "ss03";
  -webkit-font-smoothing: antialiased;
  text-rendering: optimizeLegibility;
}

button { font-family: inherit; cursor: pointer; }
input, textarea, select { font-family: inherit; }
code, pre, .mono { font-family: var(--mono); }

::selection { background: var(--accent-soft-2); color: var(--text); }

::-webkit-scrollbar { width: 10px; height: 10px; }
::-webkit-scrollbar-track { background: transparent; }
::-webkit-scrollbar-thumb { background: var(--border-strong); border-radius: 8px; }
::-webkit-scrollbar-thumb:hover { background: var(--text-faint); }

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
```

- [ ] **Step 2.3: Icons.tsx — 디자인 icons.jsx 1:1 포팅**

`docs/design-package/project/src/icons.jsx` 전체를 Read 한 다음 `admin-ui/src/icons/Icons.tsx` 로 옮긴다. 변환 규칙:

- 파일 상단의 `/* global ... */` 주석 제거
- 첫 줄에 `import React from 'react';` 추가
- 마지막 `window.Icons = Icons;` 또는 `const Icons = { ... }` 같은 글로벌 등록 코드 제거
- 대신 마지막에 `export const Icons = { Foo, Bar, ... };` 와 같은 형태로 named export 추가
- 각 컴포넌트는 `function Foo({ size = 16, className }) { return <svg ... /> }` 형태 — 기본 SVG 컴포넌트. TypeScript 시그니처 추가: `function Foo({ size = 16, className }: { size?: number; className?: string })`
- 또한 default export 도 같이: `export default Icons;`

확인 방법: `docs/design-package/project/src/icons.jsx` 의 마지막 `const Icons = {` 줄을 보고 정확히 같은 키 목록을 export 함.

- [ ] **Step 2.4: lib/cn.ts**

`admin-ui/src/lib/cn.ts`:
```ts
export type ClassValue = string | false | null | undefined;

export function cn(...xs: ClassValue[]): string {
  return xs.filter(Boolean).join(' ');
}
```

- [ ] **Step 2.5: main.tsx 에 styles import**

`admin-ui/src/main.tsx` 수정:
```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import './styles/globals.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <div style={{ padding: 40 }}>
      <h1>admin-ui redesign (Phase E1.2)</h1>
      <p style={{ color: 'var(--text-mute)' }}>tokens.css imported. accent: <span style={{ color: 'var(--accent)' }}>blue text</span></p>
    </div>
  </React.StrictMode>
);
```

- [ ] **Step 2.6: 빌드 + 빠른 시각 확인 (선택)**

Run: `cd admin-ui && npm run build`
Expected: 0 에러.

(선택) `npm run dev` → http://localhost:5173 → 토큰 색상 적용 확인.

- [ ] **Step 2.7: codex review**

Run: `codex review`. issue fix.

- [ ] **Step 2.8: Commit**

```bash
git add admin-ui/src/styles/ admin-ui/src/icons/ admin-ui/src/lib/cn.ts admin-ui/src/main.tsx
git commit -m "feat(admin-ui): tokens.css + globals.css + Icons + cn (Phase E1.2)"
```

---

## Task 3: shell primitives — Dialog / ToastHost / Breadcrumb / EmptyState / StatusBadge / CopyBtn

**Files (Create)**:
- `admin-ui/src/shell/Dialog.tsx`
- `admin-ui/src/shell/ToastHost.tsx`
- `admin-ui/src/shell/Breadcrumb.tsx`
- `admin-ui/src/shell/EmptyState.tsx`
- `admin-ui/src/shell/StatusBadge.tsx`
- `admin-ui/src/shell/CopyBtn.tsx`

각 파일은 `docs/design-package/project/src/shell.jsx` 의 해당 함수를 추출해서 .tsx 로 옮김.

- [ ] **Step 3.1: shell.jsx 전체 Read 해서 6 함수 위치 확인**

확인 메서드 위치:
- `ToastHost` (line ~7) + `useToast` hook
- `Dialog` (line ~38)
- `Breadcrumb` (line ~259)
- `EmptyState` (line ~278)
- `StatusBadge` (line ~293)
- `CopyBtn` (line ~304)

- [ ] **Step 3.2: ToastHost.tsx 작성**

디자인 `shell.jsx` 의 ToastHost + useToast 부분을 그대로 옮김. 변환 규칙:
- 상단 `/* global ... */` 제거
- `const { useState, ..., createContext, useContext, useCallback } = React;` → `import { useState, useEffect, useRef, createContext, useContext, useCallback } from 'react';`
- `Icons.X`, `Icons.Alert`, `Icons.Check` → `import { Icons } from '@/icons/Icons';` 후 `Icons.X` 등 그대로
- `ToastCtx` 와 `ToastHost`, `useToast` 모두 named export
- TypeScript 타입: `type ToastInput = { kind?: 'ok' | 'err' | 'warn'; title?: string; message?: string; traceId?: string; duration?: number }`, useToast() 반환 함수가 그 input 받음

코드 (그대로 옮긴 결과):
```tsx
import { useState, useEffect, useRef, createContext, useContext, useCallback, ReactNode } from 'react';
import { Icons } from '@/icons/Icons';

export type ToastInput = {
  kind?: 'ok' | 'err' | 'warn';
  title?: string;
  message?: string;
  traceId?: string;
  duration?: number;
};

type ToastItem = ToastInput & { id: string };

const ToastCtx = createContext<((t: ToastInput) => void) | null>(null);

export function ToastHost({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const push = useCallback((t: ToastInput) => {
    const id = Math.random().toString(36).slice(2);
    setToasts((arr) => [...arr, { id, ...t }]);
    setTimeout(() => setToasts((arr) => arr.filter((x) => x.id !== id)), t.duration || 4200);
  }, []);
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="toast-rack">
        {toasts.map((t) => (
          <div key={t.id} className="toast" role="status">
            <div className={`toast__icon toast__icon--${t.kind || 'ok'}`}>
              {t.kind === 'err' ? <Icons.X size={11} /> : t.kind === 'warn' ? <Icons.Alert size={11} /> : <Icons.Check size={11} />}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="toast__title">{t.title}</div>
              {t.message && <div className="toast__sub">{t.message}</div>}
              {t.traceId && <div className="toast__trace">traceId · {t.traceId}</div>}
            </div>
            <button className="btn btn--ghost btn--xs" onClick={() => setToasts((a) => a.filter((x) => x.id !== t.id))} aria-label="닫기"><Icons.X size={12} /></button>
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

export function useToast() {
  const ctx = useContext(ToastCtx);
  if (!ctx) throw new Error('useToast must be used inside <ToastHost>');
  return ctx;
}
```

**중요**: `Icons.X` 등 정확한 키가 `Icons.tsx` 의 export 에 있는지 확인. 없으면 그 단계에서 추가.

- [ ] **Step 3.3: Dialog.tsx 작성**

디자인 `shell.jsx` 의 Dialog (line ~38) 를 그대로 옮김. JSX 구조, className, props 보존:

`docs/design-package/project/src/shell.jsx` 의 line 38-77 영역을 Read 한 다음, 동일한 시그니처/구조로 .tsx 작성. 시그니처:
```ts
type DialogProps = {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  sub?: ReactNode;
  children?: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
  closeOnScrim?: boolean;
};
```

- 디자인 jsx 가 `useEffect` 로 Esc keyboard 처리, scrim onClick → onClose (closeOnScrim 일 때) 등
- 코드 그대로 옮기되 React import + ReactNode 타입 추가

- [ ] **Step 3.4: Breadcrumb.tsx, EmptyState.tsx, StatusBadge.tsx, CopyBtn.tsx 작성**

각각 디자인 `shell.jsx` 의 해당 함수를 그대로 옮김. 변환 규칙은 동일 (React import, Icons named import, 시그니처 타입 추가). 작성 후 컴파일 확인.

각 함수의 시그니처:
- `Breadcrumb({ items }: { items: { label: ReactNode; onClick?: () => void }[] })`
- `EmptyState({ icon = 'Sparkles', title, description, action }: { icon?: string; title: ReactNode; description?: ReactNode; action?: ReactNode })`
- `StatusBadge({ status }: { status: string })` — class 변환 로직 그대로
- `CopyBtn({ value, label = '복사' }: { value: string; label?: string })`

- [ ] **Step 3.5: main.tsx 에 ToastHost wrap (sanity)**

`admin-ui/src/main.tsx` 수정:
```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import './styles/globals.css';
import { ToastHost } from '@/shell/ToastHost';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ToastHost>
      <div style={{ padding: 40 }}>
        <h1>shell primitives (Phase E1.3)</h1>
      </div>
    </ToastHost>
  </React.StrictMode>
);
```

- [ ] **Step 3.6: 빌드 확인**

```bash
cd admin-ui && npm run build
```
0 에러.

- [ ] **Step 3.7: codex review**

Run: `codex review`. fix.

- [ ] **Step 3.8: Commit**

```bash
git add admin-ui/src/shell/ admin-ui/src/main.tsx
git commit -m "feat(admin-ui): shell primitives (Dialog/ToastHost/Breadcrumb/EmptyState/StatusBadge/CopyBtn) (Phase E1.3)"
```

---

## Task 4: Sidebar + Header

**Files (Create)**:
- `admin-ui/src/shell/Sidebar.tsx`
- `admin-ui/src/shell/Header.tsx`

- [ ] **Step 4.1: shell.jsx 의 Sidebar (line ~78-171) Read**

확인: Sidebar 함수 시그니처, NavBtn 보조 함수, 디자인의 nav items 정의 위치, chain status carousel 부분, role 분기 패턴.

- [ ] **Step 4.2: Sidebar.tsx 작성 — 디자인 1:1 포팅**

`Sidebar.tsx`:
- 시그니처: `Sidebar({ me, currentRoute, onNavigate, tenant, sidebarMode = 'labels' })` — props 타입은 `any` 또는 명시:
  ```ts
  type SidebarProps = {
    me: { role: string; tenantId?: string | null; email: string; displayName?: string };
    currentRoute: { name: string; tenantId?: string; tab?: string };
    onNavigate: (route: SidebarProps['currentRoute']) => void;
    tenant?: { id: string; name: string; slug: string } | null;
    sidebarMode?: 'labels' | 'icons' | 'collapsed';
  };
  ```
- `NavBtn` 보조 함수 같은 파일 안에 정의 (private)
- 디자인 jsx 의 BrandMark 컴포넌트 사용 → `Icons.tsx` 에 BrandMark 있으면 사용, 없으면 inline SVG (디자인 코드의 BrandMark 부분 그대로 inline 화)
- chain status carousel — 디자인 jsx 의 정적 텍스트 그대로 ("AUDIT CHAIN OK · 마지막 검증 2분 전 · 1,284,920 행"). Phase E3 에서 실시간으로 교체.
- 디자인 코드 거의 그대로. 변환만 (React import, Icons import).

- [ ] **Step 4.3: Header.tsx 작성**

디자인 `shell.jsx` 의 Header (line ~173-244) + MenuItem (line ~245-258) 그대로 옮김.

- 시그니처: `Header({ me, onLogout, onSwitchRole, breadcrumb, onOpenPalette })`
- breadcrumb prop 은 `{ label, onClick? }[]` (Task 3 의 Breadcrumb 컴포넌트와 호환)
- ⌘K 버튼 + 검색바 (placeholder text "tenant, credential, audit ID 검색...") 우상단
- role badge (PLATFORM = 보라색, RP_ADMIN = teal). 디자인 코드 그대로
- 사용자 메뉴 dropdown (Logout / Switch role) — 디자인 코드 그대로

- [ ] **Step 4.4: 빌드 확인**

```bash
cd admin-ui && npm run build
```

- [ ] **Step 4.5: codex review**

- [ ] **Step 4.6: Commit**

```bash
git add admin-ui/src/shell/Sidebar.tsx admin-ui/src/shell/Header.tsx
git commit -m "feat(admin-ui): Sidebar + Header (디자인 1:1, chain status carousel 정적) (Phase E1.4)"
```

---

## Task 5: TweaksPanel + useTweaks + tweaks-utils

**Files (Create)**:
- `admin-ui/src/tweaks/TweaksPanel.tsx`
- `admin-ui/src/tweaks/useTweaks.ts`
- `admin-ui/src/tweaks/tweaks-utils.ts`

- [ ] **Step 5.1: tweaks-utils.ts — shade/withAlpha 함수**

디자인 `app.jsx` 의 line ~199-216 의 두 utility 함수를 그대로 옮김.

`admin-ui/src/tweaks/tweaks-utils.ts`:
```ts
export function shade(hex: string, percent: number): string {
  const h = hex.replace('#', '');
  const num = parseInt(h, 16);
  let r = (num >> 16) + Math.round(2.55 * percent);
  let g = ((num >> 8) & 0xff) + Math.round(2.55 * percent);
  let b = (num & 0xff) + Math.round(2.55 * percent);
  r = Math.max(0, Math.min(255, r));
  g = Math.max(0, Math.min(255, g));
  b = Math.max(0, Math.min(255, b));
  return `rgb(${r},${g},${b})`;
}

export function withAlpha(hex: string, a: number): string {
  const h = hex.replace('#', '');
  const num = parseInt(h, 16);
  const r = num >> 16;
  const g = (num >> 8) & 0xff;
  const b = num & 0xff;
  return `rgba(${r},${g},${b},${a})`;
}
```

- [ ] **Step 5.2: useTweaks.ts — 디자인 app.jsx 의 hook 패턴 + localStorage**

`admin-ui/src/tweaks/useTweaks.ts`:
```ts
import { useState, useEffect, useCallback } from 'react';
import { shade, withAlpha } from './tweaks-utils';

export type Tweaks = {
  theme: 'light' | 'dark';
  density: 'compact' | 'comfortable';
  tableStyle: 'lines' | 'striped' | 'borderless';
  sidebarMode: 'labels' | 'icons' | 'collapsed';
  accent: string; // hex color
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
    root.style.setProperty('--accent-soft', withAlpha(t.accent, 0.12));
    root.style.setProperty('--accent-soft-2', withAlpha(t.accent, 0.22));
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
```

- [ ] **Step 5.3: TweaksPanel.tsx — 디자인 tweaks-panel.jsx 1:1 포팅**

`docs/design-package/project/src/tweaks-panel.jsx` 전체 Read. 그 안의 `TweaksPanel`, `TweakSection`, `TweakRadio`, `TweakColor`, `TweakToggle` 함수를 모두 `admin-ui/src/tweaks/TweaksPanel.tsx` 안에 옮김.

- 시그니처: `<TweaksPanel title="Tweaks">{children}</TweaksPanel>`
- 자식 sub-components: `TweakSection`, `TweakRadio`, `TweakColor`, `TweakToggle` 모두 export
- 디자인 코드의 useState (open/closed), 위치 (fixed bottom-right), trigger 버튼 등 그대로

- [ ] **Step 5.4: 빌드 확인**

```bash
cd admin-ui && npm run build
```

- [ ] **Step 5.5: codex review**

- [ ] **Step 5.6: Commit**

```bash
git add admin-ui/src/tweaks/
git commit -m "feat(admin-ui): TweaksPanel + useTweaks + accent color picker (Phase E1.5)"
```

---

## Task 6: CommandPalette + IdleSessionModal

**Files (Create)**:
- `admin-ui/src/extras/CommandPalette.tsx`
- `admin-ui/src/extras/IdleSessionModal.tsx`

- [ ] **Step 6.1: extras.jsx 전체 Read**

확인 위치:
- `IdleSessionModal` (line ~5-65)
- `CommandPalette` (line ~66-217)

- [ ] **Step 6.2: IdleSessionModal.tsx 작성**

디자인 `extras.jsx` 의 IdleSessionModal 그대로 옮김. 시그니처: `IdleSessionModal({ onExtend, onLogout })`. 30분 idle + 60s 카운트다운 (디자인 코드 그대로). 디자인은 `Dialog` 컴포넌트를 사용하지 않고 자체 portal/scrim 패턴이면 그대로 유지.

- [ ] **Step 6.3: CommandPalette.tsx 작성**

디자인 `extras.jsx` 의 CommandPalette 그대로 옮김. 시그니처: `CommandPalette({ open, onClose, me, onNavigate, onAction })`. ⌘K 단축키 listener, 검색 input, 페이지 nav 그룹, tenant 점프 그룹, actions 그룹 모두 디자인 코드 그대로.

검색 결과는 일단 정적 (디자인의 mock 데이터 사용 — `window.MOCK` 참조가 있다면 일단 빈 배열로 처리, Phase E3 에서 fixture 연결).

- [ ] **Step 6.4: 빌드 확인**

```bash
cd admin-ui && npm run build
```

- [ ] **Step 6.5: codex review**

- [ ] **Step 6.6: Commit**

```bash
git add admin-ui/src/extras/
git commit -m "feat(admin-ui): CommandPalette + IdleSessionModal (Phase E1.6)"
```

---

## Task 7: LoginPage (디자인 pages-1.jsx 의 LoginPage)

**Files (Create)**:
- `admin-ui/src/pages/LoginPage.tsx`

- [ ] **Step 7.1: pages-1.jsx 의 LoginPage 영역 Read (line 5-141)**

확인: LoginPage 함수 시그니처 `LoginPage({ onLogin })`, role 카드 마크업, 좌측 marketing 패널, 데모 prefill 패턴.

- [ ] **Step 7.2: LoginPage.tsx 작성 — 디자인 1:1 + 실 서버 호출 연결**

기본 골격은 디자인 jsx 그대로. 단 onLogin 호출 시:
- 디자인은 `onLogin(roleKey)` 로 mock user 선택
- 우리는 실제 `api.loginForm(email, password)` 호출 후 `/admin/api/me` 응답에서 me 받아 onLogin(me) 호출

```tsx
import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { api } from '@/api/client';
import { useToast } from '@/shell/ToastHost';

type Me = { email: string; role: string; tenantId?: string | null };

export default function LoginPage({ onLogin }: { onLogin: (me: Me) => void }) {
  // 디자인 jsx 의 모든 useState/JSX 마크업을 그대로 옮김
  // 단, 폼 submit 핸들러만 실 호출:
  //   await api.loginForm(email, password);
  //   const me = await api.get<Me>('/admin/api/me');
  //   onLogin(me);
  // role 카드 클릭 시 dev profile prefill: /admin/api/profile 응답 보고 active==='dev' 면
  //   role === 'platform' → email='alice@crosscert.com', password='alice-temp-pw'
  //   role === 'rp'       → email='bob@crosscert.com',   password='bob-temp-pw'
  // (디자인의 데모 카드 의도 보존)

  // ... (디자인 jsx 의 마크업 — 좌측 marketing + 우측 form + role 카드 + 활성 KPI 3종)
}
```

**중요**:
- 디자인 마크업 보존 (좌측 보라색 패턴 + "v1.0 · multi-tenant FIDO2 server" + KPI 3종 + 한국어 카피)
- 우측 폼 (이메일/비밀번호 + 데모 role 선택 + 로그인 버튼 + idle 안내 banner)
- 디자인의 정확한 className 사용

- [ ] **Step 7.3: 빌드 확인**

```bash
cd admin-ui && npm run build
```

- [ ] **Step 7.4: codex review**

- [ ] **Step 7.5: Commit**

```bash
git add admin-ui/src/pages/LoginPage.tsx
git commit -m "feat(admin-ui): LoginPage (디자인 1:1, 실 서버 로그인 호출) (Phase E1.7)"
```

---

## Task 8: App.tsx + main.tsx + react-router-dom 어댑터

**Files (Create/Modify)**:
- `admin-ui/src/App.tsx` (신규)
- `admin-ui/src/main.tsx` (전면 교체)

- [ ] **Step 8.1: 디자인 app.jsx Read 후 라우터 어댑팅 계획**

디자인 `app.jsx` 의 App 함수는:
- `route = { name, tenantId, tab }` self state
- `useEffect` 로 hash 변경 감지

우리 어댑터:
- `useLocation()` + `useNavigate()` 로 변환
- route → URL: `/tenants`, `/tenants/:id?tab=...`, `/activity`, `/audit-chain`, `/settings`, `/login`
- URL → route: pathname 매칭 + searchParams.get('tab')

- [ ] **Step 8.2: App.tsx 작성**

```tsx
import { useState, useEffect, useMemo, useCallback } from 'react';
import { Routes, Route, useLocation, useNavigate, Navigate } from 'react-router-dom';
import { ToastHost } from '@/shell/ToastHost';
import { Sidebar } from '@/shell/Sidebar';
import { Header } from '@/shell/Header';
import { IdleSessionModal } from '@/extras/IdleSessionModal';
import { CommandPalette } from '@/extras/CommandPalette';
import { TweaksPanel, TweakSection, TweakRadio, TweakColor } from '@/tweaks/TweaksPanel';
import { useTweaks } from '@/tweaks/useTweaks';
import LoginPage from '@/pages/LoginPage';
import { MeProvider, useMe } from '@/me/MeContext';
import { api } from '@/api/client';

type Me = { email: string; role: 'PLATFORM_OPERATOR' | 'RP_ADMIN'; tenantId?: string | null; displayName?: string };
type Route = { name: 'tenants'; } | { name: 'tenant'; tenantId: string; tab: string } | { name: 'activity' } | { name: 'audit-chain' } | { name: 'settings' };

function urlToRoute(pathname: string, search: URLSearchParams): Route {
  if (pathname.startsWith('/tenants/')) {
    const id = pathname.split('/')[2];
    return { name: 'tenant', tenantId: id, tab: search.get('tab') || 'overview' };
  }
  if (pathname === '/activity') return { name: 'activity' };
  if (pathname === '/audit-chain') return { name: 'audit-chain' };
  if (pathname === '/settings') return { name: 'settings' };
  return { name: 'tenants' };
}

function routeToUrl(r: Route): string {
  if (r.name === 'tenants') return '/tenants';
  if (r.name === 'tenant') return `/tenants/${r.tenantId}?tab=${r.tab}`;
  if (r.name === 'activity') return '/activity';
  if (r.name === 'audit-chain') return '/audit-chain';
  return '/settings';
}

function buildBreadcrumb(route: Route, tenant: any | null, me: Me, navigate: (r: Route) => void) {
  const items: { label: string; onClick?: () => void }[] = [];
  if (route.name === 'tenants' || (route.name === 'tenant' && me.role === 'PLATFORM_OPERATOR')) {
    items.push({ label: 'Tenants', onClick: route.name === 'tenants' ? undefined : () => navigate({ name: 'tenants' }) });
  }
  if (route.name === 'tenant' && tenant) {
    items.push({ label: tenant.name, onClick: () => navigate({ name: 'tenant', tenantId: tenant.id, tab: 'overview' }) });
    const tabName: Record<string, string> = { overview: '개요', webauthn: 'WebAuthn', aaguid: 'AAGUID 정책', apikeys: 'API Keys', credentials: 'Credentials', audit: 'Audit Logs', funnel: 'Funnel' };
    items.push({ label: tabName[route.tab] || route.tab });
  } else if (route.name === 'activity') {
    items.push({ label: 'Activity' });
  } else if (route.name === 'audit-chain') {
    items.push({ label: 'Audit Chain Monitor' });
  } else if (route.name === 'settings') {
    items.push({ label: '설정' });
  }
  return items;
}

function AuthenticatedApp({ me, onLogout }: { me: Me; onLogout: () => void }) {
  const location = useLocation();
  const navigate = useNavigate();
  const route = useMemo(() => urlToRoute(location.pathname, new URLSearchParams(location.search)), [location]);

  const setRoute = useCallback((r: Route) => { navigate(routeToUrl(r)); }, [navigate]);

  const [t, setTweak] = useTweaks();
  const [paletteOpen, setPaletteOpen] = useState(false);

  useEffect(() => {
    function k(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen((v) => !v);
      }
    }
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, []);

  const tenant = null; // Phase E2 에서 tenant 로딩 추가
  const breadcrumb = buildBreadcrumb(route, tenant, me, setRoute);

  async function handleSwitchRole() {
    // dev 데모용 — Phase E3 에서 구현
  }

  function paletteAction(kind: string) {
    if (kind === 'logout') onLogout();
    if (kind === 'switch-role') handleSwitchRole();
    if (kind === 'new-tenant') setRoute({ name: 'tenants' });
  }

  return (
    <div className="app" style={{ gridTemplateAreas: '"sidebar content"' }}>
      <Sidebar me={me} currentRoute={route} onNavigate={setRoute} tenant={tenant} sidebarMode={t.sidebarMode} />
      <div className="content" style={{ gridArea: 'content' }}>
        <Header me={me} onLogout={onLogout} onSwitchRole={handleSwitchRole} breadcrumb={breadcrumb} onOpenPalette={() => setPaletteOpen(true)} />
        <main style={{ padding: 24 }}>
          {/* Phase E2 에서 페이지 라우팅 추가 */}
          <div className="card"><div className="card__body">
            <div className="card__title">현재 라우트: <code>{location.pathname}{location.search}</code></div>
            <div className="muted" style={{ marginTop: 8 }}>페이지 구현은 Phase E2 에서 진행됩니다.</div>
          </div></div>
        </main>
      </div>
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} me={me} onNavigate={setRoute} onAction={paletteAction} />
      <IdleSessionModal onExtend={() => { /* refresh /me */ }} onLogout={onLogout} />
      <TweaksPanel title="Tweaks">
        <TweakSection label="테마" />
        <TweakRadio label="모드" value={t.theme} onChange={(v) => setTweak('theme', v as Tweaks['theme'])} options={[{value: 'light', label: 'Light'}, {value: 'dark', label: 'Dark'}]} />
        <TweakColor label="Accent" value={t.accent} onChange={(v) => setTweak('accent', v)} options={['#4f46e5', '#5b5bd6', '#7c3aed', '#0f766e', '#db7706']} />
        <TweakSection label="레이아웃" />
        <TweakRadio label="밀도" value={t.density} onChange={(v) => setTweak('density', v as Tweaks['density'])} options={[{value: 'compact', label: 'Compact'}, {value: 'comfortable', label: 'Comfort'}]} />
        <TweakRadio label="테이블" value={t.tableStyle} onChange={(v) => setTweak('tableStyle', v as Tweaks['tableStyle'])} options={[{value: 'lines', label: '구분선'}, {value: 'striped', label: '줄무늬'}, {value: 'borderless', label: '보더리스'}]} />
        <TweakRadio label="사이드바" value={t.sidebarMode} onChange={(v) => setTweak('sidebarMode', v as Tweaks['sidebarMode'])} options={[{value: 'labels', label: '라벨'}, {value: 'icons', label: '아이콘만'}]} />
      </TweaksPanel>
    </div>
  );
}

import type { Tweaks } from '@/tweaks/useTweaks';

function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // 초기 me 로드 시도 (이미 세션 있으면)
    api.get<Me>('/admin/api/me')
      .then(setMe)
      .catch(() => { /* 인증 안됨 — login 페이지로 */ })
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div style={{ padding: 40, color: 'var(--text-mute)' }}>Loading…</div>;

  if (!me) {
    return (
      <ToastHost>
        <LoginPage onLogin={setMe} />
      </ToastHost>
    );
  }

  return (
    <ToastHost>
      <Routes>
        <Route path="*" element={<AuthenticatedApp me={me} onLogout={async () => {
          try { await api.post('/admin/logout', {}); } catch {}
          setMe(null);
        }} />} />
      </Routes>
    </ToastHost>
  );
}

export default App;
```

- [ ] **Step 8.3: main.tsx 전면 교체**

```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import './styles/globals.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>
);
```

- [ ] **Step 8.4: 빌드 확인**

```bash
cd admin-ui && npm run build
```
0 에러.

- [ ] **Step 8.5: codex review**

Run: `codex review`. fix.

- [ ] **Step 8.6: Commit**

```bash
git add admin-ui/src/App.tsx admin-ui/src/main.tsx
git commit -m "feat(admin-ui): App.tsx + main.tsx + react-router-dom 어댑터 (Phase E1.8)"
```

---

## Task 9: 빌드 + 브라우저 smoke 체크리스트

이번 phase 가 자동 테스트 0 정책이므로, **수동 smoke** 가 phase 종료 게이트.

- [ ] **Step 9.1: admin-app 재기동 (Phase 변경 반영)**

(서버 변경 없음. admin-app 이 admin-ui dist 를 정적 서빙하므로 빌드만 신규 dist 반영.)

```bash
cd admin-ui && npm run build
```

dist 갱신 확인.

- [ ] **Step 9.2: 브라우저 smoke**

브라우저로 http://localhost:8081/admin/login 접속:

체크리스트:
- [ ] Login 페이지 — 디자인 `01-login.png` 스크린샷과 픽셀 거의 일치
  - 좌측 보라색 marketing 패널 + "v1.0 · multi-tenant FIDO2 server" + KPI 3종
  - 우측 폼: 이메일/비밀번호 + 데모 role 카드 2개 + 로그인 버튼 + idle 안내 banner
- [ ] alice@crosscert.com / alice-temp-pw 로 로그인 성공
- [ ] 사이드바 표시: 브랜드 헤더 + nav (Tenants/Activity/Audit Chain/설정) + 하단 chain status carousel ("AUDIT CHAIN OK · 마지막 검증 N분 전")
- [ ] 헤더 표시: ⌘K 검색바 + role badge (PLATFORM, 보라색) + 사용자 메뉴
- [ ] ⌘K → CommandPalette 오픈
- [ ] TweaksPanel 우하단 → 5 옵션 모두 즉시 반영:
  - Theme light/dark 토글
  - Density compact/comfortable
  - Table style 3종
  - Sidebar labels/icons
  - Accent color picker (5개 swatch + custom color)
- [ ] 새로고침 후 Tweaks 설정 유지 확인 (localStorage)
- [ ] 사용자 메뉴 → Logout → /login 으로 이동

- [ ] **Step 9.3: 디자인 스크린샷과 시각 비교**

`docs/design-package/project/screenshots/01-login.png` 를 옆에 두고 픽셀 일치 정도 확인. 큰 어긋남 (예: 색상 완전 다름, 폰트 미적용, 레이아웃 깨짐) 발견되면 해당 컴포넌트의 디자인 jsx 다시 정독 → 누락된 className/style 보강.

확인 사항:
- [ ] 좌측 marketing 패널의 보라색 그라데이션 + radial 패턴
- [ ] 우측 form 의 필드 간격, 버튼 색
- [ ] role 카드의 active state 색상

- [ ] **Step 9.4: 가벼운 commit (선택)**

smoke 도중 발견된 작은 fix 가 있으면 별도 commit:
```bash
git add ...
git commit -m "fix(admin-ui): smoke 발견 fix (Phase E1.9)"
```

발견 없으면 commit 안 함.

- [ ] **Step 9.5: Phase E1 완료 보고**

main 으로 돌아가 merge 준비. 보고 형식:
- 모든 task commits 목록
- smoke 통과 여부
- 알려진 잔여 이슈 (있다면)

---

## Self-Review

### Spec 커버리지 (Phase E1 부분만)
- [x] admin-ui/src/ 통째 삭제 → Task 1
- [x] Tailwind/shadcn 의존성 제거 → Task 1
- [x] tokens.css + globals.css → Task 2
- [x] Icons → Task 2
- [x] Dialog/ToastHost/Breadcrumb/EmptyState/StatusBadge/CopyBtn → Task 3
- [x] Sidebar (chain status carousel 정적) + Header → Task 4
- [x] TweaksPanel + useTweaks + accent color picker → Task 5
- [x] CommandPalette + IdleSessionModal → Task 6
- [x] LoginPage (디자인 1:1 + 실 서버 호출) → Task 7
- [x] App.tsx + react-router-dom 어댑터 → Task 8
- [x] 브라우저 smoke → Task 9
- [x] api/client.ts, me/MeContext.tsx, lib/formatDateTime.ts, api/types.ts 보존 → Task 1 step 1.1, 1.6

### Placeholder scan
- Task 7 의 LoginPage 코드가 "디자인 jsx 의 모든 useState/JSX 마크업을 그대로 옮김" 으로 시작 — 구체적 코드 대신 가이드만. 이는 디자인 jsx 가 137 줄이고 그대로 옮기는 게 핵심이므로 implementer 가 `pages-1.jsx` 를 직접 Read 해서 옮기는 게 가장 정확. 마크업의 핵심 부분 (좌측 marketing + role 카드 + 우측 form) 은 명시함. 허용.
- 그 외 placeholder 없음.

### Type 일관성
- `Me` 타입 정의가 App.tsx, LoginPage.tsx 에서 일치 (`{email, role, tenantId?, displayName?}`)
- `Route` 타입 App.tsx 내부에서만 사용
- `Tweaks` 타입 useTweaks.ts 에서 정의, TweaksPanel 의 onChange 콜백에서 cast
- `ToastInput` 타입 ToastHost.tsx 정의, 사용처에서 import

### Scope 검증
- 9 task → 각각 5~20 분 분량
- 자동 테스트 0 (사용자 정책)
- 각 task 끝의 codex review → fix → commit 루프 명시

---

## 실행 가이드 요약

1. **각 Task 의 step 을 순서대로 진행**
2. **각 Task 끝의 codex review step 에서 `codex review` 실행**, issue 발견 시 같은 task 안에서 fix 후 다시 review
3. **commit 은 codex review 통과 후**
4. **빌드 실패 시 task 끝나기 전에 반드시 해결**
5. **Task 9 의 브라우저 smoke 통과 후에만 main merge**
6. **`window.MOCK.*`, `BrandMark` 같은 디자인 글로벌 참조는 import 또는 inline 처리**
7. **디자인 jsx 의 함수명·prop·className·inline style 은 절대 변경하지 않는다**
