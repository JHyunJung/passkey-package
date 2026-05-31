# 코드베이스 하드닝 Quick Wins (risk=none 감사 findings) — Design

- **작성일**: 2026-05-31
- **대상**: Crosscert Passkey Platform (core / admin-app / passkey-app / admin-ui / sdk-java)
- **근거**: `docs/superpowers/audit/2026-05-31-audit-risk-none-findings.json` — security/performance/code-quality 3축 심층 감사(10 lens, 97 findings, 94 confirmed, adversarial 검증). 즉시 악용 가능한 critical/high 0건. 본 spec은 그중 **riskToBehaviorOrUI=none 41건**을 대상으로 한다.
- **성격**: 구현 설계. 후속 writing-plans의 입력물.

## 1. 목적 / 범위

심층 감사에서 확정된 **동작·UI 무변경(risk=none) 개선 41건**을 안전하게 닫는다. 전부 defense-in-depth / 위생 / SaaS 확장 대비 성격이며, **응답 계약·해시 출력·admin-ui 표시값·사용자 흐름을 바이트 단위로 보존**한다.

**핵심 제약(불변식)**: 기존 기능 동작과 admin-ui UI/UX 디자인(레이아웃/컴포넌트 외관/사용자 흐름)을 바꾸지 않는다. 각 Task는 이 불변식을 검증 단계에서 명시적으로 확인한다.

**범위 밖(deferred → followups)**:
- **risk=high 3건**(사용자 결정으로 전부 백로그): credential 파괴적 DELETE scope 분리(403 정책 변경), API key 인증 결과 캐시 TTL(revocation 즉시성 손해), admin CSP/HSTS literal(폰트 차단으로 외관 변경).
- **risk=low 50건**: actuator 노출 축소·forward-headers 신뢰프록시·보안헤더 등 다수가 배포 토폴로지 의존 또는 응답헤더 추가라 별도 판단 필요. 본 spec 제외.
- **의존성 CVE 후보**(nimbus 9.40, vite/esbuild): `confidence=low`("버전 확인 필요")라 Phase QW-3에서 **실제 advisory 검증 후** 범프 여부를 결정하는 Task로만 포함(무검증 범프 금지).

## 2. Phase 구성

41건을 응집도 + 검증 단위로 5개 Phase로 나눈다. 각 Phase는 독립 빌드·테스트·머지 가능.

| Phase | 축 | 대상 | 모듈 |
|---|---|---|---|
| **QW-1** Cross-tenant 회귀 방지 | security | boundary self-guard + 방어적 clear + ArchUnit | core, admin-app, passkey-app |
| **QW-2** RP_ADMIN 게이팅 + @PreAuthorize 정합 | security | GET 권한 축소 + list 게이팅 명시 | admin-app |
| **QW-3** DB/JPA 성능 + 의존성 검증 | performance | api_key 인덱스, N+1 집계, Hibernate batch, CVE 검증 | core, admin-app, gradle |
| **QW-4** 백엔드 순수 위생 | perf+quality | HexFormat, RSA keygen 통합, Converter 재사용, Clock, ProblemJson, 로깅 | core, passkey-app, admin-app |
| **QW-5** admin-ui 코드 위생 | perf+quality | useMemo, dead code 제거, ESLint, 폴링 가드 | admin-ui |

## 3. QW-1 — Cross-tenant 회귀 방지 (security)

admin-app은 VPD-EXEMPT(`APP_ADMIN_USER`)로 접속하므로 cross-tenant 격리가 `TenantBoundary` 단일 app-layer 계층에만 의존한다(`sec-admin-vpd-exempt-sole-layer`). 한 줄 누락 회귀를 막는다.

### 3.1 WebauthnDiffService self-guard (`sec-webauthndiff-no-boundary-fragile`)
- `admin-app/.../tenant/WebauthnDiffService.java`의 `diff(tenantId, ...)` 첫 줄에 `tenantBoundary.assertCanAccessTenant(tenantId)` 추가.
- **불변식**: 현재 호출 경로(`TenantAdminController.diff` → `service.get()`)는 이미 boundary를 거치므로 정상 동작 동일. cross-tenant 직접 호출만 차단(방어 강화).
- `WebauthnDiffService`에 `TenantBoundary` 의존성이 없으면 생성자 주입 추가.

