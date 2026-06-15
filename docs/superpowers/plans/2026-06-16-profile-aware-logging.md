# 프로필별 로깅 차별화 + 가독성 개선 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 빈 MDC 슬롯 `[] [] [] []` 노이즈를 제거하고(값 있는 키만 출력), logback `<springProfile>` 로 local/dev·qa·prod 로깅을 차별화하며(레벨/패턴/스택깊이/opt-in JSON), local/dev 에서만 request/response 본문을 안전하게 디버깅할 수 있게 한다.

**Architecture:** core 의 `logback-spring.xml` 을 표준으로 삼는다(passkey-app·admin-app 은 자체 logback 이 없어 core 분을 classpath 공유 → 자동 수혜). rp-app 만 자체 logback 이라 동일하게 동기화한다. `CompactMdcConverter`(값 있는 MDC만 출력)를 core/rp-app 양쪽에 둔다(`SecretMaskingConverter` 중복 패턴과 동일). req/resp 본문은 전용 `com.crosscert.passkey.payload` 로거에 DEBUG 로 기록하고 local/dev logback 에서만 그 레벨을 활성화한다. 비즈니스 로직·MDC 생성 필터는 불변.

**Tech Stack:** Logback (logback-spring.xml, ClassicConverter, springProfile), SLF4J/MDC, Spring Boot logging, logstash-logback-encoder(opt-in JSON), JUnit 5, logback ListAppender(테스트).

---

## 파일 구조

핵심 통찰: passkey-app·admin-app 은 자체 logback 이 없어 **core 의 logback-spring.xml 을 classpath 공유**한다. 따라서 core logback 개선이 두 앱에 자동 적용된다. rp-app 만 자체 logback 보유 → 동기화 필요.

- Create: `core/src/main/java/com/crosscert/passkey/core/logging/CompactMdcConverter.java` — 값 있는 MDC 키만 `[k=v ..]` 출력
- Create: `core/src/test/java/com/crosscert/passkey/core/logging/CompactMdcConverterTest.java`
- Modify: `core/src/main/resources/logback-spring.xml` — compactMdc 등록 + `<springProfile>` 분기 + opt-in JSON appender
- Create: `rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/web/CompactMdcConverter.kt` — core 의 Kotlin 트윈
- Create: `rp-app/src/test/kotlin/com/crosscert/passkey/rpapp/web/CompactMdcConverterTest.kt`
- Modify: `rp-app/src/main/resources/logback-spring.xml` — core 와 동기화
- Modify: `rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/web/RequestLoggingFilter.kt` — req/resp 본문을 payload 로거에 DEBUG 기록(ContentCaching 래퍼)
- Create: `rp-app/src/test/kotlin/com/crosscert/passkey/rpapp/web/PayloadLoggingTest.kt` — 레벨 게이트 + 마스킹/캡 검증
- Create: `rp-app/src/main/resources/application-dev.yml`, `application-qa.yml`, `application-prod.yml`
- Modify: `passkey-app/src/main/resources/application-{dev,qa,prod}.yml` — payload 로거 레벨 보강
- Modify: `core/build.gradle.kts`, `rp-app/build.gradle.kts` — logstash-logback-encoder
- Modify: `gradle/libs.versions.toml` — logstash-logback-encoder 좌표

순서 원칙: 컨버터(Task 1~2) → logback 프로필 분기(Task 3~4) → 의존성+JSON(Task 5) → req/resp payload(Task 6) → 프로필 yml(Task 7) → 회귀(Task 8). 컨버터를 먼저 해야 logback 이 참조할 수 있다.

---

### Task 1: CompactMdcConverter (core) — 값 있는 MDC만 출력

**Files:**
- Create: `core/src/test/java/com/crosscert/passkey/core/logging/CompactMdcConverterTest.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/logging/CompactMdcConverter.java`

- [ ] **Step 1: Write the failing test**

`CompactMdcConverter` 는 logback `ILoggingEvent` 의 MDC 맵에서 고정 순서(traceId, tenantId, actorEmail, apiKeyPrefix)로 값 있는 키만 `[traceId=v tenant=v ..]` 로 묶고, 없으면 빈 문자열을 반환한다. 값은 16자 캡.

