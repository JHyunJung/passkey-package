# Dev/Prod Logging — 3-Server Observability Design

> 어드민/Passkey/RP 3 서버에 개발·운영에 필요한 로그를 일관되게 도입한다.
> admin-ui 디자인은 영향 없음. 인프라(MDC + RequestLoggingFilter + logback-spring.xml)는 한 번만 깔고, 이후 phase 에서 비즈니스/보안 이벤트 로그를 추가한다.
> dev/prod 모두 동일 console PatternLayout (사용자 결정). JSON appender 의존성 추가 없음.

---

## Phase 구분

| Phase | 주제 | 핵심 산출물 |
| --- | --- | --- |
| **G1** | 공통 인프라 | logback-spring.xml, RequestLoggingFilter, MDC 키 5개, SDK TraceIdPropagationInterceptor |
| **G2** | 비즈니스 이벤트 | 12 service / filter / controller 에 INFO/WARN 로그 ~35개 |
| **G3** | 외부 호출 + 보안 | MDS / SDK / IdToken / admin auth / VPD / rate limit 로그 ~25개 |
| **G4** | redaction + 가이드 | LogRedact 헬퍼, SecretLeakFilter, logging-operations.md |

각 phase 별로 worktree → plan → subagent-driven-development → main merge.

---

## 공통 원칙 (4 phase 모두 적용)

1. **로깅 라이브러리**: SLF4J + logback-classic (Spring Boot 기본). **추가 의존성 0개**.
2. **logback config**: `core/src/main/resources/logback-spring.xml` 한 개 (admin-app/passkey-app 공유) + `sample-rp/src/main/resources/logback-spring.xml` 동일 내용 복사. **profile 분기 없음** — dev/prod 모두 동일 PatternLayout console.
3. **포맷**:
   ```
   %d{HH:mm:ss.SSS} %-5level [%X{traceId:-}] [%X{tenantId:-}] [%X{actorEmail:-}] [%X{apiKeyPrefix:-}] %logger{36} - %msg%n
   ```
   각 MDC 칸은 빈 값이면 `[]` 그대로. logfmt/grep/sed 친화.
4. **MDC 키 5종**:
   | 키 | Set 위치 | Clear 위치 |
   | --- | --- | --- |
   | `traceId` | `TraceIdFilter` (기존) | finally |
   | `tenantId` | passkey-app `ApiKeyAuthFilter` 인증 성공 시 + admin-app `DevTenantHeaderFilter` (기존) | finally |
   | `actorEmail` | admin-app Spring Security success handler | logout/세션 종료 |
   | `apiKeyPrefix` | passkey-app `ApiKeyAuthFilter` (prefix 만, secret 절대 제외) | finally |
   | `userHandle` | passkey-app ceremony service (**DEBUG 레벨일 때만**) | finally |
5. **로그 레벨 규칙**:
   - `INFO` — 정상 비즈니스 이벤트
   - `WARN` — 정상 흐름이지만 주의 (auth 실패, signature regression, sync skipped)
   - `ERROR` — 시스템 오류 + stack trace
   - `DEBUG` — dev 전용. prod 는 application-common.yml 의 `com.crosscert.passkey: INFO` 가 root 차단
6. **메시지 컨벤션**: `event: key1=val key2=val` (logfmt). ID 는 prefix/tail. duration 은 `durMs=N`.
7. **Redaction 절대 금지 목록**: API key secret 부분, JWT/ID Token body, password, bcrypt hash, raw attestation/assertion bytes
8. **traceId 헤더**: `X-Trace-Id` (기존 TraceIdFilter). sample-rp → passkey-app SDK 호출에 propagate.
9. **Per-phase worktree → codex review → --no-ff merge** (F-series 와 동일 패턴).
10. **Autonomous decisions during execution** — 사용자에게 묻지 말고 결정, commit message 에 surface.

---

## Phase G1 — 공통 인프라

### G1.1 logback-spring.xml (× 2)

