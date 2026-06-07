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

## 2. 자격증명·PII 는 `LogRedact` 통과

소스에서 raw 값 출력 금지. 반드시 헬퍼:

```java
log.info("admin invite issued: email={} tenantId={}",
         LogRedact.email(req.email()), req.tenantId());
```

대상 필드: email, API key full, JWT, password, bcrypt hash, raw bytes.

`SecretMaskingConverter` 가 출력 시점에서도 한 번 더 차단하지만,
그건 마지막 방어선이고 컨벤션 위반.

`LogRedact` API:
| 헬퍼 | 입력 | 출력 |
| --- | --- | --- |
| `LogRedact.email(s)` | `"alice@x.com"` | `"a***@x.com"` |
| `LogRedact.apiKey(s)` | `"pk_devacme0longsecret"` | `"pk_devacme0"` |
| `LogRedact.token(s)` | `"eyJhbGciOiJSUzI1NiJ9.x.y"` | `"eyJhb…<redacted>"` |
| `LogRedact.idTail(s, 12)` | `"…HIJKLMNOPQRS"` | `"HIJKLMNOPQRS"` |

null-safe — null 입력 시 helper 마다 안전한 sentinel 반환 (`""` 또는 `"<redacted>"`).

## 3. Exception 은 ERROR + throwable

```java
log.error("mds sync failed: cause={}", e.toString(), e);  // 두 번째 e 가 stack trace 포함
```

`e.printStackTrace()` 같은 콘솔 직접 호출 금지.

## 4. 새 MDC 키 → 운영 가이드 갱신

현재 표준 키: `traceId`, `tenantId`, `actorEmail`, `apiKeyPrefix`,
`userHandle` (DEBUG only).

새 MDC 키 추가 시:
1. 어떤 Filter/Handler 가 set/clear 하는지 명시.
2. `core/src/main/resources/logback-spring.xml` +
   `rp-app/src/main/resources/logback-spring.xml` 의 pattern 에 추가.
3. `docs/logging-operations.md` 검색 cookbook 갱신.

## 5. DEBUG 는 dev 전용

prod 의 root 가 INFO. DEBUG 라인이 prod 에 나오면 안 됨.

- request body / response body 출력은 DEBUG.
- 외부 호출 성공 detail 은 DEBUG (실패는 WARN/ERROR).
- `if (log.isDebugEnabled()) { … }` guard 로 expensive `toString` 회피.

## 6. 새 비즈니스 이벤트 → INFO

`audit_log` 와 별도 — log 는 ops 의 실시간 가시성용. audit 는 영구 진실,
log 는 ELK 7d retention 가정.

새 admin action / scheduler tick / external call 결과 → INFO 한 줄.
새 보안 거부 / 정책 위반 → WARN.
새 시스템 오류 + 예외 → ERROR + throwable.
