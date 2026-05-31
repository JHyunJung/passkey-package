# Phase QW-5 — admin-ui 코드 위생 (perf+quality) Implementation Plan

**REQUIRED SUB-SKILL: subagent-driven-development** — each Task below is a self-contained unit dispatched to an implementer subagent, then verified. Execute Tasks in order; do not parallelize (Tasks 3→5 share files/build gates).

## Goal

`docs/superpowers/specs/2026-05-31-codebase-hardening-quick-wins-design.md` §7의 admin-ui 코드 위생 8건(필수 5 + 선택 3)을 닫는다. **전부 표시값·외관·사용자 흐름 불변. UI/UX 디자인 무변경.** 순수 메모이제이션, dead code 제거, 불필요 폴링 가드, ESLint(warn 레벨) 도입이며 런타임 동작 바이트 불변.

커버하는 finding id:
- `perf-activity-unmemoized-filtering` (Task 1, 필수)
- `perf-tenantslist-unmemoized-aggregates` (Task 2, 필수)
- `cq-dead-mecontext` (Task 3, 필수)
- `cq-sidebar-fetch-all-roles` (Task 4, 필수)
- `cq-no-eslint` (Task 5, 필수)
- `cq-a11y-clickable-rows` (Task 6, 선택 — 외관 불변 한정)
- `perf-sidebar-not-memoized-inline-props` (Task 7, 선택 — 외관 불변 한정)
- `perf-audittab-payload-stringify-per-row` (Task 8, 선택 — 외관 불변 한정)

## Architecture

admin-ui는 Vite + React 18 + TypeScript SPA(`admin-ui/`). 빌드 게이트:
- `npx tsc -b` (build의 1단계, `package.json:8`) — EXIT 0
- `npm test` = `vitest run` — **baseline: 9 files / 22 tests passing** (변경 후 동일 카운트 유지가 회귀 게이트)
- `npm run build` = `tsc -b && vite build` — EXIT 0

State 흐름은 prop drilling(MeContext 미사용). Sidebar는 `me`를 prop으로 받고 `isPlatform(me)`로 platform 여부 계산(`Sidebar.tsx:85`). ActivityPage/TenantsListPage는 5s/manual 폴링 state에서 derived 값을 매 렌더 재계산 — 메모이제이션 대상.

## Tech Stack

- React 18.3.1, `useMemo` (일부 파일 이미 import)
- TypeScript 5.4.5, `strict: true`, `noUnusedLocals: false` / `noUnusedParameters: false` (이번 Phase에서 flip 안 함 — spec §7 "미사용 식별자가 많으면 보류")
- vitest 2.1.1 + @testing-library/react 16
- 신규: eslint + typescript-eslint + eslint-plugin-react-hooks + eslint-plugin-jsx-a11y + globals (flat config, `"type":"module"`이므로 ESM flat config 필수)

## 사전 확인 (이미 수집됨 — 재확인 불요)

수집된 정확한 현재 코드 기준:
- `ActivityPage.tsx:1` import = `import { useState, useEffect, useRef } from 'react';` → **useMemo 미import**. state: `events`(L161, `DisplayEvent[] | null`), `filter`(L166, `'all'|'mutations'|'failures'`).
- `TenantsListPage.tsx:1` import = `import { useState, useEffect, useMemo } from 'react';` → **useMemo 이미 import**, L86-96에서 이미 사용 중.
- `Sidebar.tsx:5` `import { isPlatform } from '@/me/roles';` 이미 import, L85 `const platform = isPlatform(me);` 이미 계산됨. Footer(L173-201)는 `sidebarMode === 'labels'` 게이트, `!chain?.totals`면 '확인 중…' 표시(L175-182).
- `MeContext.tsx` — grep 결과 `MeProvider`/`useMe()` 실제 호출 ZERO(`useMemo` 매치는 substring noise). `getMe`는 `client.ts:132`에 export, `AccountTab.tsx:6,54,83`에서 사용 → **client.ts는 건드리지 않음**, MeContext.tsx 파일만 삭제.
- `package.json` `"type":"module"`(L5), eslint 일체 없음, config 파일(`eslint.config.*`/`.eslintrc*`) 없음 확인됨.
- baseline: `npx tsc -b` EXIT 0, `vitest run` = 22 passed (9 files).

---

## Task 1 — ActivityPage: filtered / failureCount / mutationCount → useMemo (`perf-activity-unmemoized-filtering`)

### Files
- `admin-ui/src/pages/ActivityPage.tsx`

### Steps

