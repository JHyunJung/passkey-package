# passkey-app Kotlin 전환 (2단계) — 설계

- 작성일: 2026-11-06
- 상태: 승인됨 (2026-11-06, builder check 반영)
- 선행: 1단계 Spring Boot 4.1.2 업그레이드 완료(main `7dd0e4b8`). 상위 로드맵: `docs/superpowers/specs/2026-11-06-spring-boot-4-kotlin-design.md` §8.

## 1. 의도와 이유

- 사용자는 **커리어를 위해** Kotlin 을 쓰고 싶다. 목표 스타일은 **Spring 실무 팀의 정석적인 Kotlin**(사용자 결정) —
  코루틴·Kotest·MockK 같은 생태계 확장이 아니라, 회사 코드베이스에서 바로 통하는 관용적 Kotlin.
- 작업은 전부 Claude 가 수행하고 사용자는 결과 코드를 읽으며 익힌다.
- passkey-app 은 규모가 작고(본 코드 34파일 ≈2.2k줄, 테스트 28파일 ≈4.4k줄) 1단계 계약 안전망이 이 모듈에 집중돼 있어
  Kotlin 빌드 기반과 전환 패턴을 확정하기에 가장 안전하다. admin-app(3단계)은 이 패턴을 따른다.

## 2. 목표 / 비목표 / 반목표

### 목표
- passkey-app 본 코드와 테스트 코드를 **전부 Kotlin** 으로 옮긴다(Java 파일 0).
- Kotlin **2.4.x 최신(조사 시점 2.4.21)** 과 이에 맞는 플러그인·라이브러리로 빌드한다.
- 이후 admin-app 이 재사용할 수 있는 Kotlin 빌드 설정(루트/카탈로그)을 확정한다.

### 비목표
- core·webauthn·admin-app·rp-app·sdk-java 의 언어 변경(이 단계는 passkey-app 만).
- 코루틴, WebFlux, Kotest, MockK, Kotlin DSL 라우터(`router {}`), Exposed 등 생태계 확장.
- 기능 추가·리팩터링·버그 수정(전환으로 자연스럽게 정리되는 수준의 관용화만 허용 — §5).

### 반목표 (동작해도 실패)
- **외부 동작 변화**: passkey-app API 의 JSON 계약(1단계 `ApiContractIT` 스냅샷), 에러 봉투·HTTP 상태,
  Redis 저장 형식(`ChallengeStoreFormatCompatTest` 픽스처), SDK E2E(`SdkClientEndToEndIT`), 보안 동작
  (API Key 인증·scope, Rate limit, 파서 DoS 상한 64/256KB/1MB, CBOR 컨버터 제거, actuator), 로그 마스킹,
  메트릭 이름·태그 중 무엇이라도 바뀌면 실패. **1단계 계약 스냅샷·픽스처는 수정 금지.**
- **null 안전성의 거짓 약속**: Java(core·webauthn) 반환값을 근거 없이 non-null 로 선언해, 예전엔 처리되던 null 이
  NPE 로 바뀌는 것. 반대로 `?.`/`?:` 로 예전엔 실패하던 경로를 조용히 통과시키는 것(fail-open).
- **"자바를 문법만 옮긴 코틀린"**: getter/setter 수동 구현, `!!` 남발, 불필요한 `lateinit`, companion 에 몰아넣은 유틸 등.

## 3. 결정 사항

### 3.1 Kotlin 스타일 (사용자 승인한 방향, 세부는 Claude 결정)
- 생성자 주입은 주 생성자(`class X(private val dep: Dep)`), Lombok 제거, 로거는 파일/클래스 단위 `LoggerFactory` 상수.
- DTO·값 객체는 `data class`, 불변(`val`) 기본, null 은 타입(`?`)으로만 표현.
- `@ConfigurationProperties` 는 생성자 바인딩 data class.
- 확장 함수·scope 함수(`let`/`also`/`apply`/`run`)·`when`·expression body 는 **가독성이 나아질 때만**.
- 상수는 top-level `const val` 또는 `companion object` (용도에 맞게), 정적 유틸은 top-level 함수.
- 예외 처리 흐름·검증 순서·로그 메시지 문구는 그대로 유지(동작·운영 로그 불변).

### 3.2 빌드 (Claude 결정)
- 카탈로그 `kotlin = "2.4.21"`(1단계 백로그 항목 해소). Boot 4.1.2 BOM 의 `kotlin.version`(2.3.21) 을 덮어써
  stdlib·reflect 를 2.4.21 로 정렬.
- passkey-app 에 `kotlin("jvm")`, `kotlin("plugin.spring")`(all-open). JPA 엔티티는 core(Java) 에 있으므로 `plugin.jpa` 불필요.
- 컴파일러 옵션: `-Xjsr305=strict`, `javaParameters = true`, JVM target 17.
- `tools.jackson.module:jackson-module-kotlin`(Jackson 3, BOM 관리) 추가 — Boot 가 자동 등록한다.
  **이 모듈은 Kotlin 클래스의 (역)직렬화 규칙을 바꿀 수 있으므로** 계약 스냅샷이 판정한다.
- 테스트: JUnit 5 + AssertJ 유지, Mock 은 **mockito-kotlin**(사용자 승인) — Spring `@MockitoBean` 과 같은 계열.
- 혼합 단계(본 코드 Kotlin + 테스트 Java)가 컴파일되도록 Kotlin/Java 공동 컴파일 설정.

