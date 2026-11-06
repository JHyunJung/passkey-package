# Spring Boot 4.1 업그레이드 (1단계) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** core·webauthn·passkey-app·admin-app·rp-app 을 Spring Boot 3.5.14 → 4.1.2 로 올리고(Java 유지), sdk-java 는 Boot 3.5 기준을 유지하면서 외부 계약·보안 동작·Redis 형식을 보존한다.

**Architecture:** 먼저 Boot 3.5 위에서 계약 안전망(응답 JSON 형태 스냅샷·Redis 저장 형식 픽스처·SDK E2E)을 만든다. 그다음 빌드 플랫폼을 올리고, **Jackson 2 호환 모듈로 Jackson 계열 변화를 잠시 묶어 둔 채** 모듈별로 Boot 4 에 맞춘다. 마지막에 애플리케이션 코드를 Jackson 3 로 옮기고, 안전망이 바뀌지 않았음을 증명한다.

**Tech Stack:** Spring Boot 4.1.2(Framework 7.0.10, Security 7.1.2, Session 4.1.1), Hibernate 7.4.13, Jackson 3.1.7(+2.21.7 공존), Flyway 12.4.0, Testcontainers 2.0.5, JUnit Jupiter 6.0.3, Gradle 9.x, Java 17, Oracle(SE2), Redis.

**Spec:** `docs/superpowers/specs/2026-11-06-spring-boot-4-kotlin-design.md`

## Global Constraints

- Boot 4 모듈(core·webauthn·passkey-app·admin-app·rp-app) = Spring Boot **4.1.2** BOM. sdk-java = Spring Boot **3.5.x** BOM(3.5.16 허용) — Spring 6 / Jackson 2 로 컴파일.
- **Java 17 유지.** toolchain·Docker 베이스(`eclipse-temurin:17-*`) 변경 금지.
- passkey-app 외부 API JSON 계약 불변: 필드명·필드 존재·null 생략(`non_null`)·날짜 ISO-8601 `+09:00`·숫자 표기·에러 봉투.
- passkey-app 의 Redis 값(ChallengeStore JSON, Bucket4j, 캐시) 형식 불변. 예외: admin Spring Session 비호환 허용(배포 시 재로그인).
- DB 스키마(`V1__baseline_schema.sql`) 무변경. schema-validation 통과.
- 보안 동작 불변: admin 로그인/MFA/CSRF/권한, 테넌트 `@Filter`, API Key, Rate limit, actuator 차단, 로그 마스킹.
- **rp-app 은 Jackson 2 를 유지한다**(설계 §5.1 예외 조항): sdk-java 가 공개 API 로 Jackson 2 `JsonNode` 를 돌려주고 rp-app 이 그대로 응답에 싣기 때문. rp-app 은 `spring-boot-jackson2` 경로를 영구 사용. DONE 보고에 근거 명시.
- 로깅 provider(`core/logging/RedactingMessageJsonProvider`, rp-app 동명 클래스)는 logstash-logback-encoder 의 Jackson 2 API 를 쓰므로 Jackson 2 유지.
- 테스트 회귀 기준 = **업그레이드 전 base 결과와 대조해 새로 깨진 테스트 0**. 기준 결과: 스크래치패드 `baseline/<module>.failed.txt`(Task 0 산출). 전체 `./gradlew build` 는 게이트로 쓰지 않는다.
- 커밋은 worktree `feat/boot4-upgrade` 에서만. 각 Task 끝에 커밋, 메시지 끝에 Co-Authored-By/Claude-Session 트레일러.
- Task 2~6 사이에는 아직 손대지 않은 모듈이 컴파일되지 않는 것이 **정상**이다. 각 Task 의 검증은 그 Task 가 명시한 모듈에 한정한다.

## Review Focus

