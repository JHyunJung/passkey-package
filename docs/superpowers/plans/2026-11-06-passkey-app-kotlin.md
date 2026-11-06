# passkey-app Kotlin 전환 (2단계) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** passkey-app 의 본 코드(34파일)와 테스트(28파일)를 실무 정석 Kotlin 으로 전부 옮기되, 외부 동작은 1비트도 바꾸지 않는다.

**Architecture:** 빌드 기반을 먼저 깔고(Kotlin 2.4.21, all-open, jackson-module-kotlin, mockito-kotlin), 요청 DTO 누락 필드 계약 테스트를 Java 로 추가한다. 그다음 본 코드를 패키지 단위로 Kotlin 화하면서 **기존 Java 테스트를 동작 동일성 안전망으로** 쓴다(접근자 이름·import 변경만 허용). 마지막에 테스트를 Kotlin 으로 옮긴다.

**Tech Stack:** Kotlin 2.4.21, Spring Boot 4.1.2 (Spring 7, Jackson 3 + jackson-module-kotlin), JUnit 5, AssertJ, mockito-kotlin, Testcontainers 2, Java 17 target.

**Spec:** `docs/superpowers/specs/2026-11-06-passkey-app-kotlin-design.md`

## Global Constraints

- Kotlin **2.4.21**; Boot BOM `kotlin.version` 을 2.4.21 로 덮어써 stdlib/reflect 정렬. JVM target 17. 컴파일러 옵션 `-Xjsr305=strict`, `javaParameters = true`.
- passkey-app 에만 `kotlin("jvm")` + `kotlin("plugin.spring")`. `plugin.jpa` 불필요(엔티티는 core). core·webauthn·admin-app·rp-app·sdk-java 언어 변경 금지.
- 테스트: JUnit 5 + AssertJ 유지, Mock 은 **mockito-kotlin**. MockK·Kotest·코루틴 금지.
- 스타일: 주 생성자 주입, Lombok 제거, record → `data class`(`@JvmRecord` 아님), `val` 기본, null 은 타입으로. 예외 흐름·검증 순서·로그 메시지 문구 불변. `!!` 금지(불가피하면 근거 주석).
- **요청 DTO 의 검증 대상 필드는 nullable(`?`) + 같은 Bean Validation 제약 유지**(에러 봉투 계약 보존).
- Java(core·webauthn) 반환값은 원 코드의 null 처리 그대로 타입화 — 근거 없는 non-null 선언 금지, `?.`/`?:` 로 실패 경로를 조용히 통과시키기 금지.
- **동결**: `passkey-app/src/test/resources/contract/**` 기존 파일 수정 금지(새 스냅샷 추가는 허용).
- Task 2~5(본 코드 전환) 동안 Java 테스트 수정은 **접근자 호출 이름(`x()`→`getX()` 등)과 import 만**. 단언·입력·기대값 불변. 수정한 테스트 파일·줄 수를 커밋 메시지에 기록.
- 모든 커밋에서 `./gradlew :passkey-app:test` 실패 0 (혼합 Java/Kotlin 컴파일 포함).
- 기준선: 1단계 머지 후 전 모듈 결과 — passkey-app·rp-app·sdk-java·admin-app 실패 0, webauthn `MdsDifferentialTest`, core `AdminUserTenantRepositoryTest`·`CredentialAuthEventRepositoryTest` 만 실패.

## Review Focus

1. **요청 필드 누락/null** — `{}` 나 필드 하나 빠진 요청이 지금과 같은 400 + 에러 봉투(`fieldErrors`)로 가야 한다(Kotlin non-null 로 두면 Jackson 단계 실패로 바뀜). → Task 1 `RequestDtoMissingFieldContractIT`.
2. **Java 플랫폼 타입 null** — core/webauthn 이 null 을 돌려주는 경로(조회 결과 없음, 선택 필드)에서 NPE 가 새로 나면 안 된다. → 기존 Java 테스트(Task 2~5 무수정 통과)가 판정; 서비스 Task 에서 null 경로마다 원 코드 분기 보존을 리뷰.
3. **트랜잭션·AOP 프록시** — `@Transactional` 서비스가 Kotlin final 클래스가 되어 프록시가 안 걸리면 테넌트 필터·잠금이 빠진다. → Task 4 `KotlinProxyWiringTest` (각 `@Transactional` 빈이 CGLIB 프록시인지 단언).
4. **Jackson Kotlin 모듈 직렬화** — data class 응답의 null 생략·필드 순서·이름이 바뀌면 안 된다. → `ApiContractIT` 스냅샷(Task 2 이후 매 Task 필수 통과) + `CoreJacksonWireFormatTest`.
5. **메인 클래스 이름** — Kotlin `fun main` 이면 클래스명이 `PasskeyApplicationKt` 가 되어 bootJar/Docker 기동이 깨질 수 있다. → Task 5 bootJar 기동 + Docker 이미지 빌드·기동 확인.

