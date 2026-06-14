# Kotlin 마이그레이션 Phase 2 — sdk-java Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** sdk-java 모듈의 main Java 24개 파일을 Kotlin으로 빅뱅 이식하되, **외부 Java 소비자가 쓰는 공개 API를 깨지 않고**(record식 접근자 `config.baseUrl()` 보존) 동작·기존 Java 테스트를 무변경 유지한다.

**Architecture:** 모듈 단위 빅뱅. 공개 표면(dto/envelope/exception/PasskeyClientConfig/PasskeyClient)은 Kotlin data class로 옮기되 `@get:JvmName`/`@JvmStatic`/`const`로 Java 호출 호환을 유지한다. 테스트(src/test/java)는 Java로 유지 — Java 테스트가 record식 접근자로 Kotlin을 호출하는 것 자체가 공개 API 호환의 자동 검증이 된다. 선행 Phase 1(rp-app, 이미 Kotlin)이 Kotlin sdk-java를 소비하므로 내부 통합도 함께 검증된다.

**Tech Stack:** Kotlin 2.0.21, kotlin-jvm + kotlin-spring, Spring Boot 3.5.14 BOM(spring-web), nimbus-jose-jwt, jackson-module-kotlin, slf4j(직접). `java-library` + `maven-publish` 유지.

**작업 위치:** 워크트리 `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/kotlin-migration`, 브랜치 `worktree-kotlin-migration`. 메인 repo 작업 금지.

**선행 조건:** Phase 1(rp-app Kotlin) 완료·머지된 상태. 참고 스펙: `docs/superpowers/specs/2026-06-14-rp-app-sdk-java-kotlin-migration-design.md`. 공통 변환 레시피 R1~R7은 Phase 1 plan 참조.

---

## 사전 확인된 사실

- sdk-java는 `java-library`+`maven-publish` → 외부 Java 소비자 존재. 공개 API Java 상호운용 필수.
- main 24개. record 14개(dto 8/envelope 3/IdTokenClaims/PasskeyClientConfig 등), `@Slf4j` 3개(PasskeyClient, RedactingRequestInterceptor, IdTokenVerifier).
- 공개 정적 멤버 3개: `PasskeyClient.of(config)`, `PasskeyClientConfig.defaults(baseUrl, supplier)`, `PasskeyClientConfig.MDC_TRACE_ID_KEY`.
- test 3개(Java): PasskeyClientContractIT(WireMock 계약, JSON 6개), IdTokenVerifierAlgTest(JWS alg), DynamicApiKeyHeaderIT.
- PoC 확인: data class 전환 시 `config.baseUrl()`(record 접근자) → `cannot find symbol`. `@get:JvmName("baseUrl")`로 해결.

## 공개 API 호환 레시피 (Phase 2 핵심 — 외부 Java 소비 보존)

**J1. 공개 data class — record식 접근자 보존**
```java
// Java record (외부가 config.baseUrl() 로 호출 중)
public record PasskeyClientConfig(URI baseUrl, Supplier<String> apiKeySupplier, ...) { ... }
```
```kotlin
data class PasskeyClientConfig(
    @get:JvmName("baseUrl") val baseUrl: URI,
    @get:JvmName("apiKeySupplier") val apiKeySupplier: Supplier<String>,
    @get:JvmName("connectTimeout") val connectTimeout: Duration = Duration.ofSeconds(3),
    @get:JvmName("readTimeout") val readTimeout: Duration = Duration.ofSeconds(10),
    @get:JvmName("jwksCacheTtl") val jwksCacheTtl: Duration = Duration.ofMinutes(5),
    @get:JvmName("clock") val clock: Clock = Clock.systemUTC(),
    @get:JvmName("traceIdProvider") val traceIdProvider: Supplier<String> = Supplier { MDC.get(MDC_TRACE_ID_KEY) },
) {
    companion object {
        const val MDC_TRACE_ID_KEY: String = "traceId"   // public static final String 보존

        @JvmStatic
        fun defaults(baseUrl: URI, apiKeySupplier: Supplier<String>): PasskeyClientConfig =
            PasskeyClientConfig(baseUrl, apiKeySupplier)   // 나머지는 기본 인자(원본 defaults가 null→기본값과 동일 결과)
    }
}
```
- `@get:JvmName("baseUrl")` → JVM 메서드명이 `baseUrl()`(record식)이 됨. 외부 Java `config.baseUrl()` 그대로 동작. Kotlin 내부는 `.baseUrl` 프로퍼티 접근.
- **주의**: data class는 기본적으로 `getBaseUrl()`+`component1()` 생성. `@get:JvmName`은 `getBaseUrl()`을 `baseUrl()`로 대체. componentN은 Kotlin 전용이라 무관.
- 원본 컴팩트 생성자의 `Objects.requireNonNull(baseUrl)`은 Kotlin non-null 타입(`URI`, 플랫폼이 아닌 선언)이 보장. null 기본값 치환(connectTimeout 등)은 기본 인자로 동일.

