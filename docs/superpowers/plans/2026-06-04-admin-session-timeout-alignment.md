# 어드민 세션 타임아웃 정합성 수정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 어드민 로그인 세션 만료 알림이 실제 백엔드 세션(`sessionIdleTimeoutMinutes` 정책값)과 정확히 일치하도록 만들고, "세션 연장" 버튼이 실제로 세션을 갱신하게 한다.

**Architecture:** 단일 진실 소스 = `sessionIdleTimeoutMinutes` 정책값. (1) 백엔드 로그인 성공 핸들러에서 정책값을 `HttpSession.setMaxInactiveInterval`에 스냅샷 적용. (2) 프론트 `IdleSessionModal`이 정책값을 prop으로 받아 (전체 idle − 60초) 후 경고를 띄우고 문구도 동적화. (3) 빈 `onExtend`를 `getMe()` 호출로 교체해 서버 세션 `lastAccessedTime`을 갱신.

**Tech Stack:** Java 17 / Spring Boot 3.5 / Spring Security / Spring Session(Redis) (admin-app), React 18 / TypeScript / Vitest / Testing Library (admin-ui).

**작업 위치:** 워크트리 `.claude/worktrees/admin-session-timeout`, 브랜치 `worktree-admin-session-timeout`. **모든 `git`/빌드/테스트 명령은 이 워크트리 디렉토리에서 실행한다.** 절대 메인 repo(`/Users/jhyun/Git/10-work/crosscert/Passkey2` 직하)에서 커밋하지 말 것.

---

## File Structure

| 파일 | 역할 | 변경 |
|------|------|------|
| `admin-app/.../config/AdminSecurityConfig.java` | 로그인 성공 핸들러 — 정책값을 세션 만료에 적용 | Modify |
| `admin-app/.../config/AdminLoginSuccessHandlerTest.java` | 핸들러가 `setMaxInactiveInterval(정책분*60)` 호출하는지 단위 검증 | Create |
| `admin-ui/src/extras/IdleSessionModal.tsx` | idle 타이머·문구를 정책값 기반 동적화 | Modify |
| `admin-ui/src/extras/IdleSessionModal.test.tsx` | 동적 타이머·문구·연장 호출 검증 | Create |
| `admin-ui/src/App.tsx` | 정책값 로드 → 모달에 주입 + `onExtend`=`getMe()` | Modify |

> 백엔드 검증은 무거운 `SecurityPolicyIT`(Oracle+Redis Testcontainers) 대신 **핸들러 람다 단위 테스트**(MockHttpServletRequest + Mockito stub)로 한다. admin-app에 이미 Mockito 패턴(`TenantAdminServiceTest`)이 있어 일관적이고 빠르다.

---

## Task 1: 백엔드 — 로그인 성공 시 정책값을 세션 만료에 적용

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java` (`adminLoginSuccessHandler` Bean, 현재 197–230행 부근)
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/config/AdminLoginSuccessHandlerTest.java`

`adminLoginSuccessHandler`는 Bean 메서드로, 의존성을 파라미터로 주입받는다. 핸들러 람다는 직접 호출 가능하므로, 이 Bean 메서드를 호출해 람다를 얻은 뒤 `MockHttpServletRequest`/`MockHttpServletResponse`로 실행하고, 세션의 `getMaxInactiveInterval()`을 검증한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-app/src/test/java/com/crosscert/passkey/admin/config/AdminLoginSuccessHandlerTest.java` 생성:

```java
package com.crosscert.passkey.admin.config;

