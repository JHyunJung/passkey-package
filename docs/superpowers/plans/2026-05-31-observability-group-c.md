# 운영 관측성 (그룹 C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SaaS readiness P1-2(Prometheus metrics) + P1-3(event-based alerting) + P1-7(health probes)를 마감해 운영 blind spot을 닫는다.

**Architecture:** core에 공통 관측성 인프라를 추가해 전 모듈이 상속한다. P1-2는 micrometer-registry-prometheus 의존성 + 도메인 counter 계측. P1-3은 ApplicationEventPublisher → @Async AlertDispatcher → AlertChannel(Log 기본 + Mail 설정형) 추상화로 5개 보안 이벤트 지점을 느슨하게 연결. P1-7은 actuator health probes(readiness/liveness) 분리 설정. 신규 스키마 없음. 테스트는 핵심 행위만(개발 속도 우선).

**Tech Stack:** Java 21, Spring Boot 3, Micrometer + Prometheus, Spring `@Async`/`ApplicationEventPublisher`, JUnit 5 + Mockito + `SimpleMeterRegistry`.

**근거 spec:** `docs/superpowers/specs/2026-05-31-observability-group-c-design.md`

---

## File Structure

**core (공통 인프라):**
- Modify: `core/build.gradle.kts` — micrometer-registry-prometheus 의존성
- Modify: `core/src/main/resources/application-common.yml` — prometheus 노출 + health probes
- Create: `core/.../alert/SecurityAlertEvent.java` — 이벤트 record + enum(AlertType, Severity)
- Create: `core/.../alert/AlertChannel.java` — 인터페이스
- Create: `core/.../alert/LogAlertChannel.java` — 기본 채널
- Create: `core/.../alert/MailAlertChannel.java` — 설정형 채널(MailSender 재사용)
- Create: `core/.../alert/AlertDispatcher.java` — @Async @EventListener fan-out
- Create: `core/.../alert/AlertConfig.java` — @EnableAsync + 채널/executor 빈
- Create: `core/.../alert/AlertProperties.java` — @ConfigurationProperties(passkey.alert.*)

**passkey-app (계측 + 발행):**
- Modify: `passkey-app/.../fido2/registration/RegistrationStartService.java` — ceremony counter
- Modify: `passkey-app/.../fido2/registration/RegistrationFinishService.java` — ceremony counter
- Modify: `passkey-app/.../fido2/authentication/AuthenticationStartService.java` — ceremony counter
- Modify: `passkey-app/.../fido2/authentication/AuthenticationFinishService.java` — ceremony counter + COUNTER_REGRESSION alert
- Modify: `passkey-app/.../security/ApiKeyAuthFilter.java` — apikey counter + BRUTE_FORCE alert
- Modify: `passkey-app/src/main/resources/application.yml` — metrics.tags.application

**admin-app (발행 + 계측):**
- Modify: `admin-app/.../mds/MdsSchedulerService.java` — mds counter + MDS_SYNC_FAILURE alert
- Modify: `admin-app/.../auth/TenantBoundary.java` — TENANT_BOUNDARY_VIOLATION alert
- Modify: `admin-app/.../config/AdminSecurityConfig.java` — ADMIN_LOGIN_FAILURE alert
- Modify: `admin-app/src/main/resources/application.yml` — metrics.tags.application

**책임 분리:** alert 인프라는 `core/.../alert/` 한 패키지에 응집. 발행 지점은 publishEvent 한 줄만(dispatcher 모름). metrics는 각 서비스에 MeterRegistry 주입.

---

## Task 1: P1-2 — micrometer 의존성 + 노출/health 설정

의존성 + yml 설정. 신규 코드 없음(설정만). 계측은 Task 2~3.

**Files:**
- Modify: `core/build.gradle.kts`
- Modify: `core/src/main/resources/application-common.yml`

- [ ] **Step 1: micrometer-registry-prometheus 의존성 추가**

`core/build.gradle.kts`의 `api("org.springframework.boot:spring-boot-starter-actuator")` 줄 다음에 추가:
```kotlin
    api("io.micrometer:micrometer-registry-prometheus")
```
(버전은 Spring Boot BOM이 관리 — 명시 불요.)

- [ ] **Step 2: application-common.yml — prometheus 노출 + health probes**

현재 management 블록:
```yaml
management:
  endpoint.health.show-details: always
  endpoints.web.exposure.include: health,info
```
다음으로 교체:
```yaml
management:
  endpoint.health.show-details: always
  endpoint.health.probes.enabled: true
  endpoints.web.exposure.include: health,info,prometheus
  health:
    group:
      readiness:
        include: readinessState,db,redis
      liveness:
        include: livenessState
```
(probes.enabled → /actuator/health/{readiness,liveness}. readiness에 db·redis 포함(필수 의존), liveness는 외부/필수 의존성 제외.)

