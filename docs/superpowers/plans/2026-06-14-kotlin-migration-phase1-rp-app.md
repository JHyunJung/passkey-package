# Kotlin 마이그레이션 Phase 1 — rp-app Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** rp-app 모듈의 main Java 32개 파일을 Kotlin으로 빅뱅 이식하되, 동작·기존 Java 테스트 무변경을 유지한다.

**Architecture:** 모듈 단위 빅뱅 — rp-app의 `src/main/java`를 통째로 `src/main/kotlin`으로 옮기며 Lombok을 제거. 테스트(`src/test/java`)는 Java로 유지하여, Java 테스트가 Kotlin main을 호출하는 것으로 상호운용·동작을 검증한다. rp-app은 leaf 모듈이라 공개 API 호환 제약이 없다.

**Tech Stack:** Kotlin 2.0.21, kotlin-jvm + kotlin-spring 플러그인, Spring Boot 3.5.14, jackson-module-kotlin, slf4j(직접), Gradle 8.10.

**작업 위치:** 워크트리 `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/kotlin-migration`, 브랜치 `worktree-kotlin-migration`. 모든 명령은 이 경로를 cwd로. 메인 repo에서 작업 금지.

**참고 스펙:** `docs/superpowers/specs/2026-06-14-rp-app-sdk-java-kotlin-migration-design.md`

---

## 사전 확인된 사실 (조사·PoC로 실증)

- rp-app은 leaf(다른 모듈이 의존 안 함), JPA Entity 없음 → kotlin-jpa 불필요.
- main 32개 파일. record 18개(DTO/config/response), `@Slf4j` 6개 파일, `@Bean` 보유 @Configuration 2개(WebSecurityConfig, PasskeyClientConfiguration).
- test 8개(Java). RpAppSmokeIT는 webauthn4j-test로 앱 컨텍스트 로드 → kotlin-spring 누락 시 여기서 부팅 실패로 포착.
- PoC 확인: kotlin-spring 없으면 @Bean 클래스 final → CGLIB 실패. Kotlin엔 record 없음 → data class 사용.

## 변환 레시피 (모든 Task 공통 — 파일 유형별)