import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.policy.SecurityPolicyDto;
import com.crosscert.passkey.admin.policy.SecurityPolicyService;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminLoginSuccessHandlerTest {

    private AuthenticationSuccessHandler handlerWithPolicyMinutes(int minutes) {
        AuditLogService audit = mock(AuditLogService.class);
        AdminUserRepository users = mock(AdminUserRepository.class);
        SecurityPolicyService policy = mock(SecurityPolicyService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-04T00:00:00Z"), ZoneOffset.UTC);
        ObjectMapper mapper = new ObjectMapper();

        AdminUser u = mock(AdminUser.class);
        when(u.getRole()).thenReturn("PLATFORM_OPERATOR");
        when(u.isMfaEnabled()).thenReturn(false);
        when(users.findByEmail("op@example.com")).thenReturn(Optional.of(u));

        when(policy.get()).thenReturn(new SecurityPolicyDto.View(
                minutes, 12, false, List.of(), Instant.now(), null));

        return new AdminSecurityConfig()
                .adminLoginSuccessHandler(audit, users, policy, clock, mapper);
    }

    @Test
    void appliesPolicyIdleTimeoutToSessionInSeconds() throws Exception {
        AuthenticationSuccessHandler handler = handlerWithPolicyMinutes(15);
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        Authentication auth =
                new UsernamePasswordAuthenticationToken("op@example.com", "x", List.of());

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(req.getSession(false)).isNotNull();
        assertThat(req.getSession(false).getMaxInactiveInterval()).isEqualTo(15 * 60);
    }

    @Test
    void defaultThirtyMinutesMapsTo1800Seconds() throws Exception {
        AuthenticationSuccessHandler handler = handlerWithPolicyMinutes(30);
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        Authentication auth =
                new UsernamePasswordAuthenticationToken("op@example.com", "x", List.of());

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(req.getSession(false).getMaxInactiveInterval()).isEqualTo(1800);
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 실패하는지 확인**

Run (워크트리 디렉토리에서):
```bash
./gradlew :admin-app:compileTestJava
```
Expected: 컴파일 실패 — `adminLoginSuccessHandler(...)`의 시그니처가 `SecurityPolicyService` 파라미터를 아직 받지 않음 ("method ... cannot be applied to given types" 또는 인자 개수 불일치).

- [ ] **Step 3: 핸들러에 SecurityPolicyService 주입 + setMaxInactiveInterval 호출**

`AdminSecurityConfig.java`에서 import 추가 (파일 상단 import 블록, `com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource;` 줄 아래):
```java
import com.crosscert.passkey.admin.policy.SecurityPolicyService;
```

`adminLoginSuccessHandler` Bean 메서드 시그니처에 `SecurityPolicyService policy` 추가:
```java
    @Bean
    public AuthenticationSuccessHandler adminLoginSuccessHandler(AuditLogService audit,
                                                                  AdminUserRepository users,
                                                                  SecurityPolicyService policy,
                                                                  Clock clock,
                                                                  ObjectMapper mapper) {
```

람다 본문에서 세션 생성 직후(이메일/유저 조회 뒤, MFA stamp 부근) 정책값을 적용. 기존 `boolean mfaRequired = u.isMfaEnabled();` **위**에 삽입:
```java
            // Snapshot the operator-configured idle timeout onto this session.
            // application.yml's session.timeout (PT30M) is only the pre-login
            // default; from here on the session honors the SecurityPolicy value
            // so the settings label and the front-end warning modal are truthful.
            int idleMinutes = policy.get().sessionIdleTimeoutMinutes();
            req.getSession().setMaxInactiveInterval(idleMinutes * 60);

            boolean mfaRequired = u.isMfaEnabled();
```

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
./gradlew :admin-app:test --tests "com.crosscert.passkey.admin.config.AdminLoginSuccessHandlerTest"
```
Expected: PASS (2 tests). `appliesPolicyIdleTimeoutToSessionInSeconds` → 900, `defaultThirtyMinutesMapsTo1800Seconds` → 1800.

- [ ] **Step 5: 커밋**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/config/AdminLoginSuccessHandlerTest.java
git commit -m "fix(admin): 로그인 시 세션 idle 타임아웃을 정책값으로 설정

sessionIdleTimeoutMinutes 정책이 실제 HttpSession.maxInactiveInterval에
적용되지 않아 설정 화면 라벨이 거짓이었음. adminLoginSuccessHandler에서
SecurityPolicyService.get().sessionIdleTimeoutMinutes()를 읽어
setMaxInactiveInterval(분*60) 적용. application.yml PT30M은 로그인 전
기본값/폴백으로 유지.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 프론트 — IdleSessionModal을 정책값 기반으로 동적화

**Files:**
- Modify: `admin-ui/src/extras/IdleSessionModal.tsx`
- Test: `admin-ui/src/extras/IdleSessionModal.test.tsx`

현재 모달은 `90 * 1000`(90초 데모) 하드코딩 + "30분" 문구 하드코딩. `idleTimeoutMinutes` prop을 추가해 (전체 idle − 60초) 후 경고를 띄우고 문구도 동적화한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`admin-ui/src/extras/IdleSessionModal.test.tsx` 생성:
```tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { IdleSessionModal } from './IdleSessionModal';

describe('IdleSessionModal', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it('shows the warning after (idleMinutes*60 - 60) seconds of inactivity', () => {
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={vi.fn()} onLogout={vi.fn()} />);
    // 2분 = 120s, 경고 리드타임 60s → 60s 후 모달.
    act(() => { vi.advanceTimersByTime(59 * 1000); });
    expect(screen.queryByText(/세션이 곧 만료됩니다/)).toBeNull();
    act(() => { vi.advanceTimersByTime(2 * 1000); }); // 61s 총
    expect(screen.getByText(/세션이 곧 만료됩니다/)).toBeInTheDocument();
  });

  it('renders the configured minutes in the body copy, not a hardcoded 30', () => {
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={vi.fn()} onLogout={vi.fn()} />);
    act(() => { vi.advanceTimersByTime(61 * 1000); });
    expect(screen.getByText(/보안을 위해 2분 동안/)).toBeInTheDocument();
    expect(screen.queryByText(/30분 동안/)).toBeNull();
  });

  it('calls onExtend when 세션 연장 is clicked', () => {
    const onExtend = vi.fn();
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={onExtend} onLogout={vi.fn()} />);
    act(() => { vi.advanceTimersByTime(61 * 1000); });
    act(() => { screen.getByRole('button', { name: '세션 연장' }).click(); });
    expect(onExtend).toHaveBeenCalledTimes(1);
  });
});
```

- [ ] **Step 2: 테스트 실패 확인**

Run:
```bash
cd admin-ui && npx vitest run src/extras/IdleSessionModal.test.tsx
```
Expected: FAIL — 첫 테스트에서 90초 하드코딩 때문에 61초 시점에 모달이 안 뜸 / prop 타입(`idleTimeoutMinutes`) 미존재로 TS 에러.

- [ ] **Step 3: IdleSessionModal을 prop 기반으로 수정**

`admin-ui/src/extras/IdleSessionModal.tsx` 전체를 아래로 교체:
```tsx
import { useState, useEffect, useRef, ReactNode } from 'react';
import { Dialog } from '@/shell/Dialog';
import { Icons } from '@/icons/Icons';

