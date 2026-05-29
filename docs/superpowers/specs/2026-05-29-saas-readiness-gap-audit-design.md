# SaaS Production Readiness — Gap Audit

- **작성일**: 2026-05-29
- **대상**: Crosscert Passkey Platform (core / passkey-app / admin-app / admin-ui / sdk-java / sample-rp)
- **목적**: 외부 고객 대상 **production SaaS 출시** 관점에서 코드베이스 전체 기능 gap 인벤토리 작성
- **성격**: 감사 리포트(읽기 전용). 코드 변경 없음. 후속 plan 수립의 입력물.

> 본 리포트의 분류·우선순위는 감사자가 판단했으며, 각 항목에 file:line 근거와 함께 제시한다.

---

## 1. Executive Summary

핵심 인증 경로(WebAuthn ceremony, ID Token 발급, audit hash-chain, VPD 테넌트 격리)는 **production-grade로 견고**하다. 출시를 막는 gap은 "엔진"이 아니라 **SaaS 운영 표면(operational surface)** 에 집중되어 있다 — admin 계정 보안(MFA/lockout 미구현), 설정값이 저장만 되고 미적용(CORS·password 정책), 테넌트 정지(suspend) 라이프사이클 부재, 일부 테넌트 스코프 테이블의 VPD 누락이 대표적이다.

| 우선순위 | 정의 | 건수 |
|---|---|---|
| **P0 — 출시 차단** | 외부 고객에게 켜는 순간 보안 사고·교차테넌트 유출·돌이킬 수 없는 운영 문제로 직결 | 6 |
| **P1 — 출시 직후 필수** | 출시 후 수 주 내 운영/고객요구로 반드시 필요하나 첫날 사고는 아님 | 7 |
| **P2 — 이후 / 경쟁력** | 편의·확장·경쟁력. 없어도 운영 가능 | 8 |

**한 줄 결론**: 기반은 탄탄하다. P0 6건(주로 admin 보안 강제 + 테넌트 정지 + VPD 보강)을 닫으면 안전하게 SaaS로 켤 수 있다.

---

## 2. 무엇이 견고한가 (Solid Foundation)

출시 차단이 아닌, 이미 production 수준으로 검증된 부분. gap 목록을 "전부 부족"으로 오독하지 않도록 먼저 명시한다.

| 영역 | 근거 |
|---|---|
| WebAuthn 등록/인증 ceremony 핵심 | challenge one-shot(GETDEL, `ChallengeStore.java:36`), 5분 TTL(`:15`), counter 단조증가·clone 탐지(`AuthenticationFinishService.java:190-202`), finish 동시성 row-lock(`:115-118`), origin/RP 검증(`RegistrationFinishService.java:130-141`) |
| VPD 테넌트 격리 (핵심 테이블) | `credential`·`api_key`에 row-level policy(V3/V8→V20), 컨텍스트 NULL 시 `1=0` 안전 기본값 |
| ID Token (RS256) + 키 관리 | iss/aud/sub/exp/iat 발급(`IdTokenIssuer.java:49-53`), JWKS 공개키 강제 투영(`JwksAssembler.java:42`), kid=SHA-256 thumbprint, 키 봉투 AES-256-GCM 암호화(`KeyEnvelope.java`), 수동 rotation + grace period(`KeyRotationService`, `KeyExpirationJob`) |
| API key 인증 | bcrypt cost 12 + timing-equalized 실패(`ApiKeyAuthFilter.java:107-123`), prefix O(1) 조회, revoke·last-used 추적 |
| Audit hash-chain | SHA-256 global + tenant 이중 체인, pessimistic lock 직렬화(`AuditLogService.java:104-196`), **무결성 검증기 + 테스트 존재**(`AuditChainVerifier.java`, `AuditChainVerifierTest.java`), 월간 PDF 보고서 |
| 구조적 로깅 | MDC 4-tuple(traceId/tenantId/actorEmail/apiKeyPrefix) + SecretMaskingConverter redaction |
| 스케줄러 leader election | DB-backed lease, split-brain 방지(`SchedulerLeaseService.java:43-82`) |
| Rate limiting | per-IP + per-API-key, IP 버킷 선행(bcrypt 전 flood drop), Redis 백엔드 |
| MDS | blob fetch + webauthn4j 서명검증, 테넌트별 AAGUID allow/deny 정책 적용, 6h 동기화 + history(V34) |
| 프로필/마이그레이션 | dev/qa/prod 분리(prod는 env var 필수), Flyway V1~V34 idempotent bootstrap |

