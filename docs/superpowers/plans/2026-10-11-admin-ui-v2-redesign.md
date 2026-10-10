# admin-ui V2 리디자인 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** admin-ui 를 V2 캔버스(디자인 시스템 · 화면 30개 · 일러스트)와 동일하게 바꾼다. 기능·API·권한 분기는 유지한다.

**Architecture:** `tokens.css` 를 V2 토큰으로 교체하고, 컴포넌트 클래스는 `components.css` 한 곳에 둔다(기존 클래스 이름 체계 `.btn--primary` 등 유지 → 미전환 화면도 즉시 V2 룩). `src/ui/` 에 데이터 비의존 컴포넌트를 만들고, 셸 → 화면 순으로 재조립한다. 서버 후속 작업의 필드는 optional 타입으로 선언하고 없으면 렌더하지 않는다.

**Tech Stack:** React 18 · react-router-dom 6.26 (data router 로 전환) · Vite 5 · TypeScript 5 · Vitest 2 + Testing Library · 일반 CSS · @fontsource IBM Plex

**Spec:** `docs/superpowers/specs/2026-10-11-admin-ui-v2-redesign-design.md` — 각 태스크는 스펙 §3~§6 을 근거로 한다. 디자인 원본 파일(로컬 사본): `/private/tmp/claude-501/-Users-jaehyunjung-Git-10-work-crosscert-Passkey2/bc0559aa-de7a-4ce9-b949-d6587e6a9d7e/scratchpad/admin-v1/project/v2/*.dc.html` (+ `ds.css`). 사본이 없으면 캔버스 https://claude.ai/artifact/7Hf6x25iguc9CvU54brtPA 의 `project/v2/` 파일을 읽는다.

## Global Constraints

- 작업 디렉터리 `admin-ui/`. 명령은 모두 `admin-ui/` 에서 실행: `npm test`, `npm run lint`, `npm run build`.
- 서버(`admin-app/`, `passkey-app/`, `core/`) 코드 변경 금지. 유일한 예외 없음.
- 라이트 테마 단일. `data-theme`, `data-density`, `data-tablestyle`, `data-sidebar`, `src/tweaks/` 를 다시 만들지 않는다.
- 색은 토큰만 사용: `--brand-700 #0B4EA2`(주 액션·링크·활성), `--brand-500 #1E88D8` 은 포커스 링·차트 전용(텍스트 금지), 텍스트는 `--ink`/`--ink-2`/`--mute` 만.
- 상태 표시는 점+라벨 배지(`Badge`) 하나로 통일. 색만으로 의미 전달 금지.
- 이모지 금지. 아이콘은 `src/icons/Icons.tsx` 의 1.5~1.6px 스트로크 아이콘만. 아이콘 전용 버튼은 `aria-label` 필수.
- 외부 URL(폰트·CDN) 금지. 폰트는 `@fontsource/ibm-plex-sans-kr`, `@fontsource/ibm-plex-mono` 만.
- 일러스트는 빈 상태·온보딩 전용, 한 화면에 하나, `alt=""`. 크기는 해당 V2 아트보드에 지정된 값(예: MFA 챌린지 88px, 내 계정 96px, 복구 코드 72px)을 따르고, 아트보드에 값이 없으면 120–160px.
- 키·값 목록은 `<dl className="kv">`(4열은 `kv kv--4`) 를 쓴다. 인라인 grid 로 다시 만들지 않는다.
- 스펙 §6 의 서버 필드는 optional 로만 선언하고, 값이 `undefined` 면 해당 요소를 렌더하지 않는다(자리표시·"준비 중" 금지).
- 문구는 스펙/캔버스 V2 문구를 따른다. 기존 기능 문구(버튼 라벨 등)가 V2 와 같으면 그대로 둔다.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## Review Focus

1. **미저장 변경 + 탭 전환** — WebAuthn 탭에서 값을 바꾼 뒤 다른 탭(같은 라우트, `?tab=` 만 변경)이나 사이드바로 이동하면 이탈 확인이 떠야 한다. 검색 파라미터만 바뀌는 이동도 막아야 한다. → Task 11 테스트 `blocks search-param-only navigation`.
2. **시크릿 다이얼로그 잠금** — API 키 발급 완료·복구 코드 다이얼로그는 ESC·바깥 클릭·브라우저 뒤로가기로 닫히면 안 된다(키 영구 소실). → Task 8 테스트 `dismissible=false ignores Escape and scrim`, Task 11 은 `dirty` 와 무관하므로 해당 화면 태스크에서 `dismissible={false}` 사용을 테스트로 고정.
3. **OTP 붙여넣기 이상 입력** — "123 456", "12a456", 7자리 이상 붙여넣기. 숫자만 남겨 앞에서부터 6자리를 채우고, 6자리가 안 되면 `onComplete` 를 부르지 않는다. → Task 7 테스트 `paste with spaces/letters`.
4. **G 단축키 오작동** — 입력창·textarea·contenteditable 에 포커스가 있을 때 `g` 를 타이핑해도 이동하면 안 된다. 다이얼로그가 열려 있을 때도 무시. → Task 13 테스트 `ignores G-chord while typing`.
5. **빈 데이터 / 긴 값** — tenant 0개, credential 0건, 매우 긴 tenant 이름·RP ID(60자+)에서 레이아웃이 깨지지 않고 말줄임된다. → Task 5 `PropChip` 말줄임 테스트, 화면 태스크의 빈 상태 테스트.

## 계획 중 결정한 동작 변경 (사용자 확인 대상)

V2 를 실제 데이터·API 에 맞추면서 기존 동작이나 캔버스와 달라지는 지점. 각 태스크 본문에 근거가 있다.

| # | 태스크 | 변경 | 이유 |
|---|---|---|---|
| 1 | 15 | 새 비밀번호 설정에서 **12자 미만이면 클라이언트가 제출을 막음** (지금은 일치 여부만 검사, 서버도 길이 미검사) | V2 문구 "12자 이상으로…"·가입 요청 규칙과 일치 |
| 2 | 16·17·18 | **하드코딩된 가짜 수치 제거** — Tenants "24h ceremony 2.4M/18ms" KPI(→ KPI 3칸), Activity p95 픽스처 값, 감사 체인 "p99 920ms"·"검증 주기 60s" | 실제 API 값이 없음. 캔버스와 칸 수가 달라짐 |
| 3 | 18 | 감사 체인 행별 **"검증" 버튼을 실제 `verifyTenant` 에 연결** (지금은 동작 없음), 월간 보고서의 tenant 체크박스 제거(목록 표시만) | API 가 기간만 받음 |
| 4 | 18·20 | "60초마다 자동 검증" 문구 삭제 → "열 때·버튼으로 검증" | 서버에 스케줄러 없음 |
| 5 | 20 | Tenant ⋯ 메뉴(정지/정지 해제)를 **PLATFORM 에게만** 표시. 정지 해제는 `window.confirm` → Dialog | 서버 API 가 PLATFORM 전용이라 RP 에겐 항상 403 |
| 6 | 17 | Activity 행 전체 클릭 대신 행의 **"상세 열기" 버튼**으로만 상세를 엶 | 접근성 린트, 행 안 tenant 링크 중첩 |
| 7 | 19 | License 만료 **진행 바 미표시**(남은 일수만) | 발급일 데이터 없음 |
| 8 | 24 | Credentials 검색 `search`→`q` 버그 수정. aaguid·status 필터는 현재 페이지 안에서만 동작(지금과 동일) | 서버 파라미터 없음 |
| 9 | 27 | 설정 탭이 URL `?tab=` 에 저장됨 | 보안 정책 미저장 이탈 확인이 탭 전환에도 동작하도록 |
| 10 | 28 | 가입 승인: tenant 미선택 시 버튼을 막는 대신 제출 시 "최소 1개" 오류 표시. 동작 없던 운영자 행 ⋯ 버튼 제거 | 기존 오류 문구가 표시될 수 없던 문제 |
| 11 | 29 | MDS SKIPPED 를 실패가 아닌 "건너뜀"으로, 시스템 상태 라벨 OK/DEGRADED/DOWN → 정상/저하/중단 | V2 라벨 |
| 12 | 23 | API 키 시크릿 복사 실패 시 토스트 없음 | `SecretBox` 는 실패 피드백 없음 — 필요하면 `onCopied` 에 실패 콜백 추가 |

---

## File Structure

```
admin-ui/
  index.html                         MODIFY  외부 폰트 링크 제거
  package.json                       MODIFY  @fontsource 2개 추가
  src/main.tsx                       MODIFY  폰트 import, createBrowserRouter
  src/App.tsx                        MODIFY  Tweaks 제거, V2 셸 레이아웃, G 단축키
  src/styles/tokens.css              REWRITE V2 토큰만(변수)
  src/styles/components.css          CREATE  모든 컴포넌트·레이아웃 클래스
  src/styles/globals.css             MODIFY  import 순서, 리셋
  src/tweaks/                        DELETE
  src/assets/illustrations/*.webp    CREATE  10장
  src/assets/illustrations/index.ts  CREATE  illustrations 맵
  src/ui/                            CREATE  디자인 시스템 컴포넌트(아래)
    Button.tsx IconButton.tsx Badge.tsx StatusBadge.tsx Card.tsx Alert.tsx
    PageHeader.tsx PropChip.tsx KpiCard.tsx CopyBtn.tsx EmptyState.tsx
    Field.tsx Switch.tsx Segmented.tsx ChoiceCard.tsx Chips.tsx ChipInput.tsx
    OtpInput.tsx Dialog.tsx ToastHost.tsx Menu.tsx SecretBox.tsx
    ConfirmByTyping.tsx Stepper.tsx StatusStrip.tsx Tabs.tsx DataTable.tsx
    Pagination.tsx SaveBar.tsx useUnsavedGuard.tsx index.ts
  src/shell/Dialog.tsx StatusBadge.tsx EmptyState.tsx CopyBtn.tsx ToastHost.tsx  DELETE (ui/ 로 이동)
  src/shell/Sidebar.tsx Header.tsx Breadcrumb.tsx  REWRITE V2 셸
  src/extras/CommandPalette.tsx      REWRITE V2 그룹·경로·단축키
  src/extras/useGoShortcuts.ts       CREATE  G 체이닝 단축키
  src/extras/IdleSessionModal.tsx    MODIFY  진행 링
  src/pages/**                       화면 태스크에서 수정
  src/api/types.ts 등                화면 태스크에서 optional 필드 추가
```

---

### Task 1: 폰트 번들 포함 + 외부 URL 제거

**Files:**
- Modify: `admin-ui/package.json`, `admin-ui/index.html`, `admin-ui/src/main.tsx`
- Test: `admin-ui/src/test/noExternalAssets.test.ts`

**Interfaces:**
- Produces: CSS 폰트 패밀리 `'IBM Plex Sans KR'`, `'IBM Plex Mono'` 가 번들로 로드됨(Task 3 토큰이 사용).

- [ ] **Step 1: 실패하는 테스트 작성**

```ts
// admin-ui/src/test/noExternalAssets.test.ts
import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = join(__dirname, '..', '..');
const FORBIDDEN = /(fonts\.googleapis\.com|fonts\.gstatic\.com|cdn\.jsdelivr\.net)/;

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
}

describe('폐쇄망 대응: 외부 폰트/CDN 참조 금지', () => {
  it('index.html 에 외부 폰트 링크가 없다', () => {
    expect(readFileSync(join(ROOT, 'index.html'), 'utf8')).not.toMatch(FORBIDDEN);
  });
  it('src/ 의 ts/tsx/css 에 외부 폰트 URL 이 없다', () => {
    const offenders = walk(join(ROOT, 'src'))
      .filter((f) => /\.(tsx?|css)$/.test(f) && !f.endsWith('noExternalAssets.test.ts'))
      .filter((f) => FORBIDDEN.test(readFileSync(f, 'utf8')));
    expect(offenders).toEqual([]);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/test/noExternalAssets.test.ts`
Expected: FAIL — `index.html` 이 `cdn.jsdelivr.net` 과 `fonts.googleapis.com` 을 포함.

- [ ] **Step 3: 의존성 추가**

Run: `npm install @fontsource/ibm-plex-sans-kr@5 @fontsource/ibm-plex-mono@5`
Expected: `package.json` dependencies 에 두 패키지 추가.

- [ ] **Step 4: index.html 에서 외부 링크 제거**

`<title>` 다음의 `preconnect` 3줄과 `stylesheet` 2개(jsdelivr Pretendard, Google Fonts Geist)를 삭제한다. 결과:

```html
<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <link rel="icon" type="image/png" href="/admin/favicon.png" />
    <title>Crosscert Passkey Admin</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 5: main.tsx 에서 폰트 import**

`import './styles/globals.css';` 바로 위에 추가:

```ts
import '@fontsource/ibm-plex-sans-kr/400.css';
import '@fontsource/ibm-plex-sans-kr/500.css';
import '@fontsource/ibm-plex-sans-kr/600.css';
import '@fontsource/ibm-plex-sans-kr/700.css';
import '@fontsource/ibm-plex-mono/400.css';
import '@fontsource/ibm-plex-mono/500.css';
```

- [ ] **Step 6: 테스트·빌드 확인**

Run: `npx vitest run src/test/noExternalAssets.test.ts && npm run build && ! grep -rE "fonts\.googleapis|fonts\.gstatic|jsdelivr" dist`
Expected: 테스트 PASS, 빌드 성공, grep 결과 없음(종료코드 0). `ls dist/assets | grep woff2` 로 woff2 포함 확인.

- [ ] **Step 7: 커밋**

```bash
git add package.json package-lock.json index.html src/main.tsx src/test/noExternalAssets.test.ts
git commit -m "feat(admin-ui): IBM Plex 폰트 번들 포함, 외부 폰트 CDN 제거(폐쇄망 대응)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: data router 전환 (이탈 확인 기반)

`useBlocker` 는 data router 안에서만 동작한다. 현재 `BrowserRouter` 를 `createBrowserRouter` + `RouterProvider` 로 바꾼다. 앱 내부의 `<Routes>` 는 그대로 둔다(catch-all 라우트 아래 중첩).

**Files:**
- Modify: `admin-ui/src/main.tsx`
- Test: `admin-ui/src/test/router.test.tsx`

**Interfaces:**
- Produces: 앱 전체가 data router 컨텍스트 안에서 렌더 → Task 11 `useUnsavedGuard` 가 `useBlocker` 사용 가능.

- [ ] **Step 1: 실패하는 테스트 작성**

```tsx
// admin-ui/src/test/router.test.tsx
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

describe('main.tsx router', () => {
  it('createBrowserRouter + RouterProvider 를 쓰고 BrowserRouter 를 쓰지 않는다', () => {
    const src = readFileSync(join(__dirname, '..', 'main.tsx'), 'utf8');
    expect(src).toMatch(/createBrowserRouter/);
    expect(src).toMatch(/RouterProvider/);
    expect(src).not.toMatch(/<BrowserRouter/);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/test/router.test.tsx`
Expected: FAIL — `createBrowserRouter` 없음.

- [ ] **Step 3: main.tsx 교체**

```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import App from './App';
import '@fontsource/ibm-plex-sans-kr/400.css';
import '@fontsource/ibm-plex-sans-kr/500.css';
import '@fontsource/ibm-plex-sans-kr/600.css';
import '@fontsource/ibm-plex-sans-kr/700.css';
import '@fontsource/ibm-plex-mono/400.css';
import '@fontsource/ibm-plex-mono/500.css';
import './styles/globals.css';

// data router: useBlocker(미저장 이탈 확인)가 동작하려면 필요.
// 앱 내부 라우팅은 App 의 <Routes> 가 그대로 담당한다.
const router = createBrowserRouter([{ path: '*', element: <App /> }], { basename: '/admin' });

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <RouterProvider router={router} />
  </React.StrictMode>,
);
```

- [ ] **Step 4: 테스트·빌드**

Run: `npm test && npm run build`
Expected: 전체 PASS, 빌드 성공.

- [ ] **Step 5: 수동 확인**

Run: `npm run dev` (admin-app 이 8081 에서 동작 중이어야 함, `docs/dev-setup.md`). 브라우저에서 `/admin/tenants` 진입 → 사이드바 이동·새로고침·뒤로가기가 이전과 같이 동작.

- [ ] **Step 6: 커밋**

```bash
git add src/main.tsx src/test/router.test.tsx
git commit -m "refactor(admin-ui): BrowserRouter → createBrowserRouter(data router) 전환

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: V2 토큰 · 컴포넌트 CSS · 다크/Tweaks 제거

**Files:**
- Rewrite: `admin-ui/src/styles/tokens.css`
- Create: `admin-ui/src/styles/components.css`
- Modify: `admin-ui/src/styles/globals.css`, `admin-ui/src/App.tsx`
- Delete: `admin-ui/src/tweaks/` (전체)
- Test: `admin-ui/src/test/tokens.test.ts`

**Interfaces:**
- Produces: CSS 변수 `--brand-900/700/600/500/300/100/50`, `--ink`, `--ink-2`, `--mute`, `--line`, `--line-soft`, `--canvas`, `--sunk`, `--surface`, `--ok/--ok-soft`, `--warn/--warn-soft`, `--bad/--bad-soft`, `--plat/--plat-soft`, `--r-sm/--r/--r-lg`, `--sh-1/--sh-2`, `--font`, `--mono`. 별칭(전환기간): `--accent → --brand-700`, `--accent-soft → --brand-100`, `--text → --ink`, `--text-soft → --ink-2`, `--text-mute → --mute`, `--text-faint → #9AA6B5`, `--border → --line`, `--border-subtle → --line-soft`, `--border-strong → #C3CEDB`, `--bg → --canvas`, `--surface-2 → --canvas`, `--surface-3 → --sunk`, `--success → --ok`, `--success-soft → --ok-soft`, `--warning → --warn`, `--warning-soft → --warn-soft`, `--danger → --bad`, `--danger-soft → --bad-soft`, `--info → --brand-700`, `--info-soft → --brand-100`, `--violet → --plat`, `--violet-soft → --plat-soft`, `--shadow-sm → --sh-1`, `--shadow-lg → --sh-2`.
- Produces: 클래스(아래 components.css 전체). 이후 모든 `ui/` 컴포넌트·화면은 이 클래스만 쓴다.

- [ ] **Step 1: 실패하는 테스트 작성**

```ts
// admin-ui/src/test/tokens.test.ts
import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const S = join(__dirname, '..', 'styles');
const tokens = () => readFileSync(join(S, 'tokens.css'), 'utf8');

describe('V2 tokens', () => {
  it('CI 브랜드 색을 정의한다', () => {
    expect(tokens()).toMatch(/--brand-700:\s*#0B4EA2/i);
    expect(tokens()).toMatch(/--brand-500:\s*#1E88D8/i);
    expect(tokens()).toMatch(/--ink:\s*#16243A/i);
  });
  it('다크/밀도/테이블/사이드바 분기가 없다', () => {
    const all = tokens() + readFileSync(join(S, 'components.css'), 'utf8');
    expect(all).not.toMatch(/data-theme|data-density|data-tablestyle|data-sidebar/);
  });
  it('tweaks 폴더가 삭제되었다', () => {
    expect(existsSync(join(__dirname, '..', 'tweaks'))).toBe(false);
  });
  it('IBM Plex 를 기본 폰트로 쓴다', () => {
    expect(tokens()).toMatch(/--font:\s*'IBM Plex Sans KR'/);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/test/tokens.test.ts`
Expected: FAIL (brand 토큰 없음, components.css 없음).

- [ ] **Step 3: tokens.css 재작성**

```css
/* Crosscert Passkey Admin — Design tokens V2
   정본: V2 캔버스 project/v2/ds.css. CI(딥 블루 C + 스카이 스우시)에서 추출.
   라이트 단일 테마. 컴포넌트 클래스는 components.css. */
:root {
  /* brand */
  --brand-900: #0A2E63; --brand-700: #0B4EA2; --brand-600: #1361BF; --brand-500: #1E88D8;
  --brand-300: #7CC3EE; --brand-100: #EAF3FB; --brand-50: #F5F9FD;
  /* neutral */
  --ink: #16243A; --ink-2: #3C4A5E; --mute: #5B6778; --faint: #9AA6B5;
  --line: #DCE3EC; --line-soft: #EBF0F5; --line-strong: #C3CEDB; --input-line: #C9D3DF;
  --surface: #FFFFFF; --canvas: #F6F8FB; --sunk: #EEF2F7;
  /* status */
  --ok: #1F7A4D; --ok-soft: #E8F5EE; --ok-line: #B9DFC9; --ok-ink: #14573A;
  --warn: #9A5B00; --warn-soft: #FFF4E0; --warn-line: #F1D39A; --warn-ink: #6E4100; --warn-dot: #D98A00;
  --bad: #C0362C; --bad-soft: #FDECEA; --bad-line: #F2BDB7; --bad-ink: #8E2219;
  --plat: #5B3FB6; --plat-soft: #F1EDFB;
  --strip-ok: #7FC29B;
  /* shape */
  --r-xs: 4px; --r-sm: 6px; --r: 10px; --r-lg: 14px; --r-pill: 999px;
  --sh-1: 0 1px 2px rgba(22, 36, 58, .05);
  --sh-2: 0 16px 40px rgba(22, 36, 58, .16);
  --sh-float: 0 8px 24px rgba(22, 36, 58, .14);
  --focus-ring: 0 0 0 3px rgba(30, 136, 216, .2);
  --scrim: rgba(22, 36, 58, .32);
  /* type */
  --font: 'IBM Plex Sans KR', system-ui, -apple-system, 'Apple SD Gothic Neo', sans-serif;
  --mono: 'IBM Plex Mono', ui-monospace, SFMono-Regular, Menlo, monospace;
  /* layout */
  --sidebar-w: 248px; --topbar-h: 60px;

  /* ── 전환기간 별칭 (Task 30 에서 제거) ── */
  --accent: var(--brand-700); --accent-hover: var(--brand-900); --accent-soft: var(--brand-100); --accent-fg: #fff;
  --text: var(--ink); --text-soft: var(--ink-2); --text-mute: var(--mute); --text-faint: var(--faint);
  --border: var(--line); --border-subtle: var(--line-soft); --border-strong: var(--line-strong);
  --bg: var(--canvas); --surface-2: var(--canvas); --surface-3: var(--sunk); --surface-sunk: var(--sunk);
  --success: var(--ok); --success-soft: var(--ok-soft);
  --warning: var(--warn); --warning-soft: var(--warn-soft);
  --danger: var(--bad); --danger-soft: var(--bad-soft);
  --info: var(--brand-700); --info-soft: var(--brand-100);
  --violet: var(--plat); --violet-soft: var(--plat-soft);
  --teal: var(--brand-600); --teal-soft: var(--brand-100);
  --shadow-xs: var(--sh-1); --shadow-sm: var(--sh-1); --shadow-md: var(--sh-float); --shadow-lg: var(--sh-2);
  --radius-xs: var(--r-xs); --radius-sm: var(--r-sm); --radius: var(--r); --radius-lg: var(--r-lg); --radius-pill: var(--r-pill);
}
```

- [ ] **Step 4: components.css 작성**

```css
/* Crosscert Passkey Admin — component classes V2 (tokens.css 의 변수만 사용) */

/* ── base ───────────────────────────── */
a { color: var(--brand-700); }
a:hover { color: var(--brand-900); }
:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.mono { font-family: var(--mono); }
.muted { color: var(--mute); }
.faint { color: var(--faint); }
.num { text-align: right; font-variant-numeric: tabular-nums; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
.truncate { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.col { display: flex; flex-direction: column; }
.spacer { flex: 1 1 0; }
.stack-1 { display: flex; flex-direction: column; gap: 4px; }
.stack-2 { display: flex; flex-direction: column; gap: 8px; }
.stack-3 { display: flex; flex-direction: column; gap: 12px; }
.stack-4 { display: flex; flex-direction: column; gap: 16px; }
.stack-6 { display: flex; flex-direction: column; gap: 24px; }
.stack-8 { display: flex; flex-direction: column; gap: 32px; }
.grid-2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }
.grid-3 { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 20px; }
.grid-4 { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }
.divider { height: 1px; background: var(--line-soft); border: 0; margin: 0; }
.kbd, kbd { font-family: var(--mono); font-size: 11px; border: 1px solid var(--line); border-bottom-width: 2px; border-radius: var(--r-xs); padding: 0 5px; background: #fff; color: var(--ink-2); }
.skeleton { display: inline-block; height: 9px; border-radius: 5px; background: #E1E7EF; vertical-align: middle; }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; flex: none; }
.tag { display: inline-block; padding: 1px 6px; border-radius: var(--r-xs); font-family: var(--mono); font-size: 11px; line-height: 16px; background: var(--sunk); color: var(--ink-2); white-space: nowrap; }
.t-over { font-size: 11px; line-height: 16px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase; color: var(--mute); }

/* ── app layout ─────────────────────── */
.app { display: grid; grid-template-columns: var(--sidebar-w) minmax(0, 1fr); min-height: 100vh; background: var(--canvas); }
.content { min-width: 0; display: flex; flex-direction: column; }
.page { padding: 28px 32px 48px; display: flex; flex-direction: column; gap: 24px; min-width: 0; }
.page__head { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 12px; }
.page__title { margin: 0; font-size: 22px; line-height: 30px; font-weight: 700; letter-spacing: -.01em; }
.page__sub { margin: 4px 0 0; font-size: 13px; line-height: 20px; color: var(--ink-2); }
@media (max-width: 900px) { .app { grid-template-columns: minmax(0, 1fr); } .grid-2, .grid-3 { grid-template-columns: minmax(0, 1fr); } .grid-4 { grid-template-columns: repeat(2, minmax(0, 1fr)); } }

/* ── sidebar ────────────────────────── */
.sidebar { position: sticky; top: 0; height: 100vh; background: var(--surface); border-right: 1px solid var(--line); display: flex; flex-direction: column; gap: 22px; padding: 20px 14px; box-sizing: border-box; overflow: auto; }
.sidebar__brand { display: flex; flex-direction: column; gap: 8px; padding: 2px 8px; }
.sidebar__brand img { width: 132px; height: auto; display: block; }
.sidebar__env { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; color: var(--ink-2); }
.sidebar__env-badge { font-size: 11px; font-weight: 600; padding: 0 6px; border-radius: var(--r-xs); background: var(--ok-soft); color: var(--ok); }
.sidebar__group { display: flex; flex-direction: column; gap: 2px; }
.sidebar__label { font-size: 11px; font-weight: 600; letter-spacing: .06em; color: var(--mute); padding: 0 10px 6px; }
.nav-item { position: relative; display: flex; align-items: center; gap: 10px; min-height: 40px; padding: 0 10px; border: 0; border-radius: 8px; background: transparent; color: var(--ink-2); font: inherit; font-size: 14px; font-weight: 500; text-align: left; width: 100%; cursor: pointer; text-decoration: none; }
.nav-item:hover { background: var(--canvas); color: var(--ink); }
.nav-item--active { background: var(--brand-100); color: var(--brand-700); font-weight: 600; }
.nav-item--active::before { content: ""; position: absolute; left: -14px; top: 8px; bottom: 8px; width: 3px; border-radius: 0 3px 3px 0; background: var(--brand-700); }
.nav-item__tag { margin-left: auto; font-size: 11px; color: var(--mute); }
.nav-item__count { margin-left: auto; min-width: 18px; height: 18px; padding: 0 5px; box-sizing: border-box; border-radius: var(--r-pill); background: var(--bad); color: #fff; font-size: 11px; font-weight: 600; display: inline-flex; align-items: center; justify-content: center; }
.sidebar__foot { margin-top: auto; display: flex; flex-direction: column; gap: 12px; }
.chain-card { text-decoration: none; border: 1px solid #CFE8DA; background: #F4FAF6; border-radius: var(--r); padding: 12px; display: flex; gap: 10px; align-items: flex-start; color: inherit; cursor: pointer; text-align: left; font: inherit; width: 100%; }
.chain-card__dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 6px; background: var(--ok); flex: none; }
.chain-card__title { font-family: var(--mono); font-size: 11px; font-weight: 500; color: var(--ok); }
.chain-card__sub { font-size: 12px; color: var(--ink-2); }
.chain-card--bad { border-color: var(--bad-line); background: var(--bad-soft); }
.chain-card--bad .chain-card__dot { background: var(--bad); }
.chain-card--bad .chain-card__title { color: var(--bad); }
.chain-card--pending .chain-card__dot { background: var(--faint); }
.chain-card--pending .chain-card__title { color: var(--mute); }
.chain-card--pending { border-color: var(--line); background: var(--canvas); }
.sidebar__copy { font-size: 11px; color: var(--mute); padding: 0 8px; }

/* ── topbar ─────────────────────────── */
.topbar { position: sticky; top: 0; z-index: 30; min-height: var(--topbar-h); box-sizing: border-box; padding: 10px 28px; background: var(--surface); border-bottom: 1px solid var(--line); display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.crumbs { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 14px; color: var(--mute); }
.crumbs__item { border: 0; background: none; font: inherit; color: var(--mute); padding: 2px 4px; border-radius: var(--r-xs); cursor: pointer; }
.crumbs__item:hover { color: var(--ink); background: var(--canvas); }
.crumbs__current { color: var(--ink); font-weight: 600; padding: 2px 4px; }
.topbar__search { display: flex; align-items: center; gap: 8px; width: 300px; max-width: 100%; min-height: 38px; padding: 0 10px 0 12px; border: 1px solid var(--line); border-radius: 8px; background: var(--canvas); color: var(--mute); font: inherit; font-size: 13px; cursor: pointer; }
.topbar__search span { flex: 1 1 0; text-align: left; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.user-pill { display: flex; align-items: center; gap: 10px; min-height: 42px; padding: 0 8px 0 4px; border: 1px solid var(--line); border-radius: var(--r-pill); background: var(--surface); color: var(--ink); font: inherit; font-size: 13px; cursor: pointer; }
.avatar { width: 32px; height: 32px; border-radius: 50%; background: var(--brand-100); color: var(--brand-700); display: inline-flex; align-items: center; justify-content: center; font-weight: 700; flex: none; }
.avatar--sq { border-radius: 8px; }
.avatar--sm { width: 26px; height: 26px; font-size: 12px; }
.avatar--lg { width: 48px; height: 48px; border-radius: 12px; font-size: 20px; }

/* ── buttons ────────────────────────── */
.btn { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 36px; padding: 0 14px; border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface); color: var(--ink); font: inherit; font-size: 13px; font-weight: 500; white-space: nowrap; text-decoration: none; cursor: pointer; }
.btn:hover { border-color: var(--line-strong); background: var(--canvas); color: var(--ink); }
.btn--primary { background: var(--brand-700); border-color: var(--brand-700); color: #fff; }
.btn--primary:hover { background: var(--brand-900); border-color: var(--brand-900); color: #fff; }
.btn--ghost { border-color: transparent; background: transparent; color: var(--brand-700); }
.btn--ghost:hover { background: var(--brand-50); border-color: transparent; color: var(--brand-900); }
.btn--outline { /* legacy = secondary */ }
.btn--danger { border-color: #E9B7B2; color: var(--bad); }
.btn--danger:hover { background: var(--bad-soft); border-color: var(--bad-line); color: var(--bad); }
.btn--danger-solid { background: var(--bad); border-color: var(--bad); color: #fff; }
.btn--danger-solid:hover { background: var(--bad-ink); border-color: var(--bad-ink); color: #fff; }
.btn--xs { min-height: 26px; padding: 0 8px; font-size: 12px; }
.btn--sm { min-height: 30px; padding: 0 10px; font-size: 12px; }
.btn--lg { min-height: 44px; padding: 0 18px; font-size: 14px; }
.btn:disabled, .btn[aria-disabled="true"] { background: var(--sunk); border-color: var(--line); color: var(--mute); cursor: not-allowed; }
.icon-btn { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; border: 0; border-radius: var(--r-sm); background: transparent; color: var(--ink-2); cursor: pointer; flex: none; }
.icon-btn:hover { background: var(--canvas); color: var(--ink); }
.icon-btn--sm { width: 24px; height: 24px; }
.icon-btn--danger { color: var(--bad); }

/* ── badges ─────────────────────────── */
.badge { display: inline-flex; align-items: center; gap: 5px; padding: 2px 8px; border-radius: var(--r-pill); font-size: 12px; font-weight: 500; line-height: 16px; white-space: nowrap; background: var(--sunk); color: var(--ink-2); }
.badge--dot::before { content: ""; width: 6px; height: 6px; border-radius: 50%; background: currentColor; opacity: .85; }
.badge--success { background: var(--ok-soft); color: var(--ok); }
.badge--warning { background: var(--warn-soft); color: var(--warn); }
.badge--danger { background: var(--bad-soft); color: var(--bad); }
.badge--info, .badge--accent, .badge--teal { background: var(--brand-100); color: var(--brand-700); }
.badge--violet { background: var(--plat-soft); color: var(--plat); }

/* ── card / kpi ─────────────────────── */
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--r); box-shadow: var(--sh-1); min-width: 0; }
.card__head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; padding: 16px 20px; border-bottom: 1px solid var(--line-soft); }
.card__title { margin: 0; font-size: 16px; line-height: 24px; font-weight: 600; }
.card__sub { margin: 0; font-size: 13px; line-height: 20px; color: var(--ink-2); }
.card__body { padding: 20px; }
.card__foot { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 12px 20px; border-top: 1px solid var(--line-soft); }
.kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }
.kpis--3 { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.kpis--2 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
@media (max-width: 760px) { .kpis, .kpis--3 { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
.kpi { background: var(--surface); border: 1px solid var(--line); border-radius: var(--r); padding: 16px 18px; display: flex; flex-direction: column; gap: 4px; box-shadow: var(--sh-1); text-align: left; font: inherit; color: inherit; min-width: 0; }
.kpi--clickable { cursor: pointer; }
.kpi--clickable:hover { border-color: var(--line-strong); }
.kpi--selected { border-color: var(--brand-700); box-shadow: 0 0 0 1px var(--brand-700); }
.kpi__label, .metric-label { font-size: 12px; font-weight: 500; color: var(--mute); }
.kpi__value, .metric-value { font-size: 26px; line-height: 34px; font-weight: 700; letter-spacing: -.01em; }
.kpi__hint, .metric-delta { font-size: 12px; color: var(--ink-2); }
.kpi__hint--warn { color: var(--warn); font-weight: 600; }

/* ── page header bits ───────────────── */
.propchips { display: flex; flex-wrap: wrap; gap: 8px; }
.propchip { display: inline-flex; align-items: center; gap: 6px; min-height: 28px; max-width: 360px; padding: 0 4px 0 10px; border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface); font-size: 12px; }
.propchip__k { color: var(--mute); flex: none; }
.propchip__v { font-family: var(--mono); color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.propchip--nocopy { padding-right: 10px; }

/* ── forms ──────────────────────────── */
.label { display: block; font-size: 13px; font-weight: 600; color: var(--ink); margin-bottom: 6px; }
.hint { font-size: 12px; line-height: 16px; color: var(--mute); margin: 6px 0 0; }
.field__error { font-size: 12px; line-height: 16px; color: var(--bad); margin: 6px 0 0; }
.input { width: 100%; min-height: 38px; border: 1px solid var(--input-line); border-radius: var(--r-sm); padding: 0 12px; box-sizing: border-box; font: inherit; font-size: 13px; background: var(--surface); color: var(--ink); }
textarea.input { padding: 10px 12px; min-height: 80px; resize: vertical; }
.input::placeholder { color: #6E7A8A; }
.input:focus { outline: none; border-color: var(--brand-500); box-shadow: var(--focus-ring); }
.input--error, .input[aria-invalid="true"] { border-color: var(--bad); }
.input[readonly] { background: var(--sunk); color: var(--ink-2); }
.input--lg { min-height: 44px; font-size: 14px; }
.search { position: relative; }
.search .input { padding-left: 34px; }
.search > svg { position: absolute; left: 11px; top: 50%; transform: translateY(-50%); color: var(--mute); pointer-events: none; }
.seg { display: inline-flex; gap: 2px; padding: 3px; background: var(--sunk); border-radius: 8px; flex-wrap: wrap; }
.seg__btn { min-height: 30px; padding: 0 12px; border: 0; border-radius: 6px; background: transparent; font: inherit; font-size: 12px; font-weight: 500; color: var(--ink-2); cursor: pointer; }
.seg__btn--on { background: var(--surface); color: var(--ink); box-shadow: 0 1px 2px rgba(22, 36, 58, .12); }
.switch { display: inline-flex; align-items: center; gap: 10px; min-height: 36px; font-size: 13px; font-weight: 500; cursor: pointer; }
.switch__track { position: relative; display: inline-block; width: 36px; height: 20px; border-radius: var(--r-pill); background: var(--input-line); flex: none; transition: background .12s; }
.switch__track::after { content: ""; position: absolute; left: 2px; top: 2px; width: 16px; height: 16px; border-radius: 50%; background: #fff; transition: left .12s; }
.switch input:checked + .switch__track { background: var(--brand-700); }
.switch input:checked + .switch__track::after { left: 18px; }
.switch input:focus-visible + .switch__track { box-shadow: var(--focus-ring); }
.choices { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.choices--2 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
@media (max-width: 900px) { .choices, .choices--2 { grid-template-columns: minmax(0, 1fr); } }
.choice { display: flex; gap: 10px; align-items: flex-start; padding: 14px; border: 1px solid var(--line); border-radius: var(--r); background: var(--surface); font: inherit; text-align: left; color: var(--ink); cursor: pointer; }
.choice--on { border-color: var(--brand-700); box-shadow: 0 0 0 1px var(--brand-700); background: var(--brand-50); }
.choice__radio { width: 16px; height: 16px; border-radius: 50%; border: 1.5px solid #9AA6B5; box-sizing: border-box; flex: none; margin-top: 2px; }
.choice--on .choice__radio { border: 5px solid var(--brand-700); }
.choice__title { font-size: 13px; font-weight: 600; }
.choice__desc { font-size: 12px; color: var(--mute); }
.fchip { display: inline-flex; align-items: center; gap: 6px; min-height: 30px; padding: 0 12px; border: 1px solid var(--line); border-radius: var(--r-pill); font: inherit; font-size: 12px; font-weight: 500; background: var(--surface); color: var(--ink-2); cursor: pointer; }
.fchip--on { background: var(--brand-100); border-color: var(--brand-300); color: var(--brand-900); }
.fchip--mono { font-family: var(--mono); font-size: 11px; }
.chip { display: inline-flex; align-items: center; gap: 4px; min-height: 28px; max-width: 100%; padding: 0 4px 0 10px; border: 1px solid var(--line); border-radius: var(--r-pill); font-size: 12px; background: var(--canvas); color: var(--ink); }
.chip--added { background: var(--ok-soft); border-color: var(--ok-line); }
.chip__x { border: 0; background: none; width: 22px; height: 22px; border-radius: 50%; color: var(--mute); cursor: pointer; display: inline-flex; align-items: center; justify-content: center; }
.chip__x:hover { background: var(--sunk); color: var(--ink); }
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.applied { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 10px 20px; border-bottom: 1px solid var(--line-soft); }

/* ── settings rows (설명 좌 / 컨트롤 우) ── */
.set { display: grid; grid-template-columns: 280px minmax(0, 1fr); gap: 12px 40px; padding: 24px; border-bottom: 1px solid var(--line-soft); }
.set:last-child { border-bottom: 0; }
.set__label { margin: 0; font-size: 13px; font-weight: 600; }
.set__desc { margin: 4px 0 0; font-size: 12px; line-height: 16px; color: var(--mute); }
.desc-box { border-radius: 8px; background: var(--canvas); padding: 10px 12px; font-size: 12px; line-height: 18px; color: var(--ink-2); }
.kv { display: grid; grid-template-columns: 150px minmax(0, 1fr); gap: 12px 16px; font-size: 13px; margin: 0; }
.kv dt { color: var(--mute); }
.kv dd { margin: 0; min-width: 0; }
.kv--4 { grid-template-columns: 130px minmax(0, 1fr) 130px minmax(0, 1fr); }
@media (max-width: 760px) { .kv, .kv--4 { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 900px) { .set { grid-template-columns: minmax(0, 1fr); } }

/* ── otp ────────────────────────────── */
.otp { display: flex; gap: 8px; justify-content: center; }
.otp__cell { width: 46px; height: 54px; padding: 0; text-align: center; font-family: var(--mono); font-size: 20px; border: 1px solid var(--input-line); border-radius: var(--r-sm); background: var(--surface); color: var(--ink); }
.otp__cell:focus { outline: none; border-color: var(--brand-500); box-shadow: var(--focus-ring); }

/* ── table ──────────────────────────── */
.tw { overflow-x: auto; }
.table { width: 100%; border-collapse: collapse; font-size: 13px; }
.table th { text-align: left; font-size: 12px; font-weight: 600; color: var(--mute); padding: 10px 16px; border-bottom: 1px solid var(--line); background: var(--surface); white-space: nowrap; }
.table td { padding: 12px 16px; border-bottom: 1px solid var(--line-soft); white-space: nowrap; vertical-align: middle; }
.table tbody tr:hover td { background: var(--brand-50); }
.table tr.is-clickable { cursor: pointer; }
.table tr.is-expanded td { background: var(--brand-50); }
.table th.num, .table td.num { text-align: right; font-variant-numeric: tabular-nums; }
.table__group td { padding: 8px 16px; background: var(--canvas); font-size: 11px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase; color: var(--mute); }
.table tbody tr.table__group:hover td { background: var(--canvas); }
.table__expanded td { background: var(--brand-50); white-space: normal; padding: 14px 16px 16px; }
.table__empty td { padding: 0; }
.pager { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 12px 20px; border-top: 1px solid var(--line-soft); font-size: 12px; color: var(--mute); }
.pager select.input { width: auto; min-height: 30px; }

/* ── tabs ───────────────────────────── */
.tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--line); overflow-x: auto; }
.tabs__btn { display: inline-flex; align-items: center; gap: 6px; min-height: 44px; padding: 0 12px; white-space: nowrap; border: 0; background: none; font: inherit; color: var(--mute); font-weight: 500; border-bottom: 2px solid transparent; margin-bottom: -1px; cursor: pointer; text-decoration: none; }
.tabs__btn:hover { color: var(--ink); }
.tabs__btn--active { color: var(--brand-700); border-bottom-color: var(--brand-700); font-weight: 600; }
.tabs__dot { width: 7px; height: 7px; border-radius: 50%; background: var(--warn-dot); }
.tabs__count { min-width: 18px; height: 18px; padding: 0 5px; box-sizing: border-box; border-radius: var(--r-pill); background: var(--warn-soft); color: var(--warn); font-size: 11px; font-weight: 600; display: inline-flex; align-items: center; justify-content: center; }

/* ── alerts (legacy banner 이름 유지) ── */
.banner { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 14px 16px; border-radius: var(--r); border: 1px solid; font-size: 13px; }
.banner__icon { flex: none; display: inline-flex; }
.banner__body { flex: 1 1 240px; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.banner__title { font-weight: 600; }
.banner__actions { display: flex; gap: 8px; flex-wrap: wrap; }
.banner--success { background: var(--ok-soft); border-color: var(--ok-line); color: var(--ok-ink); }
.banner--danger { background: var(--bad-soft); border-color: var(--bad-line); color: var(--bad-ink); }
.banner--warning { background: var(--warn-soft); border-color: var(--warn-line); color: var(--warn-ink); }
.banner--info { background: var(--brand-50); border-color: #C9DFF3; color: var(--brand-900); }
.topbanner { display: flex; align-items: center; gap: 10px; padding: 10px 24px; font-size: 13px; border-bottom: 1px solid; }
.topbanner--warning { background: var(--warn-soft); border-color: var(--warn-line); color: var(--warn-ink); }
.topbanner--danger { background: var(--bad-soft); border-color: var(--bad-line); color: var(--bad-ink); }

/* ── dialog ─────────────────────────── */
.scrim { position: fixed; inset: 0; z-index: 50; background: var(--scrim); display: flex; align-items: flex-start; justify-content: center; padding: 8vh 16px; overflow: auto; }
.dialog { width: 100%; max-width: 520px; background: var(--surface); border-radius: var(--r-lg); box-shadow: var(--sh-2); display: flex; flex-direction: column; overflow: hidden; }
.dialog--wide { max-width: 760px; }
.dialog--xwide { max-width: 820px; }
.dialog__head { padding: 20px 24px 4px; display: flex; flex-direction: column; gap: 4px; }
.dialog__title { margin: 0; font-size: 18px; line-height: 26px; font-weight: 600; }
.dialog__sub { font-size: 13px; color: var(--ink-2); }
.dialog__body { padding: 16px 24px 20px; display: flex; flex-direction: column; gap: 16px; }
.dialog__foot { padding: 14px 24px; background: var(--canvas); border-top: 1px solid var(--line-soft); display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 8px; }
.dialog__foot-start { margin-right: auto; display: flex; align-items: center; gap: 8px; }

/* ── empty state ────────────────────── */
.empty { display: flex; flex-direction: column; align-items: center; gap: 10px; text-align: center; padding: 32px 20px; }
.empty__art { width: 140px; height: 140px; object-fit: contain; }
.empty__title { font-size: 16px; font-weight: 600; margin: 0; }
.empty__sub { font-size: 13px; color: var(--ink-2); max-width: 420px; margin: 0; }

/* ── menu ───────────────────────────── */
.menu-wrap { position: relative; display: inline-flex; }
.menu { position: absolute; right: 0; top: calc(100% + 6px); z-index: 40; min-width: 200px; background: var(--surface); border: 1px solid var(--line); border-radius: var(--r); box-shadow: var(--sh-2); padding: 6px; }
.menu__item { display: flex; align-items: center; gap: 8px; width: 100%; min-height: 36px; padding: 0 10px; border: 0; border-radius: var(--r-sm); background: transparent; font: inherit; font-size: 13px; color: var(--ink); text-align: left; cursor: pointer; }
.menu__item:hover, .menu__item:focus { background: var(--canvas); outline: none; }
.menu__item--danger { color: var(--bad); }
.menu__sep { height: 1px; background: var(--line-soft); margin: 6px 0; }
.menu__section { display: block; padding: 2px 10px 4px; font-size: 11px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase; color: var(--bad); }

/* ── secret / stepper / strip / savebar / propbits ── */
.secret { display: flex; gap: 8px; align-items: center; padding: 10px 12px; border: 1px dashed var(--brand-300); border-radius: var(--r-sm); background: var(--brand-50); font-family: var(--mono); font-size: 12px; word-break: break-all; }
.secret__value { flex: 1 1 0; min-width: 0; }
.stepper { display: flex; flex-direction: column; gap: 6px; }
.stepper__item { display: flex; gap: 10px; align-items: center; padding: 8px 6px; border-radius: 8px; }
.stepper__item--now { background: var(--surface); }
.stepper__mark { width: 24px; height: 24px; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 700; flex: none; background: var(--surface); color: var(--mute); border: 1.5px solid var(--input-line); }
.stepper__item--now .stepper__mark { color: var(--brand-700); border-color: var(--brand-700); }
.stepper__item--done .stepper__mark { background: var(--brand-700); color: #fff; border-color: var(--brand-700); }
.stepper__label { font-size: 13px; font-weight: 500; color: var(--mute); }
.stepper__item--now .stepper__label, .stepper__item--done .stepper__label { color: var(--ink); }
.stepper__item--now .stepper__label { font-weight: 600; }
.stepper__desc { font-size: 12px; color: var(--mute); }
.strip { display: flex; gap: 2px; align-items: center; }
.strip__bar { width: 6px; height: 18px; border-radius: 2px; background: var(--strip-ok); flex: none; }
.strip__bar--bad { background: var(--bad); }
.strip__bar--none { background: var(--line); }
.strip--fluid .strip__bar { flex: 1 1 0; width: auto; height: 20px; }
.savebar { position: sticky; bottom: 16px; z-index: 20; display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 12px 16px 12px 20px; background: var(--surface); border: 1px solid var(--brand-700); border-radius: var(--r); box-shadow: var(--sh-float); }
.savebar__dot { width: 8px; height: 8px; border-radius: 50%; background: var(--warn-dot); }
.savebar__title { font-size: 13px; font-weight: 600; }

/* ── auth shell ─────────────────────── */
.auth { min-height: 100vh; display: flex; flex-wrap: wrap; align-items: flex-start; justify-content: center; gap: 48px; padding: 56px 24px; box-sizing: border-box; background: var(--canvas); }
.auth__col { display: flex; flex-direction: column; align-items: center; gap: 16px; flex: 0 1 420px; }
.auth__logo { width: 140px; height: auto; }
.auth-card { width: 100%; max-width: 420px; background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-lg); padding: 32px; box-sizing: border-box; display: flex; flex-direction: column; gap: 20px; box-shadow: var(--sh-1); }

/* ── toast ──────────────────────────── */
.toast-rack { position: fixed; right: 20px; bottom: 20px; z-index: 60; display: flex; flex-direction: column; gap: 8px; }
.toast { display: flex; align-items: flex-start; gap: 10px; min-width: 300px; max-width: 420px; padding: 12px 14px; border-radius: var(--r); background: var(--ink); color: #fff; box-shadow: var(--sh-2); font-size: 13px; }
.toast__icon { width: 18px; height: 18px; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center; flex: none; margin-top: 1px; }
.toast__icon--ok { background: #8FD4A9; color: var(--ink); }
.toast__icon--err { background: #F2A39B; color: var(--ink); }
.toast__icon--warn { background: #F1D39A; color: var(--ink); }
.toast__title { font-weight: 600; }
.toast__sub { color: #C9D3DF; margin-top: 2px; }
.toast__trace { font-family: var(--mono); font-size: 11px; color: #9AA6B5; margin-top: 4px; }
.toast .btn--ghost { color: #C9D3DF; }
```

- [ ] **Step 5: globals.css 정리**

```css
@import './tokens.css';
@import './components.css';

* { box-sizing: border-box; }
html, body, #root { margin: 0; padding: 0; min-height: 100%; }
body { background: var(--canvas); color: var(--ink); font-family: var(--font); font-size: 14px; line-height: 1.57; -webkit-font-smoothing: antialiased; text-rendering: optimizeLegibility; }
button { font-family: inherit; }
input, textarea, select { font-family: inherit; }
code, pre { font-family: var(--mono); }
```

- [ ] **Step 6: Tweaks 제거**

`admin-ui/src/tweaks/` 폴더 삭제: `git rm -r src/tweaks`.
`src/App.tsx` 에서 다음을 삭제한다:
- import 3줄: `TweaksPanel…`, `useTweaks`, `type { Tweaks }`
- `const [t, setTweak] = useTweaks();`
- `<Sidebar … sidebarMode={t.sidebarMode} />` 의 `sidebarMode` prop
- `<TweaksPanel title="Tweaks"> … </TweaksPanel>` 블록 전체

- [ ] **Step 7: 테스트·빌드**

Run: `npx vitest run src/test/tokens.test.ts && npm test && npm run build`
Expected: 모두 PASS. (SystemInfoTab 의 `badge--success` 등 클래스 테스트는 클래스명이 유지되므로 통과.)

- [ ] **Step 8: 커밋**

```bash
git add -A src/styles src/App.tsx src/test/tokens.test.ts src/tweaks
git commit -m "feat(admin-ui): V2 토큰·컴포넌트 CSS 적용, 다크 모드·Tweaks 제거

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 일러스트 에셋

**Files:**
- Create: `admin-ui/src/assets/illustrations/{login-hero,empty-tenants,empty-credentials,all-clear,mfa,recovery-codes,no-results,api-key-issued,signup-sent,load-error}.webp`
- Create: `admin-ui/src/assets/illustrations/index.ts`
- Create: `admin-ui/src/vite-env.d.ts` (없을 때만)
- Test: `admin-ui/src/assets/illustrations/index.test.ts`

**Interfaces:**
- Produces: `import { illustrations } from '@/assets/illustrations'` → `illustrations.loginHero | emptyTenants | emptyCredentials | allClear | mfa | recoveryCodes | noResults | apiKeyIssued | signupSent | loadError` (모두 `string` URL).

- [ ] **Step 1: 실패하는 테스트**

```ts
// admin-ui/src/assets/illustrations/index.test.ts
import { describe, it, expect } from 'vitest';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { illustrations } from './index';

const FILES = ['login-hero', 'empty-tenants', 'empty-credentials', 'all-clear', 'mfa', 'recovery-codes', 'no-results', 'api-key-issued', 'signup-sent', 'load-error'];

describe('illustrations', () => {
  it('10개 webp 파일이 존재한다', () => {
    for (const f of FILES) expect(existsSync(join(__dirname, `${f}.webp`))).toBe(true);
  });
  it('맵에 10개 키가 모두 문자열로 있다', () => {
    expect(Object.keys(illustrations)).toHaveLength(10);
    for (const v of Object.values(illustrations)) expect(typeof v).toBe('string');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/assets/illustrations/index.test.ts`
Expected: FAIL — 모듈 없음.

- [ ] **Step 3: webp 변환 (원본은 캔버스 업로드 사본)**

```bash
SRC=/private/tmp/claude-501/-Users-jaehyunjung-Git-10-work-crosscert-Passkey2/bc0559aa-de7a-4ce9-b949-d6587e6a9d7e/scratchpad/admin-v1/upload
OUT=src/assets/illustrations; mkdir -p $OUT
cwebp -q 82 -resize 840 0 $SRC/gpt/v2g-login-hero.png -o $OUT/login-hero.webp
for n in empty-tenants empty-credentials all-clear mfa recovery-codes no-results; do
  cwebp -q 82 -resize 320 320 $SRC/gpt/v2g-$n.png -o $OUT/$n.webp; done
cwebp -q 82 -resize 320 320 $SRC/v2-api-key-issued.png -o $OUT/api-key-issued.webp
cwebp -q 82 -resize 320 320 $SRC/v2-signup-sent.png -o $OUT/signup-sent.webp
cwebp -q 82 -resize 320 320 $SRC/v2-load-error.png -o $OUT/load-error.webp
ls -la $OUT
```

원본 사본이 없으면 캔버스 에셋을 asset id(스펙 §3.3 표)로 내려받아 같은 이름으로 변환한다. Expected: webp 10개, 각 40KB 이하(히어로 120KB 이하).

- [ ] **Step 4: index.ts**

```ts
// admin-ui/src/assets/illustrations/index.ts
// V2 일러스트(ChatGPT Images, 흰 배경 플랫). 빈 상태·온보딩 전용, alt="" 로 사용.
import loginHero from './login-hero.webp';
import emptyTenants from './empty-tenants.webp';
import emptyCredentials from './empty-credentials.webp';
import allClear from './all-clear.webp';
import mfa from './mfa.webp';
import recoveryCodes from './recovery-codes.webp';
import noResults from './no-results.webp';
import apiKeyIssued from './api-key-issued.webp';
import signupSent from './signup-sent.webp';
import loadError from './load-error.webp';

export const illustrations = {
  loginHero, emptyTenants, emptyCredentials, allClear, mfa,
  recoveryCodes, noResults, apiKeyIssued, signupSent, loadError,
} as const;
export type IllustrationName = keyof typeof illustrations;
```

`src/vite-env.d.ts` 가 없으면 생성: `/// <reference types="vite/client" />` (webp 모듈 타입 제공).

- [ ] **Step 5: 테스트·빌드**

Run: `npx vitest run src/assets/illustrations/index.test.ts && npm run build`
Expected: PASS, `dist/assets/*.webp` 10개.

- [ ] **Step 6: 커밋**

```bash
git add src/assets/illustrations src/vite-env.d.ts
git commit -m "feat(admin-ui): V2 일러스트 10종(webp) 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 기본 UI 컴포넌트 (Button · Badge · Card · Alert · KPI · PageHeader · PropChip · CopyBtn · EmptyState)

**Files:**
- Create: `admin-ui/src/ui/{Button,IconButton,Badge,StatusBadge,Card,Alert,PageHeader,PropChip,KpiCard,CopyBtn,EmptyState,index}.tsx|ts`
- Delete: `admin-ui/src/shell/{StatusBadge,EmptyState,CopyBtn}.tsx` (import 경로 교체 후)
- Modify: StatusBadge/CopyBtn import 하는 파일(`grep -rl "@/shell/StatusBadge\|@/shell/CopyBtn" src`)
- Test: `admin-ui/src/ui/basics.test.tsx`

**Interfaces:**
- Produces (모두 `@/ui` 에서 export):
  - `Button(props: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary'|'secondary'|'ghost'|'danger'|'danger-solid'; size?: 'xs'|'sm'|'md'|'lg'; icon?: ReactNode })` — `type="button"` 기본
  - `IconButton(props: ButtonHTMLAttributes & { label: string; icon: ReactNode; size?: 'sm'|'md'; tone?: 'default'|'danger' })`
  - `type BadgeTone = 'ok'|'warn'|'bad'|'info'|'plat'|'neutral'`; `Badge({ tone?: BadgeTone; dot?: boolean; mono?: boolean; children })`
  - `StatusBadge({ status: string })` — ACTIVE→ok, REVOKED→bad, EXPIRED→neutral, SUSPENDED→bad, PENDING→warn, 라벨은 `statusLabel(status)`
  - `Card({ children, className?, as?: 'section'|'div' })`, `CardHeader({ title, sub?, actions? })`, `CardBody({ children, className? })`, `CardFooter({ children })`
  - `type AlertTone = 'ok'|'warn'|'bad'|'info'`; `Alert({ tone, title?, children?, actions?, icon?: ReactNode })` — `bad` 은 `role="alert"`, 나머지 `role="status"`
  - `PageHeader({ title, sub?, actions? })`
  - `PropChip({ label: string; value: string; copy?: boolean })`
  - `KpiCard({ label, value, hint?, hintTone?: 'default'|'warn', selected?: boolean; onClick?: () => void })`
  - `CopyBtn({ value: string; label?: string; iconOnly?: boolean })` — iconOnly 면 `IconButton`(aria-label=`${label}`), 아니면 버튼+텍스트
  - `EmptyState({ illustration?: string; title; description?; action?; compact?: boolean })`

- [ ] **Step 1: 실패하는 테스트**

```tsx
// admin-ui/src/ui/basics.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Badge, StatusBadge, Button, IconButton, KpiCard, PropChip, Alert, EmptyState } from '@/ui';

describe('ui basics', () => {
  it('Badge tone 이 기존 클래스 체계로 매핑된다', () => {
    render(<><Badge tone="ok">활성</Badge><Badge tone="bad">정지</Badge><Badge tone="plat" dot={false}>PLATFORM</Badge></>);
    expect(screen.getByText('활성')).toHaveClass('badge', 'badge--success', 'badge--dot');
    expect(screen.getByText('정지')).toHaveClass('badge--danger');
    expect(screen.getByText('PLATFORM')).toHaveClass('badge--violet');
    expect(screen.getByText('PLATFORM')).not.toHaveClass('badge--dot');
  });
  it('StatusBadge 가 상태코드를 한국어 라벨로 보여준다', () => {
    render(<StatusBadge status="ACTIVE" />);
    expect(screen.getByText('활성')).toHaveClass('badge--success');
  });
  it('Button 은 기본 type=button, variant 클래스', () => {
    render(<Button variant="danger-solid">회수</Button>);
    const b = screen.getByRole('button', { name: '회수' });
    expect(b).toHaveAttribute('type', 'button');
    expect(b).toHaveClass('btn', 'btn--danger-solid');
  });
  it('IconButton 은 label 을 aria-label 로 노출', () => {
    render(<IconButton label="더보기" icon={<span />} />);
    expect(screen.getByRole('button', { name: '더보기' })).toBeInTheDocument();
  });
  it('KpiCard onClick 이 있으면 aria-pressed 버튼', () => {
    const onClick = vi.fn();
    render(<KpiCard label="활성 Tenant" value="3" selected onClick={onClick} />);
    const b = screen.getByRole('button', { name: /활성 Tenant/ });
    expect(b).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(b);
    expect(onClick).toHaveBeenCalled();
  });
  it('KpiCard onClick 이 없으면 버튼이 아니다', () => {
    render(<KpiCard label="등록 Credential" value="10" />);
    expect(screen.queryByRole('button')).toBeNull();
  });
  it('PropChip 긴 값은 말줄임 클래스 + title 로 전체값 제공', () => {
    const long = 'passkey.'.repeat(10) + 'example.com';
    render(<PropChip label="RP ID" value={long} copy />);
    const v = screen.getByText(long);
    expect(v).toHaveClass('propchip__v');
    expect(v).toHaveAttribute('title', long);
    expect(screen.getByRole('button', { name: 'RP ID 복사' })).toBeInTheDocument();
  });
  it('Alert bad 는 role=alert', () => {
    render(<Alert tone="bad" title="위변조 의심">본문</Alert>);
    expect(screen.getByRole('alert')).toHaveTextContent('위변조 의심');
  });
  it('EmptyState 일러스트는 장식(alt="")', () => {
    const { container } = render(<EmptyState illustration="/x.webp" title="없음" />);
    expect(container.querySelector('img')).toHaveAttribute('alt', '');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/ui/basics.test.tsx`
Expected: FAIL — `@/ui` 없음.

- [ ] **Step 3: 구현**

```tsx
// admin-ui/src/ui/Button.tsx
import { ButtonHTMLAttributes, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'danger-solid';
export type ButtonSize = 'xs' | 'sm' | 'md' | 'lg';
export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; size?: ButtonSize; icon?: ReactNode };

export function Button({ variant = 'secondary', size = 'md', icon, className, children, type = 'button', ...rest }: ButtonProps) {
  return (
    <button type={type} className={cn('btn', variant !== 'secondary' && `btn--${variant}`, size !== 'md' && `btn--${size}`, className)} {...rest}>
      {icon}
      {children}
    </button>
  );
}
```

```tsx
// admin-ui/src/ui/IconButton.tsx
import { ButtonHTMLAttributes, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type IconButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { label: string; icon: ReactNode; size?: 'sm' | 'md'; tone?: 'default' | 'danger' };

export function IconButton({ label, icon, size = 'md', tone = 'default', className, type = 'button', ...rest }: IconButtonProps) {
  return (
    <button type={type} aria-label={label} title={label} className={cn('icon-btn', size === 'sm' && 'icon-btn--sm', tone === 'danger' && 'icon-btn--danger', className)} {...rest}>
      {icon}
    </button>
  );
}
```

```tsx
// admin-ui/src/ui/Badge.tsx
import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type BadgeTone = 'ok' | 'warn' | 'bad' | 'info' | 'plat' | 'neutral';
const TONE: Record<BadgeTone, string | null> = { ok: 'badge--success', warn: 'badge--warning', bad: 'badge--danger', info: 'badge--info', plat: 'badge--violet', neutral: null };

export function Badge({ tone = 'neutral', dot = true, mono, children }: { tone?: BadgeTone; dot?: boolean; mono?: boolean; children: ReactNode }) {
  return <span className={cn('badge', TONE[tone], dot && 'badge--dot', mono && 'mono')}>{children}</span>;
}
```

```tsx
// admin-ui/src/ui/StatusBadge.tsx
import { statusLabel } from '@/i18n/labels';
import { Badge, BadgeTone } from './Badge';

const MAP: Record<string, BadgeTone> = { ACTIVE: 'ok', REVOKED: 'bad', EXPIRED: 'neutral', SUSPENDED: 'bad', PENDING: 'warn' };

export function StatusBadge({ status }: { status: string }) {
  return <Badge tone={MAP[status] ?? 'neutral'}>{statusLabel(status)}</Badge>;
}
```

```tsx
// admin-ui/src/ui/Card.tsx
import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export function Card({ children, className, as: Tag = 'section' }: { children: ReactNode; className?: string; as?: 'section' | 'div' }) {
  return <Tag className={cn('card', className)}>{children}</Tag>;
}
export function CardHeader({ title, sub, actions }: { title: ReactNode; sub?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="card__head">
      <div className="stack-1" style={{ gap: 2, minWidth: 0 }}>
        <h2 className="card__title">{title}</h2>
        {sub && <p className="card__sub">{sub}</p>}
      </div>
      {actions && <><div className="spacer" />{actions}</>}
    </div>
  );
}
export function CardBody({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn('card__body', className)}>{children}</div>;
}
export function CardFooter({ children }: { children: ReactNode }) {
  return <div className="card__foot">{children}</div>;
}
```

```tsx
// admin-ui/src/ui/Alert.tsx
import { ReactNode } from 'react';
import { Icons } from '@/icons/Icons';

export type AlertTone = 'ok' | 'warn' | 'bad' | 'info';
const CLS: Record<AlertTone, string> = { ok: 'banner--success', warn: 'banner--warning', bad: 'banner--danger', info: 'banner--info' };
const ICON: Record<AlertTone, ReactNode> = { ok: <Icons.Check size={18} />, warn: <Icons.Alert size={18} />, bad: <Icons.Alert size={18} />, info: <Icons.Info size={18} /> };

export function Alert({ tone, title, children, actions, icon }: { tone: AlertTone; title?: ReactNode; children?: ReactNode; actions?: ReactNode; icon?: ReactNode }) {
  return (
    <div className={`banner ${CLS[tone]}`} role={tone === 'bad' ? 'alert' : 'status'}>
      <span className="banner__icon">{icon ?? ICON[tone]}</span>
      <div className="banner__body">
        {title && <div className="banner__title">{title}</div>}
        {children && <div>{children}</div>}
      </div>
      {actions && <div className="banner__actions">{actions}</div>}
    </div>
  );
}
```

```tsx
// admin-ui/src/ui/PageHeader.tsx
import { ReactNode } from 'react';

export function PageHeader({ title, sub, actions }: { title: ReactNode; sub?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page__head">
      <div style={{ minWidth: 0 }}>
        <h1 className="page__title">{title}</h1>
        {sub && <p className="page__sub">{sub}</p>}
      </div>
      {actions && <><div className="spacer" /><div className="row">{actions}</div></>}
    </div>
  );
}
```

```tsx
// admin-ui/src/ui/CopyBtn.tsx
import { useState } from 'react';
import { Icons } from '@/icons/Icons';
import { copyToClipboard } from '@/lib/clipboard';
import { IconButton } from './IconButton';

export function CopyBtn({ value, label = '복사', iconOnly }: { value: string; label?: string; iconOnly?: boolean }) {
  const [copied, setCopied] = useState(false);
  async function onCopy(e: React.MouseEvent) {
    e.stopPropagation();
    if (await copyToClipboard(value)) { setCopied(true); setTimeout(() => setCopied(false), 1500); }
  }
  const icon = copied ? <Icons.Check size={13} /> : <Icons.Copy size={13} />;
  if (iconOnly) return <IconButton label={label} icon={icon} size="sm" onClick={(e) => void onCopy(e)} />;
  return (
    <button type="button" className="btn btn--sm" onClick={(e) => void onCopy(e)}>
      {icon}{copied ? '복사됨' : label}
    </button>
  );
}
```

```tsx
// admin-ui/src/ui/PropChip.tsx
import { CopyBtn } from './CopyBtn';

export function PropChip({ label, value, copy }: { label: string; value: string; copy?: boolean }) {
  return (
    <span className={copy ? 'propchip' : 'propchip propchip--nocopy'}>
      <span className="propchip__k">{label}</span>
      <span className="propchip__v" title={value}>{value}</span>
      {copy && <CopyBtn value={value} label={`${label} 복사`} iconOnly />}
    </span>
  );
}
```

```tsx
// admin-ui/src/ui/KpiCard.tsx
import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

type Props = { label: ReactNode; value: ReactNode; hint?: ReactNode; hintTone?: 'default' | 'warn'; selected?: boolean; onClick?: () => void };

export function KpiCard({ label, value, hint, hintTone = 'default', selected, onClick }: Props) {
  const body = (
    <>
      <span className="kpi__label">{label}</span>
      <span className="kpi__value">{value}</span>
      {hint != null && <span className={cn('kpi__hint', hintTone === 'warn' && 'kpi__hint--warn')}>{hint}</span>}
    </>
  );
  if (onClick) {
    return <button type="button" className={cn('kpi', 'kpi--clickable', selected && 'kpi--selected')} aria-pressed={!!selected} onClick={onClick}>{body}</button>;
  }
  return <div className="kpi">{body}</div>;
}
```

```tsx
// admin-ui/src/ui/EmptyState.tsx
import { ReactNode } from 'react';

export function EmptyState({ illustration, title, description, action, compact }: { illustration?: string; title: ReactNode; description?: ReactNode; action?: ReactNode; compact?: boolean }) {
  return (
    <div className="empty" style={compact ? { padding: '20px 16px' } : undefined}>
      {illustration && <img className="empty__art" src={illustration} alt="" style={compact ? { width: 104, height: 104 } : undefined} />}
      <p className="empty__title">{title}</p>
      {description && <p className="empty__sub">{description}</p>}
      {action}
    </div>
  );
}
```

```ts
// admin-ui/src/ui/index.ts  (이후 태스크가 export 를 추가한다)
export * from './Button';
export * from './IconButton';
export * from './Badge';
export * from './StatusBadge';
export * from './Card';
export * from './Alert';
export * from './PageHeader';
export * from './PropChip';
export * from './KpiCard';
export * from './CopyBtn';
export * from './EmptyState';
```

- [ ] **Step 4: shell 사본 삭제 + import 교체**

```bash
git rm src/shell/StatusBadge.tsx src/shell/EmptyState.tsx src/shell/CopyBtn.tsx
grep -rl "@/shell/StatusBadge\|@/shell/CopyBtn\|@/shell/EmptyState" src | xargs sed -i '' \
  -e "s#import { StatusBadge } from '@/shell/StatusBadge';#import { StatusBadge } from '@/ui';#" \
  -e "s#import { CopyBtn } from '@/shell/CopyBtn';#import { CopyBtn } from '@/ui';#" \
  -e "s#import { EmptyState } from '@/shell/EmptyState';#import { EmptyState } from '@/ui';#"
grep -rn "@/shell/\(StatusBadge\|CopyBtn\|EmptyState\)" src || echo "none left"
```

- [ ] **Step 5: 테스트**

Run: `npx vitest run src/ui/basics.test.tsx && npm test && npm run build`
Expected: 모두 PASS. (StatusBadge 매핑 변경으로 `SUSPENDED` 가 warning→danger, `EXPIRED` 가 warning→neutral 로 바뀐다 — V2 디자인 기준. 이 매핑을 검사하는 기존 테스트가 있으면 V2 기준으로 갱신.)

- [ ] **Step 6: 커밋**

```bash
git add -A src/ui src/shell src/pages
git commit -m "feat(admin-ui): ui/ 기본 컴포넌트(Button·Badge·Card·Alert·KPI·PropChip·EmptyState) 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 폼 컨트롤 (Field · Switch · Segmented · ChoiceCard · 칩 · ChipInput)

**Files:**
- Create: `admin-ui/src/ui/{Field,Switch,Segmented,ChoiceCard,Chips,ChipInput}.tsx`
- Modify: `admin-ui/src/ui/index.ts`
- Test: `admin-ui/src/ui/forms.test.tsx`

**Interfaces:**
- Produces:
  - `Field({ label: ReactNode; htmlFor?: string; help?: ReactNode; error?: ReactNode; required?: boolean; children })` — error 가 있으면 `.field__error`(id=`${htmlFor}-error`)
  - `Switch({ checked: boolean; onChange: (v: boolean) => void; label: ReactNode; disabled?: boolean })` — 숨은 `input[type=checkbox][role=switch]`
  - `Segmented<T extends string>({ options: { value: T; label: ReactNode }[]; value: T; onChange: (v: T) => void; ariaLabel: string })` — `role="group"`, 각 버튼 `aria-pressed`
  - `ChoiceCard({ checked: boolean; onSelect: () => void; title: ReactNode; description?: ReactNode })` — `role="radio"` `aria-checked`; 부모는 `<div className="choices" role="radiogroup" aria-label=…>`
  - `FilterChip({ active: boolean; onClick: () => void; mono?: boolean; children })` — `aria-pressed`
  - `RemovableChip({ children; onRemove: () => void; removeLabel: string; tone?: 'default'|'added'; mono?: boolean })`
  - `ChipInput({ values: string[]; onChange: (next: string[]) => void; placeholder: string; ariaLabel: string; validate?: (v: string) => string | null; mono?: boolean; addedValues?: string[] })` — Enter 시 trim, 빈값 무시, 중복 무시, validate 오류면 추가 안 하고 `.field__error` 표시

- [ ] **Step 1: 실패하는 테스트**

```tsx
// admin-ui/src/ui/forms.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useState } from 'react';
import { Switch, Segmented, ChoiceCard, FilterChip, ChipInput, Field } from '@/ui';

describe('forms', () => {
  it('Switch 토글', () => {
    const onChange = vi.fn();
    render(<Switch checked={false} onChange={onChange} label="MFA 필수" />);
    fireEvent.click(screen.getByRole('switch', { name: 'MFA 필수' }));
    expect(onChange).toHaveBeenCalledWith(true);
  });
  it('Segmented aria-pressed 와 onChange', () => {
    const onChange = vi.fn();
    render(<Segmented ariaLabel="기간" value="7d" onChange={onChange} options={[{ value: '24h', label: '24h' }, { value: '7d', label: '7d' }]} />);
    expect(screen.getByRole('button', { name: '7d' })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('button', { name: '24h' }));
    expect(onChange).toHaveBeenCalledWith('24h');
  });
  it('ChoiceCard role=radio', () => {
    const onSelect = vi.fn();
    render(<div role="radiogroup" aria-label="mode"><ChoiceCard checked={false} onSelect={onSelect} title="허용 목록" /></div>);
    fireEvent.click(screen.getByRole('radio', { name: /허용 목록/ }));
    expect(onSelect).toHaveBeenCalled();
  });
  it('FilterChip aria-pressed', () => {
    render(<FilterChip active onClick={() => {}}>AUTH_*</FilterChip>);
    expect(screen.getByRole('button', { name: 'AUTH_*' })).toHaveAttribute('aria-pressed', 'true');
  });
  it('ChipInput: Enter 로 추가, 중복·빈값 무시, validate 오류는 추가하지 않고 표시', () => {
    function Host() {
      const [v, setV] = useState<string[]>(['https://a.com']);
      return <ChipInput values={v} onChange={setV} placeholder="origin" ariaLabel="origin 추가" validate={(s) => (s.startsWith('https://') ? null : 'https:// 로 시작해야 합니다')} />;
    }
    render(<Host />);
    const input = screen.getByRole('textbox', { name: 'origin 추가' });
    fireEvent.change(input, { target: { value: '  https://b.com ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getByText('https://b.com')).toBeInTheDocument();
    fireEvent.change(input, { target: { value: 'https://a.com' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getAllByText('https://a.com')).toHaveLength(1);
    fireEvent.change(input, { target: { value: 'http://c.com' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.queryByText('http://c.com')).toBeNull();
    expect(screen.getByText('https:// 로 시작해야 합니다')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'https://a.com 삭제' }));
    expect(screen.queryByText('https://a.com')).toBeNull();
  });
  it('Field 오류 표시', () => {
    render(<Field label="이메일" htmlFor="em" error="필수"><input id="em" /></Field>);
    expect(screen.getByText('필수')).toHaveClass('field__error');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/ui/forms.test.tsx` → FAIL (export 없음).

- [ ] **Step 3: 구현**

```tsx
// admin-ui/src/ui/Field.tsx
import { ReactNode } from 'react';

export function Field({ label, htmlFor, help, error, required, children }: { label: ReactNode; htmlFor?: string; help?: ReactNode; error?: ReactNode; required?: boolean; children: ReactNode }) {
  return (
    <div>
      <label className="label" htmlFor={htmlFor}>{label}{required && <span style={{ color: 'var(--bad)' }}> *</span>}</label>
      {children}
      {error ? <p className="field__error" id={htmlFor ? `${htmlFor}-error` : undefined}>{error}</p> : help ? <p className="hint">{help}</p> : null}
    </div>
  );
}
```

```tsx
// admin-ui/src/ui/Switch.tsx
import { ReactNode } from 'react';

export function Switch({ checked, onChange, label, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: ReactNode; disabled?: boolean }) {
  return (
    <label className="switch">
      <input type="checkbox" role="switch" className="sr-only" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      <span className="switch__track" aria-hidden="true" />
      <span>{label}</span>
    </label>
  );
}
```

```tsx
// admin-ui/src/ui/Segmented.tsx
import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export function Segmented<T extends string>({ options, value, onChange, ariaLabel }: { options: { value: T; label: ReactNode }[]; value: T; onChange: (v: T) => void; ariaLabel: string }) {
  return (
    <div className="seg" role="group" aria-label={ariaLabel}>
      {options.map((o) => (
        <button key={o.value} type="button" className={cn('seg__btn', o.value === value && 'seg__btn--on')} aria-pressed={o.value === value} onClick={() => onChange(o.value)}>{o.label}</button>
      ))}
    </div>
  );
}
```

```tsx
// admin-ui/src/ui/ChoiceCard.tsx
import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export function ChoiceCard({ checked, onSelect, title, description }: { checked: boolean; onSelect: () => void; title: ReactNode; description?: ReactNode }) {
  return (
    <button type="button" role="radio" aria-checked={checked} className={cn('choice', checked && 'choice--on')} onClick={onSelect}>
      <span className="choice__radio" aria-hidden="true" />
      <span className="stack-1" style={{ gap: 2 }}>
        <span className="choice__title">{title}</span>
        {description && <span className="choice__desc">{description}</span>}
      </span>
    </button>
  );
}
```

```tsx
// admin-ui/src/ui/Chips.tsx
import { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { Icons } from '@/icons/Icons';

export function FilterChip({ active, onClick, mono, children }: { active: boolean; onClick: () => void; mono?: boolean; children: ReactNode }) {
  return <button type="button" className={cn('fchip', active && 'fchip--on', mono && 'fchip--mono')} aria-pressed={active} onClick={onClick}>{children}</button>;
}

export function RemovableChip({ children, onRemove, removeLabel, tone = 'default', mono }: { children: ReactNode; onRemove: () => void; removeLabel: string; tone?: 'default' | 'added'; mono?: boolean }) {
  return (
    <span className={cn('chip', tone === 'added' && 'chip--added', mono && 'mono')}>
      <span className="truncate">{children}</span>
      <button type="button" className="chip__x" aria-label={removeLabel} onClick={onRemove}><Icons.X size={12} /></button>
    </span>
  );
}
```

```tsx
// admin-ui/src/ui/ChipInput.tsx
import { useState, KeyboardEvent } from 'react';
import { RemovableChip } from './Chips';

type Props = { values: string[]; onChange: (next: string[]) => void; placeholder: string; ariaLabel: string; validate?: (v: string) => string | null; mono?: boolean; addedValues?: string[] };

export function ChipInput({ values, onChange, placeholder, ariaLabel, validate, mono, addedValues = [] }: Props) {
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);
  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    const v = draft.trim();
    if (!v) return;
    const err = validate?.(v) ?? null;
    if (err) { setError(err); return; }
    setError(null);
    setDraft('');
    if (!values.includes(v)) onChange([...values, v]);
  }
  return (
    <div className="stack-2">
      {values.length > 0 && (
        <div className="chips">
          {values.map((v) => (
            <RemovableChip key={v} mono={mono} tone={addedValues.includes(v) ? 'added' : 'default'} removeLabel={`${v} 삭제`} onRemove={() => onChange(values.filter((x) => x !== v))}>{v}</RemovableChip>
          ))}
        </div>
      )}
      <input className={mono ? 'input mono' : 'input'} aria-label={ariaLabel} aria-invalid={!!error} placeholder={placeholder} value={draft}
        onChange={(e) => { setDraft(e.target.value); if (error) setError(null); }} onKeyDown={onKeyDown} />
      {error && <p className="field__error" role="alert">{error}</p>}
    </div>
  );
}
```

`src/ui/index.ts` 에 추가:

```ts
export * from './Field';
export * from './Switch';
export * from './Segmented';
export * from './ChoiceCard';
export * from './Chips';
export * from './ChipInput';
```

- [ ] **Step 4: 테스트** — Run: `npx vitest run src/ui/forms.test.tsx` → PASS

- [ ] **Step 5: 커밋**

```bash
git add src/ui
git commit -m "feat(admin-ui): ui 폼 컨트롤(Field·Switch·Segmented·ChoiceCard·칩·ChipInput)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: OtpInput

**Files:**
- Create: `admin-ui/src/ui/OtpInput.tsx`; Modify: `admin-ui/src/ui/index.ts`
- Test: `admin-ui/src/ui/OtpInput.test.tsx`

**Interfaces:**
- Produces: `OtpInput({ length?: number /*6*/; value: string; onChange: (v: string) => void; onComplete?: (v: string) => void; disabled?: boolean; ariaLabel?: string /*'인증 코드'*/; autoFocus?: boolean })` — value 는 숫자만, 최대 length. 칸 i 의 aria-label: `${ariaLabel} ${i+1}번째 자리`. `<fieldset>` + `<legend className="sr-only">`.

- [ ] **Step 1: 실패하는 테스트**

```tsx
// admin-ui/src/ui/OtpInput.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useState } from 'react';
import { OtpInput } from '@/ui';

function Host({ onComplete }: { onComplete: (v: string) => void }) {
  const [v, setV] = useState('');
  return <OtpInput value={v} onChange={setV} onComplete={onComplete} />;
}
const cells = () => screen.getAllByRole('textbox');

describe('OtpInput', () => {
  it('6칸을 렌더한다', () => {
    render(<Host onComplete={vi.fn()} />);
    expect(cells()).toHaveLength(6);
    expect(screen.getByRole('textbox', { name: '인증 코드 1번째 자리' })).toBeInTheDocument();
  });
  it('한 자리씩 입력하면 다음 칸으로 이동하고 6자리에서 onComplete', () => {
    const onComplete = vi.fn();
    render(<Host onComplete={onComplete} />);
    '123456'.split('').forEach((d, i) => fireEvent.change(cells()[i], { target: { value: d } }));
    expect(onComplete).toHaveBeenCalledWith('123456');
    expect(onComplete).toHaveBeenCalledTimes(1);
  });
  it('숫자가 아닌 입력은 무시', () => {
    const onComplete = vi.fn();
    render(<Host onComplete={onComplete} />);
    fireEvent.change(cells()[0], { target: { value: 'a' } });
    expect(cells()[0]).toHaveValue('');
  });
  it('paste with spaces/letters: 숫자만 남겨 앞에서부터 채우고 6자리면 완료', () => {
    const onComplete = vi.fn();
    render(<Host onComplete={onComplete} />);
    fireEvent.paste(cells()[0], { clipboardData: { getData: () => '12a 34-56789' } });
    expect(cells().map((c) => (c as HTMLInputElement).value).join('')).toBe('123456');
    expect(onComplete).toHaveBeenCalledWith('123456');
  });
  it('6자리 미만 붙여넣기는 완료하지 않는다', () => {
    const onComplete = vi.fn();
    render(<Host onComplete={onComplete} />);
    fireEvent.paste(cells()[0], { clipboardData: { getData: () => '123 4' } });
    expect(onComplete).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(cells()[4]);
  });
  it('빈 칸에서 Backspace 는 이전 칸을 지우고 포커스 이동', () => {
    render(<Host onComplete={vi.fn()} />);
    fireEvent.change(cells()[0], { target: { value: '1' } });
    fireEvent.change(cells()[1], { target: { value: '2' } });
    fireEvent.keyDown(cells()[2], { key: 'Backspace' });
    expect(cells()[1]).toHaveValue('');
    expect(document.activeElement).toBe(cells()[1]);
  });
});
```

- [ ] **Step 2: 실패 확인** — Run: `npx vitest run src/ui/OtpInput.test.tsx` → FAIL

- [ ] **Step 3: 구현**

```tsx
// admin-ui/src/ui/OtpInput.tsx
import { useRef, ClipboardEvent, KeyboardEvent } from 'react';

type Props = { length?: number; value: string; onChange: (v: string) => void; onComplete?: (v: string) => void; disabled?: boolean; ariaLabel?: string; autoFocus?: boolean };

export function OtpInput({ length = 6, value, onChange, onComplete, disabled, ariaLabel = '인증 코드', autoFocus }: Props) {
  const refs = useRef<(HTMLInputElement | null)[]>([]);
  const digits = Array.from({ length }, (_, i) => value[i] ?? '');

  function commit(next: string, focusIndex: number) {
    const v = next.replace(/\D/g, '').slice(0, length);
    onChange(v);
    refs.current[Math.min(focusIndex, length - 1)]?.focus();
    if (v.length === length) onComplete?.(v);
  }
  function setAt(i: number, d: string) {
    const arr = digits.slice();
    arr[i] = d;
    // 앞 칸이 비어 있으면 그 자리로 당겨 채운다(값은 항상 연속 숫자열)
    return arr.join('');
  }
  function onInput(i: number, raw: string) {
    const d = raw.replace(/\D/g, '').slice(-1);
    if (!d) return;
    commit(setAt(i, d), i + 1);
  }
  function onPaste(e: ClipboardEvent<HTMLInputElement>) {
    e.preventDefault();
    const only = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, length);
    if (!only) return;
    commit(only, only.length);
  }
  function onKeyDown(i: number, e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Backspace' && !digits[i] && i > 0) {
      e.preventDefault();
      onChange(digits.slice(0, i - 1).join(''));
      refs.current[i - 1]?.focus();
    } else if (e.key === 'ArrowLeft' && i > 0) refs.current[i - 1]?.focus();
    else if (e.key === 'ArrowRight' && i < length - 1) refs.current[i + 1]?.focus();
  }
  return (
    <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
      <legend className="sr-only">{ariaLabel} {length}자리</legend>
      <div className="otp">
        {digits.map((d, i) => (
          <input key={i} ref={(el) => { refs.current[i] = el; }} className="otp__cell" inputMode="numeric" autoComplete={i === 0 ? 'one-time-code' : 'off'}
            aria-label={`${ariaLabel} ${i + 1}번째 자리`} value={d} disabled={disabled} autoFocus={autoFocus && i === 0} maxLength={1}
            onChange={(e) => onInput(i, e.target.value)} onPaste={onPaste} onKeyDown={(e) => onKeyDown(i, e)} />
        ))}
      </div>
    </fieldset>
  );
}
```

`index.ts` 에 `export * from './OtpInput';` 추가.

- [ ] **Step 4: 테스트** — Run: `npx vitest run src/ui/OtpInput.test.tsx` → PASS

- [ ] **Step 5: 커밋**

```bash
git add src/ui
git commit -m "feat(admin-ui): OtpInput(6칸·붙여넣기 분배·자동 완료)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Dialog 확장 + ToastHost 이동

**Files:**
- Create: `admin-ui/src/ui/Dialog.tsx`, `admin-ui/src/ui/ToastHost.tsx`
- Delete: `admin-ui/src/shell/Dialog.tsx`, `admin-ui/src/shell/ToastHost.tsx`
- Modify: 이 둘을 import 하는 모든 파일(약 30개, `grep -rl "@/shell/Dialog\|@/shell/ToastHost" src`)
- Test: `admin-ui/src/ui/Dialog.test.tsx`

**Interfaces:**
- Produces:
  - `Dialog({ open: boolean; onClose: () => void; title?: ReactNode; sub?: ReactNode; children?: ReactNode; footer?: ReactNode; footerStart?: ReactNode; wide?: boolean | 'x'; dismissible?: boolean /*true*/; closeOnScrim?: boolean /*legacy, true*/; initialFocusRef?: RefObject<HTMLElement>; role?: 'dialog'|'alertdialog'; ariaLabel?: string })`
    - `dismissible=false` → ESC·스크림 클릭 모두 무시. `closeOnScrim=false` → 스크림만 무시(기존 동작).
    - 열릴 때 `initialFocusRef` 또는 첫 포커스 가능 요소에 포커스. Tab/Shift+Tab 은 다이얼로그 안에서 순환. 닫힐 때 열기 전 포커스 요소로 복귀.
    - `aria-modal="true"`, `aria-labelledby` = 제목 id(제목이 없으면 `ariaLabel`).
  - `ToastHost`, `useToast`, `type ToastInput` — 기존 시그니처 그대로(`@/ui` 에서 export).

- [ ] **Step 1: 실패하는 테스트**

```tsx
// admin-ui/src/ui/Dialog.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useRef } from 'react';
import { Dialog } from '@/ui';

describe('Dialog', () => {
  it('기본: Escape 와 스크림 클릭으로 닫힌다', () => {
    const onClose = vi.fn();
    render(<Dialog open onClose={onClose} title="제목"><button>a</button></Dialog>);
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
    fireEvent.mouseDown(document.querySelector('.scrim')!);
    expect(onClose).toHaveBeenCalledTimes(2);
  });
  it('dismissible=false ignores Escape and scrim', () => {
    const onClose = vi.fn();
    render(<Dialog open onClose={onClose} title="시크릿" dismissible={false}><button>a</button></Dialog>);
    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.mouseDown(document.querySelector('.scrim')!);
    expect(onClose).not.toHaveBeenCalled();
  });
  it('제목으로 라벨링되고 aria-modal', () => {
    render(<Dialog open onClose={() => {}} title="신규 Tenant 생성" />);
    const d = screen.getByRole('dialog', { name: '신규 Tenant 생성' });
    expect(d).toHaveAttribute('aria-modal', 'true');
  });
  it('role=alertdialog 지원', () => {
    render(<Dialog open onClose={() => {}} title="정지" role="alertdialog" />);
    expect(screen.getByRole('alertdialog', { name: '정지' })).toBeInTheDocument();
  });
  it('initialFocusRef 에 포커스', () => {
    function Host() {
      const ref = useRef<HTMLButtonElement>(null);
      return <Dialog open onClose={() => {}} title="t" initialFocusRef={ref} footer={<><button ref={ref}>취소</button><button>정지</button></>} />;
    }
    render(<Host />);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '취소' }));
  });
  it('Tab 이 다이얼로그 안에서 순환한다', () => {
    render(<Dialog open onClose={() => {}} title="t" footer={<><button>하나</button><button>둘</button></>} />);
    const two = screen.getByRole('button', { name: '둘' });
    two.focus();
    fireEvent.keyDown(two, { key: 'Tab' });
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '하나' }));
  });
});
```

- [ ] **Step 2: 실패 확인** — Run: `npx vitest run src/ui/Dialog.test.tsx` → FAIL

- [ ] **Step 3: 구현**

```tsx
// admin-ui/src/ui/Dialog.tsx
import { useEffect, useId, useRef, ReactNode, RefObject, KeyboardEvent } from 'react';
import { cn } from '@/lib/cn';

type Props = {
  open: boolean; onClose: () => void; title?: ReactNode; sub?: ReactNode; children?: ReactNode;
  footer?: ReactNode; footerStart?: ReactNode; wide?: boolean | 'x'; dismissible?: boolean; closeOnScrim?: boolean;
  initialFocusRef?: RefObject<HTMLElement>; role?: 'dialog' | 'alertdialog'; ariaLabel?: string;
};
const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function Dialog({ open, onClose, title, sub, children, footer, footerStart, wide, dismissible = true, closeOnScrim = true, initialFocusRef, role = 'dialog', ariaLabel }: Props) {
  const titleId = useId();
  const boxRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open || !dismissible) return;
    const k = (e: globalThis.KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, [open, dismissible, onClose]);

  useEffect(() => {
    if (!open) return;
    const prev = document.activeElement as HTMLElement | null;
    const target = initialFocusRef?.current ?? boxRef.current?.querySelector<HTMLElement>(FOCUSABLE);
    target?.focus();
    return () => { prev?.focus?.(); };
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  function trap(e: KeyboardEvent<HTMLDivElement>) {
    if (e.key !== 'Tab' || !boxRef.current) return;
    const els = Array.from(boxRef.current.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (els.length === 0) return;
    const first = els[0], last = els[els.length - 1];
    if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
  }

  if (!open) return null;
  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget && dismissible && closeOnScrim) onClose(); }}>
      <div ref={boxRef} className={cn('dialog', wide === true && 'dialog--wide', wide === 'x' && 'dialog--xwide')} role={role} aria-modal="true"
        aria-labelledby={title ? titleId : undefined} aria-label={title ? undefined : ariaLabel} onKeyDown={trap}>
        {(title || sub) && (
          <div className="dialog__head">
            {title && <h3 className="dialog__title" id={titleId}>{title}</h3>}
            {sub && <div className="dialog__sub">{sub}</div>}
          </div>
        )}
        {children && <div className="dialog__body">{children}</div>}
        {(footer || footerStart) && (
          <div className="dialog__foot">
            {footerStart && <div className="dialog__foot-start">{footerStart}</div>}
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}
```

`ui/ToastHost.tsx`: 기존 `shell/ToastHost.tsx` 내용을 그대로 옮긴다(`git mv src/shell/ToastHost.tsx src/ui/ToastHost.tsx`). 닫기 버튼의 `className="btn btn--ghost btn--xs"` 는 유지.

`index.ts` 에 추가:

```ts
export * from './Dialog';
export * from './ToastHost';
```

- [ ] **Step 4: import 경로 교체**

```bash
git rm src/shell/Dialog.tsx
grep -rl "@/shell/Dialog\|@/shell/ToastHost" src | xargs sed -i '' \
  -e "s#import { Dialog } from '@/shell/Dialog';#import { Dialog } from '@/ui';#" \
  -e "s#from '@/shell/ToastHost'#from '@/ui'#"
grep -rn "@/shell/Dialog\|@/shell/ToastHost" src || echo "none left"
```

- [ ] **Step 5: 테스트** — Run: `npx vitest run src/ui/Dialog.test.tsx && npm test && npm run build` → 모두 PASS. (IdleSessionModal 은 `closeOnScrim={false}` 를 쓰므로 동작 동일.)

- [ ] **Step 6: 커밋**

```bash
git add -A src/ui src/shell src/pages src/extras src/App.tsx
git commit -m "feat(admin-ui): Dialog 확장(dismissible·포커스 트랩·initialFocus·alertdialog), ToastHost ui/ 이동

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Menu · SecretBox · ConfirmByTyping · Stepper · StatusStrip

**Files:**
- Create: `admin-ui/src/ui/{Menu,SecretBox,ConfirmByTyping,Stepper,StatusStrip}.tsx`; Modify: `index.ts`
- Test: `admin-ui/src/ui/misc.test.tsx`

**Interfaces:**
- Produces:
  - `type MenuItem = { label: ReactNode; onSelect: () => void; icon?: ReactNode; danger?: boolean }`; `Menu({ label: string /*트리거 aria-label*/; items: MenuItem[]; dangerItems?: MenuItem[]; dangerTitle?: string /*'위험'*/; trigger?: ReactNode })` — 기본 트리거는 `Icons.Dots` IconButton. 열리면 첫 항목 포커스, ↑↓ 이동(순환), Enter/Space 선택, Escape 닫고 트리거 포커스, 바깥 클릭 닫힘. `role="menu"`, 항목 `role="menuitem"`.
  - `SecretBox({ value: string; copyLabel?: string /*'클립보드'*/; onCopied?: () => void })`
  - `ConfirmByTyping({ expected: string; label: ReactNode; onMatchChange: (ok: boolean) => void; id?: string })` — 정확히 일치(trim 없음) 시 true
  - `type StepState = 'done'|'now'|'todo'`; `Stepper({ title?: string; steps: { label: ReactNode; description?: ReactNode; state: StepState }[] })`
  - `StatusStrip({ results: (boolean | null)[]; label: string; fluid?: boolean })` — `role="img"` `aria-label={label}`, true=ok, false=bad, null=none

- [ ] **Step 1: 실패하는 테스트**

```tsx
// admin-ui/src/ui/misc.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Menu, ConfirmByTyping, StatusStrip, Stepper, SecretBox } from '@/ui';

describe('Menu', () => {
  function setup() {
    const rotate = vi.fn(), revoke = vi.fn();
    render(<Menu label="키 액션" items={[{ label: '회전', onSelect: rotate }, { label: '접두사 복사', onSelect: vi.fn() }]} dangerItems={[{ label: '회수', onSelect: revoke, danger: true }]} />);
    fireEvent.click(screen.getByRole('button', { name: '키 액션' }));
    return { rotate, revoke };
  }
  it('열리면 첫 항목에 포커스, 위험 구획 표시', () => {
    setup();
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '회전' }));
    expect(screen.getByText('위험')).toBeInTheDocument();
  });
  it('ArrowDown 순환 + Enter 선택 후 닫힘', () => {
    const { revoke } = setup();
    const menu = screen.getByRole('menu');
    fireEvent.keyDown(menu, { key: 'ArrowDown' });
    fireEvent.keyDown(menu, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '회수' }));
    fireEvent.keyDown(menu, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '회전' }));
    fireEvent.keyDown(menu, { key: 'ArrowUp' });
    fireEvent.keyDown(document.activeElement!, { key: 'Enter' });
    expect(revoke).toHaveBeenCalled();
    expect(screen.queryByRole('menu')).toBeNull();
  });
  it('Escape 로 닫히고 트리거로 포커스 복귀', () => {
    setup();
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '키 액션' }));
  });
});

describe('ConfirmByTyping', () => {
  it('정확히 일치할 때만 true', () => {
    const onMatch = vi.fn();
    render(<ConfirmByTyping expected="acme" label="slug 입력" onMatchChange={onMatch} id="c" />);
    const input = screen.getByRole('textbox', { name: /slug 입력/ });
    fireEvent.change(input, { target: { value: 'acm' } });
    expect(onMatch).toHaveBeenLastCalledWith(false);
    fireEvent.change(input, { target: { value: 'acme' } });
    expect(onMatch).toHaveBeenLastCalledWith(true);
    fireEvent.change(input, { target: { value: 'acme ' } });
    expect(onMatch).toHaveBeenLastCalledWith(false);
  });
});

describe('StatusStrip / Stepper / SecretBox', () => {
  it('StatusStrip 은 요약 라벨을 가진 img', () => {
    const { container } = render(<StatusStrip results={[true, true, false, null]} label="최근 4회 중 1회 위변조" />);
    expect(screen.getByRole('img', { name: '최근 4회 중 1회 위변조' })).toBeInTheDocument();
    expect(container.querySelectorAll('.strip__bar--bad')).toHaveLength(1);
    expect(container.querySelectorAll('.strip__bar--none')).toHaveLength(1);
  });
  it('Stepper done 은 체크, now 는 aria-current', () => {
    render(<Stepper steps={[{ label: 'QR 스캔', state: 'done' }, { label: '코드 확인', state: 'now' }, { label: '복구 코드 저장', state: 'todo' }]} />);
    expect(screen.getByText('코드 확인').closest('li')).toHaveAttribute('aria-current', 'step');
  });
  it('SecretBox 값과 복사 버튼', () => {
    render(<SecretBox value="pk_live_x_secret" />);
    expect(screen.getByText('pk_live_x_secret')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /클립보드/ })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 실패 확인** — Run: `npx vitest run src/ui/misc.test.tsx` → FAIL

- [ ] **Step 3: 구현**

```tsx
// admin-ui/src/ui/Menu.tsx
import { useEffect, useRef, useState, ReactNode, KeyboardEvent } from 'react';
import { Icons } from '@/icons/Icons';
import { cn } from '@/lib/cn';
import { IconButton } from './IconButton';

export type MenuItem = { label: ReactNode; onSelect: () => void; icon?: ReactNode; danger?: boolean };

export function Menu({ label, items, dangerItems = [], dangerTitle = '위험', trigger }: { label: string; items: MenuItem[]; dangerItems?: MenuItem[]; dangerTitle?: string; trigger?: ReactNode }) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const all = [...items, ...dangerItems];

  useEffect(() => {
    if (!open) return;
    wrapRef.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    const outside = (e: MouseEvent) => { if (!wrapRef.current?.contains(e.target as Node)) setOpen(false); };
    document.addEventListener('mousedown', outside);
    return () => document.removeEventListener('mousedown', outside);
  }, [open]);

  function close(refocus: boolean) { setOpen(false); if (refocus) triggerRef.current?.focus(); }
  function onKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    const els = Array.from(wrapRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
    const i = els.indexOf(document.activeElement as HTMLElement);
    if (e.key === 'ArrowDown') { e.preventDefault(); els[(i + 1) % els.length]?.focus(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); els[(i - 1 + els.length) % els.length]?.focus(); }
    else if (e.key === 'Escape') { e.preventDefault(); close(true); }
    else if ((e.key === 'Enter' || e.key === ' ') && i >= 0) { e.preventDefault(); all[i].onSelect(); close(true); }
  }
  const renderItem = (it: MenuItem, k: number) => (
    <button key={k} type="button" role="menuitem" tabIndex={-1} className={cn('menu__item', it.danger && 'menu__item--danger')} onClick={() => { it.onSelect(); close(true); }}>
      {it.icon}{it.label}
    </button>
  );
  return (
    <div className="menu-wrap" ref={wrapRef}>
      {trigger ?? <IconButton ref={triggerRef as never} label={label} icon={<Icons.Dots size={18} />} aria-haspopup="menu" aria-expanded={open} onClick={(e) => { e.stopPropagation(); setOpen((v) => !v); }} />}
      {open && (
        <div className="menu" role="menu" aria-label={label} onKeyDown={onKeyDown} onClick={(e) => e.stopPropagation()}>
          {items.map(renderItem)}
          {dangerItems.length > 0 && <><div className="menu__sep" /><span className="menu__section">{dangerTitle}</span>{dangerItems.map((it, k) => renderItem(it, items.length + k))}</>}
        </div>
      )}
    </div>
  );
}
```

`IconButton` 이 ref 를 받도록 `forwardRef` 로 바꾼다:

```tsx
// admin-ui/src/ui/IconButton.tsx (교체)
import { ButtonHTMLAttributes, ReactNode, forwardRef } from 'react';
import { cn } from '@/lib/cn';

export type IconButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { label: string; icon: ReactNode; size?: 'sm' | 'md'; tone?: 'default' | 'danger' };

export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton({ label, icon, size = 'md', tone = 'default', className, type = 'button', ...rest }, ref) {
  return (
    <button ref={ref} type={type} aria-label={label} title={label} className={cn('icon-btn', size === 'sm' && 'icon-btn--sm', tone === 'danger' && 'icon-btn--danger', className)} {...rest}>
      {icon}
    </button>
  );
});
```

그리고 Menu 의 `ref={triggerRef as never}` 를 `ref={triggerRef}` 로 쓴다.

```tsx
// admin-ui/src/ui/SecretBox.tsx
import { useState } from 'react';
import { copyToClipboard } from '@/lib/clipboard';

export function SecretBox({ value, copyLabel = '클립보드', onCopied }: { value: string; copyLabel?: string; onCopied?: () => void }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="secret">
      <span className="secret__value">{value}</span>
      <button type="button" className="btn btn--sm btn--primary" onClick={async () => { if (await copyToClipboard(value)) { setCopied(true); onCopied?.(); } }}>
        {copied ? '복사됨' : copyLabel}
      </button>
    </div>
  );
}
```

```tsx
// admin-ui/src/ui/ConfirmByTyping.tsx
import { useId, useState, ReactNode } from 'react';

export function ConfirmByTyping({ expected, label, onMatchChange, id }: { expected: string; label: ReactNode; onMatchChange: (ok: boolean) => void; id?: string }) {
  const auto = useId();
  const inputId = id ?? auto;
  const [v, setV] = useState('');
  const ok = v === expected;
  return (
    <div>
      <label className="label" htmlFor={inputId}>{label}</label>
      <input id={inputId} className="input mono" value={v} autoComplete="off" aria-describedby={`${inputId}-h`}
        onChange={(e) => { setV(e.target.value); onMatchChange(e.target.value === expected); }}
        style={ok ? { borderColor: 'var(--ok)' } : undefined} />
      <p id={`${inputId}-h`} className="hint" style={ok ? { color: 'var(--ok)' } : undefined}>{ok ? '일치합니다' : '일치해야 실행 버튼이 활성화됩니다.'}</p>
    </div>
  );
}
```

```tsx
// admin-ui/src/ui/Stepper.tsx
import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type StepState = 'done' | 'now' | 'todo';

export function Stepper({ title, steps }: { title?: string; steps: { label: ReactNode; description?: ReactNode; state: StepState }[] }) {
  return (
    <nav aria-label={title ?? '진행 단계'}>
      {title && <span className="t-over" style={{ display: 'block', padding: '0 6px 8px' }}>{title}</span>}
      <ol className="stepper" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
        {steps.map((s, i) => (
          <li key={i} className={cn('stepper__item', `stepper__item--${s.state}`)} aria-current={s.state === 'now' ? 'step' : undefined}>
            <span className="stepper__mark" aria-hidden="true">{s.state === 'done' ? '✓' : i + 1}</span>
            <span className="stack-1" style={{ gap: 0 }}>
              <span className="stepper__label">{s.label}</span>
              {s.description && <span className="stepper__desc">{s.description}</span>}
            </span>
          </li>
        ))}
      </ol>
    </nav>
  );
}
```

```tsx
// admin-ui/src/ui/StatusStrip.tsx
import { cn } from '@/lib/cn';

export function StatusStrip({ results, label, fluid }: { results: (boolean | null)[]; label: string; fluid?: boolean }) {
  return (
    <div className={cn('strip', fluid && 'strip--fluid')} role="img" aria-label={label}>
      {results.map((r, i) => <span key={i} className={cn('strip__bar', r === false && 'strip__bar--bad', r === null && 'strip__bar--none')} />)}
    </div>
  );
}
```

`index.ts` 에 추가: `export * from './Menu'; export * from './SecretBox'; export * from './ConfirmByTyping'; export * from './Stepper'; export * from './StatusStrip';`

- [ ] **Step 4: 테스트** — Run: `npx vitest run src/ui && npm run build` → PASS

- [ ] **Step 5: 커밋**

```bash
git add src/ui
git commit -m "feat(admin-ui): Menu·SecretBox·ConfirmByTyping·Stepper·StatusStrip

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Tabs · DataTable · Pagination

**Files:**
- Create: `admin-ui/src/ui/{Tabs,DataTable,Pagination}.tsx`; Modify: `index.ts`
- Test: `admin-ui/src/ui/table.test.tsx`

**Interfaces:**
- Produces:
  - `type TabItem = { id: string; label: ReactNode; dot?: boolean; count?: number }`; `Tabs({ items: TabItem[]; active: string; onChange: (id: string) => void; ariaLabel: string })` — `role="tablist"`, 탭 `role="tab"` `aria-selected`; dot 은 `<span className="tabs__dot" aria-label="확인 필요">`; count>0 이면 `.tabs__count`
  - `type Column<T> = { key: string; header: ReactNode; render: (row: T) => ReactNode; align?: 'left'|'right'; width?: number | string; srOnlyHeader?: boolean }`
  - `DataTable<T>({ columns: Column<T>[]; rows: T[]; rowKey: (r: T) => string; onRowClick?: (r: T) => void; groupBy?: (r: T) => string; renderExpanded?: (r: T) => ReactNode; expandedKey?: string | null; empty?: ReactNode; rowClassName?: (r: T) => string | undefined })` — 행 클릭 시 Enter/Space 도 동작(`tabIndex=0`), 그룹 라벨이 바뀔 때 `<tr className="table__group">`, `expandedKey === rowKey(r)` 이면 다음 행에 `<tr className="table__expanded"><td colSpan>`, rows 가 비면 `empty` 를 `<tr className="table__empty"><td colSpan>` 로
  - `Pagination({ page: number /*1-based*/; pageSize: number; total: number; onPage: (p: number) => void; pageSizeOptions?: number[]; onPageSize?: (n: number) => void; unit?: string /*'건'*/ })` — 텍스트 `"{from}–{to} / {total}{unit}"`, total=0 이면 `"0{unit}"`; 이전/다음 버튼 `aria-label="이전 페이지" | "다음 페이지"`, 경계에서 disabled

- [ ] **Step 1: 실패하는 테스트**

```tsx
// admin-ui/src/ui/table.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { Tabs, DataTable, Pagination, type Column } from '@/ui';

type R = { id: string; day: string; name: string };
const cols: Column<R>[] = [{ key: 'name', header: '이름', render: (r) => r.name }];
const rows: R[] = [{ id: '1', day: '오늘', name: 'a' }, { id: '2', day: '오늘', name: 'b' }, { id: '3', day: '어제', name: 'c' }];

describe('Tabs', () => {
  it('aria-selected 와 dot/count', () => {
    const onChange = vi.fn();
    render(<Tabs ariaLabel="Tenant 탭" active="overview" onChange={onChange} items={[{ id: 'overview', label: '개요' }, { id: 'aaguid', label: 'AAGUID 정책', dot: true }, { id: 'admins', label: 'Admin 사용자', count: 3 }]} />);
    expect(screen.getByRole('tab', { name: '개요' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByLabelText('확인 필요')).toBeInTheDocument();
    expect(screen.getByText('3')).toHaveClass('tabs__count');
    fireEvent.click(screen.getByRole('tab', { name: /AAGUID/ }));
    expect(onChange).toHaveBeenCalledWith('aaguid');
  });
});

describe('DataTable', () => {
  it('그룹 헤더 행을 라벨이 바뀔 때마다 넣는다', () => {
    const { container } = render(<DataTable columns={cols} rows={rows} rowKey={(r) => r.id} groupBy={(r) => r.day} />);
    const groups = container.querySelectorAll('tr.table__group');
    expect(Array.from(groups).map((g) => g.textContent)).toEqual(['오늘', '어제']);
  });
  it('행 클릭과 키보드 Enter', () => {
    const onRowClick = vi.fn();
    render(<DataTable columns={cols} rows={rows} rowKey={(r) => r.id} onRowClick={onRowClick} />);
    fireEvent.click(screen.getByText('a'));
    fireEvent.keyDown(screen.getByText('b').closest('tr')!, { key: 'Enter' });
    expect(onRowClick.mock.calls.map((c) => c[0].id)).toEqual(['1', '2']);
  });
  it('행 안 버튼에서 누른 Enter 는 행 클릭으로 처리하지 않는다', () => {
    const onRowClick = vi.fn();
    const withBtn: Column<R>[] = [...cols, { key: 'act', header: '액션', render: (r) => <button type="button">act-{r.name}</button> }];
    render(<DataTable columns={withBtn} rows={rows} rowKey={(r) => r.id} onRowClick={onRowClick} />);
    fireEvent.keyDown(screen.getByRole('button', { name: 'act-a' }), { key: 'Enter' });
    expect(onRowClick).not.toHaveBeenCalled();
  });
  it('expandedKey 행 아래 펼침 행', () => {
    const { container } = render(<DataTable columns={cols} rows={rows} rowKey={(r) => r.id} expandedKey="2" renderExpanded={(r) => <div>detail-{r.name}</div>} />);
    expect(screen.getByText('detail-b')).toBeInTheDocument();
    expect(container.querySelectorAll('tr.table__expanded')).toHaveLength(1);
  });
  it('빈 rows 는 empty 슬롯', () => {
    render(<DataTable columns={cols} rows={[]} rowKey={(r) => r.id} empty={<p>없음</p>} />);
    expect(screen.getByText('없음')).toBeInTheDocument();
  });
});

describe('Pagination', () => {
  it('범위 텍스트와 경계 비활성', () => {
    const onPage = vi.fn();
    render(<Pagination page={1} pageSize={50} total={120} onPage={onPage} />);
    expect(screen.getByText('1–50 / 120건')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이전 페이지' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
    expect(onPage).toHaveBeenCalledWith(2);
  });
  it('마지막 페이지와 0건', () => {
    const { rerender } = render(<Pagination page={3} pageSize={50} total={120} onPage={() => {}} />);
    expect(screen.getByText('101–120 / 120건')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled();
    rerender(<Pagination page={1} pageSize={50} total={0} onPage={() => {}} />);
    expect(screen.getByText('0건')).toBeInTheDocument();
  });
  it('페이지당 선택', () => {
    const onPageSize = vi.fn();
    render(<Pagination page={1} pageSize={50} total={10} onPage={() => {}} pageSizeOptions={[50, 100, 200]} onPageSize={onPageSize} />);
    fireEvent.change(within(screen.getByText(/페이지당/).closest('label')!).getByRole('combobox'), { target: { value: '100' } });
    expect(onPageSize).toHaveBeenCalledWith(100);
  });
});
```

- [ ] **Step 2: 실패 확인** — Run: `npx vitest run src/ui/table.test.tsx` → FAIL

- [ ] **Step 3: 구현**

```tsx
// admin-ui/src/ui/Tabs.tsx
import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type TabItem = { id: string; label: ReactNode; dot?: boolean; count?: number };

export function Tabs({ items, active, onChange, ariaLabel }: { items: TabItem[]; active: string; onChange: (id: string) => void; ariaLabel: string }) {
  return (
    <div className="tabs" role="tablist" aria-label={ariaLabel}>
      {items.map((t) => (
        <button key={t.id} type="button" role="tab" aria-selected={t.id === active} className={cn('tabs__btn', t.id === active && 'tabs__btn--active')} onClick={() => onChange(t.id)}>
          {t.label}
          {t.dot && <span className="tabs__dot" aria-label="확인 필요" role="img" />}
          {t.count != null && t.count > 0 && <span className="tabs__count">{t.count}</span>}
        </button>
      ))}
    </div>
  );
}
```

```tsx
// admin-ui/src/ui/DataTable.tsx
import { Fragment, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type Column<T> = { key: string; header: ReactNode; render: (row: T) => ReactNode; align?: 'left' | 'right'; width?: number | string; srOnlyHeader?: boolean };

type Props<T> = {
  columns: Column<T>[]; rows: T[]; rowKey: (r: T) => string; onRowClick?: (r: T) => void;
  groupBy?: (r: T) => string; renderExpanded?: (r: T) => ReactNode; expandedKey?: string | null;
  empty?: ReactNode; rowClassName?: (r: T) => string | undefined;
};

export function DataTable<T>({ columns, rows, rowKey, onRowClick, groupBy, renderExpanded, expandedKey, empty, rowClassName }: Props<T>) {
  const span = columns.length;
  let lastGroup: string | null = null;
  return (
    <div className="tw">
      <table className="table">
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} className={c.align === 'right' ? 'num' : undefined} style={c.width != null ? { width: c.width } : undefined}>
                {c.srOnlyHeader ? <span className="sr-only">{c.header}</span> : c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && empty != null && <tr className="table__empty"><td colSpan={span}>{empty}</td></tr>}
          {rows.map((r) => {
            const key = rowKey(r);
            const g = groupBy?.(r);
            const groupRow = g != null && g !== lastGroup ? <tr className="table__group"><td colSpan={span}>{g}</td></tr> : null;
            if (g != null) lastGroup = g;
            const expanded = expandedKey != null && expandedKey === key;
            return (
              <Fragment key={key}>
                {groupRow}
                <tr className={cn(onRowClick && 'is-clickable', expanded && 'is-expanded', rowClassName?.(r))}
                  tabIndex={onRowClick ? 0 : undefined}
                  onClick={onRowClick ? () => onRowClick(r) : undefined}
                  onKeyDown={onRowClick ? (e) => {
                    // 행 자신에 포커스가 있을 때만. 셀 안 버튼(복사·회수 등)에서 버블된 Enter/Space 는 무시.
                    if (e.target !== e.currentTarget) return;
                    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onRowClick(r); }
                  } : undefined}>
                  {columns.map((c) => <td key={c.key} className={c.align === 'right' ? 'num' : undefined}>{c.render(r)}</td>)}
                </tr>
                {expanded && renderExpanded && <tr className="table__expanded"><td colSpan={span}>{renderExpanded(r)}</td></tr>}
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
```

```tsx
// admin-ui/src/ui/Pagination.tsx
import { Icons } from '@/icons/Icons';

type Props = { page: number; pageSize: number; total: number; onPage: (p: number) => void; pageSizeOptions?: number[]; onPageSize?: (n: number) => void; unit?: string };

export function Pagination({ page, pageSize, total, onPage, pageSizeOptions, onPageSize, unit = '건' }: Props) {
  const last = Math.max(1, Math.ceil(total / pageSize));
  const from = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const to = Math.min(total, page * pageSize);
  return (
    <div className="pager">
      <span>{total === 0 ? `0${unit}` : `${from}–${to} / ${total}${unit}`}</span>
      <div className="spacer" />
      {pageSizeOptions && onPageSize && (
        <label className="row" style={{ gap: 6 }}>페이지당
          <select className="input" value={pageSize} onChange={(e) => onPageSize(Number(e.target.value))}>
            {pageSizeOptions.map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </label>
      )}
      <button type="button" className="btn btn--sm" aria-label="이전 페이지" disabled={page <= 1} onClick={() => onPage(page - 1)}><Icons.ChevronLeft size={14} /></button>
      <span>{page} / {last}</span>
      <button type="button" className="btn btn--sm" aria-label="다음 페이지" disabled={page >= last} onClick={() => onPage(page + 1)}><Icons.ChevronRight size={14} /></button>
    </div>
  );
}
```

`index.ts` 에 추가: `export * from './Tabs'; export * from './DataTable'; export * from './Pagination';`

- [ ] **Step 4: 테스트** — Run: `npx vitest run src/ui/table.test.tsx` → PASS

- [ ] **Step 5: 커밋**

```bash
git add src/ui
git commit -m "feat(admin-ui): Tabs·DataTable(그룹·펼침·빈 상태)·Pagination

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: SaveBar + useUnsavedGuard

**Files:**
- Create: `admin-ui/src/ui/SaveBar.tsx`, `admin-ui/src/ui/useUnsavedGuard.tsx`; Modify: `index.ts`
- Test: `admin-ui/src/ui/unsaved.test.tsx`

**Interfaces:**
- Consumes: Task 2 data router (`useBlocker`), Task 8 `Dialog`.
- Produces:
  - `SaveBar({ count: number; summary?: ReactNode; onRevert: () => void; onSave: () => void; saving?: boolean; saveLabel?: string /*'저장'*/; revertLabel?: string /*'되돌리기'*/ })` — count=0 이면 `null`. `role="region"` `aria-label="저장되지 않은 변경"`. 제목 텍스트 `저장하지 않은 변경 {count}건`.
  - `useUnsavedGuard(dirty: boolean): ReactNode` — `useBlocker(dirty)` 로 앱 내 이동(경로·검색 파라미터 포함)을 막고 확인 다이얼로그 엘리먼트를 반환(호출부가 JSX 에 렌더). `beforeunload` 로 새로고침·탭 닫기 경고. 다이얼로그: 제목 `저장하지 않은 변경이 있습니다`, 본문 `이동하면 변경 사항이 사라집니다.`, 버튼 `머무르기`(기본 포커스) / `이동`(danger-solid).
  - 화면 테스트에서 이 훅을 쓰는 컴포넌트는 `createMemoryRouter` + `RouterProvider` 로 렌더해야 한다(아래 헬퍼).
  - `renderWithDataRouter(ui: ReactElement, { initialPath?: string })` — `admin-ui/src/test/renderWithDataRouter.tsx` (테스트 전용 헬퍼)

- [ ] **Step 1: 테스트 헬퍼 작성**

```tsx
// admin-ui/src/test/renderWithDataRouter.tsx
import { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';

/** useBlocker 를 쓰는 컴포넌트 테스트용. '*' 하나에 ui 를 마운트한다. */
export function renderWithDataRouter(ui: ReactElement, { initialPath = '/' }: { initialPath?: string } = {}) {
  const router = createMemoryRouter([{ path: '*', element: ui }], { initialEntries: [initialPath] });
  return { router, ...render(<RouterProvider router={router} />) };
}
```

- [ ] **Step 2: 실패하는 테스트**

```tsx
// admin-ui/src/ui/unsaved.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { screen, fireEvent, act, render } from '@testing-library/react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { SaveBar, useUnsavedGuard } from '@/ui';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';

function Page() {
  const [dirty, setDirty] = useState(false);
  const guard = useUnsavedGuard(dirty);
  const nav = useNavigate();
  return (
    <div>
      <button onClick={() => setDirty(true)}>편집</button>
      <button onClick={() => nav('/tenants/1?tab=aaguid')}>탭 이동</button>
      <button onClick={() => nav('/activity')}>다른 화면</button>
      {guard}
    </div>
  );
}

describe('useUnsavedGuard', () => {
  it('dirty 가 아니면 바로 이동', async () => {
    const { router } = renderWithDataRouter(<Page />, { initialPath: '/tenants/1?tab=webauthn' });
    await act(async () => { fireEvent.click(screen.getByText('다른 화면')); });
    expect(router.state.location.pathname).toBe('/activity');
  });
  it('blocks search-param-only navigation 그리고 머무르기', async () => {
    const { router } = renderWithDataRouter(<Page />, { initialPath: '/tenants/1?tab=webauthn' });
    fireEvent.click(screen.getByText('편집'));
    await act(async () => { fireEvent.click(screen.getByText('탭 이동')); });
    expect(screen.getByRole('alertdialog', { name: '저장하지 않은 변경이 있습니다' })).toBeInTheDocument();
    expect(router.state.location.search).toBe('?tab=webauthn');
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '머무르기' }));
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '머무르기' })); });
    expect(router.state.location.search).toBe('?tab=webauthn');
  });
  it('이동 을 누르면 진행', async () => {
    const { router } = renderWithDataRouter(<Page />, { initialPath: '/tenants/1?tab=webauthn' });
    fireEvent.click(screen.getByText('편집'));
    await act(async () => { fireEvent.click(screen.getByText('다른 화면')); });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '이동' })); });
    expect(router.state.location.pathname).toBe('/activity');
  });
  it('dirty 면 beforeunload 를 막는다', () => {
    renderWithDataRouter(<Page />);
    fireEvent.click(screen.getByText('편집'));
    const ev = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(ev);
    expect(ev.defaultPrevented).toBe(true);
  });
});

describe('SaveBar', () => {
  it('count 0 이면 렌더하지 않음', () => {
    const { container } = render(<SaveBar count={0} onRevert={() => {}} onSave={() => {}} />);
    expect(container).toBeEmptyDOMElement();
  });
  it('건수와 버튼', () => {
    const onSave = vi.fn(), onRevert = vi.fn();
    render(<SaveBar count={2} summary="timeoutMs · origins" onRevert={onRevert} onSave={onSave} saveLabel="저장…" />);
    expect(screen.getByRole('region', { name: '저장되지 않은 변경' })).toHaveTextContent('저장하지 않은 변경 2건');
    fireEvent.click(screen.getByRole('button', { name: '저장…' }));
    fireEvent.click(screen.getByRole('button', { name: '되돌리기' }));
    expect(onSave).toHaveBeenCalled();
    expect(onRevert).toHaveBeenCalled();
  });
});
```

- [ ] **Step 3: 실패 확인** — Run: `npx vitest run src/ui/unsaved.test.tsx` → FAIL

- [ ] **Step 4: 구현**

```tsx
// admin-ui/src/ui/SaveBar.tsx
import { ReactNode } from 'react';

type Props = { count: number; summary?: ReactNode; onRevert: () => void; onSave: () => void; saving?: boolean; saveLabel?: string; revertLabel?: string };

export function SaveBar({ count, summary, onRevert, onSave, saving, saveLabel = '저장', revertLabel = '되돌리기' }: Props) {
  if (count <= 0) return null;
  return (
    <div className="savebar" role="region" aria-label="저장되지 않은 변경">
      <span className="savebar__dot" aria-hidden="true" />
      <strong className="savebar__title">저장하지 않은 변경 {count}건</strong>
      {summary && <span className="hint" style={{ margin: 0 }}>{summary}</span>}
      <div className="spacer" />
      <button type="button" className="btn" onClick={onRevert} disabled={saving}>{revertLabel}</button>
      <button type="button" className="btn btn--primary" onClick={onSave} disabled={saving}>{saving ? '저장 중…' : saveLabel}</button>
    </div>
  );
}
```

```tsx
// admin-ui/src/ui/useUnsavedGuard.tsx
import { useEffect, useRef, ReactNode } from 'react';
import { useBlocker } from 'react-router-dom';
import { Dialog } from './Dialog';

export function useUnsavedGuard(dirty: boolean): ReactNode {
  const blocker = useBlocker(({ currentLocation, nextLocation }) =>
    dirty && (currentLocation.pathname !== nextLocation.pathname || currentLocation.search !== nextLocation.search));
  const stayRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!dirty) return;
    const h = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ''; };
    window.addEventListener('beforeunload', h);
    return () => window.removeEventListener('beforeunload', h);
  }, [dirty]);

  if (blocker.state !== 'blocked') return null;
  return (
    <Dialog open onClose={() => blocker.reset?.()} role="alertdialog" title="저장하지 않은 변경이 있습니다" initialFocusRef={stayRef}
      footer={<>
        <button ref={stayRef} type="button" className="btn" onClick={() => blocker.reset?.()}>머무르기</button>
        <button type="button" className="btn btn--danger-solid" onClick={() => blocker.proceed?.()}>이동</button>
      </>}>
      <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>이동하면 변경 사항이 사라집니다.</p>
    </Dialog>
  );
}
```

`index.ts` 에 추가: `export * from './SaveBar'; export * from './useUnsavedGuard';`

- [ ] **Step 5: 테스트** — Run: `npx vitest run src/ui/unsaved.test.tsx && npm run build` → PASS

- [ ] **Step 6: 커밋**

```bash
git add src/ui src/test/renderWithDataRouter.tsx
git commit -m "feat(admin-ui): SaveBar·useUnsavedGuard(경로·검색 파라미터 이동 차단, beforeunload)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: 앱 셸 (Sidebar · Topbar · Breadcrumb · 레이아웃 · LicenseBanner)

V2 아트보드: `v2/Sidebar.dc.html`, `v2/Topbar.dc.html`, `v2/License.dc.html`(배너).

**Files:**
- Rewrite: `admin-ui/src/shell/Sidebar.tsx`, `admin-ui/src/shell/Header.tsx`, `admin-ui/src/shell/Breadcrumb.tsx`
- Modify: `admin-ui/src/App.tsx`(레이아웃 마크업), `admin-ui/src/components/LicenseBanner.tsx`, `admin-ui/src/icons/Icons.tsx`(`Help` 추가)
- Test: `admin-ui/src/shell/Sidebar.test.tsx`(갱신), `admin-ui/src/shell/Header.test.tsx`(신규)

**Interfaces:**
- Consumes: `auditChainMonitorApi.overview(24)` → `ChainOverview { verifiedAt: string; totals: { tenantsTotal; tenantsTampered; verifiedRows } }`, `licenseApi.get()` → `{ deploymentMode }`, `isPlatform(me)`.
- Produces:
  - `Sidebar({ me: Me; currentRoute: AppRoute; onNavigate: (r: AppRoute) => void })` — `tenant` prop 과 `sidebarMode` prop 제거(테넌트 컨텍스트 nav 는 범위 밖, 스펙 §7).
  - `Header({ me; onLogout; onSwitchRole?; breadcrumb; onOpenPalette; onRpSwitch? })` — 시그니처 유지, 마크업만 V2.
  - `Icons.Help`.

V2 사이드바 규칙(아트보드와 동일):
- 브랜드: `<img src="/admin/crosscert-logo-transparent.png" alt="CROSSCERT">` 132px + `Passkey Admin` + `prod` 배지.
- PLATFORM: 그룹 `플랫폼`(Tenants·활동·감사 체인·[온프렘이면 라이선스, `온프렘` 태그]) + 그룹 `관리`(설정). RP_ADMIN: 그룹 `관리`(설정)만.
- 아이콘: Tenants=`Building`, 활동=`Activity`, 감사 체인=`Link`, 라이선스=`Key`, 설정=`Cog`. 크기 18.
- 활성 항목 `.nav-item--active`(좌측 3px 바), `aria-current="page"`.
- 감사 체인 항목: `chain.totals.tenantsTampered > 0` 이면 `.nav-item__count` 에 그 수.
- 하단 체인 카드(PLATFORM 만, 클릭 시 감사 체인으로 이동): 로딩 `chain-card--pending` "AUDIT CHAIN / 확인 중…", 정상 "AUDIT CHAIN OK / 마지막 검증 · {timeAgo} · {rows} 행", 위변조 `chain-card--bad` "위변조 의심 · {n} tenant / 즉시 확인이 필요합니다". 하단 `© Crosscert`.

- [ ] **Step 1: 테스트 갱신/추가 (실패)**

```tsx
// admin-ui/src/shell/Sidebar.test.tsx (교체)
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import type { Me } from '@/api/types';

const overview = vi.fn();
vi.mock('@/api/auditChainMonitor', () => ({ auditChainMonitorApi: { overview: (...a: unknown[]) => overview(...a) } }));
vi.mock('@/api/license', () => ({ licenseApi: { get: vi.fn().mockResolvedValue({ deploymentMode: 'saas' }) } }));

import { Sidebar } from './Sidebar';

const platform: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const rp: Me = { email: 'b@x.com', role: 'RP_ADMIN', tenantIds: ['tid-123'], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };

describe('Sidebar V2', () => {
  it('PLATFORM: 플랫폼/관리 그룹과 4개 항목, 활성 aria-current', async () => {
    overview.mockResolvedValue(null);
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={() => {}} />);
    expect(screen.getByText('플랫폼')).toBeInTheDocument();
    expect(screen.getByText('관리')).toBeInTheDocument();
    for (const l of ['Tenants', '활동', '감사 체인', '설정']) expect(screen.getByRole('button', { name: new RegExp(l) })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Tenants/ })).toHaveAttribute('aria-current', 'page');
    expect(await screen.findByText('확인 중…')).toBeInTheDocument();
  });
  it('RP_ADMIN: 설정만, 체인 카드 없음', async () => {
    render(<Sidebar me={rp} currentRoute={{ name: 'settings' }} onNavigate={() => {}} />);
    expect(screen.queryByText('Tenants')).toBeNull();
    expect(screen.queryByText('플랫폼')).toBeNull();
    expect(screen.getByRole('button', { name: /설정/ })).toBeInTheDocument();
    expect(screen.queryByText(/AUDIT CHAIN/)).toBeNull();
  });
  it('위변조가 있으면 감사 체인 항목에 건수 배지와 빨간 카드', async () => {
    overview.mockResolvedValue({ verifiedAt: new Date().toISOString(), totals: { tenantsTotal: 5, tenantsTampered: 1, verifiedRows: 10 } });
    const onNavigate = vi.fn();
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={onNavigate} />);
    expect(await screen.findByText('위변조 의심 · 1 tenant')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /감사 체인/ })).toHaveTextContent('1');
    fireEvent.click(screen.getByText('위변조 의심 · 1 tenant'));
    expect(onNavigate).toHaveBeenCalledWith({ name: 'audit-chain' });
  });
});
```

```tsx
// admin-ui/src/shell/Header.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
vi.mock('@/api/activeTenant', () => ({ activeTenantApi: { get: vi.fn().mockResolvedValue(null), switch: vi.fn() } }));
import { Header } from './Header';

describe('Header V2', () => {
  it('브레드크럼 마지막 항목은 현재 위치, 검색 버튼은 팔레트를 연다', () => {
    const onOpenPalette = vi.fn();
    render(<Header me={{ role: 'PLATFORM_OPERATOR', email: 'a@x.com' }} onLogout={() => {}} breadcrumb={[{ label: 'Tenants', onClick: () => {} }, { label: 'Acme' }]} onOpenPalette={onOpenPalette} />);
    expect(screen.getByText('Acme')).toHaveAttribute('aria-current', 'page');
    fireEvent.click(screen.getByRole('button', { name: /검색/ }));
    expect(onOpenPalette).toHaveBeenCalled();
    expect(screen.getByText('PLATFORM')).toBeInTheDocument();
  });
  it('사용자 메뉴에 role 전환·API 문서·로그아웃', () => {
    const onLogout = vi.fn();
    render(<Header me={{ role: 'PLATFORM_OPERATOR', email: 'a@x.com' }} onLogout={onLogout} breadcrumb={[]} onOpenPalette={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: '사용자 메뉴' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '로그아웃' }));
    expect(onLogout).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: 실패 확인** — Run: `npx vitest run src/shell` → FAIL (그룹 라벨·aria-current 없음)

- [ ] **Step 3: Icons.Help 추가**

`src/icons/Icons.tsx` 에서 `const Info = …` 근처에 추가하고 `Icons` 객체에 `Help` 를 넣는다:

```ts
const Help = makeIconR([
  { tag: 'circle', attrs: { cx: 12, cy: 12, r: 9 } },
  { tag: 'path', attrs: { d: 'M9.5 9.5a2.5 2.5 0 114 2c-.9.5-1.5 1-1.5 2M12 17h.01' } },
]);
```

- [ ] **Step 4: Sidebar 재작성**

```tsx
// admin-ui/src/shell/Sidebar.tsx
import { useEffect, useMemo, useState } from 'react';
import { Icons } from '@/icons/Icons';
import { auditChainMonitorApi, type ChainOverview } from '@/api/auditChainMonitor';
import { licenseApi } from '@/api/license';
import { isPlatform } from '@/me/roles';
import { cn } from '@/lib/cn';
import type { Me } from '@/api/types';
import type { AppRoute } from '@/appRoute';

type TopRoute = 'tenants' | 'activity' | 'audit-chain' | 'settings' | 'license';
type Item = { id: TopRoute; label: string; icon: keyof typeof Icons; tag?: string };

export function timeAgo(iso: string): string {
  const mins = Math.floor((Date.now() - new Date(iso).getTime()) / 60_000);
  if (mins < 1) return '방금';
  if (mins < 60) return `${mins}분 전`;
  return `${Math.floor(mins / 60)}시간 전`;
}

export function Sidebar({ me, currentRoute, onNavigate }: { me: Me; currentRoute: AppRoute; onNavigate: (r: AppRoute) => void }) {
  const platform = isPlatform(me);
  const [chain, setChain] = useState<ChainOverview | null>(null);
  const [mode, setMode] = useState<'saas' | 'onprem' | null>(null);

  useEffect(() => { licenseApi.get().then((v) => setMode(v.deploymentMode)).catch(() => setMode('saas')); }, []);
  useEffect(() => {
    if (!platform) return; // RP 는 PLATFORM 전용 엔드포인트를 폴링하지 않는다
    let cancelled = false;
    const load = () => auditChainMonitorApi.overview(24).then((o) => { if (!cancelled) setChain(o); }).catch(() => {});
    load();
    const id = setInterval(load, 30_000);
    return () => { cancelled = true; clearInterval(id); };
  }, [platform]);

  const groups = useMemo(() => {
    const admin: Item[] = [{ id: 'settings', label: '설정', icon: 'Cog' }];
    if (!platform) return [{ label: '관리', items: admin }];
    const plat: Item[] = [
      { id: 'tenants', label: 'Tenants', icon: 'Building' },
      { id: 'activity', label: '활동', icon: 'Activity' },
      { id: 'audit-chain', label: '감사 체인', icon: 'Link' },
      ...(mode === 'onprem' ? [{ id: 'license' as const, label: '라이선스', icon: 'Key' as const, tag: '온프렘' }] : []),
    ];
    return [{ label: '플랫폼', items: plat }, { label: '관리', items: admin }];
  }, [platform, mode]);

  const tampered = chain?.totals?.tenantsTampered ?? 0;
  const activeName = currentRoute.name === 'tenant' ? 'tenants' : currentRoute.name;

  return (
    <aside className="sidebar" aria-label="주 메뉴">
      <div className="sidebar__brand">
        <img src="/admin/crosscert-logo-transparent.png" alt="CROSSCERT" />
        <div className="sidebar__env">Passkey Admin <span className="sidebar__env-badge">prod</span></div>
      </div>
      {groups.map((g) => (
        <div key={g.label} className="sidebar__group">
          <span className="sidebar__label">{g.label}</span>
          {g.items.map((it) => {
            const I = Icons[it.icon] as React.ComponentType<{ size?: number }>;
            const active = activeName === it.id;
            return (
              <button key={it.id} type="button" className={cn('nav-item', active && 'nav-item--active')} aria-current={active ? 'page' : undefined} onClick={() => onNavigate({ name: it.id })}>
                <I size={18} />
                <span>{it.label}</span>
                {it.tag && <span className="nav-item__tag">{it.tag}</span>}
                {it.id === 'audit-chain' && tampered > 0 && <span className="nav-item__count">{tampered}</span>}
              </button>
            );
          })}
        </div>
      ))}
      <div className="sidebar__foot">
        {platform && (
          <button type="button" className={cn('chain-card', !chain?.totals && 'chain-card--pending', tampered > 0 && 'chain-card--bad')} onClick={() => onNavigate({ name: 'audit-chain' })}>
            <span className="chain-card__dot" />
            <span className="stack-1" style={{ gap: 2 }}>
              {!chain?.totals ? (<><span className="chain-card__title">AUDIT CHAIN</span><span className="chain-card__sub">확인 중…</span></>)
                : tampered > 0 ? (<><span className="chain-card__title">위변조 의심 · {tampered} tenant</span><span className="chain-card__sub">즉시 확인이 필요합니다</span></>)
                : (<><span className="chain-card__title">AUDIT CHAIN OK</span><span className="chain-card__sub">마지막 검증 · {timeAgo(chain.verifiedAt)} · {chain.totals.verifiedRows.toLocaleString()} 행</span></>)}
            </span>
          </button>
        )}
        <span className="sidebar__copy">© Crosscert</span>
      </div>
    </aside>
  );
}
```

- [ ] **Step 5: Breadcrumb · Header 재작성**

```tsx
// admin-ui/src/shell/Breadcrumb.tsx
import { Fragment, ReactNode } from 'react';
import { Icons } from '@/icons/Icons';

export function Breadcrumb({ items }: { items: { label: ReactNode; onClick?: () => void }[] }) {
  if (!items || items.length === 0) return null;
  return (
    <nav aria-label="현재 위치" className="crumbs">
      {items.map((it, i) => {
        const last = i === items.length - 1;
        return (
          <Fragment key={i}>
            {i > 0 && <Icons.ChevronRight size={14} />}
            {last ? <span className="crumbs__current" aria-current="page">{it.label}</span>
              : it.onClick ? <button type="button" className="crumbs__item" onClick={it.onClick}>{it.label}</button>
              : <span>{it.label}</span>}
          </Fragment>
        );
      })}
    </nav>
  );
}
```

`Header.tsx`: 아래 코드로 교체한다. `RpSwitcher` 의 상태·API 로직(`activeTenantApi.get/switch`, `allowedTenants` 라벨 맵, 2개 미만이면 `null`)은 기존과 동일하게 유지하되 마크업을 `.menu`/`.menu__item` 으로 바꾼다.

```tsx
// admin-ui/src/shell/Header.tsx
import { ReactNode, useEffect, useRef, useState } from 'react';
import { Icons } from '@/icons/Icons';
import { Breadcrumb } from './Breadcrumb';
import { activeTenantApi, type ActiveTenantView } from '@/api/activeTenant';
import { Badge } from '@/ui';

function useOutsideClose(open: boolean, close: () => void) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const h = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) close(); };
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    document.addEventListener('mousedown', h); document.addEventListener('keydown', k);
    return () => { document.removeEventListener('mousedown', h); document.removeEventListener('keydown', k); };
  }, [open, close]);
  return ref;
}

function RpSwitcher({ onSwitch }: { onSwitch: () => void }) {
  const [active, setActive] = useState<ActiveTenantView | null>(null);
  const [open, setOpen] = useState(false);
  const [switching, setSwitching] = useState(false);
  const ref = useOutsideClose(open, () => setOpen(false));
  useEffect(() => { activeTenantApi.get().then(setActive).catch(() => {}); }, []);
  if (!active || active.allowedTenantIds.length < 2) return null;
  const names: Record<string, string> = {};
  active.allowedTenants.forEach((t) => { names[t.id] = t.name; });
  const label = active.activeTenantId ? (names[active.activeTenantId] ?? active.activeTenantId.slice(-8)) : '—';
  async function pick(id: string) {
    if (id === active?.activeTenantId) { setOpen(false); return; }
    setSwitching(true);
    try { await activeTenantApi.switch(id); setOpen(false); onSwitch(); } catch { /* 무시 */ } finally { setSwitching(false); }
  }
  return (
    <div className="menu-wrap" ref={ref}>
      <button type="button" className="btn btn--ghost" aria-haspopup="menu" aria-expanded={open} disabled={switching} onClick={() => setOpen((v) => !v)}>
        <Icons.Building size={14} /><span className="truncate" style={{ maxWidth: 140 }}>{switching ? '전환 중…' : label}</span><Icons.ChevronDown size={12} />
      </button>
      {open && (
        <div className="menu" role="menu" aria-label="RP 전환" style={{ left: 0, right: 'auto', minWidth: 220 }}>
          <span className="t-over" style={{ display: 'block', padding: '4px 10px 6px' }}>RP 전환</span>
          {active.allowedTenantIds.map((id) => (
            <button key={id} type="button" role="menuitemradio" aria-checked={id === active.activeTenantId} className="menu__item" onClick={() => void pick(id)}>
              <span style={{ width: 14, display: 'inline-flex' }}>{id === active.activeTenantId && <Icons.Check size={14} />}</span>
              {names[id] ?? id.slice(-8)}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

type HeaderProps = {
  me: { role: string; email: string; displayName?: string };
  onLogout: () => void; onSwitchRole?: () => void;
  breadcrumb: { label: ReactNode; onClick?: () => void }[];
  onOpenPalette: () => void; onRpSwitch?: () => void;
};

export function Header({ me, onLogout, onSwitchRole, breadcrumb, onOpenPalette, onRpSwitch }: HeaderProps) {
  const [open, setOpen] = useState(false);
  const ref = useOutsideClose(open, () => setOpen(false));
  const name = me.displayName ?? me.email;
  const platform = me.role === 'PLATFORM_OPERATOR';
  return (
    <header className="topbar">
      <Breadcrumb items={breadcrumb} />
      <div className="spacer" />
      {me.role === 'RP_ADMIN' && <RpSwitcher onSwitch={onRpSwitch ?? (() => window.location.reload())} />}
      <button type="button" className="topbar__search" onClick={onOpenPalette} aria-label="검색 (⌘K)">
        <Icons.Search size={15} /><span>tenant, credential, audit ID 검색…</span><kbd>⌘K</kbd>
      </button>
      <a className="icon-btn" href="/admin/api-docs" target="_blank" rel="noreferrer" aria-label="도움말 · API 문서" title="도움말 · API 문서"><Icons.Help size={18} /></a>
      <div className="menu-wrap" ref={ref}>
        <button type="button" className="user-pill" aria-label="사용자 메뉴" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
          <span className="avatar">{name.slice(0, 1).toUpperCase()}</span>
          <span className="stack-1" style={{ gap: 0, lineHeight: 1.2, textAlign: 'left' }}><span style={{ fontWeight: 600 }}>{name}</span><span style={{ fontSize: 11, color: 'var(--mute)' }}>{me.email}</span></span>
          <Badge tone={platform ? 'plat' : 'info'} dot={false}>{platform ? 'PLATFORM' : 'RP_ADMIN'}</Badge>
          <Icons.ChevronDown size={14} />
        </button>
        {open && (
          <div className="menu" role="menu" aria-label="사용자 메뉴" style={{ minWidth: 240 }}>
            <div className="stack-1" style={{ padding: '8px 10px 10px', borderBottom: '1px solid var(--line-soft)', marginBottom: 4, gap: 2 }}>
              <strong style={{ fontSize: 13 }}>{name}</strong><span style={{ fontSize: 12, color: 'var(--mute)' }}>{me.email}</span>
              <span style={{ marginTop: 4 }}><Badge tone={platform ? 'plat' : 'info'} dot={false}>{platform ? 'PLATFORM' : 'RP_ADMIN'}</Badge></span>
            </div>
            <button type="button" role="menuitem" className="menu__item" onClick={() => { setOpen(false); onSwitchRole?.(); }}><Icons.Refresh size={14} />role 전환 (데모)</button>
            <a role="menuitem" className="menu__item" href="/admin/api-docs" target="_blank" rel="noreferrer" style={{ textDecoration: 'none' }}><Icons.ExternalLink size={14} />API 문서 열기</a>
            <div className="menu__sep" />
            <button type="button" role="menuitem" className="menu__item menu__item--danger" onClick={() => { setOpen(false); onLogout(); }}><Icons.LogOut size={14} />로그아웃</button>
          </div>
        )}
      </div>
    </header>
  );
}
```

`API 문서` 의 기존 동작: 기존 `MenuItem` 은 onClick 이 없어 아무 동작도 하지 않았다. `href` 를 넣기 전에 `grep -rn "api-docs\|swagger" ../admin-app/src/main/resources ../admin-app/src/main/java | head` 로 실제 문서 경로를 확인하고, 없으면 `<a>` 대신 기존처럼 동작 없는 `<button role="menuitem">` 로 둔다(기능 변경 금지). 상단 도움말 아이콘도 같은 규칙.

- [ ] **Step 6: App.tsx 레이아웃**

`AuthenticatedApp` 의 return 을 다음으로 바꾼다(라우트·팔레트·IdleSessionModal 은 그대로):

```tsx
return (
  <Fragment>
    <LicenseBanner />
    <div className="app">
      <ErrorBoundary fallback={null}>
        <Sidebar me={me} currentRoute={route} onNavigate={setRoute} />
      </ErrorBoundary>
      <div className="content">
        <Header me={me} onLogout={onLogout} onSwitchRole={handleSwitchRole} breadcrumb={breadcrumb} onOpenPalette={() => setPaletteOpen(true)} />
        <main>
          <ErrorBoundary>
            <Routes>{/* 기존 Route 7개 그대로 */}</Routes>
          </ErrorBoundary>
        </main>
      </div>
      {/* CommandPalette, IdleSessionModal 그대로 */}
    </div>
  </Fragment>
);
```

`const tenant = null;` 와 `buildBreadcrumb(route, tenant, …)` 는 유지한다. 각 페이지는 자체 루트에 `className="page"` 를 둔다(화면 태스크에서 적용). 그 전까지 레이아웃이 붙지 않도록 이 태스크에서 `<main>` 안 `<Routes>` 를 `<div className="page">` 로 감싸고, 화면 태스크가 각 페이지 루트를 `.page` 로 바꾸면서 마지막 화면 태스크(Task 29)에서 이 래퍼를 제거한다.

- [ ] **Step 7: LicenseBanner V2**

`src/components/LicenseBanner.tsx` 의 상태 판단·문구 로직은 유지하고, 렌더 마크업만 다음 형태로 바꾼다: 경고(만료 임박·서버 연결 불가) `className="topbanner topbanner--warning"`, 만료 `className="topbanner topbanner--danger"`, 각각 `role="status"`/`role="alert"`, 앞에 `<Icons.Alert size={16} />`, 문구는 `<strong>` 로 감싼다. 인라인 색 스타일은 삭제.

- [ ] **Step 8: 테스트·빌드**

Run: `npx vitest run src/shell && npm test && npm run build`
Expected: PASS.

- [ ] **Step 9: 커밋**

```bash
git add src/shell src/App.tsx src/components/LicenseBanner.tsx src/icons/Icons.tsx
git commit -m "feat(admin-ui): V2 앱 셸 — 사이드바 그룹·체인 카드·상단 바·라이선스 배너

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: 커맨드 팔레트 · G 단축키 · 세션 만료 진행 링

V2 아트보드: `v2/Overlays.dc.html`.

**Files:**
- Rewrite: `admin-ui/src/extras/CommandPalette.tsx`
- Create: `admin-ui/src/extras/useGoShortcuts.ts`
- Modify: `admin-ui/src/extras/IdleSessionModal.tsx`, `admin-ui/src/App.tsx`(훅 연결)
- Test: `admin-ui/src/extras/useGoShortcuts.test.tsx`, `admin-ui/src/extras/CommandPalette.test.tsx`, `admin-ui/src/extras/IdleSessionModal.test.tsx`(유지 — 문구 `보안을 위해 {n}분 동안` 를 그대로 둔다)

**Interfaces:**
- Consumes: 기존 `CommandPalette({ open, onClose, me, onNavigate, onAction })` 시그니처(먼저 `src/extras/CommandPalette.tsx` 를 읽고 검색·필터 로직, 액션 kind 값 `new-tenant | verify-chain | switch-role | logout` 등 실제 값을 확인해 그대로 유지).
- Produces:
  - `useGoShortcuts(onNavigate: (r: AppRoute) => void, enabled: boolean)` — `g` 후 1초 안에 `t`→tenants, `a`→activity, `c`→audit-chain, `s`→settings. 대상이 `input|textarea|select|[contenteditable]` 이거나 `document.querySelector('[aria-modal="true"]')` 가 있으면 무시. 수정키(meta/ctrl/alt) 동반 시 무시.
  - `export const GO_SHORTCUTS: Record<'tenants'|'activity'|'audit-chain'|'settings', string> = { tenants: 'G T', activity: 'G A', 'audit-chain': 'G C', settings: 'G S' }` — 팔레트 표시용.

- [ ] **Step 1: 실패하는 테스트**

```tsx
// admin-ui/src/extras/useGoShortcuts.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, fireEvent, act } from '@testing-library/react';
import { useGoShortcuts } from './useGoShortcuts';

function Host({ onNavigate, enabled = true }: { onNavigate: (r: unknown) => void; enabled?: boolean }) {
  useGoShortcuts(onNavigate as never, enabled);
  return <div><input aria-label="검색" /><textarea aria-label="메모" /></div>;
}

describe('useGoShortcuts', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('g → t 는 tenants 로', () => {
    const nav = vi.fn();
    render(<Host onNavigate={nav} />);
    fireEvent.keyDown(document.body, { key: 'g' });
    fireEvent.keyDown(document.body, { key: 't' });
    expect(nav).toHaveBeenCalledWith({ name: 'tenants' });
  });
  it('1초가 지나면 체인이 끊긴다', () => {
    const nav = vi.fn();
    render(<Host onNavigate={nav} />);
    fireEvent.keyDown(document.body, { key: 'g' });
    act(() => { vi.advanceTimersByTime(1100); });
    fireEvent.keyDown(document.body, { key: 'a' });
    expect(nav).not.toHaveBeenCalled();
  });
  it('ignores G-chord while typing (input/textarea)', () => {
    const nav = vi.fn();
    const { getByLabelText } = render(<Host onNavigate={nav} />);
    fireEvent.keyDown(getByLabelText('검색'), { key: 'g' });
    fireEvent.keyDown(getByLabelText('검색'), { key: 's' });
    fireEvent.keyDown(getByLabelText('메모'), { key: 'g' });
    fireEvent.keyDown(getByLabelText('메모'), { key: 'c' });
    expect(nav).not.toHaveBeenCalled();
  });
  it('모달이 열려 있으면 무시', () => {
    const nav = vi.fn();
    render(<><Host onNavigate={nav} /><div aria-modal="true" /></>);
    fireEvent.keyDown(document.body, { key: 'g' });
    fireEvent.keyDown(document.body, { key: 's' });
    expect(nav).not.toHaveBeenCalled();
  });
  it('수정키 동반·disabled 면 무시', () => {
    const nav = vi.fn();
    const { rerender } = render(<Host onNavigate={nav} />);
    fireEvent.keyDown(document.body, { key: 'g', metaKey: true });
    fireEvent.keyDown(document.body, { key: 't' });
    rerender(<Host onNavigate={nav} enabled={false} />);
    fireEvent.keyDown(document.body, { key: 'g' });
    fireEvent.keyDown(document.body, { key: 't' });
    expect(nav).not.toHaveBeenCalled();
  });
});
```

```tsx
// admin-ui/src/extras/CommandPalette.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { CommandPalette } from './CommandPalette';
import type { Me } from '@/api/types';

const me: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };

describe('CommandPalette V2', () => {
  it('이동/액션 그룹과 단축키 표시, 결과 없음 상태', () => {
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={() => {}} onAction={() => {}} />);
    expect(screen.getByText('이동')).toBeInTheDocument();
    expect(screen.getByText('액션')).toBeInTheDocument();
    expect(screen.getByText('G T')).toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox', { name: '검색' }), { target: { value: 'zzzzzz' } });
    expect(screen.getByText('일치하는 결과가 없습니다.')).toBeInTheDocument();
  });
  it('Enter 로 선택 항목 실행', () => {
    const onNavigate = vi.fn();
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={onNavigate} onAction={() => {}} />);
    fireEvent.keyDown(screen.getByRole('textbox', { name: '검색' }), { key: 'Enter' });
    expect(onNavigate).toHaveBeenCalledWith({ name: 'tenants' });
  });
});
```

- [ ] **Step 2: 실패 확인** — Run: `npx vitest run src/extras` → FAIL

- [ ] **Step 3: useGoShortcuts 구현**

```ts
// admin-ui/src/extras/useGoShortcuts.ts
import { useEffect } from 'react';
import type { AppRoute } from '@/appRoute';

export const GO_SHORTCUTS = { tenants: 'G T', activity: 'G A', 'audit-chain': 'G C', settings: 'G S' } as const;
const KEY_TO_ROUTE: Record<string, AppRoute> = { t: { name: 'tenants' }, a: { name: 'activity' }, c: { name: 'audit-chain' }, s: { name: 'settings' } };

function isTyping(t: EventTarget | null): boolean {
  const el = t as HTMLElement | null;
  if (!el || !el.tagName) return false;
  return /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) || el.isContentEditable;
}

export function useGoShortcuts(onNavigate: (r: AppRoute) => void, enabled: boolean) {
  useEffect(() => {
    if (!enabled) return;
    let armed = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    function onKey(e: KeyboardEvent) {
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
```

App.tsx 의 `AuthenticatedApp` 에서 ⌘K effect 아래에 추가: `useGoShortcuts(setRoute, isPlatform(me));` (RP_ADMIN 은 플랫폼 화면이 없으므로 비활성). import: `import { useGoShortcuts } from '@/extras/useGoShortcuts'; import { isPlatform } from '@/me/roles';`

- [ ] **Step 4: CommandPalette V2**

기존 파일을 먼저 읽어 그룹 데이터(이동 4개, 현재 Tenant 탭, 액션 4개), 필터링, ↑↓/Enter/Escape 처리, `onAction(kind)` 값을 확인한다. 로직은 유지하고 마크업만 V2(아트보드 `Overlays.dc.html` 왼쪽)로 바꾼다:
- 컨테이너: `<Dialog open onClose wide ariaLabel="Command Palette">` 를 쓰지 말고 기존처럼 자체 `.scrim` + `<div className="dialog" role="dialog" aria-modal="true" aria-label="Command Palette" style={{ maxWidth: 640 }}>` — 헤더 없이 검색줄부터 시작.
- 검색줄: `<label className="row" style={{ flexWrap: 'nowrap', gap: 10, padding: '14px 16px', borderBottom: '1px solid var(--line-soft)' }}><Icons.Search size={18} /><span className="sr-only">검색</span><input aria-label="검색" … style={{ flex: 1, border: 0, outline: 'none', font: 'inherit', fontSize: 15, background: 'transparent' }} /><kbd>esc</kbd></label>`
- 그룹 제목: `<span className="t-over" style={{ display: 'block', padding: '10px 16px 4px' }}>이동</span>` / `현재 Tenant · {이름}` / `액션`.
- 항목: `<button className="menu__item" aria-selected={i === sel} style={i === sel ? { background: 'var(--brand-100)', boxShadow: 'inset 3px 0 0 var(--brand-700)' } : undefined}>` + 아이콘 16 + 라벨 + 오른쪽 경로 `<span className="hint" style={{ margin: '0 0 0 auto' }}>플랫폼|관리|탭 7</span>` + 이동 항목에는 `<kbd>{GO_SHORTCUTS[id]}</kbd>`. 로그아웃 항목은 `menu__item--danger`.
- 결과 없음: `<EmptyState compact illustration={illustrations.noResults} title="일치하는 결과가 없습니다." />`
- 푸터: `<div className="row" style={{ gap: 14, padding: '10px 16px', borderTop: '1px solid var(--line-soft)', background: 'var(--canvas)', fontSize: 12, color: 'var(--mute)' }}><span><kbd>↑</kbd><kbd>↓</kbd> 이동</span><span><kbd>↵</kbd> 실행</span><span style={{ marginLeft: 'auto' }}>{n} 결과</span></div>`

- [ ] **Step 5: IdleSessionModal 진행 링**

`Dialog` 의 `title` 영역을 다음으로 바꾸고, 기존 가로 진행 바(`height: 6`)를 삭제한다. 본문 문구(`보안을 위해 {idleTimeoutMinutes}분 동안 …`)와 버튼·타이머 로직은 그대로.

```tsx
const pct = secondsLeft / countdownSeconds;
const C = 2 * Math.PI * 28;
// title
<span className="row" style={{ gap: 16, flexWrap: 'nowrap' }}>
  <span style={{ position: 'relative', width: 64, height: 64, flex: 'none' }}>
    <svg width="64" height="64" viewBox="0 0 64 64" aria-hidden="true">
      <circle cx="32" cy="32" r="28" fill="none" stroke="var(--line-soft)" strokeWidth="6" />
      <circle cx="32" cy="32" r="28" fill="none" stroke={secondsLeft > 20 ? 'var(--warn-dot)' : 'var(--bad)'} strokeWidth="6" strokeLinecap="round"
        strokeDasharray={`${C} ${C}`} strokeDashoffset={C * (1 - pct)} transform="rotate(-90 32 32)" style={{ transition: 'stroke-dashoffset 1s linear' }} />
    </svg>
    <span className="mono" style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 15, fontWeight: 600 }}>{secondsLeft}s</span>
  </span>
  <span>세션이 곧 만료됩니다</span>
</span>
```

`Dialog` 호출에 `dismissible={false} role="alertdialog"` 를 추가한다(`closeOnScrim={false}` 대신).

- [ ] **Step 6: 테스트·빌드** — Run: `npx vitest run src/extras && npm test && npm run build` → PASS

- [ ] **Step 7: 커밋**

```bash
git add src/extras src/App.tsx
git commit -m "feat(admin-ui): V2 커맨드 팔레트·G 이동 단축키·세션 만료 진행 링

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: 로그인 · MFA 챌린지

V2 아트보드: `v2/Auth-Login.dc.html`, `v2/Auth-Mfa.dc.html`.

**Files:**
- Rewrite: `admin-ui/src/pages/LoginPage.tsx`, `admin-ui/src/pages/MfaChallenge.tsx`
- Create: `admin-ui/src/pages/LoginPage.test.tsx`
- Rewrite: `admin-ui/src/pages/MfaChallenge.test.tsx`

**Interfaces:**
- Consumes: Task 4 `illustrations.loginHero`, `illustrations.mfa` / Task 5 `Alert`, `Button` / Task 6 `Field` / Task 7 `OtpInput` / Task 8 `useToast`, `ToastHost` (`@/ui`) / 기존 `api.get`, `api.loginForm`, `getMe`(`@/api/client`), `mfaApi.verify`(`@/api/mfa`), `formatRecoveryCode`(`@/lib/recoveryCode`).
- Produces: `LoginPage({ onLogin: (me: Me) => void })`(default export), `MfaChallenge({ onVerified: () => void; onLogout: () => void })`(default export) — 시그니처 변경 없음. App.tsx 수정 없음.

유지하는 동작(코드 그대로 옮김):
- LoginPage: `GET /admin/api/profile` 로 dev/local 판별 → `devProfile`, `selectDemo('PLATFORM_OPERATOR')` 는 dev 일 때만 `alice@crosscert.com` / `alice-temp-pw` prefill, `handleSubmit` 의 `api.loginForm` → false 면 `A001 · 이메일 또는 비밀번호가 올바르지 않습니다.`, 예외면 `code ?? 'C999'` + `serverMessage || message || '로그인 실패'`, 두 경우 모두 토스트 `로그인 실패`, 성공 시 `getMe()` → `onLogin(me)`. 링크 `/admin/forgot-password`, `/admin/signup`. 브랜드 패널 지표 3개는 현재 값(58 · 2.4M · 100%) 그대로.
- MfaChallenge: `mfaApi.verify(code.trim())`, 복구 코드 사용 시 경고 토스트 `복구 코드를 사용했습니다.` / `남은 복구 코드: {n}개`, 실패 시 토스트 `인증 실패` + 입력 초기화, 모드 전환 시 입력 초기화, 복구 코드 형식 `formatRecoveryCode` + `/^[A-Z2-9]{4}-[A-Z2-9]{4}$/`.

변경 동작:
- 서버 오류는 폼 맨 위 `Alert tone="bad"`(제목 `로그인 실패`, 본문 `{message}` + 고정폭 `{code}`).
- DEV 데모 카드는 기존과 같이 `devProfile` 일 때만, 아트보드 위치(로그인 버튼 아래)로 이동.
- MFA: 6자리를 채우면 `onComplete` 로 자동 제출. 수동 `확인` 버튼은 유지. 자동 제출과 버튼/Enter 가 겹쳐도 `verify` 는 한 번만 호출되도록 `useRef` 가드.

- [ ] **Step 1: 실패하는 테스트 작성**

```tsx
// admin-ui/src/pages/LoginPage.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import type { Me } from '@/api/types';
import { ApiError } from '@/api/types';

vi.mock('@/api/client', () => ({
  api: { get: vi.fn(), loginForm: vi.fn() },
  getMe: vi.fn(),
}));

import { api, getMe } from '@/api/client';
import { ToastHost } from '@/ui';
import LoginPage from './LoginPage';

const me: Me = { email: 'alice@crosscert.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };

function renderLogin(onLogin = vi.fn()) {
  const utils = render(<ToastHost><LoginPage onLogin={onLogin} /></ToastHost>);
  return { onLogin, ...utils };
}
function fillAndSubmit(email: string, pw: string) {
  fireEvent.change(screen.getByLabelText('이메일'), { target: { value: email } });
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: pw } });
  fireEvent.click(screen.getByRole('button', { name: '로그인' }));
}

beforeEach(() => {
  vi.mocked(api.get).mockReset().mockRejectedValue(new Error('prod'));
  vi.mocked(api.loginForm).mockReset();
  vi.mocked(getMe).mockReset();
});

describe('LoginPage V2', () => {
  it('브랜드 패널: 워드마크·헤드라인·장식 일러스트·지표 3개', () => {
    const { container } = renderLogin();
    expect(screen.getByAltText('CROSSCERT')).toHaveAttribute('src', '/admin/crosscert-logo-transparent.png');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('패스키 인증을운영하는 콘솔.');
    expect(container.querySelector('aside img[alt=""]')).not.toBeNull();
    for (const l of ['활성 tenant', 'ceremony / 24h', 'chain 무결성']) expect(screen.getByText(l)).toBeInTheDocument();
  });

  it('비밀번호 라벨 오른쪽에 재설정 링크, 하단에 가입 요청 링크와 자동 로그아웃 안내', () => {
    renderLogin();
    expect(screen.getByRole('link', { name: '비밀번호를 잊으셨나요?' })).toHaveAttribute('href', '/admin/forgot-password');
    expect(screen.getByRole('link', { name: '가입 요청' })).toHaveAttribute('href', '/admin/signup');
    expect(screen.getByText('30분 동안 활동이 없으면 자동으로 로그아웃됩니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '로그인' })).toHaveClass('btn', 'btn--primary', 'btn--lg');
  });

  it('로그인 실패: 폼 맨 위 alert 에 메시지와 코드', async () => {
    vi.mocked(api.loginForm).mockResolvedValue(false);
    renderLogin();
    fillAndSubmit('a@x.com', 'wrong');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('로그인 실패');
    expect(alert).toHaveTextContent('이메일 또는 비밀번호가 올바르지 않습니다.');
    expect(alert).toHaveTextContent('A001');
    // 이메일 입력보다 앞(폼 상단)에 위치
    expect(alert.compareDocumentPosition(screen.getByLabelText('이메일')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('서버 예외는 code · serverMessage 로 표시', async () => {
    vi.mocked(api.loginForm).mockRejectedValue(new ApiError(503, 'C503', '서버 점검 중'));
    renderLogin();
    fillAndSubmit('a@x.com', 'pw');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('서버 점검 중');
    expect(alert).toHaveTextContent('C503');
  });

  it('성공하면 getMe 결과로 onLogin', async () => {
    vi.mocked(api.loginForm).mockResolvedValue(true);
    vi.mocked(getMe).mockResolvedValue(me);
    const { onLogin } = renderLogin();
    fillAndSubmit('alice@crosscert.com', 'pw');
    await waitFor(() => expect(onLogin).toHaveBeenCalledWith(me));
    expect(api.loginForm).toHaveBeenCalledWith('alice@crosscert.com', 'pw');
  });

  it('dev/local 이 아니면 DEV 데모 카드가 없다', async () => {
    renderLogin();
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/admin/api/profile'));
    expect(screen.queryByText('데모 계정으로 로그인')).toBeNull();
  });

  it('dev 프로필이면 DEV 데모 카드가 보이고 클릭 시 alice 계정을 채운다', async () => {
    vi.mocked(api.get).mockResolvedValue({ active: ['dev'] });
    renderLogin();
    const card = await screen.findByRole('button', { name: /데모 계정으로 로그인/ });
    expect(card).toHaveTextContent('DEV');
    expect(card).toHaveTextContent('Platform Operator / alice@crosscert.com');
    fireEvent.click(card);
    expect(card).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByLabelText('이메일')).toHaveValue('alice@crosscert.com');
    expect(screen.getByLabelText('비밀번호')).toHaveValue('alice-temp-pw');
  });
});
```

```tsx
// admin-ui/src/pages/MfaChallenge.test.tsx (교체)
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

vi.mock('@/api/mfa', () => ({ mfaApi: { verify: vi.fn() } }));

import { mfaApi } from '@/api/mfa';
import { ToastHost } from '@/ui';
import MfaChallenge from './MfaChallenge';

function renderChallenge(onVerified = vi.fn(), onLogout = vi.fn()) {
  const utils = render(
    <ToastHost>
      <MfaChallenge onVerified={onVerified} onLogout={onLogout} />
    </ToastHost>,
  );
  return { onVerified, onLogout, ...utils };
}
const cell = (i: number) => screen.getByRole('textbox', { name: `인증 코드 ${i + 1}번째 자리` }) as HTMLInputElement;
const cellValues = () => [0, 1, 2, 3, 4, 5].map((i) => cell(i).value).join('');
const toRecovery = () => fireEvent.click(screen.getByRole('button', { name: /복구 코드로 로그인/ }));

beforeEach(() => {
  vi.mocked(mfaApi.verify).mockReset().mockResolvedValue({ verified: true });
});

describe('MfaChallenge V2', () => {
  it('공통 카드 셸: 워드마크·장식 일러스트·OTP 6칸·카드 밖 로그아웃', () => {
    const { container, onLogout } = renderChallenge();
    expect(container.querySelector('.auth .auth-card')).not.toBeNull();
    expect(screen.getByAltText('CROSSCERT')).toHaveClass('auth__logo');
    expect(container.querySelector('.auth-card img[alt=""]')).not.toBeNull();
    expect(screen.getByRole('heading', { name: '2단계 인증' })).toBeInTheDocument();
    expect(screen.getAllByRole('textbox')).toHaveLength(6);
    const logout = screen.getByRole('button', { name: '로그아웃' });
    expect(logout.closest('.auth-card')).toBeNull();
    fireEvent.click(logout);
    expect(onLogout).toHaveBeenCalled();
  });

  it('TOTP 칸은 숫자 아닌 입력을 무시하고, 붙여넣기는 숫자만 6자리로 자른다', () => {
    renderChallenge();
    fireEvent.change(cell(0), { target: { value: 'a' } });
    expect(cell(0).value).toBe('');
    fireEvent.paste(cell(0), { clipboardData: { getData: () => 'a1b2c3d4e5f6g7' } });
    expect(cellValues()).toBe('123456');
  });

  it('6자리를 모두 입력하면 verify 를 정확히 한 번 호출한다(자동 제출)', async () => {
    let resolve!: (v: { verified: boolean }) => void;
    vi.mocked(mfaApi.verify).mockReturnValue(new Promise((r) => { resolve = r; }));
    const { onVerified } = renderChallenge();
    '123456'.split('').forEach((d, i) => fireEvent.change(cell(i), { target: { value: d } }));
    // 진행 중 수동 확인(버튼·Enter)이 겹쳐도 중복 호출하지 않는다
    fireEvent.submit(screen.getByRole('button', { name: /확인/ }).closest('form')!);
    expect(mfaApi.verify).toHaveBeenCalledTimes(1);
    expect(mfaApi.verify).toHaveBeenCalledWith('123456');
    resolve({ verified: true });
    await waitFor(() => expect(onVerified).toHaveBeenCalledTimes(1));
    expect(mfaApi.verify).toHaveBeenCalledTimes(1);
  });

  it('수동 확인 버튼은 6자리 전에는 비활성, 실패하면 칸을 비우고 토스트', async () => {
    vi.mocked(mfaApi.verify).mockRejectedValue(new Error('invalid_code'));
    renderChallenge();
    fireEvent.change(cell(0), { target: { value: '1' } });
    expect(screen.getByRole('button', { name: '확인' })).toBeDisabled();
    fireEvent.paste(cell(0), { clipboardData: { getData: () => '999999' } });
    expect(await screen.findByText('인증 실패')).toBeInTheDocument();
    await waitFor(() => expect(cellValues()).toBe(''));
  });

  it('복구 코드 모드는 같은 카드 안에서 전환되고 대문자·대시로 정형한다', () => {
    const { container } = renderChallenge();
    const card = container.querySelector('.auth-card');
    toRecovery();
    expect(container.querySelector('.auth-card')).toBe(card);
    expect(screen.getByRole('heading', { name: '복구 코드로 로그인' })).toBeInTheDocument();
    const rec = screen.getByLabelText('복구 코드') as HTMLInputElement;
    expect(rec).toHaveAttribute('placeholder', 'AB3F-2K7M');
    fireEvent.change(rec, { target: { value: 'ab3f2k7m' } });
    expect(rec.value).toBe('AB3F-2K7M');
  });

  it('TOTP 로 돌아가면 입력이 비워진다', () => {
    renderChallenge();
    toRecovery();
    fireEvent.change(screen.getByLabelText('복구 코드'), { target: { value: 'abcd' } });
    fireEvent.click(screen.getByRole('button', { name: /authenticator 코드로 돌아가기/ }));
    expect(cellValues()).toBe('');
  });

  it('복구 코드가 부분 입력이면 확인 비활성', () => {
    renderChallenge();
    toRecovery();
    fireEvent.change(screen.getByLabelText('복구 코드'), { target: { value: 'ab3f2' } });
    expect(screen.getByRole('button', { name: '확인' })).toBeDisabled();
  });

  it('복구 코드가 완성되면 확인 활성, 제출 시 그 코드로 verify', async () => {
    const { onVerified } = renderChallenge();
    toRecovery();
    fireEvent.change(screen.getByLabelText('복구 코드'), { target: { value: 'ab3f2k7m' } });
    const btn = screen.getByRole('button', { name: '확인' });
    expect(btn).not.toBeDisabled();
    fireEvent.click(btn);
    await waitFor(() => expect(onVerified).toHaveBeenCalled());
    expect(mfaApi.verify).toHaveBeenCalledWith('AB3F-2K7M');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/LoginPage.test.tsx src/pages/MfaChallenge.test.tsx`
Expected: FAIL — LoginPage: `aside img[alt=""]` 없음, `role="alert"` 없음(현재 오류는 인라인 div), DEV 카드 문구 불일치. MfaChallenge: `인증 코드 1번째 자리` textbox 없음, `.auth-card` 없음.

- [ ] **Step 3: LoginPage 구현**

```tsx
// admin-ui/src/pages/LoginPage.tsx
import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { api, getMe } from '@/api/client';
import type { Me } from '@/api/types';
import { illustrations } from '@/assets/illustrations';
import { Alert, Button, useToast } from '@/ui';

type LoginPageProps = {
  onLogin: (me: Me) => void;
};

// 브랜드 패널 지표 — 현재 화면에 표시하던 값 그대로(로그인 전이라 API 조회 없음).
const STATS = [
  { value: '58', label: '활성 tenant' },
  { value: '2.4M', label: 'ceremony / 24h' },
  { value: '100%', label: 'chain 무결성' },
] as const;

const DEMO_ACCOUNTS = [
  { v: 'PLATFORM_OPERATOR' as const, t: 'Platform Operator', s: 'alice@crosscert.com' },
] as const;

export default function LoginPage({ onLogin }: LoginPageProps) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [demoRole, setDemoRole] = useState<'PLATFORM_OPERATOR' | 'RP_ADMIN' | null>(null);
  const [devProfile, setDevProfile] = useState(false);
  const [error, setError] = useState<{ code: string; message: string } | null>(null);
  const toast = useToast();

  // dev/local profile 감지 — 데모 prefill + 데모 카드 노출 게이팅
  useEffect(() => {
    api.get<{ active: string[]; local?: boolean }>('/admin/api/profile')
      .then((p) => {
        if (p.active?.includes('dev') || p.active?.includes('local') || p.local) {
          setDevProfile(true);
        }
      })
      .catch(() => { /* prod 등 — 무시 */ });
  }, []);

  // 데모 role 카드 클릭 → 자동 prefill (dev/local 일 때만)
  function selectDemo(role: 'PLATFORM_OPERATOR' | 'RP_ADMIN') {
    setDemoRole(role);
    if (devProfile && role === 'PLATFORM_OPERATOR') {
      setEmail('alice@crosscert.com');
      setPassword('alice-temp-pw');
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting) return;
    setError(null);
    setSubmitting(true);
    try {
      const ok = await api.loginForm(email, password);
      if (!ok) {
        const msg = '이메일 또는 비밀번호가 올바르지 않습니다.';
        setError({ code: 'A001', message: msg });
        toast({ kind: 'err', title: '로그인 실패', message: msg });
        return;
      }
      const me = await getMe();
      onLogin(me);
    } catch (err: unknown) {
      const e = err as { code?: string; serverMessage?: string; message?: string };
      const code = e?.code ?? 'C999';
      const message = e?.serverMessage || e?.message || '로그인 실패';
      setError({ code, message });
      toast({ kind: 'err', title: '로그인 실패', message });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'flex', flexWrap: 'wrap', background: 'var(--surface)' }}>
      {/* 브랜드 패널 */}
      <aside style={{ flex: '1 1 440px', background: 'var(--brand-50)', borderRight: '1px solid var(--line)', padding: '48px 56px', boxSizing: 'border-box', display: 'flex', flexDirection: 'column', gap: 24 }}>
        <img src="/admin/crosscert-logo-transparent.png" alt="CROSSCERT" style={{ width: 156, height: 'auto' }} />
        <div className="col" style={{ gap: 10, marginTop: 12 }}>
          <h1 style={{ margin: 0, fontSize: 34, lineHeight: '44px', fontWeight: 700, letterSpacing: '-0.02em', color: 'var(--ink)' }}>
            패스키 인증을<br />운영하는 콘솔.
          </h1>
          <p style={{ margin: 0, fontSize: 15, lineHeight: '24px', color: 'var(--ink-2)', maxWidth: 420 }}>
            RP 별 tenant · credential · API 키를 한 곳에서 관리하고, 모든 조작은 hash chain 감사 로그로 남습니다.
          </p>
        </div>
        <img src={illustrations.loginHero} alt="" style={{ width: '100%', maxWidth: 420, height: 'auto', alignSelf: 'center' }} />
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 16, paddingTop: 18, borderTop: '1px solid var(--line)', marginTop: 'auto' }}>
          {STATS.map((s) => (
            <div key={s.label} className="col">
              <span style={{ fontSize: 22, fontWeight: 700, color: 'var(--ink)' }}>{s.value}</span>
              <span className="hint" style={{ margin: 0 }}>{s.label}</span>
            </div>
          ))}
        </div>
        <p className="hint" style={{ margin: 0 }}>© 2026 Crosscert · 본 콘솔 접근은 모두 audit log에 기록됩니다.</p>
      </aside>

      {/* 폼 */}
      <main style={{ flex: '1 1 440px', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '48px 24px', boxSizing: 'border-box' }}>
        <form onSubmit={handleSubmit} className="col" style={{ width: '100%', maxWidth: 380, gap: 20 }}>
          <div className="col" style={{ gap: 6 }}>
            <h2 className="page__title">관리자 로그인</h2>
            <p className="card__sub">등록된 운영자 계정으로 로그인하세요.</p>
          </div>

          {error && (
            <Alert tone="bad" title="로그인 실패">
              {error.message} <span className="mono" style={{ fontSize: 11 }}>{error.code}</span>
            </Alert>
          )}

          <div>
            <label className="label" htmlFor="login-email">이메일</label>
            <input
              id="login-email"
              className="input input--lg"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="username"
              placeholder="you@crosscert.com"
            />
          </div>
          <div>
            <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-end' }}>
              <label className="label" htmlFor="login-password">비밀번호</label>
              <a href="/admin/forgot-password" style={{ fontSize: 12, fontWeight: 500, marginBottom: 6 }}>비밀번호를 잊으셨나요?</a>
            </div>
            <input
              id="login-password"
              className="input input--lg"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              placeholder="••••••••"
            />
          </div>

          <Button type="submit" variant="primary" size="lg" disabled={submitting} style={{ width: '100%' }}>
            {submitting ? '확인 중…' : '로그인'}
          </Button>

          {/* 데모 계정 — dev/local 프로필에서만 */}
          {devProfile && DEMO_ACCOUNTS.map((opt) => {
            const on = demoRole === opt.v;
            return (
              <button
                key={opt.v}
                type="button"
                aria-pressed={on}
                onClick={() => selectDemo(opt.v)}
                className="row"
                style={{
                  flexWrap: 'nowrap', gap: 10, width: '100%', padding: '12px 14px', boxSizing: 'border-box',
                  border: `1px dashed ${on ? 'var(--brand-700)' : 'var(--input-line)'}`, borderRadius: 'var(--r)',
                  background: on ? 'var(--brand-50)' : 'var(--canvas)', color: 'var(--ink)', font: 'inherit', textAlign: 'left', cursor: 'pointer',
                }}
              >
                <span className="tag">DEV</span>
                <span className="col" style={{ flex: '1 1 0', minWidth: 0 }}>
                  <span style={{ fontSize: 13, fontWeight: 600 }}>데모 계정으로 로그인</span>
                  <span className="mono hint truncate" style={{ margin: 0 }}>{opt.t} / {opt.s}</span>
                </span>
                <span style={{ color: 'var(--mute)', display: 'inline-flex' }}><Icons.ChevronRight size={16} /></span>
              </button>
            );
          })}

          <p className="card__sub" style={{ textAlign: 'center' }}>
            계정이 없으신가요? <a href="/admin/signup" style={{ fontWeight: 600 }}>가입 요청</a>
          </p>
          <p className="hint" style={{ margin: 0, textAlign: 'center', paddingTop: 14, borderTop: '1px solid var(--line-soft)' }}>
            30분 동안 활동이 없으면 자동으로 로그아웃됩니다.
          </p>
        </form>
      </main>
    </div>
  );
}
```

`Stat` 함수와 `BrandMark` import, 남색 그라데이션 패널·원형 SVG 장식은 삭제한다.

- [ ] **Step 4: MfaChallenge 구현**

```tsx
// admin-ui/src/pages/MfaChallenge.tsx
import { useRef, useState } from 'react';
import { mfaApi } from '@/api/mfa';
import { formatRecoveryCode } from '@/lib/recoveryCode';
import { illustrations } from '@/assets/illustrations';
import { Button, Field, OtpInput, useToast } from '@/ui';

const TOTP_RE = /^\d{6}$/;
const RECOVERY_RE = /^[A-Z2-9]{4}-[A-Z2-9]{4}$/;

export default function MfaChallenge({ onVerified, onLogout }: { onVerified: () => void; onLogout: () => void }) {
  const [mode, setMode] = useState<'totp' | 'recovery'>('totp');
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  // 자동 제출(onComplete)과 수동 확인이 같은 틱에 겹쳐도 verify 는 한 번만.
  const inFlight = useRef(false);
  const toast = useToast();

  const isReady = (v: string) => (mode === 'totp' ? TOTP_RE.test(v) : RECOVERY_RE.test(v));
  const ready = isReady(code);

  async function verify(value: string) {
    if (inFlight.current || !isReady(value)) return;
    inFlight.current = true;
    setSubmitting(true);
    try {
      const res = await mfaApi.verify(value.trim());
      if (res.usedRecoveryCode && typeof res.remaining === 'number') {
        toast({ kind: 'warn', title: '복구 코드를 사용했습니다.', message: `남은 복구 코드: ${res.remaining}개` });
      }
      onVerified();
    } catch {
      toast({ kind: 'err', title: '인증 실패', message: mode === 'totp' ? '코드가 올바르지 않습니다.' : '복구 코드가 올바르지 않습니다.' });
      setCode('');
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
  }

  function submit(e: React.FormEvent) {
    e.preventDefault();
    void verify(code);
  }

  function switchMode(next: 'totp' | 'recovery') {
    setMode(next);
    setCode('');
  }

  return (
    <main className="auth">
      <div className="auth__col">
        <img className="auth__logo" src="/admin/crosscert-logo-transparent.png" alt="CROSSCERT" />
        <form className="auth-card" onSubmit={submit}>
          {mode === 'totp' ? (
            <>
              <div className="col" style={{ gap: 6, alignItems: 'center', textAlign: 'center' }}>
                <img src={illustrations.mfa} alt="" style={{ width: 88, height: 88, objectFit: 'contain' }} />
                <h1 className="page__title">2단계 인증</h1>
                <p className="card__sub">authenticator 앱에 표시된 6자리 코드를 입력하세요.</p>
              </div>
              <div>
                <OtpInput value={code} onChange={setCode} onComplete={(v) => void verify(v)} disabled={submitting} autoFocus />
                <p className="hint" style={{ textAlign: 'center' }}>붙여넣기 지원 · 6자리를 모두 입력하면 자동 확인</p>
              </div>
              <Button type="submit" variant="primary" size="lg" disabled={submitting || !ready}>
                {submitting ? '확인 중…' : '확인'}
              </Button>
              <div className="row" style={{ justifyContent: 'center', gap: 6, paddingTop: 4, borderTop: '1px solid var(--line-soft)' }}>
                <span className="card__sub">기기를 잃으셨나요?</span>
                <Button variant="ghost" size="sm" onClick={() => switchMode('recovery')}>복구 코드로 로그인</Button>
              </div>
            </>
          ) : (
            <>
              <div className="col" style={{ gap: 6 }}>
                <h1 className="page__title">복구 코드로 로그인</h1>
                <p className="card__sub">MFA 등록 때 저장한 복구 코드 하나를 입력하세요. 사용한 코드는 다시 쓸 수 없습니다.</p>
              </div>
              <Field label="복구 코드" htmlFor="mfa-recovery">
                <input
                  id="mfa-recovery"
                  className="input input--lg mono"
                  autoFocus
                  value={code}
                  onChange={(e) => setCode(formatRecoveryCode(e.target.value))}
                  placeholder="AB3F-2K7M"
                  style={{ minHeight: 48, fontSize: 16, letterSpacing: '0.08em', textAlign: 'center' }}
                />
              </Field>
              <Button type="submit" variant="primary" size="lg" disabled={submitting || !ready}>
                {submitting ? '확인 중…' : '확인'}
              </Button>
              <Button variant="ghost" size="sm" onClick={() => switchMode('totp')}>← authenticator 코드로 돌아가기</Button>
            </>
          )}
        </form>
        <Button variant="ghost" onClick={onLogout} style={{ color: 'var(--ink-2)' }}>로그아웃</Button>
      </div>
    </main>
  );
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `npx vitest run src/pages/LoginPage.test.tsx src/pages/MfaChallenge.test.tsx`
Expected: PASS (LoginPage 7개, MfaChallenge 8개).

- [ ] **Step 6: 전체 테스트·린트·빌드**

Run: `npm test && npm run lint && npm run build`
Expected: 전체 PASS, lint 오류 0(경고만 허용 — 현재 설정이 warn 레벨), 빌드 성공. `grep -n "BrandMark\|linear-gradient" src/pages/LoginPage.tsx` 결과 없음.

- [ ] **Step 7: 수동 확인**

Run: `npm run dev` → `/admin` (로그아웃 상태). 1280px 에서 `v2/Auth-Login.dc.html` 과 비교: 좌 브랜드 패널(연한 브랜드 배경·워드마크·히어로·지표 3), 우 폼. 잘못된 비밀번호로 로그인 → 폼 맨 위 빨간 배너. dev 프로필이면 로그인 버튼 아래 점선 DEV 카드. MFA 켜진 계정으로 로그인 → 6자리 입력 즉시 자동 확인, `복구 코드로 로그인` 이 같은 카드 안에서 전환, 카드 밖 `로그아웃`.

- [ ] **Step 8: 커밋**

```bash
git add src/pages/LoginPage.tsx src/pages/LoginPage.test.tsx src/pages/MfaChallenge.tsx src/pages/MfaChallenge.test.tsx
git commit -m "feat(admin-ui): V2 로그인(브랜드 패널·상단 오류 배너)·MFA 챌린지(OtpInput 자동 제출·같은 카드 복구 코드)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 15: 비밀번호 재설정 요청 · 새 비밀번호 설정 · 가입 요청

V2 아트보드: `v2/Auth-Forgot.dc.html`, `v2/Auth-Reset.dc.html`, `v2/Auth-Signup.dc.html`.

**Files:**
- Rewrite: `admin-ui/src/pages/ForgotPasswordPage.tsx`, `admin-ui/src/pages/ResetPasswordPage.tsx`, `admin-ui/src/pages/SignupRequestPage.tsx`
- Create: `admin-ui/src/pages/ForgotPasswordPage.test.tsx`, `admin-ui/src/pages/ResetPasswordPage.test.tsx`
- Rewrite: `admin-ui/src/pages/SignupRequestPage.test.tsx`

**Interfaces:**
- Consumes: Task 3 클래스 `.auth`, `.auth__col`, `.auth__logo`, `.auth-card`, `.page__title`, `.card__sub`, `.hint` / Task 4 `illustrations.signupSent` / Task 5 `Alert`, `Button` / Task 6 `Field` / Task 11 `renderWithDataRouter`(테스트) / 기존 `passwordResetApi.request|confirm`, `signupRequestsApi.request`, `ApiError`.
- Produces: 세 페이지 default export, props 없음 — 변경 없음. App.tsx 의 경로 분기(`/forgot-password`, `/reset-password`, `/signup`) 그대로.

공통 셸(세 화면 동일): `<main className="auth"><div className="auth__col"><img className="auth__logo" …/>` + 카드 `.auth-card` + (해당 상태일 때) 카드 밖 `← 로그인으로` 링크. 제출 후에는 **같은 `.auth-card` 요소** 안의 내용만 완료 상태로 바뀐다(새 화면·새 카드 아님).

유지하는 동작:
- Forgot: `passwordResetApi.request(email.trim())`, 성공·실패와 무관하게 같은 완료 안내(계정 존재 비노출), 빈 이메일이면 제출 불가, 버튼 `재설정 링크 보내기` / `전송 중…`.
- Reset: `?token=` 없으면 무효 상태, `passwordResetApi.confirm(token, pw)`, 실패 시 `ApiError.serverMessage`(아니면 `비밀번호 변경에 실패했습니다.`) 표시, 불일치면 제출 불가, 버튼 `비밀번호 변경` / `변경 중…`.
- Signup: `EMAIL_RE`, 비밀번호 12~128자, 일치, 사유 500자 이하, `{ email: trim().toLowerCase(), password, reason: trim() || undefined }`, 성공·실패 무관 같은 접수 안내, 버튼 `가입 요청 보내기` / `전송 중…`.

변경 동작:
- Reset: V2 문구 `12자 이상으로 새 비밀번호를 입력하세요.` 에 맞춰 클라이언트에서 12자 미만이면 제출 불가 + 길이 미터(`12자 이상` / `12자 이상 충족`). 가입 요청과 같은 정책(12자)이며 서버는 길이 검증을 하지 않으므로 서버 동작에는 영향 없음.
- 무효 링크 상태 버튼 `재설정 다시 요청`(→ `/admin/forgot-password`), 카드 밖 `← 로그인으로` 유지. 완료 상태 `로그인 →`(→ `/admin`).
- 불일치 문구 `두 비밀번호가 일치하지 않습니다.` → V2 `비밀번호가 일치하지 않습니다.`, 가입 요청 길이 오류 → V2 `12자 이상`.

- [ ] **Step 1: 실패하는 테스트 작성**

```tsx
// admin-ui/src/pages/ForgotPasswordPage.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';

vi.mock('@/api/passwordReset', () => ({ passwordResetApi: { request: vi.fn(), confirm: vi.fn() } }));

import { passwordResetApi } from '@/api/passwordReset';
import ForgotPasswordPage from './ForgotPasswordPage';

afterEach(cleanup);
beforeEach(() => vi.mocked(passwordResetApi.request).mockReset().mockResolvedValue({ ok: true }));

async function submit(email: string) {
  const utils = render(<ForgotPasswordPage />);
  const card = utils.container.querySelector('.auth-card');
  fireEvent.change(screen.getByLabelText('이메일'), { target: { value: email } });
  fireEvent.click(screen.getByRole('button', { name: '재설정 링크 보내기' }));
  await screen.findByText('메일을 확인하세요');
  return { ...utils, card };
}

describe('ForgotPasswordPage V2', () => {
  it('공통 셸: 워드마크 + 카드 + 카드 밖 로그인 링크', () => {
    const { container } = render(<ForgotPasswordPage />);
    expect(screen.getByAltText('CROSSCERT')).toHaveClass('auth__logo');
    expect(container.querySelector('.auth .auth-card')).not.toBeNull();
    const back = screen.getByRole('link', { name: '← 로그인으로' });
    expect(back).toHaveAttribute('href', '/admin');
    expect(back.closest('.auth-card')).toBeNull();
  });

  it('빈 이메일이면 제출 불가', () => {
    render(<ForgotPasswordPage />);
    expect(screen.getByRole('button', { name: '재설정 링크 보내기' })).toBeDisabled();
  });

  it('제출 후 같은 카드 안에서 완료 상태로 바뀐다', async () => {
    const { container, card } = await submit('  a@x.com ');
    expect(passwordResetApi.request).toHaveBeenCalledWith('a@x.com');
    expect(container.querySelector('.auth-card')).toBe(card);
    expect(screen.getByRole('heading', { name: '비밀번호 재설정' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('해당 이메일로 가입된 계정이 있으면 재설정 링크가 발송됩니다.');
    expect(screen.getByText('메일이 오지 않으면 스팸함을 확인하거나 플랫폼 운영자에게 문의하세요.')).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(screen.queryByRole('button', { name: '재설정 링크 보내기' })).toBeNull();
  });

  it('요청이 실패해도 완료 카드 내용이 성공 때와 같다(계정 존재 비노출)', async () => {
    const ok = await submit('a@x.com');
    const okText = ok.container.querySelector('.auth-card')!.textContent;
    cleanup();
    vi.mocked(passwordResetApi.request).mockRejectedValueOnce(new Error('404'));
    const fail = await submit('nobody@x.com');
    const failText = fail.container.querySelector('.auth-card')!.textContent;
    expect(failText).toBe(okText);
    expect(failText).not.toContain('nobody@x.com');
  });
});
```

```tsx
// admin-ui/src/pages/ResetPasswordPage.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, fireEvent } from '@testing-library/react';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import { ApiError } from '@/api/types';

vi.mock('@/api/passwordReset', () => ({ passwordResetApi: { request: vi.fn(), confirm: vi.fn() } }));

import { passwordResetApi } from '@/api/passwordReset';
import ResetPasswordPage from './ResetPasswordPage';

const withToken = () => renderWithDataRouter(<ResetPasswordPage />, { initialPath: '/reset-password?token=t1' });
function fill(pw: string, pw2: string) {
  fireEvent.change(screen.getByLabelText('새 비밀번호'), { target: { value: pw } });
  fireEvent.change(screen.getByLabelText('새 비밀번호 확인'), { target: { value: pw2 } });
}

beforeEach(() => vi.mocked(passwordResetApi.confirm).mockReset().mockResolvedValue({ reset: true }));

describe('ResetPasswordPage V2', () => {
  it('token 이 없으면 무효 링크 상태: 경고 + 재설정 다시 요청', () => {
    const { container } = renderWithDataRouter(<ResetPasswordPage />, { initialPath: '/reset-password' });
    expect(screen.getByRole('status')).toHaveTextContent('링크를 사용할 수 없습니다');
    expect(screen.getByText('유효하지 않은 링크입니다. 만료됐거나 이미 사용된 링크일 수 있습니다.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '재설정 다시 요청' })).toHaveAttribute('href', '/admin/forgot-password');
    expect(screen.getByRole('link', { name: '← 로그인으로' })).toHaveAttribute('href', '/admin');
    expect(container.querySelector('input')).toBeNull();
  });

  it('12자 미만이면 제출 불가, 12자 이상이면 미터가 충족 표시', () => {
    withToken();
    fill('short', 'short');
    expect(screen.getByText('12자 이상')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '비밀번호 변경' })).toBeDisabled();
    fill('password-12chars', 'password-12chars');
    expect(screen.getByText('12자 이상 충족')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '비밀번호 변경' })).not.toBeDisabled();
  });

  it('불일치면 오류 표시 + 제출 불가', () => {
    withToken();
    fill('password-12chars', 'password-12charX');
    expect(screen.getByText('비밀번호가 일치하지 않습니다.')).toHaveClass('field__error');
    expect(screen.getByLabelText('새 비밀번호 확인')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('button', { name: '비밀번호 변경' })).toBeDisabled();
  });

  it('성공하면 같은 카드에서 변경 완료 상태', async () => {
    const { container } = withToken();
    const card = container.querySelector('.auth-card');
    fill('password-12chars', 'password-12chars');
    fireEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }));
    expect(await screen.findByText('변경 완료')).toBeInTheDocument();
    expect(passwordResetApi.confirm).toHaveBeenCalledWith('t1', 'password-12chars');
    expect(container.querySelector('.auth-card')).toBe(card);
    expect(screen.getByRole('link', { name: '로그인 →' })).toHaveAttribute('href', '/admin');
  });

  it('서버 400 은 serverMessage 를 오류 배너로', async () => {
    vi.mocked(passwordResetApi.confirm).mockRejectedValue(new ApiError(400, 'C400', 'token expired'));
    withToken();
    fill('password-12chars', 'password-12chars');
    fireEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('token expired');
    expect(screen.getByRole('button', { name: '비밀번호 변경' })).toBeInTheDocument();
  });
});
```

```tsx
// admin-ui/src/pages/SignupRequestPage.test.tsx (교체)
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';

vi.mock('@/api/signupRequests', () => ({
  signupRequestsApi: { request: vi.fn().mockResolvedValue({ accepted: true }) },
}));

import { signupRequestsApi } from '@/api/signupRequests';
import SignupRequestPage from './SignupRequestPage';

afterEach(cleanup);

function fill(email: string, pw: string, pw2: string) {
  fireEvent.change(screen.getByLabelText('이메일'), { target: { value: email } });
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: pw } });
  fireEvent.change(screen.getByLabelText('비밀번호 확인'), { target: { value: pw2 } });
}

describe('SignupRequestPage V2', () => {
  it('공통 셸: 워드마크 + 카드 + 카드 밖 로그인 링크', () => {
    const { container } = render(<SignupRequestPage />);
    expect(screen.getByAltText('CROSSCERT')).toHaveClass('auth__logo');
    expect(container.querySelector('.auth .auth-card')).not.toBeNull();
    expect(screen.getByRole('heading', { name: '관리자 계정 가입 요청' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '← 로그인으로' }).closest('.auth-card')).toBeNull();
  });

  it('keeps submit disabled while passwords differ', () => {
    render(<SignupRequestPage />);
    fill('a@x.com', 'password-12chars', 'password-12charX');
    expect(screen.getByText('비밀번호가 일치하지 않습니다.')).toHaveClass('field__error');
    expect(screen.getByRole('button', { name: '가입 요청 보내기' })).toBeDisabled();
  });

  it('keeps submit disabled for a password shorter than 12', () => {
    render(<SignupRequestPage />);
    fill('a@x.com', 'short', 'short');
    expect(screen.getByText('12자 이상')).toHaveClass('field__error');
    expect(screen.getByLabelText('비밀번호')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('button', { name: '가입 요청 보내기' })).toBeDisabled();
  });

  it('keeps submit disabled for an invalid email', () => {
    render(<SignupRequestPage />);
    fill('not-an-email', 'password-12chars', 'password-12chars');
    expect(screen.getByRole('button', { name: '가입 요청 보내기' })).toBeDisabled();
  });

  it('제출 후 같은 카드 안에서 접수 완료 상태(일러스트·제목·돌아가기)', async () => {
    const { container } = render(<SignupRequestPage />);
    const card = container.querySelector('.auth-card');
    fill(' A@X.com ', 'password-12chars', 'password-12chars');
    fireEvent.click(screen.getByRole('button', { name: '가입 요청 보내기' }));
    expect(await screen.findByRole('heading', { name: '요청이 접수되었습니다' })).toBeInTheDocument();
    expect(container.querySelector('.auth-card')).toBe(card);
    expect(card!.querySelector('img[alt=""]')).not.toBeNull();
    expect(screen.getByText('운영자 승인 후 이메일로 안내됩니다. 승인 전에는 로그인할 수 없습니다.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '로그인으로 돌아가기' })).toHaveAttribute('href', '/admin');
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(signupRequestsApi.request).toHaveBeenCalledWith({
      email: 'a@x.com', password: 'password-12chars', reason: undefined,
    });
  });

  it('shows the same accepted notice even when the request fails', async () => {
    (signupRequestsApi.request as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new Error('boom'));
    render(<SignupRequestPage />);
    fill('b@x.com', 'password-12chars', 'password-12chars');
    fireEvent.click(screen.getByRole('button', { name: '가입 요청 보내기' }));
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: '요청이 접수되었습니다' })).toBeInTheDocument(),
    );
  });

  it('사유는 trim 해서 보낸다', async () => {
    render(<SignupRequestPage />);
    fill('c@x.com', 'password-12chars', 'password-12chars');
    fireEvent.change(screen.getByLabelText(/요청 사유/), { target: { value: '  보안팀 · RP 운영  ' } });
    fireEvent.click(screen.getByRole('button', { name: '가입 요청 보내기' }));
    await screen.findByRole('heading', { name: '요청이 접수되었습니다' });
    expect(signupRequestsApi.request).toHaveBeenLastCalledWith({
      email: 'c@x.com', password: 'password-12chars', reason: '보안팀 · RP 운영',
    });
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/ForgotPasswordPage.test.tsx src/pages/ResetPasswordPage.test.tsx src/pages/SignupRequestPage.test.tsx`
Expected: FAIL — `.auth-card`·`auth__logo` 없음, 완료 문구 `메일을 확인하세요`·`링크를 사용할 수 없습니다`·`요청이 접수되었습니다`(heading) 없음, `12자 이상`·`비밀번호가 일치하지 않습니다.` 문구 불일치.

- [ ] **Step 3: ForgotPasswordPage 구현**

```tsx
// admin-ui/src/pages/ForgotPasswordPage.tsx
import { useState } from 'react';
import { passwordResetApi } from '@/api/passwordReset';
import { Alert, Button, Field } from '@/ui';

/**
 * 미인증 public 화면 — 이메일로 재설정 링크 요청.
 * enumeration 방지: 항상 동일한 "메일을 확인하세요" 안내(계정 존재 비노출).
 * 제출 후 같은 카드 안에서 완료 상태로 전환한다.
 */
export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting || !email.trim()) return;
    setSubmitting(true);
    try {
      await passwordResetApi.request(email.trim());
    } catch {
      /* enumeration 방지: 실패해도 동일 안내. */
    } finally {
      setSubmitting(false);
      setSent(true);
    }
  }

  return (
    <main className="auth">
      <div className="auth__col">
        <img className="auth__logo" src="/admin/crosscert-logo-transparent.png" alt="CROSSCERT" />
        <div className="auth-card">
          <div className="col" style={{ gap: 6 }}>
            <h1 className="page__title">비밀번호 재설정</h1>
            <p className="card__sub">
              {sent
                ? '가입한 이메일을 입력하면 재설정 링크를 보내드립니다.'
                : '가입한 이메일을 입력하면 재설정 링크를 보내드립니다. 링크는 일정 시간 후 만료됩니다.'}
            </p>
          </div>
          {sent ? (
            <>
              <Alert tone="ok" title="메일을 확인하세요">해당 이메일로 가입된 계정이 있으면 재설정 링크가 발송됩니다.</Alert>
              <p className="hint" style={{ margin: 0 }}>메일이 오지 않으면 스팸함을 확인하거나 플랫폼 운영자에게 문의하세요.</p>
            </>
          ) : (
            <form className="col" style={{ gap: 20 }} onSubmit={submit}>
              <Field label="이메일" htmlFor="forgot-email">
                <input
                  id="forgot-email"
                  className="input input--lg"
                  type="email"
                  autoFocus
                  autoComplete="username"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="you@crosscert.com"
                />
              </Field>
              <Button type="submit" variant="primary" size="lg" disabled={submitting || !email.trim()}>
                {submitting ? '전송 중…' : '재설정 링크 보내기'}
              </Button>
            </form>
          )}
        </div>
        <a href="/admin" style={{ fontSize: 13, fontWeight: 600 }}>← 로그인으로</a>
      </div>
    </main>
  );
}
```

- [ ] **Step 4: ResetPasswordPage 구현**

```tsx
// admin-ui/src/pages/ResetPasswordPage.tsx
import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { passwordResetApi } from '@/api/passwordReset';
import { ApiError } from '@/api/types';
import { Alert, Button, Field } from '@/ui';

const MIN_LENGTH = 12;

/** 3칸 길이 미터: 6자당 1칸, 12자 이상이면 ok 색. 문구로도 충족 여부를 알린다(색만으로 전달 금지). */
function LengthMeter({ id, length }: { id: string; length: number }) {
  const ok = length >= MIN_LENGTH;
  const filled = Math.min(3, Math.floor(length / 6));
  return (
    <div id={id} className="row" style={{ gap: 6, marginTop: 8, flexWrap: 'nowrap' }}>
      {[0, 1, 2].map((i) => (
        <span key={i} aria-hidden="true" style={{ flex: '1 1 0', height: 4, borderRadius: 2, background: i < filled ? (ok ? 'var(--ok)' : 'var(--warn-dot)') : 'var(--line)' }} />
      ))}
      <span className="hint" style={{ margin: 0, flex: 'none', color: length > 0 && !ok ? 'var(--bad)' : undefined }}>
        {ok ? '12자 이상 충족' : '12자 이상'}
      </span>
    </div>
  );
}

/**
 * 미인증 public 화면 — 이메일 링크의 ?token= 으로 진입해 새 비밀번호 설정.
 * 클라이언트 검증: 12자 이상(V2 안내 문구·가입 요청 정책과 동일), 두 입력 일치.
 * 그 밖의 거부(만료·사용된 토큰)는 백엔드 400 message 를 그대로 표시.
 */
export default function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get('token');
  const [pw, setPw] = useState('');
  const [pw2, setPw2] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const mismatch = pw.length > 0 && pw2.length > 0 && pw !== pw2;
  const tooShort = pw.length > 0 && pw.length < MIN_LENGTH;
  const canSubmit = !!token && pw.length >= MIN_LENGTH && pw === pw2 && !submitting;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      await passwordResetApi.confirm(token!, pw);
      setDone(true);
    } catch (err: unknown) {
      const msg = err instanceof ApiError ? err.serverMessage : '비밀번호 변경에 실패했습니다.';
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="auth">
      <div className="auth__col">
        <img className="auth__logo" src="/admin/crosscert-logo-transparent.png" alt="CROSSCERT" />
        <div className="auth-card">
          {!token ? (
            <>
              <Alert tone="warn" title="링크를 사용할 수 없습니다">유효하지 않은 링크입니다. 만료됐거나 이미 사용된 링크일 수 있습니다.</Alert>
              <a className="btn" href="/admin/forgot-password">재설정 다시 요청</a>
            </>
          ) : done ? (
            <>
              <Alert tone="ok" title="변경 완료">비밀번호가 변경되었습니다. 새 비밀번호로 로그인하세요.</Alert>
              <a className="btn btn--primary" href="/admin">로그인 →</a>
            </>
          ) : (
            <form className="col" style={{ gap: 20 }} onSubmit={submit}>
              <div className="col" style={{ gap: 6 }}>
                <h1 className="page__title">새 비밀번호 설정</h1>
                <p className="card__sub">12자 이상으로 새 비밀번호를 입력하세요.</p>
              </div>
              <div>
                <label className="label" htmlFor="reset-pw">새 비밀번호</label>
                <input
                  id="reset-pw"
                  className="input input--lg"
                  type="password"
                  autoFocus
                  value={pw}
                  onChange={(e) => setPw(e.target.value)}
                  autoComplete="new-password"
                  aria-invalid={tooShort}
                  aria-describedby="reset-pw-meter"
                />
                <LengthMeter id="reset-pw-meter" length={pw.length} />
              </div>
              <Field label="새 비밀번호 확인" htmlFor="reset-pw2" error={mismatch ? '비밀번호가 일치하지 않습니다.' : undefined}>
                <input
                  id="reset-pw2"
                  className="input input--lg"
                  type="password"
                  value={pw2}
                  onChange={(e) => setPw2(e.target.value)}
                  autoComplete="new-password"
                  aria-invalid={mismatch}
                  aria-describedby={mismatch ? 'reset-pw2-error' : undefined}
                />
              </Field>
              {error && <Alert tone="bad">{error}</Alert>}
              <Button type="submit" variant="primary" size="lg" disabled={!canSubmit}>
                {submitting ? '변경 중…' : '비밀번호 변경'}
              </Button>
            </form>
          )}
        </div>
        {!token && <a href="/admin" style={{ fontSize: 13, fontWeight: 600 }}>← 로그인으로</a>}
      </div>
    </main>
  );
}
```

- [ ] **Step 5: SignupRequestPage 구현**

```tsx
// admin-ui/src/pages/SignupRequestPage.tsx
import { useState } from 'react';
import { signupRequestsApi } from '@/api/signupRequests';
import { illustrations } from '@/assets/illustrations';
import { Button, Field } from '@/ui';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * 미인증 public 화면 — 관리자 계정 가입 요청. PLATFORM_OPERATOR 가 승인해야 로그인 가능.
 * enumeration 방지: 서버는 항상 202 를 주고, 실패해도 같은 안내를 보여준다.
 * 비밀번호 정책(12~128자)은 서버와 동일하게 클라이언트에서도 선검증.
 * 제출 후 같은 카드 안에서 접수 완료 상태로 전환한다.
 */
export default function SignupRequestPage() {
  const [email, setEmail] = useState('');
  const [pw, setPw] = useState('');
  const [pw2, setPw2] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  const mismatch = pw.length > 0 && pw2.length > 0 && pw !== pw2;
  const pwTooShort = pw.length > 0 && pw.length < 12;
  const canSubmit =
    EMAIL_RE.test(email.trim()) && pw.length >= 12 && pw.length <= 128 && pw === pw2
    && reason.length <= 500 && !submitting;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    try {
      await signupRequestsApi.request({
        email: email.trim().toLowerCase(),
        password: pw,
        reason: reason.trim() ? reason.trim() : undefined,
      });
    } catch {
      /* enumeration 방지: 실패해도 동일 안내. */
    } finally {
      setSubmitting(false);
      setDone(true);
    }
  }

  return (
    <main className="auth">
      <div className="auth__col">
        <img className="auth__logo" src="/admin/crosscert-logo-transparent.png" alt="CROSSCERT" />
        <div className="auth-card" style={done ? { alignItems: 'center', textAlign: 'center' } : { gap: 18 }}>
          {done ? (
            <>
              <img src={illustrations.signupSent} alt="" style={{ width: 132, height: 132, objectFit: 'contain' }} />
              <h1 className="page__title">요청이 접수되었습니다</h1>
              <p className="card__sub">운영자 승인 후 이메일로 안내됩니다. 승인 전에는 로그인할 수 없습니다.</p>
              <a className="btn" href="/admin">로그인으로 돌아가기</a>
            </>
          ) : (
            <form className="col" style={{ gap: 18 }} onSubmit={submit}>
              <div className="col" style={{ gap: 6 }}>
                <h1 className="page__title">관리자 계정 가입 요청</h1>
                <p className="card__sub">플랫폼 운영자가 승인하면 계정이 활성화됩니다. 역할과 담당 tenant 는 승인 시 지정됩니다.</p>
              </div>
              <Field label="이메일" htmlFor="signup-email">
                <input id="signup-email" className="input" type="email" autoFocus value={email}
                  onChange={(e) => setEmail(e.target.value)} autoComplete="username" placeholder="you@company.com" />
              </Field>
              <Field label="비밀번호" htmlFor="signup-pw" error={pwTooShort ? '12자 이상' : undefined}>
                <input id="signup-pw" className="input" type="password" value={pw}
                  onChange={(e) => setPw(e.target.value)} autoComplete="new-password"
                  aria-invalid={pwTooShort} aria-describedby={pwTooShort ? 'signup-pw-error' : undefined} />
              </Field>
              <Field label="비밀번호 확인" htmlFor="signup-pw2" error={mismatch ? '비밀번호가 일치하지 않습니다.' : undefined}>
                <input id="signup-pw2" className="input" type="password" value={pw2}
                  onChange={(e) => setPw2(e.target.value)} autoComplete="new-password"
                  aria-invalid={mismatch} aria-describedby={mismatch ? 'signup-pw2-error' : undefined} />
              </Field>
              <Field label={<>요청 사유 <span style={{ fontWeight: 400, color: 'var(--mute)' }}>(선택)</span></>} htmlFor="signup-reason">
                <textarea id="signup-reason" className="input" value={reason} maxLength={500}
                  onChange={(e) => setReason(e.target.value)} rows={3}
                  placeholder="소속 · 담당 RP · 필요한 권한" />
              </Field>
              <Button type="submit" variant="primary" size="lg" disabled={!canSubmit}>
                {submitting ? '전송 중…' : '가입 요청 보내기'}
              </Button>
            </form>
          )}
        </div>
        {!done && <a href="/admin" style={{ fontSize: 13, fontWeight: 600 }}>← 로그인으로</a>}
      </div>
    </main>
  );
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `npx vitest run src/pages/ForgotPasswordPage.test.tsx src/pages/ResetPasswordPage.test.tsx src/pages/SignupRequestPage.test.tsx`
Expected: PASS (Forgot 4, Reset 5, Signup 7).

- [ ] **Step 7: 전체 테스트·린트·빌드**

Run: `npm test && npm run lint && npm run build`
Expected: 전체 PASS, lint 오류 0, 빌드 성공. `grep -n "var(--bg)\|var(--info\|var(--danger" src/pages/ForgotPasswordPage.tsx src/pages/ResetPasswordPage.tsx src/pages/SignupRequestPage.tsx` 결과 없음(별칭 토큰 미사용).

- [ ] **Step 8: 수동 확인**

Run: `npm run dev` → `/admin/forgot-password` 에서 이메일 제출 → 같은 카드에 초록 `메일을 확인하세요`. `/admin/reset-password`(token 없음) → 경고 카드 + `재설정 다시 요청`. `/admin/reset-password?token=x` → 11자 입력 시 버튼 비활성·미터 `12자 이상`, 12자 이상 `12자 이상 충족`, 확인 불일치 시 빨간 오류. `/admin/signup` 제출 → 같은 카드에 일러스트 + `요청이 접수되었습니다`. 세 화면 모두 `v2/Auth-*.dc.html` 과 1280px 비교.

- [ ] **Step 9: 커밋**

```bash
git add src/pages/ForgotPasswordPage.tsx src/pages/ForgotPasswordPage.test.tsx src/pages/ResetPasswordPage.tsx src/pages/ResetPasswordPage.test.tsx src/pages/SignupRequestPage.tsx src/pages/SignupRequestPage.test.tsx
git commit -m "feat(admin-ui): V2 비밀번호 재설정·새 비밀번호·가입 요청 — 공통 카드 셸, 같은 카드 완료 상태

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 16: Tenants 목록 + 신규 Tenant 다이얼로그

V2 아트보드: `v2/Tenants.dc.html`, `v2/Dialogs-Tenant.dc.html`(신규 Tenant 생성).

기존 동작 유지: `tenantsApi.list()` 1회 로드 + 실패 토스트, `tenantsApi.create()` 성공 시 토스트 → 다이얼로그 닫기 → `/tenants/{id}?tab=webauthn` 이동, 실패 토스트, 검색(name·slug, 대소문자 무시), CSV(현재 필터 결과 전체, 같은 헤더·열), 행 클릭 → `/tenants/{id}?tab=overview`, `NewTenantDialog` 검증 규칙(slug·rpId 정규식, 닫힐 때 초기화, 제출 시 trim).

바뀌는 것:
- 순환 `필터` 버튼 → `Segmented` 전체/활성/정지(건수 표시, 건수는 검색과 무관한 전체 기준).
- KPI `활성 Tenant` 클릭 = 상태 필터 `ACTIVE` 토글(필터가 `ACTIVE` 면 `selected`). 다시 누르면 `ALL`.
- KPI `유효 API Key` 보조 줄에 `만료 임박 N`(ACTIVE 키 중 30일 이내 만료) — 새 API `apiKeysApi.listAll()`(tenantId 없이 `GET /admin/api/api-keys`, 서버 `@RequestParam(required = false) String tenantId` 확인됨). 조회 실패 시 보조 줄의 `만료 임박` 부분만 숨긴다.
- KPI `등록 Credential` 보조 줄 `7일 +N` 은 `TenantView.credentialsDelta7d?` 가 하나라도 있을 때만(스펙 §6 ⑤).
- 기존 4번째 KPI `24h ceremony 2.4M / 평균 응답 18ms` 는 **하드코딩 가짜 값**이고 대응 API 가 없다(`activity` 의 `events24h` 는 감사 이벤트 수, `p95Ms` 는 서버가 항상 `null`). Global Constraints(자리표시 금지)에 따라 제거하고 KPI 는 3개(`kpis kpis--3`)로 둔다.
- 적용 필터 칩(상태·검색어) + `모두 지우기`, `DataTable` + 클라이언트 `Pagination`(50/100), 빈 상태 3종: 0개(`illustrations.emptyTenants`), 검색 결과 없음(`illustrations.noResults`), 로드 실패(`illustrations.loadError`).
- 생성일 열은 KST `YYYY-MM-DD`.

**Files:**
- Modify: `admin-ui/src/api/apiKeys.ts`(`listAll` 추가), `admin-ui/src/api/apiKeys.test.ts`(테스트 추가)
- Modify: `admin-ui/src/api/types.ts`(`TenantView.credentialsDelta7d?`), `admin-ui/src/api/designTypes.ts`(`Tenant.credentialsDelta7d?`), `admin-ui/src/api/tenants.ts`(어댑터 전달)
- Create: `admin-ui/src/lib/kst.ts`, `admin-ui/src/lib/kst.test.ts`
- Create: `admin-ui/src/lib/apiKeyExpiry.ts`, `admin-ui/src/lib/apiKeyExpiry.test.ts`
- Rewrite: `admin-ui/src/pages/TenantsListPage.tsx`(페이지 + `NewTenantDialog`)
- Create: `admin-ui/src/pages/TenantsListPage.test.tsx`
- Modify: `admin-ui/src/pages/NewTenantDialog.test.tsx`(V2 테스트 추가, 기존 5개 유지)

**Interfaces:**
- Consumes: `@/ui` — `Button`, `Card`, `DataTable`, `type Column`, `Dialog`, `EmptyState`, `Field`, `KpiCard`, `PageHeader`, `Pagination`, `RemovableChip`, `Segmented`, `StatusBadge`, `ToastHost`, `useToast`; `illustrations.emptyTenants | noResults | loadError`; `renderWithDataRouter`; `statusLabel`(`@/i18n/labels`); `downloadCsv`.
- Produces:
  - `apiKeysApi.listAll(): Promise<ApiKeyView[]>` — `GET /admin/api/api-keys`(쿼리 없음). 서버 원형(`ApiKeyView`, `tenantId`·`revokedAt`·`expiresAt` 보존)을 그대로 반환한다(기존 `list(tenantId)` 의 `ApiKey` 어댑터는 `tenantId` 를 버리므로 쓰지 않는다).
  - `TenantView.credentialsDelta7d?: number`, `Tenant.credentialsDelta7d?: number`
  - `@/lib/kst`: `kstDate(iso: string): string`(`YYYY-MM-DD`), `kstTime(iso: string): string`(`HH:mm`), `kstDateTime(iso: string): string`(`YYYY-MM-DD HH:mm:ss`), `utcIso(iso: string): string`(`YYYY-MM-DDTHH:mm:ssZ`). 모두 Asia/Seoul 기준, 파싱 불가 입력은 원문 반환. (Task 17 이 `dayLabel`·`groupByDay` 를 추가한다.)
  - `@/lib/apiKeyExpiry`: `EXPIRY_WARN_DAYS = 30`, `isExpiringSoon(k: { revokedAt?: string | null; expiresAt?: string | null }, now: Date, days?: number): boolean` — 회수 안 됨 + 아직 유효 + `days` 일 이내 만료. 무기한은 false.
  - `NewTenantDialog({ open, onClose, onCreate })` — 시그니처·export 위치(`./TenantsListPage`) 유지.

- [ ] **Step 1: 실패하는 테스트 — 헬퍼·API**

```ts
// admin-ui/src/lib/kst.test.ts
import { describe, it, expect } from 'vitest';
import { kstDate, kstTime, kstDateTime, utcIso } from './kst';

describe('kst formatters', () => {
  it('UTC 15:10 은 KST 다음날 00:10', () => {
    expect(kstDate('2026-10-11T15:10:00Z')).toBe('2026-10-12');
    expect(kstTime('2026-10-11T15:10:00Z')).toBe('00:10');
  });
  it('kstDateTime 은 초까지 24시간제', () => {
    expect(kstDateTime('2026-10-11T05:02:03Z')).toBe('2026-10-11 14:02:03');
  });
  it('+09:00 오프셋 입력도 같은 시각으로 해석', () => {
    expect(kstDateTime('2026-10-11T14:02:03+09:00')).toBe('2026-10-11 14:02:03');
  });
  it('utcIso 는 밀리초 없는 Z 표기', () => {
    expect(utcIso('2026-10-11T14:02:03+09:00')).toBe('2026-10-11T05:02:03Z');
  });
  it('파싱 불가 입력은 원문 반환', () => {
    expect(kstDate('not-a-date')).toBe('not-a-date');
    expect(utcIso('not-a-date')).toBe('not-a-date');
  });
});
```

```ts
// admin-ui/src/lib/apiKeyExpiry.test.ts
import { describe, it, expect } from 'vitest';
import { isExpiringSoon } from './apiKeyExpiry';

const now = new Date('2026-10-11T00:00:00Z');

describe('isExpiringSoon (30일)', () => {
  it('30일 이내 만료 예정 ACTIVE 키는 true', () => {
    expect(isExpiringSoon({ expiresAt: '2026-10-21T00:00:00Z' }, now)).toBe(true);
    expect(isExpiringSoon({ expiresAt: '2026-11-10T00:00:00Z' }, now)).toBe(true); // 정확히 30일
  });
  it('30일 초과는 false', () => {
    expect(isExpiringSoon({ expiresAt: '2026-11-10T00:00:01Z' }, now)).toBe(false);
  });
  it('이미 만료·회수·무기한은 false', () => {
    expect(isExpiringSoon({ expiresAt: '2026-10-10T00:00:00Z' }, now)).toBe(false);
    expect(isExpiringSoon({ expiresAt: '2026-10-21T00:00:00Z', revokedAt: '2026-10-01T00:00:00Z' }, now)).toBe(false);
    expect(isExpiringSoon({}, now)).toBe(false);
    expect(isExpiringSoon({ expiresAt: null }, now)).toBe(false);
  });
});
```

`admin-ui/src/api/apiKeys.test.ts` 의 `describe('apiKeysApi', …)` 블록 끝(마지막 `it` 다음)에 추가:

```ts
  it('listAll GETs /admin/api/api-keys without tenantId and returns raw views', async () => {
    const view = {
      id: 'k1', keyPrefix: 'pk_a', name: 'prod', tenantId: 't1', scopes: ['registration'],
      createdAt: '2026-10-01T00:00:00Z', expiresAt: '2026-10-20T00:00:00Z',
    };
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(envelope([view]));
    const res = await apiKeysApi.listAll();
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('/admin/api/api-keys');
    expect(url).not.toContain('tenantId');
    expect(init.method).toBe('GET');
    expect(res).toEqual([view]);
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/lib/kst.test.ts src/lib/apiKeyExpiry.test.ts src/api/apiKeys.test.ts`
Expected: FAIL — `./kst`, `./apiKeyExpiry` 모듈 없음, `apiKeysApi.listAll is not a function`.

- [ ] **Step 3: 헬퍼·API 구현**

```ts
// admin-ui/src/lib/kst.ts
// 화면 표시용 KST(Asia/Seoul) 포매터. 서버 시각은 ISO(Z 또는 +09:00) — 둘 다 같은 시각으로 해석한다.
const FMT = new Intl.DateTimeFormat('en-US', {
  timeZone: 'Asia/Seoul',
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit',
  hourCycle: 'h23',
});

function parts(iso: string): Record<string, string> | null {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  const o: Record<string, string> = {};
  for (const p of FMT.formatToParts(d)) o[p.type] = p.value;
  return o;
}

/** KST 날짜 `YYYY-MM-DD`. */
export function kstDate(iso: string): string {
  const p = parts(iso);
  return p ? `${p.year}-${p.month}-${p.day}` : iso;
}

/** KST 시각 `HH:mm`. */
export function kstTime(iso: string): string {
  const p = parts(iso);
  return p ? `${p.hour}:${p.minute}` : iso;
}

/** KST `YYYY-MM-DD HH:mm:ss`. */
export function kstDateTime(iso: string): string {
  const p = parts(iso);
  return p ? `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}:${p.second}` : iso;
}

/** UTC ISO `YYYY-MM-DDTHH:mm:ssZ`(밀리초 제거). */
export function utcIso(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toISOString().replace(/\.\d{3}Z$/, 'Z');
}
```

```ts
// admin-ui/src/lib/apiKeyExpiry.ts
export const EXPIRY_WARN_DAYS = 30;
const DAY_MS = 86_400_000;

/** 회수되지 않았고, 아직 유효하며, now 로부터 days 일 이내에 만료되는 키. 무기한(expiresAt 없음)은 false. */
export function isExpiringSoon(
  k: { revokedAt?: string | null; expiresAt?: string | null },
  now: Date,
  days: number = EXPIRY_WARN_DAYS,
): boolean {
  if (k.revokedAt || !k.expiresAt) return false;
  const exp = new Date(k.expiresAt).getTime();
  if (Number.isNaN(exp)) return false;
  const t = now.getTime();
  return exp > t && exp - t <= days * DAY_MS;
}
```

`admin-ui/src/api/apiKeys.ts` 의 `apiKeysApi` 객체에서 `list` 바로 다음에 추가:

```ts
  /** PLATFORM 전용 — 전체 tenant 의 키를 서버 원형 그대로(tenantId 보존). Tenants 목록 KPI "만료 임박" 계산용. */
  listAll: (): Promise<ApiKeyView[]> => api.get<ApiKeyView[]>('/admin/api/api-keys'),
```

`admin-ui/src/api/types.ts` 의 `TenantView` 마지막 필드(`lastEventAt`) 다음에 추가:

```ts
  /** 스펙 §6 ⑤ 지표 보강(서버 후속). 최근 7일 credential 증감. 없으면 KPI 보조 줄을 그리지 않는다. */
  credentialsDelta7d?: number;
```

`admin-ui/src/api/designTypes.ts` 의 `Tenant` 마지막 필드(`webauthnTimeoutMs`) 다음에 추가:

```ts
  credentialsDelta7d?: number;
```

`admin-ui/src/api/tenants.ts` 의 `adaptTenant` 반환 객체 마지막(`webauthnTimeoutMs: s.webauthnTimeoutMs,`) 다음에 추가:

```ts
    credentialsDelta7d: s.credentialsDelta7d,
```

- [ ] **Step 4: 헬퍼·API 테스트 통과 확인**

Run: `npx vitest run src/lib/kst.test.ts src/lib/apiKeyExpiry.test.ts src/api/apiKeys.test.ts`
Expected: PASS (kst 5, apiKeyExpiry 3, apiKeys 4).

- [ ] **Step 5: 실패하는 테스트 — 페이지·다이얼로그**

```tsx
// admin-ui/src/pages/TenantsListPage.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, fireEvent, within } from '@testing-library/react';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import { ToastHost } from '@/ui';
import type { Tenant } from '@/api/designTypes';
import type { ApiKeyView } from '@/api/types';

vi.mock('@/api/tenants', () => ({ tenantsApi: { list: vi.fn(), create: vi.fn() } }));
vi.mock('@/api/apiKeys', () => ({ apiKeysApi: { listAll: vi.fn() } }));

import { tenantsApi } from '@/api/tenants';
import { apiKeysApi } from '@/api/apiKeys';
import TenantsListPage from './TenantsListPage';

function tenant(id: string, over: Partial<Tenant> = {}): Tenant {
  return {
    id, name: `Tenant ${id}`, slug: `slug-${id}`, rpId: `${id}.example.org`, status: 'ACTIVE',
    credentials: 10, apiKeys: 2, lastEventAt: null, createdAt: '2026-10-01T00:00:00Z',
    userVerification: 'REQUIRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000, ...over,
  };
}
const key = (over: Partial<ApiKeyView>): ApiKeyView => ({
  id: 'k', keyPrefix: 'pk_x', name: 'n', tenantId: 'a1', scopes: [], createdAt: '2026-01-01T00:00:00Z', ...over,
});
const DAY = 86_400_000;
const fromNow = (ms: number) => new Date(Date.now() + ms).toISOString();

function renderPage() {
  return renderWithDataRouter(<ToastHost><TenantsListPage /></ToastHost>, { initialPath: '/tenants' });
}

beforeEach(() => {
  vi.mocked(tenantsApi.list).mockReset();
  vi.mocked(apiKeysApi.listAll).mockReset().mockResolvedValue([]);
});

describe('TenantsListPage V2', () => {
  const three = [tenant('a1'), tenant('a2'), tenant('s1', { status: 'SUSPENDED' })];

  it('활성 Tenant KPI 클릭 = 상태 필터 ACTIVE 토글', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    renderPage();
    await screen.findByText('Tenant s1');
    const kpi = screen.getByRole('button', { name: /활성 Tenant/ });
    expect(kpi).toHaveAttribute('aria-pressed', 'false');

    fireEvent.click(kpi);
    expect(kpi).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByText('Tenant s1')).toBeNull();
    expect(screen.getByText('Tenant a1')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '활성 2' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('상태: 활성')).toBeInTheDocument();

    fireEvent.click(kpi);
    expect(kpi).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByText('Tenant s1')).toBeInTheDocument();
  });

  it('세그먼트는 상태별 건수를 보이고 선택한 상태만 남긴다', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    renderPage();
    await screen.findByText('Tenant a1');
    expect(screen.getByRole('button', { name: '전체 3' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: '활성 2' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '정지 1' }));
    expect(screen.queryByText('Tenant a1')).toBeNull();
    expect(screen.getByText('Tenant s1')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /활성 Tenant/ })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByText('1 / 3건')).toBeInTheDocument();
  });

  it('적용 필터 칩 제거와 모두 지우기', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    renderPage();
    await screen.findByText('Tenant a1');
    fireEvent.click(screen.getByRole('button', { name: '정지 1' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'tenant 검색' }), { target: { value: 's1' } });
    expect(screen.getByText('검색: s1')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '상태 필터 제거' }));
    expect(screen.queryByText('상태: 정지')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '모두 지우기' }));
    expect(screen.queryByText('적용된 필터')).toBeNull();
    expect(screen.getByText('3 / 3건')).toBeInTheDocument();
  });

  it('tenant 0개: emptyTenants 빈 상태(검색 결과 없음과 구분)', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText('아직 tenant 가 없습니다')).toBeInTheDocument();
    expect(screen.queryByText('검색 결과가 없습니다')).toBeNull();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('검색 결과 없음: noResults 빈 상태', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    renderPage();
    await screen.findByText('Tenant a1');
    fireEvent.change(screen.getByRole('textbox', { name: 'tenant 검색' }), { target: { value: 'zzz' } });
    expect(screen.getByText('검색 결과가 없습니다')).toBeInTheDocument();
    expect(screen.queryByText('아직 tenant 가 없습니다')).toBeNull();
  });

  it('로드 실패: 빈 목록 안내 대신 불러오기 실패 상태', async () => {
    vi.mocked(tenantsApi.list).mockRejectedValue(new Error('boom'));
    renderPage();
    expect(await screen.findByText('tenant 목록을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByText('아직 tenant 가 없습니다')).toBeNull();
  });

  it('만료 임박 N = ACTIVE 키 중 30일 이내 만료', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    vi.mocked(apiKeysApi.listAll).mockResolvedValue([
      key({ id: 'k1', expiresAt: fromNow(10 * DAY) }),
      key({ id: 'k2', expiresAt: fromNow(40 * DAY) }),
      key({ id: 'k3', expiresAt: fromNow(5 * DAY), revokedAt: fromNow(-DAY) }),
      key({ id: 'k4', expiresAt: fromNow(-DAY) }),
      key({ id: 'k5' }),
    ]);
    renderPage();
    expect(await screen.findByText('만료 임박 1')).toBeInTheDocument();
  });

  it('listAll 실패 시 만료 임박 표시를 숨긴다', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    vi.mocked(apiKeysApi.listAll).mockRejectedValue(new Error('403'));
    renderPage();
    await screen.findByText('Tenant a1');
    expect(screen.queryByText(/만료 임박/)).toBeNull();
  });

  it('credentialsDelta7d 없으면 7일 증감 미표시, 있으면 합계 표시', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    const { unmount } = renderPage();
    await screen.findByText('Tenant a1');
    expect(screen.getByText('모든 tenant 합산')).toBeInTheDocument();
    expect(screen.queryByText(/7일/)).toBeNull();
    unmount();

    vi.mocked(tenantsApi.list).mockResolvedValue([tenant('a1', { credentialsDelta7d: 3 }), tenant('a2', { credentialsDelta7d: 2 })]);
    renderPage();
    expect(await screen.findByText('모든 tenant 합산 · 7일 +5')).toBeInTheDocument();
  });

  it('행 클릭 → tenant 개요로 이동', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    const { router } = renderPage();
    fireEvent.click(await screen.findByText('Tenant a1'));
    expect(router.state.location.pathname).toBe('/tenants/a1');
    expect(router.state.location.search).toBe('?tab=overview');
  });

  it('클라이언트 페이지네이션 50건', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(Array.from({ length: 60 }, (_, i) => tenant(`n${i + 1}`)));
    renderPage();
    await screen.findByText('Tenant n1');
    expect(screen.getByText('1–50 / 60건')).toBeInTheDocument();
    expect(screen.queryByText('Tenant n55')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
    expect(screen.getByText('51–60 / 60건')).toBeInTheDocument();
    expect(screen.getByText('Tenant n55')).toBeInTheDocument();
    const table = screen.getByRole('table');
    expect(within(table).queryByText('Tenant n1')).toBeNull();
  });
});
```

`admin-ui/src/pages/NewTenantDialog.test.tsx` — import 줄을 `import { render, screen, fireEvent, cleanup, within } from '@testing-library/react';` 로 바꾸고, `describe('NewTenantDialog', …)` 블록 끝에 추가(기존 5개 테스트는 그대로):

```tsx
  it('V2: 다음 단계 3개 + RP ID 잠금 안내', () => {
    renderDialog();
    const steps = screen.getByRole('list', { name: '다음 단계' });
    expect(within(steps).getAllByRole('listitem')).toHaveLength(3);
    expect(within(steps).getByText('WebAuthn config')).toBeInTheDocument();
    expect(within(steps).getByText('AAGUID 정책')).toBeInTheDocument();
    expect(within(steps).getByText('API key 발급')).toBeInTheDocument();
    expect(screen.getByText('생성 후에는 변경할 수 없습니다. 기존 credential 이 이 값에 묶입니다.')).toBeInTheDocument();
    expect(screen.getByLabelText('RP ID')).toHaveAttribute('placeholder', '예: passkey.acme.com');
  });

  it('V2: placeholder 도메인 rpId 는 제출 시 오류를 보이고 생성하지 않는다', () => {
    const onCreate = vi.fn();
    const { name, slug, rpId } = renderDialog(onCreate);
    fireEvent.change(name, { target: { value: 'Acme' } });
    fireEvent.change(slug, { target: { value: 'acme' } });
    fireEvent.change(rpId, { target: { value: 'passkey.example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '생성하고 설정으로 이동' }));
    expect(onCreate).not.toHaveBeenCalled();
    expect(screen.getByText(/example.com은 placeholder라 사용할 수 없습니다/)).toBeInTheDocument();
  });
```

- [ ] **Step 6: 실패 확인**

Run: `npx vitest run src/pages/TenantsListPage.test.tsx src/pages/NewTenantDialog.test.tsx`
Expected: FAIL — `활성 Tenant` 가 버튼이 아님, `전체 3` 세그먼트 없음, `다음 단계` list 없음, `RP ID` 라벨 없음 등.

- [ ] **Step 7: TenantsListPage.tsx 재작성**

```tsx
// admin-ui/src/pages/TenantsListPage.tsx
import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { tenantsApi } from '@/api/tenants';
import { apiKeysApi } from '@/api/apiKeys';
import type { Tenant } from '@/api/designTypes';
import type { ApiKeyView } from '@/api/types';
import { downloadCsv } from '@/lib/csvExport';
import { kstDate } from '@/lib/kst';
import { isExpiringSoon } from '@/lib/apiKeyExpiry';
import { statusLabel } from '@/i18n/labels';
import { illustrations } from '@/assets/illustrations';
import {
  Button, Card, DataTable, Dialog, EmptyState, Field, KpiCard, PageHeader, Pagination,
  RemovableChip, Segmented, StatusBadge, useToast, type Column,
} from '@/ui';

// ── local utilities ──────────────────────────────────────────────────────────

function timeAgo(iso: string | null | undefined): string {
  if (!iso) return '—';
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return '방금 전';
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d}일 전`;
  const mo = Math.floor(d / 30);
  return `${mo}개월 전`;
}

function tail(s: string, n: number): string {
  return s.slice(-n);
}

function fmt(n: number): string {
  return n.toLocaleString();
}

function signed(n: number): string {
  return n >= 0 ? `+${fmt(n)}` : `−${fmt(Math.abs(n))}`;
}

type StatusFilter = 'ALL' | 'ACTIVE' | 'SUSPENDED';
const PAGE_SIZES = [50, 100];

// ── TenantsListPage ───────────────────────────────────────────────────────────

export default function TenantsListPage() {
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [keys, setKeys] = useState<ApiKeyView[] | null>(null);
  const [showNew, setShowNew] = useState(false);
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(PAGE_SIZES[0]);
  const navigate = useNavigate();
  const toast = useToast();

  async function reload() {
    setLoading(true);
    try {
      const list = await tenantsApi.list();
      setTenants(list);
      setLoadFailed(false);
    } catch (e: unknown) {
      const err = e as { message?: string };
      setLoadFailed(true);
      toast({ kind: 'err', title: 'tenant 목록 로드 실패', message: err?.message });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void reload(); }, []);

  // 만료 임박 키 계산용 전체 키 목록. 실패해도 목록 화면은 그대로 — KPI 보조 줄만 숨긴다.
  useEffect(() => {
    apiKeysApi.listAll().then(setKeys).catch(() => setKeys(null));
  }, []);

  function onOpen(id: string) {
    navigate(`/tenants/${id}?tab=overview`);
  }

  async function handleCreate(input: { name: string; slug: string; rpId: string }) {
    try {
      const t = await tenantsApi.create(input);
      toast({ kind: 'ok', title: 'Tenant 생성 완료', message: `${t.name} · WebAuthn 설정으로 이동합니다` });
      setShowNew(false);
      // 안내문("다음 단계: WebAuthn config …")과 버튼 라벨("생성하고 설정으로 이동")대로,
      // 생성 직후 새 테넌트의 WebAuthn config 탭으로 이동시켜 온보딩 흐름을 잇는다.
      navigate(`/tenants/${t.id}?tab=webauthn`);
    } catch (e: unknown) {
      const err = e as { message?: string };
      toast({ kind: 'err', title: '생성 실패', message: err?.message || '오류' });
    }
  }

  function applyStatus(s: StatusFilter) { setStatusFilter(s); setPage(1); }
  function applySearch(v: string) { setQ(v); setPage(1); }
  function clearFilters() { setStatusFilter('ALL'); setQ(''); setPage(1); }

  const filtered = useMemo(
    () =>
      tenants.filter((t) => {
        const matchesQ =
          !q ||
          t.name.toLowerCase().includes(q.toLowerCase()) ||
          t.slug.includes(q.toLowerCase());
        const matchesStatus = statusFilter === 'ALL' || t.status === statusFilter;
        return matchesQ && matchesStatus;
      }),
    [q, statusFilter, tenants],
  );

  const counts = useMemo(() => ({
    ALL: tenants.length,
    ACTIVE: tenants.filter((t) => t.status === 'ACTIVE').length,
    SUSPENDED: tenants.filter((t) => t.status === 'SUSPENDED').length,
  }), [tenants]);

  const totalCredentials = useMemo(() => tenants.reduce((a, t) => a + t.credentials, 0), [tenants]);
  const totalKeys = useMemo(() => tenants.reduce((a, t) => a + t.apiKeys, 0), [tenants]);
  const credDelta = useMemo(() => {
    const ds = tenants.map((t) => t.credentialsDelta7d).filter((d): d is number => d !== undefined);
    return ds.length > 0 ? ds.reduce((a, b) => a + b, 0) : undefined;
  }, [tenants]);
  const expiringKeys = useMemo(() => {
    if (keys === null) return null;
    const now = new Date();
    return keys.filter((k) => isExpiringSoon(k, now)).length;
  }, [keys]);

  const pageRows = filtered.slice((page - 1) * pageSize, page * pageSize);
  const hasFilter = statusFilter !== 'ALL' || q !== '';

  const columns: Column<Tenant>[] = [
    {
      key: 'tenant', header: 'Tenant',
      render: (t) => (
        <div className="row" style={{ gap: 10, flexWrap: 'nowrap', minWidth: 0 }}>
          <span className="avatar avatar--sq" style={{ width: 30, height: 30, fontSize: 13 }} aria-hidden="true">{t.name.slice(0, 1).toUpperCase()}</span>
          <div className="col" style={{ minWidth: 0 }}>
            <strong className="truncate" style={{ fontSize: 13, maxWidth: 260 }} title={t.name}>{t.name}</strong>
            <span className="mono muted" style={{ fontSize: 12 }}>{`…${tail(t.id, 8)}`}</span>
          </div>
        </div>
      ),
    },
    { key: 'slug', header: '슬러그', render: (t) => <span className="mono" style={{ fontSize: 12 }}>{t.slug}</span> },
    {
      key: 'rpId', header: 'RP ID',
      render: (t) => <span className="mono truncate" style={{ fontSize: 12, display: 'inline-block', maxWidth: 240, verticalAlign: 'middle' }} title={t.rpId}>{t.rpId}</span>,
    },
    { key: 'credentials', header: 'Credentials', align: 'right', render: (t) => fmt(t.credentials) },
    { key: 'apiKeys', header: 'API 키', align: 'right', render: (t) => fmt(t.apiKeys) },
    { key: 'status', header: '상태', render: (t) => <StatusBadge status={t.status} /> },
    { key: 'lastEvent', header: '마지막 이벤트', render: (t) => <span className="muted">{timeAgo(t.lastEventAt)}</span> },
    { key: 'createdAt', header: '생성일', render: (t) => <span className="muted">{t.createdAt ? kstDate(t.createdAt) : '—'}</span> },
    {
      key: 'open', header: '열기', srOnlyHeader: true, width: 40,
      render: () => <span style={{ color: 'var(--faint)', display: 'inline-flex' }}><Icons.ChevronRight size={16} /></span>,
    },
  ];

  return (
    <div className="page">
      {/* '신규 tenant'(POST /admin/api/tenants)는 PLATFORM_OPERATOR 전용. 이 페이지(/tenants)
          자체가 App.tsx 의 RequirePlatform 으로 감싸져 있어 RP_ADMIN 은 진입 시 자기 테넌트로
          redirect 된다 — 따라서 여기서 별도 버튼 게이팅은 불필요(가드가 단일 진입점). */}
      <PageHeader
        title="Tenants"
        sub="RP 회사별로 격리된 Passkey 환경 — 각 tenant 는 자체 rpId · credential · API 키를 가집니다."
        actions={<Button variant="primary" icon={<Icons.Plus size={14} />} onClick={() => setShowNew(true)}>신규 tenant</Button>}
      />

      <div className="kpis kpis--3">
        <KpiCard
          label="활성 Tenant"
          value={fmt(counts.ACTIVE)}
          hint={`전체 ${fmt(counts.ALL)}건 · 클릭하면 활성만 보기`}
          selected={statusFilter === 'ACTIVE'}
          onClick={() => applyStatus(statusFilter === 'ACTIVE' ? 'ALL' : 'ACTIVE')}
        />
        <KpiCard
          label="등록 Credential"
          value={fmt(totalCredentials)}
          hint={`모든 tenant 합산${credDelta !== undefined ? ` · 7일 ${signed(credDelta)}` : ''}`}
        />
        <KpiCard
          label="유효 API Key"
          value={fmt(totalKeys)}
          hint={<>ACTIVE 만{expiringKeys !== null && <> · <span style={{ color: 'var(--warn)', fontWeight: 600 }}>{`만료 임박 ${expiringKeys}`}</span></>}</>}
        />
      </div>

      {loading ? (
        <Card><div className="empty"><p className="empty__sub">불러오는 중…</p></div></Card>
      ) : loadFailed && tenants.length === 0 ? (
        <Card>
          <EmptyState
            illustration={illustrations.loadError}
            title="tenant 목록을 불러오지 못했습니다"
            description="서버에 연결할 수 없거나 접근 권한이 없습니다."
            action={<Button icon={<Icons.Refresh size={14} />} onClick={() => void reload()}>다시 시도</Button>}
          />
        </Card>
      ) : tenants.length === 0 ? (
        <Card>
          <EmptyState
            illustration={illustrations.emptyTenants}
            title="아직 tenant 가 없습니다"
            description="RP 회사마다 tenant 를 만들면 rpId · credential · API 키가 격리됩니다. 생성 후 WebAuthn 설정 → AAGUID 정책 → API key 발급 순으로 진행하세요."
            action={<Button variant="primary" icon={<Icons.Plus size={14} />} onClick={() => setShowNew(true)}>신규 tenant</Button>}
          />
        </Card>
      ) : (
        <Card>
          <div className="card__head">
            <div className="search" style={{ flex: '0 1 280px' }}>
              <Icons.Search size={15} />
              <input className="input" aria-label="tenant 검색" placeholder="name · slug 검색" value={q} onChange={(e) => applySearch(e.target.value)} />
            </div>
            <Segmented<StatusFilter>
              ariaLabel="상태"
              value={statusFilter}
              onChange={applyStatus}
              options={[
                { value: 'ALL', label: `전체 ${counts.ALL}` },
                { value: 'ACTIVE', label: `활성 ${counts.ACTIVE}` },
                { value: 'SUSPENDED', label: `정지 ${counts.SUSPENDED}` },
              ]}
            />
            <div className="spacer" />
            <span className="muted" style={{ fontSize: 12 }}>{`${filtered.length} / ${tenants.length}건`}</span>
            <Button
              icon={<Icons.Download size={14} />}
              onClick={() =>
                downloadCsv(
                  `tenants-${new Date().toISOString().slice(0, 10)}.csv`,
                  ['name', 'slug', 'rpId', 'status', 'credentials', 'apiKeys', 'createdAt'],
                  filtered.map((t) => [t.name, t.slug, t.rpId, t.status, t.credentials, t.apiKeys, t.createdAt]),
                )
              }
            >
              CSV
            </Button>
          </div>

          {hasFilter && (
            <div className="applied">
              <span className="muted" style={{ fontSize: 12 }}>적용된 필터</span>
              {statusFilter !== 'ALL' && (
                <RemovableChip removeLabel="상태 필터 제거" onRemove={() => applyStatus('ALL')}>{`상태: ${statusLabel(statusFilter)}`}</RemovableChip>
              )}
              {q !== '' && (
                <RemovableChip removeLabel="검색어 제거" onRemove={() => applySearch('')}>{`검색: ${q}`}</RemovableChip>
              )}
              <Button variant="ghost" size="sm" onClick={clearFilters}>모두 지우기</Button>
            </div>
          )}

          <DataTable
            columns={columns}
            rows={pageRows}
            rowKey={(t) => t.id}
            onRowClick={(t) => onOpen(t.id)}
            empty={
              <EmptyState
                compact
                illustration={illustrations.noResults}
                title="검색 결과가 없습니다"
                description="검색어나 상태 필터를 바꿔 보세요."
                action={<Button size="sm" onClick={clearFilters}>필터 모두 지우기</Button>}
              />
            }
          />

          <Pagination
            page={page}
            pageSize={pageSize}
            total={filtered.length}
            onPage={setPage}
            pageSizeOptions={PAGE_SIZES}
            onPageSize={(n) => { setPageSize(n); setPage(1); }}
          />
        </Card>
      )}

      {showNew && (
        <NewTenantDialog
          open={showNew}
          onClose={() => setShowNew(false)}
          onCreate={handleCreate}
        />
      )}
    </div>
  );
}

// ── NewTenantDialog ───────────────────────────────────────────────────────────

const NEXT_STEPS = [
  { title: 'WebAuthn config', desc: 'origin · UV · attestation' },
  { title: 'AAGUID 정책', desc: '허용할 인증기' },
  { title: 'API key 발급', desc: 'RP 서버 연동' },
];

export function NewTenantDialog({ open, onClose, onCreate }: {
  open: boolean;
  onClose: () => void;
  onCreate: (i: { name: string; slug: string; rpId: string }) => void;
}) {
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [rpId, setRpId] = useState('');
  const [touched, setTouched] = useState(false);
  const slugRe = /^[a-z0-9][a-z0-9-]{1,62}$/;
  const slugOk = slugRe.test(slug);
  // rpId 는 WebAuthn RP ID — registrable domain(hostname). 스킴/포트/경로 없이
  // 점이 포함된 호스트명이어야 한다(예: passkey.acme.com). placeholder 그대로 저장 방지.
  const rpIdRe = /^(?!.*\.example\.com$)([a-z0-9-]+\.)+[a-z]{2,}$/;
  const rpIdOk = rpIdRe.test(rpId.trim());

  // Reset form when dialog is closed (open: true → false) so stale input
  // doesn't bleed into the next open. This also runs when a create fails and
  // the parent leaves the dialog open — state is preserved until actual close.
  useEffect(() => {
    if (!open) { setName(''); setSlug(''); setRpId(''); setTouched(false); }
  }, [open]);

  function submit() {
    setTouched(true);
    if (!name || !slugOk || !rpIdOk) return;
    onCreate({ name, slug, rpId: rpId.trim() });
    // Do NOT clear here — let the parent's handleCreate success path call
    // setShowNew(false), which triggers the useEffect above to reset state.
  }

  const slugError = touched && slug && !slugOk
    ? '영문 소문자·숫자로 시작하고, 영문 소문자·숫자·하이픈(-)만 사용해 2~63자로 입력하세요.'
    : undefined;
  const rpIdError = touched && rpId && !rpIdOk
    ? '스킴·포트·경로 없이 실제 도메인 hostname을 입력하세요(예: passkey.acme.com). example.com은 placeholder라 사용할 수 없습니다.'
    : undefined;

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="신규 Tenant 생성"
      sub="RP 회사 하나에 대한 격리된 Passkey 환경을 만듭니다."
      footer={<>
        <Button onClick={onClose}>취소</Button>
        <Button variant="primary" onClick={submit}>생성하고 설정으로 이동</Button>
      </>}
    >
      <Field label="표시 이름" htmlFor="nt-name">
        <input id="nt-name" className="input" placeholder="예: Acme Corp" value={name} onChange={(e) => setName(e.target.value)} />
      </Field>
      <Field label="Slug (영문 식별자)" htmlFor="nt-slug" help="소문자·숫자·하이픈. URL 과 로그에서 tenant 를 식별합니다." error={slugError}>
        <input id="nt-slug" className="input mono" placeholder="acme-corp" value={slug}
               aria-invalid={!!slugError} aria-describedby={slugError ? 'nt-slug-error' : undefined}
               onChange={(e) => setSlug(e.target.value.toLowerCase())} />
      </Field>
      <Field
        label="RP ID"
        htmlFor="nt-rpid"
        error={rpIdError}
        help={
          <span className="row" style={{ gap: 5, color: 'var(--warn)', flexWrap: 'nowrap' }}>
            <Icons.Lock size={13} />
            <span>생성 후에는 변경할 수 없습니다. 기존 credential 이 이 값에 묶입니다.</span>
          </span>
        }
      >
        <input id="nt-rpid" className="input mono" placeholder="예: passkey.acme.com" value={rpId}
               aria-invalid={!!rpIdError} aria-describedby={rpIdError ? 'nt-rpid-error' : undefined}
               onChange={(e) => setRpId(e.target.value.toLowerCase().trim())} />
      </Field>

      <div className="stack-2" style={{ gap: 10, padding: '14px 16px', borderRadius: 'var(--r)', background: 'var(--brand-50)', border: '1px solid var(--brand-100)' }}>
        <span className="t-over" id="nt-next-steps" style={{ color: 'var(--brand-700)' }}>다음 단계</span>
        <ol aria-labelledby="nt-next-steps" className="stack-2" style={{ listStyle: 'none', margin: 0, padding: 0, gap: 10 }}>
          {NEXT_STEPS.map((s, i) => (
            <li key={s.title} className="row" style={{ gap: 10, flexWrap: 'nowrap', fontSize: 13 }}>
              <span aria-hidden="true" style={{ width: 22, height: 22, borderRadius: '50%', border: '1.5px solid var(--brand-700)', color: 'var(--brand-700)', fontSize: 11, fontWeight: 700, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flex: 'none' }}>{i + 1}</span>
              <strong>{s.title}</strong>
              <span className="muted">{s.desc}</span>
            </li>
          ))}
        </ol>
      </div>
    </Dialog>
  );
}
```

- [ ] **Step 8: 테스트 통과 확인**

Run: `npx vitest run src/pages/TenantsListPage.test.tsx src/pages/NewTenantDialog.test.tsx src/lib src/api/apiKeys.test.ts`
Expected: PASS (TenantsListPage 11, NewTenantDialog 7, lib·api 전부).

- [ ] **Step 9: 전체 검증**

Run: `npm test && npm run lint && npm run build`
Expected: 테스트 전부 PASS, lint error 0(warn 은 허용 — eslint 설정이 도입 단계 warn), 빌드 성공.

- [ ] **Step 10: 커밋**

```bash
git add src/api/apiKeys.ts src/api/apiKeys.test.ts src/api/types.ts src/api/designTypes.ts src/api/tenants.ts \
  src/lib/kst.ts src/lib/kst.test.ts src/lib/apiKeyExpiry.ts src/lib/apiKeyExpiry.test.ts \
  src/pages/TenantsListPage.tsx src/pages/TenantsListPage.test.tsx src/pages/NewTenantDialog.test.tsx
git commit -m "feat(admin-ui): Tenants 목록 V2 — KPI 필터·상태 세그먼트·적용 필터 칩·페이지네이션·빈 상태, 만료 임박 키(listAll)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 17: Activity

V2 아트보드: `v2/Activity.dc.html`.

기존 동작 유지(이름 그대로): `adaptServerView`, `eventTone`(+`FAILURE_TYPES`·`AUTH_TYPES`), `parsePayload`, `fmt`, 상태 `events`/`kpi`/`topTenants`/`serverError`/`detail`/`detailLoading`/`categoryFilter`/`visibleCount`/`tenants`/`actionQuery`/`selectedTenant`(초기값 `?tenantId=`), refs `cancelledRef`/`hasServerDataRef`, tenant 목록 1회 로드, **5초 폴링 effect**(`[categoryFilter, selectedTenant]`, 쿼리 변경 시 `visibleCount` 24로 리셋, 첫 실패만 오류 화면·이후 실패는 기존 데이터 유지), `filtered`(액션 코드·한글 라벨 검색), `openDetail`, `handleOpenTenant`, `handleRefresh`, CSV 내보내기(같은 파일명·헤더·열), `이전 24시간 더 보기`(`before`+`beforeId` 튜플 커서, F07).

바뀌는 것:
- KPI 초기값을 `activityFixture.kpi` 대신 0/`null` 로 둔다. 기존 코드는 서버 `p95Ms` 가 항상 `null` 이라 픽스처의 가짜 p95 를 계속 보여줬다(자리표시 금지 위반). `p95Ms` 가 `null` 이면 `—` 와 보조 줄 없음. `topTenants` 초기값도 `[]`. (`src/fixtures/activity.ts` 는 다른 사용처가 없지만 이 태스크에서 삭제하지 않는다 — 정리 태스크 몫.)
- 카테고리 `Segmented`(전체/운영/보안), 테넌트 `select`, 액션 검색, `Switch '보안 실패만'`.
  - **스위치 ↔ 세그먼트 결합 규칙:** 둘은 같은 상태 `categoryFilter` 의 두 가지 보기다. 스위치 `checked = categoryFilter === 'security'`. 스위치 켜기 → `categoryFilter = 'security'`(세그먼트도 `보안` 으로 이동), 끄기 → `'all'`. 세그먼트에서 `운영`·`전체` 를 고르면 스위치는 꺼진다. 서버 `SECURITY_ACTIONS` 가 실패 액션(`ADMIN_LOGIN_FAILED`)뿐이라 `category=security` 가 곧 "보안 실패" 이며, 새 쿼리 파라미터는 없다.
- 적용 필터 칩: 카테고리(전체가 아닐 때)·테넌트·액션 + `모두 지우기`. 기본값이면 줄을 그리지 않는다.
- 피드: 날짜 그룹(`오늘`/`어제`/`YYYY-MM-DD`, KST) — 순수 헬퍼 `dayLabel`·`groupByDay`. 행 = KST `HH:mm` · 유형 색 점 · 액션 코드 태그 · tenant 링크 · 문장 · `상세 열기` 버튼. 선택 행 강조(`aria-current="true"`, 좌측 3px 바). 행 전체 클릭은 두지 않는다(jsx-a11y `click-events-have-key-events` 가 error, tenant 링크와 중첩 상호작용 방지).
- 범례 4종(보안 실패 `--bad` · 운영 변경 `--warn-dot` · 인증 `--brand-700` · 시스템 `--faint`).
- 우측 상세 패널: `언제`(KST + UTC ISO) / `누가`(+ `actorIp` 있을 때만 `IP …`) / `테넌트 · 대상` / `어떻게 바뀜`(행 있을 때만) / `원본 JSON` + `CopyBtn`.
  - diff 우선순위: `detail.diff` 가 정의되어 있으면 그것, 아니면 payload 의 `before`/`after` 객체(현재 서버에서 이를 쓰는 액션은 `TENANT_UPDATE`: `{ before: TenantSnapshot, after: TenantSnapshot, changedFields: string[] }`, `TenantAdminService.update`) — `changedFields` 순서로, 없으면 값이 다른 키만. 배열 필드(`allowedOrigins`·`acceptedFormats`)는 `+ 추가`/`− 제거` 태그.
- 헤더 `Live` 표시, `활발한 Tenant` 카드(막대 + 건수, 클릭 = 테넌트 필터).
- 오류 상태: `EmptyState(illustrations.loadError)` + 재시도(`handleRefresh`), 결과 없음: `EmptyState(illustrations.noResults, compact)`.

**Files:**
- Modify: `admin-ui/src/api/types.ts`(`ActivityEvent`·`ActivityDetailView` 에 optional `actorIp?`, `diff?`)
- Modify: `admin-ui/src/lib/kst.ts`(`dayLabel`, `groupByDay` 추가), `admin-ui/src/lib/kst.test.ts`(테스트 추가)
- Create: `admin-ui/src/pages/activityDiff.ts`, `admin-ui/src/pages/activityDiff.test.ts`
- Rewrite: `admin-ui/src/pages/ActivityPage.tsx`
- Create: `admin-ui/src/pages/ActivityPage.test.tsx`

**Interfaces:**
- Consumes: Task 16 `@/lib/kst`(`kstTime`, `kstDateTime`, `utcIso`); `@/ui` — `Button`, `IconButton`, `KpiCard`, `PageHeader`, `Segmented`, `Switch`, `RemovableChip`, `CopyBtn`, `EmptyState`, `ToastHost`, `useToast`; `illustrations.loadError | noResults`; `activityApi.fetch/fetchDetail`, `tenantsApi.list`, `adaptFeedItems`, `actionLabel`, `eventSentence`.
- Produces:
  - `ActivityEvent.actorIp?: string`, `ActivityEvent.diff?: { field: string; from: unknown; to: unknown }[]` — 스펙 §6 ③ 이름 그대로. 상세 패널은 `GET /admin/api/activity/{id}` 응답을 쓰므로 `ActivityDetailView` 에도 같은 두 필드를 optional 로 선언한다.
  - `dayLabel(iso: string, now: Date): string` — KST 날짜가 now 와 같으면 `'오늘'`, 하루 전이면 `'어제'`, 그 외 `'YYYY-MM-DD'`.
  - `groupByDay<T>(items: T[], isoOf: (item: T) => string, now: Date): { label: string; items: T[] }[]` — 입력 순서를 유지하며 연속 구간으로 묶는다.
  - `type DiffRow = { field: string; from: unknown; to: unknown }`; `buildDiffRows(detail: Pick<ActivityDetailView, 'diff' | 'payload'>): DiffRow[]`; `arrayChange(from: unknown, to: unknown): { added: string[]; removed: string[] } | null`.

- [ ] **Step 1: 실패하는 테스트 — 헬퍼**

`admin-ui/src/lib/kst.test.ts` — import 줄을 `import { kstDate, kstTime, kstDateTime, utcIso, dayLabel, groupByDay } from './kst';` 로 바꾸고 파일 끝에 추가:

```ts
describe('dayLabel (KST 자정 경계)', () => {
  // now = KST 2026-10-12 00:30
  const now = new Date('2026-10-11T15:30:00Z');
  it('같은 KST 날짜는 오늘', () => {
    expect(dayLabel('2026-10-11T15:10:00Z', now)).toBe('오늘'); // KST 10-12 00:10
  });
  it('UTC 로는 같은 날이어도 KST 전날이면 어제', () => {
    expect(dayLabel('2026-10-11T14:50:00Z', now)).toBe('어제'); // KST 10-11 23:50
  });
  it('그 이전은 KST 날짜', () => {
    expect(dayLabel('2026-10-10T14:50:00Z', now)).toBe('2026-10-10'); // KST 10-10 23:50
  });
});

describe('groupByDay', () => {
  it('최신순 입력을 연속 구간으로 묶고 순서를 유지한다', () => {
    const now = new Date('2026-10-11T03:00:00Z'); // KST 10-11 12:00
    const items = [
      { id: 'a', ts: '2026-10-11T02:00:00Z' },
      { id: 'b', ts: '2026-10-11T00:00:00Z' },
      { id: 'c', ts: '2026-10-10T09:00:00Z' },
      { id: 'd', ts: '2026-10-08T01:00:00Z' },
    ];
    const g = groupByDay(items, (i) => i.ts, now);
    expect(g.map((x) => x.label)).toEqual(['오늘', '어제', '2026-10-08']);
    expect(g[0].items.map((i) => i.id)).toEqual(['a', 'b']);
  });
  it('빈 입력은 빈 배열', () => {
    expect(groupByDay([], (i: { ts: string }) => i.ts, new Date())).toEqual([]);
  });
});
```

```ts
// admin-ui/src/pages/activityDiff.test.ts
import { describe, it, expect } from 'vitest';
import { buildDiffRows, arrayChange } from './activityDiff';

const tenantUpdate = JSON.stringify({
  before: { displayName: 'Acme', webauthnTimeoutMs: 60000, allowedOrigins: ['https://acme.com'] },
  after: { displayName: 'Acme', webauthnTimeoutMs: 120000, allowedOrigins: ['https://acme.com', 'https://b.acme.com'] },
  changedFields: ['allowedOrigins', 'webauthnTimeoutMs'],
});

describe('buildDiffRows', () => {
  it('diff 필드가 있으면 그대로 쓴다(서버 ③)', () => {
    const diff = [{ field: 'rpName', from: 'A', to: 'B' }];
    expect(buildDiffRows({ diff, payload: tenantUpdate })).toEqual(diff);
  });
  it('diff 가 없으면 TENANT_UPDATE before/after 를 changedFields 순서로', () => {
    expect(buildDiffRows({ payload: tenantUpdate })).toEqual([
      { field: 'allowedOrigins', from: ['https://acme.com'], to: ['https://acme.com', 'https://b.acme.com'] },
      { field: 'webauthnTimeoutMs', from: 60000, to: 120000 },
    ]);
  });
  it('changedFields 가 없으면 값이 다른 키만', () => {
    const payload = JSON.stringify({ before: { a: 1, b: 2 }, after: { a: 1, b: 3 } });
    expect(buildDiffRows({ payload })).toEqual([{ field: 'b', from: 2, to: 3 }]);
  });
  it('before/after 가 없거나 JSON 이 아니면 빈 배열', () => {
    expect(buildDiffRows({ payload: JSON.stringify({ ip: '1.2.3.4' }) })).toEqual([]);
    expect(buildDiffRows({ payload: 'not json' })).toEqual([]);
  });
});

describe('arrayChange', () => {
  it('추가/제거 원소', () => {
    expect(arrayChange(['a', 'b'], ['b', 'c'])).toEqual({ added: ['c'], removed: ['a'] });
  });
  it('배열이 아니면 null', () => {
    expect(arrayChange(1, 2)).toBeNull();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/lib/kst.test.ts src/pages/activityDiff.test.ts`
Expected: FAIL — `dayLabel`/`groupByDay` export 없음, `./activityDiff` 모듈 없음.

- [ ] **Step 3: 헬퍼·타입 구현**

`admin-ui/src/lib/kst.ts` 끝에 추가:

```ts
const DAY_MS = 86_400_000;

/** 피드 날짜 그룹 라벨. KST 기준 오늘/어제, 그 외 YYYY-MM-DD. KST 는 DST 가 없어 24h 빼기로 전날을 구한다. */
export function dayLabel(iso: string, now: Date): string {
  const key = kstDate(iso);
  if (key === kstDate(now.toISOString())) return '오늘';
  if (key === kstDate(new Date(now.getTime() - DAY_MS).toISOString())) return '어제';
  return key;
}

/** 입력 순서를 유지하며 같은 dayLabel 의 연속 구간을 하나의 그룹으로 묶는다(피드는 최신순). */
export function groupByDay<T>(items: T[], isoOf: (item: T) => string, now: Date): { label: string; items: T[] }[] {
  const groups: { label: string; items: T[] }[] = [];
  for (const it of items) {
    const label = dayLabel(isoOf(it), now);
    const last = groups[groups.length - 1];
    if (last && last.label === label) last.items.push(it);
    else groups.push({ label, items: [it] });
  }
  return groups;
}
```

```ts
// admin-ui/src/pages/activityDiff.ts
import type { ActivityDetailView } from '@/api/types';

export type DiffRow = { field: string; from: unknown; to: unknown };

function asObject(v: unknown): Record<string, unknown> | null {
  return v !== null && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
}

/**
 * 상세 패널 "어떻게 바뀜" 행.
 * 1) 서버 ③ 의 detail.diff 가 정의되어 있으면 그대로.
 * 2) 아니면 payload 의 before/after 객체(현재 TENANT_UPDATE: { before, after, changedFields }).
 *    changedFields 가 문자열 배열이면 그 순서, 아니면 JSON 값이 다른 키만.
 */
export function buildDiffRows(detail: Pick<ActivityDetailView, 'diff' | 'payload'>): DiffRow[] {
  if (detail.diff !== undefined) return detail.diff;
  let parsed: unknown;
  try { parsed = JSON.parse(detail.payload); } catch { return []; }
  const p = asObject(parsed);
  if (!p) return [];
  const before = asObject(p.before);
  const after = asObject(p.after);
  if (!before || !after) return [];
  const fields = Array.isArray(p.changedFields) && p.changedFields.every((f) => typeof f === 'string')
    ? (p.changedFields as string[])
    : Object.keys({ ...before, ...after }).filter((k) => JSON.stringify(before[k]) !== JSON.stringify(after[k]));
  return fields.map((field) => ({ field, from: before[field], to: after[field] }));
}

/** 두 값이 모두 배열이면 추가/제거 원소(문자열화), 아니면 null. */
export function arrayChange(from: unknown, to: unknown): { added: string[]; removed: string[] } | null {
  if (!Array.isArray(from) || !Array.isArray(to)) return null;
  const f = from.map(String);
  const t = to.map(String);
  return { added: t.filter((x) => !f.includes(x)), removed: f.filter((x) => !t.includes(x)) };
}
```

`admin-ui/src/api/types.ts` — `ActivityEvent` 의 `category` 다음과 `ActivityDetailView` 의 `payload` 다음에 각각 추가:

```ts
  /** 스펙 §6 ③ 로그 보강(서버 후속). 없으면 상세 패널 IP 줄을 그리지 않는다. */
  actorIp?: string;
  /** 스펙 §6 ③. 없으면 payload 의 before/after(TENANT_UPDATE)로 diff 를 만든다. */
  diff?: { field: string; from: unknown; to: unknown }[];
```

- [ ] **Step 4: 헬퍼 테스트 통과 확인**

Run: `npx vitest run src/lib/kst.test.ts src/pages/activityDiff.test.ts`
Expected: PASS.

- [ ] **Step 5: 실패하는 테스트 — 페이지**

```tsx
// admin-ui/src/pages/ActivityPage.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, fireEvent, within, waitFor } from '@testing-library/react';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import { ToastHost } from '@/ui';
import type { ActivityEvent, ActivityView, ActivityDetailView } from '@/api/types';

vi.mock('@/api/activity', () => ({ activityApi: { fetch: vi.fn(), fetchDetail: vi.fn() } }));
vi.mock('@/api/tenants', () => ({ tenantsApi: { list: vi.fn() } }));

import { activityApi } from '@/api/activity';
import { tenantsApi } from '@/api/tenants';
import ActivityPage from './ActivityPage';

const NOW = new Date('2026-10-11T03:00:00Z'); // KST 2026-10-11 12:00

function ev(id: string, createdAt: string, over: Partial<ActivityEvent> = {}): ActivityEvent {
  return {
    id, action: 'TENANT_UPDATE', actorEmail: 'op@x.com', targetType: 'TENANT', targetId: 'tid-0000-1111-2222',
    tenantId: 't1', tenantSlug: 'acme', createdAt, category: 'ops', ...over,
  };
}
const view = (feed: ActivityEvent[]): ActivityView => ({
  kpi: { events24h: 3, ops24h: 2, security24h: 1, p95Ms: null },
  top5: [{ tenantId: 't1', slug: 'acme', count: 3 }],
  feed,
});
const FEED = [
  ev('e1', '2026-10-11T02:48:00Z'),                                           // 오늘 11:48
  ev('e2', '2026-10-10T09:00:00Z', { action: 'ADMIN_LOGIN_FAILED', category: 'security', tenantId: null, tenantSlug: null }), // 어제 18:00
  ev('e3', '2026-10-08T01:00:00Z', { action: 'API_KEY_ISSUE' }),             // 2026-10-08 10:00
];
const PAYLOAD = JSON.stringify({
  before: { webauthnTimeoutMs: 60000, allowedOrigins: ['https://acme.com'] },
  after: { webauthnTimeoutMs: 120000, allowedOrigins: ['https://acme.com', 'https://b.acme.com'] },
  changedFields: ['allowedOrigins', 'webauthnTimeoutMs'],
});
function detail(over: Partial<ActivityDetailView> = {}): ActivityDetailView {
  return {
    id: 'e1', action: 'TENANT_UPDATE', actorId: '1', actorEmail: 'op@x.com', targetType: 'TENANT',
    targetId: 'tid-0000-1111-2222', tenantId: 't1', tenantSlug: 'acme', createdAt: '2026-10-11T02:48:00Z',
    category: 'ops', payload: PAYLOAD, ...over,
  };
}

function renderPage() {
  return renderWithDataRouter(<ToastHost><ActivityPage /></ToastHost>, { initialPath: '/activity' });
}

async function openFirstDetail() {
  const today = await screen.findByRole('group', { name: '오늘' });
  fireEvent.click(within(today).getByRole('button', { name: '상세 열기' }));
  return screen.findByRole('region', { name: '이벤트 상세' });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] }); // setInterval(5초 폴링)은 실제 타이머 유지
  vi.setSystemTime(NOW);
  vi.mocked(tenantsApi.list).mockResolvedValue([]);
  vi.mocked(activityApi.fetch).mockResolvedValue(view(FEED));
  vi.mocked(activityApi.fetchDetail).mockResolvedValue(detail());
});
afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe('ActivityPage V2', () => {
  it('피드를 KST 날짜(오늘/어제/YYYY-MM-DD)로 묶는다', async () => {
    renderPage();
    const today = await screen.findByRole('group', { name: '오늘' });
    expect(within(today).getByText('11:48')).toBeInTheDocument();
    expect(within(screen.getByRole('group', { name: '어제' })).getByText('18:00')).toBeInTheDocument();
    expect(within(screen.getByRole('group', { name: '2026-10-08' })).getByText('10:00')).toBeInTheDocument();
  });

  it('상세 패널 5개 섹션과 TENANT_UPDATE before/after diff, 선택 행 강조', async () => {
    renderPage();
    const panel = await openFirstDetail();
    expect(activityApi.fetchDetail).toHaveBeenCalledWith('e1');
    expect(await within(panel).findByRole('group', { name: '언제' })).toHaveTextContent('2026-10-11 11:48:00');
    expect(within(panel).getByText('2026-10-11T02:48:00Z UTC')).toBeInTheDocument();
    expect(within(panel).getByRole('group', { name: '누가' })).toHaveTextContent('op@x.com');
    expect(within(panel).getByRole('group', { name: '테넌트 · 대상' })).toHaveTextContent('TENANT');
    const diff = within(panel).getByRole('group', { name: '어떻게 바뀜' });
    expect(within(diff).getByText('60000')).toBeInTheDocument();
    expect(within(diff).getByText('120000')).toBeInTheDocument();
    expect(within(diff).getByText('+ https://b.acme.com')).toBeInTheDocument();
    const raw = within(panel).getByRole('group', { name: '원본 JSON' });
    expect(within(raw).getByRole('button', { name: '복사' })).toBeInTheDocument();
    expect(screen.getByText('11:48').closest('[aria-current]')).toHaveAttribute('aria-current', 'true');
  });

  it('actorIp 가 없으면 IP 줄을 그리지 않는다', async () => {
    renderPage();
    const panel = await openFirstDetail();
    await within(panel).findByRole('group', { name: '누가' });
    expect(within(panel).queryByText(/^IP /)).toBeNull();
  });

  it('actorIp 가 있으면 IP 줄을 보인다', async () => {
    vi.mocked(activityApi.fetchDetail).mockResolvedValue(detail({ actorIp: '203.0.113.9' }));
    renderPage();
    const panel = await openFirstDetail();
    expect(await within(panel).findByText('IP 203.0.113.9')).toBeInTheDocument();
  });

  it('diff 필드가 있으면 payload before/after 대신 그것을 쓴다', async () => {
    vi.mocked(activityApi.fetchDetail).mockResolvedValue(detail({ diff: [{ field: 'rpName', from: 'Old', to: 'New' }] }));
    renderPage();
    const panel = await openFirstDetail();
    const diff = await within(panel).findByRole('group', { name: '어떻게 바뀜' });
    expect(within(diff).getByText('rpName')).toBeInTheDocument();
    expect(within(diff).queryByText('webauthnTimeoutMs')).toBeNull();
  });

  it('보안 실패만 스위치 = category=security, 카테고리 세그먼트와 같은 상태', async () => {
    renderPage();
    await screen.findByRole('group', { name: '오늘' });
    fireEvent.click(screen.getByRole('switch', { name: '보안 실패만' }));
    await waitFor(() =>
      expect(activityApi.fetch).toHaveBeenLastCalledWith(null, 'security', undefined, undefined),
    );
    expect(screen.getByRole('button', { name: '보안' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('카테고리: 보안')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '운영' }));
    expect(screen.getByRole('switch', { name: '보안 실패만' })).not.toBeChecked();
  });

  it('첫 로드 실패 시 불러오기 실패 상태', async () => {
    vi.mocked(activityApi.fetch).mockRejectedValue(new Error('down'));
    renderPage();
    expect(await screen.findByText('Activity 데이터를 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '재시도' })).toBeInTheDocument();
  });
});
```

- [ ] **Step 6: 실패 확인**

Run: `npx vitest run src/pages/ActivityPage.test.tsx`
Expected: FAIL — `오늘` 그룹·`상세 열기`·`보안 실패만` 스위치·`이벤트 상세` region 없음.

- [ ] **Step 7: ActivityPage.tsx 재작성**

```tsx
// admin-ui/src/pages/ActivityPage.tsx
import { useState, useEffect, useRef, useMemo, type ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { activityApi } from '@/api/activity';
import { downloadCsv } from '@/lib/csvExport';
import { kstDateTime, kstTime, utcIso, groupByDay } from '@/lib/kst';
import type { ActivityView, ActivityCategory, ActivityDetailView } from '@/api/types';
import { adaptFeedItems, type RecentActivityEvent } from './tenant/recentActivityAdapter';
import { actionLabel, eventSentence } from './activityLabels';
import { buildDiffRows, arrayChange, type DiffRow } from './activityDiff';
import { tenantsApi } from '@/api/tenants';
import type { Tenant } from '@/api/designTypes';
import { illustrations } from '@/assets/illustrations';
import {
  Button, CopyBtn, EmptyState, IconButton, KpiCard, PageHeader, RemovableChip, Segmented, Switch, useToast,
} from '@/ui';

// ── Helpers ──────────────────────────────────────────────────────────────────

function fmt(n: number | null | undefined): string {
  if (n == null) return '—';
  return n.toLocaleString();
}

function parsePayload(raw: string): Record<string, unknown> | null {
  try { return JSON.parse(raw) as Record<string, unknown>; } catch { return null; }
}

function fmtVal(v: unknown): string {
  if (v === undefined || v === null) return '—';
  return typeof v === 'string' ? v : JSON.stringify(v);
}

// ── Adapter: server ActivityView → display shape ──────────────────────────────
// Server feed items use { action, actorEmail, category, createdAt, tenantSlug }
// Design expects  { type, actorType, actorId, ts, tenantName, tenantSlug }

type DisplayEvent = RecentActivityEvent;

function adaptServerView(view: ActivityView): {
  events: DisplayEvent[];
  kpi: { events24h: number; ops24h: number; security24h: number; p95Ms: number | null };
  topTenants: { tenantId: string; tenantName: string; tenantSlug: string; count: number }[];
} {
  const events = adaptFeedItems(view);

  const topTenants = view.top5.map((t) => ({
    tenantId: t.tenantId,
    tenantName: t.slug,
    tenantSlug: t.slug,
    count: t.count,
  }));

  return { events, kpi: view.kpi, topTenants };
}

// 픽스처의 가짜 p95 를 보이지 않도록 0/null 로 시작한다(첫 응답 전에는 로딩 화면이라 노출되지 않음).
const EMPTY_KPI: { events24h: number; ops24h: number; security24h: number; p95Ms: number | null } = {
  events24h: 0, ops24h: 0, security24h: 0, p95Ms: null,
};

// ── Event 분류 → 색상 ─────────────────────────────────────────────────────────
// 서버가 분류한 category(ops/security/system)를 1차 기준으로, 이름이 *_FAILED/
// *_FAIL 인 실패 이벤트는 보안 실패로 격상해 한눈에 빨갛게 보이도록 한다.
// V2 토큰: danger=--bad(보안 실패), warning=--warn-dot(운영 변경), info=--brand-700(인증), teal=--faint(시스템).

type EventTone = 'danger' | 'warning' | 'info' | 'teal';

const FAILURE_TYPES = new Set([
  'SIGNATURE_COUNTER_REGRESSION',
  'ATTESTATION_TRUST_FAILED',
  'ADMIN_LOGIN_FAILED',
]);

const AUTH_TYPES = new Set([
  'ADMIN_LOGIN',
]);

function eventTone(type: string, category: string): EventTone {
  // 실패(이름 기준) 또는 보안 카테고리 → 빨강
  if (FAILURE_TYPES.has(type) || /_(FAILED|FAIL|REGRESSION)$/.test(type) || category === 'security') {
    return 'danger';
  }
  // 인증 성공 → 파랑
  if (AUTH_TYPES.has(type)) return 'info';
  // 운영 액션(테넌트/키 mutation) → 주황
  if (category === 'ops') return 'warning';
  // 시스템 자동(MDS sync, retention purge 등) → 회색
  return 'teal';
}

const TONE_VAR: Record<EventTone, string> = {
  danger: 'var(--bad)',
  warning: 'var(--warn-dot)',
  info: 'var(--brand-700)',
  teal: 'var(--faint)',
};

const LEGEND: { tone: EventTone; label: string }[] = [
  { tone: 'danger', label: '보안 실패' },
  { tone: 'warning', label: '운영 변경' },
  { tone: 'info', label: '인증' },
  { tone: 'teal', label: '시스템' },
];

const CATEGORY_LABEL: Record<ActivityCategory, string> = { all: '전체', ops: '운영', security: '보안' };

const REMOVED_TAG = { background: 'var(--bad-soft)', color: 'var(--bad-ink)' };
const ADDED_TAG = { background: 'var(--ok-soft)', color: 'var(--ok-ink)' };

// ── Detail panel pieces ───────────────────────────────────────────────────────

function Section({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <div className="stack-1" style={{ gap: 6 }} role="group" aria-label={title}>
      <div className="row">
        <span className="t-over">{title}</span>
        {action && <><div className="spacer" />{action}</>}
      </div>
      {children}
    </div>
  );
}

function DiffLine({ row }: { row: DiffRow }) {
  const arr = arrayChange(row.from, row.to);
  return (
    <div className="row" style={{ gap: 8, fontSize: 12 }}>
      <span className="mono truncate" style={{ width: 84, color: 'var(--mute)' }} title={row.field}>{row.field}</span>
      {arr ? (
        <>
          {arr.removed.map((x) => <span key={`-${x}`} className="tag" style={REMOVED_TAG}>{`− ${x}`}</span>)}
          {arr.added.map((x) => <span key={`+${x}`} className="tag" style={ADDED_TAG}>{`+ ${x}`}</span>)}
        </>
      ) : (
        <>
          <span className="tag" style={{ ...REMOVED_TAG, textDecoration: 'line-through' }}>{fmtVal(row.from)}</span>
          <span style={{ color: 'var(--mute)', display: 'inline-flex' }}><Icons.ChevronRight size={14} /></span>
          <span className="tag" style={ADDED_TAG}>{fmtVal(row.to)}</span>
        </>
      )}
      <span className="sr-only">{`${row.field}: ${fmtVal(row.from)} 에서 ${fmtVal(row.to)} 로 변경`}</span>
    </div>
  );
}

function DetailPanel({
  detail, loading, onClose, onOpenTenant,
}: { detail: ActivityDetailView | null; loading: boolean; onClose: () => void; onOpenTenant: (id: string | null) => void }) {
  const payload = detail ? parsePayload(detail.payload) : null;
  const json = payload ? JSON.stringify(payload, null, 2) : (detail?.payload ?? '');
  const rows = detail ? buildDiffRows(detail) : [];
  return (
    <section className="card" aria-label="이벤트 상세">
      <div className="card__head">
        <h2 className="card__title">상세</h2>
        {detail && <span className="tag" title={actionLabel(detail.action)}>{detail.action}</span>}
        <div className="spacer" />
        <IconButton label="닫기" icon={<Icons.X size={16} />} onClick={onClose} />
      </div>
      <div className="card__body stack-4" style={{ gap: 18 }}>
        {loading && <p className="muted" style={{ margin: 0, fontSize: 13 }}>불러오는 중…</p>}
        {!loading && detail && (
          <>
            <Section title="언제">
              <span style={{ fontSize: 13 }}>{kstDateTime(detail.createdAt)} <span className="muted" style={{ fontSize: 12 }}>KST</span></span>
              <span className="mono muted" style={{ fontSize: 12 }}>{`${utcIso(detail.createdAt)} UTC`}</span>
            </Section>
            <Section title="누가">
              <span className="row" style={{ gap: 8, fontSize: 13 }}>
                <span className="avatar avatar--sm" aria-hidden="true">{(detail.actorEmail || 'S').slice(0, 1).toUpperCase()}</span>
                <span>{detail.actorEmail || 'system'}</span>
              </span>
              {detail.actorIp !== undefined && <span className="mono muted" style={{ fontSize: 12 }}>{`IP ${detail.actorIp}`}</span>}
            </Section>
            <Section title="테넌트 · 대상">
              <span className="row" style={{ gap: 4, fontSize: 13 }}>
                {detail.tenantId
                  ? <Button variant="ghost" size="xs" onClick={() => onOpenTenant(detail.tenantId)}>{detail.tenantSlug ?? detail.tenantId}</Button>
                  : <span>플랫폼</span>}
                <span>{`· ${detail.targetType ?? '—'}`}</span>
                {detail.targetId && <span className="mono muted" style={{ fontSize: 12 }} title={detail.targetId}>{`…${detail.targetId.slice(-8)}`}</span>}
              </span>
            </Section>
            {rows.length > 0 && (
              <Section title="어떻게 바뀜">
                {rows.map((r) => <DiffLine key={r.field} row={r} />)}
              </Section>
            )}
            <Section title="원본 JSON" action={<CopyBtn value={json} label="복사" />}>
              <pre className="mono" style={{ margin: 0, padding: 12, borderRadius: 8, background: 'var(--canvas)', border: '1px solid var(--line-soft)', fontSize: 11, lineHeight: '17px', whiteSpace: 'pre-wrap', wordBreak: 'break-all', color: 'var(--ink-2)', maxHeight: 320, overflow: 'auto' }}>{json}</pre>
            </Section>
          </>
        )}
      </div>
    </section>
  );
}

// ── Feed row ──────────────────────────────────────────────────────────────────

function FeedRow({ e, selected, onSelect, onOpenTenant }: {
  e: DisplayEvent; selected: boolean; onSelect: (id: string) => void; onOpenTenant: (id: string | null) => void;
}) {
  return (
    <div
      className="row"
      aria-current={selected ? 'true' : undefined}
      style={{
        flexWrap: 'nowrap', alignItems: 'flex-start', gap: 12, padding: '12px 20px',
        borderBottom: '1px solid var(--line-soft)',
        background: selected ? 'var(--brand-50)' : 'var(--surface)',
        boxShadow: selected ? 'inset 3px 0 0 var(--brand-700)' : 'none',
      }}
    >
      <span className="mono muted" style={{ width: 44, flex: 'none', paddingTop: 2, fontSize: 12 }}>{kstTime(e.ts)}</span>
      <span className="dot" aria-hidden="true" style={{ marginTop: 7, background: TONE_VAR[eventTone(e.type, e.category)] }} />
      <div className="col" style={{ gap: 4, flex: '1 1 0', minWidth: 0 }}>
        <div className="row" style={{ gap: 8 }}>
          <span className="tag" title={actionLabel(e.type)}>{e.type}</span>
          {e.tenantId
            ? <Button variant="ghost" size="xs" onClick={() => onOpenTenant(e.tenantId)}>{e.tenantName}</Button>
            : <span className="muted" style={{ fontSize: 13 }}>플랫폼</span>}
        </div>
        <span style={{ fontSize: 13, color: 'var(--ink)' }}>
          {eventSentence({
            action: e.type,
            actorEmail: e.actorId ?? '',
            tenantSlug: e.tenantSlug,
            targetType: null,
            targetId: e.subjectId,
          })}
        </span>
      </div>
      <IconButton label="상세 열기" icon={<Icons.ChevronRight size={16} />} onClick={() => onSelect(e.id)} />
    </div>
  );
}

// ── ActivityPage ──────────────────────────────────────────────────────────────

export default function ActivityPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [searchParams] = useSearchParams();

  // null = not yet loaded from server; undefined = server error (show empty state)
  const [events, setEvents] = useState<DisplayEvent[] | null>(null);
  const [kpi, setKpi] = useState(EMPTY_KPI);
  const [topTenants, setTopTenants] = useState<{ tenantId: string; tenantName: string; tenantSlug: string; count: number }[]>([]);
  const [serverError, setServerError] = useState(false);

  const [detail, setDetail] = useState<ActivityDetailView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const [categoryFilter, setCategoryFilter] = useState<ActivityCategory>('all');
  const [visibleCount, setVisibleCount] = useState(24);

  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [actionQuery, setActionQuery] = useState('');
  const [selectedTenant, setSelectedTenant] = useState<string | undefined>(
    searchParams.get('tenantId') ?? undefined,
  );

  // Load tenant list once for the filter dropdown
  useEffect(() => {
    tenantsApi.list().then(setTenants).catch(() => setTenants([]));
  }, []);

  // 5-second polling
  const cancelledRef = useRef(false);
  // Track whether we've had at least one successful server response
  const hasServerDataRef = useRef(false);

  useEffect(() => {
    cancelledRef.current = false;

    async function fetchOnce() {
      try {
        const view = await activityApi.fetch(null, categoryFilter === 'all' ? undefined : categoryFilter, undefined, selectedTenant);
        if (cancelledRef.current) return;
        const adapted = adaptServerView(view);
        hasServerDataRef.current = true;
        setServerError(false);
        setEvents(adapted.events);
        setKpi((prev) => ({
          ...prev,
          ...adapted.kpi,
          // p95Ms: server may return null; keep previous value as fallback
          p95Ms: adapted.kpi.p95Ms ?? prev.p95Ms,
        }));
        setTopTenants(adapted.topTenants);
      } catch {
        if (cancelledRef.current) return;
        if (!hasServerDataRef.current) {
          // Never had server data: show error state rather than fixture
          setServerError(true);
        }
        // If we already had server data, keep it (transient network blip)
      }
    }

    // Reset visible window when the query (category/tenant) changes so the
    // user sees fresh first-page data, not a stale large slice.
    setVisibleCount(24);

    void fetchOnce();
    const id = setInterval(() => void fetchOnce(), 5000);
    return () => {
      cancelledRef.current = true;
      clearInterval(id);
    };
  }, [categoryFilter, selectedTenant]);

  const displayEvents = useMemo(() => events ?? [], [events]);
  const filtered = useMemo(
    () =>
      displayEvents.filter((e) => {
        if (!actionQuery.trim()) return true;
        const q = actionQuery.trim().toUpperCase();
        return e.type.toUpperCase().includes(q) || actionLabel(e.type).includes(actionQuery.trim());
      }),
    [displayEvents, actionQuery],
  );

  function openDetail(id: string) {
    setSelectedId(id);
    setDetailLoading(true);
    setDetail(null);
    activityApi
      .fetchDetail(id)
      .then((d) => setDetail(d))
      .catch(() => toast({ kind: 'err', title: '상세 로드 실패' }))
      .finally(() => setDetailLoading(false));
  }

  function closeDetail() {
    setDetail(null);
    setSelectedId(null);
  }

  function handleOpenTenant(tenantId: string | null) {
    if (!tenantId) return;
    navigate(`/tenants/${tenantId}`);
  }

  function handleRefresh() {
    activityApi
      .fetch(null, categoryFilter === 'all' ? undefined : categoryFilter, undefined, selectedTenant)
      .then((view) => {
        if (cancelledRef.current) return;
        const adapted = adaptServerView(view);
        hasServerDataRef.current = true;
        setServerError(false);
        setEvents(adapted.events);
        setKpi((prev) => ({
          ...prev,
          ...adapted.kpi,
          p95Ms: adapted.kpi.p95Ms ?? prev.p95Ms,
        }));
        setTopTenants(adapted.topTenants);
        toast({ kind: 'ok', title: '새로고침 완료' });
      })
      .catch(() => toast({ kind: 'warn', title: '새로고침 실패', message: '서버 응답 없음' }));
  }

  function handleExport() {
    if (!events || events.length === 0) {
      toast({ kind: 'warn', title: '내보낼 데이터 없음' });
      return;
    }
    downloadCsv(
      `activity-${new Date().toISOString().slice(0, 10)}.csv`,
      ['timestamp', 'tenant', 'type', 'category', 'actor', 'subject'],
      events.map((e) => [e.ts, e.tenantSlug ?? e.tenantId ?? '', e.type, e.category, e.actorId ?? '', e.subjectId]),
    );
  }

  async function handleLoadMore() {
    if (!events || events.length === 0) return;
    const oldestEvent = events[events.length - 1];
    const oldest = oldestEvent.ts;
    try {
      const more = await activityApi.fetch(
        null,
        categoryFilter === 'all' ? undefined : categoryFilter,
        oldest,
        selectedTenant,
        // F07: send the oldest visible row's id as the tuple-cursor
        // partner to `before` so a row sharing the exact same
        // microsecond timestamp as `oldest` is not dropped.
        oldestEvent.id,
      );
      const adapted = adaptServerView(more);
      setEvents([...events, ...adapted.events]);
      // Expand the visible window so newly-loaded rows actually appear in the feed
      setVisibleCount((n) => n + Math.max(adapted.events.length, 24));
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      toast({ kind: 'err', title: '추가 로드 실패', message: msg });
    }
  }

  function clearFilters() {
    setCategoryFilter('all');
    setSelectedTenant(undefined);
    setActionQuery('');
  }

  const header = (
    <PageHeader
      title="Activity"
      sub="전체 tenant의 ceremony · 운영 액션 · 보안 이벤트가 실시간으로 모입니다."
      actions={serverError ? (
        <Button icon={<Icons.Refresh size={14} />} onClick={handleRefresh}>재시도</Button>
      ) : (
        <>
          <span className="row muted" style={{ gap: 6, fontSize: 12 }}>
            <span className="dot" aria-hidden="true" style={{ background: 'var(--ok)' }} />Live
          </span>
          <Button icon={<Icons.Refresh size={14} />} onClick={handleRefresh}>새로고침</Button>
          <Button icon={<Icons.Download size={14} />} onClick={handleExport}>내보내기</Button>
        </>
      )}
    />
  );

  // Loading state: waiting for first server response
  if (events === null && !serverError) {
    return (
      <div className="page">
        {header}
        <div className="card"><div className="empty"><p className="empty__sub">Activity 데이터 로딩 중…</p></div></div>
      </div>
    );
  }

  // Error state: first fetch failed — do not show fixture data as live data
  if (serverError) {
    return (
      <div className="page">
        {header}
        <div className="card">
          <EmptyState
            illustration={illustrations.loadError}
            title="Activity 데이터를 불러오지 못했습니다"
            description="서버에 연결할 수 없거나 접근 권한이 없습니다."
          />
        </div>
      </div>
    );
  }

  const groups = groupByDay(filtered.slice(0, visibleCount), (e) => e.ts, new Date());
  const maxTop = Math.max(1, ...topTenants.map((t) => t.count));
  const selectedTenantSlug = selectedTenant
    ? (tenants.find((t) => t.id === selectedTenant)?.slug ?? topTenants.find((t) => t.tenantId === selectedTenant)?.tenantSlug ?? selectedTenant)
    : null;
  const hasApplied = categoryFilter !== 'all' || !!selectedTenant || actionQuery.trim() !== '';

  return (
    <div className="page">
      {header}

      <div className="kpis">
        <KpiCard label="활동 (24h)" value={fmt(kpi.events24h)} />
        <KpiCard label="운영 액션 (24h)" value={fmt(kpi.ops24h)} />
        <KpiCard
          label="보안 이벤트 (24h)"
          value={<span style={{ color: kpi.security24h > 0 ? 'var(--bad)' : undefined }}>{fmt(kpi.security24h)}</span>}
        />
        <KpiCard
          label="평균 응답"
          value={kpi.p95Ms != null ? `${kpi.p95Ms}ms` : '—'}
          hint={kpi.p95Ms != null ? `p95 ${kpi.p95Ms}ms` : undefined}
        />
      </div>

      <div className="card" style={{ padding: '14px 20px', display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div className="row" style={{ gap: 12 }}>
          <Segmented<ActivityCategory>
            ariaLabel="카테고리"
            value={categoryFilter}
            onChange={setCategoryFilter}
            options={[{ value: 'all', label: '전체' }, { value: 'ops', label: '운영' }, { value: 'security', label: '보안' }]}
          />
          <select
            className="input"
            aria-label="테넌트"
            style={{ width: 200 }}
            value={selectedTenant ?? ''}
            onChange={(ev) => setSelectedTenant(ev.target.value || undefined)}
          >
            <option value="">테넌트 · 전체</option>
            {tenants.map((t) => (
              <option key={t.id} value={t.id}>{t.name} ({t.slug})</option>
            ))}
          </select>
          <div className="search" style={{ flex: '1 1 220px' }}>
            <Icons.Search size={15} />
            <input className="input" aria-label="액션 검색" placeholder="액션 검색…" value={actionQuery} onChange={(ev) => setActionQuery(ev.target.value)} />
          </div>
          {/* "보안 실패만" = category=security 의 다른 보기. 서버 SECURITY_ACTIONS 가 실패 액션뿐이다. */}
          <Switch
            label="보안 실패만"
            checked={categoryFilter === 'security'}
            onChange={(on) => setCategoryFilter(on ? 'security' : 'all')}
          />
        </div>
        {hasApplied && (
          <div className="row" style={{ gap: 8 }}>
            <span className="muted" style={{ fontSize: 12 }}>적용된 필터</span>
            {categoryFilter !== 'all' && (
              <RemovableChip removeLabel="카테고리 필터 제거" onRemove={() => setCategoryFilter('all')}>{`카테고리: ${CATEGORY_LABEL[categoryFilter]}`}</RemovableChip>
            )}
            {selectedTenantSlug && (
              <RemovableChip removeLabel="테넌트 필터 제거" onRemove={() => setSelectedTenant(undefined)}>{`테넌트: ${selectedTenantSlug}`}</RemovableChip>
            )}
            {actionQuery.trim() !== '' && (
              <RemovableChip removeLabel="액션 검색 제거" onRemove={() => setActionQuery('')}>{`액션: ${actionQuery.trim()}`}</RemovableChip>
            )}
            <Button variant="ghost" size="sm" onClick={clearFilters}>모두 지우기</Button>
          </div>
        )}
      </div>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 20, alignItems: 'flex-start' }}>
        <section className="card" style={{ flex: '999 1 460px', minWidth: 0 }}>
          <div className="card__head">
            <h2 className="card__title">최근 이벤트</h2>
            <div className="spacer" />
            <span className="row muted" style={{ gap: 12, fontSize: 12 }}>
              {LEGEND.map((l) => (
                <span key={l.tone} className="row" style={{ gap: 5 }}>
                  <span className="dot" aria-hidden="true" style={{ background: TONE_VAR[l.tone] }} />{l.label}
                </span>
              ))}
            </span>
          </div>
          {groups.length === 0 ? (
            <EmptyState
              compact
              illustration={illustrations.noResults}
              title="조건에 맞는 이벤트가 없습니다"
              description="필터를 바꾸거나 이전 24시간을 더 불러오세요."
            />
          ) : (
            groups.map((g, gi) => (
              <div key={`${g.label}-${gi}`} role="group" aria-label={g.label}>
                <div className="t-over" style={{ padding: '8px 20px', background: 'var(--canvas)', borderBottom: '1px solid var(--line-soft)' }}>{g.label}</div>
                {g.items.map((e) => (
                  <FeedRow key={e.id} e={e} selected={selectedId === e.id} onSelect={openDetail} onOpenTenant={handleOpenTenant} />
                ))}
              </div>
            ))
          )}
          <div className="card__foot" style={{ justifyContent: 'center' }}>
            <Button onClick={() => void handleLoadMore()}>이전 24시간 더 보기</Button>
          </div>
        </section>

        <aside style={{ flex: '1 1 320px', maxWidth: '100%', display: 'flex', flexDirection: 'column', gap: 20 }}>
          {(detail || detailLoading) && (
            <DetailPanel detail={detail} loading={detailLoading} onClose={closeDetail} onOpenTenant={handleOpenTenant} />
          )}
          <section className="card">
            <div className="card__head">
              <h2 className="card__title">활발한 Tenant</h2>
              <span className="muted" style={{ fontSize: 12 }}>24h</span>
            </div>
            {topTenants.length === 0 ? (
              <p className="muted" style={{ margin: 0, padding: '16px 20px', fontSize: 13 }}>최근 24시간 이벤트가 없습니다.</p>
            ) : topTenants.map((t) => (
              <button
                key={t.tenantId}
                type="button"
                className="row"
                aria-pressed={selectedTenant === t.tenantId}
                onClick={() => setSelectedTenant(t.tenantId)}
                style={{ flexWrap: 'nowrap', gap: 10, padding: '10px 20px', width: '100%', border: 0, borderBottom: '1px solid var(--line-soft)', background: selectedTenant === t.tenantId ? 'var(--brand-50)' : 'transparent', textAlign: 'left', cursor: 'pointer', color: 'var(--ink)', font: 'inherit' }}
              >
                <span className="avatar avatar--sq" aria-hidden="true" style={{ width: 28, height: 28, fontSize: 12 }}>{t.tenantName.slice(0, 1).toUpperCase()}</span>
                <span className="col" style={{ flex: '1 1 0', minWidth: 0 }}>
                  <span className="truncate" style={{ fontSize: 13, fontWeight: 500 }}>{t.tenantName}</span>
                  <span className="mono muted" style={{ fontSize: 12 }}>{t.tenantSlug}</span>
                </span>
                <span aria-hidden="true" style={{ width: 64, height: 6, borderRadius: 3, background: 'var(--line-soft)', flex: 'none' }}>
                  <span style={{ display: 'block', width: `${Math.round((t.count / maxTop) * 100)}%`, height: 6, borderRadius: 3, background: 'var(--brand-500)' }} />
                </span>
                <span style={{ fontSize: 13, fontWeight: 600, width: 40, textAlign: 'right' }}>{fmt(t.count)}</span>
              </button>
            ))}
          </section>
        </aside>
      </div>
    </div>
  );
}
```

- [ ] **Step 8: 테스트 통과 확인**

Run: `npx vitest run src/pages/ActivityPage.test.tsx src/pages/activityDiff.test.ts src/lib/kst.test.ts src/pages/activityLabels.test.ts`
Expected: PASS (ActivityPage 7, activityDiff 6, kst 10, activityLabels 기존 그대로).

- [ ] **Step 9: 전체 검증**

Run: `npm test && npm run lint && npm run build`
Expected: 전부 PASS, lint error 0, 빌드 성공.

- [ ] **Step 10: 커밋**

```bash
git add src/api/types.ts src/lib/kst.ts src/lib/kst.test.ts src/pages/activityDiff.ts src/pages/activityDiff.test.ts \
  src/pages/ActivityPage.tsx src/pages/ActivityPage.test.tsx
git commit -m "feat(admin-ui): Activity V2 — KST 날짜 그룹·보안 실패만 스위치·적용 필터 칩·상세 패널(언제/누가/대상/diff/원본 JSON)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 18: 감사 체인 모니터

V2 아트보드: `v2/AuditChain.dc.html`, `v2/Dialogs-Audit.dc.html`(Incident 생성 · Incident 해결 · 월간 무결성 보고서 발급).

기존 동작 유지(이름 그대로): 상태 `overview`/`loading`/`error`/`showReport`/`running`/`incidents`/`incidentError`/`creating`/`confirmCreate`/`resolving`/`resolveNote`/`resolveBusy`, 함수 `refreshIncidents`(실패 시 목록을 비우지 않고 플래그만), `load`(overview(24) → incidents, incident 실패가 페이지를 막지 않음), `handleRunAll`(backfill → load), `openTenant`, `handleCreateIncident`(409 이미 진행 중 / 422 위변조 미확인 / 기타, 모든 실패 후 `refreshIncidents`), `handleResolveIncident`(빈 메모 거부, 409/404 처리), `MonthlyReportDialog` 의 PDF 다운로드(`monthlyReportApi.download(from, to)` → blob 링크 클릭).

바뀌는 것:
- **헤더 문구:** 서버에 정기 검증 스케줄러가 없다. `GET /admin/api/audit/chain/overview` 가 호출될 때마다 `verifier.verifyAllTenants()` 를 실행한다(`AuditChainMonitorController.overview`). 따라서 `60초마다 자동 검증`, `검증 주기 60s`, `최근 60초 기준`, `Live · 60s` 를 쓰지 않고 실제 동작으로 표기한다: `화면을 열 때와 '새로 검증'·'전체 즉시 검증'을 누를 때 서버가 검증합니다.`
- 전체 상태 헤드라인 카드(아이콘 타일 · `전체 상태` · 헤드라인 · `마지막 검증 …`[· `다음 검증 …` 은 `nextRunAt` 있을 때만] · `새로 검증` 버튼 = `load()`).
- 위변조 시 `Alert tone="bad"`(tenant 열기 / Incident 생성). 불일치 entry id 가 있으면 끝 8자 표시.
- KPI 4: `정상 / 전체`, `검증된 audit row`, `검증 방식`(`nextRunAt` 없으면 `요청 시`, 있으면 `자동` + 다음 검증 KST), `검증 소요`(`verificationMs`). 기존 `100k rows 기준 · p99 920ms` 하드코딩 보조 문구는 근거 없는 값이라 삭제.
- 표: `Segmented` 전체/위변조만. 열 Tenant · 상태 · 검증 행 수 · 마지막 검증 · [`최근 14회` `StatusStrip` — **어떤 tenant 에도 `recentRuns` 가 없으면 열 자체를 숨김**] · 액션(열기 · 검증). 기존 `buckets` 스파크라인(`ChainSparkline`)은 V2 에 없어 삭제한다. 기존의 동작 없는 `검증` 버튼은 기존 API `auditChainApi.verifyTenant(tenantId)`(TenantOverview 가 이미 사용)에 연결하고 결과 토스트 후 `load()`.
- Incident 표(상태 배지 OPEN=`warn 처리중`, RESOLVED=`ok 해결`, 유형 태그, KST 시각) / 0건이면 `EmptyState(illustrations.allClear, compact)` / 목록 오류는 `Alert` + 다시 시도.
- 다이얼로그 V2: Incident 생성(본문 한 문장 + 재검증 안내 hint, `danger-solid`), Incident 해결(`Field` 필수 `해결 메모 *`, help `필수 · 감사 로그에 함께 기록됩니다.`, 빈 메모면 버튼 비활성), 월간 보고서(시작일·종료일 `Field`, 포함 tenant 요약 목록).
  - 월간 보고서 API(`GET …/monthly-report?from&to`)에는 tenant 선택 파라미터가 없다(서버 `MonthlyReportController` 확인). 아트보드의 tenant 체크박스는 요청에 반영되지 않는 가짜 컨트롤이 되므로 **체크박스 없이** `포함 tenant · N개` 읽기 전용 목록으로 그리고, 부제를 `기간 내 전체 tenant 의 chain 검증 결과를 PDF 로 묶습니다.` 로 쓴다. 기본 기간은 하드코딩(`2026-04-01~30`) 대신 직전 달.
- 로딩/오류: `불러오는 중…` 카드, overview 실패는 `EmptyState(illustrations.loadError)` + 다시 시도.

**Files:**
- Modify: `admin-ui/src/api/auditChainMonitor.ts`(`ChainOverview.nextRunAt?`, `tenants[].recentRuns?`)
- Rewrite: `admin-ui/src/pages/AuditChainPage.tsx`
- Create: `admin-ui/src/pages/AuditChainPage.test.tsx`

**Interfaces:**
- Consumes: Task 16 `kstDateTime`; `@/ui` — `Alert`, `Badge`, `Button`, `Card`, `CardHeader`, `DataTable`, `type Column`, `Dialog`, `EmptyState`, `Field`, `KpiCard`, `PageHeader`, `Segmented`, `StatusStrip`, `ToastHost`, `useToast`; `illustrations.allClear | loadError`; `auditChainMonitorApi.*`, `auditChainApi.verifyTenant`, `monthlyReportApi.download`, `ApiError`, `statusLabel`.
- Produces:
  - `ChainOverview.nextRunAt?: string`, `ChainOverview['tenants'][number].recentRuns?: { at: string; ok: boolean }[]`(오래된 → 최신 순, 화면은 마지막 14개) — 스펙 §6 ② 이름 그대로. Task 12 사이드바는 이 타입의 기존 필드만 쓰므로 영향 없음.

- [ ] **Step 1: 실패하는 테스트**

```tsx
// admin-ui/src/pages/AuditChainPage.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, fireEvent, within, waitFor } from '@testing-library/react';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import { ToastHost } from '@/ui';
import type { ChainOverview, IncidentView } from '@/api/auditChainMonitor';

vi.mock('@/api/auditChainMonitor', () => ({
  auditChainMonitorApi: {
    overview: vi.fn(), backfill: vi.fn(), listIncidents: vi.fn(), createIncident: vi.fn(), resolveIncident: vi.fn(),
  },
}));
vi.mock('@/api/auditChain', () => ({ auditChainApi: { verifyTenant: vi.fn() } }));
vi.mock('@/api/monthlyReport', () => ({ monthlyReportApi: { download: vi.fn() } }));

import { auditChainMonitorApi } from '@/api/auditChainMonitor';
import AuditChainPage from './AuditChainPage';

type Row = ChainOverview['tenants'][number];
const row = (tenantId: string, tenantName: string, intact: boolean, over: Partial<Row> = {}): Row => ({
  tenantId, tenantName, intact, verifiedRows: 1200, buckets: [], tamperedEntryId: intact ? null : 'entry-0000-abcdef12', ...over,
});
function overview(tenants: Row[], over: Partial<ChainOverview> = {}): ChainOverview {
  const tampered = tenants.filter((t) => !t.intact).length;
  return {
    verifiedAt: new Date().toISOString(), windowHours: 24, bucketSizeMinutes: 60,
    totals: { tenantsIntact: tenants.length - tampered, tenantsTotal: tenants.length, tenantsTampered: tampered, verifiedRows: 2400, verificationMs: 42 },
    tenants, ...over,
  };
}
const incident = (over: Partial<IncidentView> = {}): IncidentView => ({
  id: 'i1', tenantId: 't2', tenantName: 'Globex', tamperedEntryId: 'entry-0000-abcdef12', type: 'CHAIN_TAMPER',
  severity: 'HIGH', status: 'OPEN', detail: null, createdAt: '2026-10-11T02:00:00Z', createdByEmail: 'op@x.com',
  resolvedAt: null, resolvedByEmail: null, resolutionNote: null, ...over,
});
const runs = (n: number, badTail = 0) =>
  Array.from({ length: n }, (_, i) => ({ at: new Date(Date.now() - (n - i) * 60_000).toISOString(), ok: i < n - badTail }));

function renderPage() {
  return renderWithDataRouter(<ToastHost><AuditChainPage /></ToastHost>, { initialPath: '/audit-chain' });
}

beforeEach(() => {
  vi.mocked(auditChainMonitorApi.overview).mockReset().mockResolvedValue(overview([row('t1', 'Acme', true), row('t2', 'Globex', true)]));
  vi.mocked(auditChainMonitorApi.listIncidents).mockReset().mockResolvedValue([]);
  vi.mocked(auditChainMonitorApi.createIncident).mockReset();
  vi.mocked(auditChainMonitorApi.resolveIncident).mockReset();
});

describe('AuditChainPage V2', () => {
  it('어떤 tenant 에도 recentRuns 가 없으면 스트립 열 전체를 숨긴다', async () => {
    renderPage();
    await screen.findByText('Acme');
    expect(screen.queryByRole('columnheader', { name: '최근 14회' })).toBeNull();
    expect(screen.queryByRole('img', { name: /^최근 \d+회/ })).toBeNull();
  });

  it('recentRuns 가 있으면 스트립 열을 보이고 마지막 14회를 요약한다', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(
      overview([row('t1', 'Acme', true, { recentRuns: runs(20, 2) }), row('t2', 'Globex', true)]),
    );
    renderPage();
    await screen.findByText('Acme');
    expect(screen.getByRole('columnheader', { name: '최근 14회' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '최근 14회 중 위변조 감지 2회' })).toBeInTheDocument();
    expect(screen.getAllByRole('img', { name: /^최근 \d+회/ })).toHaveLength(1); // Globex 는 셀 비움
  });

  it('nextRunAt 이 없으면 "다음 검증" 을 그리지 않고, 있으면 그린다', async () => {
    const { unmount } = renderPage();
    await screen.findByText('Acme');
    expect(screen.queryByText(/다음 검증/)).toBeNull();
    unmount();

    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(
      overview([row('t1', 'Acme', true)], { nextRunAt: new Date(Date.now() + 5 * 60_000).toISOString() }),
    );
    renderPage();
    expect(await screen.findByText(/다음 검증 \d+분 후/)).toBeInTheDocument();
  });

  it('스케줄러가 없으므로 60초 자동 검증을 주장하지 않는다', async () => {
    renderPage();
    await screen.findByText('Acme');
    expect(screen.queryByText(/60초|60s|자동 검증/)).toBeNull();
    expect(screen.getByText(/화면을 열 때와/)).toBeInTheDocument();
    expect(screen.getByText('요청 시')).toBeInTheDocument();
  });

  it('incident 0건이면 all-clear 빈 상태', async () => {
    renderPage();
    expect(await screen.findByText('등록된 incident가 없습니다')).toBeInTheDocument();
    expect(screen.getByText('모든 chain 이 정상입니다.')).toBeInTheDocument();
  });

  it('incident 목록 실패는 빈 상태로 오인시키지 않는다', async () => {
    vi.mocked(auditChainMonitorApi.listIncidents).mockRejectedValue(new Error('500'));
    renderPage();
    expect(await screen.findByText('Incident 목록을 불러오지 못했습니다.')).toBeInTheDocument();
    expect(screen.queryByText('등록된 incident가 없습니다')).toBeNull();
  });

  it('overview 실패 시 오류 상태 + 다시 시도', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockRejectedValue(new Error('overview down'));
    renderPage();
    expect(await screen.findByText('감사 체인 상태를 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText('overview down')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
  });

  it('위변조면 bad 배너 → Incident 생성 다이얼로그 → createIncident(tenantId)', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(overview([row('t1', 'Acme', true), row('t2', 'Globex', false)]));
    vi.mocked(auditChainMonitorApi.createIncident).mockResolvedValue(incident());
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('위변조 의심 — 즉시 조사 필요');
    expect(alert).toHaveTextContent('Globex chain 에서 hash 불일치가 감지되었습니다.');
    fireEvent.click(within(alert).getByRole('button', { name: 'Incident 생성' }));
    const dialog = screen.getByRole('dialog', { name: 'Incident 생성' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Incident 생성' }));
    await waitFor(() => expect(auditChainMonitorApi.createIncident).toHaveBeenCalledWith('t2'));
  });

  it('Incident 해결은 메모가 필수', async () => {
    vi.mocked(auditChainMonitorApi.listIncidents).mockResolvedValue([incident()]);
    vi.mocked(auditChainMonitorApi.resolveIncident).mockResolvedValue(incident({ status: 'RESOLVED' }));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '해결' }));
    const dialog = screen.getByRole('dialog', { name: 'Incident 해결' });
    const submit = within(dialog).getByRole('button', { name: '해결로 표시' });
    expect(submit).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText(/해결 메모/), { target: { value: '   ' } });
    expect(submit).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText(/해결 메모/), { target: { value: 'DBA 확인, 복구 완료' } });
    expect(submit).toBeEnabled();
    fireEvent.click(submit);
    await waitFor(() => expect(auditChainMonitorApi.resolveIncident).toHaveBeenCalledWith('i1', 'DBA 확인, 복구 완료'));
  });

  it('위변조만 세그먼트', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(overview([row('t1', 'Acme', true), row('t2', 'Globex', false)]));
    renderPage();
    const table = await screen.findByRole('table');
    expect(within(table).getByText('Acme')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '위변조만' }));
    expect(within(screen.getAllByRole('table')[0]).queryByText('Acme')).toBeNull();
    expect(within(screen.getAllByRole('table')[0]).getByText('Globex')).toBeInTheDocument();
  });

  it('월간 보고서 다이얼로그는 tenant 체크박스 없이 포함 목록만 보인다', async () => {
    renderPage();
    await screen.findByText('Acme');
    fireEvent.click(screen.getByRole('button', { name: '월간 보고서' }));
    const dialog = screen.getByRole('dialog', { name: '월간 무결성 보고서 발급' });
    expect(within(dialog).getByText('포함 tenant · 2개')).toBeInTheDocument();
    expect(within(dialog).queryByRole('checkbox')).toBeNull();
    expect(within(dialog).getByLabelText('시작일')).toHaveAttribute('type', 'date');
    expect(within(dialog).getByRole('button', { name: 'PDF 생성 → 다운로드' })).toBeEnabled();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/AuditChainPage.test.tsx`
Expected: FAIL — `60초` 문구 존재, `요청 시` 없음, Incident 빈 상태 문구 불일치(`등록된 incident가 없습니다.` 마침표), `Chain (시각화)` 열 등.

- [ ] **Step 3: 타입 확장**

`admin-ui/src/api/auditChainMonitor.ts` 의 `ChainOverview` 를 다음으로 교체:

```ts
export type ChainOverview = {
  verifiedAt: string;
  windowHours: number;
  bucketSizeMinutes: number;
  /** 스펙 §6 ② 감사 체인 이력(서버 후속). 정기 검증 스케줄러가 생기면 다음 실행 시각. 없으면 "다음 검증" 을 그리지 않는다. */
  nextRunAt?: string;
  totals: {
    tenantsIntact: number;
    tenantsTotal: number;
    tenantsTampered: number;
    verifiedRows: number;
    verificationMs: number;
  };
  tenants: {
    tenantId: string | null;
    tenantName: string;
    intact: boolean;
    verifiedRows: number;
    buckets: number[];
    tamperedEntryId: string | null;
    /** 스펙 §6 ②. 최근 검증 결과(오래된 → 최신). 어떤 tenant 에도 없으면 스트립 열을 숨긴다. */
    recentRuns?: { at: string; ok: boolean }[];
  }[];
};
```

- [ ] **Step 4: AuditChainPage.tsx 재작성**

```tsx
// admin-ui/src/pages/AuditChainPage.tsx
import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { auditChainMonitorApi, type ChainOverview, type IncidentView } from '@/api/auditChainMonitor';
import { auditChainApi } from '@/api/auditChain';
import { monthlyReportApi } from '@/api/monthlyReport';
import { ApiError } from '@/api/types';
import { statusLabel } from '@/i18n/labels';
import { kstDateTime } from '@/lib/kst';
import { illustrations } from '@/assets/illustrations';
import {
  Alert, Badge, Button, Card, CardHeader, DataTable, Dialog, EmptyState, Field, KpiCard, PageHeader,
  Segmented, StatusStrip, useToast, type Column,
} from '@/ui';

type ChainRow = ChainOverview['tenants'][number];
const STRIP_RUNS = 14;

// ── Utilities ────────────────────────────────────────────────────────────────

function fmt(n: number): string {
  return n.toLocaleString();
}

function timeAgo(iso: string | null | undefined): string {
  if (!iso) return '—';
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return '방금 전';
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d}일 전`;
  const mo = Math.floor(d / 30);
  return `${mo}개월 전`;
}

function timeUntil(iso: string): string {
  const m = Math.ceil((new Date(iso).getTime() - Date.now()) / 60000);
  if (m <= 0) return '곧';
  if (m < 60) return `${m}분 후`;
  return `${Math.floor(m / 60)}시간 후`;
}

function ymd(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** 직전 달 1일 ~ 말일. */
function prevMonthRange(now: Date): { from: string; to: string } {
  return {
    from: ymd(new Date(now.getFullYear(), now.getMonth() - 1, 1)),
    to: ymd(new Date(now.getFullYear(), now.getMonth(), 0)),
  };
}

function stripLabel(runs: { ok: boolean }[]): string {
  const bad = runs.filter((r) => !r.ok).length;
  return bad === 0 ? `최근 ${runs.length}회 모두 정상` : `최근 ${runs.length}회 중 위변조 감지 ${bad}회`;
}

// ── MonthlyReportDialog ──────────────────────────────────────────────────────
// 서버 monthly-report 는 from/to 만 받는다(tenant 선택 없음) → 포함 tenant 는 읽기 전용 목록.

function MonthlyReportDialog({ open, onClose, overview }: {
  open: boolean;
  onClose: () => void;
  overview: ChainOverview;
}) {
  const initial = prevMonthRange(new Date());
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [busy, setBusy] = useState(false);
  const toast = useToast();

  async function handleGenerate() {
    setBusy(true);
    try {
      const blob = await monthlyReportApi.download(from, to);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `audit-chain-monthly-${from}-to-${to}.pdf`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      onClose();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: 'PDF 생성 실패', message: msg });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} wide title="월간 무결성 보고서 발급"
      sub="기간 내 전체 tenant 의 chain 검증 결과를 PDF 로 묶습니다."
      footer={<>
        <Button onClick={onClose} disabled={busy}>취소</Button>
        <Button variant="primary" onClick={() => void handleGenerate()} disabled={busy || !from || !to}>
          {busy ? '생성 중…' : 'PDF 생성 → 다운로드'}
        </Button>
      </>}
    >
      <div className="grid-2" style={{ gap: 12 }}>
        <Field label="시작일" htmlFor="report-from">
          <input id="report-from" className="input mono" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </Field>
        <Field label="종료일" htmlFor="report-to">
          <input id="report-to" className="input mono" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </Field>
      </div>
      <div style={{ border: '1px solid var(--line)', borderRadius: 'var(--r)', overflow: 'hidden' }}>
        <div style={{ padding: '10px 14px', background: 'var(--canvas)', borderBottom: '1px solid var(--line-soft)', fontSize: 12, fontWeight: 600, color: 'var(--mute)' }}>
          {`포함 tenant · ${overview.tenants.length}개`}
        </div>
        <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          {overview.tenants.map((c) => (
            <li key={c.tenantId ?? 'platform'} className="row" style={{ gap: 10, flexWrap: 'nowrap', padding: '10px 14px', borderBottom: '1px solid var(--line-soft)', fontSize: 13 }}>
              <span className="truncate" style={{ flex: '1 1 0' }} title={c.tenantName}>{c.tenantName}</span>
              <span className="mono muted" style={{ fontSize: 12 }}>{`${fmt(c.verifiedRows)} rows`}</span>
              <Badge tone={c.intact ? 'ok' : 'bad'}>{statusLabel(c.intact ? 'INTACT' : 'TAMPERED')}</Badge>
            </li>
          ))}
        </ul>
      </div>
    </Dialog>
  );
}

// ── AuditChainPage ────────────────────────────────────────────────────────────

const PAGE_TITLE = '감사 체인 모니터';
// 스케줄러 없음: overview 호출 시마다 서버가 전체 tenant chain 을 검증한다(AuditChainMonitorController.overview).
const PAGE_SUB = "tenant별 audit hash chain 무결성 — 화면을 열 때와 '새로 검증'·'전체 즉시 검증'을 누를 때 서버가 검증합니다.";

export default function AuditChainPage() {
  const [overview, setOverview] = useState<ChainOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showReport, setShowReport] = useState(false);
  const [running, setRunning] = useState(false);
  const [incidents, setIncidents] = useState<IncidentView[]>([]);
  const [incidentError, setIncidentError] = useState(false);
  const [creating, setCreating] = useState(false);
  const [confirmCreate, setConfirmCreate] = useState(false);
  const [resolving, setResolving] = useState<IncidentView | null>(null);
  const [resolveNote, setResolveNote] = useState('');
  const [resolveBusy, setResolveBusy] = useState(false);
  const [view, setView] = useState<'all' | 'tampered'>('all');
  const [verifyingId, setVerifyingId] = useState<string | null>(null);
  const navigate = useNavigate();
  const toast = useToast();

  // Incident 목록만 재조회(페이지 전체 spinner 없이). 실패를 "비어있음"으로
  // 오인시키면 운영자가 실제 OPEN incident(위변조)를 놓치므로, 실패 시 기존
  // incidents 를 빈 배열로 덮지 않고 에러 플래그만 세운다.
  async function refreshIncidents() {
    try {
      const list = await auditChainMonitorApi.listIncidents();
      setIncidents(list);
      setIncidentError(false);
    } catch {
      setIncidentError(true);
    }
  }

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const o = await auditChainMonitorApi.overview(24);
      setOverview(o);
      // Incident 목록 로드 실패는 페이지 전체를 막지 않는다(독립 처리).
      await refreshIncidents();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'overview 로드 실패';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  async function handleRunAll() {
    setRunning(true);
    try {
      const r = await auditChainMonitorApi.backfill();
      toast({ kind: 'ok', title: '백필 완료', message: `${r.tenantsProcessed} tenants · ${r.rowsUpdated} updated` });
      await load();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '백필 실패';
      toast({ kind: 'err', title: '백필 실패', message: msg });
    } finally {
      setRunning(false);
    }
  }

  function openTenant(tenantId: string | null) {
    if (tenantId) navigate(`/tenants/${tenantId}`);
  }

  async function handleVerifyTenant(tenantId: string) {
    setVerifyingId(tenantId);
    try {
      const r = await auditChainApi.verifyTenant(tenantId);
      toast(r.intact
        ? { kind: 'ok', title: '검증 완료', message: 'chain 이 정상입니다.' }
        : { kind: 'err', title: '위변조 의심', message: r.tamperedEntryId ? `불일치 entry ${r.tamperedEntryId}` : 'hash 불일치' });
      await load();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '검증 실패';
      toast({ kind: 'err', title: '검증 실패', message: msg });
    } finally {
      setVerifyingId(null);
    }
  }

  async function handleCreateIncident(tenantId: string) {
    setCreating(true);
    try {
      await auditChainMonitorApi.createIncident(tenantId);
      setConfirmCreate(false);
      toast({ kind: 'ok', title: 'Incident 생성됨', message: '보안팀 대응 incident 가 등록되었습니다.' });
      await load();
    } catch (e: unknown) {
      if (e instanceof ApiError && e.status === 409) {
        // 다른 운영자가 이미 OPEN incident 를 만든 stale 상태일 수 있다 — 닫고 목록 동기화.
        setConfirmCreate(false);
        toast({ kind: 'warn', title: '이미 진행 중', message: '해당 tenant 에 OPEN 상태 incident 가 이미 있습니다.' });
      } else if (e instanceof ApiError && e.status === 422) {
        toast({ kind: 'warn', title: '위변조 미확인', message: '재검증 결과 위변조가 확인되지 않아 incident 를 생성하지 않았습니다.' });
      } else {
        const msg = e instanceof Error ? e.message : 'incident 생성 실패';
        toast({ kind: 'err', title: 'Incident 생성 실패', message: msg });
      }
      // 어느 실패 경로든 서버 상태와 목록을 동기화(409 의 stale OPEN incident 노출).
      await refreshIncidents();
    } finally {
      setCreating(false);
    }
  }

  async function handleResolveIncident() {
    if (!resolving || !resolveNote.trim()) return;
    setResolveBusy(true);
    try {
      await auditChainMonitorApi.resolveIncident(resolving.id, resolveNote.trim());
      setResolving(null);
      setResolveNote('');
      toast({ kind: 'ok', title: '해결됨', message: 'incident 가 RESOLVED 로 표시되었습니다.' });
      await load();
    } catch (e: unknown) {
      if (e instanceof ApiError && e.status === 409) {
        // 이미 RESOLVED — stale 다이얼로그를 닫고 목록 동기화.
        setResolving(null);
        setResolveNote('');
        toast({ kind: 'warn', title: '이미 해결됨', message: '해당 incident 는 이미 RESOLVED 상태입니다.' });
      } else if (e instanceof ApiError && e.status === 404) {
        setResolving(null);
        setResolveNote('');
        toast({ kind: 'warn', title: '대상 없음', message: 'incident 를 찾을 수 없습니다.' });
      } else {
        const msg = e instanceof Error ? e.message : 'incident 해결 실패';
        toast({ kind: 'err', title: '해결 실패', message: msg });
      }
      await refreshIncidents();
    } finally {
      setResolveBusy(false);
    }
  }

  if (loading) {
    return (
      <div className="page">
        <PageHeader title={PAGE_TITLE} sub={PAGE_SUB} />
        <Card><div className="empty"><p className="empty__sub">불러오는 중…</p></div></Card>
      </div>
    );
  }

  if (error || !overview) {
    return (
      <div className="page">
        <PageHeader title={PAGE_TITLE} sub={PAGE_SUB} />
        <Card>
          <EmptyState
            illustration={illustrations.loadError}
            title="감사 체인 상태를 불러오지 못했습니다"
            description={error ?? undefined}
            action={<Button icon={<Icons.Refresh size={14} />} onClick={() => void load()}>다시 시도</Button>}
          />
        </Card>
      </div>
    );
  }

  const { totals, tenants } = overview;
  const tamperedTenant = tenants.find((c) => !c.intact) ?? null;
  const tampered = totals.tenantsTampered > 0;
  const showStrip = tenants.some((c) => c.recentRuns !== undefined);
  const rows = view === 'tampered' ? tenants.filter((c) => !c.intact) : tenants;
  const openCount = incidents.filter((i) => i.status === 'OPEN').length;
  const headline = tampered
    ? `${totals.tenantsTampered}개 tenant 위변조 의심 · ${totals.tenantsIntact} / ${totals.tenantsTotal} 정상`
    : `모든 tenant 정상 · ${totals.tenantsIntact} / ${totals.tenantsTotal}`;
  const lastLine = [
    `마지막 검증 ${timeAgo(overview.verifiedAt)}`,
    overview.nextRunAt !== undefined ? `다음 검증 ${timeUntil(overview.nextRunAt)}` : null,
  ].filter(Boolean).join(' · ');

  const chainColumns: Column<ChainRow>[] = [
    {
      key: 'tenant', header: 'Tenant',
      render: (c) => (
        <div className="row" style={{ gap: 10, flexWrap: 'nowrap', minWidth: 0 }}>
          <span className="avatar avatar--sq avatar--sm" aria-hidden="true">{c.tenantName.slice(0, 1).toUpperCase()}</span>
          <strong className="truncate" style={{ fontSize: 13, maxWidth: 260 }} title={c.tenantName}>{c.tenantName}</strong>
        </div>
      ),
    },
    { key: 'status', header: '상태', render: (c) => <Badge tone={c.intact ? 'ok' : 'bad'}>{statusLabel(c.intact ? 'INTACT' : 'TAMPERED')}</Badge> },
    { key: 'rows', header: '검증 행 수', align: 'right', render: (c) => fmt(c.verifiedRows) },
    { key: 'verifiedAt', header: '마지막 검증', render: () => <span className="muted">{timeAgo(overview.verifiedAt)}</span> },
    ...(showStrip ? [{
      key: 'strip',
      header: `최근 ${STRIP_RUNS}회`,
      render: (c: ChainRow) => {
        if (!c.recentRuns || c.recentRuns.length === 0) return null;
        const last = c.recentRuns.slice(-STRIP_RUNS);
        return <StatusStrip results={last.map((r) => r.ok)} label={stripLabel(last)} />;
      },
    }] : []),
    {
      key: 'actions', header: '액션',
      render: (c) => (
        <div className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
          <Button size="sm" onClick={() => openTenant(c.tenantId)} disabled={!c.tenantId}>열기</Button>
          <Button size="sm" onClick={() => { if (c.tenantId) void handleVerifyTenant(c.tenantId); }} disabled={!c.tenantId || verifyingId === c.tenantId}>
            {verifyingId === c.tenantId ? '검증 중…' : '검증'}
          </Button>
        </div>
      ),
    },
  ];

  const incidentColumns: Column<IncidentView>[] = [
    { key: 'status', header: '상태', render: (i) => <Badge tone={i.status === 'OPEN' ? 'warn' : 'ok'}>{statusLabel(i.status)}</Badge> },
    { key: 'tenant', header: 'Tenant', render: (i) => <strong style={{ fontSize: 13 }}>{i.tenantName}</strong> },
    { key: 'type', header: '유형', render: (i) => <span className="tag">{i.type}</span> },
    { key: 'createdAt', header: '생성시각', render: (i) => <span className="muted">{kstDateTime(i.createdAt).slice(0, 16)}</span> },
    { key: 'createdBy', header: '생성자', render: (i) => <span className="muted">{i.createdByEmail}</span> },
    {
      key: 'actions', header: '액션',
      render: (i) => i.status === 'OPEN'
        ? <Button size="sm" icon={<Icons.Check size={14} />} onClick={() => { setResolving(i); setResolveNote(''); }}>해결</Button>
        : null,
    },
  ];

  return (
    <div className="page">
      <PageHeader
        title={PAGE_TITLE}
        sub={PAGE_SUB}
        actions={<>
          <Button icon={<Icons.Download size={14} />} onClick={() => setShowReport(true)}>월간 보고서</Button>
          <Button variant="primary" icon={<Icons.Hash size={14} />} onClick={() => void handleRunAll()} disabled={running}>
            {running ? '검증 중…' : '전체 즉시 검증'}
          </Button>
        </>}
      />

      <section className="card" aria-label="전체 상태" style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 20, padding: '20px 24px' }}>
        <span aria-hidden="true" style={{ width: 48, height: 48, borderRadius: 12, flex: 'none', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', background: tampered ? 'var(--bad-soft)' : 'var(--ok-soft)', color: tampered ? 'var(--bad)' : 'var(--ok)' }}>
          <Icons.Link size={24} />
        </span>
        <div className="col" style={{ gap: 2, flex: '1 1 320px', minWidth: 0 }}>
          <span className="t-over">전체 상태</span>
          <strong style={{ fontSize: 20, lineHeight: '28px' }}>{headline}</strong>
          <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{lastLine}</span>
        </div>
        <Button size="sm" icon={<Icons.Refresh size={14} />} onClick={() => void load()}>새로 검증</Button>
      </section>

      {tamperedTenant && (
        <Alert
          tone="bad"
          title="위변조 의심 — 즉시 조사 필요"
          actions={<>
            <Button size="sm" onClick={() => openTenant(tamperedTenant.tenantId)} disabled={!tamperedTenant.tenantId}>
              tenant 열기 <Icons.ChevronRight size={12} />
            </Button>
            <Button
              size="sm"
              variant="danger-solid"
              onClick={() => setConfirmCreate(true)}
              disabled={!tamperedTenant.tenantId}
              title={tamperedTenant.tenantId ? undefined : '플랫폼 체인은 incident 대상이 아닙니다'}
            >
              Incident 생성
            </Button>
          </>}
        >
          {`${tamperedTenant.tenantName} chain 에서 hash 불일치가 감지되었습니다.`}
          {tamperedTenant.tamperedEntryId && ` 불일치 entry …${tamperedTenant.tamperedEntryId.slice(-8)}`}
        </Alert>
      )}

      <div className="kpis">
        <KpiCard label="정상 / 전체" value={`${totals.tenantsIntact} / ${totals.tenantsTotal}`} />
        <KpiCard label="검증된 audit row" value={fmt(totals.verifiedRows)} />
        <KpiCard
          label="검증 방식"
          value={overview.nextRunAt === undefined ? '요청 시' : '자동'}
          hint={overview.nextRunAt === undefined ? '화면 진입 · 새로 검증 · 전체 즉시 검증' : `다음 검증 ${kstDateTime(overview.nextRunAt)} KST`}
        />
        <KpiCard label="검증 소요" value={`${fmt(totals.verificationMs)}ms`} />
      </div>

      <Card>
        <CardHeader
          title="Tenant별 Chain 상태"
          sub={`${timeAgo(overview.verifiedAt)} 검증 기준${showStrip ? ` · 스트립은 최근 ${STRIP_RUNS}회 검증` : ''}`}
          actions={
            <Segmented<'all' | 'tampered'>
              ariaLabel="상태 필터"
              value={view}
              onChange={setView}
              options={[{ value: 'all', label: '전체' }, { value: 'tampered', label: '위변조만' }]}
            />
          }
        />
        <DataTable
          columns={chainColumns}
          rows={rows}
          rowKey={(c) => c.tenantId ?? 'platform'}
          empty={
            <p className="muted" style={{ margin: 0, padding: '24px 16px', textAlign: 'center', fontSize: 13 }}>
              {view === 'tampered' ? '위변조 의심 tenant 가 없습니다.' : '검증할 tenant 가 없습니다.'}
            </p>
          }
        />
      </Card>

      <Card>
        <CardHeader
          title="보안 Incident"
          sub="위변조 감지 시 등록되는 대응 incident"
          actions={openCount > 0 ? <Badge tone="warn">{`${statusLabel('OPEN')} ${openCount}`}</Badge> : undefined}
        />
        {incidentError && (
          <div className="card__body">
            <Alert
              tone="bad"
              title="Incident 목록을 불러오지 못했습니다."
              actions={<Button size="sm" icon={<Icons.Refresh size={14} />} onClick={() => void refreshIncidents()}>다시 시도</Button>}
            >
              {incidents.length > 0 ? '아래는 마지막으로 조회된 목록입니다.' : undefined}
            </Alert>
          </div>
        )}
        {!incidentError && incidents.length === 0 ? (
          <EmptyState
            compact
            illustration={illustrations.allClear}
            title="등록된 incident가 없습니다"
            description={tampered ? '위변조 의심 tenant 는 위 배너에서 Incident 를 생성하세요.' : '모든 chain 이 정상입니다.'}
          />
        ) : incidents.length > 0 ? (
          <DataTable columns={incidentColumns} rows={incidents} rowKey={(i) => i.id} />
        ) : null}
      </Card>

      <Dialog
        open={confirmCreate}
        onClose={() => { if (!creating) setConfirmCreate(false); }}
        title="Incident 생성"
        footer={<>
          <Button onClick={() => setConfirmCreate(false)} disabled={creating}>취소</Button>
          <Button
            variant="danger-solid"
            onClick={() => { if (tamperedTenant?.tenantId) void handleCreateIncident(tamperedTenant.tenantId); }}
            disabled={creating || !tamperedTenant?.tenantId}
          >
            {creating ? '생성 중…' : 'Incident 생성'}
          </Button>
        </>}
      >
        <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>
          {`${tamperedTenant?.tenantName ?? '—'} 의 위변조 의심을 보안 incident 로 등록하고 대응 이력을 남깁니다.`}
        </p>
        <p className="hint" style={{ margin: 0 }}>
          생성 시점에 서버가 재검증해 위변조 entry 를 기록합니다. 이미 진행 중인 incident 가 있거나 재검증 결과 위변조가 아니면 생성되지 않습니다.
        </p>
      </Dialog>

      <Dialog
        open={resolving !== null}
        onClose={() => { if (!resolveBusy) { setResolving(null); setResolveNote(''); } }}
        title="Incident 해결"
        sub={resolving ? `${resolving.tenantName} · ${resolving.type}` : undefined}
        footer={<>
          <Button onClick={() => { setResolving(null); setResolveNote(''); }} disabled={resolveBusy}>취소</Button>
          <Button
            variant="primary"
            onClick={() => void handleResolveIncident()}
            disabled={resolveBusy || resolveNote.trim().length === 0}
          >
            {resolveBusy ? '처리 중…' : '해결로 표시'}
          </Button>
        </>}
      >
        <Field label="해결 메모" htmlFor="resolve-note" required help="필수 · 감사 로그에 함께 기록됩니다.">
          <textarea
            id="resolve-note"
            className="input"
            rows={3}
            maxLength={1024}
            aria-required="true"
            value={resolveNote}
            onChange={(e) => setResolveNote(e.target.value)}
            placeholder="원인 · 조치 내용"
          />
        </Field>
      </Dialog>

      <MonthlyReportDialog open={showReport} onClose={() => setShowReport(false)} overview={overview} />
    </div>
  );
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `npx vitest run src/pages/AuditChainPage.test.tsx`
Expected: PASS (11).

- [ ] **Step 6: 문구 회귀 점검**

Run: `grep -n "60초\|60s\|scheduler" src/pages/AuditChainPage.tsx || echo "clean"`
Expected: `clean`.

- [ ] **Step 7: 전체 검증**

Run: `npm test && npm run lint && npm run build`
Expected: 전부 PASS(Sidebar 테스트는 `ChainOverview` 기존 필드만 쓰므로 영향 없음), lint error 0, 빌드 성공.

- [ ] **Step 8: 커밋**

```bash
git add src/api/auditChainMonitor.ts src/pages/AuditChainPage.tsx src/pages/AuditChainPage.test.tsx
git commit -m "feat(admin-ui): 감사 체인 모니터 V2 — 전체 상태 헤드라인·위변조 배너·스트립(recentRuns)·Incident 빈 상태, 요청 시 검증 문구

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 19: License 페이지

V2 아트보드: `v2/License.dc.html` (배너는 Task 12 에서 이미 V2 로 바꿈 — 이 태스크에서 `LicenseBanner.tsx` 는 건드리지 않는다).

**Files:**
- Rewrite: `admin-ui/src/pages/LicensePage.tsx`
- Create: `admin-ui/src/pages/LicensePage.test.tsx`

**Interfaces:**
- Consumes: `licenseApi.get(): Promise<LicenseView>` (`deploymentMode`, `state: LicenseState | null`, `sub`, `jti`, `expiresAt`, `daysUntilExpiry`, `features`, `lastVerifiedAt`, `graceRemainingHours`, `nextHeartbeatAt`) / `formatDateTime`(`@/lib/formatDateTime`) / Task 5 `PageHeader`, `Card`, `CardHeader`, `Badge`, `type BadgeTone`, `CopyBtn`, `Alert`.
- Produces: `LicensePage()` default export — 변경 없음. 루트 `className="page"`.

**만료 진행 바 결정:** 그리지 않는다. `LicenseView` 에는 발급일(`iat`)이나 전체 유효 기간이 없어 "남은 일수 / 전체 일수" 비율을 만들 수 없다. 임의 기준(예: 365일)을 쓰면 실제와 다른 비율을 보여주게 된다. 스펙 §6 원칙(없는 값은 그리지 않음)에 맞춰 `만료까지 {N}일` 텍스트와 `Expires at {일시}` 만 표시한다. 서버가 발급일을 주면 그때 바를 추가한다.

상태 배지(색 + 라벨): `VALID`→ok `정상`, `WARNING`→warn `만료 임박`, `NETWORK_GRACE`→warn `서버 연결 불가`, `DEAD`→bad `만료`. `만료까지` 일수 색은 WARNING=`--warn`, DEAD=`--bad`.

유지: 오류(`Error: {err}`)·로딩(`Loading…`)·SaaS(`License management is on-prem only.`) 분기와 문구, 상세 항목 8개(Grace remaining 은 `graceRemainingHours !== null` 일 때만), 값이 없을 때의 기존 표기(`(none)`, `(unknown)`, `(never)`, `(none scheduled)`). 날짜는 원시 ISO 대신 `formatDateTime`(KST)으로 표시.

- [ ] **Step 1: 실패하는 테스트 작성**

```tsx
// admin-ui/src/pages/LicensePage.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { LicenseView } from '@/api/license';

vi.mock('@/api/license', () => ({ licenseApi: { get: vi.fn() } }));

import { licenseApi } from '@/api/license';
import LicensePage from './LicensePage';

const NOW = new Date('2026-10-11T03:00:00Z');
const base: LicenseView = {
  deploymentMode: 'onprem',
  state: 'VALID',
  sub: 'acme-bank',
  jti: 'lic_01J9ZQ8V7K3M',
  expiresAt: '2027-04-29T00:00:00+09:00',
  daysUntilExpiry: 200,
  features: ['mds', 'audit-export'],
  lastVerifiedAt: new Date(NOW.getTime() - 5 * 60_000).toISOString(),
  graceRemainingHours: null,
  nextHeartbeatAt: new Date(NOW.getTime() + 55 * 60_000).toISOString(),
};

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  vi.mocked(licenseApi.get).mockReset();
});
afterEach(() => vi.useRealTimers());

describe('LicensePage V2', () => {
  it('상태 헤드라인: State + 배지 + 마지막 검증/다음 heartbeat 상대 시간', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    const { container } = render(<LicensePage />);
    expect(await screen.findByRole('heading', { name: 'License' })).toBeInTheDocument();
    expect(container.firstElementChild).toHaveClass('page');
    expect(screen.getByText('VALID')).toBeInTheDocument();
    expect(screen.getByText('정상')).toHaveClass('badge', 'badge--success');
    expect(screen.getByText('Last verified 5분 전 · Next heartbeat 55분 후')).toBeInTheDocument();
  });

  it('만료까지 일수만 표시하고 진행 바는 그리지 않는다(전체 기간 데이터 없음)', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    render(<LicensePage />);
    expect(await screen.findByText('만료까지')).toBeInTheDocument();
    expect(screen.getByText('200일')).toBeInTheDocument();
    expect(screen.getByText(/^Expires at /)).toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).toBeNull();
  });

  it('상세 카드: dl 항목, jti 복사 버튼, feature 태그, grace 없으면 숨김', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    const { container } = render(<LicensePage />);
    expect(await screen.findByRole('heading', { name: '상세' })).toBeInTheDocument();
    const terms = Array.from(container.querySelectorAll('dl dt')).map((d) => d.textContent);
    expect(terms).toEqual(['Customer', 'License ID (jti)', 'Expires at', 'Days until expiry', 'Features', 'Last verified at', 'Next heartbeat at']);
    expect(screen.getByText('acme-bank')).toBeInTheDocument();
    expect(screen.getByText('lic_01J9ZQ8V7K3M')).toHaveClass('mono');
    expect(screen.getByRole('button', { name: 'License ID 복사' })).toBeInTheDocument();
    expect(screen.getByText('audit-export')).toHaveClass('tag');
    expect(screen.queryByText('Grace remaining')).toBeNull();
  });

  it('NETWORK_GRACE: 경고 배지와 Grace remaining 행', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, state: 'NETWORK_GRACE', graceRemainingHours: 12 });
    render(<LicensePage />);
    expect(await screen.findByText('서버 연결 불가')).toHaveClass('badge--warning');
    expect(screen.getByText('Grace remaining')).toBeInTheDocument();
    expect(screen.getByText('12 hour(s)')).toBeInTheDocument();
  });

  it('값이 없으면 기존 표기, 상대 시간 줄과 jti 복사 버튼은 숨김', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, state: 'DEAD', jti: null, lastVerifiedAt: null, nextHeartbeatAt: null, features: [] });
    render(<LicensePage />);
    expect(await screen.findByText('만료')).toHaveClass('badge--danger');
    expect(screen.queryByText(/Last verified .* 전/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'License ID 복사' })).toBeNull();
    expect(screen.getByText('(never)')).toBeInTheDocument();
    expect(screen.getByText('(none scheduled)')).toBeInTheDocument();
  });

  it('SaaS 면 온프렘 전용 안내', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, deploymentMode: 'saas', state: null });
    render(<LicensePage />);
    expect(await screen.findByText('License management is on-prem only.')).toBeInTheDocument();
  });

  it('조회 실패는 오류 배너', async () => {
    vi.mocked(licenseApi.get).mockRejectedValue(new Error('boom'));
    render(<LicensePage />);
    expect(await screen.findByRole('alert')).toHaveTextContent('boom');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/LicensePage.test.tsx`
Expected: FAIL — `정상` 배지·`만료까지`·`상세` 제목·`License ID 복사` 버튼 없음, dt 가 `Customer (sub)`/`State` 포함.

- [ ] **Step 3: 구현**

```tsx
// admin-ui/src/pages/LicensePage.tsx
import { Fragment, ReactNode, useEffect, useState } from 'react';
import { licenseApi, type LicenseState, type LicenseView } from '@/api/license';
import { formatDateTime } from '@/lib/formatDateTime';
import { Alert, Badge, Card, CardHeader, CopyBtn, PageHeader, type BadgeTone } from '@/ui';

const STATE_BADGE: Record<LicenseState, { tone: BadgeTone; label: string }> = {
  VALID: { tone: 'ok', label: '정상' },
  WARNING: { tone: 'warn', label: '만료 임박' },
  NETWORK_GRACE: { tone: 'warn', label: '서버 연결 불가' },
  DEAD: { tone: 'bad', label: '만료' },
};

const DAYS_COLOR: Partial<Record<LicenseState, string>> = { WARNING: 'var(--warn)', DEAD: 'var(--bad)' };

/** "5분 전" / "55분 후" / "3시간 전" / "2일 후" */
function relativeTime(iso: string): string {
  const diff = new Date(iso).getTime() - Date.now();
  if (isNaN(diff)) return iso;
  const future = diff > 0;
  const suffix = future ? '후' : '전';
  const mins = Math.floor(Math.abs(diff) / 60_000);
  if (mins < 1) return future ? '곧' : '방금';
  if (mins < 60) return `${mins}분 ${suffix}`;
  const hours = Math.floor(mins / 60);
  if (hours < 48) return `${hours}시간 ${suffix}`;
  return `${Math.floor(hours / 24)}일 ${suffix}`;
}

export default function LicensePage() {
  const [license, setLicense] = useState<LicenseView | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    licenseApi.get().then(setLicense).catch(e => setErr(String(e)));
  }, []);

  if (err) return <div className="page"><Alert tone="bad" title="Error">{err}</Alert></div>;
  if (!license) return <div className="page"><p className="muted" style={{ margin: 0 }}>Loading…</p></div>;
  if (license.deploymentMode !== 'onprem') {
    return <div className="page"><p className="muted" style={{ margin: 0 }}>License management is on-prem only.</p></div>;
  }

  const badge = license.state ? STATE_BADGE[license.state] : null;
  const heartbeat = [
    license.lastVerifiedAt && `Last verified ${relativeTime(license.lastVerifiedAt)}`,
    license.nextHeartbeatAt && `Next heartbeat ${relativeTime(license.nextHeartbeatAt)}`,
  ].filter(Boolean).join(' · ');

  const rows: { k: string; v: ReactNode }[] = [
    {
      k: 'Customer',
      v: license.sub ? <>{license.sub} <span className="hint" style={{ margin: 0 }}>(sub)</span></> : '(none)',
    },
    {
      k: 'License ID (jti)',
      v: license.jti ? (
        <span className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
          <span className="mono" style={{ wordBreak: 'break-all' }}>{license.jti}</span>
          <CopyBtn value={license.jti} label="License ID 복사" iconOnly />
        </span>
      ) : '(none)',
    },
    { k: 'Expires at', v: license.expiresAt ? formatDateTime(license.expiresAt) : '(unknown)' },
    { k: 'Days until expiry', v: license.daysUntilExpiry },
    {
      k: 'Features',
      v: license.features.length > 0
        ? <span className="row" style={{ gap: 6 }}>{license.features.map((f) => <span key={f} className="tag">{f}</span>)}</span>
        : '(none)',
    },
    { k: 'Last verified at', v: license.lastVerifiedAt ? formatDateTime(license.lastVerifiedAt) : '(never)' },
    { k: 'Next heartbeat at', v: license.nextHeartbeatAt ? formatDateTime(license.nextHeartbeatAt) : '(none scheduled)' },
    ...(license.graceRemainingHours !== null ? [{ k: 'Grace remaining', v: `${license.graceRemainingHours} hour(s)` }] : []),
  ];

  return (
    <div className="page">
      <PageHeader title="License" sub="온프레미스 라이선스 상태 및 상세 정보." />

      <div className="stack-4" style={{ gap: 20, maxWidth: 820 }}>
        {/* 상태 헤드라인 */}
        <Card>
          <div className="row" style={{ gap: 20, padding: '20px 24px' }}>
            <div className="col" style={{ gap: 2, flex: '1 1 240px', minWidth: 0 }}>
              <span className="t-over">State</span>
              <span className="row" style={{ gap: 10 }}>
                <strong style={{ fontSize: 20 }}>{license.state ?? '(unknown)'}</strong>
                {badge && <Badge tone={badge.tone}>{badge.label}</Badge>}
              </span>
              {heartbeat && <span className="card__sub">{heartbeat}</span>}
            </div>
            <div className="col" style={{ gap: 6, flex: '1 1 240px', minWidth: 0 }}>
              <div className="row">
                <span className="hint" style={{ margin: 0 }}>만료까지</span>
                <div className="spacer" />
                <strong style={{ fontSize: 13, color: license.state ? DAYS_COLOR[license.state] : undefined }}>{license.daysUntilExpiry}일</strong>
              </div>
              {license.expiresAt && <span className="hint" style={{ margin: 0 }}>Expires at {formatDateTime(license.expiresAt)}</span>}
            </div>
          </div>
        </Card>

        {/* 상세 */}
        <Card>
          <CardHeader title="상세" />
          <dl style={{ display: 'grid', gridTemplateColumns: 'minmax(120px, 200px) minmax(0, 1fr)', margin: 0, fontSize: 13 }}>
            {rows.map((r, i) => {
              const cell = { margin: 0, padding: '14px 20px', borderBottom: i === rows.length - 1 ? 0 : '1px solid var(--line-soft)', minWidth: 0 };
              return (
                <Fragment key={r.k}>
                  <dt style={{ ...cell, color: 'var(--mute)' }}>{r.k}</dt>
                  <dd style={cell}>{r.v}</dd>
                </Fragment>
              );
            })}
          </dl>
        </Card>
      </div>
    </div>
  );
}
```

인라인 `stateColor` 맵과 `color-mix` 배지 스타일은 삭제한다(Badge 로 대체).

- [ ] **Step 4: 테스트 통과 확인**

Run: `npx vitest run src/pages/LicensePage.test.tsx`
Expected: PASS (7개).

- [ ] **Step 5: 전체 테스트·린트·빌드**

Run: `npm test && npm run lint && npm run build`
Expected: 전체 PASS, lint 오류 0, 빌드 성공. `git diff --stat src/components/LicenseBanner.tsx` 결과 없음(배너 미변경).

- [ ] **Step 6: 수동 확인**

온프렘 모드 admin-app(`docs/dev-setup.md` 의 온프렘 라이선스 설정)으로 `npm run dev` → 사이드바 `라이선스` → `v2/License.dc.html` 과 1280px 비교: 상태 헤드라인 카드(State·배지·상대 시간 | 만료까지 N일·Expires at), 상세 카드(dl 행 구분선, jti 옆 복사 아이콘, feature 태그). 복사 아이콘 클릭 → 체크 아이콘으로 바뀜. SaaS 모드에서는 `/admin/license` 직접 진입 시 온프렘 전용 안내.

- [ ] **Step 7: 커밋**

```bash
git add src/pages/LicensePage.tsx src/pages/LicensePage.test.tsx
git commit -m "feat(admin-ui): V2 License — 상태 헤드라인·만료 일수·상세 카드·jti 복사

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 20: Tenant 상세 헤더 · 정지 다이얼로그 · 탭 점 배지 · 개요

V2 아트보드: `v2/TenantHead.dc.html`, `v2/Tenant-Overview.dc.html`, `v2/Dialogs-Tenant.dc.html`(테넌트 정지).

**Files:**
- Create: `admin-ui/src/pages/tenant/tenantSignals.ts`
- Rewrite: `admin-ui/src/pages/TenantDetailPage.tsx`, `admin-ui/src/pages/tenant/TenantOverview.tsx`
- Modify: `admin-ui/src/api/auditChainMonitor.ts`(`recentRuns?`), `admin-ui/src/api/types.ts` · `admin-ui/src/api/designTypes.ts` · `admin-ui/src/api/tenants.ts`(`credentialsDelta7d?`), `admin-ui/src/styles/components.css`(`.kv`)
- Test: `admin-ui/src/pages/tenant/tenantSignals.test.ts`, `admin-ui/src/pages/TenantDetailPage.test.tsx`, `admin-ui/src/pages/tenant/TenantOverview.test.tsx`

**Interfaces:**
- Consumes: `@/ui` 의 `Tabs`/`TabItem`, `StatusBadge`, `PropChip`, `Menu`, `Dialog`(`role="alertdialog"`, `initialFocusRef`), `Alert`, `ConfirmByTyping`, `Button`, `Card`/`CardHeader`/`CardBody`, `KpiCard`, `StatusStrip`, `useToast`, `ToastHost`(Task 5·8·9·10). `@/test/renderWithDataRouter`(Task 11). 기존 API: `tenantsApi.suspend/activate/get`, `aaguidPolicyApi.get(tenantId) → AaguidPolicy`, `apiKeysApi.list(tenantId) → ApiKey[]`, `auditChainApi.verifyTenant`, `auditChainMonitorApi.overview(24)`, `funnelApi.get(id, 7)`, `activityApi.fetch(null, undefined)`, `isPlatform(me)`.
- Produces:
  - `tenantSignals.ts`: `type ChainRun = { at: string; ok: boolean }`, `EXPIRY_WARN_DAYS`(Task 16 `@/lib/apiKeyExpiry` 의 값을 재노출), `unverifiedAaguidCount(policy: AaguidPolicy | null | undefined): number`(mode≠ANY 이고 `aaguid` 가 있는 항목 중 `mdsName == null` 개수), `hasUnverifiedAaguid(policy): boolean`, `countExpiringKeys(keys: ApiKey[], now?: number, days?: number): number`(`status==='ACTIVE'` 이고 `now < expiresAt ≤ now+days`), `pickRecentRuns(o: ChainOverview, tenantId: string): ChainRun[] | undefined`, `recentRunsLabel(runs: ChainRun[]): string`
  - `TenantDetailPage.tsx`: `TENANT_TABS: TabItem[]`, `fmtDateKst(iso): string`(KST `YYYY-MM-DD`), `TenantDetailPage` 시그니처 유지(`{ tenant, currentTab, onTabChange, me, onReload }`), default `TenantDetailRoute` 유지.
  - `TenantOverview({ tenant: Tenant; isPlatformOperator?: boolean; expiringKeyCount?: number })` — 기존 `{ tenant }` 호출과 호환.
  - 타입: `ChainOverview.tenants[].recentRuns?: { at: string; ok: boolean }[]`, `TenantView.credentialsDelta7d?: number`, `Tenant.credentialsDelta7d?: number`(스펙 §6 ②·⑤, optional).

결정 사항(현재 코드·서버 확인 결과):
- 탭 점 배지 데이터: `TenantDetailPage` 가 tenant 가 바뀔 때마다 `aaguidPolicyApi.get(tenant.id)` 와 `apiKeysApi.list(tenant.id)` 를 각각 1회 호출한다. 실패하면 조용히 점을 표시하지 않는다(헤더는 보조 신호이므로 토스트 없음).
  - AAGUID 점 = `unverifiedAaguidCount(policy) > 0` (mode `ANY` 이면 항상 없음).
  - API 키 점 = `countExpiringKeys(keys, Date.now()) > 0` (만료 30일 이내의 ACTIVE 키). 같은 값을 개요 KPI "만료 임박 N" 에 넘긴다.
- ⋯ 메뉴(테넌트 정지/정지 해제)는 **PLATFORM 에게만** 렌더한다. 서버 `TenantAdminController` 의 `/suspend`·`/activate` 가 `@PreAuthorize("hasRole('PLATFORM_OPERATOR')")` 라 RP_ADMIN 에게 보이던 기존 버튼은 항상 403 이었다.
- 정지 해제: 기존 `window.confirm('이 테넌트의 정지를 해제하시겠습니까?')` 대신 같은 의미의 `Dialog`(취소 / 정지 해제). 호출·토스트(`정지 해제됨`/`해제 실패`)·`onReload` 는 동일.
- 감사 체인 카드의 스트립: PLATFORM 일 때만 `auditChainMonitorApi.overview(24)` 를 호출해(서버가 PLATFORM 전용) `tenants[]` 에서 이 tenant 의 `recentRuns` 를 꺼낸다. 값이 `undefined` 이거나 빈 배열이면 스트립을 렌더하지 않는다. "마지막 자동 검증" 대신 실제 동작인 "마지막 검증"으로 표기(스펙 §5 감사 체인 문구).
- 개요 KPI "등록 Credential" 보조 줄 `7일 +N` 은 ★`credentialsDelta7d` 가 있을 때만.

- [ ] **Step 1: 신호 계산 헬퍼 실패 테스트**

```ts
// admin-ui/src/pages/tenant/tenantSignals.test.ts
import { describe, it, expect } from 'vitest';
import type { AaguidPolicy, ApiKey } from '@/api/designTypes';
import type { ChainOverview } from '@/api/auditChainMonitor';
import { unverifiedAaguidCount, hasUnverifiedAaguid, countExpiringKeys, pickRecentRuns, recentRunsLabel } from './tenantSignals';

const DAY = 86_400_000;
const NOW = Date.parse('2026-10-11T00:00:00Z');
const key = (over: Partial<ApiKey>): ApiKey => ({ id: 'k', prefix: 'pk_x', name: 'n', status: 'ACTIVE', createdAt: '2026-01-01T00:00:00Z', lastUsedAt: null, scopes: [], expiresAt: null, ...over });
const policy = (over: Partial<AaguidPolicy>): AaguidPolicy => ({ mode: 'ALLOWLIST', mdsStrict: false, entries: [], ...over });

describe('unverifiedAaguidCount', () => {
  it('정책이 없으면 0', () => {
    expect(unverifiedAaguidCount(null)).toBe(0);
    expect(hasUnverifiedAaguid(undefined)).toBe(false);
  });
  it('mode=ANY 면 미확인 항목이 있어도 0', () => {
    expect(unverifiedAaguidCount(policy({ mode: 'ANY', entries: [{ aaguid: 'a', note: null, mdsName: null }] }))).toBe(0);
  });
  it('ALLOWLIST/DENYLIST 에서 mdsName == null 항목 수', () => {
    const entries = [
      { aaguid: 'a', note: null, mdsName: null },
      { aaguid: 'b', note: null, mdsName: 'YubiKey 5' },
      { aaguid: 'c', note: null, mdsName: undefined as unknown as null },
    ];
    expect(unverifiedAaguidCount(policy({ mode: 'ALLOWLIST', entries }))).toBe(2);
    expect(hasUnverifiedAaguid(policy({ mode: 'DENYLIST', entries }))).toBe(true);
  });
  it('aaguid 가 빈 항목은 세지 않는다(목록에 표시되지 않으므로)', () => {
    expect(unverifiedAaguidCount(policy({ entries: [{ aaguid: '', note: null, mdsName: null }] }))).toBe(0);
  });
});

describe('countExpiringKeys', () => {
  it('30일 이내 만료 ACTIVE 키만 센다(경계 30일 포함)', () => {
    const keys = [
      key({ id: '1', expiresAt: new Date(NOW + 10 * DAY).toISOString() }),
      key({ id: '2', expiresAt: new Date(NOW + 30 * DAY).toISOString() }),
      key({ id: '3', expiresAt: new Date(NOW + 31 * DAY).toISOString() }),
      key({ id: '4', expiresAt: null }),
      key({ id: '5', status: 'REVOKED', expiresAt: new Date(NOW + 5 * DAY).toISOString() }),
      key({ id: '6', status: 'EXPIRED', expiresAt: new Date(NOW - DAY).toISOString() }),
      key({ id: '7', expiresAt: new Date(NOW - 1000).toISOString() }),
    ];
    expect(countExpiringKeys(keys, NOW)).toBe(2);
  });
  it('기간을 바꿀 수 있다', () => {
    expect(countExpiringKeys([key({ expiresAt: new Date(NOW + 10 * DAY).toISOString() })], NOW, 7)).toBe(0);
  });
});

describe('pickRecentRuns / recentRunsLabel', () => {
  const base: ChainOverview = {
    verifiedAt: '2026-10-11T00:00:00Z', windowHours: 24, bucketSizeMinutes: 60,
    totals: { tenantsIntact: 1, tenantsTotal: 1, tenantsTampered: 0, verifiedRows: 10, verificationMs: 5 },
    tenants: [{ tenantId: 't-1', tenantName: 'Acme', intact: true, verifiedRows: 10, buckets: [], tamperedEntryId: null }],
  };
  it('서버가 필드를 주지 않으면 undefined', () => {
    expect(pickRecentRuns(base, 't-1')).toBeUndefined();
  });
  it('tenant 가 없으면 undefined', () => {
    expect(pickRecentRuns(base, 'nope')).toBeUndefined();
  });
  it('필드가 있으면 그대로 반환', () => {
    const runs = [{ at: 'a', ok: true }, { at: 'b', ok: false }];
    const o: ChainOverview = { ...base, tenants: [{ ...base.tenants[0], recentRuns: runs }] };
    expect(pickRecentRuns(o, 't-1')).toEqual(runs);
  });
  it('요약 라벨', () => {
    expect(recentRunsLabel([{ at: 'a', ok: true }, { at: 'b', ok: true }])).toBe('최근 2회 검증 모두 정상');
    expect(recentRunsLabel([{ at: 'a', ok: true }, { at: 'b', ok: false }, { at: 'c', ok: true }])).toBe('최근 3회 검증 중 1회 위변조');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/tenant/tenantSignals.test.ts`
Expected: FAIL — `./tenantSignals` 모듈 없음.

- [ ] **Step 3: optional 타입 추가 (스펙 §6)**

먼저 다른 태스크가 이미 추가했는지 확인한다:

```bash
grep -n "recentRuns" src/api/auditChainMonitor.ts
grep -n "credentialsDelta7d" src/api/types.ts src/api/designTypes.ts src/api/tenants.ts
```

출력이 없는 항목만 다음과 같이 추가한다.

`src/api/auditChainMonitor.ts` — `ChainOverview.tenants[]` 의 `tamperedEntryId: string | null;` 바로 아래:

```ts
    /** ② 감사 체인 이력(후속 서버 작업). 없으면 스트립을 렌더하지 않는다. */
    recentRuns?: { at: string; ok: boolean }[];
```

`src/api/types.ts` — `TenantView` 의 `lastEventAt: string | null;` 바로 아래:

```ts
  /** ⑤ 지표 보강(후속 서버 작업). 최근 7일 credential 증감. */
  credentialsDelta7d?: number;
```

`src/api/designTypes.ts` — `Tenant` 의 `webauthnTimeoutMs: number;` 바로 아래:

```ts
  credentialsDelta7d?: number;
```

`src/api/tenants.ts` — `adaptTenant` 반환 객체의 `webauthnTimeoutMs: s.webauthnTimeoutMs,` 바로 아래:

```ts
    credentialsDelta7d: s.credentialsDelta7d,
```

- [ ] **Step 4: tenantSignals 구현**

```ts
// admin-ui/src/pages/tenant/tenantSignals.ts
// Tenant 상세 헤더의 탭 점 배지·개요 KPI 가 쓰는 순수 계산 함수.
import type { AaguidPolicy, ApiKey } from '@/api/designTypes';
import type { ChainOverview } from '@/api/auditChainMonitor';
import { EXPIRY_WARN_DAYS } from '@/lib/apiKeyExpiry'; // Task 16 에서 생성

export { EXPIRY_WARN_DAYS };
export type ChainRun = { at: string; ok: boolean };

const DAY_MS = 86_400_000;

/** 목록(mode≠ANY)에 보이는 항목 중 MDS 에서 모델을 찾지 못한(mdsName == null) AAGUID 수. */
export function unverifiedAaguidCount(policy: AaguidPolicy | null | undefined): number {
  if (!policy || policy.mode === 'ANY') return 0;
  return policy.entries.filter((e) => e.aaguid && e.mdsName == null).length;
}

export function hasUnverifiedAaguid(policy: AaguidPolicy | null | undefined): boolean {
  return unverifiedAaguidCount(policy) > 0;
}

/** ACTIVE 이고 지금부터 days 일 이내(경계 포함)에 만료되는 키 수. */
export function countExpiringKeys(keys: ApiKey[], now: number = Date.now(), days: number = EXPIRY_WARN_DAYS): number {
  const limit = now + days * DAY_MS;
  return keys.filter((k) => {
    if (k.status !== 'ACTIVE' || !k.expiresAt) return false;
    const t = new Date(k.expiresAt).getTime();
    return Number.isFinite(t) && t > now && t <= limit;
  }).length;
}

/** ChainOverview 에서 이 tenant 의 최근 검증 이력. 서버가 필드를 주지 않으면 undefined. */
export function pickRecentRuns(o: ChainOverview, tenantId: string): ChainRun[] | undefined {
  return o.tenants.find((t) => t.tenantId === tenantId)?.recentRuns;
}

export function recentRunsLabel(runs: ChainRun[]): string {
  const bad = runs.filter((r) => !r.ok).length;
  return bad === 0 ? `최근 ${runs.length}회 검증 모두 정상` : `최근 ${runs.length}회 검증 중 ${bad}회 위변조`;
}
```

- [ ] **Step 5: 헬퍼 테스트 통과 확인**

Run: `npx vitest run src/pages/tenant/tenantSignals.test.ts`
Expected: PASS (10 tests).

- [ ] **Step 6: 상세 페이지·개요 실패 테스트 작성**

```tsx
// admin-ui/src/pages/TenantDetailPage.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within, cleanup, act, waitFor } from '@testing-library/react';
import { ToastHost } from '@/ui';
import type { Tenant } from '@/api/designTypes';
import type { Me } from '@/api/types';

vi.mock('@/api/tenants', () => ({ tenantsApi: { get: vi.fn(), suspend: vi.fn(), activate: vi.fn() } }));
vi.mock('@/api/aaguidPolicy', () => ({ aaguidPolicyApi: { get: vi.fn(), update: vi.fn() } }));
vi.mock('@/api/apiKeys', () => ({ apiKeysApi: { list: vi.fn() } }));
vi.mock('@/pages/tenant/TenantOverview', () => ({
  default: (p: { expiringKeyCount?: number; isPlatformOperator?: boolean }) => <div>OVERVIEW expiring={String(p.expiringKeyCount)} platform={String(p.isPlatformOperator)}</div>,
}));
vi.mock('@/pages/tenant/WebauthnConfigTab', () => ({ default: () => <div>WEBAUTHN TAB</div> }));
vi.mock('@/pages/tenant/AaguidPolicyTab', () => ({ default: () => <div>AAGUID TAB</div> }));
vi.mock('@/pages/tenant/ApiKeysTab', () => ({ default: () => <div>APIKEYS TAB</div> }));
vi.mock('@/pages/tenant/CredentialsTab', () => ({ default: () => <div>CREDENTIALS TAB</div> }));
vi.mock('@/pages/tenant/AuditTab', () => ({ default: (p: { isPlatformOperator: boolean }) => <div>AUDIT TAB platform={String(p.isPlatformOperator)}</div> }));
vi.mock('@/pages/tenant/FunnelTab', () => ({ default: () => <div>FUNNEL TAB</div> }));

import { tenantsApi } from '@/api/tenants';
import { aaguidPolicyApi } from '@/api/aaguidPolicy';
import { apiKeysApi } from '@/api/apiKeys';
import { TenantDetailPage, fmtDateKst } from './TenantDetailPage';

const DAY = 86_400_000;
const T: Tenant = {
  id: 't-1', name: 'Acme', slug: 'acme', rpId: 'passkey.acme.com', status: 'ACTIVE', credentials: 10, apiKeys: 3,
  lastEventAt: null, createdAt: '2026-03-01T16:00:00Z', userVerification: 'REQUIRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const PLATFORM: Me = { email: 'op@x.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: true, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const RP: Me = { ...PLATFORM, email: 'rp@x.com', role: 'RP_ADMIN', tenantIds: ['t-1'] };

function renderPage({ over = {}, me = PLATFORM, tab = 'overview' }: { over?: Partial<Tenant>; me?: Me; tab?: string } = {}) {
  const onReload = vi.fn();
  const onTabChange = vi.fn();
  render(<ToastHost><TenantDetailPage tenant={{ ...T, ...over }} currentTab={tab} onTabChange={onTabChange} me={me} onReload={onReload} /></ToastHost>);
  return { onReload, onTabChange };
}

beforeEach(() => {
  vi.clearAllMocks();
  (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ mode: 'ANY', mdsStrict: false, entries: [] });
  (apiKeysApi.list as ReturnType<typeof vi.fn>).mockResolvedValue([]);
  (tenantsApi.suspend as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
  (tenantsApi.activate as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
});
afterEach(cleanup);

describe('TenantDetailPage 헤더', () => {
  it('fmtDateKst 는 KST 날짜(YYYY-MM-DD)', () => {
    expect(fmtDateKst('2026-03-01T16:00:00Z')).toBe('2026-03-02');
    expect(fmtDateKst(null)).toBe('—');
  });
  it('이름·상태·RP 사이트 링크·속성 칩·탭 7개', async () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Acme' })).toBeInTheDocument();
    expect(screen.getByText('활성')).toHaveClass('badge--success');
    const link = screen.getByRole('link', { name: /RP 사이트 열기/ });
    expect(link).toHaveAttribute('href', 'https://passkey.acme.com');
    expect(link).toHaveAttribute('target', '_blank');
    expect(screen.getByRole('button', { name: 'ID 복사' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'RP ID 복사' })).toBeInTheDocument();
    expect(screen.getByText('acme')).toHaveClass('propchip__v');
    expect(screen.getByText('2026-03-02')).toBeInTheDocument();
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['개요', 'WebAuthn', 'AAGUID 정책', 'API 키', 'Credentials', '감사 로그', '퍼널']);
    await waitFor(() => expect(apiKeysApi.list).toHaveBeenCalledWith('t-1'));
    expect(aaguidPolicyApi.get).toHaveBeenCalledWith('t-1');
  });
  it('탭 클릭은 onTabChange', () => {
    const { onTabChange } = renderPage();
    fireEvent.click(screen.getByRole('tab', { name: /WebAuthn/ }));
    expect(onTabChange).toHaveBeenCalledWith('webauthn');
  });
  it('감사 로그 탭에 PLATFORM 여부를 넘긴다', () => {
    renderPage({ tab: 'audit', me: RP });
    expect(screen.getByText('AUDIT TAB platform=false')).toBeInTheDocument();
  });
});

describe('탭 점 배지', () => {
  it('AAGUID: mode≠ANY 이고 mdsName==null 항목이 있으면 점', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      mode: 'ALLOWLIST', mdsStrict: false, entries: [{ aaguid: 'a', note: null, mdsName: null }],
    });
    renderPage();
    const tab = screen.getByRole('tab', { name: /AAGUID 정책/ });
    expect(await within(tab).findByLabelText('확인 필요')).toBeInTheDocument();
    expect(within(screen.getByRole('tab', { name: /API 키/ })).queryByLabelText('확인 필요')).toBeNull();
  });
  it('API 키: 30일 이내 만료 ACTIVE 키가 있으면 점, 개요에 개수 전달', async () => {
    (apiKeysApi.list as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'k1', prefix: 'pk_a', name: 'prod', status: 'ACTIVE', createdAt: '2026-01-01T00:00:00Z', lastUsedAt: null, scopes: [], expiresAt: new Date(Date.now() + 10 * DAY).toISOString() },
      { id: 'k2', prefix: 'pk_b', name: 'old', status: 'REVOKED', createdAt: '2026-01-01T00:00:00Z', lastUsedAt: null, scopes: [], expiresAt: new Date(Date.now() + 5 * DAY).toISOString() },
    ]);
    renderPage();
    const tab = screen.getByRole('tab', { name: /API 키/ });
    expect(await within(tab).findByLabelText('확인 필요')).toBeInTheDocument();
    expect(screen.getByText('OVERVIEW expiring=1 platform=true')).toBeInTheDocument();
  });
  it('조건이 없으면 점이 없다', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('OVERVIEW expiring=0 platform=true')).toBeInTheDocument());
    expect(screen.queryByLabelText('확인 필요')).toBeNull();
  });
  it('신호 조회 실패는 조용히 무시', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('403'));
    (apiKeysApi.list as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('403'));
    renderPage();
    await waitFor(() => expect(apiKeysApi.list).toHaveBeenCalled());
    expect(screen.queryByLabelText('확인 필요')).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
  });
});

describe('테넌트 정지 / 정지 해제', () => {
  it('RP_ADMIN 에게는 ⋯ 메뉴가 없다(서버 PLATFORM 전용)', () => {
    renderPage({ me: RP });
    expect(screen.queryByRole('button', { name: '더보기' })).toBeNull();
  });
  it('정지: alertdialog, 영향 범위, 취소 기본 포커스, slug 일치 전 정지 비활성', async () => {
    const { onReload } = renderPage();
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '테넌트 정지' }));
    const dlg = screen.getByRole('alertdialog', { name: '테넌트 정지' });
    expect(within(dlg).getByText('Acme 을(를) 정지합니다.')).toBeInTheDocument();
    expect(within(dlg).getByRole('alert')).toHaveTextContent('영향 범위');
    expect(within(dlg).getByText('· 유효 API key 3개 즉시 회수')).toBeInTheDocument();
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    const go = within(dlg).getByRole('button', { name: '정지' });
    expect(go).toBeDisabled();
    const input = within(dlg).getByRole('textbox', { name: /확인을 위해 slug/ });
    fireEvent.change(input, { target: { value: 'acm' } });
    expect(go).toBeDisabled();
    fireEvent.change(input, { target: { value: 'acme ' } });
    expect(go).toBeDisabled();
    fireEvent.change(input, { target: { value: 'acme' } });
    expect(go).toBeEnabled();
    await act(async () => { fireEvent.click(go); });
    expect(tenantsApi.suspend).toHaveBeenCalledWith('t-1');
    expect(onReload).toHaveBeenCalled();
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(screen.getByText('테넌트가 정지되었습니다.')).toBeInTheDocument();
  });
  it('취소하면 다시 열 때 입력이 초기화된다', () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '테넌트 정지' }));
    fireEvent.change(screen.getByRole('textbox', { name: /확인을 위해 slug/ }), { target: { value: 'acme' } });
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '테넌트 정지' }));
    expect(screen.getByRole('textbox', { name: /확인을 위해 slug/ })).toHaveValue('');
    expect(screen.getByRole('button', { name: '정지' })).toBeDisabled();
    expect(tenantsApi.suspend).not.toHaveBeenCalled();
  });
  it('정지 해제: SUSPENDED 면 메뉴에 정지 해제 → 확인 다이얼로그 → activate', async () => {
    const { onReload } = renderPage({ over: { status: 'SUSPENDED' } });
    expect(screen.getByText('정지')).toHaveClass('badge--danger');
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    expect(screen.queryByRole('menuitem', { name: '테넌트 정지' })).toBeNull();
    fireEvent.click(screen.getByRole('menuitem', { name: '정지 해제' }));
    const dlg = screen.getByRole('dialog', { name: '정지 해제' });
    expect(within(dlg).getByText(/이 테넌트의 정지를 해제하시겠습니까\?/)).toBeInTheDocument();
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '정지 해제' })); });
    expect(tenantsApi.activate).toHaveBeenCalledWith('t-1');
    expect(onReload).toHaveBeenCalled();
    expect(screen.queryByRole('dialog', { name: '정지 해제' })).toBeNull();
  });
  it('정지 해제 취소는 호출하지 않는다', () => {
    renderPage({ over: { status: 'SUSPENDED' } });
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '정지 해제' }));
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(tenantsApi.activate).not.toHaveBeenCalled();
  });
});
```

```tsx
// admin-ui/src/pages/tenant/TenantOverview.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, fireEvent, cleanup, act, waitFor } from '@testing-library/react';
import { ToastHost } from '@/ui';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import type { Tenant } from '@/api/designTypes';

vi.mock('@/api/auditChain', () => ({ auditChainApi: { verifyTenant: vi.fn() } }));
vi.mock('@/api/auditChainMonitor', () => ({ auditChainMonitorApi: { overview: vi.fn() } }));
vi.mock('@/api/funnel', () => ({ funnelApi: { get: vi.fn() } }));
vi.mock('@/api/activity', () => ({ activityApi: { fetch: vi.fn() } }));

import { auditChainApi } from '@/api/auditChain';
import { auditChainMonitorApi } from '@/api/auditChainMonitor';
import { funnelApi } from '@/api/funnel';
import { activityApi } from '@/api/activity';
import TenantOverview from './TenantOverview';

const T: Tenant = {
  id: 't-1', name: 'Acme', slug: 'acme', rpId: 'passkey.acme.com', status: 'ACTIVE', credentials: 1234, apiKeys: 3,
  lastEventAt: null, createdAt: '2026-03-01T00:00:00Z', userVerification: 'PREFERRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const OVERVIEW = {
  verifiedAt: new Date().toISOString(), windowHours: 24, bucketSizeMinutes: 60,
  totals: { tenantsIntact: 1, tenantsTotal: 1, tenantsTampered: 0, verifiedRows: 10, verificationMs: 3 },
  tenants: [{ tenantId: 't-1', tenantName: 'Acme', intact: true, verifiedRows: 10, buckets: [], tamperedEntryId: null }],
};

function renderOverview(props: { tenant?: Tenant; isPlatformOperator?: boolean; expiringKeyCount?: number } = {}) {
  return renderWithDataRouter(
    <ToastHost><TenantOverview tenant={props.tenant ?? T} isPlatformOperator={props.isPlatformOperator} expiringKeyCount={props.expiringKeyCount} /></ToastHost>,
    { initialPath: '/tenants/t-1?tab=overview' },
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  (auditChainApi.verifyTenant as ReturnType<typeof vi.fn>).mockResolvedValue({ tenantId: 't-1', intact: true, tamperedEntryId: null, verifiedAt: new Date().toISOString() });
  (auditChainMonitorApi.overview as ReturnType<typeof vi.fn>).mockResolvedValue(OVERVIEW);
  (funnelApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
    windowDays: 7, conversion: 0, series: [], byEventType: [],
    registration: { attempts: 10, success: 9, ratio: 0.9 },
    authentication: { attempts: 200, success: 190, ratio: 0.95 },
  });
  (activityApi.fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
    kpi: {}, top5: [],
    feed: [
      { id: 'e1', action: 'CREDENTIAL_AUTHENTICATED', actorEmail: '', targetType: null, targetId: 'cred-0123456789abcdef', tenantId: 't-1', tenantSlug: 'acme', createdAt: new Date().toISOString(), category: 'ops' },
      { id: 'e2', action: 'API_KEY_ISSUED', actorEmail: 'op@x.com', targetType: null, targetId: 'k1', tenantId: 'other', tenantSlug: 'other', createdAt: new Date().toISOString(), category: 'ops' },
    ],
  });
});
afterEach(cleanup);

describe('TenantOverview', () => {
  it('KPI 4개와 요약 카드 3개', async () => {
    renderOverview({ expiringKeyCount: 2 });
    expect(screen.getByText('등록 Credential')).toBeInTheDocument();
    expect(screen.getByText('1,234')).toBeInTheDocument();
    expect(screen.getByText('만료 임박 2')).toHaveClass('kpi__hint--warn');
    expect(await screen.findByText('90.0%')).toBeInTheDocument();
    expect(screen.getByText('9 / 10 시도')).toBeInTheDocument();
    expect(screen.getByText('95.0%')).toBeInTheDocument();
    for (const h of ['WebAuthn 요약', '최근 활동', '감사 체인']) expect(screen.getByRole('heading', { name: h })).toBeInTheDocument();
    expect(screen.getByText('60000 ms')).toBeInTheDocument();
  });
  it('credentialsDelta7d 는 있을 때만 보조 줄', () => {
    const { unmount } = renderOverview({ tenant: { ...T, credentialsDelta7d: 4 } });
    expect(screen.getByText('7일 +4')).toBeInTheDocument();
    unmount();
    renderOverview();
    expect(screen.queryByText(/^7일 /)).toBeNull();
  });
  it('expiringKeyCount 가 없으면 만료 임박 줄이 없다', () => {
    renderOverview();
    expect(screen.queryByText(/만료 임박/)).toBeNull();
  });
  it('최근 활동은 이 tenant 이벤트만', async () => {
    renderOverview();
    expect(await screen.findByText('CREDENTIAL_AUTHENTICATED')).toBeInTheDocument();
    expect(screen.queryByText('API_KEY_ISSUED')).toBeNull();
  });
  it('감사 체인 무결 상태', async () => {
    renderOverview();
    expect(await screen.findByText('무결 · 위변조 0건')).toBeInTheDocument();
    expect(screen.getByText('마지막 검증 방금 전')).toBeInTheDocument();
  });
  it('위변조 상태는 bad 배너', async () => {
    (auditChainApi.verifyTenant as ReturnType<typeof vi.fn>).mockResolvedValue({ tenantId: 't-1', intact: false, tamperedEntryId: 'entry-1234567890abcdef', verifiedAt: new Date().toISOString() });
    renderOverview();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('위변조 감지');
    expect(alert).toHaveTextContent('…90abcdef');
  });
  it('RP_ADMIN: overview 를 부르지 않고 스트립이 없다', async () => {
    renderOverview({ isPlatformOperator: false });
    await screen.findByText('무결 · 위변조 0건');
    expect(auditChainMonitorApi.overview).not.toHaveBeenCalled();
    expect(screen.queryByRole('img', { name: /최근 \d+회 검증/ })).toBeNull();
  });
  it('PLATFORM 이지만 recentRuns 필드가 없으면 스트립이 없다', async () => {
    renderOverview({ isPlatformOperator: true });
    await waitFor(() => expect(auditChainMonitorApi.overview).toHaveBeenCalledWith(24));
    await screen.findByText('무결 · 위변조 0건');
    expect(screen.queryByRole('img', { name: /최근 \d+회 검증/ })).toBeNull();
  });
  it('PLATFORM + recentRuns 가 있으면 요약 라벨 스트립', async () => {
    (auditChainMonitorApi.overview as ReturnType<typeof vi.fn>).mockResolvedValue({
      ...OVERVIEW,
      tenants: [{ ...OVERVIEW.tenants[0], recentRuns: [{ at: 'a', ok: true }, { at: 'b', ok: false }, { at: 'c', ok: true }] }],
    });
    renderOverview({ isPlatformOperator: true });
    expect(await screen.findByRole('img', { name: '최근 3회 검증 중 1회 위변조' })).toBeInTheDocument();
  });
  it('수동 검증은 verifyTenant 재호출, 월간 보고서는 감사 체인 화면, 자세히는 감사 로그 탭', async () => {
    const { router } = renderOverview();
    await screen.findByText('무결 · 위변조 0건');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '수동 검증' })); });
    expect(auditChainApi.verifyTenant).toHaveBeenCalledTimes(2);
    expect(await screen.findByText('수동 검증 완료')).toBeInTheDocument();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /자세히/ })); });
    expect(router.state.location.search).toBe('?tab=audit');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '월간 보고서' })); });
    expect(router.state.location.pathname).toBe('/audit-chain');
  });
});
```

- [ ] **Step 7: 실패 확인**

Run: `npx vitest run src/pages/TenantDetailPage.test.tsx src/pages/tenant/TenantOverview.test.tsx`
Expected: FAIL — `fmtDateKst` export 없음, `더보기` 버튼·`role="tab"` 없음, `무결 · 위변조 0건` 문구 없음.

- [ ] **Step 8: `.kv` 클래스 추가**

`grep -n "^\.kv " src/styles/components.css` 결과가 없으면 `/* ── settings rows` 블록 바로 위에 추가:

```css
/* ── key-value list ─────────────────── */
.kv { display: grid; grid-template-columns: 150px minmax(0, 1fr); gap: 12px 16px; font-size: 13px; margin: 0; }
.kv dt { color: var(--mute); }
.kv dd { margin: 0; min-width: 0; }
@media (max-width: 760px) { .kv { grid-template-columns: minmax(0, 1fr); } }
```

- [ ] **Step 9: TenantDetailPage.tsx 교체**

```tsx
// admin-ui/src/pages/TenantDetailPage.tsx
import { useState, useEffect, useRef } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { tenantsApi } from '@/api/tenants';
import { aaguidPolicyApi } from '@/api/aaguidPolicy';
import { apiKeysApi } from '@/api/apiKeys';
import type { Tenant, AaguidPolicy, ApiKey } from '@/api/designTypes';
import type { Me } from '@/api/types';
import { isPlatform } from '@/me/roles';
import { Alert, Button, ConfirmByTyping, Dialog, Menu, PropChip, StatusBadge, Tabs, useToast, type TabItem } from '@/ui';
import TenantOverview from '@/pages/tenant/TenantOverview';
import WebauthnConfigTab from '@/pages/tenant/WebauthnConfigTab';
import AaguidPolicyTab from '@/pages/tenant/AaguidPolicyTab';
import ApiKeysTab from '@/pages/tenant/ApiKeysTab';
import CredentialsTab from '@/pages/tenant/CredentialsTab';
import AuditTab from '@/pages/tenant/AuditTab';
import FunnelTab from '@/pages/tenant/FunnelTab';
import { countExpiringKeys, hasUnverifiedAaguid } from '@/pages/tenant/tenantSignals';

// ── util ─────────────────────────────────────────────────────────────────────

/** ISO → KST 날짜 'YYYY-MM-DD'. 값이 없으면 '—', 파싱 불가면 원문. */
export function fmtDateKst(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  return new Date(d.getTime() + 9 * 3_600_000).toISOString().slice(0, 10);
}

export const TENANT_TABS: TabItem[] = [
  { id: 'overview', label: '개요' },
  { id: 'webauthn', label: 'WebAuthn' },
  { id: 'aaguid', label: 'AAGUID 정책' },
  { id: 'apikeys', label: 'API 키' },
  { id: 'credentials', label: 'Credentials' },
  { id: 'audit', label: '감사 로그' },
  { id: 'funnel', label: '퍼널' },
];

type TenantDetailPageProps = {
  tenant: Tenant;
  currentTab: string;
  onTabChange: (tab: string) => void;
  me: Me;
  onReload: () => void;
};

// ── SuspendDialog — 영향 범위 + slug 타이핑 확인 ─────────────────────────────

function SuspendDialog({ tenant, open, onClose, onConfirmed }: {
  tenant: Tenant; open: boolean; onClose: () => void; onConfirmed: () => void;
}) {
  const [match, setMatch] = useState(false);
  const [busy, setBusy] = useState(false);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const toast = useToast();

  function close() {
    setMatch(false);
    onClose();
  }
  async function go() {
    if (!match) return;
    setBusy(true);
    try {
      await tenantsApi.suspend(tenant.id);
      toast({ kind: 'warn', title: '테넌트가 정지되었습니다.', message: `${tenant.slug} · 모든 API 키 revoke됨` });
      onConfirmed();
      close();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '정지 실패', message: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog open={open} onClose={close} role="alertdialog" title="테넌트 정지" sub={`${tenant.name} 을(를) 정지합니다.`} initialFocusRef={cancelRef}
      footer={<>
        <button ref={cancelRef} type="button" className="btn" onClick={close}>취소</button>
        <Button variant="danger-solid" disabled={busy || !match} onClick={() => void go()}>정지</Button>
      </>}>
      <Alert tone="bad" title="영향 범위">
        <ul className="stack-1" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          <li>· 유효 API key {tenant.apiKeys}개 즉시 회수</li>
          <li>· 이 tenant 의 모든 ceremony 거부</li>
          <li>· 정지를 해제해도 회수된 키는 복구되지 않음</li>
        </ul>
      </Alert>
      <ConfirmByTyping
        id="suspend-slug"
        expected={tenant.slug}
        onMatchChange={setMatch}
        label={<>확인을 위해 slug <span className="mono" style={{ background: 'var(--sunk)', padding: '0 4px', borderRadius: 'var(--r-xs)' }}>{tenant.slug}</span> 를 입력하세요</>}
      />
    </Dialog>
  );
}

// ── ResumeDialog — 기존 window.confirm 과 같은 의미의 확인 ───────────────────

function ResumeDialog({ tenant, open, onClose, onConfirmed }: {
  tenant: Tenant; open: boolean; onClose: () => void; onConfirmed: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const toast = useToast();
  async function go() {
    setBusy(true);
    try {
      await tenantsApi.activate(tenant.id);
      toast({ kind: 'ok', title: '정지 해제됨' });
      onConfirmed();
      onClose();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '해제 실패', message: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog open={open} onClose={onClose} title="정지 해제" sub={`${tenant.name} 의 정지를 해제합니다.`}
      footer={<>
        <Button onClick={onClose}>취소</Button>
        <Button variant="primary" disabled={busy} onClick={() => void go()}>정지 해제</Button>
      </>}>
      <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>
        이 테넌트의 정지를 해제하시겠습니까? 등록·인증 ceremony 가 다시 허용됩니다. 정지 때 회수된 API 키는 복구되지 않으므로 필요하면 새로 발급하세요.
      </p>
    </Dialog>
  );
}

// ── TenantHeader — V2 2행 헤더 ──────────────────────────────────────────────

function TenantHeader({ tenant, canManage, onSuspend, onActivate }: {
  tenant: Tenant; canManage: boolean; onSuspend: () => void; onActivate: () => void;
}) {
  return (
    <>
      <div className="row" style={{ gap: 14 }}>
        <span className="avatar avatar--lg" aria-hidden="true">{tenant.name.slice(0, 1).toUpperCase()}</span>
        <div className="row" style={{ gap: 10, flex: '1 1 280px', minWidth: 0 }}>
          <h1 className="page__title truncate" title={tenant.name}>{tenant.name}</h1>
          <StatusBadge status={tenant.status} />
        </div>
        <div className="row">
          {tenant.rpId && (
            <a className="btn" href={`https://${tenant.rpId}`} target="_blank" rel="noopener noreferrer">
              RP 사이트 열기 <Icons.ExternalLink size={13} />
            </a>
          )}
          {canManage && (tenant.status === 'ACTIVE'
            ? <Menu label="더보기" items={[]} dangerItems={[{ label: '테넌트 정지', danger: true, onSelect: onSuspend }]} />
            : <Menu label="더보기" items={[{ label: '정지 해제', onSelect: onActivate }]} />)}
        </div>
      </div>
      <div className="propchips">
        <PropChip label="ID" value={tenant.id} copy />
        <PropChip label="RP ID" value={tenant.rpId} copy />
        <PropChip label="slug" value={tenant.slug} />
        <PropChip label="생성" value={fmtDateKst(tenant.createdAt)} />
      </div>
    </>
  );
}

// ── TenantDetailPage ─────────────────────────────────────────────────────────

export function TenantDetailPage({ tenant, currentTab, onTabChange, me, onReload }: TenantDetailPageProps) {
  const [suspendOpen, setSuspendOpen] = useState(false);
  const [resumeOpen, setResumeOpen] = useState(false);
  const [aaguidPolicy, setAaguidPolicy] = useState<AaguidPolicy | null>(null);
  const [apiKeys, setApiKeys] = useState<ApiKey[] | null>(null);
  const platform = isPlatform(me);

  // 탭 점 배지 신호. tenant 가 바뀌거나(onReload 포함) 처음 열릴 때 1회 조회.
  useEffect(() => {
    let cancelled = false;
    setAaguidPolicy(null);
    setApiKeys(null);
    aaguidPolicyApi.get(tenant.id).then((p) => { if (!cancelled) setAaguidPolicy(p); }).catch(() => {});
    apiKeysApi.list(tenant.id).then((k) => { if (!cancelled) setApiKeys(k); }).catch(() => {});
    return () => { cancelled = true; };
  }, [tenant]);

  const expiringKeyCount = apiKeys ? countExpiringKeys(apiKeys, Date.now()) : undefined;
  const tabs: TabItem[] = TENANT_TABS.map((t) => {
    if (t.id === 'aaguid') return { ...t, dot: hasUnverifiedAaguid(aaguidPolicy) };
    if (t.id === 'apikeys') return { ...t, dot: (expiringKeyCount ?? 0) > 0 };
    return t;
  });

  return (
    <div className="page">
      <div className="stack-3" style={{ gap: 14 }}>
        <TenantHeader tenant={tenant} canManage={platform} onSuspend={() => setSuspendOpen(true)} onActivate={() => setResumeOpen(true)} />
        <Tabs ariaLabel="Tenant 탭" items={tabs} active={currentTab} onChange={onTabChange} />
      </div>
      {currentTab === 'overview' && <TenantOverview tenant={tenant} isPlatformOperator={platform} expiringKeyCount={expiringKeyCount} />}
      {currentTab === 'webauthn' && <WebauthnConfigTab tenant={tenant} />}
      {currentTab === 'aaguid' && <AaguidPolicyTab tenant={tenant} />}
      {currentTab === 'apikeys' && <ApiKeysTab tenant={tenant} />}
      {currentTab === 'credentials' && <CredentialsTab tenant={tenant} />}
      {currentTab === 'audit' && <AuditTab tenant={tenant} isPlatformOperator={platform} />}
      {currentTab === 'funnel' && <FunnelTab tenant={tenant} />}
      <SuspendDialog tenant={tenant} open={suspendOpen} onClose={() => setSuspendOpen(false)} onConfirmed={onReload} />
      <ResumeDialog tenant={tenant} open={resumeOpen} onClose={() => setResumeOpen(false)} onConfirmed={onReload} />
    </div>
  );
}

// ── TenantDetailRoute — 라우터 wrapper (App.tsx 에서 사용) ───────────────────

export default function TenantDetailRoute({ me }: { me: Me }) {
  const { id } = useParams<{ id: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = searchParams.get('tab') || 'overview';
  const [tenant, setTenant] = useState<Tenant | null>(null);
  const [loading, setLoading] = useState(true);
  const toast = useToast();

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    tenantsApi.get(id)
      .then(setTenant)
      .catch((e: unknown) => {
        const msg = e instanceof Error ? e.message : String(e);
        toast({ kind: 'err', title: 'Tenant 로드 실패', message: msg });
        setTenant(null);
      })
      .finally(() => setLoading(false));
  }, [id]);

  const reload = () => {
    if (!id) return;
    tenantsApi.get(id).then(setTenant).catch(() => {});
  };

  if (loading) return <div className="muted" style={{ padding: 40 }}>Loading…</div>;
  if (!tenant) return <div className="muted" style={{ padding: 40 }}>Tenant 를 찾을 수 없습니다.</div>;

  return (
    <TenantDetailPage
      tenant={tenant}
      currentTab={tab}
      onTabChange={(t) => setSearchParams({ tab: t })}
      me={me}
      onReload={reload}
    />
  );
}
```

`AuditTab` 의 `isPlatformOperator={me.role === 'PLATFORM_OPERATOR'}` 는 `isPlatform(me)` 와 같은 값이다. 탭 전환은 여전히 `setSearchParams` → 같은 라우트의 검색 파라미터 이동이므로 Task 21·22 의 `useUnsavedGuard` 가 막는다.

- [ ] **Step 10: TenantOverview.tsx 교체**

```tsx
// admin-ui/src/pages/tenant/TenantOverview.tsx
import { useState, useEffect, CSSProperties } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import type { Tenant } from '@/api/designTypes';
import { auditChainApi, type TenantChainVerify } from '@/api/auditChain';
import { auditChainMonitorApi } from '@/api/auditChainMonitor';
import { funnelApi, type FunnelData } from '@/api/funnel';
import { activityApi } from '@/api/activity';
import { Alert, Button, Card, CardBody, CardHeader, KpiCard, StatusStrip, useToast } from '@/ui';
import { adaptFeedItems, type RecentActivityEvent } from './recentActivityAdapter';
import { pickRecentRuns, recentRunsLabel, type ChainRun } from './tenantSignals';

// ── util ─────────────────────────────────────────────────────────────────────

function timeAgo(iso: string | null | undefined): string {
  if (!iso) return '—';
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return '방금 전';
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d}일 전`;
  return `${Math.floor(d / 30)}개월 전`;
}

function tail(s: string, n: number): string {
  return s.slice(-n);
}

function fmt(n: number): string {
  return n.toLocaleString();
}

/** 이벤트 유형 점 색. 유형 이름 태그가 함께 표시되므로 색만으로 의미를 전달하지 않는다. */
function eventColor(type: string): string {
  if (/FAIL|REVOKED|REGRESSION|TAMPER/.test(type)) return 'var(--bad)';
  if (type.startsWith('API_KEY') || type.endsWith('_UPDATED')) return 'var(--warn-dot)';
  return 'var(--brand-700)';
}

const iconBox = (bg: string, fg: string): CSSProperties => ({
  width: 40, height: 40, borderRadius: 10, background: bg, color: fg,
  display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flex: 'none',
});

// ── ChainSummaryCard ─────────────────────────────────────────────────────────

function ChainSummaryCard({ state, recentRuns, onManualVerify, onMonthlyReport, onDetail }: {
  state: TenantChainVerify | null;
  recentRuns?: ChainRun[];
  onManualVerify: () => void;
  onMonthlyReport: () => void;
  onDetail: () => void;
}) {
  return (
    <Card>
      <CardHeader title="감사 체인" actions={<Button variant="ghost" size="sm" onClick={onDetail}>자세히 <Icons.ChevronRight size={12} /></Button>} />
      <CardBody className="stack-3">
        {state === null ? (
          <div className="row" style={{ gap: 12, flexWrap: 'nowrap' }}>
            <span style={iconBox('var(--sunk)', 'var(--mute)')} aria-hidden="true"><Icons.Shield size={20} /></span>
            <div className="col" style={{ minWidth: 0 }}>
              <strong>확인 중</strong>
              <span className="hint" style={{ margin: 0 }}>체인 무결성 상태를 불러오는 중입니다.</span>
            </div>
          </div>
        ) : state.intact ? (
          <div className="row" style={{ gap: 12, flexWrap: 'nowrap' }}>
            <span style={iconBox('var(--ok-soft)', 'var(--ok)')} aria-hidden="true"><Icons.Shield size={20} /></span>
            <div className="col" style={{ minWidth: 0 }}>
              <strong>무결 · 위변조 0건</strong>
              <span className="hint" style={{ margin: 0 }}>마지막 검증 {timeAgo(state.verifiedAt)}</span>
            </div>
          </div>
        ) : (
          <Alert tone="bad" title="위변조 감지">
            마지막 검증 {timeAgo(state.verifiedAt)}{state.tamperedEntryId ? ` · 위변조 항목 …${tail(state.tamperedEntryId, 8)}` : ''}
          </Alert>
        )}
        {recentRuns && recentRuns.length > 0 && (
          <StatusStrip fluid results={recentRuns.map((r) => r.ok)} label={recentRunsLabel(recentRuns)} />
        )}
        <div className="row">
          <Button size="sm" onClick={onManualVerify}>수동 검증</Button>
          <Button size="sm" onClick={onMonthlyReport}>월간 보고서</Button>
        </div>
      </CardBody>
    </Card>
  );
}

// ── TenantOverview ───────────────────────────────────────────────────────────

type Props = { tenant: Tenant; isPlatformOperator?: boolean; expiringKeyCount?: number };

export default function TenantOverview({ tenant, isPlatformOperator = false, expiringKeyCount }: Props) {
  const navigate = useNavigate();
  const toast = useToast();
  const [chainState, setChainState] = useState<TenantChainVerify | null>(null);
  const [recentRuns, setRecentRuns] = useState<ChainRun[] | undefined>(undefined);
  const [funnel, setFunnel] = useState<FunnelData | null>(null);
  const [recentEvents, setRecentEvents] = useState<RecentActivityEvent[]>([]);

  async function handleManualVerify() {
    try {
      const result = await auditChainApi.verifyTenant(tenant.id);
      setChainState(result);
      toast({ kind: result.intact ? 'ok' : 'warn', title: '수동 검증 완료', message: result.intact ? '체인 무결' : '위변조 감지' });
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '수동 검증 실패', message: msg });
    }
  }

  function handleMonthlyReport() {
    navigate('/audit-chain');
  }

  useEffect(() => {
    auditChainApi.verifyTenant(tenant.id)
      .then(setChainState)
      .catch(() => setChainState(null));
  }, [tenant.id]);

  // ★ 최근 검증 이력: overview 는 PLATFORM 전용 API. 필드가 없으면 undefined 유지 → 스트립 숨김.
  useEffect(() => {
    if (!isPlatformOperator) return;
    let cancelled = false;
    auditChainMonitorApi.overview(24)
      .then((o) => { if (!cancelled) setRecentRuns(pickRecentRuns(o, tenant.id)); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [tenant.id, isPlatformOperator]);

  useEffect(() => {
    let cancelled = false;
    funnelApi.get(tenant.id, 7)
      .then((data) => { if (!cancelled) setFunnel(data); })
      .catch(() => { if (!cancelled) setFunnel(null); });
    return () => { cancelled = true; };
  }, [tenant.id]);

  useEffect(() => {
    // 기존과 동일: 최신 피드를 받아 클라이언트에서 tenant 로 거른다.
    let cancelled = false;
    activityApi
      .fetch(null, undefined)
      .then((view) => {
        if (cancelled) return;
        setRecentEvents(adaptFeedItems(view).filter((e) => e.tenantId === tenant.id).slice(0, 5));
      })
      .catch(() => { if (!cancelled) setRecentEvents([]); });
    return () => { cancelled = true; };
  }, [tenant.id]);

  const f = funnel ?? {
    registration: { attempts: 0, success: 0, ratio: 0 },
    authentication: { attempts: 0, success: 0, ratio: 0 },
  };
  const delta = tenant.credentialsDelta7d;

  return (
    <div className="stack-6">
      <div className="kpis">
        <KpiCard label="등록 Credential" value={fmt(tenant.credentials)}
          hint={delta !== undefined ? `7일 ${delta >= 0 ? '+' : ''}${fmt(delta)}` : undefined} />
        <KpiCard label="유효 API Key" value={tenant.apiKeys}
          hint={expiringKeyCount !== undefined ? `만료 임박 ${expiringKeyCount}` : undefined}
          hintTone={expiringKeyCount ? 'warn' : 'default'} />
        <KpiCard label="등록 성공률 (7d)"
          value={f.registration.attempts > 0 ? `${(f.registration.ratio * 100).toFixed(1)}%` : '—'}
          hint={f.registration.attempts > 0 ? `${fmt(f.registration.success)} / ${fmt(f.registration.attempts)} 시도` : '최근 7일 등록 시도 없음'} />
        <KpiCard label="인증 성공률 (7d)"
          value={f.authentication.attempts > 0 ? `${(f.authentication.ratio * 100).toFixed(1)}%` : '—'}
          hint={f.authentication.attempts > 0 ? `${fmt(f.authentication.success)} / ${fmt(f.authentication.attempts)} 시도` : '최근 7일 인증 시도 없음'} />
      </div>

      <div className="grid-3">
        <Card>
          <CardHeader title="WebAuthn 요약" actions={
            <Button variant="ghost" size="sm" onClick={() => navigate(`/tenants/${tenant.id}?tab=webauthn`)}>편집 <Icons.ChevronRight size={12} /></Button>
          } />
          <CardBody>
            <dl className="kv" style={{ gridTemplateColumns: '120px minmax(0, 1fr)' }}>
              <dt>rpId</dt><dd className="mono truncate" style={{ fontSize: 12 }} title={tenant.rpId}>{tenant.rpId}</dd>
              <dt>rpName</dt><dd className="truncate" title={tenant.name}>{tenant.name}</dd>
              <dt>origins</dt><dd className="row" style={{ gap: 4 }}><span className="tag">https://{tenant.rpId}</span></dd>
              <dt>userVerification</dt><dd><span className="tag">{tenant.userVerification}</span></dd>
              <dt>attestation</dt><dd><span className="tag">{tenant.attestationConveyance}</span></dd>
              <dt>timeout</dt><dd className="mono" style={{ fontSize: 12 }}>{tenant.webauthnTimeoutMs} ms</dd>
            </dl>
          </CardBody>
        </Card>

        <Card>
          <CardHeader title="최근 활동" actions={
            <Button variant="ghost" size="sm" onClick={() => navigate(`/activity?tenantId=${tenant.id}`)}>전체 보기 <Icons.ChevronRight size={12} /></Button>
          } />
          {recentEvents.length === 0 ? (
            <p className="muted" style={{ margin: 0, padding: 20, fontSize: 13, textAlign: 'center' }}>최근 활동 없음</p>
          ) : (
            <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
              {recentEvents.map((e, i) => (
                <li key={e.id} className="row" style={{ flexWrap: 'nowrap', gap: 10, padding: '11px 20px', borderBottom: i === recentEvents.length - 1 ? 0 : '1px solid var(--line-soft)' }}>
                  <span className="dot" style={{ background: eventColor(e.type) }} aria-hidden="true" />
                  <span className="tag" style={{ flex: 'none' }}>{e.type}</span>
                  <span className="mono hint truncate" style={{ margin: 0, flex: '1 1 0' }}>…{tail(e.subjectId, 16)}</span>
                  <span className="hint" style={{ margin: 0, flex: 'none' }}>{timeAgo(e.ts)}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <ChainSummaryCard
          state={chainState}
          recentRuns={recentRuns}
          onManualVerify={() => void handleManualVerify()}
          onMonthlyReport={handleMonthlyReport}
          onDetail={() => navigate(`/tenants/${tenant.id}?tab=audit`)}
        />
      </div>
    </div>
  );
}
```

- [ ] **Step 11: 테스트 통과 확인**

Run: `npx vitest run src/pages/tenant/tenantSignals.test.ts src/pages/TenantDetailPage.test.tsx src/pages/tenant/TenantOverview.test.tsx`
Expected: PASS (3 files).

- [ ] **Step 12: 전체 테스트·린트·빌드**

Run: `npm test && npm run lint && npm run build`
Expected: 전체 PASS, lint 오류 0(경고는 기존 수준), 빌드 성공.

- [ ] **Step 13: 수동 확인**

`npm run dev` → PLATFORM 데모 계정으로 `/admin/tenants/{id}` 진입: 헤더 2행(아바타·이름·상태·RP 사이트 열기·⋯ / ID·RP ID 복사·slug·생성), ⋯ → `위험` 구획의 `테넌트 정지` → slug 입력 전 `정지` 비활성·`취소` 포커스. AAGUID 허용 목록에 MDS 미확인 항목이 있는 tenant 는 `AAGUID 정책` 탭에 주황 점. RP_ADMIN 계정에서는 ⋯ 없음.

- [ ] **Step 14: 커밋**

```bash
git add src/pages/TenantDetailPage.tsx src/pages/TenantDetailPage.test.tsx src/pages/tenant/TenantOverview.tsx src/pages/tenant/TenantOverview.test.tsx src/pages/tenant/tenantSignals.ts src/pages/tenant/tenantSignals.test.ts src/api/auditChainMonitor.ts src/api/types.ts src/api/designTypes.ts src/api/tenants.ts src/styles/components.css
git commit -m "feat(admin-ui): V2 Tenant 상세 헤더·탭 점 배지·정지 다이얼로그·개요 카드

- 헤더 2행(PropChip 복사), ⋯ 메뉴(테넌트 정지/정지 해제, PLATFORM 전용)
- 탭 점: AAGUID(mdsName 미확인), API 키(30일 내 만료 ACTIVE 키)
- 정지: alertdialog·영향 범위·slug 확인·취소 기본 포커스, 해제: confirm → Dialog
- 개요: KPI 4 + 요약 카드 3, ★recentRuns 스트립·★credentialsDelta7d 는 있을 때만

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 21: WebAuthn 설정 (설명 좌·컨트롤 우 · SaveBar · 이탈 확인)

V2 아트보드: `v2/Tenant-WebAuthn.dc.html`, `v2/Dialogs-Tenant.dc.html`(변경 사항 확인).

**Files:**
- Create: `admin-ui/src/pages/tenant/SetRow.tsx`
- Rewrite: `admin-ui/src/pages/tenant/WebauthnConfigTab.tsx`
- Test: `admin-ui/src/pages/tenant/WebauthnConfigTab.view.test.tsx`(신규), `admin-ui/src/pages/tenant/WebauthnConfigTab.test.ts`(기존 `validateOrigin` 테스트, 변경 없이 통과해야 함)

**Interfaces:**
- Consumes: `@/ui` 의 `Card`, `CardHeader`, `Badge`, `Button`, `ChipInput`(`validate`·`addedValues`), `Segmented`, `Alert`, `Dialog`, `SaveBar`, `useUnsavedGuard`, `useToast`, `ToastHost`; `renderWithDataRouter`. 기존 `webauthnApi.get/diff/update`, `apkKeyHashFromFingerprint`, `isApkKeyHashOrigin`, `APK_KEY_HASH_PREFIX`.
- Produces:
  - `SetRow({ label: ReactNode; htmlFor?: string; desc: ReactNode; changed?: boolean; children: ReactNode })` — `.set` 2열 행. `htmlFor` 가 있으면 `<label>`, 없으면 `<span>`. `changed` 면 라벨 옆 `Badge tone="warn" dot={false}` `변경됨`. (Task 22 재사용)
  - `WebauthnConfigTab.tsx`: 기존 export 유지 — `validateOrigin(input, rpId)`, default `WebauthnConfigTab({ tenant })`. 신규 export `changedFieldNames(changes: { key: string }[]): string[]`(화면 순서 `rpName → timeoutMs → origins → userVerification → attestationConveyance → formats`).

보존하는 동작(이름 그대로): `diffObjects`, `validateOrigin`(rpId 범위·apk-key-hash 43자 base64url·정규화), `UV_GUIDE`, `AT_GUIDE`, `timeoutGuide`, 로드 effect(`webauthnApi.get` 실패 시 `config 로드 실패` 토스트), `addApkKey`(`apkKeyHashFromFingerprint` + 중복 `이미 추가된 Android 키입니다.`), `dirty = JSON.stringify(cfg) !== JSON.stringify(draft)`, `handlePreview`(`webauthnApi.diff(tenant.id, draft, draft._mdsRequired)` → 실패 시 `diff 실패` 토스트), `handleSave`(`webauthnApi.update(tenant.id, tenant.name, draft, draft._mdsRequired)` → `WebAuthn config 저장됨` / `저장 실패`), `DiffDialog`·`DiffRow`(V2 마크업).
변경: 카드 헤더의 `변경 사항 N건` 배지·`되돌리기`·`저장…` 버튼 제거 → `SaveBar`(`저장…` 이 기존 `handlePreview`). origins 직접 입력은 `ChipInput` 으로 교체하고 `validate` 에서 `validateOrigin` + 정규화 후 중복 검사, `onChange` 에서 새 값만 `validateOrigin().value` 로 정규화. 이모지(🤖) 제거 — apk-key-hash origin 은 전체 문자열 칩(말줄임). timeout 환산 안내는 항상 실시간 표시.

- [ ] **Step 1: 실패하는 화면 테스트**

```tsx
// admin-ui/src/pages/tenant/WebauthnConfigTab.view.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, fireEvent, within, act, waitFor, cleanup } from '@testing-library/react';
import { useNavigate } from 'react-router-dom';
import { ToastHost } from '@/ui';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import type { Tenant } from '@/api/designTypes';

vi.mock('@/api/webauthn', () => ({ webauthnApi: { get: vi.fn(), diff: vi.fn(), update: vi.fn() } }));

import { webauthnApi } from '@/api/webauthn';
import WebauthnConfigTab, { changedFieldNames } from './WebauthnConfigTab';

const T: Tenant = {
  id: 't-1', name: 'Acme', slug: 'acme', rpId: 'dev-passkey.crosscert.com', status: 'ACTIVE', credentials: 0, apiKeys: 1,
  lastEventAt: null, createdAt: '2026-03-01T00:00:00Z', userVerification: 'REQUIRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const CFG = {
  rpId: 'dev-passkey.crosscert.com', rpName: 'Dev', origins: ['https://dev-passkey.crosscert.com'], formats: ['none', 'packed'],
  userVerification: 'REQUIRED' as const, attestationConveyance: 'NONE' as const, timeoutMs: 60000, _mdsRequired: true,
};

function Host() {
  const nav = useNavigate();
  return (
    <ToastHost>
      <WebauthnConfigTab tenant={T} />
      <button type="button" onClick={() => nav('/tenants/t-1?tab=aaguid')}>AAGUID 탭으로</button>
    </ToastHost>
  );
}
const renderTab = () => renderWithDataRouter(<Host />, { initialPath: '/tenants/t-1?tab=webauthn' });
const bar = () => screen.queryByRole('region', { name: '저장되지 않은 변경' });

beforeEach(() => {
  vi.clearAllMocks();
  (webauthnApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ ...CFG, origins: [...CFG.origins] });
});
afterEach(cleanup);

describe('changedFieldNames', () => {
  it('화면 순서로 정렬', () => {
    expect(changedFieldNames([{ key: 'origins' }, { key: 'timeoutMs' }])).toEqual(['timeoutMs', 'origins']);
  });
});

describe('WebauthnConfigTab V2', () => {
  it('설명 좌/컨트롤 우 행, rpId 는 읽기 전용 + 변경 불가', async () => {
    renderTab();
    const rpId = await screen.findByLabelText('rpId');
    expect(rpId).toHaveAttribute('readonly');
    expect(rpId).toHaveValue('dev-passkey.crosscert.com');
    expect(screen.getByText('변경 불가')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'WebAuthn Configuration' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'userVerification' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'REQUIRED' })).toHaveAttribute('aria-pressed', 'true');
    expect(bar()).toBeNull();
    expect(screen.queryByText(/변경 사항 \d+건/)).toBeNull();
  });

  it('dirty → SaveBar 건수·요약, 변경 필드 표시, 실시간 timeout 안내, 되돌리기', async () => {
    renderTab();
    fireEvent.change(await screen.findByLabelText('timeoutMs'), { target: { value: '120000' } });
    expect(bar()).toHaveTextContent('저장하지 않은 변경 1건');
    expect(bar()).toHaveTextContent('timeoutMs');
    expect(screen.getAllByText('변경됨')).toHaveLength(1);
    expect(screen.getByText(/= 120초 \(2분\)/)).toBeInTheDocument();

    const origin = screen.getByRole('textbox', { name: 'origin 추가' });
    fireEvent.change(origin, { target: { value: 'sub.dev-passkey.crosscert.com' } });
    fireEvent.keyDown(origin, { key: 'Enter' });
    expect(screen.getByText('https://sub.dev-passkey.crosscert.com')).toBeInTheDocument();
    expect(bar()).toHaveTextContent('저장하지 않은 변경 2건');
    expect(bar()).toHaveTextContent('timeoutMs · origins');

    fireEvent.click(within(bar()!).getByRole('button', { name: '되돌리기' }));
    expect(bar()).toBeNull();
    expect(screen.queryByText('https://sub.dev-passkey.crosscert.com')).toBeNull();
  });

  it('origin 검증: rpId 범위 밖·중복(정규화 후)은 추가하지 않고 오류 표시', async () => {
    renderTab();
    const origin = await screen.findByRole('textbox', { name: 'origin 추가' });
    fireEvent.change(origin, { target: { value: 'evil.com' } });
    fireEvent.keyDown(origin, { key: 'Enter' });
    expect(screen.getByText(/범위를 벗어납니다/)).toBeInTheDocument();
    fireEvent.change(origin, { target: { value: 'dev-passkey.crosscert.com' } });
    fireEvent.keyDown(origin, { key: 'Enter' });
    expect(screen.getByText("'https://dev-passkey.crosscert.com' 는 이미 추가되어 있습니다.")).toBeInTheDocument();
    expect(bar()).toBeNull();
  });

  it('Android 앱 키: 64 hex 지문 → apk-key-hash origin, 잘못된 길이는 오류', async () => {
    renderTab();
    const apk = await screen.findByRole('textbox', { name: 'Android APK 키 해시' });
    fireEvent.change(apk, { target: { value: 'abc' } });
    fireEvent.click(screen.getByRole('button', { name: '앱 키 추가' }));
    expect(screen.getByText('SHA-256 지문은 32바이트(64 hex)여야 합니다. 입력 길이: 3')).toBeInTheDocument();
    fireEvent.change(apk, { target: { value: '00:'.repeat(31) + '00' } });
    fireEvent.click(screen.getByRole('button', { name: '앱 키 추가' }));
    expect(screen.getByText(`android:apk-key-hash:${'A'.repeat(43)}`)).toBeInTheDocument();
    expect(bar()).toHaveTextContent('origins');
  });

  it('dirty 가 아니면 탭 이동이 바로 된다', async () => {
    const { router } = renderTab();
    await screen.findByLabelText('timeoutMs');
    await act(async () => { fireEvent.click(screen.getByText('AAGUID 탭으로')); });
    expect(router.state.location.search).toBe('?tab=aaguid');
  });

  it('dirty 면 ?tab=aaguid 이동이 막히고 확인 다이얼로그', async () => {
    const { router } = renderTab();
    fireEvent.change(await screen.findByLabelText('rpName'), { target: { value: 'Dev 2' } });
    await act(async () => { fireEvent.click(screen.getByText('AAGUID 탭으로')); });
    expect(screen.getByRole('alertdialog', { name: '저장하지 않은 변경이 있습니다' })).toBeInTheDocument();
    expect(router.state.location.search).toBe('?tab=webauthn');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '머무르기' })); });
    expect(router.state.location.search).toBe('?tab=webauthn');
    expect(screen.getByLabelText('rpName')).toHaveValue('Dev 2');
  });

  it('저장… → diff 확인 다이얼로그 → 기존과 같은 payload 로 update', async () => {
    (webauthnApi.diff as ReturnType<typeof vi.fn>).mockResolvedValue([{ key: 'timeoutMs', from: 60000, to: 120000 }]);
    (webauthnApi.update as ReturnType<typeof vi.fn>).mockResolvedValue({ ...CFG, timeoutMs: 120000 });
    renderTab();
    fireEvent.change(await screen.findByLabelText('timeoutMs'), { target: { value: '120000' } });
    await act(async () => { fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' })); });
    expect(webauthnApi.diff).toHaveBeenCalledWith('t-1', { ...CFG, timeoutMs: 120000 }, true);
    const dlg = screen.getByRole('dialog', { name: '변경 사항 확인' });
    expect(within(dlg).getByText('60000')).toBeInTheDocument();
    expect(within(dlg).getByText('120000')).toBeInTheDocument();
    expect(within(dlg).getByText('WEBAUTHN_CONFIG_UPDATED')).toBeInTheDocument();
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '1개 변경 사항 저장' })); });
    expect(webauthnApi.update).toHaveBeenCalledWith('t-1', 'Acme', { ...CFG, timeoutMs: 120000 }, true);
    await waitFor(() => expect(bar()).toBeNull());
    expect(screen.queryByRole('dialog', { name: '변경 사항 확인' })).toBeNull();
    expect(screen.getByText('WebAuthn config 저장됨')).toBeInTheDocument();
  });

  it('diff 가 비면 "변경 사항이 없습니다."', async () => {
    (webauthnApi.diff as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    renderTab();
    fireEvent.change(await screen.findByLabelText('timeoutMs'), { target: { value: '90000' } });
    await act(async () => { fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' })); });
    expect(within(screen.getByRole('dialog', { name: '변경 사항 확인' })).getByText('변경 사항이 없습니다.')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/tenant/WebauthnConfigTab.view.test.tsx`
Expected: FAIL — `changedFieldNames` export 없음, `origin 추가`/`Android APK 키 해시` 라벨·`저장하지 않은 변경` region 없음.

- [ ] **Step 3: SetRow 작성**

```tsx
// admin-ui/src/pages/tenant/SetRow.tsx
import { ReactNode } from 'react';
import { Badge } from '@/ui';

/** 설정 화면 2열 행(설명 좌 · 컨트롤 우). V2 `.set` 그리드. */
export function SetRow({ label, htmlFor, desc, changed, children }: {
  label: ReactNode; htmlFor?: string; desc: ReactNode; changed?: boolean; children: ReactNode;
}) {
  return (
    <div className="set">
      <div className="stack-1">
        <div className="row" style={{ gap: 8 }}>
          {htmlFor ? <label className="set__label" htmlFor={htmlFor}>{label}</label> : <span className="set__label">{label}</span>}
          {changed && <Badge tone="warn" dot={false}>변경됨</Badge>}
        </div>
        <p className="set__desc">{desc}</p>
      </div>
      <div style={{ minWidth: 0 }}>{children}</div>
    </div>
  );
}
```

- [ ] **Step 4: WebauthnConfigTab.tsx 교체**

```tsx
// admin-ui/src/pages/tenant/WebauthnConfigTab.tsx
import { useState, useEffect, CSSProperties } from 'react';
import { Icons } from '@/icons/Icons';
import { webauthnApi } from '@/api/webauthn';
import type { Tenant, WebauthnConfig } from '@/api/designTypes';
import { apkKeyHashFromFingerprint, isApkKeyHashOrigin, APK_KEY_HASH_PREFIX } from '@/lib/apkKeyHash';
import { Alert, Badge, Button, Card, CardHeader, ChipInput, Dialog, SaveBar, Segmented, useToast, useUnsavedGuard } from '@/ui';
import { SetRow } from './SetRow';

// webauthnApi.get returns WebauthnConfig + _mdsRequired. Use this locally.
type ConfigWithMds = WebauthnConfig & { _mdsRequired: boolean };
type Change = { key: string; from: unknown; to: unknown };

// ── util ──────────────────────────────────────────────────────────────────────

function diffObjects(a: WebauthnConfig, b: WebauthnConfig): Change[] {
  const out: Change[] = [];
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]) as Set<keyof WebauthnConfig>;
  keys.forEach((k) => {
    // Skip internal fields not shown in the UI
    if ((k as string).startsWith('_')) return;
    if (JSON.stringify(a[k]) !== JSON.stringify(b[k])) {
      out.push({ key: k, from: a[k], to: b[k] });
    }
  });
  return out;
}

const FIELD_ORDER = ['rpName', 'timeoutMs', 'origins', 'userVerification', 'attestationConveyance', 'formats'];

/** SaveBar 요약용: 바뀐 필드 이름을 화면 순서로. */
export function changedFieldNames(changes: { key: string }[]): string[] {
  const keys = changes.map((c) => c.key);
  return [...FIELD_ORDER.filter((k) => keys.includes(k)), ...keys.filter((k) => !FIELD_ORDER.includes(k))];
}

// origin 입력값을 검증한다. WebAuthn 스펙상 origin 의 host 는 rpId 와 같거나
// rpId 의 서브도메인이어야 한다(rpId 는 origin host 의 등록 가능 도메인 접미사).
//  rpId=dev-passkey.crosscert.com →
//   ✅ dev-passkey.crosscert.com (자기 자신), sub.dev-passkey.crosscert.com (서브도메인)
//   ❌ de-passkey.crosscert.com (다른 host), crosscert.com (상위 도메인)
// 반환: { ok:true, value } 정규화된 origin, 또는 { ok:false, error } 사유.
export function validateOrigin(input: string, rpId: string): { ok: true; value: string } | { ok: false; error: string } {
  const raw = input.trim();
  if (!raw) return { ok: false, error: 'origin 을 입력하세요.' };

  // Android 네이티브 앱 origin: android:apk-key-hash:<43자 base64url>.
  // 도메인이 아니라 앱 서명키 해시이므로 rpId 서브도메인 범위 검사를 적용하지 않는다.
  if (isApkKeyHashOrigin(raw)) {
    const body = raw.slice(APK_KEY_HASH_PREFIX.length);
    if (/^[A-Za-z0-9_-]{43}$/.test(body)) {
      return { ok: true, value: raw };
    }
    return { ok: false, error: 'android:apk-key-hash 값은 43자 base64url 이어야 합니다.' };
  }

  // 스킴이 없으면 https:// 를 가정해 파싱(사용자가 host만 입력한 경우 허용).
  const withScheme = /^https?:\/\//i.test(raw) ? raw : `https://${raw}`;
  let url: URL;
  try {
    url = new URL(withScheme);
  } catch {
    return { ok: false, error: `'${raw}' 는 올바른 origin 형식이 아닙니다. 예) https://${rpId}` };
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') {
    return { ok: false, error: 'origin 스킴은 https(또는 http)만 허용됩니다.' };
  }
  const host = url.hostname.toLowerCase();
  const base = rpId.toLowerCase();
  const isSelf = host === base;
  const isSub = host.endsWith(`.${base}`);
  if (!isSelf && !isSub) {
    return { ok: false, error: `origin host '${host}' 는 rpId '${rpId}' 범위를 벗어납니다. rpId 자신이거나 그 서브도메인(예: sub.${rpId})만 추가할 수 있습니다.` };
  }
  // 정규화: scheme://host[:port] (경로·쿼리 제거). WebAuthn origin 은 path 를 갖지 않는다.
  const normalized = `${url.protocol}//${url.host}`;
  return { ok: true, value: normalized };
}

// ── 옵션별 설명 (선택값에 따라 동적으로 노출) ─────────────────────────────────

const UV_GUIDE: Record<string, { tone: 'info' | 'ok' | 'warn'; text: string }> = {
  REQUIRED: { tone: 'info', text: 'PIN·지문·얼굴 등 사용자 확인(UV)을 반드시 요구합니다. UV를 못 하는 인증기는 ceremony가 실패합니다. 2FA 수준의 보안이 필요할 때 권장.' },
  PREFERRED: { tone: 'info', text: '가능하면 UV를 수행하되, 지원하지 않는 인증기는 UV 없이도 허용합니다. 보안과 호환성의 균형 — 대부분의 서비스 기본값.' },
  DISCOURAGED: { tone: 'info', text: 'UV를 요구하지 않습니다(사용자 존재 확인만). 마찰은 가장 적지만 단일 인증 요소가 되므로, 비밀번호 등 다른 요소와 함께 쓸 때만 권장.' },
};

const AT_GUIDE: Record<string, { tone: 'info' | 'ok' | 'warn'; text: string }> = {
  NONE: { tone: 'info', text: 'attestation을 요청하지 않습니다. 프라이버시가 가장 높고 등록이 단순해, 인증기 모델 검증이 불필요한 일반 서비스에 권장.' },
  INDIRECT: { tone: 'info', text: 'attestation을 원하되 클라이언트가 익명화할 수 있게 허용합니다. 출처 정보를 받되 개별 기기 추적은 피하고 싶을 때.' },
  DIRECT: { tone: 'info', text: '인증기의 attestation을 그대로 받습니다. AAGUID·모델 확인이나 MDS 매칭으로 허용 기기를 통제하려면 필요. 등록 시 사용자 동의 프롬프트가 뜰 수 있습니다.' },
  ENTERPRISE: { tone: 'info', text: '기기를 개별 식별할 수 있는 attestation을 요구합니다. 사전 등록된 인증기만 쓰는 사내 환경 전용 — 일반 사용자 대상 서비스에는 부적합.' },
};

// ── timeoutMs → 초/분 환산 안내 ──────────────────────────────────────────────
// 권장 범위 60000–120000ms(=60–120초). 벗어나면 warn 톤으로 주의.

function timeoutGuide(ms: number): { tone: 'info' | 'ok' | 'warn'; text: string } {
  if (!Number.isFinite(ms) || ms <= 0) {
    return { tone: 'warn', text: '0보다 큰 값을 입력하세요. 권장 60000–120000ms(60–120초).' };
  }
  const totalSec = ms / 1000;
  const secLabel = Number.isInteger(totalSec) ? `${totalSec}초` : `${parseFloat(totalSec.toFixed(2))}초`;
  let human = `= ${secLabel}`;
  if (totalSec >= 60) {
    const m = Math.floor(totalSec / 60);
    const s = Math.round(totalSec - m * 60);
    human = `= ${secLabel}` + (s === 0 ? ` (${m}분)` : ` (${m}분 ${s}초)`);
  }
  if (ms < 60000) return { tone: 'warn', text: `${human} — 권장(60초)보다 짧습니다. 너무 짧으면 사용자가 인증을 끝내기 전에 만료될 수 있습니다.` };
  if (ms > 120000) return { tone: 'warn', text: `${human} — 권장(120초)보다 깁니다. 너무 길면 만료를 기다리는 동안 ceremony 자원이 오래 점유됩니다.` };
  return { tone: 'ok', text: `${human} — 권장 범위(60–120초) 안입니다.` };
}

const WARN_BORDER: CSSProperties = { borderColor: 'var(--warn-dot)' };
const UV_OPTIONS = (['REQUIRED', 'PREFERRED', 'DISCOURAGED'] as const).map((v) => ({ value: v, label: v }));
const AT_OPTIONS = (['NONE', 'INDIRECT', 'DIRECT', 'ENTERPRISE'] as const).map((v) => ({ value: v, label: v }));

// ── WebauthnConfigTab ─────────────────────────────────────────────────────────

type WebauthnConfigTabProps = { tenant: Tenant };

export default function WebauthnConfigTab({ tenant }: WebauthnConfigTabProps) {
  const [cfg, setCfg] = useState<ConfigWithMds | null>(null);
  const [draft, setDraft] = useState<ConfigWithMds | null>(null);
  const [apkInput, setApkInput] = useState('');
  const [apkError, setApkError] = useState<string | null>(null);
  const [showDiff, setShowDiff] = useState(false);
  const [diffChanges, setDiffChanges] = useState<Change[]>([]);
  const [saving, setSaving] = useState(false);
  const toast = useToast();

  useEffect(() => {
    webauthnApi
      .get(tenant.id)
      .then((c) => {
        setCfg(c);
        setDraft({ ...c });
      })
      .catch((e: unknown) => {
        const msg = e instanceof Error ? e.message : String(e);
        toast({ kind: 'err', title: 'config 로드 실패', message: msg });
      });
  }, [tenant.id]);

  const dirty = cfg !== null && draft !== null && JSON.stringify(cfg) !== JSON.stringify(draft);
  const localChanges = cfg && draft ? diffObjects(cfg, draft) : [];
  const changed = new Set(localChanges.map((c) => c.key));
  const guard = useUnsavedGuard(dirty);

  // ChipInput validate: 형식·rpId 범위 + 정규화 후 중복.
  function validateOriginInput(v: string): string | null {
    if (!draft) return null;
    const r = validateOrigin(v, draft.rpId);
    if (!r.ok) return r.error;
    if (draft.origins.includes(r.value)) return `'${r.value}' 는 이미 추가되어 있습니다.`;
    return null;
  }

  // ChipInput 은 입력 원문을 넘긴다 → 새로 들어온 값만 정규화(삭제는 그대로 반영).
  function onOriginsChange(next: string[]) {
    if (!draft) return;
    const normalized = next.map((v) => {
      if (draft.origins.includes(v)) return v;
      const r = validateOrigin(v, draft.rpId);
      return r.ok ? r.value : v;
    });
    setDraft({ ...draft, origins: Array.from(new Set(normalized)) });
  }

  function addApkKey() {
    if (!draft) return;
    const conv = apkKeyHashFromFingerprint(apkInput);
    if (!conv.ok) {
      setApkError(conv.error);
      return;
    }
    if (draft.origins.includes(conv.value)) {
      setApkError('이미 추가된 Android 키입니다.');
      return;
    }
    setDraft({ ...draft, origins: [...draft.origins, conv.value] });
    setApkInput('');
    setApkError(null);
  }

  function handleRevert() {
    if (!cfg) return;
    setDraft({ ...cfg });
    setApkInput('');
    setApkError(null);
  }

  async function handlePreview() {
    if (!draft) return;
    try {
      const r = await webauthnApi.diff(tenant.id, draft, draft._mdsRequired);
      setDiffChanges(r);
      setShowDiff(true);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: 'diff 실패', message: msg });
    }
  }

  async function handleSave() {
    if (!draft) return;
    setSaving(true);
    try {
      const updated = await webauthnApi.update(tenant.id, tenant.name, draft, draft._mdsRequired);
      setCfg(updated);
      setDraft({ ...updated });
      setShowDiff(false);
      toast({ kind: 'ok', title: 'WebAuthn config 저장됨', message: `${diffChanges.length}개 필드가 업데이트되었습니다.` });
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '저장 실패', message: msg });
    } finally {
      setSaving(false);
    }
  }

  if (!draft || !cfg) {
    return <div className="muted" style={{ padding: 40 }}>Loading…</div>;
  }

  const tg = timeoutGuide(draft.timeoutMs);
  const addedOrigins = draft.origins.filter((o) => !cfg.origins.includes(o));
  const uv = UV_GUIDE[draft.userVerification];
  const at = AT_GUIDE[draft.attestationConveyance];

  return (
    <div className="stack-6">
      <Card>
        <CardHeader title="WebAuthn Configuration" sub="변경 사항은 다음 ceremony부터 적용됩니다." />

        <SetRow label="rpId" htmlFor="wa-rpid" desc="생성 시 고정. 기존 credential 이 이 값에 묶여 있습니다.">
          <div className="row" style={{ gap: 10, flexWrap: 'nowrap' }}>
            <input id="wa-rpid" className="input mono" value={draft.rpId} readOnly aria-readonly="true" title="rpId는 생성 후 변경할 수 없습니다" />
            <Badge dot={false}><Icons.Lock size={11} />변경 불가</Badge>
          </div>
        </SetRow>

        <SetRow label="rpName" htmlFor="wa-rpname" desc="사용자의 패스키 프롬프트에 보이는 이름">
          <div className="row" style={{ gap: 10, flexWrap: 'nowrap' }}>
            <input id="wa-rpname" className="input" value={draft.rpName} style={changed.has('rpName') ? WARN_BORDER : undefined}
              onChange={(e) => setDraft({ ...draft, rpName: e.target.value })} />
            {changed.has('rpName') && <Badge tone="warn" dot={false}>변경됨</Badge>}
          </div>
        </SetRow>

        <SetRow label="timeoutMs" htmlFor="wa-timeout" desc="ceremony 대기 시간">
          <div className="row" style={{ gap: 10 }}>
            <input id="wa-timeout" className="input mono" type="number" value={draft.timeoutMs} aria-describedby="wa-timeout-hint"
              style={{ maxWidth: 180, ...(changed.has('timeoutMs') ? WARN_BORDER : {}) }}
              onChange={(e) => setDraft({ ...draft, timeoutMs: parseInt(e.target.value || '0', 10) })} />
            {changed.has('timeoutMs') && <Badge tone="warn" dot={false}>변경됨</Badge>}
          </div>
          <p id="wa-timeout-hint" className="hint" style={tg.tone === 'warn' ? { color: 'var(--warn)' } : undefined}>{tg.text}</p>
        </SetRow>

        <SetRow label="origins" desc="허용할 웹 origin 과 Android 앱 키 해시. Enter 로 추가합니다." changed={changed.has('origins')}>
          <div className="stack-3" style={{ gap: 10 }}>
            <ChipInput mono values={draft.origins} addedValues={addedOrigins} onChange={onOriginsChange} validate={validateOriginInput}
              ariaLabel="origin 추가" placeholder={`https://${draft.rpId} 입력 후 Enter`} />
            <div className="row" style={{ gap: 8, flexWrap: 'nowrap' }}>
              <input className="input mono" aria-label="Android APK 키 해시" placeholder="Android APK signing key SHA-256" value={apkInput} aria-invalid={!!apkError}
                onChange={(e) => { setApkInput(e.target.value); if (apkError) setApkError(null); }}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addApkKey(); } }} />
              <Button onClick={addApkKey}>앱 키 추가</Button>
            </div>
            {apkError && <p className="field__error" role="alert" style={{ margin: 0 }}>{apkError}</p>}
          </div>
        </SetRow>

        <SetRow label="userVerification" desc="생체·PIN 검증 요구 수준" changed={changed.has('userVerification')}>
          <div className="stack-2">
            <Segmented ariaLabel="userVerification" value={draft.userVerification} options={UV_OPTIONS}
              onChange={(v) => setDraft({ ...draft, userVerification: v })} />
            {uv && <div className="desc-box">{draft.userVerification} — {uv.text}</div>}
          </div>
        </SetRow>

        <SetRow label="attestationConveyance" desc="등록 시 인증기 증명 요청" changed={changed.has('attestationConveyance')}>
          <div className="stack-2">
            <Segmented ariaLabel="attestationConveyance" value={draft.attestationConveyance} options={AT_OPTIONS}
              onChange={(v) => setDraft({ ...draft, attestationConveyance: v })} />
            {at && <div className="desc-box">{draft.attestationConveyance} — {at.text}</div>}
          </div>
        </SetRow>
      </Card>

      <SaveBar
        count={localChanges.length}
        summary={changedFieldNames(localChanges).join(' · ')}
        onRevert={handleRevert}
        onSave={() => void handlePreview()}
        saving={saving}
        saveLabel="저장…"
      />

      <DiffDialog open={showDiff} onClose={() => setShowDiff(false)} changes={diffChanges} onConfirm={() => void handleSave()} saving={saving} />
      {guard}
    </div>
  );
}

// ── DiffDialog (V2 "변경 사항 확인") ──────────────────────────────────────────

function DiffDialog({ open, onClose, changes, onConfirm, saving }: {
  open: boolean;
  onClose: () => void;
  changes: Change[];
  onConfirm: () => void;
  saving?: boolean;
}) {
  return (
    <Dialog open={open} onClose={onClose} title="변경 사항 확인" sub="다음 ceremony 부터 적용됩니다."
      footer={<>
        <Button onClick={onClose}>취소</Button>
        <Button variant="primary" onClick={onConfirm} disabled={saving}>{changes.length}개 변경 사항 저장</Button>
      </>}>
      <div style={{ border: '1px solid var(--line)', borderRadius: 'var(--r)', overflow: 'hidden' }}>
        {changes.map((c, i) => <DiffRow key={c.key} c={c} last={i === changes.length - 1} />)}
        {changes.length === 0 && <p className="muted" style={{ margin: 0, padding: '12px 14px', fontSize: 13 }}>변경 사항이 없습니다.</p>}
      </div>
      <Alert tone="warn">저장 시 <span className="mono">WEBAUTHN_CONFIG_UPDATED</span> 감사 이벤트가 기록됩니다.</Alert>
    </Dialog>
  );
}

const FROM_TAG: CSSProperties = { background: 'var(--bad-soft)', color: 'var(--bad-ink)', textDecoration: 'line-through', maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis' };
const TO_TAG: CSSProperties = { background: 'var(--ok-soft)', color: 'var(--ok-ink)', maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis' };

function DiffRow({ c, last }: { c: Change; last?: boolean }) {
  const rowStyle: CSSProperties = { gap: 10, padding: '12px 14px', borderBottom: last ? 0 : '1px solid var(--line-soft)', fontSize: 12 };
  const keyEl = <span className="mono" style={{ width: 92, flex: 'none', color: 'var(--mute)' }}>{c.key}</span>;
  // origin-array diff: enumerate added / removed values
  if (Array.isArray(c.from) && Array.isArray(c.to)) {
    const from = c.from as string[];
    const to = c.to as string[];
    const added = to.filter((x) => !from.includes(x));
    const removed = from.filter((x) => !to.includes(x));
    return (
      <div className="row" style={rowStyle}>
        {keyEl}
        {removed.map((x) => <span key={'-' + x} className="tag" style={FROM_TAG} title={x}>- {x}</span>)}
        {added.map((x) => <span key={'+' + x} className="tag" style={TO_TAG} title={x}>+ {x}</span>)}
      </div>
    );
  }
  return (
    <div className="row" style={rowStyle}>
      {keyEl}
      <span className="tag" style={FROM_TAG}>{String(c.from)}</span>
      <span aria-hidden="true" style={{ display: 'inline-flex', color: 'var(--mute)' }}><Icons.ChevronRight size={14} /></span>
      <span className="tag" style={TO_TAG}>{String(c.to)}</span>
    </div>
  );
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `npx vitest run src/pages/tenant/WebauthnConfigTab.view.test.tsx src/pages/tenant/WebauthnConfigTab.test.ts`
Expected: PASS — 신규 9개 + 기존 `validateOrigin` 15개.

- [ ] **Step 6: 전체 테스트·린트·빌드**

Run: `npm test && npm run lint && npm run build`
Expected: PASS, 빌드 성공. `grep -n "🤖\|변경 사항 {localChanges" src/pages/tenant/WebauthnConfigTab.tsx` 결과 없음.

- [ ] **Step 7: 수동 확인**

`npm run dev` → Tenant 상세 `WebAuthn` 탭: timeoutMs 를 `120000` 으로 바꾸면 입력 테두리 주황 + `변경됨`, 하단 고정 SaveBar `저장하지 않은 변경 1건 · timeoutMs`. 이 상태에서 `AAGUID 정책` 탭이나 사이드바 `활동` 클릭 → `저장하지 않은 변경이 있습니다` 다이얼로그(머무르기 포커스). 새로고침 시 브라우저 이탈 경고. `저장…` → `변경 사항 확인` 다이얼로그에서 `60000 → 120000` 표시 → 저장 후 SaveBar 사라짐.

- [ ] **Step 8: 커밋**

```bash
git add src/pages/tenant/SetRow.tsx src/pages/tenant/WebauthnConfigTab.tsx src/pages/tenant/WebauthnConfigTab.view.test.tsx
git commit -m "feat(admin-ui): V2 WebAuthn 설정 — 2열 행·ChipInput origins·변경 필드 표시·SaveBar·이탈 확인

- 헤더의 변경 배지/되돌리기/저장… 버튼 → 하단 SaveBar(저장… = 기존 diff 확인)
- origins 는 ChipInput(validateOrigin + 정규화 후 중복 검사), APK 지문 입력 유지
- useUnsavedGuard: ?tab= 전환·사이드바 이동·새로고침 차단
- API 호출·payload(diff/update, _mdsRequired 보존) 동일

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 22: AAGUID 정책 (ChoiceCard · MDS 확인 배지 · SaveBar · 이탈 확인)

V2 아트보드: `v2/Tenant-Aaguid.dc.html`.

**Files:**
- Rewrite: `admin-ui/src/pages/tenant/AaguidPolicyTab.tsx`
- Modify: `admin-ui/src/pages/TenantDetailPage.tsx`(저장 후 탭 점 갱신용 `onSaved` 전달)
- Test: `admin-ui/src/pages/tenant/AaguidPolicyTab.view.test.tsx`(신규), `admin-ui/src/pages/tenant/AaguidPolicyTab.test.ts`(기존 `formatAaguid`, 변경 없이 통과)

**Interfaces:**
- Consumes: `SetRow`(Task 21), `unverifiedAaguidCount`(Task 20), `@/ui` 의 `Alert`, `Badge`, `Button`, `Card`, `CardHeader`, `ChoiceCard`, `IconButton`, `Switch`, `SaveBar`, `useUnsavedGuard`, `useToast`; `AAGUID_MODE_LABELS`; 기존 `aaguidPolicyApi.get/update`.
- Produces:
  - `AaguidPolicyTab({ tenant: Tenant; onSaved?: (p: AaguidPolicy) => void })` — 저장 성공 시 서버 응답으로 `onSaved` 호출.
  - `aaguidChangedFields(saved: AaguidPolicy | null, draft: AaguidPolicy | null): string[]` — `'mode' | 'AAGUID 목록' | 'MDS strict'` 순.
  - 기존 `formatAaguid(raw)` 유지.

보존하는 동작(이름 그대로): `formatAaguid`, 로드 effect(`AAGUID policy 로드 실패` 토스트), `uuidRe`/`inputValid`, `dirty`(JSON 비교), `list`(mode≠ANY 일 때 `aaguid` 있는 항목), `setList`, `add`(소문자, 중복 무시, `{ aaguid, note: null, mdsName: null }`), `handleSave`(`aaguidPolicyApi.update(tenant.id, draft)` → `AAGUID 정책 저장 완료` / `저장 실패`), `handleRevert`. 저장은 확인 다이얼로그 없이 바로(기존과 동일).
변경: 헤더 버튼 → `SaveBar`, 커스텀 Toggle → `Switch`, mode 버튼 → `ChoiceCard` radiogroup, 행에 지문 아바타·모델명(`mdsName ?? 'MDS 에 없음'`)·`MDS 확인`(ok)/`미확인`(warn) 배지·삭제 `IconButton`, 저장된 정책에 미확인 항목이 있으면 카드 위 warn `Alert`(탭 점 사유). MDS Strict 설명은 V2 문구.

- [ ] **Step 1: 실패하는 화면 테스트**

```tsx
// admin-ui/src/pages/tenant/AaguidPolicyTab.view.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, fireEvent, within, act, waitFor, cleanup } from '@testing-library/react';
import { useNavigate } from 'react-router-dom';
import { ToastHost } from '@/ui';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import type { Tenant, AaguidPolicy } from '@/api/designTypes';

vi.mock('@/api/aaguidPolicy', () => ({ aaguidPolicyApi: { get: vi.fn(), update: vi.fn() } }));

import { aaguidPolicyApi } from '@/api/aaguidPolicy';
import AaguidPolicyTab, { aaguidChangedFields } from './AaguidPolicyTab';

const T: Tenant = {
  id: 't-1', name: 'Acme', slug: 'acme', rpId: 'passkey.acme.com', status: 'ACTIVE', credentials: 0, apiKeys: 1,
  lastEventAt: null, createdAt: '2026-03-01T00:00:00Z', userVerification: 'REQUIRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const YUBI = { aaguid: 'ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4', note: null, mdsName: 'YubiKey 5' };
const UNKNOWN = { aaguid: '00000000-0000-0000-0000-000000000001', note: null, mdsName: null };
const POLICY: AaguidPolicy = { mode: 'ALLOWLIST', mdsStrict: true, entries: [YUBI, UNKNOWN] };

function Host({ onSaved }: { onSaved?: (p: AaguidPolicy) => void }) {
  const nav = useNavigate();
  return (
    <ToastHost>
      <AaguidPolicyTab tenant={T} onSaved={onSaved} />
      <button type="button" onClick={() => nav('/tenants/t-1?tab=webauthn')}>WebAuthn 탭으로</button>
    </ToastHost>
  );
}
const renderTab = (onSaved?: (p: AaguidPolicy) => void) => renderWithDataRouter(<Host onSaved={onSaved} />, { initialPath: '/tenants/t-1?tab=aaguid' });
const bar = () => screen.queryByRole('region', { name: '저장되지 않은 변경' });

beforeEach(() => {
  vi.clearAllMocks();
  (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ ...POLICY, entries: [...POLICY.entries] });
});
afterEach(cleanup);

describe('aaguidChangedFields', () => {
  it('바뀐 필드 이름', () => {
    expect(aaguidChangedFields(POLICY, POLICY)).toEqual([]);
    expect(aaguidChangedFields(POLICY, { ...POLICY, mode: 'DENYLIST', mdsStrict: false })).toEqual(['mode', 'MDS strict']);
    expect(aaguidChangedFields(POLICY, { ...POLICY, entries: [YUBI] })).toEqual(['AAGUID 목록']);
    expect(aaguidChangedFields(null, POLICY)).toEqual([]);
  });
});

describe('AaguidPolicyTab V2', () => {
  it('mode radiogroup, MDS 확인/미확인 배지, 탭 점 사유 Alert', async () => {
    renderTab();
    const group = await screen.findByRole('radiogroup', { name: 'mode' });
    expect(within(group).getAllByRole('radio')).toHaveLength(3);
    expect(within(group).getByRole('radio', { name: /허용 목록/ })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByText('YubiKey 5')).toBeInTheDocument();
    expect(screen.getByText('MDS 확인')).toHaveClass('badge--success');
    expect(screen.getByText('MDS 에 없음')).toBeInTheDocument();
    expect(screen.getByText('미확인')).toHaveClass('badge--warning');
    expect(screen.getByText(/허용 목록에 MDS 에서 확인되지 않은 AAGUID 가 1건 있습니다/)).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'MDS strict 켜짐' })).toBeChecked();
    expect(bar()).toBeNull();
  });

  it('미확인 항목이 없으면 Alert 가 없다', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ ...POLICY, entries: [YUBI] });
    renderTab();
    await screen.findByText('YubiKey 5');
    expect(screen.queryByText(/탭 점 배지 사유/)).toBeNull();
  });

  it('추가: 자동 포맷 → + 추가 → SaveBar → 저장 시 기존과 같은 payload, onSaved 호출', async () => {
    const onSaved = vi.fn();
    const added = { aaguid: 'abcdef01-2345-6789-abcd-ef0123456789', note: null, mdsName: null };
    const saved: AaguidPolicy = { ...POLICY, entries: [YUBI, UNKNOWN, { ...added, mdsName: 'Some Key' }] };
    (aaguidPolicyApi.update as ReturnType<typeof vi.fn>).mockResolvedValue(saved);
    renderTab(onSaved);
    const input = await screen.findByRole('textbox', { name: 'AAGUID 입력' });
    const addBtn = screen.getByRole('button', { name: '추가' });
    expect(addBtn).toBeDisabled();
    fireEvent.change(input, { target: { value: 'ABCDEF0123456789ABCDEF0123456789' } });
    expect(input).toHaveValue('abcdef01-2345-6789-abcd-ef0123456789');
    fireEvent.click(addBtn);
    expect(input).toHaveValue('');
    expect(screen.getByText('abcdef01-2345-6789-abcd-ef0123456789')).toBeInTheDocument();
    expect(bar()).toHaveTextContent('저장하지 않은 변경 1건');
    expect(bar()).toHaveTextContent('AAGUID 목록');
    await act(async () => { fireEvent.click(within(bar()!).getByRole('button', { name: '저장' })); });
    expect(aaguidPolicyApi.update).toHaveBeenCalledWith('t-1', { mode: 'ALLOWLIST', mdsStrict: true, entries: [YUBI, UNKNOWN, added] });
    expect(onSaved).toHaveBeenCalledWith(saved);
    await waitFor(() => expect(bar()).toBeNull());
    expect(screen.getByText('AAGUID 정책 저장 완료')).toBeInTheDocument();
  });

  it('중복 AAGUID 는 추가되지 않는다', async () => {
    renderTab();
    const input = await screen.findByRole('textbox', { name: 'AAGUID 입력' });
    fireEvent.change(input, { target: { value: YUBI.aaguid.toUpperCase() } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getAllByText(YUBI.aaguid)).toHaveLength(1);
    expect(bar()).toBeNull();
  });

  it('삭제 IconButton → 목록 변경, 되돌리기로 복구', async () => {
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: `${UNKNOWN.aaguid} 삭제` }));
    expect(screen.queryByText('MDS 에 없음')).toBeNull();
    expect(bar()).toHaveTextContent('AAGUID 목록');
    fireEvent.click(within(bar()!).getByRole('button', { name: '되돌리기' }));
    expect(screen.getByText('MDS 에 없음')).toBeInTheDocument();
    expect(bar()).toBeNull();
  });

  it('전체 허용 선택 시 목록 행이 숨고 SaveBar 요약은 mode', async () => {
    renderTab();
    fireEvent.click(await screen.findByRole('radio', { name: /전체 허용/ }));
    expect(screen.queryByRole('textbox', { name: 'AAGUID 입력' })).toBeNull();
    expect(bar()).toHaveTextContent('저장하지 않은 변경 1건');
    expect(bar()).toHaveTextContent('mode');
  });

  it('빈 허용 목록 문구', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ mode: 'ALLOWLIST', mdsStrict: false, entries: [] });
    renderTab();
    expect(await screen.findByText('비어 있음 — 이 상태에서는 모든 ceremony가 차단됩니다.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('radio', { name: /차단 목록/ }));
    expect(screen.getByText('비어 있음 — 이 상태에서는 모든 ceremony가 허용됩니다.')).toBeInTheDocument();
    expect(screen.getByText('차단된 AAGUID')).toBeInTheDocument();
  });

  it('MDS strict 스위치 → SaveBar 요약', async () => {
    renderTab();
    fireEvent.click(await screen.findByRole('switch', { name: 'MDS strict 켜짐' }));
    expect(screen.getByRole('switch', { name: 'MDS strict 꺼짐' })).not.toBeChecked();
    expect(bar()).toHaveTextContent('MDS strict');
  });

  it('dirty 면 ?tab=webauthn 이동이 막힌다', async () => {
    const { router } = renderTab();
    fireEvent.click(await screen.findByRole('radio', { name: /차단 목록/ }));
    await act(async () => { fireEvent.click(screen.getByText('WebAuthn 탭으로')); });
    expect(screen.getByRole('alertdialog', { name: '저장하지 않은 변경이 있습니다' })).toBeInTheDocument();
    expect(router.state.location.search).toBe('?tab=aaguid');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '이동' })); });
    expect(router.state.location.search).toBe('?tab=webauthn');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/tenant/AaguidPolicyTab.view.test.tsx`
Expected: FAIL — `aaguidChangedFields` export 없음, `radiogroup`·`AAGUID 입력`·`switch` 없음.

- [ ] **Step 3: AaguidPolicyTab.tsx 교체**

```tsx
// admin-ui/src/pages/tenant/AaguidPolicyTab.tsx
import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { aaguidPolicyApi } from '@/api/aaguidPolicy';
import type { Tenant, AaguidPolicy } from '@/api/designTypes';
import { AAGUID_MODE_LABELS } from '@/i18n/labels';
import { Alert, Badge, Button, Card, CardHeader, ChoiceCard, IconButton, SaveBar, Switch, useToast, useUnsavedGuard } from '@/ui';
import { SetRow } from './SetRow';
import { unverifiedAaguidCount } from './tenantSignals';

// ── AAGUID 입력 자동 포맷 ─────────────────────────────────────────────────────
// 입력값에서 16진수만 남기고(최대 32자) UUID 형식 8-4-4-4-12 위치에 하이픈을
// 자동 삽입한다. 사용자가 하이픈을 직접 치거나 대문자를 넣어도 정규화된다.
//  "ea9b8d664d01" → "ea9b8d66-4d01"
//  "EA9B8D66-4D01-1D21-3CE4-B6B48CB575D4" → 동일(소문자) 결과
export function formatAaguid(raw: string): string {
  const hex = raw.toLowerCase().replace(/[^0-9a-f]/g, '').slice(0, 32);
  const seg = [8, 4, 4, 4, 12];
  const out: string[] = [];
  let i = 0;
  for (const len of seg) {
    if (i >= hex.length) break;
    out.push(hex.slice(i, i + len));
    i += len;
  }
  return out.join('-');
}

/** SaveBar 요약용 변경 필드 이름. */
export function aaguidChangedFields(saved: AaguidPolicy | null, draft: AaguidPolicy | null): string[] {
  if (!saved || !draft) return [];
  const out: string[] = [];
  if (saved.mode !== draft.mode) out.push('mode');
  if (JSON.stringify(saved.entries) !== JSON.stringify(draft.entries)) out.push('AAGUID 목록');
  if (saved.mdsStrict !== draft.mdsStrict) out.push('MDS strict');
  return out;
}

const MODES: { v: AaguidPolicy['mode']; d: string }[] = [
  { v: 'ANY', d: '모든 authenticator 허용' },
  { v: 'ALLOWLIST', d: '지정된 AAGUID만 허용' },
  { v: 'DENYLIST', d: '지정된 AAGUID만 차단' },
];

// ── AaguidPolicyTab ───────────────────────────────────────────────────────────

export default function AaguidPolicyTab({ tenant, onSaved }: { tenant: Tenant; onSaved?: (p: AaguidPolicy) => void }) {
  const [policy, setPolicy] = useState<AaguidPolicy | null>(null);
  const [draft, setDraft] = useState<AaguidPolicy | null>(null);
  const [aaguidInput, setAaguidInput] = useState('');
  const [saving, setSaving] = useState(false);
  const toast = useToast();

  const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const inputValid = uuidRe.test(aaguidInput);
  const dirty = policy !== null && draft !== null && JSON.stringify(policy) !== JSON.stringify(draft);
  const changedFields = aaguidChangedFields(policy, draft);
  const guard = useUnsavedGuard(dirty);

  useEffect(() => {
    aaguidPolicyApi
      .get(tenant.id)
      .then((p) => {
        setPolicy(p);
        setDraft(p);
      })
      .catch((e: unknown) => {
        const msg = e instanceof Error ? e.message : String(e);
        toast({ kind: 'err', title: 'AAGUID policy 로드 실패', message: msg });
      });
  }, [tenant.id]);

  const list =
    draft == null
      ? []
      : draft.mode === 'ALLOWLIST'
        ? draft.entries.filter((e) => e.aaguid)
        : draft.mode === 'DENYLIST'
          ? draft.entries.filter((e) => e.aaguid)
          : [];

  function setList(next: AaguidPolicy['entries']) {
    if (!draft) return;
    setDraft({ ...draft, entries: next });
  }

  function add() {
    if (!inputValid || !draft) return;
    const lower = aaguidInput.toLowerCase();
    if (list.some((e) => e.aaguid === lower)) return;
    setList([...list, { aaguid: lower, note: null, mdsName: null }]);
    setAaguidInput('');
  }

  async function handleSave() {
    if (!draft) return;
    setSaving(true);
    try {
      const updated = await aaguidPolicyApi.update(tenant.id, draft);
      setPolicy(updated);
      setDraft(updated);
      onSaved?.(updated);
      toast({ kind: 'ok', title: 'AAGUID 정책 저장 완료' });
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '저장 실패', message: msg });
    } finally {
      setSaving(false);
    }
  }

  function handleRevert() {
    if (!policy) return;
    setDraft({ ...policy, entries: [...policy.entries] });
  }

  if (!draft || !policy) {
    return <div className="muted" style={{ padding: 40 }}>Loading…</div>;
  }

  // 탭 점과 같은 기준(저장된 정책). 방금 추가한 항목은 저장 후 서버가 mdsName 을 채운다.
  const unknownCount = unverifiedAaguidCount(policy);

  return (
    <div className="stack-6">
      {unknownCount > 0 && (
        <Alert tone="warn">
          <strong>탭 점 배지 사유</strong> · {AAGUID_MODE_LABELS[policy.mode]}에 MDS 에서 확인되지 않은 AAGUID 가 {unknownCount}건 있습니다.
        </Alert>
      )}

      <Card>
        <CardHeader title="AAGUID Attestation Policy" sub="등록 시 허용할 authenticator 모델을 AAGUID 로 제한합니다." />

        <SetRow label="mode" desc="정책 방식" changed={changedFields.includes('mode')}>
          <div className="choices" role="radiogroup" aria-label="mode">
            {MODES.map((o) => (
              <ChoiceCard key={o.v} checked={draft.mode === o.v} onSelect={() => setDraft({ ...draft, mode: o.v })}
                title={AAGUID_MODE_LABELS[o.v]} description={o.d} />
            ))}
          </div>
        </SetRow>

        {draft.mode !== 'ANY' && (
          <SetRow label={draft.mode === 'ALLOWLIST' ? '허용된 AAGUID' : '차단된 AAGUID'} changed={changedFields.includes('AAGUID 목록')}
            desc="mode 가 전체 허용이 아닐 때만 표시. MDS 에서 모델명을 자동으로 찾아 붙입니다.">
            <div className="stack-3" style={{ gap: 10 }}>
              <div className="row" style={{ gap: 8, flexWrap: 'nowrap' }}>
                <input className="input mono" aria-label="AAGUID 입력" placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" maxLength={36}
                  value={aaguidInput}
                  onChange={(e) => setAaguidInput(formatAaguid(e.target.value))}
                  onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }} />
                <Button icon={<Icons.Plus size={14} />} disabled={!inputValid} onClick={add}>추가</Button>
              </div>
              <div style={{ border: '1px solid var(--line)', borderRadius: 'var(--r)', overflow: 'hidden' }}>
                {list.length === 0 ? (
                  <p className="hint" style={{ margin: 0, padding: '12px 14px' }}>
                    비어 있음 — 이 상태에서는 {draft.mode === 'ALLOWLIST' ? '모든 ceremony가 차단' : '모든 ceremony가 허용'}됩니다.
                  </p>
                ) : (
                  <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                    {list.map((entry, i) => (
                      <li key={entry.aaguid} className="row" style={{ flexWrap: 'nowrap', gap: 12, padding: '12px 14px', borderBottom: i === list.length - 1 ? 0 : '1px solid var(--line-soft)' }}>
                        <span className="avatar avatar--sq" aria-hidden="true"><Icons.Fingerprint size={16} /></span>
                        <div className="col" style={{ flex: '1 1 0', minWidth: 0 }}>
                          <strong className="truncate" style={{ fontSize: 13 }} title={entry.mdsName ?? undefined}>{entry.mdsName ?? 'MDS 에 없음'}</strong>
                          <span className="mono hint truncate" style={{ margin: 0 }}>{entry.aaguid}</span>
                        </div>
                        {entry.mdsName == null ? <Badge tone="warn">미확인</Badge> : <Badge tone="ok">MDS 확인</Badge>}
                        <IconButton size="sm" label={`${entry.aaguid} 삭제`} icon={<Icons.X size={14} />}
                          onClick={() => setList(list.filter((x) => x.aaguid !== entry.aaguid))} />
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          </SetRow>
        )}

        <SetRow label="MDS Strict 모드" desc="MDS 에 없거나 REVOKED 상태인 authenticator 의 등록을 거부합니다." changed={changedFields.includes('MDS strict')}>
          <Switch checked={draft.mdsStrict} onChange={(v) => setDraft({ ...draft, mdsStrict: v })}
            label={draft.mdsStrict ? 'MDS strict 켜짐' : 'MDS strict 꺼짐'} />
        </SetRow>
      </Card>

      <SaveBar count={changedFields.length} summary={changedFields.join(' · ')} onRevert={handleRevert} onSave={() => void handleSave()} saving={saving} />
      {guard}
    </div>
  );
}
```

- [ ] **Step 4: TenantDetailPage 에서 저장 후 탭 점 갱신**

`src/pages/TenantDetailPage.tsx` 의 AAGUID 탭 렌더 줄을 바꾼다:

```tsx
      {currentTab === 'aaguid' && <AaguidPolicyTab tenant={tenant} onSaved={setAaguidPolicy} />}
```

`src/pages/TenantDetailPage.test.tsx` 의 `vi.mock('@/pages/tenant/AaguidPolicyTab', …)` 를 다음으로 바꾸고, `describe('탭 점 배지', …)` 안에 테스트를 추가한다:

```tsx
vi.mock('@/pages/tenant/AaguidPolicyTab', () => ({
  default: (p: { onSaved?: (x: unknown) => void }) => (
    <button type="button" onClick={() => p.onSaved?.({ mode: 'ALLOWLIST', mdsStrict: false, entries: [{ aaguid: 'a', note: null, mdsName: 'YubiKey 5' }] })}>AAGUID 저장 시뮬레이션</button>
  ),
}));
```

```tsx
  it('AAGUID 저장 후 onSaved 로 점이 갱신된다', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      mode: 'ALLOWLIST', mdsStrict: false, entries: [{ aaguid: 'a', note: null, mdsName: null }],
    });
    renderPage({ tab: 'aaguid' });
    const tab = screen.getByRole('tab', { name: /AAGUID 정책/ });
    expect(await within(tab).findByLabelText('확인 필요')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'AAGUID 저장 시뮬레이션' }));
    expect(within(tab).queryByLabelText('확인 필요')).toBeNull();
  });
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `npx vitest run src/pages/tenant/AaguidPolicyTab.view.test.tsx src/pages/tenant/AaguidPolicyTab.test.ts src/pages/TenantDetailPage.test.tsx`
Expected: PASS — 신규 10개 + 기존 `formatAaguid` 11개 + 상세 페이지 테스트(추가 1개 포함).

- [ ] **Step 6: 전체 테스트·린트·빌드**

Run: `npm test && npm run lint && npm run build`
Expected: PASS, 빌드 성공. `grep -n "function Toggle\|function Field" src/pages/tenant/AaguidPolicyTab.tsx` 결과 없음.

- [ ] **Step 7: 수동 확인**

`npm run dev` → `AAGUID 정책` 탭: 허용 목록에 MDS 미확인 항목이 있으면 카드 위 주황 Alert 와 헤더 탭 점이 함께 보임. 행: 지문 아바타 · 모델명 · `MDS 확인`/`미확인` 배지 · ✕. `전체 허용` 선택 → 목록 행이 사라지고 SaveBar `저장하지 않은 변경 1건 · mode`, 이 상태로 다른 탭 클릭 → 이탈 확인. 저장 → SaveBar 사라짐, 미확인 항목을 모두 지우고 저장하면 탭 점도 사라짐.

- [ ] **Step 8: 커밋**

```bash
git add src/pages/tenant/AaguidPolicyTab.tsx src/pages/tenant/AaguidPolicyTab.view.test.tsx src/pages/TenantDetailPage.tsx src/pages/TenantDetailPage.test.tsx
git commit -m "feat(admin-ui): V2 AAGUID 정책 — ChoiceCard mode·MDS 확인/미확인 배지·Switch·SaveBar·이탈 확인

- 저장된 정책에 MDS 미확인 항목이 있으면 탭 점 사유 Alert
- 저장 성공 시 onSaved 로 헤더 탭 점 갱신
- API 호출·payload(aaguidPolicyApi.update) 동일

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 23: API 키 탭 + 다이얼로그 (발급 · 발급 완료 · 회수 · 회전)

V2 아트보드: `v2/Tenant-ApiKeys.dc.html`, `v2/Dialogs-ApiKey.dc.html`.

이 태스크는 Tenant 탭 4개(Task 23~26)가 함께 쓰는 표시 헬퍼 `tabFormat.ts` 와 키·값 목록 `KvList.tsx` 도 만든다.

**Files:**
- Create: `admin-ui/src/pages/tenant/tabFormat.ts`, `admin-ui/src/pages/tenant/tabFormat.test.ts`
- Create: `admin-ui/src/pages/tenant/KvList.tsx`
- Create: `admin-ui/src/pages/tenant/apiKeyHelpers.ts`, `admin-ui/src/pages/tenant/apiKeyHelpers.test.ts`
- Rewrite: `admin-ui/src/pages/tenant/ApiKeysTab.tsx`
- Create: `admin-ui/src/pages/tenant/ApiKeysTab.test.tsx`
- Modify: `admin-ui/src/api/types.ts`(`ApiKeyView.keySuffix?`), `admin-ui/src/api/designTypes.ts`(`ApiKey.keySuffix?`), `admin-ui/src/api/apiKeys.ts`(adapt), `admin-ui/src/api/apiKeys.test.ts`

**Interfaces:**
- Consumes: `@/ui` — `Alert`, `Badge`, `Button`, `Card`, `CardHeader`, `DataTable`, `type Column`, `Dialog`(`dismissible`, `role`, `initialFocusRef`, `footerStart`), `EmptyState`, `FilterChip`, `KpiCard`, `Menu`, `SecretBox`, `StatusBadge`, `useToast`, `ToastHost`(테스트). `illustrations.apiKeyIssued`. `apiKeysApi.list/create/rotate/revoke`(시그니처·전송값 변경 없음), `copyToClipboard`.
- Produces:
  - `tabFormat.ts`: `ymd(iso: string | null | undefined): string`(KST `YYYY-MM-DD`), `ymdHm(iso)`(`YYYY-MM-DD HH:mm`), `hms(iso)`(`HH:mm:ss`), `ymdHms(iso)`(`YYYY-MM-DD HH:mm:ss`), `timeAgo(iso, now = Date.now()): string`, `fmtNum(n: number): string`. 값이 없으면 `'—'`, 파싱 실패면 원문.
  - `KvList({ items: { k: ReactNode; v: ReactNode; mono?: boolean }[]; keyWidth?: number /*130*/; columns?: 1 | 2; boxed?: boolean })` — `<dl className="kv">`(Task 3 공용 클래스 + 키 폭 지정).
  - `apiKeyHelpers.ts`: `ROTATION_DAYS = 90`, `EXPIRY_WARN_DAYS`(Task 16 `@/lib/apiKeyExpiry` 재노출), `type ExpiryState = 'none'|'expired'|'soon'|'ok'`, `expiryState(expiresAt: string | null, now?): ExpiryState`, `daysLeft(expiresAt: string, now?): number`(올림), `rotationOverdueCount(keys: Pick<ApiKey,'status'|'createdAt'>[], now?, days = ROTATION_DAYS): number`(ACTIVE 이고 `now - createdAt > days` 인 키 수), `latestCreatedAt(keys): string | null`, `keyStatusCounts(keys): { active; expired; revoked }`, `previewExpiry(months: number | null, now = new Date()): string | null`(기존 로직 이동).
  - `ApiKeyView.keySuffix?: string`, `ApiKey.keySuffix?: string`(스펙 §6 ⑤). 어댑터는 서버 값이 있을 때만 키를 만든다. 없으면 `pk_prefix…` 만 렌더.
  - ⋯ 메뉴 트리거 aria-label: `` `${k.name} 키 액션` ``.

- [ ] **Step 1: 실패하는 테스트 작성 — 표시 헬퍼 · API 키 헬퍼**

```ts
// admin-ui/src/pages/tenant/tabFormat.test.ts
import { describe, it, expect } from 'vitest';
import { ymd, ymdHm, hms, ymdHms, timeAgo, fmtNum } from './tabFormat';

describe('tabFormat (KST 고정)', () => {
  it('UTC 를 KST 로 바꿔 날짜·시각을 만든다', () => {
    expect(ymd('2026-06-07T15:30:05Z')).toBe('2026-06-08');
    expect(ymdHm('2026-06-07T15:30:05Z')).toBe('2026-06-08 00:30');
    expect(hms('2026-06-07T15:30:05Z')).toBe('00:30:05');
    expect(ymdHms('2026-06-07T15:30:05Z')).toBe('2026-06-08 00:30:05');
  });
  it('빈 값은 —, 파싱 실패는 원문', () => {
    expect(ymd(null)).toBe('—');
    expect(ymdHm(undefined)).toBe('—');
    expect(ymd('not-a-date')).toBe('not-a-date');
  });
  it('timeAgo', () => {
    const now = Date.parse('2026-10-11T00:00:00Z');
    expect(timeAgo('2026-10-10T23:59:30Z', now)).toBe('방금 전');
    expect(timeAgo('2026-10-10T23:15:00Z', now)).toBe('45분 전');
    expect(timeAgo('2026-10-10T21:00:00Z', now)).toBe('3시간 전');
    expect(timeAgo('2026-10-01T00:00:00Z', now)).toBe('10일 전');
    expect(timeAgo('2026-07-01T00:00:00Z', now)).toBe('3개월 전');
    expect(timeAgo(null, now)).toBe('—');
  });
  it('fmtNum 은 천 단위 구분', () => {
    expect(fmtNum(1234567)).toBe('1,234,567');
  });
});
```

```ts
// admin-ui/src/pages/tenant/apiKeyHelpers.test.ts
import { describe, it, expect } from 'vitest';
import { rotationOverdueCount, expiryState, daysLeft, latestCreatedAt, keyStatusCounts, previewExpiry, ROTATION_DAYS } from './apiKeyHelpers';

const NOW = Date.parse('2026-10-11T00:00:00Z');
const DAY = 86_400_000;
const at = (days: number) => new Date(NOW + days * DAY).toISOString();

describe('rotationOverdueCount', () => {
  it('ACTIVE 이면서 생성 후 90일을 넘긴 키만 센다', () => {
    const keys = [
      { status: 'ACTIVE' as const, createdAt: at(-91) },   // 초과
      { status: 'ACTIVE' as const, createdAt: at(-89) },   // 아직
      { status: 'ACTIVE' as const, createdAt: at(-90) },   // 정확히 90일 — 초과 아님
      { status: 'REVOKED' as const, createdAt: at(-200) }, // 회수 키 제외
      { status: 'EXPIRED' as const, createdAt: at(-200) }, // 만료 키 제외
      { status: 'ACTIVE' as const, createdAt: 'garbage' }, // 파싱 실패 제외
    ];
    expect(ROTATION_DAYS).toBe(90);
    expect(rotationOverdueCount(keys, NOW)).toBe(1);
  });
  it('빈 목록은 0', () => {
    expect(rotationOverdueCount([], NOW)).toBe(0);
  });
});

describe('expiryState / daysLeft', () => {
  it('없음·만료·30일 이내·그 외', () => {
    expect(expiryState(null, NOW)).toBe('none');
    expect(expiryState(at(-1), NOW)).toBe('expired');
    expect(expiryState(at(10), NOW)).toBe('soon');
    expect(expiryState(at(30), NOW)).toBe('soon');
    expect(expiryState(at(31), NOW)).toBe('ok');
  });
  it('daysLeft 는 올림', () => {
    expect(daysLeft(at(9.2), NOW)).toBe(10);
  });
});

describe('기타 집계', () => {
  it('latestCreatedAt / keyStatusCounts', () => {
    const keys = [
      { status: 'ACTIVE' as const, createdAt: at(-3) },
      { status: 'REVOKED' as const, createdAt: at(-1) },
      { status: 'EXPIRED' as const, createdAt: at(-9) },
    ];
    expect(latestCreatedAt(keys)).toBe(at(-1));
    expect(latestCreatedAt([])).toBeNull();
    expect(keyStatusCounts(keys)).toEqual({ active: 1, expired: 1, revoked: 1 });
  });
  it('previewExpiry: 말일 보정(1/31 + 1개월 → 2/28), 무기한은 null', () => {
    // 기대값도 같은 KST 변환을 거쳐 만들어 실행 머신의 시간대와 무관하게 한다.
    const feb28 = new Date(2026, 1, 28, 12).toLocaleDateString('en-CA', { timeZone: 'Asia/Seoul' });
    expect(previewExpiry(1, new Date(2026, 0, 31, 12))).toBe(feb28);
    expect(previewExpiry(null)).toBeNull();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/tenant/tabFormat.test.ts src/pages/tenant/apiKeyHelpers.test.ts`
Expected: FAIL — `Failed to resolve import "./tabFormat"` / `"./apiKeyHelpers"`.

- [ ] **Step 3: 헬퍼 구현**

```ts
// admin-ui/src/pages/tenant/tabFormat.ts
// Tenant 탭(API 키·Credentials·감사 로그·퍼널) 공용 표시 헬퍼. 시간대는 KST 고정.
const TZ = 'Asia/Seoul';
const DATE = new Intl.DateTimeFormat('en-CA', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit' }); // YYYY-MM-DD
const HM = new Intl.DateTimeFormat('en-GB', { timeZone: TZ, hour: '2-digit', minute: '2-digit', hour12: false });
const HMS = new Intl.DateTimeFormat('en-GB', { timeZone: TZ, hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });

function fmtWith(iso: string | null | undefined, f: (d: Date) => string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? iso : f(d);
}

export const ymd = (iso: string | null | undefined) => fmtWith(iso, (d) => DATE.format(d));
export const ymdHm = (iso: string | null | undefined) => fmtWith(iso, (d) => `${DATE.format(d)} ${HM.format(d)}`);
export const hms = (iso: string | null | undefined) => fmtWith(iso, (d) => HMS.format(d));
export const ymdHms = (iso: string | null | undefined) => fmtWith(iso, (d) => `${DATE.format(d)} ${HMS.format(d)}`);

export function timeAgo(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '—';
  const t = new Date(iso).getTime();
  if (isNaN(t)) return iso;
  const m = Math.floor((now - t) / 60_000);
  if (m < 1) return '방금 전';
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d}일 전`;
  return `${Math.floor(d / 30)}개월 전`;
}

export const fmtNum = (n: number) => n.toLocaleString('ko-KR');
```

```ts
// admin-ui/src/pages/tenant/apiKeyHelpers.ts
import type { ApiKey } from '@/api/designTypes';
import { EXPIRY_WARN_DAYS } from '@/lib/apiKeyExpiry'; // Task 16 에서 생성

export { EXPIRY_WARN_DAYS };
const DAY = 86_400_000;
export const ROTATION_DAYS = 90;
export type ExpiryState = 'none' | 'expired' | 'soon' | 'ok';

export function expiryState(expiresAt: string | null, now = Date.now()): ExpiryState {
  if (!expiresAt) return 'none';
  const exp = new Date(expiresAt).getTime();
  if (exp <= now) return 'expired';
  if (exp - now <= EXPIRY_WARN_DAYS * DAY) return 'soon';
  return 'ok';
}

export function daysLeft(expiresAt: string, now = Date.now()): number {
  return Math.ceil((new Date(expiresAt).getTime() - now) / DAY);
}

/** 권장 rotation(기본 90일)을 넘긴 ACTIVE 키 수. createdAt 기준. */
export function rotationOverdueCount(keys: Pick<ApiKey, 'status' | 'createdAt'>[], now = Date.now(), days = ROTATION_DAYS): number {
  return keys.filter((k) => {
    if (k.status !== 'ACTIVE') return false;
    const t = new Date(k.createdAt).getTime();
    return !isNaN(t) && now - t > days * DAY;
  }).length;
}

export function latestCreatedAt(keys: Pick<ApiKey, 'createdAt'>[]): string | null {
  let best: string | null = null;
  for (const k of keys) {
    if (best === null || new Date(k.createdAt).getTime() > new Date(best).getTime()) best = k.createdAt;
  }
  return best;
}

export function keyStatusCounts(keys: Pick<ApiKey, 'status'>[]) {
  return {
    active: keys.filter((k) => k.status === 'ACTIVE').length,
    expired: keys.filter((k) => k.status === 'EXPIRED').length,
    revoked: keys.filter((k) => k.status === 'REVOKED').length,
  };
}

// now + N개월의 날짜 미리보기(YYYY-MM-DD, Asia/Seoul). null(무기한)이면 null.
// 말일 보정: 1/31 + 1개월처럼 다음 달에 같은 일자가 없으면 그 달 말일로 클램프.
export function previewExpiry(months: number | null, now = new Date()): string | null {
  if (months == null) return null;
  const lastDayOfTargetMonth = new Date(now.getFullYear(), now.getMonth() + months + 1, 0).getDate();
  const day = Math.min(now.getDate(), lastDayOfTargetMonth);
  const target = new Date(now.getFullYear(), now.getMonth() + months, day, now.getHours(), now.getMinutes(), now.getSeconds());
  return target.toLocaleDateString('en-CA', { timeZone: 'Asia/Seoul' });
}
```

```tsx
// admin-ui/src/pages/tenant/KvList.tsx
import { Fragment, ReactNode } from 'react';

export type KvItem = { k: ReactNode; v: ReactNode; mono?: boolean };

/** 공용 `.kv` 클래스(Task 3) 위에 키 폭·2쌍 배치·박스만 덧붙인다. columns=2 면 키·값 쌍 2개씩 한 줄. */
export function KvList({ items, keyWidth = 130, columns = 1, boxed }: { items: KvItem[]; keyWidth?: number; columns?: 1 | 2; boxed?: boolean }) {
  const pair = `${keyWidth}px minmax(0, 1fr)`;
  return (
    <dl className={columns === 2 ? 'kv kv--4' : 'kv'} style={{
      gridTemplateColumns: columns === 2 ? `${pair} ${pair}` : pair,
      ...(boxed ? { padding: '12px 14px', border: '1px solid var(--line-soft)', borderRadius: 'var(--r)' } : {}),
    }}>
      {items.map((it, i) => (
        <Fragment key={i}>
          <dt>{it.k}</dt>
          <dd className={it.mono ? 'mono' : undefined} style={{ overflowWrap: 'anywhere', fontSize: it.mono ? 12 : undefined }}>{it.v}</dd>
        </Fragment>
      ))}
    </dl>
  );
}
```

- [ ] **Step 4: 헬퍼 테스트 통과 확인**

Run: `npx vitest run src/pages/tenant/tabFormat.test.ts src/pages/tenant/apiKeyHelpers.test.ts`
Expected: PASS (2 files).

- [ ] **Step 5: 실패하는 테스트 작성 — keySuffix 어댑터 · 탭 동작**

`src/api/apiKeys.test.ts` 의 `describe('apiKeysApi', …)` 안에 추가:

```ts
  it('list maps keySuffix only when the server sends it (스펙 §6 ⑤)', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(envelope([
      { id: 'k1', keyPrefix: 'pk_a', name: 'a', tenantId: 't1', scopes: ['registration'], createdAt: '2026-01-01T00:00:00Z', keySuffix: 'a1c9' },
      { id: 'k2', keyPrefix: 'pk_b', name: 'b', tenantId: 't1', scopes: ['registration'], createdAt: '2026-01-01T00:00:00Z' },
    ]));
    const keys = await apiKeysApi.list('t1');
    const [url] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('/admin/api/api-keys?tenantId=t1');
    expect(keys[0].keySuffix).toBe('a1c9');
    expect('keySuffix' in keys[1]).toBe(false);
  });
```

```tsx
// admin-ui/src/pages/tenant/ApiKeysTab.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { ToastHost } from '@/ui';
import type { ApiKey, Tenant } from '@/api/designTypes';

const api = vi.hoisted(() => ({ list: vi.fn(), create: vi.fn(), rotate: vi.fn(), revoke: vi.fn() }));
vi.mock('@/api/apiKeys', () => ({ apiKeysApi: api }));

import ApiKeysTab from './ApiKeysTab';

const tenant: Tenant = {
  id: 't1', name: 'Acme', slug: 'acme', rpId: 'acme.com', status: 'ACTIVE', credentials: 0, apiKeys: 3,
  lastEventAt: null, createdAt: '2026-01-01T00:00:00Z', userVerification: 'PREFERRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const DAY = 86_400_000;
const iso = (days: number) => new Date(Date.now() + days * DAY).toISOString();
const key = (p: Partial<ApiKey>): ApiKey => ({
  id: 'k', prefix: 'pk_live_ab', name: 'prod', status: 'ACTIVE', createdAt: iso(-10), lastUsedAt: null,
  scopes: ['registration'], expiresAt: null, ...p,
});
const renderTab = () => render(<ToastHost><ApiKeysTab tenant={tenant} /></ToastHost>);

describe('ApiKeysTab V2', () => {
  beforeEach(() => vi.clearAllMocks());

  it('⋯ 메뉴는 ACTIVE 키에만 있고, 회수는 위험 구획에서 열리며 취소에 기본 포커스', async () => {
    api.list.mockResolvedValue([
      key({ id: 'a', name: 'prod' }),
      key({ id: 'r', name: 'old', status: 'REVOKED' }),
      key({ id: 'e', name: 'exp', status: 'EXPIRED', expiresAt: iso(-1) }),
    ]);
    renderTab();
    expect(await screen.findByText('prod')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'prod 키 액션' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'old 키 액션' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'exp 키 액션' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'prod 키 액션' }));
    expect(screen.getByRole('menuitem', { name: '회전' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: '접두사 복사' })).toBeInTheDocument();
    expect(screen.getByText('위험')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('menuitem', { name: '회수' }));

    const dlg = screen.getByRole('alertdialog', { name: 'API key를 회수하시겠습니까?' });
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    expect(within(dlg).getByRole('button', { name: '회수' })).toHaveClass('btn--danger-solid');
  });

  it('키 접두사 뒤 keySuffix 는 값이 있을 때만 붙는다', async () => {
    api.list.mockResolvedValue([
      key({ id: 'a', name: 'with', keySuffix: 'a1c9' }),
      key({ id: 'b', name: 'without', prefix: 'pk_live_cd' }),
    ]);
    renderTab();
    expect(await screen.findByText('pk_live_ab…a1c9')).toBeInTheDocument();
    expect(screen.getByText('pk_live_cd…')).toBeInTheDocument();
  });

  it('30일 이내 만료 ACTIVE 키는 남은 일수를 경고로 표시, KPI 에 rotation 초과 키 수', async () => {
    api.list.mockResolvedValue([
      key({ id: 'a', name: 'soon', expiresAt: iso(10) }),
      key({ id: 'b', name: 'stale', createdAt: iso(-100) }),
      key({ id: 'c', name: 'gone', status: 'REVOKED', createdAt: iso(-200) }),
    ]);
    renderTab();
    expect(await screen.findByText(/· 10일 남음/)).toBeInTheDocument();
    expect(screen.getByText('초과 키 1개')).toHaveClass('kpi__hint--warn');
    expect(screen.getByText('활성 2 · 만료 0 · 회수 1')).toBeInTheDocument();
  });

  it('발급 완료 다이얼로그: Escape·스크림 무시, 체크 전 닫기 비활성', async () => {
    api.list.mockResolvedValue([]);
    api.create.mockResolvedValue({ key: key({ id: 'n1', name: 'staging' }), plaintext: 'pk_live_ab.secret' });
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '새 키 발급' }));
    fireEvent.change(screen.getByLabelText('용도 (이름)'), { target: { value: 'staging' } });
    fireEvent.click(screen.getByRole('button', { name: '발급' }));

    const dlg = await screen.findByRole('alertdialog', { name: /새 API key가 발급되었습니다/ });
    expect(api.create).toHaveBeenCalledWith('t1', 'staging', ['registration', 'authentication'], 24);
    expect(within(dlg).getByText('pk_live_ab.secret')).toBeInTheDocument();
    const close = within(dlg).getByRole('button', { name: /닫기 \(영구 소실\)/ });
    expect(close).toBeDisabled();

    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.mouseDown(document.querySelector('.scrim')!);
    expect(screen.getByRole('alertdialog', { name: /새 API key가 발급되었습니다/ })).toBeInTheDocument();

    fireEvent.click(within(dlg).getByRole('checkbox', { name: '안전한 장소에 복사했습니다.' }));
    expect(close).toBeEnabled();
    expect(close).toHaveTextContent(/^닫기 \(영구 소실\)$/);
    fireEvent.click(close);
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  it('회전: 24시간 겹침 안내 후 실행하면 새 키와 이전 키 만료 시간을 보여준다', async () => {
    api.list.mockResolvedValue([key({ id: 'a', name: 'prod' })]);
    api.rotate.mockResolvedValue({ id: 'a2', plaintextKey: 'pk_live_ab.new', prefix: 'pk_live_ab', scopes: ['registration'], oldKeyExpiresAt: iso(1) });
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: 'prod 키 액션' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '회전' }));
    const rot = screen.getByRole('dialog', { name: 'API key를 회전하시겠습니까?' });
    expect(within(rot).getByText('24시간 함께 유효')).toBeInTheDocument();
    fireEvent.click(within(rot).getByRole('button', { name: '회전 실행' }));
    const issued = await screen.findByRole('alertdialog', { name: /새 API key가 발급되었습니다/ });
    expect(api.rotate).toHaveBeenCalledWith('a');
    expect(within(issued).getByText(/이전 키는 24시간 후/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 6: 실패 확인**

Run: `npx vitest run src/api/apiKeys.test.ts src/pages/tenant/ApiKeysTab.test.tsx`
Expected: FAIL — `keys[0].keySuffix` 가 `undefined`, `prod 키 액션` 버튼 없음(기존 마크업은 회전/회수 버튼), `alertdialog` 없음.

- [ ] **Step 7: 타입 · 어댑터에 keySuffix 추가**

`src/api/types.ts` 의 `ApiKeyView` 에 마지막 필드로 추가:

```ts
  keySuffix?: string;      // 스펙 §6 ⑤ — 서버 후속 작업에서 추가. 없으면 렌더하지 않는다.
```

`src/api/designTypes.ts` 의 `ApiKey` 에 마지막 필드로 추가:

```ts
  keySuffix?: string;      // 스펙 §6 ⑤ (optional)
```

`src/api/apiKeys.ts` 의 `adapt` 를 다음으로 교체:

```ts
function adapt(s: ApiKeyView): ApiKey {
  return {
    id: s.id,
    prefix: s.keyPrefix,
    name: s.name,
    status: s.revokedAt
      ? 'REVOKED'
      : (s.expiresAt && new Date(s.expiresAt).getTime() <= Date.now() ? 'EXPIRED' : 'ACTIVE'),
    createdAt: s.createdAt,
    lastUsedAt: s.lastUsedAt ?? null,
    scopes: s.scopes ?? [],
    expiresAt: s.expiresAt ?? null,
    // §6 ⑤: 서버가 보낼 때만 키를 만든다(undefined 키도 만들지 않음).
    ...(s.keySuffix !== undefined ? { keySuffix: s.keySuffix } : {}),
  };
}
```

- [ ] **Step 8: ApiKeysTab 재작성**

```tsx
// admin-ui/src/pages/tenant/ApiKeysTab.tsx
import { useEffect, useRef, useState } from 'react';
import { Icons } from '@/icons/Icons';
import {
  Alert, Badge, Button, Card, CardHeader, DataTable, Dialog, EmptyState, FilterChip, KpiCard, Menu, SecretBox, StatusBadge, useToast,
  type Column,
} from '@/ui';
import { illustrations } from '@/assets/illustrations';
import { apiKeysApi } from '@/api/apiKeys';
import { copyToClipboard } from '@/lib/clipboard';
import type { Tenant, ApiKey } from '@/api/designTypes';
import { KvList } from './KvList';
import { timeAgo, ymd, ymdHm } from './tabFormat';
import {
  ROTATION_DAYS, daysLeft, expiryState, keyStatusCounts, latestCreatedAt, previewExpiry, rotationOverdueCount,
} from './apiKeyHelpers';

const NAME_MAX = 64;

const SCOPE_OPTIONS: { value: string; desc: string }[] = [
  { value: 'registration', desc: '패스키 등록 · credential 목록/이름변경/삭제' },
  { value: 'authentication', desc: '패스키 인증 ceremony' },
];

// 만료 프리셋(개월). null = 무기한. 기본 24개월.
const EXPIRY_OPTIONS: { months: number | null; label: string }[] = [
  { months: 6, label: '6개월' },
  { months: 12, label: '12개월' },
  { months: 24, label: '24개월' },
  { months: 36, label: '36개월' },
  { months: null, label: '무기한' },
];

type Issued = { key: ApiKey; plaintext: string; oldKeyExpiresAt?: string };

// ── ApiKeysTab ────────────────────────────────────────────────────────────────

export default function ApiKeysTab({ tenant }: { tenant: Tenant }) {
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [loading, setLoading] = useState(true);
  const [showNew, setShowNew] = useState(false);
  const [issued, setIssued] = useState<Issued | null>(null);
  const [revoking, setRevoking] = useState<ApiKey | null>(null);
  const [rotating, setRotating] = useState<ApiKey | null>(null);
  const toast = useToast();

  async function reload() {
    setLoading(true);
    try {
      setKeys(await apiKeysApi.list(tenant.id));
    } catch (e: unknown) {
      toast({ kind: 'err', title: 'API key 로드 실패', message: e instanceof Error ? e.message : String(e) });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void reload(); }, [tenant.id]);

  async function handleIssue(name: string, scopes: string[], expiresInMonths: number | null) {
    try {
      const result = await apiKeysApi.create(tenant.id, name, scopes, expiresInMonths);
      setShowNew(false);
      setIssued(result);
      await reload();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '발급 실패', message: e instanceof Error ? e.message : String(e) });
    }
  }

  async function handleRevoke(k: ApiKey) {
    try {
      await apiKeysApi.revoke(k.id);
      toast({ kind: 'warn', title: 'API key가 회수되었습니다.', message: `${k.prefix} · ${k.name}` });
      setRevoking(null);
      await reload();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '회수 실패', message: e instanceof Error ? e.message : String(e) });
    }
  }

  async function handleRotate(k: ApiKey) {
    try {
      const res = await apiKeysApi.rotate(k.id);
      setRotating(null);
      setIssued({ key: { ...k, prefix: res.prefix, scopes: res.scopes }, plaintext: res.plaintextKey, oldKeyExpiresAt: res.oldKeyExpiresAt });
      await reload();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '회전 실패', message: e instanceof Error ? e.message : String(e) });
    }
  }

  async function copyPrefix(k: ApiKey) {
    if (await copyToClipboard(k.prefix)) toast({ kind: 'ok', title: '접두사를 복사했습니다.', message: k.prefix });
    else toast({ kind: 'warn', title: '복사 실패', message: '클립보드 복사에 실패했습니다.' });
  }

  if (loading && keys.length === 0) {
    return <p className="muted" style={{ padding: 40, margin: 0 }}>불러오는 중…</p>;
  }

  const counts = keyStatusCounts(keys);
  const latest = latestCreatedAt(keys);
  const overdue = rotationOverdueCount(keys);

  const columns: Column<ApiKey>[] = [
    {
      key: 'name', header: '이름 · 키 접두사',
      render: (k) => (
        <div className="stack-1" style={{ gap: 0 }}>
          <strong style={{ fontSize: 13 }}>{k.name}</strong>
          <span className="mono" style={{ fontSize: 12, color: 'var(--mute)' }}>{k.prefix}…{k.keySuffix ?? ''}</span>
        </div>
      ),
    },
    { key: 'scopes', header: '권한 범위', render: (k) => <div className="row" style={{ gap: 4 }}>{k.scopes.map((s) => <span key={s} className="tag">{s}</span>)}</div> },
    { key: 'status', header: '상태', render: (k) => <StatusBadge status={k.status} /> },
    { key: 'used', header: '마지막 사용', render: (k) => <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{k.lastUsedAt ? timeAgo(k.lastUsedAt) : '미사용'}</span> },
    { key: 'created', header: '생성일', render: (k) => <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{ymd(k.createdAt)}</span> },
    { key: 'expires', header: '만료일', render: (k) => <ExpiryCell k={k} /> },
    {
      key: 'actions', header: '액션', srOnlyHeader: true, width: 48,
      render: (k) => k.status === 'ACTIVE' ? (
        <Menu
          label={`${k.name} 키 액션`}
          items={[
            { label: '회전', icon: <Icons.Refresh size={15} />, onSelect: () => setRotating(k) },
            { label: '접두사 복사', icon: <Icons.Copy size={15} />, onSelect: () => { void copyPrefix(k); } },
          ]}
          dangerItems={[{ label: '회수', icon: <Icons.Trash size={15} />, danger: true, onSelect: () => setRevoking(k) }]}
        />
      ) : null,
    },
  ];

  return (
    <div className="stack-6">
      <div className="kpis kpis--3">
        <KpiCard label="총 API Key" value={keys.length} hint={`활성 ${counts.active} · 만료 ${counts.expired} · 회수 ${counts.revoked}`} />
        <KpiCard label="최근 발급" value={latest ? timeAgo(latest) : '—'} />
        <KpiCard label="권장 rotation" value={`${ROTATION_DAYS}일`} hint={`초과 키 ${overdue}개`} hintTone={overdue > 0 ? 'warn' : 'default'} />
      </div>

      <Card>
        <CardHeader
          title="API Keys"
          sub="plaintext 는 발급 직후 1회만 노출됩니다. 서버는 해시만 저장합니다."
          actions={<Button variant="primary" icon={<Icons.Plus size={14} />} onClick={() => setShowNew(true)}>새 키 발급</Button>}
        />
        <DataTable
          columns={columns}
          rows={keys}
          rowKey={(k) => k.id}
          empty={<EmptyState compact title="발급된 API key가 없습니다." description="새 키 발급으로 RP 서버용 키를 만드세요." />}
        />
      </Card>

      <NewKeyDialog open={showNew} onClose={() => setShowNew(false)} onIssue={handleIssue} />
      <IssuedKeyDialog issued={issued} onClose={() => setIssued(null)} />
      <RotateKeyDialog k={rotating} onClose={() => setRotating(null)} onConfirm={handleRotate} />
      <RevokeKeyDialog k={revoking} onClose={() => setRevoking(null)} onConfirm={handleRevoke} />
    </div>
  );
}

// ── 만료일 셀: ACTIVE 이고 30일 이내면 경고색 + 아이콘 + 남은 일수 ─────────────

function ExpiryCell({ k }: { k: ApiKey }) {
  if (!k.expiresAt) return <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>무기한</span>;
  const soon = k.status === 'ACTIVE' && expiryState(k.expiresAt) === 'soon';
  return (
    <span className="row" style={{ gap: 5, flexWrap: 'nowrap', fontSize: 13, color: soon ? 'var(--warn)' : 'var(--ink-2)', fontWeight: soon ? 600 : 400 }}>
      {soon && <Icons.Alert size={14} />}
      {ymd(k.expiresAt)}{soon && ` · ${daysLeft(k.expiresAt)}일 남음`}
    </span>
  );
}

// ── 새 키 발급 ────────────────────────────────────────────────────────────────

function NewKeyDialog({ open, onClose, onIssue }: {
  open: boolean;
  onClose: () => void;
  onIssue: (name: string, scopes: string[], expiresInMonths: number | null) => void;
}) {
  const [name, setName] = useState('');
  const [scopes, setScopes] = useState<string[]>(['registration', 'authentication']);
  const [expiresInMonths, setExpiresInMonths] = useState<number | null>(24);

  function toggle(v: string) {
    setScopes((prev) => (prev.includes(v) ? prev.filter((s) => s !== v) : [...prev, v]));
  }
  function submit() {
    if (!name || scopes.length === 0) return;
    onIssue(name, scopes, expiresInMonths);
    setName('');
    setScopes(['registration', 'authentication']);
    setExpiresInMonths(24);
  }
  const preview = previewExpiry(expiresInMonths);

  return (
    <Dialog open={open} onClose={onClose} title="새 API key 발급" sub="RP 서버가 Passkey API 를 호출할 때 쓰는 키입니다."
      footer={<>
        <Button onClick={onClose}>취소</Button>
        <Button variant="primary" disabled={!name || scopes.length === 0} onClick={submit}>발급</Button>
      </>}>
      <div>
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <label className="label" htmlFor="apikey-name">용도 (이름)</label>
          <span className="hint" style={{ margin: 0 }}>{name.length}/{NAME_MAX}</span>
        </div>
        <input id="apikey-name" className="input" maxLength={NAME_MAX} value={name} onChange={(e) => setName(e.target.value)} placeholder="예: prod RP 서버" />
      </div>
      <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
        <legend className="label">권한 범위 (scope)</legend>
        <div className="stack-2">
          {SCOPE_OPTIONS.map((o) => {
            const on = scopes.includes(o.value);
            return (
              <label key={o.value} style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: '10px 12px', border: `1px solid ${on ? 'var(--brand-300)' : 'var(--line)'}`, borderRadius: 'var(--r)', background: on ? 'var(--brand-50)' : 'var(--surface)', cursor: 'pointer' }}>
                <input type="checkbox" checked={on} onChange={() => toggle(o.value)} style={{ marginTop: 3 }} />
                <span className="stack-1" style={{ gap: 0 }}>
                  <span className="mono" style={{ fontSize: 13, fontWeight: 600 }}>{o.value}</span>
                  <span className="hint" style={{ margin: 0 }}>{o.desc}</span>
                </span>
              </label>
            );
          })}
        </div>
        {scopes.length === 0 && <p className="field__error">하나 이상 선택하세요.</p>}
      </fieldset>
      <div>
        <span className="label" id="apikey-expiry">만료 기간</span>
        <div className="row" style={{ gap: 6 }} role="group" aria-labelledby="apikey-expiry">
          {EXPIRY_OPTIONS.map((o) => (
            <FilterChip key={o.label} active={expiresInMonths === o.months} onClick={() => setExpiresInMonths(o.months)}>{o.label}</FilterChip>
          ))}
        </div>
        <p className="hint">{preview ? `만료일 ${preview}` : '만료 없음 — 키가 무기한으로 유효합니다.'} · 권장 rotation {ROTATION_DAYS}일</p>
      </div>
    </Dialog>
  );
}

// ── 발급 완료: ESC·바깥 클릭으로 닫히지 않음, 확인 체크 후에만 닫기 ───────────

function IssuedKeyDialog({ issued, onClose }: { issued: Issued | null; onClose: () => void }) {
  const [checked, setChecked] = useState(false);
  useEffect(() => { if (issued) setChecked(false); }, [issued]);
  if (!issued) return null;
  const oldHours = issued.oldKeyExpiresAt
    ? Math.max(0, Math.round((new Date(issued.oldKeyExpiresAt).getTime() - Date.now()) / 3_600_000))
    : null;

  return (
    <Dialog open onClose={onClose} dismissible={false} role="alertdialog"
      title={
        <span className="row" style={{ gap: 10, flexWrap: 'nowrap' }}>
          <img src={illustrations.apiKeyIssued} alt="" width={72} height={72} style={{ objectFit: 'contain', flex: 'none' }} />
          <span>새 API key가 발급되었습니다 — 지금만 표시됩니다</span>
        </span>
      }
      footerStart={<span className="hint" style={{ margin: 0 }}>server는 plaintext의 해시만 저장합니다.</span>}
      footer={
        <Button variant="primary" disabled={!checked} onClick={onClose}>
          {checked ? '닫기 (영구 소실)' : '닫기 (영구 소실) · 체크 필요'}
        </Button>
      }>
      {oldHours !== null && (
        <Alert tone="warn">
          이전 키는 {oldHours}시간 후({ymdHm(issued.oldKeyExpiresAt)}) 만료됩니다. 그 전에 RP 서버를 새 키로 교체하세요.
        </Alert>
      )}
      <KvList boxed keyWidth={80} items={[
        { k: '이름', v: <>{issued.key.name} <span style={{ marginLeft: 6 }}><Badge tone="ok">발급 완료</Badge></span></> },
        { k: 'prefix', v: issued.key.prefix, mono: true },
        { k: '만료', v: issued.key.expiresAt ? ymd(issued.key.expiresAt) : '무기한' },
        { k: 'id', v: issued.key.id, mono: true },
      ]} />
      <SecretBox value={issued.plaintext} />
      <label style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '10px 12px', border: '1px solid var(--brand-700)', borderRadius: 'var(--r)', background: 'var(--brand-50)', cursor: 'pointer' }}>
        <input type="checkbox" checked={checked} onChange={(e) => setChecked(e.target.checked)} />
        <span style={{ fontSize: 13, fontWeight: 600 }}>안전한 장소에 복사했습니다.</span>
      </label>
    </Dialog>
  );
}

// ── 회수 (위험) — 취소 기본 포커스 ───────────────────────────────────────────

function RevokeKeyDialog({ k, onClose, onConfirm }: { k: ApiKey | null; onClose: () => void; onConfirm: (k: ApiKey) => void }) {
  const cancelRef = useRef<HTMLButtonElement>(null);
  if (!k) return null;
  return (
    <Dialog open onClose={onClose} role="alertdialog" title="API key를 회수하시겠습니까?" initialFocusRef={cancelRef}
      footer={<>
        <button ref={cancelRef} type="button" className="btn" onClick={onClose}>취소</button>
        <Button variant="danger-solid" onClick={() => onConfirm(k)}>회수</Button>
      </>}>
      <KvList keyWidth={80} items={[
        { k: 'prefix', v: k.prefix, mono: true },
        { k: '이름', v: k.name },
        { k: '생성', v: ymd(k.createdAt) },
      ]} />
      <Alert tone="bad">회수는 되돌릴 수 없습니다. 이 키를 쓰는 RP 서버의 요청이 즉시 401 로 거부됩니다.</Alert>
    </Dialog>
  );
}

// ── 회전 — 기존 키 24시간 겹침 타임라인 ───────────────────────────────────────

function RotateKeyDialog({ k, onClose, onConfirm }: { k: ApiKey | null; onClose: () => void; onConfirm: (k: ApiKey) => void }) {
  if (!k) return null;
  return (
    <Dialog open onClose={onClose} title="API key를 회전하시겠습니까?"
      footer={<>
        <Button onClick={onClose}>취소</Button>
        <Button variant="primary" onClick={() => onConfirm(k)}>회전 실행</Button>
      </>}>
      <KvList keyWidth={80} items={[
        { k: 'prefix', v: k.prefix, mono: true },
        { k: '이름', v: k.name },
      ]} />
      <div className="row" style={{ gap: 10, flexWrap: 'nowrap', fontSize: 13, padding: '12px 14px', background: 'var(--canvas)', borderRadius: 'var(--r)' }}>
        <span className="tag">기존 키</span>
        <span style={{ color: 'var(--ink-2)' }}>24시간 함께 유효</span>
        <Icons.ChevronRight size={14} />
        <span className="tag" style={{ background: 'var(--brand-100)', color: 'var(--brand-700)' }}>새 키</span>
        <span style={{ color: 'var(--ink-2)' }}>즉시 발급</span>
      </div>
    </Dialog>
  );
}
```

주의(일러스트 크기): 캔버스 발급 완료 다이얼로그는 일러스트를 제목 옆 72px 로 그린다. Global Constraints 의 120–160px 는 빈 상태 기준이고 이 다이얼로그는 스펙 §3.3 표에서 명시한 사용처이므로 아트보드 크기(72px)를 따른다.

- [ ] **Step 9: 테스트 통과 확인**

Run: `npx vitest run src/api/apiKeys.test.ts src/pages/tenant`
Expected: PASS — `apiKeys.test.ts` 4개, `ApiKeysTab.test.tsx` 5개, 헬퍼 테스트 2개 파일.

- [ ] **Step 10: 전체 테스트 · 린트 · 빌드**

Run: `npm test && npm run lint && npm run build`
Expected: 모두 성공(린트 에러 0).

- [ ] **Step 11: 수동 확인**

`npm run dev` → Tenant › API 키 탭. 마지막 행의 ⋯ 메뉴가 표 스크롤 영역(`.tw`)에 잘리지 않는지, 발급 완료 다이얼로그에서 ESC·바깥 클릭이 무시되는지, 체크 전 버튼이 `닫기 (영구 소실) · 체크 필요` 로 비활성인지 확인.

- [ ] **Step 12: 커밋**

```bash
git add src/pages/tenant/ApiKeysTab.tsx src/pages/tenant/ApiKeysTab.test.tsx src/pages/tenant/apiKeyHelpers.ts src/pages/tenant/apiKeyHelpers.test.ts \
  src/pages/tenant/tabFormat.ts src/pages/tenant/tabFormat.test.ts src/pages/tenant/KvList.tsx \
  src/api/types.ts src/api/designTypes.ts src/api/apiKeys.ts src/api/apiKeys.test.ts
git commit -m "feat(admin-ui): V2 API 키 탭 — rotation 초과 KPI·스코프 태그·만료 임박 경고·⋯ 메뉴·발급 완료 잠금 다이얼로그

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 24: Credentials 탭 + 상세·회수 다이얼로그 (검색 파라미터 `q` 버그 수정)

V2 아트보드: `v2/Tenant-Credentials.dc.html`, `v2/Dialogs-Audit.dc.html`(Credential 상세 · Credential 회수).

**버그(확인됨):** `credentialsApi.list` 는 검색어를 `search=` 로 보내지만 서버 `CredentialAdminController.list` 는 `@RequestParam(required = false) String q` 만 읽는다(`admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminController.java:37`). 게다가 현재 탭은 검색어를 API 에 넘기지도 않고 현재 페이지 안에서만 거른다. 이 태스크에서 클라이언트가 `q` 를 보내고, 탭의 검색창을 서버 검색으로 연결한다. 서버 `q` 는 `credential_id`·`user_handle` 의 hex 표현 부분 일치로 찾는다(`CredentialRepository.searchByTenantId`, base64url 이면 hex 로 변환) — 별칭(nickname)은 서버 검색 대상이 아니므로 placeholder 에서 뺀다.

**필터 동작(정의):**
- 검색창 → 서버 `q`. 입력 후 300ms 디바운스, 앞뒤 공백 제거, 값이 바뀌면 `page=0` 으로. 빈 값이면 `q` 를 보내지 않는다.
- `+ 필터`(FilterChip, `aria-pressed`) → 툴바 아래 필터 행을 열고 닫는다(기존 `aaguid · status` 순환 모드 버튼 대체). 필터 행: `aaguid` 입력(부분 일치, 하이픈·대소문자 무시) + status `Segmented`(전체/ACTIVE/REVOKED). 서버에 aaguid·status 파라미터가 없으므로 기존과 같이 **현재 로드된 페이지 안에서** 거른다(행에 "현재 페이지 안에서 거릅니다." 안내). 서버 API 파라미터는 `page`·`size`·`q` 만 그대로 쓴다.
- 적용된 aaguid/status 는 툴바에 `RemovableChip`(`aaguid: …`, `status: …`)으로 보이고 ✕ 로 해제. 필터 행을 닫아도 값은 유지.
- 건수: 클라이언트 필터가 없으면 서버 `total` 로 `{N}건`, 있으면 `{보이는 행 수}건 (현재 페이지)`.
- 빈 상태: 서버 결과 0건이고 검색어·필터가 모두 없으면 `illustrations.emptyCredentials` "Credential이 없습니다", 그 외 보이는 행이 0건이면 `illustrations.noResults` "검색 결과가 없습니다." + `필터 지우기`(검색어·aaguid·status 모두 초기화).

**상세 이전/다음(정의):**
- 이동 대상은 현재 화면에 보이는 행(`visible`, 클라이언트 필터 적용 후) 순서.
- 페이지 안에서는 즉시 이동(추가 호출 없음).
- 페이지 경계: 클라이언트 필터가 **없을 때만** 넘어간다. 마지막 행에서 `다음` → `page+1` 을 불러와 그 페이지 첫 행을 선택, 첫 행에서 `이전` → `page-1` 을 불러와 마지막 행을 선택. 불러오는 동안 두 버튼 비활성. 클라이언트 필터가 있으면 현재 페이지의 보이는 행 안에서만 이동.
- 위치 표시: 필터 없음 `{page*size + i + 1} / {total}`, 필터 있음 `{i + 1} / {visible.length}`.

**Files:**
- Modify: `admin-ui/src/api/credentials.ts`, `admin-ui/src/api/credentials.test.ts`
- Create: `admin-ui/src/pages/tenant/credentialHelpers.ts`, `admin-ui/src/pages/tenant/credentialHelpers.test.ts`
- Rewrite: `admin-ui/src/pages/tenant/CredentialsTab.tsx`, `admin-ui/src/pages/tenant/CredentialDetailDialog.tsx`
- Modify: `admin-ui/src/pages/tenant/CredentialDetailDialog.test.tsx`
- Create: `admin-ui/src/pages/tenant/CredentialsTab.test.tsx`

**Interfaces:**
- Consumes: Task 23 `KvList`, `timeAgo`, `ymdHm`. `@/ui` — `Badge`, `Button`, `Card`, `ConfirmByTyping`, `CopyBtn`, `DataTable`, `type Column`, `Dialog`, `EmptyState`, `FilterChip`, `IconButton`, `Pagination`, `RemovableChip`, `Segmented`, `StatusBadge`, `useToast`. `illustrations.emptyCredentials`, `illustrations.noResults`. `credentialsApi.list/revoke/authEvents`.
- Produces:
  - `credentialsApi.list(tenantId, page = 0, size = 50, search?: string)` — 시그니처 유지, 검색어를 **`q`** 로 전송.
  - `credentialHelpers.ts`: `type StatusFilter = 'ALL' | 'ACTIVE' | 'REVOKED'`, `type ClientFilter = { aaguid: string; status: StatusFilter }`, `hasClientFilter(f: ClientFilter): boolean`, `applyClientFilter(items: Credential[], f: ClientFilter): Credential[]`, `authenticatorLabel(c: Credential): string`(MDS 이름 → aaguid 끝 8자(하이픈 제거) → `'unknown'`).
  - `CredentialDetailDialog({ c, tenantId, onClose, position?: { index: number; total: number }, onPrev?: () => void, onNext?: () => void, onRevoke?: (c: Credential) => void })` — `position` 이 없으면 이동 UI 를 렌더하지 않는다. `onPrev`/`onNext` 가 없으면 해당 버튼 비활성. `onRevoke` 가 있고 `c.status === 'ACTIVE'` 면 푸터 좌측 `Credential 회수…`(danger). 버튼 aria-label `이전 credential` / `다음 credential`.
  - `DataTable` 행 키보드 처리: `e.target !== e.currentTarget` 이면 무시(행 안 버튼·복사 버튼의 Enter/Space 가 행 클릭을 일으키지 않음).

- [ ] **Step 1: 실패하는 테스트 — `q` 회귀 · DataTable 키보드 · 헬퍼**

`src/api/credentials.test.ts` 끝에 추가:

```ts
describe('credentialsApi.list search param (regression: server reads q, not search)', () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
  afterEach(() => vi.unstubAllGlobals());
  const empty = () => envelope({ content: [], page: 0, size: 50, totalElements: 0, hasNext: false });

  it('sends the keyword as q', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(empty());
    await credentialsApi.list('t1', 2, 100, 'abc def');
    const [url] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toMatch(/^\/admin\/api\/tenants\/t1\/credentials\?/);
    const params = new URL(url, 'http://x').searchParams;
    expect(params.get('q')).toBe('abc def');
    expect(params.has('search')).toBe(false);
    expect(params.get('page')).toBe('2');
    expect(params.get('size')).toBe('100');
  });

  it('omits q when the keyword is empty or missing', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(empty()).mockResolvedValueOnce(empty());
    await credentialsApi.list('t1', 0, 50, '');
    await credentialsApi.list('t1', 0, 50);
    for (const [url] of (fetch as ReturnType<typeof vi.fn>).mock.calls) {
      const params = new URL(url, 'http://x').searchParams;
      expect(params.has('q')).toBe(false);
      expect(params.has('search')).toBe(false);
    }
  });
});
```

```ts
// admin-ui/src/pages/tenant/credentialHelpers.test.ts
import { describe, it, expect } from 'vitest';
import type { Credential } from '@/api/designTypes';
import { applyClientFilter, hasClientFilter, authenticatorLabel } from './credentialHelpers';

const c = (id: string, p: Partial<Credential> = {}): Credential => ({
  credentialId: id, externalUserId: `u-${id}`, nickname: null, authenticatorName: null, status: 'ACTIVE',
  aaguid: null, transports: [], signatureCounter: 0, lastUsedAt: null, createdAt: '2026-06-01T00:00:00Z', attestationFormat: null, ...p,
});

describe('credentialHelpers', () => {
  const items = [
    c('a', { aaguid: 'EA9B8D66-4D01-1D21-3CE4-B6B48CB575D4' }),
    c('b', { aaguid: 'fbfc3007-154e-4ecc-8c0b-6e020557d7bd', status: 'REVOKED' }),
    c('c'),
  ];
  it('hasClientFilter', () => {
    expect(hasClientFilter({ aaguid: '  ', status: 'ALL' })).toBe(false);
    expect(hasClientFilter({ aaguid: 'ea9b', status: 'ALL' })).toBe(true);
    expect(hasClientFilter({ aaguid: '', status: 'REVOKED' })).toBe(true);
  });
  it('aaguid 는 하이픈·대소문자 무시 부분 일치, status 는 정확히', () => {
    expect(applyClientFilter(items, { aaguid: 'ea9b8d664d01', status: 'ALL' }).map((x) => x.credentialId)).toEqual(['a']);
    expect(applyClientFilter(items, { aaguid: '', status: 'REVOKED' }).map((x) => x.credentialId)).toEqual(['b']);
    expect(applyClientFilter(items, { aaguid: 'fbfc', status: 'ACTIVE' })).toEqual([]);
    expect(applyClientFilter(items, { aaguid: '', status: 'ALL' })).toHaveLength(3);
  });
  it('authenticatorLabel: MDS 이름 → aaguid 끝 8자 → unknown', () => {
    expect(authenticatorLabel(c('x', { authenticatorName: 'iCloud Keychain', aaguid: 'fbfc3007-154e-4ecc-8c0b-6e020557d7bd' }))).toBe('iCloud Keychain');
    expect(authenticatorLabel(c('x', { aaguid: 'fbfc3007-154e-4ecc-8c0b-6e020557d7bd' }))).toBe('0557d7bd');
    expect(authenticatorLabel(c('x'))).toBe('unknown');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/api/credentials.test.ts src/ui/table.test.tsx src/pages/tenant/credentialHelpers.test.ts`
Expected: FAIL — `params.get('q')` 가 `null`(현재 `search=abc+def`), `./credentialHelpers` 모듈 없음.

- [ ] **Step 3: 수정 — API 클라이언트 · 헬퍼**

`src/api/credentials.ts` 의 `list` 를 교체:

```ts
  list: async (
    tenantId: string,
    page = 0,
    size = 50,
    search?: string,
  ): Promise<{ items: Credential[]; total: number; page: number; size: number }> => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    // 서버 CredentialAdminController.list 는 검색어를 `q` 로 받는다(이전 `search` 는 무시되던 버그).
    if (search) params.set('q', search);
    const res = await api.get<PageView<CredentialView>>(
      `/admin/api/tenants/${tenantId}/credentials?${params}`,
    );
    return {
      items: res.content.map(adapt),
      total: res.totalElements,
      page: res.page,
      size: res.size,
    };
  },
```

```ts
// admin-ui/src/pages/tenant/credentialHelpers.ts
import type { Credential } from '@/api/designTypes';

export type StatusFilter = 'ALL' | 'ACTIVE' | 'REVOKED';
export type ClientFilter = { aaguid: string; status: StatusFilter };

const norm = (s: string) => s.replace(/-/g, '').toLowerCase();

/** 서버에 aaguid·status 파라미터가 없어 현재 로드된 페이지 안에서만 거른다. */
export function hasClientFilter(f: ClientFilter): boolean {
  return f.aaguid.trim() !== '' || f.status !== 'ALL';
}

export function applyClientFilter(items: Credential[], f: ClientFilter): Credential[] {
  const a = norm(f.aaguid.trim());
  return items.filter((c) =>
    (f.status === 'ALL' || c.status === f.status) &&
    (a === '' || (c.aaguid != null && norm(c.aaguid).includes(a))));
}

export function authenticatorLabel(c: Credential): string {
  if (c.authenticatorName) return c.authenticatorName;
  return c.aaguid ? norm(c.aaguid).slice(-8) : 'unknown';
}
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/api/credentials.test.ts src/ui/table.test.tsx src/pages/tenant/credentialHelpers.test.ts`
Expected: PASS.

- [ ] **Step 5: 실패하는 테스트 — 상세 다이얼로그 이전/다음 · 탭 동작**

`src/pages/tenant/CredentialDetailDialog.test.tsx` 의 기존 5개 테스트는 그대로 두고(문구 `아직 인증 이력이 없습니다`·`불러오지 못했습니다`·`재시도`·`성공`/`실패`·`SIGN_COUNT_REPLAY`·`packed` 는 V2 에서도 유지), `describe` 안에 추가:

```tsx
  it('position 이 있으면 위치와 이전/다음 버튼, 핸들러가 없으면 비활성', async () => {
    vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([]);
    const onNext = vi.fn();
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} position={{ index: 3, total: 120 }} onNext={onNext} />);
    expect(screen.getByText('3 / 120')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이전 credential' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '다음 credential' }));
    expect(onNext).toHaveBeenCalledTimes(1);
    await screen.findByText(/아직 인증 이력이 없습니다/);
  });

  it('position 이 없으면 이동 UI 를 렌더하지 않는다', async () => {
    vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([]);
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} />);
    expect(screen.queryByRole('button', { name: '이전 credential' })).toBeNull();
    expect(screen.queryByRole('button', { name: '다음 credential' })).toBeNull();
    await screen.findByText(/아직 인증 이력이 없습니다/);
  });

  it('c 가 바뀌면(이전/다음) 그 credential 의 인증 기록을 다시 불러온다', async () => {
    const spy = vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([]);
    const { rerender } = render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} position={{ index: 1, total: 2 }} />);
    await screen.findByText(/아직 인증 이력이 없습니다/);
    rerender(<CredentialDetailDialog c={{ ...cred, credentialId: 'cred-next-000000000000', externalUserId: 'user-handle-2' }} tenantId="t1" onClose={() => {}} position={{ index: 2, total: 2 }} />);
    expect(screen.getByText('user-handle-2')).toBeInTheDocument();
    await waitFor(() => expect(spy).toHaveBeenLastCalledWith('t1', 'cred-next-000000000000', 20));
  });

  it('섹션 3개와 푸터 좌측 회수 버튼(ACTIVE + onRevoke)', async () => {
    vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([]);
    const onRevoke = vi.fn();
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} onRevoke={onRevoke} />);
    // '인증기' 는 섹션 제목과 dt 둘 다 있으므로 섹션 제목(.t-over)으로 한정
    for (const s of ['식별 정보', '인증기', '인증 기록 (최근 20건)']) expect(screen.getByText(s, { selector: '.t-over' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Credential 회수…' }));
    expect(onRevoke).toHaveBeenCalledWith(cred);
    await screen.findByText(/아직 인증 이력이 없습니다/);
  });
```

```tsx
// admin-ui/src/pages/tenant/CredentialsTab.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { ToastHost } from '@/ui';
import type { Credential, Tenant } from '@/api/designTypes';

const api = vi.hoisted(() => ({ list: vi.fn(), revoke: vi.fn(), authEvents: vi.fn() }));
vi.mock('@/api/credentials', () => ({ credentialsApi: api }));

import CredentialsTab from './CredentialsTab';

const tenant: Tenant = {
  id: 't1', name: 'Acme', slug: 'acme', rpId: 'acme.com', status: 'ACTIVE', credentials: 3, apiKeys: 0,
  lastEventAt: null, createdAt: '2026-01-01T00:00:00Z', userVerification: 'PREFERRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const cred = (id: string, p: Partial<Credential> = {}): Credential => ({
  credentialId: id, externalUserId: `user-${id}`, nickname: null, authenticatorName: 'iCloud Keychain', status: 'ACTIVE',
  aaguid: 'fbfc3007-154e-4ecc-8c0b-6e020557d7bd', transports: ['internal'], signatureCounter: 0, lastUsedAt: null,
  createdAt: '2026-06-01T00:00:00Z', attestationFormat: 'none', ...p,
});
const pageOf = (items: Credential[], total = items.length, page = 0) => ({ items, total, page, size: 50 });
const renderTab = () => render(<ToastHost><CredentialsTab tenant={tenant} /></ToastHost>);

describe('CredentialsTab V2', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.authEvents.mockResolvedValue([]);
  });

  it('검색어를 디바운스 후 서버 q 로 보낸다(앞뒤 공백 제거, 첫 페이지)', async () => {
    api.list.mockResolvedValue(pageOf([cred('c1')]));
    renderTab();
    await screen.findByText('user-c1');
    expect(api.list).toHaveBeenLastCalledWith('t1', 0, 50, undefined);
    fireEvent.change(screen.getByRole('textbox', { name: 'Credential 검색' }), { target: { value: ' abc ' } });
    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith('t1', 0, 50, 'abc'));
  });

  it('credential 이 하나도 없으면 빈 상태 일러스트', async () => {
    api.list.mockResolvedValue(pageOf([], 0));
    const { container } = renderTab();
    expect(await screen.findByText('Credential이 없습니다')).toBeInTheDocument();
    expect(container.querySelectorAll('img.empty__art')).toHaveLength(1);
  });

  it('검색 결과가 없으면 결과 없음 상태, 필터 지우기로 초기화', async () => {
    api.list.mockImplementation((_t: string, _p: number, _s: number, q?: string) => Promise.resolve(q ? pageOf([], 0) : pageOf([cred('c1')])));
    renderTab();
    await screen.findByText('user-c1');
    fireEvent.change(screen.getByRole('textbox', { name: 'Credential 검색' }), { target: { value: 'zzz' } });
    expect(await screen.findByText('검색 결과가 없습니다.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '필터 지우기' }));
    expect(await screen.findByText('user-c1')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Credential 검색' })).toHaveValue('');
    expect(api.list).toHaveBeenLastCalledWith('t1', 0, 50, undefined);
  });

  it('+ 필터: 필터 행 토글, status 를 고르면 적용 칩이 생기고 현재 페이지 안에서 거른다', async () => {
    api.list.mockResolvedValue(pageOf([cred('c1'), cred('c2', { status: 'REVOKED' })]));
    renderTab();
    await screen.findByText('user-c1');
    const toggle = screen.getByRole('button', { name: /필터 · aaguid · status/ });
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(within(screen.getByRole('group', { name: 'status 필터' })).getByRole('button', { name: 'REVOKED' }));
    expect(screen.getByText('status: REVOKED')).toBeInTheDocument();
    expect(screen.queryByText('user-c1')).toBeNull();
    expect(screen.getByText('user-c2')).toBeInTheDocument();
    expect(screen.getByText('1건 (현재 페이지)')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'status 필터 제거' }));
    expect(screen.getByText('user-c1')).toBeInTheDocument();
    expect(api.list).toHaveBeenCalledTimes(1); // 클라이언트 필터는 서버를 다시 부르지 않는다
  });

  it('상세 이전/다음: 페이지 안에서는 바로, 경계에서는 다음/이전 페이지를 불러와 이어간다', async () => {
    const p0 = [cred('c1'), cred('c2')];
    const p1 = [cred('c3')];
    api.list.mockImplementation((_t: string, page: number) => Promise.resolve(page === 0 ? pageOf(p0, 51, 0) : pageOf(p1, 51, 1)));
    renderTab();
    fireEvent.click(await screen.findByText('user-c1'));
    const dlg = screen.getByRole('dialog', { name: /Credential 상세/ });
    expect(within(dlg).getByText('1 / 51')).toBeInTheDocument();
    expect(within(dlg).getByRole('button', { name: '이전 credential' })).toBeDisabled();

    fireEvent.click(within(dlg).getByRole('button', { name: '다음 credential' }));
    expect(within(dlg).getByText('2 / 51')).toBeInTheDocument();
    expect(within(dlg).getByText('user-c2')).toBeInTheDocument();
    expect(api.list).toHaveBeenCalledTimes(1);

    fireEvent.click(within(dlg).getByRole('button', { name: '다음 credential' }));
    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith('t1', 1, 50, undefined));
    expect(await within(dlg).findByText('51 / 51')).toBeInTheDocument();
    expect(within(dlg).getByText('user-c3')).toBeInTheDocument();
    expect(within(dlg).getByRole('button', { name: '다음 credential' })).toBeDisabled();

    fireEvent.click(within(dlg).getByRole('button', { name: '이전 credential' }));
    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith('t1', 0, 50, undefined));
    expect(await within(dlg).findByText('2 / 51')).toBeInTheDocument();
    expect(within(dlg).getByText('user-c2')).toBeInTheDocument();
  });

  it('회수: 마지막 8자를 정확히 입력해야 실행, 상세 푸터에서도 진입', async () => {
    api.list.mockResolvedValue(pageOf([cred('cred-0000-abcdefgh')]));
    api.revoke.mockResolvedValue(undefined);
    renderTab();
    fireEvent.click(await screen.findByText('user-cred-0000-abcdefgh'));
    fireEvent.click(screen.getByRole('button', { name: 'Credential 회수…' }));
    expect(screen.queryByRole('dialog', { name: /Credential 상세/ })).toBeNull();

    const dlg = screen.getByRole('alertdialog', { name: 'Credential 회수' });
    const confirm = within(dlg).getByRole('button', { name: '확인 — 회수' });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dlg).getByRole('textbox', { name: /마지막 8자/ }), { target: { value: 'abcdefgh' } });
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);
    await waitFor(() => expect(api.revoke).toHaveBeenCalledWith('t1', 'cred-0000-abcdefgh'));
    await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
  });

  it('표의 휴지통 버튼은 ACTIVE 행에만', async () => {
    api.list.mockResolvedValue(pageOf([cred('aaaa-11111111'), cred('bbbb-22222222', { status: 'REVOKED' })]));
    renderTab();
    await screen.findByText('user-aaaa-11111111');
    expect(screen.getByRole('button', { name: '회수 · …11111111' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '회수 · …22222222' })).toBeNull();
  });
});
```

- [ ] **Step 6: 실패 확인**

Run: `npx vitest run src/pages/tenant/CredentialDetailDialog.test.tsx src/pages/tenant/CredentialsTab.test.tsx`
Expected: FAIL — `이전 credential` 버튼 없음, `Credential 검색` 텍스트박스 없음, `list` 가 4번째 인자 없이 호출됨.

- [ ] **Step 7: CredentialDetailDialog 재작성**

```tsx
// admin-ui/src/pages/tenant/CredentialDetailDialog.tsx
import { useState, useEffect, useCallback, useRef } from 'react';
import { Icons } from '@/icons/Icons';
import { Badge, Button, CopyBtn, Dialog, IconButton, StatusBadge } from '@/ui';
import { credentialsApi } from '@/api/credentials';
import type { Credential, AuthEvent } from '@/api/designTypes';
import { statusLabel } from '@/i18n/labels';
import { KvList } from './KvList';
import { timeAgo, ymdHm } from './tabFormat';

type Props = {
  c: Credential;
  tenantId: string;
  onClose: () => void;
  /** 1-based 위치. 없으면 이전/다음 UI 를 그리지 않는다. */
  position?: { index: number; total: number };
  onPrev?: () => void;
  onNext?: () => void;
  onRevoke?: (c: Credential) => void;
};

export default function CredentialDetailDialog({ c, tenantId, onClose, position, onPrev, onNext, onRevoke }: Props) {
  const [events, setEvents] = useState<AuthEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  // 마운트 동안만 setState 허용하는 단일 취소 신호 (effect·retry 가 공유)
  const aliveRef = useRef(true);
  useEffect(() => {
    aliveRef.current = true;
    return () => { aliveRef.current = false; };
  }, []);

  // c.credentialId 가 바뀌면(이전/다음) 새로 불러온다. 늦게 도착한 이전 응답은 버린다.
  const reqRef = useRef(0);
  const load = useCallback(() => {
    const req = ++reqRef.current;
    setLoading(true);
    setError(false);
    credentialsApi.authEvents(tenantId, c.credentialId, 20)
      .then((res) => { if (aliveRef.current && req === reqRef.current) setEvents(res); })
      .catch(() => { if (aliveRef.current && req === reqRef.current) setError(true); })
      .finally(() => { if (aliveRef.current && req === reqRef.current) setLoading(false); });
  }, [tenantId, c.credentialId]);

  useEffect(() => { load(); }, [load]);

  return (
    <Dialog open onClose={onClose} wide
      title={
        <span className="row" style={{ gap: 10, flexWrap: 'nowrap' }}>
          <span>Credential 상세</span>
          <StatusBadge status={c.status} />
          {position && (
            <>
              <span className="spacer" />
              <span className="hint" style={{ margin: 0, fontWeight: 400 }}>{position.index} / {position.total}</span>
              <IconButton label="이전 credential" icon={<Icons.ChevronLeft size={16} />} disabled={!onPrev} onClick={onPrev} />
              <IconButton label="다음 credential" icon={<Icons.ChevronRight size={16} />} disabled={!onNext} onClick={onNext} />
            </>
          )}
        </span>
      }
      sub={
        <span className="row" style={{ gap: 4, flexWrap: 'nowrap' }}>
          <span className="mono truncate" style={{ fontSize: 12, color: 'var(--mute)' }}>{c.credentialId}</span>
          <CopyBtn value={c.credentialId} label="credentialId 복사" iconOnly />
        </span>
      }
      footerStart={onRevoke && c.status === 'ACTIVE'
        ? <Button variant="danger" onClick={() => onRevoke(c)}>Credential 회수…</Button>
        : undefined}
      footer={<Button onClick={onClose}>닫기</Button>}>

      {/* 상세 — 이미 가진 c 로 즉시 렌더 */}
      <section className="stack-2">
        <span className="t-over">식별 정보</span>
        <KvList columns={2} items={[
          { k: 'externalUserId', v: c.externalUserId, mono: true },
          { k: '별칭', v: c.nickname ?? '—' },
          { k: '생성', v: ymdHm(c.createdAt) },
          { k: '마지막 사용', v: c.lastUsedAt ? timeAgo(c.lastUsedAt) : '—' },
        ]} />
      </section>

      <section className="stack-2">
        <span className="t-over">인증기</span>
        <KvList columns={2} items={[
          { k: '인증기', v: c.authenticatorName ?? (c.aaguid ?? 'unknown') },
          { k: '전송 방식', v: c.transports.length ? <span className="row" style={{ gap: 4 }}>{c.transports.map((t) => <span key={t} className="tag">{t}</span>)}</span> : '—' },
          { k: '서명 카운터', v: c.signatureCounter, mono: true },
          { k: 'AAGUID', v: c.aaguid ?? '—', mono: true },
          { k: 'attestation', v: c.attestationFormat ?? '—', mono: true },
        ]} />
      </section>

      <section className="stack-2">
        <span className="t-over">인증 기록 (최근 20건)</span>
        {loading ? (
          <div className="stack-2" aria-busy="true" style={{ padding: '8px 0' }}>
            <span className="sr-only">불러오는 중…</span>
            {[0, 1, 2].map((i) => <span key={i} className="skeleton" style={{ width: '100%' }} />)}
          </div>
        ) : error ? (
          <div className="stack-2" style={{ alignItems: 'center', padding: '16px 0' }}>
            <p className="muted" style={{ margin: 0, fontSize: 13 }}>기록을 불러오지 못했습니다.</p>
            <Button size="sm" onClick={() => load()}>재시도</Button>
          </div>
        ) : events.length === 0 ? (
          <p className="muted" style={{ margin: 0, padding: '20px 0', textAlign: 'center', fontSize: 13 }}>아직 인증 이력이 없습니다.</p>
        ) : (
          <div style={{ border: '1px solid var(--line-soft)', borderRadius: 'var(--r)', overflow: 'hidden' }}>
            <table className="table">
              <thead>
                <tr><th>시간</th><th>결과</th><th>사유</th><th className="num">서명 카운터</th></tr>
              </thead>
              <tbody>
                {events.map((e, i) => (
                  <tr key={i}>
                    <td style={{ fontSize: 13, color: 'var(--ink-2)' }}>{ymdHm(e.createdAt)}</td>
                    <td><Badge tone={e.result === 'SUCCESS' ? 'ok' : 'bad'}>{statusLabel(e.result)}</Badge></td>
                    <td>{e.failureReason ? <span className="tag">{e.failureReason}</span> : <span className="muted">—</span>}</td>
                    <td className="num mono" style={{ fontSize: 12 }}>{e.signCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </Dialog>
  );
}
```

주의: 캔버스 인증기 섹션은 4항목이지만, 기존 테스트(`packed` 표시)와 정보 보존을 위해 `attestation` 행을 5번째로 유지한다.

- [ ] **Step 8: CredentialsTab 재작성**

```tsx
// admin-ui/src/pages/tenant/CredentialsTab.tsx
import { useEffect, useMemo, useRef, useState } from 'react';
import { Icons } from '@/icons/Icons';
import {
  Button, Card, ConfirmByTyping, CopyBtn, DataTable, Dialog, EmptyState, FilterChip, IconButton, Pagination, RemovableChip,
  Segmented, StatusBadge, useToast, type Column,
} from '@/ui';
import { illustrations } from '@/assets/illustrations';
import { credentialsApi } from '@/api/credentials';
import { downloadCsv } from '@/lib/csvExport';
import type { Tenant, Credential } from '@/api/designTypes';
import CredentialDetailDialog from './CredentialDetailDialog';
import { KvList } from './KvList';
import { fmtNum, timeAgo } from './tabFormat';
import { applyClientFilter, authenticatorLabel, hasClientFilter, type StatusFilter } from './credentialHelpers';

const PAGE_SIZES = [50, 100, 200];
const SEARCH_DEBOUNCE_MS = 300;
const tail = (s: string, n: number) => s.slice(-n);

export default function CredentialsTab({ tenant }: { tenant: Tenant }) {
  const [items, setItems] = useState<Credential[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);                 // 0-based (서버 page)
  const [size, setSize] = useState(50);                // 50/100/200
  const [q, setQ] = useState('');                      // 입력 중 검색어
  const [appliedQ, setAppliedQ] = useState('');        // 서버에 보낸 검색어(q)
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [aaguid, setAaguid] = useState('');
  const [status, setStatus] = useState<StatusFilter>('ALL');
  const [loading, setLoading] = useState(true);
  const [revoking, setRevoking] = useState<Credential | null>(null);
  const [selected, setSelected] = useState<Credential | null>(null);
  // 상세 이전/다음이 페이지 경계를 넘을 때, 새 페이지 로드 후 선택할 위치
  const pendingSelect = useRef<'first' | 'last' | null>(null);
  const toast = useToast();

  // 검색어 → 서버 q (디바운스, 바뀌면 첫 페이지로)
  useEffect(() => {
    const next = q.trim();
    if (next === appliedQ) return;
    const t = setTimeout(() => { setAppliedQ(next); setPage(0); }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [q, appliedQ]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    credentialsApi.list(tenant.id, page, size, appliedQ || undefined)
      .then((res) => {
        if (cancelled) return;
        setItems(res.items);
        setTotal(res.total);
        const pend = pendingSelect.current;
        pendingSelect.current = null;
        if (pend && res.items.length > 0) setSelected(pend === 'first' ? res.items[0] : res.items[res.items.length - 1]);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        pendingSelect.current = null;
        toast({ kind: 'err', title: 'credentials 로드 실패', message: e instanceof Error ? e.message : String(e) });
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [tenant.id, page, size, appliedQ]);

  async function reload() {
    setLoading(true);
    try {
      const res = await credentialsApi.list(tenant.id, page, size, appliedQ || undefined);
      setItems(res.items);
      setTotal(res.total);
    } catch (e: unknown) {
      toast({ kind: 'err', title: 'credentials 로드 실패', message: e instanceof Error ? e.message : String(e) });
    } finally {
      setLoading(false);
    }
  }

  const clientFiltered = hasClientFilter({ aaguid, status });
  const visible = useMemo(() => applyClientFilter(items, { aaguid, status }), [items, aaguid, status]);
  const lastPage = Math.max(0, Math.ceil(total / size) - 1);

  function clearAll() {
    setQ('');
    setAppliedQ('');
    setAaguid('');
    setStatus('ALL');
    setPage(0);
  }

  // ── 상세 이전/다음 ──
  const selIdx = selected ? visible.findIndex((c) => c.credentialId === selected.credentialId) : -1;
  const canPrev = selIdx > 0 || (selIdx === 0 && !clientFiltered && page > 0);
  const canNext = (selIdx >= 0 && selIdx < visible.length - 1) || (selIdx === visible.length - 1 && selIdx >= 0 && !clientFiltered && page < lastPage);
  function goPrev() {
    if (selIdx > 0) { setSelected(visible[selIdx - 1]); return; }
    pendingSelect.current = 'last';
    setPage((p) => p - 1);
  }
  function goNext() {
    if (selIdx < visible.length - 1) { setSelected(visible[selIdx + 1]); return; }
    pendingSelect.current = 'first';
    setPage((p) => p + 1);
  }
  const position = selIdx >= 0
    ? (clientFiltered ? { index: selIdx + 1, total: visible.length } : { index: page * size + selIdx + 1, total })
    : undefined;

  async function handleRevoke() {
    if (!revoking) return;
    try {
      await credentialsApi.revoke(tenant.id, revoking.credentialId);
      toast({ kind: 'warn', title: 'Credential이 회수되었습니다.', message: `${tail(revoking.credentialId, 12)} · ${revoking.externalUserId}` });
      setRevoking(null);
      await reload();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '회수 실패', message: e instanceof Error ? e.message : String(e) });
    }
  }

  function exportCsv() {
    if (visible.length === 0) return;
    downloadCsv(
      `credentials-${tenant.slug}-${new Date().toISOString().slice(0, 10)}.csv`,
      ['credentialId', 'externalUserId', 'label', 'authenticatorName', 'status', 'aaguid', 'transports', 'signatureCounter', 'lastUsedAt', 'createdAt'],
      visible.map((c) => [
        c.credentialId, c.externalUserId, c.nickname ?? '', c.authenticatorName ?? '', c.status, c.aaguid ?? '',
        c.transports.join('|'), c.signatureCounter, c.lastUsedAt ?? '', c.createdAt,
      ]),
    );
  }

  const columns: Column<Credential>[] = [
    {
      key: 'id', header: 'Credential ID',
      render: (c) => (
        <span className="row" style={{ gap: 4, flexWrap: 'nowrap' }}>
          <span className="mono truncate" style={{ fontSize: 12, maxWidth: 120 }} title={c.credentialId}>{c.credentialId}</span>
          <CopyBtn value={c.credentialId} label="Credential ID 복사" iconOnly />
        </span>
      ),
    },
    { key: 'user', header: '사용자', render: (c) => <span className="mono" style={{ fontSize: 12 }}>{c.externalUserId}</span> },
    { key: 'nick', header: '별칭', render: (c) => c.nickname ? <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{c.nickname}</span> : <span className="muted">—</span> },
    {
      key: 'authn', header: '인증기',
      render: (c) => (
        <span className="row" style={{ gap: 6, flexWrap: 'nowrap' }} title={c.aaguid ?? undefined}>
          <span aria-hidden="true" style={{ width: 22, height: 22, borderRadius: 6, background: 'var(--brand-100)', color: 'var(--brand-700)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flex: 'none' }}>
            <Icons.Fingerprint size={13} />
          </span>
          <span className={c.authenticatorName ? undefined : 'mono'} style={c.authenticatorName ? { fontSize: 13 } : { fontSize: 12, color: 'var(--mute)' }}>{authenticatorLabel(c)}</span>
        </span>
      ),
    },
    { key: 'tr', header: '전송 방식', render: (c) => <span className="row" style={{ gap: 4 }}>{c.transports.map((t) => <span key={t} className="tag">{t}</span>)}</span> },
    {
      key: 'cnt', header: '서명 카운터', align: 'right',
      render: (c) => c.signatureCounter > 0
        ? <span className="mono" style={{ fontSize: 12 }}>{c.signatureCounter}</span>
        : c.lastUsedAt
          // 인증됐는데 counter=0 → counterless 인증기(플랫폼·싱크 패스키). WebAuthn 정상, counter 기반 복제 탐지 미적용.
          ? <span className="mono" style={{ fontSize: 12, color: 'var(--mute)' }} title="counterless 인증기 — 플랫폼·싱크 패스키는 signCount가 0으로 고정됩니다(WebAuthn 정상). counter 기반 복제 탐지 미적용.">N/A</span>
          : <span className="mono" style={{ fontSize: 12, color: 'var(--mute)' }}>0</span>,
    },
    { key: 'st', header: '상태', render: (c) => <StatusBadge status={c.status} /> },
    { key: 'used', header: '마지막 사용', render: (c) => <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{timeAgo(c.lastUsedAt)}</span> },
    {
      key: 'revoke', header: '회수', srOnlyHeader: true, width: 48,
      render: (c) => c.status === 'ACTIVE' ? (
        <IconButton label={`회수 · …${tail(c.credentialId, 8)}`} tone="danger" icon={<Icons.Trash size={15} />}
          onClick={(e) => { e.stopPropagation(); setRevoking(c); }} />
      ) : null,
    },
  ];

  const noData = total === 0 && appliedQ === '' && !clientFiltered;
  const empty = noData
    ? <EmptyState illustration={illustrations.emptyCredentials} title="Credential이 없습니다" description="사용자가 RP 에서 패스키를 등록하면 여기에 나타납니다." />
    : <EmptyState illustration={illustrations.noResults} title="검색 결과가 없습니다." description="검색어나 필터를 바꿔 보세요." action={<Button size="sm" onClick={clearAll}>필터 지우기</Button>} />;

  return (
    <div className="stack-6">
      <Card>
        <div className="card__head">
          <div className="search" style={{ flex: '0 1 340px' }}>
            <Icons.Search size={15} />
            <input className="input" aria-label="Credential 검색" placeholder="externalUserId · credentialId 검색" value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <FilterChip active={filtersOpen} onClick={() => setFiltersOpen((v) => !v)}><Icons.Plus size={13} />필터 · aaguid · status</FilterChip>
          {aaguid.trim() !== '' && <RemovableChip mono removeLabel="aaguid 필터 제거" onRemove={() => setAaguid('')}>aaguid: {aaguid.trim()}</RemovableChip>}
          {status !== 'ALL' && <RemovableChip removeLabel="status 필터 제거" onRemove={() => setStatus('ALL')}>status: {status}</RemovableChip>}
          <div className="spacer" />
          <span className="hint" style={{ margin: 0 }}>{clientFiltered ? `${fmtNum(visible.length)}건 (현재 페이지)` : `${fmtNum(total)}건`}</span>
          <Button icon={<Icons.Download size={14} />} onClick={exportCsv} disabled={visible.length === 0}>CSV</Button>
        </div>
        {filtersOpen && (
          <div className="row" style={{ gap: 16, padding: '10px 20px', borderBottom: '1px solid var(--line-soft)', background: 'var(--canvas)' }}>
            <label className="row" style={{ gap: 6, fontSize: 12, color: 'var(--mute)', flexWrap: 'nowrap' }}>
              aaguid
              <input className="input mono" style={{ width: 280, minHeight: 30 }} placeholder="예: ea9b8d66" value={aaguid} onChange={(e) => setAaguid(e.target.value)} />
            </label>
            <Segmented ariaLabel="status 필터" value={status} onChange={setStatus}
              options={[{ value: 'ALL', label: '전체' }, { value: 'ACTIVE', label: 'ACTIVE' }, { value: 'REVOKED', label: 'REVOKED' }]} />
            <span className="hint" style={{ margin: 0 }}>현재 페이지 안에서 거릅니다.</span>
          </div>
        )}
        {loading
          ? <p className="muted" style={{ padding: '40px 20px', margin: 0, textAlign: 'center', fontSize: 13 }}>불러오는 중…</p>
          : <DataTable columns={columns} rows={visible} rowKey={(c) => c.credentialId} onRowClick={setSelected} empty={empty} />}
        <Pagination page={page + 1} pageSize={size} total={total} onPage={(p) => setPage(p - 1)}
          pageSizeOptions={PAGE_SIZES} onPageSize={(n) => { setSize(n); setPage(0); }} />
      </Card>

      {revoking && (
        <RevokeCredentialDialog key={revoking.credentialId} c={revoking} onClose={() => setRevoking(null)} onConfirm={handleRevoke} />
      )}

      {selected && (
        <CredentialDetailDialog
          c={selected}
          tenantId={tenant.id}
          onClose={() => setSelected(null)}
          position={position}
          onPrev={!loading && canPrev ? goPrev : undefined}
          onNext={!loading && canNext ? goNext : undefined}
          onRevoke={(c) => { setSelected(null); setRevoking(c); }}
        />
      )}
    </div>
  );
}

// ── Credential 회수 — 마지막 8자 확인 ────────────────────────────────────────

function RevokeCredentialDialog({ c, onClose, onConfirm }: { c: Credential; onClose: () => void; onConfirm: () => void }) {
  const [ok, setOk] = useState(false);
  const required = tail(c.credentialId, 8);
  return (
    <Dialog open onClose={onClose} role="alertdialog" title="Credential 회수" sub="이 사용자는 해당 패스키로 더 이상 로그인할 수 없습니다."
      footer={<>
        <Button onClick={onClose}>취소</Button>
        <Button variant="danger-solid" disabled={!ok} onClick={onConfirm}>확인 — 회수</Button>
      </>}>
      <KvList keyWidth={100} items={[
        { k: 'credentialId', v: `…${required}`, mono: true },
        { k: '사용자', v: c.externalUserId, mono: true },
        { k: '인증기', v: authenticatorLabel(c) },
      ]} />
      <ConfirmByTyping
        id="credential-revoke-confirm"
        expected={required}
        onMatchChange={setOk}
        label={<>확인을 위해 마지막 8자 <span className="mono" style={{ background: 'var(--sunk)', padding: '0 4px', borderRadius: 'var(--r-xs)' }}>{required}</span> 를 입력하세요</>}
      />
    </Dialog>
  );
}
```

- [ ] **Step 9: 통과 확인**

Run: `npx vitest run src/pages/tenant src/api/credentials.test.ts src/ui/table.test.tsx`
Expected: PASS — `CredentialDetailDialog.test.tsx` 9개(기존 5 + 신규 4), `CredentialsTab.test.tsx` 7개.

- [ ] **Step 10: 전체 테스트 · 린트 · 빌드**

Run: `npm test && npm run lint && npm run build`
Expected: 모두 성공.

- [ ] **Step 11: 수동 확인 (서버 검색)**

`npm run dev` → Credentials 탭에서 표의 credentialId 앞 8자 정도를 검색창에 입력 → 네트워크 탭의 요청이 `…/credentials?page=0&size=50&q=…` 이고 결과가 좁혀지는지 확인. 상세 다이얼로그에서 페이지 마지막 행 → `다음` 이 다음 페이지 첫 행으로 이어지는지 확인(페이지당 50, 데모 데이터가 50건 미만이면 페이지당을 줄일 수 없으므로 이 항목은 테스트로 갈음).

- [ ] **Step 12: 커밋**

```bash
git add src/api/credentials.ts src/api/credentials.test.ts \
  src/pages/tenant/credentialHelpers.ts src/pages/tenant/credentialHelpers.test.ts \
  src/pages/tenant/CredentialsTab.tsx src/pages/tenant/CredentialsTab.test.tsx \
  src/pages/tenant/CredentialDetailDialog.tsx src/pages/tenant/CredentialDetailDialog.test.tsx
git commit -m "fix(admin-ui): credential 검색 파라미터 search → q, V2 Credentials 탭·상세(이전/다음)·회수 다이얼로그

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 25: 감사 로그 탭 (기간 → 서버 from/to · 실패만 · 날짜 그룹 · 행 펼침 · 다이얼로그 2종)

V2 아트보드: `v2/Tenant-Audit.dc.html`, `v2/Dialogs-Audit.dc.html`(Audit Event · Hash chain 검증).

**서버 확인:** `AuditLogController.list`(`admin-app/.../audit/AuditLogController.java`) 는 `tenantId`·`action`·`actorId`·`page`·`size`(최대 200)와 함께 `from`·`to` 를 `@DateTimeFormat(iso = DATE_TIME) OffsetDateTime` 으로 받는다. 응답은 `ApiResponse<List<AuditLogView>>`(건수 없음). 현재 클라이언트는 `from`/`to` 를 보내지 않고 받아온 50건을 브라우저에서 기간으로 잘라 30일을 골라도 최근 50건만 보인다 → 기간을 `from`/`to`(ISO-8601 `toISOString()`)로 서버에 보낸다.

**실패 판정:** `AuditTab.tsx` 에는 실패 목록이 없고(색 맵만 있음), 앱의 기존 실패 기준은 `pages/ActivityPage.tsx` 의 `FAILURE_TYPES`(`SIGNATURE_COUNTER_REGRESSION`·`ATTESTATION_TRUST_FAILED`·`ADMIN_LOGIN_FAILED`) + 이름 규칙 `/_(FAILED|FAIL|REGRESSION)$/` 이다. 같은 기준을 `auditHelpers.ts` 에 두고, 스펙 §6 ③ `result?` 가 있으면 그 값이 우선한다(`result === 'FAILURE'`). "실패만" 은 로드된 행을 클라이언트에서 거른다.

**건수 문구:** 스펙 §6 ③ `total?` 가 응답에 있으면 `최근 24시간 · 1,234건`, 없으면 `최근 {로드된 행 수}건`.

**더 보기:** 마지막 응답이 `size`(50)건이면 `더 보기` 를 보이고, 같은 `from`/`to` 로 `page+1` 을 불러와 뒤에 붙인다.

**Files:**
- Modify: `admin-ui/src/api/types.ts`(`AuditLogView.result?`), `admin-ui/src/api/designTypes.ts`(`AuditEvent.result?`), `admin-ui/src/api/audit.ts`
- Create: `admin-ui/src/api/audit.test.ts`
- Create: `admin-ui/src/pages/tenant/auditHelpers.ts`, `admin-ui/src/pages/tenant/auditHelpers.test.ts`
- Rewrite: `admin-ui/src/pages/tenant/AuditTab.tsx`
- Create: `admin-ui/src/pages/tenant/AuditTab.test.tsx`

**Interfaces:**
- Consumes: Task 23 `tabFormat`(`hms`, `ymd`, `ymdHms`, `timeAgo`, `fmtNum`). `@/ui` — `Badge`, `type BadgeTone`, `Button`, `Card`, `CardFooter`, `DataTable`, `type Column`, `Dialog`, `EmptyState`, `Field`, `FilterChip`, `Segmented`, `Switch`, `useToast`. `auditApi.verify`(변경 없음), `copyToClipboard`, `downloadCsv`, `useNavigate`.
- Produces:
  - `auditApi.list(tenantId: string, page = 0, size = 50, range?: { from: string; to: string }): Promise<{ items: AuditEvent[]; total?: number; page: number; size: number }>` — `range` 가 있으면 `from`·`to` 쿼리 추가. 응답이 배열이면 `total` 없음(키 자체 없음), `{ items, total? }` 객체면 `total` 전달.
  - `AuditLogView.result?: 'SUCCESS' | 'FAILURE'`, `AuditEvent.result?: 'SUCCESS' | 'FAILURE'`(스펙 §6 ③ `AuditRow.result?`). 어댑터는 값이 있을 때만 키를 만든다.
  - `auditHelpers.ts`: `type WindowKey = '24h' | '7d' | '30d'`, `WINDOWS: Record<WindowKey, { hours: number; label: string; long: string }>`, `windowRange(hours: number, now = Date.now()): { from: string; to: string }`, `FAILURE_TYPES: Set<string>`, `isFailure(e: Pick<AuditEvent, 'eventType' | 'result'>): boolean`, `eventTone(e): BadgeTone`, `type DiffEntry = { field: string; from: unknown; to: unknown }`, `payloadDiff(payload: Record<string, unknown> | null): DiffEntry[]`(`{ field: { from, to } }` 또는 `{ before: {...}, after: {...} }` 형태만), `diffValue(v: unknown): string`, `dayLabel(iso: string, now = Date.now()): string`(KST 기준 `오늘`/`어제`/`YYYY-MM-DD`).

- [ ] **Step 1: 실패하는 테스트 — API 클라이언트 · 헬퍼**

```ts
// admin-ui/src/api/audit.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { auditApi } from './audit';

function envelope(data: unknown) {
  return new Response(JSON.stringify({ success: true, data }), { status: 200, headers: { 'Content-Type': 'application/json' } });
}
const row = (p: Record<string, unknown> = {}) => ({
  id: 1, actorId: 1, actorEmail: 'a@x.com', tenantId: 't1', action: 'WEBAUTHN_CONFIG_UPDATED',
  targetType: 'TENANT', targetId: 't1', payload: '{"timeoutMs":{"from":60000,"to":120000}}', createdAt: '2026-10-10T05:00:00Z', ...p,
});

describe('auditApi.list', () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
  afterEach(() => vi.unstubAllGlobals());
  const lastParams = () => new URL((fetch as ReturnType<typeof vi.fn>).mock.calls.at(-1)![0], 'http://x').searchParams;

  it('기간을 from/to(ISO date-time) 로 서버에 보낸다', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(envelope([row()]));
    await auditApi.list('t1', 0, 50, { from: '2026-10-10T00:00:00.000Z', to: '2026-10-11T00:00:00.000Z' });
    const p = lastParams();
    expect(p.get('tenantId')).toBe('t1');
    expect(p.get('page')).toBe('0');
    expect(p.get('size')).toBe('50');
    expect(p.get('from')).toBe('2026-10-10T00:00:00.000Z');
    expect(p.get('to')).toBe('2026-10-11T00:00:00.000Z');
  });

  it('range 가 없으면 from/to 를 보내지 않는다', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(envelope([]));
    await auditApi.list('t1');
    expect(lastParams().has('from')).toBe(false);
    expect(lastParams().has('to')).toBe(false);
  });

  it('배열 응답이면 total 키가 없다(§6 total? 미배포)', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(envelope([row(), row({ id: 2 })]));
    const res = await auditApi.list('t1', 0, 50);
    expect(res.items).toHaveLength(2);
    expect('total' in res).toBe(false);
  });

  it('{ items, total } 응답이면 total 을 전달한다', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(envelope({ items: [row()], total: 1234 }));
    const res = await auditApi.list('t1', 0, 50);
    expect(res.items).toHaveLength(1);
    expect(res.total).toBe(1234);
  });

  it('result 는 서버가 줄 때만 AuditEvent.result 로 전달된다', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(envelope([row({ result: 'FAILURE' }), row({ id: 2 })]));
    const res = await auditApi.list('t1');
    expect(res.items[0].result).toBe('FAILURE');
    expect('result' in res.items[1]).toBe(false);
    expect(res.items[0].payload).toEqual({ timeoutMs: { from: 60000, to: 120000 } });
  });
});
```

```ts
// admin-ui/src/pages/tenant/auditHelpers.test.ts
import { describe, it, expect } from 'vitest';
import { windowRange, isFailure, eventTone, payloadDiff, diffValue, dayLabel, WINDOWS } from './auditHelpers';

describe('auditHelpers', () => {
  it('windowRange: now 기준 hours 시간 전 ~ now', () => {
    const now = Date.parse('2026-10-11T03:00:00Z');
    expect(windowRange(WINDOWS['24h'].hours, now)).toEqual({ from: '2026-10-10T03:00:00.000Z', to: '2026-10-11T03:00:00.000Z' });
    expect(WINDOWS['7d'].hours).toBe(168);
    expect(WINDOWS['30d'].hours).toBe(720);
  });

  it('isFailure: result 가 있으면 우선, 없으면 실패 목록·이름 규칙', () => {
    expect(isFailure({ eventType: 'ATTESTATION_TRUST_FAILED' })).toBe(true);
    expect(isFailure({ eventType: 'SIGNATURE_COUNTER_REGRESSION' })).toBe(true);
    expect(isFailure({ eventType: 'AUTH_FAIL' })).toBe(true);
    expect(isFailure({ eventType: 'WEBAUTHN_CONFIG_UPDATED' })).toBe(false);
    expect(isFailure({ eventType: 'CREDENTIAL_REVOKED' })).toBe(false);
    expect(isFailure({ eventType: 'CUSTOM_CHECK', result: 'FAILURE' })).toBe(true);
    expect(isFailure({ eventType: 'RETRY_FAILED', result: 'SUCCESS' })).toBe(false);
  });

  it('eventTone', () => {
    expect(eventTone({ eventType: 'AUTH_FAIL' })).toBe('bad');
    expect(eventTone({ eventType: 'WEBAUTHN_CONFIG_UPDATED' })).toBe('warn');
    expect(eventTone({ eventType: 'API_KEY_ROTATED' })).toBe('warn');
    expect(eventTone({ eventType: 'AUTH_SUCCESS' })).toBe('info');
    expect(eventTone({ eventType: 'MDS_SYNC' })).toBe('neutral');
  });

  it('payloadDiff: {field:{from,to}} 와 {before,after} 만 diff 로 본다', () => {
    expect(payloadDiff({ timeoutMs: { from: 60000, to: 120000 }, note: 'x' })).toEqual([{ field: 'timeoutMs', from: 60000, to: 120000 }]);
    expect(payloadDiff({ before: { a: 1, b: 2 }, after: { a: 1, b: 3, c: true } })).toEqual([
      { field: 'b', from: 2, to: 3 },
      { field: 'c', from: undefined, to: true },
    ]);
    expect(payloadDiff({ reason: 'x' })).toEqual([]);
    expect(payloadDiff(null)).toEqual([]);
  });

  it('diffValue', () => {
    expect(diffValue('a')).toBe('a');
    expect(diffValue(60000)).toBe('60000');
    expect(diffValue(['x'])).toBe('["x"]');
    expect(diffValue(undefined)).toBe('—');
  });

  it('dayLabel: KST 기준 오늘/어제/날짜', () => {
    const now = Date.parse('2026-10-11T03:00:00Z'); // KST 12:00
    expect(dayLabel('2026-10-11T00:30:00Z', now)).toBe('오늘');
    expect(dayLabel('2026-10-10T14:59:00Z', now)).toBe('어제');   // KST 10-10 23:59
    expect(dayLabel('2026-10-10T15:00:00Z', now)).toBe('오늘');   // KST 10-11 00:00
    expect(dayLabel('2026-10-08T05:00:00Z', now)).toBe('2026-10-08');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/api/audit.test.ts src/pages/tenant/auditHelpers.test.ts`
Expected: FAIL — `from` 이 `null`(현재 쿼리에 없음), `'total' in res` 가 true(현재 `rows.length`), `./auditHelpers` 모듈 없음.

- [ ] **Step 3: 타입 · API · 헬퍼 구현**

`src/api/types.ts` 의 `AuditLogView` 에 마지막 필드로 추가:

```ts
  result?: 'SUCCESS' | 'FAILURE';   // 스펙 §6 ③ AuditRow.result — 없으면 실패 판정은 액션 이름 기준
```

`src/api/designTypes.ts` 의 `AuditEvent` 에 마지막 필드로 추가:

```ts
  result?: 'SUCCESS' | 'FAILURE';   // 스펙 §6 ③ (optional)
```

`src/api/audit.ts` 에서 `adapt` 와 `auditApi.list` 를 교체(`verify` 는 그대로):

```ts
// 현재 서버: ApiResponse<List<AuditLogView>>. §6 ③ 이후 { items, total } 이 올 수 있다.
type AuditListResponse = AuditLogView[] | { items: AuditLogView[]; total?: number };

function adapt(s: AuditLogView): AuditEvent {
  let payload: Record<string, unknown> | null = null;
  if (s.payload) {
    try {
      payload = JSON.parse(s.payload) as Record<string, unknown>;
    } catch {
      payload = { raw: s.payload };
    }
  }
  return {
    id: String(s.id),
    ts: s.createdAt,
    eventType: s.action,
    actorType: s.actorEmail ? 'ADMIN' : 'SYSTEM',
    actorId: s.actorEmail ?? null,
    subjectType: s.targetType ?? null,
    subjectId: s.targetId ?? null,
    payload,
    ...(s.result !== undefined ? { result: s.result } : {}),
  };
}
```

```ts
  list: async (
    tenantId: string,
    page = 0,
    size = 50,
    range?: { from: string; to: string },
  ): Promise<{ items: AuditEvent[]; total?: number; page: number; size: number }> => {
    const q = new URLSearchParams({ tenantId, page: String(page), size: String(size) });
    // 서버 AuditLogController.list: from/to = ISO-8601 OffsetDateTime
    if (range) {
      q.set('from', range.from);
      q.set('to', range.to);
    }
    const res = await api.get<AuditListResponse>(`/admin/api/audit?${q}`);
    const rows = Array.isArray(res) ? res : res.items;
    const total = Array.isArray(res) ? undefined : res.total;
    return { items: rows.map(adapt), ...(total !== undefined ? { total } : {}), page, size };
  },
```

```ts
// admin-ui/src/pages/tenant/auditHelpers.ts
import type { AuditEvent } from '@/api/designTypes';
import type { BadgeTone } from '@/ui';
import { ymd } from './tabFormat';

export type WindowKey = '24h' | '7d' | '30d';
export const WINDOWS: Record<WindowKey, { hours: number; label: string; long: string }> = {
  '24h': { hours: 24, label: '24시간', long: '최근 24시간' },
  '7d': { hours: 168, label: '7일', long: '최근 7일' },
  '30d': { hours: 720, label: '30일', long: '최근 30일' },
};

export function windowRange(hours: number, now = Date.now()): { from: string; to: string } {
  return { from: new Date(now - hours * 3_600_000).toISOString(), to: new Date(now).toISOString() };
}

// pages/ActivityPage.tsx 의 FAILURE_TYPES·이름 규칙과 같은 기준(앱 전체 실패 판정 일치).
export const FAILURE_TYPES = new Set(['SIGNATURE_COUNTER_REGRESSION', 'ATTESTATION_TRUST_FAILED', 'ADMIN_LOGIN_FAILED']);
const FAILURE_NAME = /_(FAILED|FAIL|REGRESSION)$/;

/** §6 ③ result 가 있으면 그 값, 없으면 액션 이름 기준. */
export function isFailure(e: Pick<AuditEvent, 'eventType' | 'result'>): boolean {
  if (e.result !== undefined) return e.result === 'FAILURE';
  return FAILURE_TYPES.has(e.eventType) || FAILURE_NAME.test(e.eventType);
}

export function eventTone(e: Pick<AuditEvent, 'eventType' | 'result'>): BadgeTone {
  if (isFailure(e)) return 'bad';
  if (/_(UPDATED|ROTATED|REVOKED|ISSUED|SUSPENDED|DELETED)$/.test(e.eventType)) return 'warn';
  if (/(SUCCESS|AUTHENTICATED|REGISTERED|LOGIN)$/.test(e.eventType)) return 'info';
  return 'neutral';
}

export type DiffEntry = { field: string; from: unknown; to: unknown };
const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

export function payloadDiff(payload: Record<string, unknown> | null): DiffEntry[] {
  if (!payload) return [];
  const { before, after } = payload;
  if (isObj(before) && isObj(after)) {
    const keys = Array.from(new Set([...Object.keys(before), ...Object.keys(after)]));
    return keys
      .filter((k) => JSON.stringify(before[k]) !== JSON.stringify(after[k]))
      .map((k) => ({ field: k, from: before[k], to: after[k] }));
  }
  return Object.entries(payload)
    .filter(([, v]) => isObj(v) && 'from' in v && 'to' in v)
    .map(([k, v]) => ({ field: k, from: (v as Record<string, unknown>).from, to: (v as Record<string, unknown>).to }));
}

export function diffValue(v: unknown): string {
  if (v === undefined) return '—';
  return typeof v === 'string' ? v : JSON.stringify(v);
}

import { dayLabel as kstDayLabel } from '@/lib/kst'; // Task 17 에서 추가된 공용 구현

/** 감사 로그 그룹 라벨 — Activity(Task 17)와 같은 규칙을 쓰도록 공용 구현에 위임한다. */
export function dayLabel(iso: string, now = Date.now()): string {
  return kstDayLabel(iso, new Date(now));
}
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/api/audit.test.ts src/pages/tenant/auditHelpers.test.ts`
Expected: PASS.

- [ ] **Step 5: 실패하는 테스트 — 탭 동작**

```tsx
// admin-ui/src/pages/tenant/AuditTab.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ToastHost } from '@/ui';
import type { AuditEvent, Tenant } from '@/api/designTypes';

const api = vi.hoisted(() => ({ list: vi.fn(), verify: vi.fn() }));
vi.mock('@/api/audit', () => ({ auditApi: api }));

import AuditTab from './AuditTab';

const tenant: Tenant = {
  id: 't1', name: 'Acme', slug: 'acme', rpId: 'acme.com', status: 'ACTIVE', credentials: 0, apiKeys: 0,
  lastEventAt: null, createdAt: '2026-01-01T00:00:00Z', userVerification: 'PREFERRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const ev = (id: string, p: Partial<AuditEvent> = {}): AuditEvent => ({
  id, ts: new Date(Date.now() - 60_000).toISOString(), eventType: 'WEBAUTHN_CONFIG_UPDATED', actorType: 'ADMIN',
  actorId: 'admin@x.com', subjectType: 'TENANT', subjectId: 't1', payload: null, ...p,
});
const resp = (items: AuditEvent[], extra: { total?: number } = {}) => ({ items, page: 0, size: 50, ...extra });
const renderTab = (platform = false) =>
  render(<MemoryRouter><ToastHost><AuditTab tenant={tenant} isPlatformOperator={platform} /></ToastHost></MemoryRouter>);
const HOUR = 3_600_000;

describe('AuditTab V2', () => {
  beforeEach(() => vi.clearAllMocks());

  it('기간 세그먼트를 서버 from/to 로 보낸다 (24시간 → 7일)', async () => {
    api.list.mockResolvedValue(resp([]));
    renderTab();
    await waitFor(() => expect(api.list).toHaveBeenCalledTimes(1));
    const [tid, page, size, r1] = api.list.mock.calls[0];
    expect([tid, page, size]).toEqual(['t1', 0, 50]);
    expect(Date.parse(r1.to) - Date.parse(r1.from)).toBe(24 * HOUR);
    expect(Math.abs(Date.parse(r1.to) - Date.now())).toBeLessThan(5_000);

    fireEvent.click(within(screen.getByRole('group', { name: '기간' })).getByRole('button', { name: '7일' }));
    await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
    const r2 = api.list.mock.calls[1][3];
    expect(Date.parse(r2.to) - Date.parse(r2.from)).toBe(168 * HOUR);
  });

  it('실패만: result 가 있으면 우선, 없으면 실패 액션 목록·이름 규칙', async () => {
    api.list.mockResolvedValue(resp([
      ev('1', { eventType: 'ATTESTATION_TRUST_FAILED' }),
      ev('2', { eventType: 'WEBAUTHN_CONFIG_UPDATED' }),
      ev('3', { eventType: 'CUSTOM_CHECK', result: 'FAILURE' }),
      ev('4', { eventType: 'RETRY_FAILED', result: 'SUCCESS' }),
    ]));
    renderTab();
    await screen.findByText('WEBAUTHN_CONFIG_UPDATED', { selector: 'tbody .badge' });
    const shown = () => Array.from(document.querySelectorAll('tbody .badge')).map((b) => b.textContent);
    expect(shown()).toHaveLength(4);
    fireEvent.click(screen.getByRole('switch', { name: '실패만' }));
    expect(shown()).toEqual(['ATTESTATION_TRUST_FAILED', 'CUSTOM_CHECK']);
  });

  it('유형 칩으로 거르고 전체 해제', async () => {
    api.list.mockResolvedValue(resp([ev('1', { eventType: 'AUTH_SUCCESS' }), ev('2', { eventType: 'API_KEY_ROTATED' })]));
    renderTab();
    await screen.findByText('AUTH_SUCCESS', { selector: 'tbody .badge' });
    fireEvent.click(screen.getByRole('button', { name: 'API_KEY_ROTATED' }));
    expect(screen.queryByText('AUTH_SUCCESS', { selector: 'tbody .badge' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '전체 해제' }));
    expect(screen.getByText('AUTH_SUCCESS', { selector: 'tbody .badge' })).toBeInTheDocument();
  });

  it('날짜 그룹 행 + 행 펼침(diff·payload·JSON 복사·상세 다이얼로그)', async () => {
    api.list.mockResolvedValue(resp([ev('1', { payload: { timeoutMs: { from: 60000, to: 120000 } } })]));
    const { container } = renderTab();
    const badge = await screen.findByText('WEBAUTHN_CONFIG_UPDATED', { selector: 'tbody .badge' });
    expect(container.querySelector('tr.table__group')).toHaveTextContent('오늘');
    expect(screen.queryByRole('button', { name: 'JSON 복사' })).toBeNull();

    fireEvent.click(badge);
    expect(container.querySelectorAll('tr.table__expanded')).toHaveLength(1);
    expect(screen.getByText('60000')).toBeInTheDocument();
    expect(screen.getByText('120000')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'JSON 복사' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '상세 다이얼로그' }));
    const dlg = screen.getByRole('dialog', { name: /Audit Event/ });
    expect(within(dlg).getByText('누가 (actor)')).toBeInTheDocument();
    expect(within(dlg).getByText(/KST$/)).toBeInTheDocument();

    fireEvent.click(within(dlg).getByRole('button', { name: '닫기' }));
    fireEvent.click(badge);
    expect(container.querySelectorAll('tr.table__expanded')).toHaveLength(0);
  });

  it('건수: total 이 없으면 "최근 N건", 있으면 기간 · 총 건수', async () => {
    api.list.mockResolvedValueOnce(resp([ev('1'), ev('2')]));
    const { unmount } = renderTab();
    expect(await screen.findByText('최근 2건')).toBeInTheDocument();
    unmount();
    api.list.mockResolvedValueOnce(resp([ev('1')], { total: 1234 }));
    renderTab();
    expect(await screen.findByText('최근 24시간 · 1,234건')).toBeInTheDocument();
  });

  it('더 보기: 마지막 응답이 50건이면 같은 기간으로 다음 페이지를 붙인다', async () => {
    const first = Array.from({ length: 50 }, (_, i) => ev(`a${i}`));
    api.list.mockResolvedValueOnce(resp(first)).mockResolvedValueOnce(resp([ev('b0', { eventType: 'AUTH_SUCCESS' })]));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '더 보기' }));
    await screen.findByText('AUTH_SUCCESS', { selector: 'tbody .badge' });
    const [r1, r2] = [api.list.mock.calls[0], api.list.mock.calls[1]];
    expect(r2[1]).toBe(1);
    expect(r2[3]).toEqual(r1[3]);
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull();
    expect(screen.getByText('최근 51건')).toBeInTheDocument();
  });

  it('체인 검증 카드는 PLATFORM 전용, 검증 실행 → 결과 배지', async () => {
    api.list.mockResolvedValue(resp([]));
    const { unmount } = renderTab(false);
    await waitFor(() => expect(api.list).toHaveBeenCalled());
    expect(screen.queryByText('Audit Hash Chain 검증')).toBeNull();
    unmount();

    api.verify.mockResolvedValue({ intact: true, verifiedRows: 0, tamperedEntryIds: [], verifiedAt: new Date().toISOString() });
    renderTab(true);
    expect(await screen.findByText('Audit Hash Chain 검증')).toBeInTheDocument();
    expect(screen.getByText('PLATFORM 전용')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '검증 실행' }));
    const dlg = screen.getByRole('dialog', { name: 'Hash chain 검증' });
    fireEvent.click(within(dlg).getByRole('button', { name: '검증 실행' }));
    await waitFor(() => expect(api.verify).toHaveBeenCalledWith('t1', expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/), expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/)));
    expect(await screen.findByText('위변조 0건')).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Hash chain 검증' })).toBeNull();
  });
});
```

- [ ] **Step 6: 실패 확인**

Run: `npx vitest run src/pages/tenant/AuditTab.test.tsx`
Expected: FAIL — `list` 4번째 인자(range) 없음, `group[name=기간]` 없음(기존은 순환 버튼), `switch '실패만'` 없음.

- [ ] **Step 7: AuditTab 재작성**

```tsx
// admin-ui/src/pages/tenant/AuditTab.tsx
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import {
  Badge, Button, Card, CardFooter, DataTable, Dialog, EmptyState, Field, FilterChip, Segmented, Switch, useToast,
  type Column,
} from '@/ui';
import { auditApi } from '@/api/audit';
import { copyToClipboard } from '@/lib/clipboard';
import { downloadCsv } from '@/lib/csvExport';
import type { Tenant, AuditEvent, ChainVerifyResult } from '@/api/designTypes';
import { fmtNum, hms, timeAgo, ymd, ymdHms } from './tabFormat';
import { WINDOWS, type WindowKey, dayLabel, diffValue, eventTone, isFailure, payloadDiff, windowRange } from './auditHelpers';

const PAGE_SIZE = 50;
const tail = (s: string | null | undefined, n: number) => (s ? s.slice(-n) : '');
const pretty = (e: AuditEvent) => JSON.stringify(e.payload ?? {}, null, 2);

// ── AuditTab ──────────────────────────────────────────────────────────────────

export default function AuditTab({ tenant, isPlatformOperator = false }: { tenant: Tenant; isPlatformOperator?: boolean }) {
  const [items, setItems] = useState<AuditEvent[]>([]);
  const [total, setTotal] = useState<number | undefined>(undefined);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [filter, setFilter] = useState<Set<string>>(new Set());
  const [onlyFailures, setOnlyFailures] = useState(false);
  const [win, setWin] = useState<WindowKey>('24h');
  const [loading, setLoading] = useState(true);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [verifyResult, setVerifyResult] = useState<ChainVerifyResult | null>(null);
  const [showVerify, setShowVerify] = useState(false);
  const [open, setOpen] = useState<AuditEvent | null>(null);
  const rangeRef = useRef<{ from: string; to: string } | null>(null); // 더 보기도 같은 구간으로
  const toast = useToast();

  useEffect(() => {
    let cancelled = false;
    const range = windowRange(WINDOWS[win].hours);
    rangeRef.current = range;
    setLoading(true);
    setExpanded(null);
    auditApi.list(tenant.id, 0, PAGE_SIZE, range)
      .then((res) => {
        if (cancelled) return;
        setItems(res.items);
        setTotal(res.total);
        setPage(0);
        setHasMore(res.items.length === PAGE_SIZE);
      })
      .catch((e: unknown) => {
        if (!cancelled) toast({ kind: 'err', title: 'audit 로드 실패', message: e instanceof Error ? e.message : String(e) });
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [tenant.id, win]);

  async function loadMore() {
    if (!rangeRef.current) return;
    const next = page + 1;
    setLoadingMore(true);
    try {
      const res = await auditApi.list(tenant.id, next, PAGE_SIZE, rangeRef.current);
      setItems((prev) => [...prev, ...res.items]);
      setPage(next);
      setHasMore(res.items.length === PAGE_SIZE);
    } catch (e: unknown) {
      toast({ kind: 'err', title: 'audit 로드 실패', message: e instanceof Error ? e.message : String(e) });
    } finally {
      setLoadingMore(false);
    }
  }

  // 선택된 유형은 기간을 바꿔 목록에서 사라져도 칩으로 남긴다(해제 가능해야 함)
  const types = useMemo(() => Array.from(new Set([...items.map((e) => e.eventType), ...filter])), [items, filter]);
  const filtered = useMemo(
    () => items.filter((e) => (filter.size === 0 || filter.has(e.eventType)) && (!onlyFailures || isFailure(e))),
    [items, filter, onlyFailures],
  );

  function toggleType(t: string) {
    const next = new Set(filter);
    if (next.has(t)) next.delete(t); else next.add(t);
    setFilter(next);
  }

  async function copyJson(e: AuditEvent) {
    if (await copyToClipboard(pretty(e))) toast({ kind: 'ok', title: 'JSON 을 복사했습니다.' });
    else toast({ kind: 'warn', title: '복사 실패', message: '클립보드 복사에 실패했습니다.' });
  }

  function exportCsv() {
    if (filtered.length === 0) return;
    downloadCsv(
      `audit-${tenant.slug}-${new Date().toISOString().slice(0, 10)}.csv`,
      ['ts', 'eventType', 'actorType', 'actorId', 'subjectType', 'subjectId', 'payload'],
      filtered.map((e) => [e.ts, e.eventType, e.actorType, e.actorId ?? '', e.subjectType ?? '', e.subjectId ?? '', JSON.stringify(e.payload ?? {})]),
    );
  }

  async function handleVerifyRun(from: string, to: string) {
    try {
      const result = await auditApi.verify(tenant.id, from, to);
      setVerifyResult(result);
      setShowVerify(false);
    } catch (e: unknown) {
      toast({ kind: 'err', title: 'chain 검증 실패', message: e instanceof Error ? e.message : String(e) });
      setShowVerify(false);
      throw e; // 다이얼로그가 running 상태를 끝내도록
    }
  }

  const columns: Column<AuditEvent>[] = [
    {
      key: 'ts', header: '시각', width: 140,
      render: (e) => (
        <div className="stack-1" style={{ gap: 0 }}>
          <span style={{ fontSize: 13 }}>{hms(e.ts)}</span>
          <span className="hint" style={{ margin: 0 }}>{timeAgo(e.ts)}</span>
        </div>
      ),
    },
    { key: 'type', header: '이벤트', render: (e) => <Badge tone={eventTone(e)} dot={false} mono>{e.eventType}</Badge> },
    {
      key: 'actor', header: '행위자',
      render: (e) => (
        <span className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
          <span className="tag">{e.actorType}</span>
          {e.actorId && <span className="mono hint" style={{ margin: 0 }}>…{tail(e.actorId, 10)}</span>}
        </span>
      ),
    },
    {
      key: 'subject', header: '대상',
      render: (e) => (
        <span className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
          <span className="tag">{e.subjectType ?? '—'}</span>
          {e.subjectId && <span className="mono hint" style={{ margin: 0 }}>…{tail(e.subjectId, 12)}</span>}
        </span>
      ),
    },
    {
      key: 'detail', header: '상세 내용',
      render: (e) => <span className="mono hint truncate" style={{ display: 'inline-block', maxWidth: 280, margin: 0, verticalAlign: 'middle' }}>{JSON.stringify(e.payload)}</span>,
    },
    {
      key: 'chev', header: '펼치기', srOnlyHeader: true, width: 32,
      render: (e) => <span style={{ color: 'var(--mute)', display: 'inline-flex' }}>{expanded === e.id ? <Icons.ChevronUp size={14} /> : <Icons.ChevronDown size={14} />}</span>,
    },
  ];

  const countText = total !== undefined ? `${WINDOWS[win].long} · ${fmtNum(total)}건` : `최근 ${fmtNum(items.length)}건`;

  return (
    <div className="stack-6">
      {isPlatformOperator && <ChainVerifyCard onOpen={() => setShowVerify(true)} result={verifyResult} />}

      <Card>
        <div className="card__head">
          <div className="row" style={{ gap: 6 }}>
            {types.map((t) => <FilterChip key={t} active={filter.has(t)} onClick={() => toggleType(t)}>{t}</FilterChip>)}
            {filter.size > 0 && <Button variant="ghost" size="sm" onClick={() => setFilter(new Set())}>전체 해제</Button>}
          </div>
          <div className="spacer" />
          <Switch checked={onlyFailures} onChange={setOnlyFailures} label="실패만" />
          <Segmented ariaLabel="기간" value={win} onChange={setWin}
            options={(Object.keys(WINDOWS) as WindowKey[]).map((k) => ({ value: k, label: WINDOWS[k].label }))} />
          <Button icon={<Icons.Download size={14} />} onClick={exportCsv} disabled={filtered.length === 0}>내보내기</Button>
        </div>

        {loading ? (
          <p className="muted" style={{ padding: '40px 20px', margin: 0, textAlign: 'center', fontSize: 13 }}>불러오는 중…</p>
        ) : (
          <DataTable
            columns={columns}
            rows={filtered}
            rowKey={(e) => e.id}
            groupBy={(e) => dayLabel(e.ts)}
            onRowClick={(e) => setExpanded((k) => (k === e.id ? null : e.id))}
            expandedKey={expanded}
            renderExpanded={(e) => <ExpandedEvent e={e} onCopy={() => void copyJson(e)} onOpen={() => setOpen(e)} />}
            empty={<EmptyState compact title={filter.size > 0 || onlyFailures ? '조건에 맞는 이벤트가 없습니다.' : '이 기간에 감사 이벤트가 없습니다.'} />}
          />
        )}

        <CardFooter>
          <span className="hint" style={{ margin: 0 }}>{countText}</span>
          <div className="spacer" />
          {hasMore && <Button size="sm" disabled={loadingMore} onClick={() => void loadMore()}>{loadingMore ? '불러오는 중…' : '더 보기'}</Button>}
        </CardFooter>
      </Card>

      {isPlatformOperator && <ChainVerifyDialog open={showVerify} onClose={() => setShowVerify(false)} onRun={handleVerifyRun} />}
      {open && <AuditEventDialog event={open} onClose={() => setOpen(null)} onCopy={() => void copyJson(open)} />}
    </div>
  );
}

// ── 펼친 행: diff 칩 + payload + JSON 복사 + 상세 다이얼로그 ─────────────────

function ExpandedEvent({ e, onCopy, onOpen }: { e: AuditEvent; onCopy: () => void; onOpen: () => void }) {
  const diff = payloadDiff(e.payload);
  return (
    <div className="stack-3" style={{ paddingLeft: 140 }}>
      {diff.map((d) => (
        <div key={d.field} className="row" style={{ gap: 8, fontSize: 12, flexWrap: 'nowrap' }}>
          <span className="mono truncate" style={{ width: 96, color: 'var(--mute)' }}>{d.field}</span>
          <del className="tag" style={{ background: 'var(--bad-soft)', color: 'var(--bad-ink)' }}>{diffValue(d.from)}</del>
          <span aria-hidden="true">→</span>
          <ins className="tag" style={{ background: 'var(--ok-soft)', color: 'var(--ok-ink)', textDecoration: 'none' }}>{diffValue(d.to)}</ins>
        </div>
      ))}
      <div className="row" style={{ gap: 8 }}>
        <span className="t-over">payload</span>
        <div className="spacer" />
        <Button variant="ghost" size="sm" onClick={onCopy}>JSON 복사</Button>
        <Button size="sm" onClick={onOpen}>상세 다이얼로그</Button>
      </div>
      <pre className="mono" style={{ margin: 0, padding: '10px 12px', border: '1px solid var(--line)', borderRadius: 8, background: 'var(--surface)', fontSize: 11, lineHeight: '17px', whiteSpace: 'pre-wrap' }}>{pretty(e)}</pre>
    </div>
  );
}

// ── Audit Event 다이얼로그 (V2: 언제 · 누가 · 대상 · payload) ────────────────

function AuditEventDialog({ event, onClose, onCopy }: { event: AuditEvent; onClose: () => void; onCopy: () => void }) {
  const t = new Date(event.ts);
  return (
    <Dialog open onClose={onClose}
      title={<span className="row" style={{ gap: 8 }}><span>Audit Event</span><Badge tone={eventTone(event)} dot={false} mono>{event.eventType}</Badge></span>}
      footer={<Button onClick={onClose}>닫기</Button>}>
      <div className="stack-1">
        <span className="t-over">언제</span>
        <span style={{ fontSize: 13 }}>{ymdHms(event.ts)} KST</span>
        <span className="mono hint" style={{ margin: 0 }}>{isNaN(t.getTime()) ? event.ts : t.toISOString()}</span>
      </div>
      <div className="row" style={{ gap: 24, alignItems: 'flex-start' }}>
        <div className="stack-1">
          <span className="t-over">누가 (actor)</span>
          <span className="row" style={{ gap: 6, fontSize: 13 }}><span className="tag">{event.actorType}</span><span className="mono hint" style={{ margin: 0 }}>{event.actorId ?? '—'}</span></span>
        </div>
        <div className="stack-1">
          <span className="t-over">대상 (subject)</span>
          <span className="row" style={{ gap: 6, fontSize: 13 }}><span className="tag">{event.subjectType ?? '—'}</span><span className="mono hint" style={{ margin: 0 }}>{event.subjectId ?? '—'}</span></span>
        </div>
      </div>
      <div className="stack-2">
        <div className="row"><span className="t-over">payload</span><div className="spacer" /><Button variant="ghost" size="sm" onClick={onCopy}>JSON 복사</Button></div>
        <pre className="mono" style={{ margin: 0, padding: 12, borderRadius: 8, background: 'var(--canvas)', border: '1px solid var(--line-soft)', fontSize: 11, lineHeight: '17px', whiteSpace: 'pre-wrap' }}>{pretty(event)}</pre>
      </div>
    </Dialog>
  );
}

// ── 체인 검증 카드 (PLATFORM 전용) ─────────────────────────────────────────────

function ChainVerifyCard({ onOpen, result }: { onOpen: () => void; result: ChainVerifyResult | null }) {
  const navigate = useNavigate();
  const tampered = result != null && !result.intact;
  return (
    <Card>
      <div className="row" style={{ gap: 16, padding: '16px 20px' }}>
        <span aria-hidden="true" style={{ width: 40, height: 40, borderRadius: 10, background: tampered ? 'var(--bad-soft)' : 'var(--ok-soft)', color: tampered ? 'var(--bad)' : 'var(--ok)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flex: 'none' }}>
          <Icons.Shield size={20} />
        </span>
        <div className="stack-1" style={{ gap: 2, flex: '1 1 320px', minWidth: 0 }}>
          <div className="row" style={{ gap: 8 }}>
            <h2 className="card__title">Audit Hash Chain 검증</h2>
            {result && (result.intact
              ? <Badge tone="ok">위변조 0건</Badge>
              : <Badge tone="bad">위변조 {result.tamperedEntryIds.length}건</Badge>)}
            <Badge tone="plat" dot={false}>PLATFORM 전용</Badge>
          </div>
          <p className="card__sub">이 tenant 의 감사 로그 행이 hash 로 연결되어 변경·삭제되지 않았는지 검증합니다.</p>
        </div>
        {result?.intact && <Button onClick={() => navigate('/audit-chain')}>보고서</Button>}
        <Button variant="primary" onClick={onOpen}>검증 실행</Button>
      </div>
    </Card>
  );
}

// ── Hash chain 검증 다이얼로그 (진행률) ────────────────────────────────────────

function ChainVerifyDialog({ open, onClose, onRun }: { open: boolean; onClose: () => void; onRun: (from: string, to: string) => Promise<void> }) {
  // 기본 구간: 최근 7일(KST). auditApi.verify 가 windowHours 로 환산해 최대 168h 로 자른다.
  const [from, setFrom] = useState(() => ymd(new Date(Date.now() - 7 * 86_400_000).toISOString()));
  const [to, setTo] = useState(() => ymd(new Date().toISOString()));
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState(0);

  async function run() {
    setRunning(true);
    setProgress(0);
    // API 응답 전까지 진행 막대를 95% 까지 움직인다(서버는 진행률을 주지 않음).
    const tick = setInterval(() => {
      setProgress((p) => Math.min(95, p + 7 + Math.random() * 9));
    }, 90);
    try {
      await onRun(from, to);
      setProgress(100);
    } catch {
      // 오류 토스트는 부모가 띄운다
    } finally {
      clearInterval(tick);
      setRunning(false);
    }
  }
  const pct = Math.floor(progress);

  return (
    <Dialog open={open} onClose={onClose} dismissible={!running} title="Hash chain 검증" sub="읽기 전용 검증입니다. 데이터는 변경되지 않습니다."
      footer={<>
        <Button onClick={onClose} disabled={running}>취소</Button>
        <Button variant="primary" disabled={running} onClick={() => void run()}>{running ? `검증 중… ${pct}%` : '검증 실행'}</Button>
      </>}>
      <div className="grid-2" style={{ gap: 12 }}>
        <Field label="from" htmlFor="chain-from"><input id="chain-from" className="input" type="date" value={from} disabled={running} onChange={(e) => setFrom(e.target.value)} /></Field>
        <Field label="to" htmlFor="chain-to"><input id="chain-to" className="input" type="date" value={to} disabled={running} onChange={(e) => setTo(e.target.value)} /></Field>
      </div>
      {running && (
        <div className="stack-2">
          <div className="row">
            <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>검증 중…</span>
            <div className="spacer" />
            <span className="mono hint" style={{ margin: 0 }}>{pct}%</span>
          </div>
          <div role="progressbar" aria-label="검증 진행률" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}
            style={{ height: 8, borderRadius: 4, background: 'var(--line-soft)', overflow: 'hidden' }}>
            <div style={{ width: `${pct}%`, height: 8, background: 'var(--brand-700)', transition: 'width 90ms linear' }} />
          </div>
        </div>
      )}
    </Dialog>
  );
}
```

주의: 캔버스의 `[x] / [N] 행` 진행 행 수는 서버가 주지 않으므로(기존 코드는 가짜 숫자 `184,293` 을 그렸다) 퍼센트만 표시한다. 기존 기본 날짜 `2026-05-01 ~ 05-15` 는 고정 데모 값이라 "최근 7일" 로 바꾼다 — 두 값 모두 `windowHours=168` 로 환산되어 서버 호출은 같다.

- [ ] **Step 8: 통과 확인**

Run: `npx vitest run src/pages/tenant/AuditTab.test.tsx src/pages/tenant/auditHelpers.test.ts src/api/audit.test.ts`
Expected: PASS — 7 + 6 + 5.

- [ ] **Step 9: 전체 테스트 · 린트 · 빌드**

Run: `npm test && npm run lint && npm run build`
Expected: 모두 성공.

- [ ] **Step 10: 수동 확인 (서버 기간)**

`npm run dev` → 감사 로그 탭에서 `30일` 선택 → 네트워크 요청이 `/admin/api/audit?tenantId=…&page=0&size=50&from=…Z&to=…Z` 이고 200 응답인지(서버 `@DateTimeFormat(iso = DATE_TIME)` 가 `toISOString()` 형식을 받는지) 확인.

- [ ] **Step 11: 커밋**

```bash
git add src/api/types.ts src/api/designTypes.ts src/api/audit.ts src/api/audit.test.ts \
  src/pages/tenant/auditHelpers.ts src/pages/tenant/auditHelpers.test.ts src/pages/tenant/AuditTab.tsx src/pages/tenant/AuditTab.test.tsx
git commit -m "feat(admin-ui): V2 감사 로그 탭 — 기간 from/to 서버 전달·실패만·날짜 그룹·행 펼침·Audit Event/검증 다이얼로그

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 26: 퍼널 탭 (요약 KPI 먼저 · 단계 전환율 · 차트 표로 보기 · CSV)

V2 아트보드: `v2/Tenant-Funnel.dc.html`.

**서버 확인:** `FunnelController` 는 `windowDays ∈ {1, 7, 30}`(그 외 7)을 받고, `FunnelService.buildSeries` 는 `windowDays` 개의 `DailyPoint(day "MM-DD", attempts, success)` 를 **오래된 날 → 오늘** 순으로 돌려준다. `series` 의 시도·성공은 인증(authentication) ceremony 기준이다.

**KPI 규칙:**
- 전체 전환율 = `인증 성공 / 등록 시도`(`registration.attempts` 가 0 이면 `—`). 인증 시도는 기존 credential 로도 일어나므로 100% 를 넘을 수 있으며, 계산값을 그대로 보인다.
- 최대 이탈 단계 = 인접 단계(등록 시도→등록 성공→인증 시도→인증 성공) 중 `이전 − 현재` 가 가장 큰 구간. 감소가 없으면 `—` + `이탈 없음`.
- 직전 기간 대비(state rule): **7d 에서만** 30일 퍼널을 한 번 더 받아(`funnelApi.get(tenantId, 30)`) 그 `series` 의 마지막 7일과 그 앞 7일의 **인증 성공률**(Σsuccess/Σattempts) 차이를 %p 로 보인다. 24h·30d 에서는 30일 조회를 하지 않고 줄 자체를 렌더하지 않는다. 조회 실패·데이터 부족(14일 미만)·어느 쪽이든 시도 0건이면 렌더하지 않는다. 기간을 바꾸면 이전 비교값을 즉시 지운다.
- 기간 KPI = `최근 24시간|7일|30일` + `YYYY-MM-DD – YYYY-MM-DD`(KST).

**Files:**
- Create: `admin-ui/src/pages/tenant/funnelHelpers.ts`, `admin-ui/src/pages/tenant/funnelHelpers.test.ts`
- Rewrite: `admin-ui/src/pages/tenant/FunnelTab.tsx`
- Create: `admin-ui/src/pages/tenant/FunnelTab.test.tsx`

**Interfaces:**
- Consumes: `funnelApi.get(tenantId, windowDays: 1 | 7 | 30)`(변경 없음), `FunnelData`/`FunnelSeries`/`FunnelByEventType`, `downloadCsv`. Task 23 `ymd`, `fmtNum`. `@/ui` — `Alert`, `Button`, `Card`, `CardHeader`, `CardBody`, `DataTable`, `type Column`, `KpiCard`, `Segmented`.
- Produces:
  - `type FunnelStep = { label: string; value: number; pctOfFirst: number | null; convFromPrev: number | null; dropFromPrev: number }`
  - `type FunnelSummary = { steps: FunnelStep[]; overallRate: number | null; biggestDrop: { from: string; to: string; lost: number; rate: number } | null }`
  - `funnelSummary(f: FunnelData): FunnelSummary`
  - `priorPeriodDelta(series: FunnelSeries[], windowDays: number): number | null` — 퍼센트포인트(예: 50%→80% = `30`)
  - `fmtPct(r: number | null, digits = 1): string`(`null` → `'—'`), `fmtDeltaPp(d: number): string`(`+30.0%p` / `-2.5%p` / `±0.0%p`)

- [ ] **Step 1: 실패하는 테스트 — 순수 헬퍼**

```ts
// admin-ui/src/pages/tenant/funnelHelpers.test.ts
import { describe, it, expect } from 'vitest';
import type { FunnelData, FunnelSeries } from '@/api/funnel';
import { funnelSummary, priorPeriodDelta, fmtPct, fmtDeltaPp } from './funnelHelpers';

const data = (reg: [number, number], auth: [number, number]): FunnelData => ({
  windowDays: 7,
  registration: { attempts: reg[0], success: reg[1], ratio: 0 },
  authentication: { attempts: auth[0], success: auth[1], ratio: 0 },
  conversion: 0, series: [], byEventType: [],
});
const series = (n: number, success: (i: number) => number, attempts: (i: number) => number = () => 10): FunnelSeries[] =>
  Array.from({ length: n }, (_, i) => ({ day: `D${i}`, attempts: attempts(i), success: success(i) }));

describe('funnelSummary', () => {
  it('단계·전환율·이탈·최대 이탈 단계·전체 전환율', () => {
    const s = funnelSummary(data([100, 80], [70, 63]));
    expect(s.steps.map((x) => x.label)).toEqual(['등록 시도', '등록 성공', '인증 시도', '인증 성공']);
    expect(s.steps.map((x) => x.value)).toEqual([100, 80, 70, 63]);
    expect(s.steps.map((x) => x.pctOfFirst)).toEqual([1, 0.8, 0.7, 0.63]);
    expect(s.steps.map((x) => x.convFromPrev)).toEqual([null, 0.8, 0.875, 0.9]);
    expect(s.steps.map((x) => x.dropFromPrev)).toEqual([0, 20, 10, 7]);
    expect(s.overallRate).toBeCloseTo(0.63);
    expect(s.biggestDrop).toEqual({ from: '등록 시도', to: '등록 성공', lost: 20, rate: 0.2 });
  });
  it('단계가 늘어나는 구간은 이탈 0, 감소가 없으면 biggestDrop=null', () => {
    const s = funnelSummary(data([10, 10], [40, 40]));
    expect(s.steps.map((x) => x.dropFromPrev)).toEqual([0, 0, 0, 0]);
    expect(s.biggestDrop).toBeNull();
    expect(s.overallRate).toBe(4);
  });
  it('모두 0 이면 비율은 null (0/0 금지)', () => {
    const s = funnelSummary(data([0, 0], [0, 0]));
    expect(s.overallRate).toBeNull();
    expect(s.steps.every((x) => x.pctOfFirst === null)).toBe(true);
    expect(s.steps[1].convFromPrev).toBeNull();
    expect(s.biggestDrop).toBeNull();
  });
});

describe('priorPeriodDelta', () => {
  it('마지막 N일 vs 그 앞 N일 성공률 차이(%p)', () => {
    // 30일: 앞쪽 23일 성공 5/10, 마지막 7일 성공 8/10 → 직전 7일(인덱스 16..22) 50% vs 최근 7일 80%
    const s = series(30, (i) => (i >= 23 ? 8 : 5));
    expect(priorPeriodDelta(s, 7)).toBeCloseTo(30);
  });
  it('하락은 음수', () => {
    const s = series(14, (i) => (i >= 7 ? 4 : 6));
    expect(priorPeriodDelta(s, 7)).toBeCloseTo(-20);
  });
  it('데이터 부족·시도 0건이면 null', () => {
    expect(priorPeriodDelta(series(10, () => 5), 7)).toBeNull();
    expect(priorPeriodDelta(series(14, () => 0, (i) => (i < 7 ? 0 : 10)), 7)).toBeNull();
    expect(priorPeriodDelta([], 7)).toBeNull();
  });
});

describe('format', () => {
  it('fmtPct / fmtDeltaPp', () => {
    expect(fmtPct(0.63)).toBe('63.0%');
    expect(fmtPct(0.875, 0)).toBe('88%');
    expect(fmtPct(null)).toBe('—');
    expect(fmtDeltaPp(30.0000001)).toBe('+30.0%p');
    expect(fmtDeltaPp(-2.46)).toBe('-2.5%p');
    expect(fmtDeltaPp(0)).toBe('±0.0%p');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/tenant/funnelHelpers.test.ts`
Expected: FAIL — `Failed to resolve import "./funnelHelpers"`.

- [ ] **Step 3: 헬퍼 구현**

```ts
// admin-ui/src/pages/tenant/funnelHelpers.ts
import type { FunnelData, FunnelSeries } from '@/api/funnel';

export type FunnelStep = { label: string; value: number; pctOfFirst: number | null; convFromPrev: number | null; dropFromPrev: number };
export type FunnelSummary = {
  steps: FunnelStep[];
  overallRate: number | null; // 인증 성공 / 등록 시도
  biggestDrop: { from: string; to: string; lost: number; rate: number } | null;
};

const LABELS = ['등록 시도', '등록 성공', '인증 시도', '인증 성공'] as const;

export function funnelSummary(f: FunnelData): FunnelSummary {
  const values = [f.registration.attempts, f.registration.success, f.authentication.attempts, f.authentication.success];
  const first = values[0];
  const steps: FunnelStep[] = values.map((v, i) => {
    const prev = i > 0 ? values[i - 1] : null;
    return {
      label: LABELS[i],
      value: v,
      pctOfFirst: first > 0 ? v / first : null,
      convFromPrev: prev != null && prev > 0 ? v / prev : null,
      dropFromPrev: prev != null ? Math.max(0, prev - v) : 0,
    };
  });
  let biggestDrop: FunnelSummary['biggestDrop'] = null;
  for (let i = 1; i < steps.length; i++) {
    const lost = steps[i].dropFromPrev;
    if (lost > 0 && (biggestDrop === null || lost > biggestDrop.lost)) {
      biggestDrop = { from: steps[i - 1].label, to: steps[i].label, lost, rate: lost / values[i - 1] };
    }
  }
  return { steps, overallRate: first > 0 ? values[3] / first : null, biggestDrop };
}

function successRate(s: FunnelSeries[]): number | null {
  const attempts = s.reduce((a, d) => a + d.attempts, 0);
  return attempts > 0 ? s.reduce((a, d) => a + d.success, 0) / attempts : null;
}

/** series(오래된 날 → 오늘)의 마지막 windowDays 일 vs 그 앞 windowDays 일 인증 성공률 차이(%p). */
export function priorPeriodDelta(series: FunnelSeries[], windowDays: number): number | null {
  if (windowDays <= 0 || series.length < windowDays * 2) return null;
  const cur = successRate(series.slice(-windowDays));
  const prior = successRate(series.slice(-windowDays * 2, -windowDays));
  if (cur === null || prior === null) return null;
  return (cur - prior) * 100;
}

export function fmtPct(r: number | null, digits = 1): string {
  return r === null ? '—' : `${(r * 100).toFixed(digits)}%`;
}

export function fmtDeltaPp(d: number): string {
  const v = Math.abs(d).toFixed(1);
  if (v === '0.0') return '±0.0%p';
  return `${d > 0 ? '+' : '-'}${v}%p`;
}
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/tenant/funnelHelpers.test.ts`
Expected: PASS (7개).

- [ ] **Step 5: 실패하는 테스트 — 탭 동작**

```tsx
// admin-ui/src/pages/tenant/FunnelTab.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import type { FunnelData, FunnelSeries } from '@/api/funnel';
import type { Tenant } from '@/api/designTypes';

const api = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('@/api/funnel', () => ({ funnelApi: api }));
const csv = vi.hoisted(() => ({ downloadCsv: vi.fn() }));
vi.mock('@/lib/csvExport', () => csv);

import FunnelTab from './FunnelTab';

const tenant: Tenant = {
  id: 't1', name: 'Acme', slug: 'acme', rpId: 'acme.com', status: 'ACTIVE', credentials: 0, apiKeys: 0,
  lastEventAt: null, createdAt: '2026-01-01T00:00:00Z', userVerification: 'PREFERRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const days = (n: number, success: (i: number) => number): FunnelSeries[] =>
  Array.from({ length: n }, (_, i) => ({ day: `10-${String(i + 1).padStart(2, '0')}`, attempts: 10, success: success(i) }));
const data = (w: number, series: FunnelSeries[]): FunnelData => ({
  windowDays: w,
  registration: { attempts: 100, success: 80, ratio: 0.8 },
  authentication: { attempts: 70, success: 63, ratio: 0.9 },
  conversion: 0.63, series, byEventType: [{ type: 'AUTH_SUCCESS', n: 63 }, { type: 'AUTH_FAIL', n: 7 }],
});
const callsWith = (w: number) => api.get.mock.calls.filter((c) => c[1] === w).length;

describe('FunnelTab V2', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.get.mockImplementation((_t: string, w: 1 | 7 | 30) =>
      Promise.resolve(w === 30 ? data(30, days(30, (i) => (i >= 23 ? 8 : 5))) : data(w, days(w, () => 9))));
  });

  it('7d: 요약 KPI 먼저 — 전체 전환율, 직전 7일 대비(30일 series), 최대 이탈 단계', async () => {
    render(<FunnelTab tenant={tenant} />);
    expect(await screen.findByText('63.0%')).toBeInTheDocument();
    expect(api.get).toHaveBeenCalledWith('t1', 7);
    expect(api.get).toHaveBeenCalledWith('t1', 30);
    expect(await screen.findByText('직전 7일 대비 +30.0%p (인증 성공률)')).toBeInTheDocument();
    expect(screen.getByText('등록 시도 → 등록 성공')).toBeInTheDocument();
    expect(screen.getByText('이탈 20건 · 20.0%')).toBeInTheDocument();
    expect(screen.getByText('최근 7일')).toBeInTheDocument();
    // 단계 사이 전환율 칩
    for (const t of ['80.0%', '87.5%', '90.0%']) expect(screen.getByText(t)).toHaveClass('tag');
  });

  it('24h·30d 에서는 직전 기간 대비를 계산·표시하지 않는다', async () => {
    render(<FunnelTab tenant={tenant} />);
    await screen.findByText('직전 7일 대비 +30.0%p (인증 성공률)');
    expect(callsWith(30)).toBe(1);

    fireEvent.click(within(screen.getByRole('group', { name: '기간' })).getByRole('button', { name: '24h' }));
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('t1', 1));
    await screen.findByText('최근 24시간');
    expect(screen.queryByText(/직전 7일 대비/)).toBeNull();
    expect(callsWith(30)).toBe(1); // 24h 는 30일 비교 조회를 하지 않는다

    fireEvent.click(within(screen.getByRole('group', { name: '기간' })).getByRole('button', { name: '30d' }));
    await screen.findByText('최근 30일');
    expect(callsWith(30)).toBe(2);   // 본 조회 1회만(비교 조회 없음)
    expect(screen.queryByText(/직전 7일 대비/)).toBeNull();
  });

  it('표로 보기: 같은 데이터를 표로, CSV 는 일별 시리즈', async () => {
    render(<FunnelTab tenant={tenant} />);
    await screen.findByText('63.0%');
    const [seriesToggle] = screen.getAllByRole('button', { name: '표로 보기' });
    expect(screen.queryByRole('table')).toBeNull();
    fireEvent.click(seriesToggle);
    expect(seriesToggle).toHaveAttribute('aria-pressed', 'true');
    const table = screen.getByRole('table');
    expect(within(table).getAllByRole('row')).toHaveLength(1 + 7);
    expect(within(table).getByText('10-01')).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole('button', { name: 'CSV' })[0]);
    expect(csv.downloadCsv).toHaveBeenCalledWith(expect.stringMatching(/^funnel-series-acme-/), ['day', 'attempts', 'success'], expect.arrayContaining([['10-01', 10, 9]]));
  });

  it('조회 실패 시 오류 배너', async () => {
    api.get.mockRejectedValue(new Error('boom'));
    render(<FunnelTab tenant={tenant} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('퍼널 데이터를 불러오지 못했습니다.');
  });
});
```

- [ ] **Step 6: 실패 확인**

Run: `npx vitest run src/pages/tenant/FunnelTab.test.tsx`
Expected: FAIL — `63.0%` 없음(기존은 KPI 없음), `get('t1', 30)` 미호출, `group[name=기간]` 없음.

- [ ] **Step 7: FunnelTab 재작성**

```tsx
// admin-ui/src/pages/tenant/FunnelTab.tsx
import { Fragment, useEffect, useState } from 'react';
import { Icons } from '@/icons/Icons';
import { Alert, Button, Card, CardBody, CardHeader, DataTable, KpiCard, Segmented, type Column } from '@/ui';
import { funnelApi } from '@/api/funnel';
import type { FunnelData, FunnelSeries, FunnelByEventType } from '@/api/funnel';
import type { Tenant } from '@/api/designTypes';
import { downloadCsv } from '@/lib/csvExport';
import { fmtNum, ymd } from './tabFormat';
import { fmtDeltaPp, fmtPct, funnelSummary, priorPeriodDelta } from './funnelHelpers';

type Win = 1 | 7 | 30;
const WIN_OPTIONS: { value: string; label: string }[] = [
  { value: '1', label: '24h' }, { value: '7', label: '7d' }, { value: '30', label: '30d' },
];
const PERIOD_LABEL: Record<Win, string> = { 1: '최근 24시간', 7: '최근 7일', 30: '최근 30일' };
// 단계 막대: 옅은 → 진한 브랜드 (캔버스 4단계 블루)
const STEP_COLORS = ['var(--brand-300)', 'var(--brand-500)', 'var(--brand-600)', 'var(--brand-700)'];
const DIST_COLORS = ['var(--brand-700)', 'var(--brand-500)', 'var(--brand-300)', 'var(--brand-900)', 'var(--brand-600)'];
const today = () => new Date().toISOString().slice(0, 10);

export default function FunnelTab({ tenant }: { tenant: Tenant }) {
  const [windowDays, setWindowDays] = useState<Win>(7);
  const [data, setData] = useState<FunnelData | null>(null);
  const [failed, setFailed] = useState(false);
  const [series30, setSeries30] = useState<FunnelSeries[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    setFailed(false);
    funnelApi.get(tenant.id, windowDays)
      .then((d) => { if (!cancelled) setData(d); })
      .catch(() => { if (!cancelled) { setData(null); setFailed(true); } });
    return () => { cancelled = true; };
  }, [tenant.id, windowDays]);

  // 직전 기간 대비: 7d 에서만. 30일 series 로 최근 7일 vs 그 앞 7일 인증 성공률을 비교한다.
  useEffect(() => {
    setSeries30(null);
    if (windowDays !== 7) return;
    let cancelled = false;
    funnelApi.get(tenant.id, 30)
      .then((d) => { if (!cancelled) setSeries30(d.series); })
      .catch(() => { /* 비교 줄만 생략 */ });
    return () => { cancelled = true; };
  }, [tenant.id, windowDays]);

  const s = data ? funnelSummary(data) : null;
  const delta = windowDays === 7 && series30 ? priorPeriodDelta(series30, 7) : null;
  const now = Date.now();

  function exportFunnel() {
    if (!s) return;
    downloadCsv(
      `funnel-${tenant.slug}-${windowDays}d-${today()}.csv`,
      ['step', 'count', 'conversionFromPrev', 'dropFromPrev'],
      s.steps.map((st) => [st.label, st.value, st.convFromPrev === null ? '' : st.convFromPrev.toFixed(4), st.dropFromPrev]),
    );
  }

  return (
    <div className="stack-6">
      <div className="row" style={{ gap: 16 }}>
        <Segmented ariaLabel="기간" value={String(windowDays)} onChange={(v) => setWindowDays(Number(v) as Win)} options={WIN_OPTIONS} />
        <div className="spacer" />
        <Button icon={<Icons.Download size={14} />} onClick={exportFunnel} disabled={!s}>내보내기</Button>
      </div>

      {failed && <Alert tone="bad" title="퍼널 데이터를 불러오지 못했습니다." />}
      {!data || !s ? (
        !failed && <p className="muted" style={{ margin: 0 }}>불러오는 중…</p>
      ) : (
        <>
          <div className="kpis kpis--3">
            <KpiCard label="전체 전환율 (등록 시도 → 인증 성공)" value={fmtPct(s.overallRate)}
              hint={delta !== null ? `직전 7일 대비 ${fmtDeltaPp(delta)} (인증 성공률)` : undefined} />
            <KpiCard label="최대 이탈 단계"
              value={<span style={{ fontSize: 20 }}>{s.biggestDrop ? `${s.biggestDrop.from} → ${s.biggestDrop.to}` : '—'}</span>}
              hint={s.biggestDrop ? `이탈 ${fmtNum(s.biggestDrop.lost)}건 · ${fmtPct(s.biggestDrop.rate)}` : '이탈 없음'} />
            <KpiCard label="기간" value={<span style={{ fontSize: 20 }}>{PERIOD_LABEL[windowDays]}</span>}
              hint={`${ymd(new Date(now - windowDays * 86_400_000).toISOString())} – ${ymd(new Date(now).toISOString())}`} />
          </div>

          <Card>
            <CardHeader title="Conversion Funnel" sub={`등록 → 인증 단계별 전환 — ${PERIOD_LABEL[windowDays]}.`} />
            <CardBody>
              <div style={{ overflowX: 'auto' }}>
                <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, minWidth: 640 }}>
                  {s.steps.map((st, i) => {
                    const max = Math.max(1, ...s.steps.map((x) => x.value)); // 0/0 방지
                    return (
                      <Fragment key={st.label}>
                        {i > 0 && (
                          <div className="stack-1" style={{ alignItems: 'center', paddingBottom: 110, flex: 'none' }}>
                            <span className="tag" style={{ background: 'var(--brand-100)', color: 'var(--brand-700)' }}>{fmtPct(st.convFromPrev)}</span>
                            <span style={{ color: 'var(--faint)', display: 'inline-flex' }}><Icons.ChevronRight size={18} /></span>
                          </div>
                        )}
                        <div className="stack-2" style={{ flex: '1 1 0', alignItems: 'stretch' }}>
                          <div className="stack-1" style={{ gap: 2 }}>
                            <strong style={{ fontSize: 13 }}>{st.label}</strong>
                            <span className="hint" style={{ margin: 0 }}>{fmtNum(st.value)}건 · {fmtPct(st.pctOfFirst, 0)}</span>
                          </div>
                          <div style={{ height: 200, display: 'flex', alignItems: 'flex-end', background: 'var(--canvas)', borderRadius: 6 }}>
                            <div style={{ width: '100%', height: `${(st.value / max) * 100}%`, background: STEP_COLORS[i], borderRadius: 6 }} />
                          </div>
                          <span className="hint" style={{ margin: 0 }}>{i > 0 && st.dropFromPrev > 0 ? `이탈 ${fmtNum(st.dropFromPrev)}건` : ' '}</span>
                        </div>
                      </Fragment>
                    );
                  })}
                </div>
              </div>
            </CardBody>
          </Card>

          <div className="grid-2">
            <SeriesCard series={data.series} slug={tenant.slug} />
            <DistributionCard data={data.byEventType} slug={tenant.slug} />
          </div>
        </>
      )}
    </div>
  );
}

// ── 일별 인증 시도 vs 성공 ────────────────────────────────────────────────────

function axisLabels(series: FunnelSeries[]): string[] {
  if (series.length === 0) return [];
  if (series.length === 1) return ['오늘'];
  if (series.length === 2) return [series[0].day, '오늘'];
  return [series[0].day, series[Math.floor((series.length - 1) / 2)].day, '오늘'];
}

function Legend({ color, label }: { color: string; label: string }) {
  return <span className="row" style={{ gap: 4 }}><span aria-hidden="true" style={{ width: 10, height: 10, borderRadius: 2, background: color }} />{label}</span>;
}

function SeriesCard({ series, slug }: { series: FunnelSeries[]; slug: string }) {
  const [asTable, setAsTable] = useState(false);
  const max = Math.max(1, ...series.map((d) => d.attempts));
  const columns: Column<FunnelSeries>[] = [
    { key: 'day', header: '날짜', render: (d) => <span className="mono">{d.day}</span> },
    { key: 'a', header: '시도', align: 'right', render: (d) => fmtNum(d.attempts) },
    { key: 's', header: '성공', align: 'right', render: (d) => fmtNum(d.success) },
    { key: 'r', header: '성공률', align: 'right', render: (d) => fmtPct(d.attempts > 0 ? d.success / d.attempts : null) },
  ];
  return (
    <Card>
      <CardHeader title="일별 인증 시도 vs 성공"
        actions={!asTable && <span className="row hint" style={{ gap: 10, margin: 0 }}><Legend color="var(--brand-300)" label="시도" /><Legend color="var(--brand-700)" label="성공" /></span>} />
      <CardBody className="stack-3">
        {asTable ? (
          <DataTable columns={columns} rows={series} rowKey={(d) => d.day} />
        ) : (
          <>
            <div role="img" aria-label={`일별 인증 시도와 성공 막대 차트, ${series.length}일`}
              style={{ height: 200, display: 'flex', alignItems: 'flex-end', gap: series.length > 14 ? 4 : 14, borderBottom: '1px solid var(--line)', padding: '0 4px' }}>
              {series.map((d) => (
                <div key={d.day} title={`${d.day} · 시도 ${d.attempts} · 성공 ${d.success}`} style={{ flex: '1 1 0', display: 'flex', alignItems: 'flex-end', gap: 3, height: '100%' }}>
                  <span style={{ flex: '1 1 0', height: `${(d.attempts / max) * 100}%`, background: 'var(--brand-300)', borderRadius: '3px 3px 0 0' }} />
                  <span style={{ flex: '1 1 0', height: `${(d.success / max) * 100}%`, background: 'var(--brand-700)', borderRadius: '3px 3px 0 0' }} />
                </div>
              ))}
            </div>
            <div className="row hint" style={{ justifyContent: 'space-between', padding: '0 4px', margin: 0 }}>
              {axisLabels(series).map((l, i) => <span key={`${l}-${i}`}>{l}</span>)}
            </div>
          </>
        )}
        <div className="row" style={{ gap: 8 }}>
          <Button variant="ghost" size="sm" aria-pressed={asTable} onClick={() => setAsTable((v) => !v)}>표로 보기</Button>
          <Button variant="ghost" size="sm" onClick={() => downloadCsv(`funnel-series-${slug}-${today()}.csv`, ['day', 'attempts', 'success'], series.map((d) => [d.day, d.attempts, d.success]))}>CSV</Button>
        </div>
      </CardBody>
    </Card>
  );
}

// ── 이벤트 타입별 분포 ────────────────────────────────────────────────────────

function DistributionCard({ data, slug }: { data: FunnelByEventType[]; slug: string }) {
  const [asTable, setAsTable] = useState(false);
  const total = data.reduce((a, b) => a + b.n, 0);
  const safeTotal = total > 0 ? total : 1; // 0/0 방지
  let k = 0;
  const colorOf = data.map((d) => (/FAIL/.test(d.type) ? 'var(--bad)' : DIST_COLORS[k++ % DIST_COLORS.length]));
  const columns: Column<FunnelByEventType>[] = [
    { key: 'type', header: '이벤트 타입', render: (d) => <span className="mono">{d.type}</span> },
    { key: 'n', header: '건수', align: 'right', render: (d) => fmtNum(d.n) },
    { key: 'p', header: '비율', align: 'right', render: (d) => fmtPct(total > 0 ? d.n / total : null) },
  ];
  return (
    <Card>
      <CardHeader title="이벤트 타입별 분포" />
      <CardBody className="stack-4">
        {asTable ? (
          <DataTable columns={columns} rows={data} rowKey={(d) => d.type} />
        ) : (
          <>
            <div role="img" aria-label={`이벤트 타입 분포, 총 ${fmtNum(total)}건`} className="row" style={{ height: 28, borderRadius: 6, overflow: 'hidden', flexWrap: 'nowrap', gap: 0, background: 'var(--canvas)' }}>
              {data.map((d, i) => <span key={d.type} title={`${d.type}: ${d.n}`} style={{ flex: `${d.n / safeTotal} 1 0`, height: '100%', background: colorOf[i] }} />)}
            </div>
            {data.length === 0 ? (
              <p className="hint" style={{ margin: 0 }}>이벤트가 없습니다.</p>
            ) : (
              <div className="stack-3" style={{ fontSize: 13 }}>
                {data.map((d, i) => (
                  <span key={d.type} className="row" style={{ gap: 8, flexWrap: 'nowrap' }}>
                    <span aria-hidden="true" style={{ width: 10, height: 10, borderRadius: 2, background: colorOf[i], flex: 'none' }} />
                    <span className="mono truncate" style={{ fontSize: 12 }}>{d.type}</span>
                    <span className="spacer" />
                    <strong>{fmtNum(d.n)}</strong>
                  </span>
                ))}
              </div>
            )}
          </>
        )}
        <div className="row" style={{ gap: 8 }}>
          <Button variant="ghost" size="sm" aria-pressed={asTable} onClick={() => setAsTable((v) => !v)}>표로 보기</Button>
          <Button variant="ghost" size="sm" onClick={() => downloadCsv(`funnel-events-${slug}-${today()}.csv`, ['type', 'n'], data.map((d) => [d.type, d.n]))}>CSV</Button>
        </div>
      </CardBody>
    </Card>
  );
}
```

주의(색): 캔버스의 단계 막대 첫 색 `#A9D3F0` 과 차트 "시도" 색은 토큰에 없다. Global Constraints(토큰만 사용)에 따라 가장 가까운 `--brand-300` 으로 그린다. 차트 색은 `--brand-500` 을 포함해 브랜드 계열 토큰만 쓴다(텍스트에는 쓰지 않음).

- [ ] **Step 8: 통과 확인**

Run: `npx vitest run src/pages/tenant/FunnelTab.test.tsx src/pages/tenant/funnelHelpers.test.ts`
Expected: PASS — 4 + 7.

- [ ] **Step 9: 전체 테스트 · 린트 · 빌드**

Run: `npm test && npm run lint && npm run build`
Expected: 모두 성공. (`TenantOverview.tsx` 의 `funnelApi.get(tenant.id, 7)` 사용처는 변경 없음.)

- [ ] **Step 10: 수동 확인**

`npm run dev` → 퍼널 탭. 7d 에서 네트워크 요청 2개(`windowDays=7`, `windowDays=30`), 24h·30d 에서는 1개인지 확인. 데이터가 모두 0 인 tenant 에서 `—`·`이탈 없음` 이 나오고 NaN 이 없는지 확인.

- [ ] **Step 11: 커밋**

```bash
git add src/pages/tenant/funnelHelpers.ts src/pages/tenant/funnelHelpers.test.ts src/pages/tenant/FunnelTab.tsx src/pages/tenant/FunnelTab.test.tsx
git commit -m "feat(admin-ui): V2 퍼널 탭 — 요약 KPI(전체 전환율·최대 이탈·직전 7일 대비)·단계 전환율·표로 보기·CSV

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 27: 설정 헤더 · 내 계정 · MFA 3단계 다이얼로그 · 복구 코드

V2 아트보드: `v2/SettingsHead.dc.html`, `v2/Settings-Account.dc.html`, `v2/Dialogs-Settings.dc.html`(MFA 3단계 · 복구 코드 · MFA 끄기).

**Files:**
- Modify: `admin-ui/src/api/types.ts` (`Me` 에 §6 ④ optional 필드 2개)
- Rewrite: `admin-ui/src/pages/SettingsPage.tsx`
- Rewrite: `admin-ui/src/pages/settings/AccountTab.tsx`
- Rewrite: `admin-ui/src/pages/settings/RecoveryCodesModal.tsx`
- Modify: `admin-ui/src/pages/settings/AdminUsersTab.tsx` (prop `onPendingCountChange` 만 추가 — 전체 재작성은 Task 28)
- Rewrite: `admin-ui/src/pages/SettingsPage.test.tsx`
- Rewrite: `admin-ui/src/pages/settings/RecoveryCodesModal.test.tsx`
- Create: `admin-ui/src/pages/settings/AccountTab.test.tsx`

**Interfaces:**
- Consumes: `PageHeader`, `Tabs`/`TabItem`(Task 10, `count` 는 0 초과일 때만 `.tabs__count`), `Badge`, `Button`, `Dialog`(Task 8: `wide='x'`, `dismissible`, `role`, `ariaLabel`), `OtpInput`(Task 7), `SecretBox`(Task 9, `copyLabel`), `Stepper`/`StepState`(Task 9), `useToast`/`ToastHost`(Task 8, `@/ui`), `illustrations.mfa`·`illustrations.recoveryCodes`(Task 4), 기존 `QrCode`(`@/shell/QrCode`), `mfaApi.enroll/confirm/disable`, `getMe`, `signupRequestsApi.list`, `isPlatform`.
- Produces:
  - `Me.mfaEnrolledAt?: string`, `Me.recoveryCodesRemaining?: number` (스펙 §6 ④) — 값이 `undefined`(또는 `null`)면 해당 줄을 렌더하지 않는다.
  - `SettingsPage` 탭 상태를 `?tab=` 검색 파라미터로 보관(`account` 는 파라미터 없음). RP_ADMIN 은 `?tab=` 값과 무관하게 항상 `account`.
  - `AdminUsersTab({ onPendingCountChange?: (n: number) => void })` — 목록을 다시 읽을 때마다 대기 건수를 알린다.
  - `RecoveryCodesModal({ codes: string[]; accountEmail?: string; onClose: () => void })` — 시그니처 유지, `dismissible={false}` + `role="alertdialog"`.

설계 결정:
- 탭을 `?tab=` 로 옮기는 이유: Task 29 의 보안 정책 탭이 `useUnsavedGuard` 를 쓰므로, 탭 전환(검색 파라미터만 바뀌는 이동)도 이탈 확인 대상이 된다(Review Focus 1). 사이드바에서 `/settings` 로 들어오면 기존처럼 `내 계정` 이 열린다.
- MFA 3단계: 다이얼로그 하나에서 1·2단계(QR 스캔 + 코드 확인)를 함께 보여 주고, 확인 성공 시 다이얼로그를 닫고 3단계인 복구 코드 다이얼로그(`RecoveryCodesModal`)를 연다(아트보드와 동일한 2장 구성). 단계 표시: 코드가 비어 있으면 `QR 스캔`=now, 한 자리라도 입력되면 `QR 스캔`=done·`코드 확인`=now, `복구 코드 저장` 은 복구 코드 다이얼로그로 넘어가기 전까지 todo.
- 기존 흐름 유지: `mfaApi.enroll()` → `mfaApi.confirm(code)` → `setEnroll(null)` → `onMeChange(await getMe())` → 성공 토스트 → 복구 코드가 있으면 모달. 실패 시 코드 초기화 + `ApiError` 면 "코드가 올바르지 않습니다." 토스트. 끄기는 `mfaApi.disable(code)`(6자리 입력 필수) 그대로.
- 끄기 다이얼로그 코드 입력은 아트보드대로 단일 입력칸(기존 `sanitizeCode`) — 6칸 `OtpInput` 은 켜기 다이얼로그에만 쓴다.
- 일러스트 크기는 아트보드 값(내 계정 96px, 복구 코드 72px)을 따른다(아트보드가 시각 정본).

- [ ] **Step 1: `Me` 에 optional 필드 추가**

`admin-ui/src/api/types.ts` 의 `Me` 를 다음으로 바꾼다(기존 6개 필드는 그대로):

```ts
export interface Me {
  email: string;
  role: 'PLATFORM_OPERATOR' | 'RP_ADMIN';
  /** /me 응답의 tenantIds (복수). RP_ADMIN은 ≥1개, PLATFORM_OPERATOR는 빈 배열. */
  tenantIds: string[];
  mfaEnabled: boolean;
  mfaRequired: boolean;
  sessionIdleTimeoutMinutes: number;
  /** 스펙 §6 ④ — MFA 등록 시각(ISO). 서버가 내려주기 전까지 undefined → 내 계정 "등록일" 줄을 렌더하지 않는다. */
  mfaEnrolledAt?: string;
  /** 스펙 §6 ④ — 남은 복구 코드 수(최대 10). undefined → "남은 복구 코드" 줄을 렌더하지 않는다. */
  recoveryCodesRemaining?: number;
}
```

- [ ] **Step 2: 실패하는 테스트 작성 — SettingsPage**

```tsx
// admin-ui/src/pages/SettingsPage.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { Me } from '@/api/types';

vi.mock('./settings/AdminUsersTab', () => ({ default: () => <div>ADMIN USERS TAB</div> }));
vi.mock('./settings/MdsStatusTab', () => ({ default: () => <div>MDS TAB</div> }));
vi.mock('./settings/SystemInfoTab', () => ({ default: () => <div>SYSTEM TAB</div> }));
vi.mock('./settings/SecurityPolicyTab', () => ({ default: () => <div>SECURITY TAB</div> }));
vi.mock('./settings/AccountTab', () => ({ default: () => <div>ACCOUNT TAB</div> }));
vi.mock('@/api/signupRequests', () => ({ signupRequestsApi: { list: vi.fn() } }));

import { signupRequestsApi } from '@/api/signupRequests';
import SettingsPage from './SettingsPage';

const platform: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const rp: Me = { email: 'b@x.com', role: 'RP_ADMIN', tenantIds: ['tid-123'], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };

function renderAt(me: Me, path = '/settings') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SettingsPage me={me} onMeChange={() => {}} />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  (signupRequestsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue([
    { id: 'r1', email: 'k@x.com', reason: null, requestedAt: new Date().toISOString() },
    { id: 'r2', email: 'l@x.com', reason: null, requestedAt: new Date().toISOString() },
  ]);
});

describe('SettingsPage V2', () => {
  it('페이지 루트는 .page, 제목과 설명', () => {
    const { container } = renderAt(platform);
    expect(container.firstChild).toHaveClass('page');
    expect(screen.getByRole('heading', { level: 1, name: '설정' })).toBeInTheDocument();
    expect(screen.getByText('콘솔 운영자, MDS 신뢰 anchor, 시스템 상태.')).toBeInTheDocument();
  });

  it('PLATFORM_OPERATOR sees all tabs', () => {
    renderAt(platform);
    const tabs = screen.getByRole('tablist', { name: '설정 탭' });
    expect(tabs).toBeInTheDocument();
    for (const name of [/내 계정/, /Admin 사용자/, /MDS Status/, /시스템/, /보안 정책/]) {
      expect(screen.getByRole('tab', { name })).toBeInTheDocument();
    }
    expect(screen.getByRole('tab', { name: /내 계정/ })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText('ACCOUNT TAB')).toBeInTheDocument();
  });

  it('RP_ADMIN sees only account tab and does not fetch signup requests', () => {
    renderAt(rp);
    expect(screen.getByRole('tab', { name: /내 계정/ })).toBeInTheDocument();
    expect(screen.queryByText('Admin 사용자')).not.toBeInTheDocument();
    expect(screen.queryByText('MDS Status')).not.toBeInTheDocument();
    expect(screen.queryByText('시스템')).not.toBeInTheDocument();
    expect(screen.queryByText('보안 정책')).not.toBeInTheDocument();
    expect(signupRequestsApi.list).not.toHaveBeenCalled();
  });

  it('Admin 사용자 탭에 대기 중 가입 요청 건수 배지(PLATFORM)', async () => {
    renderAt(platform);
    const count = await screen.findByText('2');
    expect(count).toHaveClass('tabs__count');
    expect(screen.getByRole('tab', { name: /Admin 사용자/ })).toContainElement(count);
  });

  it('탭 클릭으로 내용이 바뀐다', () => {
    renderAt(platform);
    fireEvent.click(screen.getByRole('tab', { name: /시스템/ }));
    expect(screen.getByText('SYSTEM TAB')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /시스템/ })).toHaveAttribute('aria-selected', 'true');
  });

  it('?tab= 으로 진입하면 해당 탭, RP_ADMIN 은 무시하고 내 계정', () => {
    const { unmount } = renderAt(platform, '/settings?tab=security');
    expect(screen.getByText('SECURITY TAB')).toBeInTheDocument();
    unmount();
    renderAt(rp, '/settings?tab=system');
    expect(screen.getByText('ACCOUNT TAB')).toBeInTheDocument();
    expect(screen.queryByText('SYSTEM TAB')).toBeNull();
  });
});
```

- [ ] **Step 3: 실패하는 테스트 작성 — RecoveryCodesModal**

기존 5개 테스트의 의도(공용 `copyToClipboard` 경유, 실패 안내, 전체 코드 표시, 체크 전 닫기 비활성, 다운로드 파일명)를 유지하고 V2 동작 3개를 추가한다.

```tsx
// admin-ui/src/pages/settings/RecoveryCodesModal.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { RecoveryCodesModal } from './RecoveryCodesModal';
import { copyToClipboard } from '@/lib/clipboard';

// Copy must route through the shared copyToClipboard util (which has the
// execCommand fallback for insecure HTTP contexts). Calling
// navigator.clipboard directly silently no-ops on non-secure origins, which
// was the original "복사가 안 된다" bug.
vi.mock('@/lib/clipboard', () => ({
  copyToClipboard: vi.fn().mockResolvedValue(true),
}));

const CODES = ['3f9a-2b71', '8c4d-9e02', 'a17b-44ff', '6620-d3a9', 'e591-7c08',
               '1bd4-aa3e', '9038-5f6c', '72e1-0b8d', 'c4a6-118f', '5d20-93b7'];
const TITLE = '복구 코드 — 지금만 표시됩니다';

describe('RecoveryCodesModal', () => {
  beforeEach(() => {
    vi.mocked(copyToClipboard).mockClear();
    vi.mocked(copyToClipboard).mockResolvedValue(true);
  });

  it('copy button routes through copyToClipboard with all codes joined by newline', async () => {
    render(<RecoveryCodesModal codes={CODES} onClose={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: /복사/ }));
    await waitFor(() => expect(copyToClipboard).toHaveBeenCalledWith(CODES.join('\n')));
  });

  it('shows a manual-copy hint when copyToClipboard fails', async () => {
    vi.mocked(copyToClipboard).mockResolvedValue(false);
    render(<RecoveryCodesModal codes={CODES} onClose={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: /복사/ }));
    await waitFor(() => expect(screen.getByText(/복사에 실패했습니다/)).toBeInTheDocument());
  });

  it('renders all codes, numbered 01–10', () => {
    render(<RecoveryCodesModal codes={CODES} onClose={() => {}} />);
    for (const c of CODES) expect(screen.getByText(c)).toBeInTheDocument();
    expect(screen.getByText('01')).toBeInTheDocument();
    expect(screen.getByText('10')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: '복구 코드 목록' }).children).toHaveLength(10);
  });

  it('close button is disabled until saved-checkbox is checked', () => {
    const onClose = vi.fn();
    render(<RecoveryCodesModal codes={CODES} onClose={onClose} />);
    const closeBtn = screen.getByRole('button', { name: /닫기|체크/ });
    expect(closeBtn).toBeDisabled();
    expect(closeBtn).toHaveTextContent('닫기 · 체크 필요');
    fireEvent.click(screen.getByRole('checkbox', { name: '안전한 곳에 저장했습니다.' }));
    expect(closeBtn).not.toBeDisabled();
    fireEvent.click(closeBtn);
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('alertdialog 이고 Escape·스크림 클릭으로 닫히지 않는다 (체크 후에도)', () => {
    const onClose = vi.fn();
    render(<RecoveryCodesModal codes={CODES} onClose={onClose} />);
    expect(screen.getByRole('alertdialog', { name: TITLE })).toHaveAttribute('aria-modal', 'true');
    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.mouseDown(document.querySelector('.scrim')!);
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.mouseDown(document.querySelector('.scrim')!);
    expect(onClose).not.toHaveBeenCalled();
  });

  it('복구 코드 일러스트는 장식(alt="")', () => {
    render(<RecoveryCodesModal codes={CODES} onClose={() => {}} />);
    const img = screen.getByRole('alertdialog').querySelector('img');
    expect(img).toHaveAttribute('alt', '');
  });

  it('download filename includes sanitized account from accountEmail', () => {
    const clickedNames: string[] = [];
    const origCreate = document.createElement.bind(document);
    const spy = vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = origCreate(tag) as HTMLElement;
      if (tag === 'a') {
        // capture the download name at click time
        (el as HTMLAnchorElement).click = () => { clickedNames.push((el as HTMLAnchorElement).download); };
      }
      return el;
    });
    // jsdom lacks createObjectURL/revokeObjectURL
    (URL as unknown as { createObjectURL: () => string }).createObjectURL = () => 'blob:x';
    (URL as unknown as { revokeObjectURL: () => void }).revokeObjectURL = () => {};

    render(<RecoveryCodesModal codes={CODES} accountEmail="Alice@corp.com" onClose={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: /다운로드/ }));

    expect(clickedNames).toHaveLength(1);
    expect(clickedNames[0]).toMatch(/^passkey-admin-recovery-codes-alice-\d{8}\.txt$/);
    spy.mockRestore();
  });
});
```

- [ ] **Step 4: 실패하는 테스트 작성 — AccountTab**

```tsx
// admin-ui/src/pages/settings/AccountTab.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within, cleanup } from '@testing-library/react';
import { useState } from 'react';
import type { Me } from '@/api/types';
import { ToastHost } from '@/ui';

vi.mock('@/api/mfa', () => ({ mfaApi: { enroll: vi.fn(), confirm: vi.fn(), disable: vi.fn(), verify: vi.fn() } }));
vi.mock('@/api/client', () => ({ getMe: vi.fn() }));
// jsdom 에는 canvas 가 없다 — QR 렌더는 값만 확인한다.
vi.mock('@/shell/QrCode', () => ({ QrCode: ({ value }: { value: string }) => <div data-testid="qr">{value}</div> }));
vi.mock('@/lib/clipboard', () => ({ copyToClipboard: vi.fn().mockResolvedValue(true) }));

import { mfaApi } from '@/api/mfa';
import { getMe } from '@/api/client';
import AccountTab from './AccountTab';

const off: Me = { email: 'a@x.com', role: 'RP_ADMIN', tenantIds: ['t1'], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const on: Me = { ...off, mfaEnabled: true };
const CODES = ['AAAA-BBBB', 'CCCC-DDDD', 'EEEE-FFFF', 'GGGG-HHHH', 'JJJJ-KKKK', 'LLLL-MMMM', 'NNNN-PPPP', 'QQQQ-RRRR', 'SSSS-TTTT', 'UUUU-VVVV'];

const m = <T,>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>;

function Host({ initial }: { initial: Me }) {
  const [me, setMe] = useState(initial);
  return <ToastHost><AccountTab me={me} onMeChange={setMe} /></ToastHost>;
}

beforeEach(() => {
  vi.clearAllMocks();
  m(mfaApi.enroll).mockResolvedValue({ secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/Passkey:a@x.com?secret=JBSWY3DPEHPK3PXP' });
  m(mfaApi.confirm).mockResolvedValue({ confirmed: true, recoveryCodes: CODES });
  m(mfaApi.disable).mockResolvedValue({ disabled: true });
  m(getMe).mockResolvedValue(on);
});
afterEach(cleanup);

describe('AccountTab — MFA 꺼짐', () => {
  it('일러스트(장식) + 2단계 인증 켜기 카드', () => {
    const { container } = render(<Host initial={off} />);
    expect(screen.getByText('꺼짐')).toHaveClass('badge');
    expect(screen.getByText('계정을 한 단계 더 보호하세요')).toBeInTheDocument();
    expect(container.querySelector('img')).toHaveAttribute('alt', '');
    expect(screen.getByRole('button', { name: '2단계 인증 켜기' })).toBeInTheDocument();
  });

  it('stepper progression: QR 스캔 → 코드 확인 → 복구 코드 다이얼로그', async () => {
    render(<Host initial={off} />);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 켜기' }));
    const dlg = await screen.findByRole('dialog', { name: '2단계 인증 켜기' });

    // 1단계: QR + 수동 키
    expect(within(dlg).getByTestId('qr')).toHaveTextContent('otpauth://totp/');
    expect(within(dlg).getByText('JBSWY3DPEHPK3PXP')).toBeInTheDocument();
    expect(within(dlg).getByText('QR 스캔').closest('li')).toHaveAttribute('aria-current', 'step');
    expect(within(dlg).getByText('코드 확인').closest('li')).toHaveClass('stepper__item--todo');
    expect(within(dlg).getByText('복구 코드 저장').closest('li')).toHaveClass('stepper__item--todo');
    const confirmBtn = within(dlg).getByRole('button', { name: '확인 → 복구 코드' });
    expect(confirmBtn).toBeDisabled();

    // 2단계: 코드 입력 시작
    const first = within(dlg).getByRole('textbox', { name: '인증 코드 1번째 자리' });
    fireEvent.change(first, { target: { value: '1' } });
    expect(within(dlg).getByText('QR 스캔').closest('li')).toHaveClass('stepper__item--done');
    expect(within(dlg).getByText('코드 확인').closest('li')).toHaveAttribute('aria-current', 'step');

    fireEvent.paste(first, { clipboardData: { getData: () => '123456' } });
    expect(confirmBtn).not.toBeDisabled();
    fireEvent.click(confirmBtn);

    // 3단계: 복구 코드(alertdialog), 켜기 다이얼로그는 닫힘
    await waitFor(() => expect(mfaApi.confirm).toHaveBeenCalledWith('123456'));
    expect(await screen.findByRole('alertdialog', { name: '복구 코드 — 지금만 표시됩니다' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: '2단계 인증 켜기' })).toBeNull();
    expect(getMe).toHaveBeenCalled();
    expect(screen.getByText('켜짐')).toBeInTheDocument();
  });

  it('잘못된 코드면 코드가 지워지고 다이얼로그는 유지', async () => {
    const { ApiError } = await import('@/api/types');
    m(mfaApi.confirm).mockRejectedValue(new ApiError(401, 'invalid_code', 'invalid_code'));
    render(<Host initial={off} />);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 켜기' }));
    const dlg = await screen.findByRole('dialog', { name: '2단계 인증 켜기' });
    fireEvent.paste(within(dlg).getByRole('textbox', { name: '인증 코드 1번째 자리' }), { clipboardData: { getData: () => '000000' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '확인 → 복구 코드' }));
    expect(await screen.findByText('코드가 올바르지 않습니다.')).toBeInTheDocument();
    expect(within(dlg).getByRole('textbox', { name: '인증 코드 1번째 자리' })).toHaveValue('');
    expect(screen.getByRole('dialog', { name: '2단계 인증 켜기' })).toBeInTheDocument();
  });

  it('취소하면 다이얼로그가 닫히고 confirm 을 부르지 않는다', async () => {
    render(<Host initial={off} />);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 켜기' }));
    const dlg = await screen.findByRole('dialog', { name: '2단계 인증 켜기' });
    fireEvent.click(within(dlg).getByRole('button', { name: '취소' }));
    expect(screen.queryByRole('dialog', { name: '2단계 인증 켜기' })).toBeNull();
    expect(mfaApi.confirm).not.toHaveBeenCalled();
  });
});

describe('AccountTab — MFA 켜짐', () => {
  it('optional rows hidden when undefined (§6 ④)', () => {
    render(<Host initial={on} />);
    expect(screen.getByText('켜짐')).toHaveClass('badge--success');
    expect(screen.queryByText('등록일')).toBeNull();
    expect(screen.queryByText('남은 복구 코드')).toBeNull();
    expect(screen.getByText('위험 영역')).toBeInTheDocument();
  });

  it('optional rows shown when defined', () => {
    render(<Host initial={{ ...on, mfaEnrolledAt: '2026-09-01T10:00:00Z', recoveryCodesRemaining: 7 }} />);
    expect(screen.getByText('등록일')).toBeInTheDocument();
    expect(screen.getByText('2026-09-01')).toBeInTheDocument();
    expect(screen.getByText('남은 복구 코드')).toBeInTheDocument();
    expect(screen.getByText('7 / 10')).toBeInTheDocument();
  });

  it('2단계 인증 끄기: alertdialog, 6자리 입력 후 disable(code)', async () => {
    render(<Host initial={on} />);
    m(getMe).mockResolvedValue(off);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 끄기' }));
    const dlg = screen.getByRole('alertdialog', { name: '2단계 인증 끄기' });
    const offBtn = within(dlg).getByRole('button', { name: '끄기' });
    expect(offBtn).toBeDisabled();
    fireEvent.change(within(dlg).getByLabelText('인증 코드'), { target: { value: '12 34a56' } });
    expect(within(dlg).getByLabelText('인증 코드')).toHaveValue('123456');
    fireEvent.click(offBtn);
    await waitFor(() => expect(mfaApi.disable).toHaveBeenCalledWith('123456'));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    expect(await screen.findByText('꺼짐')).toBeInTheDocument();
  });
});
```

- [ ] **Step 5: 실패 확인**

Run: `npx vitest run src/pages/SettingsPage.test.tsx src/pages/settings/RecoveryCodesModal.test.tsx src/pages/settings/AccountTab.test.tsx`
Expected: FAIL — `tablist "설정 탭"` 없음, `alertdialog` 없음(현재 `role="dialog"`), `01` 번호 없음, `2단계 인증 켜기` 다이얼로그 없음(현재 인라인 폼), `위험 영역` 없음.

- [ ] **Step 6: RecoveryCodesModal V2 구현**

```tsx
// admin-ui/src/pages/settings/RecoveryCodesModal.tsx
import { useState } from 'react';
import { Icons } from '@/icons/Icons';
import { illustrations } from '@/assets/illustrations';
import { recoveryCodesFilename } from '@/lib/recoveryCodeFilename';
import { copyToClipboard } from '@/lib/clipboard';
import { Button, Dialog } from '@/ui';

const TITLE = '복구 코드 — 지금만 표시됩니다';

/**
 * MFA 복구 코드 1회 표시 모달(MFA 켜기 3단계). confirm 직후 응답의 recoveryCodes 를 보여준다.
 * "저장했습니다" 체크 전에는 닫기 불가, ESC·바깥 클릭으로도 닫히지 않는다(dismissible=false).
 * 닫으면 영구히 다시 못 본다.
 */
export function RecoveryCodesModal({
  codes,
  accountEmail,
  onClose,
}: {
  codes: string[];
  accountEmail?: string;
  onClose: () => void;
}) {
  const [checked, setChecked] = useState(false);
  const [copied, setCopied] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);
  const text = codes.join('\n');

  async function copy() {
    const ok = await copyToClipboard(text);
    setCopyFailed(!ok);
    if (ok) {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  }
  function download() {
    const blob = new Blob([text + '\n'], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = recoveryCodesFilename(accountEmail, new Date());
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <Dialog
      open
      onClose={() => { /* 저장 확인 전후 모두 ESC·스크림으로 닫지 않는다 — 닫기 버튼만 */ }}
      dismissible={false}
      role="alertdialog"
      ariaLabel={TITLE}
      footer={
        <Button variant={checked ? 'primary' : 'secondary'} disabled={!checked} onClick={onClose}>
          {checked ? '닫기' : '닫기 · 체크 필요'}
        </Button>
      }
    >
      <div className="row" style={{ gap: 14, flexWrap: 'nowrap', marginTop: 4 }}>
        <img src={illustrations.recoveryCodes} alt="" style={{ width: 72, height: 72, objectFit: 'contain', flex: 'none' }} />
        <div className="stack-1">
          <h3 className="dialog__title">{TITLE}</h3>
          <p className="dialog__sub" style={{ margin: 0 }}>
            authenticator 기기를 잃었을 때 각 코드를 한 번씩 로그인에 쓸 수 있습니다.
          </p>
        </div>
      </div>

      <ol
        className="mono"
        aria-label="복구 코드 목록"
        style={{
          listStyle: 'none', margin: 0,
          display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: '10px 20px',
          border: '1px dashed var(--brand-300)', background: 'var(--brand-50)', borderRadius: 'var(--r)',
          padding: '16px 18px', fontSize: 14,
        }}
      >
        {codes.map((c, i) => (
          <li key={c}>
            <span className="hint" style={{ display: 'inline-block', width: 22, margin: 0 }}>{String(i + 1).padStart(2, '0')}</span>
            <span>{c}</span>
          </li>
        ))}
      </ol>

      <div className="row">
        <Button onClick={() => void copy()} icon={copied ? <Icons.Check size={14} /> : <Icons.Copy size={14} />}>
          {copied ? '복사됨' : '복사'}
        </Button>
        <Button onClick={download} icon={<Icons.Download size={14} />}>다운로드 (.txt)</Button>
        <span className="hint" style={{ margin: 0 }}>복사 실패 시: 직접 선택하거나 다운로드</span>
      </div>
      {copyFailed && (
        <p className="field__error" role="alert" style={{ margin: 0 }}>
          복사에 실패했습니다. 위 코드를 직접 선택해 복사하거나 다운로드하세요.
        </p>
      )}

      <label
        className="row"
        style={{ gap: 10, padding: '12px 14px', border: '1px solid var(--brand-700)', borderRadius: 'var(--r)', background: 'var(--brand-50)', cursor: 'pointer' }}
      >
        <input
          type="checkbox"
          checked={checked}
          onChange={(e) => setChecked(e.target.checked)}
          style={{ width: 16, height: 16, margin: 0, accentColor: 'var(--brand-700)' }}
        />
        <strong style={{ fontSize: 13 }}>안전한 곳에 저장했습니다.</strong>
      </label>
    </Dialog>
  );
}
```

- [ ] **Step 7: AccountTab V2 구현**

```tsx
// admin-ui/src/pages/settings/AccountTab.tsx
import { useState } from 'react';
import { mfaApi, type EnrollResponse } from '@/api/mfa';
import { getMe } from '@/api/client';
import { ApiError, type Me } from '@/api/types';
import { QrCode } from '@/shell/QrCode';
import { illustrations } from '@/assets/illustrations';
import { Badge, Button, Dialog, OtpInput, SecretBox, Stepper, useToast, type StepState } from '@/ui';
import { RecoveryCodesModal } from './RecoveryCodesModal';

function sanitizeCode(v: string): string {
  return v.replace(/\D/g, '').slice(0, 6);
}

/** 켜기 다이얼로그(1·2단계)의 진행 표시. 3단계(복구 코드)는 별도 alertdialog 로 넘어간다. */
function enrollSteps(code: string): { label: string; description: string; state: StepState }[] {
  const scanned = code.length > 0;
  return [
    { label: 'QR 스캔', description: '앱에 계정 추가', state: scanned ? 'done' : 'now' },
    { label: '코드 확인', description: '6자리 입력', state: scanned ? 'now' : 'todo' },
    { label: '복구 코드 저장', description: '10개 · 1회 표시', state: 'todo' },
  ];
}

const DL_STYLE = { display: 'grid', gridTemplateColumns: '140px minmax(0, 1fr)', gap: '12px 16px', fontSize: 13, margin: 0 } as const;
const DT_STYLE = { color: 'var(--mute)' } as const;
const DD_STYLE = { margin: 0, minWidth: 0 } as const;

export default function AccountTab({ me, onMeChange }: { me: Me; onMeChange: (m: Me) => void }) {
  const toast = useToast();

  // enroll flow state
  const [enroll, setEnroll] = useState<EnrollResponse | null>(null);
  const [enrollCode, setEnrollCode] = useState('');
  const [enrolling, setEnrolling] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);

  // disable flow state
  const [disableOpen, setDisableOpen] = useState(false);
  const [disableCode, setDisableCode] = useState('');
  const [disabling, setDisabling] = useState(false);

  async function startEnroll() {
    setEnrolling(true);
    try {
      const res = await mfaApi.enroll();
      setEnroll(res);
      setEnrollCode('');
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '2단계 인증 시작 실패', message: msg });
    } finally {
      setEnrolling(false);
    }
  }

  function cancelEnroll() {
    setEnroll(null);
    setEnrollCode('');
  }

  async function confirmEnroll() {
    setConfirming(true);
    try {
      const res = await mfaApi.confirm(enrollCode);
      setEnroll(null);
      setEnrollCode('');
      onMeChange(await getMe());
      toast({ kind: 'ok', title: '2단계 인증이 켜졌습니다.' });
      if (res.recoveryCodes && res.recoveryCodes.length > 0) {
        setRecoveryCodes(res.recoveryCodes);
      }
    } catch (e: unknown) {
      setEnrollCode('');
      if (e instanceof ApiError) {
        toast({ kind: 'err', title: '코드가 올바르지 않습니다.' });
      } else {
        const msg = e instanceof Error ? e.message : String(e);
        toast({ kind: 'err', title: '2단계 인증 확인 실패', message: msg });
      }
    } finally {
      setConfirming(false);
    }
  }

  function openDisable() {
    setDisableCode('');
    setDisableOpen(true);
  }

  async function confirmDisable() {
    setDisabling(true);
    try {
      await mfaApi.disable(disableCode);
      setDisableOpen(false);
      setDisableCode('');
      onMeChange(await getMe());
      toast({ kind: 'warn', title: '2단계 인증이 꺼졌습니다.' });
    } catch (e: unknown) {
      setDisableCode('');
      if (e instanceof ApiError) {
        toast({ kind: 'err', title: '코드가 올바르지 않습니다.' });
      } else {
        const msg = e instanceof Error ? e.message : String(e);
        toast({ kind: 'err', title: '2단계 인증 끄기 실패', message: msg });
      }
    } finally {
      setDisabling(false);
    }
  }

  // §6 ④ — 서버가 내려줄 때만 표시(null/undefined 면 줄 자체를 렌더하지 않음)
  const enrolledAt = me.mfaEnrolledAt != null ? me.mfaEnrolledAt.slice(0, 10) : null;
  const remaining = me.recoveryCodesRemaining != null ? me.recoveryCodesRemaining : null;
  const hasInfoRows = enrolledAt !== null || remaining !== null;

  return (
    <div className="stack-4">
      <section className="card">
        <div className="set">
          <div className="stack-1" style={{ gap: 6 }}>
            <div className="row" style={{ gap: 8 }}>
              <h2 className="card__title">2단계 인증 (MFA)</h2>
              {me.mfaEnabled ? <Badge tone="ok">켜짐</Badge> : <Badge>꺼짐</Badge>}
            </div>
            <p className="card__sub">
              {me.mfaEnabled
                ? '로그인 시 authenticator 코드가 필요합니다.'
                : '로그인 시 비밀번호에 더해 authenticator 앱의 6자리 코드를 요구합니다.'}
            </p>
          </div>

          {!me.mfaEnabled && (
            <div
              className="row"
              style={{ gap: 20, flexWrap: 'nowrap', alignItems: 'center', padding: 16, border: '1px dashed var(--brand-300)', borderRadius: 'var(--r)', background: 'var(--brand-50)' }}
            >
              <img src={illustrations.mfa} alt="" style={{ width: 96, height: 96, objectFit: 'contain', flex: 'none' }} />
              <div className="stack-2" style={{ flex: '1 1 0', minWidth: 0 }}>
                <strong>계정을 한 단계 더 보호하세요</strong>
                <span className="card__sub">3단계로 끝납니다 — 앱으로 QR 스캔 → 코드 확인 → 복구 코드 저장.</span>
                <div className="row">
                  <Button variant="primary" onClick={() => void startEnroll()} disabled={enrolling}>2단계 인증 켜기</Button>
                </div>
              </div>
            </div>
          )}

          {me.mfaEnabled && (
            <div className="stack-3" style={{ gap: 14 }}>
              {hasInfoRows && (
                <dl style={DL_STYLE}>
                  {enrolledAt !== null && (<><dt style={DT_STYLE}>등록일</dt><dd style={DD_STYLE}>{enrolledAt}</dd></>)}
                  {remaining !== null && (<><dt style={DT_STYLE}>남은 복구 코드</dt><dd style={DD_STYLE}>{remaining} / 10</dd></>)}
                </dl>
              )}
              <div style={hasInfoRows ? { borderTop: '1px solid var(--line-soft)', paddingTop: 14 } : undefined}>
                <span className="t-over" style={{ color: 'var(--bad)' }}>위험 영역</span>
                <div className="row" style={{ gap: 12, marginTop: 6 }}>
                  <span className="card__sub" style={{ flex: '1 1 220px' }}>
                    끄면 비밀번호만으로 로그인됩니다. 현재 authenticator 코드가 필요합니다.
                  </span>
                  <Button variant="danger" onClick={openDisable} disabled={disabling}>2단계 인증 끄기</Button>
                </div>
              </div>
            </div>
          )}
        </div>
      </section>

      {/* ── 켜기: 1–2 / 3 (QR 스캔 + 코드 확인) ─────────────────────────── */}
      <Dialog open={enroll !== null} onClose={() => { if (!confirming) cancelEnroll(); }} wide="x" ariaLabel="2단계 인증 켜기">
        {enroll && (
          <div style={{ display: 'grid', gridTemplateColumns: '220px minmax(0, 1fr)', margin: '-16px -24px -20px' }}>
            <div style={{ background: 'var(--canvas)', borderRight: '1px solid var(--line-soft)', padding: '24px 18px' }}>
              <Stepper title="2단계 인증 켜기" steps={enrollSteps(enrollCode)} />
            </div>
            <div className="col" style={{ minWidth: 0 }}>
              <div className="dialog__head">
                <span className="hint" style={{ margin: 0 }}>1–2 / 3</span>
                <h3 className="dialog__title">앱으로 스캔하고 코드 확인</h3>
              </div>
              <div className="dialog__body">
                <div style={{ display: 'grid', gridTemplateColumns: '180px minmax(0, 1fr)', gap: 20, alignItems: 'start' }}>
                  <div className="stack-2" style={{ alignItems: 'center' }}>
                    <div style={{ border: '1px solid var(--line)', borderRadius: 'var(--r)', lineHeight: 0 }}>
                      <QrCode value={enroll.otpauthUri} size={152} />
                    </div>
                    <span className="hint" style={{ margin: 0 }}>QR 코드</span>
                  </div>
                  <div className="stack-2" style={{ gap: 10, minWidth: 0 }}>
                    <span className="label" style={{ margin: 0 }}>또는 수동 입력 키</span>
                    <SecretBox value={enroll.secret} copyLabel="복사" />
                    <p className="hint" style={{ margin: 0 }}>Google Authenticator · 1Password 등 TOTP 앱에 입력하세요.</p>
                  </div>
                </div>
                <div style={{ borderTop: '1px solid var(--line-soft)', paddingTop: 16 }}>
                  <span className="label">앱에 표시된 인증 코드</span>
                  <div style={{ display: 'inline-flex' }}>
                    <OtpInput value={enrollCode} onChange={setEnrollCode} ariaLabel="인증 코드" disabled={confirming} />
                  </div>
                </div>
              </div>
              <div className="dialog__foot">
                <Button onClick={cancelEnroll} disabled={confirming}>취소</Button>
                <Button variant="primary" onClick={() => void confirmEnroll()} disabled={enrollCode.length !== 6 || confirming}>
                  확인 → 복구 코드
                </Button>
              </div>
            </div>
          </div>
        )}
      </Dialog>

      {/* ── 끄기 ─────────────────────────────────────────────────────────── */}
      <Dialog
        open={disableOpen}
        onClose={() => setDisableOpen(false)}
        role="alertdialog"
        title="2단계 인증 끄기"
        sub="본인 확인을 위해 현재 authenticator 코드를 입력하세요."
        footer={
          <>
            <Button onClick={() => setDisableOpen(false)} disabled={disabling}>취소</Button>
            <Button variant="danger-solid" onClick={() => void confirmDisable()} disabled={disableCode.length !== 6 || disabling}>끄기</Button>
          </>
        }
      >
        <div>
          <label className="label" htmlFor="mfa-disable-code">인증 코드</label>
          <input
            id="mfa-disable-code"
            className="input mono"
            inputMode="numeric"
            autoComplete="one-time-code"
            placeholder="000000"
            value={disableCode}
            onChange={(e) => setDisableCode(sanitizeCode(e.target.value))}
            style={{ maxWidth: 200, letterSpacing: '0.2em' }}
          />
        </div>
      </Dialog>

      {/* ── 3 / 3: 복구 코드 ─────────────────────────────────────────────── */}
      {recoveryCodes && (
        <RecoveryCodesModal codes={recoveryCodes} accountEmail={me.email} onClose={() => setRecoveryCodes(null)} />
      )}
    </div>
  );
}
```

- [ ] **Step 8: AdminUsersTab 에 대기 건수 콜백 추가 (최소 변경)**

`admin-ui/src/pages/settings/AdminUsersTab.tsx` 에서 두 곳만 바꾼다(전체 V2 재작성은 Task 28).

```tsx
// 변경 전
export default function AdminUsersTab() {
// 변경 후
export default function AdminUsersTab({ onPendingCountChange }: { onPendingCountChange?: (n: number) => void } = {}) {
```

```tsx
// reload() 안 — 변경 전
      setUsers(list);
      setRequests(pending);
// 변경 후
      setUsers(list);
      setRequests(pending);
      onPendingCountChange?.(pending.length);
```

- [ ] **Step 9: SettingsPage V2 구현**

```tsx
// admin-ui/src/pages/SettingsPage.tsx
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import AccountTab from './settings/AccountTab';
import AdminUsersTab from './settings/AdminUsersTab';
import MdsStatusTab from './settings/MdsStatusTab';
import SystemInfoTab from './settings/SystemInfoTab';
import SecurityPolicyTab from './settings/SecurityPolicyTab';
import { signupRequestsApi } from '@/api/signupRequests';
import type { Me } from '@/api/types';
import { isPlatform } from '@/me/roles';
import { PageHeader, Tabs, type TabItem } from '@/ui';

type SettingsTab = 'account' | 'admins' | 'mds' | 'system' | 'security';
const PLATFORM_TABS: readonly string[] = ['admins', 'mds', 'system', 'security'];

/** ?tab= 값을 탭으로. PLATFORM 전용 탭은 PLATFORM 일 때만 허용, 그 외는 항상 '내 계정'. */
function resolveTab(raw: string | null, platform: boolean): SettingsTab {
  return platform && raw !== null && PLATFORM_TABS.includes(raw) ? (raw as SettingsTab) : 'account';
}

export default function SettingsPage({ me, onMeChange }: { me: Me; onMeChange: (m: Me) => void }) {
  const platform = isPlatform(me);
  const [params, setParams] = useSearchParams();
  const tab = resolveTab(params.get('tab'), platform);
  const [pending, setPending] = useState<number | undefined>(undefined);

  // Admin 사용자 탭 배지 = 대기 중 가입 요청 수 (PLATFORM 만 — RP 는 엔드포인트 권한 없음)
  useEffect(() => {
    if (!platform) return;
    let cancelled = false;
    signupRequestsApi.list()
      .then((rows) => { if (!cancelled) setPending(rows.length); })
      .catch(() => { /* 배지는 보조 정보 — 실패 시 숨김 */ });
    return () => { cancelled = true; };
  }, [platform]);

  const items: TabItem[] = [
    { id: 'account', label: '내 계정' },
    ...(platform
      ? [
          { id: 'admins', label: 'Admin 사용자', count: pending },
          { id: 'mds', label: 'MDS Status' },
          { id: 'system', label: '시스템' },
          { id: 'security', label: '보안 정책' },
        ]
      : []),
  ];

  function onTabChange(id: string) {
    const next = new URLSearchParams(params);
    if (id === 'account') next.delete('tab');
    else next.set('tab', id);
    setParams(next);
  }

  return (
    <div className="page">
      <div className="stack-4">
        <PageHeader title="설정" sub="콘솔 운영자, MDS 신뢰 anchor, 시스템 상태." />
        <Tabs ariaLabel="설정 탭" items={items} active={tab} onChange={onTabChange} />
      </div>

      {tab === 'account' && <AccountTab me={me} onMeChange={onMeChange} />}
      {platform && tab === 'admins' && <AdminUsersTab onPendingCountChange={setPending} />}
      {platform && tab === 'mds' && <MdsStatusTab />}
      {platform && tab === 'system' && <SystemInfoTab />}
      {platform && tab === 'security' && <SecurityPolicyTab />}
    </div>
  );
}
```

- [ ] **Step 10: 테스트·린트·빌드**

Run: `npx vitest run src/pages/SettingsPage.test.tsx src/pages/settings && npm test && npm run lint && npm run build`
Expected: 대상 3개 파일 PASS(SettingsPage 6, RecoveryCodesModal 7, AccountTab 7), 전체 PASS, lint 오류 0(경고 허용), 빌드 성공.

- [ ] **Step 11: 수동 확인**

`npm run dev` → PLATFORM 데모 계정으로 `/admin/settings`: 제목·설명·탭 5개, 대기 가입 요청이 있으면 `Admin 사용자` 옆 주황 건수 배지. 내 계정에서 `2단계 인증 켜기` → 왼쪽 단계 표시가 있는 넓은 다이얼로그, 인증 앱으로 QR 스캔 후 코드 입력 → 복구 코드 다이얼로그(ESC·바깥 클릭 무반응, 체크 후 `닫기`). RP_ADMIN 계정은 탭이 `내 계정` 하나.

- [ ] **Step 12: 커밋**

```bash
git add src/api/types.ts src/pages/SettingsPage.tsx src/pages/SettingsPage.test.tsx src/pages/settings/AccountTab.tsx src/pages/settings/AccountTab.test.tsx src/pages/settings/RecoveryCodesModal.tsx src/pages/settings/RecoveryCodesModal.test.tsx src/pages/settings/AdminUsersTab.tsx
git commit -m "feat(admin-ui): V2 설정 헤더·내 계정 — MFA 3단계 다이얼로그, 복구 코드 dismissible=false

- 설정 탭을 ?tab= 로 보관, Admin 사용자 탭에 대기 가입 요청 건수
- Me.mfaEnrolledAt?/recoveryCodesRemaining? (스펙 §6 ④) 있을 때만 표시

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 28: Admin 사용자 — 가입 요청 대기 · 운영자 필터 · 승인/거절 다이얼로그

V2 아트보드: `v2/Settings-AdminUsers.dc.html`, `v2/Dialogs-Settings.dc.html`(가입 요청 승인 · 거절).

**Files:**
- Rewrite: `admin-ui/src/pages/settings/AdminUsersTab.tsx`
- Rewrite: `admin-ui/src/pages/settings/AdminUsersTab.test.tsx`

**Interfaces:**
- Consumes: `Badge`, `Button`, `ChoiceCard`, `DataTable`/`Column`, `Dialog`(`role='alertdialog'`), `EmptyState`, `Segmented`, `StatusBadge`(SUSPENDED→bad '정지', ACTIVE→ok '활성', PENDING→warn '대기'), `useToast`/`ToastHost`, `illustrations.noResults`, `adminUsersApi.list/suspend/activate`, `signupRequestsApi.list/approve/reject`, `tenantsApi.list`.
- Produces: `AdminUsersTab({ onPendingCountChange?: (n: number) => void })` — Task 27 에서 추가한 prop 유지.

동작 규칙(기존 유지 + V2):
- 로드: `Promise.all([adminUsersApi.list(), signupRequestsApi.list()])`, 실패 토스트 `Admin 사용자 로드 실패`.
- 승인: `signupRequestsApi.approve(id, { role, tenantIds })` — RP_ADMIN 은 선택 tenant(≥1), PLATFORM_OPERATOR 는 `[]`. 409 → "이미 처리된 요청입니다." 경고 + 다이얼로그 닫고 재로드, 그 외 실패 → 다이얼로그 유지·재로드 안 함.
- 거절: `signupRequestsApi.reject(id)` — 확인 다이얼로그(alertdialog, `거절 확정` danger-solid) 후에만 호출. 409 처리 동일.
- 정지/활성화: `adminUsersApi.suspend(id)` / `adminUsersApi.activate(id)` 후 재로드.
- 승인 다이얼로그 tenant 목록은 `tenantsApi.list()` 중 `ACTIVE` 만, 기본 선택은 첫 번째. RP_ADMIN 에서 0개로 제출하면 `Tenant를 최소 1개 선택해야 합니다.` 오류를 표시하고 API 를 부르지 않는다(체크 해제로 0개가 되어도 즉시 표시). 기존에는 버튼이 비활성이라 오류 문구가 실제로 보이지 않았다 — V2 아트보드의 오류 상태를 보이도록 바꾼다.
- 운영자 필터(클라이언트): `전체` / `PLATFORM`(role=PLATFORM_OPERATOR) / `RP_ADMIN`(role=RP_ADMIN) / `정지`(status=SUSPENDED) + 검색(이메일·ID 부분 일치, 대소문자 무시). 결과 0건이면 표 안에 검색 결과 없음 상태.
- 운영자 이름 필드는 API(`AdminUserView`)에 없으므로 아트보드의 `[이름]` 자리에 이메일, 보조 줄에 ID 끝 10자를 쓴다. 검색 placeholder 도 실제 검색 대상에 맞춰 `이메일 · ID`.
- 기존 표 마지막의 동작 없는 `⋯` 버튼은 아트보드에 없으므로 제거한다(기능 없음).

- [ ] **Step 1: 실패하는 테스트 작성**

```tsx
// admin-ui/src/pages/settings/AdminUsersTab.test.tsx
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup, within } from '@testing-library/react';
import { ToastHost } from '@/ui';

vi.mock('@/api/adminUsers', () => ({
  adminUsersApi: {
    list: vi.fn().mockResolvedValue([]),
    suspend: vi.fn(), activate: vi.fn(), addTenant: vi.fn(), removeTenant: vi.fn(),
  },
  adminFetch: vi.fn(),
}));
vi.mock('@/api/signupRequests', () => ({
  signupRequestsApi: {
    list: vi.fn(),
    approve: vi.fn().mockResolvedValue({}),
    reject: vi.fn().mockResolvedValue(undefined),
  },
}));
vi.mock('@/api/tenants', () => ({
  tenantsApi: {
    list: vi.fn().mockResolvedValue([
      { id: 't1', name: 'Acme', slug: 'acme', status: 'ACTIVE' },
      { id: 't2', name: 'Globex', slug: 'globex', status: 'ACTIVE' },
      { id: 't3', name: 'Old Corp', slug: 'old', status: 'SUSPENDED' },
    ]),
  },
}));

import { adminUsersApi } from '@/api/adminUsers';
import { signupRequestsApi } from '@/api/signupRequests';
import { ApiError } from '@/api/types';
import AdminUsersTab from './AdminUsersTab';

const mock = (fn: unknown) => fn as ReturnType<typeof vi.fn>;
const pendingOne = [{ id: 'r1', email: 'new@x.com', reason: 'RP 담당', requestedAt: new Date().toISOString() }];
const base = { createdAt: '2026-01-01T00:00:00Z', suspendedAt: null, createdBy: null };
const USERS = [
  { ...base, id: 'u-platform-0001', email: 'ops@crosscert.com', role: 'PLATFORM_OPERATOR', status: 'ACTIVE', tenantIds: [], lastLoginAt: new Date().toISOString(), mfaEnabled: true },
  { ...base, id: 'u-rp-000000002', email: 'rp@acme.com', role: 'RP_ADMIN', status: 'ACTIVE', tenantIds: ['t1'], lastLoginAt: null, mfaEnabled: false },
  { ...base, id: 'u-rp-000000003', email: 'gone@globex.com', role: 'RP_ADMIN', status: 'SUSPENDED', tenantIds: ['t2', 't3'], lastLoginAt: null, mfaEnabled: true },
];

beforeEach(() => {
  vi.clearAllMocks();
  mock(adminUsersApi.list).mockResolvedValue([]);
  mock(adminUsersApi.suspend).mockResolvedValue(undefined);
  mock(adminUsersApi.activate).mockResolvedValue(undefined);
  mock(signupRequestsApi.list).mockResolvedValue(pendingOne);
  mock(signupRequestsApi.approve).mockResolvedValue({});
  mock(signupRequestsApi.reject).mockResolvedValue(undefined);
});
afterEach(cleanup);

function renderTab(props: { onPendingCountChange?: (n: number) => void } = {}) {
  return render(<ToastHost><AdminUsersTab {...props} /></ToastHost>);
}
const approveDialog = () => screen.getByRole('dialog', { name: '가입 요청 승인' });

describe('AdminUsersTab signup requests', () => {
  it('renders pending requests with count badge, email and reason', async () => {
    renderTab();
    await waitFor(() => expect(screen.getByText('new@x.com')).toBeInTheDocument());
    expect(screen.getByText('RP 담당')).toBeInTheDocument();
    expect(screen.getByText('대기 1건')).toHaveClass('badge--warning');
    expect(screen.getByRole('button', { name: '거절' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '승인…' })).toBeInTheDocument();
  });

  it('reason 이 없으면 "요청 사유 없음"', async () => {
    mock(signupRequestsApi.list).mockResolvedValue([{ ...pendingOne[0], reason: null }]);
    renderTab();
    expect(await screen.findByText('요청 사유 없음')).toBeInTheDocument();
  });

  it('shows empty notice when no request is pending', async () => {
    mock(signupRequestsApi.list).mockResolvedValue([]);
    renderTab();
    await waitFor(() => expect(screen.getByText('대기 중인 요청이 없습니다')).toBeInTheDocument());
    expect(screen.getByText('대기 0건')).not.toHaveClass('badge--warning');
  });

  it('대기 건수를 상위(SettingsPage 탭 배지)에 알린다', async () => {
    const onPendingCountChange = vi.fn();
    renderTab({ onPendingCountChange });
    await waitFor(() => expect(onPendingCountChange).toHaveBeenCalledWith(1));
  });

  it('approve opens a dialog showing the requester and submits role + tenants', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인…' }));
    const dlg = approveDialog();
    expect(within(dlg).getByText('new@x.com')).toBeInTheDocument();
    expect(within(dlg).getByRole('radio', { name: /RP Admin/ })).toHaveAttribute('aria-checked', 'true');
    await waitFor(() => within(dlg).getByText('Acme'));   // tenant 목록 로드(ACTIVE 만)
    expect(within(dlg).queryByText('Old Corp')).toBeNull();
    expect(within(dlg).getByRole('checkbox', { name: /Acme/ })).toBeChecked();
    expect(within(dlg).getByText('1개 선택')).toBeInTheDocument();
    fireEvent.click(within(dlg).getByRole('button', { name: '승인하고 계정 생성' }));
    await waitFor(() =>
      expect(signupRequestsApi.approve).toHaveBeenCalledWith('r1', { role: 'RP_ADMIN', tenantIds: ['t1'] }),
    );
  });

  it('RP Admin 으로 tenant 0개면 오류를 보이고 API 를 부르지 않는다', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인…' }));
    const dlg = approveDialog();
    await waitFor(() => within(dlg).getByText('Acme'));
    fireEvent.click(within(dlg).getByRole('checkbox', { name: /Acme/ }));
    expect(within(dlg).getByText('Tenant를 최소 1개 선택해야 합니다.')).toBeInTheDocument();
    fireEvent.click(within(dlg).getByRole('button', { name: '승인하고 계정 생성' }));
    expect(signupRequestsApi.approve).not.toHaveBeenCalled();
  });

  it('tenant 검색으로 목록을 좁힌다', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인…' }));
    const dlg = approveDialog();
    await waitFor(() => within(dlg).getByText('Acme'));
    fireEvent.change(within(dlg).getByRole('textbox', { name: 'tenant 검색' }), { target: { value: 'glob' } });
    expect(within(dlg).queryByText('Acme')).toBeNull();
    expect(within(dlg).getByText('Globex')).toBeInTheDocument();
    fireEvent.change(within(dlg).getByRole('textbox', { name: 'tenant 검색' }), { target: { value: 'zzz' } });
    expect(within(dlg).getByText('일치하는 tenant 없음')).toBeInTheDocument();
  });

  it('Platform Operator 를 고르면 tenant 없이 승인', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인…' }));
    const dlg = approveDialog();
    fireEvent.click(within(dlg).getByRole('radio', { name: /Platform Operator/ }));
    expect(within(dlg).queryByRole('textbox', { name: 'tenant 검색' })).toBeNull();
    fireEvent.click(within(dlg).getByRole('button', { name: '승인하고 계정 생성' }));
    await waitFor(() =>
      expect(signupRequestsApi.approve).toHaveBeenCalledWith('r1', { role: 'PLATFORM_OPERATOR', tenantIds: [] }),
    );
  });

  it('reject asks for in-app confirmation (alertdialog) before calling the API', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '거절' }));
    expect(signupRequestsApi.reject).not.toHaveBeenCalled();
    const dlg = screen.getByRole('alertdialog', { name: '가입 요청 거절' });
    expect(within(dlg).getByText(/new@x.com 의 요청을 거절합니다/)).toBeInTheDocument();
    const confirm = within(dlg).getByRole('button', { name: '거절 확정' });
    expect(confirm).toHaveClass('btn--danger-solid');
    fireEvent.click(confirm);
    await waitFor(() => expect(signupRequestsApi.reject).toHaveBeenCalledWith('r1'));
  });

  it('no invite button remains', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    expect(screen.queryByText(/운영자 추가/)).not.toBeInTheDocument();
    expect(screen.queryByText(/초대/)).not.toBeInTheDocument();
  });

  it('approve failure other than 409 keeps the dialog open and does not reload', async () => {
    mock(signupRequestsApi.approve).mockRejectedValue(new ApiError(500, 'C999', 'boom'));
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인…' }));
    await waitFor(() => within(approveDialog()).getByText('Acme'));
    fireEvent.click(within(approveDialog()).getByRole('button', { name: '승인하고 계정 생성' }));
    await waitFor(() => expect(signupRequestsApi.approve).toHaveBeenCalled());
    expect(approveDialog()).toBeInTheDocument();
    expect(signupRequestsApi.list).toHaveBeenCalledTimes(1);
  });

  it('approve 409 closes the dialog and reloads', async () => {
    mock(signupRequestsApi.approve).mockRejectedValue(new ApiError(409, 'C409', 'conflict'));
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인…' }));
    await waitFor(() => within(approveDialog()).getByText('Acme'));
    fireEvent.click(within(approveDialog()).getByRole('button', { name: '승인하고 계정 생성' }));
    expect(await screen.findByText('이미 처리된 요청입니다.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '가입 요청 승인' })).toBeNull());
    expect(signupRequestsApi.list).toHaveBeenCalledTimes(2);
  });

  it('reject failure other than 409 keeps the confirm dialog open', async () => {
    mock(signupRequestsApi.reject).mockRejectedValue(new ApiError(500, 'C999', 'boom'));
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '거절' }));
    fireEvent.click(screen.getByRole('button', { name: '거절 확정' }));
    await waitFor(() => expect(signupRequestsApi.reject).toHaveBeenCalledWith('r1'));
    expect(screen.getByRole('button', { name: '거절 확정' })).toBeInTheDocument();
    expect(signupRequestsApi.list).toHaveBeenCalledTimes(1);
  });
});

describe('AdminUsersTab operators', () => {
  beforeEach(() => { mock(adminUsersApi.list).mockResolvedValue(USERS); });

  const table = () => screen.getByRole('table');
  const seg = () => screen.getByRole('group', { name: '역할' });

  it('헤더 요약과 표 열', async () => {
    renderTab();
    await waitFor(() => screen.getByText('ops@crosscert.com'));
    expect(screen.getByText('3명 · 활성 2명')).toBeInTheDocument();
    for (const h of ['운영자', '역할', 'Tenant', 'MFA', '마지막 로그인', '상태', '액션']) {
      expect(within(table()).getByRole('columnheader', { name: h })).toBeInTheDocument();
    }
    const rpRow = within(table()).getByText('rp@acme.com').closest('tr')!;
    expect(within(rpRow).getByText('RP_ADMIN')).toHaveClass('badge--info');
    expect(within(rpRow).getByText('OFF')).toHaveClass('badge--warning');
    expect(within(rpRow).getByText('미접속')).toBeInTheDocument();
    const goneRow = within(table()).getByText('gone@globex.com').closest('tr')!;
    expect(within(goneRow).getByText('2개 RP')).toBeInTheDocument();
  });

  it('세그먼트 PLATFORM / RP_ADMIN / 정지 / 전체 필터', async () => {
    renderTab();
    await waitFor(() => screen.getByText('ops@crosscert.com'));
    fireEvent.click(within(seg()).getByRole('button', { name: 'PLATFORM' }));
    expect(within(seg()).getByRole('button', { name: 'PLATFORM' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(table()).getByText('ops@crosscert.com')).toBeInTheDocument();
    expect(within(table()).queryByText('rp@acme.com')).toBeNull();
    expect(within(table()).queryByText('gone@globex.com')).toBeNull();

    fireEvent.click(within(seg()).getByRole('button', { name: 'RP_ADMIN' }));
    expect(within(table()).queryByText('ops@crosscert.com')).toBeNull();
    expect(within(table()).getByText('rp@acme.com')).toBeInTheDocument();
    expect(within(table()).getByText('gone@globex.com')).toBeInTheDocument();

    fireEvent.click(within(seg()).getByRole('button', { name: '정지' }));
    expect(within(table()).getByText('gone@globex.com')).toBeInTheDocument();
    expect(within(table()).queryByText('rp@acme.com')).toBeNull();

    fireEvent.click(within(seg()).getByRole('button', { name: '전체' }));
    expect(within(table()).getAllByRole('row')).toHaveLength(1 + 3);
  });

  it('검색(이메일·ID, 대소문자 무시)과 결과 없음', async () => {
    renderTab();
    await waitFor(() => screen.getByText('ops@crosscert.com'));
    const search = screen.getByRole('textbox', { name: '운영자 검색' });
    fireEvent.change(search, { target: { value: 'ACME' } });
    expect(within(table()).getByText('rp@acme.com')).toBeInTheDocument();
    expect(within(table()).queryByText('ops@crosscert.com')).toBeNull();
    fireEvent.change(search, { target: { value: 'platform-0001' } });
    expect(within(table()).getByText('ops@crosscert.com')).toBeInTheDocument();
    fireEvent.change(search, { target: { value: 'nobody' } });
    expect(screen.getByText('조건에 맞는 운영자가 없습니다.')).toBeInTheDocument();
  });

  it('세그먼트와 검색은 함께 적용된다', async () => {
    renderTab();
    await waitFor(() => screen.getByText('ops@crosscert.com'));
    fireEvent.click(within(seg()).getByRole('button', { name: 'RP_ADMIN' }));
    fireEvent.change(screen.getByRole('textbox', { name: '운영자 검색' }), { target: { value: 'globex' } });
    expect(within(table()).getAllByRole('row')).toHaveLength(1 + 1);
    expect(within(table()).getByText('gone@globex.com')).toBeInTheDocument();
  });

  it('정지·활성화 액션', async () => {
    renderTab();
    await waitFor(() => screen.getByText('rp@acme.com'));
    const rpRow = within(table()).getByText('rp@acme.com').closest('tr')!;
    fireEvent.click(within(rpRow).getByRole('button', { name: '정지' }));
    await waitFor(() => expect(adminUsersApi.suspend).toHaveBeenCalledWith('u-rp-000000002'));
    const goneRow = within(table()).getByText('gone@globex.com').closest('tr')!;
    fireEvent.click(within(goneRow).getByRole('button', { name: '활성화' }));
    await waitFor(() => expect(adminUsersApi.activate).toHaveBeenCalledWith('u-rp-000000003'));
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledTimes(3));
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/settings/AdminUsersTab.test.tsx`
Expected: FAIL — `승인…` 버튼·`alertdialog`·`group "역할"`·`운영자 검색` 없음, `대기 1건` 이 `badge--warning` 이지만 dot 없음 등.

- [ ] **Step 3: AdminUsersTab V2 구현**

```tsx
// admin-ui/src/pages/settings/AdminUsersTab.tsx
import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { adminUsersApi, type AdminUserView } from '@/api/adminUsers';
import { signupRequestsApi, type SignupRequestView } from '@/api/signupRequests';
import { tenantsApi } from '@/api/tenants';
import type { Tenant } from '@/api/designTypes';
import { ApiError } from '@/api/types';
import { illustrations } from '@/assets/illustrations';
import {
  Badge, Button, ChoiceCard, DataTable, Dialog, EmptyState, Segmented, StatusBadge, useToast, type Column,
} from '@/ui';

// ── Local utilities ───────────────────────────────────────────────────────────

function timeAgo(iso: string | null | undefined): string {
  if (!iso) return '—';
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return '방금 전';
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d}일 전`;
  const mo = Math.floor(d / 30);
  return `${mo}개월 전`;
}

function tail(s: string, n: number): string {
  return s.slice(-n);
}

function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function initial(email: string): string {
  return email.slice(0, 1).toUpperCase();
}

type OperatorFilter = 'all' | 'PLATFORM' | 'RP_ADMIN' | 'SUSPENDED';
const FILTERS: { value: OperatorFilter; label: string }[] = [
  { value: 'all', label: '전체' },
  { value: 'PLATFORM', label: 'PLATFORM' },
  { value: 'RP_ADMIN', label: 'RP_ADMIN' },
  { value: 'SUSPENDED', label: '정지' },
];

function matchesOperator(u: AdminUserView, filter: OperatorFilter, q: string): boolean {
  if (filter === 'PLATFORM' && u.role !== 'PLATFORM_OPERATOR') return false;
  if (filter === 'RP_ADMIN' && u.role !== 'RP_ADMIN') return false;
  if (filter === 'SUSPENDED' && u.status !== 'SUSPENDED') return false;
  return q === '' || u.email.toLowerCase().includes(q) || u.id.toLowerCase().includes(q);
}

const WARN_AVATAR = { width: 34, height: 34, background: 'var(--warn-soft)', color: 'var(--warn)' } as const;
const CAPTION = { margin: 0 } as const;

// ── AdminUsersTab ─────────────────────────────────────────────────────────────

export default function AdminUsersTab({ onPendingCountChange }: { onPendingCountChange?: (n: number) => void } = {}) {
  const [users, setUsers] = useState<AdminUserView[]>([]);
  const [requests, setRequests] = useState<SignupRequestView[]>([]);
  const [loading, setLoading] = useState(true);
  const [approving, setApproving] = useState<SignupRequestView | null>(null);
  const [rejecting, setRejecting] = useState<SignupRequestView | null>(null);
  const [filter, setFilter] = useState<OperatorFilter>('all');
  const [query, setQuery] = useState('');
  const toast = useToast();

  async function reload() {
    setLoading(true);
    try {
      const [list, pending] = await Promise.all([adminUsersApi.list(), signupRequestsApi.list()]);
      setUsers(list);
      setRequests(pending);
      onPendingCountChange?.(pending.length);
    } catch (e: unknown) {
      toast({ kind: 'err', title: 'Admin 사용자 로드 실패', message: errMsg(e) });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void reload(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function handleApprove(req: SignupRequestView, body: { role: string; tenantIds: string[] }) {
    try {
      await signupRequestsApi.approve(req.id, body);
      setApproving(null);
      toast({ kind: 'ok', title: '가입 요청을 승인했습니다.', message: `${req.email} · 즉시 로그인 가능` });
      await reload();
    } catch (e: unknown) {
      if (e instanceof ApiError && e.status === 409) {
        toast({ kind: 'warn', title: '이미 처리된 요청입니다.', message: '다른 관리자가 먼저 처리했습니다.' });
        setApproving(null);
        await reload();
      } else {
        toast({ kind: 'err', title: '승인 실패', message: errMsg(e) });
      }
    }
  }

  async function handleReject(req: SignupRequestView) {
    try {
      await signupRequestsApi.reject(req.id);
      toast({ kind: 'warn', title: '가입 요청을 거절했습니다.', message: req.email });
      setRejecting(null);
      await reload();
    } catch (e: unknown) {
      if (e instanceof ApiError && e.status === 409) {
        toast({ kind: 'warn', title: '이미 처리된 요청입니다.', message: '다른 관리자가 먼저 처리했습니다.' });
        setRejecting(null);
        await reload();
      } else {
        toast({ kind: 'err', title: '거절 실패', message: errMsg(e) });
      }
    }
  }

  async function handleSuspend(u: AdminUserView) {
    try {
      await adminUsersApi.suspend(u.id);
      toast({ kind: 'warn', title: '운영자가 정지되었습니다.', message: u.email });
      await reload();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '정지 실패', message: errMsg(e) });
    }
  }

  async function handleActivate(u: AdminUserView) {
    try {
      await adminUsersApi.activate(u.id);
      toast({ kind: 'ok', title: '운영자가 재활성화되었습니다.', message: u.email });
      await reload();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '활성화 실패', message: errMsg(e) });
    }
  }

  if (loading && users.length === 0 && requests.length === 0) {
    return (
      <section className="card">
        <div className="card__body muted" style={{ fontSize: 13 }}>Loading…</div>
      </section>
    );
  }

  const activeCount = users.filter((u) => u.status === 'ACTIVE').length;
  const q = query.trim().toLowerCase();
  const visible = users.filter((u) => matchesOperator(u, filter, q));

  const columns: Column<AdminUserView>[] = [
    {
      key: 'who', header: '운영자',
      render: (u) => (
        <div className="row" style={{ gap: 10, flexWrap: 'nowrap' }}>
          <span className="avatar" style={{ width: 30, height: 30, fontSize: 12 }} aria-hidden="true">{initial(u.email)}</span>
          <div className="col" style={{ minWidth: 0 }}>
            <strong style={{ fontSize: 13 }}>{u.email}</strong>
            <span className="hint mono" style={CAPTION}>…{tail(u.id, 10)}</span>
          </div>
        </div>
      ),
    },
    {
      key: 'role', header: '역할',
      render: (u) => (
        <Badge tone={u.role === 'PLATFORM_OPERATOR' ? 'plat' : 'info'} dot={false}>
          {u.role === 'PLATFORM_OPERATOR' ? 'PLATFORM' : 'RP_ADMIN'}
        </Badge>
      ),
    },
    {
      key: 'tenant', header: 'Tenant',
      render: (u) => {
        const ids = u.tenantIds ?? [];
        if (ids.length === 0) return <span className="faint">—</span>;
        if (ids.length === 1) return <span className="mono" style={{ fontSize: 12 }} title={ids[0]}>…{tail(ids[0], 10)}</span>;
        return <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{ids.length}개 RP</span>;
      },
    },
    { key: 'mfa', header: 'MFA', render: (u) => <Badge tone={u.mfaEnabled ? 'ok' : 'warn'}>{u.mfaEnabled ? 'ON' : 'OFF'}</Badge> },
    {
      key: 'last', header: '마지막 로그인',
      render: (u) => <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{u.lastLoginAt ? timeAgo(u.lastLoginAt) : '미접속'}</span>,
    },
    { key: 'status', header: '상태', render: (u) => <StatusBadge status={u.status} /> },
    {
      key: 'act', header: '액션',
      render: (u) =>
        u.status === 'ACTIVE' ? <Button size="sm" variant="danger" onClick={() => void handleSuspend(u)}>정지</Button>
        : u.status === 'SUSPENDED' ? <Button size="sm" onClick={() => void handleActivate(u)}>활성화</Button>
        : null,
    },
  ];

  return (
    <div className="stack-6">
      {/* ── 가입 요청 ── */}
      {requests.length === 0 ? (
        <section className="card" aria-label="가입 요청">
          <div className="card__head" style={{ borderBottom: 0 }}>
            <h2 className="card__title">가입 요청</h2>
            <Badge>대기 0건</Badge>
            <div className="spacer" />
            <span className="hint" style={CAPTION}>대기 중인 요청이 없습니다</span>
          </div>
        </section>
      ) : (
        <section className="card" aria-label="가입 요청" style={{ borderColor: 'var(--warn-line)' }}>
          <div className="card__head" style={{ background: 'var(--warn-soft)', borderRadius: 'var(--r) var(--r) 0 0' }}>
            <div className="col" style={{ gap: 2 }}>
              <div className="row" style={{ gap: 8 }}>
                <h2 className="card__title">가입 요청</h2>
                <Badge tone="warn">대기 {requests.length}건</Badge>
              </div>
              <p className="card__sub">로그인 화면에서 들어온 관리자 계정 요청. 승인 시 역할과 tenant 를 지정합니다.</p>
            </div>
          </div>
          <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
            {requests.map((r, i) => (
              <li key={r.id} className="row" style={{ gap: 14, padding: '14px 20px', borderBottom: i < requests.length - 1 ? '1px solid var(--line-soft)' : 0 }}>
                <span className="avatar" style={WARN_AVATAR} aria-hidden="true">{initial(r.email)}</span>
                <div className="col" style={{ flex: '1 1 260px', minWidth: 0 }}>
                  <strong style={{ fontSize: 13 }}>{r.email}</strong>
                  <span className="truncate" style={{ fontSize: 13, color: 'var(--ink-2)' }} title={r.reason ?? undefined}>
                    {r.reason ?? '요청 사유 없음'}
                  </span>
                </div>
                <span className="hint" style={CAPTION}>{timeAgo(r.requestedAt)}</span>
                <div className="row" style={{ gap: 6 }}>
                  <Button onClick={() => setRejecting(r)}>거절</Button>
                  <Button variant="primary" onClick={() => setApproving(r)}>승인…</Button>
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* ── 콘솔 운영자 ── */}
      <section className="card">
        <div className="card__head">
          <div className="col" style={{ gap: 2 }}>
            <h2 className="card__title">콘솔 운영자</h2>
            <span className="hint" style={CAPTION}>{users.length}명 · 활성 {activeCount}명</span>
          </div>
          <div className="spacer" />
          <Segmented ariaLabel="역할" options={FILTERS} value={filter} onChange={setFilter} />
          <div className="search" style={{ flex: '0 1 220px' }}>
            <Icons.Search size={15} />
            <input className="input" placeholder="이메일 · ID" aria-label="운영자 검색" value={query} onChange={(e) => setQuery(e.target.value)} />
          </div>
        </div>
        <DataTable
          columns={columns}
          rows={visible}
          rowKey={(u) => u.id}
          empty={<EmptyState compact illustration={illustrations.noResults} title="조건에 맞는 운영자가 없습니다." />}
        />
      </section>

      {approving && (
        <ApproveDialog
          request={approving}
          onClose={() => setApproving(null)}
          onApprove={(body) => handleApprove(approving, body)}
        />
      )}

      {rejecting && (
        <Dialog
          open
          onClose={() => setRejecting(null)}
          role="alertdialog"
          title="가입 요청 거절"
          footer={
            <>
              <Button onClick={() => setRejecting(null)}>취소</Button>
              <Button variant="danger-solid" onClick={() => void handleReject(rejecting)}>거절 확정</Button>
            </>
          }
        >
          <p className="card__sub">{rejecting.email} 의 요청을 거절합니다. 요청자는 다시 가입 요청을 보낼 수 있습니다.</p>
        </Dialog>
      )}
    </div>
  );
}

// ── ApproveDialog ─────────────────────────────────────────────────────────────

function ApproveDialog({
  request,
  onClose,
  onApprove,
}: {
  request: SignupRequestView;
  onClose: () => void;
  onApprove: (body: { role: string; tenantIds: string[] }) => Promise<void>;
}) {
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [role, setRole] = useState<'PLATFORM_OPERATOR' | 'RP_ADMIN'>('RP_ADMIN');
  const [tenantIds, setTenantIds] = useState<string[]>([]);
  const [tenantQuery, setTenantQuery] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    tenantsApi
      .list()
      .then((list) => {
        const active = list.filter((t) => t.status === 'ACTIVE');
        setTenants(active);
        if (active.length > 0) setTenantIds([active[0].id]);
      })
      .catch(() => { /* non-critical */ });
  }, []);

  function toggleTenant(id: string) {
    setTouched(true);
    setTenantIds((prev) => (prev.includes(id) ? prev.filter((t) => t !== id) : [...prev, id]));
  }

  const formValid = role === 'PLATFORM_OPERATOR' || tenantIds.length > 0;
  const showTenantError = role === 'RP_ADMIN' && touched && tenantIds.length === 0;
  const tq = tenantQuery.trim().toLowerCase();
  const shown = tq ? tenants.filter((t) => t.name.toLowerCase().includes(tq) || t.slug.toLowerCase().includes(tq)) : tenants;

  async function submit() {
    setTouched(true);
    if (!formValid) return;
    setSubmitting(true);
    try {
      await onApprove({ role, tenantIds: role === 'RP_ADMIN' ? tenantIds : [] });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog
      open
      onClose={onClose}
      title="가입 요청 승인"
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button variant="primary" disabled={submitting} onClick={() => void submit()}>승인하고 계정 생성</Button>
        </>
      }
    >
      <div className="row" style={{ gap: 12, flexWrap: 'nowrap', padding: '12px 14px', borderRadius: 'var(--r)', background: 'var(--canvas)' }}>
        <span className="avatar" style={WARN_AVATAR} aria-hidden="true">{initial(request.email)}</span>
        <div className="col" style={{ minWidth: 0 }}>
          <strong style={{ fontSize: 13 }}>{request.email}</strong>
          <span style={{ fontSize: 13, color: 'var(--ink-2)', whiteSpace: 'pre-wrap' }}>{request.reason ?? '요청 사유 없음'}</span>
        </div>
      </div>

      <div>
        <span className="label">역할</span>
        <div className="choices choices--2" role="radiogroup" aria-label="역할">
          <ChoiceCard checked={role === 'PLATFORM_OPERATOR'} onSelect={() => setRole('PLATFORM_OPERATOR')} title="Platform Operator" description="모든 tenant 와 플랫폼 설정 관리" />
          <ChoiceCard checked={role === 'RP_ADMIN'} onSelect={() => setRole('RP_ADMIN')} title="RP Admin" description="할당된 tenant 만 관리" />
        </div>
      </div>

      {role === 'RP_ADMIN' && (
        <div>
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <span className="label">할당 Tenant</span>
            <span className="hint" style={{ margin: '0 0 6px' }}>{tenantIds.length}개 선택</span>
          </div>
          <div className="search" style={{ marginBottom: 8 }}>
            <Icons.Search size={15} />
            <input className="input" placeholder="tenant 검색" aria-label="tenant 검색" value={tenantQuery} onChange={(e) => setTenantQuery(e.target.value)} />
          </div>
          <div
            role="group"
            aria-label="할당 Tenant"
            aria-invalid={showTenantError || undefined}
            style={{ border: `1px solid ${showTenantError ? 'var(--bad)' : 'var(--line)'}`, borderRadius: 'var(--r)', maxHeight: 176, overflow: 'auto' }}
          >
            {tenants.length === 0 ? (
              <p className="hint" style={{ margin: 0, padding: '10px 14px' }}>활성 tenant 없음</p>
            ) : shown.length === 0 ? (
              <p className="hint" style={{ margin: 0, padding: '10px 14px' }}>일치하는 tenant 없음</p>
            ) : (
              shown.map((t, i) => {
                const checked = tenantIds.includes(t.id);
                return (
                  <label
                    key={t.id}
                    className="row"
                    style={{
                      gap: 10, flexWrap: 'nowrap', padding: '10px 14px', fontSize: 13, cursor: 'pointer',
                      borderBottom: i < shown.length - 1 ? '1px solid var(--line-soft)' : 0,
                      background: checked ? 'var(--brand-50)' : undefined,
                    }}
                  >
                    <input type="checkbox" checked={checked} onChange={() => toggleTenant(t.id)} style={{ width: 16, height: 16, margin: 0, accentColor: 'var(--brand-700)' }} />
                    <span className="truncate">{t.name}</span>
                    <span className="spacer" />
                    <span className="mono hint" style={{ margin: 0 }}>{t.slug}</span>
                  </label>
                );
              })
            )}
          </div>
          {showTenantError && <p className="field__error" role="alert">Tenant를 최소 1개 선택해야 합니다.</p>}
        </div>
      )}
    </Dialog>
  );
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `npx vitest run src/pages/settings/AdminUsersTab.test.tsx`
Expected: PASS (가입 요청 13 + 운영자 5 = 18 테스트).

- [ ] **Step 5: 전체 테스트·린트·빌드**

Run: `npm test && npm run lint && npm run build`
Expected: 전체 PASS, lint 오류 0, 빌드 성공.

- [ ] **Step 6: 수동 확인**

`npm run dev` → 로그아웃 상태에서 `/admin/signup` 으로 가입 요청 1건 접수 → PLATFORM 계정으로 `/admin/settings?tab=admins`: 주황 테두리 `가입 요청` 카드(`대기 1건`), `승인…` → 역할 카드·tenant 검색·체크 목록, 모두 해제 시 빨간 오류. `거절` → 빨간 `거절 확정`. 운영자 표 위 세그먼트·검색이 즉시 필터.

- [ ] **Step 7: 커밋**

```bash
git add src/pages/settings/AdminUsersTab.tsx src/pages/settings/AdminUsersTab.test.tsx
git commit -m "feat(admin-ui): V2 Admin 사용자 — 가입 요청 대기 카드, 역할 세그먼트·검색 필터, 승인/거절 다이얼로그

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 29: MDS Status · 시스템 · 보안 정책 + 셸 래퍼 제거

V2 아트보드: `v2/Settings-Mds.dc.html`, `v2/Settings-System.dc.html`, `v2/Settings-Security.dc.html`.

**Files:**
- Modify: `admin-ui/src/api/mdsStatus.ts` (`MDS_HISTORY_STRIP_LIMIT` 상수)
- Modify: `admin-ui/src/api/systemInfo.ts` (`SystemInfoComponent.latencyMs?`)
- Modify: `admin-ui/src/icons/Icons.tsx` (`Icons.Server`)
- Rewrite: `admin-ui/src/pages/settings/MdsStatusTab.tsx`, `admin-ui/src/pages/settings/SystemInfoTab.tsx`, `admin-ui/src/pages/settings/SecurityPolicyTab.tsx`
- Modify: `admin-ui/src/App.tsx` (Task 12 의 임시 `<div className="page">` 래퍼 제거)
- Modify(남아 있을 때만): `admin-ui/src/pages/TenantDetailPage.tsx`, `admin-ui/src/pages/LicensePage.tsx` (조기 return 루트에 `.page`)
- Create: `admin-ui/src/api/mdsStatus.test.ts`, `admin-ui/src/pages/settings/MdsStatusTab.test.tsx`, `admin-ui/src/pages/settings/SecurityPolicyTab.test.tsx`, `admin-ui/src/test/pageRoots.test.ts`
- Modify: `admin-ui/src/pages/settings/SystemInfoTab.test.tsx` (배지 문구가 한국어로 바뀌어 4번째 테스트의 쿼리만 교체, 클래스 단언은 유지)

**Interfaces:**
- Consumes: `KpiCard`, `Badge`/`BadgeTone`, `Button`, `CardHeader`, `CardBody`, `DataTable`/`Column`, `StatusStrip`(Task 9: `results: (boolean|null)[]`, `label`), `EmptyState`, `Switch`, `ChipInput`(Task 6: trim·빈값·중복 무시 = 기존 CORS 추가 규칙과 동일, `addedValues`), `SaveBar`(Task 11, `revertLabel`), `useUnsavedGuard`(Task 11), `renderWithDataRouter`(Task 11), `useToast`/`ToastHost`, `illustrations.loadError`.
- Produces:
  - `export const MDS_HISTORY_STRIP_LIMIT = 30` (`@/api/mdsStatus`). `mdsStatusApi.history(limit)` 는 이미 `?limit=` 를 지원한다. 서버 `MdsHistoryService.recent(limit)` 는 1–50 으로 제한하고 `ORDER BY started_at DESC`(최신 먼저) — 서버 변경 없음. 스트립은 왼쪽=오래된 것이 되도록 뒤집어 그린다.
  - `SystemInfoComponent.latencyMs?: number` (스펙 §6 ⑤) — `undefined`/`null` 이면 ms 표시 없음.
  - `Icons.Server` (컴포넌트 목록 아이콘, 1.6px 스트로크).
  - 라우트 페이지 루트 규칙: 라우팅되는 모든 화면은 자기 루트(조기 return 포함)에 `className="page"`. 설정·tenant 탭 컴포넌트는 `.page` 를 쓰지 않는다(중첩 패딩 방지).

설계 결정:
- MDS 상태 헤드라인: 최신 이력(`history[0]`)이 `FAILED` 면 `동기화 실패 · BLOB #N`(bad), `fetchedAt` 이 없으면 `동기화 이력 없음`(neutral), 그 외 `정상 · BLOB #N`(ok). 이력 상태 라벨은 `statusLabel` 과 같은 `동기화됨`/`건너뜀`/`실패`(기존은 SKIPPED 도 FAIL 로 보였음). 스트립: SYNCED=정상 막대, FAILED=빨강, SKIPPED=회색(`null`). 이력 표는 아트보드대로 최근 6행.
- 즉시 갱신 후 상태와 함께 이력도 다시 읽어 스트립을 갱신한다(GET 1회 추가, 쓰기 API 변화 없음).
- 시스템 전체 상태: 컴포넌트 중 하나라도 `DOWN` → `중단`, 아니면 하나라도 `DEGRADED` → `저하`, 모두 `OK` → `정상`. 컴포넌트 배지 라벨도 `정상`/`저하`/`중단`(클래스는 `badge--success/warning/danger` 그대로). 호스트 정보는 API 가 주는 `environment` 와 `deployedAt` 만 표시(아트보드의 hostname·JVM·OS·timezone 은 API 에 없고 §6 계약도 아니므로 그리지 않는다).
- 보안 정책 `취소`(SaveBar 의 되돌리기)는 기존처럼 서버에서 다시 읽는다(`securityPolicyApi.get()`).

- [ ] **Step 1: 실패하는 테스트 작성 — MDS**

```ts
// admin-ui/src/api/mdsStatus.test.ts
import { describe, it, expect, vi } from 'vitest';

vi.mock('./client', () => ({ api: { get: vi.fn().mockResolvedValue([]), post: vi.fn() } }));

import { api } from './client';
import { mdsStatusApi, MDS_HISTORY_STRIP_LIMIT } from './mdsStatus';

describe('mdsStatusApi.history', () => {
  it('limit 을 쿼리로 보낸다 (서버 상한 50, 최신순)', async () => {
    await mdsStatusApi.history(MDS_HISTORY_STRIP_LIMIT);
    expect(api.get).toHaveBeenCalledWith('/admin/api/mds/history?limit=30');
  });
  it('스트립 길이 상수는 30', () => {
    expect(MDS_HISTORY_STRIP_LIMIT).toBe(30);
  });
});
```

```tsx
// admin-ui/src/pages/settings/MdsStatusTab.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within, cleanup } from '@testing-library/react';
import { ToastHost } from '@/ui';
import type { MdsHistoryRow, MdsStatus } from '@/api/mdsStatus';

vi.mock('@/api/mdsStatus', () => ({
  MDS_HISTORY_STRIP_LIMIT: 30,
  mdsStatusApi: { get: vi.fn(), sync: vi.fn(), history: vi.fn() },
}));

import { mdsStatusApi } from '@/api/mdsStatus';
import MdsStatusTab from './MdsStatusTab';

const mock = (fn: unknown) => fn as ReturnType<typeof vi.fn>;
const status: MdsStatus = {
  version: 87, nextUpdate: '2026-10-12', fetchedAt: new Date(Date.now() - 2 * 3600_000).toISOString(),
  trustAnchorCount: 142, trustMode: 'MDS_STRICT_OPTIONAL', successRate30d: { ok: 29, total: 30 },
};
// 서버는 최신순(DESC). index 3 = 실패, index 5 = 건너뜀
const history: MdsHistoryRow[] = Array.from({ length: 12 }, (_, i) => ({
  id: 100 - i,
  startedAt: new Date(Date.now() - i * 86400_000).toISOString(),
  finishedAt: null,
  version: 87 - i,
  status: i === 3 ? 'FAILED' : i === 5 ? 'SKIPPED' : 'SYNCED',
  changeSummary: i === 0 ? '+3 항목' : null,
  durationMs: 400 + i,
  errorMessage: null,
}));

beforeEach(() => {
  vi.clearAllMocks();
  mock(mdsStatusApi.get).mockResolvedValue(status);
  mock(mdsStatusApi.history).mockResolvedValue(history);
  mock(mdsStatusApi.sync).mockResolvedValue({ status: 'SYNCED', version: 88, error: null });
});
afterEach(cleanup);

const renderTab = () => render(<ToastHost><MdsStatusTab /></ToastHost>);

describe('MdsStatusTab V2', () => {
  it('이력은 limit=30 으로 요청하고, 스트립 막대 수 = 이력 수', async () => {
    const { container } = renderTab();
    const strip = await screen.findByRole('img', { name: '최근 12회 동기화 결과 · 실패 1회' });
    expect(mdsStatusApi.history).toHaveBeenCalledWith(30);
    expect(strip.querySelectorAll('.strip__bar')).toHaveLength(history.length);
    expect(container.querySelectorAll('.strip__bar--bad')).toHaveLength(1);
    expect(container.querySelectorAll('.strip__bar--none')).toHaveLength(1);
    // 왼쪽이 오래된 것: 최신(index 0) 이 마지막 막대
    const bars = strip.querySelectorAll('.strip__bar');
    expect(bars[bars.length - 1 - 3]).toHaveClass('strip__bar--bad');
  });

  it('헤드라인·KPI·설정', async () => {
    renderTab();
    expect(await screen.findByText('정상 · BLOB #87')).toBeInTheDocument();
    expect(screen.getByText(/다음 갱신 2026-10-12 03:00/)).toBeInTheDocument();
    expect(screen.getByText('MDS BLOB 버전')).toBeInTheDocument();
    expect(screen.getByText('142')).toBeInTheDocument();
    expect(screen.getByText('2시간 전')).toBeInTheDocument();
    expect(screen.getByText('https://mds3.fidoalliance.org/')).toBeInTheDocument();
    expect(screen.getByText('97%')).toBeInTheDocument();
    expect(screen.getByText('MDS_STRICT_OPTIONAL')).toHaveClass('badge--info');
  });

  it('이력 표는 최근 6행, 상태 라벨 한국어', async () => {
    renderTab();
    const table = await screen.findByRole('table');
    await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(1 + 6));
    expect(within(table).getAllByText('동기화됨')[0]).toHaveClass('badge--success');
    expect(within(table).getByText('실패')).toHaveClass('badge--danger');
    expect(within(table).getByText('건너뜀')).not.toHaveClass('badge--danger');
    expect(within(table).getByText('+3 항목')).toBeInTheDocument();
    expect(within(table).getByText('400ms')).toBeInTheDocument();
  });

  it('최신 실행이 실패면 실패 헤드라인', async () => {
    mock(mdsStatusApi.history).mockResolvedValue([{ ...history[0], status: 'FAILED' }, ...history.slice(1)]);
    renderTab();
    expect(await screen.findByText('동기화 실패 · BLOB #87')).toBeInTheDocument();
  });

  it('즉시 갱신 → sync 후 상태와 이력을 다시 읽는다', async () => {
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: /즉시 갱신/ }));
    await waitFor(() => expect(mdsStatusApi.sync).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('MDS 즉시 갱신 완료')).toBeInTheDocument();
    await waitFor(() => expect(mdsStatusApi.get).toHaveBeenCalledTimes(2));
    expect(mdsStatusApi.history).toHaveBeenCalledTimes(2);
  });
});
```

- [ ] **Step 2: 실패하는 테스트 작성 — 시스템**

`SystemInfoTab.test.tsx` 의 기존 1~3번 테스트는 그대로 두고, 4번째(`maps each component status to its badge class`)를 아래로 교체하고 3개를 추가한다. 파일 전체:

```tsx
// admin-ui/src/pages/settings/SystemInfoTab.test.tsx
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, waitFor, cleanup, within, fireEvent } from '@testing-library/react';

vi.mock('@/api/systemInfo', () => ({
  systemInfoApi: { get: vi.fn() },
}));

import { systemInfoApi } from '@/api/systemInfo';
import type { SystemInfoData } from '@/api/systemInfo';
import SystemInfoTab from './SystemInfoTab';

const sample: SystemInfoData = {
  serverVersion: '1.2.3',
  deployedAt: '2026-05-31T09:00+09:00',
  uptimeDays: 4,
  host: { environment: 'prod,oracle' },
  components: [
    { name: 'admin-app', version: '1.2.3', status: 'OK', note: 'Spring Boot' },
    { name: 'MDS sync scheduler', version: '1.2.3', status: 'OK', note: '매 6시간' },
  ],
};

const mockGet = () => systemInfoApi.get as ReturnType<typeof vi.fn>;
const list = () => screen.getByRole('list', { name: '백엔드 컴포넌트' });

beforeEach(() => {
  vi.clearAllMocks();
  mockGet().mockResolvedValue(sample);
});
afterEach(cleanup);

describe('SystemInfoTab', () => {
  it('renders version, KST deploy date, environment and component notes', async () => {
    render(<SystemInfoTab />);

    // 버전은 메트릭 카드와 컴포넌트 행 양쪽에 나온다.
    await waitFor(() => expect(screen.getAllByText('1.2.3').length).toBeGreaterThan(0));
    // KST 문자열이어도 앞 10자가 날짜다.
    expect(screen.getByText('2026-05-31 배포')).toBeInTheDocument();
    expect(screen.getByText('prod,oracle')).toBeInTheDocument();
    expect(screen.getByText('매 6시간')).toBeInTheDocument();
    expect(screen.getByText('4d')).toBeInTheDocument();
  });

  it('shows a placeholder subtitle when deployedAt is null', async () => {
    mockGet().mockResolvedValue({ ...sample, deployedAt: null });
    render(<SystemInfoTab />);

    await waitFor(() => expect(screen.getByText('배포 시각 정보 없음')).toBeInTheDocument());
  });

  it('shows an error message instead of loading forever when the fetch fails', async () => {
    mockGet().mockRejectedValue(new Error('boom'));
    render(<SystemInfoTab />);

    await waitFor(() =>
      expect(screen.getByText('System Info 를 불러오지 못했습니다.')).toBeInTheDocument());
    expect(screen.queryByText('System Info 로딩 중…')).not.toBeInTheDocument();
  });

  it('maps each component status to its badge class', async () => {
    mockGet().mockResolvedValue({
      ...sample,
      components: [
        { name: 'admin-app', version: '1.2.3', status: 'OK', note: 'Spring Boot' },
        { name: 'Oracle DB', version: 'Oracle (unknown)', status: 'DEGRADED', note: 'primary' },
        { name: 'MDS sync scheduler', version: '1.2.3', status: 'DOWN', note: '매 6시간' },
      ],
    });
    render(<SystemInfoTab />);

    await waitFor(() => expect(list()).toBeInTheDocument());
    expect(within(list()).getByText('정상').className).toContain('badge--success');
    expect(within(list()).getByText('저하').className).toContain('badge--warning');
    expect(within(list()).getByText('중단').className).toContain('badge--danger');
  });

  it('전체 상태는 컴포넌트 중 가장 나쁜 상태', async () => {
    mockGet().mockResolvedValue({
      ...sample,
      components: [
        { name: 'admin-app', version: '1.2.3', status: 'OK', note: null },
        { name: 'Redis', version: '7', status: 'DEGRADED', note: null },
      ],
    });
    render(<SystemInfoTab />);
    await waitFor(() => expect(list()).toBeInTheDocument());
    const kpi = screen.getByText('전체 상태').closest('.kpi')!;
    expect(kpi).toHaveTextContent('저하');
    expect(kpi).toHaveTextContent('컴포넌트 1 / 2');
  });

  it('latency hidden when undefined (§6 ⑤), shown when defined', async () => {
    mockGet().mockResolvedValue({
      ...sample,
      components: [
        { name: 'admin-app', version: '1.2.3', status: 'OK', note: null },
        { name: 'Redis', version: '7', status: 'OK', note: null, latencyMs: 12 },
      ],
    });
    render(<SystemInfoTab />);
    await waitFor(() => expect(list()).toBeInTheDocument());
    const items = within(list()).getAllByRole('listitem');
    expect(items[0].textContent).not.toMatch(/\d+ms/);
    expect(within(items[1]).getByText('12ms')).toBeInTheDocument();
  });

  it('불러오기 실패 → 일러스트 빈 상태, 다시 시도로 재요청', async () => {
    mockGet().mockRejectedValueOnce(new Error('boom'));
    const { container } = render(<SystemInfoTab />);
    await waitFor(() => expect(screen.getByText('System Info 를 불러오지 못했습니다.')).toBeInTheDocument());
    expect(container.querySelector('img')).toHaveAttribute('alt', '');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(screen.getByText('2026-05-31 배포')).toBeInTheDocument());
    expect(systemInfoApi.get).toHaveBeenCalledTimes(2);
  });
});
```

- [ ] **Step 3: 실패하는 테스트 작성 — 보안 정책**

```tsx
// admin-ui/src/pages/settings/SecurityPolicyTab.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, fireEvent, waitFor, within, act, cleanup } from '@testing-library/react';
import { ToastHost } from '@/ui';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import type { SecurityPolicyView } from '@/api/securityPolicy';

vi.mock('@/api/securityPolicy', () => ({ securityPolicyApi: { get: vi.fn(), update: vi.fn() } }));

import { securityPolicyApi } from '@/api/securityPolicy';
import SecurityPolicyTab from './SecurityPolicyTab';

const mock = (fn: unknown) => fn as ReturnType<typeof vi.fn>;
const view: SecurityPolicyView = {
  sessionIdleTimeoutMinutes: 30, mfaRequired: true, corsAllowlist: ['https://admin.example.com'],
  updatedAt: '2026-10-01T00:00:00Z', updatedBy: null,
};
const bar = () => screen.queryByRole('region', { name: '저장되지 않은 변경' });

beforeEach(() => {
  vi.clearAllMocks();
  mock(securityPolicyApi.get).mockResolvedValue(view);
});
afterEach(cleanup);

async function setup() {
  const r = renderWithDataRouter(<ToastHost><SecurityPolicyTab /></ToastHost>, { initialPath: '/settings?tab=security' });
  await waitFor(() => expect(screen.getByLabelText('세션 idle timeout (분)')).toHaveValue(30));
  return r;
}

describe('SecurityPolicyTab V2', () => {
  it('.set 3행 + 변경 없으면 SaveBar 없음', async () => {
    await setup();
    expect(screen.getByRole('switch', { name: '모든 운영자에게 MFA 필수' })).toBeChecked();
    expect(screen.getByText('https://admin.example.com')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'origin 추가' })).toBeInTheDocument();
    expect(bar()).toBeNull();
  });

  it('dirty → SaveBar(건수·요약), 저장하면 update 후 사라짐', async () => {
    await setup();
    fireEvent.change(screen.getByLabelText('세션 idle timeout (분)'), { target: { value: '45' } });
    expect(bar()).toHaveTextContent('저장하지 않은 변경 1건');
    expect(bar()).toHaveTextContent('세션 idle timeout');

    fireEvent.click(screen.getByRole('switch', { name: '모든 운영자에게 MFA 필수' }));
    expect(bar()).toHaveTextContent('저장하지 않은 변경 2건');

    mock(securityPolicyApi.update).mockResolvedValue({ ...view, sessionIdleTimeoutMinutes: 45, mfaRequired: false });
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장' }));
    await waitFor(() => expect(securityPolicyApi.update).toHaveBeenCalledWith({
      sessionIdleTimeoutMinutes: 45, mfaRequired: false, corsAllowlist: ['https://admin.example.com'],
    }));
    await waitFor(() => expect(bar()).toBeNull());
    expect(await screen.findByText('보안 정책 저장됨')).toBeInTheDocument();
  });

  it('CORS: Enter 로 추가(새 칩 강조), 중복 무시, 취소는 서버 값으로 되돌림', async () => {
    await setup();
    const input = screen.getByRole('textbox', { name: 'origin 추가' });
    fireEvent.change(input, { target: { value: ' https://new.example.com ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getByText('https://new.example.com').closest('.chip')).toHaveClass('chip--added');
    fireEvent.change(input, { target: { value: 'https://admin.example.com' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getAllByText('https://admin.example.com')).toHaveLength(1);
    expect(bar()).toHaveTextContent('CORS Allowlist');

    fireEvent.click(within(bar()!).getByRole('button', { name: '취소' }));
    await waitFor(() => expect(securityPolicyApi.get).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(bar()).toBeNull());
    expect(screen.queryByText('https://new.example.com')).toBeNull();
  });

  it('dirty 상태에서 탭 이동(?tab=)은 이탈 확인으로 막힌다', async () => {
    const { router } = await setup();
    fireEvent.change(screen.getByLabelText('세션 idle timeout (분)'), { target: { value: '10' } });
    await act(async () => { await router.navigate('/settings?tab=system'); });
    expect(screen.getByRole('alertdialog', { name: '저장하지 않은 변경이 있습니다' })).toBeInTheDocument();
    expect(router.state.location.search).toBe('?tab=security');
  });
});
```

- [ ] **Step 4: 실패하는 테스트 작성 — 페이지 루트**

```ts
// admin-ui/src/test/pageRoots.test.ts
import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const SRC = join(__dirname, '..');
const read = (f: string) => readFileSync(join(SRC, f), 'utf8');

// App.tsx <Routes> 가 렌더하는 화면(라우트 element 의 루트 컴포넌트 파일)
const ROUTED = [
  'pages/TenantsListPage.tsx',
  'pages/TenantDetailPage.tsx',
  'pages/ActivityPage.tsx',
  'pages/AuditChainPage.tsx',
  'pages/SettingsPage.tsx',
  'pages/LicensePage.tsx',
  'me/RequirePlatform.tsx',
];
// 한 줄 return 으로 div/section/main/p 를 돌려주는데 className="page" 가 없는 경우(로딩·오류 조기 return)
const RETURN_WITHOUT_PAGE = /^\s*(if \(.*\)\s*)?return\s*<(div|section|main|p)\b(?![^>]*className="page")/m;
const NESTED_DIRS = ['pages/settings', 'pages/tenant'];

describe('routed page roots', () => {
  it('App.tsx 에 Task 12 의 임시 .page 래퍼가 없다', () => {
    expect(read('App.tsx')).not.toMatch(/<div className="page">\s*<Routes>/);
  });
  it.each(ROUTED)('%s 는 className="page" 루트를 가진다', (f) => {
    expect(read(f)).toMatch(/className="page"/);
  });
  it.each(ROUTED)('%s 의 한 줄(조기) return 도 .page 를 쓴다', (f) => {
    expect(read(f)).not.toMatch(RETURN_WITHOUT_PAGE);
  });
  it.each(NESTED_DIRS)('%s 의 탭 컴포넌트는 .page 를 중첩하지 않는다', (dir) => {
    const offenders = readdirSync(join(SRC, dir))
      .filter((n) => n.endsWith('.tsx') && !n.endsWith('.test.tsx'))
      .filter((n) => /className="page"/.test(read(`${dir}/${n}`)));
    expect(offenders).toEqual([]);
  });
});
```

- [ ] **Step 5: 실패 확인**

Run: `npx vitest run src/api/mdsStatus.test.ts src/pages/settings src/test/pageRoots.test.ts`
Expected: FAIL — `MDS_HISTORY_STRIP_LIMIT` 없음, `history` 가 5 로 호출됨, 스트립 없음, 시스템 `list "백엔드 컴포넌트"` 없음, 보안 정책 `switch`·`region` 없음, `App.tsx` 에 임시 래퍼 존재, `SystemInfoTab.tsx` 가 `.page` 중첩, (Task 14–26 이후에도 남아 있다면) `TenantDetailPage.tsx`·`LicensePage.tsx` 조기 return.

- [ ] **Step 6: API 타입·상수·아이콘**

`admin-ui/src/api/mdsStatus.ts` 의 `mdsStatusApi` 위에 추가(`history` 시그니처는 그대로):

```ts
/**
 * MDS Status 스트립에 그릴 최근 실행 수. 서버 MdsHistoryService.recent(limit) 는 1–50 으로 제한하고
 * started_at DESC(최신 먼저)로 돌려준다 — 서버 변경 없이 ?limit=30 으로 요청한다.
 */
export const MDS_HISTORY_STRIP_LIMIT = 30;
```

`admin-ui/src/api/systemInfo.ts` 의 `SystemInfoComponent` 를 다음으로:

```ts
export type SystemInfoComponent = {
  name: string;
  version: string;
  status: 'OK' | 'DOWN' | 'DEGRADED';
  note: string | null;
  /** 스펙 §6 ⑤ — 헬스 체크 응답 시간(ms). 서버가 내려주기 전까지 undefined → 표시하지 않는다. */
  latencyMs?: number;
};
```

`admin-ui/src/icons/Icons.tsx` 에서 `const Help = …`(Task 12) 아래에 추가하고 `Icons` 객체에 `Server` 를 넣는다:

```ts
const Server = makeIconR([
  { tag: 'rect', attrs: { x: 4, y: 4.5, width: 16, height: 6, rx: 1.5 } },
  { tag: 'rect', attrs: { x: 4, y: 13.5, width: 16, height: 6, rx: 1.5 } },
  { tag: 'path', attrs: { d: 'M7.5 7.5h.01M7.5 16.5h.01' } },
]);
```

- [ ] **Step 7: MdsStatusTab V2 구현**

```tsx
// admin-ui/src/pages/settings/MdsStatusTab.tsx
import { useState, useEffect, ReactNode } from 'react';
import { Icons } from '@/icons/Icons';
import { mdsStatusApi, MDS_HISTORY_STRIP_LIMIT, type MdsStatus, type MdsHistoryRow } from '@/api/mdsStatus';
import {
  Badge, Button, CardBody, CardHeader, DataTable, KpiCard, StatusStrip, useToast, type BadgeTone, type Column,
} from '@/ui';

// ── Helpers ───────────────────────────────────────────────────────────────────

function timeAgo(iso: string | null | undefined): string {
  if (!iso) return '—';
  const diff = Date.now() - new Date(iso).getTime();
  const s = Math.floor(diff / 1000);
  if (s < 60) return `${s}초 전`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  return `${Math.floor(h / 24)}일 전`;
}

function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

const TABLE_ROWS = 6;
const RUN: Record<MdsHistoryRow['status'], { tone: BadgeTone; label: string; strip: boolean | null }> = {
  SYNCED: { tone: 'ok', label: '동기화됨', strip: true },
  SKIPPED: { tone: 'neutral', label: '건너뜀', strip: null },
  FAILED: { tone: 'bad', label: '실패', strip: false },
};
const HEAD_TONE: Record<'ok' | 'bad' | 'neutral', { bg: string; fg: string }> = {
  ok: { bg: 'var(--ok-soft)', fg: 'var(--ok)' },
  bad: { bg: 'var(--bad-soft)', fg: 'var(--bad)' },
  neutral: { bg: 'var(--sunk)', fg: 'var(--mute)' },
};
const DL_STYLE = { display: 'grid', gridTemplateColumns: '150px minmax(0, 1fr)', gap: '12px 16px', fontSize: 13, margin: 0 } as const;
const DT_STYLE = { color: 'var(--mute)' } as const;
const DD_STYLE = { margin: 0, minWidth: 0 } as const;

const columns: Column<MdsHistoryRow>[] = [
  { key: 'at', header: '시각', render: (r) => <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{fmtDateTime(r.startedAt)}</span> },
  { key: 'ver', header: '버전', render: (r) => <span className="mono" style={{ fontSize: 12 }}>{r.version != null ? `#${r.version}` : '—'}</span> },
  { key: 'change', header: '변경', render: (r) => <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{r.changeSummary ?? '—'}</span> },
  { key: 'status', header: '상태', render: (r) => <Badge tone={RUN[r.status].tone}>{RUN[r.status].label}</Badge> },
  { key: 'ms', header: '응답 시간', align: 'right', render: (r) => <span className="mono" style={{ fontSize: 12 }}>{r.durationMs != null ? `${r.durationMs}ms` : '—'}</span> },
];

// ── MdsStatusTab ──────────────────────────────────────────────────────────────

export default function MdsStatusTab() {
  const [status, setStatus] = useState<MdsStatus | null>(null);
  const [history, setHistory] = useState<MdsHistoryRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const toast = useToast();

  async function load() {
    setLoading(true);
    try {
      const data = await mdsStatusApi.get();
      setStatus(data);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '알 수 없는 오류';
      toast({ kind: 'err', title: 'MDS 상태 로드 실패', message: msg });
    } finally {
      setLoading(false);
    }
  }

  async function loadHistory() {
    try {
      setHistory(await mdsStatusApi.history(MDS_HISTORY_STRIP_LIMIT));
    } catch {
      setHistory([]);
    }
  }

  async function handleSync() {
    setSyncing(true);
    try {
      const result = await mdsStatusApi.sync();
      if (result.status === 'SYNCED') {
        toast({ kind: 'ok', title: 'MDS 즉시 갱신 완료', message: `버전 ${result.version}` });
      } else if (result.status === 'SKIPPED') {
        toast({ kind: 'warn', title: 'MDS 갱신 건너뜀', message: '다른 인스턴스가 동기화 중입니다.' });
      } else {
        toast({ kind: 'err', title: 'MDS 갱신 실패', message: result.error ?? '알 수 없는 오류' });
      }
      await Promise.all([load(), loadHistory()]);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '알 수 없는 오류';
      toast({ kind: 'err', title: 'MDS 갱신 실패', message: msg });
    } finally {
      setSyncing(false);
    }
  }

  useEffect(() => { void load(); void loadHistory(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (loading) {
    return (
      <section className="card">
        <div className="card__body muted" style={{ fontSize: 13 }}>MDS 상태 로딩 중…</div>
      </section>
    );
  }

  const versionText = status?.version != null ? `#${status.version}` : '—';
  const latest = history[0];
  const headTone: 'ok' | 'bad' | 'neutral' = latest?.status === 'FAILED' ? 'bad' : !status?.fetchedAt ? 'neutral' : 'ok';
  const headTitle = headTone === 'bad' ? `동기화 실패 · BLOB ${versionText}` : headTone === 'neutral' ? '동기화 이력 없음' : `정상 · BLOB ${versionText}`;
  const headIcon: ReactNode = headTone === 'ok' ? <Icons.Check size={24} /> : <Icons.Alert size={24} />;
  const rate = status?.successRate30d ?? { ok: 0, total: 0 };
  const pct = rate.total > 0 ? Math.round((rate.ok / rate.total) * 100) : 0;
  const stripResults = [...history].reverse().map((r) => RUN[r.status].strip);
  const failCount = history.filter((r) => r.status === 'FAILED').length;

  return (
    <div className="stack-6">
      <section className="card" style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 20, padding: '20px 24px' }}>
        <span
          aria-hidden="true"
          style={{ width: 48, height: 48, borderRadius: 12, background: HEAD_TONE[headTone].bg, color: HEAD_TONE[headTone].fg, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flex: 'none' }}
        >
          {headIcon}
        </span>
        <div className="col" style={{ gap: 2, flex: '1 1 320px', minWidth: 0 }}>
          <span className="t-over">MDS 동기화 상태</span>
          <strong style={{ fontSize: 20, lineHeight: '28px' }}>{headTitle}</strong>
          <span className="card__sub">
            FIDO MDS3 BLOB 을 매일 03:00 KST 에 갱신합니다{status?.nextUpdate ? ` · 다음 갱신 ${status.nextUpdate} 03:00` : ''}
          </span>
        </div>
        <Button onClick={() => void handleSync()} disabled={syncing} icon={<Icons.Refresh size={14} />}>
          {syncing ? '갱신 중…' : '즉시 갱신'}
        </Button>
      </section>

      <div className="kpis kpis--3">
        <KpiCard label="MDS BLOB 버전" value={<span className="mono">{versionText}</span>} />
        <KpiCard label="Trust anchors" value={status?.trustAnchorCount ?? 0} />
        <KpiCard label="마지막 동기화" value={timeAgo(status?.fetchedAt ?? null)} />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1.4fr)', gap: 20, alignItems: 'start' }}>
        <section className="card">
          <CardHeader title="동기화 설정" />
          <CardBody>
            <dl style={DL_STYLE}>
              <dt style={DT_STYLE}>endpoint</dt>
              <dd style={DD_STYLE} className="mono"><span style={{ fontSize: 12 }}>https://mds3.fidoalliance.org/</span></dd>
              <dt style={DT_STYLE}>next 갱신 예정</dt>
              <dd style={DD_STYLE}>{status?.nextUpdate ? `${status.nextUpdate} 03:00 KST` : '알 수 없음'}</dd>
              <dt style={DT_STYLE}>동기화 성공률 (30d)</dt>
              <dd style={DD_STYLE}><span>{pct}%</span><span className="muted"> · {rate.ok} / {rate.total}</span></dd>
              <dt style={DT_STYLE}>신뢰 모드</dt>
              <dd style={DD_STYLE}><Badge tone="info" dot={false}>{status?.trustMode ?? 'MDS_STRICT_OPTIONAL'}</Badge></dd>
            </dl>
          </CardBody>
        </section>

        <section className="card">
          <CardHeader
            title="최근 동기화 이력"
            actions={history.length > 0 ? (
              <StatusStrip results={stripResults} label={`최근 ${history.length}회 동기화 결과 · 실패 ${failCount}회`} />
            ) : undefined}
          />
          <DataTable
            columns={columns}
            rows={history.slice(0, TABLE_ROWS)}
            rowKey={(r) => String(r.id)}
            empty={<p className="hint" style={{ margin: 0, padding: '16px 20px' }}>동기화 이력이 없습니다.</p>}
          />
        </section>
      </div>
    </div>
  );
}
```

- [ ] **Step 8: SystemInfoTab V2 구현**

```tsx
// admin-ui/src/pages/settings/SystemInfoTab.tsx
import { useEffect, useState } from 'react';
import { systemInfoApi, type SystemInfoData, type SystemInfoComponent } from '@/api/systemInfo';
import { Icons } from '@/icons/Icons';
import { illustrations } from '@/assets/illustrations';
import { Badge, Button, CardBody, CardHeader, EmptyState, KpiCard, type BadgeTone } from '@/ui';

type ComponentStatus = SystemInfoComponent['status'];

const STATUS: Record<ComponentStatus, { tone: BadgeTone; label: string; dot: string }> = {
  OK: { tone: 'ok', label: '정상', dot: 'var(--ok)' },
  DEGRADED: { tone: 'warn', label: '저하', dot: 'var(--warn-dot)' },
  DOWN: { tone: 'bad', label: '중단', dot: 'var(--bad)' },
};

/** 전체 상태 = 가장 나쁜 컴포넌트 상태. */
function overallStatus(components: SystemInfoComponent[]): ComponentStatus {
  if (components.some((c) => c.status === 'DOWN')) return 'DOWN';
  if (components.some((c) => c.status === 'DEGRADED')) return 'DEGRADED';
  return 'OK';
}

const DL_STYLE = { display: 'grid', gridTemplateColumns: '120px minmax(0, 1fr)', gap: '12px 16px', fontSize: 13, margin: 0 } as const;
const DT_STYLE = { color: 'var(--mute)' } as const;
const DD_STYLE = { margin: 0, minWidth: 0 } as const;
const CAPTION = { margin: 0 } as const;

export default function SystemInfoTab() {
  const [info, setInfo] = useState<SystemInfoData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(false);
    systemInfoApi.get()
      .then((d) => { if (!cancelled) setInfo(d); })
      .catch(() => { if (!cancelled) setError(true); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [attempt]);

  if (loading) {
    return (
      <div className="kpis kpis--3" aria-busy="true">
        <span className="sr-only">System Info 로딩 중…</span>
        {[0, 1, 2].map((i) => (
          <div key={i} className="kpi">
            <span className="skeleton" style={{ width: 80 }} />
            <span className="skeleton" style={{ width: 120, height: 22, marginTop: 8 }} />
          </div>
        ))}
      </div>
    );
  }

  if (error || !info) {
    return (
      <section className="card">
        <EmptyState
          illustration={illustrations.loadError}
          title="System Info 를 불러오지 못했습니다."
          description="네트워크 상태를 확인한 뒤 다시 시도하세요."
          action={<Button onClick={() => setAttempt((a) => a + 1)}>다시 시도</Button>}
        />
      </section>
    );
  }

  const overall = overallStatus(info.components);
  const okCount = info.components.filter((c) => c.status === 'OK').length;
  const profiles = info.host.environment.split(',').map((p) => p.trim()).filter(Boolean);

  return (
    <div className="stack-6">
      <div className="kpis kpis--3">
        <KpiCard
          label="전체 상태"
          value={<span className="row" style={{ gap: 8 }}><span className="dot" style={{ width: 10, height: 10, background: STATUS[overall].dot }} />{STATUS[overall].label}</span>}
          hint={`컴포넌트 ${okCount} / ${info.components.length}`}
        />
        <KpiCard
          label="Server 버전"
          value={<span className="mono" style={{ fontSize: 22 }}>{info.serverVersion}</span>}
          hint={info.deployedAt ? `${info.deployedAt.slice(0, 10)} 배포` : '배포 시각 정보 없음'}
        />
        <KpiCard label="Uptime" value={`${info.uptimeDays}d`} />
      </div>

      <div className="grid-2">
        <section className="card">
          <CardHeader title="백엔드 컴포넌트" />
          <ul aria-label="백엔드 컴포넌트" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
            {info.components.map((c, i) => (
              <li
                key={i}
                className="row"
                style={{ gap: 12, flexWrap: 'nowrap', padding: '12px 20px', borderBottom: i < info.components.length - 1 ? '1px solid var(--line-soft)' : 0 }}
              >
                <span
                  aria-hidden="true"
                  style={{ width: 30, height: 30, borderRadius: 8, background: 'var(--canvas)', border: '1px solid var(--line)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: 'var(--ink-2)', flex: 'none' }}
                >
                  <Icons.Server size={15} />
                </span>
                <span className="col" style={{ flex: '1 1 0', minWidth: 0 }}>
                  <span className="mono truncate">{c.name}</span>
                  {c.note && <span className="hint" style={CAPTION}>{c.note}</span>}
                </span>
                <span className="hint mono" style={CAPTION}>{c.version}</span>
                {c.latencyMs != null && <span className="hint" style={CAPTION}>{c.latencyMs}ms</span>}
                <Badge tone={STATUS[c.status].tone}>{STATUS[c.status].label}</Badge>
              </li>
            ))}
          </ul>
        </section>

        <section className="card">
          <CardHeader title="호스트 정보" actions={<Badge tone="info" dot={false}>환경 · <span>{info.host.environment}</span></Badge>} />
          <CardBody>
            <dl style={DL_STYLE}>
              <dt style={DT_STYLE}>활성 프로필</dt>
              <dd style={DD_STYLE}>
                <span className="chips">{profiles.map((p) => <span key={p} className="tag">{p}</span>)}</span>
              </dd>
              <dt style={DT_STYLE}>배포 시각</dt>
              <dd style={DD_STYLE} className="mono"><span style={{ fontSize: 12 }}>{info.deployedAt ?? '—'}</span></dd>
            </dl>
          </CardBody>
        </section>
      </div>
    </div>
  );
}
```

- [ ] **Step 9: SecurityPolicyTab V2 구현**

```tsx
// admin-ui/src/pages/settings/SecurityPolicyTab.tsx
import { useState, useEffect } from 'react';
import { securityPolicyApi, type SecurityPolicyView } from '@/api/securityPolicy';
import { CardHeader, ChipInput, SaveBar, Switch, useToast, useUnsavedGuard } from '@/ui';

function sameList(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((x, i) => x === b[i]);
}

export default function SecurityPolicyTab() {
  const [sessionMin, setSessionMin] = useState(30);
  const [reqMfa, setReqMfa] = useState(true);
  const [corsAllowlist, setCorsAllowlist] = useState<string[]>([]);
  const [saved, setSaved] = useState<SecurityPolicyView | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const toast = useToast();

  function applyView(v: SecurityPolicyView) {
    setSessionMin(v.sessionIdleTimeoutMinutes);
    setReqMfa(v.mfaRequired);
    setCorsAllowlist(v.corsAllowlist);
    setSaved(v);
  }

  useEffect(() => {
    setLoading(true);
    securityPolicyApi.get()
      .then(applyView)
      .catch((e: unknown) => {
        const msg = e instanceof Error ? e.message : String(e);
        toast({ kind: 'err', title: '보안 정책 로드 실패', message: msg });
      })
      .finally(() => setLoading(false));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function handleSave() {
    setSaving(true);
    try {
      const v = await securityPolicyApi.update({
        sessionIdleTimeoutMinutes: sessionMin,
        mfaRequired: reqMfa,
        corsAllowlist,
      });
      applyView(v);
      toast({ kind: 'ok', title: '보안 정책 저장됨', traceId: 'tr_sec_001' });
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '보안 정책 저장 실패', message: msg });
    } finally {
      setSaving(false);
    }
  }

  async function handleCancel() {
    try {
      const v = await securityPolicyApi.get();
      applyView(v);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '되돌리기 실패', message: msg });
    }
  }

  const changed: string[] = [];
  if (saved) {
    if (sessionMin !== saved.sessionIdleTimeoutMinutes) changed.push('세션 idle timeout');
    if (reqMfa !== saved.mfaRequired) changed.push('MFA 필수');
    if (!sameList(corsAllowlist, saved.corsAllowlist)) changed.push('CORS Allowlist');
  }
  const guard = useUnsavedGuard(changed.length > 0);
  const addedOrigins = saved ? corsAllowlist.filter((o) => !saved.corsAllowlist.includes(o)) : [];

  return (
    <div className="stack-4">
      <section className="card">
        <CardHeader title="세션 & 인증 정책" sub="모든 콘솔 운영자에게 적용" />

        <div className="set">
          <div>
            <label className="set__label" htmlFor="sec-idle-timeout" style={{ display: 'block' }}>세션 idle timeout (분)</label>
            <p className="set__desc">이 시간 동안 활동이 없으면 세션이 만료됩니다. 만료 직전 연장 모달이 뜹니다.</p>
          </div>
          <div className="row" style={{ gap: 10 }}>
            <input
              id="sec-idle-timeout"
              className="input mono"
              type="number"
              value={sessionMin}
              disabled={loading}
              onChange={(e) => setSessionMin(parseInt(e.target.value || '0', 10))}
              style={{ maxWidth: 120 }}
            />
            <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>분</span>
          </div>
        </div>

        <div className="set">
          <div>
            <span className="set__label" style={{ display: 'block' }}>MFA 필수</span>
            <p className="set__desc">켜면 MFA 를 등록하지 않은 운영자는 다음 로그인 때 등록해야 합니다.</p>
          </div>
          <div style={{ alignSelf: 'flex-start' }}>
            <Switch checked={reqMfa} onChange={setReqMfa} label="모든 운영자에게 MFA 필수" disabled={loading} />
          </div>
        </div>

        <div className="set">
          <div>
            <span className="set__label" style={{ display: 'block' }}>CORS Allowlist</span>
            <p className="set__desc">콘솔 API 를 호출할 수 있는 origin. Enter 로 추가합니다.</p>
          </div>
          <ChipInput
            values={corsAllowlist}
            onChange={setCorsAllowlist}
            placeholder="https://… 추가 후 Enter"
            ariaLabel="origin 추가"
            mono
            addedValues={addedOrigins}
          />
        </div>
      </section>

      <SaveBar
        count={changed.length}
        summary={changed.join(' · ')}
        onRevert={() => void handleCancel()}
        onSave={() => void handleSave()}
        saving={saving}
        revertLabel="취소"
      />
      {guard}
    </div>
  );
}
```

- [ ] **Step 10: App.tsx 임시 래퍼 제거**

Task 12 Step 6 에서 넣은 `<main>` 안 래퍼를 지운다. 변경 전:

```tsx
        <main>
          <ErrorBoundary>
            <div className="page">
              <Routes>
                {/* … Route 7개 … */}
              </Routes>
            </div>
          </ErrorBoundary>
        </main>
```

변경 후(라우트 7개는 현재 그대로):

```tsx
        <main>
          <ErrorBoundary>
            <Routes>
              <Route path="/tenants" element={<RequirePlatform me={me}><TenantsListPage /></RequirePlatform>} />
              <Route path="/tenants/:id" element={<TenantDetailRoute me={me} />} />
              <Route path="/activity" element={<RequirePlatform me={me}><ActivityPage /></RequirePlatform>} />
              <Route path="/audit-chain" element={<RequirePlatform me={me}><AuditChainPage /></RequirePlatform>} />
              <Route path="/settings" element={<SettingsPage me={me} onMeChange={onMeChange} />} />
              <Route path="/license" element={<RequirePlatform me={me}><LicensePage /></RequirePlatform>} />
              <Route path="*" element={<Navigate to={rpTenantId(me) ? `/tenants/${rpTenantId(me)}` : '/tenants'} replace />} />
            </Routes>
          </ErrorBoundary>
        </main>
```

- [ ] **Step 11: 남은 조기 return 루트에 `.page` 적용**

Run: `grep -nE '^\s*(if \(.*\)\s*)?return\s*<(div|section|main|p)\b' src/pages/TenantsListPage.tsx src/pages/TenantDetailPage.tsx src/pages/ActivityPage.tsx src/pages/AuditChainPage.tsx src/pages/SettingsPage.tsx src/pages/LicensePage.tsx src/me/RequirePlatform.tsx | grep -v 'className="page"'`
Expected: 출력 없음. Task 14–26 이후에도 아래 줄이 남아 있으면 그대로 교체한다(스타일 패딩은 `.page` 가 담당).

`src/pages/TenantDetailPage.tsx` (`TenantDetailRoute`):

```tsx
  if (loading) return <div className="page muted">Loading…</div>;
  if (!tenant) return <div className="page muted">Tenant 를 찾을 수 없습니다.</div>;
```

`src/pages/LicensePage.tsx`:

```tsx
  if (err) return <div className="page" style={{ color: 'var(--bad)' }}>Error: {err}</div>;
  if (!license) return <div className="page muted">Loading…</div>;
  if (license.deploymentMode !== 'onprem') {
    return <div className="page muted">License management is on-prem only.</div>;
  }
```

교체 후 같은 grep 을 다시 실행해 출력이 없음을 확인한다.

- [ ] **Step 12: 페이지 루트 목록 확인**

Run: `grep -ln 'className="page"' src/pages/TenantsListPage.tsx src/pages/TenantDetailPage.tsx src/pages/ActivityPage.tsx src/pages/AuditChainPage.tsx src/pages/SettingsPage.tsx src/pages/LicensePage.tsx src/me/RequirePlatform.tsx && grep -rln 'className="page"' src/pages/settings src/pages/tenant || echo "탭 컴포넌트 중첩 없음"`
Expected: 첫 grep 이 7개 파일을 모두 출력(`TenantsListPage`, `TenantDetailPage`, `ActivityPage`, `AuditChainPage`, `SettingsPage`, `LicensePage`, `RequirePlatform`), 두 번째는 `탭 컴포넌트 중첩 없음`.

- [ ] **Step 13: 테스트 통과 확인**

Run: `npx vitest run src/api/mdsStatus.test.ts src/pages/settings src/test/pageRoots.test.ts`
Expected: PASS — mdsStatus 2, MdsStatusTab 5, SystemInfoTab 7, SecurityPolicyTab 4, pageRoots 1+7+7+2, 그리고 Task 27·28 의 settings 테스트.

- [ ] **Step 14: 전체 테스트·린트·빌드**

Run: `npm test && npm run lint && npm run build`
Expected: 전체 PASS, lint 오류 0, 빌드 성공.

- [ ] **Step 15: 수동 확인 (1280px)**

`npm run dev` → `/admin/settings?tab=mds`: 초록 체크 헤드라인 `정상 · BLOB #N`, KPI 3, 왼쪽 설정 카드·오른쪽 이력 표(6행)와 제목 옆 30칸 스트립. `?tab=system`: 전체 상태 KPI(점+라벨), 컴포넌트 목록 배지, 호스트 카드(admin-app 중지 시 일러스트 + `다시 시도`). `?tab=security`: 3개 `.set` 행, 값을 바꾸면 하단 고정 SaveBar, 저장 전 사이드바·다른 탭 이동 시 이탈 확인. 모든 화면(Tenants·Tenant 상세·활동·감사 체인·설정·라이선스)에서 페이지 패딩이 한 번만 적용되는지(이중 여백 없음) 확인.

- [ ] **Step 16: 커밋**

```bash
git add src/api/mdsStatus.ts src/api/mdsStatus.test.ts src/api/systemInfo.ts src/icons/Icons.tsx \
  src/pages/settings/MdsStatusTab.tsx src/pages/settings/MdsStatusTab.test.tsx \
  src/pages/settings/SystemInfoTab.tsx src/pages/settings/SystemInfoTab.test.tsx \
  src/pages/settings/SecurityPolicyTab.tsx src/pages/settings/SecurityPolicyTab.test.tsx \
  src/App.tsx src/pages/TenantDetailPage.tsx src/pages/LicensePage.tsx src/test/pageRoots.test.ts
git commit -m "feat(admin-ui): V2 MDS Status·시스템·보안 정책, 셸 임시 .page 래퍼 제거

- MDS: 상태 헤드라인·KPI·설정 카드·이력 표 + 최근 30회 StatusStrip(limit=30)
- 시스템: 전체 상태 KPI, 컴포넌트 latencyMs?(스펙 §6 ⑤) 있을 때만, 불러오기 실패 일러스트·다시 시도
- 보안 정책: .set 행, Switch·ChipInput, SaveBar + useUnsavedGuard
- 라우트 페이지 루트 .page 규칙을 소스 테스트로 고정

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 30: 토큰 별칭 제거 · 죽은 CSS 정리 · 최종 점검

모든 화면이 V2 로 전환된 뒤, Task 3 에서 남겨 둔 전환기간 별칭(`--accent`, `--text-*`, `--surface-*` …)을 제거하고 V2 토큰 이름만 남긴다.

**Files:**
- Modify: `admin-ui/src/styles/tokens.css`(별칭 블록 삭제), 별칭을 참조하는 모든 `src/**/*.{ts,tsx,css}`
- Modify: `admin-ui/src/styles/components.css`(사용처 없는 클래스 삭제)
- Test: `admin-ui/src/test/tokens.test.ts`(케이스 추가)

**Interfaces:**
- Consumes: Task 3 의 별칭 표(Produces 블록).
- Produces: 코드베이스 어디에도 별칭 변수명이 남지 않는다.

- [ ] **Step 1: 실패하는 테스트 추가**

`src/test/tokens.test.ts` 의 `describe('V2 tokens', …)` 안에 추가:

```ts
import { readdirSync, statSync } from 'node:fs';

const ALIASES = ['--accent', '--text', '--text-soft', '--text-mute', '--text-faint', '--border', '--border-subtle', '--border-strong', '--bg', '--surface-2', '--surface-3', '--surface-sunk', '--success', '--warning', '--danger', '--info', '--violet', '--teal', '--shadow-xs', '--shadow-sm', '--shadow-md', '--shadow-lg', '--radius'];

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((n) => { const p = join(dir, n); return statSync(p).isDirectory() ? walk(p) : [p]; });
}

it('전환기간 별칭이 정의·참조되지 않는다', () => {
  const src = join(__dirname, '..');
  const offenders: string[] = [];
  for (const f of walk(src).filter((f) => /\.(tsx?|css)$/.test(f) && !f.endsWith('tokens.test.ts'))) {
    const text = readFileSync(f, 'utf8');
    for (const a of ALIASES) {
      // var(--accent) / var(--accent-soft) / --accent: 모두 검출 (단, --accent-* 를 V2 토큰으로 쓰지 않으므로 접두 일치로 충분)
      const re = new RegExp(`${a}(-[a-z0-9]+)?\\s*[:)]`);
      if (re.test(text)) offenders.push(`${f.replace(src, 'src')} ${a}`);
    }
  }
  expect(offenders).toEqual([]);
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/test/tokens.test.ts`
Expected: FAIL — tokens.css 의 별칭 블록과, 아직 별칭을 쓰는 파일 목록이 출력된다.

- [ ] **Step 3: 별칭 → V2 이름 일괄 치환**

```bash
cd admin-ui
FILES=$(grep -rlE "var\(--(accent|text|border|bg|surface-[23]|surface-sunk|success|warning|danger|info|violet|teal|shadow|radius)" src --include='*.ts' --include='*.tsx' --include='*.css' | grep -v tokens.css)
for f in $FILES; do
  sed -i '' -E \
    -e 's/var\(--accent-soft\)/var(--brand-100)/g' -e 's/var\(--accent-hover\)/var(--brand-900)/g' -e 's/var\(--accent-fg\)/#fff/g' -e 's/var\(--accent\)/var(--brand-700)/g' \
    -e 's/var\(--text-soft\)/var(--ink-2)/g' -e 's/var\(--text-mute\)/var(--mute)/g' -e 's/var\(--text-faint\)/var(--faint)/g' -e 's/var\(--text\)/var(--ink)/g' \
    -e 's/var\(--border-subtle\)/var(--line-soft)/g' -e 's/var\(--border-strong\)/var(--line-strong)/g' -e 's/var\(--border\)/var(--line)/g' \
    -e 's/var\(--bg\)/var(--canvas)/g' -e 's/var\(--surface-2\)/var(--canvas)/g' -e 's/var\(--surface-3\)/var(--sunk)/g' -e 's/var\(--surface-sunk\)/var(--sunk)/g' \
    -e 's/var\(--success-soft\)/var(--ok-soft)/g' -e 's/var\(--success\)/var(--ok)/g' \
    -e 's/var\(--warning-soft\)/var(--warn-soft)/g' -e 's/var\(--warning\)/var(--warn)/g' \
    -e 's/var\(--danger-soft[^)]*\)/var(--bad-soft)/g' -e 's/var\(--danger[^)]*\)/var(--bad)/g' \
    -e 's/var\(--info-soft\)/var(--brand-100)/g' -e 's/var\(--info\)/var(--brand-700)/g' \
    -e 's/var\(--violet-soft\)/var(--plat-soft)/g' -e 's/var\(--violet\)/var(--plat)/g' \
    -e 's/var\(--teal-soft\)/var(--brand-100)/g' -e 's/var\(--teal\)/var(--brand-600)/g' \
    -e 's/var\(--shadow-(xs|sm)\)/var(--sh-1)/g' -e 's/var\(--shadow-md\)/var(--sh-float)/g' -e 's/var\(--shadow-lg\)/var(--sh-2)/g' \
    -e 's/var\(--radius-xs\)/var(--r-xs)/g' -e 's/var\(--radius-sm\)/var(--r-sm)/g' -e 's/var\(--radius-lg\)/var(--r-lg)/g' -e 's/var\(--radius-pill\)/var(--r-pill)/g' -e 's/var\(--radius\)/var(--r)/g' \
    "$f"
done
```

그 다음 `src/styles/tokens.css` 에서 `/* ── 전환기간 별칭 (Task 30 에서 제거) ── */` 주석부터 `:root` 블록 끝 직전까지의 별칭 선언을 모두 삭제한다.

`color-mix(in oklab, var(--…)…)` 처럼 sed 패턴에 걸리지 않는 형태가 남으면 Step 2 출력의 파일·변수를 보고 같은 표로 손으로 바꾼다.

- [ ] **Step 4: 죽은 클래스 삭제**

```bash
cd admin-ui
for c in btn--outline metric-label metric-value metric-delta badge--accent badge--teal; do
  printf "%-16s %s\n" "$c" "$(grep -rl "$c" src --include='*.tsx' --include='*.ts' | wc -l | tr -d ' ')"
done
```

사용처가 0 인 클래스의 규칙을 `components.css` 에서 삭제한다(셀렉터 목록에서만 쓰이는 경우 해당 셀렉터만 제거, 예: `.kpi__label, .metric-label` → `.kpi__label`).

- [ ] **Step 5: 전체 점검**

Run:
```bash
cd admin-ui
npm test && npm run lint && npm run build
! grep -rE "fonts\.googleapis|fonts\.gstatic|jsdelivr" dist
grep -rn "data-theme\|useTweaks\|TweaksPanel" src || echo "clean"
```
Expected: 테스트·린트(경고만 허용, 에러 0)·빌드 통과, 외부 URL 없음, `clean` 출력.

- [ ] **Step 6: 커밋**

```bash
git add -A src
git commit -m "refactor(admin-ui): 전환기간 토큰 별칭 제거, 미사용 CSS 정리

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 31: V2 캔버스와 시각 비교 · 차이 수정

자동 테스트가 못 잡는 시각 차이를 잡는다. 결과물은 화면별 스크린샷과 차이 목록, 그리고 차이를 고친 커밋이다.

**Files:**
- Create (scratch, 커밋하지 않음): `$SCRATCH/visual/shoot.mjs`, `$SCRATCH/visual/shots/*.png`, `$SCRATCH/visual/report.md`
- Modify: 차이가 발견된 화면/컴포넌트 파일

`$SCRATCH` = 세션 scratchpad 디렉터리(예: `/private/tmp/claude-501/…/scratchpad`). Playwright 는 프로젝트 의존성으로 추가하지 않는다.

**Interfaces:**
- Consumes: 로컬 스택(`docs/dev-setup.md`: admin-app 8081 dev 프로필, 데모 PLATFORM 계정), `npm run dev`(5173, `/admin/` base).
- Produces: `report.md` — 아트보드 30개 각각에 대해 `일치` 또는 `차이: …(수정 커밋 sha)`.

- [ ] **Step 1: 로컬 스택 실행**

`docs/dev-setup.md` 절차대로 DB·admin-app(8081, dev 프로필)·passkey-app 을 띄우고, 비교용 데이터가 있도록 tenant 2개 이상·credential·API 키(만료 임박 1개 포함)·감사 로그가 있는지 확인한다(없으면 UI 로 생성). 그 다음 `cd admin-ui && npm run dev`.

- [ ] **Step 2: 캡처 스크립트**

```js
// $SCRATCH/visual/shoot.mjs   실행: cd $SCRATCH/visual && npm init -y >/dev/null && npm i playwright@1.47.2 && npx playwright install chromium && node shoot.mjs
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';

const BASE = process.env.BASE ?? 'http://localhost:5173/admin';
const EMAIL = process.env.ADMIN_EMAIL;      // 데모 PLATFORM 계정
const PASSWORD = process.env.ADMIN_PASSWORD;
mkdirSync('shots', { recursive: true });

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const shot = (name) => page.screenshot({ path: `shots/${name}.png`, fullPage: true });

// 로그인 전 화면
await page.goto(`${BASE}/`); await page.waitForLoadState('networkidle'); await shot('Auth-Login');
await page.goto(`${BASE}/forgot-password`); await shot('Auth-Forgot');
await page.goto(`${BASE}/reset-password?token=invalid`); await shot('Auth-Reset');
await page.goto(`${BASE}/signup`); await shot('Auth-Signup');

// 로그인
await page.goto(`${BASE}/`);
await page.getByLabel('이메일').fill(EMAIL); await page.getByLabel('비밀번호').fill(PASSWORD);
await page.getByRole('button', { name: '로그인', exact: true }).click();
await page.waitForURL(/\/admin\/tenants/);

const routes = [
  ['Tenants', '/tenants'], ['Activity', '/activity'], ['AuditChain', '/audit-chain'], ['License', '/license'],
  ['Settings-Account', '/settings'],
];
for (const [name, path] of routes) { await page.goto(`${BASE}${path}`); await page.waitForLoadState('networkidle'); await shot(name); }

// 첫 tenant 상세 7탭
await page.goto(`${BASE}/tenants`); await page.waitForLoadState('networkidle');
await page.locator('table tbody tr.is-clickable').first().click();
await page.waitForURL(/\/tenants\/[^/]+/);
const tenantUrl = page.url().split('?')[0];
for (const [name, tab] of [['Tenant-Overview', 'overview'], ['Tenant-WebAuthn', 'webauthn'], ['Tenant-Aaguid', 'aaguid'], ['Tenant-ApiKeys', 'apikeys'], ['Tenant-Credentials', 'credentials'], ['Tenant-Audit', 'audit'], ['Tenant-Funnel', 'funnel']]) {
  await page.goto(`${tenantUrl}?tab=${tab}`); await page.waitForLoadState('networkidle'); await shot(name);
}

// 설정 탭
for (const [name, label] of [['Settings-AdminUsers', 'Admin 사용자'], ['Settings-Mds', 'MDS Status'], ['Settings-System', '시스템'], ['Settings-Security', '보안 정책']]) {
  await page.goto(`${BASE}/settings`); await page.getByRole('tab', { name: new RegExp(label) }).click(); await page.waitForLoadState('networkidle'); await shot(name);
}

// 다이얼로그·오버레이 (열기만 하고 실행하지 않는다)
await page.goto(`${BASE}/tenants`); await page.getByRole('button', { name: /신규 tenant/ }).click(); await shot('Dialog-NewTenant'); await page.keyboard.press('Escape');
await page.goto(`${tenantUrl}?tab=overview`); await page.getByRole('button', { name: /더보기|테넌트 액션/ }).click(); await page.getByRole('menuitem', { name: '테넌트 정지' }).click(); await shot('Dialog-Suspend'); await page.getByRole('button', { name: '취소' }).click();
await page.goto(`${tenantUrl}?tab=apikeys`); await page.getByRole('button', { name: /새 키 발급/ }).click(); await shot('Dialog-IssueKey'); await page.keyboard.press('Escape');
await page.goto(`${tenantUrl}?tab=credentials`); const row = page.locator('table tbody tr.is-clickable').first(); if (await row.count()) { await row.click(); await shot('Dialog-CredentialDetail'); await page.keyboard.press('Escape'); }
await page.keyboard.press('Meta+k'); await shot('Overlay-CommandPalette'); await page.keyboard.press('Escape');

await browser.close();
console.log('done');
```

Run: `cd $SCRATCH/visual && ADMIN_EMAIL=… ADMIN_PASSWORD=… node shoot.mjs`
Expected: `done`, `shots/` 에 27개 이상 PNG. 실패한 단계가 있으면 해당 화면의 접근 이름(라벨)을 실제 UI 에 맞게 고쳐 다시 실행.

- [ ] **Step 3: 아트보드와 비교**

캔버스 https://claude.ai/artifact/7Hf6x25iguc9CvU54brtPA 의 V2 페이지에서 각 아트보드를, 대응 스크린샷과 나란히 놓고 아래 항목을 확인한다. 아트보드 원본 마크업(`project/v2/<이름>.dc.html`)과 `ds.css` 를 함께 열어 수치(간격·크기·색)를 대조한다.

체크리스트(화면마다): ① 영역 순서·배치 ② 제목/설명/버튼 문구 ③ 색(주 버튼 #0B4EA2, 상태 배지 톤) ④ 간격(페이지 28/32px, 카드 패딩 16·20px, 섹션 gap 20–24px) ⑤ 컴포넌트 상태(선택 세그먼트, 활성 탭 밑줄, 점 배지) ⑥ 빈 상태·일러스트 크기 ⑦ 폰트(IBM Plex Sans KR 적용 여부 — DevTools Computed 의 rendered font).

`report.md` 형식:

```markdown
| 아트보드 | 스크린샷 | 결과 | 비고 |
|---|---|---|---|
| Tenants.dc.html | shots/Tenants.png | 일치 | |
| Tenant-ApiKeys.dc.html | shots/Tenant-ApiKeys.png | 차이 → 수정 | 만료 임박 아이콘 누락 (abc1234) |
```

- [ ] **Step 4: 차이 수정**

차이마다 해당 화면 태스크의 테스트를 먼저 보강(가능한 경우: 문구·요소 존재·클래스)한 뒤 고친다. Run: `npm test && npm run build` → PASS. 수정 후 Step 2 를 다시 실행해 해당 스크린샷을 갱신하고 `report.md` 를 `일치` 로 바꾼다.

- [ ] **Step 5: 커밋**

```bash
git add -A src
git commit -m "fix(admin-ui): V2 캔버스 시각 비교 차이 수정

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

`report.md` 와 스크린샷은 커밋하지 않고, 최종 보고 때 사용자에게 경로와 요약을 전달한다.
