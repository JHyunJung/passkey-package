# 운영자 초대 메일 실제 발송 (SMTP) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `core.mail.MailSender` 에 SMTP 구현체(`SmtpMailSender`)를 추가해 운영 환경에서 운영자 초대·비밀번호 재설정·보안 알림 메일이 실제로 발송되게 한다. 개발/CI 는 기존 `LogMailSender` 를 그대로 유지한다.

**Architecture:** 단일 인터페이스 `MailSender` 에 `SmtpMailSender` 를 추가한다. `spring.mail.host` 가 설정되면 Spring Boot 가 `JavaMailSender` 빈을 만들고 `SmtpMailSender` 가 활성화된다(`@ConditionalOnProperty`). 미설정이면 `LogMailSender` 가 fallback(`@ConditionalOnMissingBean`). 호출자(`InvitationService`/`PasswordResetService`/`MailAlertChannel`) 코드는 무변경 — 주입되는 빈만 바뀐다.

**Tech Stack:** Java, Spring Boot, `spring-boot-starter-mail` (JavaMailSender / Jakarta Mail), JUnit 5, Mockito, AssertJ, Spring `ApplicationContextRunner`.

---

## 파일 구조

- Create: `core/src/main/java/com/crosscert/passkey/core/mail/SmtpMailSender.java` — JavaMailSender 위임 HTML 메일 발송, `spring.mail.host` 있을 때만 등록
- Modify: `core/src/main/java/com/crosscert/passkey/core/mail/LogMailSender.java` — `@ConditionalOnMissingBean(MailSender.class)` 추가
- Modify: `core/build.gradle.kts` — `spring-boot-starter-mail` 의존성 추가
- Modify: `core/src/main/resources/application-common.yml` — `passkey.mail.from` 추가
- Modify: `admin-app/src/main/resources/application-prod.yml` — `spring.mail.*`, `admin.invite.base-url` 추가
- Create: `core/src/test/java/com/crosscert/passkey/core/mail/SmtpMailSenderTest.java` — 단위 테스트 (mock JavaMailSender)
- Create: `core/src/test/java/com/crosscert/passkey/core/mail/MailSenderAutoSelectionTest.java` — 조건부 빈 선택 검증

호출자 3곳(`InvitationService`, `PasswordResetService`, `MailAlertChannel`)은 **변경하지 않는다.**

---

### Task 1: spring-boot-starter-mail 의존성 추가

**Files:**
- Modify: `core/build.gradle.kts`

- [ ] **Step 1: 의존성 추가**

`core/build.gradle.kts` 의 `dependencies { ... }` 블록 안, `spring-boot-starter-validation` api 선언 바로 아래에 추가한다. (core 의 다른 starter 가 `api` 인 패턴과 일치 — `admin-app` 이 core 를 통해 `JavaMailSender` 타입에 접근 가능해야 함.)

```kotlin
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-mail")
```

- [ ] **Step 2: 의존성 해석 확인**

Run: `./gradlew :core:dependencies --configuration compileClasspath | grep -i "spring-boot-starter-mail\|jakarta.mail"`
Expected: `spring-boot-starter-mail` 과 `jakarta.mail` (또는 `angus-mail`) 항목이 출력됨.

- [ ] **Step 3: Commit**

```bash
git add core/build.gradle.kts
git commit -m "build: core 에 spring-boot-starter-mail 추가 (SMTP 메일 발송 기반)"
```

---

### Task 2: SmtpMailSender 단위 테스트 작성 (failing)

**Files:**
- Create: `core/src/test/java/com/crosscert/passkey/core/mail/SmtpMailSenderTest.java`

- [ ] **Step 1: Write the failing test**

`MimeMessage` 는 인터페이스가 아니라 final 에 가까운 동작을 하므로, 실제 `JavaMailSenderImpl.createMimeMessage()` 가 만드는 빈 `MimeMessage` 를 반환하도록 mock 을 구성하고, `send(MimeMessage)` 로 전달된 메시지의 수신자/제목/발신자/HTML content-type 을 검증한다.

