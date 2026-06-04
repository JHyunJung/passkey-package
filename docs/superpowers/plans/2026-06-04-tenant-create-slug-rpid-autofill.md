# 테넌트 생성 slug/rpId 자동생성 버그 수정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 신규 테넌트 생성 다이얼로그에서 표시이름(displayName) 입력이 slug/rpId에 이상한 값(한글→빈값·엉뚱한 조각)을 자동으로 채우지 않게 하고, 프론트 slug 검증을 백엔드 규칙과 일치시킨다.

**Architecture:** `NewTenantDialog`(`TenantsListPage.tsx`) 단일 컴포넌트에서 displayName→slug→rpId 자동생성 체인(`generate()` + `if(!slug)` 가드 + `rpIdEdited` 제안)을 전부 제거해 세 필드를 독립 수동 입력으로 만든다. 프론트 `slugRe`를 백엔드 `@Pattern`(`^[a-z0-9][a-z0-9-]{1,62}$`)과 동일하게 맞춘다. 테스트를 위해 컴포넌트를 named export 한다.

**Tech Stack:** React 18 / TypeScript / Vitest(jsdom, globals) + @testing-library/react (admin-ui).

**작업 위치:** 워크트리 `.claude/worktrees/tenant-slug-rpid-fix`, 브랜치 `worktree-tenant-slug-rpid-fix`. **모든 git/빌드/테스트는 이 워크트리에서.** 메인 repo root 커밋 금지. 프론트는 `<worktree>/admin-ui`.

---

## File Structure

| 파일 | 역할 | 변경 |
|------|------|------|
| `admin-ui/src/pages/TenantsListPage.tsx` | `NewTenantDialog`를 named export로 + 자동생성 제거 + slug 정규식 백엔드 일치 | Modify |
| `admin-ui/src/pages/NewTenantDialog.test.tsx` | 자동생성 미발생 / slug 규칙 / 수동 생성 검증 | Create |

> `NewTenantDialog`는 현재 `TenantsListPage.tsx` 내부 비-export `function`이다. 테스트에서 import하려면 `export function NewTenantDialog(...)`로 바꾼다(동작 무영향 — 같은 파일 내 사용처 `<NewTenantDialog .../>`는 그대로 동작). 파일을 분리하지 않고 named export만 추가한다(YAGNI; 기존 파일 패턴 유지).

---

## Task 1: NewTenantDialog named export + 자동생성 미발생 테스트 (RED)

**Files:**
- Modify: `admin-ui/src/pages/TenantsListPage.tsx:265` (`function` → `export function`)
- Test: `admin-ui/src/pages/NewTenantDialog.test.tsx`

테스트를 먼저 작성한다. 현재 코드(자동생성 O)에서는 "표시이름 입력 시 slug가 비어 있어야 한다" 테스트가 FAIL(RED)한다.

- [ ] **Step 1: NewTenantDialog를 named export로 변경**

`TenantsListPage.tsx`의 265행:
```tsx
function NewTenantDialog({ open, onClose, onCreate }: {
```
을:
```tsx
export function NewTenantDialog({ open, onClose, onCreate }: {
```
로 변경. (파일 하단 사용처 `<NewTenantDialog open={showNew} .../>`는 같은 모듈 스코프라 그대로 동작.)

- [ ] **Step 2: 실패하는 테스트 작성**

`admin-ui/src/pages/NewTenantDialog.test.tsx` 생성:
```tsx
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { NewTenantDialog } from './TenantsListPage';

afterEach(cleanup);

function renderDialog(onCreate = vi.fn()) {
  render(<NewTenantDialog open={true} onClose={vi.fn()} onCreate={onCreate} />);
  return {
    name: screen.getByPlaceholderText('예: Acme Corp') as HTMLInputElement,
    slug: screen.getByPlaceholderText('acme-corp') as HTMLInputElement,
    rpId: screen.getByPlaceholderText('예: passkey.acme.com') as HTMLInputElement,
    onCreate,
  };
}

describe('NewTenantDialog', () => {
  it('does not auto-fill slug/rpId from a Korean display name', () => {
    const { name, slug, rpId } = renderDialog();
    fireEvent.change(name, { target: { value: '한글회사' } });
    expect(slug.value).toBe('');
    expect(rpId.value).toBe('');
  });

  it('does not auto-fill slug/rpId from an English display name', () => {
    const { name, slug, rpId } = renderDialog();
    fireEvent.change(name, { target: { value: 'Acme Corp' } });
    expect(slug.value).toBe('');
    expect(rpId.value).toBe('');
  });
});
```

