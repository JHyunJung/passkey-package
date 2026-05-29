# On-Premise Licensing — Design Spec

**Date**: 2026-05-29
**Status**: Draft (awaiting user review)
**Scope**: 설치형(on-prem) 싱글테넌트 배포를 위한 라이센스 검증 시스템
**Out of scope**: 라이센스 발급 API, 키 로테이션, 외부 SaaS 라이센싱 통합

---

## 배경

현재 Passkey 플랫폼은 SaaS 멀티테넌트로 설계되어 있다 (VPD 격리, audit chain, multi-tenant admin). 일부 고객은 데이터 주권·규제(금융/공공) 사유로 자체 인프라에 설치하길 원할 수 있다. 설치형 배포가 필요해지면 두 가지가 함께 필요하다:

1. **싱글테넌트 동작 모드** — 멀티테넌트 코드를 그대로 두되 단일 tenant 로 고정 운영
2. **라이센스 시스템** — 계약 만료·기능 제어를 강제하는 기술적 메커니즘

이 문서는 (1)과 (2)를 단일 코드베이스에서 구현하는 설계를 다룬다.

---

## 결정 사항 요약

| 항목 | 결정 |
|---|---|
| 배포 환경 가정 | 항상 온라인 (고객사 ↔ 우리 라이센스 서버 연결 가능) |
| 라이센스 범위 | 만료일(`exp`) + 기능 set (`features`) |
| 만료/위반 시 동작 | Hard fail (전체 API 차단), 만료 전 N일 admin 경고 배너 |
| 네트워크 단절 시 | 서명된 토큰 캐싱 + 설정 가능한 grace period(기본 72h) 후 hard fail |
| 코드 동거 전략 | 단일 바이너리 + 런타임 `passkey.deployment.mode` 프로파일 (`saas` / `onprem`) |
| 이번 스코프 | on-prem 설치 서버의 **검증 로직만**. 발급 API·발급 도구는 후속 phase |
| 라이센스 토큰 포맷 | Ed25519 서명 JWS (JWT) |
| 테스트 전략 | 보안 코어 단위 테스트 2개 + 회귀/차단 통합 테스트 2개 (개발 속도 우선) |

---

## 1. 아키텍처 개요

`passkey.deployment.mode` 프로파일이 SaaS와 on-prem의 분기점이다. 같은 jar, 다른 활성 빈.

```
                ┌─ deployment.mode=saas  ─→ 기존 동작 (멀티테넌트 admin, VPD 동적 tenant, 라이센스 검증 OFF)
build (단일 jar) │
                └─ deployment.mode=onprem ─→ 단일 tenant 고정, LicenseGuard 활성화, license 메뉴 노출
```

새 모듈은 만들지 않는다. 모든 라이센스 로직은 기존 `core` 모듈에 추가하고 `@ConditionalOnProperty(name="passkey.deployment.mode", havingValue="onprem")` 로 활성화를 통제한다.

```
core/
└─ license/
   ├─ LicenseToken              # JWS 파싱된 도메인 객체 (sub, exp, features, issuedAt, tenantId, limits)
   ├─ LicenseVerifier           # Ed25519 서명 검증 + exp/nbf/aud/iss 검증 + feature 화이트리스트 필터
   ├─ LicenseLoader             # 파일 경로에서 토큰 로드 (passkey.license.path)
   ├─ LicenseCache              # 라이센스 서버 응답 캐싱 (디스크 파일, lastVerifiedAt 단조 증가)
   ├─ LicenseHeartbeatScheduler # @Scheduled, 라이센스 서버 폴링
   ├─ LicenseState              # 싱글톤 상태 enum (VALID / WARNING / NETWORK_GRACE / DEAD)
   ├─ LicenseStateMachine       # 상태 전이 + heartbeat 결과 적용
   └─ FeatureGate               # @RequiresFeature("mds") AOP 어노테이션

admin-app/
└─ admin/license/
   ├─ LicenseController         # GET /admin/api/license (현재 상태 조회)
   └─ LicenseGuardFilter        # Spring Security filter — DEAD 시 503

admin-ui/
└─ pages/License.tsx            # 라이센스 정보·만료일·feature·grace 잔여 시간 표시
└─ components/LicenseBanner.tsx # 전역 경고 배너 (WARNING/NETWORK_GRACE 시)

passkey-app/
└─ (LicenseGuardFilter 적용 — DEAD 시 ceremony 차단)
```

