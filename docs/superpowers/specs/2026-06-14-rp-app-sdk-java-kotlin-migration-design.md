# rp-app · sdk-java Kotlin 마이그레이션 설계

- 날짜: 2026-06-14
- 상태: 설계 승인 완료, 구현 계획(Phase별) 대기
- 목표: rp-app과 sdk-java를 Java→Kotlin으로 이식. **동작·공개 API 무변경**.

## 1. 타당성 결론

**마이그레이션 가능, 근본적 blocker 없음.** rp-app/sdk-java는 JPA Entity가 없어(Kotlin-JPA 플러그인 불필요) 마이그레이션에 우호적이며, record 비중이 높아(rp-app 18 / sdk-java 14) Kotlin data class로 자연 매핑된다. 까다로운 로직(HMAC 릴레이, JWS 검증, 파일 핫리로드, HTTP 인터셉터)도 Java 라이브러리(nimbus-jose-jwt, jackson, Spring)와 Kotlin 상호운용으로 기계적 변환 가능하다.

### 의존 그래프 (조사 확인)
- `rp-app → sdk-java` (rp-app은 leaf, 다른 모듈이 의존하지 않음)
- sdk-java는 `java-library` + `maven-publish` → **외부 Java 프로젝트가 소비하는 published 라이브러리**. 공개 API Java 상호운용 필수.

### PoC로 실증한 핵심 함정 3가지
1. **kotlin-spring 플러그인 필수**: 없으면 `@Configuration`/`@Bean` 클래스가 Kotlin에서 final → Spring CGLIB 프록시 실패 → 앱 부팅 불가.
2. **Kotlin에는 `record` 키워드가 없다**: data class만 가능하고, data class는 `getBaseUrl()`(JavaBean getter)을 생성하지 record식 `baseUrl()`을 만들지 않는다. PoC에서 `PasskeyClientConfig`를 data class로 전환하니 `config.baseUrl()` 등 record 접근자 호출 9곳이 전부 `cannot find symbol` 컴파일 에러로 확인됨.
3. **sdk-java 공개 API breaking change 위험**: 외부 Java 소비자가 `config.baseUrl()`(record 접근자)로 호출 중. data class 전환 시 깨짐 → 호환 전략 필요(§3).

## 2. 핵심 제약 (이전 Lombok 작업과 다른 점)

- **바이트코드 동등성 검증 불가**: Java/Kotlin은 다른 컴파일러라 .class 바이트코드가 근본적으로 다름. 대신 **기존 Java 테스트 유지 → Kotlin main 호출(상호운용 자동 검증) + 계약/통합 테스트**로 기능 무변경을 증명한다(§5).
- **빅뱅 + 모듈 단위**: 한 모듈을 통째로 Kotlin화(rp-app 통째 → sdk-java 통째). 모듈 내 Lombok을 완전 제거 → Java+Kotlin+Lombok 혼재 컴파일 충돌 없음.
- **순서**: rp-app(저위험·leaf) 먼저 → sdk-java(공개 API 주의) 나중.

## 3. sdk-java 공개 API 호환성 전략 — `@JvmName` 호환 유지

외부 Java 소비자가 record 접근자(`config.baseUrl()`)로 호출하므로, 전체 Kotlin화하면서 **공개 API 표면만 Java 호환을 유지**한다:
- data class 프로퍼티 getter에 `@get:JvmName("baseUrl")` → Java에서 `config.baseUrl()` 그대로 호출 가능. Kotlin 내부에서는 `.baseUrl` 프로퍼티로 정상 동작(JvmName은 JVM 메서드명만 변경).
- 정적 팩토리(`PasskeyClientConfig.defaults(...)`, `PasskeyClient.of(...)`)는 companion object + `@JvmStatic`.
- public 상수(`MDC_TRACE_ID_KEY`)는 `const val`(companion object).
- 예외 클래스 getter(`getHttpStatus()` 등)는 JavaBean 스타일이 이미 표준이라 그대로 보존.

대상 공개 표면: `dto/`(14 record), `envelope/`, `exception/`, `PasskeyClientConfig`, `PasskeyClient`.

검토했으나 채택하지 않은 대안:
- B. 깔끔한 data class + SemVer major bump: 외부 소비자 코드 수정 강제 → 배포 SDK 부담으로 기각.
- C. 공개 DTO만 Java record 유지(하이브리드): sdk-java가 Java+Kotlin 영구 혼재 → "절반 마이그레이션"으로 기각.

## 4. 빌드 통합 & Phase 전략

### 빌드 통합 (PoC 검증)
1. `gradle/libs.versions.toml`: `kotlin = "2.0.21"` + 플러그인 alias `kotlin-jvm`, `kotlin-spring`.
2. 루트 `build.gradle.kts` plugins에 `apply false`로 선언. **subprojects 일괄 적용은 하지 않음** — Kotlin은 rp-app/sdk-java에만 필요(core/admin-app/passkey-app/webauthn은 Java 유지).
3. 각 모듈 `build.gradle.kts`에 `alias(libs.plugins.kotlin.jvm)` + `alias(libs.plugins.kotlin.spring)` 개별 적용.
4. **Lombok 제거**: 모듈이 Kotlin화되면 Lombok 불필요. 루트 subprojects가 모든 모듈에 Lombok을 일괄 적용 중이므로, Kotlin화한 모듈에서 Lombok 의존을 명시적으로 제거(또는 무효화).
5. **jackson-module-kotlin** 추가(두 모듈): Kotlin data class 안정적 역직렬화.
6. **`@Slf4j` 대체 = slf4j 직접 사용**: `private val log = LoggerFactory.getLogger(javaClass)`. 신규 의존성 0, 기존 로깅 파이프라인(logback, SecretMaskingConverter, MDC) 그대로. kotlin-logging은 의존성 추가라 YAGNI로 배제.
7. **소스셋**: `src/main/kotlin` 생성, `src/main/java`의 해당 파일 제거(빅뱅이라 혼재 기간 없음). 테스트는 `src/test/java` 유지.