1. **null 필드 직렬화** — Jackson 3 전환 후에도 null 값 필드는 응답에서 빠져야 한다(`non_null`). → Task 1 `ApiContractIT` 가 null 가능 필드가 있는 응답(에러 봉투의 `error.details` 등)을 포함한다.
2. **알 수 없는 요청 필드** — 요청 JSON 에 모르는 필드가 있으면 지금처럼 400. → Task 1 `ApiContractIT.unknownRequestField_isRejected`.
3. **날짜·시간 표기와 저장** — 응답의 `OffsetDateTime`(예: `CredentialView`)은 `+09:00` ISO 문자열, DB 저장은 KST(Hibernate 7 매핑 변화). → Task 1 계약 스냅샷 + Task 1 `KstPersistenceIT`(Task 3·7 에서 필수 통과).
4. **과대·과깊이 JSON** — 깊이 64·문자열 256KB·문서 1MB 상한(`PasskeyAppJacksonConfig`)이 Jackson 3 에서도 400 으로 거부. → 기존 `PasskeyAppJacksonConfigTest` 를 Task 7 에서 Jackson 3 API 로 옮기되 단언 유지 + Task 1 `ApiContractIT.overDeepJson_isRejected`(HTTP 수준).
5. **테넌트 격리** — Hibernate 7 에서 `@Filter` 가 여전히 적용. → 기존 `AppLevelIsolationIT`·`TenantFilterAspectIT`·`TenantFilterBindingIT` 가 Task 3 검증 목록에 필수 포함.

---

### Task 0: 기준 테스트 결과 확보 (실행 담당자가 이미 수행)

- [x] 업그레이드 전 6개 모듈 `test --continue` 결과를 스크래치패드 `baseline/` 에 저장(`<module>.failed.txt`, `<module>.total.txt`, `<module>.log`).

### Task 1: 계약 안전망 (Boot 3.5 위에서 작성·통과)

