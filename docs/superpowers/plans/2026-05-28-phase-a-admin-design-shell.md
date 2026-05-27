# Phase A — Admin Console 디자인 토큰 + 셸 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** admin-ui 에 Tailwind + shadcn/ui 를 도입하고, 디자인 패키지(`docs/design-package/`)의 Sidebar/Header/Layout 구조와 TweaksPanel/Command Palette/Idle Dialog 를 React 컴포넌트로 이식한다. 기존 페이지의 기능은 변화 없이 새 셸/디자인 시스템 위에서 동작해야 한다.

**Architecture:**
- Tailwind 는 디자인 시스템의 **유틸리티 + reset** 만 담당. 색상/타이포/spacing 토큰은 `src/styles/tokens.css` 의 CSS 변수를 **Tailwind theme 에서 참조**하여 단일 진실 유지.
- shadcn/ui 컴포넌트는 `src/components/ui/` 에 생성. 색상/radius/font 는 토큰 변수에 매핑 — 라이트/다크/density 토글 시 자동 반응.
- 셸은 `src/shell/` 로 신규 디렉토리 분리 (기존 `src/components/Layout.tsx`, `Sidebar.tsx`, `Header.tsx` 이관·재작성).
- TweaksProvider 는 `src/app/providers/TweaksProvider.tsx`. localStorage `passkey-admin:tweaks` 키, `document.documentElement` 의 `data-theme`/`data-density`/`data-accent`/`data-tablestyle`/`data-sidebar` 속성으로 즉시 반영.
- Toast 는 sonner 로 교체 (기존 Toast context 와 동일 API 유지하여 호출부 영향 최소화).
- 기존 페이지 마크업은 **이미 디자인 패키지의 CSS 클래스를 사용**(`.btn`, `.card`, `.table`, `.dialog`, `.banner` 등)하므로 페이지 자체 수정은 최소. 셸 wrapping 만 교체.

**Tech Stack:** React 18 + Vite 5 + TypeScript 5 + Tailwind CSS 3 + shadcn/ui + cmdk + sonner + Pretendard (이미 시스템 fallback 으로 토큰 등록됨)

---

## File Structure

**Create:**
- `admin-ui/tailwind.config.ts` — Tailwind 설정 (토큰 변수 매핑)
- `admin-ui/postcss.config.js` — PostCSS (Tailwind + autoprefixer)
- `admin-ui/components.json` — shadcn config
- `admin-ui/src/lib/utils.ts` — shadcn `cn()` 유틸
- `admin-ui/src/app/providers/TweaksProvider.tsx`
- `admin-ui/src/app/providers/AppProviders.tsx` — 모든 Provider 컴포지션
- `admin-ui/src/shell/Layout.tsx` — 새 셸 레이아웃
- `admin-ui/src/shell/Sidebar.tsx` — role 분기 + 5종 sidebar mode
- `admin-ui/src/shell/Header.tsx` — breadcrumb + role badge + ⌘K trigger + 사용자 메뉴
- `admin-ui/src/shell/TweaksPanel.tsx` — 우상단 고정 토글 패널
- `admin-ui/src/shell/CommandPalette.tsx` — cmdk 기반
- `admin-ui/src/shell/IdleTimeoutDialog.tsx` — Dialog 로 리팩터
- `admin-ui/src/components/ui/button.tsx` — shadcn (토큰 매핑)
- `admin-ui/src/components/ui/dialog.tsx` — shadcn
- `admin-ui/src/components/ui/input.tsx` — shadcn
- `admin-ui/src/components/ui/label.tsx` — shadcn
- `admin-ui/src/components/ui/badge.tsx` — shadcn
- `admin-ui/src/components/ui/switch.tsx` — shadcn
- `admin-ui/src/components/ui/dropdown-menu.tsx` — shadcn
- `admin-ui/src/components/ui/tabs.tsx` — shadcn
- `admin-ui/src/components/ui/popover.tsx` — shadcn
- `admin-ui/src/components/ui/tooltip.tsx` — shadcn
- `admin-ui/src/components/ui/sonner.tsx` — sonner toaster wrapper

**Modify:**
- `admin-ui/package.json` — Tailwind, shadcn deps, sonner, cmdk, lucide-react, class-variance-authority, clsx, tailwind-merge
- `admin-ui/tsconfig.json` — paths `@/*` → `src/*` (shadcn 관례)
- `admin-ui/vite.config.ts` — `@` alias 등록
- `admin-ui/src/styles/tokens.css` — Tailwind `@layer base` 흡수 (기존 토큰/유틸 유지)
- `admin-ui/src/main.tsx` — globals.css import + AppProviders wrap
- `admin-ui/src/App.tsx` — 새 Layout 사용
- `admin-ui/src/components/Toast.tsx` — 기존 API 시그니처는 유지하되 내부를 sonner 로 위임
- `admin-ui/src/components/IdleTimeout.tsx` — 신 IdleTimeoutDialog 로 교체 (re-export shim 만 남기거나 삭제)
- `admin-ui/src/components/Layout.tsx`, `Sidebar.tsx`, `Header.tsx`, `CommandPalette.tsx`, `Dialog.tsx`, `Switch.tsx` — 신 shell/ui 컴포넌트로 위임하거나 삭제

**Delete (마지막 Task 에 한 번에):**
- `admin-ui/src/components/Layout.tsx` (→ `src/shell/Layout.tsx`)
- `admin-ui/src/components/Sidebar.tsx` (→ `src/shell/Sidebar.tsx`)
- `admin-ui/src/components/Header.tsx` (→ `src/shell/Header.tsx`)
- `admin-ui/src/components/CommandPalette.tsx` (→ `src/shell/CommandPalette.tsx`)
- `admin-ui/src/components/IdleTimeout.tsx` (→ `src/shell/IdleTimeoutDialog.tsx`)
- `admin-ui/src/components/Dialog.tsx` (→ `src/components/ui/dialog.tsx`)
- `admin-ui/src/components/Switch.tsx` (→ `src/components/ui/switch.tsx`)

**Tests:** Phase A 는 테스트 최소화 약속에 따라 자동 테스트 없음. 각 Task 후 `npm run build` 로 타입체크 + 빌드 통과를 베이스라인으로 사용. 마지막 Task 에서 수동 smoke 체크리스트 실행.

---

## Conventions

**Working directory:** 모든 명령은 `admin-ui/` 디렉토리에서 실행.

**Path alias:** shadcn 관례에 따라 `@/` = `src/`. import 경로는 `@/components/ui/button` 같은 형태로 통일.

**Naming:** 신규 셸 컴포넌트는 PascalCase (`Layout`, `Sidebar`, …). 신규 hooks 는 `use<Name>`. tweak 옵션 값은 디자인 패키지의 `data-*` attribute 와 정확히 일치 (`light`/`dark`, `compact`/`comfortable`, `indigo`/`violet`/`blue`/`teal`/`amber`, `lines`/`striped`/`borderless`, `labels`/`icons`).

**기존 코드 호환:** `src/components/Toast.tsx` 가 export 하는 `useToast()` API 의 시그니처는 유지 (모든 페이지가 사용 중). 내부 구현만 sonner 로 교체.

**Commit per task:** 각 Task 의 마지막 step 은 commit. 메시지 prefix:
- 의존성/설정: `chore(admin-ui): ...`
- 컴포넌트 신규: `feat(admin-ui): ...`
- 마이그레이션: `refactor(admin-ui): ...`

**Commit 전 codex review:** 각 Task 의 commit step **직전**에 `codex review` 를 수행 (외부 명령). issue 가 발견되면 해당 Task 안에서 fix step 으로 처리하고 같은 message 로 commit. **(이 plan 의 step 시퀀스는 codex review 단계를 명시한다.)**

---

## Task 1: Tailwind / PostCSS / shadcn 의존성 설치 + 설정 파일

**Files:**
- Create: `admin-ui/tailwind.config.ts`
- Create: `admin-ui/postcss.config.js`
- Create: `admin-ui/components.json`
- Create: `admin-ui/src/lib/utils.ts`
- Modify: `admin-ui/package.json`
- Modify: `admin-ui/tsconfig.json`
- Modify: `admin-ui/tsconfig.node.json`
- Modify: `admin-ui/vite.config.ts`

- [ ] **Step 1.1: 의존성 설치**

```bash
npm install -D tailwindcss@^3.4.13 postcss@^8.4.47 autoprefixer@^10.4.20 @types/node
npm install clsx tailwind-merge class-variance-authority lucide-react sonner cmdk
```

확인: `package.json` 의 dependencies 에 `clsx`, `tailwind-merge`, `class-variance-authority`, `lucide-react`, `sonner`, `cmdk` 가 있고 devDependencies 에 `tailwindcss`, `postcss`, `autoprefixer`, `@types/node` 가 있어야 한다.

- [ ] **Step 1.2: `postcss.config.js` 생성**

내용:
```js
export default {
  plugins: {
    tailwindcss: {},
    autoprefixer: {},
  },
};
```

- [ ] **Step 1.3: `tailwind.config.ts` 생성 — 토큰 매핑**