export type IdleSessionModalProps = {
  idleTimeoutMinutes: number;
  onExtend: () => void;
  onLogout: () => void;
};

const WARN_LEAD_SECONDS = 60;

export function IdleSessionModal({ idleTimeoutMinutes, onExtend, onLogout }: IdleSessionModalProps): ReactNode {
  const [open, setOpen] = useState(false);
  const [secondsLeft, setSecondsLeft] = useState(WARN_LEAD_SECONDS);
  const idleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    // 경고는 전체 idle 시간에서 카운트다운 리드타임(60s)을 뺀 시점에 뜬다.
    // 정책이 1분처럼 짧아도 음수가 되지 않도록 최소 10초를 보장한다.
    const warnAfterMs = Math.max(idleTimeoutMinutes * 60 - WARN_LEAD_SECONDS, 10) * 1000;
    function bumpIdle() {
      clearTimeout(idleTimer.current!);
      idleTimer.current = setTimeout(() => {
        setSecondsLeft(WARN_LEAD_SECONDS);
        setOpen(true);
      }, warnAfterMs);
    }
    bumpIdle();
    const handler = () => { if (!open) bumpIdle(); };
    ["mousemove", "keydown", "click", "scroll"].forEach((e) => window.addEventListener(e, handler, { passive: true }));
    return () => {
      clearTimeout(idleTimer.current!);
      ["mousemove", "keydown", "click", "scroll"].forEach((e) => window.removeEventListener(e, handler));
    };
  }, [open, idleTimeoutMinutes]);

  useEffect(() => {
    if (!open) return;
    const tick = setInterval(() => {
      setSecondsLeft((s) => {
        if (s <= 1) { clearInterval(tick); setOpen(false); onLogout?.(); return 0; }
        return s - 1;
      });
    }, 1000);
    return () => clearInterval(tick);
  }, [open, onLogout]);

  if (!open) return null;
  return (
    <Dialog open={open} onClose={() => {}} closeOnScrim={false}
      title={<span style={{ display: "flex", alignItems: "center", gap: 8 }}><Icons.Lock size={18} /> 세션이 곧 만료됩니다</span>}
      sub={`${secondsLeft}초 후 자동으로 로그아웃됩니다.`}
      footer={<>
        <button className="btn" onClick={() => { setOpen(false); onLogout(); }}>지금 로그아웃</button>
        <button className="btn btn--primary" onClick={() => { setOpen(false); onExtend(); }}>세션 연장</button>
      </>}
    >
      <div className="stack-3">
        <p style={{ margin: 0, fontSize: 13, color: "var(--text-soft)", lineHeight: 1.6 }}>
          보안을 위해 {idleTimeoutMinutes}분 동안 활동이 없으면 자동으로 로그아웃됩니다. 작업을 계속하려면 <strong>세션 연장</strong>을 눌러주세요.
        </p>
        <div style={{ height: 6, borderRadius: 4, background: "var(--surface-3)", overflow: "hidden" }}>
          <div style={{ width: `${(secondsLeft / WARN_LEAD_SECONDS) * 100}%`, height: "100%", background: secondsLeft > 20 ? "var(--accent)" : "var(--danger)", transition: "width 1s linear, background 220ms" }} />
        </div>
        <div className="muted" style={{ fontSize: 12 }}>
          모든 mutation은 audit log에 기록되어 있으므로 작업 내역은 보존됩니다.
        </div>
      </div>
    </Dialog>
  );
}
```

변경 요점: `idleTimeoutMinutes` prop 추가 / `WARN_LEAD_SECONDS=60` 상수화 / `warnAfterMs = max(분*60−60, 10)*1000` / 문구·progress bar를 상수·prop 기반으로 / idle effect 의존성에 `idleTimeoutMinutes` 추가.

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd admin-ui && npx vitest run src/extras/IdleSessionModal.test.tsx
```
Expected: PASS (3 tests).

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/extras/IdleSessionModal.tsx admin-ui/src/extras/IdleSessionModal.test.tsx
git commit -m "fix(admin-ui): idle 경고 모달을 정책값 기반으로 동적화