---

## 3. P0 — 출시 차단 (Launch Blockers)

> **P0 기준**: 외부 고객에게 SaaS를 켜는 순간 보안 사고, 교차테넌트 유출, 또는 돌이킬 수 없는 운영 문제로 직결되는 것.

### [P0-1] Admin 계정 MFA 미구현 (컬럼만 존재)
- **영역**: AuthN/AuthZ
- **근거**: `mfa_enabled`/`mfa_secret` 컬럼 추가됨(V32), `SecurityPolicy.mfa_required` 토글 존재(V31). 그러나 `AdminSecurityConfig`의 filter chain에 MFA/TOTP 검증 단계가 전혀 없음(formLogin → 바로 인증 완료, `AdminSecurityConfig.java:112`). `AdminUserService`도 `isMfaEnabled()` 노출만 할 뿐 검증 로직 없음(`:131`).
- **영향**: 멀티테넌트 control plane을 password 단일 요소로만 보호. admin 계정 탈취 시 전체 테넌트 노출. `mfa_required=Y`가 기본값이나 강제되지 않아 **거짓 안전감**.
- **권고**: 로그인 성공 후 TOTP 2nd-factor 검증 단계 삽입 + recovery code. `mfa_required` 정책을 실제 게이트로 연결. (M)

### [P0-2] 설정값 미적용 — CORS allowlist & password 정책 (저장만)
- **영역**: 보안 정책 enforcement
- **근거**: `SecurityPolicyService.update()`는 `corsAllowlist`·`passwordMinLength`를 DB에 **저장만** 함(`SecurityPolicyService.java:43-45`). 이 값을 읽는 CORS 필터·password validator가 코드 어디에도 없음. 초대 수락(`InvitationService.accept()`)에서 password 길이 검증 부재.
- **영향**: 운영자가 UI에서 CORS allowlist/password 최소길이를 설정해도 **무시됨**. 약한 password 허용, CORS 정책 무력화. 규정/감사 대응 시 "설정했으나 미작동" = 신뢰 붕괴.
- **권고**: `cors_allowlist`를 동적 `CorsConfigurationSource`에 연결, `password_min_length`를 password 설정/변경 경로의 validator로 강제. (S~M)

### [P0-3] 테넌트 suspend/disable 라이프사이클 부재
- **영역**: 멀티테넌시 라이프사이클
- **근거**: `Tenant.status`(active/suspended) 컬럼 존재(V1:16)하나 `TenantAdminService`에 status 전이 메서드·엔드포인트 **전무**(grep no match). suspend 시 api-key 연쇄 revoke 로직 없음.
- **영향**: 미납·계약종료·침해 테넌트를 **차단할 방법이 없음**. 유일한 수단인 hard-delete는 audit·credential 유실 동반. 정지된 테넌트의 API key·credential이 계속 유효.
- **권고**: status 전이 API(`POST /tenants/{id}/suspend|activate`) + suspend 시 ceremony/인증 거부 + api-key 일괄 비활성. (M)

### [P0-4] 일부 테넌트 스코프 테이블 VPD 누락
- **영역**: 멀티테넌시 격리
- **근거**: `tenant_allowed_origin`·`tenant_accepted_format`(V21), `tenant_aaguid_policy`·`..._entry`(V26), `tenant_webauthn_snapshot`(V27)에 `DBMS_RLS.ADD_POLICY` 없음. 현재는 JPA 엔티티 경유 접근만이라 사실상 안전하나, DB 레이어 보호장치 부재.
- **영향**: 향후 직접 SQL/repository 추가 시 교차테넌트 유출 가능. 격리를 "app-layer 규율"에만 의존 — VPD 설계 의도(DB 강제)와 불일치. 단일 `@Query` 실수가 유출로 직결.
- **권고**: 핵심 테넌트 자식 테이블에 VPD policy 추가하여 격리를 DB로 강제. (S, 각 테이블당 마이그레이션 1건)

### [P0-5] 사용자 self-service credential 라이프사이클 부재
- **영역**: 자격증명 라이프사이클
- **근거**: admin 측 강제 revoke는 존재(`CredentialAdminService.java:68`, hard delete `:93`). 그러나 **최종 사용자(end-user)가 자신의 passkey를 조회·이름변경·삭제할 RP-facing API 없음**. credential 엔티티에 label/name 필드 없음.
- **영향**: 사용자가 분실/교체한 기기의 passkey를 스스로 제거 불가 → 보안·UX·규정(사용자 데이터 통제권) 문제. SaaS RP가 "내 패스키 관리" 화면을 만들 수 없음.
- **권고**: `/api/v1/rp/credentials` (list/rename/delete) self-service 엔드포인트 + credential label 컬럼. (M)