- [ ] **Step 3: 테스트 실행 — RED 확인**

Run:
```bash
cd admin-ui && npx vitest run src/pages/NewTenantDialog.test.tsx
```
Expected: FAIL — 현재 `generate()`가 표시이름 입력 시 slug/rpId를 자동으로 채우므로, "Acme Corp" 케이스는 slug가 `acme-corp`가 되어 `''` 기대와 불일치. (한글 케이스는 빈값이 될 수도 있으나 영문 케이스가 확실히 RED.)

- [ ] **Step 4: 커밋 (RED 상태 테스트 + export)**

```bash
git add admin-ui/src/pages/TenantsListPage.tsx admin-ui/src/pages/NewTenantDialog.test.tsx
git commit -m "test(admin-ui): NewTenantDialog 자동생성 미발생 테스트 (RED) + named export

표시이름 입력 시 slug/rpId가 자동으로 채워지지 않아야 함을 검증하는
테스트 추가. 현재 generate() 자동생성 때문에 RED. 테스트용으로
NewTenantDialog를 named export.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 자동생성 체인 제거 (GREEN)

**Files:**
- Modify: `admin-ui/src/pages/TenantsListPage.tsx` (`NewTenantDialog` 본문)

`generate()`, `if(!slug)generate` 가드, `rpIdEdited` state·로직을 전부 제거해 세 필드를 독립 수동 입력으로 만든다.

- [ ] **Step 1: rpIdEdited state 제거**

273행:
```tsx
  const [rpIdEdited, setRpIdEdited] = useState(false);
```
줄 삭제.

- [ ] **Step 2: reset effect에서 rpIdEdited 제거**

286행:
```tsx
    if (!open) { setName(''); setSlug(''); setRpId(''); setRpIdEdited(false); setTouched(false); }
```
을:
```tsx
    if (!open) { setName(''); setSlug(''); setRpId(''); setTouched(false); }
```
로 변경.

- [ ] **Step 3: generate() 함수 삭제**

289–294행 전체 삭제:
```tsx
  function generate(n: string) {
    const s = n.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40);
    setSlug(s);
    // rpId 를 사용자가 아직 직접 수정하지 않았다면 slug 기반 제안값을 채운다(수정 가능).
    if (!rpIdEdited) setRpId(s ? `${s}.crosscert.com` : '');
  }
```
(이 함수 블록을 통째로 제거.)

- [ ] **Step 4: 표시이름 onChange에서 generate 호출 제거**

318행:
```tsx
          <input className="input" placeholder="예: Acme Corp" value={name} onChange={(e) => { setName(e.target.value); if (!slug) generate(e.target.value); }} />
```
을:
```tsx
          <input className="input" placeholder="예: Acme Corp" value={name} onChange={(e) => setName(e.target.value)} />
```
로 변경.

- [ ] **Step 5: rpId onChange에서 setRpIdEdited 제거**

331–332행:
```tsx
          <input className="input mono" placeholder="예: passkey.acme.com" value={rpId}
                 onChange={(e) => { setRpId(e.target.value.toLowerCase().trim()); setRpIdEdited(true); }} />
```
을:
```tsx
          <input className="input mono" placeholder="예: passkey.acme.com" value={rpId}
                 onChange={(e) => setRpId(e.target.value.toLowerCase().trim())} />
