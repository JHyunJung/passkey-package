# rp-app 고객사 샘플 주석·문서 재작성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** rp-app 35개 Java 소스의 주석을 고객사 RP 구축 관점으로 전면 재작성하고(내부 개발 맥락 제거, 보안·설계 근거는 고객사 언어로 보존·강화), `rp-app/README.md` 통합 가이드를 신규 작성한다.

**Architecture:** 주석 4계층 차등(핵심 플로우 상세 / 설정·보안 상세 / 인프라 간결 / 데이터 한 줄). 내부 맥락(spec §, P0-4/P2-a, core twin, 마이그레이션 흔적, 테스트 참조)은 제거·변환하고 보안 근거(상수시간 비교·iss/aud 검증·fail-fast·CORS 정책·로그 마스킹)는 유지. 동작 변경 0 — 주석/문서만.

**Tech Stack:** Java 17, Spring Boot 3, Javadoc(한국어), Markdown.

## Global Constraints

- 언어: **한국어**(코드 주석·README 모두). 기존 코드베이스·sdk-java/README.md와 일관.
- **동작 변경 0**: 코드 라인(로직·시그니처·문자열 리터럴·어노테이션)은 건드리지 않는다. 주석(`//`, `/** */`)과 신규 `.md`만 변경. 각 Task diff에서 코드 라인 변경이 없어야 한다(주석 줄만).
- **제거 대상 내부 맥락**: 마이그레이션 흔적("원본 Java/Kotlin", "@JvmRecord", "companion object", "data class"), 내부 모듈 참조("core 의 twin", "Mirror of …core…", ":core 비의존", "drift/드리프트 방지", "두 파일 동기화/Keep … in sync"), 내부 spec/이슈 코드("spec §N", "P0-N", "P2-N", "W00N"), 테스트 참조("…Test 가 fixture/검증한다"), 프레임워크 내부 메모("VALUE_OBJECT 바인딩", "@ConfigurationPropertiesScan 으로만 등록되므로").
- **유지·강화 대상(삭제 금지)**: 상수시간 비교(HMAC), iss/aud 검증, issuerBase 누락 fail-fast, relay 4필드 null 거부, CORS 와일드카드 금지, 로그 비밀값 마스킹, putIfAbsent 원자적 점유, username 선검사. 고객사가 따라야 할 보안 패턴이므로 더 명확히 서술.
- **클래스명·패키지 보존**: logback-spring.xml이 SecretMaskingConverter/RedactingMessageJsonProvider/CompactMdcConverter를 FQCN 참조. 주석 작업이므로 자연히 유지되나 이름 변경 금지.
- 검증: `./gradlew :rp-app:compileJava` 성공 + `./gradlew :rp-app:test` 69 green(1 skip) + 내부맥락 잔존 grep 0건.
- 격리 worktree에서 작업, subagent는 cwd/브랜치 검증.

---

## 파일 구조 (변경 대상)

35개 Java 주석 수정 + README 1개 신규. 코드 로직 불변.

- 신규: `rp-app/README.md`
- 수정(주석만): 35개 `.java` (계층 1~4)

## 작업 순서 개요 (계층 4 → 1, 쉬운 것부터 → README)

- Task 1: 계층 4 데이터 구조 11개 (한 줄 요약)
- Task 2: 계층 3 인프라/유틸/부트스트랩 13개 (간결 + 교체 가이드)
- Task 3: 계층 2 설정/보안/저장소 8개 (운영 주의 + InMemoryUserStore 교체 강조)
- Task 4: 계층 1 핵심 플로우 3개 (상세 Javadoc + 보안 인라인)
- Task 5: `rp-app/README.md` 신규 + 최종 검증(grep/compile/test)

각 Task는 끝에 `./gradlew :rp-app:compileJava` 성공을 확인한다(주석이 코드를 깨지 않음 + Javadoc 형식 오류 없음). 전체 테스트는 Task 5에서 1회.

---

### Task 1: 계층 4 — 데이터 구조 11개 (한 줄 요약)

**Files (주석만 수정):**
- `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/dto/RegisterStartReq.java`
- `.../web/dto/RegisterCompleteReq.java`
- `.../web/dto/RegisterOptionsResp.java`
- `.../web/dto/LoginStartReq.java`
- `.../web/dto/LoginCompleteReq.java`
- `.../web/dto/LoginOptionsResp.java`
- `.../web/dto/LoginResultResp.java`
- `.../user/RpAppUser.java`
- `.../common/response/ErrorDetail.java`
- `.../common/response/FieldError.java`
- `.../common/response/PageResponse.java`

**목표:** 각 record에 한 줄 클래스 Javadoc. 요청/응답 DTO는 어느 엔드포인트의 것인지 명시. 기존 KDoc의 내부 맥락("spec §4"/"spec §5", "원본 Kotlin …")은 아래 교체 문구로 완전히 대체해 제거한다. `@JsonProperty`/검증 어노테이션/필드는 절대 건드리지 않는다(주석만 추가/교체).

- [ ] **Step 1: 등록 DTO 3개에 클래스 Javadoc 추가**

각 record 선언 바로 위에 한 줄 Javadoc을 둔다(기존 본문 KDoc이 있으면 아래 문구로 교체, 내부 맥락 제거).

`RegisterStartReq.java` — record 선언(`public record RegisterStartReq(`) 위:
```java
/** 등록 시작 요청 본문. {@code POST /passkey/register/begin} 에서 받는다. */
```
`RegisterCompleteReq.java`:
```java
/** 등록 완료 요청 본문. {@code POST /passkey/register/finish} 에서 받는다. publicKeyCredential 은 브라우저가 만든 인증기 응답, regRelayToken 은 begin 이 돌려준 서명 토큰. */
```
`RegisterOptionsResp.java` — 기존 KDoc(무상태 릴레이 설명)을 아래로 교체:
```java
/**
 * 등록 시작 응답. {@code POST /passkey/register/begin} 이 돌려준다.
 * publicKeyCredentialCreationOptions 는 브라우저 navigator.credentials.create() 에 그대로 넘기고,
 * regRelayToken 은 finish 요청에 다시 실어 보낸다(서버 세션 없이 begin↔finish 를 잇는 서명 토큰).
 */
```