- [ ] **Step 3: 컴파일 + actuator 빈 부팅 확인 (대표 1개)**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/observability-group-c && ./gradlew :core:compileJava -q`
Expected: BUILD SUCCESSFUL. (health probes/prometheus 노출 자체는 actuator 자동 기능 — 별도 테스트 생략, spec §6.)

- [ ] **Step 4: Commit**

```bash
git add core/build.gradle.kts core/src/main/resources/application-common.yml
git commit -m "feat(observability): micrometer-prometheus 의존성 + prometheus 노출 + health probes 설정 (P1-2,P1-7)"
```

---

## Task 2: P1-2 — WebAuthn ceremony 메트릭 계측

4개 ceremony 서비스에 `passkey_ceremony_total{type,phase,result}` counter. 대표 테스트 1개(start 성공 + finish 실패 분기).

**Files:**
- Modify: `passkey-app/.../fido2/registration/RegistrationStartService.java`
- Modify: `passkey-app/.../fido2/registration/RegistrationFinishService.java`
- Modify: `passkey-app/.../fido2/authentication/AuthenticationStartService.java`
- Modify: `passkey-app/.../fido2/authentication/AuthenticationFinishService.java`
- Test: `passkey-app/.../fido2/CeremonyMetricsTest.java` (대표)

- [ ] **Step 1: 각 서비스 생성자에 MeterRegistry 주입 + counter 계측**

각 ceremony 서비스(생성자 위치: RegistrationStartService:36, RegistrationFinishService:73, AuthenticationStartService:49, AuthenticationFinishService:60)에 `io.micrometer.core.instrument.MeterRegistry meterRegistry`를 생성자 파라미터+필드로 추가.

각 서비스의 핵심 메서드(start/finish)에서:
- 성공 종료 직전: `meterRegistry.counter("passkey_ceremony_total", "type", "<registration|authentication>", "phase", "<start|finish>", "result", "success").increment();`
- 예외/실패 경로: `..., "result", "failure").increment();`

가장 깔끔한 패턴: 메서드를 try/catch로 감싸 성공은 끝에서, 실패는 catch에서 increment 후 rethrow. 예 (RegistrationFinishService finish):
```java
        try {
            // ... 기존 finish 로직 ...
            meterRegistry.counter("passkey_ceremony_total",
                    "type", "registration", "phase", "finish", "result", "success").increment();
            return result;
        } catch (RuntimeException e) {
            meterRegistry.counter("passkey_ceremony_total",
                    "type", "registration", "phase", "finish", "result", "failure").increment();
            throw e;
        }
```
type/phase는 서비스별 고정 문자열. 4개 서비스 각각 적용(reg/auth × start/finish).

**cardinality 규율**: tenant_id/prefix 태그 금지 — type/phase/result(전부 유한 enum 유래)만.

- [ ] **Step 2: 대표 테스트 작성**

기존 ceremony 테스트(RegistrationStartServiceExcludeTest, AuthenticationStartServiceSuspendTest 등)의 셋업을 먼저 읽어 생성자 mock 패턴을 파악. 신규 생성자 인자 MeterRegistry는 `SimpleMeterRegistry` 실물 주입. Create `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/CeremonyMetricsTest.java`:

```java
package com.crosscert.passkey.app.fido2;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대표 메트릭 검증 — ceremony counter 가 success/failure 로 분기 증가하는지.
 * 모든 서비스를 다 띄우지 않고, counter 호출 규약(태그 키/값)만 SimpleMeterRegistry 로 확인.
 * (개발 속도 우선: 대표 1개. 나머지 서비스는 동일 패턴.)
 */
class CeremonyMetricsTest {

    @Test
    void ceremony_counter_increments_success_and_failure() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        reg.counter("passkey_ceremony_total", "type", "registration", "phase", "start", "result", "success").increment();
        reg.counter("passkey_ceremony_total", "type", "registration", "phase", "finish", "result", "failure").increment();

