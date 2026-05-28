# Phase G4 — Redaction Hardening + Operations Guide Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** G1-G3 의 인프라/이벤트/보안 로그 위에 **redaction 가드레일** + **운영 가이드 문서** 추가. defense-in-depth + 운영 cookbook + 컨벤션 명문화.

**Architecture:** 신규 파일 6개 (LogRedact, SecretLeakFilter, logback 등록, 운영 문서 × 2, IT). 의존성 0.

**Spec reference:** `docs/superpowers/specs/2026-05-29-logging-design.md` § Phase G4.

---

## Execution policy

1. Tests minimal — IT 1개 (SecretLeakFilter)
2. Per-task codex review
3. Autonomous decisions

---

## File Structure

```
core/src/main/java/com/crosscert/passkey/core/logging/LogRedact.java                # new
core/src/main/java/com/crosscert/passkey/core/logging/SecretLeakFilter.java         # new (logback Filter)
core/src/main/resources/logback-spring.xml                                          # modify (filter 등록)
sample-rp/src/main/resources/logback-spring.xml                                     # modify (filter 등록 — 동일 클래스 복사)
sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/SecretLeakFilter.java   # new (copy of core)
core/src/test/java/com/crosscert/passkey/core/logging/SecretLeakFilterTest.java     # new IT
docs/logging-operations.md                                                          # new
docs/logging-conventions.md                                                         # new
```

---

## Working directory

`.claude/worktrees/logging-g4/` on `worktree-logging-g4` (forked from main at G3 merge `5d993e0`).

---

## Task 1: LogRedact helper

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/logging/LogRedact.java`

- [ ] **Step 1: Write helper**

```java
package com.crosscert.passkey.core.logging;

/**
 * Standardized redaction helpers for log output. Use these in all
 * log.info/warn/error calls when emitting user-supplied or secret-adjacent
 * values. Direct raw value emission is forbidden by spec § 7.
 */
public final class LogRedact {

    private LogRedact() {}

    /**
     * API key header value → first 11 chars only (the "pk_xxxxxxxx" prefix).
     * Anything shorter → "<redacted>".
     */
    public static String apiKey(String raw) {
        if (raw == null || raw.length() < 11) return "<redacted>";
        return raw.substring(0, 11);
    }

    /**
     * Email → first char + "***" + domain. "alice@x.com" → "a***@x.com".
     * No '@' or empty local → "<redacted>".
     */
    public static String email(String raw) {
        if (raw == null) return "";
        int at = raw.indexOf('@');
        if (at < 1) return "<redacted>";
        return raw.charAt(0) + "***" + raw.substring(at);
    }

    /**
     * Returns the last n chars of raw, or "***" if raw is too short to
     * usefully truncate (≤ n chars).
     */
    public static String idTail(String raw, int n) {
        if (raw == null) return "";
        if (raw.length() <= n) return "***";
        return raw.substring(raw.length() - n);
    }

    /**
     * JWT or long opaque token → first 5 chars + redact marker.
     * Anything shorter than 8 chars → "<redacted>".
     */
    public static String token(String raw) {
        if (raw == null || raw.length() < 8) return "<redacted>";
        return raw.substring(0, 5) + "…<redacted>";
    }
}
```

- [ ] **Step 2: Compile + Codex + commit**

```bash
./gradlew :core:compileJava
git add core/src/main/java/com/crosscert/passkey/core/logging/LogRedact.java
```

Codex prompt: "codex review LogRedact helper. Verify: (1) 4 static methods (apiKey/email/idTail/token), (2) null-safe (no NPE), (3) too-short input → '<redacted>' or '***', (4) no raw secret leakage in any return value, (5) idTail returns '***' (not original) when raw ≤ n chars to avoid trivial leak. Must-fix only."

```bash
git commit -m "feat(core): LogRedact helper for standardized redaction (G4.1)"
```

---

## Task 2: SecretLeakFilter (logback TurboFilter)

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/logging/SecretLeakFilter.java`

logback `TurboFilter` 보다는 `Filter<ILoggingEvent>` 가 더 간단 — 실제로는 message 변환이 필요한데 Filter API 로는 mutation 어려움. 대신 **`MessageConvertor` (PatternLayout 의 converter)** 또는 **`MaskingPatternLayout`** 패턴 사용.