- [ ] **Step 2: 인증 DTO 4개에 클래스 Javadoc 추가**

`LoginStartReq.java`:
```java
/** 인증 시작 요청 본문. {@code POST /passkey/authenticate/begin}. username 이 없으면 discoverable(사용자 선택) 로그인. */
```
`LoginCompleteReq.java`:
```java
/** 인증 완료 요청 본문. {@code POST /passkey/authenticate/finish}. publicKeyCredential 은 브라우저 인증기 응답, authenticationToken 은 begin 이 돌려준 토큰. */
```
`LoginOptionsResp.java` — 기존 KDoc 교체:
```java
/**
 * 인증 시작 응답. {@code POST /passkey/authenticate/begin} 이 돌려준다.
 * publicKeyCredentialRequestOptions 는 브라우저 navigator.credentials.get() 에 넘기고,
 * authenticationToken 은 finish 요청에 다시 실어 보낸다(무상태 릴레이).
 */
```
`LoginResultResp.java` — 기존 KDoc 교체:
```java
/**
 * 인증 완료 결과. id-token 은 rp-app 내부에서만 검증·소비하고 외부에 노출하지 않는다.
 * 클라이언트는 이 결과로 "인증됨" 을 확인하고 자기 화면 흐름을 진행한다.
 */
```

- [ ] **Step 3: 나머지 데이터 구조 4개에 한 줄 Javadoc**

`RpAppUser.java` — record 위(내부 @JsonProperty 주석은 유지):
```java
/** rp-app 이 보관하는 사용자 레코드. username ↔ userHandle ↔ credentialId 매핑의 한 행. credentialId 가 null 이면 등록 미완(pending). */
```
`ErrorDetail.java`:
```java
/** 에러 응답의 상세. 에러 코드와 (검증 실패 시) 필드별 오류 목록. */
```
`FieldError.java`:
```java
/** 입력 검증 실패 시 필드 단위 오류 1건. */
```
`PageResponse.java` — record 위에 한 줄 Javadoc 추가:
```java
/** 페이지네이션 응답 봉투(목록 + 페이지 메타데이터). */
```
그리고 compact 생성자 안의 인라인 주석 `// 원본 Kotlin 의 non-null `content: List<T>` 계약을 보존(null 시 fail-fast).` 을 교체(마이그레이션 흔적 제거):
```java
        // content 는 필수 — null 이면 즉시 실패시킨다.
```
(`Objects.requireNonNull(content)` 코드 라인은 불변.)

- [ ] **Step 4: 컴파일 검증**

Run: `./gradlew :rp-app:compileJava`
Expected: BUILD SUCCESSFUL. (Javadoc `{@code}` 형식 오류 없음.)

- [ ] **Step 5: 코드 라인 불변 확인**

Run: `git diff --stat` 그리고 `git diff` 로 변경이 주석 줄(`/**`, `*`, `//`)에만 있는지 확인.
Expected: 코드(필드/생성자/어노테이션) 라인 변경 0.

- [ ] **Step 6: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/web/dto/ \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/user/RpAppUser.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/common/response/ErrorDetail.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/common/response/FieldError.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/common/response/PageResponse.java
git commit -m "docs(rp-app): 계층4 데이터 구조 11개 고객사 관점 주석"
```

---

### Task 2: 계층 3 — 인프라/유틸/부트스트랩 13개 (간결 + 교체 가이드)

**Files (주석만 수정):**
- `.../RpAppApplication.java`
- `.../web/SecretRedactor.java`
- `.../web/SecretMaskingConverter.java`
- `.../web/RedactingMessageJsonProvider.java`
- `.../web/CompactMdcConverter.java`
- `.../web/RequestLoggingFilter.java`
- `.../common/filter/TraceIdFilter.java`
- `.../web/PageController.java`
- `.../web/WellKnownController.java`
- `.../common/exception/ErrorCode.java`
- `.../common/exception/BusinessException.java`
- `.../common/exception/GlobalExceptionHandler.java`
- `.../common/response/ApiResponse.java`

**목표:** 클래스 Javadoc 1~3줄로 "무엇을 하는가 + 고객사는 그대로 써도 됨/교체"를 명시. 내부 맥락(core twin/Mirror/:core 비의존/drift/Keep in sync, 테스트 참조, 마이그레이션 흔적) 전부 제거. 보안 근거(로그 마스킹 등)는 유지.

- [ ] **Step 1: RpAppApplication.java — 부트스트랩 주석 정리**

`main` 안의 3줄 인라인 주석을 아래로 교체(내부 ":core 비의존" 제거):
```java
        // 배포 JVM 의 타임존 설정에 의존하지 않도록 기본 타임존을 KST(Asia/Seoul) 로 고정한다.
        // SpringApplication.run 이전에 호출해야 모든 빈이 KST 기준으로 초기화된다.
```
그리고 클래스 선언 위에 한 줄 Javadoc 추가:
```java
/** rp-app 진입점. RP 데모 서버를 기동한다(패스키 등록/인증 릴레이 + ID Token 검증 + 네이티브 앱 well-known 호스팅). */
```

- [ ] **Step 2: SecretRedactor.java — 클래스 Javadoc 교체(core 참조 제거)**

기존 클래스 Javadoc(`/** ... Mirror of ...core... Keep the two in sync. */`)을 교체:
```java
/**
 * 로그 메시지에서 비밀값(API key / JWT / password / bcrypt / 토큰 필드)을 마스킹하는 공유 헬퍼.
 * 텍스트 로그({@link SecretMaskingConverter})와 JSON 로그({@link RedactingMessageJsonProvider})가
 * 같은 로직을 공유한다. 고객사는 자사 로그에 노출될 수 있는 비밀 패턴을 여기에 맞춰 조정한다.
 */
