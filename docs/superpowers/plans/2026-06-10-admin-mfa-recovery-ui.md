# Admin "내 계정" MFA / 복구 코드 UI 개선 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** admin-ui "설정 → 내 계정"의 MFA/복구 코드 UX를 다듬는다 — 인쇄 제거, 다운로드 파일명 자동화, 복구코드 자동 대시, 2단계 인증 화면 시각 정돈.

**Architecture:** admin-ui(React 18 + Vite + TS) 프론트엔드만 변경. 백엔드(`RecoveryCodeService` 등) 무변경. 복구코드 포맷팅·파일명 생성은 순수 함수로 분리해 단위 테스트한다(Vitest + Testing Library). 디자인 변경은 기존 `tokens.css` 토큰/클래스만 사용한다.

**Tech Stack:** React 18, TypeScript, Vite, Vitest, @testing-library/react

**Spec:** `docs/superpowers/specs/2026-06-10-admin-mfa-recovery-ui-design.md`

---

## File Structure

- `admin-ui/src/lib/recoveryCode.ts` — **신규**. 복구코드 입력 포맷터(순수 함수). MfaChallenge 가 import.
- `admin-ui/src/lib/recoveryCode.test.ts` — **신규**. 포맷터 단위 테스트.
- `admin-ui/src/lib/recoveryCodeFilename.ts` — **신규**. 다운로드 파일명 생성(순수 함수).
- `admin-ui/src/lib/recoveryCodeFilename.test.ts` — **신규**. 파일명 생성 단위 테스트.
- `admin-ui/src/pages/settings/RecoveryCodesModal.tsx` — **수정**. 인쇄 제거, 파일명 prop 배선.
- `admin-ui/src/pages/settings/AccountTab.tsx` — **수정**. 모달에 email 전달, MFA 카드 시각 정돈.
- `admin-ui/src/pages/MfaChallenge.tsx` — **수정**. 복구코드 자동 대시, 챌린지 화면 시각 정돈.
- `admin-ui/src/pages/MfaChallenge.test.tsx` — **수정**. recovery 입력 동작 변경에 맞춰 기존 테스트 갱신.

작업 디렉터리는 항상 `admin-ui/`. 모든 명령은 `cd admin-ui` 후 실행하거나 `admin-ui` 안에서 실행한다.

---

## Task 1: 복구코드 입력 포맷터 (순수 함수, TDD)

백엔드 권위 포맷은 Base32(`ABCDEFGHJKLMNPQRSTUVWXYZ23456789`) 4-4 = `XXXX-XXXX`. 입력을 이 형태로 정형하는 순수 함수를 먼저 만든다.

**Files:**
- Create: `admin-ui/src/lib/recoveryCode.ts`
- Test: `admin-ui/src/lib/recoveryCode.test.ts`

- [ ] **Step 1: Write the failing test**

`admin-ui/src/lib/recoveryCode.test.ts`:
```ts
import { describe, it, expect } from 'vitest';
import { formatRecoveryCode } from './recoveryCode';

describe('formatRecoveryCode', () => {
  it('uppercases and inserts a dash after 4 chars', () => {
    expect(formatRecoveryCode('ab3f2k7m')).toBe('AB3F-2K7M');
  });

  it('keeps short input without a dash', () => {
    expect(formatRecoveryCode('ab3')).toBe('AB3');
    expect(formatRecoveryCode('ab3f')).toBe('AB3F');
  });

  it('inserts dash as soon as the 5th char arrives', () => {
    expect(formatRecoveryCode('ab3f2')).toBe('AB3F-2');
  });

  it('drops disallowed chars (0,1,I,O, symbols, spaces) and existing dashes', () => {
    expect(formatRecoveryCode('ab3f-2k7m')).toBe('AB3F-2K7M'); // user-typed dash ignored, re-derived
    expect(formatRecoveryCode('AB3F 2K7M')).toBe('AB3F-2K7M');
    expect(formatRecoveryCode('a@b#3$f2k7m')).toBe('AB3F-2K7M');
    expect(formatRecoveryCode('o0i1ab3f2')).toBe('AB3F-2'); // O,0,I,1 all removed
  });

  it('caps at 8 alphabet chars (XXXX-XXXX)', () => {
    expect(formatRecoveryCode('ab3f2k7mEXTRA')).toBe('AB3F-2K7M');
  });

  it('returns empty string for empty/all-invalid input', () => {
    expect(formatRecoveryCode('')).toBe('');
    expect(formatRecoveryCode('0011oo')).toBe('');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin-ui && npx vitest run src/lib/recoveryCode.test.ts`
