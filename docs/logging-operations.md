# Logging Operations Guide

> 3 서버 (admin-app / passkey-app / rp-app) 의 로그 검색·alert·troubleshooting 가이드.
> 인프라: `core/src/main/resources/logback-spring.xml`(admin-app·passkey-app)
> 과 `rp-app/src/main/resources/logback-spring.xml` + MDC 4종
> + `SecretMaskingConverter` + `CompactMdcConverter`.

## 1. 로그 포맷

```
HH:mm:ss.SSS LEVEL [traceId=… tenantId=… actorEmail=… apiKeyPrefix=…] logger - msg
```

MDC 칸은 `CompactMdcConverter`(`%compactMdc`)가 만든다 — **값이 있는 키만**
`key=value` 로 나열하고 하나의 `[…]` 로 묶는다. 값이 없는 키는 **생략**되고,
MDC 가 전부 비면 대괄호 자체가 나오지 않는다. 각 값은 **16자에서 잘린다**.

- **traceId** — 한 HTTP 요청의 식별자. cross-server 추적 가능 (`X-Trace-Id` 헤더로 propagate).
  `TraceIdFilter` 가 발급할 때는 UUID 에서 **대시를 없앤 16자**다(`9f3a2b1c4d5e6f70`).
- **tenantId** — passkey-app 의 `ApiKeyAuthFilter` 가 인증 성공 시 set (UUID).
  16자 절단 때문에 로그에는 **앞 16자만** 보인다(`7f00dead-0000-00`).
- **actorEmail** — 로그인 사용자 email. `AdminMdcFilter` 가 request attribute 로 넘기고 `RequestLoggingFilter` 가 MDC 에 넣는다.
  **MDC 칸에는 평문 email 이 그대로 들어간다** — `SecretMaskingConverter` 는 `%msg`(메시지 본문)에만 걸리고 `%compactMdc` 는 거치지 않으며,
  `CryptoUtils.maskEmail` 은 로그 메시지 인자에만 쓰인다. 즉 같은 줄에서 MDC 칸은 `actorEmail=alice@crosscert`
  (텍스트 로그는 16자에서 절단 — `CompactMdcConverter:39-40`. JSON 로그는 절단하지 않아 전체 주소가 나간다),
  메시지 본문은 `a***@crosscert.com` 으로 다르게 보일 수 있다.
- **apiKeyPrefix** — passkey-app 의 `X-API-Key` 헤더 앞 11자 (`pk_xxxxxxxx`).
- rp-app 은 실제로 `traceId` 만 남긴다. `RequestLoggingFilter` 에 `actorEmail` 자리는 있지만
  값을 넣어 주는 인증 필터가 없어(데모라 로그인 개념 없음) 항상 비어 있다. `tenantId`·`apiKeyPrefix` 는 `ApiKeyAuthFilter` 가 없어 아예 없다.

`msg` 는 logfmt 형식: `event: key=val key=val`.

## 2. 검색 cookbook

> MDC 는 `key=value` 로 찍히므로 **키 이름을 붙여서** 검색한다. 값은 16자에서
> 잘리므로 긴 값은 **앞부분만** 넣는다(접두 검색). JSON 로그(`PASSKEY_LOG_JSON=true`)
> 에서는 MDC 가 별도 필드로 나가므로 ELK 쪽 질의는 절단 영향을 받지 않는다
> (core 기반 passkey-app·admin-app 은 4종 전부, rp-app 은 `traceId`·`actorEmail` 필드를 선언하지만 실제로 채워지는 건 `traceId` 뿐).

### 한 사용자 트랜잭션 추적
```bash
grep 'traceId=9f3a2b1c4d5e6f70' *.log
# 또는 ELK
traceId: "9f3a2b1c4d5e6f70"
```
rp-app 의 `register/begin` → passkey-app 의 `registration/start` → … 가 한 trace 로 정렬됨.

### tenant 한정 이벤트
```bash
grep 'tenantId=7f00dead-0000-00' *.log   # 16자 절단분까지만 매칭
# ELK (절단 없는 원본)
tenantId: "7f00dead-0000-0000-0000-000000000000"
```

### 관리자 활동
```bash
grep 'actorEmail=alice@crosscert' *.log   # MDC 칸은 평문(16자 절단)
# ELK
actorEmail: "alice@crosscert.com"   # JSON 필드는 절단되지 않는다(텍스트 로그만 16자)
```

### 특정 API key 사용 추적
```bash
grep 'apiKeyPrefix=pk_devacme0' *.log
# ELK
apiKeyPrefix: "pk_devacme0"
```

