# Admin-UI: 테넌트 suspend/activate + MFA — Follow-ups

- **작성일**: 2026-05-30
- **브랜치**: `worktree-admin-ui-mfa-suspend`
- **spec**: [2026-05-30-admin-ui-tenant-suspend-mfa-design.md](../specs/2026-05-30-admin-ui-tenant-suspend-mfa-design.md)
- **plan**: [2026-05-30-admin-ui-tenant-suspend-mfa.md](../plans/2026-05-30-admin-ui-tenant-suspend-mfa.md)

두 기능(테넌트 suspend/activate UI, admin MFA enroll/confirm/login-gate/disable)을 구현·리뷰·브라우저 dogfooding으로 검증 완료. 아래는 범위 밖으로 미룬 항목과 dogfooding에서 발견한 **별개 버그**다.

---

## 1. dogfooding에서 발견한 별개 버그 (이 phase와 무관, 우선 처리 권장)

### 1.1 RP_ADMIN 대시보드 하드 크래시 (높음)
- **증상**: RP_ADMIN 역할(예: seed `bob@crosscert.com`)로 로그인하면 tenants 랜딩 페이지가 **화이트 스크린**으로 죽는다.
- **원인**: tenants 페이지가 `/admin/api/audit/chain/overview`를 호출하는데 RP_ADMIN에겐 **403**이 반환된다. React가 그 실패 응답을 방어 없이 사용해 `Cannot read properties of undefined (reading 'tenantsTampered')`로 크래시.
- **영향**: RP_ADMIN이 admin 콘솔을 **전혀 못 쓴다**. SaaS에서 테넌트 관리자(RP_ADMIN)가 콘솔을 못 쓰는 건 심각.
- **권고**: (a) audit chain overview는 PLATFORM_OPERATOR 전용 위젯이면 RP_ADMIN에게 호출 자체를 안 하도록 분기, (b) overview fetch 실패를 방어적으로 처리(undefined 가드 + 부분 렌더). 이 phase 범위 밖이라 별도 처리.

### 1.2 `/admin/favicon.ico` 500 (낮음, cosmetic)
- favicon 요청이 500 반환 — 콘솔 노이즈만. 정적 favicon 매핑 추가 권장.

---

## 2. 이 phase에서 의도적으로 미룬 항목 (spec §범위 밖)

### 2.1 MFA recovery code 흐름
- V36의 `admin_user_recovery_code` 테이블은 여전히 schema-only(미사용). authenticator 분실 시 self-recovery 불가 — 현재는 운영자가 DB에서 `mfa_enabled='N'` 직접 변경하거나 다른 admin이 처리. recovery code 발급/소비 UI+백엔드는 후속.

### 2.2 `mfa_secret` 평문 저장
- TOTP secret이 `admin_user.mfa_secret`에 평문 Base32로 저장(P0/이번 phase 모두 동일). `KeyEnvelope`(AES-256-GCM)로 at-rest 암호화 + 컬럼 확장(sealed는 64자 초과)이 후속. [[saas-launch-hardening-followups]] §2.2와 동일 항목.

### 2.3 프론트엔드 자동 테스트
- admin-ui에 테스트 프레임워크(vitest/RTL) 없음. 이 phase는 tsc/build 게이트 + 브라우저 dogfooding으로 검증. MfaChallenge/AccountTab/SuspendDialog 같은 상호작용 컴포넌트는 단위 테스트 가치가 높음 — 프레임워크 도입은 별도 phase.

### 2.4 사소한 UX
- AccountTab의 수동 secret에 복사 버튼 없음(`userSelect:all`로 선택만). MfaChallenge/disable이 401과 네트워크 오류를 동일 toast로 표시(catch-all). 테넌트 activate가 double-click 가드 없음(window.confirm이 modal이라 실害 낮음). 모두 cosmetic.

### 2.5 MFA enroll confirm 단계 — 구현됨 (참고)
- spec 결정대로 enroll/confirm를 **분리 구현**했다(enroll=secret만+mfa_enabled=N, confirm 성공 시 활성). [[saas-launch-hardening-followups]] §2.3에서 "후속"으로 적었던 항목을 이번 phase에 당겨와 닫음.

---

## 3. codex review 미실행 (전 task 공통)
- OpenAI usage limit("resets ~Jun 1")로 전 task에서 codex 독립 리뷰 미실행. spec + code quality subagent 2단계 리뷰 + 백엔드 단위/슬라이스 테스트 + 브라우저 dogfooding으로 게이트. 6/1 리셋 후 누적 diff에 `/codex review` 권장(특히 MfaController enroll/confirm, mfaPost 401 처리).

---

## 4. 검증 메모 (코드 이슈 아님)
- dogfooding 중 "alice mfa_enabled UPDATE가 반영 안 됨"은 캐싱 버그가 아니라 **schema-qualified 누락**(APP_ADMIN_USER 세션에서 unqualified `admin_user` → ORA-00942). `APP_OWNER.admin_user`로 qualified UPDATE 후 즉시 반영됨. `MeController`는 매 요청 fresh read — 정상.
- admin-app IT(Oracle Testcontainers)는 이 환경에서 ORA-12541로 실패(환경적). 단위/슬라이스 테스트는 전부 green.
