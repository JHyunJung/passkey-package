# admin-ui 후속 화면 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 그룹 A·B 백엔드 API의 운영자 UI를 admin-ui에 추가한다 — password reset 화면, MFA 복구 코드 표시/로그인, API key scope 선택 + rotate(+ `'ceremony'` scope 버그 수정).

**Architecture:** 백엔드 변경 없음. 기존 React + react-router-dom 6(basename `/admin`) 구조에 public route 2개 추가(me fetch보다 먼저 분기), MFA confirm 응답의 복구 코드를 1회 표시 모달로 캡처, API key 발급 다이얼로그에 scope 체크박스 + 행 rotate 버튼. 기존 공통 컴포넌트(Dialog/CopyBtn/ToastHost) 재사용.

**Tech Stack:** React 18, TypeScript, react-router-dom 6, Vite, vitest + @testing-library/react + jsdom(이미 도입됨).

**Spec:** `docs/superpowers/specs/2026-05-31-admin-ui-followup-screens-design.md`

**작업 디렉토리:** 모든 경로는 worktree 루트 기준. 빌드/테스트는 `admin-ui/` 안에서 실행:
- 빌드: `cd admin-ui && npx tsc -b` (EXIT 0 기대)
- 테스트: `cd admin-ui && npm test` (vitest run)
- 단일 테스트: `cd admin-ui && npx vitest run src/path/to.test.ts`

**Baseline:** worktree 분기 직후 `tsc -b` EXIT=0, vitest 12 tests green 확인됨.

---

## Phase 1 — Password reset

### Task 1: passwordReset API 모듈

**Files:**
- Create: `admin-ui/src/api/passwordReset.ts`
- Test: `admin-ui/src/api/passwordReset.test.ts`

`PasswordResetController`는 `ApiResponse` envelope이 아닌 raw `Map.of("ok",true)` / `Map.of("reset",true)` 를 반환한다(`admin-app/.../operator/PasswordResetController.java:33,39`). 따라서 envelope을 벗기지 않는 `rawRequest`(= `api.postRaw`)를 쓴다. `api.postRaw`는 비-2xx(400 등)를 `ApiError`로 throw하고 401이면 `/admin/login` redirect(permitAll이라 401 비기대, redirect는 무해).

- [ ] **Step 1: Write the failing test**

```ts
// admin-ui/src/api/passwordReset.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { passwordResetApi } from './passwordReset';

describe('passwordResetApi', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ ok: true }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    ));
  });
  afterEach(() => vi.unstubAllGlobals());

  it('request posts email to /password-reset/request', async () => {
    const res = await passwordResetApi.request('a@b.com');
    expect(res).toEqual({ ok: true });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('/admin/api/password-reset/request');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({ email: 'a@b.com' });
  });

  it('confirm posts token+newPassword to /password-reset/confirm', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ reset: true }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    const res = await passwordResetApi.confirm('tok123', 'NewPass!23');
    expect(res).toEqual({ reset: true });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('/admin/api/password-reset/confirm');
    expect(JSON.parse(init.body)).toEqual({ token: 'tok123', newPassword: 'NewPass!23' });
  });

  it('confirm surfaces 400 as ApiError', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ error: 'invalid_token', message: '토큰이 만료되었습니다' }), { status: 400, headers: { 'Content-Type': 'application/json' } }),
    );
    await expect(passwordResetApi.confirm('bad', 'x')).rejects.toMatchObject({ status: 400 });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin-ui && npx vitest run src/api/passwordReset.test.ts`
Expected: FAIL — `Cannot find module './passwordReset'`.

- [ ] **Step 3: Write the module**

```ts
// admin-ui/src/api/passwordReset.ts
import { api } from './client';

/**
 * Self-service password reset (P1-6 / 그룹 A).
 *
 * PasswordResetController 는 ApiResponse envelope 이 아닌 raw {ok}/{reset} 를
 * 반환하므로 postRaw(envelope unwrap 안 함)를 쓴다. 두 엔드포인트 모두 permitAll.
 * confirm 의 400(만료/소비/약한 비번)은 postRaw 가 ApiError 로 throw(본문 message 보존).
 */
export const passwordResetApi = {
  request: (email: string) =>
    api.postRaw<{ ok: boolean }>('/admin/api/password-reset/request', { email }),
  confirm: (token: string, newPassword: string) =>
    api.postRaw<{ reset: boolean }>('/admin/api/password-reset/confirm', { token, newPassword }),
};
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin-ui && npx vitest run src/api/passwordReset.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add admin-ui/src/api/passwordReset.ts admin-ui/src/api/passwordReset.test.ts
git commit -m "feat(admin-ui): password-reset API 모듈 (request/confirm, raw envelope)"
```

---

### Task 2: ForgotPasswordPage

**Files:**
- Create: `admin-ui/src/pages/ForgotPasswordPage.tsx`

Enumeration 방지: 백엔드가 항상 200을 주므로 UI도 성공/실패 구분 없이 **항상 동일 안내 메시지**를 보여준다. 제출 후에는 안내 화면으로 전환한다.

- [ ] **Step 1: Write the component**