```java
package com.crosscert.passkey.core.mail;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SmtpMailSenderTest {

    @Test
    void send_buildsHtmlMimeMessage_andDelegatesToJavaMailSender() throws Exception {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        // 실제 MimeMessage 인스턴스를 생성해 helper 가 채울 수 있게 한다.
        MimeMessage real = new JavaMailSenderImpl().createMimeMessage();
        when(javaMailSender.createMimeMessage()).thenReturn(real);

        SmtpMailSender sender = new SmtpMailSender(javaMailSender, "no-reply@passkey.test");

        sender.send("admin@example.com", "초대 제목", "<a href=\"x\">수락</a>");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertThat(sent.getSubject()).isEqualTo("초대 제목");
        assertThat(sent.getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("admin@example.com");
        assertThat(sent.getFrom()[0].toString()).isEqualTo("no-reply@passkey.test");
        // setText(body, true) → content-type 이 text/html
        assertThat(sent.getContentType()).contains("text/html");
    }

    @Test
    void send_wrapsMessagingExceptionInRuntimeException() {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        MimeMessage real = new JavaMailSenderImpl().createMimeMessage();
        when(javaMailSender.createMimeMessage()).thenReturn(real);
        doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(javaMailSender).send(any(MimeMessage.class));

        SmtpMailSender sender = new SmtpMailSender(javaMailSender, "no-reply@passkey.test");

        // 호출자의 catch(Exception) 가 받을 수 있도록 RuntimeException 계열로 전파.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> sender.send("a@b.com", "s", "b"))
                .isInstanceOf(RuntimeException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.crosscert.passkey.core.mail.SmtpMailSenderTest"`
Expected: 컴파일 실패 — `SmtpMailSender` 클래스가 아직 없음 (`cannot find symbol: class SmtpMailSender`).

- [ ] **Step 3: Commit (test only)**

```bash
git add core/src/test/java/com/crosscert/passkey/core/mail/SmtpMailSenderTest.java
git commit -m "test: SmtpMailSender 단위 테스트 추가 (failing)"
```

---

### Task 3: SmtpMailSender 구현

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/mail/SmtpMailSender.java`

- [ ] **Step 1: Write minimal implementation**

`@ConditionalOnProperty(prefix = "spring.mail", name = "host")` 로 `spring.mail.host` 설정 시에만 등록한다. `JavaMailSender` 는 Spring Boot `MailSenderAutoConfiguration` 이 같은 조건에서 만들어 준다. from 주소는 `passkey.mail.from` 에서 주입. 로그는 `LogMailSender` 와 동일하게 to/subject/body-length 만 남기고 본문 평문은 남기지 않는다.

```java
package com.crosscert.passkey.core.mail;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 운영용 SMTP 메일 발송 구현체.
 *
 * <p>{@code spring.mail.host} 가 설정된 경우에만 등록된다. 같은 조건에서 Spring Boot 의
 * {@code MailSenderAutoConfiguration} 이 {@link JavaMailSender} 빈을 제공한다.
 * 미설정 시에는 {@link LogMailSender} 가 fallback 으로 등록된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "spring.mail", name = "host")
public class SmtpMailSender implements MailSender {

    private final JavaMailSender javaMailSender;
    private final String from;