```ts
import type { Config } from 'tailwindcss';

const config: Config = {
  darkMode: ['selector', '[data-theme="dark"]'],
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        bg: 'var(--bg)',
        surface: 'var(--surface)',
        'surface-2': 'var(--surface-2)',
        'surface-3': 'var(--surface-3)',
        'surface-sunk': 'var(--surface-sunk)',
        border: 'var(--border)',
        'border-subtle': 'var(--border-subtle)',
        'border-strong': 'var(--border-strong)',
        text: 'var(--text)',
        'text-soft': 'var(--text-soft)',
        'text-mute': 'var(--text-mute)',
        'text-faint': 'var(--text-faint)',
        accent: {
          DEFAULT: 'var(--accent)',
          hover: 'var(--accent-hover)',
          press: 'var(--accent-press)',
          soft: 'var(--accent-soft)',
          'soft-2': 'var(--accent-soft-2)',
          fg: 'var(--accent-fg)',
        },
        success: {
          DEFAULT: 'var(--success)',
          soft: 'var(--success-soft)',
        },
        warning: {
          DEFAULT: 'var(--warning)',
          soft: 'var(--warning-soft)',
        },
        danger: {
          DEFAULT: 'var(--danger)',
          soft: 'var(--danger-soft)',
        },
        info: {
          DEFAULT: 'var(--info)',
          soft: 'var(--info-soft)',
        },
        violet: {
          DEFAULT: 'var(--violet)',
          soft: 'var(--violet-soft)',
        },
        teal: {
          DEFAULT: 'var(--teal)',
          soft: 'var(--teal-soft)',
        },
      },
      fontFamily: {
        sans: 'var(--font)',
        mono: 'var(--mono)',
      },
      borderRadius: {
        xs: 'var(--radius-xs)',
        sm: 'var(--radius-sm)',
        DEFAULT: 'var(--radius)',
        lg: 'var(--radius-lg)',
        xl: 'var(--radius-xl)',
        pill: 'var(--radius-pill)',
      },
      boxShadow: {
        xs: 'var(--shadow-xs)',
        sm: 'var(--shadow-sm)',
        md: 'var(--shadow-md)',
        lg: 'var(--shadow-lg)',
        focus: 'var(--focus-ring)',
      },
      transitionTimingFunction: {
        out: 'var(--ease-out)',
        'in-out': 'var(--ease-in-out)',
        spring: 'var(--ease-spring)',
      },
      transitionDuration: {
        fast: 'var(--dur-fast)',
        DEFAULT: 'var(--dur)',
        slow: 'var(--dur-slow)',
      },
    },
  },
  plugins: [],
};

export default config;
```

- [ ] **Step 1.4: `components.json` 생성 — shadcn config**

```json
{
  "$schema": "https://ui.shadcn.com/schema.json",
  "style": "default",
  "rsc": false,
  "tsx": true,
  "tailwind": {
    "config": "tailwind.config.ts",
    "css": "src/styles/tokens.css",
    "baseColor": "neutral",
    "cssVariables": true,
    "prefix": ""
  },
  "aliases": {
    "components": "@/components",
    "utils": "@/lib/utils",
    "ui": "@/components/ui",
    "lib": "@/lib",
    "hooks": "@/hooks"
  }
}
```

- [ ] **Step 1.5: `src/lib/utils.ts` 생성 — `cn()` 유틸**

```ts
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
```

- [ ] **Step 1.6: `vite.config.ts` — `@` alias**

```ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    proxy: {
      '/admin': 'http://localhost:8081',
    },
  },
});
```

기존 `vite.config.ts` 에 proxy 설정이 있으면 보존. 위 코드는 기본 형태이므로 기존 옵션과 병합.

- [ ] **Step 1.7: `tsconfig.json` — paths 추가**

기존 `compilerOptions` 에:
```json
{
  "compilerOptions": {
    "baseUrl": ".",
    "paths": {
      "@/*": ["./src/*"]
    }
  }
}
```

- [ ] **Step 1.8: 타입체크 + 빌드**

Run: `npm run build`
Expected: 0 에러, dist 생성.

- [ ] **Step 1.9: codex review**

Run: `codex review` (commit 전 staged + working tree 검토)
issue 발견 시 같은 Task 안에서 수정, 다시 build 확인.

- [ ] **Step 1.10: Commit**

```bash
git add admin-ui/package.json admin-ui/package-lock.json admin-ui/postcss.config.js \
  admin-ui/tailwind.config.ts admin-ui/components.json admin-ui/src/lib/utils.ts \
  admin-ui/vite.config.ts admin-ui/tsconfig.json admin-ui/tsconfig.node.json
git commit -m "chore(admin-ui): Tailwind + shadcn 의존성/설정 도입 (Phase A.1)"
```

---

## Task 2: tokens.css 에 Tailwind layer 통합

**Files:**
- Modify: `admin-ui/src/styles/tokens.css`
- Modify: `admin-ui/src/main.tsx` (이미 import 중이면 스킵)

기존 토큰/유틸 클래스는 모두 유지하되, 파일 최상단에 Tailwind directive 를 추가하여 hybrid 사용을 가능하게 한다.

- [ ] **Step 2.1: `tokens.css` 최상단에 Tailwind directive 추가**

파일 첫 줄에 추가:
```css
@tailwind base;
@tailwind components;
@tailwind utilities;
```

그 다음 빈 줄 후 기존 `/* ====== Design tokens ... */` 주석으로 이어진다.

- [ ] **Step 2.2: 기존 `:root` 블록을 `@layer base` 안으로 이동**

shadcn 컴포넌트가 토큰을 안전하게 override 하지 않도록 base layer 명시:
```css
@layer base {
  :root {
    /* 기존 light 토큰 */
  }
  :root[data-density="comfortable"] { ... }
  :root[data-sidebar="icons"] { ... }
  :root[data-sidebar="collapsed"] { ... }
  :root[data-theme="dark"] { ... }
  :root[data-tablestyle="striped"] .table tbody tr:nth-child(even) { ... }
  /* (다른 :root attr selectors 포함) */
}
```

기존 `*`, `html, body`, `button`, `input`, `::selection`, scrollbar 룰도 `@layer base` 로 묶는다.

- [ ] **Step 2.3: 기존 `.btn`, `.input`, `.card`, `.badge`, `.kbd`, `.chip`, `.table`, `.tabs`, `.dialog`, `.scrim`, `.toast`, `.metric-*`, `.skeleton`, `.banner`, `.empty`, layout helpers 는 `@layer components` 로 묶기**

(`@layer utilities` 는 사용 안 함 — Tailwind 가 알아서 처리)

- [ ] **Step 2.4: 빌드 확인**

Run: `npm run build`
Expected: 0 에러. CSS 출력 크기가 크게 증가하지 않아야 함 (Tailwind 가 content 스캔으로 사용된 유틸만 포함).

- [ ] **Step 2.5: 로컬 시각 확인 (수동)**

Run: `npm run dev` 시작. http://localhost:5173 접속 (백엔드 미실행이면 /login 화면).
Expected: 로그인 페이지가 기존과 시각적으로 동일하게 렌더링.

확인 후 dev 서버 종료.

- [ ] **Step 2.6: codex review**

Run: `codex review`. issue 발견 시 fix.

- [ ] **Step 2.7: Commit**

```bash
git add admin-ui/src/styles/tokens.css
git commit -m "refactor(admin-ui): tokens.css 를 Tailwind layer base/components 로 통합 (Phase A.2)"
```

---

## Task 3: shadcn 기본 컴포넌트 생성 (Button / Dialog / Input / Label / Badge / Switch)

shadcn CLI 가 자동 생성하는 컴포넌트를 토큰 변수 기반으로 직접 작성한다. (CLI 의 기본 색상 system 은 다른 변수명을 쓰기 때문에 수동 작성이 더 정확하다.)

**Files:**
- Create: `admin-ui/src/components/ui/button.tsx`
- Create: `admin-ui/src/components/ui/dialog.tsx`
- Create: `admin-ui/src/components/ui/input.tsx`
- Create: `admin-ui/src/components/ui/label.tsx`
- Create: `admin-ui/src/components/ui/badge.tsx`
- Create: `admin-ui/src/components/ui/switch.tsx`

- [ ] **Step 3.1: Radix 의존성 추가**

```bash
npm install @radix-ui/react-dialog @radix-ui/react-label @radix-ui/react-switch @radix-ui/react-slot
```

- [ ] **Step 3.2: `button.tsx` 생성**

기존 `.btn`/`.btn--primary`/`.btn--danger`/`.btn--ghost`/`.btn--outline`/`.btn--sm`/`.btn--lg` 와 시각적으로 동일하도록 cva variant 작성:

```tsx
import * as React from 'react';
import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

const buttonVariants = cva(
  'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded font-medium leading-tight tracking-[-0.011em] transition-colors disabled:opacity-50 disabled:cursor-not-allowed focus-visible:outline-none focus-visible:shadow-focus',
  {
    variants: {
      variant: {
        default: 'border border-border bg-surface text-text shadow-xs hover:bg-surface-3',
        primary: 'border border-accent bg-accent text-accent-fg shadow-xs hover:bg-accent-hover active:bg-accent-press',
        danger: 'border border-danger bg-danger text-white shadow-xs hover:brightness-[0.92]',
        ghost: 'bg-transparent hover:bg-surface-3',
        outline: 'border border-border bg-transparent hover:bg-surface-2 hover:border-border-strong',
      },
      size: {
        xs: 'px-1.5 py-0.5 text-[11px] rounded-sm',
        sm: 'px-2 py-1 text-xs rounded-sm',
        default: 'px-3 py-1.5 text-[13px]',
        lg: 'px-4 py-2 text-sm',
      },
    },
    defaultVariants: { variant: 'default', size: 'default' },
  }
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : 'button';
    return <Comp className={cn(buttonVariants({ variant, size, className }))} ref={ref} {...props} />;
  }
);
Button.displayName = 'Button';

export { buttonVariants };
```

- [ ] **Step 3.3: `dialog.tsx` 생성**

기존 `.scrim` + `.dialog` + `.dialog__head/body/foot` 의 시각을 그대로 살리는 Radix wrapper:

```tsx
import * as React from 'react';
import * as DialogPrimitive from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import { cn } from '@/lib/utils';

const Dialog = DialogPrimitive.Root;
const DialogTrigger = DialogPrimitive.Trigger;
const DialogPortal = DialogPrimitive.Portal;
const DialogClose = DialogPrimitive.Close;

const DialogOverlay = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Overlay>,
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Overlay>
>(({ className, ...props }, ref) => (
  <DialogPrimitive.Overlay
    ref={ref}
    className={cn(
      'fixed inset-0 z-[60] bg-text/40 backdrop-blur-md data-[state=open]:animate-in data-[state=closed]:animate-out',
      className
    )}
    {...props}
  />
));
DialogOverlay.displayName = DialogPrimitive.Overlay.displayName;

const DialogContent = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Content> & { wide?: boolean }
>(({ className, children, wide, ...props }, ref) => (
  <DialogPortal>
    <DialogOverlay />
    <DialogPrimitive.Content
      ref={ref}
      className={cn(
        'fixed left-[50%] top-[50%] z-[60] grid translate-x-[-50%] translate-y-[-50%]',
        'bg-surface border border-border rounded-xl shadow-lg overflow-hidden',
        'w-[92vw] max-h-[92vh]',
        wide ? 'max-w-[720px]' : 'max-w-[520px]',
        className
      )}
      {...props}
    >
      {children}
      <DialogPrimitive.Close className="absolute right-4 top-4 rounded-sm opacity-70 transition-opacity hover:opacity-100 focus:outline-none">
        <X className="h-4 w-4" />
        <span className="sr-only">Close</span>
      </DialogPrimitive.Close>
    </DialogPrimitive.Content>
  </DialogPortal>
));
DialogContent.displayName = DialogPrimitive.Content.displayName;

const DialogHeader = ({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) => (
  <div className={cn('px-6 pt-5 pb-3 flex flex-col gap-1', className)} {...props} />
);
DialogHeader.displayName = 'DialogHeader';

const DialogFooter = ({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) => (
  <div className={cn('px-6 py-3.5 border-t border-border-subtle bg-surface-2 flex justify-end gap-2', className)} {...props} />
);
DialogFooter.displayName = 'DialogFooter';

const DialogTitle = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Title>,
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Title>
>(({ className, ...props }, ref) => (
  <DialogPrimitive.Title ref={ref} className={cn('text-base font-semibold tracking-[-0.011em]', className)} {...props} />
));
DialogTitle.displayName = DialogPrimitive.Title.displayName;

const DialogDescription = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Description>,
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Description>
>(({ className, ...props }, ref) => (
  <DialogPrimitive.Description ref={ref} className={cn('text-[13px] text-text-mute', className)} {...props} />
));
DialogDescription.displayName = DialogPrimitive.Description.displayName;

const DialogBody = ({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) => (
  <div className={cn('px-6 pb-4 overflow-y-auto', className)} {...props} />
);
DialogBody.displayName = 'DialogBody';

export {
  Dialog,
  DialogTrigger,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogBody,
  DialogFooter,
  DialogClose,
};
```

- [ ] **Step 3.4: `input.tsx` 생성**

```tsx
import * as React from 'react';
import { cn } from '@/lib/utils';

export const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  ({ className, type, ...props }, ref) => (
    <input
      type={type}
      ref={ref}
      className={cn(
        'w-full rounded border border-border bg-surface text-text text-[13px] px-2.5 py-1.5',
        'hover:border-border-strong focus:outline-none focus:border-accent focus:shadow-focus',
        'placeholder:text-text-faint disabled:bg-surface-2 disabled:text-text-mute disabled:cursor-not-allowed',
        'transition-colors',
        className
      )}
      {...props}
    />
  )
);
Input.displayName = 'Input';
```

- [ ] **Step 3.5: `label.tsx` 생성**

```tsx
import * as React from 'react';
import * as LabelPrimitive from '@radix-ui/react-label';
import { cn } from '@/lib/utils';

export const Label = React.forwardRef<
  React.ElementRef<typeof LabelPrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof LabelPrimitive.Root>
>(({ className, ...props }, ref) => (
  <LabelPrimitive.Root
    ref={ref}
    className={cn('block text-xs font-medium text-text-soft mb-1.5 tracking-[-0.011em]', className)}
    {...props}
  />
));
Label.displayName = LabelPrimitive.Root.displayName;
```

- [ ] **Step 3.6: `badge.tsx` 생성**

```tsx
import * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

const badgeVariants = cva(
  'inline-flex items-center gap-1 px-1.5 py-0.5 rounded-pill text-[11px] font-semibold leading-tight tracking-[-0.011em] border border-transparent',
  {
    variants: {
      variant: {
        default: 'bg-surface-3 text-text-soft',
        success: 'bg-success-soft text-success',
        warning: 'bg-warning-soft text-warning',
        danger: 'bg-danger-soft text-danger',
        info: 'bg-info-soft text-info',
        violet: 'bg-violet-soft text-violet',
        teal: 'bg-teal-soft text-teal',
        accent: 'bg-accent-soft text-accent',
      },
    },
    defaultVariants: { variant: 'default' },
  }
);

export interface BadgeProps
  extends React.HTMLAttributes<HTMLSpanElement>,
    VariantProps<typeof badgeVariants> {
  dot?: boolean;
}

export function Badge({ className, variant, dot, children, ...props }: BadgeProps) {
  return (
    <span className={cn(badgeVariants({ variant }), className)} {...props}>
      {dot && <span className="w-1.5 h-1.5 rounded-full bg-current" />}
      {children}
    </span>
  );
}

export { badgeVariants };
```

- [ ] **Step 3.7: `switch.tsx` 생성**

```tsx
import * as React from 'react';
import * as SwitchPrimitive from '@radix-ui/react-switch';
import { cn } from '@/lib/utils';

export const Switch = React.forwardRef<
  React.ElementRef<typeof SwitchPrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof SwitchPrimitive.Root>
>(({ className, ...props }, ref) => (
  <SwitchPrimitive.Root
    ref={ref}
    className={cn(
      'peer inline-flex h-5 w-9 shrink-0 cursor-pointer items-center rounded-pill border border-transparent',
      'transition-colors focus-visible:outline-none focus-visible:shadow-focus',
      'data-[state=checked]:bg-accent data-[state=unchecked]:bg-surface-3',
      'disabled:cursor-not-allowed disabled:opacity-50',
      className
    )}
    {...props}
  >
    <SwitchPrimitive.Thumb
      className={cn(
        'pointer-events-none block h-4 w-4 rounded-full bg-white shadow-sm ring-0 transition-transform',
        'data-[state=checked]:translate-x-[18px] data-[state=unchecked]:translate-x-0.5'
      )}
    />
  </SwitchPrimitive.Root>
));
Switch.displayName = SwitchPrimitive.Root.displayName;
```

- [ ] **Step 3.8: 빌드 확인**

Run: `npm run build`
Expected: 0 에러.

- [ ] **Step 3.9: codex review**

Run: `codex review`. issue 발견 시 fix.

- [ ] **Step 3.10: Commit**

```bash
git add admin-ui/src/components/ui/ admin-ui/package.json admin-ui/package-lock.json
git commit -m "feat(admin-ui): shadcn 베이스 컴포넌트 (Button/Dialog/Input/Label/Badge/Switch) (Phase A.3)"
```

---

## Task 4: 나머지 shadcn 컴포넌트 (Dropdown / Tabs / Popover / Tooltip / Sonner)

**Files:**
- Create: `admin-ui/src/components/ui/dropdown-menu.tsx`
- Create: `admin-ui/src/components/ui/tabs.tsx`
- Create: `admin-ui/src/components/ui/popover.tsx`
- Create: `admin-ui/src/components/ui/tooltip.tsx`
- Create: `admin-ui/src/components/ui/sonner.tsx`

- [ ] **Step 4.1: Radix 의존성 추가**

```bash
npm install @radix-ui/react-dropdown-menu @radix-ui/react-tabs @radix-ui/react-popover @radix-ui/react-tooltip
```

- [ ] **Step 4.2: `dropdown-menu.tsx` 생성**

shadcn 공식 패턴 그대로 + Tailwind 토큰 클래스(`bg-surface`, `border-border`, `text-text` 등) 로 변환. 핵심:

```tsx
import * as React from 'react';
import * as DropdownMenuPrimitive from '@radix-ui/react-dropdown-menu';
import { Check, ChevronRight } from 'lucide-react';
import { cn } from '@/lib/utils';

const DropdownMenu = DropdownMenuPrimitive.Root;
const DropdownMenuTrigger = DropdownMenuPrimitive.Trigger;
const DropdownMenuPortal = DropdownMenuPrimitive.Portal;

const DropdownMenuContent = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Content>
>(({ className, sideOffset = 6, ...props }, ref) => (
  <DropdownMenuPortal>
    <DropdownMenuPrimitive.Content
      ref={ref}
      sideOffset={sideOffset}
      className={cn(
        'z-50 min-w-[10rem] overflow-hidden rounded-lg border border-border bg-surface p-1 text-[13px] text-text shadow-md',
        className
      )}
      {...props}
    />
  </DropdownMenuPortal>
));
DropdownMenuContent.displayName = DropdownMenuPrimitive.Content.displayName;

const DropdownMenuItem = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.Item>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Item>
>(({ className, ...props }, ref) => (
  <DropdownMenuPrimitive.Item
    ref={ref}
    className={cn(
      'relative flex cursor-pointer select-none items-center rounded-sm px-2 py-1.5 outline-none',
      'focus:bg-surface-2 focus:text-text data-[disabled]:pointer-events-none data-[disabled]:opacity-50',
      className
    )}
    {...props}
  />
));
DropdownMenuItem.displayName = DropdownMenuPrimitive.Item.displayName;

const DropdownMenuSeparator = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.Separator>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Separator>
>(({ className, ...props }, ref) => (
  <DropdownMenuPrimitive.Separator ref={ref} className={cn('-mx-1 my-1 h-px bg-border-subtle', className)} {...props} />
));
DropdownMenuSeparator.displayName = DropdownMenuPrimitive.Separator.displayName;

const DropdownMenuLabel = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.Label>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Label>
>(({ className, ...props }, ref) => (
  <DropdownMenuPrimitive.Label ref={ref} className={cn('px-2 py-1.5 text-[11px] font-semibold uppercase tracking-wider text-text-mute', className)} {...props} />
));
DropdownMenuLabel.displayName = DropdownMenuPrimitive.Label.displayName;

export {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
};
```

