# Admin-UI: 테넌트 suspend/activate + Admin MFA enroll/verify — Design

- **작성일**: 2026-05-30
- **대상**: `admin-ui` (React SPA) + `admin-app` (백엔드 보강)
- **목적**: P0에서 만든 백엔드 기능(테넌트 suspend/activate, admin TOTP MFA)을 운영자가 admin-ui에서 실제로 사용할 수 있게 한다. 특히 **MFA는 UI가 없으면 활성화 자체가 불가**하므로 production 필수.
- **선행**: P0 SaaS launch hardening (main에 머지됨, 483526e). MFA 백엔드(TotpService/MfaController/MfaPendingFilter), 테넌트 suspend 백엔드(TenantLifecycleService + `POST /tenants/{id}/suspend|activate`)는 구현 완료.

---

## 1. 범위

세 가지 작업 묶음:

1. **테넌트 suspend/activate UI** — 백엔드 완성. UI만 추가.
2. **Admin MFA enroll + confirm UI** — 백엔드 **보강 필요**(enroll/confirm 분리), Account 탭 신설.
3. **로그인 흐름 MFA 분기** — 로그인 후 `mfaRequired`면 코드 입력 게이트.

**범위 밖 (follow-up)**: MFA recovery code 흐름, `mfa_secret` 암호화, 프론트엔드 자동 테스트 프레임워크 도입.

---

## 2. 아키텍처

- admin-ui: React 18 + Router v6 + fetch 기반 `api` client(`src/api/client.ts`, CSRF/401 처리) + `MeContext` + 손수 만든 디자인 시스템(`.btn`/`.input`/`Dialog`/`useToast`/`StatusBadge`). **기존 패턴 준수.**
- 신규 의존성: **qrcode** 1개 (MFA QR 생성). 그 외 추가 없음.
- 백엔드: `MfaController` enroll/confirm 분리 + `MeController`에 `mfaEnabled`/`mfaRequired` 노출.

---

## 3. 백엔드 변경 (admin-app)

### 3.1 MfaController (`admin-app/.../auth/MfaController.java`)

현재 `enroll`은 secret 생성 + `mfa_enabled=Y` 즉시 활성화 + `{secret}` 반환. 아래로 변경:

- **`POST /admin/api/mfa/enroll`** (동작 변경):
  - **규칙(최초 등록·재등록 동일)**: 새 secret 생성 → `mfa_secret` 저장 + **`mfa_enabled=N`으로 세팅**. confirm 성공 시에만 Y로 올린다.
  - 반환: `{ secret, otpauthUri }`. `otpauthUri = "otpauth://totp/" + enc(issuer) + ":" + enc(email) + "?secret=" + secret + "&issuer=" + enc(issuer)`, issuer = `"Passkey Admin"` (URL-encode 적용).
  - 근거: 이미 `mfa_enabled=Y`인 사용자가 재등록을 시작했다가 confirm 없이 이탈하면, secret은 새 것으로 바뀌었는데 enabled가 Y로 남아 다음 로그인에서 옛 앱 코드로는 통과 못 하는 불일치가 생긴다. enroll에서 enabled를 N으로 내리면 이 창구가 닫힌다(미확인 상태 = 게이트 비활성). self-recovery는 재 enroll로 가능.
- **`POST /admin/api/mfa/confirm`** {code} (신규):
  - 저장된 `mfa_secret`로 `totp.verifyAt(secret, code, clock.millis())` 검증.
  - 성공 → `mfa_enabled=Y`, `{confirmed:true}` 200.
  - 실패 → `401 {"error":"invalid_code"}` (verify와 동일 형식).
  - secret 미존재(enroll 안 함) → 동일 401.
- **`POST /admin/api/mfa/verify`** {code} (변경 없음): 로그인 후 세션 `MFA_PENDING` 해제.
- **`POST /admin/api/mfa/disable`** {code} (신규):
  - 저장된 secret으로 코드 검증 성공 시에만 `mfa_enabled=N` + `mfa_secret=null`. 실패 401.
  - "코드 없이 MFA 못 끄게" — 본인 인증 강화.

`MfaPendingFilter`의 allowlist(`/admin/api/mfa/**`)가 confirm/disable도 자동 커버. CSRF 유지(인증 세션이라 XSRF-TOKEN 보유).