```tsx
// admin-ui/src/pages/ForgotPasswordPage.tsx
import { useState } from 'react';
import { passwordResetApi } from '@/api/passwordReset';

/**
 * 미인증 public 화면 — 이메일로 재설정 링크 요청.
 * enumeration 방지: 항상 동일한 "메일을 보냈습니다" 안내(계정 존재 비노출).
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
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: 'var(--bg)' }}>
      <div style={{ width: 360, padding: 28, border: '1px solid var(--border)', borderRadius: 14, background: 'var(--surface)' }}>
        <h2 style={{ marginTop: 0, fontSize: 20 }}>비밀번호 재설정</h2>
        {sent ? (
          <>
            <div style={{ padding: '12px 14px', background: 'var(--info-soft)', color: 'var(--info)', borderRadius: 8, fontSize: 13, lineHeight: 1.6 }}>
              해당 이메일이 등록돼 있다면 재설정 링크를 보냈습니다. 메일함을 확인하세요.
            </div>
            <a href="/admin/login" className="btn btn--ghost btn--sm" style={{ width: '100%', marginTop: 16, justifyContent: 'center' }}>← 로그인으로</a>
          </>
        ) : (
          <form onSubmit={submit}>
            <div style={{ fontSize: 13, color: 'var(--text-mute)', margin: '6px 0 18px' }}>
              가입한 이메일을 입력하면 재설정 링크를 보냅니다.
            </div>
            <label className="label">이메일</label>
            <input
              className="input"
              type="email"
              autoFocus
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@crosscert.com"
              style={{ width: '100%' }}
            />
            <button type="submit" className="btn btn--primary" disabled={submitting || !email.trim()} style={{ width: '100%', marginTop: 16, justifyContent: 'center' }}>
              {submitting ? '전송 중…' : '재설정 링크 보내기'}
            </button>
            <a href="/admin/login" className="btn btn--ghost btn--sm" style={{ width: '100%', marginTop: 8, justifyContent: 'center' }}>← 로그인으로</a>
          </form>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Verify build**

Run: `cd admin-ui && npx tsc -b`
Expected: EXIT 0 (컴포넌트는 아직 라우팅 미연결 — 빌드만 통과).

- [ ] **Step 3: Commit**

```bash
git add admin-ui/src/pages/ForgotPasswordPage.tsx
git commit -m "feat(admin-ui): ForgotPasswordPage (이메일 요청, enumeration-safe)"
```

---

### Task 3: ResetPasswordPage

**Files:**
- Create: `admin-ui/src/pages/ResetPasswordPage.tsx`

토큰은 URL 쿼리(`?token=`)로. 복잡도/길이 정책은 백엔드 검증에 위임 — 400 응답 `message`를 인라인 표시. 클라이언트 검증은 두 입력 일치 + 비어있지 않음만.

- [ ] **Step 1: Write the component**

```tsx
// admin-ui/src/pages/ResetPasswordPage.tsx
import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { passwordResetApi } from '@/api/passwordReset';
import { ApiError } from '@/api/types';

