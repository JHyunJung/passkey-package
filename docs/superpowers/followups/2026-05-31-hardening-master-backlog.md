# 코드베이스 하드닝 — 잔여 followups (마스터 백로그)

- **작성일**: 2026-05-31
- **근거**: `docs/superpowers/audit/2026-05-31-audit-full-report.json` (security/perf/quality 10-lens 심층 감사, 97 findings → 94 confirmed, adversarial 검증). 즉시 악용 critical/high 0건.
- **이번 머지(`8d4a403`)**: risk=none 중 가치 있는 **35건**을 6 Phase(QW-1~6)로 마감. spec `docs/superpowers/specs/2026-05-31-codebase-hardening-quick-wins-design.md`.
- **이 문서**: 머지 안 된 **59건**을 묶음별 백로그로. 각 묶음은 별도 brainstorm→spec→plan→구현 사이클 대상.

## 진행 규약 / 우선순위
1. **A·B 묶음은 결정 선행**: A(인프라 — actuator/보안헤더/forward-headers/CSRF 쿠키)는 **배포 토폴로지(리버스 프록시/LB) 확정** 후, B(risk=high — credential DELETE scope 403 정책변경 / TweaksPanel prod 렌더 / stub 핸들러)는 **동작·UI 변경 승인** 후 계획. 그 전에 계획하면 추측이 됨.
2. **C·G는 대부분 동작 보존** — 다음 하드닝 라운드 1순위 후보(at-rest 비밀 pepper, 입력검증, 로그 sanitize, 방어심화 주석).
3. **D는 성능** — 일부(apikey BCrypt 캐시)는 revocation TTL 결정 필요(risk=high 캐시와 연관). 인덱스/N+1/캐시 인프라는 SaaS 확장 시 가치 상승.
4. **E는 UI 영향 분리** — 코드 스플리팅/qrcode 동적 import/중복 fetch 정리는 외관 불변이나, 가상화/페이징은 외관 변경이라 별도.
5. **F는 일부 major 업그레이드** — `docs/superpowers/followups/2026-05-31-QW-3-dependency-decisions.md` 참조(nimbus 10.0.2 / vite 6 = major, 별도 phase).

## codex review
메모리 지침의 `/codex review`는 OpenAI quota(6/1 리셋)로 전 Phase 미실행 — Phase별 spec + code-quality subagent 2단계 + 최종 통합 리뷰로 게이트함. **6/1 후 누적 diff(`51b836e..8d4a403`)에 `/codex review` 재실행 권고**. 특히 QW-1·QW-2 보안 경계, QW-4 해시/서명/와이어 바이트 불변.

## A. 인프라 보안 하드닝 (배포 토폴로지 결정 필요) (7건)

- **`sec-ratelimit-ip-proxy-collapse`** [medium/security] — RateLimitFilter의 IP 버킷이 getRemoteAddr()만 사용 — SaaS(리버스 프록시/LB) 기본 배포에서 우회·DoS 증폭
  - 위치: `passkey-app/src/main/java/com/crosscert/passkey/app/security/RateLimitFilter.java:144, 158-165; pass`
  - 방향: 신뢰 프록시 모델을 명시적으로 고정: prod 에서 `server.forward-headers-strategy=NATIVE`(또는 Tomcat RemoteIpValve 의 internalProxies/trustedProxies 화이트리스트) 를 설정해 LB 가 부여한 XFF 의 신뢰 가능한 클라이언트 IP만 채택하게 하고, 그 위에서 getRemoteAdd
- **`sec-actuator-unauthenticated-exposure`** [medium/security] — actuator health(show-details: always)·prometheus 가 인증/레이트리밋 밖에서 노출
  - 위치: `core/src/main/resources/application-common.yml:32-35 (endpoints.web.exposure.include: health,info,pr`
  - 방향: actuator 를 별도 management.server.port(내부망/사이드카)로 분리하거나, 외부 노출 엔드포인트를 health(show-details: when-authorized 또는 never)로 축소하고 prometheus 는 인프라 내부 스크레이프 경로로만 제한. ALB/Ingress 레벨에서 /actuator 외부 차단도 가능. RP API
- **`sec-actuator-health-show-details-always`** [medium/security] — actuator health show-details: always + /actuator/health permitAll → 미인증 인프라 상태 노출
  - 위치: `core/src/main/resources/application-common.yml:33 (endpoint.health.show-details: always), admin-app/`
  - 방향: application-common.yml 의 show-details 를 'when-authorized' 로 낮추고, 미인증 노출은 health 그룹 요약(UP/DOWN)만 허용. 상세는 인증된 운영자(또는 actuator role)에만. info/prometheus 도 동일 정책 검토.
- **`sec-passkey-app-actuator-unauthenticated`** [medium/security] — passkey-app 에 Spring Security 체인 부재 → /actuator/prometheus, /actuator/health 가 완전 미인증
  - 위치: `passkey-app/build.gradle.kts:5-18 (starter-security 미선언), core/src/main/resources/application-common`
  - 방향: passkey-app 에 최소한의 SecurityFilterChain 추가(actuator 는 health 그룹 요약만 permitAll, prometheus/info 는 내부망/인증 한정) 하거나, management.server.port 를 별도 내부 전용 포트로 분리하고 외부 LB 에서 /actuator 차단. 또는 exposure.include 에서
