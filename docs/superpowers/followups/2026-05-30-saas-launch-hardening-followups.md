# SaaS Launch Hardening (P0) — Follow-ups

- **작성일**: 2026-05-30
- **브랜치**: `worktree-saas-launch-hardening`
- **근거 spec**: [2026-05-29-saas-readiness-gap-audit-design.md](../specs/2026-05-29-saas-readiness-gap-audit-design.md)
- **plan**: [2026-05-29-saas-launch-hardening.md](../plans/2026-05-29-saas-launch-hardening.md)

P0 6건을 모두 닫았다. 아래는 P0 실행·리뷰 과정에서 **의도적으로 범위 밖으로 미룬 항목**과 후속 권고다. (deferred-by-design — 누락이 아님)

---

## 1. P1으로 이월된 기능 gap (spec §4)

| ID | 항목 | 비고 |
|---|---|---|
| ~~P1-1~~ | ~~per-tenant WebAuthn 설정값 ceremony 미반영~~ | ✅ **해결 (2026-05-31, 그룹 B)** — reg/auth start가 테넌트 timeout/UV/attestation 반영, conveyance 대문자→소문자 매핑 |
| P1-2 | Metrics 파이프라인 (Prometheus/Micrometer) | actuator만 의존 |
| P1-3 | Alerting hook (email/Slack/PagerDuty) | |
| P1-4 | Audit / 데이터 retention 정책 | append-only 무한 증가 |
| ~~P1-5~~ | ~~API key rotation & scope 강제~~ | ✅ **해결 (2026-05-31, 그룹 B)** — 경로→scope 매핑 필터 enforcement(403), rotation(신규 발급+구 키 grace 만료) |
| ~~P1-6~~ | ~~Admin password reset / account lockout~~ | ✅ **해결 (2026-05-31, 그룹 A)** — temporal lockout(5회/15분 자동해제) + self-service password reset(invitation 패턴, enumeration 방지, lockout 리셋) |
| P1-7 | Health check 보강 (DB/Redis) | LicenseHealthIndicator만 존재 |

P2(billing/quota, self-service 온보딩, GDPR export, async/JS SDK, OpenAPI, WebAuthn 확장)는 spec §5 참조.

> **그룹 A 완료 (2026-05-31)**: P0 잔여 §2.1(MFA recovery-code)·§2.2(mfa_secret 암호화) + P1-6(lockout/reset)을 단일 worktree·V37 마이그레이션으로 마감. spec: `docs/superpowers/specs/2026-05-30-admin-account-security-hardening-design.md`.
>
> **그룹 B 완료 (2026-05-31)**: P1-1(per-tenant ceremony 반영) + P1-5(API key scope enforcement + rotation)을 단일 worktree로 마감(신규 마이그레이션 없음). spec: `2026-05-31-config-enforcement-group-b-design.md`, followups: `2026-05-31-config-enforcement-group-b-followups.md`.
>
> **남은 P1**: P1-2(Prometheus metrics), P1-3(alerting), P1-4(retention/purge), P1-7(DB/Redis health) — 그룹 C(P1-2/3/7 관측성) + 그룹 D(P1-4) 후보.

---

## 2. P0 구현 중 미룬 항목 (실행 과정에서 합의)

### 2.1 Admin MFA recovery-code flow (P0-5 일부) — ✅ 해결 (2026-05-31, 그룹 A)
- ~~**현황**: V36 마이그레이션이 `ADMIN_USER_RECOVERY_CODE` 테이블을 생성했으나 **엔티티·repository·발급/소비 로직 없음** (schema-only, 미사용 ship).~~
- **해결**: `AdminUserRecoveryCode` 엔티티 + `RecoveryCodeService`(`generate`/`consume`/`remaining`). MFA confirm 성공 시 10개 1회용 코드 발급(평문 1회 반환, sha-256 hash만 저장), verify에서 TOTP 실패 시 recovery code 대체 소비(조건부 `markUsed` bulk update로 one-shot·double-spend 차단). 브랜치 `worktree-admin-account-security`, plan `docs/superpowers/plans/2026-05-30-admin-account-security-hardening.md`.

