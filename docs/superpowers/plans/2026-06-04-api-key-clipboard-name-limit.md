# API Key 클립보드 복사 + 이름 제한 정합성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** API key 발급 시 클립보드 복사가 HTTP(insecure context) 포함 모든 환경에서 동작하고 실패 시 정직한 피드백을 주도록 고치고, 이름 길이 제한(64자)을 백엔드 검증·프론트 안내·프론트 강제가 일관되게 적용하도록 한다.

**Architecture:** (1) 재사용 가능한 `copyToClipboard` 유틸(`navigator.clipboard` 우선 → `execCommand('copy')` 폴백 → 실패 시 false)을 `lib/`에 추가하고 `IssuedKeyModal`의 복사 버튼이 그것을 await해 성공 시에만 "복사됨"을 표시, 실패 시 toast. (2) 백엔드 `@Size(max=256)`을 `max=64`로 축소(DB 컬럼 유지), 프론트 발급 폼에 `NAME_MAX=64` 상수 기반 maxLength·안내·글자수 카운터 추가.

**Tech Stack:** React 18 / TypeScript / Vitest(jsdom, globals, `vi.stubGlobal`) (admin-ui), Java 17 / Jakarta Bean Validation(hibernate-validator 8, testRuntime에 존재) (admin-app).

**작업 위치:** 워크트리 `.claude/worktrees/api-key-clipboard-name`, 브랜치 `worktree-api-key-clipboard-name`. **모든 `git`/빌드/테스트 명령은 이 워크트리에서 실행.** 메인 repo root에서 절대 커밋하지 말 것. 프론트는 `<worktree>/admin-ui`.

---

## File Structure

| 파일 | 역할 | 변경 |
|------|------|------|
| `admin-ui/src/lib/clipboard.ts` | `copyToClipboard(text): Promise<boolean>` 유틸 (clipboard + execCommand 폴백) | Create |
| `admin-ui/src/lib/clipboard.test.ts` | 유틸 단위 테스트 (3 경로) | Create |
| `admin-ui/src/pages/tenant/ApiKeysTab.tsx` | IssuedKeyModal 복사 버튼이 유틸 사용 + 성공 시에만 표시/실패 toast; 발급 폼 maxLength·안내·카운터 | Modify |
| `admin-app/.../apikey/ApiKeyAdminDto.java` | `@Size(max=256)` → `@Size(max=64)` | Modify |
| `admin-app/.../apikey/ApiKeyCreateRequestValidationTest.java` | DTO 검증 단위 테스트 (Jakarta Validator 직접) | Create |

> 백엔드 검증 테스트는 무거운 `@WebMvcTest`/Testcontainers 대신 **Jakarta `Validator`를 직접 쓰는 순수 단위 테스트**로 한다. hibernate-validator 8이 testRuntimeClasspath에 이미 있어 컨텍스트 로드 없이 빠르고, 슬라이스 컨텍스트 로드 회귀(기존 `*ControllerSecurityTest`의 MockBean 누락 이슈)도 피한다.

---

## Task 1: 클립보드 복사 유틸 (`copyToClipboard`)

**Files:**
- Create: `admin-ui/src/lib/clipboard.ts`
- Test: `admin-ui/src/lib/clipboard.test.ts`

