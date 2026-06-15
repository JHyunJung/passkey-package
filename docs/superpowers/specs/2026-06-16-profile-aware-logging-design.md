# 프로필별 로깅 차별화 + 가독성 개선 — 설계

- 작성일: 2026-06-16
- 대상 모듈: `core`, `rp-app`, `passkey-app`, `admin-app` (전 앱 일관)
- 상태: 승인됨 (구현 대기)

## 1. 문제 진단

콘솔 로그 가독성이 나쁘고 프로필별 차별화가 없다. (스크린샷 근거)

확정된 원인:

1. **`[] [] [] []` MDC 노이즈** — `logback-spring.xml` 패턴이
   `[%X{traceId:-}] [%X{tenantId:-}] [%X{actorEmail:-}] [%X{apiKeyPrefix:-}]` 로 4개 MDC
   슬롯을 항상 출력한다. rp-app 은 traceId 만 채워지고 나머지 3개는 거의 항상 비어 `[]` 로
   찍히며, 부팅 로그는 4개 모두 빈 `[] [] [] []` 가 된다.
2. **프로필 차별화 부재** — `core/logback-spring.xml`, `rp-app/logback-spring.xml` 둘 다
   `<springProfile>` 분기가 없어 local/dev/qa/prod 가 동일한 패턴·레벨로 출력된다.
   rp-app 은 프로필 yml 도 `application.yml` + `application-local.yml` 뿐이라 dev/qa/prod 가
   없다(passkey-app 은 dev/qa/prod yml 보유).
3. **request/response 디버깅 가시성 부재** — local/dev 에서 본문을 안전하게 볼 수단이 없다.
4. **스크린샷의 "Request Body log = {거대 base64 JSON}" 은 현재 코드에 이미 없다**
   (`WebAuthnController` 의 현재 로그는 전부 `idTail()` 마스킹 요약형). 스크린샷은 과거
   버전이며, 이번 작업은 그 raw-body 노이즈의 **재발 방지**(전용 로거+게이트+길이캡)를 포함한다.

기존 자산:
- `SecretMaskingConverter` 가 core(Java) / rp-app(Kotlin) 양쪽에 중복 존재(rp-app 은 core
  비의존 별도 webapp). `conversionRule conversionWord="msg"` 로 등록돼 모든 `%msg` 가 마스킹된다.
- `TraceIdFilter`(core / rp-app)가 `traceId` MDC 를 채우고, SDK 호출 시 `X-Trace-Id` 전파로
  rp-app↔passkey-app 로그가 한 trace 를 공유한다.

## 2. 목표 / 비목표

목표:
- 빈 MDC 슬롯 노이즈 제거 — 값 있는 키만 출력.
- 프로필별 로깅 차별화: 로그 레벨 / 패턴 포맷 / 스택트레이스 깊이 / (opt-in) JSON 구조 로그.
- local/dev 에서 request/response 본문 디버깅 가능, QA/Prod 는 자동 미출력.
- core 를 표준으로 전 앱(core·rp-app·passkey-app·admin-app) 일관 정책.

비목표:
- 비즈니스 로직/MDC 생성 지점 변경 (필터는 그대로, 출력만 정제).
- 실제 외부 로그 수집(ELK/Loki) 연동 (JSON encoder 는 추가하되 opt-in 플래그로만).
- 로그 파싱 스크립트 작성 (형식 변경 사실만 메모).

## 3. 아키텍처

core 를 표준으로, 5개 구성요소.

### 3.1 CompactMdcConverter (신규)
`ch.qos.logback.classic.pattern.ClassicConverter` 를 상속한 커스텀 컨버터. MDC 맵에서 값이
있는 키만 `[traceId=fae2.. tenant=7f00.. actor=a***@x.com]` 형태로 묶어 출력하고, 빈 키는
생략하며, MDC 가 전부 비면 빈 문자열(앞뒤 공백 없이)을 반환한다.

- 출력 키 목록은 고정 순서: `traceId, tenantId, actorEmail, apiKeyPrefix` (있는 것만).
- 값 길이 캡: 키별 과도하게 긴 값은 잘라 노이즈 방지(예: 16자).
- core(Java) `com.crosscert.passkey.core.logging.CompactMdcConverter`,
  rp-app(Kotlin) `com.crosscert.passkey.rpapp.web.CompactMdcConverter` 양쪽에 둔다
  (SecretMaskingConverter 중복 패턴과 동일 — rp-app 은 core 비의존).
- logback 에 `<conversionRule conversionWord="compactMdc" converterClass="..."/>` 로 등록,
  패턴에서 기존 `[%X{..}] [%X{..}] ...` 4개를 `%compactMdc` 하나로 대체.