/**
 * 미인증 public 화면 — 이메일 링크의 ?token= 으로 진입해 새 비밀번호 설정.
 * 비번 복잡도/길이는 백엔드 검증(400 message 표시). 클라이언트는 일치/비어있음만.
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
  const canSubmit = !!token && pw.length > 0 && pw === pw2 && !submitting;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      await passwordResetApi.confirm(token!, pw);
      setDone(true);
    } catch (err: unknown) {
      const msg = err instanceof ApiError ? err.message : '비밀번호 변경에 실패했습니다.';
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: 'var(--bg)' }}>
      <div style={{ width: 360, padding: 28, border: '1px solid var(--border)', borderRadius: 14, background: 'var(--surface)' }}>
        <h2 style={{ marginTop: 0, fontSize: 20 }}>새 비밀번호 설정</h2>

        {!token ? (
          <>
            <div style={{ padding: '12px 14px', background: 'var(--danger-soft)', color: 'var(--danger)', borderRadius: 8, fontSize: 13 }}>
              유효하지 않은 링크입니다. 재설정을 다시 요청하세요.
            </div>
            <a href="/admin/login" className="btn btn--ghost btn--sm" style={{ width: '100%', marginTop: 16, justifyContent: 'center' }}>← 로그인으로</a>
          </>
        ) : done ? (
          <>
            <div style={{ padding: '12px 14px', background: 'var(--success-soft)', color: 'var(--success)', borderRadius: 8, fontSize: 13 }}>
              비밀번호가 변경되었습니다. 새 비밀번호로 로그인하세요.
            </div>
            <a href="/admin/login" className="btn btn--primary btn--sm" style={{ width: '100%', marginTop: 16, justifyContent: 'center' }}>로그인 →</a>
          </>
        ) : (
          <form onSubmit={submit}>
            <div style={{ fontSize: 13, color: 'var(--text-mute)', margin: '6px 0 18px' }}>새 비밀번호를 입력하세요.</div>
            <label className="label">새 비밀번호</label>
            <input className="input" type="password" autoFocus value={pw} onChange={(e) => setPw(e.target.value)} autoComplete="new-password" style={{ width: '100%', marginBottom: 10 }} />
            <label className="label">새 비밀번호 확인</label>
            <input className="input" type="password" value={pw2} onChange={(e) => setPw2(e.target.value)} autoComplete="new-password" style={{ width: '100%' }} />
            {mismatch && <div style={{ color: 'var(--danger)', fontSize: 12, marginTop: 6 }}>두 비밀번호가 일치하지 않습니다.</div>}
            {error && <div style={{ color: 'var(--danger)', fontSize: 12, marginTop: 6 }}>{error}</div>}
            <button type="submit" className="btn btn--primary" disabled={!canSubmit} style={{ width: '100%', marginTop: 16, justifyContent: 'center' }}>
              {submitting ? '변경 중…' : '비밀번호 변경'}
            </button>
          </form>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Verify build**

Run: `cd admin-ui && npx tsc -b`
Expected: EXIT 0.

- [ ] **Step 3: Commit**

```bash
git add admin-ui/src/pages/ResetPasswordPage.tsx
git commit -m "feat(admin-ui): ResetPasswordPage (token + 새 비번, 백엔드 검증 위임)"
```

---

### Task 4: App.tsx public route 분기 + LoginPage 링크

**Files:**
- Modify: `admin-ui/src/App.tsx` (App 함수 — `admin-ui/src/App.tsx:237`)
- Modify: `admin-ui/src/pages/LoginPage.tsx:138-141` (더미 링크 교체)

public 경로는 me fetch를 기다리지 않고 즉시 렌더해야 한다(로그인 안 한 사용자가 메일 링크로 진입). `App` 함수 최상단에서 `useLocation().pathname` 으로 분기한다. basename `/admin` 이 react-router에 의해 벗겨지므로 pathname은 `/forgot-password`·`/reset-password`.

- [ ] **Step 1: import 추가 (App.tsx 상단 import 블록)**

`admin-ui/src/App.tsx` 의 import 영역(`import { Routes, Route, Navigate, useLocation, useNavigate } from 'react-router-dom';` 는 이미 있음 — `useLocation` 재사용)에 페이지 import 추가:

```tsx
import ForgotPasswordPage from '@/pages/ForgotPasswordPage';
import ResetPasswordPage from '@/pages/ResetPasswordPage';
```

- [ ] **Step 2: App 함수 최상단에 public 분기 추가**

`admin-ui/src/App.tsx:237` 의 `function App() {` 본문에서, `const [me, setMe] = useState...` 선언 **직후**(useEffect 위)에 다음을 추가:

```tsx
  const location = useLocation();

  // ── Public (미인증) 경로 — me fetch 와 무관하게 즉시 렌더 ──────────────────
  // 비밀번호 재설정은 로그인 전 + 이메일 링크의 ?token= 으로 진입한다.
  if (location.pathname === '/forgot-password') {
    return <ForgotPasswordPage />;
  }
  if (location.pathname === '/reset-password') {
    return <ResetPasswordPage />;
  }
```

주의: 이 분기는 `useState`/`useEffect` 호출 **뒤**, 조건부 return 으로 둔다. React Hook 규칙상 hook 호출은 early return 위에 있어야 하므로, `useLocation()` 도 다른 hook과 함께 상단에서 호출하고 분기 return 만 그 아래 둔다. `useEffect`(me fetch)는 분기 return 보다 위에 선언되어 있어야 hook 순서가 안정적이다 — 따라서 **`useEffect` 와 `useLocation` 선언을 모두 분기 return 위에 두고, 분기 return 은 `if (loading)` 위에 배치**한다.

최종 App 함수 상단 순서:
```tsx
function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);
  const location = useLocation();

  useEffect(() => {
    api.get<Me>('/admin/api/me')
      .then(setMe)
      .catch(() => { /* 인증 안됨 — LoginPage 표시 */ })
      .finally(() => setLoading(false));
  }, []);

  // public 경로 — me 로딩과 무관하게 즉시 렌더
  if (location.pathname === '/forgot-password') return <ForgotPasswordPage />;
  if (location.pathname === '/reset-password') return <ResetPasswordPage />;

  async function handleLogout() { /* 기존 그대로 */ }
  // ... 이하 기존 코드(reloadMe, if loading, if !me, ...)
}
```

- [ ] **Step 3: LoginPage 더미 링크를 실제 링크로 교체**

`admin-ui/src/pages/LoginPage.tsx:140` 의 더미 링크:
```tsx
<a href="#" style={{ fontSize: 11, color: 'var(--accent)', textDecoration: 'none' }}>관리자에게 재설정 요청</a>
```
를 다음으로 교체:
```tsx
<a href="/admin/forgot-password" style={{ fontSize: 11, color: 'var(--accent)', textDecoration: 'none' }}>비밀번호를 잊으셨나요?</a>
```
(href는 브라우저 절대경로라 basename `/admin` 포함.)

- [ ] **Step 4: Verify build + tests**

Run: `cd admin-ui && npx tsc -b && npm test`
Expected: tsc EXIT 0, vitest 15 tests green (기존 12 + Task1의 3).

- [ ] **Step 5: Commit**

```bash
git add admin-ui/src/App.tsx admin-ui/src/pages/LoginPage.tsx
git commit -m "feat(admin-ui): password-reset public route 분기 + LoginPage 링크"
```

---

## Phase 2 — MFA 복구 코드

### Task 5: mfa.ts 응답 타입 확장

**Files:**
- Modify: `admin-ui/src/api/mfa.ts:35-37` (confirm/verify 응답 타입)

백엔드 `MfaController` 실제 응답: `confirm` → `{confirmed:true, recoveryCodes:[...10개]}`(`MfaController.java:141`), `verify` → 복구 코드 사용 시 `{verified:true, usedRecoveryCode:true, remaining:N}`(`MfaController.java:80`).

- [ ] **Step 1: confirm/verify 타입 확장**

`admin-ui/src/api/mfa.ts` 의 `mfaApi` 객체에서 두 줄 교체:

```ts
// 기존:
//   confirm: (code: string) => mfaPost<{ confirmed: boolean }>('/admin/api/mfa/confirm', { code }),
//   verify: (code: string) => mfaPost<{ verified: boolean }>('/admin/api/mfa/verify', { code }),
// 교체:
  confirm: (code: string) =>
    mfaPost<{ confirmed: boolean; recoveryCodes: string[] }>('/admin/api/mfa/confirm', { code }),
  verify: (code: string) =>
    mfaPost<{ verified: boolean; usedRecoveryCode?: boolean; remaining?: number }>('/admin/api/mfa/verify', { code }),
```

(`disable`/`enroll` 은 그대로.)

- [ ] **Step 2: Verify build**

Run: `cd admin-ui && npx tsc -b`
Expected: EXIT 0.

- [ ] **Step 3: Commit**

```bash
git add admin-ui/src/api/mfa.ts
git commit -m "feat(admin-ui): mfa confirm/verify 응답 타입 확장 (recoveryCodes/remaining)"
```

---

### Task 6: RecoveryCodesModal

**Files:**
- Create: `admin-ui/src/pages/settings/RecoveryCodesModal.tsx`
- Test: `admin-ui/src/pages/settings/RecoveryCodesModal.test.tsx`

복구 코드 10개를 1회 표시. "저장했습니다" 체크 전 닫기 비활성(IssuedKeyModal 패턴). 복사 필수, 다운로드(.txt)/인쇄 간단 구현.

- [ ] **Step 1: Write the failing test**

```tsx
// admin-ui/src/pages/settings/RecoveryCodesModal.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { RecoveryCodesModal } from './RecoveryCodesModal';

const CODES = ['3f9a-2b71', '8c4d-9e02', 'a17b-44ff', '6620-d3a9', 'e591-7c08',
               '1bd4-aa3e', '9038-5f6c', '72e1-0b8d', 'c4a6-118f', '5d20-93b7'];