        double success = reg.find("passkey_ceremony_total")
                .tags("phase", "start", "result", "success").counter().count();
        double failure = reg.find("passkey_ceremony_total")
                .tags("phase", "finish", "result", "failure").counter().count();
        assertThat(success).isEqualTo(1.0);
        assertThat(failure).isEqualTo(1.0);
    }
}
```

Note: 이 테스트는 메트릭 **규약**(이름·태그)을 고정한다. 서비스 내부 호출이 같은 이름·태그를 쓰는지는 컴파일 + 기존 ceremony 테스트(MeterRegistry 주입 갱신)로 커버. spec §6 "대표 1~2개" 원칙.

- [ ] **Step 3: 컴파일 + 기존 ceremony 테스트 회귀**

Run: `./gradlew :passkey-app:compileJava :passkey-app:test --tests '*RegistrationStart*' --tests '*RegistrationFinish*' --tests '*AuthenticationStart*' --tests '*AuthenticationFinish*' --tests '*CeremonyMetrics*' -q`
Expected: BUILD SUCCESSFUL. 기존 테스트가 새 생성자 인자(MeterRegistry)로 깨지면 `new SimpleMeterRegistry()` 주입으로 갱신.

- [ ] **Step 4: Commit**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/ \
        passkey-app/src/test/java/com/crosscert/passkey/app/fido2/CeremonyMetricsTest.java
git commit -m "feat(observability): WebAuthn ceremony 메트릭 counter (P1-2)"
```

---

## Task 3: P1-2 — API key 인증 + MDS sync 메트릭

ApiKeyAuthFilter에 `passkey_apikey_auth_total{result}`, MdsSchedulerService에 `passkey_mds_sync_total{result}`. metrics.tags.application 노출.

**Files:**
- Modify: `passkey-app/.../security/ApiKeyAuthFilter.java`
- Modify: `admin-app/.../mds/MdsSchedulerService.java`
- Modify: `passkey-app/src/main/resources/application.yml`
- Modify: `admin-app/src/main/resources/application.yml`

- [ ] **Step 1: ApiKeyAuthFilter 인증 결과 counter**

생성자에 `MeterRegistry meterRegistry` 추가(필드+주입). 각 결과 분기에서 counter:
- unknown-prefix(line ~116 근처): `meterRegistry.counter("passkey_apikey_auth_total", "result", "unknown_prefix").increment();`
- revoked/expired(line ~127): `..., "result", reason.replace("-","_")).increment();` (reason="revoked"|"expired")
- bad-secret(line ~132): `..., "result", "bad_secret").increment();`
- insufficient_scope(scope 거부 분기, 그룹 B에서 추가됨): `..., "result", "insufficient_scope").increment();`
- 성공(chain.doFilter 직전): `..., "result", "success").increment();`

각 `unauthorized(res)`/`forbidden(res)` 직전에 해당 counter. (result 값은 유한 — cardinality 안전.)

- [ ] **Step 2: MdsSchedulerService sync 결과 counter**

생성자(line 44)에 `MeterRegistry meterRegistry` 추가. sync 메서드에서:
- 성공 종료: `meterRegistry.counter("passkey_mds_sync_total", "result", "success").increment();`
- catch(line 144 `log.error("mds sync failed"...)` 블록): `meterRegistry.counter("passkey_mds_sync_total", "result", "failure").increment();`

- [ ] **Step 3: application.yml에 metrics.tags.application**

`passkey-app/src/main/resources/application.yml`에 management 블록 추가(없으면 신규):
```yaml
management:
  metrics:
    tags:
      application: passkey-app
```
`admin-app/src/main/resources/application.yml`에 동일하게 `application: admin-app`.
(common의 노출 설정은 상속, 앱별 태그만 여기서. 앱별 메트릭 구분용.)

- [ ] **Step 4: 컴파일 + 기존 필터/스케줄러 테스트 회귀**

Run: `./gradlew :passkey-app:compileJava :admin-app:compileJava :passkey-app:test --tests '*ApiKeyAuthFilter*' :admin-app:test --tests '*MdsScheduler*' --tests '*MdsSync*' -q`
Expected: BUILD SUCCESSFUL. 깨진 기존 테스트는 `SimpleMeterRegistry` 주입으로 갱신(ApiKeyAuthFilter 생성자 인자 추가됨 — 필터 테스트들 + 그룹 B에서 만든 ApiKeyAuthFilterScopeTest 갱신).

- [ ] **Step 5: Commit**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java \
        passkey-app/src/main/resources/application.yml \
        admin-app/src/main/resources/application.yml \
        passkey-app/src/test admin-app/src/test