### 3.2 프로필별 logback `<springProfile>` 분기 (core + rp-app)
두 logback-spring.xml 에 프로필별 appender/패턴을 둔다. `%clr`(컬러)이 동작하려면
`<include resource="org/springframework/boot/logging/logback/defaults.xml"/>` 가 필수다(구현 중
확인 — 없으면 Spring Boot 가 `IllegalStateException: Logback configuration error` 로 부팅 실패).
`conversionRule` 은 deprecated `converterClass=` 대신 `class=` 속성을 쓴다. prod 의 logback
`<root>` 레벨은 `application-prod.yml` 의 `root: WARN` 과 맞춘다(둘 다 WARN; codex P2 — 불일치
시 혼란).

- `local,dev`:
  - 패턴: 컬러(`%clr`) + 가독성 우선. `%d{HH:mm:ss.SSS} %clr(%-5level) %compactMdc %clr(%logger{36}){cyan} - %msg%n`
  - 스택: 풀 스택(`%ex`).
  - 레벨: 앱 패키지 DEBUG, payload 로거 DEBUG(3.3).
- `qa`:
  - 패턴: 한 줄 구조화 `%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %compactMdc %logger{36} - %msg%n`
  - 스택: 축약(`%rEx{8}` — 상위 8프레임).
  - 레벨: 앱 INFO.
- `prod`:
  - 기본: qa 와 동일 텍스트 패턴, 스택 최소(`%rEx{3}`).
  - `PASSKEY_LOG_JSON=true` 면 JSON appender(3.5) 사용.
  - 레벨: 앱 INFO, framework WARN.
- 프로필 미지정(테스트 등) 폴백: 현재와 호환되는 기본 appender 1개 유지.

### 3.3 request/response payload 로거 (신규)
전용 로거명 `com.crosscert.passkey.payload` 로 본문을 **DEBUG** 레벨에 기록한다.

- rp-app: `RequestLoggingFilter` 를 확장하거나 인접한 payload 로깅 지점에서, content-caching
  래퍼로 캡처한 req/resp 본문을 `log.debug("req body: {}", masked)` / `resp body` 로 남긴다.
  (현재 `RequestLoggingFilter` 는 요약 한 줄만 — 본문 캡처를 위해 `ContentCachingRequest/
  ResponseWrapper` 도입.)
- 게이트: 이 로거는 local/dev logback 에서만 DEBUG 로 활성. qa/prod 는 INFO 기준선이라
  코드가 호출돼도 출력 안 됨(레벨 게이트).
- 안전장치(다중): (a) 레벨 게이트(local/dev 만), (b) `%msg` SecretMaskingConverter 마스킹 전 구간
  적용, (c) 본문 길이 캡(2KB truncate) — WebAuthn 거대 base64 본문 폭주 방지, (d) JSON 토큰 필드
  값 마스킹(codex 최종 P2) — req/resp 본문의 `authenticationToken`/`registrationToken`/`regRelayToken`
  은 opaque/one-dot 형식이라 기존 JWS 패턴에 안 걸리므로, `SecretRedactor` 에 JSON 필드 패턴
  (`"key":"…"` + 이스케이프된 `\"key\":\"…\"` 모두)을 추가해 값만 `<redacted>`, 필드명은 보존.

### 3.4 프로필별 logging.level (각 앱 application-{profile}.yml)
- rp-app: `application-dev.yml`, `application-qa.yml`, `application-prod.yml` 신설
  (현재 `application.yml`+`application-local.yml` 만). level 차등 + payload 로거 레벨.
- passkey-app: 기존 dev/qa/prod yml 의 `logging.level` 보강(payload 로거 포함).
- admin-app: core logback 공유분 자동 수혜 + 필요한 level 만 조정.
- logback `<springProfile>` 과 yml `logging.level` 의 역할 분리: **패턴/스택/appender 는
  logback**, **로거별 레벨은 yml**(운영 중 조정 용이). 충돌 없이 상보적.

### 3.5 JSON 구조 로그 (opt-in)
- `logstash-logback-encoder` 의존성 추가(core build, rp-app build). logback `<if>` 가 Janino 를
  요구하므로 `runtimeOnly("org.codehaus.janino:janino")` 도 추가(구현 중 확인 — transitive 부재).
- prod logback 에 JSON appender(`CONSOLE_JSON`) + 텍스트 appender(`CONSOLE_TEXT`) 정의, root 가
  `<springProperty name="logJson" source="passkey.log.json"/>` + `<if condition='property("logJson").equals("true")'>`
  로 분기. `PASSKEY_LOG_JSON=true` 면 JSON, 아니면 텍스트. 기본값 false.
  (logback 1.5.x 가 `<if condition=>` 속성을 deprecated 로 경고하나, `<condition class>` 대안이
  `<springProfile>` 2차 처리 컨텍스트에서 root appender 배선에 실패해 deprecated 형태가 유일한 동작 방식.)

