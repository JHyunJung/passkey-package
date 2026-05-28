# Logging Operations Guide

> 3 서버 (admin-app / passkey-app / sample-rp) 의 로그 검색·alert·troubleshooting 가이드.
> 인프라: G1-G4 의 `logback-spring.xml` + MDC 4종 + `SecretMaskingConverter`.

## 1. 로그 포맷

```
HH:mm:ss.SSS LEVEL [traceId] [tenantId] [actorEmail] [apiKeyPrefix] logger - msg
```

각 `[…]` 칸:
- **traceId** — 한 HTTP 요청의 식별자. cross-server 추적 가능 (`X-Trace-Id` 헤더로 propagate).
- **tenantId** — passkey-app 의 `ApiKeyAuthFilter` 가 인증 성공 시 set (UUID).
- **actorEmail** — admin-app 의 로그인 사용자 email.
- **apiKeyPrefix** — passkey-app 의 `X-API-Key` 헤더 앞 11자 (`pk_xxxxxxxx`).
- 빈 칸은 `[]` 그대로 — 그 컨텍스트에서는 의미 없는 정보.

`msg` 는 logfmt 형식: `event: key=val key=val`.

## 2. 검색 cookbook

### 한 사용자 트랜잭션 추적
```bash
grep '9f3a-2b1' *.log
# 또는 ELK
traceId: "9f3a-2b1"
```
sample-rp 의 `register/options` → passkey-app 의 `registration/start` → … 가 한 trace 로 정렬됨.

### tenant 한정 이벤트
```bash
grep '\[7f00dead-' *.log
# ELK
traceId AND tenantId: "7f00dead-…"
```

### 관리자 활동
```bash
grep '\[alice@crosscert.com\]' *.log
# ELK
actorEmail: "alice@crosscert.com"
```

### 특정 API key 사용 추적
```bash
grep '\[pk_devacme0\]' *.log
# ELK
apiKeyPrefix: "pk_devacme0"
```

### 보안 경보 패턴
```bash
grep -E 'WARN.*(api-key auth failed|signCount did not advance|tenant boundary violation|invitation (expired|used)|admin access denied)' *.log
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
1. `LogRedact` 헬퍼 — 개발자가 직접 호출 (소스 컨벤션).
2. `SecretMaskingConverter` — logback 출력 시점 자동 마스킹 (defense-in-depth, 실수 방어).

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
| `WARN login/complete failed: reason=id-token-verify-failed cause=…iss…` | passkey-app issuer-base config 불일치 | passkey-app `--passkey.id-token.issuer-base` 와 sample-rp `PASSKEY_ISSUER_BASE` 동기 |
| `WARN login/complete failed: reason=id-token-verify-failed cause=…expired…` | clock skew 또는 token TTL 너무 짧음 | 서버 시간 동기 확인 |
| `WARN admin login failed: email=… reason=unknown-user` | 잘못된 email 또는 사용자 삭제 | `admin_user` 테이블 확인 |
| `WARN tenant boundary violation` | RP_ADMIN 이 다른 tenant 접근 시도 | actor 확인 → 의도된 경로인지 정책 점검 |
| `WARN invitation expired` | 24h TTL 초과 | 재초대 발급 |
| `WARN invitation used` | 토큰 재사용 시도 | 즉시 `admin_user` 상태 + `audit_log` 검사 |
| `WARN invitation lookup failed: reason=not-found` | 잘못된 토큰 또는 만료 후 삭제 | 발급 이력 확인 |

## 7. 환경별 로그 레벨

| 환경 | root | `com.crosscert.passkey` | 메모 |
| --- | --- | --- | --- |
| dev (`application-dev.yml`) | INFO | DEBUG | `hibernate.SQL=DEBUG` 도 활성. 로컬 디버깅용. |
| prod (override 없음) | INFO | INFO | DEBUG/TRACE 출력 안 됨. |

prod 에서 일시적으로 DEBUG 필요 시: 환경 변수 `LOGGING_LEVEL_COM_CROSSCERT_PASSKEY=DEBUG` 로 재기동.
