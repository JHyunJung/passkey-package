# admin-ui 후속 화면 (그룹 A·B 백엔드의 운영자 UI) — Design

- **작성일**: 2026-05-31
- **대상**: Crosscert Passkey Platform — admin-ui (React 18 / TypeScript / react-router-dom 6 / Vite, `basename="/admin"`)
- **근거**: 그룹 A(`2026-05-31-admin-account-security-followups.md` §8) + 그룹 B(`2026-05-31-config-enforcement-group-b-followups.md` §6)의 "admin-ui 후속(별도 phase)". RP_ADMIN 게이팅(`fd9000d`) 이후의 admin-ui 잔여.
- **성격**: 구현 설계. 후속 writing-plans의 입력물.

## 1. 목적 / 범위

그룹 A·B에서 추가한 백엔드 API의 운영자 UI를 admin-ui에 붙인다. **백엔드 변경 없음** — 전부 기존 엔드포인트 소비. 보안 우선 순서로 3개 Phase.

| Phase | 항목 | 백엔드(기존) | 현재 UI 상태 |
|---|---|---|---|
| **1** | A3 password reset 화면 | `POST /admin/api/password-reset/{request,confirm}` (permitAll) | public route 0개 |
| **2** | A1 MFA 복구 코드 표시 + A2 복구 코드 로그인 | `mfa/confirm` 응답 `recoveryCodes[10]`, `mfa/verify` 복구 코드 허용 | confirm 응답 버림, TOTP만 |
| **3** | B2 scope 선택 + B1 rotate | `POST /api-keys`(scope 필수), `POST /api-keys/{id}/rotate` | `['ceremony']` 하드코딩(버그), rotate 미연결 |

**범위 밖(deferred)**:
- 그룹 C alert 메일 설정 UI — followups가 "운영 인프라(코드 밖)"로 분류(Prometheus/Grafana/Alertmanager + `passkey.alert.mail.*` 운영 설정). 코드 화면 아님.
- MFA 복구 코드 **재발급**(이미 MFA 켠 상태에서 재생성) — 백엔드에 전용 엔드포인트 없음(confirm은 enroll 직후 흐름 가정). YAGNI, 별도 후속.
- P2 전체.

**원칙**: 기존 공통 컴포넌트(Dialog/CopyBtn/ToastHost/IssuedKeyModal 패턴) 재사용, 기존 디자인 토큰/클래스(`btn`/`card`/`input`/`badge`) 사용, 백엔드 검증을 신뢰(약한 비번/만료 토큰/scope whitelist는 400 메시지 표시).

## 2. 발견된 버그 (Phase 3에서 수정)

`admin-ui/src/api/apiKeys.ts:35`가 발급 시 `scopes: ['ceremony']`를 하드코딩한다. 그러나 백엔드 허용 집합은 `ApiKeyAdminService.ALLOWED_SCOPES = {registration, authentication, admin}`(V21 CHECK 제약 `ck_aks_scope`와 동일). `'ceremony'`는 whitelist에 없으므로 `normalizeScope`가 `BusinessException(INVALID_INPUT)` → **현재 API key 발급이 400으로 실패**한다. Phase 3가 이 버그를 함께 수정한다(scope를 실제 whitelist 값으로 교체 + 운영자가 선택).

## 3. Phase 1 — Password reset

### 3.1 라우팅 (결정: App.tsx 최상단 public 분기)
현재 `App.tsx`는 `!me` → `LoginPage`, `me.mfaRequired` → `MfaChallenge` 를 `<Routes>` 밖에서 분기한다. password reset은 **미인증 + URL 토큰**으로 진입하므로 me fetch보다 먼저 경로를 본다.

`App.tsx`(또는 분리한 `PublicRoutes` 헬퍼)에서 `useLocation().pathname` 이:
- `/forgot-password` → `<ForgotPasswordPage />` (me 무관)
- `/reset-password` → `<ResetPasswordPage />` (me 무관, `?token=` 읽음)

위 두 경로가 아니면 기존 me 분기로 진행. (basename `/admin` 이므로 실제 URL은 `/admin/forgot-password`, `/admin/reset-password?token=…`.)

