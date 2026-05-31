# 운영 관측성 (그룹 C) — Follow-ups

- **작성일**: 2026-05-31
- **브랜치**: `worktree-observability-group-c`
- **spec**: [2026-05-31-observability-group-c-design.md](../specs/2026-05-31-observability-group-c-design.md)
- **plan**: [2026-05-31-observability-group-c.md](../plans/2026-05-31-observability-group-c.md)

P1-2(Prometheus metrics) + P1-3(event-based alerting) + P1-7(health probes)를 6개 Task로 마감했다. subagent-driven 실행 + Task별 spec/code-quality 2단계 리뷰. 테스트는 정책대로 최소화(핵심 행위만). 아래는 리뷰에서 **의도적으로 미룬** 항목과 후속 권고다 (deferred-by-design).

---

## 1. alert throttle / aggregation 미구현 (설계상 deferred)

brute-force 같은 고빈도 이벤트는 매 실패마다 `SecurityAlertEvent`를 발행한다(폭주 가능). 현재 완화: ① `MailAlertChannel` min-severity 기본 HIGH라 MEDIUM(brute-force/admin-login)은 메일 안 감 → log-only. ② executor queue(100) 포화 시 logging rejection handler가 드롭을 error 로그로 관측(silent 아님).

- **권고**: 같은 type+key 윈도우당 1회로 dedup하는 throttle은 channel 또는 dispatcher 책임으로 추후 추가. 현재는 훅만(설계 §3.4).

## 2. metrics — tenant 차원 미포함 (cardinality 규율)

`passkey_ceremony_total`/`passkey_apikey_auth_total`/`passkey_mds_sync_total`은 enum 유래 저-cardinality 태그(type/phase/result)만. tenant_id/prefix/trace_id는 무한 cardinality라 태그에서 제외. 테넌트별 분석은 로그/audit로.

- **권고**: 테넌트별 메트릭이 필요하면 별도 제한된 집계(상위 N 테넌트만 등) 설계. 현재 미지원이 정상.

## 3. metrics — client(4xx) vs server(5xx) ceremony 실패 미구분

`passkey_ceremony_total{result=failure}`가 IllegalArgumentException(400, 사용자 잘못)과 IllegalStateException(500, 우리 설정 오류)을 한 버킷에 담는다.

- **권고**: alerting에서 "사용자의 잘못된 assertion"과 "테넌트 설정 깨짐"을 구분하려면 result에 차원 추가 고려. cardinality 결정이라 이번 범위 제외.

## 4. ApiKeyAuthFilter — malformed header 미계측 (의도적)

헤더 자체가 없거나 형식이 틀린 early-reject(line ~110)는 `passkey_apikey_auth_total`에 미계측(spec의 5개 result 값 범위 밖 — "인증 시도조차 아님"). 향후 전체 auth-attempt 회계가 필요하면 `malformed_header` result 추가 가능. 단, 6개 분기가 ~70줄에 흩어져 있어 새 early-return 추가 시 counter 누락 가능성(known limitation) — 7번째 result 추가 시 `unauthorized`/`forbidden` 응답 writer에 단일 choke point로 모으는 리팩터 고려.

## 5. 외부 의존성 HealthIndicator 제외 (의도적)

MDS blob fetch·license heartbeat 같은 외부 HTTP health는 readiness에 넣지 않음. 외부 장애가 인스턴스를 LB에서 빼버리는 위험(특히 liveness에 들어가면 무한 재시작) 때문. readiness=DB+Redis(필수 의존), liveness=livenessState만.

- **권고**: 외부 의존성 상태가 필요하면 별도 비-probe 엔드포인트나 메트릭으로 노출.

## 6. cosmetic / minor (리뷰 관찰, 비차단)

- `AlertConfig`의 rejection handler 로그 메시지가 `queueCapacity=`라고 적었으나 실제로는 `executor.getQueue().size()`(현재 depth) 출력 — 라벨만 약간 오해 소지, 비기능.
- `MeterRegistry`+`ApplicationEventPublisher` pairing이 3개 클래스에 co-occur — 4번째 관측 관심사 생기면 `SecurityObservability` 류 facade 고려(현재 premature, 안 함).
- `MailAlertChannel` to-null short-circuit·HTML esc 단위 테스트 미커버(severity gate는 커버) — low-risk.
- LogAlertChannel double-log(operational log + SECURITY_ALERT 마커 alert log)는 의도 — 마커 라우팅이 실제 appender에 설정돼야 가치 발현. 운영 로깅 설정에서 SECURITY_ALERT 마커 분리 라우팅 권장.

## 7. async e2e 검증 범위

`AlertAsyncWiringTest`가 경량 `AnnotationConfigApplicationContext`(Testcontainers 회피)로 `@Async("alertExecutor")` 경로가 alert- 스레드에서 동작함을 검증. 전체 앱 컨텍스트(@SpringBootTest)에서의 통합은 Oracle IT 환경 제약으로 미검증 — CI에서 재실행 권장.

## 8. codex 독립 리뷰 미실행 (전 Task 공통)

OpenAI usage limit(resets Jun 1)로 `/codex review` 미실행. Task별 spec + code-quality subagent 2단계로 게이트.

- **권고**: 6/1 리셋 후 누적 diff(`07e2e91..HEAD`)에 `/codex review`. 특히 alert 비동기 실패 격리·queue rejection, metrics cardinality, 이벤트 context 마스킹.

## 9. admin-ui / 운영 후속

- Prometheus 스크래핑 설정(`/actuator/prometheus`)·Grafana 대시보드·Alertmanager 규칙은 운영 인프라 영역(코드 밖).
- `passkey.alert.mail.{enabled,to}` 운영 설정으로 메일 채널 활성화 시 발송 주소 결정 필요.

## 10. 검증 환경 메모

신규 마이그레이션 없음. 게이트: **core 94 + passkey-app 48 + admin-app 117 단위/슬라이스 green**(alert 3 + ceremony 메트릭 포함). prometheus 노출·health probes는 actuator 자동 기능이라 단위 테스트 생략(spec §6), 앱 부팅 후 `/actuator/prometheus`·`/actuator/health/{readiness,liveness}` 수동 확인 권장(이 환경에선 미수행). Oracle/Redis `*IT` flaky는 이전 phase와 동일.