가장 간단한 방안: **custom `PatternLayout` 의 `MessageConverter`** 를 override 해서 msg 변환.

- [ ] **Step 1: Write `SecretMaskingConverter`**

```java
package com.crosscert.passkey.core.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Pattern-based defense-in-depth redaction applied to every formatted log
 * message. If a developer accidentally emits a raw API key, JWT, or password
 * in log.info, this converter strips the secret tail at output time.
 *
 * Used as a custom MessageConverter in logback-spring.xml:
 *   <conversionRule conversionWord="msg" converterClass="...SecretMaskingConverter"/>
 * which overrides the default %msg behavior across all CONSOLE output.
 */
public class SecretMaskingConverter extends MessageConverter {

    // pk_ + 8 base64url + tail → keep prefix, strip secret
    private static final Pattern API_KEY = Pattern.compile("pk_[A-Za-z0-9_-]{8}[A-Za-z0-9_-]+");
    // Bearer eyJ... → keep "Bearer eyJ…", strip rest
    private static final Pattern JWT_BEARER = Pattern.compile("(?i)(Bearer\\s+)eyJ[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    // X-API-Key: pk_… header form
    private static final Pattern API_KEY_HEADER = Pattern.compile("(?i)(X-API-Key:\\s*)pk_[A-Za-z0-9_-]+");
    // password="xxx" or password=xxx
    private static final Pattern PASSWORD_KV = Pattern.compile("(?i)(password)\\s*=\\s*\"?[^\\s\"\\]]+\"?");
    // bcrypt hash $2a$... (62 chars total)
    private static final Pattern BCRYPT = Pattern.compile("\\$2[ayb]\\$\\d{2}\\$[A-Za-z0-9./]{53}");

    @Override
    public String convert(ILoggingEvent event) {
        String msg = super.convert(event);
        if (msg == null || msg.isEmpty()) return msg;

        // Apply patterns in order
        msg = API_KEY_HEADER.matcher(msg).replaceAll("$1<redacted>");
        msg = JWT_BEARER.matcher(msg).replaceAll("$1<redacted>");
        msg = API_KEY.matcher(msg).replaceAll(m -> {
            String full = m.group();
            return full.substring(0, 11) + "<redacted>";  // pk_ + 8 + <redacted>
        });
        msg = PASSWORD_KV.matcher(msg).replaceAll("$1=<redacted>");
        msg = BCRYPT.matcher(msg).replaceAll("<bcrypt-redacted>");

        return msg;
    }
}
```

- [ ] **Step 2: Register in logback-spring.xml (core)**

`core/src/main/resources/logback-spring.xml` 수정:

```xml
<configuration>
  <conversionRule conversionWord="msg"
                  converterClass="com.crosscert.passkey.core.logging.SecretMaskingConverter"/>

  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level [%X{traceId:-}] [%X{tenantId:-}] [%X{actorEmail:-}] [%X{apiKeyPrefix:-}] %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>
  ...
</configuration>
```

`%msg` 가 자동으로 SecretMaskingConverter 를 거치게 됨.

- [ ] **Step 3: Same for sample-rp**

```bash
cp core/src/main/java/com/crosscert/passkey/core/logging/SecretMaskingConverter.java \
   sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/SecretMaskingConverter.java
```

패키지 선언만 바꾸기. `sample-rp/src/main/resources/logback-spring.xml` 에도 동일 conversionRule 등록.

- [ ] **Step 4: Compile + Codex + commit**

```bash
./gradlew :core:compileJava :sample-rp:compileJava
git add core/src/main/java/com/crosscert/passkey/core/logging/SecretMaskingConverter.java \
        core/src/main/resources/logback-spring.xml \
        sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/SecretMaskingConverter.java \
        sample-rp/src/main/resources/logback-spring.xml
```

Codex prompt: "codex review SecretMaskingConverter. Verify: (1) 5 patterns (api-key header / Bearer JWT / pk_ standalone / password kv / bcrypt) correctly capture, (2) replacement preserves identifier prefix (pk_ + 8 chars) and JWT marker (Bearer eyJ…), (3) regex doesn't catastrophic-backtrack on edge cases, (4) logback registration via conversionRule applies to all %msg output, (5) sample-rp copy 동일 패키지명만 다름. Must-fix only."

```bash
git commit -m "feat(core/sample-rp): SecretMaskingConverter defense-in-depth (G4.2)"
```