**Step 1.1** — 회귀 baseline 캡처. 이 Task 전후로 표시값이 동일함을 입증할 기준선.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && npx vitest run 2>&1 | tail -4
```
기대 출력: `Test Files  9 passed (9)` / `Tests  22 passed (22)`.

**Step 1.2** — import에 `useMemo` 추가. 현재 L1:
```tsx
import { useState, useEffect, useRef } from 'react';
```
다음으로 교체:
```tsx
import { useState, useEffect, useRef, useMemo } from 'react';
```

**Step 1.3** — derived 3값을 useMemo로 감싼다. 현재 L219-227:
```tsx
  const displayEvents = events ?? [];
  const filtered = displayEvents.filter((e) => {
    if (filter === 'mutations') return e.category === 'ops';
    if (filter === 'failures') return e.category === 'security';
    return true;
  });

  const failureCount = displayEvents.filter((e) => e.category === 'security').length;
  const mutationCount = displayEvents.filter((e) => e.category === 'ops').length;
```
다음으로 교체:
```tsx
  const displayEvents = useMemo(() => events ?? [], [events]);
  const filtered = useMemo(
    () =>
      displayEvents.filter((e) => {
        if (filter === 'mutations') return e.category === 'ops';
        if (filter === 'failures') return e.category === 'security';
        return true;
      }),
    [displayEvents, filter],
  );

  const failureCount = useMemo(
    () => displayEvents.filter((e) => e.category === 'security').length,
    [displayEvents],
  );
  const mutationCount = useMemo(
    () => displayEvents.filter((e) => e.category === 'ops').length,
    [displayEvents],
  );
```
근거: 로직·결과값 1:1 동일. `displayEvents`는 `events ?? []`로 events state 변경 시에만 새 배열 생성(이전엔 매 렌더 새 `[]`였으나 빈 배열 fallback이라 child에 안 내려가 표시값 무관). `filtered` deps=[displayEvents, filter], count deps=[displayEvents] — 정확.

**Step 1.4** — 불변식 검증(표시값/외관 불변). tsc + vitest로 회귀 0 확인.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && npx tsc -b 2>&1 | tail -3; echo "tsc_exit=$?"; npx vitest run 2>&1 | tail -4
```
기대: `tsc_exit=0`, `Tests  22 passed (22)`. (ActivityPage 전용 단위 테스트는 없으나 `filtered`/count는 순수 파생값이고 JSX 소비처(`filtered.map`, `failureCount`/`mutationCount` 표시)가 동일 값을 받으므로 표시 불변. 새 테스트 불요 — spec §7 "vitest 22 tests green 유지".)

**Step 1.5** — build EXIT 0 확인(외관 무변경 입증의 일부 — 번들 산출 정상).
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && npm run build 2>&1 | tail -5; echo "build_exit=$?"
```
기대: `build_exit=0`.

**Step 1.6** — commit.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && git add admin-ui/src/pages/ActivityPage.tsx && git commit -m "perf(admin-ui): ActivityPage filtered/failureCount/mutationCount useMemo 화

perf-activity-unmemoized-filtering: 5s 폴링마다 매 렌더 3회 전체 스캔하던
파생값을 useMemo 로. useMemo import 추가. 표시값/외관 불변.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2 — TenantsListPage: 3 집계 → useMemo (`perf-tenantslist-unmemoized-aggregates`)

### Files
- `admin-ui/src/pages/TenantsListPage.tsx`

### Steps

**Step 2.1** — `useMemo`는 이미 import 됨(L1, 확인됨). import 변경 불요. 현재 L98-100:
```tsx
  const totalCredentials = tenants.reduce((a, t) => a + t.credentials, 0);
  const totalKeys = tenants.reduce((a, t) => a + t.apiKeys, 0);
  const totalActive = tenants.filter((t) => t.status === 'ACTIVE').length;
```
다음으로 교체:
```tsx
  const totalCredentials = useMemo(
    () => tenants.reduce((a, t) => a + t.credentials, 0),
    [tenants],
  );
  const totalKeys = useMemo(
    () => tenants.reduce((a, t) => a + t.apiKeys, 0),
    [tenants],
  );
  const totalActive = useMemo(
    () => tenants.filter((t) => t.status === 'ACTIVE').length,
    [tenants],
  );
```
근거: 세 집계 모두 `tenants` state에만 의존. 검색 타이핑(`q` state 변경)마다 전체 스캔하던 것을 `tenants` 불변 시 캐시. 결과값 1:1 동일.

**Step 2.2** — 불변식 검증(표시값 불변). tsc + vitest.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && npx tsc -b 2>&1 | tail -3; echo "tsc_exit=$?"; npx vitest run 2>&1 | tail -4
```
기대: `tsc_exit=0`, `Tests  22 passed (22)`.