- [ ] **Step 4.3: `tabs.tsx` 생성**

```tsx
import * as React from 'react';
import * as TabsPrimitive from '@radix-ui/react-tabs';
import { cn } from '@/lib/utils';

const Tabs = TabsPrimitive.Root;

const TabsList = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.List>,
  React.ComponentPropsWithoutRef<typeof TabsPrimitive.List>
>(({ className, ...props }, ref) => (
  <TabsPrimitive.List
    ref={ref}
    className={cn('flex gap-1 border-b border-border-subtle mb-5 overflow-x-auto', className)}
    {...props}
  />
));
TabsList.displayName = TabsPrimitive.List.displayName;

const TabsTrigger = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.Trigger>,
  React.ComponentPropsWithoutRef<typeof TabsPrimitive.Trigger>
>(({ className, ...props }, ref) => (
  <TabsPrimitive.Trigger
    ref={ref}
    className={cn(
      'bg-transparent border-0 px-3.5 py-2.5 text-[13px] font-medium text-text-soft whitespace-nowrap',
      'border-b-2 border-transparent -mb-px transition-colors',
      'hover:text-text data-[state=active]:text-accent data-[state=active]:border-accent',
      'focus-visible:outline-none',
      className
    )}
    {...props}
  />
));
TabsTrigger.displayName = TabsPrimitive.Trigger.displayName;

const TabsContent = TabsPrimitive.Content;

export { Tabs, TabsList, TabsTrigger, TabsContent };
```

- [ ] **Step 4.4: `popover.tsx` 생성**

```tsx
import * as React from 'react';
import * as PopoverPrimitive from '@radix-ui/react-popover';
import { cn } from '@/lib/utils';

const Popover = PopoverPrimitive.Root;
const PopoverTrigger = PopoverPrimitive.Trigger;

const PopoverContent = React.forwardRef<
  React.ElementRef<typeof PopoverPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof PopoverPrimitive.Content>
>(({ className, align = 'center', sideOffset = 6, ...props }, ref) => (
  <PopoverPrimitive.Portal>
    <PopoverPrimitive.Content
      ref={ref}
      align={align}
      sideOffset={sideOffset}
      className={cn(
        'z-50 w-72 rounded-lg border border-border bg-surface p-3 text-[13px] text-text shadow-md outline-none',
        className
      )}
      {...props}
    />
  </PopoverPrimitive.Portal>
));
PopoverContent.displayName = PopoverPrimitive.Content.displayName;

export { Popover, PopoverTrigger, PopoverContent };
```

- [ ] **Step 4.5: `tooltip.tsx` 생성**

```tsx
import * as React from 'react';
import * as TooltipPrimitive from '@radix-ui/react-tooltip';
import { cn } from '@/lib/utils';

const TooltipProvider = TooltipPrimitive.Provider;
const Tooltip = TooltipPrimitive.Root;
const TooltipTrigger = TooltipPrimitive.Trigger;

const TooltipContent = React.forwardRef<
  React.ElementRef<typeof TooltipPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof TooltipPrimitive.Content>
>(({ className, sideOffset = 4, ...props }, ref) => (
  <TooltipPrimitive.Content
    ref={ref}
    sideOffset={sideOffset}
    className={cn(
      'z-50 overflow-hidden rounded border border-border bg-surface px-2 py-1 text-xs text-text shadow-sm',
      className
    )}
    {...props}
  />
));
TooltipContent.displayName = TooltipPrimitive.Content.displayName;

export { Tooltip, TooltipTrigger, TooltipContent, TooltipProvider };
```

- [ ] **Step 4.6: `sonner.tsx` 생성**

```tsx
import { Toaster as Sonner } from 'sonner';

export function Toaster() {
  return (
    <Sonner
      position="bottom-right"
      richColors
      closeButton
      toastOptions={{
        classNames: {
          toast:
            'bg-surface border border-border text-text rounded-lg shadow-md p-3 text-[13px] font-sans',
          title: 'font-semibold text-text',
          description: 'text-text-mute text-xs mt-0.5',
          actionButton: 'bg-accent text-accent-fg rounded px-2 py-1 text-xs',
          cancelButton: 'bg-surface-3 text-text-soft rounded px-2 py-1 text-xs',
        },
      }}
    />
  );
}
```

- [ ] **Step 4.7: 빌드 확인**

Run: `npm run build`
Expected: 0 에러.

- [ ] **Step 4.8: codex review**

Run: `codex review`. issue 발견 시 fix.

- [ ] **Step 4.9: Commit**

```bash
git add admin-ui/src/components/ui/ admin-ui/package.json admin-ui/package-lock.json
git commit -m "feat(admin-ui): shadcn 컴포넌트 (Dropdown/Tabs/Popover/Tooltip/Sonner) (Phase A.4)"
```

---

## Task 5: TweaksProvider 구현

**Files:**
- Create: `admin-ui/src/app/providers/TweaksProvider.tsx`
- Create: `admin-ui/src/app/providers/AppProviders.tsx`

- [ ] **Step 5.1: `TweaksProvider.tsx` 생성**

```tsx
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

  React.useEffect(() => {
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
```

**Accent 변경 시 색상 적용**: `data-accent` 속성에 반응하는 CSS 룰을 `tokens.css` 에 추가해야 한다. 그러나 디자인 패키지의 기본은 indigo 만 정의되어 있다. 이번 phase 에서는 5색 옵션을 다음과 같이 처리:
- `indigo` (default): 현재 `--accent` 값 그대로 (tokens.css 의 기본)
- `violet`/`blue`/`teal`/`amber`: 추가 룰

다음 step 에서 tokens.css 에 추가한다.

- [ ] **Step 5.2: `tokens.css` 에 accent variant 룰 추가**

`@layer base` 블록 안, light/dark 토큰 다음에 추가:

```css
:root[data-accent="violet"] {
  --accent:        oklch(0.58 0.215 295);
  --accent-hover:  oklch(0.51 0.225 295);
  --accent-press:  oklch(0.45 0.220 295);
  --accent-soft:   oklch(0.96 0.030 295);
  --accent-soft-2: oklch(0.92 0.055 295);
  --focus-ring:    0 0 0 3px oklch(0.58 0.215 295 / 0.22);
}
:root[data-accent="blue"] {
  --accent:        oklch(0.58 0.180 245);
  --accent-hover:  oklch(0.51 0.190 245);
  --accent-press:  oklch(0.45 0.185 245);
  --accent-soft:   oklch(0.96 0.030 245);
  --accent-soft-2: oklch(0.92 0.055 245);
  --focus-ring:    0 0 0 3px oklch(0.58 0.180 245 / 0.22);
}
:root[data-accent="teal"] {
  --accent:        oklch(0.62 0.110 195);
  --accent-hover:  oklch(0.55 0.115 195);
  --accent-press:  oklch(0.49 0.110 195);
  --accent-soft:   oklch(0.955 0.040 195);
  --accent-soft-2: oklch(0.91 0.060 195);
  --focus-ring:    0 0 0 3px oklch(0.62 0.110 195 / 0.22);
}
:root[data-accent="amber"] {
  --accent:        oklch(0.74 0.155 70);
  --accent-hover:  oklch(0.68 0.165 70);
  --accent-press:  oklch(0.61 0.160 70);
  --accent-soft:   oklch(0.965 0.055 80);
  --accent-soft-2: oklch(0.92 0.080 80);
  --accent-fg:     oklch(0.22 0.015 70);
  --focus-ring:    0 0 0 3px oklch(0.74 0.155 70 / 0.22);
}

:root[data-theme="dark"][data-accent="violet"] { --accent: oklch(0.74 0.170 295); --accent-soft: oklch(0.31 0.110 295); }
:root[data-theme="dark"][data-accent="blue"]   { --accent: oklch(0.74 0.155 245); --accent-soft: oklch(0.30 0.095 245); }
:root[data-theme="dark"][data-accent="teal"]   { --accent: oklch(0.78 0.110 195); --accent-soft: oklch(0.30 0.060 195); }
:root[data-theme="dark"][data-accent="amber"]  { --accent: oklch(0.82 0.150 75);  --accent-soft: oklch(0.33 0.080 75); --accent-fg: oklch(0.155 0.005 270); }
```

- [ ] **Step 5.3: `AppProviders.tsx` 생성 — provider 컴포지션**

기존 `MeProvider`, `ToastProvider` 등을 한곳에 묶는다. 이번 task 에서는 새 `TweaksProvider` + `TooltipProvider` 만 도입 (기존 Toast/Me 는 다음 task 에서 흡수).

```tsx
import { TweaksProvider } from './TweaksProvider';
import { TooltipProvider } from '@/components/ui/tooltip';

export function AppProviders({ children }: { children: React.ReactNode }) {
  return (
    <TweaksProvider>
      <TooltipProvider delayDuration={150}>{children}</TooltipProvider>
    </TweaksProvider>
  );
}
```

- [ ] **Step 5.4: `main.tsx` 에 AppProviders 적용**

기존 `main.tsx` 에서 `<App />` 을 `<AppProviders><App /></AppProviders>` 로 감싼다.

- [ ] **Step 5.5: 빌드 확인**

Run: `npm run build`
Expected: 0 에러.

- [ ] **Step 5.6: 수동 smoke — 토큰 토글**

