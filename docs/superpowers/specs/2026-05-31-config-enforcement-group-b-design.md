# 설정값 미적용 마감 (그룹 B) — Design

- **작성일**: 2026-05-31
- **대상**: Crosscert Passkey Platform (core / passkey-app / admin-app)
- **근거 spec**: [2026-05-29-saas-readiness-gap-audit-design.md](2026-05-29-saas-readiness-gap-audit-design.md) §4 P1-1, P1-5
- **성격**: 구현 설계. 후속 writing-plans의 입력물.

## 1. 목적 / 범위

SaaS gap audit의 P1-1·P1-5를 하나의 "설정값 미적용 마감(config enforcement)" phase로 묶어 닫는다. 두 항목 모두 spec이 지목한 **"설정값이 DB에 저장만 되고 실제로는 미작동"** 안티패턴(P0-2와 동류)이다.

| ID | 항목 | 현황 |
|---|---|---|
| P1-1 | per-tenant WebAuthn 설정값 ceremony 미반영 | Tenant에 `webauthnTimeoutMs`/`requireUserVerification`/`attestationConveyance` 컬럼 존재(V33). ceremony **start** 2곳이 하드코딩(timeout 60s, UV "required", conveyance "indirect"). finish는 UV만 테넌트값 적용 |
| P1-5 (scope) | API key scope 강제 | scope가 정규화 테이블 `api_key_scope`(enum: registration/authentication/admin)에 저장만, 인증·엔드포인트 어디서도 미검증 |
| P1-5 (rotation) | API key rotation | issue/revoke만 존재. 무중단 교체(grace 병존) 메커니즘 없음 |

**인접 minor cleanup (포함)**: ApiKey active 정의 통일(P1-5 직접 인접), `AuthenticationStartService.findAll()`→`findByUserHandle`(P1-1이 같은 파일 수정).

**범위 밖(deferred)**: spec §4의 P1-2/P1-3/P1-4/P1-7(그룹 C·D), P2 전체.

**원칙**: 기존 컬럼·엔티티 재사용, 신규 스키마 최소화. 기존 패턴(VPD, TenantContextHolder, audit, @PreAuthorize) 준수.

## 2. P1-1: ceremony start 설정 반영

세 start 지점이 Tenant의 기존 3필드를 읽도록 수정. **신규 스키마 없음.**

### 2.1 RegistrationStartService (passkey-app)
하드코딩 3개를 테넌트 값으로 교체:
- `timeout` 60000 → `tenant.getWebauthnTimeoutMs()`
- `attestation` "indirect" → conveyance 소문자 변환 (아래 2.4)
- `userVerification` "required" → `tenant.isRequireUserVerification() ? "required" : "preferred"`

### 2.2 AuthenticationStartService (passkey-app)
- `timeout` 60000 → `tenant.getWebauthnTimeoutMs()`
- `userVerification` "required" → `tenant.isRequireUserVerification() ? "required" : "preferred"`
- (attestation은 authentication ceremony에 해당 없음)
- 인접 cleanup: `findAll()` + in-memory `Arrays.equals` 필터 → derived `findByUserHandle` 교체.

### 2.3 RegistrationFinishService
이미 `tenant.isRequireUserVerification()` 적용 중(`:149`). **변경 없음.** start UV와 finish UV가 이제 일관.

### 2.4 conveyance 매핑 헬퍼
Tenant는 대문자 enum 저장(`NONE/INDIRECT/DIRECT/ENTERPRISE`, V33 CHECK 제약), WebAuthn options는 소문자(`none/indirect/direct/enterprise`). 변환 헬퍼를 한 곳에 두어 공유(예: `Tenant.getAttestationConveyanceLowercase()` 또는 registration start의 private static 헬퍼). 알 수 없는 값(스키마 CHECK로 차단되나 방어적) → 안전 기본 `"none"` + WARN. `ENTERPRISE`는 WebAuthn 표준 값이라 그대로 소문자화.

## 3. P1-5: scope 검증 (필터에서 경로→scope 매핑)

### 3.1 scope 조회 — JPA repository (PL/SQL 확장 불필요)
`ApiKeyLookupService.findByPrefix`는 V8 definer-rights PL/SQL 패키지(`api_key_lookup_pkg`)의 7-OUT-param CallableStatement다. 여기에 scope 컬렉션 OUT을 추가하는 것은 무겁고 위험하다.

**대신**: scope 조회는 **인증 성공 후** 수행하므로 그 시점엔 `TenantContextHolder`가 이미 설정돼 VPD가 정상 작동한다. scope는 인증된 그 키(같은 테넌트)의 것이므로 **일반 JPA `ApiKeyScopeRepository`(신설)로 조회** 가능 — definer-rights 우회 불필요.
- 신설: `ApiKeyScopeRepository extends JpaRepository<ApiKeyScope, UUID>` — scope 문자열만 필요하므로 projection 쿼리 `@Query("select s.scope from ApiKeyScope s where s.apiKey.id = :apiKeyId") Set<String> findScopeValuesByApiKeyId(UUID apiKeyId)`. (ApiKeyScope의 PK 타입은 구현 시 확인 — UUID 또는 Long, 엔티티에 맞춤.)
- N+1 우려 낮음(키당 scope 1~3개, 요청당 1회).

### 3.2 경로→scope 매핑 (ApiKeyScopeResolver)
RP-facing 경로를 요구 scope로 매핑하는 컴포넌트. 실제 경로 prefix는 구현 시 RP 컨트롤러 `@RequestMapping`으로 확정하되, 설계상:
- registration ceremony 경로(register start/finish) → `registration`
- authentication ceremony 경로(authenticate start/finish) → `authentication`
- self-service credentials 경로 → 구현 시 결정(registration 계열 유력)
- 매핑 없는 경로(scope 불요) → 통과