> **구현 노트 — JSON 마스킹(보안 필수, codex P1):** `LogstashEncoder` 는 message 를 직접
> 직렬화해 텍스트 모드의 `%msg`(SecretMaskingConverter) 마스킹을 **우회**한다. 그대로 두면 JSON
> 모드에서 비밀값이 평문 노출된다. 해결: 마스킹 정규식을 `SecretRedactor.redact(String)` 정적
> 헬퍼로 추출(텍스트 컨버터와 JSON 둘 다 공유 → drift 방지)하고, JSON 은 `<message>[ignore]</message>`
> 로 기본 message provider 를 끄고 `RedactingMessageJsonProvider`(AbstractFieldJsonProvider)가
> `SecretRedactor.redact(formattedMessage)` 를 message 필드로 쓴다. 스택은 `ShortenedThrowableConverter
> maxDepthPerThrowable=3`(codex P2) 으로 텍스트 `%rEx{3}` 과 맞춘다.

## 4. 데이터 흐름

```
요청 → TraceIdFilter(MDC traceId) → 보안필터(MDC tenantId/actorEmail/apiKeyPrefix, 있으면)
  → 컨트롤러/서비스 log.info("요약", idTail(...))       (마스킹 요약 유지)
  → [DEBUG] payload 로거 req/resp 본문(마스킹+길이캡; local/dev 만 레벨 통과)
  → logback 패턴: %d %-5level %compactMdc %logger - %msg%n
       · CompactMdcConverter → 값 있는 MDC만 [k=v ..], 없으면 ""
       · SecretMaskingConverter(%msg) → 비밀값 마스킹
  → 프로필 appender: local/dev=컬러텍스트+풀스택 | qa=구조화+축약스택 | prod=최소 or JSON(env)
```

MDC 생성(필터)은 불변. 컨버터는 출력만 정제. payload 는 별도 로거+레벨로 게이팅. 두 컨버터
(compactMdc, msg 마스킹)는 독립.

## 5. 에러 처리 / 스택트레이스 깊이

- `GlobalExceptionHandler` 의 `log.error(..., e)` 코드는 그대로. 스택 렌더링을 logback
  프로필별로 제어: local/dev `%ex`(풀), qa `%rEx{8}`, prod `%rEx{3}`.
- 비즈니스 예외는 이미 `log.warn(msg)` 요약형(스택 미출력) — 유지.
- logback 은 fail-safe: 패턴/컨버터가 깨져도 원본 메시지는 출력되어 로깅 자체는 죽지 않는다.

## 6. 보안

- request/response 본문 디버깅의 삼중 방어(3.3): 레벨 게이트 + 마스킹 + 길이 캡.
- WebAuthn 본문(clientDataJSON/attestationObject)은 공개 ceremony 데이터라 비밀 아님. 단
  거대 출력 방지를 위해 길이 캡. 토큰/키 류는 SecretMaskingConverter 가 마스킹.
- QA/Prod 에서 payload 로거가 절대 출력되지 않음을 테스트로 고정(3.3 게이트 회귀 방지).

## 7. 테스트

1. `CompactMdcConverterTest` (core/rp-app 각 단위): MDC 0개→"", 일부만→해당 키만,
   전부→전체+순서, 과긴 값→캡. 핵심 회귀 방지.
2. logback 프로필 로딩 스모크: `local`/`qa`/`prod` 로 LoggerContext 가 에러 없이 구성되는지
   (xml 문법 + conversionRule 등록 검증).
3. payload 게이트 테스트: `ListAppender` 로, payload 로거가 INFO 에선 미출력 / DEBUG 에서만
   출력. 마스킹·길이캡 적용 확인.
4. 마스킹 회귀: 기존 SecretMaskingConverter 테스트 유지/확장.
5. JSON appender: 로딩 스모크만(실제 ELK 연동은 범위 밖).

## 8. 영향 범위 / 리스크

- 변경/신규 파일:
  - `core/src/main/resources/logback-spring.xml` (프로필 분기 + compactMdc + JSON appender)
  - `rp-app/src/main/resources/logback-spring.xml` (동기화)
  - `core/.../logging/CompactMdcConverter.java` (신규), `rp-app/.../web/CompactMdcConverter.kt` (신규)
  - rp-app payload 로깅: `RequestLoggingFilter` 확장 + ContentCaching 래퍼 (core 필터도 동기화 검토)
  - `rp-app/.../application-dev.yml`,`-qa.yml`,`-prod.yml` (신설), passkey-app/admin-app yml level 보강
  - `core/build.gradle.kts`, `rp-app/build.gradle.kts` (logstash-logback-encoder)
  - 신규 테스트(1~5)
- 리스크 낮음: 출력 형식만 변경, 비즈니스 로직·MDC 생성 불변. traceId 상관관계(rp↔passkey) 유지.
- 부수 효과: 로그 텍스트 형식 변경 → 외부 로그 파싱 스크립트(있다면) 갱신 필요. 메모로 남김.
- 중복 유지비: CompactMdcConverter 가 SecretMaskingConverter 처럼 core/rp-app 양쪽 중복.
  rp-app 의 core 비의존 원칙상 의도적 — 두 파일 동작을 동일하게 유지(테스트로 고정).