Run: `npm run dev`.
브라우저 콘솔에서 `document.documentElement.setAttribute('data-theme','dark')` 실행 → 즉시 다크 모드 적용.
`localStorage.setItem('passkey-admin:tweaks', JSON.stringify({theme:'dark',density:'comfortable',accent:'violet',tablestyle:'striped',sidebar:'labels'}))` 후 새로고침 → 모든 옵션이 반영되어야 함.

확인 후 dev 종료, localStorage 정리.

- [ ] **Step 5.7: codex review**

Run: `codex review`.

- [ ] **Step 5.8: Commit**

```bash
git add admin-ui/src/app/providers/ admin-ui/src/styles/tokens.css admin-ui/src/main.tsx
git commit -m "feat(admin-ui): TweaksProvider + accent variant 토큰 + AppProviders (Phase A.5)"
```

---

## Task 6: Toast 를 sonner 로 위임 (기존 useToast API 보존)

기존 `src/components/Toast.tsx` 는 `{ kind, title, message, traceId }` 시그니처의 `useToast()` 를 export 한다. 페이지들이 이를 사용 중이므로 시그니처는 보존하고 내부만 sonner 로 위임한다.

**Files:**
- Modify: `admin-ui/src/components/Toast.tsx`
- Modify: `admin-ui/src/app/providers/AppProviders.tsx`
- Modify: `admin-ui/src/App.tsx` (ToastProvider wrap 제거 — sonner Toaster 가 단일 호스트)

- [ ] **Step 6.1: 기존 `Toast.tsx` 의 사용 시그니처 확인**

Read `admin-ui/src/components/Toast.tsx` — `kind: 'ok' | 'err' | 'warn'`, `title?`, `message?`, `traceId?` 를 받는 함수가 반환되는지 확인.

- [ ] **Step 6.2: `Toast.tsx` 를 sonner wrapper 로 교체**

```tsx
import { toast as sonnerToast } from 'sonner';

export type ToastInput = {
  kind: 'ok' | 'err' | 'warn';
  title?: string;
  message?: string;
  traceId?: string;
};

function show({ kind, title, message, traceId }: ToastInput) {
  const description = [message, traceId ? `traceId: ${traceId}` : null].filter(Boolean).join('\n');
  const opts = description ? { description } : undefined;
  const label = title ?? '';
  if (kind === 'ok') return sonnerToast.success(label, opts);
  if (kind === 'err') return sonnerToast.error(label, opts);
  return sonnerToast.warning(label, opts);
}

export function useToast() {
  return show;
}

// Backwards-compat shim — ToastProvider 가 export 되던 곳을 위해 no-op 유지
export function ToastProvider({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
```

(import 인 React 가 JSX 만 쓰일 경우 `import * as React from 'react'` 필요 — TS 설정에 따라 추가)

- [ ] **Step 6.3: `AppProviders.tsx` 에 Toaster 추가**

```tsx
import { TweaksProvider } from './TweaksProvider';
import { TooltipProvider } from '@/components/ui/tooltip';
import { Toaster } from '@/components/ui/sonner';

export function AppProviders({ children }: { children: React.ReactNode }) {
  return (
    <TweaksProvider>
      <TooltipProvider delayDuration={150}>
        {children}
        <Toaster />
      </TooltipProvider>
    </TweaksProvider>
  );
}
```

- [ ] **Step 6.4: `App.tsx` 에서 `<ToastProvider>` wrap 제거**

기존:
```tsx
<ToastProvider>
  <ApiErrorBridge />
  ...
</ToastProvider>
```
변경:
```tsx
<>
  <ApiErrorBridge />
  ...
</>
```

(`useToast` import 는 그대로 유지 — `ApiErrorBridge` 가 사용)

- [ ] **Step 6.5: 빌드 + 수동 smoke**

Run: `npm run build` → 0 에러.
Run: `npm run dev`, 로그인 페이지에서 잘못된 비밀번호로 로그인 시도 → toast 가 sonner 스타일로 표시되어야 함 (right-bottom).

- [ ] **Step 6.6: codex review**

- [ ] **Step 6.7: Commit**

```bash
git add admin-ui/src/components/Toast.tsx admin-ui/src/app/providers/AppProviders.tsx admin-ui/src/App.tsx
git commit -m "refactor(admin-ui): Toast 를 sonner 로 위임, useToast 시그니처 보존 (Phase A.6)"
```

---

## Task 7: 신 Shell — Layout / Sidebar / Header

기존 `src/components/Layout.tsx`, `Sidebar.tsx`, `Header.tsx` 의 역할을 `src/shell/` 로 이식하면서 디자인 패키지 구조에 정렬한다.

**Files:**
- Create: `admin-ui/src/shell/Layout.tsx`
- Create: `admin-ui/src/shell/Sidebar.tsx`
- Create: `admin-ui/src/shell/Header.tsx`

기존 컴포넌트의 핵심 로직(role 기반 nav 등)은 새 파일로 옮기고, 기존 파일은 Task 11 에서 일괄 삭제한다.

- [ ] **Step 7.1: 기존 `Sidebar.tsx`, `Header.tsx`, `Layout.tsx` 를 Read 하여 현재 라우트/네비게이션 항목 파악**

확인할 것:
- `useMe()` 로 role/email/tenantId 접근 방식
- Sidebar nav 항목 목록 (PLATFORM_OPERATOR vs RP_ADMIN)
- Header 의 로그아웃 처리 방식
- `<Outlet />` 사용 여부

(plan 작성 시점 시스템 정보로는 라우트가 `/tenants /tenants/new /tenants/:id /activity /audit /mds /keys` 임. 신 Sidebar 도 동일 라우트.)

- [ ] **Step 7.2: `src/shell/Sidebar.tsx` 생성**

```tsx
import { Link, NavLink } from 'react-router-dom';
import {
  Building2,
  Activity as ActivityIcon,
  ShieldCheck,
  Database,
  KeyRound,
  Settings as SettingsIcon,
} from 'lucide-react';
import { useMe } from '@/me/MeContext';
import { useTweaks } from '@/app/providers/TweaksProvider';
import { cn } from '@/lib/utils';

type NavItem = { to: string; label: string; icon: React.ComponentType<{ className?: string }>; };

const PLATFORM_NAV: NavItem[] = [
  { to: '/tenants', label: 'Tenants', icon: Building2 },
  { to: '/activity', label: 'Activity', icon: ActivityIcon },
  { to: '/audit', label: 'Audit', icon: ShieldCheck },
  { to: '/keys', label: 'Signing Keys', icon: KeyRound },
  { to: '/mds', label: 'MDS', icon: Database },
];

const RP_NAV = (tenantId: string): NavItem[] => [
  { to: `/tenants/${tenantId}`, label: 'Overview', icon: Building2 },
  { to: '/audit', label: 'Audit', icon: ShieldCheck },
];

export function Sidebar() {
  const me = useMe();
  const { tweaks } = useTweaks();
  const iconOnly = tweaks.sidebar === 'icons';

  const items =
    me?.role === 'RP_ADMIN' && me.tenantId
      ? RP_NAV(me.tenantId)
      : PLATFORM_NAV;

  return (
    <aside className="border-r border-border-subtle bg-surface-2 flex flex-col">
      <Link to="/" className="px-4 py-4 border-b border-border-subtle flex items-center gap-2 text-sm font-semibold tracking-[-0.011em] text-text">
        <ShieldCheck className="h-4 w-4 text-accent" />
        {!iconOnly && <span>Passkey Admin</span>}
      </Link>
      <nav className="flex-1 p-2 flex flex-col gap-0.5">
        {items.map((it) => (
          <NavLink
            key={it.to}
            to={it.to}
            end={it.to === '/tenants'}
            className={({ isActive }) =>
              cn(
                'flex items-center gap-2.5 px-2.5 py-1.5 rounded text-[13px] text-text-soft transition-colors',
                'hover:bg-surface-3 hover:text-text',
                isActive && 'bg-accent-soft text-accent font-medium'
              )
            }
          >
            <it.icon className="h-4 w-4 shrink-0" />
            {!iconOnly && <span>{it.label}</span>}
          </NavLink>
        ))}
      </nav>
      <div className="p-3 border-t border-border-subtle text-[11px] text-text-faint">
        {/* Audit Chain 인디케이터 placeholder — Phase B 에서 채움 */}
        <span className="opacity-50">chain status · pending</span>
      </div>
    </aside>
  );
}
```

- [ ] **Step 7.3: `src/shell/Header.tsx` 생성**

```tsx
import { useNavigate } from 'react-router-dom';
import { Command, LogOut, User } from 'lucide-react';
import { useMe } from '@/me/MeContext';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { api } from '@/api/client';

type Props = { onOpenPalette: () => void };

export function Header({ onOpenPalette }: Props) {
  const me = useMe();
  const navigate = useNavigate();

  async function handleLogout() {
    try {
      await api.post('/admin/logout', {});
    } catch {
      /* 무시 — 어차피 로그인으로 이동 */
    }
    navigate('/login');
  }

  return (
    <header className="h-14 border-b border-border-subtle bg-surface flex items-center justify-between px-6 gap-4">
      <div className="text-[13px] text-text-mute">
        {me?.role === 'PLATFORM_OPERATOR' ? 'Platform Operator Console' : 'Tenant Admin Console'}
      </div>
      <div className="flex items-center gap-3">
        <Button variant="outline" size="sm" onClick={onOpenPalette} className="gap-2">
          <Command className="h-3.5 w-3.5" />
          <span className="text-[12px]">⌘K</span>
        </Button>
        {me?.role && (
          <Badge variant={me.role === 'PLATFORM_OPERATOR' ? 'accent' : 'teal'}>
            {me.role === 'PLATFORM_OPERATOR' ? 'PLATFORM' : 'TENANT'}
          </Badge>
        )}
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="sm" className="gap-2">
              <User className="h-3.5 w-3.5" />
              <span className="text-[12px]">{me?.email ?? '...'}</span>
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuLabel>{me?.email}</DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={handleLogout} className="text-danger">
              <LogOut className="h-3.5 w-3.5 mr-2" /> Logout
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </header>
  );
}
```

