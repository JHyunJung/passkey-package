# Spring Boot 4.1 업그레이드 + passkey-app·admin-app Kotlin 전환 — 설계

- 작성일: 2026-11-06
- 상태: 승인됨 (2026-11-06, builder check 반영)
- 범위: 3단계 로드맵 중 **1단계(Spring Boot 4.1 업그레이드, Java 유지)를 상세 설계**한다.
  2·3단계(Kotlin 전환)는 방향과 결정만 적고, 1단계 머지 후 각자 상세 설계·계획을 따로 만든다.

## 1. 의도와 이유

- 사용자는 **커리어를 위해 Java 대신 Kotlin을 쓰고 싶다.** 목표는 passkey-app·admin-app을
  **실무에서 통하는 관용적(idiomatic) Kotlin** 으로 바꾸는 것이다. "자바를 문법만 옮긴 코틀린"은 목적에 맞지 않는다.
- 작업은 전부 Claude가 수행하고 사용자는 결과 코드를 읽으며 익힌다.
- 최신 안정 Kotlin(조사 시점 2.4.21)과 라이브러리 호환성을 정식으로 맞추려면
  **Spring Boot 4.x 가 전제**다:
  - 현재 Spring Boot 3.5 는 **2026-06-30 오픈소스 지원 종료**(마지막 3.5.16, 현재 3.5.14 사용).
  - Boot 3.5 BOM 의 Kotlin 은 1.9.25. Boot 4.x 는 Kotlin 2.2+ 를 공식 기준으로 한다.
  - Boot 4.1 은 2027-07-31 까지 OSS 지원(4.0 은 2026-12-31). → **4.1.x 최신(4.1.2)** 을 쓴다.
- 업그레이드 문제와 언어 전환 문제가 섞이지 않도록 순서를 나눈다(사용자 승인):

| 단계 | 내용 | 언어 |
|---|---|---|
| **1** | Spring Boot 3.5.14 → 4.1.2 (core·webauthn·passkey-app·admin-app·**rp-app**) | Java 그대로 |
| 2 | passkey-app Kotlin 전환 + Kotlin 빌드 기반 확정 | Kotlin |
| 3 | admin-app Kotlin 전환(패키지 단위로 분할) | Kotlin |

## 2. 1단계 목표 / 비목표 / 반목표

### 목표
- core·webauthn·passkey-app·admin-app·rp-app 이 Spring Boot **4.1.2** BOM 위에서 빌드·테스트·기동된다.
- sdk-java 는 **Spring 6 / Jackson 2 기준 컴파일을 유지**해 Boot 3 고객 호환을 지킨다.
- 연관 라이브러리를 Boot 4.1.2 BOM 관리 버전 또는 그와 호환되는 최신 버전으로 정렬한다.
- Gradle wrapper 를 Boot 4.1 및 (2단계의) Kotlin 2.4 Gradle 플러그인과 호환되는 버전으로 올린다.
- 배포 산출물(Docker 이미지 3종, QA 번들)이 그대로 빌드·기동된다.

### 비목표
- Kotlin 코드 작성(2·3단계).
- Java 버전 상향. **Java 17 유지**(Boot 4 최소 17, 배포 이미지 temurin 17). 21/25 상향은 별도 결정.
- 기능 추가·리팩터링·버그 수정(업그레이드로 강제되는 수정만 한다).

### 반목표 (기술적으로 동작해도 실패로 간주)
- **passkey-app 외부 API 의 JSON 계약 변경.** 필드명·필드 존재·null 표기·날짜/시간 포맷(+09:00 KST)·
  숫자 표기·에러 응답 봉투가 바뀌면 안 된다. sdk-java(고객사 배포물)와 기존 고객 RP 가 이 계약에 의존한다.
  필드 **순서** 변화는 JSON 의미상 무해하지만, 문자열 비교 테스트·서명 대상 바이트가 있으면 계약으로 본다.
- **보안 동작 변화.** admin 로그인·MFA·CSRF·세션·권한(RP_ADMIN/PLATFORM_OPERATOR)·테넌트 격리(Hibernate `@Filter`)·
  API Key 인증·Rate limit(Bucket4j)·actuator 외부 차단·로그 비밀값 마스킹 중 어느 것이라도 약해지면 실패.
- **DB 스키마 변경.** 운영 DB 는 DBA 가 DDL 을 적용하고 앱은 `ddl-auto: validate` 수준으로만 본다.
  업그레이드로 Hibernate 가 다른 컬럼 타입을 기대하게 되어 schema-validation 이 깨지거나, 같은 컬럼에
  다른 값(예: KST 가 UTC 로)을 쓰게 되면 실패.