### 3.2 ApiKeyAuthFilter 방어적 clear (`sec-apikeyfilter-no-defensive-clear`)
- `passkey-app/.../security/ApiKeyAuthFilter.java`의 `doFilterInternal` 진입 직후 `TenantContextHolder.clear()` 추가(`DevTenantHeaderFilter.java:55-59`와 동일 패턴/주석).
- **불변식**: 정상 요청은 인증 성공 시 항상 `set(tenantId)`로 덮으므로 동작 동일. stale ThreadLocal 누출만 원천 차단. 성공 경로 `finally`의 기존 clear는 유지.

### 3.3 ArchUnit boundary 강제 (`sec-admin-vpd-exempt-sole-layer`)
- admin-app 테스트에 ArchUnit 규칙 신규: RP_ADMIN 도달 가능한 admin 서비스의 tenant-scoped 조회/변경 메서드가 `TenantBoundary` 호출(또는 `currentTenantScope`/`assertCanAccessTenant`)을 거치도록 강제. 구현 현실에 맞춰 규칙 범위를 명확히 한정(전수 강제가 거짓양성을 내면 명시적 화이트리스트 + 주석).
- ArchUnit 의존성이 없으면 admin-app `testImplementation`에 추가.
- **검증**: 기존 `RpAdminBoundaryIT`, `PlatformOperatorUnrestrictedIT` green 유지 + 신규 ArchUnit green.

## 4. QW-2 — RP_ADMIN 게이팅 + @PreAuthorize 정합 (security)

### 4.1 전역 데이터 GET을 PLATFORM-only로
- `SecurityPolicyController.get()`: `@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")` → `hasRole('PLATFORM_OPERATOR')` (`sec-rpadmin-global-security-policy-read`). 보안정책은 전역 싱글톤(테넌트 스코프 없음)이라 RP_ADMIN이 플랫폼 CORS allowlist·운영자 이메일을 읽을 이유 없음.
- `SystemInfoController.get()`: 동일하게 `hasRole('PLATFORM_OPERATOR')` (`sec-rpadmin-system-info-infra-disclosure`). 인프라 메타(호스트/리전/DB 배너)는 RP_ADMIN 불요.
- **불변식 — 코드로 확인됨**: `admin-ui/src/pages/SettingsPage.tsx:32,63-66`에서 `system`/`security` 탭과 `SystemInfoTab`/`SecurityPolicyTab`은 `{platform && ...}`로 게이트된다. **RP_ADMIN admin-ui는 이 화면을 렌더하지 않아 API를 호출하지 않으므로** PLATFORM-only로 좁혀도 SPA 영향 0. (좁힘으로 깨지는 것은 정식 경로 밖 RP_ADMIN 직접 API 호출자뿐.)
- **PUT** 경로는 이미 PLATFORM-only(변경 없음).

### 4.2 list/get 읽기 엔드포인트 @PreAuthorize 명시 (`sec-tenant-get-list-no-preauthorize`)
- `TenantAdminController.list()`/`get()`, `ApiKeyAdminController.list()`에 `@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")` 추가.
- **불변식**: 현재 역할이 두 개뿐이고 서비스 boundary가 이미 cross-tenant를 막으므로 동작 동일. 컨트롤러 1차 게이팅 명시화(defense-in-depth) + mutating 메서드와의 일관성.
- **검증**: 신규 보안 테스트 — `security-policy`/`system-info` GET이 RP_ADMIN→403, PLATFORM→200. 기존 `*ControllerSecurityTest` 회귀 없음.

## 5. QW-3 — DB/JPA 성능 + 의존성 검증 (performance)

### 5.1 api_key(tenant_id) 인덱스 (`perf-apikey-no-tenant-index`)
- 신규 Flyway 마이그레이션 V38: `CREATE INDEX ix_api_key_tenant ON api_key(tenant_id)` (활성 판정 커버하려면 `(tenant_id, revoked_at, expires_at)`). 기존 idempotent 패턴(V24/V25의 `EXECUTE IMMEDIATE` + ORA-00955 swallow) 그대로.
- **불변식**: 순수 스키마 추가. 코드·UI·동작 무변경. GRANT는 인덱스에 불요(테이블 권한 상속).

### 5.2 TenantAdminService.list() N+1 제거 (`perf-tenant-view-nplus1`)
- `list()`의 `toView`가 테넌트당 credentials/apiKeys/lastEventAt 3쿼리 → `tenant_id GROUP BY` 집계 쿼리로 묶어 `Map<UUID,..>` lookup. 단건 `get()` 경로는 현행 유지.
- **불변식**: 반환 `TenantView`의 숫자/필드 동일 — UI 표시값 불변. 신규 집계 쿼리가 기존과 동일 값을 내는지 단위 테스트로 확인.

