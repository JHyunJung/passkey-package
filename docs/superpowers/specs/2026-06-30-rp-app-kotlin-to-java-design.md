# rp-app Kotlin → Java 전면 마이그레이션 설계

- 날짜: 2026-06-30
- 대상 모듈: `rp-app` (전체 소스 + 테스트)
- 목적: **전 모듈 단일 언어(Java) 통일**

## 1. 배경과 목표

`sdk-java`는 이미 순수 Java로 환원됐다(main 머지 `1f9b192`, 2026-06-29). 당시 spec은
"rp-app 모듈의 Kotlin→Java 환원"을 명시적 **비목표**로 두고 Kotlin 유지를 택했으나, 이번에
전 모듈을 단일 언어(Java)로 통일하기 위해 그 결정을 뒤집는다.

rp-app은 leaf 모듈이다(`settings.gradle.kts`의 어떤 모듈도 `:rp-app`을 의존하지 않음).
현재 main 소스 35개 `.kt`(약 1,400줄) + 테스트 3개 `.kt`로 구성되며, 테스트 8개는 이미
Java로 작성돼 있다. 이 Java 테스트들이 **동작 동일성의 핵심 증거** 역할을 한다.

코드 곳곳의 주석이 "원본 Java 는 record 였다", "원본 Java 의 …" 식으로 원본이 Java였음을
반복 언급한다. 즉 Kotlin 마이그레이션 때 Java 호환성 보존을 위해 붙은
`@JvmStatic`/`@JvmRecord`/`companion object`/3중 어노테이션 같은 보일러플레이트가 Java로
되돌리면 **소멸**한다. 따라서 단순 역번역이 아니라 정리(cleanup)에 가깝다.

목표:
1. rp-app 전체(`src/main/kotlin`, `src/test/kotlin`)를 순수 Java로 환원한다.
2. Kotlin 플러그인·런타임 의존성(`kotlin.jvm`, `kotlin.spring`, `kotlin-stdlib`,
   `jackson-module-kotlin`)을 빌드에서 제거한다.
3. 동작 변경 0. 기존 테스트(이미 Java 8개 + 변환되는 3개)로 동일성을 보장한다.

### 비목표 (YAGNI)

- 기능 추가/동작 변경. 순수 형태 변환 + 동작 보존만 한다.
- 구조 리팩터링(예: `WebAuthnController` 217줄 분해). 단일 언어 통일이 목적이며,
  리팩터링이 섞이면 회귀 원인 격리가 어려워진다.
- 공개 API/엔드포인트 시그니처 변경.
- 리소스(템플릿/yml/정적/logback) 변경. 언어 무관이며 클래스명 보존으로 안전하다.

## 2. 변환 대상 인벤토리

`rp-app/src/main/kotlin/...` 35개 `.kt` → 동일 경로의 `src/main/java/...` `.java`
(패키지/클래스명 동일). `rp-app/src/test/kotlin/...` 3개 `.kt` → `src/test/java/...`.

Kotlin 특화 기능 사용도(조사 결과): coroutine/suspend 0, sealed 0, when 표현식 0,
`object` 싱글톤 1(SecretRedactor), `companion object` 11, `data class` 17,
제네릭 확장(ApiResponse) 일부. 즉 변환 핵심은 data class·companion·object 정도이며
복잡한 Kotlin 전용 구조는 없다.

| 영역 | 파일(수) | 변환 포인트 |
|---|---|---|
| `web/dto` | 8 | `data class` → `record` + `@JsonProperty` |
| `common/response` | 5 (ApiResponse, ApiResponse$companion, ErrorDetail, FieldError, PageResponse) | record + 제네릭 static factory |
| `common/exception` | 3 (ErrorCode enum, BusinessException, GlobalExceptionHandler) | enum/class 직번역 |
| `common/filter` | 1 (TraceIdFilter) | class + companion→static |
| `config` | 9 | properties(record/class) + Security + Supplier |
| `web` | 11 | controller·converter·provider·filter·SecretRedactor |
| `web/relay` | 1 (RegRelayCodec) | class + companion + 내부 record |
| `user` | 2 (RpAppUser, InMemoryUserStore) | data class·class |
| (root) | 1 (RpAppApplication) | `fun main` → `static void main` |

## 3. 변환 규칙 (Kotlin 관용구 → Java 등가물)

