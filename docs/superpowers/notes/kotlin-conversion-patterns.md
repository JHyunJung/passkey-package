# Kotlin 전환 패턴 메모 (passkey-app → admin-app 3단계용)

passkey-app Java→Kotlin 전환(2026-11-06, 브랜치 `feat/passkey-app-kotlin`)에서 실제로 쓴 설정과 부딪힌 함정만 적는다.
상세 근거: spec `docs/superpowers/specs/2026-11-06-passkey-app-kotlin-design.md`, plan `docs/superpowers/plans/2026-11-06-passkey-app-kotlin.md`.

## 1. 빌드 설정
- 카탈로그 `kotlin = "2.4.21"`, 플러그인 `kotlin.jvm` + `kotlin.spring`(all-open: `@Component`/`@Configuration`/`@Transactional` 클래스 open). 엔티티는 core(Java)라 `plugin.jpa` 불필요 — admin-app 도 엔티티를 직접 두지 않으면 동일.
- 루트 `build.gradle.kts` 에서 Boot BOM `bomProperty("kotlin.version", rootProject.libs.versions.kotlin.get())` 로 덮어씀 → stdlib/reflect 가 컴파일러 버전과 정렬(Boot 4.1.2 BOM 기본값 2.3.21).
- `kotlin { jvmToolchain(17); compilerOptions { freeCompilerArgs.add("-Xjsr305=strict"); javaParameters = true } }`.
- 의존성: `jackson-module-kotlin`(Jackson 3 좌표 `tools.jackson.module`, 버전은 BOM), `kotlin-reflect`, 테스트 `mockito-kotlin`.
- Docker 빌드 단계(eclipse-temurin:17-jdk 안 Gradle wrapper)에서 Kotlin 컴파일 추가 설정 없이 통과.
- 루트 `subprojects` 의 Lombok `compileOnly`/`annotationProcessor` 는 Kotlin 전용 모듈(passkey-app)에도 그대로 걸린다 — 쓰는 곳이 없어 무해. 모듈별 스코핑은 빌드 전역 변경이라 보류.

