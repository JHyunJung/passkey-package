# Admin-UI: 테넌트 suspend/activate + Admin MFA — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** P0 백엔드 기능(테넌트 suspend/activate, admin TOTP MFA)을 운영자가 admin-ui에서 실제로 사용할 수 있게 한다 — MFA enroll/confirm 분리 + Account 탭 + 로그인 MFA 게이트 + 테넌트 정지 버튼.

**Architecture:** 백엔드(admin-app)를 먼저 보강해 프론트가 호출할 API를 갖춘다: MfaController enroll/confirm 분리, MeController에 mfaEnabled/mfaRequired 노출, MfaPendingFilter가 /admin/api/me 허용. 그 다음 admin-ui(React 18 + Router v6 + fetch api client + Dialog/toast 디자인 시스템)에 MFA UI(MfaChallenge 게이트 + AccountTab 라이프사이클)와 테넌트 정지 UI를 기존 패턴대로 추가. qrcode 의존성 1개.

**Tech Stack:** Java 17 / Spring Boot 3 / JUnit5 (백엔드), React 18 + TypeScript + Vite + react-router-dom v6 + qrcode (프론트). 백엔드는 TDD, 프론트는 tsc/vite build 게이트 + 브라우저 dogfooding.

**근거 spec:** `docs/superpowers/specs/2026-05-30-admin-ui-tenant-suspend-mfa-design.md`

---

## 파일 구조 (생성/수정 맵)

**백엔드 (admin-app):**
- Modify: `admin-app/.../auth/MfaController.java` — enroll(비활성+otpauthUri), confirm(신규), disable(신규)
- Modify: `admin-app/.../config/MeController.java` — mfaEnabled/mfaRequired
- Modify: `admin-app/.../auth/MfaPendingFilter.java` — /admin/api/me allowlist
- Modify: `admin-app/.../auth/MfaControllerSecurityTest.java` — enroll 새 동작 + confirm/disable 테스트
- Modify: `admin-app/.../config/` MeController 테스트 (있으면)

**프론트 (admin-ui):**
- Modify: `admin-ui/package.json` — qrcode + @types/qrcode
- Modify: `admin-ui/src/api/types.ts` — Me에 mfaEnabled/mfaRequired
- Create: `admin-ui/src/api/mfa.ts` — enroll/confirm/verify/disable
- Modify: `admin-ui/src/api/tenants.ts` — suspend/activate
- Create: `admin-ui/src/shell/QrCode.tsx` — qrcode 래퍼
- Create: `admin-ui/src/pages/MfaChallenge.tsx` — 로그인 MFA 게이트
- Create: `admin-ui/src/pages/settings/AccountTab.tsx` — 내 계정/보안
- Modify: `admin-ui/src/pages/SettingsPage.tsx` — account 탭
- Modify: `admin-ui/src/App.tsx` — mfaRequired 분기
- Modify: `admin-ui/src/pages/TenantDetailPage.tsx` — 정지/해제 버튼 + 다이얼로그

---

## Task 1 (백엔드): MfaController enroll 동작 변경 + confirm/disable 추가

**근거:** spec §3.1. enroll은 secret만 발급(mfa_enabled=N), confirm 성공 시에만 활성화. disable은 코드 검증 후 비활성.

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaController.java`
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/auth/MfaControllerSecurityTest.java`

- [ ] **Step 1: 기존 enroll 테스트가 깨지도록 새 동작 기대 테스트로 갱신 (TDD)**

먼저 현재 `MfaControllerSecurityTest`를 읽어 enroll 테스트가 `setMfaEnabled(true)`를 검증하는 부분을 찾는다. 그 테스트를 **enroll은 secret 저장 + mfa_enabled를 false로 세팅**하도록 기대를 뒤집고, confirm/disable 테스트를 추가한다. `MfaControllerSecurityTest.java`에 아래 테스트들을 반영(기존 enroll 테스트 교체 + 신규 추가):