90초 데모 하드코딩과 '30분' 문구 하드코딩 제거. idleTimeoutMinutes
prop을 받아 (전체-60초) 후 경고, 문구도 동적. 최소 10초 가드.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 프론트 — App.tsx에서 정책값 주입 + 연장 버튼 구현

**Files:**
- Modify: `admin-ui/src/App.tsx` (import 블록, `AuthenticatedApp` 내부, 163행 모달 렌더)

`AuthenticatedApp`가 마운트 시 `securityPolicyApi.get()`으로 `sessionIdleTimeoutMinutes`를 읽어 state에 담고, 모달에 주입. 로드 전/실패 시 30분 폴백. `onExtend`는 `getMe()` 호출로 교체.

- [ ] **Step 1: import 추가**

`App.tsx` import 블록에서 `import { api } from '@/api/client';` 줄을 아래로 교체:
```tsx
import { api, getMe } from '@/api/client';
import { securityPolicyApi } from '@/api/securityPolicy';
```

- [ ] **Step 2: 정책값 state + 로드 effect 추가**

`AuthenticatedApp` 함수 본문에서 `const [paletteOpen, setPaletteOpen] = useState(false);` 줄 **아래**에 추가:
```tsx
  const [idleTimeoutMinutes, setIdleTimeoutMinutes] = useState(30); // 정책 로드 전/실패 시 30분 폴백
  useEffect(() => {
    let cancelled = false;
    securityPolicyApi.get()
      .then((p) => { if (!cancelled) setIdleTimeoutMinutes(p.sessionIdleTimeoutMinutes); })
      .catch(() => { /* 폴백 30분 유지 */ });
    return () => { cancelled = true; };
  }, []);
```

- [ ] **Step 3: 모달 렌더를 정책값 + getMe로 교체**

163행:
```tsx
      <IdleSessionModal onExtend={() => { /* refresh /me — Phase E3 */ }} onLogout={onLogout} />
```
을 아래로 교체:
```tsx
      <IdleSessionModal
        idleTimeoutMinutes={idleTimeoutMinutes}
        onExtend={() => { void getMe(); }}
        onLogout={onLogout}
      />
```

- [ ] **Step 4: 타입체크 + 전체 프론트 테스트 통과 확인**

Run:
```bash
cd admin-ui && npx tsc --noEmit && npx vitest run
```
Expected: tsc 에러 없음. 모든 vitest 스위트 PASS (신규 IdleSessionModal 포함).

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/App.tsx
git commit -m "fix(admin-ui): 세션 정책값 주입 + 연장 버튼 실제 구현