    public SmtpMailSender(JavaMailSender javaMailSender,
                          @Value("${passkey.mail.from:no-reply@passkey.local}") String from) {
        this.javaMailSender = javaMailSender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String body) {
        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, true); // HTML 본문 (초대 URL <a href> 포함)
            javaMailSender.send(message);
            log.info("[MAIL] sent to={} subject=\"{}\" body-length={}", to, subject,
                    body == null ? 0 : body.length());
        } catch (Exception e) {
            // 호출자(InvitationService 등)의 catch(Exception) 가 fallback 처리.
            log.warn("[MAIL] send failed to={} subject=\"{}\" reason={}", to, subject,
                    e.getMessage());
            throw new RuntimeException("mail send failed", e);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.crosscert.passkey.core.mail.SmtpMailSenderTest"`
Expected: PASS (2 tests).

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/mail/SmtpMailSender.java
git commit -m "feat: SmtpMailSender 추가 — spring.mail.host 설정 시 SMTP 발송"
```

---

### Task 4: LogMailSender 를 fallback 으로 강등

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/mail/LogMailSender.java`

- [ ] **Step 1: Write minimal implementation**

`@ConditionalOnMissingBean(MailSender.class)` 를 추가해, `SmtpMailSender` 등 다른 `MailSender` 빈이 있으면 등록되지 않게 한다. import 와 애너테이션 두 줄만 추가하고 나머지는 그대로 둔다.

`@Component` 위에 추가:

```java
package com.crosscert.passkey.core.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 개발/CI 용 메일 발송 구현체.
 *
 * <p>실제 메일을 보내지 않고 SLF4J INFO 로그로만 출력한다.
 * 다른 {@link MailSender} 빈({@link SmtpMailSender})이 등록되면 이 빈은 물러난다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(MailSender.class)
public class LogMailSender implements MailSender {

    @Override
    public void send(String to, String subject, String body) {
        log.info("[MAIL] to={} subject=\"{}\" body-length={}", to, subject,
                body == null ? 0 : body.length());
        log.debug("[MAIL] body={}", body);
    }
}
```

- [ ] **Step 2: Run existing mail-using tests to verify no regression**

Run: `./gradlew :core:test --tests "com.crosscert.passkey.core.alert.*"`
Expected: PASS — alert 테스트는 `MailSender` 를 mock 으로 주입하므로 영향 없음.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/mail/LogMailSender.java
git commit -m "refactor: LogMailSender 를 @ConditionalOnMissingBean fallback 으로 강등"
```

---

### Task 5: 조건부 빈 선택 통합 테스트

**Files:**
- Create: `core/src/test/java/com/crosscert/passkey/core/mail/MailSenderAutoSelectionTest.java`

- [ ] **Step 1: Write the failing test**

`ApplicationContextRunner` 로 두 시나리오를 검증한다. SMTP 분기는 Spring Boot 의 `MailSenderAutoConfiguration` 이 `JavaMailSender` 를 제공하도록 해당 auto-config 를 포함시키고 `spring.mail.host` 를 준다.

```java
package com.crosscert.passkey.core.mail;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spring.mail.host 유무에 따른 MailSender 빈 자동 선택 검증.
 * - host 설정 → SmtpMailSender (JavaMailSender 는 MailSenderAutoConfiguration 이 제공)
 * - host 미설정 → LogMailSender fallback
 */
class MailSenderAutoSelectionTest {

    @Configuration
    static class MailBeansConfig {
        @Bean
        SmtpMailSender smtpMailSender(org.springframework.mail.javamail.JavaMailSender s) {
            return new SmtpMailSender(s, "no-reply@test");
        }
        @Bean
        LogMailSender logMailSender() {
            return new LogMailSender();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class));

    @Test
    void usesSmtpMailSender_whenMailHostIsSet() {
        runner.withUserConfiguration(MailBeansConfig.class)
                .withPropertyValues("spring.mail.host=smtp.example.com")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(MailSender.class);
                    assertThat(ctx.getBean(MailSender.class)).isInstanceOf(SmtpMailSender.class);
                });
    }

    @Test
    void usesLogMailSender_whenMailHostIsAbsent() {
        runner.withUserConfiguration(MailBeansConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(MailSender.class);
                    assertThat(ctx.getBean(MailSender.class)).isInstanceOf(LogMailSender.class);
                });
    }
}
```

> 참고: `@ConditionalOnMissingBean` 은 빈 등록 순서에 민감하다. `MailBeansConfig` 에서
> `smtpMailSender` 를 `logMailSender` 보다 먼저 선언해, SMTP 분기에서 Smtp 빈이 먼저 평가되어
> Log 빈이 물러나도록 한다. host 미설정 시에는 `@ConditionalOnProperty` 가 Smtp 빈을 제외해
> Log 빈만 남는다.

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.crosscert.passkey.core.mail.MailSenderAutoSelectionTest"`
Expected: PASS (2 tests).