### 보안 경보 패턴
```bash
grep -E 'WARN.*(api-key auth failed|signCount did not advance|tenant boundary violation|signup request skipped|admin access denied)' *.log
```

## 3. 레벨 의미

| Level | 사용 시점 | 예 |
| --- | --- | --- |
| `INFO` | 정상 비즈니스 이벤트 | ceremony start/finish, admin write, scheduler tick |
| `WARN` | 정상 흐름이지만 주의 필요 | auth 실패, signature regression, MDS sync skipped |
| `ERROR` | 시스템 오류 + 예외 | MDS sync failed, JPA exception (stack trace 동반) |
| `DEBUG` | dev 디버깅 전용 | request body 크기, JWKS 호출, key kid 발급 |

prod 에서는 root 가 **WARN**, `com.crosscert.passkey` 만 INFO 다(3앱 공통).
따라서 애플리케이션 로그는 INFO 까지 나오지만 프레임워크(Spring·Hibernate 등)
로그는 WARN 이상만 남고, DEBUG 는 어느 쪽도 출력되지 않는다.

## 4. Redaction 약속

다음 값은 로그 **메시지 본문**(`%msg`)에서 `SecretMaskingConverter` 가 가린다.
MDC 칸(`%compactMdc`·JSON MDC 필드)은 이 변환을 거치지 않으므로 표의 약속이 적용되지 않는다:

| 자격증명/PII | 처리 방법 |
| --- | --- |
| API key secret (prefix 이후) | `pk_devacme0` 11자만 노출 |
| JWT body | `Bearer <redacted>`(헤더 형태) / 본문에 홀로 나온 JWS 는 `<jws-redacted>` |
| Password | `password=<redacted>` |
| Bcrypt hash | `<bcrypt-redacted>` |
| Raw attestation/assertion bytes | 메시지에 포함 안 함 (claim 메타만) |
| Email (로그 **메시지** 안) | `a***@domain` — `CryptoUtils.maskEmail` 을 호출한 곳만. **MDC `actorEmail` 칸은 평문**(§1 참조) |
| User handle (b64url) | `idTail(…, 12)` 끝 12자만 |

방어선:
1. `CryptoUtils.maskEmail` 등 마스킹 유틸 — 개발자가 로그 호출 시 직접 적용 (소스 컨벤션).
   email 마스킹의 실제 관례는 이쪽이다(`AdminSecurityConfig`, `TenantBoundary`,
   `SignupRequestService`, `PasswordResetService`). `core` 에 `LogRedact` 클래스가 남아
   있지만 main 코드에서 호출하는 곳이 없다 — 컨벤션의 정본이 아니다.
2. `SecretMaskingConverter` — logback 출력 시점 자동 마스킹 (defense-in-depth, 실수 방어).
   텍스트·JSON(`RedactingMessageJsonProvider`) 양쪽에 걸린다.

## 5. Alert 추천 규칙

운영팀이 ELK/Splunk/Datadog 등에 걸 만한 alert pattern:

| Alert 이름 | 조건 | 임계 | 우선순위 |
| --- | --- | --- | --- |
| API key brute force | `WARN api-key auth failed: reason=bad-secret` | > 10/min per apiKeyPrefix | High |
| Signature counter regression | `WARN signCount did not advance for credential …` | > 5/hour | High (보안) |
| Tenant boundary violation | `WARN tenant boundary violation` | > 1 | Critical (보안) |
| Admin login failure | `WARN admin login failed: email=… reason=…` | > 5/min per email | Medium |
| MDS sync 연속 실패 | `ERROR mds sync failed: cause=…` | 연속 3회 | High |
| Rate limit 초과 | `WARN rate limit exceeded: scope=…` | > 100/min cluster-wide | Medium |
| Access denied 폭발 | `WARN admin access denied: …` | > 50/min | Medium |
| ID Token 검증 실패 | `WARN login/complete failed: reason=id-token-verify-failed` | > 10/min | Medium |

## 6. Trouble-shooting matrix