**R1. record DTO → data class** (web/dto/*, common/response/*, RpAppUser)
```java
// Java
public record RegisterStartReq(@NotBlank String username, @NotBlank String displayName) {}
```
```kotlin
// Kotlin — Bean Validation 어노테이션은 use-site target @field: 필수
data class RegisterStartReq(
    @field:NotBlank val username: String,
    @field:NotBlank val displayName: String,
)
```
- `@JsonProperty`가 있으면 `@field:JsonProperty("x")`. rp-app은 leaf라 record 접근자 호환(@JvmName) 불필요 — 호출처를 함께 Kotlin화하므로 `.username` 프로퍼티 접근으로 변경.

**R2. @Slf4j → slf4j 직접** (6개 파일)
```java
@Slf4j
public class X { ... log.info(...) }
```
```kotlin
class X {
    companion object { private val log = org.slf4j.LoggerFactory.getLogger(X::class.java) }
    // ... log.info(...) 동일
}
```
- 또는 톱레벨 `private val log = LoggerFactory.getLogger(X::class.java)` (파일당 1클래스면 동일 효과). companion object 방식 권장(정적 final 보존).

**R3. @RequiredArgsConstructor / 수동 생성자 → Kotlin primary constructor**
```java
@RequiredArgsConstructor
public class WebAuthnController { private final PasskeyClient passkey; ... }
```
```kotlin
@RestController
class WebAuthnController(
    private val passkey: PasskeyClient,
    private val users: InMemoryUserStore,
    private val props: PasskeyProperties,
    private val relay: RegRelayCodec,
) { ... }
```

**R4. @Configuration + @Bean → kotlin-spring이 자동 open** (WebSecurityConfig, PasskeyClientConfiguration)
```kotlin
@Configuration
class WebSecurityConfig(private val cors: CorsProperties) {
    @Bean
    fun chain(http: HttpSecurity): SecurityFilterChain {   // Kotlin은 throws 선언 불필요
        // 람다: a -> a... 는 Kotlin 람다 { a -> a... } 또는 it 사용
    }
}
```
- kotlin-spring 플러그인이 @Configuration/@Bean/@Component 클래스를 자동으로 open 처리. **플러그인 없으면 부팅 실패.**

**R5. @ConfigurationProperties record → data class + 기본 인자/init** (PasskeyProperties, CorsProperties, RelayProperties, WellKnownProperties)
```java
@ConfigurationProperties(prefix = "rp.relay")
public record RelayProperties(String secret, Duration ttl) {
    public RelayProperties {
        if (secret == null || secret.isBlank()) secret = RelayKeyGuard.DEMO_SECRET;
        if (ttl == null) ttl = Duration.ofMinutes(5);
    }
}
```
```kotlin
@ConfigurationProperties(prefix = "rp.relay")
data class RelayProperties(
    val secret: String = RelayKeyGuard.DEMO_SECRET,
    val ttl: Duration = Duration.ofMinutes(5),
) {
    // blank 폴백은 기본값(null→기본)만으론 부족 → init에서 보정 불가(val). 
    // Spring 바인딩은 누락 시 기본 인자 사용. blank("") 입력 시 폴백이 필요하면
    // 보조 생성자 또는 companion 팩토리로 정규화. 원본 동작(blank→DEMO) 보존 위해:
    companion object {
        // 바인딩 후 정규화가 필요하면 @ConstructorBinding 대신 검증은 그대로 두되,
        // 원본은 blank도 DEMO로 치환했으므로 init 블록에서 require 대신 보정:
    }
    init {
        // val 재할당 불가 → blank 폴백은 nullable 받아 보조 처리. 아래 Task에서 파일별 확정.
    }
}
```
- **주의(설계 판단 필요 지점)**: Kotlin `val`은 init에서 재할당 불가. 원본의 "blank→DEMO 치환"을 보존하려면 (a) 생성자 파라미터를 nullable로 받고 `val secret: String = if (raw.isNullOrBlank()) DEMO else raw` 형태의 보조 생성자, 또는 (b) `@ConstructorBinding`과 함께 nullable 입력 정규화. 각 properties 파일 Task에서 원본 로직을 정확히 보존하도록 개별 처리하고, 검증은 해당 properties를 읽는 통합 테스트로 확인.

**R6. static 필드/상수 → companion object** (Base64 encoder, Pattern, 상수)
```kotlin
companion object {
    private val ENC = Base64.getUrlEncoder().withoutPadding()
    const val DEMO_SECRET = "..."   // 컴파일 상수면 const, 아니면 @JvmStatic val
}
```

**R7. nullable·Optional·체크예외**
- Java `Optional.ofNullable(x).filter(..).orElseGet(..)` → Kotlin `x?.takeIf { .. } ?: default`
- fail-closed null 가드(RegRelayCodec decode의 4-필드 null 체크)는 **그대로 보존** — Kotlin nullable로 표현하되 동작 동일.
- Java 체크예외(`throws Exception`)는 Kotlin에서 선언 불필요(호출부 동일 동작).

## 빌드 통합 (Task 1에서 일괄)

이미 PoC로 검증된 구성. (브레인스토밍 PoC에서 일부 적용 후 원복됨 — Task 1에서 정식 적용.)

---

## Task 1: 빌드 통합 — Kotlin 플러그인 + jackson-module-kotlin

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts` (루트)
- Modify: `rp-app/build.gradle.kts`

- [ ] **Step 1: `gradle/libs.versions.toml`에 Kotlin 추가**

`[versions]`에 추가:
```toml
kotlin = "2.0.21"
```
`[plugins]`에 추가:
```toml
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-spring = { id = "org.jetbrains.kotlin.plugin.spring", version.ref = "kotlin" }
```

- [ ] **Step 2: 루트 `build.gradle.kts` plugins에 `apply false` 선언**

```kotlin
plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dep.mgmt) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
}
```
- subprojects {} 블록은 건드리지 않는다(Kotlin은 모듈별 개별 적용; Lombok 일괄 적용은 그대로 두되 Step 3에서 rp-app만 무효화).

- [ ] **Step 3: `rp-app/build.gradle.kts`에 Kotlin 적용 + Lombok 제거 + jackson-module-kotlin**

plugins 블록을 다음으로:
```kotlin
plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}
```
dependencies 블록 맨 위에 추가(Lombok 무효화 + jackson kotlin):
```kotlin
    // rp-app은 Kotlin 전환 완료 — Lombok 불필요. 루트 subprojects가 일괄 적용한
    // compileOnly/annotationProcessor lombok 을 이 모듈에선 쓰지 않는다(코드에 Lombok 0).
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(kotlin("stdlib"))
```
- (Lombok 의존 자체는 루트가 넣지만 코드에서 안 쓰면 무해. 명시 제거가 필요하면 configurations에서 제외 가능하나 YAGNI — 코드에서 Lombok import 0이면 충분.)

- [ ] **Step 4: 컴파일 가능 확인 (코드 변경 0, 플러그인만 추가)**

Run: `./gradlew :rp-app:compileJava -q`
Expected: BUILD SUCCESSFUL. 아직 Kotlin 소스 없음, 기존 Java 그대로 컴파일.

- [ ] **Step 5: 커밋**
```bash
git add gradle/libs.versions.toml build.gradle.kts rp-app/build.gradle.kts
git commit -m "build(kotlin): rp-app에 kotlin-jvm+kotlin-spring 플러그인 + jackson-module-kotlin (Phase 1)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 순수 DTO/value record → data class (15개, 의존 없는 leaf 타입 먼저)

**Files (Modify→Kotlin 이동):** `src/main/java` → `src/main/kotlin` 동일 패키지로.
- web/dto/: LoginCompleteReq, LoginOptionsResp, LoginResultResp, LoginStartReq, RegisterCompleteReq, RegisterOptionsResp, RegisterStartReq (7)
- common/response/: ApiResponse, ErrorDetail, FieldError, PageResponse (4)
- common/exception/: BusinessException, ErrorCode (2)
- user/RpAppUser (1)
- config/CorsProperties (1)

- [ ] **Step 1: `src/main/kotlin` 디렉토리 생성**
```bash
mkdir -p rp-app/src/main/kotlin/com/crosscert/passkey/rpapp
```

- [ ] **Step 2: 각 파일을 레시피 R1(record→data class)로 변환, Java 원본 삭제**

각 record를 data class로. 예 (`web/dto/RegisterStartReq`):
```kotlin
package com.crosscert.passkey.rpapp.web.dto

import jakarta.validation.constraints.NotBlank

data class RegisterStartReq(
    @field:NotBlank val username: String,
    @field:NotBlank val displayName: String,
)
```
- `ErrorCode`가 enum이면 Kotlin enum class로(생성자 인자 보존). `BusinessException`이 exception이면 `class BusinessException(...) : RuntimeException(...)`.
- `@JsonProperty`는 `@field:JsonProperty`. `@JsonInclude`/`@JsonIgnoreProperties`는 클래스에 그대로.
- 각 원본 `.java` 삭제: `git rm rp-app/src/main/java/.../X.java` (또는 rm 후 새 .kt 생성).

- [ ] **Step 3: 컴파일**

Run: `./gradlew :rp-app:compileKotlin :rp-app:compileJava -q`
Expected: BUILD SUCCESSFUL. (아직 Java 호출처가 남아 있으면 `.username` vs `.username()` 불일치로 에러 날 수 있음 — 이 경우 호출처도 이 Task에서 함께 수정하거나, 호출처가 다음 Task의 클래스면 그 Task에서 일괄 처리. leaf DTO부터 하므로 호출처는 주로 Controller/Service = Task 4~5.)
- **중요**: Java 호출처가 record 접근자 `.username()`를 쓰는데 data class로 바꾸면 `getUsername()`이 되어 깨진다. rp-app은 빅뱅이므로, DTO를 Kotlin화하는 순간 그 DTO를 쓰는 Java 파일도 같은 PR에서 Kotlin화되어야 한다. 따라서 **컴파일 에러가 나면 그 호출처 파일을 식별**해 다음 Task 순서를 조정하거나 함께 변환. 안전하게: Task 2~6을 한 번에 끝내고 마지막에 컴파일하는 전략도 가능(빅뱅).

- [ ] **Step 4: 커밋** (또는 Task 6까지 묶어 마지막에 한 번 — 빅뱅 특성상 중간 컴파일이 안 될 수 있음)
```bash
git add rp-app/src/main
git commit -m "refactor(kotlin): rp-app DTO/response/exception → data class (Phase 1)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```
- **빅뱅 주의**: rp-app은 32개가 상호 참조하므로 Task 2~6의 중간 컴파일이 실패할 수 있다. 그 경우 Task 2~6을 **하나의 작업 단위로 묶어** 전부 변환 후 Step 컴파일/커밋한다. 서브에이전트 실행 시 "rp-app main 32개 전부 Kotlin 변환 → 컴파일 → 테스트 → 커밋"을 한 Task로 수행해도 된다(아래 Task 7이 통합 검증).

---

## Task 3: config properties + 빈 (R5 + R4)

**Files:** config/PasskeyProperties, RelayProperties, WellKnownProperties (R5) / WebSecurityConfig, PasskeyClientConfiguration (R4) / RelayKeyGuard, ReloadableApiKeySupplier(R2+R6).

- [ ] **Step 1: @ConfigurationProperties 3개를 R5로 변환** — 각 원본의 컴팩트 생성자 폴백 로직을 정확히 보존.
  - `RelayProperties`: blank→DEMO_SECRET 폴백. nullable 입력 정규화:
    ```kotlin
    @ConfigurationProperties(prefix = "rp.relay")
    data class RelayProperties(
        val secret: String = RelayKeyGuard.DEMO_SECRET,
        val ttl: Duration = Duration.ofMinutes(5),
    ) {
        // 누락은 기본값. 명시적 blank("")가 들어와도 DEMO로 치환해야 원본과 동일.
        // → 보조 정규화: Spring이 바인딩한 값을 그대로 쓰되, blank 방어는 이 값을 읽는
        //   RelayKeyGuard/RegRelayCodec 쪽에서 이미 DEMO 비교로 처리되는지 원본 확인.
        //   원본이 record 컴팩트 생성자에서 치환했으므로 동일 위치 보존 위해:
        init { require(ttl > Duration.ZERO) }  // 원본에 없던 검증 추가 금지 — 원본 로직만 보존
    }
    ```
    원본을 다시 읽고(`config/RelayProperties.java`) blank 폴백을 보존하는 최종 형태를 확정. val 재할당 불가 문제는 nullable 파라미터 + 기본값 표현식으로 해결:
    ```kotlin
    data class RelayProperties(secret: String?, ttl: Duration?) {
        val secret: String = if (secret.isNullOrBlank()) RelayKeyGuard.DEMO_SECRET else secret
        val ttl: Duration = ttl ?: Duration.ofMinutes(5)
    }
    ```
    (이 형태가 원본 컴팩트 생성자와 동작 동일. data class의 component/equals는 프로퍼티 기준.)
- [ ] **Step 2: WebSecurityConfig/PasskeyClientConfiguration을 R4로 변환** — @Bean 메서드, kotlin-spring이 open 처리. 람다 변환 주의.
- [ ] **Step 3: RelayKeyGuard(R6 상수), ReloadableApiKeySupplier(R2 로그+@Volatile+AtomicReference) 변환.**
  - `@Volatile`은 `kotlin.jvm.Volatile`. AtomicReference는 그대로.
- [ ] **Step 4: 컴파일** `./gradlew :rp-app:compileKotlin -q`
- [ ] **Step 5: 커밋** (또는 통합 Task로 묶음)

---

## Task 4: 까다로운 로직 — relay/filter/store (R2+R6+R7)

**Files:** web/relay/RegRelayCodec, web/RequestLoggingFilter, common/filter/TraceIdFilter, web/SecretMaskingConverter, user/InMemoryUserStore.

- [ ] **Step 1: RegRelayCodec 변환** — HMAC, Base64(companion), `MessageDigest.isEqual(ByteArray, ByteArray)` 상수시간 비교 보존, decode의 fail-closed 4-필드 null 가드 보존(R7). 내부 record `ObjectNodePayload`는 private data class.
- [ ] **Step 2: RequestLoggingFilter/TraceIdFilter 변환** — MDC.put/remove try-finally 보존, R2 로깅, System.nanoTime 그대로.
- [ ] **Step 3: SecretMaskingConverter 변환** — logback `ClassicConverter` 상속, static Pattern → companion object, regex replaceAll 람다.
- [ ] **Step 4: InMemoryUserStore 변환** — @Value 생성자 주입(`@Value("\${...}") file: String`), ConcurrentMap 필드(companion 아닌 인스턴스), Path.of(file) 변환 로직 보존, JSON 파일 핫리로드. R2 로깅.
- [ ] **Step 5: 컴파일** `./gradlew :rp-app:compileKotlin -q`
- [ ] **Step 6: 커밋** (또는 통합)

---

## Task 5: 컨트롤러 + 앱 진입점 (R3+R1 호출처)

**Files:** web/WebAuthnController, web/WellKnownController, web/PageController, common/exception/GlobalExceptionHandler, RpAppApplication.

- [ ] **Step 1: 컨트롤러 3개 변환** — R3(생성자 주입), DTO 접근을 `.username()` → `.username` 프로퍼티로. @RestController/@Controller/@GetMapping 등 어노테이션 보존. WebAuthnController는 무상태 릴레이 흐름(189줄) — 토큰 relay 바인딩 로직 동작 보존.
- [ ] **Step 2: GlobalExceptionHandler 변환** — @RestControllerAdvice, R2 로깅, @ExceptionHandler 메서드들.
- [ ] **Step 3: RpAppApplication 변환** —
  ```kotlin
  @SpringBootApplication
  @ConfigurationPropertiesScan
  class RpAppApplication
  fun main(args: Array<String>) { runApplication<RpAppApplication>(*args) }
  ```
  (원본의 @SpringBootApplication/@EnableConfigurationProperties 등 어노테이션 정확히 이식.)
- [ ] **Step 4: 전체 컴파일** `./gradlew :rp-app:compileKotlin :rp-app:compileJava -q` → Java(test 제외 main) 잔존 0 확인.
- [ ] **Step 5: 커밋**

---

## Task 6: Java main 잔존 0 확인 + Lombok import 0

- [ ] **Step 1: main에 Java 파일 잔존 없음 확인**
```bash
find rp-app/src/main/java -name "*.java" | grep -v "/build/"
```
Expected: 출력 없음(전부 Kotlin으로 이동).
- [ ] **Step 2: Lombok import 0 확인**
```bash
grep -rn "import lombok" rp-app/src/main --include="*.kt" --include="*.java"
```
Expected: 출력 없음.
- [ ] **Step 3: Kotlin 파일 수 = 원본 Java main 수(32) 확인**
```bash
find rp-app/src/main/kotlin -name "*.kt" | wc -l
```
Expected: 32 (또는 톱레벨 함수 통합으로 약간 적을 수 있음 — RpAppApplication의 main 함수가 클래스와 같은 파일이면 31).

---

## Task 7: 통합 검증 — 기존 Java 테스트가 Kotlin main 호출

**Files:** 변경 없음(검증만). test 8개는 Java 그대로.

- [ ] **Step 1: 기존 Java 테스트 컴파일 — 상호운용 검증 (핵심)**
```bash
./gradlew :rp-app:compileTestJava -q
```
Expected: BUILD SUCCESSFUL. **Java 테스트가 Kotlin main을 호출하므로, 공개 메서드/프로퍼티 접근이 깨지면 여기서 컴파일 에러.** 에러 나면: 테스트가 record 접근자(`.username()`)나 옛 시그니처를 쓰는 것 → 테스트는 Java 유지가 원칙이나, Kotlin 프로퍼티는 Java에서 `getUsername()`으로 보임. 테스트가 `.username()`(record)을 기대하면 **테스트의 그 호출만 `getUsername()`으로 수정**(테스트 로직 불변, 접근자 이름만). 이는 leaf 모듈이라 허용(외부 호환 제약 없음).

- [ ] **Step 2: rp-app 전체 테스트 실행**
```bash
./gradlew :rp-app:test
```
Expected: BUILD SUCCESSFUL. RpAppSmokeIT(앱 컨텍스트 로드) 통과 = kotlin-spring open 정상 = @Bean 부팅 성공. RegRelayCodecTest/InMemoryUserStoreTest/ReloadableApiKeySupplierTest = 까다로운 로직 동작 보존 확인. WebAuthnControllerTest/WellKnownControllerTest(@WebMvcTest+MockMvc) = 컨트롤러 동작.
- 실패 시: base(ececec9)에서 같은 테스트 돌려 pre-existing인지 확정. 신규 실패면 해당 로직 이식 오류 → 원본과 대조 수정.

- [ ] **Step 3: 앱 부팅 스모크 (선택, RpAppSmokeIT가 커버하면 생략 가능)**
```bash
./gradlew :rp-app:compileKotlin -q && echo "컴파일 OK"
```

- [ ] **Step 4: codex 리뷰** — 전체 diff에 대해 `/codex:review`. 동작 변경 의심 지점 점검.

- [ ] **Step 5: 최종 커밋** (Task별 커밋했으면 생략)
```bash
git add rp-app
git commit -m "refactor(kotlin): rp-app main 32개 Java→Kotlin 빅뱅 이식 — Lombok 제거, 동작 무변경 (Phase 1)

테스트(Java) 유지로 상호운용 검증. RpAppSmokeIT 앱 컨텍스트 로드 통과(kotlin-spring open).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 실행 노트 (빅뱅 특성)

rp-app 32개 파일은 상호 참조가 많아 **Task 2~6의 중간 컴파일이 실패할 수 있다**. 두 가지 실행 방식 중 택일:
- **(권장) 묶음 실행**: Task 2~6의 변환을 하나의 작업 단위로 수행(32개 전부 Kotlin화) → Task 7에서 첫 컴파일·테스트. 빅뱅 원칙에 부합.
- **점진 실행**: leaf 타입(Task 2)부터 변환하되, 호출처가 깨지면 그 호출처까지 같은 단계에서 변환. 중간 커밋은 컴파일 통과 시점에만.

어느 쪽이든 **Task 7의 검증 게이트(테스트 통과)가 최종 기준**이다.

## Self-Review (작성자 점검)

- **스펙 커버리지**: §3 빌드통합=Task1. §4 Phase1 변환=Task2~6(R1~R7 레시피). §5 검증(Java테스트→Kotlin호출)=Task7 Step1~2. §4 까다로운 로직(RegRelayCodec/ReloadableApiKeySupplier/SecretMaskingConverter)=Task4. @ConfigurationProperties=Task3. @Slf4j→slf4j=R2 전역. ✅
- **placeholder**: 변환 레시피 R1~R7에 실제 before/after 코드. 32개 전부의 완전 코드는 비현실적이라 유형별 레시피+파일 그룹으로 제공(빅뱅 묶음 실행 전제). RelayProperties는 val 재할당 함정까지 최종 형태 명시. ✅
- **타입 일관성**: data class 프로퍼티 접근(`.username`), @field: target, kotlin-spring open, slf4j companion 패턴이 전 Task 일관. ✅
- **검증**: 바이트코드 동등성(불가) 대신 컴파일+테스트. RpAppSmokeIT가 kotlin-spring 게이트. ✅