```
인라인 패턴 주석(// X-API-Key …, // Bearer …, // Order matters …)은 보안 동작 설명이므로 유지. "drift 를 막는다"가 들어간 줄만 위 Javadoc으로 흡수돼 사라진다.

- [ ] **Step 3: SecretMaskingConverter.java — core 참조 제거**

기존 Javadoc 교체:
```java
/**
 * 모든 콘솔 로그 메시지에 비밀값 마스킹을 적용하는 logback MessageConverter.
 * 개발자가 실수로 API key·JWT·password 를 로그에 남겨도 출력 시점에 가린다.
 *
 * <p>logback-spring.xml 에서 {@code %msg} 변환 규칙으로 등록된다:
 * <pre>{@code <conversionRule conversionWord="msg" converterClass="...SecretMaskingConverter"/>}</pre>
 * 마스킹 로직은 {@link SecretRedactor} 를 공유한다.
 */
```

- [ ] **Step 4: RedactingMessageJsonProvider.java — 한 줄 유지(이미 깔끔, 변경 최소)**

기존 Javadoc은 내부 맥락이 없으므로 표현만 다듬는다:
```java
/**
 * JSON 로그의 message 필드에 텍스트 로그와 동일한 비밀값 마스킹을 적용한다.
 * LogstashEncoder 기본 provider 는 마스킹을 우회하므로 이 provider 로 교체한다.
 */
```

- [ ] **Step 5: CompactMdcConverter.java — core twin 제거**

기존 Javadoc 교체:
```java
/**
 * 값이 있는 MDC 키만 {@code [traceId=.. tenantId=.. ..]} 형태로 묶어 로그에 출력한다.
 * 빈 키는 생략하고, 전부 비면 빈 문자열. 값은 16자로 자른다.
 */
```

- [ ] **Step 6: RequestLoggingFilter.java — twin/drift/actorEmail 내부 메모 정리**

기존 클래스 Javadoc(core twin, Drift check, TraceIdPropagationInterceptor 내부 설명, "rp-app does not yet have one" 등)을 고객사 관점으로 교체:
```java
/**
 * 요청 1건당 한 줄 요약(method/path/status/durMs)을 INFO/WARN/ERROR 로 남기는 필터.
 * 요청·응답 본문은 별도 payload 로거에 DEBUG 로만 남기므로 운영(qa/prod)에서는 자동 억제된다.
 * 본문은 2KB 로 자르고, {@link SecretMaskingConverter} 가 비밀값을 마스킹한다(다중 방어).
 *
 * <p>{@code TraceIdFilter} 가 먼저 traceId 를 MDC 에 넣고, SDK 호출 시 같은 X-Trace-Id 가
 * passkey-app 으로 전파돼 두 서버 로그를 한 추적 단위로 묶는다.
 */
```
`/** Mirror of core's RequestLoggingFilter.ACTOR_EMAIL_ATTR. */` 줄은 아래로 교체:
```java
/** 인증 필터가 요청 속성에 넣은 사용자 이메일을 읽어 MDC actorEmail 로 남길 때 쓰는 키(데모는 미설정). */
```

- [ ] **Step 7: TraceIdFilter.java — 표현 다듬기(내부 맥락 없음, 경미)**

```java
/**
 * 요청마다 추적 ID 를 준비하는 필터. X-Trace-Id 헤더가 오면 그대로 쓰고, 없으면 새로 발급해
 * MDC "traceId" 와 응답 헤더에 넣는다. 이 MDC 키는 SDK 가 passkey-app 으로 전파하는 키와 같아야 한다.
 */
```

- [ ] **Step 8: PageController.java — 클래스 Javadoc 신규(현재 없음)**

클래스 선언 위:
```java
/** 데모 화면(Thymeleaf) 라우팅. index / register / login 페이지를 돌려준다. */
```

- [ ] **Step 9: WellKnownController.java — 표현 다듬기 + 교체 가이드 한 줄**

기존 Javadoc 끝에 고객사 가이드를 더하고 "검증 에이전트" 같은 내부 표현 정리:
```java
/**
 * 네이티브 앱 패스키용 Well-Known URI 호스팅. OS/CDN(Google Play services, Apple CDN)이
 * 직접 가져가 "도메인 → 앱" 소유를 검증하는 표준 포맷이라, 공통 응답 봉투 없이 표준 JSON 을 직접 반환한다.
 *
 * <p>주의: apple-app-site-association 은 확장자가 없어 {@code produces=APPLICATION_JSON_VALUE} 로
 * Content-Type 을 강제한다. 고객사는 {@code rp-app.well-known.*} 설정만 자사 앱 값으로 바꾸면 된다.
 */