> 만약 `usesSmtpMailSender` 가 `hasSingleBean` 에서 실패하면(두 빈 공존), `@ConditionalOnMissingBean`
> 평가 순서 문제다 — `MailBeansConfig` 내 빈 선언 순서(Smtp 먼저)를 확인할 것. 그래도 실패하면
> `LogMailSender` 의 조건을 `@ConditionalOnMissingBean(SmtpMailSender.class)` 로 좁혀 명시적으로 만든다.

- [ ] **Step 3: Commit**

```bash
git add core/src/test/java/com/crosscert/passkey/core/mail/MailSenderAutoSelectionTest.java
git commit -m "test: spring.mail.host 유무에 따른 MailSender 빈 선택 검증"
```

---

### Task 6: 발신 주소 설정 추가 (application-common.yml)

**Files:**
- Modify: `core/src/main/resources/application-common.yml`

- [ ] **Step 1: Add config**

`passkey:` 블록 끝(`license:` 블록 다음)에 `mail:` 을 추가한다. 들여쓰기는 `passkey:` 하위 2칸.

`application-common.yml` 의 `license:` 블록 끝부분:

```yaml
  license:
    path: /etc/passkey/license.jwt
    cache-path: /var/lib/passkey/license-cache.jwt
    issuer: license.crosscert.com
    audience: passkey-onprem
    heartbeat-url: https://license.crosscert.com/v1/license
    heartbeat-interval: PT1H
  mail:
    # 발신 주소. SmtpMailSender 가 사용. SMTP 활성화(spring.mail.host 설정) 시 의미 있음.
    from: ${PASSKEY_MAIL_FROM:no-reply@passkey.local}
```

- [ ] **Step 2: Verify YAML parses (app context starts)**

Run: `./gradlew :core:test --tests "com.crosscert.passkey.core.mail.*"`
Expected: PASS — yml 변경이 기존 테스트를 깨지 않음.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/resources/application-common.yml
git commit -m "config: passkey.mail.from 발신 주소 설정 추가"
```

---

### Task 7: SMTP 연결·초대 URL 설정 추가 (application-prod.yml)

**Files:**
- Modify: `admin-app/src/main/resources/application-prod.yml`

- [ ] **Step 1: Add SMTP config under spring:**

`application-prod.yml` 의 `spring:` 블록 안, `mail` 키를 추가한다 (기존 `datasource`/`data`/`flyway` 와 같은 레벨, 2칸 들여쓰기). `host` 빈 폴백이면 `JavaMailSender` 가 안 만들어져 `LogMailSender` 로 안전하게 떨어진다.

```yaml
  mail:
    host:     ${SPRING_MAIL_HOST:}          # 비어있으면 LogMailSender fallback (메일만 미발송, 초대는 동작)
    port:     ${SPRING_MAIL_PORT:587}
    username: ${SPRING_MAIL_USERNAME:}
    password: ${SPRING_MAIL_PASSWORD:}
    properties:
      mail.smtp.auth:            ${SPRING_MAIL_AUTH:true}
      mail.smtp.starttls.enable: ${SPRING_MAIL_STARTTLS:true}
