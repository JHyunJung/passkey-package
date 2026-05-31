# 운영 관측성 (그룹 C) — Design

- **작성일**: 2026-05-31
- **대상**: Crosscert Passkey Platform (core / passkey-app / admin-app)
- **근거 spec**: [2026-05-29-saas-readiness-gap-audit-design.md](2026-05-29-saas-readiness-gap-audit-design.md) §4 P1-2, P1-3, P1-7
- **성격**: 구현 설계. 후속 writing-plans의 입력물.

## 1. 목적 / 범위

SaaS gap audit의 운영 관측성 P1 3건을 하나의 "observability" phase로 묶어 닫는다. 의존 순서: metrics(P1-2)가 토대 → alerting(P1-3) → health(P1-7) 병행.

| ID | 항목 | 현황(조사 기준) |
|---|---|---|
| P1-2 | Metrics 파이프라인 (Prometheus/Micrometer) | actuator만 의존, micrometer-registry-prometheus·custom meter 없음, `/actuator/prometheus` 미노출 |
| P1-3 | Alerting hook | 6개 핵심 보안 이벤트가 log.warn/error로만, dispatcher·ApplicationEventPublisher 없음. `MailSender`(LogMailSender)는 존재 |
| P1-7 | Health check 보강 | **부분 구현** — DB/Redis HealthIndicator는 actuator 자동 등록(Redis는 challenge store+admin session 필수 의존). probes(readiness/liveness) 분리 미설정 |

**원칙**: core에 공통 인프라 추가(전 모듈 상속), 기존 패턴(MailSender, MDC maskEmail, actuator, dev/qa/prod 프로필) 재사용. **신규 스키마 없음.** 테스트는 핵심 행위만(아래 §6).

**범위 밖(deferred)**: P1-4(그룹 D), P2 전체, RP_ADMIN role 게이팅·admin-ui 후속(별개 phase). MDS/license 외부 의존성 HealthIndicator(외부 장애가 인스턴스를 빼는 위험 — 제외).

## 2. P1-2: Metrics 파이프라인

### 2.1 의존성 + 노출
- `core/build.gradle.kts`에 `api("io.micrometer:micrometer-registry-prometheus")` 추가 → passkey-app/admin-app 자동 상속. (버전은 Spring Boot BOM 관리 — 명시 버전 불요.)
- `core/src/main/resources/application-common.yml`의 `management.endpoints.web.exposure.include`를 `health,info,prometheus`로 → **전 프로파일 노출**. 공통 `application` 태그는 각 앱(passkey-app/admin-app)의 application.yml `management.metrics.tags.application`에 모듈명을 두어 메트릭을 앱별로 구분(common에 단일 값 두면 모든 앱이 같은 태그라 구분 불가). 모듈별 값이라 common이 아닌 앱 yml에 배치.
- 보안: `/actuator/prometheus`는 네트워크/인그레스로 보호(앱 레벨 인증 미부착 — 표준 운영 패턴).

### 2.2 자동 메트릭
Micrometer가 의존성만으로 JVM·HTTP(server.requests)·HikariCP·DataSource·Logback 자동 계측. 추가 코드 불요.

### 2.3 커스텀 도메인 메트릭
`MeterRegistry` 생성자 주입 후 결과 분기에서 `Counter.increment()`. (AOP `@Timed`보다 명시적 — result를 코드 분기에서 태깅.)

| 메트릭 | 태그 | 계측 위치 |
|---|---|---|
| `passkey_ceremony_total` | `type`(registration/authentication), `phase`(start/finish), `result`(success/failure) | Registration/Authentication Start/Finish Service |
| `passkey_apikey_auth_total` | `result`(success/unknown_prefix/revoked/expired/bad_secret/insufficient_scope) | ApiKeyAuthFilter |
| `passkey_mds_sync_total` | `result`(success/failure) | MdsSchedulerService |

**cardinality 규율**: `tenant_id`·`api_key_prefix`·`trace_id`는 **태그 금지**(무한 cardinality → 메모리 폭발). 태그는 enum 유래 저-cardinality 값만. 테넌트별 분석은 로그/audit.

## 3. P1-3: Alerting hook (이벤트 기반 dispatcher)

### 3.1 아키텍처
보안 이벤트 지점 → `ApplicationEventPublisher.publishEvent(SecurityAlertEvent)` → `@Async @EventListener` `AlertDispatcher` 수신 → 등록된 `AlertChannel` 목록으로 fan-out. **이벤트 지점은 dispatcher를 모름**(느슨한 결합). `core`에 `@EnableAsync` 신규 도입(전용 TaskExecutor).

### 3.2 컴포넌트 (전부 core)
- **`SecurityAlertEvent`** (record): `type`(enum: API_KEY_BRUTE_FORCE, COUNTER_REGRESSION, TENANT_BOUNDARY_VIOLATION, ADMIN_LOGIN_FAILURE, MDS_SYNC_FAILURE), `severity`(enum LOW/MEDIUM/HIGH/CRITICAL), `summary`(String), `context`(Map<String,String> — 마스킹된 값만: prefix/maskedEmail/tenantId).
- **`AlertChannel`** 인터페이스: `void send(SecurityAlertEvent)` + `boolean supports(Severity)`(채널별 min-severity 필터).
- **`LogAlertChannel`** (항상 등록, 기본): severity→로그레벨 매핑(HIGH/CRITICAL→error, 그 외 warn), alert 식별 마커(예: SLF4J Marker `SECURITY_ALERT`).
- **`MailAlertChannel`** (`passkey.alert.mail.enabled=true`일 때만 빈 등록): 기존 `MailSender` 재사용, `passkey.alert.mail.to` 주소로. 기본 min-severity HIGH.
- **`AlertDispatcher`**: `List<AlertChannel>` 주입, `@Async @EventListener`로 수신 → `supports(event.severity())` 통과 채널에 **per-channel try/catch** fan-out(한 채널 실패가 다른 채널·요청 흐름 차단 안 함).