- **passkey 쪽 Redis 저장 데이터 비호환.** 롤링 배포 중 구버전/신버전 passkey-app 이 같은 Redis 를 본다.
  `ChallengeStore`(진행 중 ceremony, JSON)·rate-limit(Bucket4j)·캐시의 저장 형식이 바뀌어 상호 읽기가 깨지면 실패.
  Jackson 3 전환 후에도 같은 JSON 바이트 형식을 읽고 써야 한다(Claude 결정).
  - **예외(사용자 결정)**: admin-app 의 Spring Session(JDK 직렬화)은 Spring Security 7 로 비호환이 될 수 있으며
    **배포 시 admin 전원 재로그인을 허용**한다. 배포 절차에 기존 admin 세션 정리 단계를 명시한다.
- sdk-java 의 공개 API·동작 변화, rp-app 의 외부 동작(`/passkey/**` 계약, well-known) 변화.

## 3. 제약

- **sdk-java 는 Spring Boot 3.5 BOM(Spring 6 / Jackson 2) 기준 컴파일을 유지한다.** (Claude 결정)
  - 고객사가 Boot 3 환경에서 쓰는 배포물이라 Spring 7 로 컴파일하면 고객 호환이 깨질 수 있다.
  - sdk-java 는 Boot Gradle 플러그인을 쓰지 않는 라이브러리이므로 BOM 만 모듈별로 분리하면 된다:
    sdk-java = Boot 3.5.x BOM(3.5.16 패치 상향 허용), 나머지 5개 모듈 = Boot 4.1.2 BOM.
  - sdk-java 는 Boot 3 classpath(자체 테스트)와 Boot 4 classpath(rp-app 안에서) **양쪽에서 동작**함을 검증한다.
- **rp-app 은 Boot 4.1 로 함께 올린다.** (사용자 결정) 같은 Boot Gradle 플러그인을 공유하므로 BOM 만 3.5 로
  남기는 어중간한 상태를 피한다. 별도 저장소 `../passkey_rp`(Boot 3 고객 샘플)는 건드리지 않는다.
- 운영 DB 는 Oracle **SE2**. EE 전용 기능 금지(기존 감사 결과 유지).
- 배포는 폐쇄망 QA 번들(amd64 이미지 tar) 포함. 이미지 재빌드·번들 재생성 절차는 기존 스크립트를 따른다.
- 테스트 회귀 판정은 기존 관례대로 **base worktree 대조**(전체 `./gradlew build` 는 pre-existing 이유로
  항상 빨갛다 — SliceConfig 충돌, Oracle 컨테이너 경합). 업그레이드 전 실패 목록과 비교해 **새로 깨진 것 0** 을 기준으로 한다.

## 4. 영향 지점 (조사 결과)

조사 기준: 현재 main(64de811b), Boot 4.1.2 BOM(로컬 캐시 확인).

| 영역 | 현재 | Boot 4.1.2 BOM | 해야 할 일 |
|---|---|---|---|
| Gradle | 8.10 | — | Boot 4.1 지원 버전으로 상향(9.x 계열). Docker 빌더 단계도 wrapper 를 쓰므로 함께 검증 |
| Spring Framework / Security / Session | 6.2 / 6.5 / 3.5 | 7.0.10 / 7.1.2 / 4.1.1 | 제거된 API 대응. `AdminSecurityConfig`·rp-app `WebSecurityConfig`(SecurityFilterChain) 재검증 |
| Hibernate | 6.6 | 7.4.13 | `@Filter`(테넌트 격리) 동작, `OffsetDateTime`(+09:00) 매핑, schema-validation 타입 기대치 재확인 |
| Jackson | 2.x (`com.fasterxml.jackson.databind`) | **3.1.7 기본**(`tools.jackson`), 2.21.7 공존 가능 | §5.1 참조 |
| Flyway | 10.20.1(카탈로그 고정) | 12.4.0 | `flyway-database-oracle` 정렬. 배포 컨테이너에선 꺼져 있으나 테스트(Testcontainers)에서 V1 적용 확인 |
| Oracle JDBC | 23.4 | 23.26.3 | BOM 관리로 전환 가능(카탈로그 고정 해제 여부는 빌더 판단) |
| Testcontainers | 1.20.4(카탈로그 BOM) | 2.0.5 | 2.x 아티팩트명·패키지 변경 대응(`oracle-xe` 모듈) |
| JUnit | 5.12 | **Jupiter 6.0.3** | 확장·API 변경 대응 |
| Mockito | 5.x | 5.23.0 | — |
| `@MockBean`/`@SpyBean` | **admin-app 43파일, rp-app 3파일** | Boot 4 에서 제거 | `@MockitoBean`/`@MockitoSpyBean` 으로 교체. 슬라이스 테스트 MockBean 구성(기존 12개 필수 목록) 보존 |
| 테스트 슬라이스 패키지 | `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` 20곳, `AutoConfigureMockMvc` 2곳 | 모듈화로 패키지 이동 | 새 패키지 + 해당 test starter 의존성 추가 |
| 스타터 | `spring-boot-starter-web` 등 | 모듈화(`-webmvc` 등), 구 이름은 deprecated | 새 스타터명으로 정리(빌더 판단, 동작 동일 전제) |
| Thymeleaf | 3.1.x + `thymeleaf-extras-springsecurity6` | 3.1.5, extras 3.1.5(BOM 이 여전히 `springsecurity6` 아티팩트를 관리) | Spring Security 7 과 실제 동작(`sec:authorize` 등) 렌더링 검증 |
| Lettuce / Bucket4j | Boot 3.5 관리 / bucket4j 8.18 lettuce | Lettuce 7.5.2 | Bucket4j lettuce 모듈의 Lettuce 7 호환 확인, 필요시 Bucket4j 상향 |
| Micrometer / Actuator | 1.15 | 1.17.2 | health,info 노출·prometheus env 동작 유지, nginx actuator 차단과 무관 |
| logstash-logback-encoder | 8.0 (Jackson 2 사용) | logback 1.5.38 | 마스킹 provider(`RedactingMessageJsonProvider` 등)는 Jackson 2 API 사용 → Jackson 2 공존으로 유지 가능. 최신 호환 버전 확인 |
| webauthn4j(테스트 전용) | 0.31.5 (Jackson 3 를 전이로 끌어옴) | — | 루트의 `jackson-annotations:2.21` 강제 오버라이드는 Jackson 2.21.7 BOM 으로 불필요해질 수 있음 — 확인 후 정리 |
| Nimbus JOSE, ZXing, OpenHTMLtoPDF, WireMock, ArchUnit, Lombok | 각 고정 버전 | — | 호환 확인, 필요시 최신 패치로 |