---

### Task 1: Kotlin 빌드 기반 + 요청 누락 필드 계약 테스트 (Kotlin 코드 0)

**Files:**
- Modify: `gradle/libs.versions.toml` (`kotlin = "2.4.21"`, 라이브러리 `jackson-module-kotlin` = `tools.jackson.module:jackson-module-kotlin`(BOM 관리), `mockito-kotlin` = `org.mockito.kotlin:mockito-kotlin`(Maven Central 최신 6.x — 확인 후 고정), 플러그인 `kotlin-jvm`/`kotlin-spring` 버전 ref)
- Modify: `build.gradle.kts` (루트: Boot BOM 적용 시 `kotlin.version` 프로퍼티 2.4.21 덮어쓰기 — dependency-management 의 `bomProperty`/`ext` 방식 중 동작 확인된 것)
- Modify: `passkey-app/build.gradle.kts` (plugins: kotlin jvm + spring; `kotlin { jvmToolchain(17); compilerOptions { freeCompilerArgs.add("-Xjsr305=strict"); javaParameters = true } }`; deps: `jackson-module-kotlin`, `kotlin-reflect`; test: `mockito-kotlin`)
- Create: `passkey-app/src/test/java/com/crosscert/passkey/app/contract/RequestDtoMissingFieldContractIT.java` + 새 스냅샷 `contract/missing-field-*.shape.json`

**Interfaces:**
- Produces: passkey-app 이 Kotlin 소스(`src/main/kotlin`, `src/test/kotlin`)와 Java 소스를 함께 컴파일하는 빌드. 이후 Task 는 파일을 `src/main/kotlin/<같은 패키지>` 로 옮긴다.

- [ ] **Step 1: `RequestDtoMissingFieldContractIT` 작성(Java, `ApiContractIT`·`PasskeyAppItSupport` 패턴)** — 요청 DTO 마다(RegistrationStartRequest, RegistrationFinishRequest, AuthenticationStartRequest, AuthenticationFinishRequest, CredentialSelfServiceController.RenameRequest): (a) 빈 객체 `{}` (b) 검증 필드 하나씩 누락 (c) 검증 필드에 `null` → HTTP 상태와 응답 바디 `JsonShape` 가 새 스냅샷과 같다. 검증 애너테이션이 없는 필드는 현재 동작(성공 또는 다른 에러)을 그대로 기록.
- [ ] **Step 2: 현재 코드(Boot 4, Java)에서 `-Pcontract.record=true` 로 스냅샷 기록 → 검토 → 비교 모드 2회 통과.**
- [ ] **Step 3: 빌드 기반 적용.** `./gradlew :passkey-app:dependencies --configuration runtimeClasspath | grep -E "kotlin-stdlib|kotlin-reflect|jackson-module-kotlin"` → 2.4.21 / 3.1.x.
- [ ] **Step 4: 전 테스트** — `./gradlew :passkey-app:test --continue --max-workers=1` 실패 0 (jackson-module-kotlin 등록 후에도 `ApiContractIT`·`ChallengeStoreFormatCompatTest`·새 IT 통과). `:rp-app:test`, `:sdk-java:test`, `:core:compileJava :admin-app:compileJava` 성공.
- [ ] **Step 5: 커밋** — `build(passkey-app): Kotlin 2.4.21 빌드 기반 + 요청 누락 필드 계약 스냅샷`

### Task 2: DTO·challenge·config 패키지 Kotlin 화

**Files (Java 삭제 → `src/main/kotlin` 동일 패키지 Kotlin 생성):**
- `api/v1/rp/dto/*` 10개 (record → data class; 요청 DTO 검증 필드는 `?` + 같은 제약; `JsonNode` 필드 타입 유지)
- `fido2/challenge/{RegistrationChallenge, AuthenticationChallenge, ChallengeIssuer, ChallengeStore}`
- `config/{PasskeyAppJacksonConfig, PasskeyAppHttpConvertersConfig}`, `fido2/WebAuthnVerifierConfig`, `fido2/CeremonyMetrics`
- Java 테스트: 접근자/import 만 수정 허용.

**Interfaces:**
- Produces: Kotlin 프로퍼티 이름 = 원 record 컴포넌트 이름(JSON 필드명 불변). Java 호출부는 `getX()`(Boolean `isX` 주의 — 원 이름이 `isX` 형태인지 확인).