**deployment.mode=onprem 시 활성화**:
- `LicenseGuardFilter` (모든 HTTP 진입점)
- `LicenseHeartbeatScheduler`
- `LicenseStateMachine`
- admin-ui의 license 메뉴
- VPD `set_tenant` 을 부팅 시 1회 고정값으로 호출 (라이센스의 `tenantId`)

**deployment.mode=saas 시**:
- 위 빈들 모두 비활성
- 기존 SaaS 동작 그대로

---

## 2. 라이센스 토큰 포맷

### 서명 알고리즘

**Ed25519 (EdDSA)** — ID Token에 쓰는 RS256과 분리한다.
- 이유: 보안 도메인 격리. 키가 짧고 서명 빠름.
- 우리(라이센스 발급자) private key는 보안 보관, public key만 jar에 임베드 (`core/src/main/resources/license-public.ed25519.pub`).

### 토큰 위치

- `passkey.license.path` 환경변수 (기본 `/etc/passkey/license.jwt`)
- 평문 파일 한 줄 (compact JWS 형식)

### Payload 스키마

```json
{
  "iss": "license.crosscert.com",
  "sub": "acme-corp",
  "aud": "passkey-onprem",
  "iat": 1735689600,
  "nbf": 1735689600,
  "exp": 1767225600,
  "jti": "lic-acme-2026-001",
  "tenantId": "7f00dead-0000-0000-0000-00000ace0001",
  "features": ["mds", "audit-pdf", "security-policy-advanced"],
  "limits": {
    "warningDaysBeforeExpiry": 30,
    "graceHoursWhenOffline": 72
  }
}
```

| 필드 | 의미 |
|---|---|
| `iss` | 발급자 식별자 — `license.crosscert.com` 고정 |
| `sub` | 고객사 식별자 (비즈니스 식별자, tenant_id 와 별개) |
| `aud` | 환경 식별자 — `passkey-onprem` 고정 (잘못된 환경 사용 방지) |
| `iat` / `nbf` | 발급/유효 시작 시각 (epoch sec) |
| `exp` | 만료 시각 (epoch sec) — hard fail 기준 |
| `jti` | 라이센스 ID — 갱신/revocation 추적 |
| `tenantId` | on-prem 부팅 시 VPD 에 고정할 tenant UUID |
| `features` | 라이센스가 허용하는 feature 문자열 배열 |
| `limits.warningDaysBeforeExpiry` | 만료 전 경고 시작 시점 (기본 30일) |
| `limits.graceHoursWhenOffline` | 네트워크 단절 후 grace period (기본 72h) |

### 검증 순서 (`LicenseVerifier`)

1. JWS 서명 검증 (jar 임베드 public key)
2. `iss == "license.crosscert.com"`, `aud == "passkey-onprem"`
3. `nbf <= now <= exp`
4. `jti` revocation list 확인 (heartbeat 응답 기반, 없으면 skip)

### 설계 노트

- **features 는 string 배열**: enum 이 아니라 string. 새 feature 추가 시 라이센스만 갱신해서 활성화. 검증 시 알려진 feature 화이트리스트와 교집합만 신뢰 (오타·악의적 주입 차단).
- **tenantId 를 토큰에 박는 이유**: 부팅 시 VPD `set_tenant(tenantId)` 를 라이센스가 지정한 값으로 고정. SaaS DB에서 export 한 데이터와 같은 tenant_id 사용 가능.
- **키 로테이션**: 다음 phase. v1은 단일 키 가정.

---

## 3. 데이터 흐름과 상태 머신

### LicenseState (4가지)

```
        부팅
         │
         ▼
   ┌─────────────┐     exp - now > warningDays
   │   VALID     │ ◀───────────────────────────┐
   └──────┬──────┘                              │
          │ exp - now ≤ warningDays            │
          ▼                                     │
   ┌─────────────┐     heartbeat 성공          │
   │   WARNING   │ ◀───────────────────────────┤
   └──────┬──────┘ (만료 다가옴, admin 배너)    │
          │                                     │
          │ heartbeat 실패 (어디 단계든)        │
          ▼                                     │
   ┌─────────────┐                              │
   │NETWORK_GRACE│ ─────── heartbeat 성공 ─────┘
   └──────┬──────┘
          │ now - lastVerifiedAt > graceHours
          ▼
   ┌─────────────┐
   │    DEAD     │ ◀── exp 초과 시에도 진입
   └─────────────┘
          (모든 API 차단)
```

