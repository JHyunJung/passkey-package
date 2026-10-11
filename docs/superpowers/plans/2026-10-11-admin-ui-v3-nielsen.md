# admin-ui V3(+닐슨) 1단계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** V3(+닐슨) 캔버스 디자인 중 현재 백엔드 API로 가능한 전부를 admin-ui 에 적용한다.

**Architecture:** 먼저 공통 패턴(`useLoad` 로드 상태 훅, `LoadError`, `Skeleton`, `ConfirmDialog` L1/L2/L3, 토스트 규칙, 포커스 토큰)을 `ui/`·`lib/` 에 만들고, 이후 셸 → 인증 → 플랫폼 → Tenant 탭 → 설정 → 다이얼로그·오버레이 순으로 각 화면이 이 패턴을 쓰도록 바꾼다. 백엔드는 건드리지 않는다.

**Tech Stack:** React 18, TypeScript, Vite, react-router-dom 6, Vitest 2 + @testing-library/react (jsdom), ESLint 9 (jsx-a11y).

**Spec:** `docs/superpowers/specs/2026-10-11-admin-ui-v3-nielsen-design.md`

## Global Constraints

- 작업 디렉터리: worktree `.claude/worktrees/admin-ui-v3-nielsen`, 브랜치 `feat/admin-ui-v3-nielsen`. 모든 명령은 `admin-ui/` 에서 실행.
- 백엔드(`admin-app/`, `core/`) 코드는 변경하지 않는다. 백엔드가 필요한 항목은 스펙 §8 의 1단계 처리 방식을 따른다.
- 디자인 시스템(색·타이포·컴포넌트 외형)은 V2 구현을 유지한다. 예외: 포커스 링 → `outline: 2px solid var(--brand-500); outline-offset: 2px`.
- 사용자 문구는 한국어. 용어: ceremony → "패스키 등록·인증", revoke/revoke됨 → "회수"/"회수됨", "mutation"·"audit log" 를 사용자 문구에 쓰지 않음, 원시 enum 은 라벨로(원문은 `title` 툴팁).
- 로딩 표시는 `Skeleton` 하나로 통일. `Loading…`·`불러오는 중…` 텍스트를 새로 쓰지 않는다.
- 로드 실패는 `LoadError` 로 표시. 실패를 빈 상태·기본값·무한 로딩으로 보이지 않게 한다. 실패 시 건수는 `—`, 내보내기·검색은 비활성.
- 오류 토스트는 자동으로 닫히지 않는다(`role="alert"`). 같은 오류를 인라인과 토스트 두 곳에 띄우지 않는다.
- 위험 동작 확인은 `ConfirmDialog` 의 L1/L2/L3 를 쓴다. 기본 포커스는 취소, 확인 버튼 라벨 = `verb`(제목의 동사).
- 하드코딩·가짜 수치(지표, 진행률)를 표시하지 않는다.
- 각 Task 의 커밋 전: `npx vitest run`(전체), `npx tsc -b`, `npx eslint src` 통과. 기존 테스트는 삭제하지 않고, 문구·동작이 바뀐 경우에만 새 기대값으로 고친다.
- 커밋 메시지는 `feat(admin-ui): …` / `fix(admin-ui): …` 한국어, 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **재시도 중 이전 데이터 사라짐** — `reload()` 중 화면이 스켈레톤으로 바뀌면 안 된다(이전 데이터 유지, `reloading` 만 true). Task 1 테스트로 고정.
2. **늦게 도착한 이전 요청 응답** — deps 가 바뀐 뒤 먼저 보낸 요청이 늦게 끝나면 새 데이터를 덮어쓰지 않아야 한다. Task 1 테스트로 고정.
3. **확인 다이얼로그 연타** — 처리 중 확인 버튼을 다시 눌러도 `onConfirm` 이 한 번만 호출돼야 한다. Task 4 테스트로 고정.
4. **확인 실패 후 다이얼로그 닫힘** — `onConfirm` 이 throw 하면 다이얼로그는 열린 채 인라인 오류를 보여야 한다(토스트 아님). Task 4 테스트로 고정.
5. **오류 토스트가 사라짐** — `kind: 'err'` 토스트는 시간이 지나도 남아 있어야 하고, `ok` 는 hover 중에는 사라지지 않아야 한다. Task 5 테스트로 고정.

---

### Task 0: 작업 환경 준비

**Files:** 없음 (의존성 설치만)

- [ ] **Step 1: 의존성 설치**

Run: `cd admin-ui && npm ci`
Expected: `node_modules/` 생성, 에러 없음.

- [ ] **Step 2: 기준선 확인**

Run: `npx vitest run && npx tsc -b && npx eslint src`
Expected: 모든 테스트 PASS, 타입·린트 에러 0. 실패가 있으면 이 계획을 진행하기 전에 보고한다.

---

### Task 1: `useLoad` 로드 상태 훅 + 오류 설명 헬퍼

**Files:**
- Create: `admin-ui/src/lib/useLoad.ts`
- Create: `admin-ui/src/lib/describeError.ts`
- Create: `admin-ui/src/lib/josa.ts`
- Test: `admin-ui/src/lib/useLoad.test.tsx`, `admin-ui/src/lib/describeError.test.ts`

**Interfaces:**
- Produces:
  - `type LoadStatus = 'loading' | 'ok' | 'failed'`
  - `type LoadState<T> = { status: LoadStatus; data: T | undefined; error: unknown; reloading: boolean; reload: () => void }`
  - `function useLoad<T>(fetcher: () => Promise<T>, deps: unknown[]): LoadState<T>`
  - `type ErrorInfo = { title: string; detail: string; httpStatus?: number; traceId?: string; code?: string }`
  - `function describeError(e: unknown): ErrorInfo`
  - `function eulReul(word: string): '을' | '를'`, `function iGa(word: string): '이' | '가'`

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/lib/useLoad.test.tsx`:
```tsx
import { describe, it, expect, vi } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useLoad } from './useLoad';

function deferred<T>() {
  let resolve!: (v: T) => void, reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

describe('useLoad', () => {
  it('처음에는 loading, 성공하면 ok + data', async () => {
    const { result } = renderHook(() => useLoad(() => Promise.resolve(3), []));
    expect(result.current.status).toBe('loading');
    await waitFor(() => expect(result.current.status).toBe('ok'));
    expect(result.current.data).toBe(3);
  });

  it('실패하면 failed + error', async () => {
    const err = new Error('boom');
    const { result } = renderHook(() => useLoad(() => Promise.reject(err), []));
    await waitFor(() => expect(result.current.status).toBe('failed'));
    expect(result.current.error).toBe(err);
  });

  it('reload 중에는 이전 data 를 유지하고 reloading=true', async () => {
    let n = 0;
    const d = deferred<number>();
    const fetcher = vi.fn(() => (n++ === 0 ? Promise.resolve(1) : d.promise));
    const { result } = renderHook(() => useLoad(fetcher, []));
    await waitFor(() => expect(result.current.data).toBe(1));
    act(() => result.current.reload());
    expect(result.current.status).toBe('ok');
    expect(result.current.data).toBe(1);
    expect(result.current.reloading).toBe(true);
    await act(async () => { d.resolve(2); });
    expect(result.current.data).toBe(2);
    expect(result.current.reloading).toBe(false);
  });

  it('재시도가 실패하면 failed 로 바뀌지만 이전 data 는 남는다', async () => {
    let n = 0;
    const fetcher = () => (n++ === 0 ? Promise.resolve(1) : Promise.reject(new Error('x')));
    const { result } = renderHook(() => useLoad(fetcher, []));
    await waitFor(() => expect(result.current.data).toBe(1));
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.status).toBe('failed'));
    expect(result.current.data).toBe(1);
  });

  it('deps 가 바뀐 뒤 늦게 끝난 이전 요청은 무시한다', async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    const { result, rerender } = renderHook(({ id }) => useLoad(() => (id === 'a' ? first.promise : second.promise), [id]), { initialProps: { id: 'a' } });
    rerender({ id: 'b' });
    await act(async () => { second.resolve('B'); });
    await act(async () => { first.resolve('A'); });
    expect(result.current.data).toBe('B');
  });
});
```

`admin-ui/src/lib/describeError.test.ts`:
```ts
import { describe, it, expect } from 'vitest';
import { describeError } from './describeError';
import { eulReul, iGa } from './josa';
import { ApiError } from '@/api/types';

describe('describeError', () => {
  it('5xx 는 서버 일시 오류', () => {
    const i = describeError(new ApiError(503, 'C999', 'HTTP 503'));
    expect(i.title).toBe('서버가 잠시 응답하지 않습니다');
    expect(i.httpStatus).toBe(503);
  });
  it('fetch 실패(TypeError)는 네트워크 오류', () => {
    expect(describeError(new TypeError('Failed to fetch')).title).toBe('네트워크에 연결할 수 없습니다');
  });
  it('403 은 권한 없음, 404 는 찾을 수 없음', () => {
    expect(describeError(new ApiError(403, 'A003', 'Forbidden')).title).toBe('권한이 없습니다');
    expect(describeError(new ApiError(404, 'C404', 'Not found')).title).toBe('찾을 수 없습니다');
  });
  it('그 밖의 ApiError 는 서버 메시지를 detail 로', () => {
    const i = describeError(new ApiError(400, 'V001', 'slug 형식이 올바르지 않습니다'));
    expect(i.title).toBe('요청을 처리하지 못했습니다');
    expect(i.detail).toBe('slug 형식이 올바르지 않습니다');
    expect(i.code).toBe('V001');
  });
});

describe('josa', () => {
  it('받침 유무로 을/를, 이/가', () => {
    expect(eulReul('목록')).toBe('을');
    expect(eulReul('정보')).toBe('를');
    expect(iGa('키')).toBe('가');
    expect(iGa('목록')).toBe('이');
  });
  it('한글이 아니면 을/이 기본', () => {
    expect(eulReul('Credential')).toBe('을');
    expect(iGa('API')).toBe('이');
  });
});
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `npx vitest run src/lib/useLoad.test.tsx src/lib/describeError.test.ts`
Expected: FAIL — `Failed to resolve import "./useLoad"` 등.

- [ ] **Step 3: 구현**

`admin-ui/src/lib/useLoad.ts`:
```ts
import { useCallback, useEffect, useRef, useState } from 'react';

export type LoadStatus = 'loading' | 'ok' | 'failed';
export type LoadState<T> = { status: LoadStatus; data: T | undefined; error: unknown; reloading: boolean; reload: () => void };

/** 비동기 로드를 loading/ok/failed 로 추적한다. 재시도 중에는 이전 data 를 유지하고, 늦게 끝난 이전 요청은 버린다. */
export function useLoad<T>(fetcher: () => Promise<T>, deps: unknown[]): LoadState<T> {
  const [status, setStatus] = useState<LoadStatus>('loading');
  const [data, setData] = useState<T | undefined>(undefined);
  const [error, setError] = useState<unknown>(undefined);
  const [reloading, setReloading] = useState(false);
  const seq = useRef(0);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  const run = useCallback((isReload: boolean) => {
    const my = ++seq.current;
    if (isReload) setReloading(true); else setStatus('loading');
    fetcherRef.current().then(
      (v) => { if (my !== seq.current) return; setData(v); setError(undefined); setStatus('ok'); setReloading(false); },
      (e) => { if (my !== seq.current) return; setError(e); setStatus('failed'); setReloading(false); },
    );
  }, []);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { run(false); }, deps);

  const reload = useCallback(() => run(true), [run]);
  return { status, data, error, reloading, reload };
}
```

`admin-ui/src/lib/josa.ts`:
```ts
function hasBatchim(word: string): boolean | null {
  const ch = word.trim().slice(-1);
  const code = ch.charCodeAt(0) - 0xac00;
  if (code < 0 || code > 11171) return null;
  return code % 28 !== 0;
}
/** 목적격 조사. 한글이 아니면 '을'. */
export const eulReul = (w: string): '을' | '를' => (hasBatchim(w) === false ? '를' : '을');
/** 주격 조사. 한글이 아니면 '이'. */
export const iGa = (w: string): '이' | '가' => (hasBatchim(w) === false ? '가' : '이');
```

`admin-ui/src/lib/describeError.ts`:
```ts
import { ApiError } from '@/api/types';

export type ErrorInfo = { title: string; detail: string; httpStatus?: number; traceId?: string; code?: string };

/** 사용자에게 보여 줄 쉬운 오류 문구. 원문은 detail 로만 남긴다. */
export function describeError(e: unknown): ErrorInfo {
  if (e instanceof ApiError) {
    const base = { httpStatus: e.httpStatus, traceId: e.traceId, code: e.code };
    if (e.httpStatus >= 500) return { ...base, title: '서버가 잠시 응답하지 않습니다', detail: '저장된 데이터는 그대로 있습니다. 잠시 후 다시 시도하세요.' };
    if (e.httpStatus === 403) return { ...base, title: '권한이 없습니다', detail: '이 작업을 할 권한이 없습니다. 운영자에게 문의하세요.' };
    if (e.httpStatus === 404) return { ...base, title: '찾을 수 없습니다', detail: '삭제되었거나 주소가 바뀌었을 수 있습니다.' };
    return { ...base, title: '요청을 처리하지 못했습니다', detail: e.serverMessage };
  }
  if (e instanceof TypeError) return { title: '네트워크에 연결할 수 없습니다', detail: '연결 상태를 확인한 뒤 다시 시도하세요.' };
  return { title: '알 수 없는 오류가 발생했습니다', detail: e instanceof Error ? e.message : String(e) };
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `npx vitest run src/lib/useLoad.test.tsx src/lib/describeError.test.ts`
Expected: PASS (11 tests)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/lib/useLoad.ts admin-ui/src/lib/useLoad.test.tsx admin-ui/src/lib/describeError.ts admin-ui/src/lib/describeError.test.ts admin-ui/src/lib/josa.ts
git commit -m "feat(admin-ui): useLoad 로드 상태 훅과 쉬운 오류 문구 헬퍼 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `LoadError` 로드 실패 화면

**Files:**
- Create: `admin-ui/src/ui/LoadError.tsx`
- Modify: `admin-ui/src/ui/index.ts` (export 추가)
- Test: `admin-ui/src/ui/LoadError.test.tsx`

**Interfaces:**
- Consumes: `describeError`, `eulReul` (Task 1), `illustrations.loadError` (`@/assets/illustrations`), `Button`, `copyToClipboard(text: string): Promise<boolean>` (`@/lib/clipboard`, 기존)
- Produces: `function LoadError(props: { what: string; error: unknown; onRetry: () => void; retrying?: boolean; compact?: boolean }): JSX.Element`

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/ui/LoadError.test.tsx`:
```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { LoadError } from './LoadError';
import { ApiError } from '@/api/types';

describe('LoadError', () => {
  it('role=alert 로 무엇을 못 불러왔는지 말하고 다시 시도를 호출한다', () => {
    const onRetry = vi.fn();
    render(<LoadError what="Credential 목록" error={new ApiError(503, 'C999', 'HTTP 503', undefined, 'tr-1')} onRetry={onRetry} />);
    const box = screen.getByRole('alert');
    expect(box).toHaveTextContent('Credential 목록을 불러오지 못했습니다');
    expect(box).toHaveTextContent('서버가 잠시 응답하지 않습니다');
    expect(box).toHaveTextContent('HTTP 503');
    expect(box).toHaveTextContent('tr-1');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
  it('받침 없는 대상은 "를"', () => {
    render(<LoadError what="시스템 정보" error={new Error('x')} onRetry={() => {}} />);
    expect(screen.getByRole('alert')).toHaveTextContent('시스템 정보를 불러오지 못했습니다');
  });
  it('retrying 중에는 버튼이 비활성 + "다시 시도 중…"', () => {
    render(<LoadError what="목록" error={new Error('x')} onRetry={() => {}} retrying />);
    expect(screen.getByRole('button', { name: '다시 시도 중…' })).toBeDisabled();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/ui/LoadError.test.tsx`
Expected: FAIL — `Failed to resolve import "./LoadError"`.

- [ ] **Step 3: 구현**

`admin-ui/src/ui/LoadError.tsx`:
```tsx
import { illustrations } from '@/assets/illustrations';
import { describeError } from '@/lib/describeError';
import { eulReul } from '@/lib/josa';
import { copyToClipboard } from '@/lib/clipboard';
import { Button } from './Button';

/** 로드 실패 공통 화면. 실패를 "없음"으로 보이지 않게 하고, 그 자리에서 다시 시도하게 한다. */
export function LoadError({ what, error, onRetry, retrying, compact }: { what: string; error: unknown; onRetry: () => void; retrying?: boolean; compact?: boolean }) {
  const info = describeError(error);
  const meta = [info.httpStatus ? `HTTP ${info.httpStatus}` : null, info.traceId ? `trace ${info.traceId}` : null].filter(Boolean).join(' · ');
  return (
    <div className="empty" role="alert" style={compact ? { padding: '20px 16px' } : undefined}>
      <img className="empty__art" src={illustrations.loadError} alt="" style={compact ? { width: 96, height: 96 } : undefined} />
      <p className="empty__title">{what}{eulReul(what)} 불러오지 못했습니다</p>
      <p className="empty__sub">{info.title}. {info.detail}</p>
      <div style={{ display: 'flex', gap: 8, justifyContent: 'center', flexWrap: 'wrap' }}>
        <Button variant="primary" onClick={onRetry} disabled={retrying}>{retrying ? '다시 시도 중…' : '다시 시도'}</Button>
        <Button variant="ghost" onClick={() => void copyToClipboard([what, info.title, info.detail, meta].filter(Boolean).join('\n'))}>오류 정보 복사</Button>
      </div>
      {meta && <p className="hint mono" style={{ margin: 0 }}>{meta}</p>}
    </div>
  );
}
```

`admin-ui/src/ui/index.ts` 끝에 추가:
```ts
export * from './LoadError';
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/ui/LoadError.test.tsx`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/ui/LoadError.tsx admin-ui/src/ui/LoadError.test.tsx admin-ui/src/ui/index.ts
git commit -m "feat(admin-ui): 로드 실패 공통 화면 LoadError 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `Skeleton` 로딩 표시

**Files:**
- Create: `admin-ui/src/ui/Skeleton.tsx`
- Modify: `admin-ui/src/ui/index.ts`, `admin-ui/src/styles/components.css` (`.skeleton-block` 클래스 — 기존 인라인 `.skeleton` 과 별개)
- Test: `admin-ui/src/ui/Skeleton.test.tsx`

**Interfaces:**
- Produces: `function Skeleton(props: { rows?: number; label?: string }): JSX.Element` — `role="status"`, `aria-busy="true"`, 시각적 줄 `rows`개(기본 4), 3초 뒤 "아직 불러오는 중입니다…" 표시. `label` 은 스크린리더용(기본 "불러오는 중").

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/ui/Skeleton.test.tsx`:
```tsx
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { Skeleton } from './Skeleton';

afterEach(() => vi.useRealTimers());

describe('Skeleton', () => {
  it('status 역할과 aria-busy, 줄 수', () => {
    const { container } = render(<Skeleton rows={3} />);
    const s = screen.getByRole('status');
    expect(s).toHaveAttribute('aria-busy', 'true');
    expect(container.querySelectorAll('.skeleton-block__line')).toHaveLength(3);
  });
  it('3초가 지나면 "아직 불러오는 중입니다…"', () => {
    vi.useFakeTimers();
    render(<Skeleton />);
    expect(screen.queryByText('아직 불러오는 중입니다…')).toBeNull();
    act(() => { vi.advanceTimersByTime(3000); });
    expect(screen.getByText('아직 불러오는 중입니다…')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/ui/Skeleton.test.tsx`
Expected: FAIL — `Failed to resolve import "./Skeleton"`.

- [ ] **Step 3: 구현**

`admin-ui/src/ui/Skeleton.tsx`:
```tsx
import { useEffect, useState } from 'react';

const WIDTHS = ['100%', '92%', '96%', '70%', '88%', '80%'];

/** 로딩 표시 통일. 3초가 넘으면 기다리는 중이라는 문구를 덧붙인다(도허티·H1). */
export function Skeleton({ rows = 4, label = '불러오는 중' }: { rows?: number; label?: string }) {
  const [slow, setSlow] = useState(false);
  useEffect(() => { const t = setTimeout(() => setSlow(true), 3000); return () => clearTimeout(t); }, []);
  return (
    <div className="skeleton-block" role="status" aria-busy="true" aria-label={label}>
      {Array.from({ length: rows }, (_, i) => <span key={i} className="skeleton-block__line" style={{ width: WIDTHS[i % WIDTHS.length] }} />)}
      {slow && <p className="hint" style={{ margin: 0 }}>아직 불러오는 중입니다…</p>}
    </div>
  );
}
```

`admin-ui/src/styles/components.css` 끝에 추가:
```css
/* ── skeleton ──────────────────────── */
.skeleton-block { display: flex; flex-direction: column; gap: 12px; padding: 20px; }
.skeleton-block__line { display: block; height: 10px; border-radius: 5px; background: #E1E7EF; }
```

`admin-ui/src/ui/index.ts` 끝에 추가:
```ts
export * from './Skeleton';
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/ui/Skeleton.test.tsx`
Expected: PASS (2 tests)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/ui/Skeleton.tsx admin-ui/src/ui/Skeleton.test.tsx admin-ui/src/ui/index.ts admin-ui/src/styles/components.css
git commit -m "feat(admin-ui): 로딩 표시를 통일할 Skeleton 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `ConfirmDialog` 위험 동작 확인 L1/L2/L3

**Files:**
- Create: `admin-ui/src/ui/ConfirmDialog.tsx`
- Modify: `admin-ui/src/ui/index.ts`
- Test: `admin-ui/src/ui/ConfirmDialog.test.tsx`

**Interfaces:**
- Consumes: `Dialog` (`initialFocusRef`, `role`, `footer`), `ConfirmByTyping`, `Alert`, `Button`, `describeError` (Task 1)
- Produces:
  ```ts
  type ConfirmLevel = 'L1' | 'L2' | 'L3';
  type ConfirmDialogProps = {
    open: boolean; onClose: () => void; level: ConfirmLevel;
    title: string;                 // 예: "김운영 님을 정지할까요?"
    verb: string;                  // 확인 버튼 라벨. 예: "정지"
    sub?: ReactNode;
    children?: ReactNode;          // 영향 요약(L1/L2) 또는 변경 비교(L2)
    danger?: boolean;              // 기본 true → danger-solid 버튼
    typeToConfirm?: { expected: string; label: ReactNode };  // L3: 입력 확인
    acknowledge?: string;          // L3: 체크 확인 라벨 (typeToConfirm 과 둘 중 하나)
    onConfirm: () => Promise<void>; // throw 하면 인라인 오류, 성공하면 onClose()
  };
  function ConfirmDialog(p: ConfirmDialogProps): JSX.Element | null
  ```
  호출자는 L1 성공 후 "되돌리기" 토스트를 직접 띄운다(Task 5 의 `action`).

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/ui/ConfirmDialog.test.tsx`:
```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { ConfirmDialog } from './ConfirmDialog';
import { ApiError } from '@/api/types';

const base = { open: true, onClose: vi.fn(), title: '김운영 님을 정지할까요?', verb: '정지' };

describe('ConfirmDialog', () => {
  it('alertdialog, 기본 포커스는 취소, 확인 버튼 라벨은 verb', () => {
    render(<ConfirmDialog {...base} level="L1" onConfirm={vi.fn().mockResolvedValue(undefined)} />);
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '취소' }));
    expect(screen.getByRole('button', { name: '정지' })).toBeEnabled();
  });

  it('성공하면 onConfirm 한 번 + onClose', async () => {
    const onClose = vi.fn();
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    render(<ConfirmDialog {...base} onClose={onClose} level="L1" onConfirm={onConfirm} />);
    fireEvent.click(screen.getByRole('button', { name: '정지' }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('처리 중에는 버튼이 "정지 중…"으로 비활성, 연타해도 한 번만 호출', async () => {
    let resolve!: () => void;
    const onConfirm = vi.fn(() => new Promise<void>((r) => { resolve = r; }));
    render(<ConfirmDialog {...base} level="L1" onConfirm={onConfirm} />);
    fireEvent.click(screen.getByRole('button', { name: '정지' }));
    const busy = screen.getByRole('button', { name: '정지 중…' });
    expect(busy).toBeDisabled();
    fireEvent.click(busy);
    expect(onConfirm).toHaveBeenCalledTimes(1);
    resolve();
  });

  it('실패하면 다이얼로그 안 인라인 오류, 닫히지 않음', async () => {
    const onClose = vi.fn();
    render(<ConfirmDialog {...base} onClose={onClose} level="L1" onConfirm={vi.fn().mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'))} />);
    fireEvent.click(screen.getByRole('button', { name: '정지' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('정지하지 못했습니다');
    expect(alert).toHaveTextContent('서버가 잠시 응답하지 않습니다');
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '정지' })).toBeEnabled();
  });

  it('L3 입력 확인: 일치해야 버튼이 켜진다', () => {
    render(<ConfirmDialog {...base} level="L3" verb="정지" typeToConfirm={{ expected: 'acme', label: 'slug 를 입력하세요' }} onConfirm={vi.fn().mockResolvedValue(undefined)} />);
    const btn = screen.getByRole('button', { name: '정지' });
    expect(btn).toBeDisabled();
    fireEvent.change(screen.getByLabelText('slug 를 입력하세요'), { target: { value: 'acme' } });
    expect(btn).toBeEnabled();
  });

  it('L3 체크 확인: 체크해야 버튼이 켜진다', () => {
    render(<ConfirmDialog {...base} level="L3" verb="저장" acknowledge="모든 등록이 차단된다는 것을 이해했습니다" onConfirm={vi.fn().mockResolvedValue(undefined)} />);
    const btn = screen.getByRole('button', { name: '저장' });
    expect(btn).toBeDisabled();
    fireEvent.click(screen.getByLabelText('모든 등록이 차단된다는 것을 이해했습니다'));
    expect(btn).toBeEnabled();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/ui/ConfirmDialog.test.tsx`
Expected: FAIL — `Failed to resolve import "./ConfirmDialog"`.

- [ ] **Step 3: 구현**

`admin-ui/src/ui/ConfirmDialog.tsx`:
```tsx
import { ReactNode, useEffect, useRef, useState } from 'react';
import { Dialog } from './Dialog';
import { Button } from './Button';
import { Alert } from './Alert';
import { ConfirmByTyping } from './ConfirmByTyping';
import { describeError } from '@/lib/describeError';

export type ConfirmLevel = 'L1' | 'L2' | 'L3';
export type ConfirmDialogProps = {
  open: boolean; onClose: () => void; level: ConfirmLevel;
  title: string; verb: string; sub?: ReactNode; children?: ReactNode; danger?: boolean;
  typeToConfirm?: { expected: string; label: ReactNode };
  acknowledge?: string;
  onConfirm: () => Promise<void>;
};

/** 위험 동작 확인 3등급(V3 가이드). 기본 포커스는 취소, 실패는 다이얼로그 안에서 보여 준다. */
export function ConfirmDialog({ open, onClose, level, title, verb, sub, children, danger = true, typeToConfirm, acknowledge, onConfirm }: ConfirmDialogProps) {
  const cancelRef = useRef<HTMLButtonElement>(null);
  const busyRef = useRef(false);
  const [busy, setBusy] = useState(false);
  const [typed, setTyped] = useState(false);
  const [acked, setAcked] = useState(false);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => { if (open) { setTyped(false); setAcked(false); setError(null); busyRef.current = false; setBusy(false); } }, [open]);

  const gateOk = level !== 'L3' || (typeToConfirm ? typed : acknowledge ? acked : true);

  async function confirm() {
    if (busyRef.current || !gateOk) return;
    busyRef.current = true; setBusy(true); setError(null);
    try { await onConfirm(); onClose(); }
    catch (e) { setError(e); }
    finally { busyRef.current = false; setBusy(false); }
  }

  const info = error ? describeError(error) : null;
  return (
    <Dialog open={open} onClose={busy ? () => {} : onClose} title={title} sub={sub} role="alertdialog" initialFocusRef={cancelRef} closeOnScrim={false}
      footer={<>
        <Button ref={cancelRef} onClick={onClose} disabled={busy}>취소</Button>
        <Button variant={danger ? 'danger-solid' : 'primary'} onClick={() => void confirm()} disabled={busy || !gateOk}>{busy ? `${verb} 중…` : verb}</Button>
      </>}>
      {info && <div role="alert"><Alert tone="bad" title={`${verb}하지 못했습니다`}>{info.title}. {info.detail}</Alert></div>}
      {children}
      {level === 'L3' && typeToConfirm && <ConfirmByTyping expected={typeToConfirm.expected} label={typeToConfirm.label} onMatchChange={setTyped} actionLabel={verb} />}
      {level === 'L3' && acknowledge && (
        <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13 }}>
          <input type="checkbox" checked={acked} onChange={(e) => setAcked(e.target.checked)} />{acknowledge}
        </label>
      )}
    </Dialog>
  );
}
```

`Button` 이 `ref` 를 받지 않으면(`forwardRef` 아님) 이 Task 에서 `admin-ui/src/ui/Button.tsx` 를 `forwardRef` 로 바꾼다:
```tsx
import { ButtonHTMLAttributes, ReactNode, forwardRef } from 'react';
import { cn } from '@/lib/cn';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'danger-solid';
export type ButtonSize = 'xs' | 'sm' | 'md' | 'lg';
export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; size?: ButtonSize; icon?: ReactNode };

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button({ variant = 'secondary', size = 'md', icon, className, children, type = 'button', ...rest }, ref) {
  return (
    <button ref={ref} type={type} className={cn('btn', variant !== 'secondary' && `btn--${variant}`, size !== 'md' && `btn--${size}`, className)} {...rest}>
      {icon}
      {children}
    </button>
  );
});
```

`admin-ui/src/ui/index.ts` 끝에 추가:
```ts
export * from './ConfirmDialog';
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/ui/ConfirmDialog.test.tsx src/ui/basics.test.tsx`
Expected: PASS (ConfirmDialog 6 tests + 기존 basics 전부)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/ui/ConfirmDialog.tsx admin-ui/src/ui/ConfirmDialog.test.tsx admin-ui/src/ui/Button.tsx admin-ui/src/ui/index.ts
git commit -m "feat(admin-ui): 위험 동작 확인 3등급 ConfirmDialog 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 토스트 규칙 — 오류는 남기고, 성공은 되돌리기

**Files:**
- Modify: `admin-ui/src/ui/ToastHost.tsx`
- Modify: `admin-ui/src/styles/components.css` (`.toast__action`)
- Test: `admin-ui/src/ui/ToastHost.test.tsx` (신규)

**Interfaces:**
- Produces: `ToastInput` 에 `action?: { label: string; onClick: () => void }` 추가. 동작: `kind: 'err'` → 자동 닫힘 없음, `role="alert"`; 그 외 → `duration ?? 5000` ms 후 닫힘, 마우스가 올라가 있는 동안 멈춤(나가면 처음부터 다시). `action` 버튼을 누르면 `onClick` 후 토스트 닫힘.

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/ui/ToastHost.test.tsx`:
```tsx
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { ToastHost, useToast, ToastInput } from './ToastHost';

afterEach(() => vi.useRealTimers());

function Push({ t }: { t: ToastInput }) {
  const toast = useToast();
  return <button onClick={() => toast(t)}>push</button>;
}
const setup = (t: ToastInput) => { render(<ToastHost><Push t={t} /></ToastHost>); fireEvent.click(screen.getByText('push')); };

describe('ToastHost', () => {
  it('오류 토스트는 role=alert 이고 시간이 지나도 남는다', () => {
    vi.useFakeTimers();
    setup({ kind: 'err', title: '저장하지 못했습니다' });
    expect(screen.getByRole('alert')).toHaveTextContent('저장하지 못했습니다');
    act(() => { vi.advanceTimersByTime(60000); });
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('성공 토스트는 5초 뒤 사라지지만 hover 중에는 남는다', () => {
    vi.useFakeTimers();
    setup({ kind: 'ok', title: '저장했습니다' });
    const t = screen.getByRole('status');
    fireEvent.mouseEnter(t);
    act(() => { vi.advanceTimersByTime(6000); });
    expect(screen.getByText('저장했습니다')).toBeInTheDocument();
    fireEvent.mouseLeave(t);
    act(() => { vi.advanceTimersByTime(5000); });
    expect(screen.queryByText('저장했습니다')).toBeNull();
  });

  it('action 버튼을 누르면 onClick 후 닫힌다', () => {
    const onClick = vi.fn();
    setup({ kind: 'ok', title: '정지했습니다', action: { label: '되돌리기', onClick } });
    fireEvent.click(screen.getByRole('button', { name: '되돌리기' }));
    expect(onClick).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('정지했습니다')).toBeNull();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/ui/ToastHost.test.tsx`
Expected: FAIL — 오류 토스트가 `role="status"` 라 `getByRole('alert')` 실패, `action` 버튼 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/ui/ToastHost.tsx` 전체를 아래로 교체:
```tsx
import { useState, createContext, useContext, useCallback, useEffect, useRef, ReactNode } from 'react';
import { Icons } from '@/icons/Icons';

export type ToastInput = {
  kind?: 'ok' | 'err' | 'warn';
  title?: string;
  message?: string;
  traceId?: string;
  duration?: number;
  action?: { label: string; onClick: () => void };
};

type ToastItem = ToastInput & { id: string };

const ToastCtx = createContext<((t: ToastInput) => void) | null>(null);

function Toast({ t, onClose }: { t: ToastItem; onClose: () => void }) {
  const sticky = t.kind === 'err';
  const [hover, setHover] = useState(false);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    if (sticky || hover) return;
    const h = setTimeout(() => closeRef.current(), t.duration ?? 5000);
    return () => clearTimeout(h);
  }, [sticky, hover, t.duration]);
  return (
    <div className="toast" role={sticky ? 'alert' : 'status'} onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}>
      <div className={`toast__icon toast__icon--${t.kind || 'ok'}`}>
        {t.kind === 'err' ? <Icons.X size={11} /> : t.kind === 'warn' ? <Icons.Alert size={11} /> : <Icons.Check size={11} />}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="toast__title">{t.title}</div>
        {t.message && <div className="toast__sub">{t.message}</div>}
        {t.traceId && <div className="toast__trace">traceId · {t.traceId}</div>}
      </div>
      {t.action && (
        <button type="button" className="btn btn--sm toast__action" onClick={() => { t.action!.onClick(); onClose(); }}>{t.action.label}</button>
      )}
      <button type="button" className="btn btn--ghost btn--xs" onClick={onClose} aria-label="닫기"><Icons.X size={12} /></button>
    </div>
  );
}

export function ToastHost({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const push = useCallback((t: ToastInput) => {
    const id = Math.random().toString(36).slice(2);
    setToasts((arr) => [...arr, { id, ...t }]);
  }, []);
  const close = (id: string) => setToasts((arr) => arr.filter((x) => x.id !== id));
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="toast-rack">
        {toasts.map((t) => <Toast key={t.id} t={t} onClose={() => close(t.id)} />)}
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

`admin-ui/src/styles/components.css` 의 toast 블록 끝(`.toast__trace` 다음 줄)에 추가:
```css
.toast__action { background: transparent; border-color: #5B6778; color: #fff; align-self: center; }
```

- [ ] **Step 4: 통과 + 기존 테스트 확인**

Run: `npx vitest run`
Expected: ToastHost 3 tests PASS. 기존 테스트 중 오류 토스트를 `role="status"` 로 찾던 것이 있으면 실패한다 — 그 테스트만 `getByRole('alert')` 로 고친다(테스트 삭제 금지). 다시 `npx vitest run` 전체 PASS.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/ui/ToastHost.tsx admin-ui/src/ui/ToastHost.test.tsx admin-ui/src/styles/components.css
git add -u admin-ui/src
git commit -m "feat(admin-ui): 오류 토스트는 자동으로 닫지 않고 성공 토스트에 되돌리기 동작 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 보이는 포커스 링

**Files:**
- Modify: `admin-ui/src/styles/tokens.css:23`
- Modify: `admin-ui/src/styles/components.css` (`:focus-visible`, `.input:focus`, `.switch input:focus-visible + .switch__track`, `.otp__cell:focus`)
- Test: `admin-ui/src/test/tokens.test.ts`

**Interfaces:**
- Produces: CSS 변수 `--focus-outline: 2px solid var(--brand-500)`, `--focus-offset: 2px`. `--focus-ring` 은 삭제.

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/test/tokens.test.ts` 의 `describe('V2 tokens', …)` 안 마지막에 추가:
```ts
  it('포커스 링은 2px 실선 outline 이고 흐린 box-shadow 링을 쓰지 않는다', () => {
    const css = tokens() + readFileSync(join(S, 'components.css'), 'utf8');
    expect(tokens()).toMatch(/--focus-outline:\s*2px solid var\(--brand-500\)/);
    expect(tokens()).toMatch(/--focus-offset:\s*2px/);
    expect(css).not.toMatch(/--focus-ring/);
    expect(css).toMatch(/:focus-visible\s*\{[^}]*outline:\s*var\(--focus-outline\)/);
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/test/tokens.test.ts`
Expected: FAIL — `--focus-outline` 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/styles/tokens.css` 23행 `--focus-ring: 0 0 0 3px rgba(30, 136, 216, .2);` 을 아래 두 줄로 교체:
```css
  --focus-outline: 2px solid var(--brand-500);
  --focus-offset: 2px;
```

`admin-ui/src/styles/components.css` 에서 네 곳을 교체:
```css
:focus-visible { outline: var(--focus-outline); outline-offset: var(--focus-offset); }
```
```css
.input:focus { outline: var(--focus-outline); outline-offset: 1px; border-color: var(--brand-500); }
```
```css
.switch input:focus-visible + .switch__track { outline: var(--focus-outline); outline-offset: var(--focus-offset); }
```
```css
.otp__cell:focus { outline: var(--focus-outline); outline-offset: 1px; border-color: var(--brand-500); }
```
(각 줄은 기존 줄의 선택자를 그대로 두고 선언부만 바꾼 것이다. `grep -n "focus-ring" src -r` 결과가 0이 될 때까지 남은 참조도 같은 방식으로 바꾼다.)

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/test/tokens.test.ts && grep -rn "focus-ring" src || echo "none"`
Expected: PASS, `none`.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/styles/tokens.css admin-ui/src/styles/components.css admin-ui/src/test/tokens.test.ts
git commit -m "fix(admin-ui): 포커스 링을 2px 실선으로 바꿔 키보드 위치가 보이게 함

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## 셸 · 인증

> 이 묶음은 스펙 §3(셸), §4(인증), §2 의 도움말 진입점을 다룬다. Task 1–6 의 `describeError`, `Button`(forwardRef), `ToastHost`(`err` = `role="alert"`, 자동 닫힘 없음)를 쓴다.
> `shell/Breadcrumb.tsx` 는 V3 와 같은 구조(마지막 항목 `aria-current="page"`)라 바꾸지 않는다. `lib/search.ts` 는 이미 tenant 이름·slug·id 만 찾으므로 그대로 두고, 상단 바 문구를 실제 범위에 맞춘다(Task 9).

---

### Task 7: 도움말 주소 상수 + OS별 단축키 표기 헬퍼

**Files:**
- Create: `admin-ui/src/config/links.ts`
- Create: `admin-ui/src/lib/platform.ts`
- Test: `admin-ui/src/lib/platform.test.ts`

**Interfaces:**
- Produces:
  - `const HELP_LINKS: { apiDocs: string; rpGuide: string; support: string }` — 기본값은 모두 `''`. 빈 값이면 그 항목을 숨긴다(스펙 §8 "문서·도움말 주소").
  - `function isMacPlatform(nav?: { platform?: string; userAgent?: string }): boolean`
  - `function paletteShortcutLabel(mac?: boolean): '⌘K' | 'Ctrl K'`

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/lib/platform.test.ts`:
```ts
import { describe, it, expect } from 'vitest';
import { isMacPlatform, paletteShortcutLabel } from './platform';

describe('platform', () => {
  it('Mac·iOS 는 Mac 으로 본다', () => {
    expect(isMacPlatform({ platform: 'MacIntel' })).toBe(true);
    expect(isMacPlatform({ platform: 'iPhone' })).toBe(true);
  });
  it('Windows·Linux 는 Mac 이 아니다', () => {
    expect(isMacPlatform({ platform: 'Win32' })).toBe(false);
    expect(isMacPlatform({ platform: 'Linux x86_64' })).toBe(false);
  });
  it('platform 이 비면 userAgent 로 판단', () => {
    expect(isMacPlatform({ platform: '', userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0)' })).toBe(true);
    expect(isMacPlatform({ platform: '', userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' })).toBe(false);
  });
  it('navigator 가 없으면 Mac 아님', () => {
    expect(isMacPlatform(undefined)).toBe(false);
  });
  it('팔레트 단축키 표기', () => {
    expect(paletteShortcutLabel(true)).toBe('⌘K');
    expect(paletteShortcutLabel(false)).toBe('Ctrl K');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/lib/platform.test.ts`
Expected: FAIL — `Failed to resolve import "./platform"`.

- [ ] **Step 3: 구현**

`admin-ui/src/lib/platform.ts`:
```ts
type NavLike = { platform?: string; userAgent?: string };

/** macOS·iOS 인지. navigator.platform 이 비어 있으면 userAgent 로 판단한다. */
export function isMacPlatform(nav: NavLike | undefined = typeof navigator === 'undefined' ? undefined : navigator): boolean {
  if (!nav) return false;
  return /Mac|iPhone|iPad|iPod/.test(nav.platform || nav.userAgent || '');
}

/** 명령 팔레트 단축키 표기(H4 플랫폼 관례). Mac 은 ⌘K, 그 밖은 Ctrl K. */
export function paletteShortcutLabel(mac: boolean = isMacPlatform()): '⌘K' | 'Ctrl K' {
  return mac ? '⌘K' : 'Ctrl K';
}
```

`admin-ui/src/config/links.ts`:
```ts
/**
 * 도움말 주소(스펙 §2 도움말 · §8 "문서·도움말 주소").
 * 서버 설정으로 받는 API 가 아직 없어 1단계는 빌드 상수로 둔다.
 * 값이 빈 문자열이면 그 항목(상단 바 도움말 메뉴, 로그인 화면 문의 링크)을 숨긴다 — 동작하지 않는 링크를 보여 주지 않는다.
 */
export const HELP_LINKS: { apiDocs: string; rpGuide: string; support: string } = {
  /** API 문서(새 창으로 연다) */
  apiDocs: '',
  /** RP 연동 가이드(새 창으로 연다) */
  rpGuide: '',
  /** 운영자 문의 — mailto: 또는 페이지 주소 */
  support: '',
};
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/lib/platform.test.ts && npx tsc -b && npx eslint src`
Expected: PASS (5 tests), 타입·린트 에러 0.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/config/links.ts admin-ui/src/lib/platform.ts admin-ui/src/lib/platform.test.ts
git commit -m "feat(admin-ui): 도움말 주소 상수와 OS별 단축키 표기 헬퍼 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 키보드 단축키 시트 (`?` 키)

**Files:**
- Create: `admin-ui/src/extras/ShortcutSheet.tsx`
- Create: `admin-ui/src/extras/useHelpShortcut.ts`
- Modify: `admin-ui/src/extras/useGoShortcuts.ts:7` (`isTyping` export)
- Modify: `admin-ui/src/App.tsx` (훅 연결, 시트 마운트, 팔레트 동작 `'shortcuts'`)
- Test: `admin-ui/src/extras/ShortcutSheet.test.tsx`

**Interfaces:**
- Consumes: `Dialog`, `Button` (`@/ui`), `GO_SHORTCUTS` (`./useGoShortcuts`), `isMacPlatform` (Task 7)
- Produces:
  - `type ShortcutRow = { keys: string; alt?: string; desc: string }`
  - `function shortcutRows(opts: { mac: boolean; platform: boolean }): ShortcutRow[]` — G 이동 단축키는 `platform`(PLATFORM_OPERATOR)일 때만(현재 `useGoShortcuts` 가 PLATFORM 에서만 켜짐).
  - `function ShortcutSheet(props: { open: boolean; onClose: () => void; platform: boolean }): JSX.Element | null`
  - `function useHelpShortcut(onOpen: () => void): void` — `?` 키(수정키 없음)로 호출. 입력 중(input/textarea/select/contenteditable)이거나 `aria-modal="true"` 다이얼로그(비밀값·위험 확인 `alertdialog` 포함)가 열려 있으면 무시.
  - `function isTyping(t: EventTarget | null): boolean` (`useGoShortcuts.ts` 에서 export)
  - `App.tsx`: `AuthenticatedApp` 안에 `openSheet: () => void` 콜백. Task 9 가 이것을 `Header` 의 `onOpenShortcuts` 로 넘긴다. 팔레트 `onAction('shortcuts')` 도 시트를 연다(팔레트 항목 추가는 §7 묶음).

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/extras/ShortcutSheet.test.tsx`:
```tsx
import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useState } from 'react';
import { ShortcutSheet, shortcutRows } from './ShortcutSheet';
import { useHelpShortcut } from './useHelpShortcut';

// App.tsx 의 AuthenticatedApp 과 같은 조합: 훅이 시트를 연다.
function Harness({ platform = true }: { platform?: boolean }) {
  const [open, setOpen] = useState(false);
  useHelpShortcut(() => setOpen(true));
  return (
    <>
      <input aria-label="검색어" />
      <ShortcutSheet open={open} onClose={() => setOpen(false)} platform={platform} />
    </>
  );
}

describe('ShortcutSheet', () => {
  it('? 키로 열리고, 닫기 버튼으로 닫힌다', () => {
    render(<Harness />);
    fireEvent.keyDown(window, { key: '?' });
    const dlg = screen.getByRole('dialog', { name: '키보드 단축키' });
    expect(dlg).toHaveTextContent('명령 팔레트 열기');
    expect(dlg).toHaveTextContent('Tenants 목록으로 이동');
    expect(dlg).toHaveTextContent('이 단축키 목록');
    expect(dlg).toHaveTextContent('다이얼로그 · 메뉴 닫기');
    expect(dlg).toHaveTextContent('입력란에 포커스가 있으면 G · ? 단축키는 동작하지 않습니다.');
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '닫기' }));
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('입력란에서 ? 를 치면 열리지 않는다', () => {
    render(<Harness />);
    fireEvent.keyDown(screen.getByLabelText('검색어'), { key: '?' });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('alertdialog(비밀값·위험 확인)가 열려 있으면 열리지 않는다', () => {
    render(<><Harness /><div role="alertdialog" aria-modal="true" aria-label="발급된 키" /></>);
    fireEvent.keyDown(window, { key: '?' });
    expect(screen.queryByRole('dialog', { name: '키보드 단축키' })).toBeNull();
  });

  it('Ctrl·⌘·Alt 와 함께 누르면 무시', () => {
    render(<Harness />);
    fireEvent.keyDown(window, { key: '?', ctrlKey: true });
    fireEvent.keyDown(window, { key: '?', metaKey: true });
    fireEvent.keyDown(window, { key: '?', altKey: true });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('RP 관리자에게는 G 이동 단축키를 보여 주지 않는다', () => {
    const rows = shortcutRows({ mac: false, platform: false });
    expect(rows.map((r) => r.keys)).not.toContain('G T');
    expect(rows[0]).toMatchObject({ keys: 'Ctrl K', desc: '명령 팔레트 열기' });
  });

  it('OS 에 맞는 팔레트 단축키를 먼저, 다른 OS 는 보조 표기', () => {
    expect(shortcutRows({ mac: true, platform: true })[0]).toMatchObject({ keys: '⌘ K', alt: 'Windows · Ctrl K' });
    expect(shortcutRows({ mac: false, platform: true })[0]).toMatchObject({ keys: 'Ctrl K', alt: 'Mac · ⌘ K' });
    expect(shortcutRows({ mac: true, platform: true }).map((r) => r.keys)).toEqual(expect.arrayContaining(['G T', 'G A', 'G C', 'G S']));
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/extras/ShortcutSheet.test.tsx`
Expected: FAIL — `Failed to resolve import "./ShortcutSheet"`.

- [ ] **Step 3: 구현**

`admin-ui/src/extras/useGoShortcuts.ts` 7행:
```ts
function isTyping(t: EventTarget | null): boolean {
```
을 아래로 교체(본문은 그대로):
```ts
export function isTyping(t: EventTarget | null): boolean {
```

`admin-ui/src/extras/useHelpShortcut.ts`:
```ts
import { useEffect } from 'react';
import { isTyping } from './useGoShortcuts';

/**
 * `?` 로 키보드 단축키 시트를 연다(H10 도움말 진입점).
 * 입력 중이거나 모달(발급 키·복구 코드·위험 확인 alertdialog 포함)이 열려 있으면 무시한다 —
 * 비밀값 다이얼로그 위에 다른 다이얼로그를 띄우지 않는다.
 */
export function useHelpShortcut(onOpen: () => void) {
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key !== '?' || e.metaKey || e.ctrlKey || e.altKey || e.repeat) return;
      if (isTyping(e.target)) return;
      if (document.querySelector('[aria-modal="true"]')) return;
      e.preventDefault();
      onOpen();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onOpen]);
}
```

`admin-ui/src/extras/ShortcutSheet.tsx`:
```tsx
import { Button, Dialog } from '@/ui';
import { isMacPlatform } from '@/lib/platform';
import { GO_SHORTCUTS } from './useGoShortcuts';

export type ShortcutRow = { keys: string; alt?: string; desc: string };

/** 실제로 동작하는 단축키만 나열한다. G 이동은 PLATFORM 운영자에게만 켜져 있다(useGoShortcuts). */
export function shortcutRows({ mac, platform }: { mac: boolean; platform: boolean }): ShortcutRow[] {
  const rows: ShortcutRow[] = [
    { keys: mac ? '⌘ K' : 'Ctrl K', alt: mac ? 'Windows · Ctrl K' : 'Mac · ⌘ K', desc: '명령 팔레트 열기' },
  ];
  if (platform) {
    rows.push(
      { keys: GO_SHORTCUTS.tenants, desc: 'Tenants 목록으로 이동' },
      { keys: GO_SHORTCUTS.activity, desc: '활동으로 이동' },
      { keys: GO_SHORTCUTS['audit-chain'], desc: '감사 체인으로 이동' },
      { keys: GO_SHORTCUTS.settings, desc: '설정으로 이동' },
    );
  }
  rows.push(
    { keys: '?', desc: '이 단축키 목록' },
    { keys: '← →', desc: '탭 사이 이동' },
    { keys: 'Enter', desc: '표에서 선택한 행 열기' },
    { keys: 'Esc', desc: '다이얼로그 · 메뉴 닫기' },
  );
  return rows;
}

/** 키보드 단축키 시트. `?` 키와 상단 바 도움말 메뉴에서 연다. */
export function ShortcutSheet({ open, onClose, platform }: { open: boolean; onClose: () => void; platform: boolean }) {
  const rows = shortcutRows({ mac: isMacPlatform(), platform });
  return (
    <Dialog open={open} onClose={onClose} title="키보드 단축키"
      footerStart={<span className="hint" style={{ margin: 0 }}>입력란에 포커스가 있으면 G · ? 단축키는 동작하지 않습니다.</span>}
      footer={<Button onClick={onClose}>닫기</Button>}>
      <table className="table">
        <caption className="sr-only">키보드 단축키 목록</caption>
        <tbody>
          {rows.map((r) => (
            <tr key={r.desc}>
              <td style={{ width: 180 }}>
                <kbd>{r.keys}</kbd>
                {r.alt && <span className="hint" style={{ margin: '0 0 0 8px' }}>{r.alt}</span>}
              </td>
              <td>{r.desc}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Dialog>
  );
}
```

> 참고: "← → 탭 사이 이동"은 스펙 §6 Tenant 공통 `Tabs` ←/→ 키 이동 Task 에서 구현된다. 이 계획을 끝까지 실행하면 시트의 모든 행이 실제 동작과 일치한다.

`admin-ui/src/App.tsx` 수정:

12–13행
```tsx
import { useGoShortcuts } from '@/extras/useGoShortcuts';
import { usePaletteShortcut } from '@/extras/usePaletteShortcut';
```
을 아래로 교체:
```tsx
import { useGoShortcuts } from '@/extras/useGoShortcuts';
import { usePaletteShortcut } from '@/extras/usePaletteShortcut';
import { useHelpShortcut } from '@/extras/useHelpShortcut';
import { ShortcutSheet } from '@/extras/ShortcutSheet';
```

96행 `useGoShortcuts(setRoute, isPlatform(me));` 바로 아래에 추가:
```tsx

  // ? → 키보드 단축키 시트 (입력 중·모달 열림이면 무시)
  const [sheetOpen, setSheetOpen] = useState(false);
  const openSheet = useCallback(() => setSheetOpen(true), []);
  useHelpShortcut(openSheet);
```

101–104행 `paletteAction`
```tsx
  function paletteAction(kind: string) {
    if (kind === 'logout') onLogout();
    if (kind === 'new-tenant') setRoute({ name: 'tenants' });
  }
```
을 아래로 교체:
```tsx
  function paletteAction(kind: string) {
    if (kind === 'logout') onLogout();
    if (kind === 'new-tenant') setRoute({ name: 'tenants' });
    if (kind === 'shortcuts') openSheet();
  }
```

135–141행 `<CommandPalette … />` 바로 아래에 추가:
```tsx
      <ShortcutSheet open={sheetOpen} onClose={() => setSheetOpen(false)} platform={isPlatform(me)} />
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/extras && npx vitest run && npx tsc -b && npx eslint src`
Expected: ShortcutSheet 6 tests PASS, 기존 `useGoShortcuts.test.tsx`·`usePaletteShortcut.test.tsx` 포함 전체 PASS.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/extras/ShortcutSheet.tsx admin-ui/src/extras/ShortcutSheet.test.tsx admin-ui/src/extras/useHelpShortcut.ts admin-ui/src/extras/useGoShortcuts.ts admin-ui/src/App.tsx
git commit -m "feat(admin-ui): ? 키로 여는 키보드 단축키 시트 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 상단 바 — 검색 문구·OS별 단축키, 도움말 메뉴, RP 전환 실패 알림

**Files:**
- Modify: `admin-ui/src/shell/Header.tsx` (전체 교체)
- Modify: `admin-ui/src/App.tsx:114-119` (`onOpenShortcuts` 전달)
- Test: `admin-ui/src/shell/Header.test.tsx` (전체 교체 — 기존 2개 테스트는 새 기대값으로 고쳐 유지)

**Interfaces:**
- Consumes: `HELP_LINKS`, `paletteShortcutLabel` (Task 7), `openSheet` (Task 8), `describeError` (Task 1), `useToast` (Task 5: `err` 는 `role="alert"`)
- Produces: `HeaderProps` 에 `onOpenShortcuts: () => void` 추가(필수). 도움말 메뉴: `role="menu" aria-label="도움말"`, 항목 "API 문서 열기"(`HELP_LINKS.apiDocs`), "RP 연동 가이드"(`rpGuide`), "키보드 단축키"(`aria-keyshortcuts="?"`), "운영자에게 문의"(`support`). 주소가 빈 항목은 렌더하지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/shell/Header.test.tsx` 전체를 아래로 교체:
```tsx
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, within, cleanup } from '@testing-library/react';

const links = vi.hoisted(() => ({ apiDocs: '', rpGuide: '', support: '' }));
vi.mock('@/config/links', () => ({ HELP_LINKS: links }));
vi.mock('@/api/activeTenant', () => ({ activeTenantApi: { get: vi.fn().mockResolvedValue(null), switch: vi.fn() } }));

import { activeTenantApi } from '@/api/activeTenant';
import { ApiError } from '@/api/types';
import { ToastHost } from '@/ui';
import { Header } from './Header';

type Props = Parameters<typeof Header>[0];
function renderHeader(over: Partial<Props> = {}) {
  const props: Props = {
    me: { role: 'PLATFORM_OPERATOR', email: 'a@x.com' },
    onLogout: vi.fn(), breadcrumb: [], onOpenPalette: vi.fn(), onOpenShortcuts: vi.fn(),
    ...over,
  };
  render(<ToastHost><Header {...props} /></ToastHost>);
  return props;
}

afterEach(() => { Object.assign(links, { apiDocs: '', rpGuide: '', support: '' }); });

describe('Header V3', () => {
  it('브레드크럼 마지막 항목은 현재 위치, 검색 버튼은 실제 검색 범위를 말하고 팔레트를 연다', () => {
    const p = renderHeader({ breadcrumb: [{ label: 'Tenants', onClick: () => {} }, { label: 'Acme' }] });
    expect(screen.getByText('Acme')).toHaveAttribute('aria-current', 'page');
    const search = screen.getByRole('button', { name: /검색/ });
    expect(search).toHaveTextContent('tenant 이름 · slug 검색');
    expect(search).not.toHaveTextContent('credential');
    fireEvent.click(search);
    expect(p.onOpenPalette).toHaveBeenCalled();
    expect(screen.getByText('PLATFORM')).toBeInTheDocument();
  });

  it('검색 단축키 표기는 OS 별: Mac ⌘K, 그 밖 Ctrl K', () => {
    const spy = vi.spyOn(navigator, 'platform', 'get').mockReturnValue('MacIntel');
    renderHeader();
    expect(screen.getByRole('button', { name: /검색/ }).querySelector('kbd')).toHaveTextContent('⌘K');
    cleanup();
    spy.mockReturnValue('Win32');
    renderHeader();
    expect(screen.getByRole('button', { name: /검색/ }).querySelector('kbd')).toHaveTextContent('Ctrl K');
    spy.mockRestore();
  });

  it('사용자 메뉴는 로그아웃만 — 동작하지 않는 역할 전환(데모)은 없다. 도움말은 별도 메뉴', () => {
    const p = renderHeader();
    expect(screen.getByRole('button', { name: /도움말/ })).toHaveAttribute('aria-haspopup', 'menu');
    fireEvent.click(screen.getByRole('button', { name: '사용자 메뉴' }));
    expect(screen.getAllByRole('menuitem').map((m) => m.textContent)).toEqual(['로그아웃']);
    fireEvent.click(screen.getByRole('menuitem', { name: '로그아웃' }));
    expect(p.onLogout).toHaveBeenCalled();
  });

  it('도움말: 주소가 비어 있으면 문서 항목을 숨기고 키보드 단축키만, 누르면 시트를 연다', () => {
    const p = renderHeader();
    fireEvent.click(screen.getByRole('button', { name: /도움말/ }));
    const menu = screen.getByRole('menu', { name: '도움말' });
    expect(within(menu).getAllByRole('menuitem')).toHaveLength(1);
    const item = within(menu).getByRole('menuitem', { name: '키보드 단축키' });
    expect(item).toHaveAttribute('aria-keyshortcuts', '?');
    expect(document.activeElement).toBe(item);
    fireEvent.click(item);
    expect(p.onOpenShortcuts).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu', { name: '도움말' })).toBeNull();
  });

  it('도움말: 주소가 있으면 API 문서·RP 연동 가이드는 새 창, 운영자 문의 링크', () => {
    Object.assign(links, { apiDocs: 'https://docs.example/api', rpGuide: 'https://docs.example/rp', support: 'mailto:ops@example.com' });
    renderHeader();
    fireEvent.click(screen.getByRole('button', { name: /도움말/ }));
    const apiDocs = screen.getByRole('menuitem', { name: /API 문서 열기/ });
    expect(apiDocs).toHaveAttribute('href', 'https://docs.example/api');
    expect(apiDocs).toHaveAttribute('target', '_blank');
    expect(apiDocs).toHaveAttribute('rel', 'noopener noreferrer');
    expect(apiDocs).toHaveTextContent('새 창');
    expect(screen.getByRole('menuitem', { name: /RP 연동 가이드/ })).toHaveAttribute('href', 'https://docs.example/rp');
    expect(screen.getByRole('menuitem', { name: '운영자에게 문의' })).toHaveAttribute('href', 'mailto:ops@example.com');
  });

  it('RP 전환이 실패하면 오류 토스트를 띄우고 화면은 그대로 둔다', async () => {
    vi.mocked(activeTenantApi.get).mockResolvedValueOnce({
      activeTenantId: 't1', allowedTenantIds: ['t1', 't2'],
      allowedTenants: [{ id: 't1', name: 'Acme' }, { id: 't2', name: 'Beta' }],
    });
    vi.mocked(activeTenantApi.switch).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    const onRpSwitch = vi.fn();
    renderHeader({ me: { role: 'RP_ADMIN', email: 'b@x.com' }, onRpSwitch });
    fireEvent.click(await screen.findByRole('button', { name: /Acme/ }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Beta' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('RP를 전환하지 못했습니다');
    expect(alert).toHaveTextContent('서버가 잠시 응답하지 않습니다');
    expect(alert).toHaveTextContent('Acme');
    expect(onRpSwitch).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/shell/Header.test.tsx`
Expected: FAIL — 검색 문구가 `tenant, credential, audit ID 검색…`, `도움말` 버튼 없음, RP 전환 실패 시 `alert` 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/shell/Header.tsx` 전체를 아래로 교체:
```tsx
import { KeyboardEvent, ReactNode, useEffect, useRef, useState } from 'react';
import { Icons } from '@/icons/Icons';
import { Breadcrumb } from './Breadcrumb';
import { activeTenantApi, type ActiveTenantView } from '@/api/activeTenant';
import { Badge, useToast } from '@/ui';
import { HELP_LINKS } from '@/config/links';
import { paletteShortcutLabel } from '@/lib/platform';
import { describeError } from '@/lib/describeError';

function useOutsideClose(open: boolean, close: () => void) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const h = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) close(); };
    const k = (e: globalThis.KeyboardEvent) => { if (e.key === 'Escape') close(); };
    document.addEventListener('mousedown', h); document.addEventListener('keydown', k);
    return () => { document.removeEventListener('mousedown', h); document.removeEventListener('keydown', k); };
  }, [open, close]);
  return ref;
}

/** 메뉴 안 ↑/↓ 이동. */
function moveFocus(root: HTMLElement | null, e: KeyboardEvent<HTMLDivElement>) {
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
  const els = Array.from(root?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
  if (els.length === 0) return;
  e.preventDefault();
  const i = els.indexOf(document.activeElement as HTMLElement);
  const next = e.key === 'ArrowDown' ? (i + 1) % els.length : (i <= 0 ? els.length - 1 : i - 1);
  els[next]?.focus();
}

function RpSwitcher({ onSwitch }: { onSwitch: () => void }) {
  const [active, setActive] = useState<ActiveTenantView | null>(null);
  const [open, setOpen] = useState(false);
  const [switching, setSwitching] = useState(false);
  const ref = useOutsideClose(open, () => setOpen(false));
  const toast = useToast();
  useEffect(() => { activeTenantApi.get().then(setActive).catch(() => {}); }, []);
  if (!active || active.allowedTenantIds.length < 2) return null;
  const names: Record<string, string> = {};
  active.allowedTenants.forEach((t) => { names[t.id] = t.name; });
  const label = active.activeTenantId ? (names[active.activeTenantId] ?? active.activeTenantId.slice(-8)) : '—';
  async function pick(id: string) {
    if (id === active?.activeTenantId) { setOpen(false); return; }
    setSwitching(true);
    try {
      await activeTenantApi.switch(id);
      setOpen(false);
      onSwitch();
    } catch (e) {
      // 실패를 삼키지 않는다(H9). 서버는 활성 RP 를 바꾸지 않았으므로 화면도 그대로다.
      const info = describeError(e);
      setOpen(false);
      toast({ kind: 'err', title: 'RP를 전환하지 못했습니다', message: `${info.title}. 지금 보고 있는 RP(${label})는 그대로입니다.`, traceId: info.traceId });
    } finally {
      setSwitching(false);
    }
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

/** 도움말 진입점(H10). 주소가 빈 항목은 숨긴다(config/links.ts). */
function HelpMenu({ onOpenShortcuts }: { onOpenShortcuts: () => void }) {
  const [open, setOpen] = useState(false);
  const ref = useOutsideClose(open, () => setOpen(false));
  useEffect(() => {
    if (open) ref.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
  }, [open, ref]);
  const external = (href: string, label: string) => (
    <a role="menuitem" className="menu__item" href={href} target="_blank" rel="noopener noreferrer" onClick={() => setOpen(false)}>
      {label}
      <span className="hint" style={{ margin: '0 0 0 auto', display: 'inline-flex', alignItems: 'center', gap: 4 }}>새 창 <Icons.ExternalLink size={13} /></span>
    </a>
  );
  return (
    <div className="menu-wrap" ref={ref} onKeyDown={(e) => { if (open) moveFocus(ref.current, e); }}>
      <button type="button" className="btn" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
        <Icons.Help size={16} />도움말
      </button>
      {open && (
        <div className="menu" role="menu" aria-label="도움말" style={{ minWidth: 220 }}>
          {HELP_LINKS.apiDocs && external(HELP_LINKS.apiDocs, 'API 문서 열기')}
          {HELP_LINKS.rpGuide && external(HELP_LINKS.rpGuide, 'RP 연동 가이드')}
          <button type="button" role="menuitem" className="menu__item" aria-keyshortcuts="?" onClick={() => { setOpen(false); onOpenShortcuts(); }}>
            키보드 단축키<kbd aria-hidden="true" style={{ marginLeft: 'auto' }}>?</kbd>
          </button>
          {HELP_LINKS.support && (
            <>
              <div className="menu__sep" />
              <a role="menuitem" className="menu__item" href={HELP_LINKS.support} onClick={() => setOpen(false)}>운영자에게 문의</a>
            </>
          )}
        </div>
      )}
    </div>
  );
}

type HeaderProps = {
  me: { role: string; email: string; displayName?: string };
  onLogout: () => void;
  breadcrumb: { label: ReactNode; onClick?: () => void }[];
  onOpenPalette: () => void;
  onOpenShortcuts: () => void;
  onRpSwitch?: () => void;
};

export function Header({ me, onLogout, breadcrumb, onOpenPalette, onOpenShortcuts, onRpSwitch }: HeaderProps) {
  const [open, setOpen] = useState(false);
  const ref = useOutsideClose(open, () => setOpen(false));
  const name = me.displayName ?? me.email;
  const platform = me.role === 'PLATFORM_OPERATOR';
  const shortcut = paletteShortcutLabel();
  return (
    <header className="topbar">
      <Breadcrumb items={breadcrumb} />
      <div className="spacer" />
      {me.role === 'RP_ADMIN' && <RpSwitcher onSwitch={onRpSwitch ?? (() => window.location.reload())} />}
      {/* 문구는 실제 검색 범위(lib/search.ts: tenant 이름·slug)만 약속한다(H2). */}
      <button type="button" className="topbar__search" onClick={onOpenPalette} aria-label={`tenant 이름 · slug 검색 (${shortcut})`}>
        <Icons.Search size={15} /><span>tenant 이름 · slug 검색</span><kbd>{shortcut}</kbd>
      </button>
      <HelpMenu onOpenShortcuts={onOpenShortcuts} />
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
            <button type="button" role="menuitem" className="menu__item menu__item--danger" onClick={() => { setOpen(false); onLogout(); }}><Icons.LogOut size={14} />로그아웃</button>
          </div>
        )}
      </div>
    </header>
  );
}
```

`admin-ui/src/App.tsx` 114–119행
```tsx
          <Header
            me={me}
            onLogout={onLogout}
            breadcrumb={breadcrumb}
            onOpenPalette={() => setPaletteOpen(true)}
          />
```
을 아래로 교체:
```tsx
          <Header
            me={me}
            onLogout={onLogout}
            breadcrumb={breadcrumb}
            onOpenPalette={() => setPaletteOpen(true)}
            onOpenShortcuts={openSheet}
          />
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/shell/Header.test.tsx && npx vitest run && npx tsc -b && npx eslint src`
Expected: Header 6 tests PASS, 전체 PASS.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/shell/Header.tsx admin-ui/src/shell/Header.test.tsx admin-ui/src/App.tsx
git commit -m "feat(admin-ui): 상단 바 검색 문구를 실제 범위로, OS별 단축키 표기, 도움말 메뉴, RP 전환 실패 알림

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: 사이드바 — 감사 체인 카드 4상태, 메뉴 배지 "위변조 N"

**Files:**
- Modify: `admin-ui/src/shell/Sidebar.tsx` (전체 교체)
- Modify: `admin-ui/src/styles/components.css:62` 다음 줄 (`.chain-card--warn`)
- Test: `admin-ui/src/shell/Sidebar.test.tsx` (전체 교체 — 기존 3개 테스트는 새 기대값으로 고쳐 유지)

**Interfaces:**
- Consumes: `auditChainMonitorApi.overview(24)`, `kstTime(iso): string` (`@/lib/kst`), `Button` (`@/ui`)
- Produces: 체인 카드 상태 `'checking' | 'ok' | 'unknown'`(+ ok 중 위변조). 문구:
  - 검증 중: 제목 "검증 중…", 부제 "[N]초째 확인 중"
  - 정상: "AUDIT CHAIN OK", "마지막 검증 · [시간 전] · [N] 행"
  - 위변조: "위변조 의심 · [N] tenant", "즉시 확인이 필요합니다"
  - 확인 불가: "검증 상태 확인 불가", "서버 응답이 없습니다 · [HH:MM] 마지막 확인"(성공한 적 없으면 "서버 응답이 없습니다"), "다시 시도" 버튼
  - 감사 체인 메뉴 배지: "위변조 [N]"

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/shell/Sidebar.test.tsx` 전체를 아래로 교체:
```tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import type { Me } from '@/api/types';

const overview = vi.fn();
vi.mock('@/api/auditChainMonitor', () => ({ auditChainMonitorApi: { overview: (...a: unknown[]) => overview(...a) } }));
vi.mock('@/api/license', () => ({ licenseApi: { get: vi.fn().mockResolvedValue({ deploymentMode: 'saas' }) } }));

import { Sidebar } from './Sidebar';

const platform: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const rp: Me = { email: 'b@x.com', role: 'RP_ADMIN', tenantIds: ['tid-123'], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const okOverview = (tampered = 0) => ({
  verifiedAt: new Date().toISOString(), windowHours: 24, bucketSizeMinutes: 60,
  totals: { tenantsIntact: 5 - tampered, tenantsTotal: 5, tenantsTampered: tampered, verifiedRows: 1234, verificationMs: 10 },
  tenants: [],
});

beforeEach(() => overview.mockReset());
afterEach(() => vi.useRealTimers());

describe('Sidebar V3', () => {
  it('PLATFORM: 플랫폼/관리 그룹과 4개 항목, 활성 aria-current, 첫 조회 중에는 "검증 중…" + 경과 초', () => {
    vi.useFakeTimers();
    overview.mockReturnValue(new Promise(() => {}));
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={() => {}} />);
    expect(screen.getByText('플랫폼')).toBeInTheDocument();
    expect(screen.getByText('관리')).toBeInTheDocument();
    for (const l of ['Tenants', '활동', '감사 체인', '설정']) expect(screen.getByRole('button', { name: new RegExp(l) })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Tenants/ })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByText('검증 중…')).toBeInTheDocument();
    expect(screen.getByText('0초째 확인 중')).toBeInTheDocument();
    act(() => { vi.advanceTimersByTime(3000); });
    expect(screen.getByText('3초째 확인 중')).toBeInTheDocument();
    // 환경 배지는 서버 profile 을 읽는 클라이언트 API 가 없으므로 하드코딩하지 않는다.
    expect(screen.queryByText('prod')).toBeNull();
  });

  it('RP_ADMIN: 설정만, 체인 카드 없음, 체인 조회도 안 함', () => {
    render(<Sidebar me={rp} currentRoute={{ name: 'settings' }} onNavigate={() => {}} />);
    expect(screen.queryByText('Tenants')).toBeNull();
    expect(screen.queryByText('플랫폼')).toBeNull();
    expect(screen.getByRole('button', { name: /설정/ })).toBeInTheDocument();
    expect(screen.queryByText(/AUDIT CHAIN|검증 중/)).toBeNull();
    expect(overview).not.toHaveBeenCalled();
  });

  it('정상: AUDIT CHAIN OK + 마지막 검증 · 행 수, 메뉴 배지 없음', async () => {
    overview.mockResolvedValue(okOverview(0));
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={() => {}} />);
    expect(await screen.findByText('AUDIT CHAIN OK')).toBeInTheDocument();
    expect(screen.getByText('마지막 검증 · 방금 · 1,234 행')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /감사 체인/ })).not.toHaveTextContent('위변조');
  });

  it('위변조가 있으면 감사 체인 항목에 "위변조 N" 배지와 빨간 카드, 카드를 누르면 감사 체인으로', async () => {
    overview.mockResolvedValue(okOverview(1));
    const onNavigate = vi.fn();
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={onNavigate} />);
    expect(await screen.findByText('위변조 의심 · 1 tenant')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /감사 체인/ })).toHaveTextContent('위변조 1');
    fireEvent.click(screen.getByText('위변조 의심 · 1 tenant'));
    expect(onNavigate).toHaveBeenCalledWith({ name: 'audit-chain' });
  });

  it('조회가 실패하면 "검증 상태 확인 불가" + 다시 시도, 다시 시도가 성공하면 정상', async () => {
    overview.mockRejectedValueOnce(new Error('503')).mockResolvedValueOnce(okOverview(0));
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={() => {}} />);
    expect(await screen.findByText('검증 상태 확인 불가')).toBeInTheDocument();
    expect(screen.getByText('서버 응답이 없습니다')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(screen.getByText('검증 중…')).toBeInTheDocument();
    expect(await screen.findByText('AUDIT CHAIN OK')).toBeInTheDocument();
    expect(overview).toHaveBeenCalledTimes(2);
  });

  it('성공 뒤 30초 폴링이 실패하면 마지막 확인 시각(KST)을 보여 준다', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-11T01:02:00Z'));
    overview.mockResolvedValueOnce(okOverview(0)).mockRejectedValueOnce(new Error('503'));
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={() => {}} />);
    await act(async () => {});
    expect(screen.getByText('AUDIT CHAIN OK')).toBeInTheDocument();
    await act(async () => { vi.advanceTimersByTime(30_000); });
    expect(screen.getByText('검증 상태 확인 불가')).toBeInTheDocument();
    expect(screen.getByText('서버 응답이 없습니다 · 10:02 마지막 확인')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/shell/Sidebar.test.tsx`
Expected: FAIL — "검증 중…"·"검증 상태 확인 불가" 없음, 배지가 `1`(위변조 문구 없음).

- [ ] **Step 3: 구현**

`admin-ui/src/shell/Sidebar.tsx` 전체를 아래로 교체:
```tsx
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Icons } from '@/icons/Icons';
import { auditChainMonitorApi, type ChainOverview } from '@/api/auditChainMonitor';
import { licenseApi } from '@/api/license';
import { isPlatform } from '@/me/roles';
import { cn } from '@/lib/cn';
import { kstTime } from '@/lib/kst';
import { Button } from '@/ui';
import type { Me } from '@/api/types';
import type { AppRoute } from '@/appRoute';

type TopRoute = 'tenants' | 'activity' | 'audit-chain' | 'settings' | 'license';
type Item = { id: TopRoute; label: string; icon: keyof typeof Icons; tag?: string };
type ChainStatus = 'checking' | 'ok' | 'unknown';

export function timeAgo(iso: string): string {
  const mins = Math.floor((Date.now() - new Date(iso).getTime()) / 60_000);
  if (mins < 1) return '방금';
  if (mins < 60) return `${mins}분 전`;
  return `${Math.floor(mins / 60)}시간 전`;
}

export function Sidebar({ me, currentRoute, onNavigate }: { me: Me; currentRoute: AppRoute; onNavigate: (r: AppRoute) => void }) {
  const platform = isPlatform(me);
  const [chain, setChain] = useState<ChainOverview | null>(null);
  const [chainStatus, setChainStatus] = useState<ChainStatus>('checking');
  const [lastCheckedAt, setLastCheckedAt] = useState<string | null>(null);
  const [checkStart, setCheckStart] = useState(() => Date.now());
  const [now, setNow] = useState(() => Date.now());
  const [mode, setMode] = useState<'saas' | 'onprem' | null>(null);
  const alive = useRef(true);

  useEffect(() => { licenseApi.get().then((v) => setMode(v.deploymentMode)).catch(() => setMode('saas')); }, []);

  // 실패를 "확인 중"으로 감추지 않는다(H1·H9): 실패하면 확인 불가 + 다시 시도.
  const loadChain = useCallback((showChecking: boolean) => {
    if (showChecking) { const t = Date.now(); setChainStatus('checking'); setCheckStart(t); setNow(t); }
    auditChainMonitorApi.overview(24)
      .then((o) => {
        if (!o?.totals) throw new Error('empty overview');
        if (!alive.current) return;
        setChain(o);
        setLastCheckedAt(new Date().toISOString());
        setChainStatus('ok');
      })
      .catch(() => { if (alive.current) setChainStatus('unknown'); });
  }, []);

  useEffect(() => {
    if (!platform) return; // RP 는 PLATFORM 전용 엔드포인트를 폴링하지 않는다
    alive.current = true;
    loadChain(false);
    const id = setInterval(() => loadChain(false), 30_000);
    return () => { alive.current = false; clearInterval(id); };
  }, [platform, loadChain]);

  // 검증 중일 때만 경과 초를 갱신한다.
  useEffect(() => {
    if (chainStatus !== 'checking') return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [chainStatus]);

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
  const checkingSecs = Math.max(0, Math.floor((now - checkStart) / 1000));

  return (
    <aside className="sidebar" aria-label="주 메뉴">
      <div className="sidebar__brand">
        <img src="/admin/crosscert-logo-transparent.png" alt="CROSSCERT" />
        <div className="sidebar__env">Passkey Admin</div>
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
                {it.id === 'audit-chain' && tampered > 0 && <span className="nav-item__count">위변조 {tampered}</span>}
              </button>
            );
          })}
        </div>
      ))}
      <div className="sidebar__foot">
        {platform && (chainStatus === 'unknown' ? (
          <div className="chain-card chain-card--warn" role="status">
            <span className="chain-card__dot" />
            <span className="stack-1" style={{ gap: 4 }}>
              <span className="chain-card__title">검증 상태 확인 불가</span>
              <span className="chain-card__sub">{lastCheckedAt ? `서버 응답이 없습니다 · ${kstTime(lastCheckedAt)} 마지막 확인` : '서버 응답이 없습니다'}</span>
              <Button size="sm" onClick={() => loadChain(true)} style={{ alignSelf: 'flex-start' }}>다시 시도</Button>
            </span>
          </div>
        ) : (
          <button type="button" className={cn('chain-card', chainStatus === 'checking' && 'chain-card--pending', chainStatus === 'ok' && tampered > 0 && 'chain-card--bad')} onClick={() => onNavigate({ name: 'audit-chain' })}>
            <span className="chain-card__dot" />
            <span className="stack-1" style={{ gap: 2 }}>
              {chainStatus === 'checking' ? (<><span className="chain-card__title">검증 중…</span><span className="chain-card__sub">{checkingSecs}초째 확인 중</span></>)
                : tampered > 0 ? (<><span className="chain-card__title">위변조 의심 · {tampered} tenant</span><span className="chain-card__sub">즉시 확인이 필요합니다</span></>)
                : chain ? (<><span className="chain-card__title">AUDIT CHAIN OK</span><span className="chain-card__sub">마지막 검증 · {timeAgo(chain.verifiedAt)} · {chain.totals.verifiedRows.toLocaleString()} 행</span></>)
                : null}
            </span>
          </button>
        ))}
        <span className="sidebar__copy">© Crosscert</span>
      </div>
    </aside>
  );
}
```

`admin-ui/src/styles/components.css` 62행 `.chain-card--pending { … }` 다음 줄에 추가:
```css
.chain-card--warn { border-color: var(--warn-line); background: var(--warn-soft); cursor: default; }
.chain-card--warn .chain-card__dot { background: var(--warn-dot); }
.chain-card--warn .chain-card__title { color: var(--warn); }
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/shell/Sidebar.test.tsx && npx vitest run && npx tsc -b && npx eslint src`
Expected: Sidebar 6 tests PASS, 전체 PASS.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/shell/Sidebar.tsx admin-ui/src/shell/Sidebar.test.tsx admin-ui/src/styles/components.css
git commit -m "feat(admin-ui): 사이드바 체인 카드에 검증 중·확인 불가 상태와 다시 시도, 메뉴 배지를 '위변조 N'으로

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: `loginForm` 이 `{ ok, status }` 를 반환 + 일시 오류 판별 헬퍼

**Files:**
- Modify: `admin-ui/src/api/client.ts:129-144`
- Create: `admin-ui/src/lib/transientError.ts`
- Modify: `admin-ui/src/pages/LoginPage.tsx:59-60` (새 반환형에 맞춤 — 동작은 그대로)
- Modify: `admin-ui/src/pages/LoginPage.test.tsx:52,73` (mock 반환값)
- Test: `admin-ui/src/api/client.test.ts` (신규), `admin-ui/src/lib/transientError.test.ts` (신규)

**Interfaces:**
- Produces:
  - `api.loginForm(email: string, password: string): Promise<{ ok: boolean; status: number }>` — 네트워크 오류는 fetch 의 `TypeError` 를 그대로 던진다.
  - `function isTransientError(e: unknown): boolean` — `TypeError`(fetch 실패) 또는 `ApiError.httpStatus >= 500`. 인증 화면이 "일시 오류 · 다시 시도"와 "계정 존재 비노출 동일 안내"를 가르는 기준.

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/api/client.test.ts`:
```ts
import { describe, it, expect, vi, afterEach } from 'vitest';
import { api } from './client';

afterEach(() => vi.unstubAllGlobals());

describe('api.loginForm', () => {
  it('성공이면 { ok: true, status: 200 }, 폼 인코딩으로 /admin/login 에 보낸다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(api.loginForm('a@x.com', 'pw')).resolves.toEqual({ ok: true, status: 200 });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/admin/login');
    expect(init.method).toBe('POST');
    expect(init.body).toBe('email=a%40x.com&password=pw');
  });

  it('401·503 은 ok=false 와 상태 코드를 그대로', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
    await expect(api.loginForm('a@x.com', 'x')).resolves.toEqual({ ok: false, status: 401 });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 503 })));
    await expect(api.loginForm('a@x.com', 'x')).resolves.toEqual({ ok: false, status: 503 });
  });

  it('네트워크 오류는 TypeError 로 던진다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    await expect(api.loginForm('a@x.com', 'x')).rejects.toBeInstanceOf(TypeError);
  });
});
```

`admin-ui/src/lib/transientError.test.ts`:
```ts
import { describe, it, expect } from 'vitest';
import { isTransientError } from './transientError';
import { ApiError } from '@/api/types';

describe('isTransientError', () => {
  it('fetch 실패(TypeError)와 5xx 는 일시 오류', () => {
    expect(isTransientError(new TypeError('Failed to fetch'))).toBe(true);
    expect(isTransientError(new ApiError(500, 'C999', 'HTTP 500'))).toBe(true);
    expect(isTransientError(new ApiError(503, 'C999', 'HTTP 503'))).toBe(true);
  });
  it('4xx 와 일반 Error 는 일시 오류가 아니다', () => {
    expect(isTransientError(new ApiError(400, 'C400', 'bad'))).toBe(false);
    expect(isTransientError(new ApiError(404, 'C404', 'nf'))).toBe(false);
    expect(isTransientError(new Error('boom'))).toBe(false);
    expect(isTransientError(null)).toBe(false);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/api/client.test.ts src/lib/transientError.test.ts`
Expected: FAIL — `loginForm` 이 `true`/`false` 를 반환(`toEqual({ ok: true, status: 200 })` 불일치), `Failed to resolve import "./transientError"`.

- [ ] **Step 3: 구현**

`admin-ui/src/lib/transientError.ts`:
```ts
import { ApiError } from '@/api/types';

/**
 * 네트워크 오류(fetch 의 TypeError) 또는 5xx — 요청이 처리되지 않았을 수 있어 "잠시 후 다시 시도"로 안내할 실패.
 * 비밀번호 찾기·가입 요청은 이 경우만 실패로 보여 주고, 4xx 는 계정 존재를 드러내지 않도록 성공과 같은 안내를 쓴다.
 */
export function isTransientError(e: unknown): boolean {
  if (e instanceof TypeError) return true;
  return e instanceof ApiError && e.httpStatus >= 500;
}
```

`admin-ui/src/api/client.ts` 129–144행
```ts
  loginForm: async (email: string, password: string) => {
    const csrf = getCookie('XSRF-TOKEN');
    ...
    return res.ok;
  },
```
을 아래로 교체:
```ts
  /**
   * 폼 로그인. 성공 여부와 HTTP 상태를 돌려준다 — 화면이 5xx(일시 오류)를 자격 증명 오류와 구분한다.
   * 423/429 잠김 구분은 백엔드 2단계(스펙 §8). 네트워크 오류는 fetch 의 TypeError 를 그대로 던진다.
   */
  loginForm: async (email: string, password: string): Promise<{ ok: boolean; status: number }> => {
    const csrf = getCookie('XSRF-TOKEN');
    const form = new URLSearchParams();
    form.set('email', email);
    form.set('password', password);
    const res = await fetch('/admin/login', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        ...(csrf ? { 'X-XSRF-TOKEN': csrf } : {}),
      },
      credentials: 'include',
      body: form.toString(),
    });
    return { ok: res.ok, status: res.status };
  },
```

`admin-ui/src/pages/LoginPage.tsx` 59–60행
```tsx
      const ok = await api.loginForm(email, password);
      if (!ok) {
```
을 아래로 교체(화면 동작 변경은 Task 12):
```tsx
      const { ok } = await api.loginForm(email, password);
      if (!ok) {
```

`admin-ui/src/pages/LoginPage.test.tsx` 52행
```tsx
    vi.mocked(api.loginForm).mockResolvedValue(false);
```
→
```tsx
    vi.mocked(api.loginForm).mockResolvedValue({ ok: false, status: 401 });
```
73행
```tsx
    vi.mocked(api.loginForm).mockResolvedValue(true);
```
→
```tsx
    vi.mocked(api.loginForm).mockResolvedValue({ ok: true, status: 200 });
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/api/client.test.ts src/lib/transientError.test.ts src/pages/LoginPage.test.tsx && npx vitest run && npx tsc -b && npx eslint src`
Expected: 신규 5 tests PASS, LoginPage 기존 테스트 PASS, 전체 PASS.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/api/client.ts admin-ui/src/api/client.test.ts admin-ui/src/lib/transientError.ts admin-ui/src/lib/transientError.test.ts admin-ui/src/pages/LoginPage.tsx admin-ui/src/pages/LoginPage.test.tsx
git commit -m "feat(admin-ui): loginForm 이 HTTP 상태를 함께 반환하고 일시 오류 판별 헬퍼 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: 로그인 — 가짜 지표 제거, 인라인 오류 하나, 일시 오류 구분, 이메일 자동 포커스

**Files:**
- Modify: `admin-ui/src/pages/LoginPage.tsx` (전체 교체)
- Test: `admin-ui/src/pages/LoginPage.test.tsx` (전체 교체 — 기존 7개 테스트는 새 기대값으로 고쳐 유지)

**Interfaces:**
- Consumes: `api.loginForm → { ok, status }`, `isTransientError` (Task 11), `describeError` (Task 1), `HELP_LINKS.support` (Task 7)
- Produces: 오류 상태 `{ kind: 'credentials' } | { kind: 'transient'; status?: number } | { kind: 'other'; message: string }`. 오류는 폼 위 `Alert`(role="alert") 하나뿐, 토스트 없음, 이메일·비밀번호 입력이 바뀌면 사라짐. 401 등 → 기존 문구 "로그인 실패 / 이메일 또는 비밀번호가 올바르지 않습니다. A001" + 비밀번호만 비우고 포커스. 5xx·네트워크 → "지금은 로그인할 수 없습니다 / 서버가 응답하지 않습니다. 입력한 내용은 그대로 있습니다." + "다시 시도".

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/pages/LoginPage.test.tsx` 전체를 아래로 교체:
```tsx
// admin-ui/src/pages/LoginPage.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import type { Me } from '@/api/types';
import { ApiError } from '@/api/types';

const links = vi.hoisted(() => ({ apiDocs: '', rpGuide: '', support: '' }));
vi.mock('@/config/links', () => ({ HELP_LINKS: links }));
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
afterEach(() => { links.support = ''; });

describe('LoginPage V3', () => {
  it('브랜드 패널: 워드마크·헤드라인·장식 일러스트, 가짜 지표 대신 콘솔이 하는 일', () => {
    const { container } = renderLogin();
    expect(screen.getByAltText('CROSSCERT')).toHaveAttribute('src', '/admin/crosscert-logo-transparent.png');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('패스키 인증을운영하는 콘솔.');
    expect(container.querySelector('aside img[alt=""]')).not.toBeNull();
    expect(screen.getByText('이 콘솔에서 하는 일')).toBeInTheDocument();
    for (const d of ['tenant 생성 · 정책 설정 · API 키 발급', '사용자 패스키 조회와 회수', '감사 로그 무결성 확인과 내보내기']) expect(screen.getByText(d)).toBeInTheDocument();
    for (const fake of ['58', '2.4M', '100%', '활성 tenant', 'ceremony / 24h', 'chain 무결성']) expect(screen.queryByText(fake)).toBeNull();
    expect(container.textContent).not.toMatch(/audit log|hash chain|ceremony/);
  });

  it('이메일 칸에 자동 포커스', () => {
    renderLogin();
    expect(document.activeElement).toBe(screen.getByLabelText('이메일'));
  });

  it('비밀번호 라벨 오른쪽에 재설정 링크, 하단에 가입 요청 링크와 자동 로그아웃 안내', () => {
    renderLogin();
    expect(screen.getByRole('link', { name: '비밀번호를 잊으셨나요?' })).toHaveAttribute('href', '/admin/forgot-password');
    expect(screen.getByRole('link', { name: '가입 요청' })).toHaveAttribute('href', '/admin/signup');
    expect(screen.getByText('30분 동안 활동이 없으면 자동으로 로그아웃됩니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '로그인' })).toHaveClass('btn', 'btn--primary', 'btn--lg');
  });

  it('로그인 실패: 폼 맨 위 경고 하나만(토스트 없음), 이메일 유지 · 비밀번호만 비우고 포커스', async () => {
    vi.mocked(api.loginForm).mockResolvedValue({ ok: false, status: 401 });
    renderLogin();
    fillAndSubmit('a@x.com', 'wrong');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('로그인 실패');
    expect(alert).toHaveTextContent('이메일 또는 비밀번호가 올바르지 않습니다.');
    expect(alert).toHaveTextContent('A001');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(document.querySelector('.toast')).toBeNull();
    expect(screen.getByLabelText('이메일')).toHaveValue('a@x.com');
    expect(screen.getByLabelText('비밀번호')).toHaveValue('');
    expect(document.activeElement).toBe(screen.getByLabelText('비밀번호'));
    // 이메일 입력보다 앞(폼 상단)에 위치
    expect(alert.compareDocumentPosition(screen.getByLabelText('이메일')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('입력을 바꾸면 경고가 사라진다', async () => {
    vi.mocked(api.loginForm).mockResolvedValue({ ok: false, status: 401 });
    renderLogin();
    fillAndSubmit('a@x.com', 'wrong');
    await screen.findByRole('alert');
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'n' } });
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('5xx 응답은 일시 오류 + 다시 시도, 입력 유지', async () => {
    vi.mocked(api.loginForm).mockResolvedValue({ ok: false, status: 503 });
    renderLogin();
    fillAndSubmit('a@x.com', 'pw');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('지금은 로그인할 수 없습니다');
    expect(alert).toHaveTextContent('서버가 응답하지 않습니다. 입력한 내용은 그대로 있습니다.');
    expect(alert).toHaveTextContent('HTTP 503');
    expect(alert).not.toHaveTextContent('이메일 또는 비밀번호');
    expect(screen.getByLabelText('비밀번호')).toHaveValue('pw');
    fireEvent.click(within(alert).getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(api.loginForm).toHaveBeenCalledTimes(2));
    expect(api.loginForm).toHaveBeenLastCalledWith('a@x.com', 'pw');
  });

  it('네트워크 오류(fetch TypeError)도 일시 오류', async () => {
    vi.mocked(api.loginForm).mockRejectedValue(new TypeError('Failed to fetch'));
    renderLogin();
    fillAndSubmit('a@x.com', 'pw');
    expect(await screen.findByRole('alert')).toHaveTextContent('지금은 로그인할 수 없습니다');
  });

  it('서버 예외(ApiError 503)도 일시 오류로 표시', async () => {
    vi.mocked(api.loginForm).mockRejectedValue(new ApiError(503, 'C503', '서버 점검 중'));
    renderLogin();
    fillAndSubmit('a@x.com', 'pw');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('지금은 로그인할 수 없습니다');
    expect(alert).toHaveTextContent('HTTP 503');
  });

  it('로그인 뒤 /me 가 403 이면 쉬운 문구로', async () => {
    vi.mocked(api.loginForm).mockResolvedValue({ ok: true, status: 200 });
    vi.mocked(getMe).mockRejectedValue(new ApiError(403, 'A003', 'Forbidden'));
    renderLogin();
    fillAndSubmit('a@x.com', 'pw');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('로그인 실패');
    expect(alert).toHaveTextContent('권한이 없습니다');
  });

  it('성공하면 getMe 결과로 onLogin', async () => {
    vi.mocked(api.loginForm).mockResolvedValue({ ok: true, status: 200 });
    vi.mocked(getMe).mockResolvedValue(me);
    const { onLogin } = renderLogin();
    fillAndSubmit('alice@crosscert.com', 'pw');
    await waitFor(() => expect(onLogin).toHaveBeenCalledWith(me));
    expect(api.loginForm).toHaveBeenCalledWith('alice@crosscert.com', 'pw');
  });

  it('비밀번호 표시 토글', () => {
    renderLogin();
    const pw = screen.getByLabelText('비밀번호');
    expect(pw).toHaveAttribute('type', 'password');
    const toggle = screen.getByRole('button', { name: '비밀번호 표시' });
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(toggle);
    expect(pw).toHaveAttribute('type', 'text');
    expect(screen.getByRole('button', { name: '비밀번호 숨기기' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('문의 주소가 있을 때만 하단 "도움말 · 운영자에게 문의" 링크', () => {
    const { unmount } = renderLogin();
    expect(screen.queryByRole('link', { name: '도움말 · 운영자에게 문의' })).toBeNull();
    unmount();
    links.support = 'mailto:ops@example.com';
    renderLogin();
    expect(screen.getByRole('link', { name: '도움말 · 운영자에게 문의' })).toHaveAttribute('href', 'mailto:ops@example.com');
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

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/LoginPage.test.tsx`
Expected: FAIL — "이 콘솔에서 하는 일" 없음, 지표 `58` 존재, 오류 시 토스트(`.toast`)도 생김, 5xx 가 "이메일 또는 비밀번호" 문구, 이메일 자동 포커스 없음, 표시 토글 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/LoginPage.tsx` 전체를 아래로 교체:
```tsx
// admin-ui/src/pages/LoginPage.tsx
import { useState, useEffect, useRef } from 'react';
import { Icons } from '@/icons/Icons';
import { api, getMe } from '@/api/client';
import type { Me } from '@/api/types';
import { ApiError } from '@/api/types';
import { illustrations } from '@/assets/illustrations';
import { HELP_LINKS } from '@/config/links';
import { describeError } from '@/lib/describeError';
import { isTransientError } from '@/lib/transientError';
import { Alert, Button } from '@/ui';

type LoginPageProps = {
  onLogin: (me: Me) => void;
};

// 브랜드 패널 — 로그인 전에는 실제 지표를 조회할 수 없으므로 숫자 대신 콘솔이 하는 일을 적는다(H2).
const DUTIES = [
  'tenant 생성 · 정책 설정 · API 키 발급',
  '사용자 패스키 조회와 회수',
  '감사 로그 무결성 확인과 내보내기',
] as const;

const DEMO_ACCOUNTS = [
  { v: 'PLATFORM_OPERATOR' as const, t: 'Platform Operator', s: 'alice@crosscert.com' },
] as const;

type LoginError =
  | { kind: 'credentials' }
  | { kind: 'transient'; status?: number }
  | { kind: 'other'; message: string };

export default function LoginPage({ onLogin }: LoginPageProps) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPw, setShowPw] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [demoRole, setDemoRole] = useState<'PLATFORM_OPERATOR' | 'RP_ADMIN' | null>(null);
  const [devProfile, setDevProfile] = useState(false);
  const [error, setError] = useState<LoginError | null>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

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
    setError(null);
    if (devProfile && role === 'PLATFORM_OPERATOR') {
      setEmail('alice@crosscert.com');
      setPassword('alice-temp-pw');
    }
  }

  async function attempt() {
    if (submitting) return;
    setError(null);
    setSubmitting(true);
    try {
      const res = await api.loginForm(email, password);
      if (!res.ok) {
        if (res.status >= 500) { setError({ kind: 'transient', status: res.status }); return; }
        // 401 등(423/429 잠김 구분은 백엔드 2단계) — 이메일은 두고 비밀번호만 지운 뒤 비밀번호 칸으로.
        setError({ kind: 'credentials' });
        setPassword('');
        passwordRef.current?.focus();
        return;
      }
      onLogin(await getMe());
    } catch (err: unknown) {
      if (isTransientError(err)) {
        setError({ kind: 'transient', status: err instanceof ApiError ? err.httpStatus : undefined });
      } else {
        const info = describeError(err);
        setError({ kind: 'other', message: `${info.title}. ${info.detail}` });
      }
    } finally {
      setSubmitting(false);
    }
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    void attempt();
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
            RP 별 tenant · 패스키 · API 키를 한 곳에서 관리하고, 모든 변경은 위·변조를 확인할 수 있는 감사 로그로 남습니다.
          </p>
        </div>
        <img src={illustrations.loginHero} alt="" style={{ width: '100%', maxWidth: 420, height: 'auto', alignSelf: 'center' }} />
        <div className="col" style={{ gap: 8, paddingTop: 18, borderTop: '1px solid var(--line)', marginTop: 'auto' }}>
          <span className="t-over">이 콘솔에서 하는 일</span>
          <ul style={{ margin: 0, paddingLeft: 18, display: 'flex', flexDirection: 'column', gap: 4, fontSize: 14, color: 'var(--ink-2)' }}>
            {DUTIES.map((d) => <li key={d}>{d}</li>)}
          </ul>
          <span className="hint" style={{ margin: 0 }}>실시간 지표는 로그인 후 확인할 수 있습니다.</span>
        </div>
        <p className="hint" style={{ margin: 0 }}>© 2026 Crosscert · 이 콘솔의 모든 접근은 감사 로그에 기록됩니다.</p>
      </aside>

      {/* 폼 */}
      <main style={{ flex: '1 1 440px', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '48px 24px', boxSizing: 'border-box' }}>
        <form onSubmit={handleSubmit} className="col" style={{ width: '100%', maxWidth: 380, gap: 20 }}>
          <div className="col" style={{ gap: 6 }}>
            <h2 className="page__title">관리자 로그인</h2>
            <p className="card__sub">등록된 운영자 계정으로 로그인하세요.</p>
          </div>

          {/* 오류는 폼 위 한 곳에만(H4·H9). 토스트로 중복하지 않는다. */}
          {error?.kind === 'credentials' && (
            <Alert tone="bad" title="로그인 실패">
              이메일 또는 비밀번호가 올바르지 않습니다. <span className="mono" style={{ fontSize: 11 }}>A001</span>
            </Alert>
          )}
          {error?.kind === 'transient' && (
            <Alert tone="bad" title="지금은 로그인할 수 없습니다"
              actions={<Button size="sm" onClick={() => void attempt()} disabled={submitting}>다시 시도</Button>}>
              서버가 응답하지 않습니다. 입력한 내용은 그대로 있습니다.
              {error.status !== undefined && <span className="mono" style={{ display: 'block', fontSize: 11 }}>HTTP {error.status}</span>}
            </Alert>
          )}
          {error?.kind === 'other' && <Alert tone="bad" title="로그인 실패">{error.message}</Alert>}

          <div>
            <label className="label" htmlFor="login-email">이메일</label>
            <input
              id="login-email"
              className="input input--lg"
              type="email"
              // 로그인 화면의 유일한 할 일이라 바로 입력할 수 있게 한다(H1).
              // eslint-disable-next-line jsx-a11y/no-autofocus
              autoFocus
              value={email}
              onChange={(e) => { setEmail(e.target.value); setError(null); }}
              autoComplete="username"
              placeholder="you@crosscert.com"
            />
          </div>
          <div>
            <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-end' }}>
              <label className="label" htmlFor="login-password">비밀번호</label>
              <a href="/admin/forgot-password" style={{ fontSize: 12, fontWeight: 500, marginBottom: 6 }}>비밀번호를 잊으셨나요?</a>
            </div>
            <div className="row" style={{ gap: 8, flexWrap: 'nowrap' }}>
              <input
                id="login-password"
                ref={passwordRef}
                className="input input--lg"
                type={showPw ? 'text' : 'password'}
                value={password}
                onChange={(e) => { setPassword(e.target.value); setError(null); }}
                autoComplete="current-password"
                placeholder="비밀번호"
                style={{ flex: '1 1 0', minWidth: 0 }}
              />
              <Button size="lg" aria-pressed={showPw} aria-label={showPw ? '비밀번호 숨기기' : '비밀번호 표시'} onClick={() => setShowPw((v) => !v)}>
                {showPw ? '숨기기' : '표시'}
              </Button>
            </div>
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
          <div className="row" style={{ justifyContent: 'space-between', gap: 8, paddingTop: 14, borderTop: '1px solid var(--line-soft)' }}>
            <p className="hint" style={{ margin: 0 }}>30분 동안 활동이 없으면 자동으로 로그아웃됩니다.</p>
            {HELP_LINKS.support && <a href={HELP_LINKS.support} style={{ fontSize: 12 }}>도움말 · 운영자에게 문의</a>}
          </div>
        </form>
      </main>
    </div>
  );
}
```

> 로그인 화면은 여전히 `App.tsx` 에서 `ToastHost` 안에 렌더된다(다른 동작이 쓸 수 있으므로 그대로 둔다). 이 화면 자체는 토스트를 쓰지 않는다.

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/LoginPage.test.tsx && npx vitest run && npx tsc -b && npx eslint src`
Expected: LoginPage 14 tests PASS, 전체 PASS.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/LoginPage.tsx admin-ui/src/pages/LoginPage.test.tsx
git commit -m "feat(admin-ui): 로그인 화면 가짜 지표 제거, 오류는 폼 위 한 곳만, 일시 오류와 자격 증명 오류 구분

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: MFA — 인라인 경고 + 첫 칸 포커스, "← 로그인으로", 기기 분실 안내

**Files:**
- Modify: `admin-ui/src/pages/MfaChallenge.tsx` (전체 교체)
- Test: `admin-ui/src/pages/MfaChallenge.test.tsx` (전체 교체 — 기존 9개 테스트는 새 기대값으로 고쳐 유지)

**Interfaces:**
- Consumes: `isTransientError` (Task 11), `describeError` (Task 1), `OtpInput.focusKey`(기존)
- Produces: 실패 시 카드 안 `Alert`(role="alert") — TOTP "코드가 맞지 않습니다." / 복구 코드 "복구 코드가 맞지 않습니다." / 5xx·네트워크 "확인하지 못했습니다". 칸을 비우고 첫 칸(복구 코드 칸)으로 포커스. 토스트 없음(복구 코드 사용 알림 `warn` 토스트는 유지). 카드 밖 "← 로그인으로"(= `onLogout`).

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/pages/MfaChallenge.test.tsx` 전체를 아래로 교체:
```tsx
// admin-ui/src/pages/MfaChallenge.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { ApiError } from '@/api/types';

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
const wrongCode = () => new ApiError(401, 'invalid_code', 'invalid_code');

beforeEach(() => {
  vi.mocked(mfaApi.verify).mockReset().mockResolvedValue({ verified: true });
});

describe('MfaChallenge V3', () => {
  it('공통 카드 셸: 워드마크·장식 일러스트·OTP 6칸·카드 밖 "← 로그인으로"', () => {
    const { container, onLogout } = renderChallenge();
    expect(container.querySelector('.auth .auth-card')).not.toBeNull();
    expect(screen.getByAltText('CROSSCERT')).toHaveClass('auth__logo');
    expect(container.querySelector('.auth-card img[alt=""]')).not.toBeNull();
    expect(screen.getByRole('heading', { name: '2단계 인증' })).toBeInTheDocument();
    expect(screen.getAllByRole('textbox')).toHaveLength(6);
    const back = screen.getByRole('button', { name: '← 로그인으로' });
    expect(back.closest('.auth-card')).toBeNull();
    fireEvent.click(back);
    expect(onLogout).toHaveBeenCalled();
  });

  it('기기 분실 안내: 운영자에게 MFA 초기화 요청', () => {
    renderChallenge();
    expect(screen.getByText('기기를 잃어버렸나요? 운영자에게 MFA 초기화를 요청하세요.')).toBeInTheDocument();
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

  it('수동 확인 버튼은 6자리 전에는 비활성, 실패하면 칸을 비우고 카드 안 경고(토스트 없음)', async () => {
    vi.mocked(mfaApi.verify).mockRejectedValue(wrongCode());
    renderChallenge();
    fireEvent.change(cell(0), { target: { value: '1' } });
    expect(screen.getByRole('button', { name: '확인' })).toBeDisabled();
    fireEvent.paste(cell(0), { clipboardData: { getData: () => '999999' } });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('코드가 맞지 않습니다.');
    expect(alert).toHaveTextContent('앱에 지금 표시된 코드를 다시 입력하세요.');
    expect(alert.closest('.auth-card')).not.toBeNull();
    expect(document.querySelector('.toast')).toBeNull();
    expect(screen.getByText(/휴대폰 시간 자동 설정을 확인하세요/)).toBeInTheDocument();
    await waitFor(() => expect(cellValues()).toBe(''));
  });

  it('검증 실패 후 첫 칸에 다시 포커스 — 클릭 없이 바로 다시 입력', async () => {
    vi.mocked(mfaApi.verify).mockRejectedValue(wrongCode());
    renderChallenge();
    fireEvent.paste(cell(0), { clipboardData: { getData: () => '999999' } });
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await waitFor(() => expect(cell(0)).toBeEnabled());
    await waitFor(() => expect(document.activeElement).toBe(cell(0)));
  });

  it('서버·네트워크 오류는 코드 오류가 아니라 "확인하지 못했습니다"', async () => {
    vi.mocked(mfaApi.verify).mockRejectedValue(new ApiError(503, 'error', 'error'));
    renderChallenge();
    fireEvent.paste(cell(0), { clipboardData: { getData: () => '123456' } });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('확인하지 못했습니다');
    expect(alert).toHaveTextContent('서버가 잠시 응답하지 않습니다');
    expect(alert).not.toHaveTextContent('코드가 맞지 않습니다');
  });

  it('복구 코드가 틀리면 경고 + 복구 코드 칸을 비우고 포커스', async () => {
    vi.mocked(mfaApi.verify).mockRejectedValue(wrongCode());
    renderChallenge();
    toRecovery();
    const rec = screen.getByLabelText('복구 코드') as HTMLInputElement;
    fireEvent.change(rec, { target: { value: 'ab3f2k7m' } });
    fireEvent.click(screen.getByRole('button', { name: '확인' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('복구 코드가 맞지 않습니다.');
    expect(rec.value).toBe('');
    expect(document.activeElement).toBe(rec);
  });

  it('모드를 바꾸면 경고가 사라진다', async () => {
    vi.mocked(mfaApi.verify).mockRejectedValue(wrongCode());
    renderChallenge();
    fireEvent.paste(cell(0), { clipboardData: { getData: () => '999999' } });
    await screen.findByRole('alert');
    toRecovery();
    expect(screen.queryByRole('alert')).toBeNull();
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

Run: `npx vitest run src/pages/MfaChallenge.test.tsx`
Expected: FAIL — 실패가 `인증 실패` 토스트(`role="alert"` 는 토스트), "← 로그인으로" 버튼 없음(현재 "로그아웃"), 기기 분실 안내 문구 없음, 복구 코드 칸 포커스 안 됨.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/MfaChallenge.tsx` 전체를 아래로 교체:
```tsx
// admin-ui/src/pages/MfaChallenge.tsx
import { useRef, useState } from 'react';
import { mfaApi } from '@/api/mfa';
import { formatRecoveryCode } from '@/lib/recoveryCode';
import { describeError } from '@/lib/describeError';
import { isTransientError } from '@/lib/transientError';
import { illustrations } from '@/assets/illustrations';
import { Alert, Button, Field, OtpInput, useToast } from '@/ui';

const TOTP_RE = /^\d{6}$/;
const RECOVERY_RE = /^[A-Z2-9]{4}-[A-Z2-9]{4}$/;

type Mode = 'totp' | 'recovery';

/** 실패 원인별 문구. 서버·네트워크 오류를 "코드가 틀렸다"로 말하지 않는다(H9). */
function VerifyError({ error, mode }: { error: unknown; mode: Mode }) {
  if (isTransientError(error)) {
    const info = describeError(error);
    return <Alert tone="bad" title="확인하지 못했습니다">{info.title}. 잠시 후 다시 시도하세요.</Alert>;
  }
  return mode === 'totp'
    ? <Alert tone="bad" title="코드가 맞지 않습니다.">앱에 지금 표시된 코드를 다시 입력하세요.</Alert>
    : <Alert tone="bad" title="복구 코드가 맞지 않습니다.">저장해 둔 복구 코드 중 아직 쓰지 않은 코드를 입력하세요.</Alert>;
}

export default function MfaChallenge({ onVerified, onLogout }: { onVerified: () => void; onLogout: () => void }) {
  const [mode, setMode] = useState<Mode>('totp');
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState<{ error: unknown } | null>(null);
  // 실패하면 칸이 비고(비활성 동안 포커스를 잃음) — 다시 활성이 되면 첫 칸에 포커스한다.
  const [refocus, setRefocus] = useState(0);
  // 자동 제출(onComplete)과 수동 확인이 같은 틱에 겹쳐도 verify 는 한 번만.
  const inFlight = useRef(false);
  const recoveryRef = useRef<HTMLInputElement>(null);
  const toast = useToast();

  const isReady = (v: string) => (mode === 'totp' ? TOTP_RE.test(v) : RECOVERY_RE.test(v));
  const ready = isReady(code);

  async function verify(value: string) {
    if (inFlight.current || !isReady(value)) return;
    inFlight.current = true;
    setSubmitting(true);
    setFailure(null);
    try {
      const res = await mfaApi.verify(value.trim());
      if (res.usedRecoveryCode && typeof res.remaining === 'number') {
        toast({ kind: 'warn', title: '복구 코드를 사용했습니다.', message: `남은 복구 코드: ${res.remaining}개` });
      }
      onVerified();
    } catch (e) {
      // 오류는 카드 안 한 곳(role="alert")에만. 칸을 비우고 첫 칸으로 포커스를 돌려 바로 다시 입력하게 한다.
      setFailure({ error: e });
      setCode('');
      if (mode === 'totp') setRefocus((n) => n + 1);
      else recoveryRef.current?.focus();
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
  }

  function submit(e: React.FormEvent) {
    e.preventDefault();
    void verify(code);
  }

  function switchMode(next: Mode) {
    setMode(next);
    setCode('');
    setFailure(null);
  }

  const wrongTotp = mode === 'totp' && failure !== null && !isTransientError(failure.error);

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
              {failure && <VerifyError error={failure.error} mode="totp" />}
              <div>
                <OtpInput value={code} onChange={setCode} onComplete={(v) => void verify(v)} disabled={submitting} autoFocus focusKey={refocus} />
                <p className="hint" style={{ textAlign: 'center' }}>붙여넣기 지원 · 6자리를 모두 입력하면 자동 확인</p>
              </div>
              <Button type="submit" variant="primary" size="lg" disabled={submitting || !ready}>
                {submitting ? '확인 중…' : '확인'}
              </Button>
              <div className="col" style={{ gap: 6, alignItems: 'center', paddingTop: 4, borderTop: '1px solid var(--line-soft)' }}>
                <Button variant="ghost" size="sm" onClick={() => switchMode('recovery')}>복구 코드로 로그인</Button>
                <p className="hint" style={{ margin: 0, textAlign: 'center' }}>기기를 잃어버렸나요? 운영자에게 MFA 초기화를 요청하세요.</p>
                {wrongTotp && (
                  <p className="hint" style={{ margin: 0, textAlign: 'center' }}>시계가 맞지 않으면 코드가 계속 틀릴 수 있습니다. 휴대폰 시간 자동 설정을 확인하세요.</p>
                )}
              </div>
            </>
          ) : (
            <>
              <div className="col" style={{ gap: 6 }}>
                <h1 className="page__title">복구 코드로 로그인</h1>
                <p className="card__sub">MFA 등록 때 저장한 복구 코드 하나를 입력하세요. 사용한 코드는 다시 쓸 수 없습니다.</p>
              </div>
              {failure && <VerifyError error={failure.error} mode="recovery" />}
              <Field label="복구 코드" htmlFor="mfa-recovery">
                <input
                  id="mfa-recovery"
                  ref={recoveryRef}
                  className="input input--lg mono"
                  // eslint-disable-next-line jsx-a11y/no-autofocus
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
        {/* 출구(H3): MFA 대기 세션을 끝내고 로그인 화면으로 */}
        <Button variant="ghost" onClick={onLogout} style={{ color: 'var(--ink-2)' }}>← 로그인으로</Button>
      </div>
    </main>
  );
}
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/MfaChallenge.test.tsx && npx vitest run && npx tsc -b && npx eslint src`
Expected: MfaChallenge 13 tests PASS, 전체 PASS.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/MfaChallenge.tsx admin-ui/src/pages/MfaChallenge.test.tsx
git commit -m "feat(admin-ui): MFA 실패를 카드 안 경고로 알리고 첫 칸에 포커스, 로그인으로 돌아가기와 기기 분실 안내

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: 비밀번호 찾기 — 네트워크·5xx 만 "보내지 못했습니다 · 다시 시도"

**Files:**
- Modify: `admin-ui/src/pages/ForgotPasswordPage.tsx` (전체 교체)
- Test: `admin-ui/src/pages/ForgotPasswordPage.test.tsx` (기존 4개 유지 + 3개 추가)

**Interfaces:**
- Consumes: `isTransientError` (Task 11), `Button` forwardRef (Task 4)
- Produces: 2xx·4xx → 기존 "메일을 확인하세요" 동일 안내(계정 존재 비노출). 네트워크·5xx → 폼 유지 + `Alert`(role="alert") "요청을 보내지 못했습니다." + 버튼 "다시 시도"(포커스) + 이메일 도움말 "입력한 이메일은 그대로 있습니다."

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/pages/ForgotPasswordPage.test.tsx` 2행
```tsx
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
```
을 아래로 교체:
```tsx
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import { ApiError } from '@/api/types';
```

같은 파일 마지막 `});`(58행, `describe` 닫기) 바로 앞에 추가:
```tsx

  it('4xx 도 성공과 같은 안내(계정 존재 비노출)', async () => {
    vi.mocked(passwordResetApi.request).mockRejectedValueOnce(new ApiError(400, 'C400', 'bad email'));
    await submit('nobody@x.com');
    expect(screen.getByRole('status')).toHaveTextContent('해당 이메일로 가입된 계정이 있으면 재설정 링크가 발송됩니다.');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('5xx 면 보내지 못했다고 알리고 이메일을 유지, "다시 시도"에 포커스, 다시 시도가 성공하면 안내', async () => {
    vi.mocked(passwordResetApi.request).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    render(<ForgotPasswordPage />);
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'a@x.com' } });
    fireEvent.click(screen.getByRole('button', { name: '재설정 링크 보내기' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('요청을 보내지 못했습니다.');
    expect(alert).toHaveTextContent('메일은 발송되지 않았습니다');
    expect(alert).toHaveTextContent('HTTP 503');
    expect(screen.queryByText('메일을 확인하세요')).toBeNull();
    expect(screen.getByLabelText('이메일')).toHaveValue('a@x.com');
    expect(screen.getByText('입력한 이메일은 그대로 있습니다.')).toBeInTheDocument();
    const retry = screen.getByRole('button', { name: '다시 시도' });
    await waitFor(() => expect(document.activeElement).toBe(retry));
    fireEvent.click(retry);
    expect(await screen.findByText('메일을 확인하세요')).toBeInTheDocument();
    expect(passwordResetApi.request).toHaveBeenCalledTimes(2);
  });

  it('네트워크 오류(fetch TypeError)도 보내지 못했다고 알린다', async () => {
    vi.mocked(passwordResetApi.request).mockRejectedValueOnce(new TypeError('Failed to fetch'));
    render(<ForgotPasswordPage />);
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'a@x.com' } });
    fireEvent.click(screen.getByRole('button', { name: '재설정 링크 보내기' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('요청을 보내지 못했습니다.');
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/ForgotPasswordPage.test.tsx`
Expected: FAIL — 5xx·TypeError 에도 "메일을 확인하세요"로 바뀌어 `alert` 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/ForgotPasswordPage.tsx` 전체를 아래로 교체:
```tsx
import { useEffect, useRef, useState } from 'react';
import { passwordResetApi } from '@/api/passwordReset';
import { ApiError } from '@/api/types';
import { isTransientError } from '@/lib/transientError';
import { Alert, Button, Field } from '@/ui';

/**
 * 미인증 public 화면 — 이메일로 재설정 링크 요청.
 * enumeration 방지: 2xx·4xx 는 항상 동일한 "메일을 확인하세요" 안내(계정 존재 비노출).
 * 네트워크·5xx 는 실제로 보내지 못했으므로 실패로 알리고 입력을 유지한다(H9).
 * 제출 후 같은 카드 안에서 완료 상태로 전환한다.
 */
export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);
  const [failure, setFailure] = useState<{ error: unknown } | null>(null);
  const submitRef = useRef<HTMLButtonElement>(null);

  useEffect(() => { if (failure) submitRef.current?.focus(); }, [failure]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting || !email.trim()) return;
    setSubmitting(true);
    setFailure(null);
    try {
      await passwordResetApi.request(email.trim());
      setSent(true);
    } catch (err) {
      if (isTransientError(err)) setFailure({ error: err });
      else setSent(true); /* enumeration 방지: 4xx 는 성공과 같은 안내. */
    } finally {
      setSubmitting(false);
    }
  }

  const status = failure?.error instanceof ApiError ? failure.error.httpStatus : undefined;

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
              {failure && (
                <Alert tone="bad" title="요청을 보내지 못했습니다.">
                  네트워크 또는 서버 문제입니다. 메일은 발송되지 않았습니다. 잠시 후 다시 시도하세요.
                  {status !== undefined && <span className="mono" style={{ display: 'block', fontSize: 11 }}>HTTP {status}</span>}
                </Alert>
              )}
              <Field label="이메일" htmlFor="forgot-email" help={failure ? '입력한 이메일은 그대로 있습니다.' : undefined}>
                <input
                  id="forgot-email"
                  className="input input--lg"
                  type="email"
                  // eslint-disable-next-line jsx-a11y/no-autofocus
                  autoFocus
                  autoComplete="username"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="you@crosscert.com"
                />
              </Field>
              <Button ref={submitRef} type="submit" variant="primary" size="lg" disabled={submitting || !email.trim()}>
                {submitting ? '전송 중…' : failure ? '다시 시도' : '재설정 링크 보내기'}
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

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/ForgotPasswordPage.test.tsx && npx vitest run && npx tsc -b && npx eslint src`
Expected: ForgotPasswordPage 7 tests PASS(기존 "요청이 실패해도 완료 카드 내용이 성공 때와 같다"는 일반 `Error` 라 그대로 통과), 전체 PASS.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/ForgotPasswordPage.tsx admin-ui/src/pages/ForgotPasswordPage.test.tsx
git commit -m "fix(admin-ui): 비밀번호 찾기에서 네트워크·5xx 실패를 접수 완료로 덮지 않고 다시 시도로 안내

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 15: 가입 요청 — 이메일 형식 인라인 오류, 비밀번호 12–128자, 네트워크·5xx 실패

**Files:**
- Modify: `admin-ui/src/pages/SignupRequestPage.tsx` (전체 교체)
- Test: `admin-ui/src/pages/SignupRequestPage.test.tsx` (기존 7개 유지 — 1개 문구 수정, 5개 추가)

**Interfaces:**
- Consumes: `isTransientError` (Task 11), `Button` forwardRef (Task 4), `Field`(`error` 시 `${htmlFor}-error` id)
- Produces:
  - 이메일: 칸을 벗어난 뒤(blur) 형식이 틀리면 "이메일 형식이 올바르지 않습니다. 예: you@company.com"(`aria-invalid`).
  - 비밀번호: `maxLength={128}`, 도움말 "12–128자", 짧으면 오류 "[N]자 입력됨 · 12–128자로 입력하세요"(기존 "12자 이상" 대체).
  - 2xx·4xx → 기존 "요청이 접수되었습니다" 동일 안내. 네트워크·5xx → 폼·입력 유지 + "요청을 보내지 못했습니다." + 버튼 "다시 시도"(포커스).

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/pages/SignupRequestPage.test.tsx` 2행
```tsx
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
```
을 아래로 교체:
```tsx
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { ApiError } from '@/api/types';
```

37–43행 테스트(짧은 비밀번호)
```tsx
  it('keeps submit disabled for a password shorter than 12', () => {
    render(<SignupRequestPage />);
    fill('a@x.com', 'short', 'short');
    expect(screen.getByText('12자 이상')).toHaveClass('field__error');
    expect(screen.getByLabelText('비밀번호')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('button', { name: '가입 요청 보내기' })).toBeDisabled();
  });
```
을 아래로 교체:
```tsx
  it('keeps submit disabled for a password shorter than 12', () => {
    render(<SignupRequestPage />);
    fill('a@x.com', 'short', 'short');
    expect(screen.getByText('5자 입력됨 · 12–128자로 입력하세요')).toHaveClass('field__error');
    expect(screen.getByLabelText('비밀번호')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('button', { name: '가입 요청 보내기' })).toBeDisabled();
  });
```

파일 마지막 `});`(87행, `describe` 닫기) 바로 앞에 추가:
```tsx

  it('비밀번호는 최대 128자, 허용 범위 "12–128자"를 미리 보여 준다', () => {
    render(<SignupRequestPage />);
    expect(screen.getByLabelText('비밀번호')).toHaveAttribute('maxLength', '128');
    expect(screen.getByText('12–128자')).toHaveClass('hint');
  });

  it('이메일 형식 오류는 칸을 벗어날 때 인라인으로', () => {
    render(<SignupRequestPage />);
    const email = screen.getByLabelText('이메일');
    fireEvent.change(email, { target: { value: 'not-an-email' } });
    expect(screen.queryByText(/이메일 형식이 올바르지 않습니다/)).toBeNull();
    fireEvent.blur(email);
    expect(screen.getByText('이메일 형식이 올바르지 않습니다. 예: you@company.com')).toHaveClass('field__error');
    expect(email).toHaveAttribute('aria-invalid', 'true');
    expect(email).toHaveAttribute('aria-describedby', 'signup-email-error');
    fireEvent.change(email, { target: { value: 'a@x.com' } });
    expect(screen.queryByText(/이메일 형식이 올바르지 않습니다/)).toBeNull();
  });

  it('4xx 는 성공과 같은 접수 안내(계정 존재 비노출)', async () => {
    (signupRequestsApi.request as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new ApiError(409, 'C409', 'duplicate'));
    render(<SignupRequestPage />);
    fill('dup@x.com', 'password-12chars', 'password-12chars');
    fireEvent.click(screen.getByRole('button', { name: '가입 요청 보내기' }));
    expect(await screen.findByRole('heading', { name: '요청이 접수되었습니다' })).toBeInTheDocument();
  });

  it('5xx 면 보내지 못했다고 알리고 입력을 유지, "다시 시도"에 포커스, 다시 시도가 성공하면 접수', async () => {
    (signupRequestsApi.request as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    render(<SignupRequestPage />);
    fill('d@x.com', 'password-12chars', 'password-12chars');
    fireEvent.change(screen.getByLabelText(/요청 사유/), { target: { value: '보안팀' } });
    fireEvent.click(screen.getByRole('button', { name: '가입 요청 보내기' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('요청을 보내지 못했습니다.');
    expect(alert).toHaveTextContent('입력한 내용은 그대로 있습니다');
    expect(alert).toHaveTextContent('HTTP 503');
    expect(screen.queryByRole('heading', { name: '요청이 접수되었습니다' })).toBeNull();
    expect(screen.getByLabelText('이메일')).toHaveValue('d@x.com');
    expect(screen.getByLabelText('비밀번호')).toHaveValue('password-12chars');
    expect(screen.getByLabelText(/요청 사유/)).toHaveValue('보안팀');
    const retry = screen.getByRole('button', { name: '다시 시도' });
    await waitFor(() => expect(document.activeElement).toBe(retry));
    fireEvent.click(retry);
    expect(await screen.findByRole('heading', { name: '요청이 접수되었습니다' })).toBeInTheDocument();
  });

  it('네트워크 오류(fetch TypeError)도 보내지 못했다고 알린다', async () => {
    (signupRequestsApi.request as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new TypeError('Failed to fetch'));
    render(<SignupRequestPage />);
    fill('e@x.com', 'password-12chars', 'password-12chars');
    fireEvent.click(screen.getByRole('button', { name: '가입 요청 보내기' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('요청을 보내지 못했습니다.');
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/SignupRequestPage.test.tsx`
Expected: FAIL — 비밀번호 오류 문구가 "12자 이상", `maxLength` 없음, 이메일 인라인 오류 없음, 5xx 에도 접수 완료.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/SignupRequestPage.tsx` 전체를 아래로 교체:
```tsx
import { useEffect, useRef, useState } from 'react';
import { signupRequestsApi } from '@/api/signupRequests';
import { ApiError } from '@/api/types';
import { illustrations } from '@/assets/illustrations';
import { isTransientError } from '@/lib/transientError';
import { Alert, Button, Field } from '@/ui';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PW_MIN = 12;
const PW_MAX = 128;

/**
 * 미인증 public 화면 — 관리자 계정 가입 요청. PLATFORM_OPERATOR 가 승인해야 로그인 가능.
 * enumeration 방지: 서버는 항상 202 를 주고, 2xx·4xx 는 같은 접수 안내를 보여준다.
 * 네트워크·5xx 는 접수되지 않았으므로 실패로 알리고 입력을 유지한다(H9).
 * 비밀번호 정책(12~128자)은 서버와 동일하게 클라이언트에서도 선검증하고 범위를 미리 보여 준다(H5).
 */
export default function SignupRequestPage() {
  const [email, setEmail] = useState('');
  const [emailTouched, setEmailTouched] = useState(false);
  const [pw, setPw] = useState('');
  const [pw2, setPw2] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);
  const [failure, setFailure] = useState<{ error: unknown } | null>(null);
  const submitRef = useRef<HTMLButtonElement>(null);

  const emailInvalid = emailTouched && email.trim().length > 0 && !EMAIL_RE.test(email.trim());
  const mismatch = pw.length > 0 && pw2.length > 0 && pw !== pw2;
  const pwTooShort = pw.length > 0 && pw.length < PW_MIN;
  const canSubmit =
    EMAIL_RE.test(email.trim()) && pw.length >= PW_MIN && pw.length <= PW_MAX && pw === pw2
    && reason.length <= 500 && !submitting;

  useEffect(() => { if (failure) submitRef.current?.focus(); }, [failure]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setFailure(null);
    try {
      await signupRequestsApi.request({
        email: email.trim().toLowerCase(),
        password: pw,
        reason: reason.trim() ? reason.trim() : undefined,
      });
      setDone(true);
    } catch (err) {
      if (isTransientError(err)) setFailure({ error: err });
      else setDone(true); /* enumeration 방지: 4xx 는 성공과 같은 안내. */
    } finally {
      setSubmitting(false);
    }
  }

  const status = failure?.error instanceof ApiError ? failure.error.httpStatus : undefined;

  return (
    <main className="auth">
      <div className="auth__col auth__col--wide">
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
              {failure && (
                <Alert tone="bad" title="요청을 보내지 못했습니다.">
                  네트워크 또는 서버 문제로 접수되지 않았습니다. 입력한 내용은 그대로 있습니다.
                  {status !== undefined && <span className="mono" style={{ display: 'block', fontSize: 11 }}>HTTP {status}</span>}
                </Alert>
              )}
              <Field label="이메일" htmlFor="signup-email" error={emailInvalid ? '이메일 형식이 올바르지 않습니다. 예: you@company.com' : undefined}>
                {/* eslint-disable-next-line jsx-a11y/no-autofocus */}
                <input id="signup-email" className="input" type="email" autoFocus value={email}
                  onChange={(e) => setEmail(e.target.value)} onBlur={() => setEmailTouched(true)}
                  autoComplete="username" placeholder="you@company.com"
                  aria-invalid={emailInvalid} aria-describedby={emailInvalid ? 'signup-email-error' : undefined} />
              </Field>
              <Field label="비밀번호" htmlFor="signup-pw" help="12–128자"
                error={pwTooShort ? `${pw.length}자 입력됨 · 12–128자로 입력하세요` : undefined}>
                <input id="signup-pw" className="input" type="password" value={pw} maxLength={PW_MAX}
                  onChange={(e) => setPw(e.target.value)} autoComplete="new-password"
                  aria-invalid={pwTooShort} aria-describedby={pwTooShort ? 'signup-pw-error' : undefined} />
              </Field>
              <Field label="비밀번호 확인" htmlFor="signup-pw2" error={mismatch ? '비밀번호가 일치하지 않습니다.' : undefined}>
                <input id="signup-pw2" className="input" type="password" value={pw2} maxLength={PW_MAX}
                  onChange={(e) => setPw2(e.target.value)} autoComplete="new-password"
                  aria-invalid={mismatch} aria-describedby={mismatch ? 'signup-pw2-error' : undefined} />
              </Field>
              <Field label={<>요청 사유 <span style={{ fontWeight: 400, color: 'var(--mute)' }}>(선택)</span></>} htmlFor="signup-reason">
                <textarea id="signup-reason" className="input" value={reason} maxLength={500}
                  onChange={(e) => setReason(e.target.value)} rows={3}
                  placeholder="소속 · 담당 RP · 필요한 권한" />
              </Field>
              <Button ref={submitRef} type="submit" variant="primary" size="lg" disabled={!canSubmit}>
                {submitting ? '전송 중…' : failure ? '다시 시도' : '가입 요청 보내기'}
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

> `Field` 의 `hint` 는 `error` 가 없을 때만 보인다(`ui/Field.tsx`) — 짧은 비밀번호 오류가 뜨면 "12–128자" 도움말 자리를 오류 문구(범위 포함)가 대신한다.

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/SignupRequestPage.test.tsx && npx vitest run && npx tsc -b && npx eslint src`
Expected: SignupRequestPage 12 tests PASS, 전체 PASS.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/SignupRequestPage.tsx admin-ui/src/pages/SignupRequestPage.test.tsx
git commit -m "fix(admin-ui): 가입 요청에 이메일 형식·비밀번호 범위 인라인 안내, 네트워크·5xx 실패는 다시 시도로

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 16: 비밀번호 재설정 — 모든 상태에 "← 로그인으로", 토큰 400 → 만료 카드

**Files:**
- Modify: `admin-ui/src/pages/ResetPasswordPage.tsx` (전체 교체)
- Test: `admin-ui/src/pages/ResetPasswordPage.test.tsx` (전체 교체 — 기존 5개는 새 기대값으로 고쳐 유지, 3개 추가)

**Interfaces:**
- Consumes: `isTransientError` (Task 11), `ApiError`
- Produces:
  - `function isTokenRejection(e: unknown): boolean` (모듈 내부) — `ApiError` 400 이고 `serverMessage` 에 `token` 포함(백엔드 `PasswordResetService.confirm` 의 `invalid token`·`token already used`·`token expired` → `GlobalExceptionHandler` 400 + 메시지 그대로).
  - 상태: 토큰 없음 / 토큰 거부(400) → "링크를 사용할 수 없습니다" 카드 + "재설정 링크 다시 받기"(`/admin/forgot-password`). 5xx·네트워크 → "비밀번호를 바꾸지 못했습니다." + "다시 시도", 입력 유지. 그 밖의 400 → 서버 메시지 배너(기존). 카드 밖 "← 로그인으로"는 모든 상태에 표시.
  - 토큰 사전 확인(`GET …/validate`)은 2단계(스펙 §8) — 1단계는 제출 후 400 일 때 만료 카드.

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/pages/ResetPasswordPage.test.tsx` 전체를 아래로 교체:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor, within } from '@testing-library/react';
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
function submitValid() {
  fill('password-12chars', 'password-12chars');
  fireEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }));
}
const backLink = () => screen.getByRole('link', { name: '← 로그인으로' });

beforeEach(() => { vi.mocked(passwordResetApi.confirm).mockReset().mockResolvedValue({ reset: true }); });

describe('ResetPasswordPage V3', () => {
  it('token 이 없으면 무효 링크 상태: 경고 + 재설정 링크 다시 받기 + 로그인으로', () => {
    const { container } = renderWithDataRouter(<ResetPasswordPage />, { initialPath: '/reset-password' });
    expect(screen.getByRole('status')).toHaveTextContent('링크를 사용할 수 없습니다');
    expect(screen.getByText('유효하지 않은 링크입니다. 만료됐거나 이미 사용된 링크일 수 있습니다.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '재설정 링크 다시 받기' })).toHaveAttribute('href', '/admin/forgot-password');
    expect(backLink()).toHaveAttribute('href', '/admin');
    expect(container.querySelector('input')).toBeNull();
  });

  it('입력 상태에도 카드 밖 "← 로그인으로"', () => {
    const { container } = withToken();
    expect(backLink()).toHaveAttribute('href', '/admin');
    expect(backLink().closest('.auth-card')).toBeNull();
    expect(container.querySelector('input')).not.toBeNull();
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

  it('성공하면 같은 카드에서 변경 완료 상태, 로그인으로 링크도 유지', async () => {
    const { container } = withToken();
    const card = container.querySelector('.auth-card');
    submitValid();
    expect(await screen.findByText('변경 완료')).toBeInTheDocument();
    expect(passwordResetApi.confirm).toHaveBeenCalledWith('t1', 'password-12chars');
    expect(container.querySelector('.auth-card')).toBe(card);
    expect(screen.getByRole('link', { name: '로그인 →' })).toHaveAttribute('href', '/admin');
    expect(backLink()).toHaveAttribute('href', '/admin');
  });

  it('토큰 관련 400 은 만료 카드 + 재설정 링크 다시 받기, 입력 폼은 사라진다', async () => {
    vi.mocked(passwordResetApi.confirm).mockRejectedValue(new ApiError(400, 'C400', 'token expired'));
    const { container } = withToken();
    submitValid();
    const status = await screen.findByRole('status');
    expect(status).toHaveTextContent('링크가 만료되었거나 이미 사용되었습니다');
    expect(screen.getByRole('link', { name: '재설정 링크 다시 받기' })).toHaveAttribute('href', '/admin/forgot-password');
    expect(container.querySelector('input')).toBeNull();
    expect(backLink()).toBeInTheDocument();
  });

  it('토큰과 무관한 400 은 서버 메시지를 오류 배너로, 폼 유지', async () => {
    vi.mocked(passwordResetApi.confirm).mockRejectedValue(new ApiError(400, 'C400', 'password too weak'));
    withToken();
    submitValid();
    expect(await screen.findByRole('alert')).toHaveTextContent('password too weak');
    expect(screen.getByRole('button', { name: '비밀번호 변경' })).toBeInTheDocument();
  });

  it('5xx 는 "비밀번호를 바꾸지 못했습니다" + 다시 시도, 입력 유지', async () => {
    vi.mocked(passwordResetApi.confirm).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    withToken();
    submitValid();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('비밀번호를 바꾸지 못했습니다.');
    expect(alert).toHaveTextContent('입력한 내용은 그대로 있습니다');
    expect(alert).toHaveTextContent('HTTP 503');
    expect(screen.getByLabelText('새 비밀번호')).toHaveValue('password-12chars');
    fireEvent.click(within(alert).getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(passwordResetApi.confirm).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('변경 완료')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/ResetPasswordPage.test.tsx`
Expected: FAIL — 링크 문구 "재설정 다시 요청", 입력·완료 상태에 "← 로그인으로" 없음, 토큰 400 이 배너로 표시, 5xx "다시 시도" 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/ResetPasswordPage.tsx` 전체를 아래로 교체:
```tsx
import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { passwordResetApi } from '@/api/passwordReset';
import { ApiError } from '@/api/types';
import { isTransientError } from '@/lib/transientError';
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
 * 백엔드 PasswordResetService.confirm 의 토큰 거부(invalid token · token already used · token expired)는
 * IllegalArgumentException → 400 + 메시지 그대로. 토큰 사전 확인 API 는 2단계(스펙 §8).
 */
function isTokenRejection(e: unknown): boolean {
  return e instanceof ApiError && e.httpStatus === 400 && /token/i.test(e.serverMessage);
}

type Failure =
  | { kind: 'expired' }
  | { kind: 'transient'; error: unknown }
  | { kind: 'rejected'; message: string };

/**
 * 미인증 public 화면 — 이메일 링크의 ?token= 으로 진입해 새 비밀번호 설정.
 * 클라이언트 검증: 12자 이상, 두 입력 일치.
 * 토큰 거부(400) → 만료 카드, 5xx·네트워크 → 다시 시도(입력 유지), 그 밖의 거부 → 서버 메시지.
 * 모든 상태에 "← 로그인으로"(H3).
 */
export default function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get('token');
  const [pw, setPw] = useState('');
  const [pw2, setPw2] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);

  const mismatch = pw.length > 0 && pw2.length > 0 && pw !== pw2;
  const tooShort = pw.length > 0 && pw.length < MIN_LENGTH;
  const canSubmit = !!token && pw.length >= MIN_LENGTH && pw === pw2 && !submitting;

  async function save() {
    if (!canSubmit) return;
    setSubmitting(true);
    setFailure(null);
    try {
      await passwordResetApi.confirm(token!, pw);
      setDone(true);
    } catch (err: unknown) {
      if (isTransientError(err)) setFailure({ kind: 'transient', error: err });
      else if (isTokenRejection(err)) setFailure({ kind: 'expired' });
      else setFailure({ kind: 'rejected', message: err instanceof ApiError ? err.serverMessage : '비밀번호 변경에 실패했습니다.' });
    } finally {
      setSubmitting(false);
    }
  }

  function submit(e: React.FormEvent) {
    e.preventDefault();
    void save();
  }

  const linkUnusable = !token || failure?.kind === 'expired';
  const transientStatus = failure?.kind === 'transient' && failure.error instanceof ApiError ? failure.error.httpStatus : undefined;

  return (
    <main className="auth">
      <div className="auth__col">
        <img className="auth__logo" src="/admin/crosscert-logo-transparent.png" alt="CROSSCERT" />
        <div className="auth-card">
          {linkUnusable ? (
            <>
              <Alert tone="warn" title="링크를 사용할 수 없습니다">
                {token
                  ? '링크가 만료되었거나 이미 사용되었습니다. 보안을 위해 재설정 링크는 정해진 시간 안에 한 번만 쓸 수 있습니다.'
                  : '유효하지 않은 링크입니다. 만료됐거나 이미 사용된 링크일 수 있습니다.'}
              </Alert>
              <a className="btn btn--primary" href="/admin/forgot-password">재설정 링크 다시 받기</a>
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
              {failure?.kind === 'transient' && (
                <Alert tone="bad" title="비밀번호를 바꾸지 못했습니다."
                  actions={<Button size="sm" onClick={() => void save()} disabled={!canSubmit}>다시 시도</Button>}>
                  서버가 응답하지 않습니다. 입력한 내용은 그대로 있습니다.
                  {transientStatus !== undefined && <span className="mono" style={{ display: 'block', fontSize: 11 }}>HTTP {transientStatus}</span>}
                </Alert>
              )}
              <div>
                <label className="label" htmlFor="reset-pw">새 비밀번호</label>
                <input
                  id="reset-pw"
                  className="input input--lg"
                  type="password"
                  // eslint-disable-next-line jsx-a11y/no-autofocus
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
              {failure?.kind === 'rejected' && <Alert tone="bad">{failure.message}</Alert>}
              <Button type="submit" variant="primary" size="lg" disabled={!canSubmit}>
                {submitting ? '변경 중…' : '비밀번호 변경'}
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

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/ResetPasswordPage.test.tsx && npx vitest run && npx tsc -b && npx eslint src`
Expected: ResetPasswordPage 8 tests PASS, 전체 PASS.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/ResetPasswordPage.tsx admin-ui/src/pages/ResetPasswordPage.test.tsx
git commit -m "fix(admin-ui): 비밀번호 재설정 모든 상태에 로그인으로 링크, 토큰 거부는 만료 카드, 5xx 는 다시 시도

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## 플랫폼 화면

> 스펙 §5. Tenants · Activity · 감사 체인 · License(+ 전역 라이선스 배너). 공통 API(`useLoad`, `describeError`, `eulReul`, `LoadError`, `Skeleton`, `ConfirmDialog`, `ToastInput.action`, `Button` forwardRef)는 Task 1–5 에서 만든 것을 쓴다.
>
> 현재 코드 기준 메모:
> - Activity 의 p95 KPI 숨김(`kpi.p95Ms == null` 이면 그리지 않음)은 이미 구현되어 있고 테스트(`ActivityPage.test.tsx` "평균 응답(p95) …" 2개)가 있다. 이 파트는 그 동작을 바꾸지 않고 테스트를 유지한다.
> - `AuditChainPage.tsx` 에는 가짜 진행률 코드가 없다(가짜 진행 막대는 `pages/tenant/AuditTab.tsx` 의 체인 검증 다이얼로그 — Tenant 탭 파트 담당). 이 파트는 "지금 검증" 이 숫자 없는 "검증 중…" 만 보이고 `progressbar` 를 그리지 않는다는 것을 테스트로 고정한다.
> - Tenants 의 "가짜 KPI" 는 로딩 중·실패 시 빈 배열로 계산된 `0` 이 실제 값처럼 보이는 문제다. 로드 전·실패 시 `—` 로 바꾼다(전역 제약: 실패 시 건수는 `—`). 서버에 없는 지표(V3 캔버스의 "24시간 패스키 등록·인증")는 추가하지 않는다.

---

### Task 17: Tenants 목록 — 로드 상태 통일(`useLoad`·`Skeleton`·`LoadError`), 로드 전·실패 시 KPI `—`

**Files:**
- Modify: `admin-ui/src/pages/TenantsListPage.tsx` (1–305행: import, `TenantsListPage` 컴포넌트 전체. `NewTenantDialog`(307–402행)는 Task 18)
- Test: `admin-ui/src/pages/TenantsListPage.test.tsx`

**Interfaces:**
- Consumes: `useLoad`, `LoadState` (Task 1), `LoadError` (Task 2), `Skeleton` (Task 3)
- Produces: 없음(페이지 내부 동작만). `TenantsListPage` 기본 export, `NewTenantDialog` named export 시그니처는 이 Task 에서 바꾸지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/pages/TenantsListPage.test.tsx` 2–7행 import 에 `ApiError` 를 추가한다:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, fireEvent, within } from '@testing-library/react';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import { ToastHost } from '@/ui';
import type { Tenant } from '@/api/designTypes';
import type { ApiKeyView } from '@/api/types';
import { ApiError } from '@/api/types';
```

104–109행의 기존 테스트 "로드 실패: 빈 목록 안내 대신 불러오기 실패 상태" 를 아래로 교체한다(같은 테스트에 V3 기대값 추가 — 삭제 아님):
```tsx
  it('로드 실패: 빈 목록 안내 대신 불러오기 실패 상태(KPI 는 "—", 오류 토스트 중복 없음)', async () => {
    vi.mocked(tenantsApi.list).mockRejectedValue(new Error('boom'));
    renderPage();
    expect(await screen.findByText('tenant 목록을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByText('아직 tenant 가 없습니다')).toBeNull();
    expect(screen.getAllByText('—')).toHaveLength(3);
    expect(screen.queryByRole('button', { name: /활성 Tenant/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'CSV' })).toBeNull();
    expect(screen.getAllByRole('alert')).toHaveLength(1);
  });
```

파일 끝 `});`(165행) 바로 앞에 추가:
```tsx

  it('V3: 불러오는 동안 Skeleton, KPI 는 0 이 아니라 "—"', async () => {
    vi.mocked(tenantsApi.list).mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(await screen.findByRole('status', { name: '불러오는 중' })).toHaveAttribute('aria-busy', 'true');
    expect(screen.queryByText('불러오는 중…')).toBeNull();
    expect(screen.getAllByText('—')).toHaveLength(3);
    expect(screen.queryByText('0')).toBeNull();
  });

  it('V3: 로드 실패 → 다시 시도하면 목록을 다시 불러온다', async () => {
    vi.mocked(tenantsApi.list)
      .mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'))
      .mockResolvedValueOnce(three);
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('서버가 잠시 응답하지 않습니다');
    expect(alert).toHaveTextContent('HTTP 503');
    fireEvent.click(within(alert).getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('Tenant a1')).toBeInTheDocument();
    expect(tenantsApi.list).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('button', { name: /활성 Tenant/ })).toHaveTextContent('2');
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/TenantsListPage.test.tsx`
Expected: FAIL — 로드 실패 시 KPI 가 `0` 으로 보여 `getAllByText('—')` 가 0개, 오류 토스트 때문에 `alert` 2개, 로딩 중 `status` 역할 없음("불러오는 중…" 텍스트).

- [ ] **Step 3: 구현**

`admin-ui/src/pages/TenantsListPage.tsx` 1–17행 import 를 아래로 교체:
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
import { kstDate, kstToday } from '@/lib/kst';
import { isExpiringSoon } from '@/lib/apiKeyExpiry';
import { useLoad } from '@/lib/useLoad';
import { statusLabel } from '@/i18n/labels';
import { illustrations } from '@/assets/illustrations';
import {
  Button, Card, DataTable, Dialog, EmptyState, Field, KpiCard, LoadError, PageHeader, Pagination,
  RemovableChip, Segmented, Skeleton, StatusBadge, useToast, type Column,
} from '@/ui';
```

52–305행 `export default function TenantsListPage() { … }` 전체를 아래로 교체:
```tsx
export default function TenantsListPage() {
  // 로드 상태는 useLoad 하나로: loading → Skeleton, failed(데이터 없음) → LoadError, 재시도 중에는 이전 데이터 유지.
  const list = useLoad(() => tenantsApi.list(), []);
  const loaded = list.data !== undefined;
  const tenants = useMemo<Tenant[]>(() => list.data ?? [], [list.data]);
  const [keys, setKeys] = useState<ApiKeyView[] | null>(null);
  const [showNew, setShowNew] = useState(false);
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(PAGE_SIZES[0]);
  const navigate = useNavigate();
  const toast = useToast();

  // 만료 임박 키 계산용 전체 키 목록. 실패해도 목록 화면은 그대로 — KPI 보조 줄만 숨긴다.
  useEffect(() => {
    apiKeysApi.listAll().then(setKeys).catch(() => setKeys(null));
  }, []);

  function onOpen(id: string) {
    navigate(`/tenants/${id}?tab=overview`);
  }

  // 실패하면 throw 한다 — NewTenantDialog 가 다이얼로그 안에 인라인으로 보여 준다(토스트 중복 없음).
  async function handleCreate(input: { name: string; slug: string; rpId: string }) {
    const t = await tenantsApi.create(input);
    toast({ kind: 'ok', title: 'Tenant 생성 완료', message: `${t.name} · WebAuthn 설정으로 이동합니다` });
    setShowNew(false);
    // 안내문("다음 단계: WebAuthn config …")과 버튼 라벨("생성하고 설정으로 이동")대로,
    // 생성 직후 새 테넌트의 WebAuthn config 탭으로 이동시켜 온보딩 흐름을 잇는다.
    navigate(`/tenants/${t.id}?tab=webauthn`);
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

      {/* 로드 전·실패 시 빈 배열로 계산한 0 을 보이지 않는다 — 실제 값이 없으면 "—". */}
      <div className="kpis kpis--3">
        <KpiCard
          label="활성 Tenant"
          value={loaded ? fmt(counts.ACTIVE) : '—'}
          hint={loaded ? `전체 ${fmt(counts.ALL)}건 · 클릭하면 활성만 보기` : undefined}
          selected={statusFilter === 'ACTIVE'}
          onClick={loaded ? () => applyStatus(statusFilter === 'ACTIVE' ? 'ALL' : 'ACTIVE') : undefined}
        />
        <KpiCard
          label="등록 Credential"
          value={loaded ? fmt(totalCredentials) : '—'}
          hint={loaded ? `모든 tenant 합산${credDelta !== undefined ? ` · 7일 ${signed(credDelta)}` : ''}` : undefined}
        />
        <KpiCard
          label="유효 API Key"
          value={loaded ? fmt(totalKeys) : '—'}
          hint={loaded ? <>ACTIVE 만{expiringKeys !== null && <> · <span style={{ color: 'var(--warn)', fontWeight: 600 }}>{`만료 임박 ${expiringKeys}`}</span></>}</> : undefined}
        />
      </div>

      {!loaded ? (
        <Card>
          {list.status === 'failed'
            ? <LoadError what="tenant 목록" error={list.error} onRetry={list.reload} retrying={list.reloading} />
            : <Skeleton rows={6} />}
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
                  `tenants-${kstToday()}.csv`,
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
```
(이 시점에는 `NewTenantDialog` 의 `onCreate` 타입이 아직 `=> void` 이다. `Promise<void>` 를 반환하는 함수는 `=> void` 에 대입 가능하므로 `tsc` 는 통과한다. 다만 실패 시 Promise 가 거부되어도 아무도 받지 않으므로 Task 18 를 바로 이어서 적용한다.)

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/TenantsListPage.test.tsx src/pages/NewTenantDialog.test.tsx`
Expected: PASS (TenantsListPage 13 tests, NewTenantDialog 7 tests)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/TenantsListPage.tsx admin-ui/src/pages/TenantsListPage.test.tsx
git commit -m "fix(admin-ui): Tenants 목록 로드 상태를 Skeleton·LoadError 로 통일하고 로드 전·실패 시 KPI 를 0 대신 — 로 표시

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 18: 신규 tenant 다이얼로그 — "생성 중…"·연타 방지, 실패는 다이얼로그 안 인라인 경고

**Files:**
- Modify: `admin-ui/src/pages/TenantsListPage.tsx` (307–402행 `NewTenantDialog`)
- Test: `admin-ui/src/pages/NewTenantDialog.test.tsx`, `admin-ui/src/pages/TenantsListPage.test.tsx`

**Interfaces:**
- Consumes: `describeError` (Task 1), `Alert`, `Button`, `Dialog` (`dismissible`)
- Produces: `NewTenantDialog` 의 `onCreate` 시그니처 변경 — `onCreate: (i: { name: string; slug: string; rpId: string }) => Promise<void> | void`. 거부(throw)하면 다이얼로그가 열린 채 인라인 경고를 보이고 입력값을 유지한다. 성공 시 닫기는 호출자 책임(기존과 동일).

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/pages/NewTenantDialog.test.tsx` 1–15행을 아래로 교체(헬퍼가 비동기 `onCreate` 를 받도록):
```tsx
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, within, act } from '@testing-library/react';
import { NewTenantDialog } from './TenantsListPage';
import { ApiError } from '@/api/types';

afterEach(cleanup);

type CreateFn = (i: { name: string; slug: string; rpId: string }) => Promise<void> | void;

function renderDialog(onCreate: CreateFn = vi.fn()) {
  render(<NewTenantDialog open={true} onClose={vi.fn()} onCreate={onCreate} />);
  return {
    name: screen.getByPlaceholderText('예: Acme Corp') as HTMLInputElement,
    slug: screen.getByPlaceholderText('acme-corp') as HTMLInputElement,
    rpId: screen.getByPlaceholderText('예: passkey.acme.com') as HTMLInputElement,
    onCreate,
  };
}

function fillValid(f: ReturnType<typeof renderDialog>) {
  fireEvent.change(f.name, { target: { value: 'Acme' } });
  fireEvent.change(f.slug, { target: { value: 'acme' } });
  fireEvent.change(f.rpId, { target: { value: 'passkey.acme.com' } });
}
```

파일 끝 `});`(82행) 바로 앞에 추가:
```tsx

  it('V3: 생성 중에는 "생성 중…"으로 비활성, 취소도 비활성, 연타해도 onCreate 한 번', async () => {
    let resolve!: () => void;
    const onCreate = vi.fn(() => new Promise<void>((r) => { resolve = r; }));
    const f = renderDialog(onCreate);
    fillValid(f);
    fireEvent.click(screen.getByRole('button', { name: '생성하고 설정으로 이동' }));
    const busy = screen.getByRole('button', { name: '생성 중…' });
    expect(busy).toBeDisabled();
    expect(screen.getByRole('button', { name: '취소' })).toBeDisabled();
    fireEvent.click(busy);
    expect(onCreate).toHaveBeenCalledTimes(1);
    await act(async () => { resolve(); });
  });

  it('V3: 생성 실패는 다이얼로그 안 인라인 경고, 입력값 유지, 다시 누를 수 있다', async () => {
    const onCreate = vi.fn().mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503', undefined, 'tr-9'));
    const f = renderDialog(onCreate);
    fillValid(f);
    fireEvent.click(screen.getByRole('button', { name: '생성하고 설정으로 이동' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Tenant를 만들지 못했습니다');
    expect(alert).toHaveTextContent('서버가 잠시 응답하지 않습니다');
    expect(alert).toHaveTextContent('입력한 내용은 그대로 있습니다');
    expect(alert).toHaveTextContent('HTTP 503 · trace tr-9');
    expect(f.name.value).toBe('Acme');
    expect(f.slug.value).toBe('acme');
    expect(f.rpId.value).toBe('passkey.acme.com');
    const retry = screen.getByRole('button', { name: '생성하고 설정으로 이동' });
    expect(retry).toBeEnabled();
    fireEvent.click(retry);
    expect(onCreate).toHaveBeenCalledTimes(2);
  });
```

`admin-ui/src/pages/TenantsListPage.test.tsx` 의 `beforeEach`(33–36행)를 아래로 교체:
```tsx
beforeEach(() => {
  vi.mocked(tenantsApi.list).mockReset();
  vi.mocked(tenantsApi.create).mockReset();
  vi.mocked(apiKeysApi.listAll).mockReset().mockResolvedValue([]);
});
```

같은 파일 끝 `});` 바로 앞에 추가:
```tsx

  it('V3: 생성 실패는 다이얼로그 안에만 보이고 오류 토스트로 중복하지 않는다', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    vi.mocked(tenantsApi.create).mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderPage();
    await screen.findByText('Tenant a1');
    fireEvent.click(screen.getByRole('button', { name: '신규 tenant' }));
    const dlg = screen.getByRole('dialog', { name: '신규 Tenant 생성' });
    fireEvent.change(within(dlg).getByPlaceholderText('예: Acme Corp'), { target: { value: 'Acme' } });
    fireEvent.change(within(dlg).getByPlaceholderText('acme-corp'), { target: { value: 'acme' } });
    fireEvent.change(within(dlg).getByPlaceholderText('예: passkey.acme.com'), { target: { value: 'passkey.acme.com' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '생성하고 설정으로 이동' }));
    expect(await within(dlg).findByRole('alert')).toHaveTextContent('Tenant를 만들지 못했습니다');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(screen.getByRole('dialog', { name: '신규 Tenant 생성' })).toBeInTheDocument();
  });

  it('V3: 생성 성공 → 새 tenant 의 WebAuthn 탭으로 이동', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    vi.mocked(tenantsApi.create).mockResolvedValue(tenant('new1', { name: 'Acme' }));
    const { router } = renderPage();
    await screen.findByText('Tenant a1');
    fireEvent.click(screen.getByRole('button', { name: '신규 tenant' }));
    const dlg = screen.getByRole('dialog', { name: '신규 Tenant 생성' });
    fireEvent.change(within(dlg).getByPlaceholderText('예: Acme Corp'), { target: { value: 'Acme' } });
    fireEvent.change(within(dlg).getByPlaceholderText('acme-corp'), { target: { value: 'acme' } });
    fireEvent.change(within(dlg).getByPlaceholderText('예: passkey.acme.com'), { target: { value: 'passkey.acme.com' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '생성하고 설정으로 이동' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/tenants/new1'));
    expect(router.state.location.search).toBe('?tab=webauthn');
  });
```
그리고 3행 import 에 `waitFor` 추가:
```tsx
import { screen, fireEvent, within, waitFor } from '@testing-library/react';
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/NewTenantDialog.test.tsx src/pages/TenantsListPage.test.tsx`
Expected: FAIL — "생성 중…" 버튼 없음, 실패 시 `alert` 가 다이얼로그에 없음(현재는 Promise 거부가 처리되지 않음).

- [ ] **Step 3: 구현**

`admin-ui/src/pages/TenantsListPage.tsx` import(Task 17 결과)에서 세 곳을 바꾼다:
```tsx
import { useState, useEffect, useMemo } from 'react';
```
→
```tsx
import { useState, useEffect, useMemo, useRef } from 'react';
```
```tsx
import { useLoad } from '@/lib/useLoad';
```
→
```tsx
import { useLoad } from '@/lib/useLoad';
import { describeError } from '@/lib/describeError';
```
```tsx
import {
  Button, Card, DataTable, Dialog, EmptyState, Field, KpiCard, LoadError, PageHeader, Pagination,
```
→
```tsx
import {
  Alert, Button, Card, DataTable, Dialog, EmptyState, Field, KpiCard, LoadError, PageHeader, Pagination,
```

같은 파일의 `export function NewTenantDialog(…) { … }`(`// ── NewTenantDialog` 구획의 `NEXT_STEPS` 다음부터 파일 끝까지) 전체를 아래로 교체(`NEXT_STEPS` 상수는 그대로 둔다):
```tsx
export function NewTenantDialog({ open, onClose, onCreate }: {
  open: boolean;
  onClose: () => void;
  /** 거부(throw)하면 다이얼로그 안에 인라인 경고를 보이고 입력값을 유지한다. 성공 시 닫기는 호출자가 한다. */
  onCreate: (i: { name: string; slug: string; rpId: string }) => Promise<void> | void;
}) {
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [rpId, setRpId] = useState('');
  const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<unknown>(null);
  const slugRe = /^[a-z0-9][a-z0-9-]{1,62}$/;
  const slugOk = slugRe.test(slug);
  // rpId 는 WebAuthn RP ID — registrable domain(hostname). 스킴/포트/경로 없이
  // 점이 포함된 호스트명이어야 한다(예: passkey.acme.com). placeholder 그대로 저장 방지.
  const rpIdRe = /^(?!.*\.example\.com$)([a-z0-9-]+\.)+[a-z]{2,}$/;
  const rpIdOk = rpIdRe.test(rpId.trim());

  // 닫힐 때(open: true → false)만 폼을 비운다. 생성 실패로 열린 채 남으면 입력값은 유지된다.
  useEffect(() => {
    if (!open) { setName(''); setSlug(''); setRpId(''); setTouched(false); setError(null); }
  }, [open]);

  async function submit() {
    if (busyRef.current) return; // 연타 방지 — state 반영 전 두 번째 클릭도 막는다.
    setTouched(true);
    if (!name || !slugOk || !rpIdOk) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await onCreate({ name, slug, rpId: rpId.trim() });
      // 성공 시 닫기는 부모(handleCreate → setShowNew(false))가 한다.
    } catch (e) {
      setError(e);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  const slugError = touched && slug && !slugOk
    ? '영문 소문자·숫자로 시작하고, 영문 소문자·숫자·하이픈(-)만 사용해 2~63자로 입력하세요.'
    : undefined;
  const rpIdError = touched && rpId && !rpIdOk
    ? '스킴·포트·경로 없이 실제 도메인 hostname을 입력하세요(예: passkey.acme.com). example.com은 placeholder라 사용할 수 없습니다.'
    : undefined;

  const info = error ? describeError(error) : null;
  const meta = info ? [info.httpStatus ? `HTTP ${info.httpStatus}` : null, info.traceId ? `trace ${info.traceId}` : null].filter(Boolean).join(' · ') : '';

  return (
    <Dialog
      open={open}
      onClose={busy ? () => {} : onClose}
      dismissible={!busy}
      title="신규 Tenant 생성"
      sub="RP 회사 하나에 대한 격리된 Passkey 환경을 만듭니다."
      footer={<>
        <Button onClick={onClose} disabled={busy}>취소</Button>
        <Button variant="primary" onClick={() => void submit()} disabled={busy}>{busy ? '생성 중…' : '생성하고 설정으로 이동'}</Button>
      </>}
    >
      {info && (
        <Alert tone="bad" title="Tenant를 만들지 못했습니다">
          {`${info.title}. ${info.detail} 입력한 내용은 그대로 있습니다.`}
          {meta && <span className="mono" style={{ display: 'block', marginTop: 4, fontSize: 12 }}>{meta}</span>}
        </Alert>
      )}
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

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/NewTenantDialog.test.tsx src/pages/TenantsListPage.test.tsx`
Expected: PASS (NewTenantDialog 9 tests, TenantsListPage 15 tests)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/TenantsListPage.tsx admin-ui/src/pages/NewTenantDialog.test.tsx admin-ui/src/pages/TenantsListPage.test.tsx
git commit -m "fix(admin-ui): 신규 tenant 생성 중 버튼 비활성·연타 방지, 실패는 다이얼로그 안 인라인 경고로 입력값 유지

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 19: Activity — "보안 실패만" 스위치 제거, `category`·`tenantId` 필터를 URL 에 보존

**Files:**
- Modify: `admin-ui/src/pages/ActivityPage.tsx` (15–17행 import, 246행, 258행, 263–265행, 423–427행, 519–524행)
- Test: `admin-ui/src/pages/ActivityPage.test.tsx`

**Interfaces:**
- Produces: `/activity` 의 쿼리 계약 — `category=ops|security`(없으면 전체), `tenantId=<id>`(없으면 전체). 둘 다 `replace` 로 갱신(뒤로 가기 기록을 쌓지 않음). `TenantOverview.tsx:204` 의 `/activity?tenantId=…` 링크와 호환.

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/pages/ActivityPage.test.tsx` 117–128행의 기존 테스트 "보안 실패만 스위치 = category=security, …" 를 아래로 교체(스위치 제거에 맞춘 새 기대값 — 같은 동작인 보안 세그먼트를 검증):
```tsx
  it('"보안 실패만" 스위치는 없고, 보안 세그먼트가 category=security 로 조회한다', async () => {
    renderPage();
    await screen.findByRole('group', { name: '오늘' });
    expect(screen.queryByRole('switch')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '보안' }));
    await waitFor(() =>
      expect(activityApi.fetch).toHaveBeenLastCalledWith(null, 'security', undefined, undefined),
    );
    expect(screen.getByRole('button', { name: '보안' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('카테고리: 보안')).toBeInTheDocument();
  });

  it('카테고리·tenant 필터는 URL(category·tenantId)에 남고, URL 로 열면 그대로 적용된다', async () => {
    const { router } = renderWithDataRouter(<ToastHost><ActivityPage /></ToastHost>, { initialPath: '/activity?category=security&tenantId=t1' });
    await screen.findByRole('group', { name: '오늘' });
    expect(activityApi.fetch).toHaveBeenCalledWith(null, 'security', undefined, 't1');
    expect(screen.getByRole('button', { name: '보안' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('테넌트: acme')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '운영' }));
    await waitFor(() => expect(router.state.location.search).toBe('?category=ops&tenantId=t1'));
    await waitFor(() => expect(activityApi.fetch).toHaveBeenLastCalledWith(null, 'ops', undefined, 't1'));

    fireEvent.click(screen.getByRole('button', { name: '테넌트 필터 제거' }));
    await waitFor(() => expect(router.state.location.search).toBe('?category=ops'));

    fireEvent.click(screen.getByRole('button', { name: '모두 지우기' }));
    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(screen.getByRole('button', { name: '전체' })).toHaveAttribute('aria-pressed', 'true');
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/ActivityPage.test.tsx`
Expected: FAIL — `queryByRole('switch')` 가 스위치를 찾음, 운영 클릭 후 `location.search` 가 `?category=security&tenantId=t1` 그대로.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/ActivityPage.tsx` 15–17행:
```tsx
import {
  Button, CopyBtn, EmptyState, IconButton, KpiCard, PageHeader, RemovableChip, Segmented, Switch, useToast,
} from '@/ui';
```
→
```tsx
import {
  Button, CopyBtn, EmptyState, IconButton, KpiCard, PageHeader, RemovableChip, Segmented, useToast,
} from '@/ui';
```

246행:
```tsx
  const [searchParams] = useSearchParams();
```
→
```tsx
  const [searchParams, setSearchParams] = useSearchParams();

  // 카테고리·tenant 필터는 URL(category·tenantId)이 단일 출처 — 새로고침·링크 공유에도 유지된다.
  const rawCategory = searchParams.get('category');
  const categoryFilter: ActivityCategory = rawCategory === 'ops' || rawCategory === 'security' ? rawCategory : 'all';
  const selectedTenant = searchParams.get('tenantId') || undefined;

  function setFilterParam(key: 'category' | 'tenantId', value: string | null) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      if (value) next.set(key, value); else next.delete(key);
      return next;
    }, { replace: true });
  }
  function setCategoryFilter(c: ActivityCategory) { setFilterParam('category', c === 'all' ? null : c); }
  function setSelectedTenant(id: string | undefined) { setFilterParam('tenantId', id ?? null); }
```

258행 `const [categoryFilter, setCategoryFilter] = useState<ActivityCategory>('all');` 를 삭제한다.

263–265행:
```tsx
  const [selectedTenant, setSelectedTenant] = useState<string | undefined>(
    searchParams.get('tenantId') ?? undefined,
  );
```
를 삭제한다.

423–427행 `clearFilters` 를 교체:
```tsx
  function clearFilters() {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.delete('category');
      next.delete('tenantId');
      return next;
    }, { replace: true });
    setActionQuery('');
  }
```

519–524행의 스위치 블록을 삭제한다:
```tsx
          {/* "보안 실패만" = category=security 의 다른 보기. 서버 SECURITY_ACTIONS 가 실패 액션뿐이다. */}
          <Switch
            label="보안 실패만"
            checked={categoryFilter === 'security'}
            onChange={(on) => setCategoryFilter(on ? 'security' : 'all')}
          />
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/ActivityPage.test.tsx`
Expected: PASS (16 tests — p95 관련 2개 포함 기존 전부)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/ActivityPage.tsx admin-ui/src/pages/ActivityPage.test.tsx
git commit -m "fix(admin-ui): Activity 보안 실패만 스위치 제거, 카테고리·tenant 필터를 URL 에 보존

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 20: Activity — Live 연결 상태("실시간 / 연결 끊김 · N초 전 갱신"), 로드 실패 `LoadError`, 로딩 `Skeleton`

**Files:**
- Modify: `admin-ui/src/pages/ActivityPage.tsx` (1–17행 import, 148–199행 `DetailPanel`, 241행~끝 `ActivityPage`)
- Test: `admin-ui/src/pages/ActivityPage.test.tsx`

**Interfaces:**
- Consumes: `describeError` (Task 1), `LoadError` (Task 2), `Skeleton` (Task 3), `Alert`, `kstDateTime`
- Produces: 없음(페이지 내부). 상태 규칙: 서버 데이터를 한 번도 받지 못한 실패 → `LoadError`(KPI `—`, 새로고침·내보내기 비활성). 받은 뒤의 폴링 실패 → 목록 유지 + 헤더 "연결 끊김 · N초 전 갱신"(회색 점·경고색) + 경고 배너 "지금 다시 연결". 다음 성공 시 "실시간 · N초 전 갱신".

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/pages/ActivityPage.test.tsx` 130–135행의 기존 테스트 "첫 로드 실패 시 불러오기 실패 상태" 를 아래로 교체(LoadError 문구로 새 기대값):
```tsx
  it('첫 로드 실패 시 불러오기 실패 상태(LoadError, KPI "—", 내보내기 비활성)', async () => {
    vi.mocked(activityApi.fetch).mockRejectedValue(new Error('down'));
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('활동을 불러오지 못했습니다');
    expect(within(alert).getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
    expect(screen.getAllByText('—')).toHaveLength(3);
    expect(screen.getByRole('button', { name: '내보내기' })).toBeDisabled();
    expect(screen.queryByText(/^실시간/)).toBeNull();
  });

  it('첫 로드 실패 → 다시 시도 성공하면 목록을 보인다', async () => {
    vi.mocked(activityApi.fetch).mockRejectedValueOnce(new Error('down'));
    renderPage();
    const alert = await screen.findByRole('alert');
    fireEvent.click(within(alert).getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('group', { name: '오늘' })).toBeInTheDocument();
    expect(screen.getByText(/^실시간 · \d+초 전 갱신$/)).toBeInTheDocument();
  });
```

파일 끝 `});` 바로 앞에 추가:
```tsx

  it('불러오는 동안 Skeleton, "로딩 중…" 텍스트 없음', async () => {
    vi.mocked(activityApi.fetch).mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(await screen.findByRole('status', { name: '불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText(/로딩 중…/)).toBeNull();
  });

  it('폴링이 실패하면 "연결 끊김 · N초 전 갱신" + 목록 유지, 다시 연결되면 "실시간"', async () => {
    vi.useRealTimers();
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(NOW);
    renderPage();
    await screen.findByRole('group', { name: '오늘' });
    expect(screen.getByText(/^실시간 · \d+초 전 갱신$/)).toBeInTheDocument();

    vi.mocked(activityApi.fetch).mockRejectedValue(new Error('down'));
    await act(async () => { vi.advanceTimersByTime(5000); });
    await waitFor(() => expect(screen.getByText(/^연결 끊김 · \d+초 전 갱신$/)).toBeInTheDocument());
    expect(screen.getByText('새 이벤트를 받지 못하고 있습니다')).toBeInTheDocument();
    expect(screen.getByText(/아래 목록은 12:00:00 기준입니다/)).toBeInTheDocument();
    expect(screen.getByRole('group', { name: '오늘' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull(); // 오류 토스트·LoadError 로 바꾸지 않는다

    vi.mocked(activityApi.fetch).mockResolvedValue(view(FEED));
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '지금 다시 연결' })); });
    await waitFor(() => expect(screen.getByText(/^실시간 · \d+초 전 갱신$/)).toBeInTheDocument());
    expect(screen.queryByText('새 이벤트를 받지 못하고 있습니다')).toBeNull();
  });

  it('상세 로드 실패는 패널 안 LoadError, 다시 시도로 재요청', async () => {
    vi.mocked(activityApi.fetchDetail).mockRejectedValueOnce(new Error('x'));
    renderPage();
    const panel = await openFirstDetail();
    expect(await within(panel).findByRole('alert')).toHaveTextContent('이벤트 상세를 불러오지 못했습니다');
    fireEvent.click(within(panel).getByRole('button', { name: '다시 시도' }));
    expect(await within(panel).findByRole('group', { name: '언제' })).toBeInTheDocument();
    expect(activityApi.fetchDetail).toHaveBeenCalledTimes(2);
  });
```
(`12:00:00` 은 `NOW = 2026-10-11T03:00:00Z` 의 KST 시각 — 첫 응답이 가짜 Date 기준 NOW 에 도착한다.)

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/ActivityPage.test.tsx`
Expected: FAIL — "활동을 불러오지 못했습니다" 없음(현재 "Activity 데이터를 불러오지 못했습니다"), "실시간 · N초 전 갱신" 없음(현재 "Live"), 폴링 실패가 조용히 무시됨, 상세 실패가 토스트.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/ActivityPage.tsx` 1–17행 import 를 아래로 교체:
```tsx
// admin-ui/src/pages/ActivityPage.tsx
import { useState, useEffect, useRef, useMemo, type ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { activityApi } from '@/api/activity';
import { downloadCsv } from '@/lib/csvExport';
import { kstDateTime, kstTime, kstToday, utcIso, groupByDay } from '@/lib/kst';
import { describeError } from '@/lib/describeError';
import type { ActivityView, ActivityCategory, ActivityDetailView } from '@/api/types';
import { adaptFeedItems, type RecentActivityEvent } from './tenant/recentActivityAdapter';
import { actionLabel, eventSentence } from './activityLabels';
import { buildDiffRows, arrayChange, type DiffRow } from './activityDiff';
import { tenantsApi } from '@/api/tenants';
import type { Tenant } from '@/api/designTypes';
import { illustrations } from '@/assets/illustrations';
import {
  Alert, Button, CopyBtn, EmptyState, IconButton, KpiCard, LoadError, PageHeader, RemovableChip, Segmented, Skeleton, useToast,
} from '@/ui';
```

148–163행(`DetailPanel` 시그니처부터 로딩 줄까지):
```tsx
function DetailPanel({
  detail, loading, onClose, onOpenTenant,
}: { detail: ActivityDetailView | null; loading: boolean; onClose: () => void; onOpenTenant: (id: string | null) => void }) {
  ...
      <div className="card__body stack-4" style={{ gap: 18 }}>
        {loading && <p className="muted" style={{ margin: 0, fontSize: 13 }}>불러오는 중…</p>}
```
→
```tsx
function DetailPanel({
  detail, loading, error, onRetry, onClose, onOpenTenant,
}: { detail: ActivityDetailView | null; loading: boolean; error: unknown; onRetry: () => void; onClose: () => void; onOpenTenant: (id: string | null) => void }) {
  ...
      <div className="card__body stack-4" style={{ gap: 18 }}>
        {loading && <Skeleton rows={4} label="상세 불러오는 중" />}
        {!loading && error != null && <LoadError compact what="이벤트 상세" error={error} onRetry={onRetry} />}
```
(`...` 는 151–161행 그대로. 164행 이하 `{!loading && detail && (` 블록은 그대로.)

241행 `// ── ActivityPage ─…` 부터 파일 끝(614행)까지를 아래로 교체(Task 19 의 URL 필터 코드를 포함한 최종본):
```tsx
// ── Live 연결 상태 ────────────────────────────────────────────────────────────

/** 폴링 상태. 실패하면 초록 "실시간" 대신 회색 점 + 경고색 "연결 끊김" 과 마지막 갱신 경과 시간을 보인다. */
function LiveStatus({ disconnected, lastOkAt, now }: { disconnected: boolean; lastOkAt: number | null; now: number }) {
  const ago = lastOkAt === null ? '' : ` · ${Math.max(0, Math.floor((now - lastOkAt) / 1000))}초 전 갱신`;
  return (
    <span className="row" style={{ gap: 6, fontSize: 12, color: disconnected ? 'var(--warn)' : 'var(--mute)', fontWeight: disconnected ? 600 : undefined }}>
      <span className="dot" aria-hidden="true" style={{ background: disconnected ? 'var(--faint)' : 'var(--ok)' }} />
      {disconnected ? `연결 끊김${ago}` : `실시간${ago}`}
    </span>
  );
}

// ── ActivityPage ──────────────────────────────────────────────────────────────

const POLL_MS = 5000;

export default function ActivityPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [searchParams, setSearchParams] = useSearchParams();

  // 카테고리·tenant 필터는 URL(category·tenantId)이 단일 출처 — 새로고침·링크 공유에도 유지된다.
  const rawCategory = searchParams.get('category');
  const categoryFilter: ActivityCategory = rawCategory === 'ops' || rawCategory === 'security' ? rawCategory : 'all';
  const selectedTenant = searchParams.get('tenantId') || undefined;

  // null = 아직 서버 응답 없음
  const [events, setEvents] = useState<DisplayEvent[] | null>(null);
  const [kpi, setKpi] = useState(EMPTY_KPI);
  const [topTenants, setTopTenants] = useState<{ tenantId: string; tenantName: string; tenantSlug: string; count: number }[]>([]);
  // 데이터를 한 번도 받지 못한 실패 → loadError(LoadError). 받은 뒤의 실패 → disconnected(목록 유지).
  const [loadError, setLoadError] = useState<unknown>(null);
  const [disconnected, setDisconnected] = useState(false);
  const [lastOkAt, setLastOkAt] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [retrying, setRetrying] = useState(false);

  const [detail, setDetail] = useState<ActivityDetailView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<unknown>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const [visibleCount, setVisibleCount] = useState(24);

  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [actionQuery, setActionQuery] = useState('');

  function setFilterParam(key: 'category' | 'tenantId', value: string | null) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      if (value) next.set(key, value); else next.delete(key);
      return next;
    }, { replace: true });
  }
  function setCategoryFilter(c: ActivityCategory) { setFilterParam('category', c === 'all' ? null : c); }
  function setSelectedTenant(id: string | undefined) { setFilterParam('tenantId', id ?? null); }

  // Load tenant list once for the filter dropdown
  useEffect(() => {
    tenantsApi.list().then(setTenants).catch(() => setTenants([]));
  }, []);

  // "N초 전 갱신" 을 1초마다 다시 그린다.
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  // 5-second polling. 필터(category/tenant)가 바뀔 때마다 세대를 올리고, 이전 세대의 늦은 응답은 버린다.
  const queryGenRef = useRef(0);
  // Track whether we've had at least one successful server response
  const hasServerDataRef = useRef(false);

  function applyView(view: ActivityView, mode: 'replace' | 'merge') {
    const adapted = adaptServerView(view);
    hasServerDataRef.current = true;
    const t = Date.now();
    setLoadError(null);
    setDisconnected(false);
    setLastOkAt(t);
    setNow(t);
    // merge: 폴링은 최신 한 페이지만 받으므로, 아직 없는 행만 앞에 붙여 '더 보기'로 불러온 행을 유지한다.
    setEvents((prev) => {
      if (mode === 'replace' || prev === null) return adapted.events;
      const have = new Set(prev.map((e) => e.id));
      const fresh = adapted.events.filter((e) => !have.has(e.id));
      return fresh.length === 0 ? prev : [...fresh, ...prev];
    });
    setKpi((prev) => ({
      ...prev,
      ...adapted.kpi,
      // p95Ms: server may return null; keep previous value as fallback
      p95Ms: adapted.kpi.p95Ms ?? prev.p95Ms,
    }));
    setTopTenants(adapted.topTenants);
  }

  function onFetchFailed(e: unknown) {
    if (hasServerDataRef.current) setDisconnected(true); // 이미 보이는 목록은 지우지 않는다
    else setLoadError(e);
  }

  useEffect(() => {
    let cancelled = false;
    const gen = ++queryGenRef.current;
    let first = true;

    async function fetchOnce() {
      try {
        const view = await activityApi.fetch(null, categoryFilter === 'all' ? undefined : categoryFilter, undefined, selectedTenant);
        if (cancelled || gen !== queryGenRef.current) return;
        applyView(view, first ? 'replace' : 'merge');
        first = false;
      } catch (e) {
        if (cancelled || gen !== queryGenRef.current) return;
        onFetchFailed(e);
      }
    }

    // Reset visible window when the query (category/tenant) changes so the
    // user sees fresh first-page data, not a stale large slice.
    setVisibleCount(24);

    void fetchOnce();
    const id = setInterval(() => void fetchOnce(), POLL_MS);
    return () => {
      cancelled = true;
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

  // 마지막으로 연 상세 요청만 반영한다(A→B 순서로 열고 A 가 늦게 오거나, 로딩 중 닫은 경우).
  const detailReqRef = useRef(0);

  function openDetail(id: string) {
    const req = ++detailReqRef.current;
    setSelectedId(id);
    setDetailLoading(true);
    setDetail(null);
    setDetailError(null);
    activityApi
      .fetchDetail(id)
      .then((d) => { if (req === detailReqRef.current) setDetail(d); })
      .catch((e: unknown) => { if (req === detailReqRef.current) setDetailError(e); })
      .finally(() => { if (req === detailReqRef.current) setDetailLoading(false); });
  }

  function closeDetail() {
    detailReqRef.current++;
    setDetail(null);
    setDetailError(null);
    setDetailLoading(false);
    setSelectedId(null);
  }

  function handleOpenTenant(tenantId: string | null) {
    if (!tenantId) return;
    navigate(`/tenants/${tenantId}`);
  }

  // 새로고침 · "지금 다시 연결" · LoadError "다시 시도" 공통. 결과는 상태 표시로 알린다(토스트 중복 없음).
  function handleRefresh() {
    const gen = queryGenRef.current;
    setRetrying(true);
    activityApi
      .fetch(null, categoryFilter === 'all' ? undefined : categoryFilter, undefined, selectedTenant)
      .then((view) => { if (gen === queryGenRef.current) applyView(view, 'merge'); })
      .catch((e: unknown) => { if (gen === queryGenRef.current) onFetchFailed(e); })
      .finally(() => setRetrying(false));
  }

  function handleExport() {
    if (!events || events.length === 0) {
      toast({ kind: 'warn', title: '내보낼 데이터 없음' });
      return;
    }
    downloadCsv(
      `activity-${kstToday()}.csv`,
      ['timestamp', 'tenant', 'type', 'category', 'actor', 'subject'],
      events.map((e) => [e.ts, e.tenantSlug ?? e.tenantId ?? '', e.type, e.category, e.actorId ?? '', e.subjectId]),
    );
  }

  async function handleLoadMore() {
    if (!events || events.length === 0) return;
    const oldestEvent = events[events.length - 1];
    const oldest = oldestEvent.ts;
    const gen = queryGenRef.current;
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
      if (gen !== queryGenRef.current) return;
      const adapted = adaptServerView(more);
      setEvents((prev) => {
        const base = prev ?? [];
        const have = new Set(base.map((e) => e.id));
        return [...base, ...adapted.events.filter((e) => !have.has(e.id))];
      });
      // Expand the visible window so newly-loaded rows actually appear in the feed
      setVisibleCount((n) => n + Math.max(adapted.events.length, 24));
    } catch (err: unknown) {
      const info = describeError(err);
      toast({ kind: 'err', title: '이전 기록을 불러오지 못했습니다', message: `${info.title}. ${info.detail}` });
    }
  }

  function clearFilters() {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.delete('category');
      next.delete('tenantId');
      return next;
    }, { replace: true });
    setActionQuery('');
  }

  const header = (
    <PageHeader
      title="Activity"
      sub="전체 tenant의 패스키 등록·인증 · 운영 변경 · 보안 이벤트를 모아 봅니다."
      actions={<>
        {events !== null && <LiveStatus disconnected={disconnected} lastOkAt={lastOkAt} now={now} />}
        <Button icon={<Icons.Refresh size={14} />} onClick={handleRefresh} disabled={events === null || retrying}>새로고침</Button>
        <Button icon={<Icons.Download size={14} />} onClick={handleExport} disabled={events === null}>내보내기</Button>
      </>}
    />
  );

  // 응답 전·실패 시 KPI 는 0 이 아니라 "—".
  const kpiValue = (n: number) => (events === null ? '—' : fmt(n));
  const kpis = (
    <div className={kpi.p95Ms != null ? 'kpis' : 'kpis kpis--3'}>
      <KpiCard label="활동 (24h)" value={kpiValue(kpi.events24h)} />
      <KpiCard label="운영 액션 (24h)" value={kpiValue(kpi.ops24h)} />
      <KpiCard
        label="보안 이벤트 (24h)"
        value={<span style={{ color: events !== null && kpi.security24h > 0 ? 'var(--bad)' : undefined }}>{kpiValue(kpi.security24h)}</span>}
      />
      {/* §6: 서버가 p95Ms 를 주지 않으면(현재 항상 null) KPI 를 그리지 않는다. */}
      {kpi.p95Ms != null && <KpiCard label="평균 응답" value={`${kpi.p95Ms}ms`} hint={`p95 ${kpi.p95Ms}ms`} />}
    </div>
  );

  // 첫 응답 전: 로딩(Skeleton) 또는 첫 로드 실패(LoadError). 픽스처를 실데이터처럼 보이지 않는다.
  if (events === null) {
    return (
      <div className="page">
        {header}
        {kpis}
        <div className="card">
          {loadError != null
            ? <LoadError what="활동" error={loadError} onRetry={handleRefresh} retrying={retrying} />
            : <Skeleton rows={6} />}
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

      {disconnected && lastOkAt !== null && (
        <Alert
          tone="warn"
          title="새 이벤트를 받지 못하고 있습니다"
          actions={<Button size="sm" icon={<Icons.Refresh size={14} />} onClick={handleRefresh} disabled={retrying}>지금 다시 연결</Button>}
        >
          {`아래 목록은 ${kstDateTime(new Date(lastOkAt).toISOString()).slice(11)} 기준입니다. ${POLL_MS / 1000}초마다 자동으로 다시 연결합니다.`}
        </Alert>
      )}

      {kpis}

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
          {(detail || detailLoading || detailError != null) && (
            <DetailPanel
              detail={detail}
              loading={detailLoading}
              error={detailError}
              onRetry={() => { if (selectedId) openDetail(selectedId); }}
              onClose={closeDetail}
              onOpenTenant={handleOpenTenant}
            />
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

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/ActivityPage.test.tsx`
Expected: PASS (20 tests). 기존 "필터를 바꾼 뒤 이전 필터의 늦은 응답은 버린다", "폴링은 새 행만 앞에 붙이고 …", p95 2개도 PASS.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/ActivityPage.tsx admin-ui/src/pages/ActivityPage.test.tsx
git commit -m "fix(admin-ui): Activity 폴링 실패를 '연결 끊김 · N초 전 갱신'으로 표시하고 로드 실패·로딩을 LoadError·Skeleton 으로 통일

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 21: `Menu` — 비활성 항목(사유 표시, 클릭·Enter 무시)

**Files:**
- Modify: `admin-ui/src/ui/Menu.tsx` (6행, 62행, 64–68행)
- Modify: `admin-ui/src/styles/components.css:265`
- Test: `admin-ui/src/ui/Menu.test.tsx` (신규)

**Interfaces:**
- Produces: `MenuItem` 에 `disabled?: boolean; disabledReason?: string` 추가. 비활성 항목은 `role="menuitem"` + `aria-disabled="true"`(포커스는 받음 — 키보드 순서 유지), 사유를 라벨 아래 한 줄로 보이고 `title` 툴팁으로도 둔다. 클릭·Enter·Space 로 `onSelect` 를 호출하지 않고 메뉴도 닫지 않는다. Task 23 가 사용.

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/ui/Menu.test.tsx`:
```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Menu } from './Menu';

const REASON = '위변조 의심 tenant가 있으면 쓸 수 없습니다.';

function setup() {
  const onRun = vi.fn();
  const onOther = vi.fn();
  render(
    <Menu
      label="더 보기"
      trigger="button"
      items={[
        { label: '내보내기', onSelect: onOther },
        { label: '체인 백필…', onSelect: onRun, danger: true, disabled: true, disabledReason: REASON },
      ]}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
  return { onRun, onOther };
}

describe('Menu 비활성 항목', () => {
  it('aria-disabled 와 사유를 보이고, 클릭해도 실행·닫힘 없음', () => {
    const { onRun } = setup();
    const item = screen.getByRole('menuitem', { name: /체인 백필…/ });
    expect(item).toHaveAttribute('aria-disabled', 'true');
    expect(item).toHaveAttribute('title', REASON);
    expect(item).toHaveTextContent(REASON);
    fireEvent.click(item);
    expect(onRun).not.toHaveBeenCalled();
    expect(screen.getByRole('menu')).toBeInTheDocument();
  });

  it('Enter 로도 실행되지 않는다', () => {
    const { onRun } = setup();
    const item = screen.getByRole('menuitem', { name: /체인 백필…/ });
    item.focus();
    fireEvent.keyDown(item, { key: 'Enter' });
    expect(onRun).not.toHaveBeenCalled();
    expect(screen.getByRole('menu')).toBeInTheDocument();
  });

  it('활성 항목은 그대로 실행하고 닫힌다', () => {
    const { onOther } = setup();
    const item = screen.getByRole('menuitem', { name: '내보내기' });
    expect(item).not.toHaveAttribute('aria-disabled');
    fireEvent.click(item);
    expect(onOther).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu')).toBeNull();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/ui/Menu.test.tsx`
Expected: FAIL — `aria-disabled` 없음, 클릭 시 `onRun` 호출됨(타입 오류: `disabled` 가 `MenuItem` 에 없음).

- [ ] **Step 3: 구현**

`admin-ui/src/ui/Menu.tsx` 6행:
```tsx
export type MenuItem = { label: ReactNode; onSelect: () => void; icon?: ReactNode; danger?: boolean };
```
→
```tsx
/** disabled: 보이되 실행되지 않는 항목(aria-disabled, 포커스 순서 유지). disabledReason 은 라벨 아래와 툴팁에 보인다. */
export type MenuItem = { label: ReactNode; onSelect: () => void; icon?: ReactNode; danger?: boolean; disabled?: boolean; disabledReason?: string };
```

62행:
```tsx
    else if ((e.key === 'Enter' || e.key === ' ') && i >= 0) { e.preventDefault(); all[i].onSelect(); close(true); }
```
→
```tsx
    else if ((e.key === 'Enter' || e.key === ' ') && i >= 0) { e.preventDefault(); if (all[i].disabled) return; all[i].onSelect(); close(true); }
```

64–68행 `renderItem` 을 교체:
```tsx
  const renderItem = (it: MenuItem, k: number) => (
    <button
      key={k}
      type="button"
      role="menuitem"
      tabIndex={-1}
      aria-disabled={it.disabled || undefined}
      title={it.disabled ? it.disabledReason : undefined}
      className={cn('menu__item', it.danger && 'menu__item--danger')}
      onClick={(e) => { e.stopPropagation(); if (it.disabled) return; it.onSelect(); close(true); }}
    >
      {it.icon}
      {it.disabled && it.disabledReason ? (
        <span style={{ display: 'flex', flexDirection: 'column', gap: 2, alignItems: 'flex-start', padding: '6px 0' }}>
          <span>{it.label}</span>
          <span className="hint" style={{ margin: 0, whiteSpace: 'normal' }}>{it.disabledReason}</span>
        </span>
      ) : it.label}
    </button>
  );
```

`admin-ui/src/styles/components.css` 265행:
```css
.menu__item:disabled { color: var(--faint); cursor: not-allowed; background: transparent; }
```
→
```css
.menu__item:disabled, .menu__item[aria-disabled="true"] { color: var(--faint); cursor: not-allowed; background: transparent; }
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/ui/Menu.test.tsx src/ui/misc.test.tsx`
Expected: PASS (Menu 3 tests + 기존 misc 전부)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/ui/Menu.tsx admin-ui/src/ui/Menu.test.tsx admin-ui/src/styles/components.css
git commit -m "feat(admin-ui): Menu 비활성 항목 — 사유 표시, 클릭·Enter 로 실행되지 않음

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 22: 감사 체인 — 재검증 중 데이터 유지·단일 "지금 검증", 위변조 배너에 모든 tenant + 행별 Incident

**Files:**
- Modify: `admin-ui/src/pages/AuditChainPage.tsx` (전체 교체)
- Test: `admin-ui/src/pages/AuditChainPage.test.tsx`

**Interfaces:**
- Consumes: `useLoad` (Task 1), `describeError` (Task 1), `LoadError` (Task 2), `Skeleton` (Task 3)
- Produces: 없음(페이지 내부). 화면 계약: 헤더 주 버튼 하나 "지금 검증"(처리 중 "검증 중…", 숫자 진행률 없음). 재검증 중에는 표·배너·KPI 가 이전 결과를 유지한다. 위변조 배너는 `/overview` 의 `intact=false` tenant 를 모두 나열하고 행마다 "감사 로그 열기"(`/tenants/:id?tab=audit`)와 "Incident 생성"(OPEN incident 가 이미 있으면 "Incident 처리 중" 배지)을 둔다. 백필·월간 보고서 검증은 Task 23.

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/pages/AuditChainPage.test.tsx` 2–3행 import 를 교체:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, fireEvent, within, waitFor, act } from '@testing-library/react';
```

114–120행 기존 테스트 "overview 실패 시 오류 상태 + 다시 시도" 를 교체(LoadError 는 제목과 원문을 한 문단에 보이므로 `toHaveTextContent` 로):
```tsx
  it('overview 실패 시 오류 상태 + 다시 시도', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockRejectedValue(new Error('overview down'));
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('감사 체인 상태를 불러오지 못했습니다');
    expect(alert).toHaveTextContent('overview down');
    expect(within(alert).getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
    expect(screen.queryByText(/모든 tenant 정상/)).toBeNull();
  });
```

122–133행 기존 테스트 "위변조면 bad 배너 → …" 를 교체(배너 제목이 V3 문구로 바뀜):
```tsx
  it('위변조면 bad 배너 → Incident 생성 다이얼로그 → createIncident(tenantId)', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(overview([row('t1', 'Acme', true), row('t2', 'Globex', false)]));
    vi.mocked(auditChainMonitorApi.createIncident).mockResolvedValue(incident());
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('위변조 의심 — 1개 tenant 모두 조사가 필요합니다');
    expect(alert).toHaveTextContent('Globex chain 에서 hash 불일치가 감지되었습니다.');
    fireEvent.click(within(alert).getByRole('button', { name: 'Incident 생성' }));
    const dialog = screen.getByRole('dialog', { name: 'Incident 생성' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Incident 생성' }));
    await waitFor(() => expect(auditChainMonitorApi.createIncident).toHaveBeenCalledWith('t2'));
  });
```

172–177행 기존 테스트 "전체 즉시 검증은 읽기 전용 …" 을 교체(버튼 통합 — 이름이 "지금 검증"):
```tsx
  it('지금 검증은 읽기 전용 — overview 를 다시 읽고 backfill 을 부르지 않는다', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '지금 검증' }));
    await waitFor(() => expect(auditChainMonitorApi.overview).toHaveBeenCalledTimes(2));
    expect(auditChainMonitorApi.backfill).not.toHaveBeenCalled();
  });
```

파일 끝 `});` 바로 앞에 추가:
```tsx

  it('검증 버튼은 "지금 검증" 하나뿐(새로 검증·전체 즉시 검증 통합)', async () => {
    renderPage();
    await screen.findByText('Acme');
    expect(screen.getAllByRole('button', { name: '지금 검증' })).toHaveLength(1);
    expect(screen.queryByRole('button', { name: '새로 검증' })).toBeNull();
    expect(screen.queryByRole('button', { name: '전체 즉시 검증' })).toBeNull();
  });

  it('재검증 중에도 표를 유지하고 버튼은 "검증 중…"(비활성, 가짜 진행률 없음), 끝나면 새 결과', async () => {
    renderPage();
    await screen.findByText('Acme');
    let resolve!: (o: ChainOverview) => void;
    vi.mocked(auditChainMonitorApi.overview).mockImplementationOnce(() => new Promise((r) => { resolve = r; }));
    fireEvent.click(screen.getByRole('button', { name: '지금 검증' }));
    expect(screen.getByRole('button', { name: '검증 중…' })).toBeDisabled();
    expect(screen.getByText('Acme')).toBeInTheDocument();
    expect(screen.queryByRole('status', { name: '불러오는 중' })).toBeNull();
    expect(screen.queryByRole('progressbar')).toBeNull();
    await act(async () => { resolve(overview([row('t1', 'Acme', true), row('t2', 'Globex', false)])); });
    expect(screen.getByRole('button', { name: '지금 검증' })).toBeEnabled();
    expect(screen.getByRole('alert')).toHaveTextContent('Globex');
  });

  it('다시 검증이 실패해도 이전 결과를 유지하고 경고를 보인다', async () => {
    renderPage();
    await screen.findByText('Acme');
    vi.mocked(auditChainMonitorApi.overview).mockRejectedValueOnce(new Error('down'));
    fireEvent.click(screen.getByRole('button', { name: '지금 검증' }));
    expect(await screen.findByText('다시 검증하지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText('Acme')).toBeInTheDocument();
  });

  it('위변조 tenant 가 여럿이면 배너에 모두 나열하고 행마다 Incident 생성', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(
      overview([row('t1', 'Acme', true), row('t2', 'Globex', false), row('t3', 'Initech', false)]),
    );
    vi.mocked(auditChainMonitorApi.createIncident).mockResolvedValue(incident({ tenantId: 't3', tenantName: 'Initech' }));
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('위변조 의심 — 2개 tenant 모두 조사가 필요합니다');
    const items = within(alert).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    const initech = items.find((li) => li.textContent?.includes('Initech'));
    expect(initech).toBeDefined();
    fireEvent.click(within(initech!).getByRole('button', { name: 'Incident 생성' }));
    const dialog = screen.getByRole('dialog', { name: 'Incident 생성' });
    expect(dialog).toHaveTextContent('Initech');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Incident 생성' }));
    await waitFor(() => expect(auditChainMonitorApi.createIncident).toHaveBeenCalledWith('t3'));
  });

  it('OPEN incident 가 있는 tenant 는 생성 버튼 대신 "Incident 처리 중"', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(overview([row('t1', 'Acme', true), row('t2', 'Globex', false)]));
    vi.mocked(auditChainMonitorApi.listIncidents).mockResolvedValue([incident()]); // t2 OPEN
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(await within(alert).findByText('Incident 처리 중')).toBeInTheDocument();
    expect(within(alert).queryByRole('button', { name: 'Incident 생성' })).toBeNull();
  });

  it('배너의 "감사 로그 열기"는 그 tenant 의 감사 로그 탭으로 이동', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(overview([row('t1', 'Acme', true), row('t2', 'Globex', false)]));
    const { router } = renderPage();
    const alert = await screen.findByRole('alert');
    fireEvent.click(within(alert).getByRole('button', { name: '감사 로그 열기' }));
    expect(router.state.location.pathname).toBe('/tenants/t2');
    expect(router.state.location.search).toBe('?tab=audit');
  });

  it('처음 불러오는 동안은 Skeleton', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(await screen.findByRole('status', { name: '불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText('불러오는 중…')).toBeNull();
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/AuditChainPage.test.tsx`
Expected: FAIL — "지금 검증" 버튼 없음, 재검증 시 화면이 "불러오는 중…" 으로 바뀌어 'Acme' 사라짐, 배너가 첫 위변조 tenant 하나만 보임, "감사 로그 열기" 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/AuditChainPage.tsx` 전체를 아래로 교체:
```tsx
import { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { auditChainMonitorApi, type ChainOverview, type IncidentView } from '@/api/auditChainMonitor';
import { auditChainApi } from '@/api/auditChain';
import { monthlyReportApi } from '@/api/monthlyReport';
import { ApiError } from '@/api/types';
import { statusLabel } from '@/i18n/labels';
import { kstDateTime, kstPrevMonthRange } from '@/lib/kst';
import { useLoad } from '@/lib/useLoad';
import { describeError } from '@/lib/describeError';
import { illustrations } from '@/assets/illustrations';
import {
  Alert, Badge, Button, Card, CardHeader, DataTable, Dialog, EmptyState, Field, KpiCard, LoadError, PageHeader,
  Segmented, Skeleton, StatusStrip, useToast, type Column,
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
  const initial = kstPrevMonthRange(new Date());
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
const PAGE_SUB = "tenant별 감사 로그 해시 체인 무결성 — 화면을 열 때와 '지금 검증'을 누를 때 서버가 검증합니다.";

/** 아바타 글자 — 첫 문자/숫자(예: "[platform]" → "P"). */
function initial(name: string): string {
  const m = name.match(/[\p{L}\p{N}]/u);
  return (m ? m[0] : name.slice(0, 1)).toUpperCase();
}

export default function AuditChainPage() {
  // overview 호출 = 서버 전체 검증. reload() 중에는 이전 결과(data)를 그대로 보여 준다.
  const chain = useLoad(() => auditChainMonitorApi.overview(24), []);
  const overview = chain.data;
  const [showReport, setShowReport] = useState(false);
  const [backfillOpen, setBackfillOpen] = useState(false);
  const [backfilling, setBackfilling] = useState(false);
  const backfillCancelRef = useRef<HTMLButtonElement>(null);
  const [incidents, setIncidents] = useState<IncidentView[]>([]);
  const [incidentError, setIncidentError] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createTarget, setCreateTarget] = useState<ChainRow | null>(null);
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

  // Incident 목록 로드 실패는 페이지 전체를 막지 않는다(독립 처리).
  useEffect(() => { void refreshIncidents(); }, []);

  /** 다시 검증 — 화면은 이전 결과를 유지하고(chain.reloading), 끝난 결과로 바꾼다. */
  function reverify() {
    chain.reload();
    void refreshIncidents();
  }

  // 체인 백필은 검증이 아니다: 링크가 맞지 않는 행의 hash 를 다시 계산해 덮어쓰고 이후를 다시 잇는다.
  // 행이 삭제된 체인에 실행하면 공백이 다시 이어져 위변조 흔적이 사라진다 — 확인 다이얼로그 뒤에서만 실행한다.
  async function handleBackfill() {
    setBackfilling(true);
    try {
      const r = await auditChainMonitorApi.backfill();
      setBackfillOpen(false);
      toast({ kind: 'ok', title: '백필 완료', message: `tenant ${fmt(r.tenantsProcessed)}개 처리 · 행 ${fmt(r.rowsUpdated)}개 갱신` });
      reverify();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '백필 실패';
      toast({ kind: 'err', title: '백필 실패', message: msg });
    } finally {
      setBackfilling(false);
    }
  }

  function openTenant(tenantId: string | null) {
    if (tenantId) navigate(`/tenants/${tenantId}`);
  }

  function openAudit(tenantId: string | null) {
    if (tenantId) navigate(`/tenants/${tenantId}?tab=audit`);
  }

  async function handleVerifyTenant(tenantId: string) {
    setVerifyingId(tenantId);
    try {
      const r = await auditChainApi.verifyTenant(tenantId);
      toast(r.intact
        ? { kind: 'ok', title: '검증 완료', message: 'chain 이 정상입니다.' }
        : { kind: 'err', title: '위변조 의심', message: r.tamperedEntryId ? `불일치 entry ${r.tamperedEntryId}` : 'hash 불일치' });
      reverify();
    } catch (e: unknown) {
      const info = describeError(e);
      toast({ kind: 'err', title: '검증하지 못했습니다', message: `${info.title}. ${info.detail}` });
    } finally {
      setVerifyingId(null);
    }
  }

  async function handleCreateIncident(tenantId: string) {
    setCreating(true);
    try {
      await auditChainMonitorApi.createIncident(tenantId);
      setCreateTarget(null);
      toast({ kind: 'ok', title: 'Incident 생성됨', message: '보안팀 대응 incident 가 등록되었습니다.' });
      reverify();
    } catch (e: unknown) {
      if (e instanceof ApiError && e.status === 409) {
        // 다른 운영자가 이미 OPEN incident 를 만든 stale 상태일 수 있다 — 닫고 목록 동기화.
        setCreateTarget(null);
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
      reverify();
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

  // 결과가 한 번도 없을 때만 로딩/실패 화면. 재검증 중·재검증 실패에는 이전 결과를 유지한다.
  if (!overview) {
    return (
      <div className="page">
        <PageHeader title={PAGE_TITLE} sub={PAGE_SUB} />
        <Card>
          {chain.status === 'failed'
            ? <LoadError what="감사 체인 상태" error={chain.error} onRetry={chain.reload} retrying={chain.reloading} />
            : <Skeleton rows={6} />}
        </Card>
      </div>
    );
  }

  const { totals, tenants } = overview;
  const tamperedRows = tenants.filter((c) => !c.intact);
  const tampered = totals.tenantsTampered > 0 || tamperedRows.length > 0;
  const verifying = chain.reloading;
  const openIncidentTenants = new Set(incidents.filter((i) => i.status === 'OPEN').map((i) => i.tenantId));
  const showStrip = tenants.some((c) => c.recentRuns !== undefined);
  const rows = view === 'tampered' ? tamperedRows : tenants;
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
          <span className="avatar avatar--sq avatar--sm" aria-hidden="true">{initial(c.tenantName)}</span>
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
          <Button onClick={() => setBackfillOpen(true)}>체인 백필(마이그레이션)…</Button>
          <Button icon={<Icons.Download size={14} />} onClick={() => setShowReport(true)}>월간 보고서</Button>
          {/* 읽기 전용: overview 호출 시 서버가 전 tenant chain 을 검증한다. 진행률은 서버가 주지 않으므로 표시하지 않는다. */}
          <Button variant="primary" icon={<Icons.Hash size={14} />} onClick={reverify} disabled={verifying}>
            {verifying ? '검증 중…' : '지금 검증'}
          </Button>
        </>}
      />

      {chain.status === 'failed' && (
        <Alert
          tone="warn"
          title="다시 검증하지 못했습니다"
          actions={<Button size="sm" icon={<Icons.Refresh size={14} />} onClick={reverify} disabled={verifying}>다시 시도</Button>}
        >
          {`${describeError(chain.error).title}. 아래는 ${timeAgo(overview.verifiedAt)} 검증 결과입니다.`}
        </Alert>
      )}

      <section className="card" aria-label="전체 상태" style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 20, padding: '20px 24px' }}>
        <span aria-hidden="true" style={{ width: 48, height: 48, borderRadius: 12, flex: 'none', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', background: tampered ? 'var(--bad-soft)' : 'var(--ok-soft)', color: tampered ? 'var(--bad)' : 'var(--ok)' }}>
          <Icons.Link size={24} />
        </span>
        <div className="col" style={{ gap: 2, flex: '1 1 320px', minWidth: 0 }}>
          <span className="t-over">전체 상태</span>
          <strong style={{ fontSize: 20, lineHeight: '28px' }}>{headline}</strong>
          <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{lastLine}</span>
        </div>
      </section>

      {tamperedRows.length > 0 && (
        <Alert tone="bad" title={`위변조 의심 — ${tamperedRows.length}개 tenant 모두 조사가 필요합니다`}>
          <ul aria-label="위변조 의심 tenant" style={{ listStyle: 'none', margin: '8px 0 0', padding: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
            {tamperedRows.map((c) => {
              const hasOpen = c.tenantId !== null && openIncidentTenants.has(c.tenantId);
              return (
                <li key={c.tenantId ?? 'platform'} className="row" style={{ gap: 8 }}>
                  <span style={{ flex: '1 1 240px', minWidth: 0 }}>
                    <strong>{c.tenantName}</strong>
                    {' chain 에서 hash 불일치가 감지되었습니다.'}
                    {c.tamperedEntryId && ` 불일치 entry …${c.tamperedEntryId.slice(-8)}`}
                  </span>
                  <Button size="sm" onClick={() => openAudit(c.tenantId)} disabled={!c.tenantId}>
                    감사 로그 열기 <Icons.ChevronRight size={12} />
                  </Button>
                  {hasOpen ? (
                    <Badge tone="warn">Incident 처리 중</Badge>
                  ) : (
                    <Button
                      size="sm"
                      variant="danger-solid"
                      onClick={() => setCreateTarget(c)}
                      disabled={!c.tenantId}
                      title={c.tenantId ? undefined : '플랫폼 체인은 incident 대상이 아닙니다'}
                    >
                      Incident 생성
                    </Button>
                  )}
                </li>
              );
            })}
          </ul>
        </Alert>
      )}

      <div className="kpis">
        <KpiCard label="정상 / 전체" value={`${totals.tenantsIntact} / ${totals.tenantsTotal}`} />
        <KpiCard label="검증된 audit row" value={fmt(totals.verifiedRows)} />
        <KpiCard
          label="검증 방식"
          value={overview.nextRunAt === undefined ? '요청 시' : '자동'}
          hint={overview.nextRunAt === undefined ? '화면 진입 · 지금 검증' : `다음 검증 ${kstDateTime(overview.nextRunAt)} KST`}
        />
        <KpiCard label="검증 소요" value={`${fmt(totals.verificationMs)}ms`} />
      </div>

      <Card>
        <CardHeader
          title="Tenant별 Chain 상태"
          cap={`${timeAgo(overview.verifiedAt)} 검증 기준${showStrip ? ` · 스트립은 최근 ${STRIP_RUNS}회 검증` : ''}`}
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
          cap="위변조 감지 시 등록되는 대응 incident"
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
        open={createTarget !== null}
        onClose={() => { if (!creating) setCreateTarget(null); }}
        title="Incident 생성"
        sub={createTarget?.tenantName}
        footer={<>
          <Button onClick={() => setCreateTarget(null)} disabled={creating}>취소</Button>
          <Button
            variant="danger-solid"
            onClick={() => { if (createTarget?.tenantId) void handleCreateIncident(createTarget.tenantId); }}
            disabled={creating || !createTarget?.tenantId}
          >
            {creating ? '생성 중…' : 'Incident 생성'}
          </Button>
        </>}
      >
        <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>
          {`${createTarget?.tenantName ?? '—'} 의 위변조 의심을 보안 incident 로 등록하고 대응 이력을 남깁니다.`}
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

      <Dialog
        open={backfillOpen}
        onClose={() => { if (!backfilling) setBackfillOpen(false); }}
        role="alertdialog"
        title="체인 백필을 실행하시겠습니까?"
        initialFocusRef={backfillCancelRef}
        footer={<>
          <button ref={backfillCancelRef} type="button" className="btn" onClick={() => setBackfillOpen(false)} disabled={backfilling}>취소</button>
          <Button variant="danger-solid" onClick={() => void handleBackfill()} disabled={backfilling}>
            {backfilling ? '백필 중…' : '백필 실행'}
          </Button>
        </>}
      >
        <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>
          백필은 검증이 아닙니다. 이전 행과 링크가 맞지 않는 모든 행의 hash 를 다시 계산해 덮어씁니다. 그 뒤의 체인도 모두 다시 연결합니다.
        </p>
        <Alert tone="bad">
          행이 삭제된 체인에 실행하면 빈자리가 다시 이어져, 삭제 흔적(위변조 증거)이 사라집니다. 위변조가 감지된 상태라면 먼저 Incident 를 생성해 조사하세요.
        </Alert>
        <p className="hint" style={{ margin: 0 }}>hash 가 비어 있는 기존 데이터를 체인에 편입하는 마이그레이션 용도로만 사용하세요.</p>
      </Dialog>

      <MonthlyReportDialog open={showReport} onClose={() => setShowReport(false)} overview={overview} />
    </div>
  );
}
```
(백필 다이얼로그·버튼은 이 Task 에서는 기존 그대로 두고 Task 23 에서 더 보기 메뉴 + L3 로 바꾼다.)

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/AuditChainPage.test.tsx`
Expected: PASS (21 tests — 기존 14개 + 신규 7개)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/AuditChainPage.tsx admin-ui/src/pages/AuditChainPage.test.tsx
git commit -m "fix(admin-ui): 감사 체인 재검증 중 이전 결과 유지·검증 버튼 통합, 위변조 배너에 모든 tenant 와 행별 Incident 생성

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 23: 감사 체인 — 백필을 더 보기 메뉴 + `ConfirmDialog` L3 로(위변조 시 비활성), 월간 보고서 기간 인라인 검증

**Files:**
- Modify: `admin-ui/src/pages/AuditChainPage.tsx` (Task 22 결과 기준: import, 백필 state·핸들러, 헤더 actions, 백필 Dialog, `MonthlyReportDialog`)
- Test: `admin-ui/src/pages/AuditChainPage.test.tsx`

**Interfaces:**
- Consumes: `ConfirmDialog` L3 `acknowledge` (Task 4), `Menu` `disabled`/`disabledReason` (Task 21)
- Produces: 없음. 동작: 헤더 "⋯ 더 보기"(`aria-label="더 보기"`) 메뉴의 "체인 백필…" → L3 확인(체크 "…이해했습니다", 확인 버튼 "백필 실행"). 위변조 tenant 가 있으면 메뉴 항목 `aria-disabled` + 사유. 백필 실패는 다이얼로그 안 인라인 오류(ConfirmDialog). 월간 보고서: 시작일 > 종료일이면 종료일 아래 "시작일이 종료일보다 늦습니다. 날짜는 KST 기준입니다." + 생성 버튼 비활성(같은 날은 허용).

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/pages/AuditChainPage.test.tsx` 의 기존 테스트 "체인 백필은 별도 동작 — 위험을 설명하는 확인 다이얼로그(취소 기본 포커스) 후에만 실행"(179–196행)을 아래로 교체:
```tsx
  const ACK = '위변조 흔적이 사라질 수 있고 되돌릴 수 없다는 것을 이해했습니다';

  it('체인 백필은 더 보기 메뉴 안 L3 확인(취소 기본 포커스, 이해 체크) 후에만 실행', async () => {
    vi.mocked(auditChainMonitorApi.backfill).mockReset().mockResolvedValue({ tenantsProcessed: 2, rowsUpdated: 5, rowsSkipped: 0 });
    renderPage();
    await screen.findByText('Acme');
    expect(screen.queryByRole('button', { name: '체인 백필(마이그레이션)…' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '체인 백필…' }));
    const dlg = screen.getByRole('alertdialog', { name: '체인 백필을 실행할까요?' });
    expect(dlg).toHaveTextContent(/hash 를 다시 계산해 덮어씁니다/);
    expect(dlg).toHaveTextContent(/삭제/);
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    expect(within(dlg).getByRole('button', { name: '백필 실행' })).toBeDisabled();
    fireEvent.click(within(dlg).getByRole('button', { name: '취소' }));
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(auditChainMonitorApi.backfill).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '체인 백필…' }));
    const dlg2 = screen.getByRole('alertdialog', { name: '체인 백필을 실행할까요?' });
    fireEvent.click(within(dlg2).getByLabelText(ACK));
    fireEvent.click(within(dlg2).getByRole('button', { name: '백필 실행' }));
    await waitFor(() => expect(auditChainMonitorApi.backfill).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('백필 완료')).toBeInTheDocument();
    expect(screen.queryByRole('alertdialog')).toBeNull();
    await waitFor(() => expect(auditChainMonitorApi.overview).toHaveBeenCalledTimes(2));
  });

  it('백필 실패는 다이얼로그 안 인라인 오류, 다이얼로그는 열린 채', async () => {
    vi.mocked(auditChainMonitorApi.backfill).mockReset().mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderPage();
    await screen.findByText('Acme');
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '체인 백필…' }));
    const dlg = screen.getByRole('alertdialog', { name: '체인 백필을 실행할까요?' });
    fireEvent.click(within(dlg).getByLabelText(ACK));
    fireEvent.click(within(dlg).getByRole('button', { name: '백필 실행' }));
    expect(await within(dlg).findByText('백필 실행하지 못했습니다')).toBeInTheDocument();
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
  });

  it('위변조가 있으면 체인 백필 메뉴는 비활성 + 사유, 눌러도 다이얼로그가 열리지 않는다', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(overview([row('t1', 'Acme', true), row('t2', 'Globex', false)]));
    renderPage();
    await screen.findByRole('alert');
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    const item = screen.getByRole('menuitem', { name: /체인 백필…/ });
    expect(item).toHaveAttribute('aria-disabled', 'true');
    expect(item).toHaveTextContent('위변조 의심 tenant가 있으면 쓸 수 없습니다. 먼저 조사·해결하세요.');
    fireEvent.click(item);
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(auditChainMonitorApi.backfill).not.toHaveBeenCalled();
  });

  it('월간 보고서: 시작일이 종료일보다 늦으면 인라인 오류 + 생성 비활성, 고치면 해제', async () => {
    renderPage();
    await screen.findByText('Acme');
    fireEvent.click(screen.getByRole('button', { name: '월간 보고서' }));
    const dialog = screen.getByRole('dialog', { name: '월간 무결성 보고서 발급' });
    const from = within(dialog).getByLabelText('시작일');
    const to = within(dialog).getByLabelText('종료일');
    fireEvent.change(from, { target: { value: '2026-10-01' } });
    fireEvent.change(to, { target: { value: '2026-09-01' } });
    expect(within(dialog).getByText('시작일이 종료일보다 늦습니다. 날짜는 KST 기준입니다.')).toBeInTheDocument();
    expect(to).toHaveAttribute('aria-invalid', 'true');
    expect(to).toHaveAttribute('aria-describedby', 'report-to-error');
    expect(within(dialog).getByRole('button', { name: 'PDF 생성 → 다운로드' })).toBeDisabled();
    fireEvent.change(to, { target: { value: '2026-10-01' } }); // 같은 날은 허용
    expect(within(dialog).queryByText(/시작일이 종료일보다 늦습니다/)).toBeNull();
    expect(within(dialog).getByRole('button', { name: 'PDF 생성 → 다운로드' })).toBeEnabled();
  });
```
그리고 파일 위쪽 import(6행 아래)에 추가:
```tsx
import { ApiError } from '@/api/types';
```
(이 테스트 파일은 `@/api/types` 를 mock 하지 않으므로 실제 `ApiError` 를 쓴다.)

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/AuditChainPage.test.tsx`
Expected: FAIL — "더 보기" 버튼 없음, 기간 오류 문구 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/AuditChainPage.tsx` (Task 22 결과)에서:

(a) 1행과 13–16행 import:
```tsx
import { useState, useEffect, useRef } from 'react';
```
→
```tsx
import { useState, useEffect } from 'react';
```
```tsx
import {
  Alert, Badge, Button, Card, CardHeader, DataTable, Dialog, EmptyState, Field, KpiCard, LoadError, PageHeader,
  Segmented, Skeleton, StatusStrip, useToast, type Column,
} from '@/ui';
```
→
```tsx
import {
  Alert, Badge, Button, Card, CardHeader, ConfirmDialog, DataTable, Dialog, EmptyState, Field, KpiCard, LoadError, Menu,
  PageHeader, Segmented, Skeleton, StatusStrip, useToast, type Column,
} from '@/ui';
```

(b) `MonthlyReportDialog` 안의 state 아래(`const toast = useToast();` 다음 줄)에 추가:
```tsx
  // YYYY-MM-DD 문자열은 사전순 = 날짜순. 같은 날은 허용한다.
  const rangeError = from && to && from > to ? '시작일이 종료일보다 늦습니다. 날짜는 KST 기준입니다.' : undefined;
```
같은 컴포넌트의 생성 버튼과 날짜 입력을 교체:
```tsx
        <Button variant="primary" onClick={() => void handleGenerate()} disabled={busy || !from || !to}>
```
→
```tsx
        <Button variant="primary" onClick={() => void handleGenerate()} disabled={busy || !from || !to || !!rangeError}>
```
```tsx
        <Field label="시작일" htmlFor="report-from">
          <input id="report-from" className="input mono" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </Field>
        <Field label="종료일" htmlFor="report-to">
          <input id="report-to" className="input mono" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </Field>
```
→
```tsx
        <Field label="시작일" htmlFor="report-from">
          <input id="report-from" className="input mono" type="date" value={from} aria-invalid={!!rangeError}
                 onChange={(e) => setFrom(e.target.value)} />
        </Field>
        <Field label="종료일" htmlFor="report-to" error={rangeError}>
          <input id="report-to" className="input mono" type="date" value={to} aria-invalid={!!rangeError}
                 aria-describedby={rangeError ? 'report-to-error' : undefined}
                 onChange={(e) => setTo(e.target.value)} />
        </Field>
```

(c) `AuditChainPage` 의 백필 state 세 줄:
```tsx
  const [backfillOpen, setBackfillOpen] = useState(false);
  const [backfilling, setBackfilling] = useState(false);
  const backfillCancelRef = useRef<HTMLButtonElement>(null);
```
→
```tsx
  const [backfillOpen, setBackfillOpen] = useState(false);
```

(d) `handleBackfill` 함수(주석 두 줄 포함) 전체를 교체:
```tsx
  // 체인 백필은 검증이 아니다: 링크가 맞지 않는 행의 hash 를 다시 계산해 덮어쓰고 이후를 다시 잇는다.
  // 행이 삭제된 체인에 실행하면 공백이 다시 이어져 위변조 흔적이 사라진다 — 위변조가 있으면 메뉴에서 막고,
  // L3 확인 뒤에만 실행한다. 실패(throw)는 ConfirmDialog 가 다이얼로그 안에 보여 준다.
  async function runBackfill() {
    const r = await auditChainMonitorApi.backfill();
    toast({ kind: 'ok', title: '백필 완료', message: `tenant ${fmt(r.tenantsProcessed)}개 처리 · 행 ${fmt(r.rowsUpdated)}개 갱신` });
    reverify();
  }
```

(e) `PageHeader` 의 `actions` 를 교체:
```tsx
        actions={<>
          <Button onClick={() => setBackfillOpen(true)}>체인 백필(마이그레이션)…</Button>
          <Button icon={<Icons.Download size={14} />} onClick={() => setShowReport(true)}>월간 보고서</Button>
          {/* 읽기 전용: overview 호출 시 서버가 전 tenant chain 을 검증한다. 진행률은 서버가 주지 않으므로 표시하지 않는다. */}
          <Button variant="primary" icon={<Icons.Hash size={14} />} onClick={reverify} disabled={verifying}>
            {verifying ? '검증 중…' : '지금 검증'}
          </Button>
        </>}
```
→
```tsx
        actions={<>
          <Button icon={<Icons.Download size={14} />} onClick={() => setShowReport(true)}>월간 보고서</Button>
          {/* 읽기 전용: overview 호출 시 서버가 전 tenant chain 을 검증한다. 진행률은 서버가 주지 않으므로 표시하지 않는다. */}
          <Button variant="primary" icon={<Icons.Hash size={14} />} onClick={reverify} disabled={verifying}>
            {verifying ? '검증 중…' : '지금 검증'}
          </Button>
          <Menu
            label="더 보기"
            trigger="button"
            items={[{
              label: '체인 백필…',
              danger: true,
              disabled: tampered,
              disabledReason: '위변조 의심 tenant가 있으면 쓸 수 없습니다. 먼저 조사·해결하세요.',
              onSelect: () => setBackfillOpen(true),
            }]}
          />
        </>}
```

(f) 백필 `<Dialog open={backfillOpen} … role="alertdialog" title="체인 백필을 실행하시겠습니까?" …> … </Dialog>` 블록 전체를 교체:
```tsx
      <ConfirmDialog
        open={backfillOpen && !tampered}
        onClose={() => setBackfillOpen(false)}
        level="L3"
        title="체인 백필을 실행할까요?"
        verb="백필 실행"
        acknowledge="위변조 흔적이 사라질 수 있고 되돌릴 수 없다는 것을 이해했습니다"
        onConfirm={runBackfill}
      >
        <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>
          백필은 검증이 아닙니다. 이전 행과 링크가 맞지 않는 모든 행의 hash 를 다시 계산해 덮어씁니다. 그 뒤의 체인도 모두 다시 연결합니다.
        </p>
        <Alert tone="warn">
          행이 삭제된 체인에 실행하면 빈자리가 다시 이어져, 삭제 흔적(위변조 증거)이 사라집니다. 되돌릴 수 없습니다.
        </Alert>
        <p className="hint" style={{ margin: 0 }}>hash 가 비어 있는 기존 데이터를 체인에 편입하는 마이그레이션 용도로만 사용하세요.</p>
      </ConfirmDialog>
```
(`open={backfillOpen && !tampered}`: 다이얼로그가 열린 사이 재검증 결과가 위변조로 바뀌면 닫힌다.)

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/AuditChainPage.test.tsx src/ui/Menu.test.tsx src/ui/ConfirmDialog.test.tsx`
Expected: PASS (AuditChainPage 24 tests, Menu 3, ConfirmDialog 6)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/AuditChainPage.tsx admin-ui/src/pages/AuditChainPage.test.tsx
git commit -m "fix(admin-ui): 체인 백필을 더 보기 메뉴의 L3 확인으로 옮기고 위변조 시 비활성, 월간 보고서 기간 인라인 검증

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 24: License — 한국어화, 원시 상태 헤드라인 제거(배지만), WARNING/GRACE/만료 조치 카드, `LoadError`

**Files:**
- Modify: `admin-ui/src/pages/LicensePage.tsx` (전체 교체)
- Test: `admin-ui/src/pages/LicensePage.test.tsx` (전체 교체 — 기존 8개 테스트는 새 문구로 고쳐 유지하고 4개 추가)

**Interfaces:**
- Consumes: `useLoad` (Task 1), `describeError` (Task 1), `LoadError` (Task 2), `Skeleton` (Task 3)
- Produces: 없음. 화면 계약: 제목 "라이선스". 상태는 배지 하나(원시 enum 은 `title` 툴팁). "마지막 확인 N분 전 · 다음 확인 N분 후". 빈 값은 `—`. 유예 시간 "N시간". 상태별 조치 카드 `section[aria-label="지금 해야 할 일 — …"]`: WARNING "라이선스 갱신", NETWORK_GRACE "라이선스 서버 연결 복구", DEAD "새 라이선스 적용". "지금 다시 확인"은 이전 정보를 유지한 채 다시 조회.

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/pages/LicensePage.test.tsx` 전체를 아래로 교체:
```tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
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

/** 상세 dl 에서 항목 이름(dt)에 해당하는 값(dd). */
const dd = (term: string) => screen.getByText(term, { selector: 'dt' }).nextElementSibling as HTMLElement;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  vi.mocked(licenseApi.get).mockReset();
});
afterEach(() => vi.useRealTimers());

describe('LicensePage', () => {
  it('상태 헤드라인: 원시 VALID 대신 배지만(원문은 툴팁) + 마지막/다음 확인 상대 시간', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    const { container } = render(<LicensePage />);
    expect(await screen.findByRole('heading', { name: '라이선스' })).toBeInTheDocument();
    expect(container.firstElementChild).toHaveClass('page');
    expect(screen.queryByText('VALID')).toBeNull();
    expect(screen.getByText('정상')).toHaveClass('badge', 'badge--success');
    expect(screen.getByTitle('VALID')).toContainElement(screen.getByText('정상'));
    expect(screen.getByText('마지막 확인 5분 전 · 다음 확인 55분 후')).toBeInTheDocument();
  });

  it('만료까지 일수만 표시하고 진행 바는 그리지 않는다(전체 기간 데이터 없음)', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    render(<LicensePage />);
    expect(await screen.findByText('만료까지')).toBeInTheDocument();
    expect(screen.getByText('200일')).toBeInTheDocument();
    expect(screen.getByText(/^만료일 /)).toBeInTheDocument();
    expect(screen.queryByText(/^Expires at /)).toBeNull();
    expect(screen.queryByRole('progressbar')).toBeNull();
  });

  it('상세 카드: 한국어 항목, 라이선스 ID 복사 버튼, 기능 태그, 유예 없으면 숨김', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    const { container } = render(<LicensePage />);
    expect(await screen.findByRole('heading', { name: '상세' })).toBeInTheDocument();
    const terms = Array.from(container.querySelectorAll('dl dt')).map((d) => d.textContent);
    expect(terms).toEqual(['고객사', '라이선스 ID', '만료일', '남은 일수', '사용 가능한 기능', '마지막 확인', '다음 확인 예정']);
    expect(screen.getByText('acme-bank')).toBeInTheDocument();
    expect(screen.getByText('lic_01J9ZQ8V7K3M')).toHaveClass('mono');
    expect(screen.getByRole('button', { name: '라이선스 ID 복사' })).toBeInTheDocument();
    expect(screen.getByText('audit-export')).toHaveClass('tag');
    expect(screen.queryByText('유예 기간 남은 시간')).toBeNull();
  });

  it('V2 레이아웃: 카드 간격 20px(stack-5), 상세는 행 구분선 kv(kv--rows)', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    const { container } = render(<LicensePage />);
    await screen.findByRole('heading', { name: '상세' });
    expect(container.querySelector('.stack-5')).not.toBeNull();
    expect(container.querySelector('dl')).toHaveClass('kv', 'kv--rows');
  });

  it('NETWORK_GRACE: 경고 배지, 유예 기간 "N시간"(hour(s) 아님), 연결 복구 조치 카드', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, state: 'NETWORK_GRACE', graceRemainingHours: 12 });
    render(<LicensePage />);
    expect(await screen.findByText('서버 연결 불가')).toHaveClass('badge--warning');
    expect(dd('유예 기간 남은 시간')).toHaveTextContent('12시간');
    expect(screen.queryByText(/hour\(s\)/)).toBeNull();
    const card = screen.getByRole('region', { name: '지금 해야 할 일 — 라이선스 서버 연결 복구' });
    expect(card).toHaveTextContent('12시간 안에 연결되지 않으면 서비스가 중단됩니다');
    expect(within(card).getAllByRole('listitem')).toHaveLength(3);
  });

  it('값이 없으면 "—", 상대 시간 줄과 복사 버튼은 숨김, 만료면 새 라이선스 적용 카드', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, state: 'DEAD', jti: null, lastVerifiedAt: null, nextHeartbeatAt: null, features: [] });
    render(<LicensePage />);
    expect(await screen.findByText('만료')).toHaveClass('badge--danger');
    expect(screen.queryByText(/마지막 확인 .* 전/)).toBeNull();
    expect(screen.queryByRole('button', { name: '라이선스 ID 복사' })).toBeNull();
    expect(dd('마지막 확인')).toHaveTextContent('—');
    expect(dd('다음 확인 예정')).toHaveTextContent('—');
    expect(dd('라이선스 ID')).toHaveTextContent('—');
    expect(dd('사용 가능한 기능')).toHaveTextContent('—');
    expect(screen.queryByText('(never)')).toBeNull();
    expect(screen.queryByText('(none scheduled)')).toBeNull();
    expect(screen.getByRole('region', { name: '지금 해야 할 일 — 새 라이선스 적용' })).toHaveTextContent('관리 콘솔은 계속 쓸 수 있습니다');
  });

  it('WARNING: 갱신 조치 카드(3단계)', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, state: 'WARNING', daysUntilExpiry: 30 });
    render(<LicensePage />);
    const card = await screen.findByRole('region', { name: '지금 해야 할 일 — 라이선스 갱신' });
    expect(card).toHaveTextContent('라이선스가 30일 후 만료됩니다');
    expect(within(card).getAllByRole('listitem')).toHaveLength(3);
  });

  it('VALID 는 조치 카드 없음', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    render(<LicensePage />);
    await screen.findByText('정상');
    expect(screen.queryByRole('region', { name: /지금 해야 할 일/ })).toBeNull();
  });

  it('SaaS 면 온프레미스 전용 안내(한국어)', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, deploymentMode: 'saas', state: null });
    render(<LicensePage />);
    expect(await screen.findByText('라이선스 관리는 온프레미스 설치본에서만 사용합니다')).toBeInTheDocument();
    expect(screen.queryByText(/on-prem only/)).toBeNull();
  });

  it('조회 실패는 LoadError, 다시 시도하면 다시 불러온다', async () => {
    vi.mocked(licenseApi.get).mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce(base);
    render(<LicensePage />);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('라이선스 정보를 불러오지 못했습니다');
    expect(alert).toHaveTextContent('boom');
    fireEvent.click(within(alert).getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('정상')).toBeInTheDocument();
  });

  it('지금 다시 확인: 확인하는 동안 기존 정보를 유지하고 "확인 중…"(비활성)', async () => {
    vi.mocked(licenseApi.get).mockResolvedValueOnce(base).mockReturnValueOnce(new Promise(() => {}));
    render(<LicensePage />);
    fireEvent.click(await screen.findByRole('button', { name: '지금 다시 확인' }));
    expect(screen.getByRole('button', { name: '확인 중…' })).toBeDisabled();
    expect(screen.getByText('정상')).toBeInTheDocument();
    expect(screen.queryByRole('status', { name: '불러오는 중' })).toBeNull();
  });

  it('처음 불러오는 동안 Skeleton(Loading… 없음)', async () => {
    vi.mocked(licenseApi.get).mockReturnValue(new Promise(() => {}));
    render(<LicensePage />);
    expect(await screen.findByRole('status', { name: '불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText('Loading…')).toBeNull();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/LicensePage.test.tsx`
Expected: FAIL — 제목 "License", 원시 "VALID" 헤드라인, `hour(s)`, `(none scheduled)`, `License management is on-prem only.`, 조치 카드 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/LicensePage.tsx` 전체를 아래로 교체:
```tsx
import { Fragment, ReactNode } from 'react';
import { licenseApi, type LicenseState, type LicenseView } from '@/api/license';
import { formatDateTime } from '@/lib/formatDateTime';
import { useLoad } from '@/lib/useLoad';
import { describeError } from '@/lib/describeError';
import { Alert, Badge, Button, Card, CardHeader, CopyBtn, LoadError, PageHeader, Skeleton, type BadgeTone } from '@/ui';

const STATE_BADGE: Record<LicenseState, { tone: BadgeTone; label: string }> = {
  VALID: { tone: 'ok', label: '정상' },
  WARNING: { tone: 'warn', label: '만료 임박' },
  NETWORK_GRACE: { tone: 'warn', label: '서버 연결 불가' },
  DEAD: { tone: 'bad', label: '만료' },
};

const DAYS_COLOR: Partial<Record<LicenseState, string>> = { WARNING: 'var(--warn)', DEAD: 'var(--bad)' };

const PAGE_TITLE = '라이선스';
const PAGE_SUB = '이 설치본(온프레미스)의 라이선스 상태와 상세 정보입니다.';

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

function ActionCard({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="card" aria-label={title}>
      <div className="card__head"><h2 className="card__title">{title}</h2></div>
      <div className="card__body" style={{ display: 'flex', flexDirection: 'column', gap: 12, fontSize: 13 }}>{children}</div>
    </section>
  );
}

function Steps({ items }: { items: string[] }) {
  return (
    <ol style={{ margin: 0, paddingLeft: 20, display: 'flex', flexDirection: 'column', gap: 6 }}>
      {items.map((s) => <li key={s}>{s}</li>)}
    </ol>
  );
}

const RECHECK_STEP = '이 화면에서 "지금 다시 확인"을 누릅니다. 상태가 "정상"으로 바뀌면 끝입니다.';
const REQUEST_STEP = '영업 담당자에게 갱신을 요청합니다. 아래 상세의 라이선스 ID와 고객사 이름을 함께 보내 주세요.';
const APPLY_STEP = '받은 라이선스 파일을 서버에 적용합니다.';

/** 상태만 알리지 않고 다음 행동을 함께 보인다(V3 · H9). VALID 는 할 일이 없으므로 카드 없음. */
function NextAction({ license }: { license: LicenseView }) {
  if (license.state === 'WARNING') {
    return (
      <ActionCard title="지금 해야 할 일 — 라이선스 갱신">
        <p style={{ margin: 0 }}>{`라이선스가 ${license.daysUntilExpiry}일 후 만료됩니다. 만료되면 패스키 등록·인증이 중단됩니다.`}</p>
        <Steps items={[REQUEST_STEP, APPLY_STEP, RECHECK_STEP]} />
      </ActionCard>
    );
  }
  if (license.state === 'NETWORK_GRACE') {
    const left = license.graceRemainingHours !== null ? `${license.graceRemainingHours}시간 안에` : '곧';
    return (
      <ActionCard title="지금 해야 할 일 — 라이선스 서버 연결 복구">
        <p style={{ margin: 0 }}>{`라이선스 서버에 연결할 수 없습니다. ${left} 연결되지 않으면 서비스가 중단됩니다.`}</p>
        <Steps items={[
          '방화벽에서 라이선스 서버(443 포트)로 나가는 연결을 허용했는지 확인합니다.',
          '프록시를 쓰는 경우 프록시 설정을 확인합니다.',
          RECHECK_STEP,
        ]} />
      </ActionCard>
    );
  }
  if (license.state === 'DEAD') {
    return (
      <ActionCard title="지금 해야 할 일 — 새 라이선스 적용">
        <p style={{ margin: 0 }}>라이선스가 만료되어 패스키 등록·인증이 중단되었습니다. 관리 콘솔은 계속 쓸 수 있습니다. 새 라이선스 파일을 적용하면 바로 복구됩니다.</p>
        <Steps items={[REQUEST_STEP, APPLY_STEP, RECHECK_STEP]} />
      </ActionCard>
    );
  }
  return null;
}

export default function LicensePage() {
  // "지금 다시 확인"(reload) 중에는 이전 정보를 그대로 보여 준다.
  const load = useLoad(() => licenseApi.get(), []);
  const license = load.data;

  if (!license) {
    return (
      <div className="page">
        <PageHeader title={PAGE_TITLE} sub={PAGE_SUB} />
        <Card>
          {load.status === 'failed'
            ? <LoadError what="라이선스 정보" error={load.error} onRetry={load.reload} retrying={load.reloading} />
            : <Skeleton rows={5} />}
        </Card>
      </div>
    );
  }

  if (license.deploymentMode !== 'onprem') {
    return (
      <div className="page">
        <PageHeader title={PAGE_TITLE} />
        <Card>
          <div className="empty">
            <p className="empty__title">라이선스 관리는 온프레미스 설치본에서만 사용합니다</p>
            <p className="empty__sub">클라우드 설치본은 계약에 따라 자동 관리됩니다.</p>
          </div>
        </Card>
      </div>
    );
  }

  const badge = license.state ? STATE_BADGE[license.state] : null;
  const heartbeat = [
    license.lastVerifiedAt && `마지막 확인 ${relativeTime(license.lastVerifiedAt)}`,
    license.nextHeartbeatAt && `다음 확인 ${relativeTime(license.nextHeartbeatAt)}`,
  ].filter(Boolean).join(' · ');

  const rows: { k: string; v: ReactNode }[] = [
    { k: '고객사', v: license.sub ?? '—' },
    {
      k: '라이선스 ID',
      v: license.jti ? (
        <span className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
          <span className="mono" style={{ wordBreak: 'break-all' }}>{license.jti}</span>
          <CopyBtn value={license.jti} label="라이선스 ID 복사" iconOnly />
        </span>
      ) : '—',
    },
    { k: '만료일', v: license.expiresAt ? formatDateTime(license.expiresAt) : '—' },
    { k: '남은 일수', v: license.daysUntilExpiry },
    {
      k: '사용 가능한 기능',
      v: license.features.length > 0
        ? <span className="row" style={{ gap: 6 }}>{license.features.map((f) => <span key={f} className="tag">{f}</span>)}</span>
        : '—',
    },
    { k: '마지막 확인', v: license.lastVerifiedAt ? formatDateTime(license.lastVerifiedAt) : '—' },
    { k: '다음 확인 예정', v: license.nextHeartbeatAt ? formatDateTime(license.nextHeartbeatAt) : '—' },
    ...(license.graceRemainingHours !== null ? [{ k: '유예 기간 남은 시간', v: `${license.graceRemainingHours}시간` }] : []),
  ];

  return (
    <div className="page">
      <PageHeader title={PAGE_TITLE} sub={PAGE_SUB} />

      <div className="stack-5" style={{ maxWidth: 820 }}>
        {load.status === 'failed' && (
          <Alert tone="warn" title="다시 확인하지 못했습니다">
            {`${describeError(load.error).title}. 아래는 마지막으로 확인한 정보입니다.`}
          </Alert>
        )}

        {/* 상태 헤드라인 — 원시 enum 대신 배지(원문은 툴팁) */}
        <Card>
          <div className="row" style={{ gap: 20, padding: '20px 24px' }}>
            <div className="col" style={{ gap: 6, flex: '1 1 240px', minWidth: 0 }}>
              <span className="t-over">상태</span>
              <span className="row" style={{ gap: 10 }}>
                {badge
                  ? <span title={license.state ?? undefined}><Badge tone={badge.tone}>{badge.label}</Badge></span>
                  : <span className="muted">알 수 없음</span>}
              </span>
              {heartbeat && <span className="card__sub">{heartbeat}</span>}
            </div>
            <div className="col" style={{ gap: 6, flex: '1 1 240px', minWidth: 0 }}>
              <div className="row">
                <span className="hint" style={{ margin: 0 }}>만료까지</span>
                <div className="spacer" />
                <strong style={{ fontSize: 13, color: license.state ? DAYS_COLOR[license.state] : undefined }}>{`${license.daysUntilExpiry}일`}</strong>
              </div>
              {license.expiresAt && <span className="hint" style={{ margin: 0 }}>{`만료일 ${formatDateTime(license.expiresAt)}`}</span>}
            </div>
            <Button onClick={load.reload} disabled={load.reloading}>{load.reloading ? '확인 중…' : '지금 다시 확인'}</Button>
          </div>
        </Card>

        <NextAction license={license} />

        {/* 상세 */}
        <Card>
          <CardHeader title="상세" />
          <dl className="kv kv--rows">
            {rows.map((r) => (
              <Fragment key={r.k}>
                <dt>{r.k}</dt>
                <dd>{r.v}</dd>
              </Fragment>
            ))}
          </dl>
        </Card>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/LicensePage.test.tsx`
Expected: PASS (12 tests)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/LicensePage.tsx admin-ui/src/pages/LicensePage.test.tsx
git commit -m "fix(admin-ui): 라이선스 화면 한국어화, 원시 상태 헤드라인 대신 배지, 상태별 조치 카드와 LoadError

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 25: 라이선스 배너 — 조치 링크, 한국어 표기 통일

**Files:**
- Modify: `admin-ui/src/components/LicenseBanner.tsx` (전체 교체)
- Modify: `admin-ui/src/App.tsx:108`
- Test: `admin-ui/src/components/LicenseBanner.test.tsx` (신규)

**Interfaces:**
- Consumes: react-router `Link`, `useLocation`; `isPlatform(me)` (`@/me/roles`, 기존)
- Produces: `function LicenseBanner(props: { showLink?: boolean }): JSX.Element | null` — `showLink` 가 참이고 현재 경로가 `/license` 가 아니면 상태별 조치 링크(→ `/license`)를 띄운다. WARNING "라이선스 보기", NETWORK_GRACE "조치 방법 보기", DEAD "갱신 방법 보기". `/license` 는 PLATFORM 전용이므로 App 은 `showLink={isPlatform(me)}` 로 넘긴다(RP 관리자에게 막힌 링크를 보이지 않음). 라우터 안에서만 렌더해야 한다(App 셸은 이미 라우터 안).

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/components/LicenseBanner.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { LicenseView } from '@/api/license';

vi.mock('@/api/license', () => ({ licenseApi: { get: vi.fn() } }));

import { licenseApi } from '@/api/license';
import { LicenseBanner } from './LicenseBanner';

const base: LicenseView = {
  deploymentMode: 'onprem', state: 'WARNING', sub: 'acme', jti: 'lic_1', expiresAt: '2026-11-10T00:00:00+09:00',
  daysUntilExpiry: 30, features: [], lastVerifiedAt: null, graceRemainingHours: null, nextHeartbeatAt: null,
};

function renderBanner(props: { showLink?: boolean } = {}, path = '/tenants') {
  return render(<MemoryRouter initialEntries={[path]}><LicenseBanner {...props} /></MemoryRouter>);
}

beforeEach(() => { vi.mocked(licenseApi.get).mockReset(); });

describe('LicenseBanner', () => {
  it('WARNING: 남은 일수 + "라이선스 보기" 링크(/license)', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    renderBanner({ showLink: true });
    expect(await screen.findByText('라이선스가 30일 후 만료됩니다. 영업 담당자에게 갱신을 요청하세요.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '라이선스 보기' })).toHaveAttribute('href', '/license');
  });

  it('NETWORK_GRACE: 남은 시간 + "조치 방법 보기"', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, state: 'NETWORK_GRACE', graceRemainingHours: 12 });
    renderBanner({ showLink: true });
    expect(await screen.findByText('라이선스 서버에 연결할 수 없습니다. 12시간 안에 연결되지 않으면 서비스가 중단됩니다.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '조치 방법 보기' })).toHaveAttribute('href', '/license');
  });

  it('DEAD: role=alert + 쉬운 영향 문구 + "갱신 방법 보기"', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, state: 'DEAD' });
    renderBanner({ showLink: true });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('라이선스가 만료되어 패스키 등록·인증이 중단되었습니다. 관리 콘솔은 계속 쓸 수 있습니다.');
    expect(screen.getByRole('link', { name: '갱신 방법 보기' })).toBeInTheDocument();
  });

  it('showLink 가 없으면(RP 관리자) 링크를 그리지 않는다', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    renderBanner();
    await screen.findByText(/30일 후 만료됩니다/);
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('이미 /license 에 있으면 링크를 숨긴다', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    renderBanner({ showLink: true }, '/license');
    await screen.findByText(/30일 후 만료됩니다/);
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('VALID·SaaS 는 아무것도 그리지 않는다', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, state: 'VALID' });
    const { container } = renderBanner({ showLink: true });
    await vi.waitFor(() => expect(licenseApi.get).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/components/LicenseBanner.test.tsx`
Expected: FAIL — 링크 없음, 문구 "라이센스…" 불일치.

- [ ] **Step 3: 구현**

`admin-ui/src/components/LicenseBanner.tsx` 전체를 아래로 교체:
```tsx
import { useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { licenseApi, type LicenseView } from '@/api/license';

/**
 * Global license banner. Renders nothing on SaaS mode (state === null) or VALID,
 * a warning/danger strip otherwise. Polls /admin/api/license every 5 minutes to
 * reflect state transitions without requiring a reload.
 * 상태만 알리지 않고 다음 행동 링크(/license 의 조치 카드)를 함께 보인다(V3 · H9).
 * showLink: /license 는 PLATFORM 전용 — RP 관리자에게는 막힌 링크를 보이지 않는다.
 */
export function LicenseBanner({ showLink = false }: { showLink?: boolean }) {
  const [license, setLicense] = useState<LicenseView | null>(null);
  const { pathname } = useLocation();

  useEffect(() => {
    let cancelled = false;
    async function poll() {
      try {
        const view = await licenseApi.get();
        if (!cancelled) setLicense(view);
      } catch {
        // Silent — banner is best-effort UI.
      }
    }
    poll();
    const id = setInterval(poll, 5 * 60 * 1000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);

  if (!license || license.deploymentMode !== 'onprem' || !license.state) return null;
  if (license.state === 'VALID') return null;

  const action = (label: string) => (showLink && pathname !== '/license'
    ? <Link to="/license" className="btn btn--sm" style={{ marginLeft: 'auto', flex: 'none' }}>{label}</Link>
    : null);

  if (license.state === 'WARNING') {
    return (
      <div className="topbanner topbanner--warning" role="status">
        <Icons.Alert size={16} />
        <strong>{`라이선스가 ${license.daysUntilExpiry}일 후 만료됩니다. 영업 담당자에게 갱신을 요청하세요.`}</strong>
        {action('라이선스 보기')}
      </div>
    );
  }
  if (license.state === 'NETWORK_GRACE') {
    const left = license.graceRemainingHours !== null ? `${license.graceRemainingHours}시간 안에` : '곧';
    return (
      <div className="topbanner topbanner--warning" role="status">
        <Icons.Alert size={16} />
        <strong>{`라이선스 서버에 연결할 수 없습니다. ${left} 연결되지 않으면 서비스가 중단됩니다.`}</strong>
        {action('조치 방법 보기')}
      </div>
    );
  }
  // DEAD — 숨길 수 없음
  return (
    <div className="topbanner topbanner--danger" role="alert">
      <Icons.Alert size={16} />
      <strong>라이선스가 만료되어 패스키 등록·인증이 중단되었습니다. 관리 콘솔은 계속 쓸 수 있습니다.</strong>
      {action('갱신 방법 보기')}
    </div>
  );
}
```

`admin-ui/src/App.tsx` 108행:
```tsx
      <LicenseBanner />
```
→
```tsx
      <LicenseBanner showLink={isPlatform(me)} />
```
(`isPlatform` 은 14행에서 이미 import 되어 있다.)

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/components/LicenseBanner.test.tsx src/test/router.test.tsx src/test/smoke.test.ts`
Expected: PASS (LicenseBanner 6 tests + 기존 router/smoke 전부). 이어서 전체 확인: `npx vitest run && npx tsc -b && npx eslint src` — 모두 PASS.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/components/LicenseBanner.tsx admin-ui/src/components/LicenseBanner.test.tsx admin-ui/src/App.tsx
git commit -m "feat(admin-ui): 라이선스 배너에 상태별 조치 링크 추가, 표기를 '라이선스'로 통일

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Tenant 상세 · 탭

> 이 묶음은 스펙 §6 "Tenant 공통"·"Tenant 탭" 표와 §7 의 Tenant 정지·Credential 회수·API 키 회수/회전 행을 다룬다.
> 선행: Task 1–6(공통 패턴). `@/config/links` 는 셸 묶음의 도움말 Task 가 만든다 — 이 묶음은 `HELP_LINKS.rpGuide: string` 만 쓴다(빈 문자열이면 링크를 숨김).
> 1단계 범위: Credential·감사 로그 필터는 서버 파라미터가 없으므로(스펙 §8) **현재 페이지 필터 + 범위 표시**로만 구현한다.

---

### Task 26: `Tabs` — ←/→ 키 이동과 "확인 필요 N" 라벨

**Files:**
- Modify: `admin-ui/src/ui/Tabs.tsx` (전체 교체)
- Modify: `admin-ui/src/styles/components.css:223` (`.tabs__dot` → `.tabs__badge`)
- Modify: `admin-ui/src/ui/table.test.tsx:10-18` (기존 `dot` 기대값 갱신)
- Test: `admin-ui/src/ui/Tabs.test.tsx` (신규)

**Interfaces:**
- Consumes: 없음
- Produces:
  ```ts
  type TabItem = { id: string; label: ReactNode; badge?: { text: string; reason: string }; count?: number };
  function Tabs(p: { items: TabItem[]; active: string; onChange: (id: string) => void; ariaLabel: string }): JSX.Element
  ```
  `dot?: boolean` 은 삭제된다(사용처: `TenantDetailPage` — Task 28 에서 `badge` 로 바꾼다). 선택된 탭만 `tabIndex=0`, ←/→/Home/End 로 포커스와 선택을 함께 옮긴다. `badge.reason` 은 탭 버튼과 배지의 `title`.

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/ui/Tabs.test.tsx`:
```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useState } from 'react';
import { Tabs, type TabItem } from './Tabs';

const ITEMS: TabItem[] = [
  { id: 'a', label: '개요' },
  { id: 'b', label: 'AAGUID 정책', badge: { text: '확인 필요 2', reason: 'MDS 미확인 AAGUID 2건' } },
  { id: 'c', label: '퍼널' },
];

function Host({ onChange }: { onChange?: (id: string) => void }) {
  const [active, setActive] = useState('a');
  return <Tabs ariaLabel="Tenant 탭" items={ITEMS} active={active} onChange={(id) => { setActive(id); onChange?.(id); }} />;
}

describe('Tabs', () => {
  it('선택된 탭만 tabIndex=0 (로빙 탭 순서)', () => {
    render(<Host />);
    expect(screen.getByRole('tab', { name: '개요' })).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('tab', { name: /AAGUID/ })).toHaveAttribute('tabindex', '-1');
    expect(screen.getByRole('tab', { name: '퍼널' })).toHaveAttribute('tabindex', '-1');
  });

  it('→ 는 다음, ← 는 이전 탭으로 포커스·선택을 옮기고 양 끝에서 되돌아간다', () => {
    const onChange = vi.fn();
    render(<Host onChange={onChange} />);
    const first = screen.getByRole('tab', { name: '개요' });
    first.focus();
    fireEvent.keyDown(first, { key: 'ArrowRight' });
    const second = screen.getByRole('tab', { name: /AAGUID/ });
    expect(onChange).toHaveBeenLastCalledWith('b');
    expect(document.activeElement).toBe(second);
    expect(second).toHaveAttribute('aria-selected', 'true');

    fireEvent.keyDown(second, { key: 'ArrowLeft' });
    expect(onChange).toHaveBeenLastCalledWith('a');
    fireEvent.keyDown(screen.getByRole('tab', { name: '개요' }), { key: 'ArrowLeft' });
    expect(onChange).toHaveBeenLastCalledWith('c');
    expect(document.activeElement).toBe(screen.getByRole('tab', { name: '퍼널' }));
    fireEvent.keyDown(screen.getByRole('tab', { name: '퍼널' }), { key: 'ArrowRight' });
    expect(onChange).toHaveBeenLastCalledWith('a');
  });

  it('Home/End 는 처음/마지막 탭, 다른 키는 무시', () => {
    const onChange = vi.fn();
    render(<Host onChange={onChange} />);
    const first = screen.getByRole('tab', { name: '개요' });
    fireEvent.keyDown(first, { key: 'End' });
    expect(onChange).toHaveBeenLastCalledWith('c');
    fireEvent.keyDown(screen.getByRole('tab', { name: '퍼널' }), { key: 'Home' });
    expect(onChange).toHaveBeenLastCalledWith('a');
    onChange.mockClear();
    fireEvent.keyDown(screen.getByRole('tab', { name: '개요' }), { key: 'a' });
    expect(onChange).not.toHaveBeenCalled();
  });

  it('경고는 색 점 대신 "확인 필요 N" 라벨, 사유는 title 툴팁', () => {
    render(<Host />);
    const badge = screen.getByText('확인 필요 2');
    expect(badge).toHaveClass('badge', 'badge--warning', 'tabs__badge');
    expect(badge).toHaveAttribute('title', 'MDS 미확인 AAGUID 2건');
    expect(screen.getByRole('tab', { name: /AAGUID/ })).toHaveAttribute('title', 'MDS 미확인 AAGUID 2건');
    expect(document.querySelector('.tabs__dot')).toBeNull();
  });
});
```

`admin-ui/src/ui/table.test.tsx` 10–18행의 `it('aria-selected 와 dot/count', …)` 블록을 아래로 교체(테스트 삭제 아님, 기대값만 새 API 로):
```tsx
  it('aria-selected 와 badge/count', () => {
    const onChange = vi.fn();
    render(<Tabs ariaLabel="Tenant 탭" active="overview" onChange={onChange} items={[{ id: 'overview', label: '개요' }, { id: 'aaguid', label: 'AAGUID 정책', badge: { text: '확인 필요 1', reason: 'MDS 미확인 AAGUID 1건' } }, { id: 'admins', label: 'Admin 사용자', count: 3 }]} />);
    expect(screen.getByRole('tab', { name: '개요' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText('확인 필요 1')).toHaveAttribute('title', 'MDS 미확인 AAGUID 1건');
    expect(screen.getByText('3')).toHaveClass('tabs__count');
    fireEvent.click(screen.getByRole('tab', { name: /AAGUID/ }));
    expect(onChange).toHaveBeenCalledWith('aaguid');
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/ui/Tabs.test.tsx src/ui/table.test.tsx`
Expected: FAIL — `tabindex` 속성 없음, `확인 필요 2` 텍스트 없음, 키 입력에 `onChange` 미호출. (`tsc` 기준으로는 `badge` 가 `TabItem` 에 없다는 타입 오류.)

- [ ] **Step 3: 구현**

`admin-ui/src/ui/Tabs.tsx` 전체 교체:
```tsx
import { KeyboardEvent, ReactNode, useRef } from 'react';
import { cn } from '@/lib/cn';

/** badge: 확인이 필요한 탭. text 는 보이는 라벨("확인 필요 N"), reason 은 사유 툴팁(title). 색만으로 전달하지 않는다. */
export type TabItem = { id: string; label: ReactNode; badge?: { text: string; reason: string }; count?: number };

export function Tabs({ items, active, onChange, ariaLabel }: { items: TabItem[]; active: string; onChange: (id: string) => void; ariaLabel: string }) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const activeIdx = Math.max(0, items.findIndex((t) => t.id === active));

  function onKeyDown(e: KeyboardEvent<HTMLButtonElement>, i: number) {
    const last = items.length - 1;
    const next =
      e.key === 'ArrowRight' ? (i === last ? 0 : i + 1)
        : e.key === 'ArrowLeft' ? (i === 0 ? last : i - 1)
          : e.key === 'Home' ? 0
            : e.key === 'End' ? last
              : null;
    if (next === null) return;
    e.preventDefault();
    refs.current[next]?.focus();
    onChange(items[next].id);
  }

  return (
    <div className="tabs" role="tablist" aria-label={ariaLabel}>
      {items.map((t, i) => (
        <button
          key={t.id}
          ref={(el) => { refs.current[i] = el; }}
          type="button"
          role="tab"
          aria-selected={t.id === active}
          tabIndex={i === activeIdx ? 0 : -1}
          title={t.badge?.reason}
          className={cn('tabs__btn', t.id === active && 'tabs__btn--active')}
          onClick={() => onChange(t.id)}
          onKeyDown={(e) => onKeyDown(e, i)}
        >
          {t.label}
          {t.badge && <span className="badge badge--warning tabs__badge" title={t.badge.reason}>{t.badge.text}</span>}
          {t.count != null && t.count > 0 && <span className="tabs__count">{t.count}</span>}
        </button>
      ))}
    </div>
  );
}
```

`admin-ui/src/styles/components.css` 223행 `.tabs__dot { … }` 을 아래로 교체:
```css
.tabs__badge { font-size: 11px; padding: 0 6px; line-height: 18px; }
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/ui/Tabs.test.tsx src/ui/table.test.tsx`
Expected: PASS (Tabs 4 tests + table 전부). `npx tsc -b` 는 `TenantDetailPage.tsx` 의 `dot` 사용 때문에 실패한다 — Task 28 에서 고친다. 이 Task 의 커밋 전에는 `admin-ui/src/pages/TenantDetailPage.tsx:189-190` 의 `dot: …` 두 줄을 임시로 아래처럼 바꿔 타입을 맞춘다(Task 28 에서 최종 문구로 다시 바꾼다):
```tsx
    if (t.id === 'aaguid') return hasUnverifiedAaguid(aaguidPolicy) ? { ...t, badge: { text: '확인 필요', reason: 'MDS 미확인 AAGUID' } } : t;
    if (t.id === 'apikeys') return (expiringKeyCount ?? 0) > 0 ? { ...t, badge: { text: '확인 필요', reason: '30일 안에 만료되는 키' } } : t;
```
그리고 `admin-ui/src/pages/TenantDetailPage.test.tsx` 의 `findByLabelText('확인 필요')` / `queryByLabelText('확인 필요')` 를 각각 `findByText(/확인 필요/)` / `queryByText(/확인 필요/)` 로 바꾼다(98, 99, 108, 114, 121, 130, 132행).
Run: `npx vitest run && npx tsc -b && npx eslint src`
Expected: 전부 PASS.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/ui/Tabs.tsx admin-ui/src/ui/Tabs.test.tsx admin-ui/src/ui/table.test.tsx admin-ui/src/styles/components.css admin-ui/src/pages/TenantDetailPage.tsx admin-ui/src/pages/TenantDetailPage.test.tsx
git commit -m "feat(admin-ui): 탭 ←/→ 키 이동과 경고 점 대신 '확인 필요' 라벨·사유 툴팁

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 27: Tenant 상세 로드 — `LoadError` 와 404 구분

**Files:**
- Modify: `admin-ui/src/pages/TenantDetailPage.tsx:213-261` (`TenantDetailRoute` 교체), 1–19행 import
- Test: `admin-ui/src/pages/TenantDetailPage.test.tsx` (`describe('TenantDetailRoute (M14)')` 에 추가)

**Interfaces:**
- Consumes: `useLoad` (Task 1), `LoadError` (Task 2), `Skeleton` (Task 3), `ApiError` (`@/api/types`), `EmptyState`, `illustrations.noResults`
- Produces: 없음(라우트 컴포넌트 동작 변경). 로딩 = `Skeleton(label="Tenant 정보 불러오는 중")`, 404 = "Tenant를 찾을 수 없습니다" + `Tenants로 돌아가기`(`/tenants`), 그 밖의 실패 = `LoadError what="Tenant 정보"` + "‹ Tenant 목록으로".

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/pages/TenantDetailPage.test.tsx` 상단 import 에 추가:
```tsx
import { ApiError } from '@/api/types';
```

`describe('TenantDetailRoute (M14)', …)` 블록 안 마지막에 추가:
```tsx
  function renderRoute(path = '/tenants/t-1') {
    const router = createMemoryRouter(
      [
        { path: '/tenants/:id', element: <ToastHost><TenantDetailRoute me={PLATFORM} /></ToastHost> },
        { path: '/tenants', element: <div>TENANTS LIST</div> },
      ],
      { initialEntries: [path] },
    );
    render(<RouterProvider router={router} />);
    return router;
  }

  it('로딩 중에는 Skeleton, "Loading…" 텍스트 없음', () => {
    (tenantsApi.get as ReturnType<typeof vi.fn>).mockReturnValue(new Promise(() => {}));
    renderRoute();
    expect(screen.getByRole('status', { name: 'Tenant 정보 불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText('Loading…')).toBeNull();
  });

  it('404 면 "찾을 수 없습니다" + Tenants로 돌아가기(다시 시도 없음)', async () => {
    (tenantsApi.get as ReturnType<typeof vi.fn>).mockRejectedValue(new ApiError(404, 'C404', 'not found'));
    const router = renderRoute();
    expect(await screen.findByText('Tenant를 찾을 수 없습니다')).toBeInTheDocument();
    expect(screen.getByText('삭제됐거나 주소가 잘못된 tenant입니다.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('link', { name: 'Tenants로 돌아가기' })); });
    expect(router.state.location.pathname).toBe('/tenants');
  });

  it('5xx 면 LoadError(다시 시도) — 다시 시도 성공 시 상세를 그린다', async () => {
    (tenantsApi.get as ReturnType<typeof vi.fn>)
      .mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503', undefined, 'tr-9'))
      .mockResolvedValueOnce(T);
    renderRoute();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Tenant 정보를 불러오지 못했습니다');
    expect(alert).toHaveTextContent('서버가 잠시 응답하지 않습니다');
    expect(alert).toHaveTextContent('tr-9');
    expect(screen.getByRole('link', { name: '‹ Tenant 목록으로' })).toHaveAttribute('href', '/tenants');
    expect(screen.queryByText('Tenant를 찾을 수 없습니다')).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 시도' })); });
    expect(await screen.findByRole('heading', { level: 1, name: 'Acme' })).toBeInTheDocument();
    expect(tenantsApi.get).toHaveBeenCalledTimes(2);
  });

  it('로드 실패를 토스트로 띄우지 않는다(인라인 하나만)', async () => {
    (tenantsApi.get as ReturnType<typeof vi.fn>).mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderRoute();
    await screen.findByText('Tenant 정보를 불러오지 못했습니다');
    expect(screen.queryByText('Tenant 로드 실패')).toBeNull();
    expect(screen.getAllByRole('alert')).toHaveLength(1);
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/TenantDetailPage.test.tsx`
Expected: FAIL — `status` 역할 없음(현재 `Loading…` 문단), `Tenant를 찾을 수 없습니다` 없음(현재 "Tenant 를 찾을 수 없습니다." 는 404·5xx 공통), `다시 시도` 버튼 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/TenantDetailPage.tsx` 2–3행 import 를 교체:
```tsx
import { useState, useEffect } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
```
그리고 18–19행 뒤에 추가:
```tsx
import { ApiError } from '@/api/types';
import { useLoad } from '@/lib/useLoad';
import { illustrations } from '@/assets/illustrations';
```
11행의 `@/ui` import 에 `EmptyState, LoadError, Skeleton` 을 추가한다:
```tsx
import { Alert, Button, ConfirmByTyping, Dialog, EmptyState, LoadError, Menu, PropChip, Skeleton, StatusBadge, Tabs, useToast, type TabItem } from '@/ui';
```
(`ConfirmByTyping`·`useRef` 는 Task 28 에서 정리한다. 이 Task 에서는 `SuspendDialog` 가 아직 `useRef` 를 쓰므로 2행에 `useRef` 를 남긴다: `import { useState, useEffect, useRef } from 'react';`)

213–261행(`// ── TenantDetailRoute …` 부터 파일 끝까지)을 아래로 교체:
```tsx
// ── TenantNotFound — 404 일 때만 ─────────────────────────────────────────────

function TenantNotFound() {
  return (
    <div className="page">
      <div role="alert">
        <EmptyState
          illustration={illustrations.noResults}
          title="Tenant를 찾을 수 없습니다"
          description="삭제됐거나 주소가 잘못된 tenant입니다."
          action={<Link className="btn btn--primary" to="/tenants">Tenants로 돌아가기</Link>}
        />
      </div>
    </div>
  );
}

// ── TenantDetailRoute — 라우터 wrapper (App.tsx 에서 사용) ───────────────────

export default function TenantDetailRoute({ me }: { me: Me }) {
  const { id = '' } = useParams<{ id: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = searchParams.get('tab') || 'overview';
  // useLoad 는 늦게 온 이전 id 응답을 버리고(M14), 재조회(onReload) 중에는 이전 tenant 를 유지한다.
  const load = useLoad(() => tenantsApi.get(id), [id]);
  // 주소가 막 바뀐 경우 이전 tenant 를 새 주소에 그리지 않는다.
  const tenant = load.data && load.data.id === id ? load.data : undefined;

  if (!tenant && load.status !== 'failed') {
    return <div className="page"><Skeleton rows={6} label="Tenant 정보 불러오는 중" /></div>;
  }
  if (!tenant) {
    if (load.error instanceof ApiError && load.error.httpStatus === 404) return <TenantNotFound />;
    return (
      <div className="page">
        <LoadError what="Tenant 정보" error={load.error} onRetry={load.reload} retrying={load.reloading} />
        <div className="row" style={{ justifyContent: 'center' }}>
          <Link className="btn btn--ghost" to="/tenants">‹ Tenant 목록으로</Link>
        </div>
      </div>
    );
  }

  return (
    <TenantDetailPage
      tenant={tenant}
      currentTab={tab}
      onTabChange={(t) => setSearchParams({ tab: t })}
      me={me}
      onReload={load.reload}
    />
  );
}
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/TenantDetailPage.test.tsx`
Expected: PASS — 새 4개 + 기존 M14(A→B 늦은 응답) 포함 전부.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/TenantDetailPage.tsx admin-ui/src/pages/TenantDetailPage.test.tsx
git commit -m "fix(admin-ui): Tenant 상세 로드 실패를 LoadError 로, 404 일 때만 '찾을 수 없습니다'

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 28: Tenant 헤더 — 탭 "확인 필요 N", 정지 L3(`ConfirmDialog`), 토스트 "회수됨"

**Files:**
- Modify: `admin-ui/src/pages/TenantDetailPage.tsx` (import, `SuspendDialog` 49–99행, `ResumeDialog` 문구 127–129행, 탭 배지 187–192행)
- Test: `admin-ui/src/pages/TenantDetailPage.test.tsx`

**Interfaces:**
- Consumes: `ConfirmDialog` L3 `typeToConfirm` (Task 4), `eulReul` (Task 1), `TabItem.badge` (Task 26), `unverifiedAaguidCount`, `countExpiringKeys` (`./tenant/tenantSignals`, 기존)
- Produces: 없음. 탭 배지 — AAGUID: `{ text: '확인 필요 N', reason: 'MDS 미확인 AAGUID N건' }`, API 키: `{ text: '확인 필요 N', reason: '30일 안에 만료되는 키 N개' }`.

- [ ] **Step 1: 실패하는 테스트 작성 + 바뀐 문구의 기존 테스트 갱신**

`admin-ui/src/pages/TenantDetailPage.test.tsx` 의 `describe('탭 점 배지', …)` 블록 전체를 아래로 교체(기존 6개 테스트를 같은 의도로 유지하면서 새 라벨을 검증):
```tsx
describe('탭 "확인 필요 N" 배지', () => {
  it('AAGUID: mode≠ANY 이고 mdsName==null 항목 수를 라벨로, 사유는 툴팁', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      mode: 'ALLOWLIST', mdsStrict: false, entries: [{ aaguid: 'a', note: null, mdsName: null }, { aaguid: 'b', note: null, mdsName: null }],
    });
    renderPage();
    const tab = screen.getByRole('tab', { name: /AAGUID 정책/ });
    const badge = await within(tab).findByText('확인 필요 2');
    expect(badge).toHaveAttribute('title', 'MDS 미확인 AAGUID 2건');
    expect(within(screen.getByRole('tab', { name: /API 키/ })).queryByText(/확인 필요/)).toBeNull();
  });
  it('API 키: 30일 이내 만료 ACTIVE 키 수를 라벨로, 개요에 개수 전달', async () => {
    (apiKeysApi.list as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'k1', prefix: 'pk_a', name: 'prod', status: 'ACTIVE', createdAt: '2026-01-01T00:00:00Z', lastUsedAt: null, scopes: [], expiresAt: new Date(Date.now() + 10 * DAY).toISOString() },
      { id: 'k2', prefix: 'pk_b', name: 'old', status: 'REVOKED', createdAt: '2026-01-01T00:00:00Z', lastUsedAt: null, scopes: [], expiresAt: new Date(Date.now() + 5 * DAY).toISOString() },
    ]);
    renderPage();
    const tab = screen.getByRole('tab', { name: /API 키/ });
    expect(await within(tab).findByText('확인 필요 1')).toHaveAttribute('title', '30일 안에 만료되는 키 1개');
    expect(screen.getByText('OVERVIEW expiring=1 platform=true')).toBeInTheDocument();
  });
  it('조건이 없으면 배지가 없다', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('OVERVIEW expiring=0 platform=true')).toBeInTheDocument());
    expect(screen.queryByText(/확인 필요/)).toBeNull();
  });
  it('신호 조회 실패는 조용히 무시', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('403'));
    (apiKeysApi.list as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('403'));
    renderPage();
    await waitFor(() => expect(apiKeysApi.list).toHaveBeenCalled());
    expect(screen.queryByText(/확인 필요/)).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
  });
  it('AAGUID 저장 후 onSaved 로 배지가 갱신된다', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      mode: 'ALLOWLIST', mdsStrict: false, entries: [{ aaguid: 'a', note: null, mdsName: null }],
    });
    renderPage({ tab: 'aaguid' });
    const tab = screen.getByRole('tab', { name: /AAGUID 정책/ });
    expect(await within(tab).findByText('확인 필요 1')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'AAGUID 저장 시뮬레이션' }));
    expect(within(tab).queryByText(/확인 필요/)).toBeNull();
  });
});
```

`describe('테넌트 정지 / 정지 해제', …)` 의 `it('정지: alertdialog, 영향 범위, …')` 블록을 아래로 교체하고, 같은 describe 끝에 실패 테스트 하나를 추가:
```tsx
  it('정지: L3 — 제목 동사, 영향 범위, 취소 기본 포커스, slug 일치 전 정지 비활성, 토스트 "회수됨"', async () => {
    const { onReload } = renderPage();
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '테넌트 정지' }));
    const dlg = screen.getByRole('alertdialog', { name: 'Acme을 정지할까요?' });
    expect(within(dlg).getByText('정지하면 이 tenant의 모든 RP 서버 요청이 거부됩니다.')).toBeInTheDocument();
    expect(within(dlg).getByRole('alert')).toHaveTextContent('영향 범위');
    expect(within(dlg).getByText('· 유효한 API 키 3개를 즉시 회수')).toBeInTheDocument();
    expect(within(dlg).getByText('· 이 tenant의 패스키 등록·인증 모두 거부')).toBeInTheDocument();
    expect(dlg).not.toHaveTextContent('ceremony');
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    expect(within(dlg).getByText('일치해야 정지 버튼이 활성화됩니다.')).toBeInTheDocument();
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
    expect(screen.getByText('acme · 모든 API 키 회수됨')).toBeInTheDocument();
    expect(screen.queryByText(/revoke/)).toBeNull();
  });
```
```tsx
  it('정지 실패는 다이얼로그 안 인라인 오류, 닫히지 않고 토스트도 없다', async () => {
    (tenantsApi.suspend as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('boom'));
    const { onReload } = renderPage();
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '테넌트 정지' }));
    const dlg = screen.getByRole('alertdialog', { name: 'Acme을 정지할까요?' });
    fireEvent.change(within(dlg).getByRole('textbox', { name: /확인을 위해 slug/ }), { target: { value: 'acme' } });
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '정지' })); });
    expect(within(dlg).getByText('정지하지 못했습니다')).toBeInTheDocument();
    expect(screen.getByRole('alertdialog', { name: 'Acme을 정지할까요?' })).toBeInTheDocument();
    expect(onReload).not.toHaveBeenCalled();
    expect(screen.queryByText('정지 실패')).toBeNull();
  });
```
`it('정지 해제: …')` 블록의 189행 기대값 다음 줄에 추가(용어 검증):
```tsx
    expect(within(dlg).getByText(/패스키 등록·인증이 다시 허용됩니다/)).toBeInTheDocument();
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/TenantDetailPage.test.tsx`
Expected: FAIL — 다이얼로그 이름이 "테넌트 정지", 배지 라벨이 "확인 필요"(숫자 없음), 토스트 메시지가 "acme · 모든 API 키 revoke됨", 정지 해제 문구에 "ceremony".

- [ ] **Step 3: 구현**

`admin-ui/src/pages/TenantDetailPage.tsx`:

2행 import 교체:
```tsx
import { useState, useEffect } from 'react';
```
11행 `@/ui` import 교체:
```tsx
import { Alert, Button, ConfirmDialog, Dialog, EmptyState, LoadError, Menu, PropChip, Skeleton, StatusBadge, Tabs, useToast, type TabItem } from '@/ui';
```
19행 교체 + josa import 추가:
```tsx
import { countExpiringKeys, unverifiedAaguidCount } from '@/pages/tenant/tenantSignals';
import { eulReul } from '@/lib/josa';
```

49–99행 `SuspendDialog` 전체를 교체:
```tsx
// ── SuspendDialog — L3 slug 입력 확인 (V3 Dialogs-Tenant) ─────────────────────

function SuspendDialog({ tenant, open, onClose, onConfirmed }: {
  tenant: Tenant; open: boolean; onClose: () => void; onConfirmed: () => void;
}) {
  const toast = useToast();
  return (
    <ConfirmDialog
      open={open}
      onClose={onClose}
      level="L3"
      verb="정지"
      title={`${tenant.name}${eulReul(tenant.name)} 정지할까요?`}
      sub="정지하면 이 tenant의 모든 RP 서버 요청이 거부됩니다."
      typeToConfirm={{
        expected: tenant.slug,
        label: <>확인을 위해 slug <span className="mono" style={{ background: 'var(--sunk)', padding: '0 4px', borderRadius: 'var(--r-xs)' }}>{tenant.slug}</span> 를 입력하세요</>,
      }}
      onConfirm={async () => {
        await tenantsApi.suspend(tenant.id);
        toast({ kind: 'warn', title: '테넌트가 정지되었습니다.', message: `${tenant.slug} · 모든 API 키 회수됨` });
        onConfirmed();
      }}
    >
      <Alert tone="bad" title="영향 범위">
        <ul className="stack-1" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          <li>· 유효한 API 키 {tenant.apiKeys}개를 즉시 회수</li>
          <li>· 이 tenant의 패스키 등록·인증 모두 거부</li>
          <li>· 정지를 해제해도 회수된 키는 복구되지 않음</li>
        </ul>
      </Alert>
    </ConfirmDialog>
  );
}
```

`ResumeDialog` 127–129행 문단 교체:
```tsx
      <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>
        이 테넌트의 정지를 해제하시겠습니까? 패스키 등록·인증이 다시 허용됩니다. 정지 때 회수된 API 키는 복구되지 않으므로 필요하면 새로 발급하세요.
      </p>
```

탭 배지 계산(Task 26 에서 임시로 바꾼 187–192행 블록)을 교체:
```tsx
  const expiringKeyCount = apiKeys ? countExpiringKeys(apiKeys, Date.now()) : undefined;
  const unverified = unverifiedAaguidCount(aaguidPolicy);
  const tabs: TabItem[] = TENANT_TABS.map((t) => {
    if (t.id === 'aaguid' && unverified > 0) return { ...t, badge: { text: `확인 필요 ${unverified}`, reason: `MDS 미확인 AAGUID ${unverified}건` } };
    if (t.id === 'apikeys' && (expiringKeyCount ?? 0) > 0) return { ...t, badge: { text: `확인 필요 ${expiringKeyCount}`, reason: `30일 안에 만료되는 키 ${expiringKeyCount}개` } };
    return t;
  });
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/TenantDetailPage.test.tsx && npx tsc -b && npx eslint src`
Expected: PASS. (`취소하면 다시 열 때 입력이 초기화된다` 는 `ConfirmDialog` 가 닫힐 때 `ConfirmByTyping` 을 언마운트하므로 그대로 통과.)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/TenantDetailPage.tsx admin-ui/src/pages/TenantDetailPage.test.tsx
git commit -m "feat(admin-ui): Tenant 정지를 L3 확인으로, 탭 경고를 '확인 필요 N' 라벨로, 토스트 '회수됨'

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 29: 개요 탭 — 퍼널 실패 `LoadError`, 체인 카드 확인 불가, 수동 검증 연타 방지

**Files:**
- Modify: `admin-ui/src/pages/tenant/TenantOverview.tsx` (전체 교체)
- Test: `admin-ui/src/pages/tenant/TenantOverview.test.tsx`

**Interfaces:**
- Consumes: `useLoad` (Task 1), `describeError` (Task 1), `LoadError compact` (Task 2), `Badge`
- Produces:
  - `export const CHAIN_TIMEOUT_MS = 15_000`
  - `type Props = { tenant: Tenant; isPlatformOperator?: boolean; expiringKeyCount?: number; chainTimeoutMs?: number }` — `chainTimeoutMs` 는 테스트용(기본 15초). 이 시간 안에 체인 검증 응답이 없으면 "확인 불가".

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/pages/tenant/TenantOverview.test.tsx` 의 `renderOverview` 를 교체(선택 prop 추가):
```tsx
function renderOverview(props: { tenant?: Tenant; isPlatformOperator?: boolean; expiringKeyCount?: number; chainTimeoutMs?: number } = {}) {
  return renderWithDataRouter(
    <ToastHost><TenantOverview tenant={props.tenant ?? T} isPlatformOperator={props.isPlatformOperator} expiringKeyCount={props.expiringKeyCount} chainTimeoutMs={props.chainTimeoutMs} /></ToastHost>,
    { initialPath: '/tenants/t-1?tab=overview' },
  );
}
```
import 에 `waitFor` 를 추가하고(`import { screen, fireEvent, cleanup, act, waitFor } from '@testing-library/react';`), `describe('TenantOverview', …)` 끝에 추가:
```tsx
  it('퍼널 실패: 성공률 KPI 는 "—" + "불러오지 못했습니다", "등록 시도 없음"으로 보이지 않고 다시 시도', async () => {
    (funnelApi.get as ReturnType<typeof vi.fn>)
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({
        windowDays: 7, conversion: 0, series: [], byEventType: [],
        registration: { attempts: 10, success: 9, ratio: 0.9 },
        authentication: { attempts: 200, success: 190, ratio: 0.95 },
      });
    renderOverview({ isPlatformOperator: false });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('퍼널 지표를 불러오지 못했습니다');
    expect(screen.getAllByText('불러오지 못했습니다')).toHaveLength(2);
    expect(screen.queryByText('최근 7일 등록 시도 없음')).toBeNull();
    expect(screen.queryByText('최근 7일 인증 시도 없음')).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 시도' })); });
    expect(await screen.findByText('90.0%')).toBeInTheDocument();
    expect(funnelApi.get).toHaveBeenCalledTimes(2);
  });

  it('퍼널 로딩 중에는 "등록 시도 없음"을 보이지 않는다', () => {
    (funnelApi.get as ReturnType<typeof vi.fn>).mockReturnValue(new Promise(() => {}));
    renderOverview();
    expect(screen.queryByText('최근 7일 등록 시도 없음')).toBeNull();
  });

  it('체인 검증 실패: "확인 중"에 멈추지 않고 확인 불가 + 다시 시도', async () => {
    (auditChainApi.verifyTenant as ReturnType<typeof vi.fn>)
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({ tenantId: 't-1', intact: true, tamperedEntryId: null, verifiedAt: new Date().toISOString() });
    renderOverview({ isPlatformOperator: true });
    expect(await screen.findByText('검증 결과를 받지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText('확인 불가')).toBeInTheDocument();
    expect(screen.queryByText('확인 중')).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 시도' })); });
    expect(await screen.findByText('무결 · 위변조 0건')).toBeInTheDocument();
    expect(auditChainApi.verifyTenant).toHaveBeenCalledTimes(2);
  });

  it('체인 검증 응답이 제한 시간 안에 없으면 확인 불가, "감사 로그로"는 감사 로그 탭', async () => {
    (auditChainApi.verifyTenant as ReturnType<typeof vi.fn>).mockReturnValue(new Promise(() => {}));
    const { router } = renderOverview({ isPlatformOperator: true, chainTimeoutMs: 20 });
    expect(screen.getByText('확인 중')).toBeInTheDocument();
    expect(await screen.findByText('검증 결과를 받지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText(/응답이 없었습니다/)).toBeInTheDocument();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '감사 로그로' })); });
    expect(router.state.location.search).toBe('?tab=audit');
  });

  it('수동 검증 중에는 "검증 중…" 비활성, 연타해도 한 번만 호출', async () => {
    renderOverview({ isPlatformOperator: true });
    await screen.findByText('무결 · 위변조 0건');
    (auditChainApi.verifyTenant as ReturnType<typeof vi.fn>).mockReturnValue(new Promise(() => {}));
    const btn = screen.getByRole('button', { name: '수동 검증' });
    fireEvent.click(btn);
    const busy = screen.getByRole('button', { name: '검증 중…' });
    expect(busy).toBeDisabled();
    fireEvent.click(busy);
    expect(auditChainApi.verifyTenant).toHaveBeenCalledTimes(2); // 처음 로드 1 + 수동 1
    expect(screen.getByRole('button', { name: '월간 보고서' })).toBeDisabled();
    await waitFor(() => expect(screen.getByText('검증 중')).toBeInTheDocument());
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/tenant/TenantOverview.test.tsx`
Expected: FAIL — 퍼널 실패 시 `alert` 없음("최근 7일 등록 시도 없음" 표시), 체인 실패 시 "확인 중" 고정, `검증 중…` 버튼 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/tenant/TenantOverview.tsx` 전체 교체:
```tsx
// admin-ui/src/pages/tenant/TenantOverview.tsx
import { useState, useEffect, useRef, CSSProperties } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import type { AuditEvent, Tenant } from '@/api/designTypes';
import { auditChainApi, type TenantChainVerify } from '@/api/auditChain';
import { funnelApi, type FunnelStage } from '@/api/funnel';
import { activityApi } from '@/api/activity';
import { auditApi } from '@/api/audit';
import { describeError } from '@/lib/describeError';
import { useLoad } from '@/lib/useLoad';
import { Alert, Badge, Button, Card, CardBody, CardHeader, KpiCard, LoadError, StatusStrip, useToast } from '@/ui';
import { adaptFeedItems, type RecentActivityEvent } from './recentActivityAdapter';
import { recentRunsLabel, type ChainRun } from './tenantSignals';

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

function auditToRecent(e: AuditEvent): RecentActivityEvent {
  return {
    id: e.id, ts: e.ts, tenantId: null, tenantName: '', tenantSlug: null, type: e.eventType,
    actorType: e.actorType, actorId: e.actorId, subjectId: e.subjectId ?? '—', category: 'ops',
  };
}

const iconBox = (bg: string, fg: string): CSSProperties => ({
  width: 40, height: 40, borderRadius: 10, background: bg, color: fg,
  display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flex: 'none',
});

/** 체인 검증 응답을 이 시간까지 기다린 뒤 "확인 불가"로 바꾼다(V3 개요 — "확인 중"에 멈추지 않음). */
export const CHAIN_TIMEOUT_MS = 15_000;

type ChainView =
  | { kind: 'checking' }
  | { kind: 'done'; r: TenantChainVerify }
  | { kind: 'unavailable'; reason: string };

// ── ChainSummaryCard — 무결 · 위변조 · 확인 중 · 확인 불가 + 검증 중 ─────────────

function ChainSummaryCard({ view, verifying, recentRuns, onRetry, onManualVerify, onMonthlyReport, onDetail }: {
  view: ChainView;
  verifying: boolean;
  recentRuns?: ChainRun[];
  onRetry: () => void;
  onManualVerify: () => void;
  onMonthlyReport: () => void;
  onDetail: () => void;
}) {
  return (
    <Card>
      <CardHeader title="감사 체인" actions={<Button variant="ghost" size="sm" onClick={onDetail}>자세히 <Icons.ChevronRight size={12} /></Button>} />
      <CardBody className="stack-3">
        {verifying ? (
          <div className="row" style={{ gap: 10 }} aria-busy="true">
            <Badge>검증 중</Badge>
            <span className="hint" style={{ margin: 0 }}>수동 검증을 실행하고 있습니다.</span>
          </div>
        ) : view.kind === 'checking' ? (
          <div className="row" style={{ gap: 12, flexWrap: 'nowrap' }}>
            <span style={iconBox('var(--sunk)', 'var(--mute)')} aria-hidden="true"><Icons.Shield size={20} /></span>
            <div className="col" style={{ minWidth: 0 }}>
              <strong>확인 중</strong>
              <span className="hint" style={{ margin: 0 }}>체인 무결성 상태를 확인하고 있습니다.</span>
            </div>
          </div>
        ) : view.kind === 'unavailable' ? (
          <div className="stack-2">
            <div className="row" style={{ gap: 10 }}>
              <Badge>확인 불가</Badge>
              <strong>검증 결과를 받지 못했습니다</strong>
            </div>
            <span className="hint" style={{ margin: 0 }}>{view.reason} 다시 시도하거나 감사 로그에서 확인하세요.</span>
          </div>
        ) : view.r.intact ? (
          <div className="row" style={{ gap: 12, flexWrap: 'nowrap' }}>
            <span style={iconBox('var(--ok-soft)', 'var(--ok)')} aria-hidden="true"><Icons.Shield size={20} /></span>
            <div className="col" style={{ minWidth: 0 }}>
              <strong>무결 · 위변조 0건</strong>
              <span className="hint" style={{ margin: 0 }}>마지막 검증 {timeAgo(view.r.verifiedAt)}</span>
            </div>
          </div>
        ) : (
          <Alert tone="bad" title="위변조 감지">
            마지막 검증 {timeAgo(view.r.verifiedAt)}{view.r.tamperedEntryId ? ` · 위변조 항목 …${tail(view.r.tamperedEntryId, 8)}` : ''}
          </Alert>
        )}
        {recentRuns && recentRuns.length > 0 && (
          <StatusStrip fluid results={recentRuns.map((r) => r.ok)} label={recentRunsLabel(recentRuns)} />
        )}
        {view.kind === 'unavailable' && !verifying ? (
          <div className="row">
            <Button size="sm" variant="primary" onClick={onRetry}>다시 시도</Button>
            <Button size="sm" variant="ghost" onClick={onDetail}>감사 로그로</Button>
          </div>
        ) : (
          <div className="row">
            <Button size="sm" onClick={onManualVerify} disabled={verifying || view.kind === 'checking'}>{verifying ? '검증 중…' : '수동 검증'}</Button>
            <Button size="sm" onClick={onMonthlyReport} disabled={verifying}>월간 보고서</Button>
          </div>
        )}
      </CardBody>
    </Card>
  );
}

// ── TenantOverview ───────────────────────────────────────────────────────────

type Props = { tenant: Tenant; isPlatformOperator?: boolean; expiringKeyCount?: number; chainTimeoutMs?: number };

export default function TenantOverview({ tenant, isPlatformOperator = false, expiringKeyCount, chainTimeoutMs = CHAIN_TIMEOUT_MS }: Props) {
  const navigate = useNavigate();
  const toast = useToast();
  const [chain, setChain] = useState<ChainView>({ kind: 'checking' });
  const [chainAttempt, setChainAttempt] = useState(0);
  const [verifying, setVerifying] = useState(false);
  const verifyingRef = useRef(false);
  const [recentEvents, setRecentEvents] = useState<RecentActivityEvent[]>([]);
  const funnel = useLoad(() => funnelApi.get(tenant.id, 7), [tenant.id]);

  async function handleManualVerify() {
    if (verifyingRef.current) return; // 같은 틱의 연타까지 막는다
    verifyingRef.current = true;
    setVerifying(true);
    try {
      const r = await auditChainApi.verifyTenant(tenant.id);
      setChain({ kind: 'done', r });
      toast({ kind: r.intact ? 'ok' : 'warn', title: '수동 검증 완료', message: r.intact ? '체인 무결' : '위변조 감지' });
    } catch (e: unknown) {
      const info = describeError(e);
      toast({ kind: 'err', title: '수동 검증 실패', message: `${info.title}. ${info.detail}` });
    } finally {
      verifyingRef.current = false;
      setVerifying(false);
    }
  }

  // 감사 체인 검증(verifyTenant)은 PLATFORM 전용 API — RP_ADMIN 에게는 카드 자체를 그리지 않는다.
  useEffect(() => {
    if (!isPlatformOperator) return;
    let cancelled = false;
    setChain({ kind: 'checking' });
    const timer = setTimeout(() => {
      if (!cancelled) setChain((c) => (c.kind === 'checking' ? { kind: 'unavailable', reason: `${Math.round(chainTimeoutMs / 1000)}초 안에 응답이 없었습니다.` } : c));
    }, chainTimeoutMs);
    auditChainApi.verifyTenant(tenant.id)
      .then((r) => { if (!cancelled) setChain({ kind: 'done', r }); })
      .catch((e: unknown) => { if (!cancelled) setChain({ kind: 'unavailable', reason: `${describeError(e).title}.` }); })
      .finally(() => clearTimeout(timer));
    return () => { cancelled = true; clearTimeout(timer); };
  }, [tenant.id, isPlatformOperator, chainAttempt, chainTimeoutMs]);

  // ★ 최근 검증 이력(recentRuns)은 서버에 아직 없다. 그 필드를 찾으려고 전 tenant 체인을 검증하는
  // overview 를 부르지 않는다 — 서버가 필드를 주면 여기서 읽어 ChainSummaryCard 의 recentRuns 로 넘긴다.

  useEffect(() => {
    let cancelled = false;
    // PLATFORM: 활동 피드(PLATFORM 전용)에서 이 tenant 만. RP_ADMIN: 이 tenant 의 감사 로그 최근 5건.
    const load: Promise<RecentActivityEvent[]> = isPlatformOperator
      ? activityApi.fetch(null, undefined).then((view) => adaptFeedItems(view).filter((e) => e.tenantId === tenant.id).slice(0, 5))
      : auditApi.list(tenant.id, 0, 5).then((res) => res.items.slice(0, 5).map(auditToRecent));
    load
      .then((events) => { if (!cancelled) setRecentEvents(events); })
      .catch(() => { if (!cancelled) setRecentEvents([]); });
    return () => { cancelled = true; };
  }, [tenant.id, isPlatformOperator]);

  const delta = tenant.credentialsDelta7d;
  const f = funnel.status === 'ok' ? funnel.data : undefined;

  // 실패는 "—" + 원인, 로딩은 "—" 만. "시도 없음"은 조회가 성공했을 때만.
  function rateKpi(label: string, stage: 'registration' | 'authentication', none: string) {
    if (funnel.status === 'failed') return <KpiCard label={label} value="—" hint="불러오지 못했습니다" hintTone="warn" />;
    if (!f) return <KpiCard label={label} value="—" />;
    const s: FunnelStage = f[stage];
    return (
      <KpiCard label={label}
        value={s.attempts > 0 ? `${(s.ratio * 100).toFixed(1)}%` : '—'}
        hint={s.attempts > 0 ? `${fmt(s.success)} / ${fmt(s.attempts)} 시도` : none} />
    );
  }

  return (
    <div className="stack-6">
      <div className="kpis">
        <KpiCard label="등록 Credential" value={fmt(tenant.credentials)}
          hint={delta !== undefined ? `7일 ${delta >= 0 ? '+' : ''}${fmt(delta)}` : undefined} />
        <KpiCard label="유효 API Key" value={tenant.apiKeys}
          hint={expiringKeyCount !== undefined ? `만료 임박 ${expiringKeyCount}` : undefined}
          hintTone={expiringKeyCount ? 'warn' : 'default'} />
        {rateKpi('등록 성공률 (7d)', 'registration', '최근 7일 등록 시도 없음')}
        {rateKpi('인증 성공률 (7d)', 'authentication', '최근 7일 인증 시도 없음')}
      </div>
      {funnel.status === 'failed' && (
        <LoadError compact what="퍼널 지표" error={funnel.error} onRetry={funnel.reload} retrying={funnel.reloading} />
      )}

      <div className={isPlatformOperator ? 'grid-3' : 'grid-2'}>
        <Card>
          <CardHeader title="WebAuthn 요약" actions={
            <Button variant="ghost" size="sm" onClick={() => navigate(`/tenants/${tenant.id}?tab=webauthn`)}>편집 <Icons.ChevronRight size={12} /></Button>
          } />
          <CardBody>
            <dl className="kv" style={{ gridTemplateColumns: '120px minmax(0, 1fr)' }}>
              <dt>rpId</dt><dd className="mono truncate" style={{ fontSize: 12 }} title={tenant.rpId}>{tenant.rpId}</dd>
              <dt>rpName</dt><dd className="truncate" title={tenant.name}>{tenant.name}</dd>
              {tenant.allowedOrigins && (
                <><dt>origins</dt><dd className="row" style={{ gap: 4 }}>{tenant.allowedOrigins.map((o) => <span key={o} className="tag">{o}</span>)}</dd></>
              )}
              <dt>userVerification</dt><dd><span className="tag">{tenant.userVerification}</span></dd>
              <dt>attestation</dt><dd><span className="tag">{tenant.attestationConveyance}</span></dd>
              <dt>timeout</dt><dd className="mono" style={{ fontSize: 12 }}>{tenant.webauthnTimeoutMs} ms</dd>
            </dl>
          </CardBody>
        </Card>

        <Card>
          <CardHeader title="최근 활동" actions={
            <Button variant="ghost" size="sm" onClick={() => navigate(isPlatformOperator ? `/activity?tenantId=${tenant.id}` : `/tenants/${tenant.id}?tab=audit`)}>전체 보기 <Icons.ChevronRight size={12} /></Button>
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

        {isPlatformOperator && (
          <ChainSummaryCard
            view={chain}
            verifying={verifying}
            onRetry={() => setChainAttempt((n) => n + 1)}
            onManualVerify={() => void handleManualVerify()}
            onMonthlyReport={() => navigate('/audit-chain')}
            onDetail={() => navigate(`/tenants/${tenant.id}?tab=audit`)}
          />
        )}
      </div>
    </div>
  );
}
```

`FunnelStage` 가 `@/api/funnel` 에서 export 되어 있는지 확인(`api/funnel.ts:3` — 있음).

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/tenant/TenantOverview.test.tsx`
Expected: PASS — 새 5개 + 기존 10개(기존 "수동 검증은 verifyTenant 재호출…" 은 버튼 이름이 처리 후 다시 "수동 검증" 으로 돌아오므로 그대로 통과).

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/tenant/TenantOverview.tsx admin-ui/src/pages/tenant/TenantOverview.test.tsx
git commit -m "fix(admin-ui): 개요 퍼널 실패를 '시도 없음'과 구분, 체인 카드 확인 불가·다시 시도, 수동 검증 연타 방지

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 30: WebAuthn 탭 — `LoadError`, "자세히" 문서 링크, L2 저장 확인

**Files:**
- Modify: `admin-ui/src/pages/tenant/WebauthnConfigTab.tsx`
- Test: `admin-ui/src/pages/tenant/WebauthnConfigTab.view.test.tsx`

**Interfaces:**
- Consumes: `useLoad`, `describeError` (Task 1), `LoadError` (Task 2), `Skeleton` (Task 3), `ConfirmDialog` L2 (Task 4), `HELP_LINKS.rpGuide: string` (`@/config/links`, 셸 묶음)
- Produces: `export const FIELD_LABELS: Record<string, string>` (diff 표시용 화면 라벨). `changedFieldNames`·`validateOrigin` 시그니처 유지.

- [ ] **Step 0: 선행 확인**

Run: `grep -n "rpGuide" src/config/links.ts`
Expected: `rpGuide` 를 가진 `HELP_LINKS` export 가 있다. 없으면 셸 묶음의 도움말 Task 를 먼저 끝낸다(이 Task 는 `HELP_LINKS` 를 만들지 않는다).

- [ ] **Step 1: 실패하는 테스트 작성 + 바뀐 동작의 기존 테스트 갱신**

`admin-ui/src/pages/tenant/WebauthnConfigTab.view.test.tsx`:

9행 아래에 추가:
```tsx
vi.mock('@/config/links', () => ({ HELP_LINKS: { rpGuide: 'https://docs.example/rp', apiDocs: '', support: '' } }));
import { HELP_LINKS } from '@/config/links';
import { ApiError } from '@/api/types';
```

`it('설명 좌/컨트롤 우 행, …')` 의 55행 기대값을 교체:
```tsx
    expect(screen.getByRole('heading', { name: 'WebAuthn 설정' })).toBeInTheDocument();
    expect(screen.getByText('변경 사항은 저장 후 다음 패스키 등록·인증부터 적용됩니다.')).toBeInTheDocument();
```

`it('저장… → diff 확인 다이얼로그 → …')` 블록을 교체:
```tsx
  it('저장… → L2 변경 비교(화면 라벨·초 단위) → 기존과 같은 payload 로 update', async () => {
    (webauthnApi.diff as ReturnType<typeof vi.fn>).mockResolvedValue([{ key: 'timeoutMs', from: 60000, to: 120000 }]);
    (webauthnApi.update as ReturnType<typeof vi.fn>).mockResolvedValue({ ...CFG, timeoutMs: 120000 });
    renderTab();
    fireEvent.change(await screen.findByLabelText('timeoutMs'), { target: { value: '120000' } });
    await act(async () => { fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' })); });
    expect(webauthnApi.diff).toHaveBeenCalledWith('t-1', { ...CFG, timeoutMs: 120000 }, true);
    const dlg = screen.getByRole('alertdialog', { name: '변경 사항 1개를 저장할까요?' });
    expect(within(dlg).getByText('다음 패스키 등록·인증부터 적용됩니다.')).toBeInTheDocument();
    expect(within(dlg).getByText('대기 시간')).toHaveAttribute('title', 'timeoutMs');
    expect(within(dlg).getByText('60초')).toBeInTheDocument();
    expect(within(dlg).getByText('120초')).toBeInTheDocument();
    expect(within(dlg).getByText(/WebAuthn 설정 변경/)).toBeInTheDocument();
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '저장' })); });
    expect(webauthnApi.update).toHaveBeenCalledWith('t-1', 'Acme', { ...CFG, timeoutMs: 120000 }, true);
    await waitFor(() => expect(bar()).toBeNull());
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(screen.getByText('WebAuthn 설정을 저장했습니다')).toBeInTheDocument();
    expect(onSaved).toHaveBeenCalledTimes(1);
  });
```

`it('diff 가 비면 "변경 사항이 없습니다."')` 블록을 교체:
```tsx
  it('diff 가 비면 확인 다이얼로그 없이 "변경 사항이 없습니다."', async () => {
    (webauthnApi.diff as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    renderTab();
    fireEvent.change(await screen.findByLabelText('timeoutMs'), { target: { value: '90000' } });
    await act(async () => { fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' })); });
    expect(screen.getByText('변경 사항이 없습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });
```

`describe('WebauthnConfigTab V2', …)` 끝에 추가:
```tsx
  it('저장 실패는 다이얼로그 안 인라인 오류, 입력과 SaveBar 는 그대로', async () => {
    (webauthnApi.diff as ReturnType<typeof vi.fn>).mockResolvedValue([{ key: 'timeoutMs', from: 60000, to: 120000 }]);
    (webauthnApi.update as ReturnType<typeof vi.fn>).mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    fireEvent.change(await screen.findByLabelText('timeoutMs'), { target: { value: '120000' } });
    await act(async () => { fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' })); });
    const dlg = screen.getByRole('alertdialog', { name: '변경 사항 1개를 저장할까요?' });
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '저장' })); });
    expect(within(dlg).getByText('저장하지 못했습니다')).toBeInTheDocument();
    expect(within(dlg).getByText(/서버가 잠시 응답하지 않습니다/)).toBeInTheDocument();
    expect(screen.getByLabelText('timeoutMs')).toHaveValue(120000);
    expect(bar()).toHaveTextContent('저장하지 않은 변경 1건');
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('로드 실패: "Loading…"에 멈추지 않고 LoadError, 다시 시도로 복구', async () => {
    (webauthnApi.get as ReturnType<typeof vi.fn>)
      .mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'))
      .mockResolvedValueOnce({ ...CFG, origins: [...CFG.origins] });
    renderTab();
    expect(await screen.findByText('WebAuthn 설정을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByText('Loading…')).toBeNull();
    expect(screen.queryByText('config 로드 실패')).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 시도' })); });
    expect(await screen.findByLabelText('rpId')).toHaveValue('dev-passkey.crosscert.com');
  });

  it('로딩 중에는 Skeleton', () => {
    (webauthnApi.get as ReturnType<typeof vi.fn>).mockReturnValue(new Promise(() => {}));
    renderTab();
    expect(screen.getByRole('status', { name: 'WebAuthn 설정 불러오는 중' })).toBeInTheDocument();
  });

  it('"자세히" 문서 링크와 RP 연동 가이드 — 주소가 비면 숨긴다', async () => {
    const { unmount } = renderTab();
    await screen.findByLabelText('rpId');
    expect(screen.getByRole('link', { name: /RP 연동 가이드/ })).toHaveAttribute('href', 'https://docs.example/rp');
    expect(screen.getByRole('link', { name: '허용 origin 자세히' })).toHaveAttribute('href', 'https://docs.example/rp#origins');
    expect(screen.getByRole('link', { name: '사용자 검증 자세히' })).toHaveAttribute('href', 'https://docs.example/rp#user-verification');
    expect(screen.getByRole('link', { name: '인증기 증명 자세히' })).toHaveAttribute('target', '_blank');
    unmount();
    const links = HELP_LINKS as { rpGuide: string };
    links.rpGuide = '';
    try {
      renderTab();
      await screen.findByLabelText('rpId');
      expect(screen.queryByRole('link', { name: /자세히|RP 연동 가이드/ })).toBeNull();
    } finally {
      links.rpGuide = 'https://docs.example/rp';
    }
  });

  it('사용자 문구에 ceremony 를 쓰지 않는다', async () => {
    const { container } = renderTab();
    await screen.findByLabelText('rpId');
    expect(container).not.toHaveTextContent(/ceremony/i);
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/tenant/WebauthnConfigTab.view.test.tsx`
Expected: FAIL — 제목이 "WebAuthn Configuration", 다이얼로그가 `role=dialog` "변경 사항 확인", 로드 실패 시 "Loading…" 고정, 링크 없음, "ceremony" 문구.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/tenant/WebauthnConfigTab.tsx`:

7행 `@/ui` import 를 교체하고 그 아래에 import 3줄 추가:
```tsx
import { Alert, Badge, Button, Card, CardHeader, ChipInput, ConfirmDialog, LoadError, SaveBar, Segmented, Skeleton, useToast, useUnsavedGuard } from '@/ui';
import { useLoad } from '@/lib/useLoad';
import { describeError } from '@/lib/describeError';
import { HELP_LINKS } from '@/config/links';
```

29행 `const FIELD_ORDER = …` 바로 위에 추가:
```tsx
/** diff 다이얼로그용 화면 라벨. 원시 키는 title 툴팁으로 남긴다. */
export const FIELD_LABELS: Record<string, string> = {
  rpName: 'RP 표시 이름',
  timeoutMs: '대기 시간',
  origins: '허용 origin',
  userVerification: '사용자 검증',
  attestationConveyance: '인증기 증명',
  formats: '허용 증명 형식',
};
```

83행 `REQUIRED` 문구 교체:
```tsx
  REQUIRED: { tone: 'info', text: 'PIN·지문·얼굴 등 사용자 확인(UV)을 반드시 요구합니다. UV를 못 하는 인증기는 패스키 등록·인증이 실패합니다. 2FA 수준의 보안이 필요할 때 권장.' },
```
111행 교체:
```tsx
  if (ms > 120000) return { tone: 'warn', text: `${human} — 권장(120초)보다 깁니다. 너무 길면 만료를 기다리는 동안 서버 자원이 오래 점유됩니다.` };
```

117행(`AT_OPTIONS`) 아래에 추가:
```tsx
/** "자세히" 문서 링크. 주소가 비면 그리지 않는다(스펙 §2 도움말). */
function DocLink({ anchor, label }: { anchor: string; label: string }) {
  if (!HELP_LINKS.rpGuide) return null;
  return <a href={`${HELP_LINKS.rpGuide}#${anchor}`} target="_blank" rel="noopener noreferrer" aria-label={`${label} 자세히`}>자세히</a>;
}
```

124–145행(컴포넌트 시작 ~ 로드 `useEffect`)을 교체:
```tsx
export default function WebauthnConfigTab({ tenant, onSaved }: WebauthnConfigTabProps) {
  const load = useLoad(() => webauthnApi.get(tenant.id), [tenant.id]);
  const [cfg, setCfg] = useState<ConfigWithMds | null>(null);
  const [draft, setDraft] = useState<ConfigWithMds | null>(null);
  const [apkInput, setApkInput] = useState('');
  const [apkError, setApkError] = useState<string | null>(null);
  const [showDiff, setShowDiff] = useState(false);
  const [diffChanges, setDiffChanges] = useState<Change[]>([]);
  const toast = useToast();

  useEffect(() => {
    if (!load.data) return;
    setCfg(load.data);
    setDraft({ ...load.data });
  }, [load.data]);
```

195–227행(`handlePreview`, `handleSave`, 로딩 분기)을 교체:
```tsx
  async function handlePreview() {
    if (!draft) return;
    try {
      const r = await webauthnApi.diff(tenant.id, draft, draft._mdsRequired);
      if (r.length === 0) {
        toast({ kind: 'warn', title: '변경 사항이 없습니다.' });
        return;
      }
      setDiffChanges(r);
      setShowDiff(true);
    } catch (e: unknown) {
      const info = describeError(e);
      toast({ kind: 'err', title: '변경 사항을 비교하지 못했습니다', message: `${info.title}. ${info.detail}` });
    }
  }

  /** ConfirmDialog 의 onConfirm — 실패하면 throw 해서 다이얼로그 안에 오류를 보인다(입력 유지). */
  async function handleSave() {
    if (!draft) return;
    const updated = await webauthnApi.update(tenant.id, tenant.name, draft, draft._mdsRequired);
    setCfg(updated);
    setDraft({ ...updated });
    toast({ kind: 'ok', title: 'WebAuthn 설정을 저장했습니다', message: `${diffChanges.length}개 항목이 바뀌었습니다.` });
    onSaved?.();
  }

  if (load.status === 'failed' && !cfg) {
    return <LoadError what="WebAuthn 설정" error={load.error} onRetry={load.reload} retrying={load.reloading} />;
  }
  if (!draft || !cfg) {
    return <Skeleton rows={6} label="WebAuthn 설정 불러오는 중" />;
  }
```

237행 `CardHeader` 교체:
```tsx
        <CardHeader
          title="WebAuthn 설정"
          sub="변경 사항은 저장 후 다음 패스키 등록·인증부터 적용됩니다."
          actions={HELP_LINKS.rpGuide ? (
            <a className="btn btn--ghost btn--sm" href={HELP_LINKS.rpGuide} target="_blank" rel="noopener noreferrer">RP 연동 가이드 <Icons.ExternalLink size={13} /></a>
          ) : undefined}
        />
```

254행 `SetRow label="timeoutMs"` 의 `desc` 를 `desc="사용자가 패스키 창에 응답할 때까지 기다리는 시간"` 으로 바꾼다.
264행 `SetRow label="origins"` 의 `desc` 를 교체:
```tsx
        <SetRow label="origins" desc={<>허용할 웹 origin 과 Android 앱 키 해시. Enter 로 추가합니다. <DocLink anchor="origins" label="허용 origin" /></>} changed={changed.has('origins')}>
```
278행 교체:
```tsx
        <SetRow label="userVerification" desc={<>생체·PIN 검증 요구 수준. <DocLink anchor="user-verification" label="사용자 검증" /></>} changed={changed.has('userVerification')}>
```
286행 교체:
```tsx
        <SetRow label="attestationConveyance" desc={<>등록 시 인증기 증명 요청. <DocLink anchor="attestation" label="인증기 증명" /></>} changed={changed.has('attestationConveyance')}>
```

295–305행(SaveBar ~ `{guard}`)을 교체:
```tsx
      <SaveBar
        count={localChanges.length}
        summary={changedFieldNames(localChanges).join(' · ')}
        onRevert={handleRevert}
        onSave={() => void handlePreview()}
        saveLabel="저장…"
      />

      <DiffDialog open={showDiff} onClose={() => setShowDiff(false)} changes={diffChanges} onConfirm={handleSave} />
      {guard}
```

310–362행(`DiffDialog`·`FROM_TAG`·`TO_TAG`·`DiffRow`)을 교체:
```tsx
// ── DiffDialog — L2 변경 비교 (V3 Dialogs-Tenant) ─────────────────────────────

function DiffDialog({ open, onClose, changes, onConfirm }: {
  open: boolean;
  onClose: () => void;
  changes: Change[];
  onConfirm: () => Promise<void>;
}) {
  return (
    <ConfirmDialog open={open} onClose={onClose} level="L2" danger={false} verb="저장"
      title={`변경 사항 ${changes.length}개를 저장할까요?`} sub="다음 패스키 등록·인증부터 적용됩니다." onConfirm={onConfirm}>
      <div style={{ border: '1px solid var(--line)', borderRadius: 'var(--r)', overflow: 'hidden' }}>
        {changes.map((c, i) => <DiffRow key={c.key} c={c} last={i === changes.length - 1} />)}
      </div>
      <Alert tone="info">저장하면 감사 로그에 “WebAuthn 설정 변경”이 기록됩니다.</Alert>
    </ConfirmDialog>
  );
}

const FROM_TAG: CSSProperties = { background: 'var(--bad-soft)', color: 'var(--bad-ink)', textDecoration: 'line-through', maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis' };
const TO_TAG: CSSProperties = { background: 'var(--ok-soft)', color: 'var(--ok-ink)', maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis' };

/** 원시 값 대신 사람이 읽는 값. timeoutMs 는 초 단위. */
function showValue(key: string, v: unknown): string {
  if (key === 'timeoutMs' && typeof v === 'number') return `${parseFloat((v / 1000).toFixed(2))}초`;
  return String(v);
}

function DiffRow({ c, last }: { c: Change; last?: boolean }) {
  const rowStyle: CSSProperties = { gap: 10, padding: '12px 14px', borderBottom: last ? 0 : '1px solid var(--line-soft)', fontSize: 12 };
  const keyEl = <span style={{ width: 110, flex: 'none', color: 'var(--mute)' }} title={c.key}>{FIELD_LABELS[c.key] ?? c.key}</span>;
  if (Array.isArray(c.from) && Array.isArray(c.to)) {
    const from = c.from as string[];
    const to = c.to as string[];
    const added = to.filter((x) => !from.includes(x));
    const removed = from.filter((x) => !to.includes(x));
    return (
      <div className="row" style={rowStyle}>
        {keyEl}
        {removed.map((x) => <span key={'-' + x} className="tag mono" style={FROM_TAG} title={x}>- {x}</span>)}
        {added.map((x) => <span key={'+' + x} className="tag mono" style={TO_TAG} title={x}>+ {x}</span>)}
      </div>
    );
  }
  return (
    <div className="row" style={rowStyle}>
      {keyEl}
      <span className="tag" style={FROM_TAG}>{showValue(c.key, c.from)}</span>
      <span aria-hidden="true" style={{ display: 'inline-flex', color: 'var(--mute)' }}><Icons.ChevronRight size={14} /></span>
      <span className="tag" style={TO_TAG}>{showValue(c.key, c.to)}</span>
    </div>
  );
}
```
(`saving` 상태와 `setSaving` 은 쓰이지 않으므로 삭제된 상태다 — `ConfirmDialog` 가 처리 중 비활성을 맡는다. `Dialog` import 도 제거됨.)

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/tenant/WebauthnConfigTab.view.test.tsx src/pages/tenant/WebauthnConfigTab.test.ts && npx tsc -b && npx eslint src`
Expected: PASS (view 13 tests + 순수 함수 테스트).

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/tenant/WebauthnConfigTab.tsx admin-ui/src/pages/tenant/WebauthnConfigTab.view.test.tsx
git commit -m "feat(admin-ui): WebAuthn 탭 로드 실패 LoadError, 자세히 문서 링크, 저장 L2 변경 비교·인라인 오류

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 31: AAGUID 탭 — 경고 제목, 빈 허용 목록 강조, 저장 L2/L3, MDS 엄격 L2

**Files:**
- Modify: `admin-ui/src/pages/tenant/AaguidPolicyTab.tsx` (전체 교체)
- Test: `admin-ui/src/pages/tenant/AaguidPolicyTab.view.test.tsx`, `admin-ui/src/pages/tenant/AaguidPolicyTab.test.ts`

**Interfaces:**
- Consumes: `useLoad` (Task 1), `LoadError` (Task 2), `Skeleton` (Task 3), `ConfirmDialog` L2/L3 `acknowledge` (Task 4), `unverifiedAaguidCount` (기존)
- Produces:
  - `export type SaveConfirmKind = 'block-all' | 'mds-strict' | 'diff'`
  - `export function saveConfirmKind(saved: AaguidPolicy, draft: AaguidPolicy): SaveConfirmKind` — 허용 목록(ALLOWLIST)이 비면 `block-all`(L3 체크), 아니면 MDS 엄격을 켜는 변경이면 `mds-strict`(L2), 그 밖은 `diff`(L2)
  - `formatAaguid`, `aaguidChangedFields` 시그니처 유지

- [ ] **Step 1: 실패하는 테스트 작성 + 바뀐 문구의 기존 테스트 갱신**

`admin-ui/src/pages/tenant/AaguidPolicyTab.test.ts` 2행 import 교체 + 끝에 describe 추가:
```ts
import { formatAaguid, saveConfirmKind } from './AaguidPolicyTab';
import type { AaguidPolicy } from '@/api/designTypes';
```
```ts
describe('saveConfirmKind', () => {
  const p = (over: Partial<AaguidPolicy>): AaguidPolicy => ({ mode: 'ALLOWLIST', mdsStrict: false, entries: [{ aaguid: 'a', note: null, mdsName: 'X' }], ...over });
  it('허용 목록이 비면 block-all (MDS 엄격보다 우선)', () => {
    expect(saveConfirmKind(p({}), p({ entries: [], mdsStrict: true }))).toBe('block-all');
    expect(saveConfirmKind(p({}), p({ entries: [{ aaguid: '', note: null, mdsName: null }] }))).toBe('block-all');
  });
  it('차단 목록·전체 허용이 비는 것은 block-all 이 아니다', () => {
    expect(saveConfirmKind(p({}), p({ mode: 'DENYLIST', entries: [] }))).toBe('diff');
    expect(saveConfirmKind(p({}), p({ mode: 'ANY', entries: [] }))).toBe('diff');
  });
  it('MDS 엄격을 켜면 mds-strict, 끄는 것은 diff', () => {
    expect(saveConfirmKind(p({}), p({ mdsStrict: true }))).toBe('mds-strict');
    expect(saveConfirmKind(p({ mdsStrict: true }), p({ mdsStrict: false }))).toBe('diff');
  });
});
```

`admin-ui/src/pages/tenant/AaguidPolicyTab.view.test.tsx`:

9행 아래에 추가:
```tsx
import { ApiError } from '@/api/types';
```

`it('mode radiogroup, MDS 확인/미확인 배지, 탭 점 사유 Alert')` 블록 교체:
```tsx
  it('mode radiogroup, MDS 확인/미확인 배지, 무엇을·왜·어떻게 담은 경고 제목', async () => {
    renderTab();
    const group = await screen.findByRole('radiogroup', { name: 'mode' });
    expect(within(group).getAllByRole('radio')).toHaveLength(3);
    expect(within(group).getByRole('radio', { name: /허용 목록/ })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByText('YubiKey 5')).toBeInTheDocument();
    expect(screen.getByText('MDS 확인')).toHaveClass('badge--success');
    expect(screen.getByText('MDS 에 없음')).toBeInTheDocument();
    expect(screen.getByText('MDS 미확인')).toHaveClass('badge--warning');
    expect(screen.getByText('MDS 미확인 AAGUID 1건 — 인증기 모델을 확인하세요')).toBeInTheDocument();
    expect(screen.queryByText(/탭 점 배지 사유/)).toBeNull();
    expect(screen.getByRole('button', { name: '항목 보기' })).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'MDS strict 켜짐' })).toBeChecked();
    expect(bar()).toBeNull();
  });
```
`it('미확인 항목이 없으면 Alert 가 없다')` 의 마지막 줄 교체:
```tsx
    expect(screen.queryByText(/MDS 미확인 AAGUID/)).toBeNull();
```
`it('추가: 자동 포맷 → + 추가 → SaveBar → 저장 시 …')` 블록 교체:
```tsx
  it('추가: 자동 포맷 → + 추가 → SaveBar 저장… → L2 변경 비교 → 기존과 같은 payload, onSaved 호출', async () => {
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
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' }));
    const dlg = screen.getByRole('alertdialog', { name: '변경 사항 1개를 저장할까요?' });
    expect(within(dlg).getByText('+ abcdef01-2345-6789-abcd-ef0123456789')).toBeInTheDocument();
    expect(aaguidPolicyApi.update).not.toHaveBeenCalled();
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '저장' })); });
    expect(aaguidPolicyApi.update).toHaveBeenCalledWith('t-1', { mode: 'ALLOWLIST', mdsStrict: true, entries: [YUBI, UNKNOWN, added] });
    expect(onSaved).toHaveBeenCalledWith(saved);
    await waitFor(() => expect(bar()).toBeNull());
    expect(screen.getByText('AAGUID 정책 저장 완료')).toBeInTheDocument();
  });
```
`it('빈 허용 목록 문구')` 블록 교체:
```tsx
  it('빈 허용 목록: 목록 자리에 빨간 경고 + AAGUID 추가 / 전체 허용으로 되돌리기, 차단 목록은 안내만', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ mode: 'ALLOWLIST', mdsStrict: false, entries: [] });
    renderTab();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('허용 목록이 비어 있습니다');
    expect(alert).toHaveTextContent('이대로 저장하면 이 tenant의 모든 패스키 등록·인증이 차단됩니다.');
    fireEvent.click(within(alert).getByRole('button', { name: 'AAGUID 추가' }));
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: 'AAGUID 입력' }));
    fireEvent.click(screen.getByRole('radio', { name: /차단 목록/ }));
    expect(screen.queryByText('허용 목록이 비어 있습니다')).toBeNull();
    expect(screen.getByText('비어 있음 — 이 상태에서는 모든 패스키 등록·인증이 허용됩니다.')).toBeInTheDocument();
    expect(screen.getByText('차단된 AAGUID')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('radio', { name: /허용 목록/ }));
    fireEvent.click(within(screen.getByRole('alert')).getByRole('button', { name: '전체 허용으로 되돌리기' }));
    expect(screen.getByRole('radio', { name: /전체 허용/ })).toHaveAttribute('aria-checked', 'true');
  });
```
`describe('AaguidPolicyTab V2', …)` 끝에 추가:
```tsx
  it('허용 목록을 비우고 저장하면 L3 — "이해했습니다" 체크 전에는 저장 불가', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ mode: 'ALLOWLIST', mdsStrict: false, entries: [YUBI] });
    (aaguidPolicyApi.update as ReturnType<typeof vi.fn>).mockResolvedValue({ mode: 'ALLOWLIST', mdsStrict: false, entries: [] });
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: `${YUBI.aaguid} 삭제` }));
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' }));
    const dlg = screen.getByRole('alertdialog', { name: '모든 등록·인증을 차단할까요?' });
    expect(within(dlg).getByText(/허용 목록\(0개\)/)).toBeInTheDocument();
    const go = within(dlg).getByRole('button', { name: '차단 정책 저장' });
    expect(go).toBeDisabled();
    expect(go).toHaveClass('btn--danger-solid');
    fireEvent.click(within(dlg).getByLabelText('차단된다는 것을 이해했습니다'));
    expect(go).toBeEnabled();
    await act(async () => { fireEvent.click(go); });
    expect(aaguidPolicyApi.update).toHaveBeenCalledWith('t-1', { mode: 'ALLOWLIST', mdsStrict: false, entries: [] });
  });

  it('MDS 엄격을 켜는 저장은 L2 영향 요약 — "켜고 저장"', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ ...POLICY, mdsStrict: false, entries: [...POLICY.entries] });
    (aaguidPolicyApi.update as ReturnType<typeof vi.fn>).mockResolvedValue({ ...POLICY, mdsStrict: true });
    renderTab();
    const sw = await screen.findByRole('switch', { name: 'MDS strict 꺼짐' });
    expect(screen.getByText('켜면 현재 목록 중 MDS 미확인 1개 모델의 신규 등록이 막힙니다.')).toBeInTheDocument();
    fireEvent.click(sw);
    expect(screen.getByText('현재 목록 중 MDS 미확인 1개 모델의 신규 등록이 막힙니다.')).toBeInTheDocument();
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' }));
    const dlg = screen.getByRole('alertdialog', { name: 'MDS 엄격 모드를 켤까요?' });
    expect(dlg).toHaveTextContent('MDS 엄격: 꺼짐 → 켜짐');
    expect(dlg).toHaveTextContent('미확인 모델 1개');
    expect(dlg).toHaveTextContent('기존 Credential은 유지');
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '켜고 저장' })); });
    expect(aaguidPolicyApi.update).toHaveBeenCalledWith('t-1', expect.objectContaining({ mdsStrict: true }));
  });

  it('저장 실패는 다이얼로그 안 인라인 오류, SaveBar·입력 유지', async () => {
    (aaguidPolicyApi.update as ReturnType<typeof vi.fn>).mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    fireEvent.click(await screen.findByRole('radio', { name: /차단 목록/ }));
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' }));
    const dlg = screen.getByRole('alertdialog', { name: '변경 사항 1개를 저장할까요?' });
    expect(dlg).toHaveTextContent('방식: 허용 목록 → 차단 목록');
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '저장' })); });
    expect(within(dlg).getByText('저장하지 못했습니다')).toBeInTheDocument();
    expect(bar()).toHaveTextContent('mode');
    expect(screen.queryByText('저장 실패')).toBeNull();
  });

  it('로드 실패: LoadError + 다시 시도', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>)
      .mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'))
      .mockResolvedValueOnce({ ...POLICY, entries: [...POLICY.entries] });
    renderTab();
    expect(await screen.findByText('AAGUID 정책을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByText('Loading…')).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 시도' })); });
    expect(await screen.findByText('YubiKey 5')).toBeInTheDocument();
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/tenant/AaguidPolicyTab.test.ts src/pages/tenant/AaguidPolicyTab.view.test.tsx`
Expected: FAIL — `saveConfirmKind` export 없음, 경고 제목이 "탭 점 배지 사유", SaveBar 버튼이 "저장"(확인 없이 바로 저장), 빈 목록이 회색 캡션, 로드 실패 시 "Loading…".

- [ ] **Step 3: 구현**

`admin-ui/src/pages/tenant/AaguidPolicyTab.tsx` 전체 교체:
```tsx
// admin-ui/src/pages/tenant/AaguidPolicyTab.tsx
import { useState, useEffect, useRef } from 'react';
import { Icons } from '@/icons/Icons';
import { aaguidPolicyApi } from '@/api/aaguidPolicy';
import type { Tenant, AaguidPolicy } from '@/api/designTypes';
import { AAGUID_MODE_LABELS } from '@/i18n/labels';
import { useLoad } from '@/lib/useLoad';
import {
  Alert, Badge, Button, Card, CardHeader, ChoiceCard, ConfirmDialog, IconButton, LoadError, SaveBar, Skeleton, Switch, useToast, useUnsavedGuard,
} from '@/ui';
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

export type SaveConfirmKind = 'block-all' | 'mds-strict' | 'diff';

/** 저장 확인 등급(V3 가이드). 빈 허용 목록 = L3 체크, MDS 엄격 켜기 = L2 영향 요약, 그 밖 = L2 변경 비교. */
export function saveConfirmKind(saved: AaguidPolicy, draft: AaguidPolicy): SaveConfirmKind {
  if (draft.mode === 'ALLOWLIST' && draft.entries.filter((e) => e.aaguid).length === 0) return 'block-all';
  if (!saved.mdsStrict && draft.mdsStrict) return 'mds-strict';
  return 'diff';
}

const MODES: { v: AaguidPolicy['mode']; d: string }[] = [
  { v: 'ANY', d: '모든 인증기 허용' },
  { v: 'ALLOWLIST', d: '목록의 모델만 허용' },
  { v: 'DENYLIST', d: '목록의 모델만 차단' },
];

const onOff = (b: boolean) => (b ? '켜짐' : '꺼짐');

/** L2 변경 비교 목록 — 방식 · 추가/삭제된 AAGUID · MDS 엄격. */
function ChangeList({ saved, draft }: { saved: AaguidPolicy; draft: AaguidPolicy }) {
  const added = draft.entries.filter((e) => e.aaguid && !saved.entries.some((s) => s.aaguid === e.aaguid));
  const removed = saved.entries.filter((s) => s.aaguid && !draft.entries.some((e) => e.aaguid === s.aaguid));
  return (
    <ul className="stack-1" style={{ listStyle: 'none', margin: 0, padding: '12px 14px', border: '1px solid var(--line)', borderRadius: 'var(--r)', fontSize: 13 }}>
      {saved.mode !== draft.mode && <li>방식: {AAGUID_MODE_LABELS[saved.mode]} → {AAGUID_MODE_LABELS[draft.mode]}</li>}
      {added.map((e) => <li key={'+' + e.aaguid} className="mono">+ {e.aaguid}</li>)}
      {removed.map((e) => <li key={'-' + e.aaguid} className="mono">- {e.aaguid}</li>)}
      {saved.mdsStrict !== draft.mdsStrict && <li>MDS 엄격: {onOff(saved.mdsStrict)} → {onOff(draft.mdsStrict)}</li>}
    </ul>
  );
}

// ── AaguidPolicyTab ───────────────────────────────────────────────────────────

export default function AaguidPolicyTab({ tenant, onSaved }: { tenant: Tenant; onSaved?: (p: AaguidPolicy) => void }) {
  const load = useLoad(() => aaguidPolicyApi.get(tenant.id), [tenant.id]);
  const [policy, setPolicy] = useState<AaguidPolicy | null>(null);
  const [draft, setDraft] = useState<AaguidPolicy | null>(null);
  const [aaguidInput, setAaguidInput] = useState('');
  const [confirmOpen, setConfirmOpen] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const toast = useToast();

  const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const inputValid = uuidRe.test(aaguidInput);
  const dirty = policy !== null && draft !== null && JSON.stringify(policy) !== JSON.stringify(draft);
  const changedFields = aaguidChangedFields(policy, draft);
  const guard = useUnsavedGuard(dirty);

  useEffect(() => {
    if (!load.data) return;
    setPolicy(load.data);
    setDraft(load.data);
  }, [load.data]);

  const list = draft == null || draft.mode === 'ANY' ? [] : draft.entries.filter((e) => e.aaguid);

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

  /** ConfirmDialog 의 onConfirm — 실패하면 throw 해서 다이얼로그 안에 오류를 보인다(입력 유지). */
  async function save() {
    if (!draft) return;
    const updated = await aaguidPolicyApi.update(tenant.id, draft);
    setPolicy(updated);
    setDraft(updated);
    onSaved?.(updated);
    toast({ kind: 'ok', title: 'AAGUID 정책 저장 완료' });
  }

  function handleRevert() {
    if (!policy) return;
    setDraft({ ...policy, entries: [...policy.entries] });
  }

  if (load.status === 'failed' && !policy) {
    return <LoadError what="AAGUID 정책" error={load.error} onRetry={load.reload} retrying={load.reloading} />;
  }
  if (!draft || !policy) {
    return <Skeleton rows={6} label="AAGUID 정책 불러오는 중" />;
  }

  // 탭 배지와 같은 기준(저장된 정책). 방금 추가한 항목은 저장 후 서버가 mdsName 을 채운다.
  const unknownCount = unverifiedAaguidCount(policy);
  const draftUnknown = unverifiedAaguidCount(draft);
  const kind = saveConfirmKind(policy, draft);
  const closeConfirm = () => setConfirmOpen(false);

  return (
    <div className="stack-6">
      {unknownCount > 0 && (
        <Alert tone="warn" title={`MDS 미확인 AAGUID ${unknownCount}건 — 인증기 모델을 확인하세요`}
          actions={<Button size="sm" onClick={() => document.getElementById('aaguid-list')?.scrollIntoView?.({ block: 'start' })}>항목 보기</Button>}>
          {AAGUID_MODE_LABELS[policy.mode]}의 아래 “MDS 미확인” 항목이 MDS에 없습니다.
        </Alert>
      )}

      <Card>
        <CardHeader title="AAGUID 정책" sub="등록할 수 있는 인증기 모델을 AAGUID로 제한합니다." />

        <SetRow label="mode" desc="정책 방식 · 저장 전 영향 요약을 보여 줍니다." changed={changedFields.includes('mode')}>
          <div className="choices" role="radiogroup" aria-label="mode">
            {MODES.map((o) => (
              <ChoiceCard key={o.v} checked={draft.mode === o.v} onSelect={() => setDraft({ ...draft, mode: o.v })}
                title={AAGUID_MODE_LABELS[o.v]} description={o.d} />
            ))}
          </div>
        </SetRow>

        {draft.mode !== 'ANY' && (
          <div id="aaguid-list">
            <SetRow label={draft.mode === 'ALLOWLIST' ? '허용된 AAGUID' : '차단된 AAGUID'} changed={changedFields.includes('AAGUID 목록')}
              desc="MDS 에서 모델명을 자동으로 찾아 붙입니다.">
              <div className="stack-3" style={{ gap: 10 }}>
                <div className="row" style={{ gap: 8, flexWrap: 'nowrap' }}>
                  <input ref={inputRef} className="input mono" aria-label="AAGUID 입력" placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" maxLength={36}
                    value={aaguidInput}
                    onChange={(e) => setAaguidInput(formatAaguid(e.target.value))}
                    onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }} />
                  <Button icon={<Icons.Plus size={14} />} disabled={!inputValid} onClick={add}>추가</Button>
                </div>
                {list.length === 0 ? (
                  draft.mode === 'ALLOWLIST' ? (
                    <Alert tone="bad" title="허용 목록이 비어 있습니다"
                      actions={<>
                        <Button size="sm" onClick={() => inputRef.current?.focus()}>AAGUID 추가</Button>
                        <Button size="sm" variant="ghost" onClick={() => setDraft({ ...draft, mode: 'ANY' })}>전체 허용으로 되돌리기</Button>
                      </>}>
                      이대로 저장하면 이 tenant의 모든 패스키 등록·인증이 차단됩니다.
                    </Alert>
                  ) : (
                    <p className="hint" style={{ margin: 0, padding: '12px 14px', border: '1px solid var(--line)', borderRadius: 'var(--r)' }}>
                      비어 있음 — 이 상태에서는 모든 패스키 등록·인증이 허용됩니다.
                    </p>
                  )
                ) : (
                  <ul style={{ listStyle: 'none', margin: 0, padding: 0, border: '1px solid var(--line)', borderRadius: 'var(--r)', overflow: 'hidden' }}>
                    {list.map((entry, i) => (
                      <li key={entry.aaguid} className="row" style={{ flexWrap: 'nowrap', gap: 12, padding: '12px 14px', borderBottom: i === list.length - 1 ? 0 : '1px solid var(--line-soft)' }}>
                        <span className="avatar avatar--sq" aria-hidden="true"><Icons.Fingerprint size={16} /></span>
                        <div className="col" style={{ flex: '1 1 0', minWidth: 0 }}>
                          <strong className="truncate" style={{ fontSize: 13 }} title={entry.mdsName ?? undefined}>{entry.mdsName ?? 'MDS 에 없음'}</strong>
                          <span className="mono hint truncate" style={{ margin: 0 }}>{entry.aaguid}</span>
                        </div>
                        {entry.mdsName == null ? <Badge tone="warn">MDS 미확인</Badge> : <Badge tone="ok">MDS 확인</Badge>}
                        <IconButton size="sm" label={`${entry.aaguid} 삭제`} icon={<Icons.X size={14} />}
                          onClick={() => setList(list.filter((x) => x.aaguid !== entry.aaguid))} />
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </SetRow>
          </div>
        )}

        <SetRow label="MDS 엄격 모드" desc="MDS 에 없거나 폐기(REVOKED)된 인증기의 등록을 거부합니다." changed={changedFields.includes('MDS strict')}>
          <div className="stack-2">
            <Switch checked={draft.mdsStrict} onChange={(v) => setDraft({ ...draft, mdsStrict: v })}
              label={draft.mdsStrict ? 'MDS strict 켜짐' : 'MDS strict 꺼짐'} />
            {draftUnknown > 0 && (
              <p className="hint" style={{ margin: 0 }}>
                {draft.mdsStrict ? `현재 목록 중 MDS 미확인 ${draftUnknown}개 모델의 신규 등록이 막힙니다.` : `켜면 현재 목록 중 MDS 미확인 ${draftUnknown}개 모델의 신규 등록이 막힙니다.`}
              </p>
            )}
          </div>
        </SetRow>
      </Card>

      <SaveBar count={changedFields.length} summary={changedFields.join(' · ')} onRevert={handleRevert} onSave={() => setConfirmOpen(true)} saveLabel="저장…" />

      <ConfirmDialog open={confirmOpen && kind === 'block-all'} onClose={closeConfirm} level="L3" verb="차단 정책 저장"
        title="모든 등록·인증을 차단할까요?" acknowledge="차단된다는 것을 이해했습니다" onConfirm={save}>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>
          방식: {AAGUID_MODE_LABELS[policy.mode]} → 허용 목록(0개)<br />
          영향: 이 tenant의 모든 사용자가 패스키로 등록·인증할 수 없습니다.
        </p>
      </ConfirmDialog>

      <ConfirmDialog open={confirmOpen && kind === 'mds-strict'} onClose={closeConfirm} level="L2" danger={false} verb="켜고 저장"
        title="MDS 엄격 모드를 켤까요?" onConfirm={save}>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>
          MDS 엄격: 꺼짐 → 켜짐<br />
          영향: MDS에 없거나 폐기된 인증기의 신규 등록 거부{draftUnknown > 0 ? `(현재 목록의 미확인 모델 ${draftUnknown}개 포함)` : ''}, 기존 Credential은 유지
        </p>
        <ChangeList saved={policy} draft={draft} />
      </ConfirmDialog>

      <ConfirmDialog open={confirmOpen && kind === 'diff'} onClose={closeConfirm} level="L2" danger={false} verb="저장"
        title={`변경 사항 ${changedFields.length}개를 저장할까요?`} sub="다음 패스키 등록부터 적용됩니다." onConfirm={save}>
        <ChangeList saved={policy} draft={draft} />
      </ConfirmDialog>
      {guard}
    </div>
  );
}
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/tenant/AaguidPolicyTab.test.ts src/pages/tenant/AaguidPolicyTab.view.test.tsx src/pages/TenantDetailPage.test.tsx && npx tsc -b && npx eslint src`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/tenant/AaguidPolicyTab.tsx admin-ui/src/pages/tenant/AaguidPolicyTab.test.ts admin-ui/src/pages/tenant/AaguidPolicyTab.view.test.tsx
git commit -m "feat(admin-ui): AAGUID 저장을 L2 변경 비교로, 빈 허용 목록은 경고 + L3 체크, MDS 엄격 켜기는 L2 영향 요약

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 32: API 키 탭 — 로드 실패·빈 상태 다음 단계·발급 실패 시 입력 유지·만료 배지 라벨

**Files:**
- Modify: `admin-ui/src/pages/tenant/ApiKeysTab.tsx`
- Modify: `admin-ui/src/pages/tenant/apiKeyHelpers.ts` (`expiryBadge` 추가)
- Test: `admin-ui/src/pages/tenant/ApiKeysTab.test.tsx`, `admin-ui/src/pages/tenant/apiKeyHelpers.test.ts`

**Interfaces:**
- Consumes: `useLoad`, `describeError` (Task 1), `LoadError` (Task 2), `Skeleton` (Task 3), `HELP_LINKS.rpGuide` (`@/config/links`), `BadgeTone` (`@/ui`)
- Produces:
  - `export function expiryBadge(k: Pick<ApiKey, 'status' | 'expiresAt'>, now?: number): { tone: BadgeTone; label: string; sub: string }` — 무기한/정상/만료 임박 · N일 남음/만료됨/해당 없음(회수)
  - `NewKeyDialog` 의 `onIssue` 가 `(name, scopes, months) => Promise<void>` 로 바뀐다(실패 시 throw → 다이얼로그 안 인라인 경고, 성공했을 때만 폼 초기화)

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/pages/tenant/apiKeyHelpers.test.ts` 2행 import 에 `expiryBadge` 추가, 끝에 추가:
```ts
describe('expiryBadge', () => {
  const k = (status: 'ACTIVE' | 'EXPIRED' | 'REVOKED', expiresAt: string | null) => ({ status, expiresAt });
  it('무기한 · 정상 · 만료 임박 · 만료됨 · 회수', () => {
    expect(expiryBadge(k('ACTIVE', null), NOW)).toEqual({ tone: 'neutral', label: '무기한', sub: '만료일 없음' });
    expect(expiryBadge(k('ACTIVE', at(100)), NOW)).toMatchObject({ tone: 'ok', label: '정상' });
    expect(expiryBadge(k('ACTIVE', at(10)), NOW)).toMatchObject({ tone: 'warn', label: '만료 임박 · 10일 남음' });
    expect(expiryBadge(k('EXPIRED', at(-1)), NOW)).toMatchObject({ tone: 'bad', label: '만료됨' });
    expect(expiryBadge(k('ACTIVE', at(-1)), NOW)).toMatchObject({ tone: 'bad', label: '만료됨' });
    expect(expiryBadge(k('REVOKED', at(10)), NOW)).toMatchObject({ tone: 'neutral', label: '해당 없음' });
  });
});
```

`admin-ui/src/pages/tenant/ApiKeysTab.test.tsx` 8행 아래에 추가:
```tsx
vi.mock('@/config/links', () => ({ HELP_LINKS: { rpGuide: 'https://docs.example/rp', apiDocs: '', support: '' } }));
import { ApiError } from '@/api/types';
```
`describe('ApiKeysTab V2', …)` 끝에 추가:
```tsx
  it('로드 실패: "키 없음"이 아니라 LoadError, 다시 시도', async () => {
    api.list.mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503')).mockResolvedValueOnce([key({ id: 'a', name: 'prod' })]);
    renderTab();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('API 키 목록을 불러오지 못했습니다');
    expect(screen.queryByText('아직 발급한 API 키가 없습니다')).toBeNull();
    expect(screen.queryByText('API key 로드 실패')).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 시도' })); });
    expect(await screen.findByText('prod')).toBeInTheDocument();
  });

  it('로딩 중에는 Skeleton', () => {
    api.list.mockReturnValue(new Promise(() => {}));
    renderTab();
    expect(screen.getByRole('status', { name: 'API 키 불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText('불러오는 중…')).toBeNull();
  });

  it('빈 상태: 첫 API 키 발급 + RP 연동 가이드', async () => {
    api.list.mockResolvedValue([]);
    renderTab();
    expect(await screen.findByText('아직 발급한 API 키가 없습니다')).toBeInTheDocument();
    expect(screen.getByText('RP 서버가 패스키 등록·인증 API를 호출하려면 키가 필요합니다.')).toBeInTheDocument();
    const guides = screen.getAllByRole('link', { name: /RP 연동 가이드/ });
    expect(guides.length).toBeGreaterThanOrEqual(1);
    for (const g of guides) expect(g).toHaveAttribute('href', 'https://docs.example/rp');
    fireEvent.click(screen.getByRole('button', { name: '첫 API 키 발급' }));
    expect(screen.getByRole('dialog', { name: '새 API 키 발급' })).toBeInTheDocument();
  });

  it('발급 실패: 다이얼로그 안 인라인 경고, 이름·권한·만료 입력 유지, 토스트 없음 → 다시 발급 성공', async () => {
    api.list.mockResolvedValue([]);
    api.create
      .mockRejectedValueOnce(new ApiError(409, 'K409', '키 개수 한도 초과'))
      .mockResolvedValueOnce({ key: key({ id: 'n1', name: 'staging' }), plaintext: 'pk_live_ab.secret' });
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '새 키 발급' }));
    const dlg = screen.getByRole('dialog', { name: '새 API 키 발급' });
    fireEvent.change(within(dlg).getByLabelText('용도 (이름)'), { target: { value: 'staging' } });
    fireEvent.click(within(dlg).getByRole('checkbox', { name: /authentication/ }));
    fireEvent.click(within(dlg).getByRole('button', { name: '12개월' }));
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '발급' })); });
    const alert = within(dlg).getByRole('alert');
    expect(alert).toHaveTextContent('API 키를 발급하지 못했습니다.');
    expect(alert).toHaveTextContent('키 개수 한도 초과');
    expect(alert).toHaveTextContent('입력한 내용은 그대로 있습니다.');
    expect(within(dlg).getByLabelText('용도 (이름)')).toHaveValue('staging');
    expect(within(dlg).getByRole('checkbox', { name: /authentication/ })).not.toBeChecked();
    expect(within(dlg).getByRole('button', { name: '12개월' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByText('발급 실패')).toBeNull();

    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '발급' })); });
    expect(api.create).toHaveBeenLastCalledWith('t1', 'staging', ['registration'], 12);
    expect(await screen.findByRole('alertdialog', { name: /새 API key가 발급되었습니다/ })).toBeInTheDocument();
  });

  it('발급 중에는 "발급 중…" 비활성', async () => {
    api.list.mockResolvedValue([]);
    api.create.mockReturnValue(new Promise(() => {}));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '새 키 발급' }));
    fireEvent.change(screen.getByLabelText('용도 (이름)'), { target: { value: 'staging' } });
    fireEvent.click(screen.getByRole('button', { name: '발급' }));
    expect(screen.getByRole('button', { name: '발급 중…' })).toBeDisabled();
  });

  it('만료 열은 글로 된 배지: 정상 · 만료 임박 · 무기한 · 만료됨', async () => {
    api.list.mockResolvedValue([
      key({ id: 'a', name: 'ok', expiresAt: iso(100) }),
      key({ id: 'b', name: 'soon', expiresAt: iso(10) }),
      key({ id: 'c', name: 'forever', expiresAt: null }),
      key({ id: 'd', name: 'gone', status: 'EXPIRED', expiresAt: iso(-1) }),
    ]);
    renderTab();
    expect(await screen.findByText('정상')).toHaveClass('badge--success');
    expect(screen.getByText('만료 임박 · 10일 남음')).toHaveClass('badge--warning');
    expect(screen.getByText('무기한')).toHaveClass('badge');
    expect(screen.getByText('만료일 없음')).toBeInTheDocument();
    expect(screen.getByText('만료됨')).toHaveClass('badge--danger');
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/tenant/ApiKeysTab.test.tsx src/pages/tenant/apiKeyHelpers.test.ts`
Expected: FAIL — `expiryBadge` 없음, 로드 실패가 토스트 + 빈 목록, "불러오는 중…" 텍스트, 빈 상태 문구 다름, 발급 실패가 토스트이고 폼이 이미 비워짐.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/tenant/apiKeyHelpers.ts` 1–3행 import 교체 + 파일 끝에 추가:
```ts
// admin-ui/src/pages/tenant/apiKeyHelpers.ts
import type { ApiKey } from '@/api/designTypes';
import type { BadgeTone } from '@/ui';
import { EXPIRY_WARN_DAYS } from '@/lib/apiKeyExpiry'; // 기존 상수
import { ymd } from './tabFormat';
```
```ts
/** 만료 열 배지 — 색만이 아니라 글로 상태를 말한다(V3: 정상 · 만료 임박 N일 · 만료됨 · 무기한). */
export function expiryBadge(k: Pick<ApiKey, 'status' | 'expiresAt'>, now = Date.now()): { tone: BadgeTone; label: string; sub: string } {
  if (!k.expiresAt) return { tone: 'neutral', label: '무기한', sub: '만료일 없음' };
  const sub = ymd(k.expiresAt);
  if (k.status === 'REVOKED') return { tone: 'neutral', label: '해당 없음', sub };
  const st = k.status === 'EXPIRED' ? 'expired' : expiryState(k.expiresAt, now);
  if (st === 'expired') return { tone: 'bad', label: '만료됨', sub };
  if (st === 'soon') return { tone: 'warn', label: `만료 임박 · ${daysLeft(k.expiresAt, now)}일 남음`, sub };
  return { tone: 'ok', label: '정상', sub };
}
```

`admin-ui/src/pages/tenant/ApiKeysTab.tsx`:

1–24행(import ~ `SCOPE_OPTIONS`)을 교체:
```tsx
import { useEffect, useRef, useState } from 'react';
import { Icons } from '@/icons/Icons';
import {
  Alert, Badge, Button, Card, CardHeader, DataTable, Dialog, EmptyState, FilterChip, KpiCard, LoadError, Menu, SecretBox, Skeleton, StatusBadge, useToast,
  useUnsavedGuard,
  type Column,
} from '@/ui';
import { illustrations } from '@/assets/illustrations';
import { apiKeysApi } from '@/api/apiKeys';
import { copyToClipboard } from '@/lib/clipboard';
import { describeError } from '@/lib/describeError';
import { useLoad } from '@/lib/useLoad';
import { HELP_LINKS } from '@/config/links';
import type { Tenant, ApiKey } from '@/api/designTypes';
import { KvList } from './KvList';
import { timeAgo, ymd, ymdHm } from './tabFormat';
import {
  ROTATION_DAYS, expiryBadge, keyStatusCounts, latestCreatedAt, previewExpiry, rotationOverdueCount,
} from './apiKeyHelpers';

const NAME_MAX = 64;

const SCOPE_OPTIONS: { value: string; desc: string }[] = [
  { value: 'registration', desc: '패스키 등록 · 목록 · 이름 변경 · 삭제' },
  { value: 'authentication', desc: '패스키 인증' },
];
```

41–72행(컴포넌트 시작 ~ 로드 `useEffect`)을 교체:
```tsx
export default function ApiKeysTab({ tenant, onChanged }: { tenant: Tenant; onChanged?: () => void }) {
  const load = useLoad(() => apiKeysApi.list(tenant.id), [tenant.id]);
  const keys = load.data ?? [];
  const failed = load.status === 'failed';
  const [showNew, setShowNew] = useState(false);
  const [issued, setIssued] = useState<Issued | null>(null);
  const [revoking, setRevoking] = useState<ApiKey | null>(null);
  const [rotating, setRotating] = useState<ApiKey | null>(null);
  // 회수·회전 진행 중 표시. ref 는 같은 틱의 연타(두 번째 클릭)까지 막는다.
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const toast = useToast();
  // 평문 키는 이 다이얼로그에서 한 번만 보인다 — 닫기 전 이동·새로고침을 막는다.
  const leaveGuard = useUnsavedGuard(issued !== null, {
    title: '키를 아직 저장하지 않았습니다',
    body: '이동하면 발급된 키를 다시 볼 수 없습니다. 키는 서버에서 계속 유효합니다.',
  });

  // 회전 응답에는 새 키의 만료가 없다 — 목록을 다시 읽어 새 키가 보이면 만료를 채운다.
  useEffect(() => {
    if (!issued?.expiryUnknown || !load.data) return;
    const fresh = load.data.find((x) => x.id === issued.key.id);
    if (fresh) setIssued((prev) => (prev && prev.key.id === fresh.id ? { ...prev, key: fresh, expiryUnknown: false } : prev));
  }, [load.data, issued]);
```

`handleIssue`(86–98행)를 교체:
```tsx
  /** NewKeyDialog 의 onIssue — 실패하면 throw 해서 다이얼로그 안에 경고를 띄우고 입력을 유지한다. */
  async function handleIssue(name: string, scopes: string[], expiresInMonths: number | null) {
    const result = await apiKeysApi.create(tenant.id, name, scopes, expiresInMonths);
    setShowNew(false);
    setIssued(result);
    onChanged?.();
    load.reload();
  }
```

`handleRevoke` 의 107행 `await reload();` 를 `load.reload();` 로 바꾼다.
`handleRotate` 의 124–127행(`onChanged?.();` 부터 `if (fresh) …` 까지)을 교체:
```tsx
        onChanged?.();
        load.reload(); // 새 키 만료는 위 useEffect 가 목록에서 찾아 채운다
```

139–141행(로딩 분기)을 교체:
```tsx
  if (load.status === 'loading' && !issued) {
    return <Skeleton rows={5} label="API 키 불러오는 중" />;
  }
```

`columns` 의 만료일 열(161행)을 교체:
```tsx
    { key: 'expires', header: '만료', render: (k) => <ExpiryCell k={k} /> },
```

177–199행(return 시작 ~ `NewKeyDialog`)을 교체:
```tsx
  const guide = HELP_LINKS.rpGuide
    ? <a className="btn btn--ghost" href={HELP_LINKS.rpGuide} target="_blank" rel="noopener noreferrer">RP 연동 가이드 <Icons.ExternalLink size={13} /></a>
    : null;

  return (
    <div className="stack-6">
      <div className="kpis kpis--3">
        <KpiCard label="총 API Key" value={failed ? '—' : keys.length} hint={failed ? undefined : `활성 ${counts.active} · 만료 ${counts.expired} · 회수 ${counts.revoked}`} />
        <KpiCard label="최근 발급" value={!failed && latest ? timeAgo(latest) : '—'} />
        <KpiCard label="권장 rotation" value={`${ROTATION_DAYS}일`} hint={failed ? undefined : `초과 키 ${overdue}개`} hintTone={overdue > 0 ? 'warn' : 'default'} />
      </div>

      <Card>
        <CardHeader
          title="API 키"
          sub="키 원문은 발급 직후 한 번만 보입니다. 서버는 해시만 저장합니다."
          actions={<div className="row" style={{ gap: 8 }}>
            {guide}
            <Button variant="primary" icon={<Icons.Plus size={14} />} onClick={() => setShowNew(true)}>새 키 발급</Button>
          </div>}
        />
        {failed ? (
          <LoadError what="API 키 목록" error={load.error} onRetry={load.reload} retrying={load.reloading} />
        ) : (
          <DataTable
            columns={columns}
            rows={keys}
            rowKey={(k) => k.id}
            empty={
              <EmptyState
                illustration={illustrations.apiKeyIssued}
                title="아직 발급한 API 키가 없습니다"
                description="RP 서버가 패스키 등록·인증 API를 호출하려면 키가 필요합니다."
                action={<div className="row" style={{ gap: 8, justifyContent: 'center' }}>
                  <Button variant="primary" icon={<Icons.Plus size={14} />} onClick={() => setShowNew(true)}>첫 API 키 발급</Button>
                  {guide}
                </div>}
              />
            }
          />
        )}
      </Card>

      <NewKeyDialog open={showNew} onClose={() => setShowNew(false)} onIssue={handleIssue} />
```

208–219행 `ExpiryCell` 교체:
```tsx
// ── 만료 셀: 상태를 글로 된 배지로 + 날짜 ────────────────────────────────────

function ExpiryCell({ k }: { k: ApiKey }) {
  const b = expiryBadge(k);
  return (
    <div className="stack-1" style={{ gap: 2 }}>
      <Badge tone={b.tone} dot={false}>{b.label}</Badge>
      <span className="hint" style={{ margin: 0 }}>{b.sub}</span>
    </div>
  );
}
```

223–287행 `NewKeyDialog` 전체 교체:
```tsx
// ── 새 키 발급 — 실패해도 입력 유지, 성공했을 때만 비운다 ──────────────────────

function NewKeyDialog({ open, onClose, onIssue }: {
  open: boolean;
  onClose: () => void;
  onIssue: (name: string, scopes: string[], expiresInMonths: number | null) => Promise<void>;
}) {
  const [name, setName] = useState('');
  const [scopes, setScopes] = useState<string[]>(['registration', 'authentication']);
  const [expiresInMonths, setExpiresInMonths] = useState<number | null>(24);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => { if (open) setError(null); }, [open]);

  function toggle(v: string) {
    setScopes((prev) => (prev.includes(v) ? prev.filter((s) => s !== v) : [...prev, v]));
  }
  async function submit() {
    if (busyRef.current || !name || scopes.length === 0) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await onIssue(name, scopes, expiresInMonths);
      setName('');
      setScopes(['registration', 'authentication']);
      setExpiresInMonths(24);
    } catch (e: unknown) {
      setError(e);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }
  const preview = previewExpiry(expiresInMonths);
  const info = error ? describeError(error) : null;

  return (
    <Dialog open={open} onClose={busy ? () => {} : onClose} title="새 API 키 발급" sub="RP 서버가 Passkey API 를 호출할 때 쓰는 키입니다."
      footer={<>
        <Button onClick={onClose} disabled={busy}>취소</Button>
        <Button variant="primary" disabled={busy || !name || scopes.length === 0} onClick={() => void submit()}>{busy ? '발급 중…' : '발급'}</Button>
      </>}>
      {info && (
        <Alert tone="bad" title="API 키를 발급하지 못했습니다.">
          {info.title}. {info.detail} 입력한 내용은 그대로 있습니다.
        </Alert>
      )}
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
        <p className="hint">{preview ? `만료일 ${preview} (KST)` : '만료 없음 — 키가 무기한으로 유효합니다.'} · 권장 회전 주기 {ROTATION_DAYS}일</p>
      </div>
    </Dialog>
  );
}
```
(`expiryState`·`daysLeft` import 는 `ExpiryCell` 이 `expiryBadge` 로 바뀌어 ApiKeysTab 에서 쓰지 않으므로 위 import 에서 빠졌다. `guarded` 는 회수·회전이 아직 사용 — Task 33 에서 정리.)

`counts`·`latest`·`overdue` 계산(143–145행)은 그대로 둔다(`keys` 가 `load.data ?? []`).

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/tenant/ApiKeysTab.test.tsx src/pages/tenant/apiKeyHelpers.test.ts && npx tsc -b && npx eslint src`
Expected: PASS — 새 7개 + 기존 9개(기존 "발급 버튼 연타는 키를 한 번만 발급한다" 는 `NewKeyDialog` 내부 `busyRef` 로 유지, "회전 다이얼로그는 새 키의 id …" 는 위 `useEffect` 로 만료를 채워 통과).

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/tenant/ApiKeysTab.tsx admin-ui/src/pages/tenant/ApiKeysTab.test.tsx admin-ui/src/pages/tenant/apiKeyHelpers.ts admin-ui/src/pages/tenant/apiKeyHelpers.test.ts
git commit -m "feat(admin-ui): API 키 로드 실패 LoadError, 빈 상태 다음 단계, 발급 실패 시 입력 유지, 만료 배지 라벨

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 33: API 키 회수·회전 — L2 영향 요약 (`ConfirmDialog`)

**Files:**
- Modify: `admin-ui/src/pages/tenant/ApiKeysTab.tsx` (`busy`/`guarded`/`handleRevoke`/`handleRotate`, `RevokeKeyDialog`, `RotateKeyDialog`)
- Test: `admin-ui/src/pages/tenant/ApiKeysTab.test.tsx`

**Interfaces:**
- Consumes: `ConfirmDialog` L2 (Task 4), `eulReul` (Task 1)
- Produces: 없음. 회수 = L2 "API 키를 회수할까요?" / 버튼 "회수"(danger), 회전 = L2 "API 키를 회전할까요?" / 버튼 "회전"(primary). 성공 토스트 "API 키 “[이름]”을 회수했습니다." (되돌릴 수 없으므로 되돌리기 버튼 없음).

- [ ] **Step 1: 실패하는 테스트 작성 + 바뀐 문구의 기존 테스트 갱신**

`admin-ui/src/pages/tenant/ApiKeysTab.test.tsx`:

`it('⋯ 메뉴는 ACTIVE 키에만 있고, …')` 의 45–47행 교체:
```tsx
    const dlg = screen.getByRole('alertdialog', { name: 'API 키를 회수할까요?' });
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    expect(within(dlg).getByRole('button', { name: '회수' })).toHaveClass('btn--danger-solid');
```
`it('회전: 24시간 겹침 안내 후 …')` 의 103–105행 교체:
```tsx
    const rot = screen.getByRole('alertdialog', { name: 'API 키를 회전할까요?' });
    expect(within(rot).getByText('24시간 함께 유효')).toBeInTheDocument();
    expect(document.activeElement).toBe(within(rot).getByRole('button', { name: '취소' }));
    await act(async () => { fireEvent.click(within(rot).getByRole('button', { name: '회전' })); });
```
`it('회전 다이얼로그는 새 키의 id …')` 의 140행 교체:
```tsx
    await act(async () => { fireEvent.click(within(screen.getByRole('alertdialog', { name: 'API 키를 회전할까요?' })).getByRole('button', { name: '회전' })); });
```
`it('회수·회전 확인 버튼도 처리 중에는 …')` 의 172행 교체(회수 다이얼로그만 열려 있을 때의 alertdialog):
```tsx
    const revokeBtn = within(screen.getByRole('alertdialog', { name: 'API 키를 회수할까요?' })).getByRole('button', { name: '회수' });
```
그리고 같은 테스트 끝(177행 뒤)에 추가:
```tsx
    expect(revokeBtn).toHaveTextContent('회수 중…');
```

`describe` 끝에 추가:
```tsx
  it('회수: L2 영향 요약(입력 확인 없음), 성공 토스트 "회수했습니다"', async () => {
    api.list.mockResolvedValue([key({ id: 'a', name: 'prod', lastUsedAt: null })]);
    api.revoke.mockResolvedValue(undefined);
    const onChanged = vi.fn();
    renderTab(onChanged);
    fireEvent.click(await screen.findByRole('button', { name: 'prod 키 액션' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '회수' }));
    const dlg = screen.getByRole('alertdialog', { name: 'API 키를 회수할까요?' });
    expect(within(dlg).getByText(/즉시 거부됩니다\(401\)/)).toBeInTheDocument();
    expect(within(dlg).getByText('사용 안 함')).toBeInTheDocument();
    expect(within(dlg).queryByRole('textbox')).toBeNull();
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '회수' })); });
    expect(api.revoke).toHaveBeenCalledWith('a');
    expect(screen.queryByRole('alertdialog', { name: 'API 키를 회수할까요?' })).toBeNull();
    expect(await screen.findByText('API 키 “prod”을 회수했습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '되돌리기' })).toBeNull();
    expect(onChanged).toHaveBeenCalled();
  });

  it('회수 실패는 다이얼로그 안 인라인 오류, 닫히지 않는다', async () => {
    api.list.mockResolvedValue([key({ id: 'a', name: 'prod' })]);
    api.revoke.mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: 'prod 키 액션' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '회수' }));
    const dlg = screen.getByRole('alertdialog', { name: 'API 키를 회수할까요?' });
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '회수' })); });
    expect(within(dlg).getByText('회수하지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByText('회수 실패')).toBeNull();
  });

  it('회전: L2 — 유예 기간 안내, 실패는 인라인', async () => {
    api.list.mockResolvedValue([key({ id: 'a', name: 'prod' })]);
    api.rotate.mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: 'prod 키 액션' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '회전' }));
    const dlg = screen.getByRole('alertdialog', { name: 'API 키를 회전할까요?' });
    expect(within(dlg).getByText(/유예 기간 안에 RP 서버 설정을 새 키로 바꾸세요/)).toBeInTheDocument();
    expect(within(dlg).getByRole('button', { name: '회전' })).toHaveClass('btn--primary');
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '회전' })); });
    expect(within(dlg).getByText('회전하지 못했습니다')).toBeInTheDocument();
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/tenant/ApiKeysTab.test.tsx`
Expected: FAIL — 다이얼로그 이름이 "API key를 회수하시겠습니까?"/"API key를 회전하시겠습니까?", 회전 버튼이 "회전 실행", 실패가 토스트.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/tenant/ApiKeysTab.tsx`:

import 에 `ConfirmDialog` 를 추가하고(`@/ui` 목록), josa import 추가:
```tsx
import { eulReul } from '@/lib/josa';
```

Task 32 의 컴포넌트 상단에서 회수·회전용 `busy`/`busyRef` 두 줄(`// 회수·회전 진행 중 표시…` 주석 포함)을 삭제한다(처리 중 비활성은 `ConfirmDialog` 가 맡는다).

`guarded`·`handleRevoke`·`handleRotate`(원래 74–132행 자리) 전체를 교체:
```tsx
  /** ConfirmDialog 의 onConfirm — 실패하면 throw 해서 다이얼로그 안에 오류를 보인다. */
  async function handleRevoke(k: ApiKey) {
    await apiKeysApi.revoke(k.id);
    toast({ kind: 'ok', title: `API 키 “${k.name}”${eulReul(k.name)} 회수했습니다.`, message: k.prefix });
    onChanged?.();
    load.reload();
  }

  async function handleRotate(k: ApiKey) {
    const res = await apiKeysApi.rotate(k.id);
    // 회전 응답의 id 는 새 키다. 만료는 응답에 없으므로 목록에서 새 키를 찾을 때까지 숨긴다(이전 키 만료를 보이지 않음).
    setIssued({
      key: { ...k, id: res.id, prefix: res.prefix, scopes: res.scopes, createdAt: new Date().toISOString(), lastUsedAt: null },
      plaintext: res.plaintextKey, oldKeyExpiresAt: res.oldKeyExpiresAt, expiryUnknown: true,
    });
    onChanged?.();
    load.reload();
  }
```

다이얼로그 사용부(원래 201–202행)를 교체:
```tsx
      <RotateKeyDialog k={rotating} onClose={() => setRotating(null)} onConfirm={handleRotate} />
      <RevokeKeyDialog k={revoking} onClose={() => setRevoking(null)} onConfirm={handleRevoke} />
```

`RevokeKeyDialog`·`RotateKeyDialog`(원래 333–377행)를 교체:
```tsx
// ── 회수 — L2 영향 요약, 기본 포커스 취소 ─────────────────────────────────────

function RevokeKeyDialog({ k, onClose, onConfirm }: { k: ApiKey | null; onClose: () => void; onConfirm: (k: ApiKey) => Promise<void> }) {
  if (!k) return null;
  return (
    <ConfirmDialog open onClose={onClose} level="L2" verb="회수" title="API 키를 회수할까요?" onConfirm={() => onConfirm(k)}>
      <KvList keyWidth={80} items={[
        { k: '이름', v: k.name },
        { k: 'prefix', v: k.prefix, mono: true },
        { k: '마지막 사용', v: k.lastUsedAt ? timeAgo(k.lastUsedAt) : '사용 안 함' },
      ]} />
      <Alert tone="bad" title="영향">이 키를 쓰는 RP 서버의 요청이 즉시 거부됩니다(401). 회수는 되돌릴 수 없습니다.</Alert>
    </ConfirmDialog>
  );
}

// ── 회전 — L2 영향 요약: 기존 키 24시간 겹침 ─────────────────────────────────

function RotateKeyDialog({ k, onClose, onConfirm }: { k: ApiKey | null; onClose: () => void; onConfirm: (k: ApiKey) => Promise<void> }) {
  if (!k) return null;
  return (
    <ConfirmDialog open onClose={onClose} level="L2" danger={false} verb="회전" title="API 키를 회전할까요?" onConfirm={() => onConfirm(k)}>
      <KvList keyWidth={80} items={[
        { k: '이름', v: k.name },
        { k: 'prefix', v: k.prefix, mono: true },
      ]} />
      <div className="row" style={{ gap: 10, flexWrap: 'nowrap', fontSize: 13, padding: '12px 14px', background: 'var(--canvas)', borderRadius: 'var(--r)' }}>
        <span className="tag">기존 {k.prefix}</span>
        <span style={{ color: 'var(--ink-2)' }}>24시간 함께 유효</span>
        <Icons.ChevronRight size={14} />
        <span className="tag" style={{ background: 'var(--brand-100)', color: 'var(--brand-700)' }}>새 키</span>
        <span style={{ color: 'var(--ink-2)' }}>즉시 발급 · 1회 표시</span>
      </div>
      <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>유예 기간 안에 RP 서버 설정을 새 키로 바꾸세요. 유예가 끝나면 기존 키 요청은 거부됩니다.</p>
    </ConfirmDialog>
  );
}
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/tenant/ApiKeysTab.test.tsx && npx tsc -b && npx eslint src`
Expected: PASS (`useRef` 는 `NewKeyDialog` 가 계속 사용).

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/tenant/ApiKeysTab.tsx admin-ui/src/pages/tenant/ApiKeysTab.test.tsx
git commit -m "feat(admin-ui): API 키 회수·회전을 L2 영향 요약 확인으로, 실패는 다이얼로그 안에

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 34: Credentials 탭 — `LoadError`, 검색 placeholder, "이 페이지 N건 중 M건", CSV "현재 페이지"

**Files:**
- Modify: `admin-ui/src/pages/tenant/CredentialsTab.tsx`
- Test: `admin-ui/src/pages/tenant/CredentialsTab.test.tsx`

**Interfaces:**
- Consumes: `useLoad` (Task 1), `LoadError` (Task 2), `Skeleton` (Task 3)
- Produces: 없음. 서버 검색(`q`)은 credentialId·사용자 핸들(hex/base64url) 부분 일치뿐이므로(`CredentialRepository.searchByTenantId`) placeholder 는 "Credential ID · 사용자 ID 검색", 별칭은 검색되지 않음을 `title` 로 알린다.

- [ ] **Step 1: 실패하는 테스트 작성 + 바뀐 문구의 기존 테스트 갱신**

`admin-ui/src/pages/tenant/CredentialsTab.test.tsx`:

8행 아래에 추가:
```tsx
import { ApiError } from '@/api/types';
```
`it('+ 필터: 필터 행 토글, …')` 의 79행 교체:
```tsx
    expect(screen.getByText('이 페이지 2건 중 1건')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'CSV 내보내기 (현재 페이지 1건)' })).toBeEnabled();
```
`describe` 끝에 추가:
```tsx
  it('검색 placeholder 는 실제 검색 범위(Credential ID · 사용자 ID), 별칭은 안 된다고 알린다', async () => {
    api.list.mockResolvedValue(pageOf([cred('c1')]));
    renderTab();
    await screen.findByText('user-c1');
    const box = screen.getByRole('textbox', { name: 'Credential 검색' });
    expect(box).toHaveAttribute('placeholder', 'Credential ID · 사용자 ID 검색');
    expect(box).toHaveAttribute('title', expect.stringContaining('별칭'));
  });

  it('필터가 없으면 서버 총 건수, CSV 라벨에 현재 페이지 건수', async () => {
    api.list.mockResolvedValue(pageOf([cred('c1'), cred('c2')], 120));
    renderTab();
    await screen.findByText('user-c1');
    expect(screen.getByText('120건')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'CSV 내보내기 (현재 페이지 2건)' })).toBeEnabled();
  });

  it('로드 실패: LoadError, 건수 "—", CSV·검색 비활성, 다시 시도로 복구', async () => {
    api.list.mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503')).mockResolvedValueOnce(pageOf([cred('c1')]));
    renderTab();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Credential 목록을 불러오지 못했습니다');
    expect(screen.queryByText('Credential이 없습니다')).toBeNull();
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /CSV 내보내기/ })).toBeDisabled();
    expect(screen.getByRole('textbox', { name: 'Credential 검색' })).toBeDisabled();
    expect(screen.queryByText('credentials 로드 실패')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('user-c1')).toBeInTheDocument();
  });

  it('로딩 중에는 Skeleton', () => {
    api.list.mockReturnValue(new Promise(() => {}));
    renderTab();
    expect(screen.getByRole('status', { name: 'Credential 목록 불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText('불러오는 중…')).toBeNull();
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/tenant/CredentialsTab.test.tsx`
Expected: FAIL — "1건 (현재 페이지)" 문구, CSV 버튼 이름 "CSV", placeholder "externalUserId · credentialId 검색", 로드 실패가 토스트 + 빈 상태.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/tenant/CredentialsTab.tsx`:

1–16행 import 교체:
```tsx
// admin-ui/src/pages/tenant/CredentialsTab.tsx
import { useEffect, useMemo, useRef, useState } from 'react';
import { Icons } from '@/icons/Icons';
import {
  Button, Card, ConfirmByTyping, CopyBtn, DataTable, Dialog, EmptyState, FilterChip, IconButton, LoadError, Pagination, RemovableChip,
  Segmented, Skeleton, StatusBadge, useToast, type Column,
} from '@/ui';
import { illustrations } from '@/assets/illustrations';
import { credentialsApi } from '@/api/credentials';
import { downloadCsv } from '@/lib/csvExport';
import { kstToday } from '@/lib/kst';
import { useLoad } from '@/lib/useLoad';
import type { Tenant, Credential } from '@/api/designTypes';
import CredentialDetailDialog from './CredentialDetailDialog';
import { KvList } from './KvList';
import { fmtNum, timeAgo } from './tabFormat';
import { applyClientFilter, authenticatorLabel, hasClientFilter, type StatusFilter } from './credentialHelpers';
```

22–79행(컴포넌트 시작 ~ `reload` 함수 끝)을 교체:
```tsx
export default function CredentialsTab({ tenant }: { tenant: Tenant }) {
  const [page, setPage] = useState(0);                 // 0-based (서버 page)
  const [size, setSize] = useState(50);                // 50/100/200
  const [q, setQ] = useState('');                      // 입력 중 검색어
  const [appliedQ, setAppliedQ] = useState('');        // 서버에 보낸 검색어(q)
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [aaguid, setAaguid] = useState('');
  const [status, setStatus] = useState<StatusFilter>('ALL');
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

  const load = useLoad(() => credentialsApi.list(tenant.id, page, size, appliedQ || undefined), [tenant.id, page, size, appliedQ]);
  const items = useMemo(() => load.data?.items ?? [], [load.data]);
  const total = load.data?.total ?? 0;
  const failed = load.status === 'failed';
  const busy = load.status === 'loading' || load.reloading;

  // 경계를 넘은 이전/다음: 새 페이지가 도착하면 첫/마지막 항목을 고른다. 실패하면 대기만 지운다.
  useEffect(() => {
    if (load.status === 'loading') return;
    const pend = pendingSelect.current;
    if (!pend) return;
    pendingSelect.current = null;
    if (load.status === 'ok' && items.length > 0) setSelected(pend === 'first' ? items[0] : items[items.length - 1]);
  }, [load.status, items]);
```

`handleRevoke` 의 117행 `await reload();` 를 `load.reload();` 로 바꾼다.

187–212행(`card__head` ~ 로딩/표 분기)을 교체:
```tsx
        <div className="card__head">
          <div className="search" style={{ flex: '0 1 340px' }}>
            <Icons.Search size={15} />
            <input className="input" aria-label="Credential 검색" placeholder="Credential ID · 사용자 ID 검색"
              title="Credential ID 또는 사용자 ID 의 일부로 찾습니다. 별칭·인증기 이름은 검색되지 않습니다."
              value={q} disabled={failed} onChange={(e) => setQ(e.target.value)} />
          </div>
          <FilterChip active={filtersOpen} onClick={() => setFiltersOpen((v) => !v)}><Icons.Plus size={13} />필터 · aaguid · status</FilterChip>
          {aaguid.trim() !== '' && <RemovableChip mono removeLabel="aaguid 필터 제거" onRemove={() => setAaguid('')}>aaguid: {aaguid.trim()}</RemovableChip>}
          {status !== 'ALL' && <RemovableChip removeLabel="status 필터 제거" onRemove={() => setStatus('ALL')}>status: {status}</RemovableChip>}
          <div className="spacer" />
          <span className="hint" style={{ margin: 0 }}>
            {failed ? '—' : clientFiltered ? `이 페이지 ${fmtNum(items.length)}건 중 ${fmtNum(visible.length)}건` : `${fmtNum(total)}건`}
          </span>
          <Button icon={<Icons.Download size={14} />} onClick={exportCsv} disabled={failed || visible.length === 0}>
            CSV 내보내기 (현재 페이지 {fmtNum(visible.length)}건)
          </Button>
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
        {failed
          ? <LoadError what="Credential 목록" error={load.error} onRetry={load.reload} retrying={load.reloading} />
          : load.status === 'loading'
            ? <Skeleton rows={6} label="Credential 목록 불러오는 중" />
            : <DataTable columns={columns} rows={visible} rowKey={(c) => c.credentialId} onRowClick={setSelected} empty={empty} />}
```

상세 다이얼로그의 227–228행 교체:
```tsx
          onPrev={!busy && canPrev ? goPrev : undefined}
          onNext={!busy && canNext ? goNext : undefined}
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/tenant/CredentialsTab.test.tsx && npx tsc -b && npx eslint src`
Expected: PASS — 새 4개 + 기존 8개(상세 이전/다음 경계 이동은 위 `useEffect` 로 유지).

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/tenant/CredentialsTab.tsx admin-ui/src/pages/tenant/CredentialsTab.test.tsx
git commit -m "fix(admin-ui): Credentials 로드 실패 LoadError, 검색 범위 문구, 필터 범위 '이 페이지 N건 중 M건', CSV 현재 페이지 표시

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 35: Credential 회수 L3 — 버튼 "회수", 처리 중·연타 방지 + 상세 인증 기록 로딩/실패 통일

**Files:**
- Modify: `admin-ui/src/pages/tenant/CredentialsTab.tsx` (`handleRevoke`, 회수 다이얼로그 사용부 217–219행, `RevokeCredentialDialog` 236–260행)
- Modify: `admin-ui/src/pages/tenant/CredentialDetailDialog.tsx` (인증 기록 로딩·실패 표시)
- Test: `admin-ui/src/pages/tenant/CredentialsTab.test.tsx`, `admin-ui/src/pages/tenant/CredentialDetailDialog.test.tsx`

**Interfaces:**
- Consumes: `ConfirmDialog` L3 `typeToConfirm` (Task 4), `LoadError compact` (Task 2), `Skeleton` (Task 3)
- Produces: 없음. 회수 다이얼로그 = "Credential을 회수할까요?", 마지막 8자 입력, 확인 버튼 "회수"(처리 중 "회수 중…").

- [ ] **Step 1: 실패하는 테스트 작성 + 바뀐 문구의 기존 테스트 갱신**

`admin-ui/src/pages/tenant/CredentialsTab.test.tsx` 의 `it('회수: 마지막 8자를 정확히 입력해야 실행, …')` 블록 교체:
```tsx
  it('회수: L3 — 마지막 8자를 정확히 입력해야 "회수", 상세 푸터에서도 진입', async () => {
    api.list.mockResolvedValue(pageOf([cred('cred-0000-abcdefgh')]));
    api.revoke.mockResolvedValue(undefined);
    renderTab();
    fireEvent.click(await screen.findByText('user-cred-0000-abcdefgh'));
    fireEvent.click(screen.getByRole('button', { name: 'Credential 회수…' }));
    expect(screen.queryByRole('dialog', { name: /Credential 상세/ })).toBeNull();

    const dlg = screen.getByRole('alertdialog', { name: 'Credential을 회수할까요?' });
    expect(within(dlg).getByText(/더 이상 로그인할 수 없습니다/)).toBeInTheDocument();
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    const confirm = within(dlg).getByRole('button', { name: '회수' });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dlg).getByRole('textbox', { name: /마지막 8자/ }), { target: { value: 'abcdefgh' } });
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);
    await waitFor(() => expect(api.revoke).toHaveBeenCalledWith('t1', 'cred-0000-abcdefgh'));
    await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(await screen.findByText('Credential을 회수했습니다.')).toBeInTheDocument();
  });
```
`describe` 끝에 추가:
```tsx
  it('회수 처리 중에는 "회수 중…" 비활성, 연타해도 한 번만', async () => {
    api.list.mockResolvedValue(pageOf([cred('aaaa-11111111')]));
    api.revoke.mockReturnValue(new Promise(() => {}));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '회수 · …11111111' }));
    const dlg = screen.getByRole('alertdialog', { name: 'Credential을 회수할까요?' });
    fireEvent.change(within(dlg).getByRole('textbox', { name: /마지막 8자/ }), { target: { value: '11111111' } });
    const btn = within(dlg).getByRole('button', { name: '회수' });
    fireEvent.click(btn);
    fireEvent.click(btn);
    expect(api.revoke).toHaveBeenCalledTimes(1);
    expect(btn).toBeDisabled();
    expect(btn).toHaveTextContent('회수 중…');
  });

  it('회수 실패는 다이얼로그 안 인라인 오류, 닫히지 않고 토스트 없음', async () => {
    api.list.mockResolvedValue(pageOf([cred('aaaa-11111111')]));
    api.revoke.mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '회수 · …11111111' }));
    const dlg = screen.getByRole('alertdialog', { name: 'Credential을 회수할까요?' });
    fireEvent.change(within(dlg).getByRole('textbox', { name: /마지막 8자/ }), { target: { value: '11111111' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '회수' }));
    expect(await within(dlg).findByText('회수하지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByText('회수 실패')).toBeNull();
    expect(api.list).toHaveBeenCalledTimes(1);
  });
```

`admin-ui/src/pages/tenant/CredentialDetailDialog.test.tsx`:
`it('shows error state with retry when load fails')` 의 63행 교체:
```tsx
    expect(screen.getByRole('alert')).toHaveTextContent('인증 기록을 불러오지 못했습니다');
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
```
`it('refetches and renders events when retry is clicked after a failure')` 의 75행 교체:
```tsx
    const retry = await screen.findByRole('button', { name: '다시 시도' });
```
`describe` 끝에 추가:
```tsx
  it('인증 기록 로딩은 Skeleton("불러오는 중…" 텍스트 없음)', async () => {
    let resolve: (v: never[]) => void = () => {};
    vi.spyOn(credentialsApi, 'authEvents').mockReturnValue(new Promise((r) => { resolve = r; }));
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} />);
    expect(screen.getByRole('status', { name: '인증 기록 불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText('불러오는 중…')).toBeNull();
    resolve([]);
    await screen.findByText(/아직 인증 이력이 없습니다/);
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/tenant/CredentialsTab.test.tsx src/pages/tenant/CredentialDetailDialog.test.tsx`
Expected: FAIL — 다이얼로그 이름 "Credential 회수", 버튼 "확인 — 회수", 처리 중 비활성 없음(연타 시 2회 호출), 인증 기록 실패 시 "재시도" 버튼·`alert` 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/tenant/CredentialsTab.tsx`:

5행 `@/ui` import 에서 `ConfirmByTyping`, `Dialog` 를 빼고 `ConfirmDialog` 를 넣는다:
```tsx
import {
  Button, Card, ConfirmDialog, CopyBtn, DataTable, EmptyState, FilterChip, IconButton, LoadError, Pagination, RemovableChip,
  Segmented, Skeleton, StatusBadge, useToast, type Column,
} from '@/ui';
```

`handleRevoke`(111–121행)를 교체:
```tsx
  /** ConfirmDialog 의 onConfirm — 실패하면 throw 해서 다이얼로그 안에 오류를 보인다. */
  async function handleRevoke(c: Credential) {
    await credentialsApi.revoke(tenant.id, c.credentialId);
    toast({ kind: 'ok', title: 'Credential을 회수했습니다.', message: `${tail(c.credentialId, 12)} · ${c.externalUserId}` });
    load.reload();
  }
```

217–219행 교체:
```tsx
      {revoking && (
        <RevokeCredentialDialog key={revoking.credentialId} c={revoking} onClose={() => setRevoking(null)} onConfirm={() => handleRevoke(revoking)} />
      )}
```

236–260행 `RevokeCredentialDialog` 교체:
```tsx
// ── Credential 회수 — L3 마지막 8자 입력 확인 ────────────────────────────────

function RevokeCredentialDialog({ c, onClose, onConfirm }: { c: Credential; onClose: () => void; onConfirm: () => Promise<void> }) {
  const required = tail(c.credentialId, 8);
  return (
    <ConfirmDialog open onClose={onClose} level="L3" verb="회수" title="Credential을 회수할까요?"
      sub="이 사용자는 해당 패스키로 더 이상 로그인할 수 없습니다. 되돌릴 수 없습니다."
      typeToConfirm={{
        expected: required,
        label: <>확인을 위해 마지막 8자 <span className="mono" style={{ background: 'var(--sunk)', padding: '0 4px', borderRadius: 'var(--r-xs)' }}>{required}</span> 를 입력하세요</>,
      }}
      onConfirm={onConfirm}>
      <KvList keyWidth={100} items={[
        { k: 'credentialId', v: `…${required}`, mono: true },
        { k: '사용자', v: c.externalUserId, mono: true },
        { k: '인증기', v: authenticatorLabel(c) },
      ]} />
    </ConfirmDialog>
  );
}
```

`admin-ui/src/pages/tenant/CredentialDetailDialog.tsx`:

4행 import 교체:
```tsx
import { Badge, Button, CopyBtn, Dialog, IconButton, LoadError, Skeleton, StatusBadge } from '@/ui';
```
25행 교체:
```tsx
  const [error, setError] = useState<unknown>(null);
```
36–44행 `load` 콜백 교체:
```tsx
  const load = useCallback(() => {
    const req = ++reqRef.current;
    setLoading(true);
    setError(null);
    credentialsApi.authEvents(tenantId, c.credentialId, 20)
      .then((res) => { if (aliveRef.current && req === reqRef.current) setEvents(res); })
      .catch((e: unknown) => { if (aliveRef.current && req === reqRef.current) setError(e ?? new Error('unknown')); })
      .finally(() => { if (aliveRef.current && req === reqRef.current) setLoading(false); });
  }, [tenantId, c.credentialId]);
```
99–108행(로딩·실패 분기)을 교체:
```tsx
        {loading ? (
          <Skeleton rows={3} label="인증 기록 불러오는 중" />
        ) : error ? (
          <LoadError compact what="인증 기록" error={error} onRetry={() => load()} />
```
(그 뒤 `: events.length === 0 ? (` 이하는 그대로.)

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/tenant/CredentialsTab.test.tsx src/pages/tenant/CredentialDetailDialog.test.tsx && npx tsc -b && npx eslint src`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/tenant/CredentialsTab.tsx admin-ui/src/pages/tenant/CredentialsTab.test.tsx admin-ui/src/pages/tenant/CredentialDetailDialog.tsx admin-ui/src/pages/tenant/CredentialDetailDialog.test.tsx
git commit -m "feat(admin-ui): Credential 회수 L3 확인 버튼 '회수'·처리 중 연타 방지, 인증 기록 로딩·실패 표시 통일

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 36: 감사 로그 탭 — `LoadError`, 이벤트 종류 그룹 칩, 범위 표시, 체인 검증 가짜 진행률 제거

**Files:**
- Modify: `admin-ui/src/pages/tenant/auditHelpers.ts` (`EVENT_GROUPS`, `eventGroup` 추가)
- Modify: `admin-ui/src/ui/Chips.tsx:5-7` (`FilterChip` 에 `title` 추가)
- Modify: `admin-ui/src/pages/tenant/AuditTab.tsx` (전체 교체)
- Modify: `admin-ui/src/styles/components.css` (`.indet` 진행 표시 추가)
- Test: `admin-ui/src/pages/tenant/auditHelpers.test.ts`, `admin-ui/src/pages/tenant/AuditTab.test.tsx`

**Interfaces:**
- Consumes: `describeError` (Task 1), `LoadError` (Task 2), `Skeleton` (Task 3)
- Produces:
  ```ts
  type EventGroup = 'auth' | 'registration' | 'config' | 'keys';
  const EVENT_GROUPS: { id: EventGroup; label: string; codes: string }[]; // 인증 · 등록 · 설정 변경 · 키 관리, codes = 툴팁용 원시 패턴
  function eventGroup(eventType: string): EventGroup | null;
  FilterChip props + { title?: string }
  ```

- [ ] **Step 1: 실패하는 테스트 작성 + 바뀐 문구의 기존 테스트 갱신**

`admin-ui/src/pages/tenant/auditHelpers.test.ts` 2행 import 에 `eventGroup, EVENT_GROUPS` 추가, `describe` 끝에 추가:
```ts
  it('eventGroup: 인증 · 등록 · 설정 변경 · 키 관리, 그 밖은 null', () => {
    expect(eventGroup('AUTH_SUCCESS')).toBe('auth');
    expect(eventGroup('AUTH_FAIL')).toBe('auth');
    expect(eventGroup('AUTHENTICATION_FINISH_OK')).toBe('auth');
    expect(eventGroup('CREDENTIAL_AUTHENTICATED')).toBe('auth');
    expect(eventGroup('SIGNATURE_COUNTER_REGRESSION')).toBe('auth');
    expect(eventGroup('REGISTRATION_FINISH_OK')).toBe('registration');
    expect(eventGroup('CREDENTIAL_REGISTERED')).toBe('registration');
    expect(eventGroup('ATTESTATION_TRUST_FAILED')).toBe('registration');
    expect(eventGroup('WEBAUTHN_CONFIG_UPDATED')).toBe('config');
    expect(eventGroup('AAGUID_POLICY_UPDATED')).toBe('config');
    expect(eventGroup('TENANT_SUSPEND')).toBe('config');
    expect(eventGroup('API_KEY_ROTATED')).toBe('keys');
    expect(eventGroup('CREDENTIAL_REVOKE')).toBe('keys');
    expect(eventGroup('CREDENTIAL_REVOKED')).toBe('keys');
    expect(eventGroup('MDS_BLOB_SYNC')).toBeNull();
    expect(EVENT_GROUPS.map((g) => g.label)).toEqual(['인증', '등록', '설정 변경', '키 관리']);
  });
```

`admin-ui/src/pages/tenant/AuditTab.test.tsx`:

8행 아래에 추가:
```tsx
import { ApiError } from '@/api/types';
```
import 에 `act` 추가: `import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react';`

`it('실패만: …')` 의 55행 다음에 추가(범위 표시):
```tsx
    expect(screen.getByText('이 페이지 4건 중 2건')).toBeInTheDocument();
```

`it('유형 칩으로 거르고 전체 해제')` 블록 교체:
```tsx
  it('이벤트 종류 그룹 칩(원시 코드는 툴팁)으로 현재 페이지를 거르고, 범위·CSV 라벨 표시, 필터 지우기', async () => {
    api.list.mockResolvedValue(resp([ev('1', { eventType: 'AUTH_SUCCESS' }), ev('2', { eventType: 'API_KEY_ROTATED' })]));
    renderTab();
    await screen.findByText('AUTH_SUCCESS', { selector: 'tbody .badge' });
    const group = screen.getByRole('group', { name: '이벤트 종류' });
    expect(within(group).getAllByRole('button').map((b) => b.textContent)).toEqual(['인증', '등록', '설정 변경', '키 관리']);
    const keys = within(group).getByRole('button', { name: '키 관리' });
    expect(keys).toHaveAttribute('title', 'API_KEY_* · CREDENTIAL_REVOKE*');
    expect(screen.queryByRole('button', { name: 'API_KEY_ROTATED' })).toBeNull();
    fireEvent.click(keys);
    expect(keys).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByText('AUTH_SUCCESS', { selector: 'tbody .badge' })).toBeNull();
    expect(screen.getByText('API_KEY_ROTATED', { selector: 'tbody .badge' })).toBeInTheDocument();
    expect(screen.getByText('이 페이지 2건 중 1건')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'CSV 내보내기 (현재 페이지 1건)' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: '필터 지우기' }));
    expect(screen.getByText('AUTH_SUCCESS', { selector: 'tbody .badge' })).toBeInTheDocument();
    expect(screen.getByText('최근 2건')).toBeInTheDocument();
  });
```

`it('체인 검증 카드는 PLATFORM 전용, …')` 블록 교체:
```tsx
  it('체인 검증 카드는 PLATFORM 전용, 검증 실행 → 결과 배지', async () => {
    api.list.mockResolvedValue(resp([]));
    const { unmount } = renderTab(false);
    await waitFor(() => expect(api.list).toHaveBeenCalled());
    expect(screen.queryByText('감사 로그 위변조 검증')).toBeNull();
    unmount();

    api.verify.mockResolvedValue({ intact: true, verifiedRows: 0, tamperedEntryIds: [], verifiedAt: new Date().toISOString() });
    renderTab(true);
    expect(await screen.findByText('감사 로그 위변조 검증')).toBeInTheDocument();
    expect(screen.getByText('PLATFORM 전용')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '검증 실행' }));
    const dlg = screen.getByRole('dialog', { name: '해시 체인 검증' });
    // 서버 /chain/verify 는 기간을 받지 않고 항상 전체 체인을 검증한다 — 기간 입력을 보이지 않는다.
    expect(within(dlg).queryByLabelText('from')).toBeNull();
    expect(within(dlg).queryByLabelText('to')).toBeNull();
    expect(dlg).toHaveTextContent('전체 체인 검증');
    fireEvent.click(within(dlg).getByRole('button', { name: '검증 실행' }));
    await waitFor(() => expect(api.verify).toHaveBeenCalledWith('t1'));
    expect(await screen.findByText('위변조 0건')).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: '해시 체인 검증' })).toBeNull();
  });
```

`describe` 끝에 추가:
```tsx
  it('체인 검증 중: 가짜 퍼센트 없이 "검증 중…" + 경과 시간, 닫아도 계속 실행되고 결과는 카드에', async () => {
    api.list.mockResolvedValue(resp([]));
    let resolve: (v: unknown) => void = () => {};
    api.verify.mockReturnValue(new Promise((r) => { resolve = r; }));
    renderTab(true);
    fireEvent.click(await screen.findByRole('button', { name: '검증 실행' }));
    const dlg = screen.getByRole('dialog', { name: '해시 체인 검증' });
    fireEvent.click(within(dlg).getByRole('button', { name: '검증 실행' }));
    expect(within(dlg).getByRole('button', { name: '검증 중…' })).toBeDisabled();
    const bar = within(dlg).getByRole('progressbar', { name: '검증 진행 중' });
    expect(bar).not.toHaveAttribute('aria-valuenow');
    expect(dlg.textContent).not.toMatch(/\d+%/);
    expect(dlg).toHaveTextContent(/경과 \d+초/);
    fireEvent.click(within(dlg).getByRole('button', { name: '닫기 (계속 실행)' }));
    expect(screen.queryByRole('dialog', { name: '해시 체인 검증' })).toBeNull();
    expect(screen.getByRole('button', { name: '검증 중…' })).toBeDisabled(); // 카드 버튼
    await act(async () => { resolve({ intact: true, verifiedRows: 0, tamperedEntryIds: [], verifiedAt: new Date().toISOString() }); });
    expect(await screen.findByText('위변조 0건')).toBeInTheDocument();
    expect(api.verify).toHaveBeenCalledTimes(1);
  });

  it('로드 실패: "이 기간에 이벤트 없음"이 아니라 LoadError, 건수 "—", CSV 비활성, 다시 시도', async () => {
    api.list.mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503')).mockResolvedValueOnce(resp([ev('1')]));
    renderTab();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('감사 로그를 불러오지 못했습니다');
    expect(screen.queryByText('이 기간에 감사 이벤트가 없습니다.')).toBeNull();
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /CSV 내보내기/ })).toBeDisabled();
    expect(screen.queryByText('audit 로드 실패')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('WEBAUTHN_CONFIG_UPDATED', { selector: 'tbody .badge' })).toBeInTheDocument();
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  it('로딩 중에는 Skeleton', () => {
    api.list.mockReturnValue(new Promise(() => {}));
    renderTab();
    expect(screen.getByRole('status', { name: '감사 로그 불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText('불러오는 중…')).toBeNull();
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/tenant/auditHelpers.test.ts src/pages/tenant/AuditTab.test.tsx`
Expected: FAIL — `eventGroup` 없음, 칩이 원시 이벤트 이름, 다이얼로그 이름 "Hash chain 검증", 진행 막대에 `aria-valuenow`/퍼센트, 로드 실패가 토스트 + "이 기간에 감사 이벤트가 없습니다."

- [ ] **Step 3: 구현**

`admin-ui/src/pages/tenant/auditHelpers.ts` 31행(`eventTone` 끝) 아래에 추가:
```ts
// ── 이벤트 종류 그룹 (V3 감사 로그) — 서버에 그룹 파라미터가 없어 프론트에서 매핑한다(스펙 §8) ──

export type EventGroup = 'auth' | 'registration' | 'config' | 'keys';

export const EVENT_GROUPS: { id: EventGroup; label: string; codes: string }[] = [
  { id: 'auth', label: '인증', codes: 'AUTH_* · AUTHENTICATION_* · CREDENTIAL_AUTHENTICATED · SIGNATURE_*' },
  { id: 'registration', label: '등록', codes: 'REGISTRATION_* · CREDENTIAL_REGISTERED · ATTESTATION_TRUST_*' },
  { id: 'config', label: '설정 변경', codes: 'WEBAUTHN_CONFIG_* · AAGUID_* · ATTESTATION_POLICY_* · TENANT_*' },
  { id: 'keys', label: '키 관리', codes: 'API_KEY_* · CREDENTIAL_REVOKE*' },
];

/** 이벤트 타입 → 그룹. 어느 그룹에도 속하지 않으면 null(그룹 필터를 켜면 숨김). */
export function eventGroup(type: string): EventGroup | null {
  if (/^AUTH(ENTICATION)?_/.test(type) || type === 'CREDENTIAL_AUTHENTICATED' || /^SIGNATURE_/.test(type)) return 'auth';
  if (/^REGISTRATION_/.test(type) || type === 'CREDENTIAL_REGISTERED' || /^ATTESTATION_TRUST_/.test(type)) return 'registration';
  if (/^(WEBAUTHN_CONFIG|AAGUID|ATTESTATION_POLICY|TENANT)_/.test(type)) return 'config';
  if (/^API_KEY_/.test(type) || /^CREDENTIAL_REVOKE/.test(type)) return 'keys';
  return null;
}
```

`admin-ui/src/ui/Chips.tsx` 5–7행 교체:
```tsx
export function FilterChip({ active, onClick, mono, title, children }: { active: boolean; onClick: () => void; mono?: boolean; title?: string; children: ReactNode }) {
  return <button type="button" className={cn('fchip', active && 'fchip--on', mono && 'fchip--mono')} aria-pressed={active} title={title} onClick={onClick}>{children}</button>;
}
```

`admin-ui/src/styles/components.css` 끝에 추가:
```css
/* ── indeterminate progress (진행률을 모를 때 — 퍼센트 없음) ── */
.indet { position: relative; height: 6px; border-radius: 3px; background: var(--line-soft); overflow: hidden; }
.indet::after { content: ""; position: absolute; top: 0; bottom: 0; left: -30%; width: 30%; border-radius: 3px; background: var(--brand-700); animation: indet 1.2s ease-in-out infinite; }
@keyframes indet { from { left: -30%; } to { left: 100%; } }
@media (prefers-reduced-motion: reduce) { .indet::after { animation: none; left: 0; width: 100%; opacity: .35; } }
```

`admin-ui/src/pages/tenant/AuditTab.tsx` 전체 교체:
```tsx
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import {
  Badge, Button, Card, CardFooter, DataTable, Dialog, EmptyState, FilterChip, LoadError, Segmented, Skeleton, Switch, useToast,
  type Column,
} from '@/ui';
import { auditApi } from '@/api/audit';
import { copyToClipboard } from '@/lib/clipboard';
import { downloadCsv } from '@/lib/csvExport';
import { describeError } from '@/lib/describeError';
import { kstToday } from '@/lib/kst';
import type { Tenant, AuditEvent, ChainVerifyResult } from '@/api/designTypes';
import { fmtNum, hms, timeAgo, ymdHm, ymdHms } from './tabFormat';
import {
  EVENT_GROUPS, WINDOWS, type EventGroup, type WindowKey, dayLabel, diffValue, eventGroup, eventTone, isFailure, payloadDiff, windowRange,
} from './auditHelpers';

const PAGE_SIZE = 50;
const tail = (s: string | null | undefined, n: number) => (s ? s.slice(-n) : '');
const pretty = (e: AuditEvent) => JSON.stringify(e.payload ?? {}, null, 2);

// ── AuditTab ──────────────────────────────────────────────────────────────────

export default function AuditTab({ tenant, isPlatformOperator = false }: { tenant: Tenant; isPlatformOperator?: boolean }) {
  const [items, setItems] = useState<AuditEvent[]>([]);
  const [total, setTotal] = useState<number | undefined>(undefined);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [groups, setGroups] = useState<Set<EventGroup>>(new Set());
  const [onlyFailures, setOnlyFailures] = useState(false);
  const [win, setWin] = useState<WindowKey>('24h');
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [attempt, setAttempt] = useState(0);        // 다시 시도
  const [expanded, setExpanded] = useState<string | null>(null);
  const [verifyResult, setVerifyResult] = useState<ChainVerifyResult | null>(null);
  const [showVerify, setShowVerify] = useState(false);
  const [verifyStartedAt, setVerifyStartedAt] = useState<number | null>(null);
  const verifyingRef = useRef(false);
  const [open, setOpen] = useState<AuditEvent | null>(null);
  const rangeRef = useRef<{ from: string; to: string } | null>(null); // 더 보기도 같은 구간으로
  const genRef = useRef(0);       // 초기 로드마다 증가 — 늦게 온 더 보기 응답 폐기용
  const pageRef = useRef(0);
  const loadingMoreRef = useRef(false);
  const toast = useToast();

  useEffect(() => {
    let cancelled = false;
    const range = windowRange(WINDOWS[win].hours);
    rangeRef.current = range;
    genRef.current += 1;
    pageRef.current = 0;
    loadingMoreRef.current = false;
    setLoadingMore(false);
    setLoading(true);
    setLoadError(null);
    setExpanded(null);
    auditApi.list(tenant.id, 0, PAGE_SIZE, range)
      .then((res) => {
        if (cancelled) return;
        setItems(res.items);
        setTotal(res.total);
        pageRef.current = 0;
        setHasMore(res.items.length === PAGE_SIZE);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        // 실패를 "이 기간에 이벤트 없음"으로 보이지 않게 목록을 비우고 오류를 남긴다.
        setItems([]);
        setTotal(undefined);
        setHasMore(false);
        setLoadError(e ?? new Error('unknown'));
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [tenant.id, win, attempt]);

  async function loadMore() {
    if (!rangeRef.current || loadingMoreRef.current) return;
    const gen = genRef.current;
    const next = pageRef.current + 1;
    loadingMoreRef.current = true;
    setLoadingMore(true);
    try {
      const res = await auditApi.list(tenant.id, next, PAGE_SIZE, rangeRef.current);
      if (gen !== genRef.current) return; // 기간·테넌트가 바뀜 — 이전 응답 폐기
      setItems((prev) => [...prev, ...res.items]);
      pageRef.current = next;
      setHasMore(res.items.length === PAGE_SIZE);
    } catch (e: unknown) {
      if (gen === genRef.current) {
        const info = describeError(e);
        toast({ kind: 'err', title: '감사 로그를 더 불러오지 못했습니다', message: `${info.title}. ${info.detail}` });
      }
    } finally {
      if (gen === genRef.current) {
        loadingMoreRef.current = false;
        setLoadingMore(false);
      }
    }
  }

  const filtering = groups.size > 0 || onlyFailures;
  const filtered = useMemo(
    () => items.filter((e) => {
      if (groups.size > 0) {
        const g = eventGroup(e.eventType);
        if (g === null || !groups.has(g)) return false;
      }
      return !onlyFailures || isFailure(e);
    }),
    [items, groups, onlyFailures],
  );

  function toggleGroup(g: EventGroup) {
    const next = new Set(groups);
    if (next.has(g)) next.delete(g); else next.add(g);
    setGroups(next);
  }
  function clearFilters() {
    setGroups(new Set());
    setOnlyFailures(false);
  }

  async function copyJson(e: AuditEvent) {
    if (await copyToClipboard(pretty(e))) toast({ kind: 'ok', title: 'JSON 을 복사했습니다.' });
    else toast({ kind: 'warn', title: '복사 실패', message: '클립보드 복사에 실패했습니다.' });
  }

  function exportCsv() {
    if (filtered.length === 0) return;
    downloadCsv(
      `audit-${tenant.slug}-${kstToday()}.csv`,
      ['ts', 'eventType', 'actorType', 'actorId', 'subjectType', 'subjectId', 'payload'],
      filtered.map((e) => [e.ts, e.eventType, e.actorType, e.actorId ?? '', e.subjectType ?? '', e.subjectId ?? '', JSON.stringify(e.payload ?? {})]),
    );
  }

  async function handleVerifyRun() {
    if (verifyingRef.current) return;
    verifyingRef.current = true;
    setVerifyStartedAt(Date.now());
    try {
      // 서버 /chain/verify 는 기간을 받지 않고 항상 전체 체인을 검증한다. 진행률도 주지 않는다.
      const result = await auditApi.verify(tenant.id);
      setVerifyResult(result);
      setShowVerify(false);
    } catch (e: unknown) {
      const info = describeError(e);
      // 다이얼로그를 닫은 뒤 끝날 수도 있으므로 오류는 토스트(자동으로 닫히지 않음)로 알린다.
      toast({ kind: 'err', title: '체인 검증에 실패했습니다', message: `${info.title}. ${info.detail}` });
    } finally {
      verifyingRef.current = false;
      setVerifyStartedAt(null);
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

  const failed = loadError !== null;
  const countText = failed
    ? '—'
    : filtering
      ? `이 페이지 ${fmtNum(items.length)}건 중 ${fmtNum(filtered.length)}건`
      : total !== undefined ? `${WINDOWS[win].long} · ${fmtNum(total)}건` : `최근 ${fmtNum(items.length)}건`;

  return (
    <div className="stack-6">
      {isPlatformOperator && <ChainVerifyCard onOpen={() => setShowVerify(true)} result={verifyResult} verifying={verifyStartedAt !== null} />}

      <Card>
        <div className="card__head">
          <div className="row" role="group" aria-label="이벤트 종류" style={{ gap: 6 }}>
            {EVENT_GROUPS.map((g) => (
              <FilterChip key={g.id} active={groups.has(g.id)} title={g.codes} onClick={() => toggleGroup(g.id)}>{g.label}</FilterChip>
            ))}
          </div>
          {filtering && <Button variant="ghost" size="sm" onClick={clearFilters}>필터 지우기</Button>}
          <div className="spacer" />
          <Switch checked={onlyFailures} onChange={setOnlyFailures} label="실패만" />
          <Segmented ariaLabel="기간" value={win} onChange={setWin}
            options={(Object.keys(WINDOWS) as WindowKey[]).map((k) => ({ value: k, label: WINDOWS[k].label }))} />
          <Button icon={<Icons.Download size={14} />} onClick={exportCsv} disabled={failed || filtered.length === 0}>
            CSV 내보내기 (현재 페이지 {fmtNum(filtered.length)}건)
          </Button>
        </div>

        {loading ? (
          <Skeleton rows={6} label="감사 로그 불러오는 중" />
        ) : failed ? (
          <LoadError what="감사 로그" error={loadError} onRetry={() => setAttempt((n) => n + 1)} />
        ) : (
          <DataTable
            columns={columns}
            rows={filtered}
            rowKey={(e) => e.id}
            groupBy={(e) => dayLabel(e.ts)}
            onRowClick={(e) => setExpanded((k) => (k === e.id ? null : e.id))}
            expandedKey={expanded}
            renderExpanded={(e) => <ExpandedEvent e={e} onCopy={() => void copyJson(e)} onOpen={() => setOpen(e)} />}
            empty={<EmptyState compact title={filtering ? '조건에 맞는 이벤트가 없습니다.' : '이 기간에 감사 이벤트가 없습니다.'} />}
          />
        )}

        <CardFooter>
          <span className="hint" style={{ margin: 0 }}>{countText}</span>
          <div className="spacer" />
          {hasMore && !failed && <Button size="sm" disabled={loadingMore} onClick={() => void loadMore()}>{loadingMore ? '더 불러오는 중' : '더 보기'}</Button>}
        </CardFooter>
      </Card>

      {isPlatformOperator && (
        <ChainVerifyDialog open={showVerify} onClose={() => setShowVerify(false)} onRun={() => void handleVerifyRun()}
          startedAt={verifyStartedAt} tenantName={tenant.name} />
      )}
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

function ChainVerifyCard({ onOpen, result, verifying }: { onOpen: () => void; result: ChainVerifyResult | null; verifying: boolean }) {
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
            <h2 className="card__title">감사 로그 위변조 검증</h2>
            {verifying ? <Badge>검증 중</Badge> : result && (result.intact
              ? <Badge tone="ok">위변조 0건</Badge>
              : <Badge tone="bad">위변조 {result.tamperedEntryIds.length}건</Badge>)}
            <Badge tone="plat" dot={false}>PLATFORM 전용</Badge>
          </div>
          <p className="card__sub">
            감사 로그 행이 해시로 이어져 바뀌거나 지워지지 않았는지 확인합니다.{result ? ` 마지막 검증 ${ymdHm(result.verifiedAt)} KST` : ''}
          </p>
        </div>
        {result?.intact && <Button onClick={() => navigate('/audit-chain')}>월간 보고서</Button>}
        <Button variant="primary" onClick={onOpen} disabled={verifying}>{verifying ? '검증 중…' : '검증 실행'}</Button>
      </div>
    </Card>
  );
}

// ── 해시 체인 검증 다이얼로그 — 진행률을 모르므로 퍼센트 없이 "검증 중…" + 경과 시간 ──

function ChainVerifyDialog({ open, onClose, onRun, startedAt, tenantName }: {
  open: boolean; onClose: () => void; onRun: () => void; startedAt: number | null; tenantName: string;
}) {
  const running = startedAt !== null;
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!running) return;
    setNow(Date.now());
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [running]);
  const elapsed = startedAt !== null ? Math.max(0, Math.floor((now - startedAt) / 1000)) : 0;

  return (
    <Dialog open={open} onClose={onClose} title="해시 체인 검증" sub={`${tenantName}의 감사 로그 전체를 검증합니다. 읽기 전용이며 데이터는 바뀌지 않습니다.`}
      footer={<>
        <Button onClick={onClose}>{running ? '닫기 (계속 실행)' : '취소'}</Button>
        <Button variant="primary" disabled={running} onClick={onRun}>{running ? '검증 중…' : '검증 실행'}</Button>
      </>}>
      <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>
        전체 체인 검증 — 처음부터 해시를 다시 계산해 확인합니다. 기간은 지정하지 않습니다.
      </p>
      {startedAt !== null && (
        <div className="stack-2" role="status" aria-live="polite">
          <div className="row">
            <strong style={{ fontSize: 13 }}>검증 중…</strong>
            <div className="spacer" />
            <span className="hint" style={{ margin: 0 }}>시작 {hms(new Date(startedAt).toISOString())} · 경과 {elapsed}초</span>
          </div>
          <div className="indet" role="progressbar" aria-label="검증 진행 중" />
        </div>
      )}
      <p className="hint" style={{ margin: 0 }}>진행률을 알 수 없어 퍼센트를 표시하지 않습니다. 창을 닫아도 검증은 계속되고 결과는 위 카드에 표시됩니다.</p>
    </Dialog>
  );
}
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/tenant/auditHelpers.test.ts src/pages/tenant/AuditTab.test.tsx src/ui && npx tsc -b && npx eslint src`
Expected: PASS — 새 4개 + 기존(기간 from/to, 실패만, 날짜 그룹·펼침, 건수, 더 보기, 늦은 응답 폐기) 전부.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/tenant/auditHelpers.ts admin-ui/src/pages/tenant/auditHelpers.test.ts admin-ui/src/pages/tenant/AuditTab.tsx admin-ui/src/pages/tenant/AuditTab.test.tsx admin-ui/src/ui/Chips.tsx admin-ui/src/styles/components.css
git commit -m "feat(admin-ui): 감사 로그 이벤트 종류 그룹 칩·현재 페이지 범위 표시, 로드 실패 LoadError, 체인 검증 가짜 진행률 제거

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 37: 퍼널 탭 — `LoadError` + 다시 시도, `Skeleton`

**Files:**
- Modify: `admin-ui/src/pages/tenant/FunnelTab.tsx:1-36, 62-73`
- Test: `admin-ui/src/pages/tenant/FunnelTab.test.tsx`

**Interfaces:**
- Consumes: `useLoad` (Task 1), `LoadError` (Task 2), `Skeleton` (Task 3)
- Produces: 없음. 실패 = `LoadError what="퍼널 데이터"`(내보내기 비활성), 로딩·기간 전환 = `Skeleton label="퍼널 데이터 불러오는 중"`.

- [ ] **Step 1: 실패하는 테스트 작성 + 바뀐 문구의 기존 테스트 갱신**

`admin-ui/src/pages/tenant/FunnelTab.test.tsx`:

`it('기간 전환 중에는 이전 기간 숫자를 …')` 의 80행 교체:
```tsx
    expect(screen.getByRole('status', { name: '퍼널 데이터 불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText('불러오는 중…')).toBeNull();
```
`it('조회 실패 시 오류 배너')` 블록 교체:
```tsx
  it('조회 실패: 재시도 없는 배너 대신 LoadError — 지표·차트 없음, 내보내기 비활성, 다시 시도로 복구', async () => {
    api.get.mockRejectedValueOnce(new Error('boom'));
    render(<FunnelTab tenant={tenant} />);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('퍼널 데이터를 불러오지 못했습니다');
    expect(screen.queryByText('63.0%')).toBeNull();
    expect(screen.getByRole('button', { name: '내보내기' })).toBeDisabled();
    expect(within(alert).getByRole('button', { name: '오류 정보 복사' })).toBeInTheDocument();
    fireEvent.click(within(alert).getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('63.0%')).toBeInTheDocument();
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/tenant/FunnelTab.test.tsx`
Expected: FAIL — `status` 역할 없음("불러오는 중…" 문단), 실패 배너에 `다시 시도` 버튼 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/tenant/FunnelTab.tsx`:

4행 import 교체 + 아래에 `useLoad` import 추가:
```tsx
import { Button, Card, CardBody, CardHeader, DataTable, KpiCard, LoadError, Segmented, Skeleton, type Column } from '@/ui';
import { useLoad } from '@/lib/useLoad';
```

22–36행(컴포넌트 시작 ~ 본 조회 `useEffect`)을 교체:
```tsx
export default function FunnelTab({ tenant }: { tenant: Tenant }) {
  const [windowDays, setWindowDays] = useState<Win>(7);
  const load = useLoad(() => funnelApi.get(tenant.id, windowDays), [tenant.id, windowDays]);
  // 기간·테넌트 전환 중(status=loading)에는 이전 숫자를 새 라벨 아래 보이지 않게 data 를 쓰지 않는다.
  const data: FunnelData | null = load.status === 'ok' ? load.data ?? null : null;
  const [series30, setSeries30] = useState<FunnelSeries[] | null>(null);
```

62–73행(return 시작 ~ 로딩 분기)을 교체:
```tsx
  return (
    <div className="stack-6">
      <div className="row" style={{ gap: 16 }}>
        <Segmented ariaLabel="기간" value={String(windowDays)} onChange={(v) => setWindowDays(Number(v) as Win)} options={WIN_OPTIONS} />
        <div className="spacer" />
        <Button icon={<Icons.Download size={14} />} onClick={exportFunnel} disabled={!s}>내보내기</Button>
      </div>

      {load.status === 'failed' ? (
        <LoadError what="퍼널 데이터" error={load.error} onRetry={load.reload} retrying={load.reloading} />
      ) : !data || !s ? (
        <Skeleton rows={6} label="퍼널 데이터 불러오는 중" />
      ) : (
```
(그 뒤 `<>` 로 시작하는 KPI·차트 블록과 `)}` 닫힘은 그대로. `failed` 상태 변수와 `Alert` import 는 삭제된 상태다.)

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/tenant/FunnelTab.test.tsx && npx tsc -b && npx eslint src`
Expected: PASS (6 tests).

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/tenant/FunnelTab.tsx admin-ui/src/pages/tenant/FunnelTab.test.tsx
git commit -m "fix(admin-ui): 퍼널 조회 실패를 다시 시도가 있는 LoadError 로, 로딩은 Skeleton 으로

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## 설정 · 오버레이

> 스펙 §6 "설정 탭" 표와 §7 "다이얼로그·오버레이" 중 커맨드 팔레트·세션 만료 모달을 다룬다. Task 1–6(공통 패턴)이 먼저 끝나 있어야 한다: `useLoad`, `describeError`, `eulReul`, `LoadError`, `Skeleton`, `ConfirmDialog`, `ToastInput.action`, `Button` forwardRef.
> 이 파트의 "현재 파일" 줄 번호는 이 계획을 쓴 시점(커밋 `90290641`) 기준이다. 앞 파트가 같은 파일을 먼저 고쳤다면 줄 번호 대신 인용한 기준 코드로 위치를 찾는다.

---

### Task 38: 내 계정 — OTP 오류 인라인 + 포커스 복귀, 복구 코드 보관 안내

**Files:**
- Modify: `admin-ui/src/pages/settings/AccountTab.tsx` (전체 교체)
- Test: `admin-ui/src/pages/settings/AccountTab.test.tsx`

**Interfaces:**
- Consumes: `describeError` (Task 1), `OtpInput` 의 `focusKey` prop(기존), `Alert`(기존)
- Produces: 없음 (화면 동작만). 문구 상수 `'코드가 맞지 않습니다. 앱에 표시된 새 코드(30초마다 바뀜)를 입력하세요.'`

- [ ] **Step 1: 실패하는 테스트 작성 + 바뀐 기대값 수정**

`admin-ui/src/pages/settings/AccountTab.test.tsx` 에서 기존 테스트 `'잘못된 코드면 코드가 지워지고 다이얼로그는 유지'`(93–104행) 전체를 아래로 교체:
```tsx
  it('잘못된 코드면 다이얼로그 안 인라인 오류 + 코드 지움 + 첫 칸 포커스, 토스트 없음', async () => {
    const { ApiError } = await import('@/api/types');
    m(mfaApi.confirm).mockRejectedValue(new ApiError(401, 'invalid_code', 'invalid_code'));
    renderHost(<Host initial={off} />);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 켜기' }));
    const dlg = await screen.findByRole('dialog', { name: '2단계 인증 켜기' });
    fireEvent.paste(within(dlg).getByRole('textbox', { name: '인증 코드 1번째 자리' }), { clipboardData: { getData: () => '000000' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '확인 → 복구 코드' }));
    expect(await within(dlg).findByText(CODE_MISMATCH)).toBeInTheDocument();
    const first = within(dlg).getByRole('textbox', { name: '인증 코드 1번째 자리' });
    expect(first).toHaveValue('');
    await waitFor(() => expect(document.activeElement).toBe(first));
    expect(screen.getByRole('dialog', { name: '2단계 인증 켜기' })).toBeInTheDocument();
    expect(document.querySelector('.toast')).toBeNull();
    // 다시 입력하면 오류가 사라진다
    fireEvent.change(first, { target: { value: '1' } });
    expect(within(dlg).queryByText(CODE_MISMATCH)).toBeNull();
  });

  it('네트워크 오류는 코드 불일치가 아니라 쉬운 오류 문구로 인라인 표시', async () => {
    m(mfaApi.confirm).mockRejectedValue(new TypeError('Failed to fetch'));
    renderHost(<Host initial={off} />);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 켜기' }));
    const dlg = await screen.findByRole('dialog', { name: '2단계 인증 켜기' });
    fireEvent.paste(within(dlg).getByRole('textbox', { name: '인증 코드 1번째 자리' }), { clipboardData: { getData: () => '123456' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '확인 → 복구 코드' }));
    expect(await within(dlg).findByText(/네트워크에 연결할 수 없습니다/)).toBeInTheDocument();
    expect(within(dlg).queryByText(CODE_MISMATCH)).toBeNull();
  });
```

같은 파일 21행(`const CODES = …`) 다음 줄에 추가:
```tsx
const CODE_MISMATCH = '코드가 맞지 않습니다. 앱에 표시된 새 코드(30초마다 바뀜)를 입력하세요.';
```

기존 테스트 `'optional rows shown when defined'`(125–131행) 전체를 아래로 교체(복구 코드 개수는 2단계 — 스펙 §8):
```tsx
  it('등록일은 서버 값이 있을 때만, 복구 코드는 개수 대신 보관 안내', () => {
    renderHost(<Host initial={{ ...on, mfaEnrolledAt: '2026-08-31T20:00:00Z', recoveryCodesRemaining: 7 }} />);
    expect(screen.getByText('등록일')).toBeInTheDocument();
    expect(screen.getByText('2026-09-01')).toBeInTheDocument(); // UTC 08-31 20:00 = KST 09-01 05:00
    expect(screen.queryByText('남은 복구 코드')).toBeNull();
    expect(screen.queryByText('7 / 10')).toBeNull();
    expect(screen.getByText('복구 코드 보관')).toBeInTheDocument();
    expect(screen.getByText(/한 번만 표시됩니다/)).toBeInTheDocument();
  });
```

`describe('AccountTab — MFA 켜짐', …)` 블록 마지막(146행 `});` 앞)에 추가:
```tsx
  it('끄기 코드가 틀리면 다이얼로그 안 인라인 오류, 입력값 유지 + 전체 선택 + 포커스, 토스트 없음', async () => {
    const { ApiError } = await import('@/api/types');
    m(mfaApi.disable).mockRejectedValue(new ApiError(401, 'invalid_code', 'invalid_code'));
    renderHost(<Host initial={on} />);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 끄기' }));
    const dlg = screen.getByRole('alertdialog', { name: '2단계 인증 끄기' });
    const input = within(dlg).getByLabelText('인증 코드') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '123456' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '끄기' }));
    expect(await within(dlg).findByText(CODE_MISMATCH)).toBeInTheDocument();
    expect(input).toHaveValue('123456');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(document.activeElement).toBe(input);
    expect(input.selectionStart).toBe(0);
    expect(input.selectionEnd).toBe(6);
    expect(document.querySelector('.toast')).toBeNull();
    expect(screen.getByRole('alertdialog', { name: '2단계 인증 끄기' })).toBeInTheDocument();
    fireEvent.change(input, { target: { value: '65432' } });
    expect(within(dlg).queryByText(CODE_MISMATCH)).toBeNull();
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/settings/AccountTab.test.tsx`
Expected: FAIL — `CODE_MISMATCH` 문구를 찾지 못함(현재는 토스트 "코드가 올바르지 않습니다."), `남은 복구 코드` 가 보임, `복구 코드 보관` 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/settings/AccountTab.tsx` 전체를 아래로 교체:
```tsx
// admin-ui/src/pages/settings/AccountTab.tsx
import { useRef, useState } from 'react';
import { mfaApi, type EnrollResponse } from '@/api/mfa';
import { getMe } from '@/api/client';
import { ApiError, type Me } from '@/api/types';
import { QrCode } from '@/shell/QrCode';
import { kstDate } from '@/lib/kst';
import { describeError } from '@/lib/describeError';
import { illustrations } from '@/assets/illustrations';
import { Alert, Badge, Button, Dialog, OtpInput, SecretBox, Stepper, useToast, useUnsavedGuard, type StepState } from '@/ui';
import { KvList, type KvItem } from '@/pages/tenant/KvList';
import { RecoveryCodesModal } from './RecoveryCodesModal';

function sanitizeCode(v: string): string {
  return v.replace(/\D/g, '').slice(0, 6);
}

const CODE_MISMATCH = '코드가 맞지 않습니다. 앱에 표시된 새 코드(30초마다 바뀜)를 입력하세요.';

/** OTP 확인 실패 문구. 4xx 는 코드 불일치, 그 밖(5xx·네트워크)은 쉬운 오류 문구. 토스트 없이 입력칸 아래에만 보인다. */
function codeErrorText(e: unknown): string {
  if (e instanceof ApiError && e.httpStatus < 500) return CODE_MISMATCH;
  const info = describeError(e);
  return `${info.title}. ${info.detail}`;
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

export default function AccountTab({ me, onMeChange }: { me: Me; onMeChange: (m: Me) => void }) {
  const toast = useToast();

  // enroll flow state
  const [enroll, setEnroll] = useState<EnrollResponse | null>(null);
  const [enrollCode, setEnrollCode] = useState('');
  const [enrollError, setEnrollError] = useState<string | null>(null);
  const [enrollFocusKey, setEnrollFocusKey] = useState(0);
  const [enrolling, setEnrolling] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
  // 복구 코드는 1회만 표시된다 — 닫기 전 이동·새로고침을 막는다.
  const leaveGuard = useUnsavedGuard(recoveryCodes !== null, {
    title: '복구 코드를 아직 저장하지 않았습니다',
    body: '이동하면 복구 코드를 다시 볼 수 없습니다.',
  });

  // disable flow state
  const [disableOpen, setDisableOpen] = useState(false);
  const [disableCode, setDisableCode] = useState('');
  const [disableError, setDisableError] = useState<string | null>(null);
  const [disabling, setDisabling] = useState(false);
  const disableInputRef = useRef<HTMLInputElement>(null);

  async function startEnroll() {
    setEnrolling(true);
    try {
      const res = await mfaApi.enroll();
      setEnroll(res);
      setEnrollCode('');
      setEnrollError(null);
    } catch (e: unknown) {
      const info = describeError(e);
      toast({ kind: 'err', title: '2단계 인증을 시작하지 못했습니다', message: `${info.title}. ${info.detail}` });
    } finally {
      setEnrolling(false);
    }
  }

  function cancelEnroll() {
    setEnroll(null);
    setEnrollCode('');
    setEnrollError(null);
  }

  function changeEnrollCode(v: string) {
    setEnrollCode(v);
    if (enrollError) setEnrollError(null);
  }

  async function confirmEnroll() {
    setConfirming(true);
    try {
      const res = await mfaApi.confirm(enrollCode);
      setEnroll(null);
      setEnrollCode('');
      setEnrollError(null);
      // 복구 코드는 1회만 표시 — me 갱신 실패와 무관하게 먼저 보여 준다.
      if (res.recoveryCodes && res.recoveryCodes.length > 0) {
        setRecoveryCodes(res.recoveryCodes);
      }
      toast({ kind: 'ok', title: '2단계 인증이 켜졌습니다.' });
      try {
        onMeChange(await getMe());
      } catch {
        toast({ kind: 'warn', title: '계정 정보를 새로고침하지 못했습니다.', message: '페이지를 새로고침해 주세요.' });
      }
    } catch (e: unknown) {
      // 오류는 입력칸 아래에만(토스트와 중복하지 않음). 코드를 비우고 첫 칸으로 포커스를 돌려 바로 다시 입력하게 한다.
      setEnrollCode('');
      setEnrollError(codeErrorText(e));
      setEnrollFocusKey((k) => k + 1);
    } finally {
      setConfirming(false);
    }
  }

  function openDisable() {
    setDisableCode('');
    setDisableError(null);
    setDisableOpen(true);
  }

  async function confirmDisable() {
    setDisabling(true);
    try {
      await mfaApi.disable(disableCode);
    } catch (e: unknown) {
      // 입력값은 남기고 전체 선택 — 새 코드를 치면 바로 덮어쓴다. 토스트로 따로 알리지 않는다.
      setDisableError(codeErrorText(e));
      setDisabling(false);
      disableInputRef.current?.focus();
      disableInputRef.current?.select();
      return;
    }
    setDisabling(false);
    setDisableOpen(false);
    setDisableCode('');
    toast({ kind: 'warn', title: '2단계 인증이 꺼졌습니다.' });
    try {
      onMeChange(await getMe());
    } catch {
      toast({ kind: 'warn', title: '계정 정보를 새로고침하지 못했습니다.', message: '페이지를 새로고침해 주세요.' });
    }
  }

  // §6 ④ — 서버가 내려줄 때만 표시(null/undefined 면 줄 자체를 렌더하지 않음)
  const enrolledAt = me.mfaEnrolledAt != null ? kstDate(me.mfaEnrolledAt) : null;
  const infoRows: KvItem[] = enrolledAt !== null ? [{ k: '등록일', v: enrolledAt }] : [];

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
              {infoRows.length > 0 && <KvList items={infoRows} keyWidth={140} />}
              {/* 남은 개수·재발급은 서버 지원 후(스펙 §8). 지금은 보관 안내만. */}
              <Alert tone="info" title="복구 코드 보관">
                복구 코드는 2단계 인증을 켤 때 한 번만 표시됩니다. 휴대폰을 잃어버렸을 때 로그인할 수 있는 유일한 방법이니 비밀번호 관리자 등 안전한 곳에 보관하세요. 코드를 모두 잃어버렸다면 운영자에게 MFA 초기화를 요청하세요.
              </Alert>
              <div style={{ borderTop: '1px solid var(--line-soft)', paddingTop: 14 }}>
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
                    <OtpInput value={enrollCode} onChange={changeEnrollCode} ariaLabel="인증 코드" disabled={confirming} focusKey={enrollFocusKey} />
                  </div>
                  {enrollError && <p className="field__error" role="alert">{enrollError}</p>}
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
        onClose={() => { if (!disabling) setDisableOpen(false); }}
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
            ref={disableInputRef}
            id="mfa-disable-code"
            className="input mono"
            inputMode="numeric"
            autoComplete="one-time-code"
            placeholder="000000"
            value={disableCode}
            aria-invalid={disableError ? true : undefined}
            aria-describedby={disableError ? 'mfa-disable-err' : undefined}
            onChange={(e) => { setDisableCode(sanitizeCode(e.target.value)); if (disableError) setDisableError(null); }}
            style={{ maxWidth: 200, letterSpacing: '0.2em' }}
          />
          {disableError && <p id="mfa-disable-err" className="field__error" role="alert">{disableError}</p>}
        </div>
      </Dialog>

      {/* ── 3 / 3: 복구 코드 ─────────────────────────────────────────────── */}
      {recoveryCodes && (
        <RecoveryCodesModal codes={recoveryCodes} accountEmail={me.email} onClose={() => setRecoveryCodes(null)} />
      )}
      {leaveGuard}
    </div>
  );
}
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/settings/AccountTab.test.tsx`
Expected: PASS (기존 + 신규 전부)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/settings/AccountTab.tsx admin-ui/src/pages/settings/AccountTab.test.tsx
git commit -m "fix(admin-ui): 내 계정 OTP 오류를 입력칸 아래 인라인으로 표시하고 포커스 복귀, 복구 코드 개수 대신 보관 안내

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 39: Admin 사용자 — 로드 상태, Tenant 이름 조인, 가입 거절 L1

**Files:**
- Modify: `admin-ui/src/pages/settings/AdminUsersTab.tsx` (전체 교체)
- Test: `admin-ui/src/pages/settings/AdminUsersTab.test.tsx`

**Interfaces:**
- Consumes: `useLoad` (Task 1), `LoadError` (Task 2), `Skeleton` (Task 3), `ConfirmDialog` L1 (Task 4), `tenantsApi.list(): Promise<Tenant[]>`(기존)
- Produces: `TenantCell({ u, names }: { u: AdminUserView; names: Record<string, string> })` — 파일 내부 컴포넌트, Task 40 의 정지 다이얼로그가 재사용.

가입 거절은 L1 이지만 거절을 되돌리는 API 가 없어(백엔드 변경 필요) 되돌리기 토스트는 붙이지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성 + 바뀐 기대값 수정**

`admin-ui/src/pages/settings/AdminUsersTab.test.tsx` 수정:

30행 `import { adminUsersApi } from '@/api/adminUsers';` 다음 줄에 추가:
```tsx
import { tenantsApi } from '@/api/tenants';
```

66행:
```tsx
    expect(screen.getByRole('button', { name: '거절' })).toBeInTheDocument();
```
→
```tsx
    expect(screen.getByRole('button', { name: '거절…' })).toBeInTheDocument();
```

145–156행 테스트 `'reject asks for in-app confirmation (alertdialog) before calling the API'` 전체를 교체:
```tsx
  it('거절은 L1 확인(기본 포커스 취소) 후 API 호출, 확인 버튼 라벨은 "거절"', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '거절…' }));
    expect(signupRequestsApi.reject).not.toHaveBeenCalled();
    const dlg = screen.getByRole('alertdialog', { name: '가입 요청을 거절할까요?' });
    expect(within(dlg).getByText('new@x.com의 요청을 거절합니다. 요청자는 다시 가입 요청을 보낼 수 있습니다.')).toBeInTheDocument();
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    expect(within(dlg).queryByRole('button', { name: '거절 확정' })).toBeNull();
    const confirm = within(dlg).getByRole('button', { name: '거절' });
    expect(confirm).toHaveClass('btn--danger-solid');
    fireEvent.click(confirm);
    await waitFor(() => expect(signupRequestsApi.reject).toHaveBeenCalledWith('r1'));
    expect(await screen.findByText('가입 요청을 거절했습니다.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
  });
```

191–200행 테스트 `'reject failure other than 409 keeps the confirm dialog open'` 전체를 교체:
```tsx
  it('reject failure other than 409 keeps the confirm dialog open with an inline error', async () => {
    mock(signupRequestsApi.reject).mockRejectedValue(new ApiError(500, 'C999', 'boom'));
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '거절…' }));
    const dlg = screen.getByRole('alertdialog', { name: '가입 요청을 거절할까요?' });
    fireEvent.click(within(dlg).getByRole('button', { name: '거절' }));
    await waitFor(() => expect(signupRequestsApi.reject).toHaveBeenCalledWith('r1'));
    expect(await within(dlg).findByText('거절하지 못했습니다')).toBeInTheDocument();
    expect(within(dlg).getByRole('button', { name: '거절' })).toBeEnabled();
    expect(signupRequestsApi.list).toHaveBeenCalledTimes(1);
  });

  it('reject 409 는 안내 후 다이얼로그를 닫고 가입 요청을 다시 읽는다', async () => {
    mock(signupRequestsApi.reject).mockRejectedValue(new ApiError(409, 'C409', 'conflict'));
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '거절…' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '거절' }));
    expect(await screen.findByText('이미 처리된 요청입니다.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    await waitFor(() => expect(signupRequestsApi.list).toHaveBeenCalledTimes(2));
  });
```

220–221행:
```tsx
    const goneRow = within(table()).getByText('gone@globex.com').closest('tr')!;
    expect(within(goneRow).getByText('2개 RP')).toBeInTheDocument();
```
→
```tsx
    const goneRow = within(table()).getByText('gone@globex.com').closest('tr')!;
    expect(within(goneRow).getByText('Globex')).toBeInTheDocument();
    expect(within(goneRow).getByText('외 1개')).toBeInTheDocument();
```

파일 끝에 새 describe 추가:
```tsx
describe('AdminUsersTab V3 — 로드 상태 · Tenant 이름', () => {
  beforeEach(() => { mock(adminUsersApi.list).mockResolvedValue(USERS); });
  const rowOf = (email: string) => within(screen.getByRole('table')).getByText(email).closest('tr')!;

  it('로딩 중에는 Skeleton(“Loading…” 텍스트 없음)', () => {
    mock(adminUsersApi.list).mockReturnValue(new Promise(() => {}));
    renderTab();
    expect(screen.getByRole('status', { name: '운영자 목록 불러오는 중' })).toHaveAttribute('aria-busy', 'true');
    expect(screen.queryByText('Loading…')).toBeNull();
  });

  it('운영자 목록 실패 → LoadError, 건수 "—", 검색 비활성, 다시 시도로 복구', async () => {
    mock(adminUsersApi.list).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    expect(await screen.findByText('운영자 목록을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.getByText('콘솔 운영자').parentElement).toHaveTextContent('—');
    expect(screen.getByRole('textbox', { name: '운영자 검색' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('ops@crosscert.com')).toBeInTheDocument();
    expect(screen.getByText('3명 · 활성 2명')).toBeInTheDocument();
  });

  it('가입 요청 실패 → LoadError, 대기 "—" (“없음”으로 보이지 않음)', async () => {
    mock(signupRequestsApi.list).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    expect(await screen.findByText('가입 요청을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText('대기 —')).toBeInTheDocument();
    expect(screen.queryByText('대기 중인 요청이 없습니다')).toBeNull();
  });

  it('Tenant 열: tenants 목록과 조인한 이름, 여러 개면 "외 N개", PLATFORM 은 "전체 tenant"', async () => {
    renderTab();
    await screen.findByText('rp@acme.com');
    expect(within(rowOf('rp@acme.com')).getByText('Acme')).toBeInTheDocument();
    expect(within(rowOf('gone@globex.com')).getByText('Globex')).toBeInTheDocument();
    expect(within(rowOf('gone@globex.com')).getByText('외 1개')).toBeInTheDocument();
    expect(within(rowOf('ops@crosscert.com')).getByText('전체 tenant')).toBeInTheDocument();
  });

  it('tenants 목록을 못 읽으면 오류 없이 id 꼬리로 표시', async () => {
    mock(tenantsApi.list).mockRejectedValueOnce(new Error('x'));
    renderTab();
    await screen.findByText('rp@acme.com');
    expect(within(rowOf('rp@acme.com')).getByText('…t1')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/settings/AdminUsersTab.test.tsx`
Expected: FAIL — `거절…` 버튼 없음, `가입 요청을 거절할까요?` 다이얼로그 없음, `Globex`/`외 1개` 없음, `운영자 목록을 불러오지 못했습니다` 없음, Skeleton 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/settings/AdminUsersTab.tsx` 전체를 아래로 교체:
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
import { useLoad } from '@/lib/useLoad';
import {
  Badge, Button, ChoiceCard, ConfirmDialog, DataTable, Dialog, EmptyState, LoadError, Segmented, Skeleton, StatusBadge, useToast, type Column,
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

/** Tenant 열. 운영자 응답에는 tenant 이름이 없어(스펙 §8) tenants 목록과 조인한다. 목록을 못 읽으면 id 꼬리로. */
function TenantCell({ u, names }: { u: AdminUserView; names: Record<string, string> }) {
  if (u.role === 'PLATFORM_OPERATOR') return <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>전체 tenant</span>;
  const ids = u.tenantIds ?? [];
  if (ids.length === 0) return <span className="faint">—</span>;
  const first = names[ids[0]];
  return (
    <div className="col" style={{ minWidth: 0 }}>
      {first
        ? <span className="truncate" style={{ fontSize: 13 }} title={ids[0]}>{first}</span>
        : <span className="mono" style={{ fontSize: 12 }} title={ids[0]}>…{tail(ids[0], 10)}</span>}
      {ids.length > 1 && <span className="hint" style={CAPTION}>외 {ids.length - 1}개</span>}
    </div>
  );
}

// ── AdminUsersTab ─────────────────────────────────────────────────────────────

export default function AdminUsersTab({ onPendingCountChange }: { onPendingCountChange?: (n: number) => void } = {}) {
  const users = useLoad(() => adminUsersApi.list(), []);
  const requests = useLoad(() => signupRequestsApi.list(), []);
  // 이름 조인용 — 실패해도 id 꼬리로 보이므로 오류 화면을 띄우지 않는다.
  const tenants = useLoad(() => tenantsApi.list(), []);
  const [approving, setApproving] = useState<SignupRequestView | null>(null);
  const [rejecting, setRejecting] = useState<SignupRequestView | null>(null);
  const [filter, setFilter] = useState<OperatorFilter>('all');
  const [query, setQuery] = useState('');
  const toast = useToast();

  useEffect(() => {
    if (requests.data) onPendingCountChange?.(requests.data.length);
  }, [requests.data]); // eslint-disable-line react-hooks/exhaustive-deps

  function reloadAll() {
    users.reload();
    requests.reload();
  }

  async function handleApprove(req: SignupRequestView, body: { role: string; tenantIds: string[] }) {
    try {
      await signupRequestsApi.approve(req.id, body);
      setApproving(null);
      toast({ kind: 'ok', title: '가입 요청을 승인했습니다.', message: `${req.email} · 즉시 로그인 가능` });
      reloadAll();
    } catch (e: unknown) {
      if (e instanceof ApiError && e.status === 409) {
        toast({ kind: 'warn', title: '이미 처리된 요청입니다.', message: '다른 관리자가 먼저 처리했습니다.' });
        setApproving(null);
        reloadAll();
      } else {
        toast({ kind: 'err', title: '승인 실패', message: errMsg(e) });
      }
    }
  }

  /** L1 확인 후 실행. 409(다른 관리자가 먼저 처리)는 안내 후 닫고, 그 밖의 실패는 throw 해 다이얼로그 안에 보인다. */
  async function confirmReject(req: SignupRequestView) {
    try {
      await signupRequestsApi.reject(req.id);
      toast({ kind: 'ok', title: '가입 요청을 거절했습니다.', message: req.email });
    } catch (e: unknown) {
      if (!(e instanceof ApiError && e.status === 409)) throw e;
      toast({ kind: 'warn', title: '이미 처리된 요청입니다.', message: '다른 관리자가 먼저 처리했습니다.' });
    }
    requests.reload();
  }

  async function handleSuspend(u: AdminUserView) {
    try {
      await adminUsersApi.suspend(u.id);
      toast({ kind: 'warn', title: '운영자가 정지되었습니다.', message: u.email });
      users.reload();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '정지 실패', message: errMsg(e) });
    }
  }

  async function handleActivate(u: AdminUserView) {
    try {
      await adminUsersApi.activate(u.id);
      toast({ kind: 'ok', title: '운영자가 재활성화되었습니다.', message: u.email });
      users.reload();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '활성화 실패', message: errMsg(e) });
    }
  }

  const names: Record<string, string> = {};
  for (const t of tenants.data ?? []) names[t.id] = t.name;
  const list = users.data ?? [];
  const activeCount = list.filter((u) => u.status === 'ACTIVE').length;
  const q = query.trim().toLowerCase();
  const visible = list.filter((u) => matchesOperator(u, filter, q));

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
    { key: 'tenant', header: 'Tenant', render: (u) => <TenantCell u={u} names={names} /> },
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

  const pending = requests.data;

  return (
    <div className="stack-6">
      {/* ── 가입 요청 ── */}
      {requests.status === 'failed' ? (
        <section className="card" aria-label="가입 요청">
          <div className="card__head">
            <h2 className="card__title">가입 요청</h2>
            <Badge>대기 —</Badge>
          </div>
          <LoadError compact what="가입 요청" error={requests.error} onRetry={requests.reload} retrying={requests.reloading} />
        </section>
      ) : !pending ? (
        <section className="card" aria-label="가입 요청">
          <div className="card__head"><h2 className="card__title">가입 요청</h2></div>
          <Skeleton rows={2} label="가입 요청 불러오는 중" />
        </section>
      ) : pending.length === 0 ? (
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
                <Badge tone="warn">대기 {pending.length}건</Badge>
              </div>
              <p className="card__sub">로그인 화면에서 들어온 관리자 계정 요청. 승인 시 역할과 tenant 를 지정합니다.</p>
            </div>
          </div>
          <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
            {pending.map((r, i) => (
              <li key={r.id} className="row" style={{ gap: 14, padding: '14px 20px', borderBottom: i < pending.length - 1 ? '1px solid var(--line-soft)' : 0 }}>
                <span className="avatar" style={WARN_AVATAR} aria-hidden="true">{initial(r.email)}</span>
                <div className="col" style={{ flex: '1 1 260px', minWidth: 0 }}>
                  <strong style={{ fontSize: 13 }}>{r.email}</strong>
                  <span className="truncate" style={{ fontSize: 13, color: 'var(--ink-2)' }} title={r.reason ?? undefined}>
                    {r.reason ?? '요청 사유 없음'}
                  </span>
                </div>
                <span className="hint" style={CAPTION}>{timeAgo(r.requestedAt)}</span>
                <div className="row" style={{ gap: 6 }}>
                  <Button onClick={() => setRejecting(r)}>거절…</Button>
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
            <span className="hint" style={CAPTION}>{users.data ? `${list.length}명 · 활성 ${activeCount}명` : '—'}</span>
          </div>
          <div className="spacer" />
          <Segmented ariaLabel="역할" options={FILTERS} value={filter} onChange={setFilter} />
          <div className="search" style={{ flex: '0 1 220px' }}>
            <Icons.Search size={15} />
            <input className="input" placeholder="이메일 · ID" aria-label="운영자 검색" value={query} disabled={!users.data} onChange={(e) => setQuery(e.target.value)} />
          </div>
        </div>
        {users.status === 'failed' ? (
          <LoadError what="운영자 목록" error={users.error} onRetry={users.reload} retrying={users.reloading} />
        ) : !users.data ? (
          <Skeleton rows={5} label="운영자 목록 불러오는 중" />
        ) : (
          <DataTable
            columns={columns}
            rows={visible}
            rowKey={(u) => u.id}
            empty={<EmptyState compact illustration={illustrations.noResults} title="조건에 맞는 운영자가 없습니다." />}
          />
        )}
      </section>

      {approving && (
        <ApproveDialog
          request={approving}
          onClose={() => setApproving(null)}
          onApprove={(body) => handleApprove(approving, body)}
        />
      )}

      <ConfirmDialog
        open={rejecting !== null}
        onClose={() => setRejecting(null)}
        level="L1"
        title="가입 요청을 거절할까요?"
        verb="거절"
        onConfirm={() => confirmReject(rejecting!)}
      >
        {rejecting && (
          <p className="card__sub" style={{ margin: 0 }}>{rejecting.email}의 요청을 거절합니다. 요청자는 다시 가입 요청을 보낼 수 있습니다.</p>
        )}
      </ConfirmDialog>
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
        // 임의 tenant 를 미리 선택하지 않는다 — 운영자가 접근 권한을 줄 tenant 를 직접 고른다.
        setTenants(list.filter((t) => t.status === 'ACTIVE'));
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

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/settings/AdminUsersTab.test.tsx src/pages/SettingsPage.test.tsx`
Expected: PASS (기존 + 신규 전부)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/settings/AdminUsersTab.tsx admin-ui/src/pages/settings/AdminUsersTab.test.tsx
git commit -m "feat(admin-ui): Admin 사용자 탭 로드 실패·로딩 상태, Tenant 이름 조인, 가입 거절 L1 확인

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 40: Admin 사용자 — 운영자 정지 L1 + 되돌리기, 본인·마지막 PLATFORM 보호, 처리 중

**Files:**
- Modify: `admin-ui/src/pages/settings/AdminUsersTab.tsx` (Task 39 결과 기준)
- Modify: `admin-ui/src/pages/SettingsPage.tsx:65`
- Test: `admin-ui/src/pages/settings/AdminUsersTab.test.tsx`

**Interfaces:**
- Consumes: `ConfirmDialog` L1 (Task 4), `ToastInput.action` (Task 5), `eulReul` / `describeError` (Task 1), `adminUsersApi.suspend/activate(id): Promise<void>`(기존)
- Produces: `AdminUsersTab` props 가 `{ me: Pick<Me, 'email'>; onPendingCountChange?: (n: number) => void }` 로 바뀜(`me` 필수).

정지 보호(본인·마지막 활성 PLATFORM)는 서버가 409 를 주지 않으므로(스펙 §8) 프론트에서 미리 막는다. 되돌리기 = 기존 `activate` API.

- [ ] **Step 1: 실패하는 테스트 작성 + 바뀐 기대값 수정**

`admin-ui/src/pages/settings/AdminUsersTab.test.tsx`:

3행 import 를 교체(`act` 추가):
```tsx
import { render, screen, fireEvent, waitFor, cleanup, within, act } from '@testing-library/react';
```

55–57행 `renderTab` 을 교체:
```tsx
function renderTab(props: { me?: { email: string }; onPendingCountChange?: (n: number) => void } = {}) {
  return render(<ToastHost><AdminUsersTab me={{ email: 'me@crosscert.com' }} {...props} /></ToastHost>);
}
```

`'정지·활성화 액션'` 테스트(268–278행)의 앞부분을 교체:
```tsx
  it('정지·활성화 액션', async () => {
    renderTab();
    await waitFor(() => screen.getByText('rp@acme.com'));
    const rpRow = within(table()).getByText('rp@acme.com').closest('tr')!;
    fireEvent.click(within(rpRow).getByRole('button', { name: '정지…' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '정지' }));
    await waitFor(() => expect(adminUsersApi.suspend).toHaveBeenCalledWith('u-rp-000000002'));
    const goneRow = within(table()).getByText('gone@globex.com').closest('tr')!;
    fireEvent.click(within(goneRow).getByRole('button', { name: '활성화' }));
    await waitFor(() => expect(adminUsersApi.activate).toHaveBeenCalledWith('u-rp-000000003'));
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledTimes(3));
  });
```

파일 끝에 새 describe 추가:
```tsx
describe('AdminUsersTab V3 — 운영자 정지', () => {
  beforeEach(() => { mock(adminUsersApi.list).mockResolvedValue(USERS); });
  const rowOf = (email: string) => within(screen.getByRole('table')).getByText(email).closest('tr')!;

  it('정지… → L1 확인(기본 포커스 취소) → 정지 → 되돌리기 토스트 → 기존 활성화 API', async () => {
    renderTab();
    await screen.findByText('rp@acme.com');
    fireEvent.click(within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' }));
    expect(adminUsersApi.suspend).not.toHaveBeenCalled();
    const dlg = screen.getByRole('alertdialog', { name: 'rp@acme.com을 정지할까요?' });
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    expect(within(dlg).getByText('Acme')).toBeInTheDocument();
    expect(within(dlg).getByText(/언제든 되돌릴 수 있습니다/)).toBeInTheDocument();
    fireEvent.click(within(dlg).getByRole('button', { name: '정지' }));
    await waitFor(() => expect(adminUsersApi.suspend).toHaveBeenCalledWith('u-rp-000000002'));
    expect(await screen.findByText('rp@acme.com 운영자를 정지했습니다.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    fireEvent.click(screen.getByRole('button', { name: '되돌리기' }));
    await waitFor(() => expect(adminUsersApi.activate).toHaveBeenCalledWith('u-rp-000000002'));
    expect(await screen.findByText('rp@acme.com 운영자의 정지를 되돌렸습니다.')).toBeInTheDocument();
  });

  it('정지 실패는 다이얼로그 안 인라인 오류, 다이얼로그 유지, 되돌리기 토스트 없음', async () => {
    mock(adminUsersApi.suspend).mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    await screen.findByText('rp@acme.com');
    fireEvent.click(within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' }));
    const dlg = screen.getByRole('alertdialog', { name: 'rp@acme.com을 정지할까요?' });
    fireEvent.click(within(dlg).getByRole('button', { name: '정지' }));
    expect(await within(dlg).findByText('정지하지 못했습니다')).toBeInTheDocument();
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '되돌리기' })).toBeNull();
  });

  it('본인 계정과 마지막 활성 PLATFORM 운영자는 정지 버튼 비활성 + 사유', async () => {
    renderTab({ me: { email: 'rp@acme.com' } });
    await screen.findByText('rp@acme.com');
    const self = within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' });
    expect(self).toBeDisabled();
    expect(self).toHaveAccessibleDescription('본인 계정은 정지할 수 없음');
    const last = within(rowOf('ops@crosscert.com')).getByRole('button', { name: '정지…' });
    expect(last).toBeDisabled();
    expect(last).toHaveAccessibleDescription('마지막 활성 PLATFORM 운영자');
  });

  it('활성 PLATFORM 운영자가 둘 이상이면 PLATFORM 도 정지할 수 있다', async () => {
    mock(adminUsersApi.list).mockResolvedValue([...USERS, { ...USERS[0], id: 'u-platform-0002', email: 'ops2@crosscert.com' }]);
    renderTab();
    await screen.findByText('ops2@crosscert.com');
    expect(within(rowOf('ops@crosscert.com')).getByRole('button', { name: '정지…' })).toBeEnabled();
  });

  it('활성화 처리 중에는 버튼이 "활성화 중…"으로 비활성', async () => {
    let resolve!: () => void;
    mock(adminUsersApi.activate).mockReturnValue(new Promise<void>((r) => { resolve = r; }));
    renderTab();
    await screen.findByText('gone@globex.com');
    fireEvent.click(within(rowOf('gone@globex.com')).getByRole('button', { name: '활성화' }));
    expect(within(rowOf('gone@globex.com')).getByRole('button', { name: '활성화 중…' })).toBeDisabled();
    await act(async () => { resolve(); });
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/settings/AdminUsersTab.test.tsx`
Expected: FAIL — `정지…` 버튼 없음(현재 "정지"는 즉시 실행), 확인 다이얼로그·되돌리기 없음, `me` prop 타입 오류는 vitest 에서는 무시되지만 `tsc -b` 에서 실패.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/settings/AdminUsersTab.tsx` (Task 39 결과) 수정:

(1) import — `import { ApiError } from '@/api/types';` 를 교체하고 두 줄 추가:
```tsx
import { ApiError, type Me } from '@/api/types';
import { eulReul } from '@/lib/josa';
import { describeError } from '@/lib/describeError';
```

(2) 컴포넌트 시그니처와 상태 — 아래 블록을
```tsx
export default function AdminUsersTab({ onPendingCountChange }: { onPendingCountChange?: (n: number) => void } = {}) {
```
다음으로 교체:
```tsx
export default function AdminUsersTab({ me, onPendingCountChange }: { me: Pick<Me, 'email'>; onPendingCountChange?: (n: number) => void }) {
```
그리고 `const [rejecting, setRejecting] = useState<SignupRequestView | null>(null);` 다음 줄에 추가:
```tsx
  const [suspending, setSuspending] = useState<AdminUserView | null>(null);
  // 정지·활성화·되돌리기 처리 중인 운영자 id — 행 버튼을 "… 중…"으로 비활성.
  const [busyId, setBusyId] = useState<string | null>(null);
```

(3) `handleSuspend` 와 `handleActivate` 두 함수 전체(Task 39 의 `async function handleSuspend(u: AdminUserView) { … }` 부터 `async function handleActivate(u: AdminUserView) { … }` 끝까지)를 교체:
```tsx
  /** L1 확인 후 실행. 실패는 throw 해 다이얼로그 안에 보이고, 성공하면 되돌리기(= 활성화) 토스트. */
  async function confirmSuspend(u: AdminUserView) {
    setBusyId(u.id);
    try {
      await adminUsersApi.suspend(u.id);
    } finally {
      setBusyId(null);
    }
    toast({
      kind: 'ok',
      title: `${u.email} 운영자를 정지했습니다.`,
      message: '다음 요청부터 콘솔에 접근할 수 없습니다.',
      action: { label: '되돌리기', onClick: () => void undoSuspend(u) },
    });
    users.reload();
  }

  async function undoSuspend(u: AdminUserView) {
    setBusyId(u.id);
    try {
      await adminUsersApi.activate(u.id);
      toast({ kind: 'ok', title: `${u.email} 운영자의 정지를 되돌렸습니다.` });
    } catch (e: unknown) {
      toast({ kind: 'err', title: '정지를 되돌리지 못했습니다', message: `${describeError(e).title}. 목록에서 “활성화”를 눌러 다시 시도하세요.` });
    } finally {
      setBusyId(null);
    }
    users.reload();
  }

  async function handleActivate(u: AdminUserView) {
    setBusyId(u.id);
    try {
      await adminUsersApi.activate(u.id);
      toast({ kind: 'ok', title: '운영자를 다시 활성화했습니다.', message: u.email });
      users.reload();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '활성화하지 못했습니다', message: describeError(e).title });
    } finally {
      setBusyId(null);
    }
  }
```
(`errMsg` 는 `handleApprove` 가 계속 쓴다.)

(4) `const visible = list.filter(...)` 다음 줄에 추가:
```tsx
  // 서버는 본인·마지막 PLATFORM 정지를 409 로 막지 않는다(스펙 §8) — 프론트에서 미리 비활성화하고 이유를 보인다.
  const activePlatform = list.filter((u) => u.role === 'PLATFORM_OPERATOR' && u.status === 'ACTIVE').length;
  function suspendBlock(u: AdminUserView): string | null {
    if (u.email.toLowerCase() === me.email.toLowerCase()) return '본인 계정은 정지할 수 없음';
    if (u.role === 'PLATFORM_OPERATOR' && u.status === 'ACTIVE' && activePlatform <= 1) return '마지막 활성 PLATFORM 운영자';
    return null;
  }
```

(5) 액션 열 정의 전체
```tsx
    {
      key: 'act', header: '액션',
      render: (u) =>
        u.status === 'ACTIVE' ? <Button size="sm" variant="danger" onClick={() => void handleSuspend(u)}>정지</Button>
        : u.status === 'SUSPENDED' ? <Button size="sm" onClick={() => void handleActivate(u)}>활성화</Button>
        : null,
    },
```
를 교체:
```tsx
    {
      key: 'act', header: '액션',
      render: (u) => {
        if (busyId === u.id) {
          return <Button size="sm" disabled>{u.status === 'ACTIVE' ? '정지 중…' : '활성화 중…'}</Button>;
        }
        if (u.status === 'SUSPENDED') return <Button size="sm" onClick={() => void handleActivate(u)}>활성화</Button>;
        if (u.status !== 'ACTIVE') return null;
        const why = suspendBlock(u);
        if (why) {
          const id = `suspend-why-${u.id}`;
          return (
            <div className="col" style={{ gap: 2, alignItems: 'flex-start' }}>
              <Button size="sm" disabled aria-describedby={id}>정지…</Button>
              <span id={id} className="hint" style={CAPTION}>{why}</span>
            </div>
          );
        }
        return <Button size="sm" variant="danger" onClick={() => setSuspending(u)}>정지…</Button>;
      },
    },
```

(6) 거절 `ConfirmDialog` 닫는 태그 `</ConfirmDialog>` 바로 다음(`</div>` 앞)에 추가:
```tsx
      <ConfirmDialog
        open={suspending !== null}
        onClose={() => setSuspending(null)}
        level="L1"
        title={suspending ? `${suspending.email}${eulReul(suspending.email)} 정지할까요?` : ''}
        verb="정지"
        onConfirm={() => confirmSuspend(suspending!)}
      >
        {suspending && (
          <>
            <dl className="kv" style={{ gridTemplateColumns: '80px minmax(0, 1fr)' }}>
              <dt>이메일</dt>
              <dd>{suspending.email}</dd>
              <dt>역할</dt>
              <dd className="row" style={{ gap: 6 }}>
                {suspending.role === 'PLATFORM_OPERATOR' ? 'PLATFORM' : 'RP_ADMIN'} · <TenantCell u={suspending} names={names} />
              </dd>
            </dl>
            <p className="card__sub" style={{ margin: 0 }}>다음 요청부터 콘솔에 접근할 수 없고, 다시 로그인할 수도 없습니다.</p>
            <p className="hint" style={{ margin: 0 }}>계정과 기록은 남습니다. 목록의 “활성화”로 언제든 되돌릴 수 있습니다.</p>
          </>
        )}
      </ConfirmDialog>
```

`admin-ui/src/pages/SettingsPage.tsx` 65행:
```tsx
      {platform && tab === 'admins' && <AdminUsersTab onPendingCountChange={setPending} />}
```
→
```tsx
      {platform && tab === 'admins' && <AdminUsersTab me={me} onPendingCountChange={setPending} />}
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/settings/AdminUsersTab.test.tsx src/pages/SettingsPage.test.tsx && npx tsc -b`
Expected: PASS, 타입 에러 0

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/settings/AdminUsersTab.tsx admin-ui/src/pages/settings/AdminUsersTab.test.tsx admin-ui/src/pages/SettingsPage.tsx
git commit -m "feat(admin-ui): 운영자 정지에 L1 확인과 되돌리기 추가, 본인·마지막 PLATFORM 운영자 정지 버튼 비활성화

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 41: MDS — 신뢰 모드 라벨, 값 없음 "—", 한국어화, LoadError

**Files:**
- Modify: `admin-ui/src/i18n/labels.ts` (끝에 추가)
- Modify: `admin-ui/src/pages/settings/MdsStatusTab.tsx` (전체 교체)
- Test: `admin-ui/src/pages/settings/MdsStatusTab.test.tsx`

**Interfaces:**
- Consumes: `useLoad`, `LoadError`, `Skeleton`
- Produces: `MDS_TRUST_MODES: Record<string, { label: string; description: string }>` (`@/i18n/labels`)

- [ ] **Step 1: 실패하는 테스트 작성 + 바뀐 기대값 수정**

`admin-ui/src/pages/settings/MdsStatusTab.test.tsx`:

11행 다음에 추가:
```tsx
import { ApiError } from '@/api/types';
```

63행:
```tsx
    expect(screen.getByText('MDS_STRICT_OPTIONAL')).toHaveClass('badge--info');
```
→
```tsx
    expect(screen.getByText('MDS 확인 권장')).toHaveClass('badge--info');
```

104행:
```tsx
    expect(screen.getByText('알 수 없음')).toBeInTheDocument();
```
→
```tsx
    expect(screen.getByText('다음 갱신 예정').nextElementSibling).toHaveTextContent('—');
```

파일 끝에 추가:
```tsx
describe('MdsStatusTab V3', () => {
  it('신뢰 모드는 사람이 읽는 라벨 + 설명, 원문 enum 은 툴팁에만', async () => {
    renderTab();
    const badge = await screen.findByText('MDS 확인 권장');
    expect(badge.closest('[title]')).toHaveAttribute('title', 'MDS_STRICT_OPTIONAL');
    expect(screen.getByText('MDS 에 없는 인증기도 등록은 허용하고, 폐기·취약 판정된 인증기는 거부합니다.')).toBeInTheDocument();
    expect(screen.queryByText('MDS_STRICT_OPTIONAL')).toBeNull();
  });

  it('모르는 신뢰 모드는 원문 그대로(설명 없음)', async () => {
    mock(mdsStatusApi.get).mockResolvedValue({ ...status, trustMode: 'CUSTOM_MODE' });
    renderTab();
    expect(await screen.findByText('CUSTOM_MODE')).toHaveClass('badge--info');
  });

  it('서버가 값을 주지 않으면 "—" — 기본값을 지어내지 않는다', async () => {
    mock(mdsStatusApi.get).mockResolvedValue({ ...status, trustMode: null, nextUpdate: null, successRate30d: null, trustAnchorCount: null } as unknown as MdsStatus);
    renderTab();
    expect(await screen.findByText('설정되지 않음')).toBeInTheDocument();
    expect(screen.queryByText('MDS_STRICT_OPTIONAL')).toBeNull();
    expect(screen.getByText('다음 갱신 예정').nextElementSibling).toHaveTextContent('—');
    expect(screen.getByText('동기화 성공률 (30일)').nextElementSibling).toHaveTextContent('—');
    expect(screen.getByText('신뢰 앵커 (Trust anchors)').closest('.kpi')).toHaveTextContent('—');
    expect(screen.queryByText('0%')).toBeNull();
  });

  it('영어 잔재 "next 갱신 예정" 대신 "다음 갱신 예정 … KST"', async () => {
    renderTab();
    expect(await screen.findByText('다음 갱신 예정')).toBeInTheDocument();
    expect(screen.queryByText(/next/)).toBeNull();
    expect(screen.getByText('다음 갱신 예정').nextElementSibling).toHaveTextContent('2026-10-12 03:00 KST');
  });

  it('로드 실패는 LoadError(다시 시도 + 오류 정보 복사), 토스트 없음', async () => {
    mock(mdsStatusApi.get).mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('MDS 상태를 불러오지 못했습니다');
    expect(within(alert).getByRole('button', { name: '오류 정보 복사' })).toBeInTheDocument();
    expect(document.querySelector('.toast')).toBeNull();
  });

  it('로딩 중에는 Skeleton', () => {
    mock(mdsStatusApi.get).mockReturnValue(new Promise(() => {}));
    renderTab();
    expect(screen.getByRole('status', { name: 'MDS 상태 불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText('MDS 상태 로딩 중…')).toBeNull();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/settings/MdsStatusTab.test.tsx`
Expected: FAIL — `MDS 확인 권장` 없음, `다음 갱신 예정` 없음(현재 "next 갱신 예정"), `오류 정보 복사` 없음, Skeleton 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/i18n/labels.ts` 끝에 추가:
```ts

/** MDS 신뢰 모드 enum → 사람이 읽는 라벨 + 설명. 원문 enum 은 화면에서 title 툴팁으로만 보인다. 모르는 값은 원문 그대로. */
export const MDS_TRUST_MODES: Record<string, { label: string; description: string }> = {
  MDS_STRICT_OPTIONAL: {
    label: 'MDS 확인 권장',
    description: 'MDS 에 없는 인증기도 등록은 허용하고, 폐기·취약 판정된 인증기는 거부합니다.',
  },
};
```

`admin-ui/src/pages/settings/MdsStatusTab.tsx` 전체를 아래로 교체:
```tsx
import { useState, ReactNode } from 'react';
import { Icons } from '@/icons/Icons';
import { kstDateTimeMin } from '@/lib/kst';
import { useLoad } from '@/lib/useLoad';
import { MDS_TRUST_MODES } from '@/i18n/labels';
import { mdsStatusApi, MDS_HISTORY_STRIP_LIMIT, type MdsStatus, type MdsHistoryRow } from '@/api/mdsStatus';
import {
  Badge, Button, CardBody, CardHeader, DataTable, KpiCard, LoadError, Skeleton, StatusStrip, useToast, type BadgeTone, type Column,
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
  return iso ? kstDateTimeMin(iso) : '—';
}

/** 서버 시드(mds_blob_cache: version 0, 1970-01-01)는 "한 번도 동기화되지 않음"이다. 2000년 이전 시각은 시드로 본다. */
const SEED_CUTOFF_MS = Date.UTC(2000, 0, 1);
function isSeedTime(iso: string | null | undefined): boolean {
  if (!iso) return true;
  const t = new Date(iso).getTime();
  return Number.isNaN(t) || t < SEED_CUTOFF_MS;
}
function isNeverSynced(s: MdsStatus): boolean {
  return s.version === 0 || isSeedTime(s.fetchedAt);
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
  { key: 'at', header: '시각 (KST)', render: (r) => <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{fmtDateTime(r.startedAt)}</span> },
  { key: 'ver', header: '버전', render: (r) => <span className="mono" style={{ fontSize: 12 }}>{r.version != null ? `#${r.version}` : '—'}</span> },
  { key: 'change', header: '변경', render: (r) => <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{r.changeSummary ?? '—'}</span> },
  { key: 'status', header: '상태', render: (r) => <Badge tone={RUN[r.status].tone}>{RUN[r.status].label}</Badge> },
  { key: 'ms', header: '응답 시간', align: 'right', render: (r) => <span className="mono" style={{ fontSize: 12 }}>{r.durationMs != null ? `${r.durationMs}ms` : '—'}</span> },
];

type MdsData = { status: MdsStatus; history: MdsHistoryRow[] };

// 상태와 이력을 함께 읽고 둘 다 끝난 뒤에 그린다 — 이력이 늦게 와서 '정상' 헤드라인이 먼저 보이는 일을 막는다.
// 상태 조회 실패는 로드 실패, 이력 조회 실패는 빈 이력으로 본다.
async function fetchMds(): Promise<MdsData> {
  const [st, hist] = await Promise.allSettled([mdsStatusApi.get(), mdsStatusApi.history(MDS_HISTORY_STRIP_LIMIT)]);
  if (st.status === 'rejected') throw st.reason;
  return { status: st.value, history: hist.status === 'fulfilled' ? hist.value : [] };
}

/** 신뢰 모드: 사람이 읽는 라벨 + 설명, 원문은 툴팁. 값이 없으면 기본값을 지어내지 않고 "—". */
function TrustMode({ mode }: { mode: string | null | undefined }) {
  if (!mode) return <span>— <span className="hint" style={{ margin: 0 }}>설정되지 않음</span></span>;
  const known = MDS_TRUST_MODES[mode];
  return (
    <span className="col" style={{ gap: 6, alignItems: 'flex-start' }}>
      <span title={mode}><Badge tone="info" dot={false}>{known ? known.label : mode}</Badge></span>
      {known && <span className="hint" style={{ margin: 0 }}>{known.description}</span>}
    </span>
  );
}

// ── MdsStatusTab ──────────────────────────────────────────────────────────────

export default function MdsStatusTab() {
  const load = useLoad(fetchMds, []);
  const [syncing, setSyncing] = useState(false);
  const toast = useToast();

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
      load.reload();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '알 수 없는 오류';
      toast({ kind: 'err', title: 'MDS 갱신 실패', message: msg });
    } finally {
      setSyncing(false);
    }
  }

  if (load.status === 'failed') {
    return (
      <section className="card">
        <LoadError what="MDS 상태" error={load.error} onRetry={load.reload} retrying={load.reloading} />
      </section>
    );
  }
  if (!load.data) {
    return <section className="card"><Skeleton rows={5} label="MDS 상태 불러오는 중" /></section>;
  }

  const { status, history } = load.data;
  // 헤드라인의 기준은 상태(status). 시드뿐이면 중립, 아니면 가장 최근 실행이 실패일 때만 실패로 표시한다.
  const neverSynced = isNeverSynced(status);
  const nextUpdate = status.nextUpdate && !isSeedTime(status.nextUpdate) ? status.nextUpdate : null;
  const versionText = !neverSynced && status.version != null ? `#${status.version}` : '—';
  const latest = history[0];
  const headTone: 'ok' | 'bad' | 'neutral' = neverSynced ? 'neutral' : latest?.status === 'FAILED' ? 'bad' : 'ok';
  const headTitle = headTone === 'bad' ? `동기화 실패 · BLOB ${versionText}` : headTone === 'neutral' ? '동기화 이력 없음' : `정상 · BLOB ${versionText}`;
  const headIcon: ReactNode = headTone === 'ok' ? <Icons.Check size={24} /> : <Icons.Alert size={24} />;
  const rate = status.successRate30d;
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
            FIDO MDS3 BLOB 을 매일 03:00 KST 에 갱신합니다{nextUpdate ? ` · 다음 갱신 ${nextUpdate} 03:00 KST` : ''}
          </span>
        </div>
        <Button onClick={() => void handleSync()} disabled={syncing} icon={<Icons.Refresh size={14} />}>
          {syncing ? '갱신 중…' : '즉시 갱신'}
        </Button>
      </section>

      <div className="kpis kpis--3">
        <KpiCard label="MDS BLOB 버전" value={<span className="mono">{versionText}</span>} />
        <KpiCard label="신뢰 앵커 (Trust anchors)" value={status.trustAnchorCount ?? '—'} />
        <KpiCard label="마지막 동기화" value={neverSynced ? '—' : timeAgo(status.fetchedAt)} />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1.4fr)', gap: 20, alignItems: 'start' }}>
        <section className="card">
          <CardHeader title="동기화 설정" />
          <CardBody>
            <dl style={DL_STYLE}>
              <dt style={DT_STYLE}>주소 (endpoint)</dt>
              <dd style={DD_STYLE} className="mono"><span style={{ fontSize: 12 }}>https://mds3.fidoalliance.org/</span></dd>
              <dt style={DT_STYLE}>다음 갱신 예정</dt>
              <dd style={DD_STYLE}>{nextUpdate ? `${nextUpdate} 03:00 KST` : '—'}</dd>
              <dt style={DT_STYLE}>동기화 성공률 (30일)</dt>
              <dd style={DD_STYLE}>
                {rate && rate.total > 0
                  ? <><span>{Math.round((rate.ok / rate.total) * 100)}%</span><span className="muted"> · {rate.ok} / {rate.total}</span></>
                  : '—'}
              </dd>
              <dt style={DT_STYLE}>신뢰 모드</dt>
              <dd style={DD_STYLE}><TrustMode mode={status.trustMode} /></dd>
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

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/settings/MdsStatusTab.test.tsx src/i18n/labels.test.ts`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/i18n/labels.ts admin-ui/src/pages/settings/MdsStatusTab.tsx admin-ui/src/pages/settings/MdsStatusTab.test.tsx
git commit -m "fix(admin-ui): MDS 탭 신뢰 모드를 사람이 읽는 라벨로, 값 없음은 —, 다음 갱신 예정 한국어화, 로드 실패는 LoadError

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 42: 시스템 — 가동 시간 "[N]일", 배포 시각 KST, LoadError

**Files:**
- Modify: `admin-ui/src/pages/settings/SystemInfoTab.tsx` (전체 교체)
- Test: `admin-ui/src/pages/settings/SystemInfoTab.test.tsx`

**Interfaces:**
- Consumes: `useLoad`, `LoadError`, `Skeleton`, `kstDateTimeMin(iso: string): string`(기존 `@/lib/kst`)
- Produces: 없음

- [ ] **Step 1: 실패하는 테스트 작성 + 바뀐 기대값 수정**

`admin-ui/src/pages/settings/SystemInfoTab.test.tsx`:

39행과 42행:
```tsx
    expect(screen.getByText('2026-05-31 배포')).toBeInTheDocument();
```
```tsx
    expect(screen.getByText('4d')).toBeInTheDocument();
```
→
```tsx
    expect(screen.getByText('2026-05-31 09:00 KST 배포')).toBeInTheDocument();
```
```tsx
    expect(screen.getByText('4일')).toBeInTheDocument();
```

64–71행 테스트 `'shows an error message instead of loading forever when the fetch fails'` 안의
```tsx
      expect(screen.getByText('System Info 를 불러오지 못했습니다.')).toBeInTheDocument());
```
→
```tsx
      expect(screen.getByText('시스템 정보를 불러오지 못했습니다')).toBeInTheDocument());
```

120–128행 테스트 `'불러오기 실패 → 일러스트 빈 상태, 다시 시도로 재요청'` 안의 두 줄
```tsx
    await waitFor(() => expect(screen.getByText('System Info 를 불러오지 못했습니다.')).toBeInTheDocument());
```
```tsx
    await waitFor(() => expect(screen.getByText('2026-05-31 배포')).toBeInTheDocument());
```
→
```tsx
    await waitFor(() => expect(screen.getByText('시스템 정보를 불러오지 못했습니다')).toBeInTheDocument());
```
```tsx
    await waitFor(() => expect(screen.getByText('2026-05-31 09:00 KST 배포')).toBeInTheDocument());
```

파일 끝 `});` 앞에 추가:
```tsx
  it('KPI 라벨은 한국어, 영어 단위 "d" 를 쓰지 않는다', async () => {
    render(<SystemInfoTab />);
    await waitFor(() => expect(list()).toBeInTheDocument());
    expect(screen.getByText('서버 버전')).toBeInTheDocument();
    expect(screen.getByText('가동 시간')).toBeInTheDocument();
    expect(screen.queryByText('Uptime')).toBeNull();
    expect(screen.queryByText('4d')).toBeNull();
  });

  it('로드 실패는 LoadError(role=alert, 오류 정보 복사)', async () => {
    mockGet().mockRejectedValue(new Error('boom'));
    render(<SystemInfoTab />);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('시스템 정보를 불러오지 못했습니다');
    expect(within(alert).getByRole('button', { name: '오류 정보 복사' })).toBeInTheDocument();
  });

  it('로딩 중에는 Skeleton', () => {
    mockGet().mockReturnValue(new Promise(() => {}));
    render(<SystemInfoTab />);
    expect(screen.getByRole('status', { name: '시스템 정보 불러오는 중' })).toHaveAttribute('aria-busy', 'true');
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/settings/SystemInfoTab.test.tsx`
Expected: FAIL — `4일`, `… KST 배포`, `시스템 정보를 불러오지 못했습니다`, `가동 시간` 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/settings/SystemInfoTab.tsx` 전체를 아래로 교체:
```tsx
import { systemInfoApi, type SystemInfoComponent } from '@/api/systemInfo';
import { Icons } from '@/icons/Icons';
import { kstDateTimeMin } from '@/lib/kst';
import { useLoad } from '@/lib/useLoad';
import { Badge, CardBody, CardHeader, KpiCard, LoadError, Skeleton, type BadgeTone } from '@/ui';

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

const CAPTION = { margin: 0 } as const;

export default function SystemInfoTab() {
  const load = useLoad(() => systemInfoApi.get(), []);

  if (load.status === 'failed') {
    return (
      <section className="card">
        <LoadError what="시스템 정보" error={load.error} onRetry={load.reload} retrying={load.reloading} />
      </section>
    );
  }
  const info = load.data;
  if (!info) {
    return <section className="card"><Skeleton rows={6} label="시스템 정보 불러오는 중" /></section>;
  }

  const overall = overallStatus(info.components);
  const okCount = info.components.filter((c) => c.status === 'OK').length;
  const profiles = info.host.environment.split(',').map((p) => p.trim()).filter(Boolean);
  const deployed = info.deployedAt ? `${kstDateTimeMin(info.deployedAt)} KST` : null;

  return (
    <div className="stack-6">
      <div className="kpis kpis--3">
        <KpiCard
          label="전체 상태"
          value={<span className="row" style={{ gap: 8 }}><span className="dot" style={{ width: 10, height: 10, background: STATUS[overall].dot }} />{STATUS[overall].label}</span>}
          hint={`컴포넌트 ${okCount} / ${info.components.length}`}
        />
        <KpiCard
          label="서버 버전"
          value={<span className="mono" style={{ fontSize: 22 }}>{info.serverVersion}</span>}
          hint={deployed ? `${deployed} 배포` : '배포 시각 정보 없음'}
        />
        {/* 시간 단위 세분화는 서버 지원 후(스펙 §8). */}
        <KpiCard label="가동 시간" value={`${info.uptimeDays}일`} />
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
                <span className="hint mono truncate" style={{ ...CAPTION, maxWidth: '45%' }} title={c.version}>{c.version}</span>
                {c.latencyMs != null && <span className="hint" style={CAPTION}>{c.latencyMs}ms</span>}
                <Badge tone={STATUS[c.status].tone}>{STATUS[c.status].label}</Badge>
              </li>
            ))}
          </ul>
        </section>

        <section className="card">
          <CardHeader title="호스트 정보" actions={<Badge tone="info" dot={false}>환경 · <span>{info.host.environment}</span></Badge>} />
          <CardBody>
            <dl className="kv" style={{ gridTemplateColumns: '120px minmax(0, 1fr)' }}>
              <dt>활성 프로필</dt>
              <dd>
                <span className="chips">{profiles.map((p) => <span key={p} className="tag">{p}</span>)}</span>
              </dd>
              <dt>배포 시각</dt>
              <dd className="mono" style={{ fontSize: 12 }}>{deployed ?? '—'}</dd>
            </dl>
          </CardBody>
        </section>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/settings/SystemInfoTab.test.tsx`
Expected: PASS (기존 8 + 신규 3)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/settings/SystemInfoTab.tsx admin-ui/src/pages/settings/SystemInfoTab.test.tsx
git commit -m "fix(admin-ui): 시스템 탭 가동 시간을 [N]일로, 배포 시각을 KST 날짜·시각으로, 로드 실패는 LoadError

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 43: 보안 정책 입력 검증 헬퍼 (CORS origin, timeout)

**Files:**
- Create: `admin-ui/src/lib/policyValidation.ts`
- Test: `admin-ui/src/lib/policyValidation.test.ts`

**Interfaces:**
- Produces:
  - `const ORIGIN_FORMAT_ERROR: string`, `const ORIGIN_DUPLICATE_ERROR = '이미 목록에 있습니다.'`, `const TIMEOUT_ERROR: string`
  - `function validateOrigin(value: string, existing: string[]): string | null` — `https://호스트[:포트]`(경로·와일드카드·끝 슬래시 불가), `http://` 는 `localhost`·`127.0.0.1` 만. 통과하면 `null`.
  - `function timeoutError(raw: string): string | null` — 정수 1–1440 만 통과.

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/lib/policyValidation.test.ts`:
```ts
import { describe, it, expect } from 'vitest';
import { validateOrigin, timeoutError, ORIGIN_FORMAT_ERROR, ORIGIN_DUPLICATE_ERROR, TIMEOUT_ERROR } from './policyValidation';

describe('validateOrigin', () => {
  it('https://호스트[:포트] 는 통과', () => {
    expect(validateOrigin('https://admin.example.com', [])).toBeNull();
    expect(validateOrigin('https://admin.example.com:8443', [])).toBeNull();
    expect(validateOrigin('  https://a.example.com  ', [])).toBeNull();
  });
  it('스킴 누락 · 경로 · 끝 슬래시 · 와일드카드 · 잘못된 포트는 형식 오류', () => {
    for (const v of ['admin.example.com', 'https://admin.example.com/login', 'https://admin.example.com/', 'https://*.example.com', 'ftp://x.com', 'https://', 'https://a.com:99999', 'https://a.com:0']) {
      expect(validateOrigin(v, [])).toBe(ORIGIN_FORMAT_ERROR);
    }
  });
  it('http 는 localhost · 127.0.0.1 만 허용', () => {
    expect(validateOrigin('http://localhost:5173', [])).toBeNull();
    expect(validateOrigin('http://127.0.0.1:8080', [])).toBeNull();
    expect(validateOrigin('http://admin.example.com', [])).toBe(ORIGIN_FORMAT_ERROR);
  });
  it('이미 목록에 있으면 중복 오류', () => {
    expect(validateOrigin('https://a.example.com', ['https://a.example.com'])).toBe(ORIGIN_DUPLICATE_ERROR);
  });
  it('형식 오류 문구는 예시를 담는다', () => {
    expect(ORIGIN_FORMAT_ERROR).toBe('형식이 올바르지 않습니다. https:// 로 시작하고 경로 없이 입력하세요. 예: https://admin.example.com');
  });
});

describe('timeoutError', () => {
  it('1–1440 정수만 통과', () => {
    for (const v of ['1', '30', '1440', ' 45 ']) expect(timeoutError(v)).toBeNull();
    for (const v of ['0', '1441', '', '-5', '1.5', 'abc']) expect(timeoutError(v)).toBe(TIMEOUT_ERROR);
  });
  it('오류 문구', () => {
    expect(TIMEOUT_ERROR).toBe('1–1440 사이의 분을 입력하세요. 비워 둘 수 없습니다.');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/lib/policyValidation.test.ts`
Expected: FAIL — `Failed to resolve import "./policyValidation"`.

- [ ] **Step 3: 구현**

`admin-ui/src/lib/policyValidation.ts`:
```ts
// 보안 정책(설정 › 보안 정책) 입력 검증. 서버에 보내기 전에 형식 오류를 그 자리에서 알린다.

export const ORIGIN_FORMAT_ERROR = '형식이 올바르지 않습니다. https:// 로 시작하고 경로 없이 입력하세요. 예: https://admin.example.com';
export const ORIGIN_DUPLICATE_ERROR = '이미 목록에 있습니다.';
export const TIMEOUT_ERROR = '1–1440 사이의 분을 입력하세요. 비워 둘 수 없습니다.';

const ORIGIN_RE = /^(https?):\/\/([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*)(?::(\d{1,5}))?$/;
const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1']);

/** CORS origin 검증. https://호스트[:포트] 만(경로·와일드카드 불가). http 는 로컬 개발 주소만. 통과하면 null. */
export function validateOrigin(value: string, existing: string[]): string | null {
  const v = value.trim();
  const m = ORIGIN_RE.exec(v);
  if (!m) return ORIGIN_FORMAT_ERROR;
  const [, scheme, host, port] = m;
  if (scheme === 'http' && !LOCAL_HOSTS.has(host.toLowerCase())) return ORIGIN_FORMAT_ERROR;
  if (port !== undefined) {
    const n = Number(port);
    if (n < 1 || n > 65535) return ORIGIN_FORMAT_ERROR;
  }
  if (existing.includes(v)) return ORIGIN_DUPLICATE_ERROR;
  return null;
}

/** 세션 idle timeout(분) 검증. 정수 1–1440(최대 24시간)만 통과. */
export function timeoutError(raw: string): string | null {
  const v = raw.trim();
  if (!/^\d+$/.test(v)) return TIMEOUT_ERROR;
  const n = Number(v);
  return n < 1 || n > 1440 ? TIMEOUT_ERROR : null;
}
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/lib/policyValidation.test.ts`
Expected: PASS (7 tests)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/lib/policyValidation.ts admin-ui/src/lib/policyValidation.test.ts
git commit -m "feat(admin-ui): 보안 정책 CORS origin·세션 timeout 입력 검증 헬퍼 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 44: 보안 정책 — LoadError, timeout 1–1440, origin 형식, 현재 콘솔 origin 삭제 경고

**Files:**
- Modify: `admin-ui/src/pages/settings/SecurityPolicyTab.tsx` (전체 교체)
- Test: `admin-ui/src/pages/settings/SecurityPolicyTab.test.tsx`

**Interfaces:**
- Consumes: `validateOrigin`, `timeoutError` (Task 43), `useLoad`, `LoadError`, `Skeleton`, `ConfirmDialog` L2, `describeError`, `ChipInput.validate`(기존)
- Produces: 없음

- [ ] **Step 1: 실패하는 테스트 작성 + 바뀐 기대값 수정**

`admin-ui/src/pages/settings/SecurityPolicyTab.test.tsx`:

9행 다음에 추가:
```tsx
import { ApiError } from '@/api/types';
```

64–79행 테스트 `'CORS: Enter 로 추가(새 칩 강조), 중복 무시, 취소는 서버 값으로 되돌림'` 의 제목과 중복 부분을 교체 — 아래 세 줄
```tsx
  it('CORS: Enter 로 추가(새 칩 강조), 중복 무시, 취소는 서버 값으로 되돌림', async () => {
```
```tsx
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getAllByText('https://admin.example.com')).toHaveLength(1);
```
→
```tsx
  it('CORS: Enter 로 추가(새 칩 강조), 중복은 안내만, 취소는 서버 값으로 되돌림', async () => {
```
```tsx
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getAllByText('https://admin.example.com')).toHaveLength(1);
    expect(screen.getByText('이미 목록에 있습니다.')).toBeInTheDocument();
```

파일 끝에 추가:
```tsx
describe('SecurityPolicyTab V3', () => {
  it('로드 실패 → 기본값 대신 LoadError, 입력칸·저장 숨김, 다시 시도로 복구', async () => {
    mock(securityPolicyApi.get).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    renderWithDataRouter(<ToastHost><SecurityPolicyTab /></ToastHost>, { initialPath: '/settings?tab=security' });
    expect(await screen.findByText('보안 정책을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByLabelText('세션 idle timeout (분)')).toBeNull();
    expect(screen.queryByRole('switch')).toBeNull();
    expect(document.querySelector('.toast')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(screen.getByLabelText('세션 idle timeout (분)')).toHaveValue(30));
  });

  it('timeout 은 1–1440 — 범위 밖·빈 값은 인라인 오류, 저장해도 API 를 부르지 않고 입력칸으로 포커스', async () => {
    await setup();
    const input = screen.getByLabelText('세션 idle timeout (분)');
    const ERR = '1–1440 사이의 분을 입력하세요. 비워 둘 수 없습니다.';
    expect(screen.getByText('1–1440분 (최대 24시간)')).toBeInTheDocument();
    fireEvent.change(input, { target: { value: '0' } });
    expect(screen.getByText(ERR)).toBeInTheDocument();
    expect(input).toHaveAttribute('aria-invalid', 'true');
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장' }));
    expect(securityPolicyApi.update).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(input);
    fireEvent.change(input, { target: { value: '1441' } });
    expect(screen.getByText(ERR)).toBeInTheDocument();
    fireEvent.change(input, { target: { value: '' } });
    expect(screen.getByText(ERR)).toBeInTheDocument();
    fireEvent.change(input, { target: { value: '1440' } });
    expect(screen.queryByText(ERR)).toBeNull();
  });

  it('CORS origin 형식 오류면 추가하지 않고 입력값을 남긴다', async () => {
    await setup();
    const input = screen.getByRole('textbox', { name: 'origin 추가' });
    fireEvent.change(input, { target: { value: 'admin.example.com/login' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getByText('형식이 올바르지 않습니다. https:// 로 시작하고 경로 없이 입력하세요. 예: https://admin.example.com')).toBeInTheDocument();
    expect(input).toHaveValue('admin.example.com/login');
    expect(bar()).toBeNull();
    expect(screen.getByText('https://호스트[:포트] 형식. 경로 · 와일드카드(*) 불가.')).toBeInTheDocument();
  });

  it('현재 콘솔 origin 을 지우면 경고 + 삭제 되돌리기, 그래도 저장하면 L2 확인 후 저장', async () => {
    const origin = window.location.origin;
    mock(securityPolicyApi.get).mockResolvedValue({ ...view, corsAllowlist: [origin, 'https://admin.example.com'] });
    mock(securityPolicyApi.update).mockResolvedValue({ ...view, corsAllowlist: ['https://admin.example.com'] });
    await setup();
    expect(screen.queryByText(/지금 쓰고 있는 콘솔 주소입니다/)).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: `${origin} 삭제` }));
    expect(screen.getByText(/지금 쓰고 있는 콘솔 주소입니다/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '삭제 되돌리기' }));
    expect(screen.queryByText(/지금 쓰고 있는 콘솔 주소입니다/)).toBeNull();
    expect(bar()).toBeNull(); // 원래 순서로 되돌려 변경 0건

    fireEvent.click(screen.getByRole('button', { name: `${origin} 삭제` }));
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장' }));
    const dlg = screen.getByRole('alertdialog', { name: '현재 콘솔 주소를 빼고 저장할까요?' });
    expect(securityPolicyApi.update).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    fireEvent.click(within(dlg).getByRole('button', { name: '저장' }));
    await waitFor(() => expect(securityPolicyApi.update).toHaveBeenCalledWith({
      sessionIdleTimeoutMinutes: 30, mfaRequired: true, corsAllowlist: ['https://admin.example.com'],
    }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/settings/SecurityPolicyTab.test.tsx`
Expected: FAIL — 실패 시 기본값(30분)이 보이고 `보안 정책을 불러오지 못했습니다` 없음, timeout 오류 문구 없음(0 이 그대로 저장됨), origin 형식 오류 없음, 콘솔 origin 경고 없음.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/settings/SecurityPolicyTab.tsx` 전체를 아래로 교체:
```tsx
import { useState, useEffect, useRef } from 'react';
import { securityPolicyApi, type SecurityPolicyView } from '@/api/securityPolicy';
import { useLoad } from '@/lib/useLoad';
import { describeError } from '@/lib/describeError';
import { timeoutError, validateOrigin } from '@/lib/policyValidation';
import {
  Alert, Button, CardHeader, ChipInput, ConfirmDialog, LoadError, SaveBar, Skeleton, Switch, useToast, useUnsavedGuard,
} from '@/ui';

function sameList(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((x, i) => x === b[i]);
}

export default function SecurityPolicyTab() {
  const policy = useLoad(() => securityPolicyApi.get(), []);
  const [sessionDraft, setSessionDraft] = useState('');
  const [reqMfa, setReqMfa] = useState(false);
  const [corsAllowlist, setCorsAllowlist] = useState<string[]>([]);
  const [saved, setSaved] = useState<SecurityPolicyView | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirmLockout, setConfirmLockout] = useState(false);
  const timeoutRef = useRef<HTMLInputElement>(null);
  const toast = useToast();
  const consoleOrigin = window.location.origin;

  function applyView(v: SecurityPolicyView) {
    setSessionDraft(String(v.sessionIdleTimeoutMinutes));
    setReqMfa(v.mfaRequired);
    setCorsAllowlist(v.corsAllowlist);
    setSaved(v);
  }

  useEffect(() => {
    if (policy.data) applyView(policy.data);
  }, [policy.data]);

  const timeoutErr = timeoutError(sessionDraft);
  // 지금 쓰고 있는 콘솔 주소를 허용 목록에서 빼면 저장 즉시 이 콘솔이 잠긴다.
  const removesConsole = saved !== null && saved.corsAllowlist.includes(consoleOrigin) && !corsAllowlist.includes(consoleOrigin);

  /** 저장 본체. 실패는 throw — SaveBar 경로는 토스트로, 확인 다이얼로그 경로는 다이얼로그 안에서 보인다. */
  async function save(): Promise<void> {
    const v = await securityPolicyApi.update({
      sessionIdleTimeoutMinutes: Number(sessionDraft.trim()),
      mfaRequired: reqMfa,
      corsAllowlist,
    });
    applyView(v);
    toast({ kind: 'ok', title: '보안 정책 저장됨' });
  }

  async function handleSave() {
    if (timeoutErr) { timeoutRef.current?.focus(); return; }
    if (removesConsole) { setConfirmLockout(true); return; }
    setSaving(true);
    try {
      await save();
    } catch (e: unknown) {
      const info = describeError(e);
      toast({ kind: 'err', title: '보안 정책을 저장하지 못했습니다', message: `${info.title}. 입력한 내용은 그대로 있습니다.` });
    } finally {
      setSaving(false);
    }
  }

  async function handleCancel() {
    try {
      const v = await securityPolicyApi.get();
      applyView(v);
    } catch (e: unknown) {
      const info = describeError(e);
      toast({ kind: 'err', title: '되돌리지 못했습니다', message: info.title });
    }
  }

  /** 삭제한 콘솔 origin 을 원래 자리(서버 순서)로 되돌린다 — 되돌린 뒤 변경 0건이 되게. */
  function restoreConsoleOrigin() {
    if (!saved) return;
    const kept = saved.corsAllowlist.filter((o) => o === consoleOrigin || corsAllowlist.includes(o));
    const added = corsAllowlist.filter((o) => !saved.corsAllowlist.includes(o));
    setCorsAllowlist([...kept, ...added]);
  }

  const changed: string[] = [];
  if (saved) {
    if (sessionDraft.trim() !== String(saved.sessionIdleTimeoutMinutes)) changed.push('세션 idle timeout');
    if (reqMfa !== saved.mfaRequired) changed.push('MFA 필수');
    if (!sameList(corsAllowlist, saved.corsAllowlist)) changed.push('CORS Allowlist');
  }
  const guard = useUnsavedGuard(changed.length > 0);
  const addedOrigins = saved ? corsAllowlist.filter((o) => !saved.corsAllowlist.includes(o)) : [];

  // 로드 실패를 기본값(30분 · MFA 켜짐)처럼 보이지 않게 — 입력칸·저장을 숨기고 LoadError.
  if (!saved && policy.status === 'failed') {
    return (
      <section className="card">
        <LoadError what="보안 정책" error={policy.error} onRetry={policy.reload} retrying={policy.reloading} />
      </section>
    );
  }
  if (!saved) {
    return <section className="card"><Skeleton rows={6} label="보안 정책 불러오는 중" /></section>;
  }

  return (
    <div className="stack-4">
      <section className="card">
        <CardHeader title="세션 & 인증 정책" cap="모든 콘솔 운영자에게 적용" />

        <div className="set">
          <div>
            <label className="set__label" htmlFor="sec-idle-timeout" style={{ display: 'block' }}>세션 idle timeout (분)</label>
            <p className="set__desc">이 시간 동안 활동이 없으면 세션이 만료됩니다. 만료 직전 연장 모달이 뜹니다.</p>
          </div>
          <div className="stack-1">
            <div className="row" style={{ gap: 10 }}>
              <input
                ref={timeoutRef}
                id="sec-idle-timeout"
                className="input mono"
                type="number"
                inputMode="numeric"
                min={1}
                max={1440}
                step={1}
                value={sessionDraft}
                aria-invalid={timeoutErr ? true : undefined}
                aria-describedby={timeoutErr ? 'sec-idle-timeout-err' : 'sec-idle-timeout-help'}
                onChange={(e) => setSessionDraft(e.target.value)}
                style={{ maxWidth: 120 }}
              />
              <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>분</span>
            </div>
            {timeoutErr
              ? <p id="sec-idle-timeout-err" className="field__error" role="alert" style={{ margin: 0 }}>{timeoutErr}</p>
              : <p id="sec-idle-timeout-help" className="hint" style={{ margin: 0 }}>1–1440분 (최대 24시간)</p>}
          </div>
        </div>

        <div className="set">
          <div>
            <span className="set__label" style={{ display: 'block' }}>MFA 필수</span>
            <p className="set__desc">켜면 MFA 를 등록하지 않은 운영자는 다음 로그인 때 등록해야 합니다.</p>
          </div>
          <div style={{ alignSelf: 'flex-start' }}>
            <Switch checked={reqMfa} onChange={setReqMfa} label="모든 운영자에게 MFA 필수" />
          </div>
        </div>

        <div className="set">
          <div>
            <span className="set__label" style={{ display: 'block' }}>CORS Allowlist</span>
            <p className="set__desc">콘솔 API 를 호출할 수 있는 origin. Enter 로 추가합니다.</p>
          </div>
          <div className="stack-2">
            <ChipInput
              values={corsAllowlist}
              onChange={setCorsAllowlist}
              placeholder="https://… 추가 후 Enter"
              ariaLabel="origin 추가"
              mono
              addedValues={addedOrigins}
              validate={(v) => validateOrigin(v, corsAllowlist)}
            />
            <p className="hint" style={{ margin: 0 }}>https://호스트[:포트] 형식. 경로 · 와일드카드(*) 불가.</p>
            {removesConsole && (
              <Alert tone="warn" actions={<Button size="sm" onClick={restoreConsoleOrigin}>삭제 되돌리기</Button>}>
                <span className="mono">{consoleOrigin}</span> 은 지금 쓰고 있는 콘솔 주소입니다. 저장하면 이 콘솔에서 API 를 호출할 수 없어 바로 잠깁니다.
              </Alert>
            )}
          </div>
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

      <ConfirmDialog
        open={confirmLockout}
        onClose={() => setConfirmLockout(false)}
        level="L2"
        title="현재 콘솔 주소를 빼고 저장할까요?"
        verb="저장"
        onConfirm={save}
      >
        <p className="card__sub" style={{ margin: 0 }}>
          <span className="mono">{consoleOrigin}</span> 이 CORS 허용 목록에서 빠집니다. 저장하는 즉시 이 콘솔에서 API 를 호출할 수 없어 화면이 잠깁니다.
        </p>
        <p className="hint" style={{ margin: 0 }}>다른 주소의 콘솔에서 이 주소를 다시 추가해야 풀립니다.</p>
      </ConfirmDialog>
      {guard}
    </div>
  );
}
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/settings/SecurityPolicyTab.test.tsx src/lib/policyValidation.test.ts`
Expected: PASS (기존 5 + 신규 4, 헬퍼 7)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/settings/SecurityPolicyTab.tsx admin-ui/src/pages/settings/SecurityPolicyTab.test.tsx
git commit -m "fix(admin-ui): 보안 정책 로드 실패는 LoadError, timeout 1–1440·CORS origin 형식 검증, 현재 콘솔 origin 삭제 경고와 L2 확인

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 45: `useFocusTrap` — Dialog 포커스 가둠·복원을 훅으로 분리

**Files:**
- Create: `admin-ui/src/ui/useFocusTrap.ts`
- Modify: `admin-ui/src/ui/Dialog.tsx` (전체 교체), `admin-ui/src/ui/index.ts`
- Test: `admin-ui/src/ui/useFocusTrap.test.tsx`

**Interfaces:**
- Produces:
  - `const FOCUSABLE: string`
  - `function useFocusTrap(open: boolean, boxRef: RefObject<HTMLElement>, initialFocusRef?: RefObject<HTMLElement>): (e: KeyboardEvent<HTMLElement>) => void` — 열리면 `initialFocusRef`(없으면 첫 포커스 가능 요소)로 포커스, 닫히면 열기 전 요소로 복원. 반환 함수는 컨테이너 `onKeyDown` 에 거는 Tab 순환 핸들러(비활성 요소는 건너뜀).

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/ui/useFocusTrap.test.tsx`:
```tsx
import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useRef, useState } from 'react';
import { useFocusTrap } from './useFocusTrap';

function Host({ withInitial }: { withInitial?: boolean }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const second = useRef<HTMLButtonElement>(null);
  const trap = useFocusTrap(open, box, withInitial ? second : undefined);
  return (
    <>
      <button onClick={() => setOpen(true)}>열기</button>
      {open && (
        <div ref={box} onKeyDown={trap}>
          <button>하나</button>
          <button ref={second}>둘</button>
          <button disabled>꺼짐</button>
          <button onClick={() => setOpen(false)}>닫기</button>
        </div>
      )}
    </>
  );
}

function openFrom() {
  const opener = screen.getByRole('button', { name: '열기' });
  opener.focus();
  fireEvent.click(opener);
  return opener;
}

describe('useFocusTrap', () => {
  it('열리면 첫 요소, 닫히면 열기 전 위치로 포커스', () => {
    render(<Host />);
    const opener = openFrom();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '하나' }));
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(document.activeElement).toBe(opener);
  });
  it('initialFocusRef 가 있으면 그 요소로', () => {
    render(<Host withInitial />);
    openFrom();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '둘' }));
  });
  it('Tab 은 안에서 순환하고 비활성 요소를 건너뛴다', () => {
    render(<Host />);
    openFrom();
    const last = screen.getByRole('button', { name: '닫기' });
    last.focus();
    fireEvent.keyDown(last, { key: 'Tab' });
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '하나' }));
    fireEvent.keyDown(document.activeElement!, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(last);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/ui/useFocusTrap.test.tsx`
Expected: FAIL — `Failed to resolve import "./useFocusTrap"`.

- [ ] **Step 3: 구현**

`admin-ui/src/ui/useFocusTrap.ts`:
```ts
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
```

`admin-ui/src/ui/Dialog.tsx` 전체를 아래로 교체(동작은 그대로, 포커스 로직만 훅으로):
```tsx
import { useEffect, useId, useRef, ReactNode, RefObject } from 'react';
import { cn } from '@/lib/cn';
import { useFocusTrap } from './useFocusTrap';

type Props = {
  open: boolean; onClose: () => void; title?: ReactNode; sub?: ReactNode; children?: ReactNode;
  footer?: ReactNode; footerStart?: ReactNode; wide?: boolean | 'x'; dismissible?: boolean; closeOnScrim?: boolean;
  initialFocusRef?: RefObject<HTMLElement>; headerIcon?: ReactNode; role?: 'dialog' | 'alertdialog'; ariaLabel?: string;
};

export function Dialog({ open, onClose, title, sub, children, footer, footerStart, wide, dismissible = true, closeOnScrim = true, initialFocusRef, role = 'dialog', ariaLabel, headerIcon }: Props) {
  const titleId = useId();
  const boxRef = useRef<HTMLDivElement>(null);
  const trap = useFocusTrap(open, boxRef, initialFocusRef);

  useEffect(() => {
    if (!open || !dismissible) return;
    const k = (e: globalThis.KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, [open, dismissible, onClose]);

  if (!open) return null;
  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget && dismissible && closeOnScrim) onClose(); }}>
      <div ref={boxRef} className={cn('dialog', wide === true && 'dialog--wide', wide === 'x' && 'dialog--xwide')} role={role} aria-modal="true"
        aria-labelledby={title ? titleId : undefined} aria-label={title ? undefined : ariaLabel} onKeyDown={trap}>
        {(title || sub) && (
          <div className="dialog__head" style={headerIcon ? { flexDirection: 'row', alignItems: 'center', gap: 16 } : undefined}>
            {headerIcon && <span style={{ flex: 'none' }}>{headerIcon}</span>}
            {headerIcon ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0 }}>
                {title && <h3 className="dialog__title" id={titleId}>{title}</h3>}
                {sub && <div className="dialog__sub">{sub}</div>}
              </div>
            ) : (<>
              {title && <h3 className="dialog__title" id={titleId}>{title}</h3>}
              {sub && <div className="dialog__sub">{sub}</div>}
            </>)}
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

`admin-ui/src/ui/index.ts` 의 `export * from './Dialog';` 다음 줄에 추가:
```ts
export * from './useFocusTrap';
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/ui/useFocusTrap.test.tsx src/ui/Dialog.test.tsx src/ui/ConfirmDialog.test.tsx`
Expected: PASS (useFocusTrap 3 + Dialog·ConfirmDialog 기존 전부)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/ui/useFocusTrap.ts admin-ui/src/ui/useFocusTrap.test.tsx admin-ui/src/ui/Dialog.tsx admin-ui/src/ui/index.ts
git commit -m "refactor(admin-ui): Dialog 포커스 가둠·복원을 useFocusTrap 훅으로 분리

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 46: 커맨드 팔레트 — 포커스 가둠·복원, 비활성 건너뛰기, 활성 tenant 기준, 정직한 검색 문구

**Files:**
- Modify: `admin-ui/src/extras/CommandPalette.tsx` (전체 교체)
- Test: `admin-ui/src/extras/CommandPalette.test.tsx`

**Interfaces:**
- Consumes: `useFocusTrap` (Task 45), `activeTenantApi.get(): Promise<ActiveTenantView>`(기존 `@/api/activeTenant`)
- Produces: props 는 그대로(`CommandPaletteProps`). `onAction('new-tenant')` 의 의미가 "생성 다이얼로그 열기"로 바뀌고(Task 47 가 연결), PLATFORM 이 아니면 그 항목은 비활성 + "권한 없음".

- [ ] **Step 1: 실패하는 테스트 작성 + 바뀐 기대값 수정**

`admin-ui/src/extras/CommandPalette.test.tsx` 전체를 아래로 교체(기존 3개 테스트는 유지하고 결과 없음 문구만 새 기대값):
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import type { Me } from '@/api/types';

vi.mock('@/api/activeTenant', () => ({ activeTenantApi: { get: vi.fn(), switch: vi.fn() } }));

import { activeTenantApi } from '@/api/activeTenant';
import { CommandPalette } from './CommandPalette';

const me: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const rp: Me = { ...me, role: 'RP_ADMIN', tenantIds: ['t1', 't2'] };
const ACTIVE = { activeTenantId: 't2', allowedTenantIds: ['t1', 't2'], allowedTenants: [{ id: 't1', name: 'Acme' }, { id: 't2', name: 'Globex' }] };

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(activeTenantApi.get).mockResolvedValue(ACTIVE);
});

describe('CommandPalette V2', () => {
  it('이동/액션 그룹과 단축키 표시, 결과 없음 상태', () => {
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={() => {}} onAction={() => {}} />);
    expect(screen.getAllByText('이동').some((e) => e.classList.contains('t-over'))).toBe(true);
    expect(screen.getByText('액션')).toBeInTheDocument();
    expect(screen.getByText('G T')).toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox', { name: '검색' }), { target: { value: 'zzzzzz' } });
    expect(screen.getByText('“zzzzzz”와 일치하는 tenant · 화면이 없습니다.')).toBeInTheDocument();
  });
  it('Enter 로 선택 항목 실행', () => {
    const onNavigate = vi.fn();
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={onNavigate} onAction={() => {}} />);
    fireEvent.keyDown(screen.getByRole('textbox', { name: '검색' }), { key: 'Enter' });
    expect(onNavigate).toHaveBeenCalledWith({ name: 'tenants' });
  });
  it('동작하지 않는 액션(Hash chain 즉시 검증·Role 전환 데모)은 없다', () => {
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={() => {}} onAction={() => {}} />);
    expect(screen.queryByText('Hash chain 즉시 검증')).toBeNull();
    expect(screen.queryByText('Role 전환 (데모)')).toBeNull();
    expect(screen.getByText('로그아웃')).toBeInTheDocument();
  });
});

describe('CommandPalette V3', () => {
  it('검색 안내는 실제 기능에 맞춘다(credential·audit ID 검색을 약속하지 않음)', () => {
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={() => {}} onAction={() => {}} />);
    expect(screen.getByRole('textbox', { name: '검색' })).toHaveAttribute('placeholder', 'tenant 이름 · slug, 화면, 동작 검색…');
    fireEvent.change(screen.getByRole('textbox', { name: '검색' }), { target: { value: 'zzzzzz' } });
    expect(screen.getByText('credential · 감사 이벤트 ID는 해당 화면의 검색에서 찾으세요.')).toBeInTheDocument();
  });

  it('열리면 검색칸에 포커스, 닫히면 열기 전 위치로', () => {
    function Host({ open }: { open: boolean }) {
      return <><button>바깥</button><CommandPalette open={open} onClose={() => {}} me={me} onNavigate={() => {}} onAction={() => {}} /></>;
    }
    const { rerender } = render(<Host open={false} />);
    const outside = screen.getByRole('button', { name: '바깥' });
    outside.focus();
    rerender(<Host open />);
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: '검색' }));
    rerender(<Host open={false} />);
    expect(document.activeElement).toBe(outside);
  });

  it('Tab 은 팔레트 안에서 순환한다', () => {
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={() => {}} onAction={() => {}} />);
    const logout = screen.getByRole('button', { name: /로그아웃/ });
    logout.focus();
    fireEvent.keyDown(logout, { key: 'Tab' });
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: '검색' }));
  });

  it('PLATFORM: "신규 tenant 생성"은 다이얼로그 열기 동작을 요청한다', () => {
    const onAction = vi.fn();
    const onClose = vi.fn();
    render(<CommandPalette open onClose={onClose} me={me} onNavigate={() => {}} onAction={onAction} />);
    const btn = screen.getByRole('button', { name: /신규 tenant 생성/ });
    expect(btn).toHaveTextContent('다이얼로그 열기');
    fireEvent.click(btn);
    expect(onAction).toHaveBeenCalledWith('new-tenant');
    expect(onClose).toHaveBeenCalled();
  });

  it('RP 관리자는 첫 tenant 가 아니라 활성 tenant 기준, 그룹 라벨 "현재 Tenant · [이름]"', async () => {
    const onNavigate = vi.fn();
    render(<CommandPalette open onClose={() => {}} me={rp} onNavigate={onNavigate} onAction={() => {}} />);
    expect(await screen.findByText('현재 Tenant · Globex')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /^개요/ }));
    expect(onNavigate).toHaveBeenCalledWith({ name: 'tenant', tenantId: 't2', tab: 'overview' });
  });

  it('활성 tenant 조회 실패면 첫 tenant, 라벨 "현재 Tenant"', async () => {
    vi.mocked(activeTenantApi.get).mockRejectedValue(new Error('x'));
    const onNavigate = vi.fn();
    render(<CommandPalette open onClose={() => {}} me={rp} onNavigate={onNavigate} onAction={() => {}} />);
    expect(await screen.findByText('현재 Tenant')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /^개요/ }));
    expect(onNavigate).toHaveBeenCalledWith({ name: 'tenant', tenantId: 't1', tab: 'overview' });
  });

  it('PLATFORM 은 활성 tenant 를 조회하지 않는다', () => {
    render(<CommandPalette open onClose={() => {}} me={me} onNavigate={() => {}} onAction={() => {}} />);
    expect(activeTenantApi.get).not.toHaveBeenCalled();
  });

  it('화살표 이동과 처음 선택은 비활성 항목을 건너뛴다', async () => {
    const onAction = vi.fn();
    render(<CommandPalette open onClose={() => {}} me={rp} onNavigate={() => {}} onAction={onAction} />);
    await screen.findByText('현재 Tenant · Globex');
    const input = screen.getByRole('textbox', { name: '검색' });
    fireEvent.change(input, { target: { value: '액션' } });
    expect(screen.getByRole('button', { name: /신규 tenant 생성/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /신규 tenant 생성/ })).toHaveTextContent('권한 없음');
    expect(screen.getByRole('button', { name: /로그아웃/ })).toHaveAttribute('aria-current', 'true');
    fireEvent.keyDown(input, { key: 'ArrowUp' });
    expect(screen.getByRole('button', { name: /로그아웃/ })).toHaveAttribute('aria-current', 'true');
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onAction).toHaveBeenCalledWith('logout');
    expect(onAction).not.toHaveBeenCalledWith('new-tenant');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/extras/CommandPalette.test.tsx`
Expected: FAIL — 결과 없음 문구·placeholder 다름, 닫힐 때 포커스 복원 안 됨, `현재 Tenant · Globex` 없음(현재 `me.tenantIds[0]`·"현재 Tenant"), ArrowUp 이 비활성 "신규 tenant 생성"에 멈춤.

- [ ] **Step 3: 구현**

`admin-ui/src/extras/CommandPalette.tsx` 전체를 아래로 교체:
```tsx
import { useState, useEffect, useRef, useMemo, type ComponentType } from 'react';
import { Icons } from '@/icons/Icons';
import { searchAll, type SearchResult } from '@/lib/search';
import type { AppRoute } from '@/appRoute';
import { activeTenantApi } from '@/api/activeTenant';
import { EmptyState, useFocusTrap } from '@/ui';
import { illustrations } from '@/assets/illustrations';
import { GO_SHORTCUTS } from './useGoShortcuts';

const GO_BY_LABEL: Record<string, keyof typeof GO_SHORTCUTS> = {
  'Tenants 목록': 'tenants', 'Activity': 'activity', '감사 체인 모니터': 'audit-chain', '설정': 'settings',
};

const TENANT_TABS = [
  { id: 'overview', label: '개요', icon: 'Activity' },
  { id: 'webauthn', label: 'WebAuthn', icon: 'Globe' },
  { id: 'aaguid', label: 'AAGUID 정책', icon: 'Shield' },
  { id: 'apikeys', label: 'API 키', icon: 'Key' },
  { id: 'credentials', label: 'Credentials', icon: 'Fingerprint' },
  { id: 'audit', label: '감사 로그', icon: 'Receipt' },
  { id: 'funnel', label: '퍼널', icon: 'Activity' },
];

type Command = { group: string; icon: string; label: string; hint?: string; path?: string; action: () => void; disabled?: boolean };

/** dir 방향으로 다음 실행 가능한 항목. 없으면 제자리 — 비활성 항목에 선택이 멈추지 않는다. */
function stepEnabled(list: Command[], from: number, dir: 1 | -1): number {
  for (let i = from + dir; i >= 0 && i < list.length; i += dir) if (!list[i].disabled) return i;
  return from;
}
function firstEnabled(list: Command[]): number {
  const i = list.findIndex((c) => !c.disabled);
  return i < 0 ? 0 : i;
}

export type CommandPaletteProps = {
  open: boolean;
  onClose: () => void;
  me: { role: string; tenantIds?: string[]; email: string; displayName?: string };
  onNavigate: (route: AppRoute) => void;
  onAction: (kind: string) => void;
};

export function CommandPalette({ open, onClose, me, onNavigate, onAction }: CommandPaletteProps) {
  const [q, setQ] = useState('');
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const [activeTenant, setActiveTenant] = useState<{ id: string; name?: string } | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const boxRef = useRef<HTMLDivElement>(null);
  // Dialog 와 같은 포커스 규칙: 열리면 검색칸, Tab 은 안에서 순환, 닫히면 원래 위치로.
  const trap = useFocusTrap(open, boxRef, inputRef);
  const isPlatform = me?.role === 'PLATFORM_OPERATOR';

  useEffect(() => {
    if (open) { setQ(''); setSearchResults([]); }
  }, [open]);

  // RP 관리자: 첫 tenant 가 아니라 지금 활성인 tenant 가 대상(상단 바 RP 전환과 같은 출처). 열 때마다 다시 읽어 전환을 반영한다.
  useEffect(() => {
    if (!open || isPlatform) return;
    let cancelled = false;
    activeTenantApi.get()
      .then((v) => {
        if (cancelled || !v.activeTenantId) return;
        const name = v.allowedTenants.find((t) => t.id === v.activeTenantId)?.name;
        setActiveTenant({ id: v.activeTenantId, name });
      })
      .catch(() => { /* 실패 시 me.tenantIds[0] 기준 + 라벨 "현재 Tenant" */ });
    return () => { cancelled = true; };
  }, [open, isPlatform]);

  // navigate adapter: path like "/tenants/:id?tab=overview" → onNavigate route object
  function navigateFromPath(path: string) {
    const tenantMatch = path.match(/^\/tenants\/([^?]+)/);
    if (tenantMatch) {
      const tabMatch = path.match(/[?&]tab=([^&]+)/);
      // AppRoute.tab is required; mirror urlToRoute's 'overview' default when absent.
      onNavigate({ name: 'tenant', tenantId: tenantMatch[1], tab: tabMatch?.[1] ?? 'overview' });
    }
  }

  useEffect(() => {
    let cancelled = false;
    if (!q.trim()) { setSearchResults([]); return; }
    searchAll(q, navigateFromPath).then((r) => { if (!cancelled) setSearchResults(r); });
    return () => { cancelled = true; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q]);

  const allCommands = useMemo(() => {
    const items: Command[] = [];

    if (isPlatform) {
      items.push({ group: '이동', icon: 'Building', label: 'Tenants 목록', hint: '전체 RP 회사', path: '플랫폼', action: () => onNavigate({ name: 'tenants' }) });
      items.push({ group: '이동', icon: 'Activity', label: 'Activity', hint: 'cross-tenant 활동 피드', path: '플랫폼', action: () => onNavigate({ name: 'activity' }) });
      items.push({ group: '이동', icon: 'Hash', label: '감사 체인 모니터', hint: '전 tenant 무결성 보드', path: '플랫폼', action: () => onNavigate({ name: 'audit-chain' }) });
      items.push({ group: '이동', icon: 'Cog', label: '설정', hint: 'Admin · MDS · 시스템', path: '관리', action: () => onNavigate({ name: 'settings' }) });
    }

    const fallbackId = !isPlatform && me?.tenantIds && me.tenantIds.length > 0 ? me.tenantIds[0] : null;
    const tenantId = activeTenant?.id ?? fallbackId;
    if (!isPlatform && tenantId) {
      const group = activeTenant?.name ? `현재 Tenant · ${activeTenant.name}` : '현재 Tenant';
      TENANT_TABS.forEach((tb) => items.push({
        group, icon: tb.icon, label: tb.label,
        action: () => onNavigate({ name: 'tenant', tenantId, tab: tb.id }),
      }));
    }

    items.push({ group: '액션', icon: 'Plus', label: '신규 tenant 생성', hint: isPlatform ? '다이얼로그 열기' : '권한 없음', action: () => onAction('new-tenant'), disabled: !isPlatform });
    items.push({ group: '액션', icon: 'LogOut', label: '로그아웃', action: () => onAction('logout') });

    return items;
  }, [isPlatform, me, activeTenant, onNavigate, onAction]);

  const filtered = useMemo(() => {
    const baseList = (() => {
      if (!q) return allCommands;
      const qq = q.toLowerCase();
      return allCommands.filter((c) => c.label.toLowerCase().includes(qq) || (c.hint || '').toLowerCase().includes(qq) || c.group.toLowerCase().includes(qq));
    })();

    // Merge platform tenant search results (only when query is active)
    if (isPlatform && q.trim() && searchResults.length > 0) {
      const tenantItems: Command[] = searchResults
        .filter((r) => r.type === 'tenant')
        .map((r) => ({
          group: 'Tenant',
          icon: 'Building',
          label: r.label,
          hint: r.sub ? `${r.sub} · ${r.id.slice(-8)}` : r.id.slice(-8),
          action: () => r.onSelect(),
          disabled: false,
        }));
      return [...baseList, ...tenantItems];
    }
    return baseList;
  }, [q, allCommands, isPlatform, searchResults]);

  const [selIdx, setSelIdx] = useState(0);
  // 검색어가 바뀌거나 다시 열리면 첫 실행 가능한 항목을 선택한다.
  useEffect(() => { setSelIdx(firstEnabled(filtered)); }, [q, open]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!open) return;
    function k(e: KeyboardEvent) {
      if (e.key === 'Escape') { onClose(); return; }
      if (e.key === 'ArrowDown') { e.preventDefault(); setSelIdx((i) => stepEnabled(filtered, i, 1)); }
      if (e.key === 'ArrowUp') { e.preventDefault(); setSelIdx((i) => stepEnabled(filtered, i, -1)); }
      if (e.key === 'Enter') {
        e.preventDefault();
        const it = filtered[selIdx];
        if (it && !it.disabled) { it.action(); onClose(); }
      }
    }
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, [open, filtered, selIdx, onClose]);

  if (!open) return null;

  type IndexedCommand = Command & { _i: number };
  const groups: Record<string, IndexedCommand[]> = {};
  filtered.forEach((c, i) => {
    groups[c.group] = groups[c.group] || [];
    groups[c.group].push({ ...c, _i: i });
  });
  const term = q.trim();

  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div ref={boxRef} onKeyDown={trap} className="dialog" role="dialog" aria-modal="true" aria-label="명령 팔레트" style={{ maxWidth: 640, marginTop: '10vh', maxHeight: '70vh', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <label className="row" style={{ flexWrap: 'nowrap', gap: 10, padding: '14px 16px', borderBottom: '1px solid var(--line-soft)' }}>
          <Icons.Search size={18} />
          <span className="sr-only">검색</span>
          <input
            ref={inputRef}
            aria-label="검색"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="tenant 이름 · slug, 화면, 동작 검색…"
            style={{ flex: 1, border: 0, outline: 'none', font: 'inherit', fontSize: 15, background: 'transparent' }}
          />
          <kbd>esc</kbd>
        </label>
        <div style={{ flex: 1, overflow: 'auto', padding: '0 0 6px' }}>
          {Object.keys(groups).length === 0 && (
            <EmptyState
              compact
              illustration={illustrations.noResults}
              title={term ? `“${term}”와 일치하는 tenant · 화면이 없습니다.` : '실행할 수 있는 항목이 없습니다.'}
              description="credential · 감사 이벤트 ID는 해당 화면의 검색에서 찾으세요."
            />
          )}
          {Object.entries(groups).map(([g, items]) => (
            <div key={g}>
              <span className="t-over" style={{ display: 'block', padding: '10px 16px 4px' }}>{g}</span>
              {items.map((it) => {
                const active = it._i === selIdx;
                const Ic = (Icons as Record<string, ComponentType<{ size?: number }>>)[it.icon] || Icons.ChevronRight;
                const goKey = it.group === '이동' ? GO_BY_LABEL[it.label] : undefined;
                return (
                  <button
                    key={it._i}
                    type="button"
                    className={`menu__item${it.label === '로그아웃' ? ' menu__item--danger' : ''}`}
                    aria-current={active || undefined}
                    disabled={it.disabled}
                    onClick={() => { it.action(); onClose(); }}
                    onMouseEnter={() => { if (!it.disabled) setSelIdx(it._i); }}
                    style={{ minHeight: 40, padding: '0 16px', ...(active ? { background: 'var(--brand-100)', boxShadow: 'inset 3px 0 0 var(--brand-700)' } : {}) }}
                  >
                    <Ic size={16} />
                    <span>{it.label}</span>
                    {(it.path ?? it.hint) && <span className="hint" style={{ margin: '0 0 0 auto' }}>{it.path ?? it.hint}</span>}
                    {goKey && <kbd>{GO_SHORTCUTS[goKey]}</kbd>}
                  </button>
                );
              })}
            </div>
          ))}
        </div>
        <div className="row" style={{ gap: 14, padding: '10px 16px', borderTop: '1px solid var(--line-soft)', background: 'var(--canvas)', fontSize: 12, color: 'var(--mute)' }}>
          <span><kbd>↑</kbd><kbd>↓</kbd> 이동</span>
          <span><kbd>↵</kbd> 실행</span>
          <span><kbd>esc</kbd> 닫기</span>
          <span style={{ marginLeft: 'auto' }}>{filtered.length}개 결과</span>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/extras/CommandPalette.test.tsx src/extras/usePaletteShortcut.test.tsx src/extras/useGoShortcuts.test.tsx`
Expected: PASS (CommandPalette 11 tests + 기존 단축키 테스트)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/extras/CommandPalette.tsx admin-ui/src/extras/CommandPalette.test.tsx
git commit -m "feat(admin-ui): 커맨드 팔레트 포커스 가둠·복원, 비활성 항목 건너뛰기, RP 는 활성 tenant 기준 그룹 라벨

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 47: 팔레트 "신규 tenant 생성" → 생성 다이얼로그 바로 열기

**Files:**
- Modify: `admin-ui/src/pages/TenantsListPage.tsx` (import 1줄, `const toast = useToast();` 다음에 effect 추가)
- Modify: `admin-ui/src/App.tsx:101-104`
- Test: `admin-ui/src/pages/TenantsListPage.test.tsx`

**Interfaces:**
- Consumes: `onAction('new-tenant')` (Task 46)
- Produces: URL 계약 `/tenants?new=1` — `TenantsListPage` 가 이 파라미터를 보면 `NewTenantDialog` 를 열고 `new` 를 주소에서 지운다(replace).

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/pages/TenantsListPage.test.tsx` 3행 import 를 교체(`waitFor` 추가):
```tsx
import { screen, fireEvent, within, waitFor } from '@testing-library/react';
```

`describe('TenantsListPage V2', …)` 블록 마지막 `});` 앞에 추가:
```tsx
  it('?new=1 로 들어오면(커맨드 팔레트) 생성 다이얼로그를 바로 열고 주소에서 new 를 지운다', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue([]);
    const { router } = renderWithDataRouter(<ToastHost><TenantsListPage /></ToastHost>, { initialPath: '/tenants?new=1' });
    expect(await screen.findByRole('dialog', { name: '신규 Tenant 생성' })).toBeInTheDocument();
    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(screen.getByRole('dialog', { name: '신규 Tenant 생성' })).toBeInTheDocument();
  });

  it('new 파라미터가 없으면 다이얼로그를 열지 않는다', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue([]);
    renderPage();
    await waitFor(() => expect(tenantsApi.list).toHaveBeenCalled());
    expect(screen.queryByRole('dialog', { name: '신규 Tenant 생성' })).toBeNull();
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/pages/TenantsListPage.test.tsx`
Expected: FAIL — `?new=1` 로 들어와도 다이얼로그가 열리지 않음.

- [ ] **Step 3: 구현**

`admin-ui/src/pages/TenantsListPage.tsx` 3행:
```tsx
import { useNavigate } from 'react-router-dom';
```
→
```tsx
import { useNavigate, useSearchParams } from 'react-router-dom';
```

같은 파일 `const toast = useToast();`(현재 63행, `TenantsListPage` 함수 안) 다음에 추가:
```tsx
  const [params, setParams] = useSearchParams();

  // 커맨드 팔레트의 "신규 tenant 생성"은 /tenants?new=1 로 온다 — 다이얼로그를 바로 열고,
  // 새로고침·뒤로가기 때 다시 열리지 않게 new 를 주소에서 지운다.
  useEffect(() => {
    if (params.get('new') !== '1') return;
    setShowNew(true);
    const next = new URLSearchParams(params);
    next.delete('new');
    setParams(next, { replace: true });
  }, [params, setParams]);
```

`admin-ui/src/App.tsx` 101–104행:
```tsx
  function paletteAction(kind: string) {
    if (kind === 'logout') onLogout();
    if (kind === 'new-tenant') setRoute({ name: 'tenants' });
  }
```
→
```tsx
  function paletteAction(kind: string) {
    if (kind === 'logout') onLogout();
    // 목록으로 보내고 끝내지 않고 생성 다이얼로그까지 연다(TenantsListPage 가 ?new=1 을 처리).
    if (kind === 'new-tenant') navigate('/tenants?new=1');
  }
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/pages/TenantsListPage.test.tsx src/pages/NewTenantDialog.test.tsx && npx tsc -b`
Expected: PASS, 타입 에러 0

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/TenantsListPage.tsx admin-ui/src/pages/TenantsListPage.test.tsx admin-ui/src/App.tsx
git commit -m "feat(admin-ui): 커맨드 팔레트의 신규 tenant 생성이 생성 다이얼로그를 바로 열도록 연결

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 48: 세션 만료 모달 — 기본 포커스 "세션 연장", 손실 경고 문구, 카운트다운 진행 막대

**Files:**
- Modify: `admin-ui/src/extras/IdleSessionModal.tsx` (전체 교체)
- Modify: `admin-ui/src/styles/components.css` (끝에 `.idle-progress` 추가)
- Test: `admin-ui/src/extras/IdleSessionModal.test.tsx`

**Interfaces:**
- Consumes: `Dialog.initialFocusRef`(기존), `Button` forwardRef (Task 4)
- Produces: 없음 (props 동일)

- [ ] **Step 1: 실패하는 테스트 작성 + 바뀐 기대값 수정**

`admin-ui/src/extras/IdleSessionModal.test.tsx` 21행:
```tsx
    expect(screen.getByText(/보안을 위해 2분 동안/)).toBeInTheDocument();
```
→
```tsx
    expect(screen.getByText(/보안 정책에 따라 2분 동안/)).toBeInTheDocument();
```

2행 import 를 교체(`fireEvent` 추가):
```tsx
import { render, screen, act, fireEvent } from '@testing-library/react';
```

`describe('IdleSessionModal', …)` 마지막 `});` 앞에 추가:
```tsx
  it('기본 포커스는 "세션 연장"(Enter = 연장), 저장하지 않은 변경 손실 경고, 내부 용어 없음', () => {
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={vi.fn()} onLogout={vi.fn()} />);
    act(() => { vi.advanceTimersByTime(60 * 1000); });
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '세션 연장' }));
    expect(screen.getByText('로그아웃하면 저장하지 않은 변경이 사라집니다.')).toBeInTheDocument();
    expect(screen.getByRole('alertdialog')).not.toHaveTextContent(/mutation|audit log/);
  });

  it('카운트다운 진행 막대(progressbar)가 남은 초를 반영한다', () => {
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={vi.fn()} onLogout={vi.fn()} />);
    act(() => { vi.advanceTimersByTime(60 * 1000); });
    const bar = screen.getByRole('progressbar', { name: '세션 만료까지 남은 시간' });
    expect(bar).toHaveAttribute('aria-valuemax', '60');
    expect(bar).toHaveAttribute('aria-valuenow', '60');
    act(() => { vi.advanceTimersByTime(10 * 1000); });
    expect(bar).toHaveAttribute('aria-valuenow', '50');
    expect(screen.getByText('50초')).toBeInTheDocument();
  });

  it('스크린리더 안내는 매초가 아니라 30초·10초 남았을 때만', () => {
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={vi.fn()} onLogout={vi.fn()} />);
    act(() => { vi.advanceTimersByTime(60 * 1000); });
    const live = document.querySelector('[aria-live="polite"]')!;
    expect(live).toHaveTextContent('');
    act(() => { vi.advanceTimersByTime(30 * 1000); });
    expect(live).toHaveTextContent('세션 만료까지 30초 남았습니다.');
    act(() => { vi.advanceTimersByTime(20 * 1000); });
    expect(live).toHaveTextContent('세션 만료까지 10초 남았습니다.');
  });

  it('"로그아웃" 버튼은 즉시 onLogout', () => {
    const onLogout = vi.fn();
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={vi.fn()} onLogout={onLogout} />);
    act(() => { vi.advanceTimersByTime(60 * 1000); });
    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }));
    expect(onLogout).toHaveBeenCalledTimes(1);
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npx vitest run src/extras/IdleSessionModal.test.tsx`
Expected: FAIL — 포커스가 "지금 로그아웃"에 있음, 손실 경고 문구·progressbar·live 영역 없음, "mutation… audit log" 문구 있음, 버튼 이름 "지금 로그아웃".

- [ ] **Step 3: 구현**

`admin-ui/src/extras/IdleSessionModal.tsx` 전체를 아래로 교체:
```tsx
import { useState, useEffect, useRef, ReactNode } from 'react';
import { Button, Dialog } from '@/ui';

export type IdleSessionModalProps = {
  idleTimeoutMinutes: number;
  onExtend: () => void;
  onLogout: () => void;
};

const WARN_LEAD_SECONDS = 60;

/** 스크린리더에는 매초가 아니라 30초·10초 남았을 때만 알린다. */
function announcement(secondsLeft: number): string {
  if (secondsLeft <= 10) return '세션 만료까지 10초 남았습니다.';
  if (secondsLeft <= 30) return '세션 만료까지 30초 남았습니다.';
  return '';
}

export function IdleSessionModal({ idleTimeoutMinutes, onExtend, onLogout }: IdleSessionModalProps): ReactNode {
  // 카운트다운 길이를 전체 idle 시간에서 파생한다.
  // 짧은 정책(예: 1분)에서도 (모달 표시 시점 + 카운트다운)이 전체 idle과 정확히 일치하도록.
  const totalSeconds = idleTimeoutMinutes * 60;
  const warnAfterSeconds = Math.max(totalSeconds - WARN_LEAD_SECONDS, 10);
  const countdownSeconds = Math.max(totalSeconds - warnAfterSeconds, 1);

  const [open, setOpen] = useState(false);
  const [secondsLeft, setSecondsLeft] = useState(countdownSeconds);
  const idleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const extendRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    // 모달이 이미 열려 있는 동안에는 idle 타이머를 다시 무장하지 않는다.
    // (그렇지 않으면 warn 타이머가 카운트다운 도중 다시 발화해 secondsLeft가 리셋됨.)
    if (open) return;
    const warnAfterMs = warnAfterSeconds * 1000;
    function bumpIdle() {
      clearTimeout(idleTimer.current!);
      idleTimer.current = setTimeout(() => {
        setSecondsLeft(countdownSeconds);
        setOpen(true);
      }, warnAfterMs);
    }
    bumpIdle();
    const handler = () => bumpIdle();
    ['mousemove', 'keydown', 'click', 'scroll'].forEach((e) => window.addEventListener(e, handler, { passive: true }));
    return () => {
      clearTimeout(idleTimer.current!);
      ['mousemove', 'keydown', 'click', 'scroll'].forEach((e) => window.removeEventListener(e, handler));
    };
  }, [open, warnAfterSeconds, countdownSeconds]);

  useEffect(() => {
    if (!open) return;
    const tick = setInterval(() => {
      setSecondsLeft((s) => (s <= 1 ? 0 : s - 1));
    }, 1000);
    return () => clearInterval(tick);
  }, [open]);

  // 카운트다운이 0에 도달하면 로그아웃 (부작용은 updater 밖, 렌더 후 effect에서 1회만).
  useEffect(() => {
    if (open && secondsLeft === 0) {
      setOpen(false);
      onLogout?.();
    }
  }, [open, secondsLeft, onLogout]);

  if (!open) return null;
  const pct = secondsLeft / countdownSeconds;
  const C = 2 * Math.PI * 28;
  const tone = secondsLeft > 20 ? 'var(--warn-dot)' : 'var(--bad)';
  return (
    // 기본 포커스 = "세션 연장": 실수로 Enter 를 눌러도 작업이 사라지지 않는다.
    <Dialog open={open} onClose={() => {}} dismissible={false} role="alertdialog" initialFocusRef={extendRef}
      headerIcon={
          <span style={{ position: 'relative', width: 64, height: 64, flex: 'none' }}>
            <svg width="64" height="64" viewBox="0 0 64 64" aria-hidden="true">
              <circle cx="32" cy="32" r="28" fill="none" stroke="var(--line-soft)" strokeWidth="6" />
              <circle cx="32" cy="32" r="28" fill="none" stroke={tone} strokeWidth="6" strokeLinecap="round"
                strokeDasharray={`${C} ${C}`} strokeDashoffset={C * (1 - pct)} transform="rotate(-90 32 32)" style={{ transition: 'stroke-dashoffset 1s linear' }} />
            </svg>
            <span className="mono" aria-hidden="true" style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 14, fontWeight: 600 }}>{secondsLeft}초</span>
          </span>
      }
      title="세션이 곧 만료됩니다"
      sub={`${secondsLeft}초 후 자동으로 로그아웃됩니다.`}
      footer={<>
        <Button onClick={() => { setOpen(false); onLogout(); }}>로그아웃</Button>
        <Button ref={extendRef} variant="primary" onClick={() => { setOpen(false); onExtend(); }}>세션 연장</Button>
      </>}
    >
      <div className="stack-3">
        <div
          className="idle-progress"
          role="progressbar"
          aria-label="세션 만료까지 남은 시간"
          aria-valuemin={0}
          aria-valuemax={countdownSeconds}
          aria-valuenow={secondsLeft}
          aria-valuetext={`${secondsLeft}초 남음`}
        >
          <span className="idle-progress__bar" style={{ width: `${pct * 100}%`, background: tone }} />
        </div>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)', lineHeight: 1.6 }}>
          보안 정책에 따라 {idleTimeoutMinutes}분 동안 활동이 없어 세션을 종료하려고 합니다. 작업 중이라면 세션을 연장하세요.
        </p>
        <p style={{ margin: 0, fontSize: 13, lineHeight: 1.6 }}>
          <strong>로그아웃하면 저장하지 않은 변경이 사라집니다.</strong>
        </p>
        <span className="sr-only" aria-live="polite">{announcement(secondsLeft)}</span>
      </div>
    </Dialog>
  );
}
```

`admin-ui/src/styles/components.css` 끝에 추가:
```css
/* ── idle session countdown ────────── */
.idle-progress { height: 6px; border-radius: 3px; background: var(--line-soft); overflow: hidden; }
.idle-progress__bar { display: block; height: 100%; transition: width 1s linear; }
```

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run src/extras/IdleSessionModal.test.tsx`
Expected: PASS (기존 5 + 신규 4)

- [ ] **Step 5: 전체 검증 후 커밋**

Run: `npx vitest run && npx tsc -b && npx eslint src`
Expected: 전부 PASS, 타입·린트 에러 0

```bash
git add admin-ui/src/extras/IdleSessionModal.tsx admin-ui/src/extras/IdleSessionModal.test.tsx admin-ui/src/styles/components.css
git commit -m "feat(admin-ui): 세션 만료 모달 기본 포커스를 세션 연장으로, 저장하지 않은 변경 손실 경고와 카운트다운 진행 막대 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## 마무리

### Task 49: 전체 마무리 확인 (남은 옛 문구 검색, 전체 테스트·타입·린트, V3 캔버스 비교)

**Files:** 없음(검증만)

- [ ] **Step 1: 남은 옛 문구 검색**

Run: `grep -rn "Loading…\|불러오는 중…\|revoke됨\|ceremony\|탭 점 배지\|확인 — 회수\|aria-valuenow" src/pages/TenantDetailPage.tsx src/pages/tenant/ | grep -v "test\." || echo "none"`
Expected: `none`. (코드 주석의 `ceremony` 는 사용자 문구가 아니므로, 남아 있으면 주석인지 확인만 한다.)

- [ ] **Step 2: 전체 검증**

Run: `npx vitest run && npx tsc -b && npx eslint src`
Expected: 전부 PASS, 타입·린트 에러 0.

- [ ] **Step 3: 화면 확인 메모**

`npm run dev` 로 Tenant 상세의 7개 탭을 열어 V3 캔버스(`project/v3/Tenant-*.dc.html`, `TenantHead.dc.html`, `Dialogs-Tenant.dc.html`, `Dialogs-ApiKey.dc.html`, `Dialogs-Audit.dc.html`)와 상태(정상 / 로드 실패 / 빈 상태 / 확인 다이얼로그)를 비교한다. 커밋 없음.
