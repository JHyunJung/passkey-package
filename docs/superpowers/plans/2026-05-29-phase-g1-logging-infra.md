# Phase G1 — Logging Common Infrastructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** 3 서버 (admin-app/passkey-app/sample-rp) 공통 로깅 인프라 도입. logback-spring.xml + RequestLoggingFilter + MDC 5종 + SDK X-Trace-Id propagation. **로그 호출 추가 0** — G2/G3 가 그 위에 비즈니스 이벤트 채움. admin-ui 디자인 영향 0.

**Architecture:** core 모듈에 RequestLoggingFilter + TraceIdPropagationInterceptor 신규. logback-spring.xml 은 core + sample-rp 양쪽에 동일 내용 복사. dev/prod 동일 PatternLayout console (사용자 결정). 의존성 추가 0.

**Tech Stack:** Spring Boot 3.x logback-classic (기본), SLF4J MDC, Spring Web ClientHttpRequestInterceptor.

**Spec reference:** `docs/superpowers/specs/2026-05-29-logging-design.md` § Phase G1.

---

## Execution policy (carries over)

1. Tests minimal — typecheck + compile + 1 smoke per new persistence path 없음 (인프라 phase)
2. Per-task codex review on staged diff before commit
3. Autonomous decisions during execution

---

## File Structure

```
core/src/main/resources/logback-spring.xml                                     # new
core/src/main/resources/application-common.yml                                 # modify — logging.level 정책
core/src/main/java/com/crosscert/passkey/core/api/RequestLoggingFilter.java   # new
admin-app/src/main/resources/application.yml                                   # modify — 기존 logging 블록 제거
admin-app/src/main/resources/application-dev.yml                               # modify — dev DEBUG override
admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java # modify — actorEmail MDC
passkey-app/src/main/resources/application.yml                                 # modify — 기존 logging 블록 제거
passkey-app/src/main/resources/application-dev.yml                             # modify — dev DEBUG override
passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java # modify — apiKeyPrefix/tenantId MDC
sample-rp/src/main/resources/logback-spring.xml                                # new (copy of core's)
sample-rp/src/main/resources/application.yml                                   # modify — 기존 logging 블록 → 정책 통일
sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/RequestLoggingFilter.java # new (copy of core's)
sdk-java/src/main/java/com/crosscert/passkey/sdk/internal/TraceIdPropagationInterceptor.java # new
sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClient.java            # modify — interceptor register
sdk-java/src/main/java/com/crosscert/passkey/sdk/internal/RedactingRequestInterceptor.java # modify — X-API-Key redact
```

---

## Working directory & branch

Worktree `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/logging-g1/` on `worktree-logging-g1` (forked from main at `d289e44`).

---

## Task 1: logback-spring.xml (core)

**Files:**
- Create: `core/src/main/resources/logback-spring.xml`

- [ ] **Step 1: Write file**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level [%X{traceId:-}] [%X{tenantId:-}] [%X{actorEmail:-}] [%X{apiKeyPrefix:-}] %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>
  <root level="INFO">
    <appender-ref ref="CONSOLE"/>
  </root>
  <logger name="com.crosscert.passkey" level="INFO"/>
</configuration>
```

- [ ] **Step 2: Codex + commit**

```bash
git add core/src/main/resources/logback-spring.xml
```

Codex prompt: "codex review core logback-spring.xml. Verify: (1) ConsoleAppender + PatternLayout matches spec § G1.1 pattern, (2) MDC 4 keys (traceId/tenantId/actorEmail/apiKeyPrefix) with default `-`, (3) root INFO, com.crosscert.passkey INFO, (4) Spring Boot 3 compatible (logback 1.5.x). Must-fix only."

```bash
git commit -m "feat(core): logback-spring.xml with MDC pattern (G1.1)"
```

---

## Task 2: application-common.yml — logging.level 정책

**Files:**
- Modify: `core/src/main/resources/application-common.yml`

- [ ] **Step 1: Inspect current**

```bash
cat core/src/main/resources/application-common.yml | head -50
```

Identify existing `logging:` block (if any).

- [ ] **Step 2: Add logging.level policy**

Append (or replace existing `logging:` block):

```yaml
logging:
  level:
    root: INFO
    com.crosscert.passkey: INFO
    com.crosscert.passkey.sdk: INFO
    org.springframework.web: WARN
    org.hibernate.SQL: WARN
    org.hibernate.orm.connections: WARN
    com.zaxxer.hikari: WARN
    org.apache.tomcat: WARN
    io.lettuce: WARN