Expected: FAIL — `formatRecoveryCode` is not defined / module not found.

- [ ] **Step 3: Write minimal implementation**

`admin-ui/src/lib/recoveryCode.ts`:
```ts
/**
 * 복구 코드 입력을 백엔드 권위 포맷(Base32 4-4, "XXXX-XXXX")으로 정형한다.
 *
 * 백엔드(RecoveryCodeService)는 알파벳 ABCDEFGHJKLMNPQRSTUVWXYZ23456789 (혼동 문자
 * I/O/0/1 제외)로 생성하고, consume() 의 normalize 는 대시를 제거하지 않으므로 검증이
 * 매칭되려면 입력이 정확히 대문자 "XXXX-XXXX" 여야 한다. 이 함수가 그 형태를 보장한다.
 *
 * - 대문자화 후 허용 문자(A-Z, 2-9)만 통과 → 소문자·기호·공백·혼동 문자(0,1,I,O) 제거
 * - 8자로 제한, 4자 초과 시 4번째 뒤 대시 1개 삽입
 * - 대시는 길이에서 파생되므로 백스페이스 시 자연히 사라진다
 */
export function formatRecoveryCode(input: string): string {
  const cleaned = input.toUpperCase().replace(/[^A-Z2-9]/g, '').slice(0, 8);
  return cleaned.length > 4 ? cleaned.slice(0, 4) + '-' + cleaned.slice(4) : cleaned;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin-ui && npx vitest run src/lib/recoveryCode.test.ts`
Expected: PASS — all 6 tests green.

- [ ] **Step 5: Commit**

```bash
cd admin-ui && git add src/lib/recoveryCode.ts src/lib/recoveryCode.test.ts
git commit -m "feat(admin-ui): 복구코드 입력 포맷터(자동 대시+대문자화) 순수 함수"
```

---

## Task 2: 다운로드 파일명 생성 (순수 함수, TDD)

`Me` 타입에는 `email` 만 있고 username/displayName 은 없다. 파일명 계정 세그먼트는 email local part 를 sanitize 해서 쓴다. 날짜는 로컬 기준 `YYYYMMDD`. 날짜를 인자로 주입해 테스트 가능하게 한다.

**Files:**
- Create: `admin-ui/src/lib/recoveryCodeFilename.ts`
- Test: `admin-ui/src/lib/recoveryCodeFilename.test.ts`

- [ ] **Step 1: Write the failing test**