- [ ] **Step 1: 전환** (위 Files). `ChallengeStore` 의 TTL·키 접두사·예외 메시지 불변.
- [ ] **Step 2: Java 테스트 컴파일 맞춤(접근자/import 만)** — `./gradlew :passkey-app:compileTestJava`.
- [ ] **Step 3: 실행** — `./gradlew :passkey-app:test --continue --max-workers=1` 실패 0. 필수: `ApiContractIT`, `RequestDtoMissingFieldContractIT`, `ChallengeStoreFormatCompatTest`, `ChallengeStoreTest`, `PasskeyAppJacksonConfigTest`, `PasskeyAppHttpConvertersConfigTest`, `RegistrationStartRequestValidationTest`.
- [ ] **Step 4: 커밋** — `refactor(passkey-app): dto·challenge·config Kotlin 화` (본문에 수정한 Java 테스트 파일·줄 수)

### Task 3: security 패키지 Kotlin 화

**Files:** `security/{ApiKeyAuthFilter, ApiKeyLookupService(+ApiKeyAuthRow), ApiKeyScopeResolver, ProblemJson, RateLimitConfig, RateLimitFilter(+RateTier)}`

- [ ] **Step 1: 공백 점검 → 특성 테스트 먼저(Java)** — 기존 `ApiKeyAuthFilterTest`, `ApiKeyAuthFilterScopeTest`, `RateLimitFilterTest`, `ApiKeyLookupServiceIT`, `ProblemJsonTest`, `ApiKeyScopeResolverTest` 가 덮지 않는 분기(예: 헤더 누락/형식 오류/만료·폐기 키/scope 불일치/Redis 장애 시 fail-open·closed 여부/Retry-After 값)가 있으면 Kotlin 전환 **전에** Java 테스트를 추가하고 현재 코드에서 통과 확인 후 별도 커밋(`test(passkey-app): security 경로 특성 테스트`).
- [ ] **Step 2: 전환.** 필터 순서(`@Order`/등록 순서), 응답 상태·헤더·바디 바이트, 로그 문구 불변.
- [ ] **Step 3: 실행** — `./gradlew :passkey-app:test --continue --max-workers=1` 실패 0.
- [ ] **Step 4: 커밋** — `refactor(passkey-app): security Kotlin 화`

### Task 4: fido2 서비스 Kotlin 화

**Files:** `fido2/registration/{RegistrationStartService, RegistrationFinishService}`, `fido2/authentication/{AuthenticationStartService, AuthenticationFinishService}`, `fido2/credential/CredentialSelfService`, `fido2/mds/MdsVerifier`
- Create: `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/KotlinProxyWiringTest.java`

- [ ] **Step 1: `KotlinProxyWiringTest` 작성(Java, 전환 전 통과 확인)** — 컨텍스트에서 6개 서비스 빈이 `AopUtils.isAopProxy(bean)` true(`@Transactional` 이 있는 빈), `TransactionSynchronizationManager.isActualTransactionActive()` 가 서비스 메서드 안에서 true 임을 대표 1개 경로로 확인. (기존 IT 컨텍스트 재사용 가능하면 그 클래스에 추가해도 됨.)
- [ ] **Step 2: 전환.** 잠금(비관적 락)·검증 순서·예외 타입·메시지·메트릭 이름/태그·로그 문구 불변. core/webauthn 반환 null 처리 원 분기 보존(Review Focus 2).
- [ ] **Step 3: 실행** — `./gradlew :passkey-app:test --continue --max-workers=1` 실패 0. 필수: `Fido2EndToEndIT`, `SdkClientEndToEndIT`, `ApiContractIT`, `KotlinProxyWiringTest`, `*Freshness*`, `*Duplicate*`, `*Exclude*`, `*Suspend*`, `*UserHandleValidation*`, `CredentialSelfServiceTest`, `MdsVerifierTest`.
- [ ] **Step 4: 커밋** — `refactor(passkey-app): fido2 서비스 Kotlin 화`

### Task 5: 컨트롤러·앱 진입점 Kotlin 화 + 기동 확인

**Files:** `api/v1/rp/{RegistrationController, AuthenticationController, CredentialSelfServiceController(+RenameRequest), JwksController}`, `PasskeyApplication` (→ `@SpringBootApplication class PasskeyApplication` + top-level `fun main`), `passkey-app/build.gradle.kts` 의 `springBoot.mainClass` 를 실제 클래스명(`com.crosscert.passkey.app.PasskeyApplicationKt`)으로.