```

Remove any `logging.pattern.*` from this file (logback-spring.xml 가 담당).

- [ ] **Step 3: Codex + commit**

```bash
git add core/src/main/resources/application-common.yml
```

Codex prompt: "codex review application-common.yml logging policy. Verify: (1) root INFO, com.crosscert.passkey INFO, library noise reducers (springframework.web/hibernate/hikari/tomcat/lettuce) at WARN, (2) no pattern overrides (logback-spring.xml owns formatting). Must-fix only."

```bash
git commit -m "feat(core): application-common.yml logging.level policy (G1.2)"
```

---

## Task 3: admin-app application.yml + application-dev.yml

**Files:**
- Modify: `admin-app/src/main/resources/application.yml`
- Modify: `admin-app/src/main/resources/application-dev.yml`

- [ ] **Step 1: Inspect**

```bash
cat admin-app/src/main/resources/application.yml admin-app/src/main/resources/application-dev.yml
```

- [ ] **Step 2: application.yml — 기존 logging 블록 제거**

Delete any `logging:` block from `admin-app/src/main/resources/application.yml`. The common policy from Task 2 covers it.

- [ ] **Step 3: application-dev.yml — DEBUG override**

Append:
```yaml
logging:
  level:
    com.crosscert.passkey: DEBUG
    org.hibernate.SQL: DEBUG
```

(or merge into existing `logging:` block if present)

- [ ] **Step 4: Codex + commit**

```bash
git add admin-app/src/main/resources/application.yml admin-app/src/main/resources/application-dev.yml
```

Codex prompt: "codex review admin-app logging config. Verify: (1) application.yml has no logging block (common policy 우선), (2) application-dev.yml only overrides com.crosscert.passkey=DEBUG + hibernate.SQL=DEBUG, (3) no pattern overrides. Must-fix only."

```bash
git commit -m "feat(admin-app): logging level via common + dev override (G1.2)"
```

---

## Task 4: passkey-app application.yml + application-dev.yml

**Files:**
- Modify: `passkey-app/src/main/resources/application.yml`
- Modify: `passkey-app/src/main/resources/application-dev.yml`

동일 패턴 (Task 3 와 동일).

- [ ] **Step 1: Inspect & remove logging block from application.yml**

- [ ] **Step 2: application-dev.yml DEBUG override**

```yaml
logging:
  level:
    com.crosscert.passkey: DEBUG
    org.hibernate.SQL: DEBUG
```

- [ ] **Step 3: Codex + commit**

```bash
git add passkey-app/src/main/resources/application.yml passkey-app/src/main/resources/application-dev.yml
```

Codex prompt: 동일 패턴. Must-fix only.

```bash
git commit -m "feat(passkey-app): logging level via common + dev override (G1.2)"
```

---

## Task 5: RequestLoggingFilter (core)

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/api/RequestLoggingFilter.java`

- [ ] **Step 1: Inspect TraceIdFilter order**

```bash
cat core/src/main/java/com/crosscert/passkey/core/api/TraceIdFilter.java
grep -rn "@Order\|OncePerRequestFilter\|FilterRegistration" core/src/main/java | head
```

`TraceIdFilter` 는 highest precedence (ordered to run first). RequestLoggingFilter 는 그보다 뒤에 (= traceId MDC 가 set 된 후).

- [ ] **Step 2: Write filter**

```java
package com.crosscert.passkey.core.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // after TraceIdFilter (HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final Set<String> EXCLUDED_PATHS = Set.of(
            "/actuator/health",
            "/.well-known/jwks.json"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (EXCLUDED_PATHS.contains(path)) {
            chain.doFilter(req, res);
            return;
        }
        long startNs = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            long durMs = (System.nanoTime() - startNs) / 1_000_000L;
            String method = req.getMethod();
            int status = res.getStatus();
            String msg = String.format("request: method=%s path=%s status=%d durMs=%d",
                    method, path, status, durMs);
            if (status >= 500) {
                log.error(msg);
            } else if (status >= 400) {
                log.warn(msg);
            } else {
                log.info(msg);
            }
        }
    }
}
```