**위치 1**: `core/src/main/resources/logback-spring.xml` (admin-app/passkey-app 가 core 의존)
**위치 2**: `sample-rp/src/main/resources/logback-spring.xml` (sample-rp 가 core 미의존, 동일 내용 복사)

내용:
```xml
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

각 서버 `application.yml` 의 기존 `logging.level.*`, `logging.pattern.*` 제거.

### G1.2 application.yml — 레벨 정책

`core/src/main/resources/application-common.yml`:
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

`application-dev.yml` (admin-app/passkey-app 각자):
```yaml
logging:
  level:
    com.crosscert.passkey: DEBUG
    org.hibernate.SQL: DEBUG
```

sample-rp 는 `application.yml` 의 기존 `logging:` 블록을 위 정책으로 교체.

### G1.3 RequestLoggingFilter (core + sample-rp)

`core/src/main/java/com/crosscert/passkey/core/api/RequestLoggingFilter.java`:
- `OncePerRequestFilter` 확장
- `TraceIdFilter` 보다 **뒤에 order** (MDC traceId 가 이미 set 된 상태에서 출력)
- request 진입 시 `Instant start` 캡처, response 완료 후 한 줄
- 메시지: `request: method=%s path=%s status=%d durMs=%d`
- 4xx → WARN, 5xx → ERROR, 그 외 → INFO
- exclude path: `/actuator/health`, `/.well-known/jwks.json`
- DEBUG 활성 시 추가: `requestBytes=N responseBytes=M` (body 자체는 미출력)

`sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/RequestLoggingFilter.java` — 동일 내용 (sample-rp 가 core 의존성 없음).

Spring Boot 자동 등록 (`@Component` 또는 `FilterRegistrationBean`).

### G1.4 MDC 확장

**ApiKeyAuthFilter** (passkey-app) — 인증 성공 분기에 추가:
```java
MDC.put("apiKeyPrefix", prefix);
MDC.put("tenantId", row.tenantId().toString());
try { chain.doFilter(req, res); }
finally { MDC.remove("apiKeyPrefix"); MDC.remove("tenantId"); }
```

**AdminSecurityConfig** (admin-app) — `formLogin().successHandler(...)` 에서:
```java
MDC.put("actorEmail", auth.getName());
```
세션 종료 / logout handler 에서 clear.

**DevTenantHeaderFilter** (이미 존재) — 이미 tenantId 컨텍스트 set 중. MDC put 한 줄 추가.

### G1.5 SDK TraceIdPropagationInterceptor

`sdk-java/src/main/java/com/crosscert/passkey/sdk/internal/TraceIdPropagationInterceptor.java`:
- Spring `ClientHttpRequestInterceptor` 구현
- MDC `traceId` 읽어 outgoing `X-Trace-Id` 헤더 추가
- 없으면 헤더 생략
- `PasskeyClient` 의 `RestTemplate` interceptor chain 에 register

### G1.6 SDK RedactingRequestInterceptor 강화

기존 `idToken` 외에 `X-API-Key` 헤더도 redact:
- 첫 11자만 노출, 나머지 `<redacted>`

### G1.7 Acceptance

- 3 서버 부팅 시 dev profile / prod profile 모두 동일 형식 console 라인
- 임의 endpoint 호출 → MDC 4 칸 + RequestLoggingFilter 1 줄
- sample-rp 의 traceId 가 passkey-app 호출에 X-Trace-Id 로 전파됨 (passkey-app 로그에서 동일 traceId 확인)
- API key 헤더 plaintext 전체가 어디서도 안 보임

### G1 산출물 (대략)

- 신규 파일: logback-spring.xml × 2, RequestLoggingFilter × 2, TraceIdPropagationInterceptor × 1 → 5 파일
- 수정 파일: ApiKeyAuthFilter, AdminSecurityConfig, RedactingRequestInterceptor, PasskeyClient, application-common.yml, 각 app application.yml/application-dev.yml → ~8 파일
- 의존성 추가 0
- 로그 호출 추가 0 (인프라만)

---

## Phase G2 — 비즈니스 이벤트 로그

G1 인프라 위에 핵심 비즈니스 이벤트 로그 추가. MDC 가 자동 첨부되므로 메시지는 "그 시점의 추가 사실" 만.

### G2.1 passkey-app Ceremony 4종

**RegistrationStartService**:
- `INFO registration/start: externalUserId=… displayName=…`
- `INFO registration/start: issued ceremonyId=… excludeCount=N timeoutMs=…`

**RegistrationFinishService**:
- 기존 `log.warn` 3건 유지
- `INFO registration/finish: ceremonyId=… credentialId=… aaguid=…`
- `ERROR registration/finish: signature verification failed (ceremonyId=…)`

**AuthenticationStartService**:
- `INFO authentication/start: externalUserId=… allowCount=N` (discoverable 시 externalUserId=null)
- `INFO authentication/start: issued ceremonyId=… timeoutMs=…`

**AuthenticationFinishService** (이미 6건):
- 기존 유지
- `INFO authentication/finish: ceremonyId=… credentialId=… counter=…`
- `WARN authentication/finish: signature counter regression (cred=… prev=… new=…)`
- `INFO id-token issued: sub=… aud=… exp=…` (claims 메타만, JWT body 금지)

### G2.2 passkey-app Security/Auth

**ApiKeyAuthFilter**:
- `WARN api-key auth failed: reason=unknown-prefix prefix=pk_xxx…`
- `WARN api-key auth failed: reason=bad-secret prefix=pk_xxx…`
- `WARN api-key auth failed: reason=revoked prefix=pk_xxx…`
- `WARN api-key auth failed: reason=expired prefix=pk_xxx…`
- `DEBUG api-key auth ok: prefix=pk_xxx… tenantId=…`

**RateLimitFilter**:
- `WARN rate limit exceeded: prefix=pk_xxx… path=… durMs=…`

### G2.3 admin-app Write Actions

audit_log append 라인 옆에 INFO 한 줄 추가:

- **AdminUserService**: `INFO admin invite issued: email=… role=… tenantId=…`, `INFO admin suspended: email=…`, `INFO admin activated: email=…`
- **ApiKeyAdminService**: `INFO api-key issued: prefix=… name=… tenantId=… scopes=[…]`, `WARN api-key revoked: prefix=… reason=…`
- **AaguidPolicyService**: `INFO aaguid policy updated: tenantId=… mode=… entries=N`
- **TenantAdminService**: `INFO tenant created: slug=… id=…`, `INFO tenant updated: id=… changed=[fields]`
- **SecurityPolicyService** (F1): `INFO security policy updated: sessionIdle=… pwMin=… mfa=… corsAllowlistSize=N`
- **CredentialAdminService**: `WARN credential revoked: id=… reason=… tenantId=…`
- **KeyMgmtService**: `INFO key rotation: tenantId=… newKid=… oldKid=…`

### G2.4 admin-app Scheduler

**MdsSchedulerService** (이미 4건):
- 일관화: `INFO mds sync started: scheduledAt=…`
- `INFO mds sync ok: version=… durMs=…` 또는 `WARN mds sync skipped: reason=…` 또는 `ERROR mds sync failed: cause=…`

**KeyExpirationJob**:
- `INFO key expiration tick: candidates=N`
- `WARN key expired: tenantId=… kid=… ageMs=…`

### G2.5 sample-rp User-facing

**WebAuthnController**:
- `INFO register/options: username=… userHandle=…`
- `INFO register/complete: userHandle=… credentialId=…`
- `INFO login/options: username=… (또는 discoverable)`
- `INFO login/complete: user=… (sub=…)` 성공
- `WARN login/complete: iss mismatch expected=… got=…`
- `WARN login/complete: aud mismatch expected=… got=…`
- `WARN login/complete: unknown sub=…`

### G2.6 Acceptance

- 등록 시도: register/options → register/complete 의 INFO 가 같은 traceId 로 연결
- 잘못된 API key 호출: WARN 1 줄 + RequestLoggingFilter 의 401 status
- admin 보안 정책 저장: INFO 1줄 + audit_log row + RequestLoggingFilter 200
- ELK 같은 도구 없이 grep 으로도 단일 traceId 검색이 일관된 사용자 시나리오를 보임

### G2 산출물

- 약 12 파일에 log 호출 추가 (~35 신규 statement)
- 신규 파일 0
- 의존성 0

---

## Phase G3 — 외부 호출 + 보안 로그

### G3.1 MDS 외부 호출 (passkey-app)

**MdsBlobClient**:
- `INFO mds blob fetch: url=… size=… durMs=…`
- `WARN mds blob fetch: signature verify failed (cause=…)`
- `ERROR mds blob fetch: http error status=… url=…`

**MdsSchedulerService** (G2 보강):
- `INFO mds sync: lease acquired (instance=…)`
- `INFO mds sync: lease skipped (held by other instance)`
- `INFO mds sync: entries diff added=N removed=M unchanged=K`

### G3.2 SDK 호출 (sample-rp → passkey-app)

**PasskeyClient.post** 내부:
- `DEBUG sdk call: POST <path> durMs=N status=200`
- `WARN sdk call: POST <path> status=4xx code=Pxxx traceId=…`

### G3.3 ID Token 발급/검증

**IdTokenIssuer** (passkey-app):
- 기존 G2.1 `INFO id-token issued: sub=… aud=… exp=…` + 보강:
- `DEBUG id-token issued: kid=… alg=RS256`

**IdTokenVerifier** (SDK):
- `INFO id-token verified: iss=… sub=… durMs=…`
- `WARN id-token verify failed: reason=signature|expired|iss-mismatch|aud-mismatch traceId=…`

### G3.4 JWKS / Key Rotation

**JwksController**: `DEBUG jwks served: keys=N activeKid=…`
**SigningKeyProvider** (key rotation 발생 시):
- `INFO signing key rotated: oldKid=… newKid=… activeUntil=…`
- `WARN signing key expired: kid=… (no rotation triggered)`

### G3.5 admin-app 로그인/세션

**AdminSecurityConfig** success/failure handler:
- `INFO admin login success: email=… mfaUsed=…`
- `WARN admin login failed: email=… reason=bad-password|user-disabled|unknown-user`
- `INFO admin logout: email=…`

**AccessDeniedHandler**:
- `WARN access denied: email=… path=… role=… requiredRole=…`

**InvitationService**:
- `INFO invitation accepted: email=… tokenPrefix=…`
- `WARN invitation expired: tokenPrefix=…`
- `WARN invitation used: tokenPrefix=…` (재사용)

### G3.6 Tenant Boundary

**TenantBoundary.assertCanAccessTenant**:
- `WARN tenant boundary violation: actor=… requested=… allowed=…`

**CTX_PKG.set_tenant** 실패:
- `ERROR vpd context set failed: tenantId=… cause=…`

### G3.7 Acceptance

- `traceId:xxx` 검색으로 sample-rp → passkey-app → IdTokenVerifier 의 한 인증 사이클이 일렬로 보임
- iss/aud/sig 미스매치 WARN 1 줄 → `reason=` 명시로 root cause 즉시 식별
- MDS 외부 장애: ERROR + stack + `mds_sync_history` row 일치
- admin RBAC 위반: WARN + audit_log deny event 양쪽 매칭

### G3 산출물

- 약 10 파일에 log 호출 추가 (~25 신규 statement)
- 신규 파일 0
- 의존성 0

---

## Phase G4 — Redaction 강화 + 운영 가이드

### G4.1 LogRedact 헬퍼

`core/src/main/java/com/crosscert/passkey/core/logging/LogRedact.java`:
- `apiKey(raw)` → 첫 11자만
- `email(raw)` → `a***@domain`
- `idTail(raw, n)` → 뒤 n 자
- `token(raw)` → `eyJ...…<redacted>`

G2/G3 의 모든 log 호출은 이 헬퍼 통과 후 출력.

### G4.2 SecretLeakFilter

`core/src/main/java/com/crosscert/passkey/core/logging/SecretLeakFilter.java`:
- logback `TurboFilter` 또는 `Filter` 구현
- 메시지에 `(?i)(password|secret|x-api-key:\s*pk_\w{12,})` 패턴 매치 시 `<redacted>` 로 replace
- 모든 log event 당 regex 1회 (overhead 무시)
- defense-in-depth: 개발자 실수 방어막

logback-spring.xml 에 등록:
```xml
<turboFilter class="com.crosscert.passkey.core.logging.SecretLeakFilter"/>
```

(sample-rp 도 core 가 없으므로 같은 클래스 내용 복사)

### G4.3 userHandle MDC 가드

passkey-app ceremony service:
```java
if (log.isDebugEnabled()) {
    MDC.put("userHandle", b64(userHandle));
}
```
INFO 이상에선 userHandle 이 메시지 본문에만 등장, MDC 미사용 → cardinality 폭발 방지.

### G4.4 운영 가이드 문서

`docs/logging-operations.md` (신규):
1. 포맷 reference
2. 검색 cookbook (traceId/tenantId/actorEmail/apiKeyPrefix 기준 grep)
3. 레벨 의미
4. redaction 약속 (절대 안 나오는 필드 명시)
5. alert 추천 규칙 (signature counter regression, MDS sync failed, api-key auth failed 등)
6. trouble-shooting matrix (WARN/ERROR → root cause → 액션)

### G4.5 commit guideline

`docs/logging-conventions.md` (또는 CONTRIBUTING.md 갱신):
1. `log.info("event: key1=val1 ...")` logfmt
2. ID/secret 은 LogRedact 통과 후 출력
3. exception → ERROR + throwable second arg
4. 새 MDC 키 추가 시 운영 가이드 갱신
5. DEBUG 레벨은 dev 전용

### G4.6 검증 IT

`core/src/test/java/com/crosscert/passkey/core/logging/SecretLeakFilterTest.java`:
- `log.info("attempting api key X-API-Key: pk_devacme0longsecretvalue")` 호출 → 캡처된 출력에 `longsecretvalue` 없고 `<redacted>` 있음
- `LogRedact.email("alice@crosscert.com")` → `a***@crosscert.com`
- `LogRedact.apiKey("pk_devacme0longsecretvalue")` → `pk_devacme0`

### G4.7 Acceptance

- 운영자가 `docs/logging-operations.md` 만 보고 traceId 로 트랜잭션 추적 가능
- 개발자 실수로 `log.info("token=" + jwt)` 머지해도 SecretLeakFilter 가 출력 시 redact
- prod 의 logging.level 이 application.yml 만으로 강제 (실수로 DEBUG override 부팅해도 root=INFO 가 우선)

### G4 산출물

- 신규 파일: LogRedact.java, SecretLeakFilter.java (× 2), logging-operations.md, logging-conventions.md, SecretLeakFilterTest.java → 6 파일
- 수정 파일: logback-spring.xml × 2 (TurboFilter 추가)
- 의존성 0

---

## 종합 Acceptance (G1-G4 통과)

1. dev/prod 모두 동일 PatternLayout console 출력
2. 3 서버의 traceId 가 cross-server 로 매칭 (sample-rp → passkey-app SDK 호출)
3. MDC 4종 (traceId/tenantId/actorEmail/apiKeyPrefix) 가 모든 라인에 자동 첨부
4. RequestLoggingFilter 가 모든 HTTP 요청에 대해 한 줄 자동 기록
5. ceremony / api-key auth / admin write / scheduler / 외부 호출 / 보안 이벤트가 INFO/WARN/ERROR 로 명시 기록
6. API key secret / JWT body / password / bcrypt hash 가 어떤 로그에도 노출 안 됨
7. `docs/logging-operations.md` 로 운영자 검색·alert 룰 셋업 가능
8. SecretLeakFilterTest 통과

---

*마지막 점검: Claude (Opus 4.7), 2026-05-29.*