- [ ] **Step 1: 전환.** 매핑 경로·HTTP 메서드·`@Valid` 위치·응답 코드 불변. `src/main/java` 에 남은 Java 파일 0 (`find passkey-app/src/main -name '*.java'` 결과 없음).
- [ ] **Step 2: 실행** — `./gradlew :passkey-app:test --continue --max-workers=1` 실패 0, `JwksControllerTest`, `ForwardHeadersConfigTest`, `LogLevelSnapshotTest` 포함.
- [ ] **Step 3: bootJar 기동** — `./gradlew :passkey-app:bootJar` 후 `java -jar deploy/passkey-app.jar` 가 dev 프로필로 기동(로컬 Oracle/Redis 필요 시 1단계 Task 9 방식), `/.well-known/jwks.json` 200. 기동 후 종료·컨테이너 정리.
- [ ] **Step 4: 커밋** — `refactor(passkey-app): 컨트롤러·진입점 Kotlin 화 — 본 코드 Java 0`

### Task 6: 단위·슬라이스 테스트 Kotlin 화

**Files (Java 삭제 → `src/test/kotlin` 동일 패키지):** `security/*Test`(ApiKeyAuthFilterTest, ApiKeyAuthFilterScopeTest, RateLimitFilterTest, ApiKeyScopeResolverTest, ProblemJsonTest), `config/*Test`(PasskeyAppJacksonConfigTest, PasskeyAppHttpConvertersConfigTest, ForwardHeadersConfigTest, LogLevelSnapshotTest), `api/v1/rp/JwksControllerTest`, `api/v1/rp/dto/RegistrationStartRequestValidationTest`, `fido2/challenge/{ChallengeStoreTest, ChallengeStoreFormatCompatTest}`, `fido2/**/*ServiceTest` 계열 7개, `fido2/mds/MdsVerifierTest`, `fido2/credential/CredentialSelfServiceTest`, Task 3·4 에서 추가한 특성/프록시 테스트.

- [ ] **Step 1: 전환** — 테스트 이름(백틱 한글 이름 허용)·단언 의미·입력값 1:1. Mockito → mockito-kotlin(`mock()`, `whenever`, `verify`, `any()`), `@MockitoBean` 유지.
- [ ] **Step 2: 테스트 수 동일성** — 전환 전후 `:passkey-app:test` 의 테스트 케이스 수와 이름 목록 비교(JUnit XML) → 누락 0.
- [ ] **Step 3: 실행** — `./gradlew :passkey-app:test --continue --max-workers=1` 실패 0.
- [ ] **Step 4: 커밋** — `test(passkey-app): 단위·슬라이스 테스트 Kotlin 화`

### Task 7: 통합 테스트·테스트 지원 코드 Kotlin 화

**Files:** `fido2/{Fido2EndToEndIT, Fido2TestAuthenticator}`, `contract/{ApiContractIT, JsonShape, RequestDtoMissingFieldContractIT}`, `sdk/SdkClientEndToEndIT`, `support/PasskeyAppItSupport`, `security/ApiKeyLookupServiceIT`.

- [ ] **Step 1: 전환** — `JsonShape` 토큰화 규칙·스냅샷 비교 로직 1:1, 기록 모드(`contract.record`) 동작 동일. 스냅샷 파일 무수정.
- [ ] **Step 2: 테스트 수 동일성** (Task 6 Step 2 와 같은 방식) + `find passkey-app/src -name '*.java'` 결과 0.
- [ ] **Step 3: 실행** — `./gradlew :passkey-app:test --continue --max-workers=1` 실패 0, 계약 테스트 2회 연속 통과.
- [ ] **Step 4: 커밋** — `test(passkey-app): 통합 테스트·지원 코드 Kotlin 화 — passkey-app Java 0`

### Task 8: 마무리 — 문서·이미지·E2E·전체 회귀

**Files:** `README.md`(스택: passkey-app Kotlin 2.4), `docs/dev-setup.md`(Kotlin 관련 있으면), `docs/backlog.md`(Kotlin 카탈로그 백로그 항목 해소 표시), `docs/superpowers/notes/kotlin-conversion-patterns.md`(3단계 admin-app 용 패턴 메모: 빌드 설정, DTO nullable 규칙, 접근자 함정, 프록시 확인, 테스트 전환 규칙 — 1쪽 이내)

- [ ] **Step 1: Docker 이미지** — passkey-app amd64 빌드(`NO_SAVE=1 scripts/build-images.sh` 또는 `docker build --platform linux/amd64 -f passkey-app/Dockerfile .`) 성공 + 컨테이너 기동해 `/.well-known/jwks.json` 200(필요 env 는 더미, 레포에 기록 금지).
- [ ] **Step 2: 로컬 E2E** — passkey-app(Kotlin) + rp-app 기동, Playwright 가상 인증기로 등록·로그인 1회 성공(1단계 Task 9 방식). 정리.
- [ ] **Step 3: 전체 회귀** — 6모듈 `test --continue --max-workers=1` → 기준선(Global Constraints) 대비 새 실패 0.
- [ ] **Step 4: 문서·패턴 메모 커밋** — `docs: passkey-app Kotlin 전환 반영 + admin-app 용 전환 패턴 메모`