### [P0-6] 중복 등록 방지 미작동 (excludeCredentials 부재)
- **영역**: WebAuthn 등록 ceremony
- **근거**: registration start가 기존 credential을 `excludeCredentials`로 광고하지 않음(grep no match, `RegistrationStartService`).
- **영향**: 동일 authenticator로 같은 사용자가 중복 credential 생성 가능 → credential 비대화, 사용자 혼란, "이미 등록됨" UX 부재. WebAuthn 권장 사항 위반.
- **권고**: registration start에서 해당 user의 기존 credentialId 목록을 `excludeCredentials`로 반환. (S)

---

## 4. P1 — 출시 직후 필수

> **P1 기준**: 출시 후 수 주 내 운영 또는 고객 요구로 반드시 필요하나, 출시 첫날 사고로 직결되지는 않는 것.

### [P1-1] Per-tenant WebAuthn 설정값 ceremony 미반영
- **영역**: WebAuthn / 멀티테넌시
- **근거**: `webauthnTimeoutMs`·`requireUserVerification`·`attestationConveyance` 컬럼 존재(Tenant 엔티티, V33)하나 ceremony에서 하드코딩 사용(timeout 60s, UV "required", conveyance "indirect" — `RegistrationStartService.java:76-83`). UV는 finish에서만 테넌트값 적용.
- **영향**: 테넌트별 정책을 UI로 설정해도 start 단계에 미반영 → 고객 요구 시 즉시 대응 불가, P0-2와 같은 "설정 무시" 패턴.
- **권고**: start 단계에서 테넌트 설정을 읽어 options에 반영. (S)

### [P1-2] Metrics 파이프라인 부재 (Prometheus/Micrometer)
- **영역**: 관측성
- **근거**: `spring-boot-starter-actuator`만 의존(`core/build.gradle.kts:15`), Micrometer registry/export·`@Timed`·custom meter 없음.
- **영향**: 요청 지연·에러율·ceremony 성공률·용량을 측정 불가 → 운영 blind. SLA/용량계획 근거 없음.
- **권고**: Micrometer + Prometheus registry, ceremony·audit·MDS 핵심 지표 계측. (M)

### [P1-3] Alerting hook 부재
- **영역**: 운영
- **근거**: 로깅 가이드에 권장 alert 패턴(API key brute force, counter regression, 테넌트 경계 위반)은 문서화되어 있으나 실제 dispatcher(email/Slack/PagerDuty) 미구현.
- **영향**: 보안 이상·license 만료·MDS sync 실패를 운영자가 인지 못 함.
- **권고**: 핵심 이벤트 → 알림 채널 dispatcher. metrics(P1-2) 기반 alerting 권장. (M)

### [P1-4] Audit / 데이터 retention 정책 부재
- **영역**: 운영 / 규정
- **근거**: audit_log append-only, TTL/purge job 없음. soft-delete credential·만료 invitation token 정리 job 없음.
- **영향**: 무한 증가 → 저장비용·쿼리성능 저하. GDPR 보존기간 규정 대응 불가.
- **권고**: 보존기간 정책 + 아카이브/purge job (audit는 forensic 보존과 균형). (M)

### [P1-5] API key rotation & scope 미강제
- **영역**: AuthN
- **근거**: revoke는 가능하나 rotation(무중단 교체) 메커니즘 없음. scope가 JSON으로 저장되나 `ApiKeyAuthFilter`에서 검증 안 함.
- **영향**: 키 정기교체 운영 어려움(revoke→재발급 시 다운타임). scope 저장만 하고 미작동 = P0-2 패턴.
- **권고**: rotation 엔드포인트(grace 기간 동안 구·신 키 병존) + 요청 시 scope 검증. (M)

### [P1-6] Admin password reset / lockout 부재
- **영역**: AuthN/AuthZ
- **근거**: 신규 사용자 invitation flow만 존재, 기존 operator self-service password reset 없음. `AdminUserDetails.isAccountNonLocked()` 하드코딩 true, 실패횟수 카운터·lockout 없음(`AdminUserDetails.java:50`).
- **영향**: password 분실 시 복구 경로 없음. 무제한 로그인 시도 → brute force 노출(rate limit은 ceremony용, admin 로그인 별도).
- **권고**: password reset flow + 실패 N회 시 lockout. (M)