**Files:**
- Create: `passkey-app/src/test/java/com/crosscert/passkey/app/contract/ApiContractIT.java`
- Create: `passkey-app/src/test/java/com/crosscert/passkey/app/contract/JsonShape.java` (응답 JSON → 정규화 형태 변환 유틸)
- Create: `passkey-app/src/test/resources/contract/*.shape.json` (형태 스냅샷)
- Create: `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/challenge/ChallengeStoreFormatCompatTest.java`
- Create: `passkey-app/src/test/resources/contract/redis/registration-challenge.json`, `authentication-challenge.json`
- Create: `passkey-app/src/test/java/com/crosscert/passkey/app/sdk/SdkClientEndToEndIT.java`
- Modify: `passkey-app/build.gradle.kts` (`testImplementation(project(":sdk-java"))`)
- Create: `core/src/test/java/com/crosscert/passkey/core/time/KstPersistenceIT.java`
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditChainJacksonCompatTest.java` + `admin-app/src/test/resources/contract/audit-chain.json`
- Delete: `rp-app/src/test/java/**/RpAppSmokeIT.java` (`@Disabled` 빈 껍데기 — 이 Task 의 SDK E2E 가 대체)

**Interfaces:**
- Produces: `JsonShape.of(String json): String` — 정렬된 JSON 경로 → 형태 토큰 맵을 pretty JSON 으로. 토큰: `string`, `number`, `boolean`, `object`, `array<원소형태>`, 그리고 패턴 토큰 `b64url`, `uuid`, `iso-offset(+09:00)`, `jwt`. 휘발 값(challenge·token·id·시각)은 값 대신 토큰으로만 남는다. **필드 순서는 비교 대상이 아니다**(키 정렬).
- Produces: 스냅샷 파일 이름 = `<엔드포인트-케이스>.shape.json`. 이후 Task 들은 이 파일을 **수정하지 않는다**(수정이 필요하면 계약 변경 = 반목표 위반으로 보고).

- [ ] **Step 1: `ApiContractIT` 작성** — `Fido2EndToEndIT` 의 컨테이너·테넌트·API Key 준비 방식을 그대로 따른다(RANDOM_PORT, Oracle+Redis Testcontainers, `Fido2TestAuthenticator`). 테스트:
  - `registrationStart_shape`, `registrationFinish_shape`, `authenticationStart_shape`, `authenticationFinish_shape` — 응답 바디 `JsonShape.of(...)` 가 스냅샷과 같다.
  - `idToken_claimShape` — finish 응답의 ID Token 헤더(`alg`,`kid`,`typ`)와 클레임 이름 집합·타입(`iat`/`exp` number, `iss`/`aud`/`sub` string, `amr` array)이 스냅샷과 같다.
  - `credentials_shape` — 자격증명 조회 응답(`CredentialView`, `OffsetDateTime` 은 `iso-offset(+09:00)`).
  - `jwks_shape` — `/.well-known/jwks.json`.
  - `missingApiKey_errorShape`(401), `validationError_errorShape`(400), `unknownToken_errorShape`(finish 에 존재하지 않는 토큰) — 에러 봉투 형태와 HTTP 상태.
  - `unknownRequestField_isRejected` — registration start 바디에 `"bogus":1` 추가 → 400, 에러 봉투 형태 스냅샷.
  - `overDeepJson_isRejected` — 깊이 65 중첩 JSON → 400.
- [ ] **Step 2: 스냅샷 생성** — 최초 실행에서 스냅샷이 없으면 파일을 쓰고 실패하는 모드(시스템 프로퍼티 `contract.record=true` 일 때만 기록). 기록 후 내용을 눈으로 검토(민감값이 토큰화됐는지) 하고 커밋 대상에 포함.
- [ ] **Step 3: `ChallengeStoreFormatCompatTest`** — 현재 직렬화기로 만든 고정 JSON 샘플을 픽스처로 저장한 뒤:
  - `readsRecordedRegistrationChallenge` / `readsRecordedAuthenticationChallenge`: 픽스처 → `deserialize` 결과의 각 필드가 기대값과 같다.
  - `writesSameJsonAsRecorded_*`: 같은 값 객체를 `serialize` → 픽스처와 **JSON 의미상 동일**(JsonNode equals).
  - ChallengeStore 의 직렬화가 private 이면 Redis 대신 `StringRedisTemplate` mock 으로 `set` 인자를 캡처해 검증한다(기존 `ChallengeStoreTest` 방식 참고).
- [ ] **Step 4: `SdkClientEndToEndIT`** — sdk-java `PasskeyClient` 를 랜덤 포트의 passkey-app 에 연결해 `registrationStart → (에뮬레이터) → registrationFinish → authenticationStart → (에뮬레이터) → authenticationFinish → verifyIdToken(idToken, expectedIssuer, tenantId)` 성공. 단언: finish 응답 `credentialId` 비어 있지 않음, `verifyIdToken` 반환 클레임의 `sub` 가 등록한 userHandle.
- [ ] **Step 5: `KstPersistenceIT`** — `OffsetDateTime` 컬럼을 가진 core 엔티티 하나를 저장 후 네이티브 쿼리로 컬럼 값을 읽어 KST 벽시계 값으로 저장됐는지, 재조회 시 오프셋이 `+09:00` 인지 단언. core 의 기존 Oracle Testcontainers IT 구성을 따른다.
- [ ] **Step 6: `AuditChainJacksonCompatTest`** — 감사 로그 해시 체인(`AuditChainVerifier`)이 JSON 직렬화 바이트에 의존하는지 먼저 확인한다. 의존하면: 현재(Jackson 2) 코드로 만든 고정 체인 레코드를 픽스처로 저장하고 `verifiesRecordedChain`(검증 통과)과 `appendProducesSameHash`(같은 입력 → 픽스처와 같은 해시)를 단언. 의존하지 않으면 근거(코드 위치)를 테스트 클래스 주석에 남기고 `verifiesRecordedChain` 만 둔다.
- [ ] **Step 7: 실행** — `./gradlew :passkey-app:test --tests '*ApiContractIT' --tests '*ChallengeStoreFormatCompatTest' --tests '*SdkClientEndToEndIT'`, `./gradlew :core:test --tests '*KstPersistenceIT'`, `./gradlew :admin-app:test --tests '*AuditChainJacksonCompatTest'` → 전부 PASS (Boot 3.5).
- [ ] **Step 8: `RpAppSmokeIT` 삭제 후 `./gradlew :rp-app:test` 실패 목록이 baseline 과 동일.**
- [ ] **Step 9: 커밋** — `test(contract): Boot 4 업그레이드 전 계약 안전망(API 형태·Redis 형식·SDK E2E)`

### Task 2: 빌드 플랫폼 (Gradle 9 · 버전 카탈로그 · BOM 분리)

**Files:**
- Modify: `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` (`./gradlew wrapper --gradle-version <X>` 로 생성)
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts` (루트)
- Modify: `sdk-java/build.gradle.kts`, `rp-app/build.gradle.kts`(중복 `spring.dep.mgmt` 정리만)