`navigator.clipboard.writeText`를 우선 시도하고, 없거나 실패하면 임시 `textarea` + `document.execCommand('copy')` 폴백, 둘 다 실패하면 `false`를 반환하는 순수 비동기 유틸.

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/lib/clipboard.test.ts` 생성:
```ts
import { describe, it, expect, vi, afterEach } from 'vitest';
import { copyToClipboard } from './clipboard';

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('copyToClipboard', () => {
  it('uses navigator.clipboard.writeText when available and returns true', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { clipboard: { writeText } });
    const ok = await copyToClipboard('secret');
    expect(ok).toBe(true);
    expect(writeText).toHaveBeenCalledWith('secret');
  });

  it('falls back to execCommand when navigator.clipboard is absent', async () => {
    vi.stubGlobal('navigator', {}); // no clipboard
    const execCommand = vi.fn().mockReturnValue(true);
    // jsdom document exists; stub execCommand on it
    vi.spyOn(document, 'execCommand' as never).mockImplementation(execCommand as never);
    const ok = await copyToClipboard('secret');
    expect(ok).toBe(true);
    expect(execCommand).toHaveBeenCalledWith('copy');
  });

  it('falls back to execCommand when writeText rejects', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('denied'));
    vi.stubGlobal('navigator', { clipboard: { writeText } });
    const execCommand = vi.fn().mockReturnValue(true);
    vi.spyOn(document, 'execCommand' as never).mockImplementation(execCommand as never);
    const ok = await copyToClipboard('secret');
    expect(ok).toBe(true);
    expect(execCommand).toHaveBeenCalledWith('copy');
  });

  it('returns false when both clipboard and execCommand fail', async () => {
    vi.stubGlobal('navigator', {});
    vi.spyOn(document, 'execCommand' as never).mockImplementation((() => false) as never);
    const ok = await copyToClipboard('secret');
    expect(ok).toBe(false);
  });
});
```

- [ ] **Step 2: 테스트 실패 확인**

Run:
```bash
cd admin-ui && npx vitest run src/lib/clipboard.test.ts
```
Expected: FAIL — `copyToClipboard`가 아직 없음 (module not found / not a function).

- [ ] **Step 3: 유틸 구현**

`admin-ui/src/lib/clipboard.ts` 생성:
```ts
// API key 등 민감 문자열 복사용 유틸.
// navigator.clipboard 는 secure context(HTTPS/localhost)에서만 존재하므로,
// HTTP(사내 IP 등) 환경을 위해 execCommand('copy') 폴백을 둔다.
export async function copyToClipboard(text: string): Promise<boolean> {
  // 1) 표준 Clipboard API (secure context)
  if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // fall through to legacy path
    }
  }
  // 2) 레거시 폴백: 임시 textarea + execCommand('copy')
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.left = '-9999px';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  } catch {
    return false;
  }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd admin-ui && npx vitest run src/lib/clipboard.test.ts
```
Expected: PASS (4 tests).

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/lib/clipboard.ts admin-ui/src/lib/clipboard.test.ts
git commit -m "feat(admin-ui): copyToClipboard 유틸 (execCommand 폴백)

navigator.clipboard 는 secure context 전용이라 HTTP 환경에서 undefined.
clipboard 우선 + 실패/부재 시 textarea+execCommand 폴백, 둘 다 실패 시
false 반환.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: IssuedKeyModal 복사 버튼이 유틸을 사용 + 정직한 피드백

**Files:**
- Modify: `admin-ui/src/pages/tenant/ApiKeysTab.tsx` (import 추가, `IssuedKeyModal` 복사 버튼 핸들러: 현재 321행)

`IssuedKeyModal`(266행~)은 별도 컴포넌트라 toast 훅을 직접 갖고 있지 않다. `useToast()`를 이 컴포넌트 안에서 호출하고, 복사 버튼이 `copyToClipboard`를 await해 성공 시에만 `setCopied(true)`, 실패 시 toast로 안내한다.

- [ ] **Step 1: import 추가**

`ApiKeysTab.tsx` 상단 import 블록에 추가 (기존 `import { useToast } from '@/shell/ToastHost';` 줄은 그대로 두고, 새 import만 추가):
```tsx
import { copyToClipboard } from '@/lib/clipboard';
```

- [ ] **Step 2: IssuedKeyModal 안에서 useToast 훅 사용**

`IssuedKeyModal` 함수 본문에서 기존 state 선언부:
```tsx
  const [copied, setCopied] = useState(false);
  const [checked, setChecked] = useState(false);
```
바로 위(또는 아래)에 toast 훅 추가:
```tsx
  const toast = useToast();
```

- [ ] **Step 3: 복사 버튼 핸들러 교체**

현재 (321행):
```tsx
            <button className="btn btn--primary btn--sm" style={{ position: 'absolute', top: 8, right: 8 }} onClick={() => { navigator.clipboard?.writeText(issued.plaintext); setCopied(true); }}>
```
을 아래로 교체 (async 핸들러 + 성공 시에만 표시 + 실패 toast):
```tsx
            <button className="btn btn--primary btn--sm" style={{ position: 'absolute', top: 8, right: 8 }} onClick={async () => {
              const ok = await copyToClipboard(issued.plaintext);
              if (ok) setCopied(true);
              else toast({ kind: 'warn', title: '복사 실패', message: '클립보드 복사에 실패했습니다. 키를 직접 선택해 복사하세요.' });
            }}>