### [P1-7] Health check 보강 (DB/Redis/의존성)
- **영역**: 관측성
- **근거**: `LicenseHealthIndicator`(on-prem)만 존재. DB connectivity·Redis·외부 의존성 health indicator 부재. actuator export 설정 미확인.
- **영향**: 로드밸런서/오케스트레이터가 비정상 인스턴스를 정확히 격리 못 함.
- **권고**: DB·Redis health indicator + actuator health 노출 표준화. (S)

---

## 5. P2 — 이후 / 경쟁력

> **P2 기준**: 편의·확장·경쟁력 향상. 없어도 SaaS 운영은 가능.

| ID | 항목 | 영역 | 근거 / 영향 | 노력 |
|---|---|---|---|---|
| P2-1 | Billing / plan / quota / usage metering | 멀티테넌시 | plan·seat·credential 한도·사용량 추적 테이블 전무. 과금·한도 비즈니스 불가 | L |
| P2-2 | 테넌트 self-service 온보딩 | 멀티테넌시 | tenant 생성은 PLATFORM_OPERATOR 전용. self-service signup·trial 없음 | L |
| P2-3 | 테넌트 데이터 export / GDPR 삭제 워크플로 | 규정 | export·right-to-be-forgotten 워크플로 없음 | M |
| P2-4 | Async / 다국어 SDK | DX | sdk-java만 동기 RestTemplate. async·TS/Go/Python·재시도/circuit breaker 없음 | L |
| P2-5 | 브라우저(JS/TS) 클라이언트 SDK | DX | client-side `@crosscert/passkey-js` 부재. RP가 직접 WebAuthn JS 작성 필요 | M |
| P2-6 | OpenAPI/Swagger + API 버저닝 전략 | DX | 생성된 API spec·interactive explorer 없음, deprecation 정책 없음 | M |
| P2-7 | WebAuthn 확장 & 알고리즘 확대 | WebAuthn | prf/largeBlob/credProps(파싱만, 미반환)·EdDSA(-8) 미지원. credProps `RegistrationFinishService.java:228` 저장만 | M |
| P2-8 | 추가 attestation format / 신뢰앵커 검증 | WebAuthn | tpm/apple/android 등 테넌트 노출 안 됨, MDS 신뢰앵커 기반 cert chain 검증 미구현 | M |

---

## 6. 부록 — 도메인별 커버리지 매트릭스

| 도메인 | IMPLEMENTED | PARTIAL | MISSING |
|---|---|---|---|
| **WebAuthn ceremony** | challenge/counter/origin/RP 검증, MDS AAGUID 정책, 동시성 lock, ES256/RS256 | per-tenant 설정 미반영(timeout/UV/conveyance), transports/BE-BS 저장만 | excludeCredentials, credential 라이프사이클(label/self-service), 확장(prf/largeBlob), EdDSA, 추가 attestation format |
| **멀티테넌시/격리** | credential·api_key VPD, PLATFORM_OPERATOR/RP_ADMIN 분리, TenantBoundary, 컨텍스트 전파 | audit_log 격리(설계상 cross-tenant), admin-app은 app-layer 격리 의존 | tenant suspend/soft-delete, 자식 테이블 VPD, billing/quota, self-service 온보딩, export/GDPR |
| **AuthN/AuthZ/보안** | api-key bcrypt+timing, RS256+JWKS+kid, 키 봉투 암호화, 키 rotation+grace, RBAC 분리, rate limit | password 정책(저장만), scope(저장만), session 관리 | MFA enforcement, account lockout, password reset/history, CORS 적용, API key rotation, ID Token nonce |
| **운영/관제/SDK** | audit hash-chain+검증기, MDC 로깅+redaction, scheduler lease, MDS sync+history, 월간 PDF, on-prem license 설계 | audit retention(없음), funnel/credential stats(부분), health(license만) | metrics(Prometheus), alerting, async/JS SDK, OpenAPI, webhook, email/notification, backup/restore 문서 |

---

## 7. 권장 다음 단계

1. 본 리포트를 사용자가 리뷰 → P0/P1 분류 동의 여부 확인.
2. **P0 6건**을 하나의 "SaaS launch hardening" phase로 묶어 writing-plans로 실행 계획 수립 (worktree 단위).
3. P1은 출시 직후 1~2 스프린트 백로그로, P2는 로드맵으로 분리.

> 분류 경계에 이견이 있는 항목(예: P0-5 credential self-service를 P1로, 또는 P1-6 lockout을 P0로)은 리뷰 시 조정 가능.
