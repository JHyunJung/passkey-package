# 운영자 초대 메일 실제 발송 (SMTP) — 설계

- 작성일: 2026-06-14
- 대상 모듈: `core`, `admin-app`
- 상태: 승인됨 (구현 대기)

## 1. 문제 진단

운영자(관리자) 초대 시 메일이 실제로 발송되지 않는다.

확정된 원인:

- 초대 메일 **발송 호출 로직은 이미 존재**한다.
  `admin-app` `InvitationService.createInvitation()` 가 `MailSender.send(email, subject, body)` 를 호출한다.
- 그러나 `core.mail.MailSender` 의 구현체가 `LogMailSender` **하나뿐**이다.
  `LogMailSender` 는 실제 메일을 보내지 않고 SLF4J 로그로만 출력한다 (주석에도 "운영 프로파일에서는 실제 SMTP 구현체로 교체할 것" 명시).
- `spring-boot-starter-mail` 의존성이 없고, `spring.mail.*` 설정도 어디에도 없다.
- `@Value("${admin.invite.base-url:http://localhost:5173}")` 의 `admin.invite.base-url` 이 어떤 yml 에도 정의돼 있지 않아 항상 기본값(localhost)에 의존한다.

핵심 관찰: `MailSender` 는 세 곳이 **공유**한다.

- `InvitationService` (운영자 초대 메일)
- `PasswordResetService` (비밀번호 재설정 메일)
- `MailAlertChannel` (보안 알림 메일, `passkey.alert.mail.enabled=true` 일 때)

따라서 `MailSender` 구현체 하나만 추가하면 세 기능의 실제 메일 발송이 동시에 활성화된다. 구현 지점이 인터페이스 하나로 수렴한다.

## 2. 목표 / 비목표

목표:

- 운영 환경에서 SMTP 로 실제 메일이 발송되게 한다 (Spring Mail / `JavaMailSender`).
- 개발/CI/로컬/테스트 환경은 기존 `LogMailSender` 동작을 그대로 유지한다 (기존 테스트 무변경).
- SMTP 연결 정보와 발신 주소, 초대 수락 URL 을 환경변수로 관리한다 (12-factor, 비밀 Git 미커밋).

비목표:

- SendGrid/Mailgun/SES 등 외부 API 채널 (이번 범위 아님 — SMTP 로 결정).
- 메일 발송 실패 시 동작 변경 (현재 "실패해도 초대 성공 + 토큰 평문 fallback" 유지).
- 실제 외부 SMTP 서버 대상 E2E 테스트 (임베디드 SMTP 는 후속 백로그로 메모).

## 3. 아키텍처

단일 인터페이스 `core.mail.MailSender` 에 SMTP 구현체를 추가하고, `spring.mail.host` 설정 유무로 빈을 자동 선택한다.

```
core/src/main/java/com/crosscert/passkey/core/mail/
  MailSender.java          (기존, 변경 없음)
  LogMailSender.java       (기존) → @ConditionalOnMissingBean(MailSender.class) 추가
  SmtpMailSender.java      (신규) → JavaMailSender 위임, HTML(MimeMessage) 발송
core/build.gradle.kts      → spring-boot-starter-mail 추가
```

### 빈 선택 메커니즘 (조건부 자동 전환)

- `spring.mail.host` 가 설정됨
  → Spring Boot `MailSenderAutoConfiguration` 이 `JavaMailSender` 빈 생성
  → `SmtpMailSender` 활성화 (`@ConditionalOnProperty(prefix="spring.mail", name="host")`)
- `spring.mail.host` 미설정
  → `JavaMailSender` 빈 없음
  → `LogMailSender` 가 fallback (`@ConditionalOnMissingBean(MailSender.class)`)

`SmtpMailSender` 와 `LogMailSender` 가 동시에 등록되지 않도록, `LogMailSender` 에 `@ConditionalOnMissingBean(MailSender.class)` 를 붙여 SMTP 빈이 있으면 물러나게 한다. (등록 순서 보장을 위해 `SmtpMailSender` 에는 명시적 조건을 두고, `LogMailSender` 는 "다른 MailSender 없을 때"만 등록.)

## 4. 데이터 흐름 (호출자 코드 무변경)

```
운영자 초대 (AdminUserController)
  → AdminUserService.invite()
    → InvitationService.createInvitation()
      → mailSender.send(email, subject, body)   ← 주입된 빈만 SmtpMailSender 로 교체
        → JavaMailSender.send(MimeMessage)        ← HTML 메일, SMTP 발송
      → catch(Exception ignore)                   ← 현재 동작 유지: 실패해도 초대 성공
    → InviteResponse(view, InvitationInfo{plaintext, acceptUrl, ...})  ← UI fallback 복사
```

`InvitationService` / `PasswordResetService` / `MailAlertChannel` 의 소스는 한 줄도 바뀌지 않는다. 빈 주입만 바뀐다.

## 5. 구현 상세