- [ ] **Step 3: Compile**

```bash
./gradlew :core:compileJava
```

- [ ] **Step 4: Codex + commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/api/RequestLoggingFilter.java
```

Codex prompt: "codex review RequestLoggingFilter (core). Verify: (1) @Order higher than TraceIdFilter (so traceId MDC is already set in finally block), (2) status >=500 ERROR, >=400 WARN, else INFO, (3) excluded paths (/actuator/health, /.well-known/jwks.json) skip logging, (4) duration in ms, (5) finally block guarantees log even on exception, (6) OncePerRequestFilter base. Must-fix only."

```bash
git commit -m "feat(core): RequestLoggingFilter with status-based level (G1.3)"
```

---

## Task 6: sample-rp logback-spring.xml + RequestLoggingFilter

**Files:**
- Create: `sample-rp/src/main/resources/logback-spring.xml` (verbatim copy of core's)
- Create: `sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/RequestLoggingFilter.java` (logic copy)
- Modify: `sample-rp/src/main/resources/application.yml` (remove existing logging block, add policy)

- [ ] **Step 1: Copy logback-spring.xml verbatim**

```bash
cp core/src/main/resources/logback-spring.xml sample-rp/src/main/resources/logback-spring.xml
```

- [ ] **Step 2: Copy RequestLoggingFilter (package adapted)**

```java
package com.crosscert.passkey.samplerp.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final Set<String> EXCLUDED_PATHS = Set.of("/actuator/health");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (EXCLUDED_PATHS.contains(path)) {
            chain.doFilter(req, res);
            return;
        }
        long startNs = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            long durMs = (System.nanoTime() - startNs) / 1_000_000L;
            String msg = String.format("request: method=%s path=%s status=%d durMs=%d",
                    req.getMethod(), path, res.getStatus(), durMs);
            if (res.getStatus() >= 500) log.error(msg);
            else if (res.getStatus() >= 400) log.warn(msg);
            else log.info(msg);
        }
    }
}
```

> NOTE: sample-rp 는 core 의존성 없음, TraceIdFilter 도 없음 (별도 인프라). traceId MDC 가 비어있으면 pattern 의 `[%X{traceId:-}]` 가 `[]` 로 출력됨 — 정상.

- [ ] **Step 3: application.yml 정리**

기존 `logging:` 블록을 다음으로 교체:
```yaml
logging:
  level:
    root: INFO
    com.crosscert.passkey: INFO
    com.crosscert.passkey.sdk: INFO
    com.crosscert.passkey.samplerp: INFO
    org.springframework.web: WARN
```

(pattern.console 제거 — logback-spring.xml 이 담당)

- [ ] **Step 4: Compile + Codex + commit**

```bash
./gradlew :sample-rp:compileJava
git add sample-rp/src/main/resources/logback-spring.xml \
        sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/RequestLoggingFilter.java \
        sample-rp/src/main/resources/application.yml
```

Codex prompt: "codex review sample-rp logging setup. Verify: (1) logback-spring.xml verbatim copy of core (drift detectable), (2) RequestLoggingFilter same logic as core (excluded paths may differ — sample-rp doesn't have jwks), (3) application.yml has no pattern.* (logback owns it), (4) sample-rp doesn't have TraceIdFilter so traceId MDC will be empty for user-facing requests (그건 정상; SDK call 로 propagated traceId 는 채워짐). Must-fix only."

```bash
git commit -m "feat(sample-rp): logback-spring.xml + RequestLoggingFilter (G1.1/G1.3)"
```

---

## Task 7: ApiKeyAuthFilter MDC (passkey-app)

**Files:**
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java`

- [ ] **Step 1: Inspect**

```bash
cat passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java
```

Identify successful-auth branch (where prefix + tenantId 가 결정된 후 chain.doFilter 호출 전).

- [ ] **Step 2: Add MDC put/remove**