### 3.3 순서 (사용자 승인)
1. 빌드 기반(Kotlin 플러그인·버전·의존성) — 아직 Kotlin 파일 0, 전 테스트 통과.
2. 본 코드 전환을 패키지 단위로: dto → challenge·config → security → fido2 서비스 → 컨트롤러·앱.
   **이 동안 Java 테스트는 수정하지 않는다**(접근자 이름 변화 등 컴파일 호환을 위한 최소 수정만 허용하고 기록).
   Java 테스트가 Kotlin 코드를 호출해 동작 동일성을 증명한다.
3. 테스트 전환(Java → Kotlin, mockito-kotlin). 단언 의미는 그대로.
4. 정리: Java 파일 0 확인, 문서(README 스택 표기)·백로그 갱신, 3단계(admin-app)가 쓸 패턴 메모.

## 4. 알려진 함정 (과거 rp-app·sdk-java 전환에서 실제 겪음)
- record 접근자 `x()` → Kotlin 프로퍼티 `getX()` — Java 호출부(테스트) 컴파일 깨짐. `compileTestJava` 필수.
- `==`(Kotlin=equals) vs Java `==`(참조) — 원 의미 보존.
- `as?` vs Java 무조건 캐스트 — 실패 시 동작(ClassCastException vs null) 보존.
- `@ConfigurationProperties` + `@Bean` 경로는 `@JvmRecord` 가 필요했던 전례.
- Java(core·webauthn) 반환값은 플랫폼 타입 — 원 코드의 null 처리 그대로 타입에 반영.
- Kotlin 클래스는 기본 final — `@Transactional`/AOP 대상은 all-open 으로 처리되는지 확인.
- jackson-module-kotlin 등록으로 Kotlin data class 의 null/기본값 처리가 바뀔 수 있음 — 계약 스냅샷이 판정.

## 5. 검증 (완료 조건)
1. 각 단계 커밋에서 `./gradlew :passkey-app:test` 전부 통과(실패 0), 계약 스냅샷·픽스처 무수정.
2. `:rp-app:test`, `:sdk-java:test` 통과(공유 빌드 영향 없음), core·webauthn·admin-app 은 컴파일+테스트가 1단계와 동일.
3. 동작 동일성 근거: 2단계(본 코드 전환) 동안 **기존 Java 테스트가 수정 없이**(§6-5 의 접근자 이름·import 변경만 허용) 통과.
4. 로컬 기동: passkey-app + rp-app 으로 등록·인증 1회(브라우저 가상 인증기).
5. Docker 이미지(passkey-app) amd64 빌드 성공.
6. Task 별 spec → quality → codex 3-gate, 최종 전체 리뷰.

## 6. Claude 가 정한 사항 (사용자 검토용)
1. Kotlin 2.4.21 + BOM kotlin.version 덮어쓰기.
2. jackson-module-kotlin(Jackson 3) 추가 — 계약 스냅샷으로 판정.
3. 본 코드 전환 순서(패키지 단위)와 "그동안 Java 테스트 무수정" 원칙.
4. 로거·상수·유틸 배치 규칙(§3.1).
5. **record → `data class`**(`@JvmRecord` 아님 — 실무 관용). 2단계 동안 Java 테스트에 허용되는 수정은
   **접근자 호출 이름 변경(`x()` → `getX()`)과 import 만**이며 단언·입력·기대값은 그대로. 수정 줄은 커밋 메시지에 기록.
   §5-3 의 "수정 없이" 는 이 의미다.
6. **요청 DTO 의 검증 대상 필드는 nullable(`?`) 로 유지**한다. 필드에 `@NotNull`/`@NotBlank` 등이 붙어 있으면
   Kotlin 에서도 `String?` + 같은 제약을 둬, 필드 누락·null 이 지금처럼 Bean Validation → 같은 에러 봉투로 가게 한다
   (non-null 로 두면 jackson-module-kotlin 이 역직렬화 단계에서 실패시켜 에러 응답이 바뀜). 계약 보존이 "null 은 타입으로"
   보다 우선. 요청 DTO 별 누락 필드 → 400 봉투 형태 테스트가 없으면 1단계 형태 스냅샷 방식으로 추가.
7. 작업 단위: worktree 1개, 브랜치 1개, 4단계 모두 끝난 뒤 main 에 한 번 머지(`--no-ff`). 중간 단계 커밋에서도
   passkey-app 테스트는 항상 녹색이어야 한다(혼합 Java/Kotlin 컴파일).
8. 보안 경로(ApiKeyAuthFilter·RateLimitFilter·ApiKeyScopeResolver)는 기존 테스트(`ApiKeyAuthFilterTest`,
   `ApiKeyAuthFilterScopeTest`, `RateLimitFilterTest`, `ApiKeyLookupServiceIT`, `ProblemJsonTest`)가 안전망이다.
   전환 전 커버리지 공백(필터 순서·예외 경로 등)이 보이면 해당 Task 에서 Java 특성 테스트를 먼저 추가한 뒤 전환.

## 7. 환경
- Docker 사용 가능(Testcontainers·이미지 빌드·로컬 기동 모두 이 Mac 에서 실행). amd64 이미지는 buildx `--platform linux/amd64`.
- ArchUnit `TenantBoundaryArchTest` 는 admin-app 소속이라 이 단계와 무관(3단계에서 다룸).