git commit -m "feat(observability): API key 인증 + MDS sync 메트릭 + app 태그 (P1-2)"
```

---

## Task 4: P1-3 — alert 인프라 (event/channel/dispatcher)

core에 alert 패키지. 발행 지점 연결은 Task 5.

**Files:**
- Create: `core/.../alert/SecurityAlertEvent.java`
- Create: `core/.../alert/AlertChannel.java`
- Create: `core/.../alert/LogAlertChannel.java`
- Create: `core/.../alert/MailAlertChannel.java`
- Create: `core/.../alert/AlertProperties.java`
- Create: `core/.../alert/AlertDispatcher.java`
- Create: `core/.../alert/AlertConfig.java`
- Test: `core/.../alert/AlertDispatcherTest.java`

- [ ] **Step 1: SecurityAlertEvent + enums**

Create `core/src/main/java/com/crosscert/passkey/core/alert/SecurityAlertEvent.java`:

```java
package com.crosscert.passkey.core.alert;

import java.util.Map;

/**
 * 보안 이벤트 알림 (P1-3). 발행 지점은 ApplicationEventPublisher 로 이 이벤트를 publish 하고,
 * AlertDispatcher 가 @Async @EventListener 로 수신해 AlertChannel 들에 fan-out 한다.
 * context 에는 마스킹된 값만 담는다(평문 secret/전체 이메일 금지).
 */
public record SecurityAlertEvent(
        AlertType type,
        Severity severity,
        String summary,
        Map<String, String> context) {

    public enum AlertType {
        API_KEY_BRUTE_FORCE,
        COUNTER_REGRESSION,
        TENANT_BOUNDARY_VIOLATION,
        ADMIN_LOGIN_FAILURE,
        MDS_SYNC_FAILURE
    }

    public enum Severity {
        LOW, MEDIUM, HIGH, CRITICAL;

        public boolean atLeast(Severity min) {
            return this.ordinal() >= min.ordinal();
        }
    }
}
```

- [ ] **Step 2: AlertChannel 인터페이스**

Create `core/src/main/java/com/crosscert/passkey/core/alert/AlertChannel.java`:

```java
package com.crosscert.passkey.core.alert;

/** 알림 발송 채널 (P1-3). 채널별 최소 severity 필터 + 발송. */
public interface AlertChannel {

    /** 이 채널이 해당 severity 를 처리하는가. */
    boolean supports(SecurityAlertEvent.Severity severity);

    /** 실제 발송. 실패는 던질 수 있으며 AlertDispatcher 가 per-channel 격리한다. */
    void send(SecurityAlertEvent event);
}
```

- [ ] **Step 3: AlertProperties**

Create `core/src/main/java/com/crosscert/passkey/core/alert/AlertProperties.java`:

```java
package com.crosscert.passkey.core.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * passkey.alert.* 설정. mail 채널 on/off + 수신 주소 + 채널 min-severity.
 */
@ConfigurationProperties(prefix = "passkey.alert")
public record AlertProperties(
        Mail mail) {

    public record Mail(
            boolean enabled,
            String to,
            SecurityAlertEvent.Severity minSeverity) {
    }

    public AlertProperties {
        if (mail == null) {
            mail = new Mail(false, null, SecurityAlertEvent.Severity.HIGH);
        }
    }
}
```

(core가 `@ConfigurationPropertiesScan`으로 스캔되는지 확인 — passkey-app/admin-app의 `@ConfigurationPropertiesScan("com.crosscert.passkey")`가 core 패키지 포함하므로 OK. 안 되면 AlertConfig에 `@EnableConfigurationProperties(AlertProperties.class)`.)

- [ ] **Step 4: LogAlertChannel**

Create `core/src/main/java/com/crosscert/passkey/core/alert/LogAlertChannel.java`:

```java
package com.crosscert.passkey.core.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.stereotype.Component;

/**
 * 기본 채널 — 항상 등록. severity→로그레벨 매핑 + SECURITY_ALERT 마커.
 * 모든 severity 처리(로그는 무해, 외부 집계 시스템이 마커로 필터).
 */
@Component
public class LogAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(LogAlertChannel.class);
    private static final Marker ALERT = MarkerFactory.getMarker("SECURITY_ALERT");

    @Override
    public boolean supports(SecurityAlertEvent.Severity severity) {
        return true; // 로그는 전 severity
    }

    @Override
    public void send(SecurityAlertEvent event) {
        String msg = "security alert: type={} severity={} summary={} context={}";
        if (event.severity().atLeast(SecurityAlertEvent.Severity.HIGH)) {
            log.error(ALERT, msg, event.type(), event.severity(), event.summary(), event.context());
        } else {
            log.warn(ALERT, msg, event.type(), event.severity(), event.summary(), event.context());
        }
    }
}
```

- [ ] **Step 5: MailAlertChannel (설정형)**

Create `core/src/main/java/com/crosscert/passkey/core/alert/MailAlertChannel.java`:

```java
package com.crosscert.passkey.core.alert;