### 3.3 발행 지점 (각 log.warn/error 옆에 publishEvent 한 줄 추가, log는 유지)
- `ApiKeyAuthFilter` — auth failed(brute force). API_KEY_BRUTE_FORCE/MEDIUM.
- `AuthenticationFinishService` — signCount 미증가(clone). COUNTER_REGRESSION/HIGH.
- `TenantBoundary` — 경계 위반. TENANT_BOUNDARY_VIOLATION/CRITICAL.
- `AdminSecurityConfig` failureHandler — admin login 실패. ADMIN_LOGIN_FAILURE/MEDIUM.
- `MdsSchedulerService` — sync 실패. MDS_SYNC_FAILURE/HIGH.

**주의**: ApiKeyAuthFilter는 인증 전 컨텍스트 — 이벤트에 필요한 값(prefix 등)을 다 담아 전달, 핸들러가 TenantContext에 의존하지 않게. `@Async` executor는 별도 풀, rejection은 abort+로그(요청 스레드 보호).

### 3.4 throttle (훅만, YAGNI)
brute force는 매 실패마다 이벤트라 폭주 가능. 우선 발행하고, throttle(같은 type+key 윈도우당 1회)은 **channel 책임**으로 두되 이번엔 미구현 — 설계에 자리만. (LogAlertChannel은 로그라 무해, MailAlertChannel은 min-severity HIGH라 MEDIUM brute-force는 메일 안 감 → 실질 폭주 완화.)

### 3.5 설정
`passkey.alert.mail.enabled`(기본 false), `passkey.alert.mail.to`, 채널 `min-severity`. 기본 LogAlertChannel만 활성.

## 4. P1-7: Health 보강 (대부분 설정)

- `core/application-common.yml`에 `management.endpoint.health.probes.enabled: true` → `/actuator/health/readiness`·`/liveness` 활성(K8s 대응).
- `management.endpoint.health.group.readiness.include: readinessState,db,redis` — readiness에 DB·Redis 포함(둘 다 필수 의존). `group.liveness.include: livenessState`(외부/필수 의존성 제외 — 일시 장애로 파드가 죽지 않게).
- DB/Redis HealthIndicator는 actuator 자동 등록 → **신규 코드 없음**, 그룹핑 설정만. (LicenseHealthIndicator는 on-prem 조건부 유지.)
- 동작 확인은 수동 step(§6에서 테스트 생략).

## 5. 에러 처리 / 보안 경계

- **metrics cardinality**: tenant/prefix/trace 태그 금지. 유한 enum 차원만.
- **alert 비동기 실패 격리**: dispatcher per-channel try/catch. `@Async` 별도 풀, rejection abort+로그(요청 스레드 보호). 발송 실패가 비즈니스 흐름에 전파 안 됨.
- **민감정보**: `SecurityAlertEvent.context`엔 마스킹 값만(기존 `maskEmail` 재사용). 평문 secret/전체 이메일 금지.
- **prometheus 노출**: 네트워크/인그레스 보호. 앱 레벨 인증 미부착(명시).
- **health probes**: liveness에 외부/필수 의존성 제외(외부 장애로 liveness 실패→무한 재시작 방지). readiness만 DB/Redis 포함.

## 6. 테스트 전략 (최소화 — 핵심 행위만)

개발 속도 우선: 행위 검증에 꼭 필요한 핵심 단위 테스트만. 자동 인프라·단순 위임은 생략.

**쓸 테스트**:
- `AlertDispatcher` — fan-out + **per-channel 실패 격리**(한 채널 throw가 다른 채널·흐름 안 막음) + severity 필터. (핵심 로직, 필수.)
- `AlertChannel` 구현 — 한 테스트로 묶어 severity 필터 + mail off일 때 미발송. (레벨 매핑 등 사소한 건 생략.)
- 도메인 메트릭 — `SimpleMeterRegistry`로 대표 1~2개(ceremony 성공/실패 분기, API key result 분기). 모든 result 값 일일이 검증 안 함.

**생략(속도)**:
- P1-7 health probes/그룹핑 — actuator 자동 기능, 설정만 + 수동 확인.
- `/actuator/prometheus` 노출 슬라이스 — Micrometer 자동, 메트릭 단위 테스트로 충분.
- 이벤트 발행→수신 통합 — dispatcher 단위 테스트가 핵심 커버, 발행 지점은 컴파일+대표 1개.
- 계측 지점마다 테스트 — 대표만(동일 패턴).

**회귀**: 기존 ceremony/필터/login 테스트가 `MeterRegistry`/`ApplicationEventPublisher` 주입으로 깨지면 그것만 갱신(`SimpleMeterRegistry`/mock publisher).

**게이트**: 전체 컴파일 + 기존 테스트 green + 위 핵심 단위. Oracle/Redis IT는 환경상 flaky → core 전체 + 단위/슬라이스로(그룹 A·B 정책).

## 7. 커밋 전 게이트

메모리 지침: 커밋 전 `/codex review`(6/1 quota 리셋 후 누적 diff) + code quality subagent. 특히 alert 비동기 실패 격리, metrics cardinality, 민감정보 마스킹.

## 8. 구현 순서(권장)

1. P1-2: micrometer 의존성 + 노출 설정 + 도메인 메트릭 계측(ceremony/apikey/mds).
2. P1-3: alert 인프라(event/channel/dispatcher + @EnableAsync) → 발행 지점 5곳 연결.
3. P1-7: health probes/그룹 설정.
4. 통합 검증 + followups.