```

- [ ] **Step 2: Add invite base-url (top-level admin: block)**

`application-prod.yml` 최상위(루트 레벨, `spring:`/`server:` 와 같은 레벨)에 `admin:` 블록을 추가한다. `InvitationService` 의 `@Value("${admin.invite.base-url:...}")` 가 이를 읽는다.

```yaml
admin:
  invite:
    # 초대 수락 페이지 base URL. 메일 본문의 /accept-invite?token=... 앞에 붙는다.
    base-url: ${ADMIN_INVITE_BASE_URL:http://localhost:5173}
```

- [ ] **Step 3: Verify admin-app context can load prod profile (compile + yml parse)**

Run: `./gradlew :admin-app:compileJava`
Expected: BUILD SUCCESSFUL — yml 은 컴파일과 무관하나, 이 단계는 변경이 빌드를 깨지 않음을 확인.

> yml 문법은 들여쓰기 오류 시 런타임에만 드러난다. 가능하면 `python3 -c "import yaml,sys; yaml.safe_load(open('admin-app/src/main/resources/application-prod.yml'))"` 로 파싱 검증한다 (단, env placeholder `${...}` 는 일반 문자열로 파싱되므로 문제없음).

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/main/resources/application-prod.yml
git commit -m "config: prod 에 spring.mail.* 와 admin.invite.base-url 환경변수 바인딩 추가"
```

---

### Task 8: 전체 회귀 검증

**Files:** (없음 — 검증만)

- [ ] **Step 1: core mail + alert 테스트**

Run: `./gradlew :core:test --tests "com.crosscert.passkey.core.mail.*" --tests "com.crosscert.passkey.core.alert.*"`
Expected: 모두 PASS.

- [ ] **Step 2: 초대 플로우 통합 테스트 (LogMailSender 경로 유지 확인)**

Run: `./gradlew :admin-app:test --tests "com.crosscert.passkey.admin.operator.*"`
Expected: PASS — `AdminUserInvitationFlowIT`, `PasswordResetServiceTest`, `InvitationServiceAcceptTest` 등이 host 미설정 → LogMailSender 그대로 → 통과.

> Testcontainers Oracle 이 필요한 IT 는 Docker 가 떠 있어야 한다. Docker 미가동이면
> 이 단계는 환경 한계로 건너뛰되, Step 1 의 단위 테스트가 핵심 검증이다 (메모: full build 함정 참고).

- [ ] **Step 3: 최종 커밋 (변경 없으면 생략)**

이 Task 는 검증 전용이라 커밋할 변경이 없다. 모든 테스트가 통과하면 구현 완료.

---

## Self-Review

**1. Spec coverage:**
- §3 아키텍처 (SmtpMailSender + 조건부 전환) → Task 3, 4, 5 ✓
- §5.1 SmtpMailSender → Task 2(test), 3(impl) ✓
- §5.2 LogMailSender 강등 → Task 4 ✓
- §5.3 build.gradle starter-mail → Task 1 ✓
- §6.1 passkey.mail.from → Task 6 ✓
- §6.2 spring.mail.* → Task 7 Step 1 ✓
- §6.3 admin.invite.base-url → Task 7 Step 2 ✓
- §7 에러 처리(예외 전파 → 호출자 catch) → Task 2 두 번째 테스트 + Task 3 구현 ✓
- §8 테스트(단위/빈선택/회귀) → Task 2, 5, 8 ✓
- 호출자 무변경 → 어떤 Task 도 InvitationService/PasswordResetService/MailAlertChannel 수정 안 함 ✓

**2. Placeholder scan:** TBD/TODO/"적절히 처리" 없음. 모든 코드 스텝에 완전한 코드 포함 ✓

**3. Type consistency:**
- `SmtpMailSender(JavaMailSender, String from)` 생성자 시그니처가 Task 2 테스트·Task 3 구현·Task 5 테스트에서 동일 ✓
- `MailSender.send(String, String, String)` 인터페이스 시그니처 일관 ✓
- `passkey.mail.from` 프로퍼티 키가 Task 3(@Value), Task 6(yml) 에서 동일 ✓
- `spring.mail.host` 조건 키가 Task 3(@ConditionalOnProperty), Task 5(test), Task 7(yml) 에서 동일 ✓