---

## Task 3: SecretLeakFilterTest IT

**Files:**
- Create: `core/src/test/java/com/crosscert/passkey/core/logging/SecretMaskingConverterTest.java`

- [ ] **Step 1: Write test**

```java
package com.crosscert.passkey.core.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class SecretMaskingConverterTest {

    private Logger logger;
    private ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender;

    @BeforeEach
    void setup() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ctx.putObject("CONVERSION_RULE_REGISTRY", new java.util.HashMap<String, String>() {{
            put("msg", SecretMaskingConverter.class.getName());
        }});
        appender = new ListAppender<>();
        appender.setContext(ctx);
        appender.start();
        logger = (Logger) LoggerFactory.getLogger(SecretMaskingConverterTest.class);
        logger.addAppender(appender);
    }

    @AfterEach
    void cleanup() {
        logger.detachAndStopAllAppenders();
    }

    @Test
    void redactApiKeyInHeader() {
        SecretMaskingConverter conv = new SecretMaskingConverter();
        LoggingEvent evt = makeEvent("attempting X-API-Key: pk_devacme0longsecretvalue");
        String out = conv.convert(evt);
        assertThat(out).doesNotContain("longsecretvalue");
        assertThat(out).contains("<redacted>");
    }

    @Test
    void redactStandalonePkPrefix() {
        SecretMaskingConverter conv = new SecretMaskingConverter();
        LoggingEvent evt = makeEvent("token=pk_devacme0moresecret");
        String out = conv.convert(evt);
        assertThat(out).contains("pk_devacme0<redacted>");
    }

    @Test
    void redactBearerJwt() {
        SecretMaskingConverter conv = new SecretMaskingConverter();
        LoggingEvent evt = makeEvent("auth: Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0In0.AbCDeFg");
        String out = conv.convert(evt);
        assertThat(out).contains("Bearer <redacted>");
        assertThat(out).doesNotContain("AbCDeFg");
    }

    @Test
    void redactPassword() {
        SecretMaskingConverter conv = new SecretMaskingConverter();
        LoggingEvent evt = makeEvent("login: password=hunter2 user=alice");
        String out = conv.convert(evt);
        assertThat(out).contains("password=<redacted>");
        assertThat(out).doesNotContain("hunter2");
    }

    @Test
    void redactBcryptHash() {
        SecretMaskingConverter conv = new SecretMaskingConverter();
        LoggingEvent evt = makeEvent("stored=$2a$12$gvD5tGra6vKnSn/9cxqfQOKZOzlzp4LCg276Ddfkpwl8Kk24Zbb1G");
        String out = conv.convert(evt);
        assertThat(out).contains("<bcrypt-redacted>");
    }

    @Test
    void logRedactHelpers() {
        assertThat(LogRedact.email("alice@crosscert.com")).isEqualTo("a***@crosscert.com");
        assertThat(LogRedact.apiKey("pk_devacme0longsecret")).isEqualTo("pk_devacme0");
        assertThat(LogRedact.token("eyJhbGciOiJSUzI1NiJ9.x.y")).isEqualTo("eyJha…<redacted>");
        assertThat(LogRedact.idTail("ABCDEFGHIJKLMNO", 8)).isEqualTo("HIJKLMNO");
        assertThat(LogRedact.idTail("short", 8)).isEqualTo("***");
        assertThat(LogRedact.email(null)).isEqualTo("");
        assertThat(LogRedact.apiKey(null)).isEqualTo("<redacted>");
    }

    private LoggingEvent makeEvent(String message) {
        LoggingEvent e = new LoggingEvent();
        e.setMessage(message);
        e.setLoggerName("test");
        e.setLevel(ch.qos.logback.classic.Level.INFO);
        return e;
    }
}
```

- [ ] **Step 2: Run**

```bash
./gradlew :core:test --tests SecretMaskingConverterTest
```

- [ ] **Step 3: Codex + commit**

```bash
git add core/src/test/java/com/crosscert/passkey/core/logging/SecretMaskingConverterTest.java
```

Codex prompt: "codex review SecretMaskingConverterTest. Verify: (1) 5 redaction patterns each tested with positive (redacted) + check that raw secret tail absent, (2) LogRedact helpers covered (email/apiKey/token/idTail/null cases), (3) ListAppender pattern or direct converter call valid, (4) no test relies on full logback pipeline (use direct converter for simplicity). Must-fix only."