이 분기는 me 로딩 상태와 독립 — public 경로는 me fetch를 기다리지 않고 즉시 렌더(로그인 안 한 사용자가 메일 링크로 진입).

### 3.2 ForgotPasswordPage (신규 `src/pages/ForgotPasswordPage.tsx`)
- 이메일 input + "재설정 링크 보내기" 버튼.
- submit → `passwordResetApi.request(email)` → `POST /admin/api/password-reset/request {email}`.
- 백엔드가 **항상 200**(`{ok:true}`, enumeration 방지) → UI도 항상 동일 성공 메시지: "해당 이메일이 등록돼 있다면 재설정 링크를 보냈습니다. 메일함을 확인하세요." (계정 존재 노출 금지).
- "← 로그인으로" 링크(`/login` 또는 `/admin/login`).
- 빈 이메일이면 클라이언트에서 막음(버튼 disabled).

### 3.3 ResetPasswordPage (신규 `src/pages/ResetPasswordPage.tsx`)
- `useSearchParams().get('token')` 로 토큰 추출. 토큰 없으면 "유효하지 않은 링크" 안내 + 로그인 링크.
- 새 비밀번호 + 확인 input 2개. 클라이언트 검증: 두 입력 일치 + 비어있지 않음(버튼 disabled). **복잡도/길이 정책은 백엔드 검증에 위임** — 400 응답의 message를 토스트/인라인으로 표시.
- submit → `passwordResetApi.confirm(token, newPassword)` → `POST /admin/api/password-reset/confirm {token, newPassword}`.
- 성공(200 `{reset:true}`) → "변경되었습니다. 새 비밀번호로 로그인하세요." + 로그인 링크.
- 실패(400, 만료/소비/약한 비번) → `ApiError.message` 표시("토큰이 만료되었거나 이미 사용되었습니다" 등 백엔드 메시지).

### 3.4 LoginPage 링크
`LoginPage.tsx` 에 "비밀번호를 잊으셨나요?" 링크(`/forgot-password`) 추가.

### 3.5 api 모듈 (신규 `src/api/passwordReset.ts`)
`PasswordResetController`는 `ApiResponse` envelope이 **아닌** raw `Map.of(...)`(`{ok}`/`{reset}`)를 반환한다 → `rawRequest`(`api.postRaw`) 사용(envelope unwrap 안 함). 미인증 경로라 CSRF 쿠키는 있으면 포함(기존 rawRequest 동작).
```ts
export const passwordResetApi = {
  request: (email: string) => api.postRaw<{ ok: boolean }>('/admin/api/password-reset/request', { email }),
  confirm: (token: string, newPassword: string) =>
    api.postRaw<{ reset: boolean }>('/admin/api/password-reset/confirm', { token, newPassword }),
};
```
주의: `rawRequest`는 401 시 `/admin/login` 으로 redirect한다. password-reset은 permitAll이라 401이 안 나야 정상이나, 만약 난다면 redirect는 무해(로그인으로 보냄). confirm 400은 `rawRequest`가 `ApiError`로 throw(본문 `error`/`message` 보존).

## 4. Phase 2 — MFA 복구 코드 (A1 + A2)

### 4.1 mfa.ts 타입 확장
`mfaApi.confirm`/`verify` 응답 타입 확장(백엔드 `MfaController` 실제 응답 반영):
```ts
confirm: (code) => mfaPost<{ confirmed: boolean; recoveryCodes: string[] }>('/admin/api/mfa/confirm', { code }),
verify:  (code) => mfaPost<{ verified: boolean; usedRecoveryCode?: boolean; remaining?: number }>('/admin/api/mfa/verify', { code }),
```
(`mfaPost`는 그대로 — MFA 전용 401 비-redirect 동작 유지.)