describe('RecoveryCodesModal', () => {
  it('renders all codes', () => {
    render(<RecoveryCodesModal codes={CODES} onClose={() => {}} />);
    for (const c of CODES) expect(screen.getByText(c)).toBeInTheDocument();
  });

  it('close button is disabled until saved-checkbox is checked', () => {
    const onClose = vi.fn();
    render(<RecoveryCodesModal codes={CODES} onClose={onClose} />);
    const closeBtn = screen.getByRole('button', { name: /닫기|확인/ });
    expect(closeBtn).toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox'));
    expect(closeBtn).not.toBeDisabled();
    fireEvent.click(closeBtn);
    expect(onClose).toHaveBeenCalledOnce();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin-ui && npx vitest run src/pages/settings/RecoveryCodesModal.test.tsx`
Expected: FAIL — `Cannot find module './RecoveryCodesModal'`.

- [ ] **Step 3: Write the component**

```tsx
// admin-ui/src/pages/settings/RecoveryCodesModal.tsx
import { useState } from 'react';
import { Dialog } from '@/shell/Dialog';
import { Icons } from '@/icons/Icons';

/**
 * MFA 복구 코드 1회 표시 모달. confirm 직후 enroll 응답의 recoveryCodes 를 보여준다.
 * "저장했습니다" 체크 전에는 닫기 불가(IssuedKeyModal 패턴). 닫으면 영구히 다시 못 봄.
 */
export function RecoveryCodesModal({ codes, onClose }: { codes: string[]; onClose: () => void }) {
  const [checked, setChecked] = useState(false);
  const [copied, setCopied] = useState(false);
  const text = codes.join('\n');

  function copy() {
    navigator.clipboard?.writeText(text);
    setCopied(true);
  }
  function download() {
    const blob = new Blob([text + '\n'], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'recovery-codes.txt';
    a.click();
    URL.revokeObjectURL(url);
  }
  function print() {
    const w = window.open('', '_blank');
    if (!w) return;
    w.document.write('<pre style="font-size:16px;line-height:2">' + codes.join('\n') + '</pre>');
    w.document.close();
    w.print();
  }

  return (
    <Dialog open onClose={() => { /* enforce */ }} closeOnScrim={false}
      title={<span style={{ display: 'flex', alignItems: 'center', gap: 8 }}><Icons.Alert size={18} /> 복구 코드 — 지금만 표시됩니다</span>}
      sub="인증 기기를 잃었을 때 로그인하는 유일한 방법입니다. 안전한 곳에 보관하세요."
      footer={
        <button className="btn btn--primary" disabled={!checked} onClick={onClose}>
          {checked ? '닫기' : '체크 필요'}
        </button>
      }
    >
      <div className="stack-3">
        <div style={{
          display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6,
          fontFamily: 'var(--mono)', fontSize: 13,
          background: 'var(--surface-3)', padding: 12, borderRadius: 8, border: '1px solid var(--border)',
        }}>
          {codes.map((c) => <span key={c}>{c}</span>)}
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn btn--sm" onClick={copy} style={{ flex: 1 }}>
            {copied ? <><Icons.Check size={12} /> 복사됨</> : <><Icons.Copy size={12} /> 복사</>}
          </button>
          <button className="btn btn--sm" onClick={download} style={{ flex: 1 }}>다운로드 (.txt)</button>
          <button className="btn btn--sm" onClick={print} style={{ flex: 1 }}>인쇄</button>
        </div>
        <label style={{ display: 'flex', gap: 10, padding: 12, background: checked ? 'var(--success-soft)' : 'var(--warning-soft)', borderRadius: 8, alignItems: 'center', cursor: 'pointer' }}>
          <input type="checkbox" checked={checked} onChange={(e) => setChecked(e.target.checked)} />
          <span style={{ fontSize: 13, fontWeight: 600, color: checked ? 'var(--success)' : 'var(--warning)' }}>안전한 곳에 저장했습니다.</span>
        </label>
      </div>
    </Dialog>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin-ui && npx vitest run src/pages/settings/RecoveryCodesModal.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add admin-ui/src/pages/settings/RecoveryCodesModal.tsx admin-ui/src/pages/settings/RecoveryCodesModal.test.tsx
git commit -m "feat(admin-ui): RecoveryCodesModal (10개 1회 표시, 저장 체크 게이팅)"
```

---

### Task 7: AccountTab confirm 후 복구 코드 표시

**Files:**
- Modify: `admin-ui/src/pages/settings/AccountTab.tsx`

`confirmEnroll()` 이 confirm 응답의 `recoveryCodes` 를 캡처해 모달로 표시. 현재는 응답을 버림(`AccountTab.tsx:49`).

- [ ] **Step 1: import + state 추가**

`AccountTab.tsx` 상단 import에 추가:
```tsx
import { RecoveryCodesModal } from './RecoveryCodesModal';
```

컴포넌트 본문 state 영역(`const [confirming, setConfirming] = useState(false);` 뒤)에 추가:
```tsx
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
```

- [ ] **Step 2: confirmEnroll 에서 응답 캡처**

`AccountTab.tsx:46-65` 의 `confirmEnroll` 본문에서 `await mfaApi.confirm(enrollCode);` 줄을 다음으로 교체:
```tsx
      const res = await mfaApi.confirm(enrollCode);
```
그리고 같은 try 블록의 성공 처리(`setEnroll(null); setEnrollCode(''); onMeChange(await getMe()); toast({...});`) **뒤**에 추가:
```tsx
      if (res.recoveryCodes && res.recoveryCodes.length > 0) {
        setRecoveryCodes(res.recoveryCodes);
      }
```

- [ ] **Step 3: 모달 렌더**

`AccountTab.tsx` 의 return JSX 최하단(disable `</Dialog>` 닫힘 **뒤**, 최상위 `</div>` 닫힘 **앞**)에 추가:
```tsx
      {recoveryCodes && (
        <RecoveryCodesModal codes={recoveryCodes} onClose={() => setRecoveryCodes(null)} />
      )}
```

- [ ] **Step 4: Verify build + tests**

Run: `cd admin-ui && npx tsc -b && npm test`
Expected: tsc EXIT 0, vitest 17 tests green (이전 15 + Task6의 2).

- [ ] **Step 5: Commit**

```bash
git add admin-ui/src/pages/settings/AccountTab.tsx
git commit -m "feat(admin-ui): MFA 켜기 confirm 후 복구 코드 모달 표시"
```

---

### Task 8: MfaChallenge 복구 코드 로그인

**Files:**
- Modify: `admin-ui/src/pages/MfaChallenge.tsx`

TOTP 입력에 "복구 코드로 로그인" 토글 추가. 복구 코드 모드에서는 숫자-only sanitize 해제. 같은 `verify` 호출(백엔드 자동 판별). 성공 후 `usedRecoveryCode && remaining` 이면 남은 개수 토스트.

- [ ] **Step 1: 컴포넌트 교체**

`admin-ui/src/pages/MfaChallenge.tsx` 전체를 다음으로 교체:

```tsx
import { useState } from 'react';
import { mfaApi } from '@/api/mfa';
import { useToast } from '@/shell/ToastHost';

export default function MfaChallenge({ onVerified, onLogout }: { onVerified: () => void; onLogout: () => void }) {
  const [mode, setMode] = useState<'totp' | 'recovery'>('totp');
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const toast = useToast();

  const ready = mode === 'totp' ? code.length === 6 : code.trim().length > 0;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting || !ready) return;
    setSubmitting(true);
    try {
      const res = await mfaApi.verify(code.trim());
      if (res.usedRecoveryCode && typeof res.remaining === 'number') {
        toast({ kind: 'warn', title: '복구 코드를 사용했습니다.', message: `남은 복구 코드: ${res.remaining}개` });
      }
      onVerified();
    } catch {
      toast({ kind: 'err', title: '인증 실패', message: mode === 'totp' ? '코드가 올바르지 않습니다.' : '복구 코드가 올바르지 않습니다.' });
      setCode('');
    } finally {
      setSubmitting(false);
    }
  }

  function switchMode(next: 'totp' | 'recovery') {
    setMode(next);
    setCode('');
  }

  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: 'var(--bg)' }}>
      <form onSubmit={submit} style={{ width: 360, padding: 28, border: '1px solid var(--border)', borderRadius: 14, background: 'var(--surface)' }}>
        <h2 style={{ marginTop: 0, fontSize: 18 }}>2단계 인증</h2>
        <div style={{ fontSize: 13, color: 'var(--text-mute)', marginBottom: 18 }}>
          {mode === 'totp'
            ? 'authenticator 앱에 표시된 6자리 코드를 입력하세요.'
            : '복구 코드(xxxx-xxxx)를 입력하세요. 1회용입니다.'}
        </div>
        {mode === 'totp' ? (
          <input
            className="input"
            inputMode="numeric"
            autoFocus
            maxLength={6}
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
            placeholder="000000"
            style={{ width: '100%', fontFamily: 'monospace', letterSpacing: 4, textAlign: 'center', fontSize: 18 }}
          />
        ) : (
          <input
            className="input mono"
            autoFocus
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder="3f9a-2b71"
            style={{ width: '100%', fontFamily: 'monospace', textAlign: 'center', fontSize: 16 }}
          />
        )}
        <button type="submit" className="btn btn--primary" disabled={submitting || !ready} style={{ width: '100%', marginTop: 16 }}>
          {submitting ? '확인 중…' : '확인'}
        </button>
        <button
          type="button"
          className="btn btn--ghost btn--sm"
          onClick={() => switchMode(mode === 'totp' ? 'recovery' : 'totp')}
          style={{ width: '100%', marginTop: 8 }}
        >
          {mode === 'totp' ? '기기를 잃으셨나요? 복구 코드로 로그인' : '← authenticator 코드로 돌아가기'}
        </button>
        <button type="button" className="btn btn--ghost btn--sm" onClick={onLogout} style={{ width: '100%', marginTop: 8 }}>
          로그아웃
        </button>
      </form>
    </div>
  );
}
```

- [ ] **Step 2: Verify build + tests**

Run: `cd admin-ui && npx tsc -b && npm test`
Expected: tsc EXIT 0, vitest 17 tests green (회귀 없음).

- [ ] **Step 3: Commit**

```bash
git add admin-ui/src/pages/MfaChallenge.tsx
git commit -m "feat(admin-ui): MFA 챌린지에 복구 코드 로그인 토글"
```

---

## Phase 3 — API key scope + rotate (+ 버그 수정)

### Task 9: apiKeys.ts — scope 인자 + rotate + 'ceremony' 버그 수정

**Files:**
- Modify: `admin-ui/src/api/apiKeys.ts`
- Modify: `admin-ui/src/api/types.ts` (`ApiKeyRotateResponse` 추가)
- Modify: `admin-ui/src/api/designTypes.ts:19-26` (`ApiKey` 에 `scopes` 추가)
- Test: `admin-ui/src/api/apiKeys.test.ts`

**버그 수정:** 현재 `create` 가 `scopes: ['ceremony']` 하드코딩 — 백엔드 whitelist(`registration`/`authentication`/`admin`)에 없어 400. scope 를 인자로 받게 하고 호출부(ApiKeysTab)가 실제 값을 넘긴다.

- [ ] **Step 1: types.ts 에 ApiKeyRotateResponse 추가**

`admin-ui/src/api/types.ts` 의 `ApiKeyCreateResponse` 인터페이스(`types.ts:61-66`) **뒤**에 추가:

```ts
export interface ApiKeyRotateResponse {
  id: string;
  plaintextKey: string;   // ONE-TIME
  prefix: string;
  scopes: string[];
  oldKeyExpiresAt: string; // ISO instant
}
```

- [ ] **Step 2: designTypes.ts ApiKey 에 scopes 추가**

`admin-ui/src/api/designTypes.ts:19-26` 의 `ApiKey` 타입에 `scopes` 필드 추가:

```ts
export type ApiKey = {
  id: string;
  prefix: string;
  name: string;
  status: 'ACTIVE' | 'REVOKED';
  createdAt: string;
  lastUsedAt: string | null;
  scopes: string[];
};
```

- [ ] **Step 3: Write the failing test**

```ts
// admin-ui/src/api/apiKeys.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { apiKeysApi } from './apiKeys';

function envelope(data: unknown) {
  return new Response(JSON.stringify({ success: true, data }), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  });
}

describe('apiKeysApi', () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
  afterEach(() => vi.unstubAllGlobals());

  it('create sends provided scopes (not hardcoded ceremony)', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      envelope({ id: 'k1', prefix: 'pk_x', plainText: 'pk_x.secret', scopes: ['registration'] }),
    );
    await apiKeysApi.create('t1', 'prod', ['registration', 'authentication']);
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('/admin/api/api-keys');
    const body = JSON.parse(init.body);
    expect(body.scopes).toEqual(['registration', 'authentication']);
    expect(body.scopes).not.toContain('ceremony');
  });

  it('rotate posts to /{id}/rotate and returns rotate response', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      envelope({ id: 'k1', plaintextKey: 'pk_x.new', prefix: 'pk_x', scopes: ['registration'], oldKeyExpiresAt: '2026-06-01T05:32:00Z' }),
    );
    const res = await apiKeysApi.rotate('k1');
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('/admin/api/api-keys/k1/rotate');
    expect(init.method).toBe('POST');
    expect(res.plaintextKey).toBe('pk_x.new');
    expect(res.oldKeyExpiresAt).toBe('2026-06-01T05:32:00Z');
  });
});
```

- [ ] **Step 4: Run test to verify it fails**

Run: `cd admin-ui && npx vitest run src/api/apiKeys.test.ts`
Expected: FAIL — `create` 가 3번째 인자(scopes)를 받지 않음 / `rotate` 미정의.

- [ ] **Step 5: apiKeys.ts 교체**

`admin-ui/src/api/apiKeys.ts` 의 `adapt` 함수에 scopes 매핑 추가하고, `apiKeysApi` 객체를 교체:

`adapt` 함수 return 에 `scopes` 추가:
```ts
function adapt(s: ApiKeyView): ApiKey {
  return {
    id: s.id,
    prefix: s.keyPrefix,
    name: s.name,
    status: s.revokedAt ? 'REVOKED' : 'ACTIVE',
    createdAt: s.createdAt,
    lastUsedAt: s.lastUsedAt ?? null,
    scopes: s.scopes ?? [],
  };
}
```

import 에 `ApiKeyRotateResponse` 추가:
```ts
import type { ApiKeyView, ApiKeyCreateRequest, ApiKeyCreateResponse, ApiKeyRotateResponse } from './types';
```

`create` 와 `rotate`:
```ts
  create: async (
    tenantId: string,
    name: string,
    scopes: string[],
  ): Promise<{ key: ApiKey; plaintext: string }> => {
    const body: ApiKeyCreateRequest = { tenantId, name, scopes };
    const res = await api.post<ApiKeyCreateResponse>('/admin/api/api-keys', body);
    const key: ApiKey = {
      id: res.id,
      prefix: res.prefix,
      name,
      status: 'ACTIVE',
      createdAt: new Date().toISOString(),
      lastUsedAt: null,
      scopes: res.scopes ?? scopes,
    };
    return { key, plaintext: res.plainText };
  },

  rotate: async (id: string): Promise<ApiKeyRotateResponse> => {
    return api.post<ApiKeyRotateResponse>(
      `/admin/api/api-keys/${encodeURIComponent(id)}/rotate`, {},
    );
  },