```

- [ ] **Step 4: 타입체크 + 전체 프론트 테스트**

Run:
```bash
cd admin-ui && npx tsc --noEmit && npx vitest run
```
Expected: tsc 에러 없음. 모든 vitest PASS (clipboard 유틸 포함).

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/tenant/ApiKeysTab.tsx
git commit -m "fix(admin-ui): API key 복사 버튼이 copyToClipboard 사용 + 정직한 피드백

기존엔 navigator.clipboard?.writeText 가 insecure context 에서 조용히
스킵되고 setCopied(true)만 호출돼 가짜 '복사됨' 표시. 이제 폴백 포함
copyToClipboard 를 await 해 성공 시에만 표시하고 실패 시 toast 안내.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 백엔드 이름 길이 검증 64자로 축소

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminDto.java:19`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyCreateRequestValidationTest.java`

`ApiKeyCreateRequest.name`의 `@Size(max = 256)`을 `@Size(max = 64)`로 줄인다. DB 컬럼(`ApiKey.NAME length=256`)은 유지 — 검증만 축소하므로 기존 데이터 영향 없음, 마이그레이션 불필요. Jakarta `Validator`를 직접 쓰는 순수 단위 테스트로 검증.

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyCreateRequestValidationTest.java` 생성:
```java
package com.crosscert.passkey.admin.apikey;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyCreateRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setup() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private ApiKeyAdminDto.ApiKeyCreateRequest req(String name) {
        return new ApiKeyAdminDto.ApiKeyCreateRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                name,
                Set.of("registration"));
    }

    @Test
    void name64CharsIsValid() {
        var violations = validator.validate(req("a".repeat(64)));
        assertThat(violations).isEmpty();
    }

    @Test
    void name65CharsViolatesSize() {
        var violations = validator.validate(req("a".repeat(65)));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }

    @Test
    void blankNameViolatesNotBlank() {
        var violations = validator.validate(req("   "));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }
}
```

- [ ] **Step 2: 테스트 실행 — name65 케이스가 (아직 max=256이라) 실패하는지 확인**

Run:
```bash
./gradlew :admin-app:test --tests "com.crosscert.passkey.admin.apikey.ApiKeyCreateRequestValidationTest"
```
Expected: `name65CharsViolatesSize` FAIL (현재 max=256이라 65자가 통과해 violation이 비어있음). 나머지 2개는 PASS.

- [ ] **Step 3: DTO 검증 축소**

`ApiKeyAdminDto.java:19`:
```java
            @NotBlank @Size(max = 256) String name,
```
을:
```java
            @NotBlank @Size(max = 64) String name,
```
로 변경.

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
./gradlew :admin-app:test --tests "com.crosscert.passkey.admin.apikey.ApiKeyCreateRequestValidationTest"
```
Expected: PASS (3 tests).

- [ ] **Step 5: 커밋**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminDto.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyCreateRequestValidationTest.java
git commit -m "fix(admin): API key 이름 검증 64자로 축소

256자는 '이름'으로 과도. @Size(max=64)로 축소(신규 요청에만 적용).
DB 컬럼(NAME length=256)은 유지 — 기존 데이터 영향 없음, 마이그레이션
불필요. Jakarta Validator 직접 단위 테스트.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: 프론트 발급 폼 — maxLength + 안내 + 글자수 카운터

**Files:**
- Modify: `admin-ui/src/pages/tenant/ApiKeysTab.tsx` (`NewKeyDialog`의 이름 입력, 현재 222~245행)

발급 폼의 이름 입력에 `NAME_MAX=64` 상수 기반 `maxLength`, 안내문 "(최대 64자)", 글자수 카운터를 추가한다.

- [ ] **Step 1: NAME_MAX 상수 추가**

`ApiKeysTab.tsx`에서 `SCOPE_OPTIONS` 상수 정의부 근처(파일 상단 모듈 스코프, 71행 부근)에 추가:
```tsx
const NAME_MAX = 64;
```

- [ ] **Step 2: 이름 입력에 maxLength + 안내 + 카운터 적용**

현재 (`NewKeyDialog` 내부, 243~245행):
```tsx
      <Field label="용도 (이름)" hint="배포 환경이나 용도를 짧게. 예: production, staging, mobile-app">
        <input autoFocus className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="production" />
      </Field>