```java
    @Test
    void enroll_storesSecret_andSetsEnabledFalse_returnsOtpauthUri() throws Exception {
        AdminUser u = mock(AdminUser.class);
        when(u.getEmail()).thenReturn("alice@crosscert.com");
        when(adminUserRepository.findByEmail("alice@crosscert.com")).thenReturn(java.util.Optional.of(u));

        mvc.perform(post("/admin/api/mfa/enroll").with(csrf())
                        .with(user("alice@crosscert.com").roles("PLATFORM_OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/")));

        verify(u).setMfaSecret(org.mockito.ArgumentMatchers.anyString());
        verify(u).setMfaEnabled(false);   // 핵심: enroll 은 활성화하지 않는다
        verify(adminUserRepository).save(u);
    }

    @Test
    void confirm_withValidCode_enablesMfa() throws Exception {
        // 실제 TotpService 로 결정론적 코드 생성 (fixed Clock 사용 — 슬라이스 Clock 빈 확인)
        String secret = totpService.newSecretBase32();
        String code = totpService.generate(secret, fixedClock.millis());
        AdminUser u = mock(AdminUser.class);
        when(u.getEmail()).thenReturn("alice@crosscert.com");
        when(u.getMfaSecret()).thenReturn(secret);
        when(adminUserRepository.findByEmail("alice@crosscert.com")).thenReturn(java.util.Optional.of(u));

        mvc.perform(post("/admin/api/mfa/confirm").with(csrf())
                        .with(user("alice@crosscert.com").roles("PLATFORM_OPERATOR"))
                        .contentType("application/json")
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(true));
        verify(u).setMfaEnabled(true);
        verify(adminUserRepository).save(u);
    }

    @Test
    void confirm_withWrongCode_returns401_doesNotEnable() throws Exception {
        AdminUser u = mock(AdminUser.class);
        when(u.getMfaSecret()).thenReturn(totpService.newSecretBase32());
        when(adminUserRepository.findByEmail("alice@crosscert.com")).thenReturn(java.util.Optional.of(u));

        mvc.perform(post("/admin/api/mfa/confirm").with(csrf())
                        .with(user("alice@crosscert.com").roles("PLATFORM_OPERATOR"))
                        .contentType("application/json").content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_code"));
        verify(u, never()).setMfaEnabled(true);
    }

    @Test
    void disable_withValidCode_clearsMfa() throws Exception {
        String secret = totpService.newSecretBase32();
        String code = totpService.generate(secret, fixedClock.millis());
        AdminUser u = mock(AdminUser.class);
        when(u.getMfaSecret()).thenReturn(secret);
        when(adminUserRepository.findByEmail("alice@crosscert.com")).thenReturn(java.util.Optional.of(u));

        mvc.perform(post("/admin/api/mfa/disable").with(csrf())
                        .with(user("alice@crosscert.com").roles("PLATFORM_OPERATOR"))
                        .contentType("application/json").content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());
        verify(u).setMfaEnabled(false);
        verify(u).setMfaSecret(null);
    }
```

> **검증 필요(슬라이스 wiring):** `MfaControllerSecurityTest`가 현재 `TotpService`를 실제 빈으로 쓰는지(@MockBean이 아니라) 확인. spec/이전 P0 리뷰에서 "실제 TotpService + fixed Clock" 패턴을 썼다고 했으므로 그 구조를 재사용한다. `fixedClock`/`totpService` 필드가 없으면, 기존 테스트가 쓰는 Clock 주입 방식을 그대로 사용. `generate(secret, millis)`는 TotpService의 public 메서드(P0에서 추가됨) — 시그니처 확인. `.with(user(...))`, `csrf()`는 spring-security-test import 필요(기존 테스트에 이미 있을 것).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :admin-app:test --tests "*MfaControllerSecurityTest*"`
Expected: 컴파일 또는 assertion 실패 — confirm/disable 엔드포인트 없음, enroll이 아직 setMfaEnabled(true) 호출.

- [ ] **Step 3: MfaController 구현 변경**

Modify `MfaController.java`:

1. enroll 메서드를 아래로 교체(otpauthUri 생성 + mfa_enabled=false):

```java
    @PostMapping("/enroll")
    public ResponseEntity<?> enroll(Authentication auth) {
        String email = auth.getName();
        AdminUser u = users.findByEmail(email).orElse(null);
        if (u == null) {
            log.warn("admin mfa enroll: no user row for principal email={}", mask(email));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        String secret = totp.newSecretBase32();
        u.setMfaSecret(secret);
        u.setMfaEnabled(false);   // confirm 전까지 비활성 — 미확인 상태에서 게이트 동작 방지
        users.save(u);
        String issuer = "Passkey Admin";
        String otpauthUri = "otpauth://totp/"
                + enc(issuer) + ":" + enc(email)
                + "?secret=" + secret
                + "&issuer=" + enc(issuer);
        log.info("admin mfa enroll (pending confirm): email={}", mask(email));
        return ResponseEntity.ok(Map.of("secret", secret, "otpauthUri", otpauthUri));
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@RequestBody VerifyRequest body, Authentication auth) {
        String email = auth.getName();
        AdminUser u = users.findByEmail(email).orElse(null);
        String code = body == null ? null : body.code();
        boolean ok = u != null && u.getMfaSecret() != null && code != null
                && totp.verifyAt(u.getMfaSecret(), code, clock.millis());
        if (!ok) {
            log.warn("admin mfa confirm failed: email={}", mask(email));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_code"));
        }
        u.setMfaEnabled(true);
        users.save(u);
        log.info("admin mfa confirmed (enabled): email={}", mask(email));
        return ResponseEntity.ok(Map.of("confirmed", true));
    }

    @PostMapping("/disable")
    public ResponseEntity<?> disable(@RequestBody VerifyRequest body, Authentication auth) {
        String email = auth.getName();
        AdminUser u = users.findByEmail(email).orElse(null);
        String code = body == null ? null : body.code();
        boolean ok = u != null && u.getMfaSecret() != null && code != null
                && totp.verifyAt(u.getMfaSecret(), code, clock.millis());
        if (!ok) {
            log.warn("admin mfa disable failed: email={}", mask(email));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_code"));
        }
        u.setMfaEnabled(false);
        u.setMfaSecret(null);
        users.save(u);
        log.info("admin mfa disabled: email={}", mask(email));
        return ResponseEntity.ok(Map.of("disabled", true));
    }
```

2. 클래스에 `enc` 헬퍼 추가 (URLEncoder, UTF-8):

```java
    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
```

> verify 메서드는 변경하지 않는다. `VerifyRequest record(String code)`는 이미 존재하므로 confirm/disable이 재사용.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :admin-app:test --tests "*MfaControllerSecurityTest*"`
Expected: PASS (enroll 새 동작 + confirm 성공/실패 + disable).

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaController.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/auth/MfaControllerSecurityTest.java
git commit -m "feat(admin): MFA enroll/confirm 분리 + disable — confirm 전 비활성 (admin-ui 선행)"
```

---

## Task 2 (백엔드): MeController에 mfaEnabled/mfaRequired + MfaPendingFilter me 허용

**근거:** spec §3.2. SPA가 로그인 후 MFA 분기를 하려면 me가 두 플래그를 줘야 하고, MFA_PENDING 세션에서도 me는 호출 가능해야 한다.

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/MeController.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaPendingFilter.java`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/config/MeControllerTest.java` (없으면 생성) 또는 기존 me 테스트 갱신

- [ ] **Step 1: MfaPendingFilter가 /admin/api/me 허용하도록 (TDD via 기존 MfaPendingFilterTest)**

`MfaPendingFilterTest`에 테스트 추가: pending 세션 + 경로 `/admin/api/me` → chain 통과(403 아님).

```java
    @Test
    void pending_allowsMeEndpoint() throws Exception {
        // 기존 테스트의 pending 세션 구성 패턴 재사용
        request.setServletPath("/admin/api/me");   // 또는 setRequestURI, 기존 테스트가 쓰는 방식대로
        // session 에 MFA_PENDING=true, 인증된 principal 세팅
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);   // 통과
    }
