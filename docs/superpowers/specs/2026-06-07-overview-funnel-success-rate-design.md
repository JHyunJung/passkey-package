# 개요 탭 등록/인증 성공률 노출 — 설계

날짜: 2026-06-07
상태: 설계 승인 대기

## 1. 문제

테넌트 상세 **개요(Overview) 탭**의 "등록 성공률 (7d)"·"인증 성공률 (7d)" 카드가
항상 `—` 와 "Phase E3 연결 예정"만 표시한다. 실제 성공률이 노출되지 않는다.

### 근본 원인 (코드·DB로 확인됨)

데이터 흐름:

```
passkey-app(등록/인증 ceremony) → audit_log 기록 → admin FunnelService 집계 → 개요 탭 표시
                                  ↑ 여기가 끊겨 있음
```

1. 개요 탭(`admin-ui/src/pages/tenant/TenantOverview.tsx:225-226`)은 `funnelApi`로
   받은 `attempts`가 0이면 `—` + "Phase E3 연결 예정"을 표시한다(의도된 폴백).
2. `FunnelService`(admin-app)는 `audit_log`에서 다음 4개 action을 카운트한다:
   `REGISTRATION_BEGIN`, `REGISTRATION_FINISH_OK`,
   `AUTHENTICATION_BEGIN`, `AUTHENTICATION_FINISH_OK`.
   코드 주석에 *"not yet emitted by passkey-app (out of F3 scope) — dev DB will
   return 0 counts"* 라고 명시돼 있다.
3. passkey-app의 `RegistrationFinishService` / `AuthenticationFinishService` 등에는
   audit 기록 코드가 **아예 없다**(audit-chain infra는 admin-app에만 존재 —
   `CredentialSelfService` 주석으로 확인). dev DB의 `audit_log`를 확인한 결과
   funnel action은 **0건**(존재하는 건 ADMIN_LOGIN, TENANT_CREATE 등 admin/시스템
   action뿐).

→ 표시 버그가 아니라 **데이터 파이프라인 미연결(미구현)**. 등록/인증이 실제로
일어나도 funnel용 이벤트가 기록되지 않아 성공률이 영원히 `—`로 표시된다.

## 2. 해결 방향 (결정 사항)

- **근본 해결**: passkey-app이 ceremony 이벤트를 기록하도록 구현한다.
- **별도 경량 이벤트 테이블** `ceremony_event`를 쓴다. 기존 `audit_log` hash chain
  재사용 안 함.
  - 이유: `audit_log`는 전역 단일 락(`AUDIT_CHAIN_LOCK`, `SELECT … FOR UPDATE`)으로
    모든 append를 직렬화한다. admin의 저빈도 이벤트엔 적합하지만, 등록/인증은
    **고빈도 ceremony**라 모든 ceremony가 전역 락을 경쟁하면 심각한 병목이 된다.
    funnel은 단순 카운트 지표라 hash chain 무결성 보증이 불필요하다.
- 검증은 **통합테스트 중심**.

## 3. 아키텍처

```
[passkey-app ceremony]
  RegistrationStartService.start()     → ceremony_event INSERT (REGISTRATION_BEGIN)
  RegistrationFinishService.finish()   → ceremony_event INSERT (REGISTRATION_FINISH_OK)
  AuthenticationStartService.start()   → ceremony_event INSERT (AUTHENTICATION_BEGIN)
  AuthenticationFinishService.finish() → ceremony_event INSERT (AUTHENTICATION_FINISH_OK)
           ↓
[ceremony_event]  (tenant_id, action, created_at) — hash chain 없음, 단순 append
           ↓
[admin FunnelService]  데이터 소스를 audit_log → ceremony_event 로 교체
           ↓
[개요 탭]  attempts > 0 → 실제 성공률 표시
```

핵심 원칙:
- `ceremony_event`는 hash chain 없는 단순 카운트 테이블 → 전역 락 없이 고빈도 INSERT 안전.
- 이벤트 기록은 **best-effort**: 기록 실패가 ceremony를 깨면 안 된다(등록/인증은 절대
  차단하지 않음). try-catch로 격리, 실패 시 로그만.
- begin/finish를 모두 기록해야 attempts(분모)·success(분자)가 잡혀 "성공률"이 계산된다.

## 4. 데이터 모델

### 4.1 테이블 (Flyway `V41__ceremony_event.sql`, core, APP_OWNER 스키마)

```sql
CREATE TABLE ceremony_event (
  id          RAW(16)        DEFAULT SYS_GUID() PRIMARY KEY,
  tenant_id   RAW(16)        NOT NULL,
  action      VARCHAR2(32)   NOT NULL,
  created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_ceremony_event_tenant_action_time
  ON ceremony_event (tenant_id, action, created_at);
```