```java
package com.crosscert.passkey.core.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompactMdcConverterTest {

    private String convert(Map<String, String> mdc) {
        CompactMdcConverter c = new CompactMdcConverter();
        c.start();
        LoggingEvent ev = new LoggingEvent();
        ev.setLevel(Level.INFO);
        ev.setMessage("x");
        ev.setMDCPropertyMap(mdc);
        return c.convert(ev);
    }

    @Test
    void emptyMdc_returnsEmptyString() {
        assertThat(convert(Map.of())).isEmpty();
    }

    @Test
    void onlyTraceId_returnsOnlyThatKey() {
        assertThat(convert(Map.of("traceId", "fae20e9bec04")))
                .isEqualTo("[traceId=fae20e9bec04]");
    }

    @Test
    void multipleKeys_renderInFixedOrder() {
        assertThat(convert(Map.of(
                "actorEmail", "a@x.com",
                "traceId", "abc",
                "tenantId", "t1")))
                .isEqualTo("[traceId=abc tenantId=t1 actorEmail=a@x.com]");
    }

    @Test
    void unknownMdcKeys_areIgnored() {
        assertThat(convert(Map.of("traceId", "abc", "random", "zzz")))
                .isEqualTo("[traceId=abc]");
    }

    @Test
    void longValue_isCappedTo16Chars() {
        assertThat(convert(Map.of("traceId", "0123456789abcdefGHIJ")))
                .isEqualTo("[traceId=0123456789abcdef]");
    }

    @Test
    void blankValue_isTreatedAsAbsent() {
        assertThat(convert(Map.of("traceId", "", "tenantId", "t1")))
                .isEqualTo("[tenantId=t1]");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.crosscert.passkey.core.logging.CompactMdcConverterTest"`
Expected: 컴파일 실패 — `cannot find symbol: class CompactMdcConverter`.

- [ ] **Step 3: Write minimal implementation**

```java
package com.crosscert.passkey.core.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.Map;

/**
 * 값이 있는 MDC 키만 {@code [traceId=.. tenantId=.. actorEmail=.. apiKeyPrefix=..]} 형태로
 * 묶어 출력한다. 빈 키는 생략하고, MDC 가 전부 비면 빈 문자열을 반환한다.
 *
 * <p>기존 패턴의 {@code [%X{traceId:-}] [%X{tenantId:-}] ...} 4개 슬롯이 비면 {@code [] [] [] []}
 * 노이즈가 되던 것을 대체한다. logback-spring.xml 에
 * {@code <conversionRule conversionWord="compactMdc" converterClass="...CompactMdcConverter"/>}
 * 로 등록해 패턴에서 {@code %compactMdc} 로 쓴다.
 *
 * <p>SecretMaskingConverter 와 마찬가지로 rp-app 에 Kotlin 트윈이 중복 존재한다(rp-app 은
 * :core 비의존). 두 파일 동작을 동일하게 유지할 것.
 */
public class CompactMdcConverter extends ClassicConverter {

    /** 출력 순서 고정. 여기 없는 MDC 키는 무시한다. */
    private static final String[] KEYS = {"traceId", "tenantId", "actorEmail", "apiKeyPrefix"};

    private static final int MAX_VALUE_LEN = 16;

    @Override
    public String convert(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc == null || mdc.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : KEYS) {
            String v = mdc.get(key);
            if (v == null || v.isBlank()) {
                continue;
            }
            if (v.length() > MAX_VALUE_LEN) {
                v = v.substring(0, MAX_VALUE_LEN);
            }
            sb.append(sb.length() == 0 ? "[" : " ").append(key).append('=').append(v);
        }
        if (sb.length() == 0) {
            return "";
        }
        return sb.append(']').toString();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.crosscert.passkey.core.logging.CompactMdcConverterTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/logging/CompactMdcConverter.java core/src/test/java/com/crosscert/passkey/core/logging/CompactMdcConverterTest.java
git commit -m "feat(logging): CompactMdcConverter — 값 있는 MDC 키만 출력 ([] [] [] [] 노이즈 제거)"
```

---

### Task 2: CompactMdcConverter (rp-app Kotlin 트윈)

**Files:**
- Create: `rp-app/src/test/kotlin/com/crosscert/passkey/rpapp/web/CompactMdcConverterTest.kt`
- Create: `rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/web/CompactMdcConverter.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.crosscert.passkey.rpapp.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.LoggingEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CompactMdcConverterTest {

    private fun convert(mdc: Map<String, String>): String {
        val c = CompactMdcConverter()
        c.start()
        val ev = LoggingEvent()
        ev.level = Level.INFO
        ev.message = "x"
        ev.setMDCPropertyMap(mdc)
        return c.convert(ev)
    }

    @Test
    fun emptyMdc_returnsEmptyString() {
        assertThat(convert(emptyMap())).isEmpty()
    }

    @Test
    fun onlyTraceId_returnsOnlyThatKey() {
        assertThat(convert(mapOf("traceId" to "fae20e9bec04")))
            .isEqualTo("[traceId=fae20e9bec04]")
    }

    @Test
    fun multipleKeys_renderInFixedOrder() {
        assertThat(
            convert(
                mapOf(
                    "actorEmail" to "a@x.com",
                    "traceId" to "abc",
                    "tenantId" to "t1",
                ),
            ),
        ).isEqualTo("[traceId=abc tenantId=t1 actorEmail=a@x.com]")
    }

    @Test
    fun longValue_isCappedTo16Chars() {
        assertThat(convert(mapOf("traceId" to "0123456789abcdefGHIJ")))
            .isEqualTo("[traceId=0123456789abcdef]")
    }

    @Test
    fun blankValue_isTreatedAsAbsent() {
        assertThat(convert(mapOf("traceId" to "", "tenantId" to "t1")))
            .isEqualTo("[tenantId=t1]")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :rp-app:test --tests "com.crosscert.passkey.rpapp.web.CompactMdcConverterTest"`