```
(`list`/`revoke` 는 그대로.)

- [ ] **Step 6: Run test to verify it passes**

Run: `cd admin-ui && npx vitest run src/api/apiKeys.test.ts`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add admin-ui/src/api/apiKeys.ts admin-ui/src/api/types.ts admin-ui/src/api/designTypes.ts admin-ui/src/api/apiKeys.test.ts
git commit -m "fix(admin-ui): api-key scope 인자화('ceremony' 버그 제거) + rotate 추가"
```

---

### Task 10: ApiKeysTab — scope 체크박스 + rotate 버튼

**Files:**
- Modify: `admin-ui/src/pages/tenant/ApiKeysTab.tsx`

발급 다이얼로그에 scope 체크박스(registration/authentication, admin 숨김). 행에 rotate 버튼 + 확인 다이얼로그. IssuedKeyModal 에 구 키 만료 배너(rotate 시).

- [ ] **Step 1: SCOPES 상수 + handleIssue/handleRotate 수정**

`ApiKeysTab.tsx` 의 `ApiKeysTab` 컴포넌트 위(파일 상단 utility 영역)에 추가:
```tsx
const SCOPE_OPTIONS: { value: string; label: string; desc: string }[] = [
  { value: 'registration', label: 'registration', desc: '패스키 등록 + self-service credential 관리' },
  { value: 'authentication', label: 'authentication', desc: '패스키 인증(로그인)' },
];
```