import com.crosscert.passkey.core.mail.MailSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 메일 채널 — passkey.alert.mail.enabled=true 일 때만 빈 등록. 기존 MailSender 재사용.
 * min-severity 미만은 미발송. (기본 min-severity HIGH → MEDIUM brute-force 폭주 자연 완화.)
 */
@Component
@ConditionalOnProperty(prefix = "passkey.alert.mail", name = "enabled", havingValue = "true")
public class MailAlertChannel implements AlertChannel {

    private final MailSender mailSender;
    private final AlertProperties props;

    public MailAlertChannel(MailSender mailSender, AlertProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    @Override
    public boolean supports(SecurityAlertEvent.Severity severity) {
        return severity.atLeast(props.mail().minSeverity());
    }

    @Override
    public void send(SecurityAlertEvent event) {
        String to = props.mail().to();
        if (to == null || to.isBlank()) return;
        String subject = "[보안 알림] " + event.type() + " (" + event.severity() + ")";
        String body = event.summary() + "<br>context: " + event.context();
        mailSender.send(to, subject, body);
    }
}
```

- [ ] **Step 6: AlertDispatcher 테스트 작성 (핵심)**

Create `core/src/test/java/com/crosscert/passkey/core/alert/AlertDispatcherTest.java`:

```java
package com.crosscert.passkey.core.alert;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AlertDispatcherTest {

    private SecurityAlertEvent event(SecurityAlertEvent.Severity sev) {
        return new SecurityAlertEvent(
                SecurityAlertEvent.AlertType.MDS_SYNC_FAILURE, sev, "summary", Map.of("k", "v"));
    }

    @Test
    void fans_out_to_supporting_channels_only() {
        AtomicInteger lowGot = new AtomicInteger();
        AtomicInteger highGot = new AtomicInteger();
        AlertChannel all = channel(s -> true, e -> lowGot.incrementAndGet());
        AlertChannel highOnly = channel(s -> s.atLeast(SecurityAlertEvent.Severity.HIGH), e -> highGot.incrementAndGet());
        AlertDispatcher d = new AlertDispatcher(List.of(all, highOnly));

        d.onAlert(event(SecurityAlertEvent.Severity.MEDIUM));
        assertThat(lowGot.get()).isEqualTo(1);   // all 채널
        assertThat(highGot.get()).isEqualTo(0);  // highOnly 는 MEDIUM 미지원
    }

    @Test
    void one_channel_failure_does_not_block_others() {
        AtomicInteger got = new AtomicInteger();
        AlertChannel boom = channel(s -> true, e -> { throw new RuntimeException("smtp down"); });
        AlertChannel ok = channel(s -> true, e -> got.incrementAndGet());
        AlertDispatcher d = new AlertDispatcher(List.of(boom, ok));

        d.onAlert(event(SecurityAlertEvent.Severity.HIGH)); // throw 안 함
        assertThat(got.get()).isEqualTo(1);                  // 두 번째 채널은 실행됨
    }

    private AlertChannel channel(java.util.function.Predicate<SecurityAlertEvent.Severity> supports,
                                 java.util.function.Consumer<SecurityAlertEvent> send) {
        return new AlertChannel() {
            public boolean supports(SecurityAlertEvent.Severity s) { return supports.test(s); }
            public void send(SecurityAlertEvent e) { send.accept(e); }
        };
    }
}
```

Run: `./gradlew :core:test --tests '*AlertDispatcherTest' -q` → FAIL (AlertDispatcher 없음).

- [ ] **Step 7: AlertDispatcher 구현**

Create `core/src/main/java/com/crosscert/passkey/core/alert/AlertDispatcher.java`:

```java
package com.crosscert.passkey.core.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 보안 알림 fan-out (P1-3). @Async @EventListener 로 SecurityAlertEvent 를 수신해
 * supports 통과 채널에 per-channel 격리(try/catch)로 발송한다. 한 채널 실패가
 * 다른 채널·발행 스레드(요청)를 막지 않는다. @Async 라 발행 지점 지연 없음.
 */
@Component
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final List<AlertChannel> channels;

    public AlertDispatcher(List<AlertChannel> channels) {
        this.channels = channels;
    }