**Step 2.3** — build EXIT 0.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && npm run build 2>&1 | tail -3; echo "build_exit=$?"
```
기대: `build_exit=0`.

**Step 2.4** — commit.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && git add admin-ui/src/pages/TenantsListPage.tsx && git commit -m "perf(admin-ui): TenantsListPage 3집계 useMemo 화

perf-tenantslist-unmemoized-aggregates: 검색 타이핑마다 reduce/filter 전체
스캔 3회를 useMemo([tenants]) 로. 표시값/외관 불변.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3 — MeContext.tsx dead code 삭제 (`cq-dead-mecontext`)

### Files
- `admin-ui/src/me/MeContext.tsx` (삭제 대상)
- **금지**: `admin-ui/src/api/client.ts` 는 건드리지 않음(`getMe`는 `AccountTab.tsx`가 사용 — 수집 결과 confirmed).

### Steps

**Step 3.1** — 삭제 안전성 최종 재확인(실제 호출 0건). substring noise(`useMemo`) 제외하고 단어 경계로 grep.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && grep -rnE '\b(MeProvider|MeContext)\b|\buseMe\(' src --include='*.ts' --include='*.tsx' | grep -v 'src/me/MeContext.tsx'; echo "exit=$?"
```
기대: 출력 없음, `exit=1`(grep no-match). → dead 확정. **만약 한 줄이라도 출력되면 STOP** — 삭제 보류하고 호출처를 plan에 보고.

**Step 3.2** — `getMe`의 다른 소비처가 있어 client.ts는 살아있음을 재확인(MeContext 삭제가 getMe를 dead로 만들지 않음).
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && grep -rn "getMe" src --include='*.ts' --include='*.tsx' | grep -v 'src/me/MeContext.tsx'
```
기대: `src/api/client.ts:132:export const getMe = ...` + `src/pages/settings/AccountTab.tsx:6/54/83`(소비처 존재). → client.ts 보존 정당.

**Step 3.3** — 파일 삭제.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && git rm admin-ui/src/me/MeContext.tsx
```