**J2. 공개 정적 팩토리 → companion @JvmStatic** (PasskeyClient.of)
```kotlin
class PasskeyClient private constructor(...) {
    companion object {
        @JvmStatic
        fun of(config: PasskeyClientConfig): PasskeyClient { ... }
    }
}
```
- 외부 Java `PasskeyClient.of(config)` 그대로.

**J3. 공개 exception 클래스 — JavaBean getter 유지**
```java
public class PasskeyApiException extends RuntimeException {
    public int getHttpStatus() {...} public String getCode() {...} ...
}
```
```kotlin
class PasskeyApiException(
    val httpStatus: Int, val code: String?, val traceId: String?,
    val fieldErrors: List<EnvelopeFieldError>?, message: String?,
) : RuntimeException(message) {
    // val 프로퍼티 → Java에서 getHttpStatus()/getCode() 자동 생성 = 원본과 동일. @JvmName 불필요.
}
```
- exception getter는 원래 JavaBean 스타일(`getHttpStatus()`)이라 Kotlin val이 그대로 생성 → **@JvmName 불필요**. (record가 아닌 일반 클래스였으므로.)
- 원본 생성자 시그니처/필드 순서 보존(외부가 catch 후 getter 호출).

**J4. 공개 DTO record (8 dto + 3 envelope + IdTokenClaims)**
- 외부가 응답 DTO의 접근자를 record식(`resp.publicKey()`)으로 쓰는지 확인 필요. **내부 소비자(rp-app, 이미 Kotlin)는 프로퍼티 접근으로 함께 변환됨.** 외부 Java가 이 DTO 접근자를 쓰면 J1처럼 `@get:JvmName` 적용. 보수적으로 **모든 공개 record DTO에 `@get:JvmName` 적용**(외부 호환 안전).
- Jackson 역직렬화: `@JsonProperty`/`@JsonCreator`/`@JsonIgnoreProperties` 보존(`@field:`/클래스 레벨). jackson-module-kotlin이 data class 역직렬화 지원.

## Task 1: 빌드 통합 — sdk-java에 Kotlin 적용

**Files:** `sdk-java/build.gradle.kts` (루트 plugins/카탈로그는 Phase 1에서 이미 추가됨).

- [ ] **Step 1: `sdk-java/build.gradle.kts` plugins에 Kotlin 추가**
```kotlin
plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}
```
- (kotlin-spring은 sdk-java에 @Configuration/@Bean이 없으면 불필요하나, 일관성+무해하므로 적용. 실제 필요한 건 kotlin-jvm. 확인: sdk-java에 Spring 빈 없음 → kotlin-spring 생략 가능. 일관성 위해 둘 다 적용 권장.)

- [ ] **Step 2: dependencies에 jackson-module-kotlin + kotlin stdlib**
```kotlin
    api("com.fasterxml.jackson.module:jackson-module-kotlin")   // 공개 표면이면 api, 내부면 implementation
    implementation(kotlin("stdlib"))
```
- jackson-module-kotlin이 공개 API의 일부(역직렬화 동작)면 `api`, 아니면 `implementation`. sdk DTO 역직렬화가 소비자 쪽에서 일어나면 `api`가 안전.