### 4.2 RecoveryCodesModal (신규 `src/pages/settings/RecoveryCodesModal.tsx`)
- props: `codes: string[]`, `onClose: () => void`.
- 기존 `Dialog`(closeOnScrim=false) 사용. 10개 코드를 2열 그리드(`mono`)로 표시.
- 경고 배너: "복구 코드를 안전한 곳에 보관하세요. 인증 기기를 잃었을 때 로그인하는 유일한 방법이며, 지금만 표시됩니다."
- 액션: **복사**(전체 코드 줄바꿈 join, CopyBtn 또는 navigator.clipboard) + **다운로드**(`.txt` Blob, 파일명 `recovery-codes.txt`) + **인쇄**(`window.print()` 또는 간단 인쇄용 텍스트). 복사 필수, 다운로드/인쇄는 간단 구현.
- "안전한 곳에 저장했습니다" 체크박스 → 체크해야 "닫기" 활성(IssuedKeyModal 패턴).

### 4.3 AccountTab confirm 흐름
`AccountTab.tsx:46-65 confirmEnroll()`:
- `const res = await mfaApi.confirm(enrollCode);` 으로 응답 캡처.
- 기존 성공 처리(enroll 초기화, getMe, 토스트) 후 `res.recoveryCodes`를 state에 담아 `RecoveryCodesModal` 표시.
- 모달 닫기 → state 초기화.
- `recoveryCodes`가 비어있으면(방어) 모달 생략, 토스트만.

### 4.4 MfaChallenge 복구 코드 입력
`MfaChallenge.tsx`:
- 기본은 TOTP 6자리 입력(기존).
- "기기를 잃으셨나요? 복구 코드로 로그인" 토글 링크 → 복구 코드 입력 모드(형식 `xxxx-xxxx`, 6자리 숫자 제약 해제).
- 같은 `mfaApi.verify(code)` 호출(백엔드가 TOTP/복구 코드 자동 판별). 성공 후 `usedRecoveryCode && remaining`이면 "복구 코드를 사용했습니다. 남은 코드: N개" 토스트.
- 복구 코드 모드에서 입력 sanitize는 TOTP와 분리(숫자-only 강제 안 함).

## 5. Phase 3 — API key scope + rotate (B2 + B1)

### 5.1 scope 선택 (결정 B: registration/authentication만, admin 숨김)
`admin` scope는 `ApiKeyScopeResolver`에 매핑된 RP 경로가 없어(현재 dead scope) 노출 시 운영자 혼란만 유발 → **registration / authentication 2개만** UI 노출.

- `apiKeys.ts` `create` 시그니처에 `scopes: string[]` 추가, 하드코딩 `['ceremony']` 제거.
- `ApiKeyView`/디자인 `ApiKey`에 scopes 노출(이미 `ApiKeyView.scopes` 존재 — 목록 행에 표시).
- 발급 다이얼로그(`NewKeyDialog`): 이름 input + scope 체크박스 2개(둘 다 기본 체크). 둘 다 해제 시 발급 버튼 비활성(백엔드 `@NotEmpty` 위반 방지).
- scope 라벨/설명:
  - `registration` — "패스키 등록 + self-service credential 관리"
  - `authentication` — "패스키 인증(로그인)"

### 5.2 rotate (결정 A: 확인 다이얼로그 + 공통 평문 키 모달 + 만료 배너)
- `apiKeys.ts`에 `rotate(id)` 추가:
  ```ts
  rotate: async (id) => {
    const res = await api.post<ApiKeyRotateResponse>(`/admin/api/api-keys/${id}/rotate`, {});
    return res; // { id, plaintextKey, prefix, scopes, oldKeyExpiresAt }
  }
  ```
  (`api.post`가 `ApiResponse` envelope unwrap — 발급 `issue`도 envelope이므로 동일. 발급도 `api.post`로 envelope unwrap됨을 재확인: 현재 `create`가 `api.post<ApiKeyCreateResponse>`라 일관.)