- hash chain 컬럼 없음.
- 복합 인덱스가 FunnelService의 `(tenant_id, action, created_at >= since)` 카운트/
  일별집계 쿼리를 커버.
- 마이그레이션은 idempotent 가드 패턴(기존 V-스크립트 관례)을 따른다.

### 4.2 엔티티 / 리포지토리 (core)

- `CeremonyEvent` 엔티티 — `BaseEntity` 상속, `tenantId`/`action`/`createdAt`.
- `CeremonyEventRepository` — FunnelService가 쓰는 3개 쿼리를 `audit_log`에서 그대로
  옮겨온 형태로 제공:
  - `countByTenantIdAndActionAndCreatedAtAfter(tenantId, action, since)`
  - `aggregateDailyByTenantAndActions(tenantId, actions, since)`
  - `aggregateByTenantAndActionsGrouped(tenantId, actions, since)`

## 5. 이벤트 기록 + 집계 변경

### 5.1 기록 컴포넌트 (core)

```java
@Component
class CeremonyEventRecorder {
  void record(UUID tenantId, String action) {
    try {
      repo.save(new CeremonyEvent(tenantId, action, clock.instant()));
    } catch (Exception e) {
      log.warn("ceremony_event 기록 실패 (무시): tenant={} action={}", tenantId, action, e);
    }
  }
}
```

- **best-effort**: 예외를 삼켜 ceremony를 깨지 않는다.
- 4개 서비스에 `recorder.record(tenantId, ACTION)` 한 줄씩 추가:
  - `RegistrationStartService.start()` → `REGISTRATION_BEGIN`
  - `RegistrationFinishService.finish()` (성공 직후) → `REGISTRATION_FINISH_OK`
  - `AuthenticationStartService.start()` → `AUTHENTICATION_BEGIN`
  - `AuthenticationFinishService.finish()` (성공 직후) → `AUTHENTICATION_FINISH_OK`
- begin은 start 진입 직후(attempts), finish_ok는 성공 직후(success)에 기록. AAGUID
  정책 위반 등으로 finish가 실패하면 begin만 남아 성공률이 정확히 떨어진다.
- action 상수의 단일 출처: FunnelService의 기존 상수를 공유하거나 core 상수로 승격해
  recorder·FunnelService가 같은 값을 참조하도록 한다(문자열 불일치 방지).

### 5.2 FunnelService 변경 (admin-app)

- 4개 action 상수는 유지. 데이터 소스만 `AuditLogRepository` → `CeremonyEventRepository`로
  교체.
- "not yet emitted by passkey-app (out of F3 scope)" 주석 제거.

### 5.3 프론트 변경 (admin-ui)

- `TenantOverview.tsx`의 attempts=0 폴백 문구 "Phase E3 연결 예정"을 정확한 표현
  (예: "최근 7일 ceremony 없음")으로 수정.

## 6. 에러 처리

- 이벤트 기록 실패 → 로그만 남기고 ceremony 정상 진행(절대 차단 안 함).
- FunnelService는 attempts=0이면 ratio=0 반환(기존 계약 유지) → 프론트 폴백 표시.

## 7. 테스트 (통합테스트 중심)

- **passkey-app IT**: registration/authentication start+finish 호출 → `ceremony_event`에
  4개 action row가 쌓이는지 검증(기존 `Fido2EndToEndIT` 패턴 활용).
- **admin-app IT**: `ceremony_event`에 시드 INSERT → `FunnelService.compute()`가 올바른
  attempts/success/ratio 반환 검증(기존 funnel IT 패턴).
- **단위**: `CeremonyEventRecorder`가 예외를 삼키는지(best-effort) 검증.
- **엣지**: begin만 있고 finish 없는 경우 성공률이 낮게 계산되는지 검증.

## 7.1 테넌트 격리 확인 항목

- `FunnelService`는 이미 `TenantBoundary.assertCanAccessTenant(tenantId)`로 RP_ADMIN의
  타 테넌트 조회를 막는다. 데이터 소스만 교체하므로 이 앱 레벨 격리는 그대로 유지된다.
- `ceremony_event`는 `tenant_id` 컬럼을 가지지만, 카운트 쿼리가 항상 명시적
  `WHERE tenant_id = ?`를 거치므로 추가 격리 계층은 불필요(audit_log와 동일한 접근).
  VPD on 환경에서 새 테이블이 정책 대상이 되어야 하는지는 구현 시 기존 VPD 정책
  스크립트와 대조해 확인한다(dev는 VPD off라 영향 없음).

## 8. 범위 밖 (Non-goals)

- audit_log hash chain 재사용/이관.
- 인증 시점 AAGUID 정책 적용(별도 주제, 본 작업과 무관).
- Funnel 탭(별도 화면)의 series/byEventType 시각화 개편 — 같은 데이터 소스를 쓰게 되니
  자동으로 채워지지만, UI 개편은 본 설계 범위 밖.