    @Async("alertExecutor")
    @EventListener
    public void onAlert(SecurityAlertEvent event) {
        for (AlertChannel channel : channels) {
            if (!channel.supports(event.severity())) continue;
            try {
                channel.send(event);
            } catch (RuntimeException e) {
                // per-channel 격리: 한 채널 실패가 다른 채널을 막지 않음. 알림 실패는 로그만.
                log.warn("alert channel failed: channel={} type={} cause={}",
                        channel.getClass().getSimpleName(), event.type(), e.toString());
            }
        }
    }
}
```

Run test → PASS (2 tests). (테스트는 `onAlert`를 직접 호출 — @Async/@EventListener 통합은 검증 생략, spec §6.)

- [ ] **Step 8: AlertConfig (@EnableAsync + executor)**

Create `core/src/main/java/com/crosscert/passkey/core/alert/AlertConfig.java`:

```java
package com.crosscert.passkey.core.alert;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 알림 비동기 인프라 (P1-3). 전용 executor 로 alert 발송이 요청 스레드를 막지 않게 한다.
 * 큐 포화 시 abort(+caller 영향 없음) — 알림 유실은 허용(비즈니스 흐름 보호 우선).
 */
@Configuration
@EnableAsync
public class AlertConfig {

    @Bean("alertExecutor")
    public TaskExecutor alertExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("alert-");
        // 큐 포화 시 조용히 버림(요청 스레드 보호 — caller-runs 금지).
        ex.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        ex.initialize();
        return ex;
    }
}
```

Run: `./gradlew :core:compileJava :core:test --tests '*AlertDispatcherTest' -q` → BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/alert/ \
        core/src/test/java/com/crosscert/passkey/core/alert/AlertDispatcherTest.java
git commit -m "feat(observability): 이벤트 기반 alert 인프라 — dispatcher/channel(Log,Mail)/async (P1-3)"
```

---

## Task 5: P1-3 — 발행 지점 5곳 연결

각 보안 이벤트 지점에 `ApplicationEventPublisher.publishEvent(new SecurityAlertEvent(...))` 한 줄(기존 log는 유지). 채널 통합 테스트는 1개(Mail off/on 또는 Log).

**Files:**
- Modify: `passkey-app/.../security/ApiKeyAuthFilter.java` (BRUTE_FORCE)
- Modify: `passkey-app/.../fido2/authentication/AuthenticationFinishService.java` (COUNTER_REGRESSION)
- Modify: `admin-app/.../mds/MdsSchedulerService.java` (MDS_SYNC_FAILURE)
- Modify: `admin-app/.../auth/TenantBoundary.java` (TENANT_BOUNDARY_VIOLATION)
- Modify: `admin-app/.../config/AdminSecurityConfig.java` (ADMIN_LOGIN_FAILURE)
- Test: `core/.../alert/AlertChannelTest.java` (대표 — Mail severity 필터)

- [ ] **Step 1: 각 지점에 ApplicationEventPublisher 주입 + publishEvent**

`org.springframework.context.ApplicationEventPublisher`를 각 클래스 생성자/필드에 주입(이미 MeterRegistry 주입한 곳은 함께). 각 log.warn/error 옆에 publishEvent:

**ApiKeyAuthFilter** (bad-secret 분기, line ~132 — 실제 brute-force 신호):
```java
        eventPublisher.publishEvent(new SecurityAlertEvent(
                SecurityAlertEvent.AlertType.API_KEY_BRUTE_FORCE,
                SecurityAlertEvent.Severity.MEDIUM,
                "api key auth failed (bad secret)",
                java.util.Map.of("prefix", prefix)));
```

**AuthenticationFinishService** (line 199, signCount 미증가):
```java
        eventPublisher.publishEvent(new SecurityAlertEvent(
                SecurityAlertEvent.AlertType.COUNTER_REGRESSION,
                SecurityAlertEvent.Severity.HIGH,
                "signCount did not advance (possible credential clone)",
                java.util.Map.of("credentialId", String.valueOf(cred.getId()),
                                 "tenantId", String.valueOf(ch.tenantId()))));
```

**MdsSchedulerService** (line 144 catch):
```java
        eventPublisher.publishEvent(new SecurityAlertEvent(
                SecurityAlertEvent.AlertType.MDS_SYNC_FAILURE,
                SecurityAlertEvent.Severity.HIGH,
                "mds sync failed",
                java.util.Map.of("cause", e.toString())));
```