## 2. 진행 순서 (효과 있었음)
1. 전환 전, **원 Java 코드에 Java 특성 테스트**를 먼저 추가(계약 스냅샷: 필드 누락·`""`·크기 초과·타입 불일치·빈 바디·잘못된 JSON, 예외 흐름, 메트릭). 뮤테이션으로 테스트가 실제로 잡는지 확인.
2. 본 코드를 패키지 단위로 Kotlin 화 — 기존 Java 테스트가 안전망. Java 테스트 수정은 접근자 이름·import 만.
3. 마지막에 테스트를 Kotlin 화(클래스#메서드 목록 동일 확인). 각 커밋에서 모듈 테스트 실패 0.
4. 프록시 확인용 `KotlinProxyWiringTest` — `@Transactional` 서비스가 CGLIB 프록시이고 실제 트랜잭션(읽기/쓰기 여부 포함) 안에서 도는지 단언.
5. **마지막 Java 호출자가 사라지면 de-interop.** 아래 표의 † 행(nullable 파라미터 + `!!`, `Optional` 반환, `@JvmStatic`/`@JvmOverloads`/`@JvmField`)은
   Java 호출자가 남아 있는 **혼합 단계 전용**이다. 모듈이 전부 Kotlin 이 되면 "서비스 호출자에서 도달 가능한 입력"을 Kotlin 타입 시스템으로 다시 정의해 걷어낸다:
   파라미터 non-null(컨트롤러가 non-null 을 넘기면 null 입력은 표현 불가 → 그 입력을 고정하던 특성 테스트는 근거와 함께 삭제), `Optional<T>` → `T?`(단, `Optional` 이
   의미를 갖는 곳은 유지 — 예: `ChallengeStore.take*` 의 키 부재 400 vs 저장값 JSON `null` 500 구분), Java 전용 `@Jvm*` 제거(JUnit companion 라이프사이클 등 프레임워크 요구는 유지),
   중복 private 헬퍼는 internal 확장 함수 한 파일로. 예: 이 브랜치 final fix wave(controller Ruling 6). DB/HTTP 원천 null(예: `ApiKeyAuthRow.keyHash: String?`)은 유지.
   `!!` 로 NPE 지점을 맞추던 곳은 기준 동작을 다시 확인한다 — null 토큰은 기준 Java 에서 NPE 가 아니라 "token missing or expired" IAE 였다(`?: throw` 로 복원).

## 3. 함정과 해결
† = Java 호출자가 남은 혼합 단계 전용 규칙(§2-5).

| 함정 | 해결 |
|---|---|
| record 접근자 `x()` → Kotlin 프로퍼티 `getX()` 로 바뀌어 Java 호출부 컴파일 실패 | `compileTestJava` 필수. Java 테스트는 접근자 이름만 수정 |
| Bean Validation 애너테이션이 생성자 파라미터에 붙어 검증이 안 됨 | `@field:NotBlank` 등 `@field:` 타깃(javap 로 필드에 붙었는지 확인) |
| 요청 DTO 를 non-null 로 두면 누락 필드가 Jackson 역직렬화 실패로 바뀌어 에러 봉투 계약 변경 | 검증 대상 필드는 **nullable + 같은 제약**. `JsonNode?` 는 기본값 없이(명시적 JSON null 은 `NullNode` 로 들어옴) |
| `@RequestBody`/`@RequestParam` 을 nullable 로 두면 Spring 이 optional 로 보고 required 검사를 건너뜀 | 컨트롤러 파라미터는 non-null, DTO 필드만 nullable |
| † Java 호출 가능한 public 멤버를 non-null 로 바꾸면 null 입력의 예외 흐름이 바뀜(NPE→파라미터 체크, 0행 no-op→NPE) | **혼합 단계 전용**(§2-5 로 해제). 범위 규칙: HTTP/Redis/DB 데이터나 서비스 호출자에서 도달 가능한 입력은 원 흐름 유지(nullable 파라미터). 불가능한 값으로 data carrier 를 만드는 경우는 제외하고 문서화 |
| † Java 가 NPE 로 실패하던 지점을 `?.`/`?:` 로 바꾸면 조용히 통과 | **혼합 단계 전용**: 같은 위치에서 `!!`(근거 주석) — 예: 측정 `try` 안에서 `req!!` 로 실패 메트릭 유지. 전부 Kotlin 이 되면 non-null 파라미터로 대체(§2-5) |
| ChallengeStore: Redis 값이 JSON `null` 이면 Java 는 try 밖 `Optional.of` NPE, Kotlin non-null 은 try 안에서 감싸져 ISE | `deserialize(): T?` 로 두고 `Optional.of` 를 try 밖에서 호출(javap 예외 테이블 확인) |
| `data class` 의 `ByteArray` 필드: `equals` 는 참조 비교, `hashCode`/`toString` 은 내용 기반 — record 와 다름 | 해시 키·로그 사용처 grep 으로 영향 없음 확인, 주석에 정확히 기술 |
| top-level `main` 이라 Boot 가 `PasskeyApplicationKt` 를 main class 로 추론 → 시작 로그 문구 변경 | `runApplication<X>(*args) { setMainApplicationClass(X::class.java) }` |
| Kotlin `split('.')` 은 끝의 빈 토큰을 남김(Java `String.split` 은 제거) → 빈 서명 JWT 통과. passkey-app 에서는 테스트(`ApiContractIT.idToken_claimShape`) 한 곳에서만 발생 — 본 코드엔 `split(` 없음 | `Pattern.compile("\\.").split(s)` (빈 문자열 입력까지 Java 와 동일). `dropLastWhile` 은 `""` 입력에서 다름 |
| mockito-kotlin `any()` 는 null 불일치 → `never()` 검증이 약해짐 | Java bare `any()` 는 `anyOrNull()` 로. de-interop 후 파라미터가 non-null 이면 `any()` |
| Testcontainers `@Container`/`@DynamicPropertySource`/`@AfterAll` | `companion object { @Container @JvmField val ... }`, `@JvmStatic` 함수 (프레임워크 요구 — de-interop 대상 아님) |
| 혼합 모듈에서 Kotlin 이 javac 보다 먼저 컴파일 → 아직 Java 인 클래스의 Lombok 생성 멤버(`@RequiredArgsConstructor` 생성자, `@Slf4j log`)가 Kotlin 에서 안 보임 | 호출자·피호출자를 같은 단계에서 전환. 로거는 file-private `LoggerFactory.getLogger(X::class.java)`(로거 이름 동일) |
| Jackson 3 `JsonNode` 에 멤버 `map(Function)` 이 있어 `node.map { }` 이 요소 순회가 아니라 노드 자체에 적용(멤버가 확장 함수보다 우선) | `node.values().map { }` 또는 `for (el in node)` |
| `Long::class.java` 는 primitive `long` | Java `Long.class` 대응은 `Long::class.javaObjectType` |
| 손으로 만든 ObjectMapper(core `LicenseCache`, webauthn `JsonMappers`)에는 Kotlin 모듈이 없음 | Kotlin 클래스를 그 매퍼로 넘기지 않음(`javaParameters = true` 면 생성자 creator 는 인식됨) |
| 라이브러리 null 애너테이션 불일치(webauthn4j `getTransports()` non-null 표기지만 null 반환, Spring Security 7 `encode` 는 `String?`) | javap/소스로 확인 후 로컬 변수를 nullable 로 선언해 원 null 체크 유지 |