- **`sec-passkey-app-no-security-filterchain-headers`** [low/security] — 공개 RP API(passkey-app)에 SecurityFilterChain 부재 → Spring 기본 보안 헤더(nosniff/frame-options 등)조차 미적용
  - 위치: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ (ApiKeyAuthFilter.java, RateLimitConfi`
  - 방향: passkey-app 에도 최소한의 SecurityFilterChain(권한은 permitAll 유지 — 인증은 ApiKeyAuthFilter 가 담당)을 추가해 기본 보안 헤더와 명시적 CSRF-disable(상태 없는 API 라 stateless 의도 명확화)을 적용. 또는 응답 헤더 추가 필터로 nosniff 만이라도 부여. API 동작/응답 본문 불
- **`sec-missing-security-headers-admin`** [low/security] — admin-app 에 HSTS/CSP 등 보안 헤더 미설정 (Spring 기본값만 존재, HSTS·CSP 없음)
  - 위치: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java:87-155 (no .head`
  - 방향: AdminSecurityConfig 에 `.headers(h -> h.httpStrictTransportSecurity(...).contentSecurityPolicy("default-src 'self'; ..."))` 추가. CSP 는 admin SPA 자산 로딩(현재 inline/eval 사용 여부 확인 후)에 맞춰 report-only 로 먼저 도입하
- **`sec-session-csrf-cookie-not-secure`** [low/security] — 세션/CSRF 쿠키에 Secure·SameSite 명시 부재 (prod yml·CookieCsrfTokenRepository 모두 미설정)
  - 위치: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java:82-83, admin-app`
  - 방향: prod 프로파일에 `server.servlet.session.cookie.secure=true`, `same-site=strict`(또는 lax) 추가, CookieCsrfTokenRepository 에 `setSecure(true)`(prod) 및 SameSite 설정. TLS 종단 환경에서 동작 불변, 비-TLS 로컬은 dev 프로파일로 분리.

## B. risk=high (동작/UI 변경 — 사용자 결정 필요) (3건)

- **`sec-credential-delete-scope-shared`** [low/security] — 파괴적 self-service DELETE 가 비파괴적 registration 과 동일 scope 로 인가됨
  - 위치: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyScopeResolver.java:27; passkey-ap`
  - 방향: 파괴적 작업(DELETE)을 별도 scope(예: credential-management 또는 credential-delete)로 분리해 최소권한 원칙 적용. 다만 이는 scope enum/매핑과 기존 키 권한 모델을 바꾸므로 기존 통합의 동작이 깨질 수 있다(현재 registration 키로 삭제되던 흐름이 403). 따라서 정책 변경으로 취급하고, 단기
- **`cq-as-any-tweaks`** [low/code-quality] — TweaksPanel 의 `as any` 타입 약화 및 dev 전용 패널이 프로덕션에 무조건 렌더
  - 위치: `src/tweaks/TweaksPanel.tsx:125,136, src/App.tsx:165-202`
  - 방향: as any 대신 `interface DeckStageEl extends Element { _railEnabled?: boolean }` 로 좁히기(동작 불변). 패널은 `import.meta.env.DEV` 가드로 프로덕션 빌드에서 제외 — 단 이는 프로덕션 UI에서 트윅 버튼을 없애므로 riskToBehaviorOrUI=high.
- **`cq-dead-stub-handlers`** [low/code-quality] — 비기능 stub 핸들러가 동작하는 UI 컨트롤에 연결됨 (handleSwitchRole 빈 함수, IdleSessionModal onExtend no-op)
  - 위치: `src/App.tsx:107-109,113,163, src/shell/Header.tsx:34, src/extras/CommandPalette.tsx`
  - 방향: 미완성 동작이 연결된 컨트롤은 (a) 비활성/숨김 처리하거나 (b) 실제 구현 연결. onExtend 는 reloadMe 를 prop으로 내려 실제 /me 재조회 연결(동작 추가). 컨트롤 숨김은 외관 변경(high), 핸들러 실제 구현은 동작 변경.

## C. at-rest 비밀 + 입력검증 (대부분 동작 보존) (8건)

- **`sec-recoverycode-hash-no-peruser-salt`** [low/security] — recovery code를 솔트 없는 단일 SHA-256으로 저장 — DB 유출 시 사전공격 가속
  - 위치: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/RecoveryCodeService.java:48,56, admin-app/s`
  - 방향: 동작·UI 불변 범위에서: recovery code 엔트로피를 늘리거나(예: 길이↑) HMAC-SHA256(서버 pepper, KeyEnvelope master-key 파생)으로 저장해 DB-only 유출 시 오프라인 공격을 차단. 단 저장형식 변경은 마이그레이션 필요 → 신규 발급분부터 적용하는 점진 경로 권장.
- **`sec-pwreset-token-prefix-logged`** [low/security] — 비밀번호 재설정 토큰의 prefix 8자가 로그·DB에 기록됨 (부분 토큰 노출)
  - 위치: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/PasswordResetService.java:67-68,82,103-`
  - 방향: prefix 길이를 'rst_'를 제외한 hex 기준으로 잡거나, 로그에는 token_hash의 처음 8자(되돌릴 수 없는 식별자)를 쓰도록 변경. 토큰 발급/검증 동작은 불변.
- **`sec-cors-allowlist-no-origin-validation`** [medium/security] — CORS allowlist 입력 검증이 origin 형식을 강제하지 않음 — '*'/와일드카드/잘못된 origin 저장 가능, allowCredentials=true 와 결합
  - 위치: `admin-app/src/main/java/com/crosscert/passkey/admin/policy/SecurityPolicyDto.java:31, admin-app/src/`
  - 방향: UpdateRequest.corsAllowlist 항목에 origin 형식 검증(@Pattern 또는 커스텀 validator: `^https?://[^/]+$`, 가급적 prod 는 https 강제, `*` 금지)을 추가하고, 서비스 저장 시 정규화(trailing slash/공백 제거). setAllowedOriginPatterns 가 아닌 setAll
- **`sec-password-reset-no-bean-validation`** [low/security] — PasswordResetController.confirm 본문에 @Valid/제약 부재 — 비밀번호 정책 검증을 전적으로 서비스에 의존
  - 위치: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/PasswordResetController.java:36-43`
  - 방향: ConfirmBody/RequestBodyDto 에 @NotBlank, @Size(max=...) (newPassword 는 최소·최대 길이) 등 제약 추가하고 컨트롤러에 @Valid 부여. GlobalExceptionHandler 가 이미 MethodArgumentNotValid→400 처리하므로 응답 형태 일관. 정상 입력 동작 불변.
- **`sec-pwreset-confirm-enumeration-timing`** [low/security] — password reset confirm의 오류 메시지가 토큰 유효성 단계를 구분해 노출
  - 위치: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/PasswordResetService.java:86-96`
  - 방향: confirm 실패를 단일 generic 메시지('유효하지 않거나 만료된 토큰')로 통일해 상태 oracle 제거. 클라이언트 UX 문구만 일반화 — 정상 흐름 동작 불변.
- **`sec-illegalarg-message-leak`** [low/security] — GlobalExceptionHandler 가 IllegalArgumentException/BusinessException 메시지를 클라이언트에 그대로 반환 — 내부 정보 누출 가능
  - 위치: `core/src/main/java/com/crosscert/passkey/core/api/GlobalExceptionHandler.java:116-121, 26-33, 95-114`
  - 방향: handleIllegalArg 는 고정 일반 메시지(예: ErrorCode.INVALID_INPUT 의 기본 메시지)만 반환하고 상세는 로그로만 남기도록 변경. BusinessException 은 통제된 메시지이므로 유지 가능. 정상 4xx 본문 형태는 유지되어 동작 영향 최소.
- **`sec-trace-id-log-injection`** [medium/security] — 클라이언트 제어 X-Trace-Id 헤더가 무해독(sanitize) 없이 MDC/로그/응답에 반영 → 로그 인젝션/로그 위조
  - 위치: `core/src/main/java/com/crosscert/passkey/core/api/TraceIdFilter.java:27-31, core/src/main/resources/`
  - 방향: 수신한 X-Trace-Id 를 신뢰하기 전에 정규식 화이트리스트(예: 영숫자+하이픈, 최대 32~64자)로 검증하고, 불일치 시 서버 생성 UUID 로 대체. 최소한 MDC/헤더에 넣기 전에 CR/LF/제어문자를 제거. (정상 traceId 형식은 16-hex 이므로 기존 동작 불변.)
- **`sec-idtoken-no-aud-iss-validation`** [medium/security] — SDK IdTokenVerifier가 aud/iss를 검증하지 않음 — 토큰 오용(audience confusion) 가능
  - 위치: `sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifier.java:29-69, sdk-java/src/ma`
  - 방향: PasskeyClientConfig에 expectedIssuer/expectedAudience를 받아 verify() 말미에 c.getIssuer() 일치 및 c.getAudience().contains(expectedAud)를 강제. 하위호환을 위해 미설정 시 경고 로그 + 옵트인 엄격모드. SDK 신규 파라미터라 기존 동작은 디폴트로 보존 가능.

## D. 백엔드 성능 (DB/캐싱 — 일부 캐시 TTL 결정) (17건)

- **`perf-apikey-bcrypt-no-cache`** [medium/performance] — 매 RP 요청마다 BCrypt(cost=12) 검증 — 인증 캐시 부재 (핫경로 최대 비용)
  - 위치: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java:127,138,145; core`
  - 방향: 검증 성공한 (prefix→{keyHash 검증 결과, tenantId, scopes, active 여부}) 를 짧은 TTL(예 30s) 로 캐시(Caffeine 또는 이미 있는 Redis). 캐시 키는 prefix+secret 해시(SHA-256) 로 하여 평문 secret 비교를 메모리에서 상수시간 수행하고 BCrypt 는 캐시 미스에만. revoke 
- **`perf-per-request-pl-sql-roundtrips`** [medium/performance] — RP 요청당 DB 커넥션 3회 차용 + 각 차용마다 set/clear_tenant PL/SQL 왕복
  - 위치: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyLookupService.java:46-91; core/sr`
  - 방향: (a) touchLastUsed 를 @Async 또는 배치(예 prefix 별 마지막 사용시각을 메모리 집계 후 주기 flush)로 비동기화 — 인증 응답 경로에서 제거. (b) findByPrefix 직후 동일 트랜잭션/커넥션 안에서 scope 까지 한 번에 조회(단일 PL/SQL 또는 조인)해 차용 횟수 축소. (c) wrap() 의 무조건 clear_
- **`perf-bcrypt-on-every-invalid-request`** [info/performance] — 모든 무효/미지 prefix 요청마다 BCrypt(cost 12) 1회 — IP 버킷 무력화 시 CPU 증폭
  - 위치: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java:127, 138, 145`
  - 방향: 근본 원인은 IP 식별(첫 finding)이므로 forward-headers 신뢰 프록시 설정으로 레이트리밋이 실효되게 하는 것이 1차. 추가로 미지 prefix 의 더미 BCrypt 는 유지하되 IP 버킷 정상화로 진입 자체를 차단. 동작/UI 변경 없음.
- **`perf-audit-chain-fullscan`** [medium/performance] — audit chain overview/verify 가 audit_log 전체를 페이지네이션 없이 메모리 로드 + 테넌트당 중복 스캔
  - 위치: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainMonitorController.java:49-84, ad`
  - 방향: 최소 침습: 컨트롤러의 중복 findAllByTenantOrdered 제거 — verifier 가 이미 스캔하므로 검증 결과에 rowCount/시간버킷 집계를 같이 반환하게 해 재스캔을 없앤다(동작/응답 형태는 유지하되 내부 1패스). 근본 개선: 검증을 스트리밍/배치(예: id 범위 커서)로 처리하거나 마지막 검증 지점 이후 증분 검증. overview 
- **`perf-apikey-list-findall-memfilter-n1`** [medium/performance] — ApiKeyAdminService.list() findAll() 후 메모리 필터 + scopes lazy 컬렉션 N+1
  - 위치: `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java:72-84, admin-app/`
  - 방향: findActiveByTenantId 처럼 tenant 스코프를 WHERE 로 내리는 repository 메서드(findByTenantId(Pageable))를 쓰고, scopes 는 `@EntityGraph` 또는 `join fetch` 로 한 번에 로드하거나 default_batch_fetch_size 로 IN 배치 로드. 반환 DTO/필드 그대로 유지
- **`perf-tenant-toview-n1`** [medium/performance] — TenantAdminService.list() — 테넌트당 KPI 집계 3쿼리 N+1 (코드 주석도 인지)
  - 위치: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java:72-108`
  - 방향: KPI 3종을 테넌트별 GROUP BY 단일 쿼리(또는 3개의 묶음 집계 쿼리)로 한 번에 가져와 Map 으로 조인. 또는 대시보드 KPI 캐싱. TenantView 필드/응답 형태는 유지하므로 UI 불변. 단기적으로는 perf-apikey-no-tenant-index 인덱스만 추가해도 체감 개선.
- **`perf-credential-search-rawtohex-like`** [medium/performance] — credential 검색이 RAWTOHEX(...) LIKE '%q%' — 인덱스 불가, 풀스캔 + 함수 평가
  - 위치: `core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java:54-72, admin-app/`
  - 방향: 동작/UI 유지가 제약이므로 신중히: (a) 정확/접두 매칭이 허용되면 LIKE 'q%' 로 바꿔 function-based index(LOWER(RAWTOHEX(...)))를 태운다 — 단 substring 검색 동작이 바뀌므로 high 위험, (b) 동작 보존하려면 credential_id/user_handle 의 hex 를 별도 정규화 컬럼+funct
- **`perf-admin-findall-then-filter`** [low/performance] — admin list 경로가 VPD-exempt findAll() 후 Java에서 테넌트 필터링 — 전 테넌트 행 메모리 적재 + N+1 집계
  - 위치: `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java:74-84, admin-app/`
  - 방향: ApiKeyAdminService.list()는 scopeTid 존재 시 repo.findByTenantId(scopeTid)로 DB에서 필터링; TenantAdminService.list()의 KPI 3종은 단일 GROUP BY 집계 쿼리 또는 대시보드 캐시로 N+1 제거. 반환 데이터/동작 불변(필터 결과 동일).
- **`perf-aaguid-policy-per-registration-db`** [low/performance] — 등록마다 AAGUID 정책 DB 조회 + 정책 entries Set 재구성 (캐시 없음)
  - 위치: `core/src/main/java/com/crosscert/passkey/core/policy/DefaultAaguidPolicyChecker.java:51,80-82`
  - 방향: 테넌트별 정책을 @Cacheable(짧은 TTL) 또는 명시적 무효화(정책 수정 시 evict) 로 캐시. entrySet 도 정책 로드 시 1회 계산해 보관. 검증 결과 의미는 동일, 변경 반영 지연만 TTL 만큼.
- **`perf-no-caching-infra`** [low/performance] — 애플리케이션 전역 캐싱 인프라 부재 (@EnableCaching/CacheManager 없음)
  - 위치: `core/src/main/java (전역: @EnableCaching/@Cacheable/CacheManager grep 결과 0건)`
  - 방향: Caffeine 기반 로컬 캐시 또는 기존 Redis 를 read-through 로 활용해 테넌트 설정/AAGUID 정책/API 키 검증을 TTL+이벤트 무효화로 캐시. 도입은 조회 결과를 바꾸지 않으므로 외부 동작·UI 불변(무효화 누락만 주의).
- **`perf-no-jdbc-batch`** [low/performance] — Hibernate JDBC 배치/insert-update 정렬 미설정 — 멀티 INSERT/UPDATE 가 라운드트립마다 1쿼리
  - 위치: `core/src/main/resources/application-common.yml:8-24`
  - 방향: application-common.yml 의 spring.jpa.properties 에 hibernate.jdbc.batch_size: 30~50, hibernate.order_inserts: true, hibernate.order_updates: true, hibernate.default_batch_fetch_size: 16~32 추가. 설정만 추가하므로
- **`perf-redis-two-roundtrips-ratelimit`** [low/performance] — rate-limit 요청당 Redis 왕복 2회 + ProxyManager bucket 빌드 2회
  - 위치: `passkey-app/src/main/java/com/crosscert/passkey/app/security/RateLimitFilter.java:144-168`
  - 방향: 두 버킷을 단일 Lua/배치로 합쳐 1 RTT 로 줄이는 방안 검토(Bucket4j 의 다중 밴드 또는 파이프라이닝). 또는 정상 트래픽 대부분이 한도 내인 점을 이용해 로컬 토큰 선차감 후 주기 동기화하는 하이브리드(정확도 trade-off). 한도 의미를 바꾸지 않는 범위에서만. 동작 변경 위험이 있어 신중 검토 필요.
- **`perf-invitation-mail-sync-in-tx`** [low/performance] — 초대/비밀번호재설정: @Transactional 내부에서 동기 mail.send 호출
  - 위치: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/InvitationService.java:49-70`
  - 방향: 메일 발송을 트랜잭션 커밋 이후(@TransactionalEventListener(AFTER_COMMIT)) 또는 @Async 로 분리해 DB 트랜잭션과 디커플. UI/응답 의미 동일(메일은 이미 best-effort).
- **`perf-secret-masking-six-regex-per-log`** [low/performance] — 모든 로그 라인마다 정규식 6회 matcher+replaceAll (CONSOLE 출력 핫경로)
  - 위치: `core/src/main/java/com/crosscert/passkey/core/logging/SecretMaskingConverter.java:42-60`
  - 방향: 빠른 사전 필터(예 msg.indexOf('pk_')<0 && indexOf('eyJ')<0 && contains('password')==false && indexOf("$2")<0 면 즉시 원본 반환) 로 secret 후보가 없는 라인은 정규식을 모두 건너뛰게 단락(short-circuit). 마스킹 결과는 불변(후보 없는 라인은 어차피 치환 0건). 동
- **`perf-license-recompute-every-request`** [info/performance] — onprem 모드: 매 요청마다 LicenseStateMachine.recompute() (synchronized)
  - 위치: `core/src/main/java/com/crosscert/passkey/core/license/LicenseGuardFilter.java:70-71; core/src/main/j`
  - 방향: recompute 를 매 요청이 아니라 짧은 주기(예 1s) throttle 하거나, state 를 volatile 로 읽고 변경 감지 시에만 synchronized 재계산. 의미상 상태 전이 가시성은 거의 동일하게 유지. saas 영향 없음.
- **`perf-cors-source-no-negative-cache`** [info/performance] — DynamicCorsConfigurationSource: allowlist 비어있을 때(가장 흔한 same-origin 케이스) 매 만료마다 DB 재조회 — 부정 결과 캐시는 되나 빈 목록 반복 파싱
  - 위치: `admin-app/src/main/java/com/crosscert/passkey/admin/policy/DynamicCorsConfigurationSource.java:66-89`
  - 방향: 필요 시 갱신을 단일 스레드로 직렬화(예: AtomicReference + compareAndSet 가드 또는 비동기 refresh)하여 만료 순간 중복 읽기를 제거. 현재도 정확성/동작에는 문제 없으므로 선택적 최적화. 동작/UI 불변.
- **`perf-alert-executor-bound`** [info/performance] — 보안 알림 executor 고정 2스레드/큐100 — 포화 시 무음 드롭
  - 위치: `core/src/main/java/com/crosscert/passkey/core/alert/AlertConfig.java:14-28; passkey-app .../ApiKeyAu`
  - 방향: 이벤트 유형별 디듀프/레이트 제한(동일 prefix 의 bad-secret 은 N초당 1건만 발행) 으로 폭주 시 큐 압력을 근본 완화. mail 채널은 타임아웃 설정 필수. 요청 경로 동작 불변.

## E. admin-ui 성능/품질 (일부 UI 영향) (13건)

- **`perf-no-code-splitting`** [medium/performance] — 코드 스플리팅 부재 — 전 페이지를 단일 372KB 청크로 정적 import
  - 위치: `admin-ui/vite.config.ts:13-17, admin-ui/src/App.tsx:4-22`
  - 방향: App.tsx 의 라우트 컴포넌트들을 React.lazy + Suspense fallback 으로 변환하거나, 최소한 vite build.rollupOptions.output.manualChunks 로 vendor(react/react-dom/react-router) 와 qrcode 를 분리. 라우트는 동일 컴포넌트를 그대로 렌더하므로 UI/흐름 불변(Su
- **`perf-qrcode-eager-bundle`** [low/performance] — qrcode 라이브러리가 메인 번들에 eager 포함 — 사용처는 AccountTab MFA 등록 1곳뿐
  - 위치: `admin-ui/src/shell/QrCode.tsx:2, admin-ui/src/pages/settings/AccountTab.tsx:3,126`
  - 방향: QrCode.tsx 내부에서 useEffect 시점에 `import('qrcode')` 동적 로드로 전환하거나, AccountTab 을 React.lazy 로 분리. 외관/동작 동일(QR 은 그대로 렌더, 약간의 첫 렌더 지연만 추가).
- **`perf-no-list-virtualization`** [low/performance] — 대량 행 테이블에 가상화 부재 — Credentials/Audit(페이지당 50) + AuditChain(전 tenant) 전부 DOM 렌더
  - 위치: `admin-ui/src/pages/tenant/CredentialsTab.tsx:205-238, admin-ui/src/pages/tenant/AuditTab.tsx:486-535`
  - 방향: 행 수가 큰 테이블(AuditChain 전 tenant, Activity 누적 피드)에 한해 react-window/virtuoso 도입 또는 AuditChain 에 클라이언트 페이징 추가. 단 행 마크업/레이아웃을 보존해야 외관 불변 — 가상화 컨테이너 도입은 스크롤 동작이 미묘하게 바뀔 수 있어 riskToBehaviorOrUI=low.
- **`perf-command-palette-no-debounce`** [low/performance] — CommandPalette 검색이 키 입력마다 searchAll 호출 (디바운스 없음)
  - 위치: `admin-ui/src/extras/CommandPalette.tsx:33-39, admin-ui/src/lib/search.ts:24-34`
  - 방향: q → searchAll 호출에 setTimeout 기반 디바운스(또는 기존 cancelled 패턴에 지연 추가). 검색 결과/표시 동일, 입력 반응만 약간 지연.
- **`perf-render-blocking-external-css`** [low/performance] — index.html 의 외부 폰트 CSS 2종이 렌더 블로킹 (jsdelivr + Google Fonts)
  - 위치: `admin-ui/index.html:8-16`
  - 방향: 폰트를 npm/self-host 로 번들에 포함하거나 link 를 preload+비차단 swap 패턴으로. self-host 가 가장 깔끔하나 FOUT 가능성으로 riskToBehaviorOrUI=low(폰트 로딩 순간의 깜빡임만).
- **`perf-license-triple-fetch`** [low/performance] — /admin/api/license 가 3개 컴포넌트에서 독립 중복 호출 (공유 캐시 없음)
  - 위치: `admin-ui/src/components/LicenseBanner.tsx:16, admin-ui/src/shell/Sidebar.tsx:90, admin-ui/src/pages/`
  - 방향: search.ts 의 tenantCache 패턴처럼 license.ts 에 짧은 TTL 모듈 캐시를 추가하거나, App 루트에 LicenseProvider 를 두고 3 컴포넌트가 구독. 데이터·UI 동일(같은 값이 같은 곳에 표시).
- **`perf-chain-overview-duplicate-fetch`** [low/performance] — audit-chain overview 가 Sidebar(30s 폴링) + AuditChainPage(마운트)에서 중복 호출
  - 위치: `admin-ui/src/shell/Sidebar.tsx:111-120, admin-ui/src/pages/AuditChainPage.tsx:161-175`
  - 방향: overview 결과를 짧은 TTL 모듈 캐시 또는 context 로 공유해 Sidebar/AuditChainPage 가 동일 fetch 를 재사용. 폴링 주기/배지 동작은 그대로 유지(동작·외관 불변).
- **`perf-idle-modal-listener-rebind`** [info/performance] — IdleSessionModal effect 가 open 의존 — 모달 토글마다 window 리스너 4종 재바인딩
  - 위치: `admin-ui/src/extras/IdleSessionModal.tsx:15-30`
  - 방향: openRef 를 useRef 로 두고 핸들러에서 openRef.current 참조, effect 의존을 [] 로. 타이머 동작/모달 표시 동일.
- **`cq-dup-date-helpers`** [medium/code-quality] — timeAgo/fmtDateTime/tail/fmt 헬퍼가 7~10개 파일에 복붙 중복 (중앙 lib는 미사용 dead code)
  - 위치: `src/lib/formatDateTime.ts:18, src/pages/tenant/AuditTab.tsx:12-47, src/pages/tenant/CredentialsTab.t`
  - 방향: 이미 존재하는 src/lib/formatDateTime.ts 로 timeAgo/fmtDateTime/tail/fmt 를 통합(lib에 timeAgo/tail/formatNumber 추가)하고 각 페이지의 로컬 정의를 제거 후 import. 단, AuditTab의 'second 미포함' 표기를 유지할지 결정 필요(외관 보존하려면 옵션 파라미터로). 순수 내부
- **`cq-large-components`** [low/code-quality] — 거대 컴포넌트 — AdminUsersTab(712), AuditTab(551), ActivityPage(541) 단일 파일에 다중 책임
  - 위치: `src/pages/settings/AdminUsersTab.tsx:1-712, src/pages/tenant/AuditTab.tsx:1-551, src/pages/ActivityP`
  - 방향: NewAdminDialog/InvitationModal 을 별 파일로 추출, 공통 유틸은 lib 통합(finding cq-dup-date-helpers와 연계). 동형 탭은 공유 DataTable/DetailDrawer 컴포넌트로 추출 검토. 순수 파일 분리/추출이면 렌더 출력 동일 — 동작/외관 불변.
- **`cq-event-type-classification-split`** [low/code-quality] — 이벤트 타입 분류/색상 로직이 ActivityPage 와 AuditTab 에 서로 다르게 이원화
  - 위치: `src/pages/ActivityPage.tsx:59-103 (MUTATION_TYPES/FAILURE_TYPES/EventTypeBadge), src/pages/tenant/Au`
  - 방향: 이벤트타입→{category,color,label} 단일 매핑을 src/api 또는 lib 에 정의하고 두 EventTypeBadge 가 이를 참조. 매핑 값을 현행과 동일하게 유지하면 색/외관 불변, 단일화만 달성.
- **`cq-fixture-as-initial-kpi`** [low/code-quality] — ActivityPage 가 가짜 fixture KPI/topTenants 를 초기 state로 사용 — 서버가 KPI 미반환 시 가짜 수치 노출 위험
  - 위치: `src/pages/ActivityPage.tsx:5,162-163,186-192, src/fixtures/activity.ts`
  - 방향: KPI 초기값을 fixture 대신 null/로딩 플레이스홀더로 두고, 서버 미반환 필드는 '—' 표시. fixture 는 스토리북/테스트 전용으로 격리. 표시 텍스트가 바뀌므로 외관에 미세 영향(로딩 전 placeholder) — 게이팅 로직만 바꾸면 흐름 불변.
- **`cq-missing-catch-search-clipboard`** [info/code-quality] — 에러 처리 누락: CommandPalette searchAll().then() 과 clipboard.writeText().then() 에 .catch 없음
  - 위치: `src/extras/CommandPalette.tsx:36, src/pages/settings/AdminUsersTab.tsx:576, src/pages/LoginPage.tsx:`
  - 방향: searchAll 체인에 `.catch(() => setSearchResults([]))` 추가. clipboard 에 `.catch(() => toast/err 표시)` 추가(실패 시 사용자 안내). 모두 에러 경로만 추가하므로 정상 동작·외관 불변.

## F. 의존성/빌드 위생 (5건)

- **`qual-flyway-version-pin-dead`** [medium/code-quality] — 선언된 flyway 10.20.1 핀이 Boot BOM 에 의해 11.7.2 로 무시됨 (버전 불일치/오해 소지)
  - 위치: `gradle/libs.versions.toml:6 (flyway = "10.20.1"), core/build.gradle.kts:21-22 (flyway-core/flyway-or`
  - 방향: 둘 중 하나로 일원화: (a) toml 핀을 실제 해석값(11.7.2 / 23.7.0)으로 갱신해 문서와 빌드를 일치시키거나, (b) dependencyManagement 에서 Flyway/ojdbc 를 명시 override 해 toml 핀을 강제 적용. 어느 쪽이든 '선언=해석' 이 되도록.
- **`qual-dual-jackson-lineage-classpath`** [low/code-quality] — Jackson 2(2.21.2)와 Jackson 3(3.1.2) 두 메이저 라인이 같은 클래스패스에 공존
  - 위치: `build.gradle.kts:33-42 (jackson-annotations 2.21 forward override 주석), 실제 admin-app.jar 내 jackson-da`
  - 방향: webauthn4j 가 Jackson 3 로 이전한 경위를 추적해, 가능하면 단일 라인으로 수렴(앱 전반 Jackson 3 채택 또는 webauthn4j 버전을 Jackson 2 호환판으로). 당장은 jackson-annotations override 를 BOM 검증 테스트로 가드해 회귀 감지.
- **`sec-pdfbox-outdated-transitive`** [low/security] — openhtmltopdf 1.0.10 가 끌어오는 pdfbox/fontbox 2.0.24 (2021년판, 구버전)
  - 위치: `admin-app/build.gradle.kts:12-13 (openhtmltopdf 1.0.10), 실제 admin-app.jar 내 pdfbox-2.0.24.jar / font`
  - 방향: dependencyManagement 에서 org.apache.pdfbox:pdfbox / fontbox 를 최신 2.0.x 안정판으로 forward override(openhtmltopdf 1.0.10 과의 호환 확인 후). CVE 매핑은 별도 dependency-check(OWASP)로 확정 권장.
- **`cq-uuid-bytes-dup`** [low/code-quality] — UUID ↔ byte[16] 변환 로직이 5개 파일에 중복 — CryptoUtils 통합 패턴 미적용
  - 위치: `core/src/main/java/com/crosscert/passkey/core/jwt/IdTokenIssuer.java:81-86; core/src/main/java/com/c`
  - 방향: CryptoUtils 에 `uuidToBytes(UUID)` / `uuidFromBytes(byte[])`(null/len!=16 가드 포함) 를 추가하고 5개 호출부를 위임하도록 치환. 순수 함수 통합이라 출력 바이트가 동일 — 동작/외관 불변.
- **`cq-idtoken-ttl-hardcoded`** [low/code-quality] — AuthenticationFinishService 가 JWT TTL 900초를 하드코딩 — IdTokenIssuer 의 설정값(passkey.id-token.ttl)과 desync
  - 위치: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService`
  - 방향: IdTokenIssuer 가 발급한 토큰의 실제 만료초(또는 tokenTtl.toSeconds())를 반환하도록 issue() 시그니처를 확장하거나 getter 를 추가하고, AuthenticationFinishService 가 그 값을 expiresIn 과 로그에 사용하도록 한다. 리터럴 900 제거. 동작 자체(발급되는 JWT)는 불변이며 응답의 exp

## G. info/주석 + 방어심화 (6건)

- **`quality-mfacipher-v2-prefix-collision-comment-only`** [low/code-quality] — MfaSecretCipher: legacy 평문 판별이 프리픽스 부재에만 의존 — 미래 v2 충돌 위험이 주석으로만 관리
  - 위치: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaSecretCipher.java:39-49`
  - 방향: 정적 'enc:' 공통 프리픽스로 1차 분기 후 버전 토큰을 파싱하는 구조로 리팩터(평문은 'enc:' 미보유로만 판정). v1 입출력 동작은 불변. 또는 최소한 알려진 모든 'enc:vN:' 후보를 검사하는 가드 추가.
- **`sec-timing-unknown-prefix-residual-oracle`** [info/security] — unknown-prefix 경로의 잔존 타이밍 오라클(DB 조회는 수행, revoked/expired 와 분기 위치 상이)
  - 위치: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java:123-155`
  - 방향: DUMMY_HASH 의 cost factor 를 실제 발급 정책과 동일하게 고정(이미 12라면 발급 cost 도 12로 보장)하고, 메트릭 카디널리티를 외부 노출에서 제외(앞 actuator finding 과 함께). 동작/UI 변경 없음(관측 가능성만 균질화).
- **`sec-scope-query-shape-vpd-dependency`** [info/security] — scope 조회가 TenantContext 설정 후 VPD 의존 — fail-closed 의도지만 scopeRepo 가 cross-tenant 격리를 전적으로 VPD에 위임
  - 위치: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java:157-176; passkey-`
  - 방향: 방어 심화로 scopeRepo 쿼리에 명시적 tenant_id = :tenantId 술어를 추가(VPD 와 이중화). 결과 동일(정상 키는 같은 테넌트)이라 동작/UI 변경 없음.
- **`quality-webauthn-nonstrict-manager-implicit`** [info/code-quality] — createNonStrictWebAuthnManager 사용이 명시 문서화/테넌트 정책 연동 없이 전역 고정
  - 위치: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/Webauthn4jConfig.java:10-13`
  - 방향: Webauthn4jConfig 에 non-strict 선택 사유와 trust 보강 책임(MDS/AAGUID/format)이 어디인지 주석으로 명시. 동작 변경 없음. (strict 전환은 동작 변경이라 별도 검토 — 여기서는 문서화만.)
- **`qual-mfapending-requesturi-inconsistency`** [low/code-quality] — MfaPendingFilter가 getRequestURI() 사용 — 다른 보안 필터의 getServletPath() 규약과 불일치(주석과도 모순)
  - 위치: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaPendingFilter.java:73-79`
  - 방향: getRequestURI()를 getServletPath()로 교체해 다른 보안 필터와 규약을 통일하고 javadoc과 일치시킨다. 루트 context-path(기본) 배포에서는 동작 불변.
- **`sec-key-rotation-no-auto-schedule-in-scope`** [info/security] — signing key 자동 회전 스케줄러 부재 — 수동 회전에만 의존(정보성)
  - 위치: `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java:84-126, core/src`
  - 방향: 확인만 권고: 자동 회전 스케줄(예: SchedulerLeaseService 기반 주기 잡)이 다른 모듈에 존재하는지 검증. 없다면 정책상 max key age 도달 시 회전을 트리거하는 잡 추가 검토(동작 변경 수반 → 별도 결정).