```
로 변경.

- [ ] **Step 6: 테스트 통과(GREEN) + 타입체크 확인**

Run:
```bash
cd admin-ui && npx tsc --noEmit && npx vitest run src/pages/NewTenantDialog.test.tsx
```
Expected: tsc 에러 없음(미사용 `generate`/`rpIdEdited` 제거로 dead code 없음). 테스트 2개 PASS — 표시이름 입력해도 slug/rpId 빈값 유지.

- [ ] **Step 7: 커밋**

```bash
git add admin-ui/src/pages/TenantsListPage.tsx
git commit -m "fix(admin-ui): 테넌트 생성 시 slug/rpId 자동생성 제거

표시이름(특히 한글) 입력 시 generate()가 한글을 -로 치환해 slug/rpId에
빈값·엉뚱한 값을 자동 채우던 버그. generate()/if(!slug)가드/rpIdEdited
제안을 전부 제거해 세 필드를 독립 수동 입력으로. 한글 표시이름도 안전.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 프론트 slug 검증을 백엔드 규칙에 일치

**Files:**
- Modify: `admin-ui/src/pages/TenantsListPage.tsx` (`slugRe`, slug 안내문)
- Test: `admin-ui/src/pages/NewTenantDialog.test.tsx` (테스트 추가)

프론트 `slugRe`(`^[a-z]...`)를 백엔드 `@Pattern`(`^[a-z0-9]...`)과 일치시킨다.

- [ ] **Step 1: 실패하는 테스트 추가**

`NewTenantDialog.test.tsx`의 describe 블록 안에 추가:
```tsx
  it('accepts a slug starting with a digit (matches backend rule)', () => {
    const onCreate = vi.fn();
    const { name, slug, rpId } = renderDialog(onCreate);
    fireEvent.change(name, { target: { value: 'Acme' } });
    fireEvent.change(slug, { target: { value: '123abc' } });
    fireEvent.change(rpId, { target: { value: 'passkey.acme.com' } });
    fireEvent.click(screen.getByRole('button', { name: '생성하고 설정으로 이동' }));
    expect(onCreate).toHaveBeenCalledWith({ name: 'Acme', slug: '123abc', rpId: 'passkey.acme.com' });
  });

  it('submits the manually entered slug/rpId verbatim', () => {
    const onCreate = vi.fn();
    const { name, slug, rpId } = renderDialog(onCreate);
    fireEvent.change(name, { target: { value: '한글회사' } });
    fireEvent.change(slug, { target: { value: 'hangul-co' } });
    fireEvent.change(rpId, { target: { value: 'passkey.hangul.com' } });
    fireEvent.click(screen.getByRole('button', { name: '생성하고 설정으로 이동' }));
    expect(onCreate).toHaveBeenCalledWith({ name: '한글회사', slug: 'hangul-co', rpId: 'passkey.hangul.com' });
  });
```

- [ ] **Step 2: 테스트 실행 — digit-slug 케이스 RED 확인**

Run:
```bash
cd admin-ui && npx vitest run src/pages/NewTenantDialog.test.tsx
```
Expected: `accepts a slug starting with a digit` FAIL — 현재 `slugRe`가 `^[a-z]...`라 `123abc`가 invalid로 판정되어 `submit()`이 `if(!slugOk) return`으로 막혀 onCreate가 호출되지 않음. (`submits ... verbatim` 케이스는 slug `hangul-co`가 유효라 PASS.)

- [ ] **Step 3: slugRe를 백엔드 규칙에 일치**

275행:
```tsx
  const slugRe = /^[a-z][a-z0-9-]{1,62}$/;
```
을:
```tsx
  const slugRe = /^[a-z0-9][a-z0-9-]{1,62}$/;
```
로 변경.

- [ ] **Step 4: slug 안내문을 규칙에 맞게 조정**

