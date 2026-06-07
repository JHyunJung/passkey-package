# Credential 상세 + 인증 기록 모달 (P2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** admin-ui Credential 탭에서 행 클릭 시 상세 정보 + 인증 기록(P1 auth-events API)을 중앙 모달로 보여준다.

**Architecture:** 기존 `shell/Dialog`(중앙 모달)를 재사용한 신규 `CredentialDetailDialog`. 상세는 이미 로드된 Credential 객체로 즉시 렌더하고, 인증 기록만 P1 API 로 최근 20건 조회한다. `CredentialsTab` 에 행 클릭 핸들러를 추가하되 회수 버튼은 stopPropagation 으로 분리한다. 백엔드 무변경.

**Tech Stack:** React 18 + TypeScript + Vite, vitest + @testing-library/react.

---

## File Structure

| 파일 | 책임 | 변경 |
|---|---|---|
| `admin-ui/src/api/types.ts` | 서버 `AuthEventView` 타입 + `CredentialView.attestationFormat` 확인 | Modify |
| `admin-ui/src/api/designTypes.ts` | `Credential.attestationFormat` + `AuthEvent` 타입 | Modify |
| `admin-ui/src/api/credentials.ts` | `authEvents()` + `adapt()` attestationFormat 보강 | Modify |
| `admin-ui/src/api/credentials.test.ts` | authEvents URL/반환 + adapt 보강 테스트 | Create |
| `admin-ui/src/pages/tenant/CredentialDetailDialog.tsx` | 상세+기록 모달 | Create |
| `admin-ui/src/pages/tenant/CredentialDetailDialog.test.tsx` | 렌더/로딩/빈/에러 테스트 | Create |
| `admin-ui/src/pages/tenant/CredentialsTab.tsx` | 행 클릭 + selected + stopPropagation | Modify |

### 설계 노트 (spec 의 확정 결정)
- 상세는 추가 호출 없음(이미 가진 `Credential`). 기록만 `authEvents()` 호출, 최근 20건.
- `result` 는 서버 String 이지만 P1 상수(SUCCESS/FAILED)로 통제됨 → TS 에서 `'SUCCESS'|'FAILED'` union 으로 좁히되 어댑터에서 `as` 단언.
- 회수(Trash) 버튼 onClick 에 `e.stopPropagation()` → 행 클릭(상세 모달)과 분리.
- `StatusBadge` 는 SUCCESS/FAILED 매핑이 없어 default 가 되므로, 기록 결과는 커스텀 색상 배지로 직접 표시.

### 모든 명령은 worktree 의 admin-ui 에서 실행
`cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/credential-detail-modal/admin-ui`

---

## Task 1: 타입 추가 (AuthEvent + attestationFormat)

**Files:**
- Modify: `admin-ui/src/api/types.ts`
- Modify: `admin-ui/src/api/designTypes.ts`

서버 응답 타입과 디자인 타입을 먼저 정의한다(이후 task 들이 의존).

- [ ] **Step 1: types.ts 에 AuthEventView 추가 + CredentialView.attestationFormat 확인**

`admin-ui/src/api/types.ts` 의 `CredentialView` 인터페이스에 `attestationFormat` 이 이미 있는지 확인(있음 — `attestationFormat: string`). 그 아래(또는 적절한 위치)에 서버 auth-event 응답 타입 추가:

```ts
export interface AuthEventView {
  result: string;          // "SUCCESS" | "FAILED" (서버 상수)
  failureReason: string | null;
  signCount: number;
  createdAt: string;
}
```

- [ ] **Step 2: designTypes.ts 에 attestationFormat + AuthEvent 추가**

`Credential` 타입에 `attestationFormat` 추가(기존 필드 끝에):

```ts
export type Credential = {
  credentialId: string;
  externalUserId: string;
  nickname: string | null;
  authenticatorName: string | null;
  status: 'ACTIVE' | 'REVOKED';
  aaguid: string | null;
  transports: string[];
  signatureCounter: number;
  lastUsedAt: string | null;
  createdAt: string;
  attestationFormat: string | null;
};
```

그리고 파일에 `AuthEvent` 타입 추가:

```ts
export type AuthEvent = {
  result: 'SUCCESS' | 'FAILED';
  failureReason: string | null;
  signCount: number;
  createdAt: string;
};
```