### 3.2 MeController (`admin-app/.../config/MeController.java`)

`MeView`에 두 필드 추가:
- **`mfaEnabled`** (boolean): 현재 운영자 계정의 `mfa_enabled` 값. Account 탭 표시용. → `AdminUserRepository.findByEmail`로 조회(현재 principal엔 없음).
- **`mfaRequired`** (boolean): 이 세션이 MFA 검증 대기 중인지. → `HttpServletRequest` 세션의 `MfaPendingFilter.MFA_PENDING_ATTR` 존재 여부.

`me()` 시그니처에 `HttpServletRequest` 추가, `AdminUserRepository` 주입. `MfaPendingFilter`가 `/admin/api/me`를 막지 않도록 확인 필요 — **me는 mfaRequired 상태를 알려주는 부트스트랩이므로 MFA_PENDING이어도 호출 가능해야 한다.** 현재 `MfaPendingFilter`는 `/admin/api/mfa/**`만 allowlist하므로 `/admin/api/me`가 MFA_PENDING 상태에서 403될 수 있다 → **`MfaPendingFilter` allowlist에 `/admin/api/me` 추가**(읽기 전용, 자기 상태 조회는 허용해야 SPA가 분기 가능).

> 세부 검증 포인트: `MeView`는 `ApiResponse.ok(...)` envelope로 반환됨. 프론트 `api.get<Me>`가 envelope 언랩하므로 일관.

---

## 4. 프론트엔드 변경 (admin-ui)

### 4.1 신규 파일

- **`src/api/mfa.ts`** — `enroll(): Promise<{secret, otpauthUri}>`, `confirm(code)`, `verify(code)`, `disable(code)`. 기존 `api.post`/envelope 또는 raw 패턴 중 MfaController 응답 형태(raw `Map`)에 맞춰 `api.postRaw` 사용. 에러는 `ApiError`로 surface.
- **`src/shell/QrCode.tsx`** — `qrcode` 라이브러리 래핑. props `{ value: string, size?: number }` → canvas/SVG 렌더. otpauthUri를 받아 QR 그림.
- **`src/pages/settings/AccountTab.tsx`** — 내 계정/보안 탭:
  - `me.mfaEnabled` 표시(켜짐/꺼짐 badge).
  - 꺼짐: "2단계 인증 켜기" 버튼 → enroll 호출 → **QR(A안) + 수동 secret + "앱의 6자리 코드 입력 → 확인"** → confirm 성공 시 toast + me reload.
  - 켜짐: "2단계 인증 끄기" 버튼 → 코드 입력 다이얼로그 → disable.
- **`src/pages/MfaChallenge.tsx`** — 로그인 후 `mfaRequired` 시 전체 화면 코드 입력. 6자리 입력 → verify. 실패 시 화면 유지 + 에러. "로그아웃" 링크 제공.

### 4.2 수정 파일

- **`src/api/types.ts`** — `Me`에 `mfaEnabled: boolean`, `mfaRequired: boolean` 추가.
- **`src/api/tenants.ts`** — `suspend(id): Promise<void>`, `activate(id): Promise<void>` (`POST /admin/api/tenants/{id}/suspend|activate`, ApiResponse envelope).
- **`src/pages/TenantDetailPage.tsx`** — `TenantHeader` action 행에:
  - status=ACTIVE → 빨간 "테넌트 정지" 버튼 → **SuspendDialog**(영향 명시 + 슬러그 정확히 타이핑해야 "정지" 활성화) → suspend → toast + 테넌트 reload.
  - status=SUSPENDED → 초록 "정지 해제" 버튼 → 가벼운 확인 다이얼로그 → activate → toast + reload.
- **`src/App.tsx`** — 부트스트랩/로그인 후 `me.mfaRequired===true`면 `<MfaChallenge onVerified={reloadMe}/>` 렌더(authenticated app 진입 차단). verify 성공 → me reload → mfaRequired=false → 정상 진입.
- **`src/pages/LoginPage.tsx`** — 로그인 성공 후 `me` 가져온 뒤 App이 mfaRequired 분기 처리하므로, LoginPage는 `onLogin(me)`만 호출(현행 유지). 분기는 App에서.
- **`src/pages/SettingsPage.tsx`** — `account`(내 계정) 탭 추가, AccountTab 연결.
- **`admin-ui/package.json`** — `qrcode` + `@types/qrcode`(devDep).