```

- [ ] **Step 10: ErrorCode.java — 클래스 Javadoc 신규 + 섹션 주석 유지**

enum 선언 위에 한 줄(섹션 주석 // Common 등은 유지):
```java
/** rp-app 의 표준 에러 코드. HTTP 상태 + 코드 문자열 + 기본 메시지를 묶는다(공통/인증/WebAuthn/업스트림). */
```

- [ ] **Step 11: BusinessException.java — 클래스 Javadoc 신규**

```java
/** 도메인 규칙 위반을 나타내는 예외. {@link ErrorCode} 를 실어 GlobalExceptionHandler 가 일관된 응답으로 변환한다. */
```

- [ ] **Step 12: GlobalExceptionHandler.java — 섹션 주석 정리(core 참조 제거)**

클래스 선언 위에 한 줄 Javadoc 추가:
```java
/** 모든 컨트롤러 예외를 표준 응답 봉투로 변환하는 전역 핸들러(입력 검증·SDK 업스트림 오류·예상 외 오류). */
```
`// rejectedValue 를 echo 하지 않는다(core GlobalExceptionHandler 와 동일) —` 줄에서 "(core … 와 동일)"만 제거:
```java
        // rejectedValue 를 응답에 그대로 내보내지 않는다 — 필드명 + 메시지로 충분하고, 입력값 반사를 피한다.
```
섹션 구분 주석(// ── 템플릿 8 ──, // ── SDK 예외 변환 4 ──, // ── 마지막 fallback ──)은 유지.

- [ ] **Step 13: ApiResponse.java — 클래스 Javadoc 신규 + 마이그레이션 흔적 제거**

record 선언 위에 한 줄:
```java
/** 모든 JSON 응답의 공통 봉투. success/code/message/data/error/traceId/timestamp 를 담는다. ok()/error() 팩터리로 생성. */
```
`// 원본 Kotlin 의 non-null `List<FieldError>` 파라미터 계약을 보존(null 시 fail-fast).` 줄을 아래로 교체:
```java
        // fieldErrors 는 필수 — null 이면 즉시 실패시켜 호출 측 실수를 빨리 드러낸다.
```

- [ ] **Step 14: 컴파일 + 코드 불변 확인**

Run: `./gradlew :rp-app:compileJava` → BUILD SUCCESSFUL.
Run: `git diff` 로 코드 라인 변경 0(주석 줄만) 확인.

- [ ] **Step 15: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/RpAppApplication.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/web/SecretRedactor.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/web/SecretMaskingConverter.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/web/RedactingMessageJsonProvider.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/web/CompactMdcConverter.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/web/RequestLoggingFilter.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/common/filter/TraceIdFilter.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/web/PageController.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/web/WellKnownController.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/common/exception/ErrorCode.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/common/exception/BusinessException.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/common/exception/GlobalExceptionHandler.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/common/response/ApiResponse.java
git commit -m "docs(rp-app): 계층3 인프라·유틸 13개 고객사 관점 주석(내부 맥락 제거)"
```

---

### Task 3: 계층 2 — 설정/보안/저장소 8개 (운영 주의 + 교체 강조)

**Files (주석만 수정):**
- `.../user/InMemoryUserStore.java`
- `.../config/PasskeyProperties.java`
- `.../config/CorsProperties.java`
- `.../config/RelayProperties.java`
- `.../config/RelayKeyGuard.java`
- `.../config/WellKnownProperties.java`
- `.../config/WebSecurityConfig.java`
- `.../config/ReloadableApiKeySupplier.java`

**목표:** 고객사가 직접 바꾸는 설정·보안·저장소. "어떻게 설정 + 운영 주의"를 명시하고 내부 맥락(spec §, P2-a, VALUE_OBJECT 바인딩, 테스트 참조, drift)을 제거. **InMemoryUserStore 교체 가이드를 가장 강하게.**

- [ ] **Step 1: InMemoryUserStore.java — 교체 강조 클래스 Javadoc**

기존 클래스 Javadoc 교체(데모 한정·교체 안내를 최상단에):
```java
/**
 * username ↔ userHandle ↔ credentialId 매핑 저장소.
 *
 * <p><b>⚠️ 데모용 인메모리 구현이다.</b> 확정 등록된 사용자만 JSON 파일에 미러링하고, 단일 인스턴스를
 * 가정하며 파일 락을 두지 않는다. <b>고객사는 이 클래스를 자사 DB/영속 계층(JPA·MyBatis 등)으로
 * 교체</b>해야 한다 — 컨트롤러가 의존하는 메서드(createPending / isUsernameTakenByOther /
 * confirmRegistration / findByUserHandle / findByUsername)만 동일하게 제공하면 된다.
 */
```
메서드 주석 중 내부 이슈 코드(W001, P0-4) 제거·풀어쓰기:
- `createPending` Javadoc의 "…W001 버그를 막기 위함." 부분을 교체:
```java
    /**
     * username 으로 새 userHandle(32바이트 base64url)을 발급하고 pending 상태로 저장한다.
     *
     * <p>begin 단계에서는 username 을 선점하지 않는다(byUsername 매핑을 만들지 않음).
     * begin 만 하고 finish 를 못 한 사용자(다이얼로그 취소·이탈·네트워크 단절)가 같은 username 으로
     * 다시 시도할 수 있게 하기 위함이다. 실제 username 충돌 방지는 confirmRegistration 의
     * putIfAbsent(최종 권위) + 컨트롤러의 isUsernameTakenByOther 선검사가 담당한다.
     */
```
- `confirmRegistration` Javadoc의 "(완전 무상태, P0-4)" → "(서버에 pending 상태를 두지 않는 무상태 설계)" 로 교체. 나머지 putIfAbsent 원자성·이중 방어 설명은 유지(보안 근거).

- [ ] **Step 2: PasskeyProperties.java — 클래스 Javadoc 신규 + 컴포넌트 주석 유지**

record 선언 위에 클래스 Javadoc 추가(개별 컴포넌트 Javadoc은 유지):
```java
/**
 * passkey-app 연동 설정({@code passkey.*}). 고객사는 발급받은 base-url / api-key / tenant-id /
 * issuer-base 를 환경변수나 yml 로 주입한다.
 */
```
`issuerBase` 컴포넌트 Javadoc의 "로컬 데모에서는 passkey-app 의 application-local.yml…" 문장은 운영 안내로 다듬되, issuer-base 가 iss 검증 prefix라는 핵심은 유지.

- [ ] **Step 3: CorsProperties.java — spec §3·드리프트·테스트 참조 제거, 보안 근거 유지**

클래스 Javadoc 교체:
```java
/**
 * cross-origin 웹 클라이언트를 위한 허용 origin 화이트리스트({@code rp.cors.allowed-origins}).
 * ⚠️ 정확한 origin 목록만 허용한다 — 와일드카드·요청 Origin 반사는 금지. 비우면 CORS 가 비활성(같은-origin 만).
 * 고객사는 자사 웹 origin 을 passkey-app 테넌트의 allowed-origins 와 동일하게 맞춘다.
 */
```
compact 생성자 안 검증 주석에서 "(spec §3)" 제거:
```java
        // 와일드카드(*)·패턴(*.example.com)·빈 값을 거부한다 — 잘못된 설정으로 cross-origin 이
        // 무차별 허용되는 것을 부팅 시점에 차단한다. 비면(목록 없음) CORS 자체가 비활성이라 정상.
```
(검증 로직·예외·메시지는 코드라 불변.)

- [ ] **Step 4: RelayProperties.java — spec §5·P2-a·VALUE_OBJECT·테스트 참조 제거**

클래스 Javadoc 교체:
```java
/**
 * 등록 릴레이 토큰의 HMAC 서명 설정({@code rp.relay.*}). registrationToken↔userHandle 바인딩에 쓴다.
 * <b>secret 은 운영에서 반드시 강한 키로 주입</b>한다(미설정이면 데모 기본 키가 쓰이고, RelayKeyGuard 가
 * 운영 프로필에서 기동을 차단한다). ttl 은 passkey-app 의 challenge 만료(기본 5분)와 맞춘다.
 */
```
생성자 안 `// 데모 폴백. non-dev 프로필에선 RelayKeyGuard 가 차단(P2-a).` → `// 미설정/빈 값이면 데모 기본 키로 폴백한다. 운영 프로필에서는 RelayKeyGuard 가 이 기본 키를 거부한다.`

- [ ] **Step 5: RelayKeyGuard.java — spec §5/P2-a 제거**

클래스 Javadoc 교체:
```java
/** 운영(또는 프로필 미지정) 환경에서 relay 데모 기본 키 사용을 막는 가드. 기동 시 검사해 위반이면 실패시킨다. */
```
`check()` 안 주석은 동작 설명이므로 유지(내부 코드 없음).

- [ ] **Step 6: WellKnownProperties.java — VALUE_OBJECT 메모 제거**

기존 Javadoc 끝의 "모든 @ConfigurationProperties 가 @ConfigurationPropertiesScan(VALUE_OBJECT …) … 정상 동작한다." 문단을 제거. 앞부분(앱 메타데이터 설명 + android/ios 목록)은 고객사에 유용하므로 유지. 결과:
```java
/**
 * 네이티브 앱 패스키용 Well-Known URI(assetlinks.json / apple-app-site-association)에 들어갈 앱 메타데이터.
 * 고객사는 코드를 고치지 않고 {@code rp-app.well-known.*} 환경변수/yml 로 자사 앱 값만 채운다.
 *
 * <ul>
 *   <li>android: assetlinks.json 의 target 배열. 앱마다 패키지명 + 서명 지문 목록(디버그/릴리즈 지문이 여러 개면 나열).</li>
 *   <li>ios: apple-app-site-association 의 webcredentials.apps. "TeamID.BundleID" 목록.</li>
 * </ul>
 */
```

- [ ] **Step 7: WebSecurityConfig.java — spec §3 제거, STATELESS/CSRF 근거 유지**

`corsConfigurationSource()` Javadoc에서 "(spec §3)"만 제거(나머지 보안 설명 유지):
```java
    /**
     * {@code /passkey/**} 경로에만 적용되는 CORS 정책.
     * ⚠️ 정확한 origin 목록만 허용(요청 Origin 반사·와일드카드 금지).
     * allowedOrigins 가 비면 매칭 origin 이 없어 cross-origin 요청은 막힌다(같은-origin 데모만).
     * 자격증명(쿠키)을 보내지 않으므로 allowCredentials=false.
     */
```
클래스 선언 위에 한 줄 Javadoc 추가:
```java
/** rp-app 보안 설정. 무상태 클라이언트 전제로 세션·CSRF 를 끄고 /passkey 경로에 CORS 를 건다(데모는 모든 경로 permitAll). */
```
인라인 주석(`// 무상태 클라이언트: …CSRF 비활성.`, `// 데모용 — 보호 리소스 없음`)은 보안 결정 근거라 유지.

- [ ] **Step 8: ReloadableApiKeySupplier.java — RedactingRequestInterceptor 내부 참조 다듬기**

클래스 Javadoc 교체(SDK 내부 클래스명 대신 동작으로):
```java
/**
 * API Key 를 파일에서 핫리로드하는 공급자. SDK 가 요청마다 get() 을 호출하므로, 운영자가 키 파일만 바꾸면
 * 재기동 없이 다음 요청부터 새 키가 반영된다(서버의 무중단 키 교체와 짝지어 쓴다).
 *
 * <p>스레드 안전: 캐시 상태(키 + 파일 수정시각)를 불변 객체로 묶어 단일 volatile 참조로 발행한다.
 */
```
maybeReload 내 인라인 주석(폴링 throttle, fail-safe 등)은 동작 설명이라 유지.

- [ ] **Step 9: 컴파일 + 코드 불변 확인**

Run: `./gradlew :rp-app:compileJava` → BUILD SUCCESSFUL.
Run: `git diff` 로 코드 라인 변경 0 확인(특히 CorsProperties/RelayProperties의 로직·예외 메시지 불변).

- [ ] **Step 10: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/user/InMemoryUserStore.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/config/PasskeyProperties.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/config/CorsProperties.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/config/RelayProperties.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/config/RelayKeyGuard.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/config/WellKnownProperties.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/config/WebSecurityConfig.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/config/ReloadableApiKeySupplier.java
git commit -m "docs(rp-app): 계층2 설정·보안·저장소 8개 운영 주석 + InMemoryUserStore 교체 강조"
```

---

### Task 4: 계층 1 — 핵심 플로우 3개 (상세 Javadoc + 보안 인라인)

**Files (주석만 수정):**
- `.../web/WebAuthnController.java`
- `.../web/relay/RegRelayCodec.java`
- `.../config/PasskeyClientConfiguration.java`

**목표:** RP 플로우의 진입점. 고객사가 "왜 이렇게 조립하는가"를 읽고 따라할 수 있도록 클래스/메서드 Javadoc을 상세히. 내부 맥락(P0-4, "원본 Java(props.issuerBase…)", jackson-module-kotlin, 단일 인스턴스 race 장문) 제거·압축. 보안 근거(HMAC 상수시간, iss/aud 검증, fail-fast, 4필드 null 거부)는 유지·강화.

- [ ] **Step 1: WebAuthnController.java — 클래스 Javadoc 신규**

클래스 선언(`public class WebAuthnController`) 위:
```java
/**
 * 패스키 등록·인증의 RP 엔드포인트. 브라우저와 passkey-app 사이의 중계자다.
 *
 * <p>4개 엔드포인트는 모두 2-step(begin → finish)으로 동작한다:
 * <ul>
 *   <li>{@code POST /passkey/register/begin} → {@code /register/finish}</li>
 *   <li>{@code POST /passkey/authenticate/begin} → {@code /authenticate/finish}</li>
 * </ul>
 *
 * <p>서버에 세션을 두지 않는다. begin 응답으로 받은 서명 토큰(regRelayToken / authenticationToken)을
 * finish 요청에 다시 실어 두 단계를 잇는다. 실제 WebAuthn 의식과 ID Token 발급은 passkey-app 이 맡고,
 * rp-app 은 SDK 호출·사용자 매핑·ID Token(iss/aud/sub) 검증을 담당한다.
 */
```

- [ ] **Step 2: WebAuthnController.java — registerComplete 의 긴 race 주석 압축**

`registerComplete` 안 username 선검사 블록의 6줄 주석(단일 인스턴스 race 장문)을 보안 의도 중심으로 압축:
```java
        // finish 전에 username 선점 여부를 검사한다. 유효한 begin(HMAC 으로 증명된) 에서 온 username 이라도
        // finish 시점에 다른 사용자에게 확정돼 있으면 typed-login 탈취/충돌을 막기 위해 거부한다.
        // 최종 권위는 confirmRegistration 의 putIfAbsent(원자적 점유)이고, 이 선검사는 upstream 호출 전 조기 차단이다.
```
`// @Valid @NotNull 로 검증된 필드 …` 인라인 주석은 유지(동작 근거).

- [ ] **Step 3: WebAuthnController.java — loginComplete iss/aud 검증 주석 정리(보안 유지, 내부 표현 제거)**

iss 검증 블록의 `// 원본 Java(props.issuerBase().toString())는 issuerBase 누락 시 NPE→500 으로 fail-fast.` 줄을 교체:
```java
        // iss = "<issuerBase>/<tenantId>". issuerBase 가 설정돼 있어야 검증할 수 있으므로 누락 시 즉시 실패시킨다.
```
앞의 tenantId 정규화 설명 주석(표기 차이로 인한 거짓 mismatch 방지)은 보안 근거라 유지. "PASSKEY_TENANT_ID" 같은 env 키 언급은 고객사에 유용하므로 유지.

- [ ] **Step 4: RegRelayCodec.java — 클래스 Javadoc 정리(P0-4 제거, 보안 강조)**

클래스 Javadoc 교체:
```java
/**
 * 등록 릴레이 토큰 코덱. {registrationToken, userHandle, username, displayName, exp} 를 HMAC-SHA256 으로
 * 서명해 "base64url(payloadJson).base64url(hmac)" 형태의 불투명 토큰을 만들고 검증한다.
 *
 * <p>서명이 맞아야 payload 를 신뢰하므로 클라이언트가 userHandle 을 조작할 수 없다. 토큰이 자기완결적이라
 * 서버에 pending 상태를 두지 않고도 finish 단계에서 사용자를 확정할 수 있다(무상태 설계).
 */
```

- [ ] **Step 5: RegRelayCodec.java — decode 4필드 검증 주석 압축(보안 유지), payload 마이그레이션 흔적 제거**

decode 안 4필드 null 거부 주석(레거시 토큰 장문)을 압축:
```java
        // 필수 4필드 검증. 값이 빠진 토큰을 upstream 호출 전에 거부해, 매핑 누락으로 인한 오류를 막고
        // 클라이언트가 등록을 처음부터 깨끗이 다시 시작하게 한다.
```
`ObjectNodePayload` Javadoc의 "jackson-module-kotlin 유무와 무관하게 record 와 동일 복원." → 교체:
```java
    /** 직렬화 payload. 필드명을 짧게(rt/uh/un/dn/exp) 유지하고 {@code @JsonProperty} 로 매핑을 명시한다. */
```
`// 상수시간 비교(타이밍 공격 방지).` 는 유지(보안 핵심). encode/decode 메서드 Javadoc(`/** {rt,...} 를 서명한 … */`, `/** relay 토큰 검증·복원 … */`)도 유지.

- [ ] **Step 6: PasskeyClientConfiguration.java — 클래스 Javadoc 신규 + 인라인 정리**

클래스 선언 위:
```java
/**
 * SDK {@link PasskeyClient} 빈 구성. 고객사 RP 의 SDK 연동 레퍼런스다.
 * baseUrl·apiKey 등 {@code passkey.*} 설정을 읽어 클라이언트를 만든다. API Key 는 핫리로드 가능한 공급자로 주입한다.
 */
```
`apiKeySupplier` 메서드 Javadoc은 유지(동작 설명). `passkeyClient` 안 `// SDK 의 Builder 는 …fail-fast.` 인라인은 유지(baseUrl 필수 근거).

- [ ] **Step 7: 컴파일 + 코드 불변 확인**

Run: `./gradlew :rp-app:compileJava` → BUILD SUCCESSFUL.
Run: `git diff` 로 코드 라인 변경 0 확인(특히 normalizeTenantId 로직, iss/aud 검증식, HMAC 비교 불변).

- [ ] **Step 8: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/web/WebAuthnController.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/web/relay/RegRelayCodec.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/config/PasskeyClientConfiguration.java
git commit -m "docs(rp-app): 계층1 핵심 플로우 3개 상세 주석(보안 근거 보존·강화)"
```

---

### Task 5: rp-app/README.md 신규 + 최종 검증

**Files:**
- Create: `rp-app/README.md`

**목표:** 고객사 RP 구축 통합 가이드. 실제 엔드포인트/설정/기본값과 1:1 대조. sdk-java/README.md로 SDK 사용법 링크.

- [ ] **Step 1: 실제 설정값 재확인(README 정확성 근거)**

Run:
```bash
cat rp-app/src/main/resources/application.yml
cat rp-app/src/main/resources/application-local.yml
grep -n "@PostMapping\|@GetMapping\|@RequestMapping" rp-app/src/main/java/com/crosscert/passkey/rpapp/web/*.java
```
Expected: 엔드포인트 경로(`/passkey/register/begin` 등)와 프로퍼티 키·기본값을 확인해 README에 정확히 반영.

- [ ] **Step 2: rp-app/README.md 작성**

`rp-app/README.md` 생성. 아래 8섹션 구조(spec §4). 모든 경로·키·기본값은 Step 1에서 확인한 실제 값으로. SDK 사용법은 `../sdk-java/README.md` 링크로 위임.

```markdown
# rp-app — Passkey RP 레퍼런스 구현

자사 서비스(RP, Relying Party)에 패스키 로그인을 붙이려는 개발자를 위한 **샘플 RP 서버**입니다.
passkey-app(패스키 서버) 뒤에 두고, 브라우저↔passkey-app 사이의 중계와 ID Token 검증을 담당합니다.

## 1. 개요

- **무엇인가**: passkey-app 의 SDK(`sdk-java`)를 사용해 패스키 등록/인증을 중계하는 RP 서버 예제.
- **누가 보는가**: 자사 RP 를 구축하려는 고객사 개발자.
- **무엇을 보여주는가**: 무상태 토큰 릴레이 기반 등록/인증 2-step 흐름, ID Token(iss/aud/sub) 검증,
  네이티브 앱용 well-known(assetlinks.json / apple-app-site-association) 호스팅, 로그 비밀값 마스킹.
- **요구 사항**: Java 17, Spring Boot 3. 실행 시 passkey-app(기본 :8080)이 떠 있어야 한다.

## 2. 아키텍처

```
Browser ──► rp-app(:9090) ──(SDK, X-API-Key)──► passkey-app(:8080)
            │
            ├─ 사용자 매핑 보관(InMemoryUserStore — 데모용, 교체 대상)
            ├─ ID Token 검증(iss/aud/sub)
            └─ /.well-known/* 호스팅
```

rp-app 의 책임: SDK 호출, username↔userHandle↔credential 매핑, ID Token 검증, well-known 정적 응답.
passkey-app 에 위임: WebAuthn 의식 처리, challenge/credential 저장, ID Token 발급.

## 3. 빠른 시작

```bash
./gradlew :rp-app:bootRun --args="--spring.profiles.active=local"
```

`local` 프로필은 데모 값으로 채워져 있다(passkey-app :8080, demo-rp 테넌트). 자사 환경에서는 아래 설정을 주입한다:
`passkey.base-url`, `passkey.api-key`(또는 `api-key-file`), `passkey.tenant-id`, `passkey.issuer-base`.

## 4. 요청 흐름

등록(2-step):
1. `POST /passkey/register/begin` `{ username, displayName }` → `{ publicKeyCredentialCreationOptions, regRelayToken }`
2. 브라우저가 `navigator.credentials.create(...)` 실행
3. `POST /passkey/register/finish` `{ publicKeyCredential, regRelayToken }` → 등록 결과

인증(2-step):
1. `POST /passkey/authenticate/begin` `{ username? }` → `{ publicKeyCredentialRequestOptions, authenticationToken }`
   (username 없으면 discoverable 로그인)
2. 브라우저가 `navigator.credentials.get(...)` 실행
3. `POST /passkey/authenticate/finish` `{ publicKeyCredential, authenticationToken }` → `{ authenticated, userHandle, displayName }`

**무상태 릴레이 토큰**: begin 이 돌려준 `regRelayToken`/`authenticationToken` 을 finish 에 다시 보낸다.
서버 세션 없이 두 단계를 잇고, regRelayToken 은 HMAC 서명이라 userHandle 조작을 막는다.

## 5. 고객사가 반드시 손봐야 할 곳

| 대상 | 무엇을 | 이유 |
|---|---|---|
| `user/InMemoryUserStore` | 자사 DB/영속 계층으로 교체 | 데모용 인메모리·단일 인스턴스 전제 |
| `rp.relay.secret` | 운영용 강한 키 주입 | 미설정 시 데모 키 → RelayKeyGuard 가 기동 차단 |
| `rp.cors.allowed-origins` | 자사 웹 origin 정확 목록 | 와일드카드·반사 금지 |
| `rp-app.well-known.*` | 자사 앱 패키지/지문/App ID | 네이티브 앱 패스키 동작 조건 |
| `passkey.api-key` / `api-key-file` | 발급받은 API Key | passkey-app 인증 |
| `passkey.tenant-id` / `issuer-base` | 자사 테넌트 값 | ID Token iss/aud 검증 |

## 6. 설정 레퍼런스

(application.yml / application-local.yml 의 실제 키·기본값을 표로. Step 1 확인 결과 반영.)

| 키 | 기본값 | 의미 |
|---|---|---|
| `passkey.base-url` | `http://localhost:8080` | passkey-app 주소 |
| `passkey.api-key` | (빈 값) | X-API-Key |
| `passkey.api-key-file` | (빈 값) | 키 파일 핫리로드 경로(설정 시 api-key 보다 우선) |
| `passkey.api-key-reload` | `10s` | 키 파일 폴링 주기 |
| `passkey.tenant-id` | (빈 값) | 테넌트 UUID |
| `passkey.issuer-base` | `http://localhost:8080` | ID Token iss prefix |
| `passkey.connect-timeout` / `read-timeout` / `jwks-cache-ttl` | `3s` / `10s` / `5m` | SDK 타임아웃·JWKS 캐시 |
| `rp.relay.secret` | (데모 키) | 릴레이 HMAC 키 — 운영 필수 교체 |
| `rp.relay.ttl` | `5m` | 릴레이 토큰 만료 |
| `rp.cors.allowed-origins` | (빈 값=비활성) | 허용 origin 목록 |
| `rp-app.well-known.android[].package-name` / `sha256-fingerprints` | 샘플 앱 값 | Android 앱 메타데이터 |
| `rp-app.well-known.ios.app-ids` | 샘플 앱 값 | iOS 앱 메타데이터 |
| `rp-app.user-store.file` | `./data/rp-app-users.json` | 데모 저장소 파일(교체 시 무의미) |

## 7. 보안 노트

고객사가 자사 RP 에서 그대로 따라야 할 패턴:
- **ID Token 검증**: iss(=issuer-base/tenant), aud(=tenant), sub(=userHandle)를 매 인증마다 검증한다. (`WebAuthnController.loginComplete`)
- **릴레이 토큰 HMAC 바인딩**: registrationToken↔userHandle 을 서명해 조작을 막는다. 서명 비교는 상수시간(`RegRelayCodec`).
- **CORS**: 정확한 origin 목록만. 와일드카드·요청 Origin 반사 금지(`CorsProperties`).
- **로그 마스킹**: API Key·JWT·password 등을 출력 시점에 가린다(`SecretRedactor`).
- **무상태/CSRF**: 서버 세션을 두지 않으므로 STATELESS + CSRF 비활성(`WebSecurityConfig`). 토큰 릴레이로 단계를 잇는다.

## 8. SDK 연동

SDK(`PasskeyClient`) 자체의 설정·API·ID Token 검증 사용법은 **[sdk-java/README.md](../sdk-java/README.md)** 를 참고한다.
rp-app 의 `config/PasskeyClientConfiguration` 이 SDK 연동 레퍼런스 예제다.
```

작성 시 Step 1에서 확인한 실제 값과 표를 1:1로 맞춘다(특히 기본값·키 이름).

- [ ] **Step 3: README 정확성 대조**

Run:
```bash
grep -oE "/passkey/(register|authenticate)/(begin|finish)" rp-app/src/main/java/com/crosscert/passkey/rpapp/web/WebAuthnController.java | sort -u
```
Expected: README §4의 4개 경로와 정확히 일치. 설정 표(§6)의 키·기본값이 application.yml과 일치하는지 눈으로 대조.

- [ ] **Step 4: 내부 맥락 잔존 grep 검사(전체)**

주의: ErrorCode 의 enum 값 `"W001"/"W002"/"W003"` 은 **API 에러 코드(코드 라인)** 이므로 제거 대상이 아니다(false-positive). 따라서 주석 줄만 검사하도록 코드 라인을 제외한다.

Run (주석 줄만 대상 — `*`/`//` 로 시작하거나 포함하는 줄로 한정):
```bash
grep -rnE "spec §|P0-[0-9]|P2-[a-z]|core 의|:core|Mirror of| twin |트윈|드리프트|drift|Keep .* in sync|원본 (Java|Kotlin)|@JvmRecord|companion object|jackson-module-kotlin|VALUE_OBJECT|fixture @Bean|Test 가 .*검증|Test 가 .*주입" rp-app/src/main/java
```
Expected: **출력 0건**. (위 패턴에서 `W00[0-9]` 는 제외했다 — ErrorCode 코드 값과 충돌하기 때문. "twin" 은 앞뒤 공백으로 한정해 단어 단위로만 매칭.)
추가 확인 — 코드 값을 제외한 "W00N" 주석 잔존이 없는지 별도 검사:
```bash
grep -rnE "W00[0-9]" rp-app/src/main/java | grep -vE '"W00[0-9]"'
```
Expected: **출력 0건**(따옴표로 둘러싸인 enum 코드 값만 남고, 주석 속 W00N 은 없음).
남아 있으면 해당 파일을 다시 정리(이전 Task 보완).

- [ ] **Step 5: 전체 컴파일 + 테스트 (회귀 0 확인)**

Run: `./gradlew :rp-app:clean :rp-app:test`
Expected: BUILD SUCCESSFUL, 69 tests, 0 failures, 1 skipped(@Disabled RpAppSmokeIT). 주석·문서만 바꿨으므로 테스트 결과는 마이그레이션 완료 시점과 동일해야 한다.

- [ ] **Step 6: 커밋**

```bash
git add rp-app/README.md
git commit -m "docs(rp-app): 고객사 RP 구축 통합 가이드 README 신규"
```

---

## Self-Review

**1. Spec coverage** (spec 섹션 → Task 매핑):
- §2 주석 계층 구조: 계층4(T1)·계층3(T2)·계층2(T3)·계층1(T4) 전부 커버, 35개 = 11+13+8+3. ✓
- §3 제거/변환/유지: Global Constraints + 각 Task의 before→after에 제거 대상·유지 대상 명시. 보안 근거 유지를 각 Task에 가드. ✓
- §4 README 8섹션: T5 Step 2가 8섹션 전부 작성. ✓
- §5 검증: compileJava(각 Task) + clean test(T5 Step5) + grep 0건(T5 Step4) + README 대조(T5 Step3). ✓
- §6 작업 순서: 계층4→3→2→1→README, Task 순서 일치. ✓

**2. Placeholder scan:** "TBD"/"TODO"/"적절히" 없음. 모든 주석 교체에 실제 before 위치 + after 문구 명시. T5 Step1·Step3은 의도된 "실제 값 확인" 검증 스텝(README 정확성 근거)이며 placeholder 아님. README 본문은 완전 작성(§6 표는 실제 기본값으로 채워짐). ✓

**3. Type consistency:** 코드 변경이 없으므로 시그니처/타입 일관성 이슈 없음. 클래스명·메서드명 참조(WebAuthnController.loginComplete, RegRelayCodec, InMemoryUserStore 메서드 5종)는 실제 코드와 일치 확인. ✓

**4. 동작 불변 가드:** 각 Task에 "코드 라인 변경 0(주석 줄만)" 확인 스텝 포함. CorsProperties/RelayProperties의 검증 로직·예외 메시지, WebAuthnController의 iss/aud 검증식, RegRelayCodec HMAC 비교는 "유지"로 명시. ✓