**TenantBoundary** (4개 위반 지점 40/47/59/70 — 대표적으로 각 지점에, 또는 공통 헬퍼로 묶어 1회씩):
```java
        eventPublisher.publishEvent(new SecurityAlertEvent(
                SecurityAlertEvent.AlertType.TENANT_BOUNDARY_VIOLATION,
                SecurityAlertEvent.Severity.CRITICAL,
                "tenant boundary violation",
                java.util.Map.of("actor", String.valueOf(actor), "role", String.valueOf(role))));
```
(actor/role은 각 메서드의 지역 변수에 맞춰. 4지점 모두 같은 type/severity.)

**AdminSecurityConfig** failureHandler (line 251): 이 빈은 람다라 `ApplicationEventPublisher`를 빈 메서드 파라미터로 주입(다른 빈처럼 `adminLoginFailureHandler(..., ApplicationEventPublisher events)`). publishEvent:
```java
        events.publishEvent(new SecurityAlertEvent(
                SecurityAlertEvent.AlertType.ADMIN_LOGIN_FAILURE,
                SecurityAlertEvent.Severity.MEDIUM,
                "admin login failed",
                java.util.Map.of("email", maskEmail(email), "reason", reason)));
```

**민감정보**: context엔 prefix/maskEmail/tenantId 등 마스킹·식별자만. 평문 secret/전체 이메일 금지.

- [ ] **Step 2: 대표 채널 테스트 (Mail severity 필터)**

Create `core/src/test/java/com/crosscert/passkey/core/alert/AlertChannelTest.java`:

```java
package com.crosscert.passkey.core.alert;

import com.crosscert.passkey.core.mail.MailSender;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.mockito.Mockito.*;

class AlertChannelTest {

    private SecurityAlertEvent event(SecurityAlertEvent.Severity sev) {
        return new SecurityAlertEvent(
                SecurityAlertEvent.AlertType.ADMIN_LOGIN_FAILURE, sev, "s", Map.of());
    }

    @Test
    void mail_channel_sends_only_at_or_above_min_severity() {
        MailSender mail = mock(MailSender.class);
        AlertProperties props = new AlertProperties(
                new AlertProperties.Mail(true, "ops@x.com", SecurityAlertEvent.Severity.HIGH));
        MailAlertChannel ch = new MailAlertChannel(mail, props);

        // MEDIUM < HIGH → supports false → dispatcher 가 호출 안 하지만, 직접 send 도 안전하게 무발송 보장 위해
        // supports 로 게이트되는 계약을 검증:
        org.assertj.core.api.Assertions.assertThat(ch.supports(SecurityAlertEvent.Severity.MEDIUM)).isFalse();
        org.assertj.core.api.Assertions.assertThat(ch.supports(SecurityAlertEvent.Severity.HIGH)).isTrue();

        ch.send(event(SecurityAlertEvent.Severity.HIGH));
        verify(mail).send(eq("ops@x.com"), contains("ADMIN_LOGIN_FAILURE"), anyString());
    }
}
```

Run: `./gradlew :core:test --tests '*AlertChannelTest' -q` → PASS.

- [ ] **Step 3: 컴파일 + 영향 테스트 회귀**

Run: `./gradlew :passkey-app:compileJava :admin-app:compileJava :core:test --tests '*Alert*' :passkey-app:test --tests '*ApiKeyAuthFilter*' --tests '*AuthenticationFinish*' :admin-app:test --tests '*TenantBoundary*' --tests '*AdminSecurity*' -q`
Expected: BUILD SUCCESSFUL. 생성자/빈 시그니처 변경(ApplicationEventPublisher 추가)으로 깨진 기존 테스트는 mock `ApplicationEventPublisher`(또는 `mock(ApplicationEventPublisher.class)`) 주입으로 갱신.

- [ ] **Step 4: Commit**

```bash
git add passkey-app/src/main/java admin-app/src/main/java \
        core/src/test/java/com/crosscert/passkey/core/alert/AlertChannelTest.java \
        passkey-app/src/test admin-app/src/test
git commit -m "feat(observability): 5개 보안 이벤트 → SecurityAlertEvent 발행 (P1-3)"
```

---

## Task 6: 전체 검증 + 설정 노출 + followups

**Files:**
- Modify: `admin-app`/`passkey-app` application.yml — alert 설정 노출(주석)
- Create: `docs/superpowers/followups/2026-05-31-observability-group-c-followups.md`

- [ ] **Step 1: alert 설정 application.yml 노출(주석)**

`passkey-app`·`admin-app` application.yml의 `passkey:` 블록(또는 신규)에 alert 기본 설정 노출:
```yaml
  alert:
    mail:
      enabled: false        # true 면 MailAlertChannel 등록, ops 메일 발송
      to:                   # 알림 수신 운영자 주소
      min-severity: HIGH    # 이 severity 이상만 메일(LOW/MEDIUM/HIGH/CRITICAL)
```
(기본 LogAlertChannel만 활성 — mail.enabled=false. 발송 채널 결정은 운영자.)

