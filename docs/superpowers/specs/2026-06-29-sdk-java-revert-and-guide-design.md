# SDK Kotlin → Java 환원 + 통합 가이드 문서 설계

- 날짜: 2026-06-29
- 대상 모듈: `sdk-java` (변환), `rp-app` (소비처 1곳만 수정)
- 산출물: ① 순수 Java SDK ② 외부 RP 개발자용 통합 가이드 `sdk-java/README.md`

## 1. 배경과 목표

`sdk-java` 모듈은 외부 RP(Relying Party)사에 배포되는 클라이언트 라이브러리다. 현재
24개 Kotlin 파일(782줄)로 되어 있으나, 외부 소비자가 순수 Java 환경에서 쓰는 라이브러리에
Kotlin 런타임 의존성(`kotlin-stdlib`)과 Kotlin↔Java 호환용 보일러플레이트가 얹혀 있어 이를
**순수 Java로 환원**한다.

핵심 통찰: 이 코드는 원래 Java였고(코드 주석이 "원본 Java record", "원본 Java 의
`Objects.requireNonNull`" 등을 반복 언급), Kotlin 마이그레이션 과정에서 Java 호환성을
보존하기 위해 `@JvmName`/`@JvmStatic`/`@JvmOverloads`/`@get:JsonProperty @param:JsonProperty`
3중 어노테이션/수동 `equals·hashCode·toString` 같은 군더더기가 붙었다. Java로 되돌리면 이
보일러플레이트가 **소멸**하므로, 단순 역번역이 아니라 정리(cleanup)에 가깝다.

목표:
1. `sdk-java`를 순수 Java로 환원하고 Kotlin 런타임 의존성을 제거한다.
2. 동작 동일성을 기존 테스트(이미 Java로 작성됨)로 보장한다.
3. 외부 RP 개발자가 SDK를 통합하는 end-to-end 가이드 문서를 작성한다.

### 비목표 (YAGNI)

- `rp-app` 모듈의 Kotlin→Java 환원 (Kotlin 유지. SDK 호출부 1곳만 Builder API에 맞춰 수정)
- SDK의 기능 추가/동작 변경 (순수 형태 변환 + 동작 보존)
- 공개 API의 의미적 재설계 (단, `PasskeyClientConfig` 생성 방식만 Builder로 개선 — 아래 4.3)

## 2. 변환 대상 인벤토리

`sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/` 하위 24개 `.kt` → 동일 경로의 `.java`
(`src/main/java/...`). 패키지/클래스명 동일.

| 패키지 | 파일 | 현재 형태 | 환원 형태 |
|---|---|---|---|
| `dto` | 8개 (RegistrationStart/Finish Request/Response, AuthenticationStart/Finish Request/Response) | `data class` + 3중 어노테이션 | Java `record` + `@JsonProperty` 1개 |
| `envelope` | 3개 (ApiResponseEnvelope, EnvelopeError, EnvelopeFieldError) | `data class` (제네릭 포함) | Java `record` (제네릭 `<T>` 포함) |
| `exception` | 5개 (PasskeyApiException 외 4개) | `open class`/`class` | Java `class` (상속 구조 보존) |
| `idtoken` | 3개 (IdTokenClaims, IdTokenVerifier, JwksCache) | class + data class | record(Claims) + class |
| `internal` | 3개 (PasskeyResponseErrorHandler, RedactingRequestInterceptor, TraceIdPropagationInterceptor) | class | Java class 직번역 |
| (root) | PasskeyClient, PasskeyClientConfig | class + companion object | Java class + Builder |

## 3. 소비처 (공개 API 호출 지점) — 전수

`grep` 전수 조사 결과 SDK 공개 API를 호출하는 곳은 4군데뿐:

1. **`rp-app` — `config/PasskeyClientConfiguration.kt`** (Kotlin, 운영 코드)
   `PasskeyClientConfig(7개 인자)` 생성자 + `PasskeyClient.of(...)`.
2. **`sdk-java` 테스트 — `PasskeyClientContractIT.java`** `PasskeyClientConfig.defaults(...)` + `.of(...)`.
3. **`sdk-java` 테스트 — `DynamicApiKeyHeaderIT.java`** (2곳) `PasskeyClientConfig.defaults(...)` + `.of(...)`.
4. **`sdk-java` 테스트 — `IdTokenVerifierAlgTest.java`** idtoken 내부 검증(설정 객체 비경유).

Builder 전환 시 1·2·3을 새 API에 맞춰 수정한다. `rp-app`은 모듈 자체는 Kotlin을 유지하고
호출 표현식만 바뀐다(아래 4.3 참고 — 오히려 `null, // clock` 류 군더더기가 사라져 깔끔해짐).

## 4. 변환 세부 설계

### 4.1 DTO·envelope → Java record

현재 (Kotlin):
```kotlin
data class RegistrationStartRequest(
    @get:JvmName("userHandle") @get:JsonProperty("userHandle") @param:JsonProperty("userHandle") val userHandle: String,
    ...
)
```
환원 (Java):
```java
public record RegistrationStartRequest(
    @JsonProperty("userHandle") String userHandle,
    @JsonProperty("displayName") String displayName,
    @JsonProperty("username") String username
) {}
```
- Jackson은 Java record를 네이티브 지원(생성자 파라미터명 + `@JsonProperty`)하므로 매핑 동일.
- `ApiResponseEnvelope<T>`는 제네릭 record로 그대로 환원. `@JsonIgnoreProperties(ignoreUnknown=true)`,
  `@JsonInclude(NON_NULL)` 어노테이션 보존.
- 접근자: record는 `config.userHandle()` 형식 → 기존 `@get:JvmName`이 보존하려던 record 접근자
  형식과 정확히 일치(소비처 호환).

### 4.2 exception → Java class

```java
public class PasskeyApiException extends RuntimeException {
    private final int httpStatus;
    private final String code;
    private final String traceId;
    private final List<EnvelopeFieldError> fieldErrors;
    public PasskeyApiException(int httpStatus, String code, String message,
                               String traceId, List<EnvelopeFieldError> fieldErrors) {
        super(message);
        this.httpStatus = httpStatus; this.code = code; this.traceId = traceId;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }
    // getters
}
```
- `List.copyOf` 방어 복사 + null→`List.of()` 동작 보존.
- `PasskeyAuthException`(401 고정 상속), `PasskeyConfigurationException`,
  `PasskeyIdTokenException`(2-생성자), `PasskeyRateLimitException`도 동일 상속 구조 보존.

### 4.3 PasskeyClientConfig → Builder 패턴

현재: `@JvmOverloads` 생성자(7 인자, null→기본값) + `@get:JvmName` 7개 + `defaults()` 팩토리 +
수동 `equals/hashCode/toString`.

환원:
```java
public final class PasskeyClientConfig {
    private final URI baseUrl;
    private final Supplier<String> apiKeySupplier;
    private final Duration connectTimeout;   // 기본 3s
    private final Duration readTimeout;      // 기본 10s
    private final Duration jwksCacheTtl;     // 기본 5m
    private final Clock clock;               // 기본 systemUTC
    private final Supplier<String> traceIdProvider; // 기본 MDC.get("traceId")
    public static final String MDC_TRACE_ID_KEY = "traceId";

    private PasskeyClientConfig(Builder b) { /* 필수 검증 + 기본값 치환 */ }
    public static Builder builder(URI baseUrl, Supplier<String> apiKeySupplier) { ... }

    // 접근자: 기존 record 접근자명 유지 → baseUrl(), apiKeySupplier(), connectTimeout() ...
    public URI baseUrl() { return baseUrl; }
    // ...

    public static final class Builder {
        // 필수: baseUrl, apiKeySupplier (builder 인자로 강제)
        // 선택: connectTimeout/readTimeout/jwksCacheTtl/clock/traceIdProvider — null이면 기본값
        public Builder connectTimeout(Duration d) { ...; return this; }
        // ...
        public PasskeyClientConfig build() { return new PasskeyClientConfig(this); }
    }
}
```

설계 결정:
- **필수값(baseUrl, apiKeySupplier)은 `builder(...)` 인자로 강제** → 빠진 채 `build()` 불가하게
  타입 레벨에서 보장. `Objects.requireNonNull`로 fail-fast 보존.
- 선택값 미설정 시 기본값 치환(현 동작과 동일): connect 3s, read 10s, jwksTtl 5m,
  clock systemUTC, traceIdProvider `MDC.get("traceId")`.
- 접근자명은 record 스타일(`baseUrl()`)을 유지해 소비처(rp-app, 테스트)의 `config.baseUrl()`
  호출과 호환.
- `equals/hashCode/toString`은 환원하지 않는다. 사유: 원본 Java record가 제공하던 것은 자동
  생성물이었고, 그 값 동등성은 `Supplier`/`Clock` 필드 때문에 의미가 없었다(인스턴스 동일성).
  소비처 어디서도 Config를 비교/해시 키로 쓰지 않으므로(전수 조사 확인) YAGNI 제거.
  필요 시 `toString`만 최소 추가 가능하나 기본은 제외.
- `apiKeySupplier`/`traceIdProvider`의 타입: 기존 `Supplier<String?>`(키가 도중에 null/blank가
  될 수 있어 매 요청 시 검증). Java에서는 `Supplier<String>`로 두되 null 반환 허용 의미는
  주석 + 요청 시점 검증(RedactingRequestInterceptor)으로 보존.

#### 소비처 수정 (rp-app, Kotlin 유지)

```kotlin
// Before: PasskeyClientConfig(baseUrl, supplier, connect, read, jwksTtl, null, null)
// After:
PasskeyClient.of(
    PasskeyClientConfig.builder(props.baseUrl!!, apiKeySupplier)
        .connectTimeout(props.connectTimeout)   // null 허용 → 기본값
        .readTimeout(props.readTimeout)
        .jwksCacheTtl(props.jwksCacheTtl)
        .build()
)
```
`null, // clock`, `null, // traceIdProvider` 줄이 사라져 더 명확해진다. Builder setter는 null
인자를 받아 "미설정=기본값"으로 처리(현 생성자 동작과 동일).

### 4.4 PasskeyClient → Java class

- `companion object { @JvmStatic fun of() }` → `public static PasskeyClient of(PasskeyClientConfig)`.
- `private constructor` → `private PasskeyClient(PasskeyClientConfig)`.
- 4개 공개 메서드(registrationStart/Finish, authenticationStart/Finish) + `verifyIdToken` 시그니처
  동일.
- private `post(...)` 제네릭 메서드: Kotlin `TypeReference` 익명 객체 → Java 익명 클래스
  `new TypeReference<ApiResponseEnvelope<T>>() {}`. 로직(타이밍 측정, envelope success 검사,
  PasskeyApiException 변환, 파싱 실패 시 C999) 1:1 보존.
- `RestClient`/`JdkClientHttpRequestFactory` 구성 로직 동일.

### 4.5 idtoken·internal → Java class 직번역

- `IdTokenClaims`: data class → record.
- `IdTokenVerifier`(93줄, nimbus-jose JWS 검증), `JwksCache`(44줄, TTL 캐시),
  `PasskeyResponseErrorHandler`(68줄, 4xx/5xx→예외), `RedactingRequestInterceptor`(94줄,
  API Key 마스킹 + 매 요청 키 검증), `TraceIdPropagationInterceptor`(42줄): 로직 위주 직번역.
- Kotlin 관용구(`?.`, `?:`, `let`, 문자열 템플릿)는 Java 등가물로 치환. 동작 보존이 최우선.

### 4.6 빌드 스크립트 (`sdk-java/build.gradle.kts`)

제거:
- `alias(libs.plugins.kotlin.jvm)` 플러그인
- `implementation(kotlin("stdlib"))`
- `api("com.fasterxml.jackson.module:jackson-module-kotlin")` (Kotlin data class 매핑용 — record는 불필요)
- `tasks.withType<KotlinCompile> { javaParameters }` 블록

유지/조정:
- `java-library`, `maven-publish`, `withSourcesJar()`
- `api(spring-web, jackson-databind, jackson-datatype-jsr310, nimbus-jose-jwt, slf4j-api)`
- 테스트 의존성(junit-jupiter, assertj, wiremock-standalone) 그대로
- Java record 파라미터명 보존: record 접근자(`userHandle()`)는 컴파일러가 항상 보존하므로
  접근자 매핑은 안전. 단, Jackson이 record를 **역직렬화**할 때 생성자 파라미터명을 쓰는 경로가
  있어, 모든 DTO에 `@JsonProperty("...")`를 **명시**해 파라미터명 의존을 제거한다(현 Kotlin
  코드도 `@param:JsonProperty`로 명시하고 있었으므로 동일 보장). 따라서 `-parameters` 컴파일
  옵션 없이도 안전.
- 결정: `@JsonProperty` 명시를 모든 record 컴포넌트에 유지 → `-parameters` 옵션은 SDK 컴파일에
  불필요(제거 대상). 계약 테스트(`PasskeyClientContractIT`)가 직렬화·역직렬화 양방향을 커버하므로
  이 결정의 정확성은 테스트로 확정된다.

## 5. 검증 전략

순서대로:
1. `./gradlew :sdk-java:compileJava` — 컴파일 성공.
2. `./gradlew :sdk-java:test` — 기존 Java 테스트 전부 통과(동작 동일성의 핵심 증거):
   - `PasskeyClientContractIT` (WireMock 계약 테스트: 등록/인증 start·finish, 401 에러 envelope)
   - `IdTokenVerifierAlgTest` (JWS 알고리즘 검증)
   - `DynamicApiKeyHeaderIT` (동적 API Key 헤더 — Supplier 매 요청 평가)
3. `./gradlew :rp-app:compileKotlin :rp-app:test` — 소비처 호환 확인.
4. (선택) `./gradlew :sdk-java:javadoc` 또는 publish dry-run으로 공개 API 표면 점검.

회귀 판정 함정(메모리 반영): 전체 `./gradlew build`는 SliceConfig 충돌·Oracle 컨테이너 경합으로
항상 실패하므로 머지 게이트로 쓰지 않는다. **모듈 단위 테스트**로 판정하고, 애매하면 base
worktree와 대조한다.

## 6. 가이드 문서 (`sdk-java/README.md`)

대상: 외부 RP 개발자(SDK 소비자). 형태: end-to-end 통합 가이드(README).

목차:
1. **개요** — SDK가 무엇을 하는가(Passkey RP API 4종 + id-token 검증 래퍼), 요구 사항(Java 17,
   Spring Web 런타임 — `RestClient` 사용).
2. **설치/의존성** — Maven local publish 또는 좌표(`com.crosscert.passkey:sdk-java`). transitive로
   끌려오는 spring-web/jackson/nimbus 명시.
3. **빠른 시작** — `PasskeyClientConfig.builder(baseUrl, apiKeySupplier).build()` →
   `PasskeyClient.of(config)`. 최소 동작 예제.
4. **설정 레퍼런스** — Builder 옵션 7종(필수 baseUrl·apiKeySupplier, 선택 5종 + 기본값 표).
   동적 API Key(Supplier 매 요청 평가)의 의미와 핫리로드 패턴.
5. **API 사용** — 4개 호출(registrationStart/finish, authenticationStart/finish)의 요청/응답 DTO
   필드와 호출 흐름(등록 2-step, 인증 2-step).
6. **id-token 검증** — `verifyIdToken(compactJwt)` → `IdTokenClaims`, JWKS 캐시·검증 의미.
7. **에러 처리** — `PasskeyApiException`(httpStatus/code/traceId/fieldErrors),
   하위 타입(Auth 401, RateLimit, Configuration, IdToken), envelope success=false 케이스,
   파싱 실패 C999.
8. **관측성** — Trace-Id 전파(`MDC_TRACE_ID_KEY`), 로그 마스킹(API Key redaction) 동작.
9. **참조 통합 예제** — `rp-app`을 레퍼런스 구현으로 안내(`PasskeyClientConfiguration`,
   `WebAuthnController`).

작성 시 elements-of-style 원칙(간결·능동) 적용. 모든 코드 예제는 환원된 Java API 기준.

## 7. 작업 순서 (구현 단계 개요)

1. 환원 작업은 격리 worktree에서(메모리: per-phase worktree, subagent cwd 격리 주의).
2. 패키지별 환원: dto → envelope → exception → idtoken → internal → Config(Builder) → Client.
3. `build.gradle.kts` 정리.
4. 소비처 수정(rp-app Config 호출부, sdk-java 테스트 3곳).
5. 검증(섹션 5).
6. 가이드 문서 작성(섹션 6).
7. main으로 `--no-ff` 머지.

## 8. 리스크와 완화

| 리스크 | 완화 |
|---|---|
| Jackson record 바인딩이 파라미터명에 의존 | 컴파일 시 `-parameters` 확인 + 계약 테스트로 검증 |
| 제네릭 envelope(`ApiResponseEnvelope<T>`) record + `TypeReference` 상호작용 | 기존 WireMock 계약 테스트가 정확히 이 경로를 커버 |
| Builder 전환으로 소비처 시그니처 변경 | 소비처가 4곳뿐(전수 확인). 같은 PR에서 수정 |
| 외부 RP사 기존 통합 코드 호환성 | `defaults()` 미보존 — 가이드 문서에 마이그레이션 노트 1줄 추가(기존 외부 배포가 있다면) |
| 전체 build 실패로 오판 | 모듈 단위 테스트로 판정(메모리 반영) |