- [ ] **Step 3: 컴파일 확인 (코드 변경 0)**
```bash
./gradlew :sdk-java:compileJava -q
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 커밋**
```bash
git add sdk-java/build.gradle.kts
git commit -m "build(kotlin): sdk-java에 kotlin-jvm + jackson-module-kotlin (Phase 2)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

## Task 2: 공개 표면 — DTO/envelope/exception/Config (J1·J3·J4)

**Files (Java→Kotlin 이동):** `src/main/kotlin` 동일 패키지.
- dto/ 8개, envelope/ 3개, idtoken/IdTokenClaims (J4)
- exception/ 5개 (J3)
- PasskeyClientConfig (J1)

- [ ] **Step 1: `mkdir -p sdk-java/src/main/kotlin/com/crosscert/passkey/sdk`**
- [ ] **Step 2: dto/envelope/IdTokenClaims를 J4로 변환** — data class + `@get:JvmName`(공개 호환) + Jackson 어노테이션 `@field:` 이식. record 컴팩트 생성자(검증) 있으면 init/기본인자 보존.
- [ ] **Step 3: exception 5개를 J3로 변환** — `: RuntimeException(message)`, val 프로퍼티(getter 자동), 생성자 시그니처 보존.
- [ ] **Step 4: PasskeyClientConfig를 J1로 변환** — `@get:JvmName` 7개 프로퍼티, companion `const MDC_TRACE_ID_KEY` + `@JvmStatic defaults()`.
- [ ] **Step 5: 원본 .java 삭제, 컴파일** `./gradlew :sdk-java:compileKotlin -q` (호출처 Java가 남아 있으면 에러 가능 → Task 3에서 함께. 빅뱅 묶음 실행 권장.)

## Task 3: 내부 구현 — Client/Verifier/Cache/Interceptors (R2·J2)

**Files:** PasskeyClient(J2 of()), IdTokenVerifier, JwksCache, internal/PasskeyResponseErrorHandler, RedactingRequestInterceptor, TraceIdPropagationInterceptor.

- [ ] **Step 1: PasskeyClient 변환** — private 생성자 + companion `@JvmStatic of()`(J2), RestClient/RestTemplate 빌드, config 접근은 Kotlin이므로 `.baseUrl` 프로퍼티. 4개 공개 메서드(registrationStart/Finish, authenticationStart/Finish) + verifyIdToken 시그니처 보존(외부 호출). R2 로깅.
- [ ] **Step 2: IdTokenVerifier 변환** — JWS/nimbus, RS256 핀(`if (JWSAlgorithm.RS256 != header.algorithm) throw`), `key as? RSAKey ?: throw`, 체크예외 ParseException은 try-catch(Kotlin 멀티캐치 `catch (e: ...)` 또는 `catch (e: Exception)` 분기). amr 캐스트 `@Suppress("UNCHECKED_CAST")`. R2 로깅.
- [ ] **Step 3: JwksCache 변환** — AtomicReference<Snapshot> (Snapshot은 private data class), TTL/fetch, nullable JWK.
- [ ] **Step 4: 인터셉터 3개 변환** — ClientHttpRequestInterceptor 구현, RedactingRequestInterceptor(R2 로깅+민감정보 마스킹), TraceId 전파(MDC), ResponseErrorHandler(envelope 파싱, 4xx/5xx → PasskeyApiException 등 매핑 — 예외 매핑 로직 동작 보존).
- [ ] **Step 5: 원본 .java 삭제, 전체 컴파일** `./gradlew :sdk-java:compileKotlin :sdk-java:compileJava -q`

## Task 4: Java main 잔존 0 + Lombok import 0

- [ ] **Step 1:** `find sdk-java/src/main/java -name "*.java"` → 출력 없음.
- [ ] **Step 2:** `grep -rn "import lombok" sdk-java/src/main` → 출력 없음.
- [ ] **Step 3:** `find sdk-java/src/main/kotlin -name "*.kt" | wc -l` → ~24 (top-level 통합 시 약간 적을 수 있음).

## Task 5: 통합 검증 — Java 테스트 + 공개 API 시그니처 + publish