- [ ] **Step 3: 타입체크**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/credential-detail-modal/admin-ui && npx tsc -b --noEmit 2>&1 | head`
Expected: 에러 없음 (단, adapt 가 attestationFormat 을 아직 안 채워 Credential 생성부에서 타입 에러가 날 수 있음 — 그러면 Task 2 에서 즉시 해소되므로, 이 step 은 "types.ts/designTypes.ts 자체 구문 에러 없음"만 확인. credentials.ts 에러는 Task 2 에서 해결).

> 만약 `adapt()` 의 반환에서 `attestationFormat` 누락으로 tsc 에러가 나면 정상 — Task 2 Step 1 에서 채운다. 이 단계에서는 커밋하지 않고 Task 2 로 진행해도 된다(타입+어댑터를 한 커밋으로 묶기).

- [ ] **Step 4: (Task 2 와 함께 커밋하므로 여기선 커밋 보류)**

타입 추가만으로는 빌드가 안 깨지면 단독 커밋, 깨지면 Task 2 와 함께 커밋. 깔끔하게 하려면 Task 2 까지 진행 후 한 번에 커밋.

---

## Task 2: credentials.ts — authEvents() + adapt 보강 (TDD)

**Files:**
- Modify: `admin-ui/src/api/credentials.ts`
- Create: `admin-ui/src/api/credentials.test.ts`

- [ ] **Step 1: 실패 테스트 작성**

`admin-ui/src/api/credentials.test.ts` — `apiKeys.test.ts` 의 fetch-stub + envelope 패턴 모방:

```ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { credentialsApi } from './credentials';

function envelope(data: unknown) {
  return new Response(JSON.stringify({ success: true, data }), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  });
}

describe('credentialsApi.authEvents', () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
  afterEach(() => vi.unstubAllGlobals());

  it('calls the per-credential auth-events endpoint with page=0 and given size', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      envelope({ content: [
        { result: 'SUCCESS', failureReason: null, signCount: 3, createdAt: '2026-06-07T05:00:00Z' },
        { result: 'FAILED', failureReason: 'SIGN_COUNT_REPLAY', signCount: 3, createdAt: '2026-06-06T05:00:00Z' },
      ], page: 0, size: 20, totalElements: 2, hasNext: false }),
    );
    const events = await credentialsApi.authEvents('t1', 'cred+slash/id', 20);
    const [url] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain('/admin/api/tenants/t1/credentials/');
    expect(url).toContain('/auth-events?');
    expect(url).toContain('page=0');
    expect(url).toContain('size=20');
    // credentialId 가 URL-encode 되어야 함 (slash/plus 안전)
    expect(url).toContain(encodeURIComponent('cred+slash/id'));
    expect(events).toHaveLength(2);
    expect(events[0].result).toBe('SUCCESS');
    expect(events[1].failureReason).toBe('SIGN_COUNT_REPLAY');
  });
});

describe('credentialsApi.list adapter', () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
  afterEach(() => vi.unstubAllGlobals());

  it('maps attestationFormat from server CredentialView', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      envelope({ content: [{
        credentialId: 'c1', userHandle: 'u1', label: null, aaguidHex: null,
        authenticatorName: null, attestationFormat: 'packed', transports: '',
        signCount: 0, lastUsedAt: null, createdAt: '2026-06-07T05:00:00Z',
      }], page: 0, size: 50, totalElements: 1, hasNext: false }),
    );
    const res = await credentialsApi.list('t1', 0, 50);
    expect(res.items[0].attestationFormat).toBe('packed');
  });
});
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/credential-detail-modal/admin-ui && npx vitest run src/api/credentials.test.ts`
Expected: FAIL (authEvents 미존재 / attestationFormat undefined)

- [ ] **Step 3: credentials.ts 구현**

import 에 `AuthEvent` 추가하고, `AuthEventView` 를 types 에서 import:

```ts
import { api } from './client';
import type { CredentialView, PageView, AuthEventView } from './types';
import type { Credential, AuthEvent } from './designTypes';
```

`adapt()` 의 반환 객체 끝에 attestationFormat 추가:

```ts
    lastUsedAt: s.lastUsedAt ?? null,
    createdAt: s.createdAt,
    attestationFormat: s.attestationFormat ?? null,
  };