(만약 기존 `api` 가 `/admin/logout` 엔드포인트와 약간 다른 형태로 호출한다면 기존 Header.tsx 에서 사용한 방식을 그대로 옮긴다. Task 7.1 에서 확인한 호출 패턴을 우선.)

- [ ] **Step 7.4: `src/shell/Layout.tsx` 생성**

```tsx
import { useState } from 'react';
import { Outlet } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { Header } from './Header';
import { CommandPalette } from './CommandPalette';
import { IdleTimeoutDialog } from './IdleTimeoutDialog';
import { TweaksPanel } from './TweaksPanel';

export function Layout() {
  const [paletteOpen, setPaletteOpen] = useState(false);

  return (
    <div
      className="grid min-h-screen"
      style={{ gridTemplateColumns: 'var(--sidebar-w) 1fr' }}
    >
      <Sidebar />
      <div className="min-w-0 flex flex-col">
        <Header onOpenPalette={() => setPaletteOpen(true)} />
        <main className="flex-1">
          <Outlet />
        </main>
      </div>
      <CommandPalette open={paletteOpen} onOpenChange={setPaletteOpen} />
      <IdleTimeoutDialog />
      <TweaksPanel />
    </div>
  );
}
```

`CommandPalette`, `IdleTimeoutDialog`, `TweaksPanel` 은 다음 task 들에서 생성. 우선 stub 으로 시작:

- [ ] **Step 7.5: 신 shell 컴포넌트들 stub 파일 생성 (build 통과용)**

`admin-ui/src/shell/CommandPalette.tsx`:
```tsx
export function CommandPalette({ open, onOpenChange }: { open: boolean; onOpenChange: (v: boolean) => void }) {
  return null;
}
```
`admin-ui/src/shell/IdleTimeoutDialog.tsx`:
```tsx
export function IdleTimeoutDialog() { return null; }
```
`admin-ui/src/shell/TweaksPanel.tsx`:
```tsx
export function TweaksPanel() { return null; }
```

(이들은 Task 8/9/10 에서 채워진다.)

- [ ] **Step 7.6: `App.tsx` — 새 Layout 사용**

기존 import `import Layout from './components/Layout';` 를 `import { Layout } from '@/shell/Layout';` 로 변경.
기존 `<Route element={<MeProvider><Layout /></MeProvider>}>` 형태는 그대로 유지.
또한 `IdleTimeout`, `CommandPalette` 등 기존 컴포넌트 임포트는 제거 (새 Layout 안에서 처리됨).

- [ ] **Step 7.7: 빌드 + 수동 smoke**

Run: `npm run build` → 0 에러.
Run: `npm run dev`, 로그인 → 사이드바/헤더가 디자인 패키지 모양으로 표시되어야 함. 역할 배지, 사이드바 nav 항목 OK 확인.

- [ ] **Step 7.8: codex review**

- [ ] **Step 7.9: Commit**

```bash
git add admin-ui/src/shell/ admin-ui/src/App.tsx
git commit -m "feat(admin-ui): 신 Shell (Layout/Sidebar/Header) 도입, App 라우터 연결 (Phase A.7)"
```

---

## Task 8: TweaksPanel 구현

**Files:**
- Modify: `admin-ui/src/shell/TweaksPanel.tsx`

- [ ] **Step 8.1: TweaksPanel 구현**

```tsx
import { useState } from 'react';
import { Sliders } from 'lucide-react';
import { useTweaks, type Tweaks } from '@/app/providers/TweaksProvider';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { cn } from '@/lib/utils';

const ACCENTS: Tweaks['accent'][] = ['indigo', 'violet', 'blue', 'teal', 'amber'];
const ACCENT_HEX: Record<Tweaks['accent'], string> = {
  indigo: 'oklch(0.56 0.215 268)',
  violet: 'oklch(0.58 0.215 295)',
  blue: 'oklch(0.58 0.180 245)',
  teal: 'oklch(0.62 0.110 195)',
  amber: 'oklch(0.74 0.155 70)',
};

function Segment<T extends string>({
  value,
  options,
  onChange,
}: {
  value: T;
  options: readonly T[];
  onChange: (v: T) => void;
}) {
  return (
    <div className="inline-flex rounded border border-border bg-surface-2 p-0.5">
      {options.map((opt) => (
        <button
          key={opt}
          type="button"
          onClick={() => onChange(opt)}
          className={cn(
            'px-2.5 py-1 text-[12px] rounded-sm transition-colors',
            value === opt ? 'bg-surface text-text shadow-xs' : 'text-text-mute hover:text-text'
          )}
        >
          {opt}
        </button>
      ))}
    </div>
  );
}

export function TweaksPanel() {
  const { tweaks, setTweak, reset } = useTweaks();
  const [open, setOpen] = useState(false);

  return (
    <div className="fixed bottom-4 right-4 z-40">
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button variant="outline" size="sm" className="shadow-md">
            <Sliders className="h-3.5 w-3.5 mr-1.5" /> Tweaks
          </Button>
        </PopoverTrigger>
        <PopoverContent align="end" sideOffset={8} className="w-72">
          <div className="flex flex-col gap-3">
            <div>
              <Label>Theme</Label>
              <Segment
                value={tweaks.theme}
                options={['light', 'dark'] as const}
                onChange={(v) => setTweak('theme', v)}
              />
            </div>
            <div>
              <Label>Density</Label>
              <Segment
                value={tweaks.density}
                options={['compact', 'comfortable'] as const}
                onChange={(v) => setTweak('density', v)}
              />
            </div>
            <div>
              <Label>Accent</Label>
              <div className="flex gap-1.5">
                {ACCENTS.map((a) => (
                  <button
                    key={a}
                    type="button"
                    aria-label={a}
                    onClick={() => setTweak('accent', a)}
                    className={cn(
                      'h-6 w-6 rounded-full border-2 transition',
                      tweaks.accent === a ? 'border-text' : 'border-transparent hover:border-border-strong'
                    )}
                    style={{ background: ACCENT_HEX[a] }}
                  />
                ))}
              </div>
            </div>
            <div>
              <Label>Table style</Label>
              <Segment
                value={tweaks.tablestyle}
                options={['lines', 'striped', 'borderless'] as const}
                onChange={(v) => setTweak('tablestyle', v)}
              />
            </div>
            <div>
              <Label>Sidebar</Label>
              <Segment
                value={tweaks.sidebar}
                options={['labels', 'icons'] as const}
                onChange={(v) => setTweak('sidebar', v)}
              />
            </div>
            <div className="flex justify-end pt-1">
              <Button variant="ghost" size="sm" onClick={reset}>
                Reset
              </Button>
            </div>
          </div>
        </PopoverContent>
      </Popover>
    </div>
  );
}
```

- [ ] **Step 8.2: 빌드 + 수동 smoke**

Run: `npm run build` → 0 에러.
Run: `npm run dev`, 로그인 후 우하단 Tweaks 버튼 클릭 → 패널 열림 → 모든 옵션이 즉시 반영, 새로고침 후 유지 확인.

- [ ] **Step 8.3: codex review**

- [ ] **Step 8.4: Commit**

```bash
git add admin-ui/src/shell/TweaksPanel.tsx
git commit -m "feat(admin-ui): TweaksPanel 5종 옵션 (theme/density/accent/tablestyle/sidebar) (Phase A.8)"
```

---

## Task 9: Command Palette (cmdk) 구현

**Files:**
- Modify: `admin-ui/src/shell/CommandPalette.tsx`

기존 `src/components/CommandPalette.tsx` 를 cmdk + role 분기로 재작성.

- [ ] **Step 9.1: 기존 CommandPalette 의 단축키 트리거 로직 (⌘K listener) 파악**

Read `admin-ui/src/components/CommandPalette.tsx` — keydown listener 가 어디서 등록되는지 확인.

- [ ] **Step 9.2: 신 CommandPalette 구현**