`admin-ui/src/lib/recoveryCodeFilename.test.ts`:
```ts
import { describe, it, expect } from 'vitest';
import { recoveryCodesFilename } from './recoveryCodeFilename';

const DATE = new Date(2026, 5, 10); // 2026-06-10 local (month is 0-based)

describe('recoveryCodesFilename', () => {
  it('uses email local part + YYYYMMDD', () => {
    expect(recoveryCodesFilename('alice@corp.com', DATE))
      .toBe('passkey-admin-recovery-codes-alice-20260610.txt');
  });

  it('sanitizes account: lowercases, replaces unsafe chars, trims dashes', () => {
    expect(recoveryCodesFilename('Bob.Smith+tag@x.io', DATE))
      .toBe('passkey-admin-recovery-codes-bob.smith-tag-20260610.txt');
  });

  it('collapses runs of dashes and trims leading/trailing dashes', () => {
    expect(recoveryCodesFilename('--a  b--@x', DATE))
      .toBe('passkey-admin-recovery-codes-a-b-20260610.txt');
  });

  it('falls back to no account segment when email is empty', () => {
    expect(recoveryCodesFilename('', DATE))
      .toBe('passkey-admin-recovery-codes-20260610.txt');
  });

  it('falls back when sanitized local part is empty', () => {
    expect(recoveryCodesFilename('@@@@@@@', DATE))
      .toBe('passkey-admin-recovery-codes-20260610.txt');
  });

  it('zero-pads month and day', () => {
    expect(recoveryCodesFilename('a@x', new Date(2026, 0, 3)))
      .toBe('passkey-admin-recovery-codes-a-20260103.txt');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin-ui && npx vitest run src/lib/recoveryCodeFilename.test.ts`
Expected: FAIL — `recoveryCodesFilename` is not defined.

- [ ] **Step 3: Write minimal implementation**

`admin-ui/src/lib/recoveryCodeFilename.ts`:
```ts
/**
 * 복구 코드 다운로드 파일명을 생성한다.
 *   passkey-admin-recovery-codes-<account>-<YYYYMMDD>.txt
 * <account> = email 의 @ 앞 local part 를 파일명-안전하게 sanitize.
 * email 이 없거나 sanitize 결과가 비면 account 세그먼트를 생략한다.
 */
export function recoveryCodesFilename(email: string | null | undefined, now: Date): string {
  const stamp = `${now.getFullYear()}${pad2(now.getMonth() + 1)}${pad2(now.getDate())}`;
  const account = sanitizeAccount(email);
  const base = 'passkey-admin-recovery-codes';
  return account
    ? `${base}-${account}-${stamp}.txt`
    : `${base}-${stamp}.txt`;
}

function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

function sanitizeAccount(email: string | null | undefined): string {
  if (!email) return '';
  const local = email.split('@')[0] ?? '';
  return local
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, '-') // 안전 문자만 유지, 나머지는 대시
    .replace(/-+/g, '-')            // 연속 대시 축약
    .replace(/^-+|-+$/g, '');       // 양끝 대시 제거
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin-ui && npx vitest run src/lib/recoveryCodeFilename.test.ts`
Expected: PASS — all 6 tests green.

- [ ] **Step 5: Commit**

```bash
cd admin-ui && git add src/lib/recoveryCodeFilename.ts src/lib/recoveryCodeFilename.test.ts
git commit -m "feat(admin-ui): 복구코드 다운로드 파일명 생성(서비스명-계정-날짜) 순수 함수"
```

---

## Task 3: RecoveryCodesModal — 인쇄 제거 + 파일명 자동화

모달에서 인쇄 버튼/함수를 없애고, 다운로드 파일명을 Task 2 함수로 생성한다. 모달은 email 을 모르므로 `accountEmail` prop 을 추가한다.

**Files:**
- Modify: `admin-ui/src/pages/settings/RecoveryCodesModal.tsx`

- [ ] **Step 1: Replace the component body**