**Interfaces:**
- Produces: 카탈로그 버전 키 `spring-boot = "4.1.2"`, `spring-boot-sdk = "3.5.16"`. 루트 BOM 적용 규칙: `project.name == "sdk-java"` 이면 `spring-boot-sdk` BOM, 나머지는 `spring-boot` BOM. Boot 4 모듈의 Testcontainers 는 Boot BOM 관리(2.0.5)로, 별도 `testcontainers-bom` import 제거.
- Produces: 카탈로그 라이브러리 별칭 `testcontainers-oracle` → `org.testcontainers:testcontainers-oracle-xe`, `testcontainers-junit` → `org.testcontainers:testcontainers-junit-jupiter`(2.x 아티팩트명), `flyway-core`/`flyway-oracle`/`oracle-jdbc`/`oracle-nls` 는 버전 고정 해제(Boot BOM 관리).

- [ ] **Step 1: Gradle 버전 결정** — Boot 4.1.2 Gradle 플러그인 문서와 Kotlin Gradle Plugin 2.4.21 의 지원 범위가 겹치는 **가장 높은 9.x** 를 고른다(근거 링크를 커밋 메시지에). wrapper 갱신.
- [ ] **Step 2: 카탈로그·루트 빌드 수정** — 위 Interfaces 대로. 루트의 `jackson-annotations:2.21` 강제 오버라이드는 Boot 4 BOM 이 관리하는 2.21.x 로 대체되면 제거(webauthn4j 테스트 의존이 끌어오는 Jackson 3 와 충돌 없는지 `:webauthn:dependencies` 로 확인). `junit-platform-launcher` 주석의 버전 근거 갱신.
- [ ] **Step 3: sdk-java 검증** — `./gradlew :sdk-java:dependencies --configuration compileClasspath | grep -E "spring-web|jackson-databind"` → `spring-web 6.2.x`, `jackson-databind 2.x`. `./gradlew :sdk-java:test` 실패 목록 = baseline.
- [ ] **Step 4: webauthn 검증** — `./gradlew :webauthn:test` 실패 목록 = baseline (Spring 비의존 모듈, Jackson 2.21.x).
- [ ] **Step 5: 커밋** — `build: Gradle 9 · Spring Boot 4.1.2 카탈로그 · sdk-java 는 Boot 3.5 BOM 유지` (본문에 "core 이후 모듈은 다음 Task 에서 맞춤" 명시)

### Task 3: core 를 Boot 4 에 맞춤 (Jackson 2 호환 유지)

**Files:**
- Modify: `core/build.gradle.kts` — 모듈화된 스타터로 교체: `spring-boot-starter-webmvc`, `spring-boot-starter-data-jpa`, `spring-boot-starter-data-redis`, Spring Session Redis 스타터, `spring-boot-starter-flyway`(Boot 4 는 flyway-core 만으로 자동설정이 켜지지 않는다), `spring-boot-starter-actuator`, `spring-boot-starter-validation`, `spring-boot-starter-mail`, AOP 스타터, `spring-boot-jackson2`(Jackson 2 자동설정 유지). 정확한 아티팩트명은 Boot 4.1 문서 기준.
- Modify: `core/src/main/java/com/crosscert/passkey/core/config/CoreJacksonConfig.java` — Boot 4 의 Jackson 2 커스터마이저 타입으로 import 이동(동작 동일).
- Modify: `core/src/main/resources/application-common.yml` — Boot 4 에서 이름이 바뀐 키만 이동(예: Jackson 2 설정 키 네임스페이스, 메시지 컨버터 선호 설정). 값 불변.
- Modify: core 테스트의 Testcontainers import(`org.testcontainers.containers.OracleContainer` → 2.x 패키지), JUnit 6 비호환 API, `@WebMvcTest` 패키지(2곳).
- Modify: Hibernate 7 에서 깨지는 core 코드(엔티티 매핑·`@Filter`·`TenantFilterAspect`·쿼리) — 깨진 것만.