```

> **검증:** 기존 `MfaPendingFilterTest`가 request 경로를 어떻게 세팅하는지(setServletPath vs setRequestURI) 보고 일치시킨다. pending 세션/인증 구성도 기존 테스트 헬퍼 그대로.

- [ ] **Step 2: Run — fail**

Run: `./gradlew :admin-app:test --tests "*MfaPendingFilterTest*"`
Expected: 새 테스트 FAIL — `/admin/api/me`가 현재 allowlist에 없어 403.

- [ ] **Step 3: MfaPendingFilter allowlist에 /admin/api/me 추가**

`MfaPendingFilter.java`에서 allowlist 판정부(현재 `/admin/api/mfa/` + `/admin/logout` 허용)에 `/admin/api/me` equals 조건 추가:

```java
        // 기존 allow 조건에 추가:
        if (uri.equals("/admin/api/me")) {
            chain.doFilter(req, res);
            return;
        }
```

> 기존 allowlist 구현 형태(startsWith/equals 분기)에 맞춰 일관되게 삽입. me는 자기 MFA 상태를 알려주는 부트스트랩이므로 pending 중에도 허용해야 SPA가 MfaChallenge로 분기 가능.

- [ ] **Step 4: Run — pass**

Run: `./gradlew :admin-app:test --tests "*MfaPendingFilterTest*"`
Expected: PASS.

- [ ] **Step 5: MeController에 mfaEnabled/mfaRequired 추가 (테스트 먼저)**

`MeControllerTest`(없으면 @WebMvcTest 슬라이스로 생성, 기존 *ControllerSecurityTest 패턴 따라)에:

```java
    @Test
    void me_returnsMfaFlags() throws Exception {
        AdminUser u = mock(AdminUser.class);
        when(u.isMfaEnabled()).thenReturn(true);
        when(adminUserRepository.findByEmail("alice@crosscert.com")).thenReturn(java.util.Optional.of(u));

        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(MfaPendingFilter.MFA_PENDING_ATTR, Boolean.TRUE);

        mvc.perform(get("/admin/api/me").session(session)
                        .with(user("alice@crosscert.com").roles("PLATFORM_OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mfaEnabled").value(true))
                .andExpect(jsonPath("$.data.mfaRequired").value(true));
    }
```

> `$.data.*` 경로 — MeController는 ApiResponse envelope로 감싸므로. 기존 me 테스트가 있으면 그 경로 컨벤션 확인.

- [ ] **Step 6: Run — fail, 그다음 구현**

`MeController.java`를 수정: `AdminUserRepository`, `HttpServletRequest` 주입. mfaEnabled = AdminUser 조회, mfaRequired = 세션 `MFA_PENDING_ATTR` 존재 여부.

```java
    private final AdminUserRepository users;

    public MeController(AdminUserRepository users) { this.users = users; }

    @GetMapping("/me")
    public ApiResponse<MeView> me(Authentication auth, jakarta.servlet.http.HttpServletRequest req) {
        AdminUserDetails principal = (AdminUserDetails) auth.getPrincipal();
        boolean mfaEnabled = users.findByEmail(principal.getUsername())
                .map(AdminUser::isMfaEnabled).orElse(false);
        var session = req.getSession(false);
        boolean mfaRequired = session != null
                && Boolean.TRUE.equals(session.getAttribute(
                        com.crosscert.passkey.admin.auth.MfaPendingFilter.MFA_PENDING_ATTR));
        return ApiResponse.ok(new MeView(
                principal.getUsername(), principal.getRole(), principal.getTenantId(),
                mfaEnabled, mfaRequired));
    }
```

`MeView` record에 두 boolean 필드 추가: `record MeView(String email, String role, String tenantId, boolean mfaEnabled, boolean mfaRequired)`. (MeView 정의 위치 확인 — MeController 내부 또는 별도 파일.)

- [ ] **Step 7: Run — pass + 회귀**

Run: `./gradlew :admin-app:test --tests "*MeController*" --tests "*MfaPendingFilterTest*"`
Expected: PASS. MeView 시그니처 변경으로 다른 호출처가 깨지면(거의 없음) 수정.

- [ ] **Step 8: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/config/MeController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaPendingFilter.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/
git commit -m "feat(admin): /me 에 mfaEnabled/mfaRequired + MfaPendingFilter me 허용 (admin-ui 선행)"
```

---

## Task 3 (프론트): qrcode 의존성 + QrCode 컴포넌트 + mfa api + Me 타입

**근거:** spec §4.1, §4.2. MFA UI의 토대.

**Files:**
- Modify: `admin-ui/package.json`
- Modify: `admin-ui/src/api/types.ts`
- Create: `admin-ui/src/api/mfa.ts`
- Create: `admin-ui/src/shell/QrCode.tsx`

- [ ] **Step 1: qrcode 의존성 추가**

Run (admin-ui 디렉터리에서):
```bash
cd admin-ui && npm install qrcode && npm install -D @types/qrcode
```
Expected: package.json dependencies에 `qrcode`, devDependencies에 `@types/qrcode` 추가. `npm install` 성공.

- [ ] **Step 2: Me 타입에 mfa 플래그 추가**

Modify `admin-ui/src/api/types.ts` — `Me` 인터페이스:
```typescript
export interface Me {
  email: string;
  role: 'PLATFORM_OPERATOR' | 'RP_ADMIN';
  tenantId: string | null;
  mfaEnabled: boolean;
  mfaRequired: boolean;
}
```

- [ ] **Step 3: mfa api 래퍼 작성**

**중요(redirect 회피)**: 공용 `api.postRaw`는 401 시 `window.location.pathname`이 `/admin/login`이 아니면 로그인 페이지로 redirect한다 (`client.ts`의 `rawRequest`). 그런데 MFA의 401은 `invalid_code`(코드 오류)로, 화면에서 toast+재입력으로 처리해야 하므로 **redirect되면 안 된다**. 따라서 mfa.ts는 공용 client를 쓰지 않고 **전용 fetch 함수 `mfaPost`**를 두어 401을 redirect 없이 `ApiError`로만 던진다.

Create `admin-ui/src/api/mfa.ts`:
```typescript
import { ApiError } from './types';

function getCookie(name: string): string | null {
  const m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return m ? decodeURIComponent(m[1]) : null;
}

// MFA 전용 POST — 401(invalid_code)을 로그인 redirect 없이 ApiError 로 던진다.
async function mfaPost<T>(path: string, body: unknown): Promise<T> {
  const csrf = getCookie('XSRF-TOKEN');
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(csrf ? { 'X-XSRF-TOKEN': csrf } : {}) },
    credentials: 'include',
    body: JSON.stringify(body),
  });
  let json: unknown = null;
  try { json = await res.json(); } catch { /* empty */ }
  if (!res.ok) {
    const err = (json as { error?: string })?.error ?? 'error';
    throw new ApiError(res.status, err, err);
  }
  return json as T;
}

export interface EnrollResponse { secret: string; otpauthUri: string; }

export const mfaApi = {
  enroll: () => mfaPost<EnrollResponse>('/admin/api/mfa/enroll', {}),
  confirm: (code: string) => mfaPost<{ confirmed: boolean }>('/admin/api/mfa/confirm', { code }),
  verify: (code: string) => mfaPost<{ verified: boolean }>('/admin/api/mfa/verify', { code }),
  disable: (code: string) => mfaPost<{ disabled: boolean }>('/admin/api/mfa/disable', { code }),
};
```

- [ ] **Step 4: QrCode 컴포넌트 작성**

Create `admin-ui/src/shell/QrCode.tsx`:
```typescript
import { useEffect, useRef } from 'react';
import QRCode from 'qrcode';

export function QrCode({ value, size = 176 }: { value: string; size?: number }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    if (!ref.current) return;
    QRCode.toCanvas(ref.current, value, { width: size, margin: 1 }, (err) => {
      if (err) console.error('QR render failed', err);
    });
  }, [value, size]);
  return <canvas ref={ref} width={size} height={size} style={{ borderRadius: 6, background: '#fff', padding: 8 }} />;
}
```

- [ ] **Step 5: 타입 체크**

Run: `cd admin-ui && npx tsc --noEmit`
Expected: 에러 없음. (Me에 새 필드 추가로 me를 생성하는 곳이 깨지면 — me는 서버 응답이므로 보통 안 깨짐. 깨지면 해당 지점 수정.)

- [ ] **Step 6: Commit**

```bash
git add admin-ui/package.json admin-ui/package-lock.json \
        admin-ui/src/api/types.ts admin-ui/src/api/mfa.ts admin-ui/src/shell/QrCode.tsx
git commit -m "feat(admin-ui): mfa api 래퍼 + QrCode 컴포넌트 + Me mfa 플래그 + qrcode 의존성"
```

---

## Task 4 (프론트): MfaChallenge — 로그인 후 MFA 게이트

**근거:** spec §4.1, §5.2. 로그인 성공 후 mfaRequired면 코드 입력 게이트.

**Files:**
- Create: `admin-ui/src/pages/MfaChallenge.tsx`
- Modify: `admin-ui/src/App.tsx`

- [ ] **Step 1: MfaChallenge 컴포넌트 작성**

Create `admin-ui/src/pages/MfaChallenge.tsx`:
```typescript
import { useState } from 'react';
import { mfaApi } from '../api/mfa';
import { useToast } from '../shell/ToastHost';

export default function MfaChallenge({ onVerified, onLogout }: { onVerified: () => void; onLogout: () => void }) {
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const toast = useToast();

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting || code.length !== 6) return;
    setSubmitting(true);
    try {
      await mfaApi.verify(code);
      onVerified();
    } catch {
      toast({ kind: 'err', title: '인증 실패', message: '코드가 올바르지 않습니다.' });
      setCode('');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: 'var(--bg)' }}>
      <form onSubmit={submit} style={{ width: 360, padding: 28, border: '1px solid var(--border)', borderRadius: 14, background: 'var(--surface)' }}>
        <h2 style={{ marginTop: 0, fontSize: 18 }}>2단계 인증</h2>
        <div style={{ fontSize: 13, color: 'var(--text-mute)', marginBottom: 18 }}>
          authenticator 앱에 표시된 6자리 코드를 입력하세요.
        </div>
        <input
          className="input"
          inputMode="numeric"
          autoFocus
          maxLength={6}
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
          placeholder="000000"
          style={{ width: '100%', fontFamily: 'var(--mono, monospace)', letterSpacing: 4, textAlign: 'center', fontSize: 18 }}
        />
        <button type="submit" className="btn btn--primary" disabled={submitting || code.length !== 6} style={{ width: '100%', marginTop: 16 }}>
          {submitting ? '확인 중…' : '확인'}
        </button>
        <button type="button" className="btn btn--ghost btn--sm" onClick={onLogout} style={{ width: '100%', marginTop: 8 }}>
          로그아웃
        </button>
      </form>
    </div>
  );
}
```

- [ ] **Step 2: App.tsx에 mfaRequired 분기**

Modify `admin-ui/src/App.tsx` — `App` 컴포넌트. me 가져온 뒤 `me.mfaRequired`면 MfaChallenge 렌더. import 추가 후, 인증 게이트 직전에:

```typescript
  async function reloadMe() {
    try { setMe(await api.get<Me>('/admin/api/me')); }
    catch { setMe(null); }
  }
```

그리고 렌더 분기를 수정 (`if (!me) {...}` 다음, AuthenticatedApp 렌더 전):

```typescript
  if (!me) {
    return (
      <ToastHost>
        <LoginPage onLogin={setMe} />
      </ToastHost>
    );
  }

  if (me.mfaRequired) {
    return (
      <ToastHost>
        <MfaChallenge onVerified={reloadMe} onLogout={handleLogout} />
      </ToastHost>
    );
  }

  return (
    <ToastHost>
      <Routes>
        <Route path="*" element={<AuthenticatedApp me={me} onLogout={handleLogout} />} />
      </Routes>
    </ToastHost>
  );
```

import: `import MfaChallenge from './pages/MfaChallenge';`

> `reloadMe`: verify 성공 → 세션 MFA_PENDING 해제됨 → /me 재호출 시 mfaRequired=false → 정상 진입. `handleLogout`은 이미 App에 존재(`me=null`로).

- [ ] **Step 3: 타입 체크 + 빌드**

Run: `cd admin-ui && npx tsc --noEmit && npm run build`
Expected: 컴파일 + 빌드 성공.

- [ ] **Step 4: Commit**

```bash
git add admin-ui/src/pages/MfaChallenge.tsx admin-ui/src/App.tsx
git commit -m "feat(admin-ui): 로그인 후 MFA 코드 게이트 (MfaChallenge)"
```

---

## Task 5 (프론트): AccountTab — 내 계정/보안 (MFA enroll/confirm/disable)

**근거:** spec §4.1, §5.1. 운영자 본인이 MFA를 켜고 끄는 self-service.

**Files:**
- Create: `admin-ui/src/pages/settings/AccountTab.tsx`
- Modify: `admin-ui/src/pages/SettingsPage.tsx`

- [ ] **Step 1: AccountTab 작성**

Create `admin-ui/src/pages/settings/AccountTab.tsx`:
```typescript
import { useState } from 'react';
import { mfaApi, type EnrollResponse } from '../../api/mfa';
import { QrCode } from '../../shell/QrCode';
import { useToast } from '../../shell/ToastHost';
import { Dialog } from '../../shell/Dialog';
import { getMe } from '../../api/client';
import type { Me } from '../../api/types';

export default function AccountTab({ me, onMeChange }: { me: Me; onMeChange: (m: Me) => void }) {
  const toast = useToast();
  const [enrolling, setEnrolling] = useState<EnrollResponse | null>(null);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [disabling, setDisabling] = useState(false);
  const [disableCode, setDisableCode] = useState('');

  async function startEnroll() {
    setBusy(true);
    try {
      const r = await mfaApi.enroll();
      setEnrolling(r);
      setCode('');
    } catch {
      toast({ kind: 'err', title: 'MFA 시작 실패', message: '다시 시도하세요.' });
    } finally { setBusy(false); }
  }

  async function confirmEnroll() {
    if (code.length !== 6) return;
    setBusy(true);
    try {
      await mfaApi.confirm(code);
      toast({ kind: 'ok', title: '2단계 인증이 켜졌습니다.' });
      setEnrolling(null);
      onMeChange(await getMe());
    } catch {
      toast({ kind: 'err', title: '확인 실패', message: '코드가 올바르지 않습니다.' });
      setCode('');
    } finally { setBusy(false); }
  }

  async function confirmDisable() {
    if (disableCode.length !== 6) return;
    setBusy(true);
    try {
      await mfaApi.disable(disableCode);
      toast({ kind: 'warn', title: '2단계 인증이 꺼졌습니다.' });
      setDisabling(false);
      setDisableCode('');
      onMeChange(await getMe());
    } catch {
      toast({ kind: 'err', title: '해제 실패', message: '코드가 올바르지 않습니다.' });
      setDisableCode('');
    } finally { setBusy(false); }
  }

  return (
    <div className="card" style={{ maxWidth: 560 }}>
      <div className="card__head"><h3 className="card__title">2단계 인증 (TOTP)</h3></div>
      <div className="card__body stack-2">
        <div className="row" style={{ gap: 10, alignItems: 'center' }}>
          <span>상태:</span>
          <span className={`badge ${me.mfaEnabled ? 'badge--success' : ''}`}>
            {me.mfaEnabled ? '켜짐' : '꺼짐'}
          </span>
        </div>
        <div style={{ fontSize: 13, color: 'var(--text-mute)' }}>
          authenticator 앱(Google Authenticator 등)으로 로그인 시 추가 코드를 요구합니다. {me.email}
        </div>

        {!me.mfaEnabled && !enrolling && (
          <button className="btn btn--primary btn--sm" disabled={busy} onClick={startEnroll}>
            2단계 인증 켜기
          </button>
        )}

        {me.mfaEnabled && (
          <button className="btn btn--danger btn--sm" onClick={() => setDisabling(true)}>
            2단계 인증 끄기
          </button>
        )}

        {enrolling && (
          <div className="stack-2" style={{ border: '1px solid var(--border)', borderRadius: 10, padding: 16 }}>
            <div style={{ fontSize: 13, color: 'var(--text-mute)' }}>① 앱으로 QR을 스캔하거나 secret을 수동 입력하세요.</div>
            <div style={{ display: 'grid', placeItems: 'center' }}>
              <QrCode value={enrolling.otpauthUri} />
            </div>
            <div className="mono" style={{ textAlign: 'center', fontSize: 13, color: 'var(--accent)', background: 'var(--bg)', border: '1px solid var(--border)', borderRadius: 6, padding: 8, letterSpacing: 1 }}>
              {enrolling.secret}
            </div>
            <div style={{ fontSize: 13, color: 'var(--text-mute)' }}>② 앱에 표시된 6자리 코드를 입력하세요.</div>
            <div className="row" style={{ gap: 8 }}>
              <input className="input mono" inputMode="numeric" maxLength={6} value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                placeholder="000000" style={{ flex: 1, textAlign: 'center', letterSpacing: 3 }} />
              <button className="btn btn--primary btn--sm" disabled={busy || code.length !== 6} onClick={confirmEnroll}>확인</button>
              <button className="btn btn--ghost btn--sm" disabled={busy} onClick={() => setEnrolling(null)}>취소</button>
            </div>
          </div>
        )}
      </div>

      <Dialog
        open={disabling}
        onClose={() => setDisabling(false)}
        title="2단계 인증 끄기"
        sub="현재 authenticator 앱의 6자리 코드를 입력해야 해제됩니다."
        footer={
          <>
            <button className="btn btn--sm" onClick={() => setDisabling(false)}>취소</button>
            <button className="btn btn--danger btn--sm" disabled={busy || disableCode.length !== 6} onClick={confirmDisable}>해제</button>
          </>
        }
      >
        <input className="input mono" inputMode="numeric" maxLength={6} value={disableCode}
          onChange={(e) => setDisableCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
          placeholder="000000" style={{ width: '100%', textAlign: 'center', letterSpacing: 3 }} />
      </Dialog>
    </div>
  );
}
```

> `card`/`card__head`/`card__title`/`card__body`/`stack-2`/`badge`/`mono` 클래스는 코드베이스에서 쓰는 것 — 실제 존재 확인하고 없으면 인접 탭(SecurityPolicyTab 등)이 쓰는 클래스로 맞춘다.

- [ ] **Step 2: SettingsPage에 account 탭 추가 + me 전달**

Modify `admin-ui/src/pages/SettingsPage.tsx`:
1. `SettingsTab` 타입에 `'account'` 추가, 기본 탭을 `'account'`로.
2. import: `import AccountTab from './settings/AccountTab';` + `import { useMe } from '../me/MeContext';` (또는 App에서 me prop 전달 — 아래 검증 참고).
3. 탭 버튼 맨 앞에 "내 계정" 추가, 렌더에 `{tab === 'account' && <AccountTab me={me} onMeChange={...} />}`.

> **검증(중요):** SettingsPage가 `me`를 어떻게 얻는가? App에서 SettingsPage로 me를 prop으로 내려주는지, 아니면 `useMe()`(MeContext)를 쓰는지 확인. App.tsx의 me(useState)와 MeContext의 me는 별개일 수 있다 — AccountTab의 `onMeChange`가 App의 me를 갱신해야 mfaEnabled 표시가 즉시 반영된다. 가장 단순한 방법: SettingsPage가 `useMe()`를 쓰고 있으면 `reload()`로 갱신; App의 me state를 쓰면 AuthenticatedApp이 me/onMeChange를 SettingsPage까지 내려준다. **구현 시**: AuthenticatedApp → SettingsPage 경로로 `me`와 `onMeChange`(=App의 setMe)를 prop으로 전달하는 방식을 택한다(App의 me가 single source of truth이므로). AuthenticatedApp의 라우팅에서 SettingsPage에 props를 넘기도록 수정.

- [ ] **Step 3: 타입 체크 + 빌드**

Run: `cd admin-ui && npx tsc --noEmit && npm run build`
Expected: 성공. me prop 배선 누락 시 tsc가 잡아줌 — 수정.

- [ ] **Step 4: Commit**

```bash
git add admin-ui/src/pages/settings/AccountTab.tsx admin-ui/src/pages/SettingsPage.tsx admin-ui/src/App.tsx
git commit -m "feat(admin-ui): 내 계정 탭 — MFA enroll(QR)/confirm/disable"
```

---

## Task 6 (프론트): 테넌트 suspend/activate UI

**근거:** spec §4.2, §5.3. 헤더 정지/해제 버튼 + suspend는 슬러그 타이핑 확인.

**Files:**
- Modify: `admin-ui/src/api/tenants.ts`
- Modify: `admin-ui/src/pages/TenantDetailPage.tsx`

- [ ] **Step 1: tenants api에 suspend/activate 추가**

Modify `admin-ui/src/api/tenants.ts` — `tenantsApi` 객체에:
```typescript
  suspend: (id: string): Promise<void> =>
    api.post<void>(`/admin/api/tenants/${id}/suspend`, {}),
  activate: (id: string): Promise<void> =>
    api.post<void>(`/admin/api/tenants/${id}/activate`, {}),
```
> 백엔드는 `ApiResponse.ok()` envelope 반환 → `api.post`(envelope 언랩) 사용. 경로는 `POST /admin/api/tenants/{idOrSlug}/suspend|activate` (백엔드 TenantAdminController). id로 호출.

- [ ] **Step 2: SuspendDialog + 헤더 버튼 (TenantDetailPage.tsx)**

Modify `admin-ui/src/pages/TenantDetailPage.tsx`:

1. `TenantHeader`가 콜백을 받도록 시그니처 변경:
```typescript
function TenantHeader({ tenant, onSuspend, onActivate }: {
  tenant: Tenant;
  onSuspend: () => void;
  onActivate: () => void;
}) {
```
헤더의 action `row`에 상태별 버튼 추가(기존 3버튼 뒤):
```typescript
        {tenant.status === 'ACTIVE' ? (
          <button className="btn btn--sm btn--danger" onClick={onSuspend}>테넌트 정지</button>
        ) : (
          <button className="btn btn--sm" style={{ color: 'var(--success)' }} onClick={onActivate}>정지 해제</button>
        )}
```

2. 파일 상단에 SuspendDialog 컴포넌트 추가(슬러그 타이핑 확인):
```typescript
function SuspendDialog({ tenant, open, onClose, onConfirmed }: {
  tenant: Tenant; open: boolean; onClose: () => void; onConfirmed: () => void;
}) {
  const [typed, setTyped] = useState('');
  const [busy, setBusy] = useState(false);
  const toast = useToast();
  async function go() {
    if (typed !== tenant.slug) return;
    setBusy(true);
    try {
      await tenantsApi.suspend(tenant.id);
      toast({ kind: 'warn', title: '테넌트가 정지되었습니다.', message: `${tenant.slug} · 모든 API 키 revoke됨` });
      onConfirmed();
      onClose();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '정지 실패', message: e instanceof Error ? e.message : String(e) });
    } finally { setBusy(false); setTyped(''); }
  }
  return (
    <Dialog open={open} onClose={onClose} title="테넌트 정지"
      sub="정지하면 이 테넌트의 모든 API 키가 revoke되고, 등록·인증 ceremony가 거부됩니다."
      footer={<>
        <button className="btn btn--sm" onClick={onClose}>취소</button>
        <button className="btn btn--sm btn--danger" disabled={busy || typed !== tenant.slug} onClick={go}>정지</button>
      </>}>
      <div style={{ fontSize: 13, color: 'var(--text-mute)', marginBottom: 8 }}>
        확인을 위해 테넌트 슬러그 <span className="mono" style={{ color: 'var(--text)' }}>{tenant.slug}</span> 를 입력하세요.
      </div>
      <input className="input mono" value={typed} onChange={(e) => setTyped(e.target.value)}
        placeholder={tenant.slug} style={{ width: '100%' }} autoFocus />
    </Dialog>
  );
}
```

3. `TenantDetailPage` 컴포넌트(tenant/currentTab/onTabChange/me를 받는 본체)에서 상태 + 다이얼로그 연결. tenant reload가 필요하므로 — **`TenantDetailRoute`가 tenant state를 갖고 있으므로**, reload 함수를 본체로 내려준다. `TenantDetailRoute`를 수정:
```typescript
  // TenantDetailRoute 내부, 기존 load 로직을 reload 함수로 추출
  const reload = () => {
    if (!id) return;
    tenantsApi.get(id).then(setTenant).catch(() => {});
  };
```
그리고 `TenantDetailPage`에 `onReload={reload}` prop 추가 전달. `TenantDetailPage` 본체에서:
```typescript
  const [suspendOpen, setSuspendOpen] = useState(false);
  // ...
  <TenantHeader
    tenant={tenant}
    onSuspend={() => setSuspendOpen(true)}
    onActivate={async () => {
      try { await tenantsApi.activate(tenant.id); toast({ kind: 'ok', title: '정지 해제됨' }); onReload(); }
      catch (e) { toast({ kind: 'err', title: '해제 실패', message: e instanceof Error ? e.message : String(e) }); }
    }}
  />
  <SuspendDialog tenant={tenant} open={suspendOpen} onClose={() => setSuspendOpen(false)} onConfirmed={onReload} />
```

> **검증:** `TenantDetailPage` 본체가 `toast`/`useState`를 이미 쓰는지, `onReload` prop을 추가해야 하는지 확인. activate는 가벼운 확인(window.confirm 또는 즉시 실행) — 위는 즉시 실행 + toast. spec은 activate를 "가벼운 확인"이라 했으므로, 원하면 `window.confirm('정지를 해제하시겠습니까?')` 한 줄을 onActivate 앞에 둔다(브라우저 기본 confirm으로 충분, 새 다이얼로그 불필요 — YAGNI).

- [ ] **Step 3: 타입 체크 + 빌드**

Run: `cd admin-ui && npx tsc --noEmit && npm run build`
Expected: 성공.

- [ ] **Step 4: Commit**

```bash
git add admin-ui/src/api/tenants.ts admin-ui/src/pages/TenantDetailPage.tsx
git commit -m "feat(admin-ui): 테넌트 정지/해제 버튼 + 슬러그 타이핑 확인 다이얼로그"
```

---

## Task 7: 통합 빌드 + 브라우저 dogfooding

**근거:** spec §7. 프론트 자동 테스트 없이 실제 동작을 눈으로 검증.

- [ ] **Step 1: 전체 빌드**

Run: `cd admin-ui && npm run build` 및 `./gradlew :admin-app:compileJava :admin-app:compileTestJava`
Expected: 둘 다 성공.

- [ ] **Step 2: 백엔드 슬라이스/단위 테스트 전체**

Run: `./gradlew :admin-app:test --tests "*MfaControllerSecurityTest*" --tests "*MfaPendingFilterTest*" --tests "*MeController*" --tests "*ControllerSecurityTest*"`
Expected: 전부 PASS. (Oracle Testcontainers *IT는 환경상 ORA-12541 실패 — 무시.)

- [ ] **Step 3: 브라우저 dogfooding (수동/도구)**

dev 3서버 기동(README 절차) 후, /run 또는 browse/playwright 도구로 아래 흐름을 실제 확인하고 스크린샷:
1. alice 로그인 → Settings → 내 계정 → "2단계 인증 켜기" → QR + secret 표시 → (TOTP 코드 생성) → 확인 → "켜짐" badge.
2. 로그아웃 → 재로그인 → MFA 코드 게이트 등장 → 코드 입력 → 진입.
3. 잘못된 코드 → "코드가 올바르지 않습니다" + 화면 유지.
4. 내 계정 → "2단계 인증 끄기" → 코드 입력 → "꺼짐".
5. 테넌트 상세 → "테넌트 정지" → 슬러그 타이핑 → 정지 → status=SUSPENDED + badge → "정지 해제" → ACTIVE.

> dogfooding은 실행 단계에서 도구(browse/playwright)로 수행. 코드 생성은 enroll에서 받은 secret으로 TOTP를 계산(또는 oathtool 등). 막히면 스크린샷으로 상태 보고.

- [ ] **Step 4: (해당 시) 최종 커밋 없음 — 검증 단계**

---

## 최종 검증 / 머지

- [ ] 전체 빌드 green(프론트 build + 백엔드 단위/슬라이스).
- [ ] 각 task 커밋 전 `/codex review` (usage limit이면 생략·기록).
- [ ] 최종 코드 리뷰(전체 diff) → finishing-a-development-branch로 main에 `--no-ff` merge.

## 범위 밖 (follow-up)
MFA recovery code 흐름, mfa_secret 암호화, 프론트엔드 자동 테스트 프레임워크(vitest/RTL). spec §범위 밖 참조.
