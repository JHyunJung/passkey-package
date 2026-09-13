# Logging Conventions

> 새 코드를 작성할 때 따라야 할 6가지 룰.

## 1. logfmt 메시지

`log.info("event: key1={} key2={}", v1, v2)` — kebab-case event name + 콜론,
이후 `key=val` 쌍. parameterized (`{}`) 플레이스홀더 사용.

좋음:
```java
log.info("registration/start: externalUserId={} displayName={}", userId, name);
```

나쁨:
```java
log.info("User " + userId + " started registration");  // 문자열 concat, key 없음
log.info("hi {}", userId);                              // event name 없음
```

## 2. 자격증명·PII 는 마스킹해서 출력

소스에서 raw 값 출력 금지. 반드시 마스킹 유틸을 거친다:

```java
log.info("signup request approved: email={} role={}", CryptoUtils.maskEmail(email), role);
```

대상 필드: email, API key full, JWT, password, bcrypt hash, raw bytes.

`SecretMaskingConverter` 가 출력 시점에서도 한 번 더 차단하지만,
그건 마지막 방어선이고 컨벤션 위반.

**email 의 정본 헬퍼는 `CryptoUtils.maskEmail`** 이다(`core/…/util/CryptoUtils.java`).
실제 호출처: `AdminSecurityConfig`, `TenantBoundary`, `SignupRequestService`,
`PasswordResetService`.

| 헬퍼 | 입력 | 출력 |
| --- | --- | --- |
| `CryptoUtils.maskEmail(s)` | `"alice@x.com"` | `"a***@x.com"` |
| `CryptoUtils.maskEmail(null)` 또는 blank | — | `"(unknown)"` |
| `CryptoUtils.maskEmail("noat")` (`@` 없음) | `"noat"` | `"***"` |

email 외의 값(API key / JWT / 식별자 꼬리)은 전용 헬퍼를 쓰지 않고 호출부에서
직접 자르되(`prefix` 11자, 꼬리 n자 등), 새어 나가면 `SecretMaskingConverter`
정규식이 잡도록 되어 있다.

> `core/…/logging/LogRedact` 클래스가 남아 있지만 **main 코드에서 호출하는 곳이 0건**이다
> (`SecretMaskingConverter` Javadoc 이 "개발자가 직접 호출" 로 가리키고 테스트가 참조할 뿐).
> 새 코드에서 표준으로 삼지 말 것 — 클래스와 그 Javadoc 정리는 `docs/backlog.md` §4 에 있다.

## 3. Exception 은 ERROR + throwable

```java
log.error("mds sync failed: cause={}", e.toString(), e);  // 두 번째 e 가 stack trace 포함
```

`e.printStackTrace()` 같은 콘솔 직접 호출 금지.

## 4. 새 MDC 키 → 운영 가이드 갱신

현재 표준 키는 **4종**이다: `traceId`(`TraceIdFilter`), `tenantId`·`apiKeyPrefix`
(passkey-app `ApiKeyAuthFilter`), `actorEmail`(`RequestLoggingFilter`).
rp-app 은 이 중 `traceId`·`actorEmail` 둘만 넣는다.

> MDC 값은 마스킹 대상이 아니다 — `SecretMaskingConverter` 는 `%msg` 에만 걸린다.
> `actorEmail` 은 MDC 칸에 평문으로 남으므로, PII 를 새 MDC 키로 넣을 때는 값을 넣기 전에 마스킹해야 한다.

새 MDC 키 추가 시:
1. 어떤 Filter/Handler 가 set/clear 하는지 명시(넣었으면 `finally` 에서 `MDC.remove`).
2. 텍스트 로그: pattern 이 아니라 **`CompactMdcConverter.KEYS` 배열**에 추가한다
   (`%compactMdc` 가 이 배열 순서대로 값 있는 키만 출력·16자 절단).
   `core` 와 `rp-app` 에 **트윈 클래스가 각각 있으므로 둘 다** 고친다(rp-app 은 :core 비의존).
3. JSON 로그: 두 `logback-spring.xml` 의 prod `CONSOLE_JSON` 인코더에
   `<includeMdcKeyName>` 을 추가한다(없으면 JSON 에 안 실린다).
4. `docs/logging-operations.md` §1 포맷 설명과 §2 검색 cookbook 갱신.

## 5. DEBUG 는 dev 전용

prod 는 root 가 **WARN**, `com.crosscert.passkey` 가 INFO 다. DEBUG 라인이 prod 에 나오면 안 됨.

- request body / response body 출력은 DEBUG.
- 외부 호출 성공 detail 은 DEBUG (실패는 WARN/ERROR).
- `if (log.isDebugEnabled()) { … }` guard 로 expensive `toString` 회피.

## 6. 새 비즈니스 이벤트 → INFO

`audit_log` 와 별도 — log 는 ops 의 실시간 가시성용. audit 는 영구 진실이고,
log 는 수집 스택의 retention 정책만큼만 남는다(저장소에 정해진 값은 없다 —
운영 환경의 ELK/Splunk 설정을 따른다). 오래 남아야 하는 사실은 `audit_log` 에 쓴다.

새 admin action / scheduler tick / external call 결과 → INFO 한 줄.
새 보안 거부 / 정책 위반 → WARN.
새 시스템 오류 + 예외 → ERROR + throwable.