### 5.3 Tenant LAZY 컬렉션 fetch (`perf-tenant-eager-collections-touch`) + Hibernate batch
- `application-common.yml`의 `spring.jpa.properties`에 `hibernate.jdbc.batch_size`(30~50), `hibernate.default_batch_fetch_size`(16~32) 추가. **`order_inserts`/`order_updates`는 제외**(flush DML 순서 전역 재배열이 미감사 write 경로의 제약 위반 리스크 — risk=low로 별도).
- **불변식**: 쿼리 결과·동작 불변, 라운드트립만 감소.

### 5.4 의존성 CVE 검증 (`sec-nimbus-jose-jwt-940`, `sec-vite-esbuild-devserver`)
- **무검증 범프 금지.** 먼저 nimbus-jose-jwt 9.40과 vite 5.4.21/esbuild 0.21.5에 대해 실제 해소된 advisory를 확인(릴리스 노트/CVE DB). 실제 보안 수정이 있으면 패치 버전으로만 범프하고, JWKS 캐시/Ed25519 경로 회귀 테스트(`SigningKeyProviderTest`/`IdTokenIssuerTest`) green 확인. advisory가 없거나 dev-only 무영향이면 followups에 "현 버전 유지 근거" 기록하고 범프 안 함.
- **불변식**: 패치 버전 범프만, 동작/API 불변. 메이저/마이너 업그레이드는 본 spec 밖.

## 6. QW-4 — 백엔드 순수 위생 (perf + quality)

전부 출력 바이트 불변 리팩토링. **특히 해시체인·서명키·와이어 응답은 바이트 단위로 보존.**

- **`perf-cryptoutils-hex-string-format`**: `CryptoUtils.hex()`를 `java.util.HexFormat.of().formatHex(bytes)`로 교체. 출력 동일한 소문자 hex → 모든 호출자·저장 해시·감사체인 불변. `CryptoUtilsTest`로 회귀 확인.
- **`cq-rsa-keygen-dup`**: RSA-2048 키쌍+kid+sealed 생성 중복(`SigningKeyProvider` + `KeyRotationService`)을 core/jwt 단일 팩토리로 추출, 두 호출부 공유. 생성 알고리즘 동일 → 발급 키 형식 불변.
- **`cq-objectconverter-field-init` / `perf-webauthn-converter-alloc`**: FIDO finish 서비스가 매 요청 `ObjectConverter`/webauthn4j Converter 신규 할당 → 필드/빈으로 1회 생성 재사용. **주의**: webauthn4j Converter의 thread-safety를 확인하고, 안전하지 않으면 변경 보류(이 항목만 빼고 진행). 검증 동작 불변.
- **`cq-clock-inconsistency`**: `TenantAdminService`·`ApiKeyAuthFilter`의 `Instant.now()` → 주입된 `Clock.instant()`. 시각 소스만 교체, 판정 결과 동일.
- **`cq-problemjson-inline-literals`**: `ApiKeyAuthFilter`/`RateLimitFilter`의 problem+json 리터럴을 공유 `ProblemJson.write(res, status, title[, error])` 헬퍼로 추출. **출력 바이트 동일** 유지(와이어 응답 불변).
- **`quality-touchlastused-silent-swallow`**: `ApiKeyLookupService`의 `touchLastUsed` catch에 warn 로그 1줄 또는 micrometer 카운터(`result=touch_failed`) 추가. 인증 결과·응답 무영향.
- **`perf-meter-counter-resolve`** (선택): `meterRegistry.counter(...)` 핫경로 재조회를 필드 캐시로. 메트릭 값 불변.
- **`perf-findall-ordered-query-pageable-sort`**: `CredentialRepository.findAllByTenantId` @Query의 ORDER BY와 Pageable Sort 이중 지정 정리(하나로). 정렬 결과 동일.
- **`cq-credential-recordauth-noop-arg`**: `Credential.recordAuthentication`의 자기복귀 인자 제거/정리. 동작 불변.
- **`cq-devtenant-todo-deadpath`**: `DevTenantHeaderFilter`의 미구현 TODO(slug fallback) 정리 — 구현하거나 명확한 주석으로 의도 고정(코드베이스 유일 TODO).
- **검증**: 각 모듈 단위 테스트 green, 특히 `CryptoUtilsTest`/`SigningKeyProviderTest`/`AuditChainVerifier` 관련 + 와이어 응답 스냅샷 불변.