If test fails, simplify to direct converter call (drop ListAppender plumbing) — autonomous decision.

```bash
git commit -m "test(core): SecretMaskingConverterTest + LogRedact (G4.6)"
```

---

## Task 4: docs/logging-operations.md

**Files:**
- Create: `docs/logging-operations.md`

- [ ] **Step 1: Write document**

```markdown
# Logging Operations Guide

> 3 서버 (admin-app / passkey-app / sample-rp) 의 로그 검색·alert·troubleshooting 가이드.
> 인프라: G1-G4 의 logback-spring.xml + MDC 4종 + SecretMaskingConverter.

## 1. 로그 포맷

```
HH:mm:ss.SSS LEVEL [traceId] [tenantId] [actorEmail] [apiKeyPrefix] logger - msg
```

각 `[…]` 칸:
- **traceId** — 한 HTTP 요청의 식별자. cross-server 추적 가능 (X-Trace-Id 헤더로 propagate).
- **tenantId** — passkey-app 의 ApiKeyAuthFilter 가 인증 성공 시 set (UUID).
- **actorEmail** — admin-app 의 로그인 사용자 email.
- **apiKeyPrefix** — passkey-app 의 X-API-Key 헤더 앞 11자 (`pk_xxxxxxxx`).
- 빈 칸은 `[]` 그대로 — 그 컨텍스트에서는 의미 없는 정보.

`msg` 는 logfmt 형식: `event: key=val key=val`.

## 2. 검색 cookbook

### 한 사용자 트랜잭션 추적
```bash
grep '9f3a-2b1' *.log
# 또는 ELK
traceId: "9f3a-2b1"
```
sample-rp 의 register/options → passkey-app 의 registration/start → … 가 한 trace 로 정렬됨.

### tenant 한정 이벤트
```bash
grep '\[7f00dead-' *.log
traceId AND tenantId: "7f00dead-…"
```

### 관리자 활동
```bash
grep '\[alice@crosscert.com\]' *.log
actorEmail: "alice@crosscert.com"
```

### 특정 API key 사용 추적
```bash
grep '\[pk_devacme0\]' *.log
apiKeyPrefix: "pk_devacme0"
```

### 보안 경보 패턴
```bash
grep -E 'WARN.*(api-key auth failed|signature counter regression|tenant boundary violation|invitation (expired|used))' *.log
```

## 3. 레벨 의미

| Level | 사용 시점 | 예 |
| --- | --- | --- |
| `INFO` | 정상 비즈니스 이벤트 | ceremony start/finish, admin write, scheduler tick |
| `WARN` | 정상 흐름이지만 주의 필요 | auth 실패, signature regression, MDS sync skipped |
| `ERROR` | 시스템 오류 + 예외 | MDS sync failed, JPA exception (stack trace 동반) |
| `DEBUG` | dev 디버깅 전용 | request body 크기, JWKS 호출, key kid 발급 |

prod 에서 root 가 INFO 라 DEBUG 는 출력 안 됨.

## 4. Redaction 약속

다음 값은 어떤 로그에도 **절대 노출되지 않음**:

| 자격증명/PII | 처리 방법 |
| --- | --- |
| API key secret (prefix 이후) | `pk_devacme0` 11자만 노출 |
| JWT body | `Bearer <redacted>` 또는 `eyJh…<redacted>` |
| Password | `password=<redacted>` |
| Bcrypt hash | `<bcrypt-redacted>` |
| Raw attestation/assertion bytes | 메시지에 포함 안 함 (claim 메타만) |
| Email (사용자 facing) | `a***@domain` |
| User handle (b64url) | `idTail(…, 12)` 끝 12자만 |

방어선:
1. `LogRedact` 헬퍼 — 개발자가 직접 호출 (소스 컨벤션)
2. `SecretMaskingConverter` — logback 출력 시점 자동 마스킹 (defense-in-depth, 실수 방어)

## 5. Alert 추천 규칙

운영팀이 ELK/Splunk/Datadog 등에 걸 만한 alert pattern:

| Alert 이름 | 조건 | 임계 | 우선순위 |
| --- | --- | --- | --- |
| API key brute force | `WARN api-key auth failed: reason=bad-secret` | > 10/min per apiKeyPrefix | High |
| Signature counter regression | `WARN authentication/finish: signature counter regression` | > 5/hour | High (보안) |
| Tenant boundary violation | `WARN tenant boundary violation` | > 1 | Critical (보안) |
| Admin login failure | `WARN admin login failed: reason=bad-password` | > 5/min per email | Medium |
| MDS sync 연속 실패 | `ERROR mds sync failed` | 연속 3회 | High |
| Rate limit 초과 | `WARN rate limit exceeded` | > 100/min cluster-wide | Medium |
| Access denied 폭발 | `WARN access denied` | > 50/min | Medium |
| ID Token 검증 실패 | `WARN id-token verify failed` | > 10/min | Medium |

## 6. Trouble-shooting matrix

| 보이는 로그 | 가능한 root cause | 다음 액션 |
| --- | --- | --- |
| `WARN api-key auth failed: reason=unknown-prefix` | RP 가 잘못된 prefix 전송 / 키 회수됨 | RP 측 설정 확인 |
| `WARN api-key auth failed: reason=bad-secret` | RP 측 secret 변경 누락 또는 attack | rate, source IP 확인 |
| `WARN signature counter regression` | 카드 클론 또는 동기화 이슈 | 해당 credential 회수 권고 |
| `ERROR mds sync failed: cause=SocketTimeoutException` | FIDO MDS 서버 도달 불가 / 방화벽 | 외부 네트워크 점검 |
| `WARN id-token verify failed: reason=iss-mismatch` | passkey-app issuer-base config 불일치 | passkey-app `--passkey.id-token.issuer-base` 와 sample-rp `PASSKEY_ISSUER_BASE` 동기 |
| `WARN id-token verify failed: reason=expired` | clock skew 또는 token TTL 너무 짧음 | 서버 시간 동기 확인 |
| `WARN admin login failed: reason=unknown-user` | 잘못된 email 또는 사용자 삭제 | admin_user 테이블 확인 |
| `WARN tenant boundary violation` | RP_ADMIN 이 다른 tenant 접근 시도 | actor 확인 → 의도된 경로인지 정책 점검 |
| `WARN invitation expired` | 24h TTL 초과 | 재초대 발급 |
| `WARN invitation used` | 토큰 재사용 시도 | 즉시 admin_user 상태 + audit_log 검사 |
| `ERROR vpd context set failed` | DB session role 미설정 | 환경 점검 (CONTAINER, role 활성) |

## 7. 환경별 로그 레벨

| 환경 | root | com.crosscert.passkey | 메모 |
| --- | --- | --- | --- |
| dev (`application-dev.yml`) | INFO | DEBUG | hibernate.SQL=DEBUG 도 활성. 로컬 디버깅용 |
| prod (override 없음) | INFO | INFO | DEBUG/TRACE 출력 안 됨 |

prod 에서 일시적으로 DEBUG 필요 시: env var `LOGGING_LEVEL_COM_CROSSCERT_PASSKEY=DEBUG` 로 재기동.
```