### 상태 전이 트리거 — LicenseHeartbeatScheduler

- 부팅 직후 1회, 이후 `@Scheduled(fixedDelayString="${passkey.license.heartbeat-interval:PT1H}")` (기본 매 1시간)
- 호출: `GET https://license.crosscert.com/v1/license/{jti}/verify`
- 응답: `{ "status": "active|revoked", "latestToken": "<JWS>" }`
- 성공 시: 응답 토큰을 디스크 캐시(`/var/lib/passkey/license-cache.jwt`)에 저장, `lastVerifiedAt = now`
- 실패 시: 캐시된 토큰 유지, `lastVerifiedAt` 갱신 안 함

### 부팅 시퀀스

`LicenseLoader` + `LicenseVerifier` (ApplicationRunner 또는 @PostConstruct):

1. `passkey.license.path` 에서 토큰 파일 로드 — 없으면 ApplicationContext 시작 실패
2. JWS 서명 / iss / aud / nbf / exp 검증 — 실패 시 시작 실패
3. 디스크 캐시 토큰 로드 (있으면) — 캐시가 더 최신 `exp` 면 캐시 우선
4. 초기 상태 결정:
   - `exp < now` → DEAD
   - `exp - now ≤ warningDays` → WARNING
   - else → VALID
5. `LicenseHeartbeatScheduler` 첫 호출 즉시 트리거

### 요청 처리 흐름 — LicenseGuardFilter

Spring Security filter chain 최상단:

```
HTTP 요청 → LicenseGuardFilter.doFilter()
  ├─ state == DEAD ?
  │    └─ 요청 경로 ∈ {/admin/api/license, /actuator/health, /admin/login} ?
  │         ├─ 예 → 통과
  │         └─ 아니오 → 503 Service Unavailable
  │              body: { "code": "LICENSE_EXPIRED", "message": "..." }
  ├─ state == NETWORK_GRACE 또는 WARNING ?
  │    └─ 응답 헤더에 X-License-Warning: "expires in N days" 추가, 통과
  └─ state == VALID ?
       └─ 통과
```

### FeatureGate (AOP)

```java
@RequiresFeature("mds")
public void runMdsSync() { ... }
```

- `FeatureGateAspect`: `LicenseState.token.features` 에 "mds" 없으면 → `FeatureNotLicensedException` → 403
- `deployment.mode=saas` 이면 aspect 비활성 (항상 통과)

**적용 대상 (이번 phase)**:
- `mds` — MDS scheduler
- `audit-pdf` — audit chain 보고서 PDF 생성
- `security-policy-advanced` — 고급 보안 정책 UI 항목

**적용 안 함**:
- Core ceremony (registration/authentication) — 라이센스 만료(DEAD) 시에만 차단되고, 평상시에는 feature gate 없음. "구독 중인 동안 핵심 인증은 항상 동작" 원칙.

---

## 4. 에러 처리, 보안, 운영 가시성

### 에러 응답 표준화

기존 `ApiResponse` 컨벤션 활용.

| 상황 | HTTP | code | message | 로그 |
|---|---|---|---|---|
| 라이센스 파일 없음 (부팅) | — | — | Application 시작 실패 | FATAL: `License file not found at /etc/passkey/license.jwt` |
| 서명 검증 실패 (부팅) | — | — | Application 시작 실패 | FATAL: `License signature invalid` |
| DEAD 상태 요청 | 503 | `LICENSE_EXPIRED` | "License has expired. Contact your administrator." | WARN (요청당 1회 sampling) |
| feature 미허용 | 403 | `FEATURE_NOT_LICENSED` | "This feature is not included in your license: {feature}" | INFO |
| heartbeat 실패 | — | — | — | WARN: `License heartbeat failed: {reason}, graceRemainingHours={n}` |
| NETWORK_GRACE 진입 | — | — | (배너로만 노출) | WARN: `License heartbeat unavailable, entered grace until {timestamp}` |
| WARNING 진입 | — | — | (배너로만 노출) | INFO: `License expires in {n} days` |

### 보안 고려사항