```tsx
import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Command as CommandIcon, Building2, Activity as ActivityIcon, ShieldCheck, Database, KeyRound, LogOut } from 'lucide-react';
import { Command, CommandDialog, CommandInput, CommandList, CommandEmpty, CommandGroup, CommandItem } from 'cmdk';
import { useMe } from '@/me/MeContext';
import { cn } from '@/lib/utils';
import { api } from '@/api/client';

type Props = { open: boolean; onOpenChange: (v: boolean) => void };

export function CommandPalette({ open, onOpenChange }: Props) {
  const navigate = useNavigate();
  const me = useMe();

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        onOpenChange(!open);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onOpenChange]);

  function go(path: string) {
    navigate(path);
    onOpenChange(false);
  }
  async function logout() {
    try { await api.post('/admin/logout', {}); } catch { /* ignore */ }
    onOpenChange(false);
    navigate('/login');
  }

  return (
    <CommandDialog open={open} onOpenChange={onOpenChange} label="Command palette">
      <div className="fixed inset-0 z-[80] bg-text/40 backdrop-blur-md" onClick={() => onOpenChange(false)} />
      <div className="fixed left-1/2 top-[20vh] -translate-x-1/2 z-[90] w-[min(560px,92vw)] rounded-xl border border-border bg-surface shadow-lg overflow-hidden">
        <Command shouldFilter className="flex flex-col">
          <div className="flex items-center gap-2 px-3 border-b border-border-subtle">
            <CommandIcon className="h-4 w-4 text-text-mute" />
            <CommandInput
              placeholder="Search pages, actions..."
              className="flex-1 py-3 bg-transparent text-[13px] text-text placeholder:text-text-faint outline-none"
            />
          </div>
          <CommandList className="max-h-[60vh] overflow-y-auto p-2">
            <CommandEmpty className="px-3 py-4 text-[13px] text-text-mute">No results.</CommandEmpty>

            <CommandGroup heading="Navigate">
              <PalItem onSelect={() => go('/tenants')} icon={<Building2 className="h-4 w-4" />} label="Tenants" />
              {me?.role === 'PLATFORM_OPERATOR' && (
                <>
                  <PalItem onSelect={() => go('/activity')} icon={<ActivityIcon className="h-4 w-4" />} label="Activity" />
                  <PalItem onSelect={() => go('/audit')} icon={<ShieldCheck className="h-4 w-4" />} label="Audit Log" />
                  <PalItem onSelect={() => go('/keys')} icon={<KeyRound className="h-4 w-4" />} label="Signing Keys" />
                  <PalItem onSelect={() => go('/mds')} icon={<Database className="h-4 w-4" />} label="MDS" />
                </>
              )}
              {me?.role === 'RP_ADMIN' && me.tenantId && (
                <PalItem onSelect={() => go(`/tenants/${me.tenantId}`)} icon={<Building2 className="h-4 w-4" />} label="My Tenant" />
              )}
            </CommandGroup>

            <CommandGroup heading="Actions">
              <PalItem onSelect={logout} icon={<LogOut className="h-4 w-4 text-danger" />} label="Logout" />
            </CommandGroup>
          </CommandList>
        </Command>
      </div>
    </CommandDialog>
  );
}

function PalItem({ onSelect, icon, label }: { onSelect: () => void; icon: React.ReactNode; label: string }) {
  return (
    <CommandItem
      onSelect={onSelect}
      className={cn(
        'flex items-center gap-2 px-2 py-1.5 rounded-sm cursor-pointer text-[13px] text-text',
        'data-[selected=true]:bg-surface-2 data-[selected=true]:text-text'
      )}
    >
      {icon}
      <span>{label}</span>
    </CommandItem>
  );
}
```

`cmdk` 의 `CommandDialog` 가 자체 portal/scrim 을 갖기 때문에 위처럼 자체 scrim 을 또 그리지 말고, **`CommandDialog` 만 쓰고 그 내부에 children 으로 input/list 를 넣는 패턴** 으로 단순화하는 게 권장이지만 cmdk@1 의 API 가 조금 까다로움. 위 코드는 작동하지만, 만약 cmdk 의 CommandDialog 에 portal 이 이미 있으면 자체 scrim 을 제거.

→ **단순화** : cmdk 의 raw `Command` 컴포넌트만 쓰고 portal/scrim 은 직접 구현:

```tsx
// 위 코드 그대로지만 <CommandDialog> 대신 직접 portal 사용
import { createPortal } from 'react-dom';
// ...
return open ? createPortal(
  <>
    <div className="fixed inset-0 z-[80] bg-text/40 backdrop-blur-md" onClick={() => onOpenChange(false)} />
    <div className="fixed left-1/2 top-[20vh] -translate-x-1/2 z-[90] w-[min(560px,92vw)] rounded-xl border border-border bg-surface shadow-lg overflow-hidden">
      <Command shouldFilter className="flex flex-col">
        {/* 위와 동일 */}
      </Command>
    </div>
  </>,
  document.body
) : null;
```

(plan 작성자: 실행 시 `cmdk` 의 최신 API 를 확인하고 위 두 패턴 중 더 깔끔한 쪽을 선택. 핵심은 ⌘K 단축키 + 검색 + role 분기 + Enter 로 navigate.)

- [ ] **Step 9.3: 빌드 + 수동 smoke**

Run: `npm run build` → 0 에러.
Run: `npm run dev`, 로그인 후 ⌘K → palette 열림 → 항목 검색/선택/네비게이션 동작.

- [ ] **Step 9.4: codex review**

- [ ] **Step 9.5: Commit**

```bash
git add admin-ui/src/shell/CommandPalette.tsx
git commit -m "feat(admin-ui): cmdk 기반 Command Palette (role 분기) (Phase A.9)"
```

---

## Task 10: IdleTimeoutDialog 구현 (기존 IdleTimeout 의 동작 보존)

**Files:**
- Modify: `admin-ui/src/shell/IdleTimeoutDialog.tsx`

기존 `src/components/IdleTimeout.tsx` 의 동작(30분 무활동 → 60초 카운트다운 → 연장/로그아웃)을 신 Dialog 컴포넌트로 옮긴다. 시각만 디자인 패키지 스타일로 교체.

- [ ] **Step 10.1: 기존 `IdleTimeout.tsx` 의 타이머 로직 그대로 읽기**

기존 파일에서:
- IDLE_MS, COUNTDOWN_S 상수
- activity event listener 목록
- 카운트다운 진행 + 로그아웃 호출 방식

이들을 신 컴포넌트에 그대로 복사한다.

- [ ] **Step 10.2: 신 IdleTimeoutDialog 작성**