### 5.1 `SmtpMailSender` (신규)

- `core.mail` 패키지.
- 생성자 주입: `JavaMailSender`, 발신 주소(`passkey.mail.from`).
- `send(to, subject, body)`:
  - `JavaMailSender.createMimeMessage()` → `MimeMessageHelper(message, "UTF-8")`
  - `setFrom(from)`, `setTo(to)`, `setSubject(subject)`, `setText(body, true)` (HTML=true — 본문이 `<a href>` 등 HTML 포함)
  - `javaMailSender.send(message)`
- 로그: `LogMailSender` 와 동일한 마스킹 수준 — `to`, `subject`, `body-length` 만 INFO 로. 본문 평문은 로그에 남기지 않는다.
- 예외: `MessagingException` 등은 `RuntimeException` 으로 감싸 던진다 (호출자의 `catch(Exception)` 가 받는다 — fallback 정책 유지).

### 5.2 `LogMailSender` (수정)

- 클래스에 `@ConditionalOnMissingBean(MailSender.class)` 추가.
- 그 외 동작 동일.

### 5.3 `core/build.gradle.kts` (수정)

- `api("org.springframework.boot:spring-boot-starter-mail")` 추가 (core 의 다른 starter 들이 `api` 인 패턴과 일치).

## 6. 설정 (환경변수 중심)

### 6.1 발신 주소 — `core/src/main/resources/application-common.yml`

```yaml
passkey:
  mail:
    from: ${PASSKEY_MAIL_FROM:no-reply@passkey.local}
```

### 6.2 SMTP 연결 — `admin-app/src/main/resources/application-prod.yml`

```yaml
spring:
  mail:
    host: ${SPRING_MAIL_HOST:}          # 비어있으면 LogMailSender fallback
    port: ${SPRING_MAIL_PORT:587}
    username: ${SPRING_MAIL_USERNAME:}
    password: ${SPRING_MAIL_PASSWORD:}
    properties:
      mail.smtp.auth: ${SPRING_MAIL_AUTH:true}
      mail.smtp.starttls.enable: ${SPRING_MAIL_STARTTLS:true}
```

> `host` 의 빈 폴백은 의도적이다. prod 에서 `SPRING_MAIL_HOST` 를 주입하지 않으면
> `JavaMailSender` 빈이 안 생기고 `LogMailSender` 로 안전하게 떨어진다(메일만 안 나가고
> 초대 자체는 동작). 운영 시 반드시 주입할 것.

### 6.3 초대 수락 URL — `admin-app/src/main/resources/application-prod.yml`

```yaml
admin:
  invite:
    base-url: ${ADMIN_INVITE_BASE_URL:http://localhost:5173}
```

dev/local/test/CI 는 `SPRING_MAIL_HOST` 를 주입하지 않으므로 자동으로 `LogMailSender` 가 유지된다.

## 7. 에러 처리 (현재 동작 유지)

- SMTP 발송 실패 → `SmtpMailSender` 가 예외를 던짐 → 호출자의 `catch(Exception ignore)` 가 삼킴
  → 초대는 성공, 응답에 토큰 평문 포함(UI 복사 fallback). (결정대로)
- `SmtpMailSender` 는 발송 성공/실패를 INFO/WARN 로 로깅한다. 본문 평문·토큰은 로그에 남기지 않는다.

## 8. 테스트

- 단위 (`SmtpMailSenderTest`): mock `JavaMailSender` 주입. `send()` 호출 시
  `createMimeMessage()` 위임 + `send(MimeMessage)` 호출을 검증. from/to/subject/HTML 플래그가
  올바르게 세팅되는지 `MimeMessage` 캡처로 확인.
- 조건부 빈 선택 (`MailSenderAutoSelectionTest`): `ApplicationContextRunner` 로
  - `spring.mail.host` 설정 시 → `SmtpMailSender` 빈,
  - 미설정 시 → `LogMailSender` 빈 이 주입되는지 검증.
- 회귀: 기존 `AdminUserInvitationFlowIT`, `PasswordResetServiceTest` 등은 host 미설정 →
  `LogMailSender` 그대로 → 통과 유지.
- 범위 밖: 실제 외부 SMTP E2E. 임베디드 SMTP(GreenMail) 통합 테스트는 후속 백로그로 기록.

## 9. 영향 범위 / 리스크

- 변경 파일: `core/build.gradle.kts`, `core/.../mail/SmtpMailSender.java`(신규),
  `core/.../mail/LogMailSender.java`(애너테이션 1줄), `application-common.yml`,
  `application-prod.yml`. 호출자 3곳 무변경.
- 리스크 낮음: 기본 동작(host 미설정)에서 기존과 100% 동일. SMTP 활성화는 prod 에서 env 주입 시에만.
- 부수 효과(의도됨): SMTP 활성화 시 초대뿐 아니라 비밀번호 재설정 메일,
  `passkey.alert.mail.enabled=true` 인 경우 보안 알림 메일도 실제 발송된다.