`org.slf4j.MDC` import.

In the success branch (just before `chain.doFilter(req, res)`):
```java
MDC.put("apiKeyPrefix", prefix);
MDC.put("tenantId", row.tenantId().toString());
try {
    chain.doFilter(req, res);
} finally {
    MDC.remove("apiKeyPrefix");
    MDC.remove("tenantId");
}
```

> 기존 `chain.doFilter` 호출을 try/finally 로 감싸기. 다른 분기 (인증 실패) 는 변경 없음 — MDC put 도 안 함.

- [ ] **Step 3: Compile + Codex + commit**

```bash
./gradlew :passkey-app:compileJava
git add passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java
```

Codex prompt: "codex review ApiKeyAuthFilter MDC additions. Verify: (1) MDC.put only on successful auth branch (secret 검증 통과 후), (2) apiKeyPrefix = prefix 변수 (앞 11자만, secret 절대 포함 안 됨), (3) tenantId = row.tenantId().toString() UUID format, (4) try/finally 로 chain.doFilter 감싸기 + finally 에서 MDC.remove 보장, (5) 실패 분기는 MDC 미오염. Must-fix only."

```bash
git commit -m "feat(passkey-app): ApiKeyAuthFilter sets apiKeyPrefix + tenantId MDC (G1.4)"
```

---

## Task 8: AdminSecurityConfig — actorEmail MDC

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java`

- [ ] **Step 1: Inspect formLogin success/failure handlers**

```bash
grep -n "formLogin\|successHandler\|failureHandler\|logout" admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java
```

- [ ] **Step 2: Approach 결정 — Filter 또는 Handler?**

Spring Security 의 session-based auth 에선 actorEmail 을 매 요청마다 set 해야 함 (successHandler 는 로그인 시점 한 번만 실행). 권장 방안:

새 filter `AdminMdcFilter` 를 만들어 모든 인증된 요청에서 `SecurityContextHolder` 의 principal 을 MDC 에 put.

`admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminMdcFilter.java` 신규:

```java
package com.crosscert.passkey.admin.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20) // after TraceIdFilter and RequestLoggingFilter setup, before app code
public class AdminMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean put = false;
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            MDC.put("actorEmail", auth.getName());
            put = true;
        }
        try {
            chain.doFilter(req, res);
        } finally {
            if (put) MDC.remove("actorEmail");
        }
    }
}
```

> Order @ HIGHEST_PRECEDENCE + 20 — TraceIdFilter (top) 와 RequestLoggingFilter (top+10) 다음, app filter 이전. 인증 정보는 Spring Security FilterChain 이후 가용하므로 실제 동작 위치는 Spring Security 내부 chain 이후 — 정상 동작 위해 `addFilterAfter(AdminMdcFilter, SecurityContextPersistenceFilter.class)` 같은 명시적 등록이 더 안전. 코드 점검 후 결정.

대안: `AdminSecurityConfig` 에서 `http.addFilterAfter(new AdminMdcFilter(), SecurityContextHolderAwareRequestFilter.class)` 명시.

- [ ] **Step 3: Compile + Codex + commit**

```bash
./gradlew :admin-app:compileJava
git add admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminMdcFilter.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java
```

Codex prompt: "codex review AdminMdcFilter + AdminSecurityConfig wiring. Verify: (1) MDC.put('actorEmail') only when authenticated and not anonymousUser, (2) try/finally with conditional remove (don't clear if didn't set), (3) filter order ensures SecurityContext is populated before this filter runs (addFilterAfter SecurityContextHolderAwareRequestFilter or similar), (4) RequestLoggingFilter still has access to actorEmail MDC (must run before RequestLoggingFilter output happens — RequestLoggingFilter's finally block runs after chain returns, so actorEmail MDC must be removed AFTER RequestLoggingFilter has logged. Order: outer=RequestLoggingFilter → inner=AdminMdcFilter; finally inside-out → AdminMdcFilter clears first, then RequestLoggingFilter logs with empty actorEmail. THIS IS A BUG — adjust ordering or set MDC earlier). Must-fix only."

> Codex 가 ordering bug 잡을 가능성 큼. 해결안: RequestLoggingFilter 보다 **앞쪽** 에서 actorEmail set (예: SecurityContext 이 채워진 직후 = SecurityContextHolderAwareRequestFilter 이후, RequestLoggingFilter 보다 먼저). RequestLoggingFilter 의 Order = HIGHEST_PRECEDENCE+10, AdminMdcFilter Order = HIGHEST_PRECEDENCE+5 (?) — 단 SecurityContext 가 아직 안 채워졌을 수 있음. 가장 안전: AdminMdcFilter 를 RequestLoggingFilter 안에서 wrap 하거나, AdminSecurityConfig 의 `successHandler` 에서 session attribute 에 email 저장 후 RequestLoggingFilter 가 그 attribute 를 읽어 MDC put.

**Simpler approach** (codex 의견 반영 후 최종): RequestLoggingFilter 자체가 SecurityContextHolder 에서 actorEmail 을 읽어 MDC put. AdminMdcFilter 불필요. RequestLoggingFilter 가 core 에 있고 admin-app 만의 Spring Security 의존성을 가지면 안 되니, 대안으로:

(A) core RequestLoggingFilter 가 try 안에서 chain 호출 직전 `SecurityContextHolder.getContext().getAuthentication()` reflection-free 호출 (Spring Security 가 classpath 에 있으면 작동, 없으면 silently skip)

(B) admin-app 전용 RequestLoggingFilter 를 만들어 SecurityContextHolder 사용 + core 의 RequestLoggingFilter 를 admin-app 에서 disable

**권장은 (A)** — Spring Security 는 admin-app 의 의존성이므로 admin-app 부팅 시 classpath 에 있고 passkey-app 에는 없음. try/catch ClassNotFoundException 로 graceful.

Plan 본문 수정: AdminMdcFilter 대신 core RequestLoggingFilter 안에 Spring Security 접근 hook. (Task 5 보강)

이 fix 는 Task 5 commit 후속에 fix-up commit 으로:

```bash
git commit -m "feat(admin-app): actorEmail MDC via SecurityContext reflection (G1.4)"
```

---

## Task 9: SDK TraceIdPropagationInterceptor

**Files:**
- Create: `sdk-java/src/main/java/com/crosscert/passkey/sdk/internal/TraceIdPropagationInterceptor.java`
- Modify: `sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClient.java` (interceptor register)

- [ ] **Step 1: Write interceptor**

```java
package com.crosscert.passkey.sdk.internal;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