**Interfaces:**
- Consumes: Task 2 카탈로그 별칭.
- Produces: Boot 4 에서 Jackson 2 `ObjectMapper` 빈이 계속 자동구성되고 Spring MVC JSON 컨버터가 Jackson 2 를 쓰는 상태(Task 4·5·6 이 전제). Task 7 에서 해제한다.

- [ ] **Step 1: 의존성·설정·코드 수정** (위 Files).
- [ ] **Step 2: 실행** — `./gradlew :core:test`. 실패 목록 ⊆ baseline. **필수 통과**: `AppLevelIsolationIT`, `TenantFilterAspectIT`, `TenantFilterBindingIT`, `KstPersistenceIT`(Task 1 작성), 로그 마스킹 테스트(`*Redact*`/`*Masking*`).
- [ ] **Step 3: Flyway 확인** — Testcontainers IT 로그에 `Successfully applied 1 migration`(V1) 과 schema-validation 오류 없음.
- [ ] **Step 4: 커밋** — `build(core): Spring Boot 4.1 · Hibernate 7 · Testcontainers 2 대응 (Jackson 2 호환 유지)`

### Task 4: passkey-app 을 Boot 4 에 맞춤

**Files:**
- Modify: `passkey-app/build.gradle.kts`, `passkey-app/src/main/java/com/crosscert/passkey/app/config/PasskeyAppJacksonConfig.java`(커스터마이저 import 이동만), Boot 4 에서 깨진 main/test 코드, 테스트 Testcontainers import.

**Interfaces:**
- Consumes: Task 3 의 Jackson 2 호환 상태, Task 1 스냅샷.

- [ ] **Step 1: 수정 후 컴파일** — `./gradlew :passkey-app:compileJava :passkey-app:compileTestJava` 성공.
- [ ] **Step 2: 실행** — `./gradlew :passkey-app:test`. 실패 목록 ⊆ baseline. **필수 통과**: Task 1 의 세 테스트(스냅샷 파일 무수정), `PasskeyAppJacksonConfigTest`, `ApiKeyAuthFilter*`, `RateLimitFilterTest`, `ForwardHeadersConfigTest`, `Fido2EndToEndIT`.
- [ ] **Step 3: 커밋** — `build(passkey-app): Spring Boot 4.1 대응`

### Task 5: rp-app 을 Boot 4 에 맞춤 (Jackson 2 영구 유지)