1. **Public key 보호**: jar 임베드라 read-only. jar 재패키징을 통한 key 교체는 코드 차원에서 막을 수 없다 (Java decompile/replace). 정식 jar 의 SHA256 을 공개 + on-prem 설치 가이드에 검증 절차 명시 + 계약·감사로 통제.
2. **라이센스 파일 무단 복제**: `sub`, `tenantId`, `jti` 가 박혀 있어 다른 환경에 옮겨도 추적 가능. heartbeat 시 `instanceId`(부팅 시 1회 생성) + `hostname` 동봉 → 같은 jti 가 여러 instanceId 에서 호출되면 우리 쪽에서 감지. **서버측 탐지는 후속 스코프**, 클라이언트 송신만 v1.
3. **시간 조작 (clock rollback)**: `lastVerifiedAt` 을 캐시에 저장. 시스템 시간이 캐시된 값보다 과거이면 DEAD 진입.
4. **시크릿 노출**: JWS payload 자체는 비밀 아님 (서명만 검증). 일부 필드(`sub`, `jti`, `exp`, `features`) 로그 OK, **전체 토큰 문자열 금지**. `SecretMaskingConverter` 에 `eyJ` prefix 패턴 추가 (ID Token 마스킹 패턴 재활용).
5. **MDC**: 모든 license 관련 로그에 `[license.jti=lic-acme-2026-001]` MDC 추가.

### 운영 가시성

#### GET /admin/api/license 응답

```json
{
  "deploymentMode": "onprem",
  "state": "WARNING",
  "sub": "acme-corp",
  "jti": "lic-acme-2026-001",
  "expiresAt": "2026-12-31T23:59:59Z",
  "daysUntilExpiry": 18,
  "features": ["mds", "audit-pdf"],
  "lastVerifiedAt": "2026-05-29T08:30:00Z",
  "graceRemainingHours": null,
  "heartbeatUrl": "https://license.crosscert.com/v1/license/lic-acme-2026-001/verify",
  "nextHeartbeatAt": "2026-05-29T09:30:00Z"
}
```

#### Actuator health indicator

```
GET /actuator/health
{
  "status": "UP",
  "components": {
    "license": {
      "status": "UP",
      "details": { "state": "WARNING", "daysUntilExpiry": 18 }
    }
  }
}
```

- VALID / WARNING / NETWORK_GRACE → UP
- DEAD → DOWN (전체 health 도 DOWN)

#### admin UI 배너

`LicenseBanner.tsx` (전역, App.tsx 최상단):

| 상태 | 배너 |
|---|---|
| VALID | (없음) |
| WARNING | 노란 배너 — "라이센스가 {n}일 후 만료됩니다. 영업 담당자에게 갱신을 요청하세요." |
| NETWORK_GRACE | 주황 배너 — "라이센스 서버와 연결할 수 없습니다. {n}시간 내 차단됩니다." |
| DEAD | 빨간 전체 페이지 — "라이센스가 만료되어 서비스가 중단되었습니다. 영업 담당자에게 문의하세요." 다른 메뉴 비활성 |

---

## 5. 테스트 전략

**원칙**: 보안 코어와 회귀 방지선만 지키고 나머지는 빠르게 진행. 운영하면서 필요할 때 추가.

### 유지하는 테스트

| 테스트 | 검증 내용 | 왜 필수인가 |
|---|---|---|
| `LicenseVerifierTest` | 유효 토큰 통과 / 만료 reject / 잘못된 서명 reject | 서명·만료 검증이 뚫리면 라이센스 전체가 무의미 |
| `LicenseStateMachineTest` | VALID → WARNING → DEAD 한 흐름 + NETWORK_GRACE 만료 후 DEAD | 상태 전이 버그는 디버깅이 어려움 |
| `LicenseGuardFilterIT` | DEAD 상태에서 ceremony API → 503, `/admin/api/license` → 200 | "차단된다"를 실제로 증명 |
| `DeploymentModeProfileIT` | `mode=saas` 부팅 → license 빈 없음 (기존 동작 무회귀) | SaaS 회귀 방지 보험 |

### 드롭한 테스트 (필요시 추가)

- `LicenseLoaderTest`, `LicenseCacheTest` — 파일 I/O wrapper, 통합 테스트에서 간접 검증
- `FeatureGateAspectTest` — feature 1개 동작 확인되면 충분
- `LicenseHeartbeatSchedulerIT` (WireMock) — 셋업 비용 큼, 수동 dev 환경에서 확인
- `OnpremBootWithoutLicenseIT` — 부팅 실패는 운영 중 1회만 마주침
- E2E 스크립트 — 영업 데모 직전 수동 검증으로 대체

### 테스트 fixture

```java
public class LicenseTestFixtures {
  public static String issueValid(Duration validFor, List<String> features);
  public static String issueExpired();
  public static String issueWithBadSignature();
}
```