**Step 3.4** — 불변식 검증(빌드/타입 무파손). MeContext.tsx가 어디에도 import 안 됐으므로 tsc/build가 깨지지 않아야 함.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && npx tsc -b 2>&1 | tail -3; echo "tsc_exit=$?"; npx vitest run 2>&1 | tail -4; npm run build 2>&1 | tail -3; echo "build_exit=$?"
```
기대: `tsc_exit=0`, `Tests  22 passed (22)`, `build_exit=0`. (전부 통과 = MeContext가 진짜 dead였다는 증명. 하나라도 실패하면 삭제가 부적절 → `git checkout` 으로 복구 후 호출처 조사.)

**Step 3.5** — commit.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && git add -A && git commit -m "refactor(admin-ui): dead MeContext/MeProvider/useMe 삭제

cq-dead-mecontext: grep 결과 실제 참조 0건(앱은 App.tsx 에서 api.get<Me>로
me 를 prop drilling). MeContext.tsx 만 삭제, client.ts getMe 는 AccountTab
가 사용하므로 보존. 동작/외관 불변.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4 — Sidebar audit-chain 폴링에 platform 가드 (`cq-sidebar-fetch-all-roles`)

### Files
- `admin-ui/src/shell/Sidebar.tsx`

### 배경 / 외관 불변 보장

- `const platform = isPlatform(me);` 이미 L85에 존재. `auditChainMonitorApi.overview`는 PLATFORM 엔드포인트 → RP_ADMIN은 30s마다 403 폴링 중. 가드로 불필요 요청만 제거.
- **외관 불변 핵심**: Footer(L173-201)는 `sidebarMode === 'labels'` && `!chain?.totals` → '확인 중…' 표시. RP에서 `chain`이 계속 null이어도 이미 '확인 중…'가 정상 표시되던 상태(가드 전 403으로 setChain 미호출 → chain null → '확인 중…'). 즉 **가드 전후 RP의 footer 표시가 동일**('확인 중…'). PLATFORM은 변화 없음(가드 통과).
- **Footer 자체는 platform-gate 안 함**(수집 note의 리스크): RP가 '확인 중…' footer를 계속 보는 것은 가드 도입 이전과 동일한 기존 동작이므로 **외관 불변 유지를 위해 footer render는 건드리지 않는다.** Footer를 `platform &&`로 숨기면 RP 외관이 바뀌므로 이번 Phase 범위 밖(spec §7 "외관·동작 불변"). 본 Task는 폴링 가드만.

### Steps

**Step 4.1** — 현재 폴링 effect(L109-120):
```tsx
  useEffect(() => {
    let cancelled = false;
    async function fetchChain() {
      try {
        const o = await auditChainMonitorApi.overview(24);
        if (!cancelled) setChain(o);
      } catch { /* 실패 시 carousel 정적 표시 유지 */ }
    }
    fetchChain();
    const id = setInterval(fetchChain, 30_000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);
```
다음으로 교체(early-return 가드 + deps에 `platform` 추가):
```tsx
  useEffect(() => {
    // audit-chain overview 는 PLATFORM 전용 엔드포인트.
    // RP_ADMIN 은 footer(AUDIT CHAIN)를 보지 않으므로 폴링하지 않는다
    // (RP 가 폴링하면 30초마다 403 — 외관엔 영향 없지만 불필요 요청).
    if (!platform) return;
    let cancelled = false;
    async function fetchChain() {
      try {
        const o = await auditChainMonitorApi.overview(24);
        if (!cancelled) setChain(o);
      } catch { /* 실패 시 carousel 정적 표시 유지 */ }
    }
    fetchChain();
    const id = setInterval(fetchChain, 30_000);
    return () => { cancelled = true; clearInterval(id); };
  }, [platform]);
```
근거: `platform`은 `me` 변경 시에만 바뀌고 `me`는 Sidebar의 안정 prop이므로 effect 재실행은 사실상 1회. PLATFORM은 가드 통과 → 기존 동작 동일. RP는 early-return → 폴링 안 함, `chain`은 null 유지 → footer '확인 중…' (가드 전과 동일).

**Step 4.2** — 불변식 검증: 기존 Sidebar.test.tsx 회귀 0. 이 테스트는 `auditChainMonitorApi.overview`를 `mockResolvedValue(null)`로 mock하고 PLATFORM/RP 두 케이스를 nav text로만 검증(L24-42). 가드 추가 후에도:
- PLATFORM 케이스: 폴링 통과 → overview mock 호출, nav text 동일 → PASS
- RP 케이스: 폴링 early-return → overview mock 미호출이나 테스트는 nav text('Tenants' 부재, '설정' 존재)만 검증 → PASS
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && npx vitest run src/shell/Sidebar.test.tsx 2>&1 | tail -6
```
기대: `Test Files  1 passed (1)` / `Tests  2 passed (2)`.

**Step 4.3** — 전체 게이트(tsc + 전체 vitest + build).
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && npx tsc -b 2>&1 | tail -3; echo "tsc_exit=$?"; npx vitest run 2>&1 | tail -4; npm run build 2>&1 | tail -3; echo "build_exit=$?"
```
기대: `tsc_exit=0`, `Tests  22 passed (22)`, `build_exit=0`.

**Step 4.4** — commit.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && git add admin-ui/src/shell/Sidebar.tsx && git commit -m "perf(admin-ui): Sidebar audit-chain 폴링 platform 가드

cq-sidebar-fetch-all-roles: overview 는 PLATFORM 전용 엔드포인트 — RP_ADMIN
의 30초 403 폴링 제거(if(!platform) return; deps [platform]). RP footer 는
가드 전과 동일하게 '확인 중…' 표시 → 외관·동작 불변. Footer render 는 미변경.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5 — ESLint(warn 레벨) 도입 (`cq-no-eslint`)

### Files
- `admin-ui/package.json` (scripts + devDependencies)
- `admin-ui/eslint.config.js` (신규, flat config ESM)

### 제약(spec §7)
- **warn 레벨로 시작** — 빌드/CI 게이트에 lint 미연결. 기존 `tsc -b`/`vitest`/`vite build` 게이트 전부 불변.
- **tsconfig의 `noUnusedLocals`/`noUnusedParameters` flip 안 함**(spec §7 "미사용 식별자가 많으면 이번엔 보류"). 이번 Phase에서 별도 검토.
- `"type":"module"`(package.json L5)이므로 flat config(`eslint.config.js`, ESM `export default`) 필수.

### Steps

**Step 5.1** — devDependencies 설치(정확한 dev 패키지 셋). lockfile 갱신 포함.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && npm install -D eslint@^9 typescript-eslint@^8 eslint-plugin-react-hooks@^5 eslint-plugin-jsx-a11y@^6 globals@^15 2>&1 | tail -8; echo "install_exit=$?"
```
기대: `install_exit=0`, package.json/package-lock.json 갱신. (eslint 9 = flat config 기본. typescript-eslint 8은 eslint 9 호환. 만약 peer 충돌로 실패하면 `--legacy-peer-deps` 재시도하고 plan에 표기.)

**Step 5.2** — `package.json` scripts에 `lint` 추가. 현재 L6-11:
```json
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "test": "vitest run"
  },
```
다음으로 교체(build는 절대 lint 의존 추가 안 함 — 게이트 불변):
```json
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "lint": "eslint src"
  },
```

**Step 5.3** — `admin-ui/eslint.config.js` 신규 작성(flat config, ESM, **warn 레벨**). 타입드 린팅은 비활성(속도/도입 마찰 최소화 — `projectService` 미사용), react-hooks/jsx-a11y 핵심 룰만 warn.
```js
// @ts-check
import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import globals from 'globals';

// 도입 단계: 모든 규칙 warn 레벨 — 빌드/CI 게이트에 미연결, 점진 정리용.
export default tseslint.config(
  { ignores: ['dist', 'node_modules', '*.config.js', '*.config.ts', '*.config.d.ts', 'src/test'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['src/**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: { ...globals.browser },
    },
    plugins: {
      'react-hooks': reactHooks,
      'jsx-a11y': jsxA11y,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      ...jsxA11y.configs.recommended.rules,
    },
  },
  {
    // 도입 단계 전역 격하: error → warn (빌드 안 깨짐)
    files: ['src/**/*.{ts,tsx}'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/no-unused-vars': 'warn',
      'no-empty': 'warn',
      'react-hooks/exhaustive-deps': 'warn',
      'react-hooks/rules-of-hooks': 'warn',
    },
  },
);
```
근거: `tseslint.config()`는 `js`/`tseslint`/플러그인 룰을 합성. 마지막 객체에서 자주 터지는 룰을 명시적으로 `warn`으로 강등 → `eslint src`가 비-0 종료해도 어떤 게이트도 안 깨짐(lint는 build/test에 미연결). `ignores`에 config/test 파일 제외.

**Step 5.4** — lint 실행이 **에러 없이 warn만** 내고 프로세스가 (warn은 exit 0) 정상 종료하는지 확인. eslint는 warning만 있으면 exit 0(`--max-warnings` 미지정 시).
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && npm run lint 2>&1 | tail -25; echo "lint_exit=${PIPESTATUS[0]}"
```
기대: warning 목록 출력 + `lint_exit=0`(error 0건 → exit 0). **만약 error로 분류된 룰이 남아 exit≠0이면**, 해당 룰 id를 Step 5.3의 강등 블록에 `'<rule-id>': 'warn'`으로 추가하고 재실행(코드 수정이 아니라 룰 레벨 조정으로 해소 — 도입 단계 원칙).

**Step 5.5** — 불변식 검증: ESLint 도입이 **기존 게이트를 안 바꿈** 확인. build/test/tsc는 lint와 무관하게 동일.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && npx tsc -b 2>&1 | tail -3; echo "tsc_exit=$?"; npx vitest run 2>&1 | tail -4; npm run build 2>&1 | tail -3; echo "build_exit=$?"
```
기대: `tsc_exit=0`, `Tests  22 passed (22)`, `build_exit=0`. (소스 코드 무변경 — config/package.json만 추가 → 런타임·표시값 불변.)

**Step 5.6** — commit.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && git add admin-ui/package.json admin-ui/package-lock.json admin-ui/eslint.config.js && git commit -m "chore(admin-ui): ESLint flat config 도입(warn 레벨)

cq-no-eslint: eslint + typescript-eslint + react-hooks + jsx-a11y flat
config(eslint.config.js, ESM). 'lint' 스크립트 추가. 전 규칙 warn 레벨로
시작 — build/test/tsc 게이트 미연결, 빌드 안 깨짐. tsconfig noUnusedLocals/
Parameters flip 은 별도 검토(이번 Phase 보류). 소스/표시값 불변.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6 — (선택) a11y: 클릭 가능 row 키보드 접근성 (`cq-a11y-clickable-rows`)

> **외관 불변 한정.** 시각적 변화(테두리/색/레이아웃/포커스 링 변경)가 한 픽셀이라도 생기면 **이 Task를 보류**하고 followups로 넘긴다. 본 Task는 `role`/`tabIndex`/`onKeyDown`만 추가(시각 무변경).

### Files
- `admin-ui/src/pages/tenant/AuditTab.tsx` (clickable `<tr onClick>` at L487)
- (동일 패턴 다른 clickable row 발견 시 동일 처리 — 단 Step 6.1에서 먼저 목록화)

### Steps

**Step 6.1** — 먼저 클릭 가능 row 후보를 정확히 식별(추측 금지). `onClick`이 달린 `<tr>`/카드 grep.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && grep -rnE '<tr[^>]*onClick' src --include='*.tsx'
```
기대: 최소 `src/pages/tenant/AuditTab.tsx:487`(`<tr key={i} onClick={() => setOpen(e)} style={{ cursor: 'pointer' }}>`). 출력된 각 위치만 대상으로 한다. **AuditTab은 단위 테스트가 없어 회귀 표면이 큼** — 표시값만 검증.

**Step 6.2** — AuditTab.tsx L487 `<tr>`에 키보드 핸들러/role 추가(시각 무변경). 현재:
```tsx
                <tr key={i} onClick={() => setOpen(e)} style={{ cursor: 'pointer' }}>
```
다음으로 교체:
```tsx
                <tr
                  key={i}
                  onClick={() => setOpen(e)}
                  onKeyDown={(ev) => {
                    if (ev.key === 'Enter' || ev.key === ' ') {
                      ev.preventDefault();
                      setOpen(e);
                    }
                  }}
                  role="button"
                  tabIndex={0}
                  style={{ cursor: 'pointer' }}
                >
```
근거: `style`/`className`/자식 무변경 → **렌더 외관 동일**. `role`/`tabIndex`/`onKeyDown`은 a11y 트리·키보드만 보강(시각 0). `' '`(Space) 기본 스크롤 방지 위해 `preventDefault`.

**Step 6.3** — 외관 불변 자가검증 결정 게이트. 변경이 시각적 산출을 바꾸는가? `style`/`className`/텍스트/자식 노드가 그대로이고 추가된 것은 비시각 속성(role/tabIndex/onKeyDown)뿐 → **외관 불변 충족**. (만약 jsx-a11y 룰이 요구하는 다른 변경이 시각에 영향 주면 그 부분은 적용하지 않는다.)

**Step 6.4** — 게이트.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && npx tsc -b 2>&1 | tail -3; echo "tsc_exit=$?"; npx vitest run 2>&1 | tail -4; npm run build 2>&1 | tail -3; echo "build_exit=$?"
```
기대: `tsc_exit=0`, `Tests  22 passed (22)`, `build_exit=0`.

**Step 6.5** — commit.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && git add admin-ui/src/pages/tenant/AuditTab.tsx && git commit -m "a11y(admin-ui): clickable audit row 키보드 접근성

cq-a11y-clickable-rows: <tr onClick> 에 role=button/tabIndex/onKeyDown(Enter,
Space) 추가. style/className/자식 무변경 → 시각 외관 불변, 키보드 접근성만 보강.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7 — (선택) Sidebar NavBtn memo + 인라인 prop (`perf-sidebar-not-memoized-inline-props`)

> **외관 불변 한정.** `React.memo`로 NavBtn을 감싸는 것은 렌더 출력 불변(memo는 동일 props면 동일 결과). 단 `onClick`이 매 렌더 새 함수면 memo 효과가 없으므로 `useCallback`이 필요한데, NavBtn의 onClick은 `() => onNavigate(...)` 인라인(L166/L168)이라 안정화하려면 변경 표면이 커진다. **memo 효과가 인라인 onClick 때문에 무의미하거나, 안정화가 외관/로직 흐름을 바꿀 위험이 있으면 이 Task 전체를 보류**하고 followups로 넘긴다.

### Files
- `admin-ui/src/shell/Sidebar.tsx`

### Steps

**Step 7.1** — 현재 NavBtn 정의(L50-73)와 사용처(L166, L168)를 재확인. 사용처는 `.map`으로 매 항목 `onClick={() => onNavigate({...})}` 인라인 함수 생성. → NavBtn을 `memo`해도 onClick prop이 매 렌더 새 reference라 memo가 리렌더를 막지 못함.

**Step 7.2** — 비용/효과 판단. NavBtn을 memo하려면 onClick을 useCallback으로 안정화해야 하는데, map 내부 인라인 클로저(`item.id`/`tenant.id` 캡처)는 useCallback으로 안정화하기 어렵다(항목별 다른 클로저). 안정화 시도는 로직 흐름을 바꿀 위험 + 코드 표면 확대. **결론: spec §7 "외관 바뀌면 보류" + minimal-impact 원칙에 따라 이 Task는 보류(NO-OP)**하고 아래 followups 항목으로 기록. memo만 추가하고 onClick은 그대로 둘 경우 perf 이득이 0이므로 변경 자체가 무의미(YAGNI).

**Step 7.3** — followups 기록(코드 변경 없음).
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && cat >> docs/superpowers/specs/followups-qw5.md <<'EOF'

## perf-sidebar-not-memoized-inline-props (보류 — QW-5 선택)
NavBtn React.memo 는 사용처(Sidebar.tsx:166/168)가 map 내부 인라인 onClick
클로저를 매 렌더 생성하므로 memo 효과 0. onClick 안정화(useCallback)는 항목별
클로저라 안정화가 어렵고 로직 흐름/외관 변경 리스크 > 이득. minimal-impact·
외관 불변 원칙에 따라 변경하지 않음. nav 항목 수가 폭증해 측정 가능한 병목이
되면 NavBtn 추출 + 항목 id 기반 단일 onSelect(id) 핸들러로 재설계 검토.
EOF
git add docs/superpowers/specs/followups-qw5.md && git commit -m "docs(qw5): perf-sidebar-not-memoized-inline-props 보류 근거 기록

선택 항목 — memo 효과가 인라인 onClick 때문에 0, 안정화는 외관/흐름 변경
리스크. minimal-impact 로 미변경, followups 기록.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8 — (선택) AuditTab payload stringify per-row 메모 (`perf-audittab-payload-stringify-per-row`)

> **외관 불변 한정.** 표 셀의 표시 문자열(`JSON.stringify(e.payload)`, L531)을 바이트 동일하게 유지해야 한다. 메모 방식이 동일 문자열을 내지 못하면 보류.

### Files
- `admin-ui/src/pages/tenant/AuditTab.tsx`

### 배경 (수집된 정확 위치)
- L531: `{JSON.stringify(e.payload)}` — `filtered.map((e,i) => ...)`(L486) 내부, 매 visible row마다 stringify.
- L460: `JSON.stringify(e.payload ?? {})` — CSV export 클릭 핸들러 내부(onClick 시 1회). **핫경로 아님 → 변경 불요**(클릭 시점에만 실행).
- L337: `JSON.stringify(event.payload, null, 2)` — PayloadDialog(단건 모달). 핫경로 아님 → 변경 불요.
- 따라서 대상은 **L531 1곳**(렌더마다 전체 row 반복 stringify).

### Steps

**Step 8.1** — L531 주변과 `filtered`/`items` 구조를 재확인(추측 금지). 메모 전략 결정용.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && grep -n "const filtered\|const items\|\.map((e" src/pages/tenant/AuditTab.tsx | head; sed -n '378,386p' src/pages/tenant/AuditTab.tsx
```
기대: L380 `const filtered = useMemo(() => {...}, [...])` 존재(이미 memo된 배열). L486 `filtered.map((e, i) => ...)`.

**Step 8.2** — 비용/효과·리스크 판단(외관 불변 우선). L531은 표시값이 `JSON.stringify(e.payload)`의 **정확한 출력 문자열**이며 ellipsis CSS로 잘려 보인다. 이를 메모하려면 `filtered`를 `useMemo`로 `{ ...e, payloadStr: JSON.stringify(e.payload) }` precompute하는 변형이 필요한데, 이는 (a) `filtered`의 형태(타입)를 바꿔 다른 소비처(L453 CSV `e.payload`, L487 `setOpen(e)` → PayloadDialog가 `event.payload` 사용)에 영향. payload를 보존한 채 추가 필드만 붙이면 타입 표면이 커지고 회귀 위험↑. **stringify 자체는 행당 한 번이고 `filtered`는 이미 memo되어 입력 변경 시에만 재렌더** → 실측 병목 근거가 약함. spec §7 "표시값 동일" + minimal-impact 기준으로 **보류(NO-OP)** 권장.

**Step 8.3** — followups 기록(코드 변경 없음).
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && cat >> docs/superpowers/specs/followups-qw5.md <<'EOF'

## perf-audittab-payload-stringify-per-row (보류 — QW-5 선택)
AuditTab.tsx:531 의 행당 JSON.stringify(e.payload) 는 filtered(이미 useMemo)
입력 변경 시에만 재실행되고, 메모하려면 filtered 항목 형태에 payloadStr 를
추가해야 해 CSV export(L460)·PayloadDialog(L337) 등 다른 소비처와 타입 표면이
얽힌다. 표시 문자열 바이트 보존 + 회귀 위험 대비 이득이 작아 미변경. payload 가
큰 테넌트에서 측정 병목이 확인되면 행 컴포넌트 추출 + React.memo + 사전 직렬화
필드로 재설계 검토.
EOF
git add docs/superpowers/specs/followups-qw5.md && git commit -m "docs(qw5): perf-audittab-payload-stringify-per-row 보류 근거 기록

선택 항목 — filtered 이미 memo, 행당 stringify 메모화는 타입 표면 확대 +
표시 문자열 회귀 리스크 > 이득. minimal-impact 로 미변경, followups 기록.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

> 주: Task 8을 **실제로 메모화하고 싶다면**(병목 근거가 생긴 경우), 표시값 바이트 동일을 보장하는 유일 안전 방식은 행 단위 컴포넌트(`AuditRow`)를 추출해 `React.memo`로 감싸고 그 안에서 `useMemo(() => JSON.stringify(e.payload), [e.payload])`로 셀 문자열을 캐시하는 것이다. 이때 L531 출력은 `JSON.stringify(e.payload)`와 정확히 동일해야 하며, 추출 전후 DOM 스냅샷이 같아야 한다. 외관/표시값이 바뀌면 즉시 롤백.

---

## 최종 통합 검증 (모든 Task 후)

**FV.1** — 전체 게이트 일괄 재실행(필수 Task 1-5 적용 후, 선택 Task는 적용분 포함).
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && echo "=== tsc ===" && npx tsc -b 2>&1 | tail -3; echo "tsc_exit=$?"; echo "=== vitest ===" && npx vitest run 2>&1 | tail -5; echo "=== build ===" && npm run build 2>&1 | tail -4; echo "build_exit=$?"; echo "=== lint(warn-only, 비게이트) ===" && npm run lint 2>&1 | tail -3; echo "lint_exit=${PIPESTATUS[0]}"
```
기대: `tsc_exit=0`, `Tests  22 passed (22)`, `build_exit=0`, lint는 warn만(또는 error 0 → exit 0). lint는 게이트 아님 — 참고용.

**FV.2** — 표시값 불변 최종 확인(diff 검토). 변경 파일이 전부 derived-value 메모/dead 삭제/폴링 가드/config이며 JSX 표시 노드(텍스트/style/className)는 무변경임을 git diff로 육안 확인.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && git diff main --stat -- admin-ui/ && echo "--- JSX 텍스트/style 변경 없음 확인(아래 출력이 비어야 정상: 표시 노드 변경 grep) ---" && git diff main -- admin-ui/src/pages/ActivityPage.tsx admin-ui/src/pages/TenantsListPage.tsx admin-ui/src/shell/Sidebar.tsx | grep -E '^[+-]' | grep -iE 'className|style=|<span|<div|toLocaleString|>.*[가-힣]' | grep -vE 'useMemo|^\+\+\+|^---' | head</parameter>
```
기대: ActivityPage/TenantsListPage/Sidebar diff에서 표시 텍스트/style/className 추가·삭제 라인 없음(메모 wrapping과 폴링 가드 주석만). Task 6 적용 시 AuditTab는 비시각 속성(role/tabIndex/onKeyDown)만 추가됨.

---

## Self-Review

### Spec coverage (이 Phase의 모든 finding id가 Task로 커버되는지)

| finding id | 필수/선택 | Task | 처리 |
|---|---|---|---|
| `perf-activity-unmemoized-filtering` | 필수 | Task 1 | filtered/failureCount/mutationCount useMemo, useMemo import 추가 ✅ |
| `perf-tenantslist-unmemoized-aggregates` | 필수 | Task 2 | 3 집계 useMemo([tenants]) (useMemo 기존 import) ✅ |
| `cq-dead-mecontext` | 필수 | Task 3 | MeContext.tsx 삭제(채택 안 함), client.ts getMe 보존 ✅ |
| `cq-sidebar-fetch-all-roles` | 필수 | Task 4 | `if(!platform) return` + deps `[platform]`, footer render 미변경 ✅ |
| `cq-no-eslint` | 필수 | Task 5 | flat config(ESM) + lint 스크립트 + devDeps, warn 레벨, 게이트 미연결 ✅ |
| `cq-a11y-clickable-rows` | 선택 | Task 6 | role/tabIndex/onKeyDown 추가(시각 무변경), 외관 바뀌면 보류 명시 ✅ |
| `perf-sidebar-not-memoized-inline-props` | 선택 | Task 7 | 보류(NO-OP) + followups 근거 — memo 효과 0/외관 리스크 ✅ |
| `perf-audittab-payload-stringify-per-row` | 선택 | Task 8 | 보류(NO-OP) + followups 근거 — 타입 표면/회귀 리스크 ✅ |

→ spec §7의 8개 finding 전부 Task로 매핑됨. tsconfig `noUnusedLocals`/`noUnusedParameters` flip은 spec §7 지시("미사용 식별자 많으면 보류")대로 이번 Phase 비포함(Task 5 commit 메시지에 명시).

### Placeholder scan
- "적절히 처리"/"TBD"/"테스트 추가" 류 placeholder 없음. 모든 코드 Step에 실제 before/after 코드 블록 + 실제 bash 명령 + 기대 출력 포함.
- 선택 Task 6은 실제 코드 변경; Task 7/8은 명시적 NO-OP(보류) + 근거 + 재설계 조건까지 기술(빈 약속 아님).

### Type 일관성
- Task 1: `useMemo` import 누락 → Step 1.2에서 명시 추가(수집 note 반영). `displayEvents`/`filtered`/count 타입 불변(`DisplayEvent[]`/`number`).
- Task 2: `useMemo` 이미 import(수집 확인) → import 변경 없음. `number` 타입 불변.
- Task 4: `platform: boolean`(L85), deps `[platform]` 타입 정합. `chain: ChainOverview | null` 불변.
- Task 5: flat config는 `"type":"module"`(L5)이라 ESM `export default` — 정합. `eslint.config.js` 확장자 `.js`(ESM로 해석됨).
- Task 6: `role`/`tabIndex: number`/`onKeyDown: KeyboardEvent handler` — `<tr>` 허용 props.

### 불변식 검증 커버리지
- 각 필수 Task(1-5)에 tsc(EXIT 0) + vitest(22 passed) + build(EXIT 0) Step 포함. Task 4는 Sidebar.test.tsx 회귀까지 개별 확인. 표시값 불변은 "derived value 1:1 동일"·"footer 표시 동일('확인 중…')"·"소스 무변경(config만)"으로 논증 + FV.2 diff 육안 게이트.
- 해시체인/서명키/와이어 응답: 본 Phase는 admin-ui 전용이라 해당 없음(백엔드 바이트 불변식은 QW-4 범위). admin-ui 표시값 불변이 이 Phase의 불변식.

### 리스크 노트
- Task 3 삭제는 Step 3.1 grep gate로 "실제 참조 0"을 재증명한 뒤에만 진행(한 줄이라도 나오면 STOP). Step 3.4 tsc/build 통과가 dead 증명.
- Task 5 eslint 9 / typescript-eslint 8 peer 충돌 가능성 → Step 5.1에 `--legacy-peer-deps` fallback 명시. lint는 어떤 게이트에도 연결 안 함 → 빌드 절대 안 깨짐.
- 선택 Task 6/7/8은 "외관 바뀌면 보류"를 각 Task 머리에 명시. Task 7/8은 minimal-impact 판단으로 사전 보류 결정 + followups 기록(코드 변경 0).