## 5. 결정 사항

### 5.1 Jackson: core·webauthn·passkey-app·admin-app 은 Jackson 3 로 전환 (Claude 결정)
- Boot 4 는 Jackson 3 가 기본이며 Jackson 2 지원은 deprecated 경로다. 2·3단계 Kotlin(jackson-module-kotlin)도
  Jackson 3 계열로 가는 것이 장기적으로 맞다.
- 대상: `CoreJacksonConfig`, `PasskeyAppJacksonConfig`(`Jackson2ObjectMapperBuilderCustomizer` → Boot 4 의 Jackson 3 커스터마이저),
  `webauthn/JsonMappers`, admin-app 의 `new ObjectMapper()` 2곳(`ActivityDiff`, `ViewHelpers`), 그 밖의 databind 사용처
  (core 7, passkey-app 11, admin-app 15, webauthn 7 파일).
- **JSON 계약 보존이 최우선**이다. Jackson 3 는 기본값이 바뀌었다(예: 프로퍼티 알파벳 정렬, null→primitive 실패,
  날짜 기본 처리 등). Boot 4 가 제공하는 Jackson 2 호환 기본값 설정을 우선 고려하고, 최종 형태는 빌더가 정하되
  아래 검증(§7)으로 **업그레이드 전후 응답 JSON 이 의미상 동일**함을 증명해야 한다.
- `application-common.yml` 의 `spring.jackson.*` 설정은 Boot 4 의 키로 옮긴다(키 이름 변경 여부 확인).
- 로깅(logstash-logback-encoder)은 Jackson 2 를 내부적으로 쓰므로 Jackson 2 가 classpath 에 공존해도 된다.
  **애플리케이션 코드**는 Jackson 3 로 통일한다.
- 예외: Jackson 3 전환이 webauthn 의 CBOR/바이트 처리나 서명 대상 바이트에 영향을 준다면, 그 지점은
  빌더가 근거와 함께 Jackson 2 유지를 선택해도 된다(DONE 보고에 명시).

### 5.2 BOM 분리 (Claude 결정)
- 루트 `subprojects {}` 에서 일괄 import 하던 Boot BOM 을 나눈다: sdk-java 만 Boot 3.5.x BOM, 나머지는 4.1.2(§3).
  버전 카탈로그에 `spring-boot`(4.1.2) 와 `spring-boot-sdk`(3.5.x) 처럼 두 버전을 둔다. 구현 방식은 빌더 판단.
- rp-app 의 `RedactingMessageJsonProvider`·`WebSecurityConfig`·Jackson 사용처도 passkey/admin 과 같은 기준으로 올린다.
- Testcontainers BOM 도 같은 방식으로 정리한다(Boot 4.1 은 2.0.5 를 관리).

### 5.3 Java 17 유지 (Claude 결정) — §2 비목표 참조.

### 5.4 Spring Boot 4.1.2 고정 (사용자 승인한 방향 + Claude 가 패치 버전 선택)
- 조사 시점 Maven Central 최신 4.1.x = 4.1.2.