`admin-ui/src/pages/settings/RecoveryCodesModal.tsx` 전체를 아래로 교체:
```tsx
import { useState } from 'react';
import { Dialog } from '@/shell/Dialog';
import { Icons } from '@/icons/Icons';
import { recoveryCodesFilename } from '@/lib/recoveryCodeFilename';

/**
 * MFA 복구 코드 1회 표시 모달. confirm 직후 enroll 응답의 recoveryCodes 를 보여준다.
 * "저장했습니다" 체크 전에는 닫기 불가(IssuedKeyModal 패턴). 닫으면 영구히 다시 못 봄.
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
  const text = codes.join('\n');

  function copy() {
    navigator.clipboard?.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
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

변경 요약: `print()` 함수 삭제, "인쇄" 버튼 삭제(복사/다운로드만 → 자동 50:50), `accountEmail` prop 추가, `a.download` 를 `recoveryCodesFilename(accountEmail, new Date())` 로.

- [ ] **Step 2: Type-check / build**

Run: `cd admin-ui && npx tsc -b`
Expected: PASS (no type errors). `accountEmail` 미전달은 optional 이라 이 시점엔 에러 없음. Task 4 에서 배선.

- [ ] **Step 3: Commit**

```bash
cd admin-ui && git add src/pages/settings/RecoveryCodesModal.tsx
git commit -m "feat(admin-ui): 복구코드 모달 인쇄 제거 + 다운로드 파일명 자동화"
```

---

## Task 4: AccountTab — 모달 배선 + MFA 카드 시각 정돈

모달에 `me.email` 을 전달하고, enroll 영역을 단계감 있게 정돈한다. 기존 토큰/클래스만 사용.

**Files:**
- Modify: `admin-ui/src/pages/settings/AccountTab.tsx`

- [ ] **Step 1: 모달에 accountEmail 전달**

`admin-ui/src/pages/settings/AccountTab.tsx` 의 모달 호출(현재 라인 근처: `{recoveryCodes && (`)을 아래로 교체:
```tsx
      {recoveryCodes && (
        <RecoveryCodesModal codes={recoveryCodes} accountEmail={me.email} onClose={() => setRecoveryCodes(null)} />
      )}
```

- [ ] **Step 2: enroll 영역 단계 정돈**

`AccountTab.tsx` 의 `{!me.mfaEnabled && enroll && (` 블록 안의 `<div className="stack-3">` 내용을 아래로 교체(단계 라벨 추가, 기존 `.label`/`.hint`/`--text-mute` 토큰만 사용):
```tsx
            <div className="stack-3">
              <div className="hint">인증 앱으로 아래 QR 코드를 스캔하거나 키를 직접 입력한 후, 생성된 6자리 코드를 입력하세요.</div>
              <div>
                <label className="label" style={{ color: 'var(--text-soft)' }}>1. QR 코드 스캔</label>
                <QrCode value={enroll.otpauthUri} />
              </div>
              <div>
                <label className="label" style={{ color: 'var(--text-soft)' }}>2. 또는 수동 입력 키</label>
                <div className="mono" style={{ wordBreak: 'break-all', userSelect: 'all' }}>{enroll.secret}</div>
              </div>
              <div>
                <label className="label" style={{ color: 'var(--text-soft)' }}>3. 인증 코드</label>
                <input
                  className="input mono"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  placeholder="000000"
                  value={enrollCode}
                  onChange={(e) => setEnrollCode(sanitizeCode(e.target.value))}
                  style={{ width: 140, letterSpacing: '0.2em' }}
                />
              </div>
              <div className="row">
                <button className="btn" onClick={cancelEnroll} disabled={confirming}>
                  취소
                </button>
                <button
                  className="btn btn--primary"
                  onClick={() => void confirmEnroll()}
                  disabled={enrollCode.length !== 6 || confirming}
                >
                  확인
                </button>
              </div>
            </div>
```

변경 요약: QR/수동키/코드 입력 각각에 `1. / 2. / 3.` 단계 라벨을 `.label` 토큰으로 부여. 기존 입력·버튼 로직은 그대로.

- [ ] **Step 3: Type-check / build**

Run: `cd admin-ui && npx tsc -b`
Expected: PASS.

- [ ] **Step 4: 기존 SettingsPage 테스트 회귀 확인**

Run: `cd admin-ui && npx vitest run src/pages/SettingsPage.test.tsx`
Expected: PASS (account 탭 렌더 변경이 셀렉터를 깨지 않음).

- [ ] **Step 5: Commit**

```bash
cd admin-ui && git add src/pages/settings/AccountTab.tsx
git commit -m "feat(admin-ui): MFA 모달 email 배선 + enroll 단계 라벨 정돈"
```

---

## Task 5: MfaChallenge — 기존 테스트 갱신 (RED)

5번 변경으로 recovery 입력이 자동 정형되므로, 현재 동작(free-text 보존)을 검증하는 기존 테스트가 깨진다. 새 동작에 맞춰 테스트를 **먼저** 고친다(TDD: 테스트가 새 기대를 표현하고 RED 가 되도록).

**Files:**
- Modify: `admin-ui/src/pages/MfaChallenge.test.tsx`

- [ ] **Step 1: recovery 테스트를 새 동작으로 교체**

`admin-ui/src/pages/MfaChallenge.test.tsx` 의 두 번째 테스트(`recovery mode accepts free-text and clears on toggle`)와 세 번째 테스트의 placeholder 셀렉터를 아래로 교체. placeholder 가 `3f9a-2b71` → `AB3F-2K7M` 로 바뀌는 점에 유의:
```tsx
  it('recovery mode auto-formats: uppercases and inserts dash after 4 chars', () => {
    renderChallenge();
    fireEvent.click(screen.getByRole('button', { name: /복구 코드로 로그인/ }));
    const rec = screen.getByPlaceholderText('AB3F-2K7M') as HTMLInputElement;
    fireEvent.change(rec, { target: { value: 'ab3f2k7m' } });
    expect(rec.value).toBe('AB3F-2K7M'); // lowercased input -> formatted
  });

  it('toggling back to TOTP clears the input', () => {
    renderChallenge();
    fireEvent.click(screen.getByRole('button', { name: /복구 코드로 로그인/ }));
    const rec = screen.getByPlaceholderText('AB3F-2K7M') as HTMLInputElement;
    fireEvent.change(rec, { target: { value: 'abcd' } });
    fireEvent.click(screen.getByRole('button', { name: /authenticator 코드로 돌아가기/ }));
    const totp = screen.getByPlaceholderText('000000') as HTMLInputElement;
    expect(totp.value).toBe(''); // switchMode clears code
  });
```

(첫 번째 테스트 `TOTP mode strips non-digits and caps at 6` 는 변경 없음.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin-ui && npx vitest run src/pages/MfaChallenge.test.tsx`
Expected: FAIL — placeholder `AB3F-2K7M` 가 아직 없어 `getByPlaceholderText` 가 throw, 또는 `rec.value` 가 `ab3f2k7m` 그대로라 불일치.

- [ ] **Step 3: Commit (red test)**

```bash
cd admin-ui && git add src/pages/MfaChallenge.test.tsx
git commit -m "test(admin-ui): MfaChallenge 복구코드 자동 정형 기대로 테스트 갱신"
```

---

## Task 6: MfaChallenge — 자동 대시 적용 + 화면 정돈 (GREEN)

Task 1 의 포맷터를 recovery input 에 연결하고, 챌린지 카드를 토큰에 맞춰 정돈한다.

**Files:**
- Modify: `admin-ui/src/pages/MfaChallenge.tsx`

- [ ] **Step 1: import + recovery input onChange 교체**

`admin-ui/src/pages/MfaChallenge.tsx` 상단 import 에 추가:
```tsx
import { formatRecoveryCode } from '@/lib/recoveryCode';
```

recovery 모드 input(현재 `placeholder="3f9a-2b71"` 인 `<input>`)을 아래로 교체:
```tsx
          <input
            className="input mono"
            autoFocus
            value={code}
            onChange={(e) => setCode(formatRecoveryCode(e.target.value))}
            placeholder="AB3F-2K7M"
            style={{ width: '100%', fontFamily: 'monospace', textAlign: 'center', fontSize: 16, letterSpacing: 2 }}
          />
```

안내 문구(현재 `복구 코드(xxxx-xxxx)를 입력하세요. 1회용입니다.`)를 대문자 표기로 통일:
```tsx
            : '복구 코드(AB3F-2K7M)를 입력하세요. 1회용입니다.'}
```

- [ ] **Step 2: 챌린지 카드 컨테이너 토큰 정돈**

`MfaChallenge.tsx` 의 `<form>` 컨테이너 style 의 `borderRadius: 14` 를 토큰으로, 제목 영역 정돈:
```tsx
      <form onSubmit={submit} style={{ width: 360, padding: 28, border: '1px solid var(--border)', borderRadius: 'var(--radius-lg)', background: 'var(--surface)', boxShadow: 'var(--shadow-xs)' }}>
```

(`--radius-lg`=12px, `--shadow-xs` 는 tokens.css 에 정의됨. 나머지 마크업은 유지.)

- [ ] **Step 3: Run the updated test to verify it passes**

Run: `cd admin-ui && npx vitest run src/pages/MfaChallenge.test.tsx`
Expected: PASS — 자동 정형 테스트 green, toggle 테스트 green.

- [ ] **Step 4: Type-check / build**

Run: `cd admin-ui && npx tsc -b`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd admin-ui && git add src/pages/MfaChallenge.tsx
git commit -m "feat(admin-ui): 2단계 복구코드 입력 자동 대시+대문자화, 챌린지 카드 토큰 정돈"
```

---

## Task 7: 전체 회귀 + lint

**Files:** (없음 — 검증만)

- [ ] **Step 1: 전체 테스트**

Run: `cd admin-ui && npm test`
Expected: PASS — 신규 2개 lib 테스트 파일 + 갱신된 MfaChallenge 테스트 + 기존 전부 green.

- [ ] **Step 2: 빌드 + lint**

Run: `cd admin-ui && npx tsc -b && npm run lint`
Expected: 타입 에러 0, lint 에러 0.

- [ ] **Step 3: 수동 검증 체크리스트 (dev 서버)**

Run: `cd admin-ui && npm run dev` 후 브라우저에서:
1. 설정 → 내 계정 → 2단계 인증 켜기 → enroll 영역에 `1./2./3.` 단계 라벨 표시.
2. 코드 확인 성공 시 복구 코드 모달: **인쇄 버튼 없음**, 복사·다운로드만.
3. 다운로드 → 파일명이 `passkey-admin-recovery-codes-<email앞부분>-<오늘YYYYMMDD>.txt`.
4. 로그아웃 후 재로그인 → 2단계 챌린지 → "복구 코드로 로그인" → `ab3f2k7m` 입력 시 `AB3F-2K7M` 으로 자동 정형, 실제 검증 통과.
5. light/dark 테마 모두에서 두 화면 레이아웃 정상.

- [ ] **Step 4: (선택) codex 리뷰 후 최종 정리 커밋**

작업 디렉터리 클린 상태 확인:
```bash
cd admin-ui && git status -s
```
Expected: clean (모든 변경 커밋됨).

---

## Self-Review (작성자 체크 결과)

- **Spec coverage:** (1) 복사=무변경 명시 / (2) 인쇄제거=Task3 / (3) 파일명=Task2+Task3+Task4 / (4) 디자인=Task4(AccountTab)+Task6(MfaChallenge) / (5) 자동대시=Task1+Task5+Task6. 전 항목 커버.
- **Placeholder scan:** 모든 코드 스텝에 완전한 코드 포함. "적절히 처리" 류 없음.
- **Type consistency:** `formatRecoveryCode`(Task1) ↔ Task6 import 일치. `recoveryCodesFilename(email, now)`(Task2) ↔ Task3 호출 시그니처 일치. `accountEmail` prop(Task3) ↔ Task4 전달 일치. placeholder `AB3F-2K7M` 가 Task5(테스트)·Task6(구현) 양쪽 동일.
- **회귀 위험:** 기존 `MfaChallenge.test.tsx` 가 옛 동작을 검증하므로 Task5 에서 새 기대로 갱신(테스트-먼저). placeholder 변경이 테스트 셀렉터를 깨므로 함께 수정.