## 7. QW-5 — admin-ui 코드 위생 (perf + quality)

전부 표시값·외관·흐름 불변. **UI/UX 디자인 무변경.**

- **`perf-activity-unmemoized-filtering`**: `ActivityPage`의 `filtered`를 `useMemo([displayEvents, filter])`로, `failureCount`/`mutationCount`를 단일 `useMemo` reduce로. 화면 출력 동일.
- **`perf-tenantslist-unmemoized-aggregates`**: `TenantsListPage`의 reduce/filter 집계 3회를 `useMemo`로. 검색 타이핑마다 전체 스캔 제거. 표시값 동일.
- **`cq-dead-mecontext`**: `MeContext`(MeProvider/useMe)가 미사용 dead code. **(B) 삭제**를 채택(prop drilling 현행 유지 — 동작·외관 불변, 최소 변경). (A) Context 채택은 prop 흐름을 바꿔 회귀 표면이 넓어 비채택.
- **`cq-sidebar-fetch-all-roles`**: Sidebar의 audit-chain overview 30초 폴링을 `isPlatform(me)`일 때만 시작하도록 가드. RP_ADMIN은 해당 footer를 안 봄. **외관·동작 불변**(불필요 요청만 제거).
- **`cq-no-eslint`**: eslint + typescript-eslint + react-hooks + jsx-a11y flat config 추가 + `lint` 스크립트. **처음엔 경고(warn) 레벨**로 도입(빌드 깨지 않음). `tsconfig`의 `noUnusedLocals`/`noUnusedParameters`를 true로 올리는 것은 별도 검토(빌드만 강화, 런타임 불변) — 미사용 식별자가 많으면 이번엔 보류.
- **`cq-a11y-clickable-rows`** (선택, 외관 불변 한정): 클릭 가능 `<tr>`/카드에 키보드 핸들러·`role`·`aria` 추가. **시각적 변화 없이** 키보드 접근성만 보강. 외관 바뀌면 보류.
- **`perf-sidebar-not-memoized-inline-props`**, **`perf-audittab-payload-stringify-per-row`** (선택): memo/useMemo로 리렌더·반복 stringify 감소. 표시값 동일.
- **검증**: vitest 22 tests green 유지, `tsc -b` + `vite build` EXIT 0, 표시값 육안 동일(스냅샷/단위).

## 8. 검증 전략 (전체)

- **백엔드 게이트**: `:core:compileJava :admin-app:compileJava :passkey-app:compileJava` + 단위/슬라이스 테스트. Oracle Testcontainers `*IT`는 이 환경에서 flaky → 가능 범위에서 실행하고 env 실패는 별도 표기(이전 phase 컨벤션).
- **admin-ui 게이트**: `npx tsc -b` EXIT 0 + `npm test`(vitest) green + `npm run build` EXIT 0.
- **불변식 검증**: 각 Phase 머지 전 — 응답 DTO/해시 출력/UI 표시값이 변경 전과 동일함을 단위 테스트 또는 스냅샷으로 확인. 특히 감사 해시체인(QW-4 hex), 서명키 형식(QW-4 keygen), 와이어 응답(QW-4 ProblemJson).
- **회귀 게이트**: 기존 보안 IT(`RpAdminBoundaryIT`/`PlatformOperatorUnrestrictedIT`/`*ControllerSecurityTest`)가 변경 후에도 green.

## 9. 커밋 전 게이트

메모리 지침: 커밋 전 `/codex review`(6/1 quota 리셋 후 누적 diff) + code quality subagent. 특히 QW-1·QW-2(보안 경계 변경)와 QW-4(해시/서명/와이어 바이트 불변).

## 10. 구현 순서(권장)

보안 우선 → 성능 → 위생. 각 Phase는 per-phase worktree에서 subagent-driven, merge --no-ff back.
1. **QW-1** Cross-tenant 회귀 방지 (가장 중요, 회귀 방지 인프라 먼저)
2. **QW-2** RP_ADMIN 게이팅 (보안 경계 명확화)
3. **QW-3** DB 성능 + 의존성 검증
4. **QW-4** 백엔드 순수 위생
5. **QW-5** admin-ui 코드 위생

각 Phase 완료 후 followups에 deferred(risk=high 3건, risk=low 50건, 미채택 선택 항목) 기록.