## 6. Claude 가 정한 사항 (사용자 검토용)
1. sdk-java 만 Boot 3.5 BOM 유지(고객 호환), BOM 모듈별 분리. (rp-app 4.1 상향은 사용자 결정)
6. passkey-app Redis 저장 형식(ChallengeStore JSON 등)은 Jackson 3 후에도 바이트 호환 유지.
7. §7-3 E2E 는 자동화 테스트로 새로 작성(기존 `RpAppSmokeIT` 는 `@Disabled` 빈 껍데기라 근거가 못 됨).
2. 애플리케이션 코드 Jackson 3 전환(로깅용 Jackson 2 공존 허용).
3. Java 17 유지.
4. Gradle 9.x 계열로 상향.
5. 2단계 Kotlin 버전은 **2.4.x 최신(조사 시점 2.4.21)**. Boot 4.1.2 BOM 은 Kotlin 2.3.21 을 관리하므로
   2단계에서 `kotlin.version` 을 덮어써 맞춘다(Boot 4 기준 2.2+ 범위 내).

## 7. 검증 (1단계 완료 조건)

1. **컴파일·테스트**: 대상 4개 모듈의 `compileJava`·`compileTestJava`·`test`. 실패 목록을 업그레이드 전
   base worktree 와 대조해 **새로 깨진 테스트 0**. Testcontainers IT(Oracle)는 Docker 가 가능한 환경에서 실행.
2. **JSON 계약**: passkey-app 의 등록 start/finish, 인증 start/finish, credentials, `/.well-known/jwks.json`,
   에러 응답(4xx/5xx 봉투) 응답 JSON 을 업그레이드 전후로 캡처해 비교 — 의미상 동일(필드·값·포맷).
   기존 테스트로 커버되지 않으면 계약 테스트를 추가한다.
3. **sdk-java 호환 E2E (자동화, 새로 작성)**: 업그레이드된 rp-app(sdk-java 사용) → passkey-app 실제 HTTP 로
   등록 begin/finish·인증 begin/finish·ID Token 검증까지 webauthn4j 에뮬레이터로 성공.
   기존 `RpAppSmokeIT` 는 `@Disabled` 껍데기이므로 대체한다. 실행 구조(Testcontainers·랜덤 포트 등)는 빌더 판단.
   sdk-java 자체 테스트는 Boot 3.5 classpath 에서 통과해야 한다.
   **Redis 호환**: 구버전이 쓴 ChallengeStore 값을 신버전이 읽는 테스트(저장 JSON 샘플 고정).
4. **보안 회귀**: admin 로그인·MFA·CSRF·권한 테스트, 테넌트 격리 테스트, actuator 차단, 로그 마스킹 테스트 통과.
5. **로컬 기동**: `SPRING_PROFILES_ACTIVE=local` 로 passkey-app·admin-app 기동, admin 주요 화면 렌더링
   (Thymeleaf + Security dialect), Flyway V1 적용 DB 에서 schema-validation 통과.
6. **배포 산출물**: 3개 Docker 이미지 amd64 빌드 성공, `docker compose config` 통과.
   배포 문서(QA 번들 README 등)에 admin 세션 정리·재로그인 안내 추가.
7. **Codex 게이트**: 기존 관례대로 Task 별 spec → quality → codex 3-gate.

## 8. 2·3단계 방향 (상세 설계는 각 단계 시작 시)

- **관용적 Kotlin**: null 을 타입으로 표현, data class / sealed class / 확장 함수 / scope 함수를 적재적소에,
  Spring Kotlin 관례(생성자 주입, `kotlin-spring` all-open, `jackson-module-kotlin`, `-Xjsr305=strict`, `javaParameters`).
  코루틴·WebFlux 전환은 하지 않는다(MVC 유지).
- core·webauthn 은 Java 유지(엔티티 31개가 core 에 있으므로 `kotlin-jpa` 불필요). Java↔Kotlin 경계의 null 처리가 핵심 위험.
- 테스트: 2단계 시작 시 Java 유지/Kotlin 전환을 결정한다. 과거 rp-app 전환 때의 함정
  (record 접근자 `x()`→`getX()`, `==`/`===`, `as?` vs 캐스트, `@ConfigurationProperties` + `@JvmRecord`)을 체크리스트로 쓴다.
- ArchUnit `TenantBoundaryArchTest` 는 Kotlin 합성 클래스(`*Kt`, `Companion`)를 고려해 규칙 재검토.

## 9. 빌더에게 맡기는 것
- 각 라이브러리의 정확한 패치 버전(§4 기준 안에서), 스타터 새 이름 정리, Task 분할과 순서,
  Jackson 3 호환 설정의 구체 방식(§7-2 검증 통과 전제), 계약 테스트 작성 방식.