| Kotlin | Java 환원 | 적용 대상 |
|---|---|---|
| `data class` (DTO·응답) | `record` + 컴포넌트마다 `@JsonProperty` 명시 | 17개 data class |
| `@JvmRecord data class` (properties) | 일반 Java `record` | WellKnownProperties, CorsProperties |
| `class X(생성자) { val derived = ... }` | 일반 class + 생성자에서 정규화 | RelayProperties (derived 필드라 record 불가) |
| `companion object { @JvmStatic fun }` | `static` 메서드 | ApiResponse, RegRelayCodec 등 11개 |
| `object Singleton` | `final class` + private 생성자 + `static` | SecretRedactor |
| `@Volatile var` | `volatile` 필드 | ReloadableApiKeySupplier |
| `?:` / `?.` / `let` | null 체크 / 삼항 / 조건식 | 8개 파일 |
| `maxOf`, `Long.MIN_VALUE` | `Math.max`, `Long.MIN_VALUE` | ReloadableApiKeySupplier |
| `"$x.$y"` 문자열 템플릿 | 문자열 연결 | RegRelayCodec 등 |
| `Matcher.replaceAll { 람다 }` | `appendReplacement`/`appendTail` 루프 (Java는 람다 미지원) | **SecretRedactor (최우선 주의)** |
| `fun main(args)` + `runApplication<T>` | `public static void main` + `SpringApplication.run` | RpAppApplication |
| `init { require(...) }` | record compact 생성자 / 생성자 내 검증 | CorsProperties |

### 3.1 properties 바인딩 (핵심)

rp-app의 모든 `@ConfigurationProperties`는 `RpAppApplication`의
`@ConfigurationPropertiesScan`으로만 등록된다(`@Bean` factory-method 없음). 즉 Spring은
VALUE_OBJECT(생성자) 바인딩 경로를 쓴다. 따라서:

- **WellKnownProperties / CorsProperties**: 코드 주석이 경고하는 "`@Bean` factory → JAVA_BEAN
  강제 → record 접근자(`allowedOrigins()`) 미인식" 함정은 **이 코드에 해당하지 않는다**(scan
  경로). Java record로 그대로 환원하면 원본 동작을 정확히 재현한다. `@JvmRecord` 우회가
  필요했던 이유 자체가 사라진다.
- **CorsProperties**: `init { require(...) }`(와일드카드 금지 검증)는 record의 compact
  생성자에서 동일하게 수행한다.
- **RelayProperties**: 생성자 인자(nullable)를 받아 정규화한 non-null 필드(`secret` DEMO 폴백,
  `ttl` 기본 5분)를 노출 → **record가 아니라 일반 class로 유지**한다. `RelayKeyGuardTest`가
  `new RelayProperties(null, ...)`로 DEMO_SECRET 폴백을 검증하므로 정규화는 노출 필드에
  반영돼야 한다.
- **PasskeyProperties**: 단순 nullable 필드 묶음 → record로 환원.

### 3.2 SecretRedactor (람다 replaceAll)

`API_KEY.matcher(result).replaceAll { m -> full.substring(0,11) + "<redacted>" }`는
Kotlin의 함수형 `replaceAll` 오버로드다. Java `Matcher`에는 `replaceAll(Function)`이
Java 9+에 존재하므로 `m -> m.group().substring(0, 11) + "<redacted>"`로 직번역한다
(Java 17이므로 사용 가능). 나머지 6개 패턴은 문자열 치환이라 `replaceAll(String)` 그대로.
**치환 순서(header→JWT→standalone JWS→pk_→password→bcrypt→JSON token field)를 보존**한다.

### 3.3 RegRelayCodec (필드 순서·null 처리 주의)

`encode`가 `ObjectNodePayload(rt, uh, un, dn, exp)` 순서로 생성하고, `decode`가 4필드
(`rt/uh/un/dn`) null 거부 → `RegRelay(p.rt, p.uh, p.un, p.dn)` 매핑한다. 이 순서/null
의미를 정확히 보존한다. `ObjectNodePayload`는 `@JsonCreator`/`@JsonProperty`를 가진 내부
record로 환원(jackson-module-kotlin 제거 후에도 plain ObjectMapper로 역직렬화되도록).
상수시간 비교(`MessageDigest.isEqual`)·HMAC 로직 1:1 보존.

## 4. 빌드 스크립트 (`rp-app/build.gradle.kts`)

제거:
- `alias(libs.plugins.kotlin.jvm)`, `alias(libs.plugins.kotlin.spring)` 플러그인
- `implementation("com.fasterxml.jackson.module:jackson-module-kotlin")`
- `implementation(kotlin("stdlib"))`
- `tasks.withType<KotlinCompile> { compilerOptions { javaParameters.set(true) } }` 블록

유지:
- `java` 플러그인, spring.boot/dep.mgmt
- spring-boot-starter-web/thymeleaf/security/validation, spring-session-core,
  logstash-logback-encoder, janino, `project(":sdk-java")`
- 테스트 의존성(spring-boot-starter-test, spring-security-test, webauthn4j-test)
- `bootJar`(archiveFileName=rp-app.jar, deploy/ 출력), `test`(useJUnitPlatform +
  `--add-opens java.base/java.util=ALL-UNNAMED`)

`-parameters`: Java record 접근자는 항상 보존되고 모든 DTO에 `@JsonProperty`를 명시하므로
파라미터명 의존이 없다. Spring Boot Gradle 플러그인이 Java 컴파일에 `-parameters`를 자동
적용하므로 별도 설정 불필요.