### 키페어

- 테스트 전용 고정 키페어 1쌍을 `core/src/test/resources/` 에 커밋 (README 에 "테스트 전용" 명시)
- 결정론적 테스트 + 셋업 단순화 우선 → 빌드 시 자동 생성 X

### 기존 테스트 영향

`deployment.mode=saas` 가 기본값 → 기존 테스트 영향 없음. `DeploymentModeProfileIT` 가 보험.

---

## 6. 구현 단계 (마이그레이션 순서)

5개 단계. 각 단계는 독립 commit 가능, 이전 단계가 깨지지 않음.

### L1 — Foundation: deployment.mode + LicenseToken/Verifier

- `application.yml`: `passkey.deployment.mode: saas` 기본값
- `core/license/` 패키지 생성: `LicenseToken`(record), `LicenseVerifier`, `LicenseLoader`
- Ed25519 의존성 추가 (기존 JWT 라이브러리 확인 후 `jose4j` 또는 `bouncycastle` 결정)
- 테스트 키페어 커밋
- `LicenseVerifierTest` 작성
- **이 시점**: onprem 빈 없음. 토큰 파싱/검증만 가능한 도구 상태.

### L2 — State machine + Heartbeat + Cache

- `LicenseState` enum, `LicenseStateMachine` (싱글톤 빈, `@ConditionalOnProperty(mode=onprem)`)
- `LicenseCache` (디스크 파일 read/write, lastVerifiedAt 단조성)
- `LicenseHeartbeatScheduler` (`@Scheduled`, RestTemplate)
- 부팅 시퀀스: `ApplicationRunner` 로 초기 상태 결정
- `LicenseStateMachineTest` 작성
- **이 시점**: 차단 없음. 상태만 추적되고 로그만 남음.

### L3 — Guard filter + Feature gate

- `LicenseGuardFilter`: Spring Security filter chain 최상단, DEAD 시 503 (예외 경로: `/admin/api/license`, `/actuator/health`, login)
- `@RequiresFeature` 어노테이션 + `FeatureGateAspect`
- 적용: MDS scheduler, audit PDF, security-policy advanced
- `LicenseGuardFilterIT` 작성
- `DeploymentModeProfileIT` 작성
- **이 시점**: onprem 모드 기능적으로 완성.

### L4 — Admin API + UI

- `LicenseController`: `GET /admin/api/license`
- `LicenseHealthIndicator`: actuator `/health` 에 license 컴포넌트 추가
- admin-ui:
  - `LicenseBanner.tsx` (전역)
  - `pages/License.tsx` (메뉴 추가, onprem 모드에서만 노출 — `/admin/api/system/deployment-mode` 메타 API 1개 추가 필요)
- SecretMaskingConverter 에 `eyJ` prefix 패턴 추가

### L5 — Documentation + 운영 가이드

- `docs/onprem-deployment.md`:
  - 부팅 환경변수 매트릭스 (`SPRING_PROFILES_ACTIVE=prod,onprem`, `PASSKEY_LICENSE_PATH`, `PASSKEY_LICENSE_HEARTBEAT_URL`)
  - 라이센스 파일 받는 절차 (영업 → 고객)
  - 단계별 상태 의미와 운영자 액션
  - 시계 동기화 (NTP) 권장
- `docs/logging-operations.md` 에 license 검색 cookbook 추가
- `README.md` 에 deployment.mode 한 줄 추가

### 병렬화

- L1 → L2 → L3 순차 (각 단계가 이전 산출물 사용)
- L4, L5 는 L3 완료 후 병렬 가능

### 예상 commit 수

단계당 2~4 commit, 총 12~18 commit.

### Phase 분기 전략

기존 컨벤션대로 worktree 에서 작업 → `--no-ff` merge.

---

## Open Questions (구현 중 결정)

- Ed25519 라이브러리 선택: 기존 `jose4j` 가 Ed25519 지원하는지 확인 → 지원하면 그대로, 아니면 `bouncycastle` 추가
- 라이센스 서버 URL은 빌드 타임 상수? 환경변수? — 환경변수 기본값으로 잡고 빌드별 prod 프로파일에서 override 가능하게
- `instanceId` 영속 위치: 디스크 캐시 파일에 함께 보관 (라이센스 캐시와 같은 lifecycle)

---

## 다음 단계

이 spec 의 사용자 리뷰가 완료되면 `writing-plans` 스킬로 implementation plan 작성.