**Files:**
- Modify: `rp-app/build.gradle.kts` — 모듈화 스타터(webmvc, thymeleaf, security, validation) + `spring-boot-jackson2`, test 슬라이스 스타터.
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/WebSecurityConfig.java` — Security 7 에서 제거된 API 만.
- Modify: `@MockBean` 3파일 → `@MockitoBean`, `@WebMvcTest` 4파일 패키지 이동.
- Modify: `rp-app/src/main/resources/application.yml` — 이름 바뀐 키만(예: `server.forward-headers-strategy` 유지 확인).

- [ ] **Step 1: 수정 후** `./gradlew :rp-app:test` 실패 목록 ⊆ baseline. 필수 통과: `/passkey/**` WebMvc 테스트(CORS·무상태·relay), well-known 테스트, 마스킹 테스트.
- [ ] **Step 2: 의존성 확인** — `./gradlew :rp-app:dependencies --configuration runtimeClasspath | grep -E "spring-web:|jackson-databind"` → spring-web 7.0.x, Jackson 2 databind 존재(sdk-java 런타임 요구).
- [ ] **Step 3: 커밋** — `build(rp-app): Spring Boot 4.1 대응 — sdk-java 의 Jackson 2 타입 때문에 Jackson 2 유지`

### Task 6: admin-app 을 Boot 4 에 맞춤

**Files:**
- Modify: `admin-app/build.gradle.kts` — 모듈화 스타터, `thymeleaf-extras-springsecurity6`(Boot 4.1.2 BOM 관리 3.1.5 — Security 7 동작은 Step 3 에서 렌더링으로 확인), 테스트 슬라이스 스타터, Testcontainers 2 별칭, WireMock/ArchUnit/jsoup 호환 버전.
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java` — Security 7 제거 API 만, 규칙·순서·매처 의미 불변.
- Modify: `@MockBean`/`@SpyBean` 43파일 → `@MockitoBean`/`@MockitoSpyBean`, `@WebMvcTest` 18파일 패키지, Testcontainers 20파일 import, JUnit 6 비호환.
- Modify: 그 밖에 Boot 4 / Spring 7 / Hibernate 7 로 깨진 main 코드(깨진 것만).

- [ ] **Step 1: 기계적 치환 + 컴파일** — `./gradlew :admin-app:compileJava :admin-app:compileTestJava` 성공. `git grep -n "@MockBean\|@SpyBean" -- admin-app` 결과 0.
- [ ] **Step 2: 실행** — `./gradlew :admin-app:test`. 실패 목록 ⊆ baseline. 필수 통과: `auth/` 패키지(로그인·MFA·CSRF), `AdminFlowIT`, `arch/TenantBoundaryArchTest`, `KeyRotationIT`, 슬라이스 테스트 전부(필수 MockBean 12개 구성이 `@MockitoBean` 으로 보존).
- [ ] **Step 3: 로컬 기동 렌더링** — `SPRING_PROFILES_ACTIVE=local` 로 admin-app 기동(로컬 `passkey-oracle` 컨테이너 + redis), 로그인 화면·대시보드·테넌트 상세·Activity 를 열어 500 없음, `sec:authorize` 로 숨겨지는 메뉴가 역할별로 동일(PLATFORM_OPERATOR vs RP_ADMIN bob). 확인 결과를 DONE 보고에 화면 목록으로.
- [ ] **Step 4: 커밋** — `build(admin-app): Spring Boot 4.1 · Security 7 대응, @MockBean→@MockitoBean`

### Task 7: 애플리케이션 코드 Jackson 3 전환 (webauthn·core·passkey-app)

**Files:**
- Modify: `webauthn/build.gradle.kts`(`tools.jackson.core:jackson-databind`), `webauthn/src/main/java/com/crosscert/passkey/webauthn/**`(JsonMappers, AndroidSafetyNet, ClientDataValidator, MdsBlobParser, MdsJws, CredentialJsonParser, NativeWebAuthnVerifier) + webauthn 테스트 7파일.
- Modify: `core/build.gradle.kts`(`spring-boot-jackson2` 제거, Jackson 3 자동설정), `CoreJacksonConfig`(Boot 4 Jackson 3 커스터마이저 — `JsonMapperBuilderCustomizer` 계열), `SigningKeyProvider`, `LicenseCache`, `LicenseGuardFilter`, `application-common.yml` 의 `spring.jackson.*`.
- Keep Jackson 2: `core/logging/RedactingMessageJsonProvider.java` (logstash 의존) — 이 파일의 Jackson 2 의존을 명시적 의존성(`com.fasterxml.jackson.core:jackson-core`)으로 선언.
- Modify: passkey-app `PasskeyAppJacksonConfig`(StreamReadConstraints 를 Jackson 3 팩토리 빌더로), dto 4개(`JsonNode` → `tools.jackson.databind.JsonNode`), `WebAuthnVerifierConfig`, 등록/인증 서비스 4개, `ChallengeStore` + passkey-app 테스트 11파일.

**Interfaces:**
- Consumes: Task 1 스냅샷·픽스처(무수정), Task 3 의 설정 키.
- Produces: core·webauthn·passkey-app 애플리케이션 코드에 `com.fasterxml.jackson.databind` import 0(로깅 provider 제외). `com.fasterxml.jackson.annotation` 은 Jackson 3 에서도 그대로 쓰는 패키지라 유지.

- [ ] **Step 1: Jackson 3 기본값을 Jackson 2 호환으로** — Boot 4 가 제공하는 Jackson 2 호환 기본값 설정을 우선 사용하고, 그래도 다른 항목(프로퍼티 정렬, null→primitive 실패, 날짜/`OffsetDateTime` 처리, `FAIL_ON_TRAILING_TOKENS` 등)은 `CoreJacksonConfig` 에서 명시. 현재 yml 의 3개 설정(`write-dates-as-timestamps: false`, `fail-on-unknown-properties: true`, `default-property-inclusion: non_null`)과 동일 의미 유지.
- [ ] **Step 2: 코드 전환** (위 Files). `git grep -n "com.fasterxml.jackson.databind" -- webauthn/src core/src passkey-app/src` → 로깅 provider 와 그 테스트만 남음.
- [ ] **Step 3: 실행** — `./gradlew :webauthn:test :core:test :passkey-app:test`. 실패 목록 ⊆ baseline. **필수 통과(스냅샷·픽스처 무수정)**: `ApiContractIT`, `ChallengeStoreFormatCompatTest`, `SdkClientEndToEndIT`, `PasskeyAppJacksonConfigTest`(단언 유지, API 만 Jackson 3), webauthn differential 테스트 전부.
- [ ] **Step 4: 커밋** — `refactor(jackson): webauthn·core·passkey-app 을 Jackson 3 로 — 계약 스냅샷 무변경`

### Task 8: admin-app Jackson 3 전환

**Files:**
- Modify: admin-app main 15파일(Audit*, AdminSecurityConfig, DynamicCorsConfigurationSource, SecurityPolicyService, TenantAdminService, ActivityPageController, ActivityDiff, ViewHelpers, Toasts, AuditHelpers, AuditTabController, CredentialsTabController) + 테스트 36파일.

- [ ] **Step 1: 전환** — `ActivityDiff` 의 `FAIL_ON_TRAILING_TOKENS`, `ViewHelpers` 의 `findAndRegisterModules()` 와 같은 의미를 Jackson 3 빌더로 유지(Jackson 3 는 java.time 내장). 감사 로그 해시 체인 호환은 Task 1 의 `AuditChainJacksonCompatTest`(픽스처 무수정)로 증명한다.
- [ ] **Step 2: 실행** — `./gradlew :admin-app:test` 실패 목록 ⊆ baseline, `AuditChainJacksonCompatTest` 통과, `git grep -n "com.fasterxml.jackson.databind" -- admin-app/src` 0.
- [ ] **Step 3: 커밋** — `refactor(admin-app): Jackson 3 전환 — 감사 체인 호환 유지`

### Task 9: 배포 산출물·문서·최종 검증

**Files:**
- Modify: `README.md`(스택: Spring Boot 4.1, Hibernate 7, Jackson 3), `docs/dev-setup.md`(Gradle 9), `qa-bundle/README.md` + `docs/onprem-deployment.md`(배포 절차에 "admin 세션 정리 → 전원 재로그인" 단계 추가: Redis 의 Spring Session 키 삭제 명령 포함), Dockerfile 주석의 버전 언급(있으면).

- [ ] **Step 1: 이미지 빌드** — `scripts/build-images.sh`(또는 Dockerfile 3개 `docker build --platform linux/amd64`) 성공. `docker compose -f deploy/docker-compose.yml config -q` 성공.
- [ ] **Step 2: 로컬 3앱 기동** — passkey-app·admin-app·rp-app 을 local 프로필로 띄우고 rp-app 웹 데모(`http://localhost:9090`)에서 등록·로그인 성공(브라우저 가상 인증기 또는 사용자 확인). 결과를 DONE 보고에.
- [ ] **Step 3: 최종 회귀 대조** — 스크래치패드의 `baseline.sh after` 로 6모듈 재실행 → 각 모듈 `after/<m>.failed.txt` 가 `baseline/<m>.failed.txt` 의 부분집합(새로 추가된 테스트는 전부 통과). 비교 결과 표를 DONE 보고에.
- [ ] **Step 4: 커밋** — `docs(deploy): Boot 4.1 업그레이드 반영 — admin 재로그인 배포 절차`