```

`credentialsApi` 객체에 `authEvents` 추가(revoke 다음):

```ts
  authEvents: async (
    tenantId: string,
    credentialId: string,
    size = 20,
  ): Promise<AuthEvent[]> => {
    const q = new URLSearchParams({ page: '0', size: String(size) });
    const res = await api.get<PageView<AuthEventView>>(
      `/admin/api/tenants/${tenantId}/credentials/${encodeURIComponent(credentialId)}/auth-events?${q}`,
    );
    return res.content.map((e) => ({
      result: e.result === 'SUCCESS' ? 'SUCCESS' : 'FAILED',
      failureReason: e.failureReason ?? null,
      signCount: e.signCount,
      createdAt: e.createdAt,
    }));
  },
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/credential-detail-modal/admin-ui && npx vitest run src/api/credentials.test.ts`
Expected: PASS (2 tests)

- [ ] **Step 5: 타입체크**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/credential-detail-modal/admin-ui && npx tsc -b --noEmit 2>&1 | head`
Expected: 에러 없음

- [ ] **Step 6: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/credential-detail-modal
git add admin-ui/src/api/types.ts admin-ui/src/api/designTypes.ts admin-ui/src/api/credentials.ts admin-ui/src/api/credentials.test.ts
git commit -m "feat(admin-ui): credentialsApi.authEvents + attestationFormat 어댑터

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: CredentialDetailDialog 컴포넌트 (TDD)

**Files:**
- Create: `admin-ui/src/pages/tenant/CredentialDetailDialog.tsx`
- Create: `admin-ui/src/pages/tenant/CredentialDetailDialog.test.tsx`

상세는 `c` 로 즉시 렌더, 기록은 `authEvents()` 호출(로딩/성공/빈/에러).

- [ ] **Step 1: 실패 테스트 작성**

`admin-ui/src/pages/tenant/CredentialDetailDialog.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import CredentialDetailDialog from './CredentialDetailDialog';
import { credentialsApi } from '@/api/credentials';
import type { Credential } from '@/api/designTypes';

const cred: Credential = {
  credentialId: 'cred-abc-123456789012',
  externalUserId: 'user-handle-1',
  nickname: 'My Phone',
  authenticatorName: 'iCloud Keychain',
  status: 'ACTIVE',
  aaguid: 'aaguid-x',
  transports: ['internal', 'hybrid'],
  signatureCounter: 5,
  lastUsedAt: '2026-06-07T05:00:00Z',
  createdAt: '2026-06-01T05:00:00Z',
  attestationFormat: 'packed',
};

describe('CredentialDetailDialog', () => {
  afterEach(() => vi.restoreAllMocks());

  it('renders credential detail immediately from props', () => {
    vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([]);
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} />);
    expect(screen.getByText('user-handle-1')).toBeInTheDocument();
    expect(screen.getByText('My Phone')).toBeInTheDocument();
    expect(screen.getByText('packed')).toBeInTheDocument();
  });

  it('shows auth events after load', async () => {
    vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([
      { result: 'SUCCESS', failureReason: null, signCount: 5, createdAt: '2026-06-07T05:00:00Z' },
      { result: 'FAILED', failureReason: 'SIGN_COUNT_REPLAY', signCount: 5, createdAt: '2026-06-06T05:00:00Z' },
    ]);
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} />);
    await waitFor(() => expect(screen.getByText('SUCCESS')).toBeInTheDocument());
    expect(screen.getByText('FAILED')).toBeInTheDocument();
    expect(screen.getByText('SIGN_COUNT_REPLAY')).toBeInTheDocument();
  });

  it('shows empty state when no events', async () => {
    vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([]);
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} />);
    await waitFor(() => expect(screen.getByText(/아직 인증 이력이 없습니다/)).toBeInTheDocument());
  });

  it('shows error state with retry when load fails', async () => {
    vi.spyOn(credentialsApi, 'authEvents').mockRejectedValue(new Error('boom'));
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} />);
    await waitFor(() => expect(screen.getByText(/불러오지 못했습니다/)).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /재시도/ })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/credential-detail-modal/admin-ui && npx vitest run src/pages/tenant/CredentialDetailDialog.test.tsx`
Expected: FAIL (컴포넌트 미존재)

- [ ] **Step 3: 컴포넌트 구현**

`admin-ui/src/pages/tenant/CredentialDetailDialog.tsx`:

```tsx
import { useState, useEffect, useCallback } from 'react';
import { Dialog } from '@/shell/Dialog';
import { credentialsApi } from '@/api/credentials';
import type { Credential, AuthEvent } from '@/api/designTypes';

function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

function ResultBadge({ result }: { result: 'SUCCESS' | 'FAILED' }) {
  const ok = result === 'SUCCESS';
  return (
    <span
      className="badge badge--dot"
      style={{ color: ok ? 'var(--success, #4caf50)' : 'var(--danger, #e5484d)' }}
    >
      {result}
    </span>
  );
}

export default function CredentialDetailDialog({
  c, tenantId, onClose,
}: {
  c: Credential;
  tenantId: string;
  onClose: () => void;
}) {
  const [events, setEvents] = useState<AuthEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const load = useCallback(() => {
    let cancelled = false;
    setLoading(true);
    setError(false);
    credentialsApi.authEvents(tenantId, c.credentialId, 20)
      .then((res) => { if (!cancelled) setEvents(res); })
      .catch(() => { if (!cancelled) setError(true); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [tenantId, c.credentialId]);

  useEffect(() => load(), [load]);

  return (
    <Dialog open onClose={onClose} wide title="Credential 상세"
      sub={c.credentialId}
      footer={<button className="btn" onClick={onClose}>닫기</button>}>

      {/* 상세 — 이미 가진 c 로 즉시 렌더 */}
      <div style={{ display: 'grid', gridTemplateColumns: '140px 1fr', rowGap: 8, fontSize: 13, marginBottom: 16 }}>
        <div className="muted">externalUserId</div><div className="mono">{c.externalUserId}</div>
        <div className="muted">별칭</div><div>{c.nickname ?? '—'}</div>
        <div className="muted">인증기</div><div>{c.authenticatorName ?? (c.aaguid ?? 'unknown')}</div>
        <div className="muted">aaguid</div><div className="mono">{c.aaguid ?? '—'}</div>
        <div className="muted">전송 방식</div>
        <div style={{ display: 'flex', gap: 3 }}>
          {c.transports.length ? c.transports.map((t) => <span key={t} className="badge" style={{ fontSize: 10 }}>{t}</span>) : '—'}
        </div>
        <div className="muted">서명 카운터</div><div className="mono">{c.signatureCounter}</div>
        <div className="muted">attestation</div><div className="mono">{c.attestationFormat ?? '—'}</div>
        <div className="muted">마지막 사용</div><div className="muted">{fmtDateTime(c.lastUsedAt)}</div>
        <div className="muted">생성</div><div className="muted">{fmtDateTime(c.createdAt)}</div>
      </div>

      {/* 인증 기록 */}
      <div className="label" style={{ borderTop: '1px solid var(--border)', paddingTop: 10 }}>인증 기록 (최근 20건)</div>
      {loading ? (
        <div style={{ padding: '20px 0', textAlign: 'center', color: 'var(--text-mute)', fontSize: 13 }}>로딩 중…</div>
      ) : error ? (
        <div style={{ padding: '16px 0', textAlign: 'center', fontSize: 13 }}>
          <div className="muted" style={{ marginBottom: 8 }}>기록을 불러오지 못했습니다.</div>
          <button className="btn btn--sm" onClick={() => load()}>재시도</button>
        </div>
      ) : events.length === 0 ? (
        <div style={{ padding: '20px 0', textAlign: 'center', color: 'var(--text-mute)', fontSize: 13 }}>아직 인증 이력이 없습니다.</div>
      ) : (
        <table className="table">
          <thead>
            <tr><th>시간</th><th>결과</th><th>사유</th><th style={{ textAlign: 'right' }}>서명 카운터</th></tr>
          </thead>
          <tbody>
            {events.map((e, i) => (
              <tr key={i}>
                <td className="muted">{fmtDateTime(e.createdAt)}</td>
                <td><ResultBadge result={e.result} /></td>
                <td>{e.failureReason ?? <span className="faint">—</span>}</td>
                <td style={{ textAlign: 'right' }} className="mono">{e.signCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Dialog>
  );
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/credential-detail-modal/admin-ui && npx vitest run src/pages/tenant/CredentialDetailDialog.test.tsx`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/credential-detail-modal
git add admin-ui/src/pages/tenant/CredentialDetailDialog.tsx admin-ui/src/pages/tenant/CredentialDetailDialog.test.tsx
git commit -m "feat(admin-ui): CredentialDetailDialog 상세+인증기록 모달

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: CredentialsTab 통합 (행 클릭 + stopPropagation)