매핑 테이블은 단일 컴포넌트에 응집(필터에 흩지 않음).

### 3.3 enforcement + 하위호환
- 요구 scope를 키 보유 scope 집합과 대조: 보유 → 통과; **보유 scope 집합이 비었거나 요구 scope 미보유 → 403** `{"error":"insufficient_scope"}`.
- 위치: `ApiKeyAuthFilter` 내 bcrypt 검증·`TenantContextHolder` 설정 직후(인증된 키에 한해 평가). rate limit·인증은 그대로 선행.
- 401(인증 실패)과 403(insufficient_scope) 구분 — 키는 유효하나 권한 부족.
- forensic 로그: 거부 시 prefix·요구scope·보유scope WARN(평문 키 미노출).

### 3.4 하위호환 게이트 (구현 1단계)
"scope 없는 키 거부" 정책이므로, 구현 첫 단계에서 **scope 빈 키 존재 여부를 쿼리로 확인**(발급 경로상 `scopes`는 `@NotBlank` Set이라 가질 가능성 높으나 seed/과거 데이터 확인). 비어있는 키가 있으면 사용자에게 보고 후 진행 결정. plan에 검증 step 포함.

## 4. P1-5: rotation (신규 발급 + 구 키 grace 만료)

`ApiKeyAdminService`에 rotation 추가. **신규 스키마 없음** — 기존 `expiresAt` 재사용.

### 4.1 서비스 메서드
`rotate(UUID oldKeyId, UUID actorId, String actorEmail)`:
1. 구 키 로드 + tenant 경계 검증(issue/revoke 권한 패턴 동일).
2. 새 키 발급 — 구 키와 동일 `tenantId`·`name`·**scope 집합 복제**(issue 로직 재사용; 새 prefix·secret·bcrypt).
3. 구 키 `expiresAt = now + grace`(grace 기본 24h, 설정값 `passkey.api-key.rotation.grace:PT24H`). 이미 만료/폐기된 키면 `BusinessException`.
4. audit `API_KEY_ROTATED`(old prefix + new prefix).
5. 응답: 신규 평문 키 1회 + `oldKeyExpiresAt`(RP 교체 데드라인 인지).

grace 동안: 구 키 `expiresAt > now`라 인증 통과(기존 active 판정), 신규도 통과 → 무중단 병존. grace 경과 후 구 키 자동 만료.

### 4.2 컨트롤러 엔드포인트
`POST /admin/api/api-keys/{id}/rotate` — `@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")` 동일. 응답 shape: `ApiKeyCreateResponse` + `oldKeyExpiresAt`.

### 4.3 scope 복제 ↔ §3 일관
rotate가 scope를 복제하므로 §3 enforcement와 자연 일관 — 새 키는 구 키와 정확히 같은 권한.

## 5. 인접 minor cleanup

### 5.1 ApiKey active 정의 통일 (P1-5 직접 인접)
`findActiveByTenantId`가 `revokedAt is null`만 보고 `expiresAt` 미체크. rotation이 `expiresAt`을 grace 만료에 쓰므로 **이 통일이 정확성에 직결**(만료된 구 키가 "active"로 안 잡히게). `countActiveByTenantId`(이미 expiresAt 체크)와 `ApiKey.isActive()`(엔티티, 두 조건 다 체크)에 맞춰 `findActiveByTenantId`도 `expiresAt > now` 추가.

### 5.2 AuthenticationStartService findByUserHandle (§2.2에 포함)

## 6. 에러 처리 / 보안 경계

- **scope 거부**: 403 `{"error":"insufficient_scope"}`, prefix·요구/보유 scope WARN(평문 미노출). 401과 구분.
- **rotation**: 만료/폐기 키 rotate → `BusinessException`(409/400). 신규 평문 키 응답 1회, audit엔 prefix만.
- **conveyance 매핑**: 알 수 없는 값 → 안전 기본 `"none"` + WARN.
- **하위호환 게이트**: scope 빈 키 존재 여부 쿼리 확인(§3.4).

## 7. 테스트 전략 (TDD)

- **단위**: conveyance 대문자→소문자 매핑(ENTERPRISE 포함), `ApiKeyScopeResolver`(경로→scope), rotation 서비스(scope 복제·grace expiresAt·만료키 거부), active 정의 통일.
- **슬라이스(`@WebMvcTest`)**: rotate 엔드포인트(평문 + oldKeyExpiresAt), 권한 게이팅.
- **필터/통합**: `ApiKeyAuthFilter` scope enforcement(보유 통과·미보유 403·빈 scope 403). ceremony start가 테넌트 값 반영(reg/auth start 응답 JSON의 timeout/UV/attestation = 테넌트 값).
- Oracle IT는 환경상 flaky → core 전체 + admin-app·passkey-app 단위/슬라이스로 게이트(그룹 A 정책).

## 8. 커밋 전 게이트

메모리 지침: 커밋 전 `/codex review`(6/1 quota 리셋 후 누적 diff) + code quality subagent. 특히 scope enforcement 경계(403 vs 401), rotation grace 병존, conveyance 매핑.

## 9. 구현 순서(권장)

1. P1-1 ceremony start 3곳 반영 + conveyance 헬퍼 + findByUserHandle cleanup (독립적, 스키마 무).
2. P1-5 scope: 하위호환 게이트 확인 → `ApiKeyScopeRepository` + `ApiKeyScopeResolver` + `ApiKeyAuthFilter` enforcement.
3. P1-5 rotation: `ApiKeyAdminService.rotate` + 컨트롤러 + active 정의 통일.
4. 통합 검증 + 게이트.