public class TraceIdPropagationInterceptor implements ClientHttpRequestInterceptor {

    private static final String TRACE_HEADER = "X-Trace-Id";

    @Override
    public ClientHttpResponse intercept(HttpRequest req, byte[] body, ClientHttpRequestExecution exec)
            throws IOException {
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            req.getHeaders().add(TRACE_HEADER, traceId);
        }
        return exec.execute(req, body);
    }
}
```

- [ ] **Step 2: Register in PasskeyClient**

In `PasskeyClient` constructor where RestTemplate is built, add this interceptor to the chain (alongside RedactingRequestInterceptor).

- [ ] **Step 3: Compile + Codex + commit**

```bash
./gradlew :sdk-java:compileJava
git add sdk-java/src/main/java/com/crosscert/passkey/sdk/internal/TraceIdPropagationInterceptor.java \
        sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClient.java
```

Codex prompt: "codex review TraceIdPropagationInterceptor + PasskeyClient registration. Verify: (1) X-Trace-Id header added only when MDC traceId is non-null and non-blank, (2) registered in RestTemplate interceptor chain, (3) order vs RedactingRequestInterceptor — propagation should run first so trace flows, then redaction acts on logging. Must-fix only."

```bash
git commit -m "feat(sdk): TraceIdPropagationInterceptor for cross-server tracing (G1.5)"
```

---

## Task 10: RedactingRequestInterceptor — X-API-Key 헤더 redact

**Files:**
- Modify: `sdk-java/src/main/java/com/crosscert/passkey/sdk/internal/RedactingRequestInterceptor.java`

- [ ] **Step 1: Inspect existing**

```bash
cat sdk-java/src/main/java/com/crosscert/passkey/sdk/internal/RedactingRequestInterceptor.java
```

기존에 idToken JSON 필드 redact 패턴 존재.

- [ ] **Step 2: X-API-Key header redact 추가**

Header logging 시 X-API-Key 값을 앞 11자 + `<redacted>` 로 마스킹. 구체 구현은 기존 패턴에 맞춰 추가.

```java
// (existing) idToken redact …