**Files:**
- Modify: `admin-ui/src/pages/tenant/CredentialsTab.tsx`

행 클릭으로 모달 열기. 회수 버튼은 stopPropagation 으로 분리.

- [ ] **Step 1: import + selected 상태 추가**

`CredentialsTab.tsx` 상단 import 에 추가:

```tsx
import CredentialDetailDialog from './CredentialDetailDialog';
```

`CredentialsTab` 컴포넌트의 기존 `useState` 들 근처에 추가:

```tsx
  const [selected, setSelected] = useState<Credential | null>(null);
```

- [ ] **Step 2: 행 클릭 핸들러 + 커서**

기존 `<tr key={c.credentialId} style={{ opacity: ... }}>` 를 클릭 가능하게 수정:

```tsx
                <tr
                  key={c.credentialId}
                  onClick={() => setSelected(c)}
                  style={{ opacity: c.status === 'REVOKED' ? 0.55 : 1, cursor: 'pointer' }}
                >
```

- [ ] **Step 3: 회수 버튼 stopPropagation**

기존 회수 버튼의 onClick 을 수정해 행 클릭으로 전파되지 않게:

```tsx
                      <button
                        className="btn btn--xs"
                        onClick={(e) => { e.stopPropagation(); setRevoking(c); }}
                        style={{ color: 'var(--danger)' }}
                      >
                        <Icons.Trash size={12} />
                      </button>
```

- [ ] **Step 4: 모달 렌더**

기존 `{revoking && (<RevokeCredentialDialog ... />)}` 블록 근처(같은 반환 트리 내)에 추가:

```tsx
      {selected && (
        <CredentialDetailDialog
          c={selected}
          tenantId={tenant.id}
          onClose={() => setSelected(null)}
        />
      )}
```

- [ ] **Step 5: 타입체크 + 전체 admin-ui 테스트**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/credential-detail-modal/admin-ui && npx tsc -b --noEmit 2>&1 | head && npm test 2>&1 | tail -8`
Expected: 타입 에러 없음, 모든 테스트 통과(기존 63 + 신규 6 = 69)

- [ ] **Step 6: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/credential-detail-modal
git add admin-ui/src/pages/tenant/CredentialsTab.tsx
git commit -m "feat(admin-ui): Credential 행 클릭 → 상세 모달(회수 버튼 분리)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: 빌드 검증

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: 프로덕션 빌드 + 전체 테스트**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/credential-detail-modal/admin-ui && npm run build 2>&1 | tail -8 && npm test 2>&1 | tail -8`
Expected: `tsc -b && vite build` 성공, 모든 테스트 통과.

- [ ] **Step 2: 수동 dogfooding (선택, dev 서버 가동 시)**

admin-ui dev(`npm run dev`, vite :5173 → admin-app :8081 proxy)에서:
1. Credential 탭 → 행 클릭 → 모달에 상세 + 인증 기록(또는 "아직 인증 이력이 없습니다").
2. 회수(Trash) 버튼 클릭 → 상세 모달 안 열리고 회수 확인 다이얼로그만.
3. P1 으로 인증 이력이 쌓인 credential → SUCCESS/FAILED 행 표시.

> 인프라 미가동이면 Step 1 만으로 충분.

---

## Self-Review 체크

- **Spec coverage**: §5.1 authEvents+adapt(Task2) + 타입(Task1), §5.2 DetailDialog 상세/기록/빈/에러(Task3), §5.3 CredentialsTab 행클릭+stopPropagation+모달(Task4), §6 데이터흐름(Task3 useEffect), §7 에러처리(Task3 error/empty/cancelled + encodeURIComponent Task2), §8 테스트(Task2 API + Task3 컴포넌트 + Task4 전체) — 전 항목 매핑.
- **Placeholder 스캔**: 모든 코드 step 에 실제 코드. TBD/TODO 없음.
- **Type 일관성**: `AuthEvent{result,failureReason,signCount,createdAt}` 가 Task1 정의와 Task2 어댑터·Task3 컴포넌트 사용 일치. `authEvents(tenantId, credentialId, size)` 시그니처가 Task2 정의와 Task3 호출 일치. `CredentialDetailDialog{c, tenantId, onClose}` props 가 Task3 정의와 Task4 사용 일치. `Credential.attestationFormat` 가 Task1 정의와 Task2 어댑터·Task3 렌더 일치.