324–326행의 안내문에서 "영문 소문자로 시작하고"를 "영문 소문자·숫자로 시작하고"로 변경. 정확히:
```tsx
            {touched && slug && !slugOk
              ? <span style={{ color: 'var(--danger)' }}>영문 소문자·숫자로 시작하고, 영문 소문자·숫자·하이픈(-)만 사용해 2~63자로 입력하세요.</span>
              : <>테넌트를 구분하는 영문 식별자입니다. 영문 소문자·숫자로 시작하고, 영문 소문자·숫자·하이픈(-)만 쓸 수 있습니다(2~63자). 띄어쓰기·대문자·한글은 사용할 수 없습니다. <strong>생성 후에는 변경할 수 없습니다.</strong></>}
```

- [ ] **Step 5: 테스트 통과 + 타입체크 + 전체 프론트 테스트**

Run:
```bash
cd admin-ui && npx tsc --noEmit && npx vitest run
```
Expected: tsc clean. NewTenantDialog.test.tsx 4개 전부 PASS. 전체 vitest 스위트 PASS(회귀 없음).

- [ ] **Step 6: 커밋**

```bash
git add admin-ui/src/pages/TenantsListPage.tsx admin-ui/src/pages/NewTenantDialog.test.tsx
git commit -m "fix(admin-ui): 테넌트 slug 검증을 백엔드 규칙(^[a-z0-9]…)에 일치

프론트 slugRe(^[a-z]…)와 백엔드 @Pattern(^[a-z0-9]…) 불일치로 숫자
시작 slug가 프론트에서만 막히던 문제. slugRe와 안내문을 백엔드와 일치.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: 전체 검증

**Files:** 없음 (검증 전용)

- [ ] **Step 1: 프론트 타입체크 + 전체 테스트**

Run:
```bash
cd admin-ui && npx tsc --noEmit && npx vitest run
```
Expected: tsc clean, 모든 vitest PASS (NewTenantDialog 4 + 기존 전부).

- [ ] **Step 2: codex 리뷰 (커밋 전 안전망)**

메모리 정책([[feedback_codex_review_before_commit]])에 따라 누적 diff를 codex로 독립 리뷰. P1/P2 지적 시 수정 후 재검증.

- [ ] **Step 3: 최종 상태 확인**

Run:
```bash
git log --oneline -5
git status
```
Expected: Task 1~3 커밋 3개 + 설계/계획 문서. working tree clean.

---

## 완료 후 (이 계획 범위 밖, 사용자 확정 사항)

- 워크트리 작업 완료 후 main으로 `merge --no-ff` ([[feedback_per_phase_worktree]]).
- 수동 dogfooding: 실제 테넌트 생성 화면에서 한글 표시이름 입력 → slug/rpId가 비어 있는지, 숫자 시작 slug가 통과하는지 브라우저 확인.

---

## Self-Review

**1. Spec coverage**
- 자동생성 체인 완전 제거(generate/if-guard/rpIdEdited) → Task 2 ✓
- 프론트 slug 검증을 백엔드 규칙에 일치 + 안내 유지 → Task 3 ✓
- rpId 자동제안 제거 → Task 2 Step 3(generate 내 rpId 제안)·Step 5(setRpIdEdited) ✓
- 신규 테스트(자동생성 미발생·slug 규칙·수동 생성) → Task 1·3 ✓
- export 추가 → Task 1 Step 1 ✓

**2. Placeholder scan:** TBD/TODO/"적절히 처리" 없음. 모든 코드 단계에 완전한 코드 블록. ✓

**3. Type consistency:**
- `NewTenantDialog` props `{open, onClose, onCreate}` — export 전후 동일, 테스트 render와 일치 ✓
- `onCreate({name, slug, rpId})` — 컴포넌트 시그니처(268행)와 테스트 `toHaveBeenCalledWith` 일치 ✓
- `slugRe = /^[a-z0-9][a-z0-9-]{1,62}$/` — Task 3 변경값이 백엔드 `TenantAdminDto` `@Pattern`과 동일 문자열 ✓
- placeholder 문자열(`예: Acme Corp`, `acme-corp`, `예: passkey.acme.com`) — 실제 코드(318/322/331행)와 테스트 getByPlaceholderText 일치 ✓
- 생성 버튼 라벨 `생성하고 설정으로 이동` — 코드(312행)와 테스트 getByRole name 일치 ✓