- `ApiKeysTab.tsx`: 각 키 행에 "↻ 회전" 버튼(revoke 옆).
- 클릭 → 확인 다이얼로그: "새 키가 즉시 발급됩니다(같은 권한). 구 키는 24시간 후 만료됩니다. 새 키는 한 번만 표시됩니다." → "회전 실행".
- 실행 → 신규 평문 키 모달(**IssuedKeyModal 공통화/확장**): 평문 키 1회 표시 + CopyBtn + "안전한 곳에 복사했습니다" 체크 + **구 키 만료 시각 배너**(`oldKeyExpiresAt`를 `formatDateTime`으로). 발급(issue)은 만료 배너 없이 같은 모달 재사용.
- 모달 닫기 → 목록 reload(만료 예정 구 키 + 신규 키 반영).

### 5.3 IssuedKeyModal 공통화
현재 `ApiKeysTab` 내부 `IssuedKeyModal`은 발급 전용. props에 `oldKeyExpiresAt?: string` 추가 → 있으면 만료 배너 렌더. 발급/회전 공유.

## 6. 테스트 전략 (vitest 단위 위주)

admin-ui는 vitest 도입됨(RP 게이팅 phase). 보안/검증 로직 위주로 단위 테스트:
- `passwordReset.ts`: request/confirm이 올바른 경로/본문으로 `postRaw` 호출(fetch mock). confirm 400 → ApiError 표면화.
- `RecoveryCodesModal`: 10개 코드 렌더, "저장했습니다" 체크 전 닫기 비활성/후 활성.
- scope 선택: 0개 선택 시 발급 버튼 disabled, 기본 2개 체크.
- `apiKeys.rotate`: 올바른 경로 호출 + 응답 반환(fetch mock).
- `MfaChallenge` 복구 코드 토글: 모드 전환 시 입력 sanitize 분리(숫자-only 해제).

**생략(YAGNI)**: 풀 라우팅 통합(App public 분기 E2E), 페이지 전체 렌더 통합, 실제 메일/브라우저 dogfooding(로컬 서버+seed 의존 — followups). **타입 게이트**: `tsc -b` 빌드 + `vitest run` green.

## 7. 파일 영향 요약

**신규**:
- `src/api/passwordReset.ts`
- `src/pages/ForgotPasswordPage.tsx`, `src/pages/ResetPasswordPage.tsx`
- `src/pages/settings/RecoveryCodesModal.tsx`
- 테스트: `passwordReset.test.ts`, `RecoveryCodesModal.test.tsx`, (scope/rotate는 ApiKeysTab 테스트 또는 apiKeys.test.ts)

**수정**:
- `src/App.tsx` (public 경로 최상단 분기)
- `src/pages/LoginPage.tsx` ("비밀번호를 잊으셨나요?" 링크)
- `src/api/mfa.ts` (confirm/verify 응답 타입 확장)
- `src/pages/settings/AccountTab.tsx` (confirm 후 RecoveryCodesModal)
- `src/pages/MfaChallenge.tsx` (복구 코드 토글)
- `src/api/apiKeys.ts` (scope 인자, `['ceremony']` 제거, rotate 추가)
- `src/pages/tenant/ApiKeysTab.tsx` (scope 체크박스, rotate 버튼+확인, IssuedKeyModal 만료 배너)

## 8. 커밋 전 게이트

메모리 지침: 커밋 전 `/codex review`(6/1 quota 리셋 후 누적 diff) + code quality subagent. 특히 password-reset enumeration 동일 메시지, 복구 코드 1회 표시 게이팅, scope whitelist 일치(`['ceremony']` 제거), rotate 비가역 확인 단계.

## 9. 구현 순서(Phase별, 보안 우선)

1. **Phase 1**: passwordReset.ts → ForgotPasswordPage → ResetPasswordPage → App public 분기 → LoginPage 링크 → 테스트.
2. **Phase 2**: mfa.ts 타입 → RecoveryCodesModal → AccountTab confirm → MfaChallenge 토글 → 테스트.
3. **Phase 3**: apiKeys.ts(scope+rotate, 버그 수정) → ApiKeysTab(scope 체크박스 + rotate + 만료 배너) → 테스트.
4. 빌드 검증(`tsc -b`, `vitest run`) + followups.

각 Phase는 독립적으로 빌드·테스트·머지 가능(별도 worktree 또는 순차 커밋). 권장: 단일 worktree에서 Phase 순서대로 subagent-driven 실행.