## 5. 회귀 함정 (메모리 + 코드 분석)

1. **`as?`/null-safe → 무조건 캐스트 회귀** — sdk-java 환원에서 Codex가 잡은 #1 함정.
   `?.`/`?:`/`as?`/4필드 null 거부(RegRelayCodec)·`cachedKey ?: envFallback`
   (ReloadableApiKeySupplier) 의미를 보존해야 한다. 컴파일·테스트 통과해도 못 잡으니 **전수
   점검 필수**.
2. **properties 바인딩 경로** — scan 경로라 record 안전하나, 테스트(`WebAuthnControllerTest`,
   `RelayKeyGuardTest`)가 properties를 fixture `@Bean`으로 주입하는 경로가 있어 JAVA_BEAN
   바인딩 가능성을 실제 부팅 + 슬라이스 테스트 양쪽으로 검증한다.
3. **logback-spring.xml FQCN 참조** — `SecretMaskingConverter`/`RedactingMessageJsonProvider`/
   `CompactMdcConverter`를 클래스명으로 참조 → 패키지·클래스명 보존하면 xml 변경 불필요.
4. **`@Volatile` → `volatile`** — ReloadableApiKeySupplier의 스레드 안전성(State holder
   단일 volatile 발행) 의미를 유지한다.
5. **전체 build로 오판 금지** — `./gradlew build`는 SliceConfig 충돌·Oracle 경합으로 항상
   실패하므로 머지 게이트로 쓰지 않는다. `:rp-app:test` 모듈 단위로 판정하고 애매하면 base
   worktree와 대조한다.

## 6. 검증 전략 (모듈 테스트 + 실제 부팅)

순서대로:
1. `./gradlew :rp-app:compileJava` — 컴파일 성공.
2. `./gradlew :rp-app:test` — 기존 테스트 전부 통과(동작 동일성의 핵심 증거):
   Java 8개 + .kt→.java 변환분 3개(SecretRedactorTest, CompactMdcConverterTest,
   PayloadLoggingTest). WebAuthnControllerTest·InMemoryUserStoreTest·RegRelayCodecTest·
   properties/Supplier 테스트가 주요 경로를 커버.
3. **실제 부팅**: `SPRING_PROFILES_ACTIVE=local`로 rp-app 기동 → @ConfigurationProperties
   바인딩(passkey/rp.relay/rp.cors/rp-app.well-known)·시큐리티 체인·로깅 마스킹·
   apiKeySupplier 동작을 육안 확인. (메모리: local 기동은 환경변수·flyway repair 등 별도
   준비가 있으나 rp-app은 :core 비의존 무상태라 부팅 자체는 가벼움.)
4. base worktree 대조 — 애매한 테스트 실패 판정용.

## 7. 작업 순서 (구현 단계 개요)

격리 worktree에서 진행(메모리: per-phase worktree, subagent cwd 격리 주의 — implementer가
메인 repo에 커밋하지 않도록 cwd/브랜치 가드).

1. 단순 파일부터: `web/dto`(8) → `common/response`·`common/exception`(8) → `user`(2)
2. `config`(9): PasskeyProperties/WellKnownProperties/CorsProperties(record) →
   RelayProperties(class) → RelayKeyGuard/ReloadableApiKeySupplier →
   WebSecurityConfig/PasskeyClientConfiguration
3. `web`(11): SecretRedactor 먼저(공유 헬퍼) → SecretMaskingConverter/
   RedactingMessageJsonProvider/CompactMdcConverter → RequestLoggingFilter/
   WellKnownController/PageController → WebAuthnController; `web/relay`의 RegRelayCodec
4. `common/filter`(TraceIdFilter), `RpAppApplication`(main)
5. 테스트 3개(.kt→.java) 변환, `src/main/kotlin`·`src/test/kotlin` → `src/.../java` 이동
   (변환 완료 후 빈 kotlin 디렉토리 제거)
6. `build.gradle.kts` 정리(섹션 4)
7. 검증(섹션 6)
8. main으로 `--no-ff` 머지

## 8. 리스크와 완화

| 리스크 | 완화 |
|---|---|
| `?.`/`?:` Java 역번역에서 의미 변화(sdk-java #1 함정) | 전수 점검 + 기존 테스트 + Codex 리뷰 |
| 테스트의 fixture `@Bean` properties 주입이 JAVA_BEAN 바인딩 유발 | 실제 부팅 + 슬라이스 테스트 양면 검증 |
| jackson-module-kotlin 제거 후 역직렬화 실패 | 모든 DTO/payload에 `@JsonProperty`·`@JsonCreator` 명시 |
| logback FQCN 참조 깨짐 | 패키지·클래스명 보존(xml 무변경) |
| 전체 build 실패로 오판 | 모듈 단위 테스트로 판정(메모리 반영) |
| worktree stale base 머지 충돌 | base 최신화 후 분기, 충돌 시 union 해소 |