// Header redact:
// "X-API-Key: pk_devacme0longsecret" → "X-API-Key: pk_devacme0<redacted>"
private static final Pattern API_KEY_HEADER = Pattern.compile("(?i)(X-API-Key:\\s*pk_[A-Za-z0-9]{8})[A-Za-z0-9_]+");
// ...
out = API_KEY_HEADER.matcher(out).replaceAll("$1<redacted>");
```

- [ ] **Step 3: Compile + Codex + commit**

```bash
./gradlew :sdk-java:compileJava
git add sdk-java/src/main/java/com/crosscert/passkey/sdk/internal/RedactingRequestInterceptor.java
```

Codex prompt: "codex review RedactingRequestInterceptor enhancement. Verify: (1) X-API-Key header redact pattern matches `pk_` + 8 chars then strips rest, (2) idToken JSON field redact still works, (3) case-insensitive header matching, (4) no regression in existing redact patterns. Must-fix only."

```bash
git commit -m "feat(sdk): RedactingRequestInterceptor redacts X-API-Key (G1.6)"
```

---

## Task 11: G1 regression + cumulative codex + `--no-ff` merge

**Files:** (verification only)

- [ ] **Step 1: Full backend compile**

```bash
./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava :sample-rp:compileJava :sdk-java:compileJava
```

- [ ] **Step 2: Boot smoke (선택)**

3 서버 모두 부팅 가능한지 한 번 띄움 (옵션). dev profile.

- [ ] **Step 3: 누적 codex review**

```bash
git diff main..HEAD --stat
```

Codex prompt: "codex review the cumulative G1 logging infrastructure diff. Focus: (1) logback-spring.xml × 2 are byte-identical (sample-rp copy), (2) MDC keys consistent across filters (traceId/tenantId/actorEmail/apiKeyPrefix), (3) RequestLoggingFilter excluded paths sensible, (4) ApiKeyAuthFilter MDC put only on success + try/finally cleanup, (5) actorEmail MDC propagation works through RequestLoggingFilter ordering, (6) SDK TraceIdPropagation registered before Redacting (so headers flow then redact during log), (7) X-API-Key redact won't leak secret. Must-fix only. APPROVED for merge if clean."

Apply must-fix as `fix(g1): codex final review feedback`.

- [ ] **Step 4: Merge to main**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git merge --no-ff worktree-logging-g1 -m "Merge Phase G1 — Logging common infrastructure"
git log --oneline -5
```

- [ ] **Step 5: Manual smoke checklist**

For user:
- 3 서버 dev profile 부팅 → console 라인이 `HH:mm:ss.SSS LEVEL [traceId] [tenantId] [actorEmail] [apiKeyPrefix] logger - msg` 포맷
- 임의 endpoint 호출 → `request: method=… status=… durMs=…` 한 줄 자동 노출
- sample-rp 의 register/options 호출 → sample-rp 로그의 traceId 가 passkey-app 의 같은 trace 로 매칭
- API key 헤더가 raw 로그에 평문 노출 안 됨

---

## Phase G1 Summary

**What ships:**
- core logback-spring.xml (admin-app/passkey-app 공유)
- sample-rp logback-spring.xml (동일 내용 copy)
- core RequestLoggingFilter + sample-rp RequestLoggingFilter
- application.yml 정책 통일 + dev override
- ApiKeyAuthFilter 의 apiKeyPrefix/tenantId MDC
- admin-app actorEmail MDC (RequestLoggingFilter 통합 hook 또는 별도 filter)
- SDK TraceIdPropagationInterceptor + RedactingRequestInterceptor 강화

**Design impact:** zero (admin-ui 무변경).

**No business log calls added** — G2 가 그 위에 빌드.

**Next phase:** G2 — 비즈니스 이벤트 로그.