| 보이는 로그 | 가능한 root cause | 다음 액션 |
| --- | --- | --- |
| `WARN api-key auth failed: reason=unknown-prefix` | RP 가 잘못된 prefix 전송 / 키 회수됨 | RP 측 설정 확인 |
| `WARN api-key auth failed: reason=bad-secret` | RP 측 secret 변경 누락 또는 attack | rate, source IP 확인 |
| `WARN signCount did not advance for credential …` | 카드 클론 또는 동기화 이슈 | 해당 credential 회수 권고 |
| `ERROR mds sync failed: cause=SocketTimeoutException` | FIDO MDS 서버 도달 불가 / 방화벽 | 외부 네트워크 점검 |
| `WARN login/complete failed: reason=id-token-verify-failed cause=…iss…` | passkey-app issuer-base config 불일치 | passkey-app `--passkey.id-token.issuer-base` 와 rp-app `PASSKEY_ISSUER_BASE` 동기 |
| `WARN login/complete failed: reason=id-token-verify-failed cause=…expired…` | clock skew 또는 token TTL 너무 짧음 | 서버 시간 동기 확인 |
| `WARN admin login failed: email=… reason=unknown-user` | 잘못된 email 또는 사용자 삭제 | `admin_user` 테이블 확인 |
| `WARN tenant boundary violation` | RP_ADMIN 이 다른 tenant 접근 시도 | actor 확인 → 의도된 경로인지 정책 점검 |
| `WARN signup request skipped: email=… reason=already-admin` | 기존 계정 이메일로 가입 요청 | 정상(열거 방지 응답). 반복되면 계정 소유자 확인 |
| `WARN signup request skipped: email=… reason=already-pending` | 이미 대기 중인 이메일로 재요청 | 정상. 운영자 탭에서 대기 요청 처리 |
| `WARN signup request skipped: email=… reason=pending-cap max=100` | 대기 요청 100건 도달 | 운영자 탭에서 요청 정리(승인/거절) 또는 retention(passkey.retention.signup-request) 대기 |
| `WARN signup request skipped: email=… reason=concurrent-duplicate` | 같은 이메일 동시 요청 | 정상 |

## 7. 환경별 로그 레벨

레벨의 단일 출처는 각 앱의 yml `logging.level` 이다(`logback-spring.xml` 은
패턴·appender 만 정의하고 root 레벨을 `logging.level.root` 에서 읽어 온다).

| 환경 | root | `com.crosscert.passkey` | 메모 |
| --- | --- | --- | --- |
| dev (`application-dev.yml`) | INFO (Boot 기본) | DEBUG | passkey-app·admin-app dev 는 `org.hibernate.SQL=DEBUG` 도 활성(admin-app 은 `local` 도 동일). **rp-app dev 에는 없다.** |
| qa (`application-qa.yml`) | INFO | INFO | rp-app 만 `org.springframework.web=WARN` 추가. |
| prod (`application-prod.yml`) | **WARN** | INFO | 3앱 공통. DEBUG/TRACE 출력 안 됨. |

## License (on-prem 전용)

### 상태 전이 추적

```bash
grep "license state transition" passkey-app.log admin-app.log
```

기대: `VALID -> WARNING`, `WARNING -> NETWORK_GRACE`, `NETWORK_GRACE -> DEAD` 등.

### Heartbeat 실패 사유 집계

```bash
grep "license heartbeat failed" admin-app.log | \
  sed -E 's/.*reason=([^ ]+).*/\1/' | sort | uniq -c
```

흔한 사유:
- `http: ResourceAccessException` — 네트워크 단절
- `status=revoked` — 라이센스 서버가 revoke 알림
- `verify: License signature invalid` — 발급자 키 불일치 (재발급 필요)

### DEAD 상태에서 차단된 요청

```bash
grep "LICENSE_EXPIRED" passkey-app.log admin-app.log
```

### 의심스러운 라이센스 토큰 노출

```bash
grep "<jws-redacted>" *.log
```

`<jws-redacted>` 가 등장하면, 그 라인을 발생시킨 소스 위치를 확인하고 raw 토큰을 로그에 흘리는 코드 경로를 수정한다. SecretMaskingConverter 가 후방 방어로 가린 상태.

### 부팅 라이센스 컨텍스트

```bash
grep -E "license loaded from disk|license state initialized|onprem mode: licensed tenant" admin-app.log | head
```

서비스 시작 시 한 줄씩 나타나야 정상. 부팅 실패면 stderr 에 `LicenseVerificationException` 메시지.

prod 에서 일시적으로 DEBUG 필요 시: 환경 변수
`LOGGING_LEVEL_COM_CROSSCERT_PASSKEY=DEBUG` 로 재기동한다. 이것은 애플리케이션
로거만 내린다 — prod root 가 WARN 이므로 **프레임워크(Spring·Hibernate 등) 로그까지
보려면** `LOGGING_LEVEL_ROOT=DEBUG`(또는 `INFO`)를 함께 준다. 조사 후에는 반드시
원복한다(로그량·PII 노출 위험).