- [ ] **Step 1: 기존 Java 테스트 컴파일 (공개 API 호환 자동 검증 — 핵심)**
```bash
./gradlew :sdk-java:compileTestJava -q
```
Expected: BUILD SUCCESSFUL. Java 테스트가 record식 접근자(`config.baseUrl()`, `resp.publicKey()` 등)와 정적 팩토리(`PasskeyClientConfig.defaults`, `PasskeyClient.of`)로 Kotlin을 호출 → `@get:JvmName`/`@JvmStatic`이 맞으면 통과, 틀리면 컴파일 에러. **이게 외부 Java 호환의 직접 증거.**

- [ ] **Step 2: sdk-java 테스트 실행**
```bash
./gradlew :sdk-java:test
```
Expected: BUILD SUCCESSFUL. PasskeyClientContractIT(WireMock 계약 JSON 6개) = 와이어 레벨 동작 불변. IdTokenVerifierAlgTest = JWS 검증 동작. DynamicApiKeyHeaderIT = 헤더 supplier 동작.

- [ ] **Step 3: rp-app(Kotlin 소비자) 통합 검증**
```bash
./gradlew :rp-app:test
```
Expected: BUILD SUCCESSFUL. Phase 1의 Kotlin rp-app이 Kotlin sdk-java를 소비 → 내부 통합 정상.

- [ ] **Step 4: 공개 API 시그니처 보존 점검 (javap)**
```bash
# 전환 전(머지된 Phase1 시점, sdk-java는 아직 Java)과 후 비교 — 임시 워크트리로 before 확보
# 핵심 공개 클래스의 public 메서드 시그니처가 record식 이름 보존하는지
javap -p sdk-java/build/classes/kotlin/main/com/crosscert/passkey/sdk/PasskeyClientConfig.class | grep -E "baseUrl|apiKeySupplier|defaults|MDC_TRACE"
```
Expected: `public java.net.URI baseUrl()`, `public ... defaults(...)`, `MDC_TRACE_ID_KEY` 가 보임(getBaseUrl 아님). @JvmName 적용 확인.

- [ ] **Step 5: publishToMavenLocal — 배포 산출물 정상**
```bash
./gradlew :sdk-java:publishToMavenLocal
```
Expected: BUILD SUCCESSFUL. JAR + sources JAR 생성. Kotlin 클래스 포함, Lombok 의존 0.

- [ ] **Step 6: codex 리뷰** — 전체 diff `/codex:review`. 특히 공개 API 호환·예외 매핑·JWS 검증 동작 보존.

- [ ] **Step 7: 최종 커밋**
```bash
git add sdk-java
git commit -m "refactor(kotlin): sdk-java main 24개 Java→Kotlin 빅뱅 이식 — @JvmName 공개 API 호환, 동작 무변경 (Phase 2)

외부 Java 소비자 호환: record식 접근자(@get:JvmName) + 정적 팩토리(@JvmStatic) 보존.
Java 테스트 유지로 호환 자동 검증. 계약 테스트로 와이어 동작 고정.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

## 실행 노트 (빅뱅)

sdk-java 24개도 상호 참조가 있어 Task 2~3 중간 컴파일이 실패할 수 있다. **묶음 실행 권장**: Task 2~3을 한 단위로 전부 변환 → Task 5에서 첫 컴파일·테스트. 최종 기준은 Task 5의 검증 게이트(Java 테스트 통과 + 공개 시그니처 보존 + publish).

## Self-Review

- **스펙 커버리지**: §3 공개 API @JvmName 전략=J1~J4+Task2. §4 Phase2 변환=Task2~3. §5 검증(Java테스트→Kotlin, javap 시그니처, publish)=Task5. 까다로운 로직(IdTokenVerifier JWS, 인터셉터 예외매핑)=Task3. ✅
- **placeholder**: J1~J4에 실제 @JvmName/@JvmStatic/const 코드. exception은 @JvmName 불필요 근거 명시. ✅
- **타입 일관성**: `@get:JvmName`/companion `@JvmStatic`/`const`가 공개 표면 전반 일관. exception은 val→JavaBean getter. ✅
- **검증**: Java 테스트 컴파일이 공개 호환의 직접 증거. javap로 record식 이름 보존 확인. ✅