```tsx
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogBody, DialogFooter } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { api } from '@/api/client';

const IDLE_MS = 30 * 60 * 1000; // 30분
const COUNTDOWN_S = 60;

const ACTIVITY_EVENTS = ['mousemove', 'keydown', 'click', 'scroll', 'touchstart'] as const;

export function IdleTimeoutDialog() {
  const [open, setOpen] = useState(false);
  const [remaining, setRemaining] = useState(COUNTDOWN_S);
  const idleTimer = useRef<number | undefined>();
  const tickTimer = useRef<number | undefined>();
  const navigate = useNavigate();

  function clearAll() {
    if (idleTimer.current) window.clearTimeout(idleTimer.current);
    if (tickTimer.current) window.clearInterval(tickTimer.current);
  }

  function scheduleIdle() {
    clearAll();
    idleTimer.current = window.setTimeout(() => {
      setOpen(true);
      setRemaining(COUNTDOWN_S);
      tickTimer.current = window.setInterval(() => {
        setRemaining((r) => {
          if (r <= 1) {
            clearAll();
            doLogout();
            return 0;
          }
          return r - 1;
        });
      }, 1000);
    }, IDLE_MS);
  }

  async function doLogout() {
    try { await api.post('/admin/logout', {}); } catch { /* ignore */ }
    setOpen(false);
    navigate('/login');
  }

  function extend() {
    setOpen(false);
    clearAll();
    scheduleIdle();
  }

  useEffect(() => {
    scheduleIdle();
    const onAct = () => { if (!open) scheduleIdle(); };
    ACTIVITY_EVENTS.forEach((e) => window.addEventListener(e, onAct, { passive: true }));
    return () => {
      clearAll();
      ACTIVITY_EVENTS.forEach((e) => window.removeEventListener(e, onAct));
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <Dialog open={open} onOpenChange={(v) => !v && extend()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>세션이 곧 만료됩니다</DialogTitle>
          <DialogDescription>
            30분 동안 활동이 없어 자동 로그아웃됩니다. 계속 사용하려면 [세션 연장] 을 클릭하세요.
          </DialogDescription>
        </DialogHeader>
        <DialogBody>
          <div className="flex items-center gap-3">
            <div className="text-[28px] font-semibold tabular-nums tracking-[-0.022em]">
              {remaining}s
            </div>
            <div className="flex-1 h-2 bg-surface-3 rounded-pill overflow-hidden">
              <div
                className="h-full bg-accent transition-all"
                style={{ width: `${(remaining / COUNTDOWN_S) * 100}%` }}
              />
            </div>
          </div>
        </DialogBody>
        <DialogFooter>
          <Button variant="ghost" onClick={doLogout}>지금 로그아웃</Button>
          <Button variant="primary" onClick={extend}>세션 연장</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

- [ ] **Step 10.3: 빌드 확인**

Run: `npm run build` → 0 에러.

- [ ] **Step 10.4: 수동 smoke (옵션)**

IDLE_MS 를 임시로 10초로 줄여서 dev 에서 동작 확인. 후 30분으로 복구.

- [ ] **Step 10.5: codex review**

- [ ] **Step 10.6: Commit**

```bash
git add admin-ui/src/shell/IdleTimeoutDialog.tsx
git commit -m "feat(admin-ui): IdleTimeoutDialog (60s 카운트다운 + 연장/로그아웃) (Phase A.10)"
```

---

## Task 11: 기존 중복 컴포넌트 제거 + cleanup

이제 `src/shell/` 가 채워졌으니 기존 `src/components/Layout.tsx`, `Sidebar.tsx`, `Header.tsx`, `CommandPalette.tsx`, `IdleTimeout.tsx`, `Dialog.tsx`, `Switch.tsx` 를 제거한다.

**Files:**
- Delete: `admin-ui/src/components/Layout.tsx`
- Delete: `admin-ui/src/components/Sidebar.tsx`
- Delete: `admin-ui/src/components/Header.tsx`
- Delete: `admin-ui/src/components/CommandPalette.tsx`
- Delete: `admin-ui/src/components/IdleTimeout.tsx`
- Delete: `admin-ui/src/components/Dialog.tsx`
- Delete: `admin-ui/src/components/Switch.tsx`

이들을 import 하던 페이지/컴포넌트는 신 위치(`@/shell/*`, `@/components/ui/*`)로 갱신한다.

- [ ] **Step 11.1: 사용처 grep**

```bash
grep -rln "from .*components/Dialog'" src/
grep -rln "from .*components/Switch'" src/
grep -rln "from .*components/Layout'" src/
grep -rln "from .*components/Sidebar'" src/
grep -rln "from .*components/Header'" src/
grep -rln "from .*components/CommandPalette'" src/
grep -rln "from .*components/IdleTimeout'" src/
```

- [ ] **Step 11.2: 사용처 import 경로 갱신**

발견된 모든 파일의 import 경로를 신 위치로 변경:
- `@/components/Dialog` → `@/components/ui/dialog` (단, API 가 다르므로 호출부 작은 수정 필요할 수 있음 — 기존 `Dialog` 가 props 기반이면 `<Dialog><DialogContent>...` 구조로 변환)
- `@/components/Switch` → `@/components/ui/switch`
- `@/components/Layout` → `@/shell/Layout` (이미 Task 7 에서 처리됨)
- `@/components/Sidebar/Header/CommandPalette/IdleTimeout` → 사용 안 함 (Layout 내부에서 처리)

**기존 `Dialog` API 가 신 shadcn Dialog 와 호환되지 않으면**: 기존 `Dialog.tsx` 를 **shadcn Dialog 를 wrapping 하는 backward-compat 컴포넌트** 로 남기는 것도 옵션. 단 plan 의 단순화 원칙상 우선 호출부를 신 API 로 변환하고 기존 파일 삭제.

기존 `Dialog` 사용 페이지가 너무 많고 변환이 부담되면, 호출부 변환을 다음 phase 로 미루고 기존 Dialog.tsx 만 유지. **결정 기준**: grep 결과로 사용처가 3개 이하면 변환, 그 이상이면 기존 Dialog.tsx 를 shadcn dialog wrapping 으로 교체 (외형은 같지만 내부는 신 컴포넌트).

→ **추천 처리**: 기존 `Dialog.tsx` 를 신 shadcn dialog 의 wrapping shim 으로 교체 (외부 API 보존), 사용처는 변경 없음. `Switch` 도 동일 패턴. 이게 가장 안전.

```tsx
// admin-ui/src/components/Dialog.tsx (신: shim)
// 기존 export 시그니처 그대로, 내부만 shadcn 위임
import * as React from 'react';
import {
  Dialog as SDialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogBody,
  DialogFooter,
} from '@/components/ui/dialog';

type Props = {
  open: boolean;
  onClose: () => void;
  title?: React.ReactNode;
  subtitle?: React.ReactNode;
  footer?: React.ReactNode;
  wide?: boolean;
  children: React.ReactNode;
};

export default function Dialog({ open, onClose, title, subtitle, footer, wide, children }: Props) {
  return (
    <SDialog open={open} onOpenChange={(v) => !v && onClose()}>
      <DialogContent wide={wide}>
        {title && (
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
            {subtitle && <DialogDescription>{subtitle}</DialogDescription>}
          </DialogHeader>
        )}
        <DialogBody>{children}</DialogBody>
        {footer && <DialogFooter>{footer}</DialogFooter>}
      </DialogContent>
    </SDialog>
  );
}
```

(기존 `Dialog` API 와 일치하지 않는 부분은 Step 11.1 grep 결과로 확인하여 보정.)

`Switch.tsx` shim 동일 패턴 — 기존 props 와 호환되는 export 유지.

- [ ] **Step 11.3: 진짜 제거할 파일들 (사용처 없음 확인 후)**

`Layout.tsx`, `Sidebar.tsx`, `Header.tsx`, `CommandPalette.tsx`, `IdleTimeout.tsx` — Task 7 에서 신 위치로 옮겼으므로, 사용처가 없는 것만 삭제:

```bash
git rm admin-ui/src/components/Layout.tsx
git rm admin-ui/src/components/Sidebar.tsx
git rm admin-ui/src/components/Header.tsx
git rm admin-ui/src/components/CommandPalette.tsx
git rm admin-ui/src/components/IdleTimeout.tsx
```

`Dialog.tsx`, `Switch.tsx` 는 shim 으로 교체 (삭제 안 함).

- [ ] **Step 11.4: 빌드 확인**

Run: `npm run build` → 0 에러.

- [ ] **Step 11.5: 수동 smoke — 전 페이지 클릭**

Run: `npm run dev`, 로그인 후:
- /tenants 진입 → 테이블 표시
- /tenants/new → 생성 폼
- /tenants/{id} → 모든 탭 클릭
- /activity → KPI + 피드
- /audit → 테이블 + Chain 검증 버튼
- /mds → 동기화 상태
- /keys → 키 목록
- ⌘K → palette
- Tweaks → 5 옵션 토글, 다크/라이트 전환, density 전환
- 각 페이지에서 toast 발생 동작 (예: 잘못된 검색) 확인

모두 정상 동작해야 함. dialog/switch 사용 페이지(Credentials revoke, ApiKey 발급 등) 도 확인.

- [ ] **Step 11.6: codex review**

- [ ] **Step 11.7: Commit**

```bash
git add admin-ui/src/components/
git commit -m "refactor(admin-ui): 기존 shell 컴포넌트 제거, Dialog/Switch 를 shadcn shim 으로 교체 (Phase A.11)"
```

---

## Task 12: 최종 수동 smoke 체크리스트 + worktree 마무리

**Files:** 없음 (검증만)

- [ ] **Step 12.1: 수동 smoke 체크리스트**

다음 항목을 순서대로 확인:

1. [ ] `npm run build` → 0 에러
2. [ ] `npm run dev` 시작
3. [ ] `/login` 진입 → 디자인 시각 OK
4. [ ] PLATFORM_OPERATOR 로 로그인 → Tenants 목록 표시
5. [ ] Sidebar 의 모든 nav 항목 클릭 → 각 페이지 로드
6. [ ] Tweaks 패널 → light/dark 토글 → 즉시 반영
7. [ ] Tweaks 패널 → compact/comfortable 토글 → 테이블 행 간격 변화
8. [ ] Tweaks 패널 → accent 5종 → 즉시 반영
9. [ ] Tweaks 패널 → table style striped → 테이블 줄무늬
10. [ ] Tweaks 패널 → sidebar icons → 사이드바 라벨 사라짐
11. [ ] Tweaks Reset → 기본값 복귀
12. [ ] 새로고침 후 Tweaks 설정 유지 확인
13. [ ] ⌘K → Command Palette 열림 → 검색 → 항목 선택 → 네비게이션
14. [ ] /tenants/new 다이얼로그 (현재는 풀페이지) → 폼 동작
15. [ ] /tenants/{id} → ApiKeys 탭 → 새 키 발급 모달 → plaintext 노출 → 회수 확인 모달
16. [ ] /tenants/{id} → Credentials 탭 → 회수 모달
17. [ ] 로그아웃 → /login 으로 이동
18. [ ] RP_ADMIN 으로 로그인 → 자기 tenant 로 자동 라우팅 확인
19. [ ] Toast 발생 (잘못된 비밀번호) → sonner 스타일 표시 + traceId 라인 (가능 시)

체크리스트 통과 시 다음 step.

- [ ] **Step 12.2: 의존성 잠금 파일 commit (이전 task 에서 누락 시)**

```bash
git status
# 미커밋 변경 있으면 추가 commit
```

- [ ] **Step 12.3: main 으로 돌아가서 merge --no-ff**

```bash
# worktree 종료
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git checkout main
git merge --no-ff worktree-admin-design-shell -m "Merge Phase A — Admin Console 디자인 토큰 + 셸 (Tailwind + shadcn)"
git push origin main   # 사용자가 원하면 - 자동 push 는 confirm 후
```

(실제 merge 는 사용자 확인 후 진행. plan 의 step 12.3 은 가이드라인.)

- [ ] **Step 12.4: 자체 worktree 정리**

`ExitWorktree` 도구 사용 (또는 `git worktree remove`).

---

## Self-Review (plan 작성 완료 후)

### Spec 커버리지 (Phase A 부분만)
- [x] Tailwind + shadcn 설치 → Task 1
- [x] tokens.css Tailwind 통합 → Task 2
- [x] shadcn 컴포넌트 (button, dialog, input/label, badge, switch, dropdown, tabs, popover, tooltip, sonner) → Task 3, 4
- [x] TweaksProvider + 5 옵션 → Task 5, 8
- [x] Toast sonner 위임 → Task 6
- [x] Shell (Layout/Sidebar/Header) → Task 7
- [x] Command Palette cmdk + role 분기 → Task 9
- [x] IdleTimeoutDialog → Task 10
- [x] 기존 페이지 동작 보존 → Task 11 (shim 패턴) + Task 12 (smoke)
- [x] 사이드바 Audit Chain 인디케이터 placeholder → Task 7 Step 7.2 마지막 줄

### Placeholder scan
- "TBD/TODO 없음" 확인. 단, Task 11 의 "사용처 grep 결과에 따라 변환 vs shim 결정" 은 합리적인 조건부 분기 (placeholder 가 아님).
- Task 9 의 "cmdk 의 최신 API 확인 후 선택" — 두 패턴 모두 제시되어 있어 placeholder 아님.

### Type 일관성
- `Tweaks` 타입의 `tablestyle` 값이 tokens.css 의 `data-tablestyle="striped"` 와 일치 (디자인 패키지 그대로).
- `sidebar` 값 `labels`/`icons` 가 tokens.css 의 `data-sidebar="icons"` 와 일치 (labels 는 기본값으로 attr 없어도 동작).
- 모든 task 의 import 경로가 `@/` 별칭 사용 통일.

### Scope check
- Phase A 에 한정 — 기능 변화 0 원칙 준수. 신규 페이지(AuditChainMonitor 등) 는 Phase B 이후.
- 한 task 당 ≤ 10분 분량으로 분할.

---

## 실행 가이드 요약

1. **각 Task 의 step 을 순서대로 진행**
2. **각 Task 마지막의 codex review step 에서 `codex review` 실행**, issue 발견 시 같은 Task 안에서 fix 후 다시 review
3. **commit 은 codex review 통과 후**
4. **빌드 실패 시 task 끝나기 전에 반드시 해결**
5. **Task 12 의 수동 smoke 체크리스트 통과 후에만 merge**