### 2.2 `mfa_secret` 평문 저장 (P0-5) — ✅ 해결 (2026-05-31, 그룹 A)
- ~~**현황**: TOTP secret이 `admin_user.mfa_secret`에 평문 Base32로 저장 (V32 스키마 그대로).~~
- **해결**: `MfaSecretCipher`가 `KeyEnvelope`(AES-256-GCM)로 at-rest 봉투 암호화(`enc:v1:` 프리픽스 + base64). V37이 `mfa_secret`을 `VARCHAR2(255)`로 확장. `open()`이 프리픽스 없는 기존 평문을 passthrough해 무중단 마이그레이션(다음 enroll 시 자동 전환). enroll 저장 시 seal, validCode 검증 시 open.

### 2.3 MFA enroll confirm-code 단계 (P0-5)
- **현황**: `/admin/api/mfa/enroll`이 secret을 생성·활성화하지만 "코드 1회 확인 후 활성화" 단계 없음.
- **영향**: secret을 잘못 복사하면 다음 로그인에서 잠김 (mfa_enabled=Y인데 secret 불일치). 단, enroll이 현 세션을 잠그지 않고 재-enroll 가능해 self-recovery 됨.
- **권고**: enroll → secret 반환 → 코드 입력 검증 성공 시에만 mfa_enabled=Y. **admin-ui phase에서 UX와 함께 다루는 것이 자연스러움.**

### 2.4 `AuthenticationStartService.findAll()` → `findByUserHandle` 통합 (P0-4 인접 cleanup)
- **현황**: `AuthenticationStartService`(line 87 근처)가 여전히 `findAll()` + in-memory `Arrays.equals` 필터 사용. P0-3/P0-4가 같은 repository에 derived `findByUserHandle`를 추가했으므로 drop-in 교체 가능. (코드 주석에 "Phase 2 will add findByUserHandle"이 이미 있음)
- **영향**: 동작 정상, 성능/명확성만. tenant당 credential 수가 적어 실害 낮음.
- **권고**: `findByUserHandle`로 교체하는 Minor cleanup.

### 2.5 ApiKeyRepository "active" 정의 분기 (P0-2 인접)
- **현황**: `countActiveByTenantId`(KPI)는 만료 키 제외(`expiresAt > now`), `findActiveByTenantId`(suspend revoke 대상)는 `revokedAt is null`만 필터 → 만료 키도 revoke. 무해(만료 키 revoke는 no-op)하나 정의가 다름.
- **권고**: 명확화 주석 또는 의미 통일.

---

## 3. codex review 미실행 (전 task 공통)
- **현황**: 메모리 지침(커밋 전 `/codex review`)을 따르려 했으나, 전 task에서 OpenAI usage limit("resets ~Jun 1")로 **codex 독립 리뷰 미실행**. 대신 spec + code quality subagent 2단계 리뷰 + (해당 시) Testcontainers live 검증으로 게이트.
- **권고**: 6/1 quota 리셋 후, 누적 diff(`2db876b..HEAD`)에 대해 `/codex review` 한 번 돌려 독립 2차 의견 확보 권장. 특히 P0-1 VPD, P0-5 TOTP, P0-6 CORS 캐시.

---

## 4. admin-ui (별도 phase로 진행 중/예정)
- P0 백엔드 API 중 운영자 UI가 필요한 것: **테넌트 suspend/activate 버튼**, **MFA enroll/verify 화면**(없으면 MFA 실활성화 불가). self-service credential은 RP-facing이라 admin-ui 대상 아님.
- 사용자 결정: "백엔드 먼저, UI는 별도 phase". → 본 P0 머지 후 brainstorm→plan→worktree 사이클로 진행.

---

## 5. 환경 메모 (코드 이슈 아님)
- admin-app의 8개 `*IT` (Oracle Testcontainers)가 이 환경에서 **ORA-12541 / connection refused**로 실패. 80+ 컨테이너 serial boot 후 listener 미가동 — 환경적 flakiness이며 본 브랜치 변경과 무관(다른 IT는 통과). 단위·슬라이스 테스트는 전부 green. CI/충분한 리소스 환경에서 재실행 권장.