### Phase 1: rp-app (저위험 선행)
- 목적: Kotlin 빌드·테스트 파이프라인 검증(leaf, 공개 API 제약 없음).
- main 32개 Java → Kotlin. 기존 Java 테스트 8개는 그대로 유지(Kotlin main 호출로 상호운용 검증).
- 까다로운 부분: `RegRelayCodec`(HMAC, Base64, MessageDigest.isEqual 상수시간 비교, fail-closed null 가드), `ReloadableApiKeySupplier`(@Volatile/AtomicReference 핫리로드, 폴 스로틀), `SecretMaskingConverter`(logback 컨버터 + static Pattern → companion object), `WebSecurityConfig`(@Bean → kotlin-spring open), `TraceIdFilter`/`RequestLoggingFilter`(MDC).
- `@ConfigurationProperties` record(PasskeyProperties/CorsProperties/RelayProperties/WellKnownProperties) → Kotlin data class, 컴팩트 생성자 검증 → init 블록 + 기본 인자.
- 검증: `:rp-app:test` BUILD SUCCESSFUL + RpAppSmokeIT(앱 컨텍스트 로드 = kotlin-spring 누락 조기 포착).

### Phase 2: sdk-java (공개 API 주의)
- main 24개 Java → Kotlin. 공개 표면은 §3의 `@JvmName`/`@JvmStatic`/`const`로 Java 호환 유지.
- 까다로운 부분: `PasskeyClientConfig`(컴팩트 생성자 기본값 → Kotlin 기본 인자 + `defaults()` @JvmStatic), `IdTokenVerifier`(JWS/nimbus, RS256 핀, 체크 예외 ParseException), `JwksCache`(AtomicReference/TTL), `PasskeyClient`(RestClient), 인터셉터 3개.
- 기존 Java 테스트 3개(WireMock 계약 JSON 6개, JWS alg) 그대로 유지 → record식 접근자로 Kotlin 호출 = 공개 API 호환 자동 검증.
- rp-app(이미 Kotlin)이 Kotlin sdk-java를 소비 → 내부 통합도 검증.
- 검증: `:sdk-java:test` + `:rp-app:test` 둘 다 green + `publishToMavenLocal` 성공.

각 Phase = 별도 plan → per-phase worktree → `--no-ff` 머지.

## 5. 검증 전략 (다층 행위 검증)

1. **기존 Java 테스트 유지 → Kotlin main 호출** (핵심): 테스트를 Kotlin화하지 않음. Java 테스트가 Kotlin 클래스를 호출하므로 공개 API 상호운용이 깨지면 컴파일 에러로 즉시 드러남. sdk-java의 record식 접근자(`.baseUrl()`) 호환이 이 방식으로 자동 검증됨.
2. **계약/통합 테스트가 동작 계약 고정**: sdk-java WireMock 계약 테스트(JSON 6개) + JWS alg 테스트, rp-app RpAppSmokeIT(webauthn4j-test ClientPlatform). 와이어 레벨 동작 불변 보장.
3. **모듈 테스트 green**: `:rp-app:test`, `:sdk-java:test` BUILD SUCCESSFUL.
4. **공개 API 시그니처 점검(sdk-java)**: `javap`로 전환 전(Java)/후(Kotlin) 공개 클래스 메서드 시그니처 비교 — `@JvmName` 접근자가 record식 이름을 보존하는지 확인(바이트코드 동등이 아니라 공개 시그니처 보존 확인용).
5. **`publishToMavenLocal`**: 배포 산출물 정상 생성 + 의존성 정합.
6. **codex 리뷰**: 커밋 전 staged diff 독립 리뷰.

## 6. 리스크 & 완화

| 리스크 | 완화 |
|---|---|
| kotlin-spring 누락 → @Bean 부팅 실패 | Phase 1 RpAppSmokeIT(앱 컨텍스트 로드)로 조기 포착 |
| 공개 API breaking | `@JvmName` + Java 테스트 유지로 컴파일 타임 포착 |
| 컴팩트 생성자 기본값 로직 누락 | Kotlin 기본 인자 + 계약 테스트 검증 |
| nullable 플랫폼 타입(nimbus/jackson 반환) | 기존 명시적 null 가드 로직 그대로 이식 |
| jackson Kotlin 역직렬화 차이 | jackson-module-kotlin 추가 + 계약 JSON 테스트 |
| 전체 build pre-existing 빨강 | 전체 build 아닌 모듈별 test로 게이트 |

## 7. 비범위 (이번에 하지 않음)

- core/admin-app/passkey-app/webauthn (Java 유지)
- 테스트 코드의 Kotlin화 (Java 유지 — 상호운용 검증 도구로 활용)
- 동작 변경·기능 추가·리팩터링 (순수 언어 이식, 동작 동일)
- 공개 API의 의도적 재설계 (대안 B 미채택)
- kotlin-logging 등 신규 로깅 의존성 (slf4j 직접 사용)