`ApiKeysTab` 함수 내 state 에 rotate 관련 추가(`const [revoking, ...]` 뒤):
```tsx
  const [rotating, setRotating] = useState<ApiKey | null>(null);
  const [issued, setIssued] = useState<{ key: ApiKey; plaintext: string; oldKeyExpiresAt?: string } | null>(null);
```
(기존 `issued` state 선언은 제거 — 위 한 줄로 대체. 타입에 `oldKeyExpiresAt?` 추가됨.)

`handleIssue` 시그니처/본문 교체(scopes 인자 받음):
```tsx
  async function handleIssue(name: string, scopes: string[]) {
    try {
      const result = await apiKeysApi.create(tenant.id, name, scopes);
      setShowNew(false);
      setIssued(result);
      await reload();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '발급 실패', message: msg });
    }
  }
```

`handleRotate` 추가(`handleRevoke` 뒤):
```tsx
  async function handleRotate(k: ApiKey) {
    try {
      const res = await apiKeysApi.rotate(k.id);
      setRotating(null);
      setIssued({
        key: { ...k, prefix: res.prefix, scopes: res.scopes },
        plaintext: res.plaintextKey,
        oldKeyExpiresAt: res.oldKeyExpiresAt,
      });
      await reload();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '회전 실패', message: msg });
    }
  }
```