### 4.3 파일 경계 원칙
MFA UI는 `MfaChallenge`(로그인 게이트 — verify만)와 `AccountTab`(라이프사이클 — enroll/confirm/disable)으로 분리. QR 렌더는 `QrCode`로 격리. 각 파일 단일 책임.

---

## 5. 데이터 흐름

### 5.1 MFA 등록 (Account 탭)
```
"2단계 인증 켜기" → POST /mfa/enroll → {secret, otpauthUri} (mfa_enabled=N)
  → QrCode(otpauthUri) 렌더 + 수동 secret 표시
  → 운영자 앱 등록 → 6자리 입력 → POST /mfa/confirm {code}
  → 성공: mfa_enabled=Y → toast("2단계 인증이 켜졌습니다") → me reload
  → 실패: 401 → "코드가 올바르지 않습니다" + 재입력
```

### 5.2 로그인 MFA 게이트
```
loginForm → 200 → App: GET /me → me.mfaRequired==true
  → <MfaChallenge> 코드 입력 → POST /mfa/verify {code}
  → 성공: 세션 MFA_PENDING 해제 → me reload(mfaRequired=false) → 정상 진입
  → 실패: 화면 유지 + 에러. "로그아웃" → 세션 버리고 로그인부터.
```

### 5.3 테넌트 suspend
```
"테넌트 정지" → SuspendDialog(영향 설명 + 슬러그 타이핑) → 슬러그 일치 시 활성
  → POST /tenants/{id}/suspend → toast + 테넌트 reload(status=SUSPENDED)
"정지 해제" → 가벼운 확인 → POST /tenants/{id}/activate → toast + reload
```

---

## 6. 에러 처리
- MFA 코드 오류 → 백엔드 401 `invalid_code` → toast + 재입력(화면 유지).
- enroll 후 confirm 전 이탈 → `mfa_enabled=N` 유지, 잠김 없음. 재 enroll로 새 secret.
- MfaChallenge 반복 실패 → 재시도 + "로그아웃" 탈출구.
- suspend 슬러그 불일치 → "정지" 버튼 비활성. 백엔드 403/404 → toast.
- 모든 mutating 호출 → 기존 client가 XSRF-TOKEN 자동 첨부.

---

## 7. 테스트 전략
- **백엔드 (필수, 자동)**: MfaController enroll(비활성화 확인)/confirm(코드 성공→Y, 실패→401)/disable, MeController mfaEnabled/mfaRequired 노출, MfaPendingFilter가 `/admin/api/me` 허용. 기존 `@WebMvcTest` 슬라이스 + 단위 테스트 패턴(P0와 동일). TotpService는 검증 완료.
- **프론트엔드 (수동)**: 신규 테스트 프레임워크 미도입(범위 밖). 대신:
  - TypeScript strict + `vite build` 컴파일 게이트.
  - **브라우저 dogfooding**(실행 단계): MFA enroll→confirm→재로그인→코드입력→진입, 테넌트 suspend(슬러그 타이핑)→해제 흐름을 실제 화면에서 확인. 시각적 기능이라 "실제 동작" 눈 확인이 핵심.
- 프론트 단위 테스트 인프라 도입은 별도 follow-up.

---

## 8. 미해결/검증 포인트 (구현 시 확인)
- `MeController.me`가 MFA_PENDING 세션에서 호출 가능해야 함 → `MfaPendingFilter` allowlist에 `/admin/api/me` 추가(§3.2에서 확정).
- enroll의 `mfa_enabled=N` 규칙(§3.1)이 기존 P0 테스트(`MfaControllerSecurityTest`의 enroll→`setMfaEnabled(true)` 검증)와 충돌 → 그 테스트를 새 동작(enroll→`mfa_enabled=N`+secret 저장, confirm→Y)으로 갱신해야 함.
- `qrcode` 라이브러리의 React 렌더 방식(canvas ref vs toDataURL) — `QrCode.tsx`에서 결정.
- SettingsPage 탭 순서·기본 탭에 account를 어디 둘지(맨 앞 권장).