```
을 아래로 교체:
```tsx
      <Field label="용도 (이름)" hint={`배포 환경이나 용도를 짧게. 예: production, staging, mobile-app (최대 ${NAME_MAX}자)`}>
        <input autoFocus className="input" maxLength={NAME_MAX} value={name} onChange={(e) => setName(e.target.value)} placeholder="production" />
        <div className="muted" style={{ fontSize: 11, textAlign: 'right', marginTop: 4 }}>{name.length} / {NAME_MAX}</div>
      </Field>
```

- [ ] **Step 3: 타입체크 + 전체 프론트 테스트**

Run:
```bash
cd admin-ui && npx tsc --noEmit && npx vitest run
```
Expected: tsc 에러 없음, 모든 vitest PASS.

- [ ] **Step 4: 커밋**

```bash
git add admin-ui/src/pages/tenant/ApiKeysTab.tsx
git commit -m "fix(admin-ui): API key 이름 입력 maxLength + 안내 + 글자수 카운터

NAME_MAX=64 상수로 maxLength·안내문·카운터를 단일 소스화. 백엔드
@Size(max=64)와 일치해 서버 400 전에 입력 단계에서 차단·안내.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: 전체 검증

**Files:** 없음 (검증 전용)

- [ ] **Step 1: 백엔드 신규 검증 테스트 + 컴파일**

Run (워크트리 루트):
```bash
./gradlew :admin-app:test --tests "com.crosscert.passkey.admin.apikey.ApiKeyCreateRequestValidationTest"
./gradlew :admin-app:compileJava :admin-app:compileTestJava
```
Expected: 검증 테스트 3 PASS, 컴파일 BUILD SUCCESSFUL.

- [ ] **Step 2: 프론트 타입체크 + 전체 테스트**

Run:
```bash
cd admin-ui && npx tsc --noEmit && npx vitest run
```
Expected: 에러 없음, 모든 테스트 PASS (clipboard 4 + 기존).

- [ ] **Step 3: codex 리뷰 (커밋 전 안전망)**

메모리 정책([[feedback_codex_review_before_commit]])에 따라 누적 diff를 codex로 독립 리뷰. P1/P2 지적이 있으면 수정 후 재검증.

- [ ] **Step 4: 최종 상태 확인**

Run:
```bash
git log --oneline -5
git status
```
Expected: Task 1~4 커밋 4개 + 설계/계획 문서. working tree clean.

---

## 완료 후 (이 계획 범위 밖, 사용자 확정 사항)

- 워크트리 작업 완료 후 main으로 `merge --no-ff` ([[feedback_per_phase_worktree]]).
- 수동 dogfooding: 실제 발급 화면에서 (HTTP 환경이면) 복사 버튼이 실제로 복사되는지, 이름 65자 입력이 막히는지, 카운터가 동작하는지 브라우저 확인.

---

## Self-Review

**1. Spec coverage**
- 클립보드 폴백 + 정직한 피드백 (설계 변경 1) → Task 1(유틸) + Task 2(버튼 연동) ✓
- 백엔드 64자 축소, DB 컬럼 유지 (설계 변경 2a) → Task 3 ✓
- 프론트 maxLength + 안내 + 카운터, NAME_MAX 상수 (설계 변경 2b) → Task 4 ✓
- 테스트 전략(복사 3경로, DTO 64/65/blank) → Task 1·3 ✓
- 성공 시에만 "복사됨" → Task 2 Step 3 (if ok) ✓

**2. Placeholder scan:** TBD/TODO/"적절히 처리" 없음. 모든 코드 단계에 완전한 코드 블록. ✓

**3. Type consistency:**
- `copyToClipboard(text: string): Promise<boolean>` — Task 1 정의 / Task 2 `await copyToClipboard(...)` 사용 일치 ✓
- `NAME_MAX = 64` — Task 4 정의 후 maxLength·안내·카운터에서 일관 사용 ✓
- `toast({ kind, title, message })` — ToastHost 시그니처(kind: 'ok'|'err'|'warn')와 Task 2의 `kind:'warn'` 일치 ✓
- `useToast()` — ApiKeysTab에 이미 import됨(5행), IssuedKeyModal에서 호출(Task 2 Step 2) ✓
- `ApiKeyCreateRequest(UUID tenantId, String name, Set<String> scopes)` — 실제 record 생성자와 테스트 호출 인자 순서 일치 ✓
- 백엔드 `@Size(max=64)` ↔ 프론트 `NAME_MAX=64` 동일 값 ✓