- [ ] **Step 2: 행에 rotate 버튼 추가**

`ApiKeysTab.tsx` 의 테이블 액션 셀(`k.status === 'ACTIVE' && (...)` 회수 버튼)에서, 회수 버튼 **앞**에 rotate 버튼 추가:
```tsx
                  {k.status === 'ACTIVE' && (
                    <span style={{ display: 'inline-flex', gap: 6, justifyContent: 'flex-end' }}>
                      <button className="btn btn--xs" onClick={() => setRotating(k)}>
                        <Icons.Refresh size={12} /> 회전
                      </button>
                      <button className="btn btn--xs" onClick={() => setRevoking(k)} style={{ color: 'var(--danger)', borderColor: 'color-mix(in oklab, var(--danger) 30%, var(--border))' }}>
                        <Icons.Trash size={12} /> 회수
                      </button>
                    </span>
                  )}
```

- [ ] **Step 3: 다이얼로그 렌더 + RotateKeyDialog 추가**

`ApiKeysTab.tsx` return 의 다이얼로그 렌더 영역(`<NewKeyDialog .../>` ... `<RevokeKeyDialog .../>`)을 교체/추가:
```tsx
      <NewKeyDialog open={showNew} onClose={() => setShowNew(false)} onIssue={handleIssue} />
      <IssuedKeyModal issued={issued} onClose={() => { setIssued(null); }} />
      <RotateKeyDialog k={rotating} onClose={() => setRotating(null)} onConfirm={handleRotate} />
      <RevokeKeyDialog k={revoking} onClose={() => setRevoking(null)} onConfirm={handleRevoke} />
```

- [ ] **Step 4: NewKeyDialog 에 scope 체크박스**

`NewKeyDialog` 컴포넌트를 교체:
```tsx
function NewKeyDialog({ open, onClose, onIssue }: {
  open: boolean;
  onClose: () => void;
  onIssue: (name: string, scopes: string[]) => void;
}) {
  const [name, setName] = useState('');
  const [scopes, setScopes] = useState<string[]>(['registration', 'authentication']);

  function toggle(v: string) {
    setScopes((prev) => prev.includes(v) ? prev.filter((s) => s !== v) : [...prev, v]);
  }
  function submit() {
    if (!name || scopes.length === 0) return;
    onIssue(name, scopes);
    setName('');
    setScopes(['registration', 'authentication']);
  }

  return (
    <Dialog open={open} onClose={onClose} title="새 API key 발급"
      sub="발급 후 plaintext는 단 한 번만 노출됩니다. 안전한 장소에 즉시 보관하세요."
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn--primary" disabled={!name || scopes.length === 0} onClick={submit}>발급</button>
      </>}
    >
      <Field label="용도 (이름)" hint="배포 환경이나 용도를 짧게. 예: production, staging, mobile-app">
        <input autoFocus className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="production" />
      </Field>
      <div style={{ marginTop: 14 }}>
        <label className="label">권한 범위 (scope) — 하나 이상</label>
        <div className="stack-2" style={{ marginTop: 6 }}>
          {SCOPE_OPTIONS.map((o) => (
            <label key={o.value} style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: '8px 10px', border: '1px solid var(--border)', borderRadius: 8, cursor: 'pointer', background: scopes.includes(o.value) ? 'var(--accent-soft)' : 'transparent' }}>
              <input type="checkbox" checked={scopes.includes(o.value)} onChange={() => toggle(o.value)} style={{ marginTop: 3 }} />
              <div>
                <div style={{ fontWeight: 600, fontSize: 13 }}>{o.label}</div>
                <div className="muted" style={{ fontSize: 12 }}>{o.desc}</div>
              </div>
            </label>
          ))}
        </div>
      </div>
    </Dialog>
  );
}
```

- [ ] **Step 5: IssuedKeyModal 에 만료 배너 + RotateKeyDialog 신규**

`IssuedKeyModal` 의 props 와 표시를 교체(`issued` 타입에 `oldKeyExpiresAt?` 추가, 있으면 배너):

`IssuedKeyModal` 시그니처:
```tsx
function IssuedKeyModal({ issued, onClose }: {
  issued: { key: ApiKey; plaintext: string; oldKeyExpiresAt?: string } | null;
  onClose: () => void;
}) {
```
그리고 `IssuedKeyModal` 의 `<div className="stack-3">` 안 맨 위(발급 완료 badge 행 **앞**)에 만료 배너 추가:
```tsx
        {issued.oldKeyExpiresAt && (
          <div style={{ padding: '8px 10px', background: 'var(--warning-soft)', color: 'var(--warning)', borderRadius: 8, fontSize: 12, display: 'flex', gap: 8 }}>
            <Icons.Alert size={14} />
            <span>구 키는 <b>{fmtDateTime(issued.oldKeyExpiresAt)}</b>에 만료됩니다. 그 전에 RP 서버를 새 키로 교체하세요.</span>
          </div>
        )}
```
모달 제목도 rotate 면 다르게(선택, 간단히 유지): 기존 제목 그대로 둬도 무방.