- [ ] **Step 2: Codex + commit**

```bash
git add docs/logging-operations.md
```

Codex prompt: "codex review logging-operations.md. Verify: (1) 7 sections (포맷/검색/레벨/redaction/alert/trouble-shoot/환경), (2) alert 규칙이 실 log message 패턴과 정확히 매칭 (G2/G3 에서 emit 한 문자열과 일치), (3) trouble-shoot matrix 의 cause/action 합리적, (4) redaction 표가 spec § 7 와 일치. Must-fix only."

```bash
git commit -m "docs(logging): operations guide (G4.4)"
```

---

## Task 5: docs/logging-conventions.md

**Files:**
- Create: `docs/logging-conventions.md`

- [ ] **Step 1: Write**

```markdown
# Logging Conventions

> 새 코드를 작성할 때 따라야 할 5가지 룰.

## 1. logfmt 메시지

`log.info("event: key1=val1 key2=val2")` — kebab-case event name, `key=val` 쌍.

좋음:
```java
log.info("registration/start: externalUserId={} displayName={}", userId, name);
```

나쁨:
```java
log.info("User " + userId + " started registration");  // 문자열 concat, key 없음
log.info("hi {}", userId);  // event name 없음
```

## 2. 자격증명·PII 는 LogRedact 통과

소스에서 raw 값 출력 금지. 반드시 헬퍼:

```java
log.info("admin invite issued: email={} tenantId={}",
         LogRedact.email(req.email()), req.tenantId());