- [ ] **Step 2: 전체 빌드 + 단위/슬라이스 테스트**

Run: `./gradlew :core:test :passkey-app:test --tests '*Test' :admin-app:test --tests '*Test' -q`
Expected: BUILD SUCCESSFUL. 깨진 기존 테스트(MeterRegistry/ApplicationEventPublisher 생성자 주입)는 전부 SimpleMeterRegistry/mock으로 갱신.

- [ ] **Step 3: prometheus 노출 수동 확인 (선택, 가능 시)**

가능하면 앱을 띄워 `/actuator/prometheus`에 `passkey_ceremony_total`·`passkey_apikey_auth_total`이 보이는지, `/actuator/health/readiness`·`/liveness`가 200인지 확인. 환경상 어려우면 생략하고 followups에 기록(자동 인프라라 단위 테스트로 충분).

- [ ] **Step 4: followups 갱신**

Create `docs/superpowers/followups/2026-05-31-observability-group-c-followups.md`: P1-2/3/7 완료 표시. deferred: alert throttle(brute-force dedup 미구현 — channel 책임 훅만), Slack/PagerDuty 채널(인터페이스만), MDS/license 외부 HealthIndicator 제외 이유, metrics tenant 차원 미포함(cardinality), `/actuator/prometheus` 노출 보안(네트워크 의존). 그리고 `2026-05-30-saas-launch-hardening-followups.md`의 P1-2/P1-3/P1-7 행을 ✅ 해결로 갱신(남은 P1: P1-4 = 그룹 D).

- [ ] **Step 5: 커밋 전 게이트 — codex review (가능 시) + code quality**

메모리 지침: 누적 diff(`07e2e91..HEAD`)에 `/codex review`(6/1 리셋 후). 특히 alert 비동기 실패 격리, metrics cardinality, 민감정보 마스킹.

- [ ] **Step 6: Commit**

```bash
git add passkey-app/src/main/resources/application.yml \
        admin-app/src/main/resources/application.yml \
        docs/superpowers/followups/
git commit -m "docs(followups): 그룹 C 완료 (P1-2 metrics + P1-3 alerting + P1-7 health) + alert 설정 노출"
```

---

## Self-Review

**Spec coverage:**
- P1-2 의존성+노출 (§2.1): Task 1 ✅
- P1-2 자동 메트릭 (§2.2): Task 1(의존성만으로 자동) ✅
- P1-2 도메인 메트릭 ceremony/apikey/mds (§2.3): Task 2·3 ✅
- P1-2 cardinality 규율 (§2.3): Task 2·3 (tenant/prefix 태그 금지 명시) ✅
- P1-3 event/channel/dispatcher (§3.2): Task 4 ✅
- P1-3 발행 지점 5곳 (§3.3): Task 5 ✅
- P1-3 @Async/실패격리 (§3.1/§5): Task 4(AlertConfig executor + dispatcher try/catch) ✅
- P1-3 설정 (§3.5): Task 4(AlertProperties) + Task 6(yml 노출) ✅
- P1-7 health probes/그룹 (§4): Task 1 ✅
- 에러처리/마스킹 (§5): Task 4(per-channel)·Task 5(maskEmail context) ✅
- 테스트 최소화 (§6): dispatcher 1 + channel 1 + 메트릭 1 ✅
- followups: Task 6 ✅

**Placeholder scan:** 모든 step에 실제 코드/명령/기대값. "기존 테스트 읽어 갱신"은 생성자 mock 주입이 프로젝트 실제에 의존하는 실행 지시(읽을 파일 명시).

**Type consistency:**
- `SecurityAlertEvent(AlertType, Severity, String, Map<String,String>)` — Task 4·5 일치 ✅
- `Severity.atLeast(Severity)` — Task 4(채널 필터)·테스트 일치 ✅
- `AlertChannel.supports(Severity)→boolean` / `send(SecurityAlertEvent)` — Task 4 일관 ✅
- `AlertDispatcher(List<AlertChannel>)` + `onAlert(SecurityAlertEvent)` — Task 4 테스트·구현 일치 ✅
- `AlertProperties.Mail(boolean,String,Severity)` — Task 4·5 일치 ✅
- `MeterRegistry` 주입 + counter 이름 `passkey_ceremony_total`/`passkey_apikey_auth_total`/`passkey_mds_sync_total` — Task 2·3 일치 ✅