`RevokeKeyDialog` **뒤**에 `RotateKeyDialog` 추가:
```tsx
function RotateKeyDialog({ k, onClose, onConfirm }: {
  k: ApiKey | null;
  onClose: () => void;
  onConfirm: (k: ApiKey) => void;
}) {
  if (!k) return null;
  return (
    <Dialog open onClose={onClose} title="API key를 회전하시겠습니까?"
      sub="같은 권한의 새 키가 즉시 발급됩니다. 구 키는 24시간 후 만료됩니다."
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn--primary" onClick={() => onConfirm(k)}>회전 실행</button>
      </>}
    >
      <div style={{ padding: 14, border: '1px solid var(--border)', borderRadius: 8, background: 'var(--surface-2)' }}>
        <div style={{ display: 'grid', gridTemplateColumns: '100px 1fr', rowGap: 8, fontSize: 13 }}>
          <div className="muted">prefix</div><div className="mono">{k.prefix}</div>
          <div className="muted">이름</div><div>{k.name}</div>
        </div>
      </div>
      <div style={{ marginTop: 12, padding: 10, background: 'var(--info-soft)', color: 'var(--info)', borderRadius: 6, fontSize: 12, display: 'flex', gap: 8 }}>
        <Icons.Info size={14} />
        <span>새 키는 발급 직후 이 화면에서 한 번만 표시됩니다. RP 서버를 24시간 안에 교체하세요.</span>
      </div>
    </Dialog>
  );
}
```

- [ ] **Step 6: Verify build + tests**

Run: `cd admin-ui && npx tsc -b && npm test`
Expected: tsc EXIT 0, vitest 19 tests green (이전 17 + Task9의 2). `Icons.Refresh` 존재 확인됨(Icons.tsx 에 Refresh 있음).

- [ ] **Step 7: Commit**

```bash
git add admin-ui/src/pages/tenant/ApiKeysTab.tsx
git commit -m "feat(admin-ui): API key scope 선택 + rotate 버튼(구 키 만료 안내)"
```

---

### Task 11: 최종 검증 + followups

**Files:**
- Create: `docs/superpowers/followups/2026-05-31-admin-ui-followup-screens-followups.md`

- [ ] **Step 1: 전체 빌드 + 테스트**

Run: `cd admin-ui && npx tsc -b && npm test`
Expected: tsc EXIT 0, vitest 19 tests green.

- [ ] **Step 2: vite 프로덕션 빌드 (test 의존성 숨김 검증)**

Run: `cd admin-ui && npm run build`
Expected: EXIT 0 (tsc -b + vite build 둘 다 통과 — tsconfig exclude 가 test 파일 빼므로 vitest 미설치 환경에서도 빌드 가능).

- [ ] **Step 3: followups 작성**

```markdown
# admin-ui 후속 화면 (그룹 A·B UI) — Follow-ups

- **작성일**: 2026-05-31
- **브랜치**: `worktree-admin-ui-followup-screens`
- **spec**: [2026-05-31-admin-ui-followup-screens-design.md](../specs/2026-05-31-admin-ui-followup-screens-design.md)
- **plan**: [2026-05-31-admin-ui-followup-screens.md](../plans/2026-05-31-admin-ui-followup-screens.md)

3 Phase(password reset / MFA 복구 코드 / API key scope+rotate)를 11 Task로 마감. 백엔드 변경 없음. vitest 단위 테스트 추가(passwordReset 3 + RecoveryCodesModal 2 + apiKeys 2). 아래는 의도적으로 미룬 항목.

## 1. 버그 수정됨: api-key scope 'ceremony'
발급이 whitelist(registration/authentication/admin) 밖 'ceremony' 를 보내 400 으로 깨져 있던 것을 scope 인자화로 수정. **이전 발급 흐름이 실제로 동작했는지 운영 로그로 확인 권장**(seed 데이터는 직접 INSERT 였을 수 있음).

## 2. 미룬 항목 (범위 밖)
- MFA 복구 코드 **재발급**(이미 켠 상태에서 재생성): 백엔드 전용 엔드포인트 없음. 별도 phase.
- 그룹 C alert 메일 설정 UI: 운영 인프라(코드 밖)로 분류 — Prometheus/Grafana/Alertmanager + passkey.alert.mail.* 운영 설정.
- admin scope: ApiKeyScopeResolver 에 RP 경로 매핑 없어 UI 노출 안 함(dead scope). 향후 admin API 경로 생기면 추가.

## 3. 브라우저 dogfooding 미수행
vitest 단위 테스트로 검증. 실제 브라우저로 password-reset 흐름(메일 발송 환경 필요)·복구 코드 표시·rotate 모달 육안 확인은 로컬 서버+seed+SMTP 의존이라 미수행. 권고: 로컬에서 흐름 확인.

## 4. codex 독립 리뷰
6/1 quota 리셋 후 누적 diff 에 /codex review 권고. 특히 password-reset enumeration 동일 메시지, 복구 코드 1회 표시 게이팅, scope whitelist 일치.
```

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/followups/2026-05-31-admin-ui-followup-screens-followups.md
git commit -m "docs(followups): admin-ui 후속 화면 완료 기록"
```

---

## Self-Review 체크

- **Spec coverage**: Phase 1(Task 1-4) = A3, Phase 2(Task 5-8) = A1+A2, Phase 3(Task 9-10) = B2+B1+버그수정. deferred(재발급/alert UI/admin scope) 모두 spec §1 범위 밖과 일치.
- **Type consistency**: `ApiKeyRotateResponse.plaintextKey`/`oldKeyExpiresAt`(Task 9) ↔ ApiKeysTab `handleRotate`(Task 10) 일치. `create(tenantId, name, scopes)`(Task 9) ↔ `handleIssue(name, scopes)`→`apiKeysApi.create(tenant.id, name, scopes)`(Task 10) 일치. `ApiKey.scopes` 추가(Task 9) ↔ `issued.key.scopes`/adapt(Task 10) 일치. `RecoveryCodesModal({codes, onClose})`(Task 6) ↔ AccountTab 사용(Task 7) 일치. `mfaApi.confirm` recoveryCodes(Task 5) ↔ AccountTab `res.recoveryCodes`(Task 7) 일치. `mfaApi.verify` usedRecoveryCode/remaining(Task 5) ↔ MfaChallenge(Task 8) 일치.
- **Placeholder scan**: 모든 코드 단계에 실제 코드 포함. "기존 그대로" 표기는 변경 없는 함수 명시용.