```

대상 필드: email, API key full, JWT, password, bcrypt hash, raw bytes.

`SecretMaskingConverter` 가 출력 시점에서도 한 번 더 차단하지만, 그건 마지막 방어선이고 컨벤션 위반.

## 3. Exception 은 ERROR + throwable

```java
log.error("mds sync failed: cause={}", e.toString(), e);  // 두 번째 e 가 stack trace 포함
```

`e.printStackTrace()` 같은 콘솔 직접 호출 금지.

## 4. 새 MDC 키 → 운영 가이드 갱신

현재 표준 키: `traceId`, `tenantId`, `actorEmail`, `apiKeyPrefix`, `userHandle` (DEBUG only).

새 MDC 키 추가 시:
1. 어떤 Filter/Handler 가 set/clear 하는지 명시
2. `logback-spring.xml` 의 pattern 에 추가
3. `logging-operations.md` 검색 cookbook 갱신

## 5. DEBUG 는 dev 전용

prod 의 root 가 INFO. DEBUG 라인이 prod 에 나오면 안 됨.

- request body / response body 출력은 DEBUG
- 외부 호출 성공 detail 은 DEBUG (실패는 WARN/ERROR)
- `if (log.isDebugEnabled()) { … }` guard 로 expensive `toString` 회피

## 6. 새 비즈니스 이벤트 → INFO

audit_log 와 별도 — log 는 ops 의 실시간 가시성용. audit 는 영구 진실, log 는 ELK 7d retention 가정.

새 admin action / scheduler / external call → INFO 한 줄.
```

- [ ] **Step 2: Codex + commit**

```bash
git add docs/logging-conventions.md
```

Codex prompt: "codex review logging-conventions.md. Verify: (1) 6 rules clear and actionable, (2) examples 가 G1-G3 에서 실제로 emit 한 메시지 스타일과 일치, (3) LogRedact 헬퍼 호출 예시 정확, (4) audit_log vs log 의 역할 분담 명확. Must-fix only."

```bash
git commit -m "docs(logging): conventions for contributors (G4.5)"
```

---

## Task 6: G4 regression + cumulative codex + merge

**Files:** (verification)

- [ ] **Step 1: 전체 컴파일 + 테스트**

```bash
./gradlew :core:compileJava :core:test --tests SecretMaskingConverterTest
./gradlew :admin-app:compileJava :passkey-app:compileJava :sample-rp:compileJava :sdk-java:compileJava
```

- [ ] **Step 2: 누적 codex review**

```bash
git diff main..HEAD --stat
```

Codex prompt: "codex review cumulative G4 diff. Focus: (1) LogRedact 4 helpers, (2) SecretMaskingConverter 5 patterns + logback-spring.xml 등록 (core + sample-rp), (3) IT 통과, (4) operations guide / conventions 문서 완결성, (5) 신규 의존성 0, (6) admin-ui 영향 0. Must-fix only. APPROVED for merge if clean."

Apply must-fix as `fix(g4): codex final review feedback`.

- [ ] **Step 3: Merge to main**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git merge --no-ff worktree-logging-g4 -m "Merge Phase G4 — Redaction hardening + operations guide"
git log --oneline -5
```

- [ ] **Step 4: G-series 최종 acceptance 보고**

종합 통과 기준 (spec § 종합 Acceptance):
1. dev/prod 동일 PatternLayout console — G1
2. 3 서버 traceId cross-server — G1
3. MDC 4종 자동 첨부 — G1
4. RequestLoggingFilter 모든 HTTP — G1
5. ~70 비즈니스/보안 이벤트 로그 (G2 45 + G3 25) — G2/G3
6. API key/JWT/password 출력 0 — G4 SecretMaskingConverter
7. operations guide 문서화 — G4
8. SecretMaskingConverterTest 통과 — G4

---

## Phase G4 Summary

신규 파일: LogRedact.java, SecretMaskingConverter.java (× 2), SecretMaskingConverterTest.java, logging-operations.md, logging-conventions.md → 6 파일.
수정: logback-spring.xml × 2.
의존성 0. admin-ui 영향 0.

**G-series 완료** — 종합 가시성 + redaction + 가이드까지 구축.