AuthenticatedApp이 securityPolicyApi.get()으로 idle 분을 로드해
IdleSessionModal에 주입(실패 시 30분 폴백). 빈 onExtend를 getMe()
호출로 교체 — 서버 세션 lastAccessedTime 갱신으로 실제 연장.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: 전체 검증 (백엔드 + 프론트)

**Files:** 없음 (검증 전용)

- [ ] **Step 1: 백엔드 신규 단위 테스트 + 컴파일 확인**

Run (워크트리 루트):
```bash
./gradlew :admin-app:test --tests "com.crosscert.passkey.admin.config.AdminLoginSuccessHandlerTest"
```
Expected: PASS. (전체 admin-app 테스트는 Testcontainers라 무거우므로 신규 단위만 확인; 기존 IT는 CI에 위임)

- [ ] **Step 2: admin-app 컴파일 전체 확인 (회귀 없음)**

Run:
```bash
./gradlew :admin-app:compileJava :admin-app:compileTestJava
```
Expected: BUILD SUCCESSFUL — 핸들러 시그니처 변경이 다른 호출부를 깨지 않음(Bean 주입은 Spring이 처리).

- [ ] **Step 3: 프론트 타입체크 + 전체 테스트**

Run:
```bash
cd admin-ui && npx tsc --noEmit && npx vitest run
```
Expected: 에러 없음, 모든 테스트 PASS.

- [ ] **Step 4: codex 리뷰 (커밋 전 안전망)**

메모리 정책([[feedback_codex_review_before_commit]])에 따라 staged/누적 diff를 codex로 독립 리뷰한다.
Run:
```bash
git diff worktree-admin-session-timeout~3 --stat
```
그 후 `/codex:review`로 누적 변경(3커밋)을 리뷰. P1/P2 지적이 있으면 수정 후 재검증.

- [ ] **Step 5: 최종 상태 확인**

Run:
```bash
git log --oneline -4
git status
```
Expected: Task 1–3 커밋 3개 + 설계/계획 문서 커밋. working tree clean.

---

## 완료 후 (이 계획 범위 밖, 사용자 확정 사항)

- 워크트리 작업 완료 후 main으로 `merge --no-ff` ([[feedback_per_phase_worktree]]).
- 수동 dogfooding: 실제 로그인 → 정책 분을 짧게(예: 1~2분) 설정하고 재로그인 → 모달이 (분−60초)에 뜨고 연장 버튼이 세션을 갱신하는지 브라우저 확인.

---

## Self-Review

**1. Spec coverage**
- 설계 변경 1 (백엔드 정책→세션) → Task 1 ✓
- 설계 변경 2 (모달 동적화: 90초→정책값, 문구) → Task 2 ✓
- 설계 변경 3 (onExtend=getMe) → Task 3 ✓
- 값 전달 경로(App에서 securityPolicyApi.get, 30분 폴백) → Task 3 ✓
- 테스트 전략(백엔드 setMaxInactiveInterval 검증, 프론트 타이머/문구/연장) → Task 1·2 ✓
- application.yml PT30M 폴백 유지 → Task 1 Step 3 주석으로 명시, 코드 변경 없음 ✓

**2. Placeholder scan:** TBD/TODO/"적절히 처리" 없음. 모든 코드 단계에 완전한 코드 블록 존재 ✓

**3. Type consistency:**
- `idleTimeoutMinutes: number` — Task 2 prop 정의 / Task 3 주입 일치 ✓
- `adminLoginSuccessHandler(audit, users, policy, clock, mapper)` — Task 1 시그니처 / 테스트 호출 인자 순서 일치 ✓
- `SecurityPolicyDto.View(sessionIdleTimeoutMinutes, passwordMinLength, mfaRequired, corsAllowlist, updatedAt, updatedBy)` — 실제 record 순서(SecurityPolicyService.toView 확인)와 테스트 stub 인자 순서 일치 ✓
- `getMe` named export (client.ts:132) / `securityPolicyApi.get` (securityPolicy.ts:22) — Task 3 import와 일치 ✓
- `WARN_LEAD_SECONDS=60` — 초기 `secondsLeft`, progress bar 분모, warnAfterMs 모두 동일 상수 사용 ✓