Expected: 컴파일 실패 — `unresolved reference: CompactMdcConverter`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.crosscert.passkey.rpapp.web

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * 값이 있는 MDC 키만 `[traceId=.. tenantId=.. ..]` 로 묶어 출력한다. 빈 키 생략, 전부 비면 "".
 *
 * core 의 `com.crosscert.passkey.core.logging.CompactMdcConverter` Kotlin 트윈
 * — rp-app 은 :core 비의존이라 중복. 두 파일 동작을 동일하게 유지할 것.
 */
class CompactMdcConverter : ClassicConverter() {

    private companion object {
        val KEYS = arrayOf("traceId", "tenantId", "actorEmail", "apiKeyPrefix")
        const val MAX_VALUE_LEN = 16
    }

    override fun convert(event: ILoggingEvent): String {
        val mdc = event.mdcPropertyMap ?: return ""
        if (mdc.isEmpty()) return ""
        val sb = StringBuilder()
        for (key in KEYS) {
            var v = mdc[key]
            if (v.isNullOrBlank()) continue
            if (v.length > MAX_VALUE_LEN) v = v.substring(0, MAX_VALUE_LEN)
            sb.append(if (sb.isEmpty()) "[" else " ").append(key).append('=').append(v)
        }
        return if (sb.isEmpty()) "" else sb.append(']').toString()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :rp-app:test --tests "com.crosscert.passkey.rpapp.web.CompactMdcConverterTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/web/CompactMdcConverter.kt rp-app/src/test/kotlin/com/crosscert/passkey/rpapp/web/CompactMdcConverterTest.kt
git commit -m "feat(logging): rp-app CompactMdcConverter Kotlin 트윈 (core 와 동기화)"
```

---

### Task 3: core logback 프로필 분기 + compactMdc 등록

**Files:**
- Modify: `core/src/main/resources/logback-spring.xml`

- [ ] **Step 1: 전체 교체**

기존 단일 패턴을 `<springProfile>` 분기로 바꾸고 `compactMdc` 를 등록한다. JSON appender 는 Task 5 에서 추가하므로 여기선 텍스트만. 프로필 미지정(테스트 등) 폴백 appender 를 둔다.

`core/src/main/resources/logback-spring.xml` 전체를 다음으로:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <conversionRule conversionWord="msg"
                  converterClass="com.crosscert.passkey.core.logging.SecretMaskingConverter"/>
  <conversionRule conversionWord="compactMdc"
                  converterClass="com.crosscert.passkey.core.logging.CompactMdcConverter"/>

  <!-- local/dev: 색상 + 가독성 우선, 풀 스택트레이스 -->
  <springProfile name="local,dev">
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
      <encoder>
        <pattern>%d{HH:mm:ss.SSS} %clr(%-5level) %compactMdc %clr(%logger{36}){cyan} - %msg%n%ex</pattern>
      </encoder>
    </appender>
    <root level="INFO"><appender-ref ref="CONSOLE"/></root>
    <logger name="com.crosscert.passkey" level="DEBUG"/>
    <!-- req/resp 본문 디버깅 로거 — local/dev 에서만 DEBUG 활성 -->
    <logger name="com.crosscert.passkey.payload" level="DEBUG"/>
  </springProfile>

  <!-- qa: 한 줄 구조화, 축약 스택 -->
  <springProfile name="qa">
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
      <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %compactMdc %logger{36} - %msg%n%rEx{8}</pattern>
      </encoder>
    </appender>
    <root level="INFO"><appender-ref ref="CONSOLE"/></root>
    <logger name="com.crosscert.passkey" level="INFO"/>
  </springProfile>

  <!-- prod: 한 줄, 스택 최소. JSON 은 Task 5 에서 PASSKEY_LOG_JSON 분기 추가 -->
  <springProfile name="prod">
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
      <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %compactMdc %logger{36} - %msg%n%rEx{3}</pattern>
      </encoder>
    </appender>
    <root level="INFO"><appender-ref ref="CONSOLE"/></root>
    <logger name="com.crosscert.passkey" level="INFO"/>
    <logger name="org.springframework" level="WARN"/>
  </springProfile>

  <!-- 프로필 미지정(테스트 등) 폴백 -->
  <springProfile name="!local &amp; !dev &amp; !qa &amp; !prod">
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
      <encoder>
        <pattern>%d{HH:mm:ss.SSS} %-5level %compactMdc %logger{36} - %msg%n</pattern>
      </encoder>
    </appender>
    <root level="INFO"><appender-ref ref="CONSOLE"/></root>
    <logger name="com.crosscert.passkey" level="INFO"/>
  </springProfile>
</configuration>
```

> `%clr` 는 Spring Boot 가 제공하는 컬러 conversionRule 로 logback-spring.xml(Spring 확장)에서
> 사용 가능하다. `%rEx{N}` 는 root-cause-first 축약 스택. 미지정 프로필 폴백의 `&amp;` 는
> XML 이스케이프된 `&`(logback `springProfile` 의 논리 AND).

- [ ] **Step 2: 프로필 로딩 스모크 — 컨텍스트가 에러 없이 뜨는지**

passkey-app 이 core logback 을 공유하므로 거기서 검증한다.
Run: `./gradlew :passkey-app:compileJava` (먼저 컴파일 확인)
이어서 core 단위 테스트가 logback 을 로드하며 깨지지 않는지:
Run: `./gradlew :core:test --tests "com.crosscert.passkey.core.logging.*"`
Expected: PASS (Task 1 의 컨버터 테스트가 그대로 통과 — logback xml 파싱은 앱 부팅 시점이라
단위 테스트엔 영향 없지만, conversionRule 클래스명 오타가 있으면 부팅 스모크(Task 8)에서 잡힘).

- [ ] **Step 3: Commit**

```bash
git add core/src/main/resources/logback-spring.xml
git commit -m "feat(logging): core logback 프로필 분기(local/dev·qa·prod) + compactMdc 등록"
```

---

### Task 4: rp-app logback 동기화

**Files:**
- Modify: `rp-app/src/main/resources/logback-spring.xml`

- [ ] **Step 1: 전체 교체 (core 와 동일 구조, 클래스명만 rp-app)**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <conversionRule conversionWord="msg"
                  converterClass="com.crosscert.passkey.rpapp.web.SecretMaskingConverter"/>
  <conversionRule conversionWord="compactMdc"
                  converterClass="com.crosscert.passkey.rpapp.web.CompactMdcConverter"/>

  <springProfile name="local,dev">
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
      <encoder>
        <pattern>%d{HH:mm:ss.SSS} %clr(%-5level) %compactMdc %clr(%logger{36}){cyan} - %msg%n%ex</pattern>
      </encoder>
    </appender>
    <root level="INFO"><appender-ref ref="CONSOLE"/></root>
    <logger name="com.crosscert.passkey" level="DEBUG"/>
    <logger name="com.crosscert.passkey.payload" level="DEBUG"/>
  </springProfile>

  <springProfile name="qa">
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
      <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %compactMdc %logger{36} - %msg%n%rEx{8}</pattern>
      </encoder>
    </appender>
    <root level="INFO"><appender-ref ref="CONSOLE"/></root>
    <logger name="com.crosscert.passkey" level="INFO"/>
  </springProfile>

  <springProfile name="prod">
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
      <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %compactMdc %logger{36} - %msg%n%rEx{3}</pattern>
      </encoder>
    </appender>
    <root level="INFO"><appender-ref ref="CONSOLE"/></root>
    <logger name="com.crosscert.passkey" level="INFO"/>
    <logger name="org.springframework" level="WARN"/>
  </springProfile>

  <springProfile name="!local &amp; !dev &amp; !qa &amp; !prod">
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
      <encoder>
        <pattern>%d{HH:mm:ss.SSS} %-5level %compactMdc %logger{36} - %msg%n</pattern>
      </encoder>
    </appender>
    <root level="INFO"><appender-ref ref="CONSOLE"/></root>
    <logger name="com.crosscert.passkey" level="INFO"/>
  </springProfile>
</configuration>
```

- [ ] **Step 2: rp-app 컨버터 테스트 회귀 (logback 파싱 영향 없음 확인)**

Run: `./gradlew :rp-app:test --tests "com.crosscert.passkey.rpapp.web.CompactMdcConverterTest"`
Expected: PASS (Task 2 의 5 tests 유지).

- [ ] **Step 3: Commit**

```bash
git add rp-app/src/main/resources/logback-spring.xml
git commit -m "feat(logging): rp-app logback 프로필 분기 — core 와 동기화"
```

---

### Task 5: logstash-logback-encoder 의존성 + opt-in JSON appender

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `core/build.gradle.kts`
- Modify: `rp-app/build.gradle.kts`
- Modify: `core/src/main/resources/logback-spring.xml`
- Modify: `rp-app/src/main/resources/logback-spring.xml`

- [ ] **Step 1: libs.versions.toml 에 좌표 추가**

`[libraries]` 섹션에 추가(버전은 logback 1.5.x 호환 8.x):

```toml
logstash-logback-encoder = { module = "net.logstash.logback:logstash-logback-encoder", version = "8.0" }
```

- [ ] **Step 2: build.gradle.kts 에 의존성 추가**

`core/build.gradle.kts` 의 dependencies 블록에:
```kotlin
    api(rootProject.libs.logstash.logback.encoder)
```
`rp-app/build.gradle.kts` 의 dependencies 블록에:
```kotlin
    implementation(rootProject.libs.logstash.logback.encoder)
```

- [ ] **Step 3: prod 프로필에 opt-in JSON appender 추가 (core + rp-app 동일)**

두 logback-spring.xml 의 `<springProfile name="prod">` 블록을 다음으로 교체한다 (`<springProperty>`
로 `PASSKEY_LOG_JSON` env 를 읽어 텍스트/JSON appender 를 분기):

```xml
  <springProfile name="prod">
    <springProperty scope="context" name="logJson" source="passkey.log.json" defaultValue="false"/>

    <appender name="CONSOLE_TEXT" class="ch.qos.logback.core.ConsoleAppender">
      <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %compactMdc %logger{36} - %msg%n%rEx{3}</pattern>
      </encoder>
    </appender>
    <appender name="CONSOLE_JSON" class="ch.qos.logback.core.ConsoleAppender">
      <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <includeMdcKeyName>traceId</includeMdcKeyName>
        <includeMdcKeyName>tenantId</includeMdcKeyName>
        <includeMdcKeyName>actorEmail</includeMdcKeyName>
        <includeMdcKeyName>apiKeyPrefix</includeMdcKeyName>
      </encoder>
    </appender>

    <root level="INFO">
      <if condition='property("logJson").equals("true")'>
        <then><appender-ref ref="CONSOLE_JSON"/></then>
        <else><appender-ref ref="CONSOLE_TEXT"/></else>
      </if>
    </root>
    <logger name="com.crosscert.passkey" level="INFO"/>
    <logger name="org.springframework" level="WARN"/>
  </springProfile>
```

> `passkey.log.json` 은 env `PASSKEY_LOG_JSON` 의 Spring relaxed-binding 대응 키. `<if>` 는
> logback 의 Janino 조건부 — Janino 가 클래스패스에 없으면 동작 안 하므로, 안 되면 대안으로
> `<springProfile name="prod & json">`(활성 프로필에 `json` 추가) 방식으로 바꾼다. Janino 가
> spring-boot 로 끌려오지 않으면 Step 4 에서 추가.

- [ ] **Step 4: JSON appender 로딩 확인 (Janino 필요 시 추가)**

Run: `./gradlew :rp-app:dependencies --configuration runtimeClasspath | grep -i "janino\|logstash"`
- `logstash-logback-encoder` 가 보이고 `janino` 가 없으면, `<if>` 가 무력화되므로 core/rp-app
  build 에 `runtimeOnly("org.codehaus.janino:janino:3.1.12")` 추가(logback 권장 버전).
- janino 추가 후 다시 dependencies 로 확인.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml core/build.gradle.kts rp-app/build.gradle.kts core/src/main/resources/logback-spring.xml rp-app/src/main/resources/logback-spring.xml
git commit -m "feat(logging): prod opt-in JSON 로그(PASSKEY_LOG_JSON) — logstash-logback-encoder"
```

---

### Task 6: request/response 본문 payload 로깅 (rp-app)

**Files:**
- Modify: `rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/web/RequestLoggingFilter.kt`
- Create: `rp-app/src/test/kotlin/com/crosscert/passkey/rpapp/web/PayloadLoggingTest.kt`

- [ ] **Step 1: Write the failing test**

payload 로거(`com.crosscert.passkey.payload`)가 DEBUG 일 때만 본문을 남기고, 본문은 2KB 로
캡됨을 검증한다. logback `ListAppender` 로 캡처.

```kotlin
package com.crosscert.passkey.rpapp.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class PayloadLoggingTest {

    private val payloadLogger = LoggerFactory.getLogger("com.crosscert.passkey.payload") as Logger
    private val appender = ListAppender<ILoggingEvent>()

    private fun attach(level: Level) {
        appender.list.clear()
        appender.start()
        payloadLogger.level = level
        payloadLogger.addAppender(appender)
    }

    @AfterEach
    fun cleanup() {
        payloadLogger.detachAppender(appender)
        appender.stop()
    }

    private fun runFilter(body: String) {
        val filter = RequestLoggingFilter()
        val req = MockHttpServletRequest("POST", "/passkey/register/finish")
        req.setContent(body.toByteArray())
        req.contentType = "application/json"
        val res = MockHttpServletResponse()
        val chain = FilterChain { _, _ -> }
        filter.doFilter(req, res, chain)
    }

    @Test
    fun debugLevel_logsRequestBody() {
        attach(Level.DEBUG)
        runFilter("{\"hello\":\"world\"}")
        val msgs = appender.list.map { it.formattedMessage }
        assertThat(msgs).anyMatch { it.contains("hello") }
    }

    @Test
    fun infoLevel_doesNotLogRequestBody() {
        attach(Level.INFO)
        runFilter("{\"hello\":\"world\"}")
        // payload 로거가 INFO 면 DEBUG 본문 로그는 억제된다.
        assertThat(appender.list).isEmpty()
    }

    @Test
    fun longBody_isCappedTo2kb() {
        attach(Level.DEBUG)
        runFilter("x".repeat(5000))
        val payloadMsgs = appender.list.map { it.formattedMessage }.filter { it.contains("req body") }
        assertThat(payloadMsgs).isNotEmpty
        // 캡 + truncation 마커. 본문 부분이 2048 + 마커 길이 이하.
        assertThat(payloadMsgs.first().length).isLessThan(2100)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :rp-app:test --tests "com.crosscert.passkey.rpapp.web.PayloadLoggingTest"`
Expected: FAIL — 현재 `RequestLoggingFilter` 는 본문을 안 찍으므로 `debugLevel_logsRequestBody`/`longBody` 실패.

- [ ] **Step 3: RequestLoggingFilter 에 payload 로깅 추가**

기존 요약 로그(method/path/status/durMs)는 그대로 두고, `ContentCachingRequestWrapper/
ResponseWrapper` 로 본문을 캡처해 payload 로거에 DEBUG 로 남긴다. 본문은 2KB 캡.
`com.crosscert.passkey.payload` 로거는 logback 에서 local/dev 만 DEBUG(Task 3/4) 이므로
qa/prod 에선 자동 억제. `%msg` SecretMaskingConverter 가 마스킹.

`RequestLoggingFilter.kt` 전체를 다음으로:

```kotlin
package com.crosscert.passkey.rpapp.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper

/**
 * rp-app local twin of core's `RequestLoggingFilter`. rp-app
 * does not depend on core (separate webapp), so the logic is duplicated.
 *
 * 한 줄 요약(method/path/status/durMs)은 항상 INFO/WARN/ERROR 로 남기고,
 * request/response 본문은 전용 payload 로거에 DEBUG 로 남긴다. payload 로거는
 * logback 에서 local/dev 만 DEBUG 라 qa/prod 에선 자동 억제된다(레벨 게이트).
 * 본문은 2KB 로 캡하고, %msg SecretMaskingConverter 가 비밀값을 마스킹한다(삼중 방어).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RequestLoggingFilter : OncePerRequestFilter() {

    companion object {
        private val log = LoggerFactory.getLogger(RequestLoggingFilter::class.java)
        private val payloadLog = LoggerFactory.getLogger("com.crosscert.passkey.payload")

        private const val MDC_ACTOR_EMAIL = "actorEmail"
        const val ACTOR_EMAIL_ATTR = "com.crosscert.passkey.actorEmail"

        private val EXCLUDED_PATHS = setOf("/actuator/health")
        private const val MAX_BODY = 2048
    }

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val path = req.requestURI
        if (EXCLUDED_PATHS.contains(path)) {
            chain.doFilter(req, res)
            return
        }
        // payload 로거가 DEBUG 일 때만 본문 캐싱 래퍼를 씌운다(불필요한 버퍼링 회피).
        val debugBody = payloadLog.isDebugEnabled
        val wrappedReq = if (debugBody) ContentCachingRequestWrapper(req) else req
        val wrappedRes = if (debugBody) ContentCachingResponseWrapper(res) else res

        val startNs = System.nanoTime()
        try {
            chain.doFilter(wrappedReq, wrappedRes)
        } finally {
            val durMs = (System.nanoTime() - startNs) / 1_000_000L
            val status = wrappedRes.status
            val actorPut = populateActorEmailMdc(req)
            try {
                val msg = String.format(
                    "request: method=%s path=%s status=%d durMs=%d",
                    req.method, path, status, durMs,
                )
                if (status >= 500) log.error(msg) else if (status >= 400) log.warn(msg) else log.info(msg)

                if (debugBody) {
                    logBody(wrappedReq as ContentCachingRequestWrapper, wrappedRes as ContentCachingResponseWrapper)
                }
            } finally {
                if (debugBody) {
                    (wrappedRes as ContentCachingResponseWrapper).copyBodyToResponse()
                }
                if (actorPut) MDC.remove(MDC_ACTOR_EMAIL)
            }
        }
    }

    private fun logBody(req: ContentCachingRequestWrapper, res: ContentCachingResponseWrapper) {
        val reqBody = cap(String(req.contentAsByteArray, Charsets.UTF_8))
        val resBody = cap(String(res.contentAsByteArray, Charsets.UTF_8))
        if (reqBody.isNotEmpty()) payloadLog.debug("req body: {}", reqBody)
        if (resBody.isNotEmpty()) payloadLog.debug("resp body: {}", resBody)
    }

    private fun cap(s: String): String =
        if (s.length <= MAX_BODY) s else s.substring(0, MAX_BODY) + "…[truncated ${s.length}]"

    private fun populateActorEmailMdc(req: HttpServletRequest): Boolean {
        return try {
            val v = req.getAttribute(ACTOR_EMAIL_ATTR) ?: return false
            val name = v.toString()
            if (name.isBlank()) return false
            MDC.put(MDC_ACTOR_EMAIL, name)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
```

> `ContentCachingRequestWrapper` 는 본문을 다 읽은 뒤에야 `contentAsByteArray` 가 채워진다
> (다운스트림이 본문을 소비해야 함). WebAuthn 컨트롤러가 `@RequestBody` 로 본문을 읽으므로
> finally 시점엔 채워져 있다. `copyBodyToResponse()` 는 캐싱된 응답 본문을 실제 출력 스트림에
> 다시 써주는 필수 호출(누락 시 빈 응답).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :rp-app:test --tests "com.crosscert.passkey.rpapp.web.PayloadLoggingTest"`
Expected: PASS (3 tests). `infoLevel_doesNotLogRequestBody` 가 레벨 게이트를, `longBody` 가 2KB 캡을 검증.

- [ ] **Step 5: Commit**

```bash
git add rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/web/RequestLoggingFilter.kt rp-app/src/test/kotlin/com/crosscert/passkey/rpapp/web/PayloadLoggingTest.kt
git commit -m "feat(logging): rp-app req/resp 본문 payload 로거(DEBUG·2KB캡·local/dev 게이트)"
```

---

### Task 7: 프로필별 logging.level yml

**Files:**
- Create: `rp-app/src/main/resources/application-dev.yml`, `application-qa.yml`, `application-prod.yml`
- Modify: `passkey-app/src/main/resources/application-dev.yml`, `application-qa.yml`, `application-prod.yml`

- [ ] **Step 1: rp-app 프로필 yml 신설**

logback 이 패턴/스택/appender 를 프로필별로 제어하므로, yml 은 **로거별 레벨**만 담당(상보적).
payload 로거 레벨은 logback 에서 이미 프로필별로 설정했으므로 yml 에선 앱 패키지 레벨만.

`rp-app/src/main/resources/application-dev.yml`:
```yaml
logging:
  level:
    root: INFO
    com.crosscert.passkey: DEBUG
    com.crosscert.passkey.sdk: DEBUG
    com.crosscert.passkey.rpapp: DEBUG
    org.springframework.web: INFO
```

`rp-app/src/main/resources/application-qa.yml`:
```yaml
logging:
  level:
    root: INFO
    com.crosscert.passkey: INFO
    org.springframework.web: WARN
```

`rp-app/src/main/resources/application-prod.yml`:
```yaml
logging:
  level:
    root: WARN
    com.crosscert.passkey: INFO
    org.springframework: WARN
```

> 주의: `logging.level.<logger>` 를 yml 에 두면 logback 의 `<logger level>` 보다 우선할 수 있다
> (Spring Boot 가 부팅 후 적용). payload 로거(`com.crosscert.passkey.payload`)를 yml 에 **명시하지
> 않는다** — 명시하면 logback 의 local/dev DEBUG 게이트를 덮어쓸 위험. logback 에만 둔다.
> 단 `com.crosscert.passkey: DEBUG`(dev)는 하위 payload 로거도 DEBUG 로 끌어올리는데, 이는
> dev 에서 의도된 동작(본문 디버깅 ON)이라 무방. qa/prod 는 `com.crosscert.passkey: INFO` 라
> payload 도 INFO → 억제(게이트 유지).

- [ ] **Step 2: passkey-app 프로필 yml 보강**

`passkey-app/src/main/resources/application-dev.yml` 의 logging 블록을:
```yaml
logging:
  level:
    com.crosscert.passkey: DEBUG
    org.hibernate.SQL: DEBUG
```
(기존과 동일 — payload 로거는 passkey-app 엔 req/resp 본문 로깅 코드가 없으므로 추가 불필요.
core logback 의 `com.crosscert.passkey.payload` DEBUG(local/dev)는 무해하게 미사용.)

`application-qa.yml`, `application-prod.yml` 는 기존 유지(이미 INFO/WARN 적정). 변경 없음 —
core logback 프로필 분기가 패턴/스택을 담당.

> passkey-app/admin-app 은 core logback 을 공유하므로 패턴/프로필 분기는 자동 적용. yml 은
> 레벨만. 이 Task 에서 passkey-app 은 사실상 무변경(확인만)이고 rp-app yml 신설이 핵심.

- [ ] **Step 3: yml 파싱 검증**

Run: `python3 -c "import yaml; [yaml.safe_load(open(f)) for f in ['rp-app/src/main/resources/application-dev.yml','rp-app/src/main/resources/application-qa.yml','rp-app/src/main/resources/application-prod.yml']]; print('ok')"`
Expected: `ok`

- [ ] **Step 4: Commit**

```bash
git add rp-app/src/main/resources/application-dev.yml rp-app/src/main/resources/application-qa.yml rp-app/src/main/resources/application-prod.yml
git commit -m "feat(logging): rp-app dev/qa/prod 프로필 logging.level yml 신설"
```

---

### Task 8: 부팅 스모크 + 회귀 검증

**Files:** (없음 — 검증만)

- [ ] **Step 1: logback 부팅 스모크 (conversionRule 클래스 해석 + 프로필별 부팅)**

logback xml 의 `conversionRule` 클래스명 오타는 부팅 시점에만 드러난다. rp-app 을 dev/prod
프로필로 띄워 logback 초기화가 에러 없이 되는지 확인(앱이 뜨자마자 종료해도 logback 파싱은 됨).

Run: `./gradlew :rp-app:test --tests "com.crosscert.passkey.rpapp.web.*"`
Expected: CompactMdcConverterTest(5) + PayloadLoggingTest(3) PASS.

rp-app 부팅 스모크(있으면): `RpAppSmokeIT` 류가 컨텍스트를 띄우며 logback 을 로드한다.
Run: `./gradlew :rp-app:test --tests "*SmokeIT*"` (존재 시)
Expected: PASS — logback conversionRule(compactMdc/msg) 클래스가 해석됨.

> 부팅 스모크가 없거나 깨지면, `SPRING_PROFILES_ACTIVE=dev ./gradlew :rp-app:bootRun` 로 수동
> 기동해 시작 로그에 `[traceId=..]` 형태(빈 `[]` 아님)와 컬러가 나오는지 육안 확인. base 비교로
> pre-existing 여부 확정(메모리: full build/슬라이스 pre-existing 함정).

- [ ] **Step 2: core/passkey-app 회귀 (공유 logback 영향 없음 확인)**

Run: `./gradlew :core:test --tests "com.crosscert.passkey.core.logging.*"`
Expected: CompactMdcConverterTest(6) + 기존 SecretMaskingConverter 테스트 PASS.

> passkey-app/admin-app 전체 테스트는 Testcontainers/슬라이스 pre-existing 실패가 섞이므로
> (메모리 참고), 로깅 관련만 필터링해 검증. 실패 시 base(main) 비교로 우리 변경 무관함을 확정.

- [ ] **Step 3: 검증 전용 — 변경 없으면 종료**

모든 로깅 테스트 green + 부팅 스모크 통과면 완료.

---

## Self-Review

**1. Spec coverage:**
- §3.1 CompactMdcConverter → Task 1(core), Task 2(rp-app) ✓
- §3.2 프로필별 logback 분기 → Task 3(core), Task 4(rp-app) ✓
- §3.3 req/resp payload 로거 → Task 6 ✓
- §3.4 프로필별 logging.level yml → Task 7 ✓
- §3.5 opt-in JSON(logstash) → Task 5 ✓
- §5 스택트레이스 깊이(%ex/%rEx{8}/%rEx{3}) → Task 3/4 패턴에 포함 ✓
- §6 보안 삼중 방어(레벨 게이트+마스킹+캡) → Task 6(게이트+캡) + 기존 마스킹 유지 ✓
- §7 테스트(컨버터/로딩스모크/게이트/마스킹회귀/JSON로딩) → Task 1,2,6,8 ✓
- §8 영향범위(파일목록) → Task 파일과 일치 ✓

**2. Placeholder scan:** TBD/TODO/"적절히" 없음. 모든 코드 스텝 완전. Task 5 Step 4(Janino 조건부
추가)는 placeholder 가 아니라 "의존성 확인 후 필요 시 추가"의 구체적 분기 지시(grep 명령 + 추가 좌표 명시) ✓

**3. Type consistency:**
- `CompactMdcConverter` KEYS 순서(traceId/tenantId/actorEmail/apiKeyPrefix) — Task 1/2 동일, Task 3/4 패턴의 의미와 일치 ✓
- MAX_VALUE_LEN=16 — Task 1/2 동일, 테스트 기대값(`0123456789abcdef`=16자)과 일치 ✓
- payload 로거명 `com.crosscert.passkey.payload` — Task 3(logback), Task 4(logback), Task 6(코드+테스트), Task 7(yml 주의노트) 전부 동일 ✓
- MAX_BODY=2048 — Task 6 코드와 테스트(`isLessThan(2100)`) 일치 ✓
- `passkey.log.json`/`PASSKEY_LOG_JSON` — Task 5 일관 ✓
- conversionWord `compactMdc`/`msg` — Task 3/4 등록과 패턴 사용 일치 ✓
