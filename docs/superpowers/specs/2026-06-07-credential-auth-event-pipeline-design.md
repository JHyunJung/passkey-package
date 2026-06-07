# credential 단위 인증 이벤트 파이프라인 (P1) — 설계

- **작성일**: 2026-06-07
- **상태**: 승인됨 (구현 대기)
- **범위**: `core`, `passkey-app`, `admin-app` (백엔드 한정 — admin-ui 화면은 P2 별도 spec)
- **상위 목표**: admin-ui Credential 탭에서 credential 클릭 시 상세 정보 + 인증 기록을 보는 기능. 이 기능은 P1(백엔드 파이프라인) + P2(admin-ui 화면) 두 sub-project로 분리됨. **본 문서는 P1.**

## 1. 문제

현재 credential 단위 인증 이력을 추적할 데이터가 없다.

- `ceremony_event` (V41) 는 `tenant_id` + `action` 만 보관하는 **집계 전용** 테이블 — credential 참조가 없어 "이 패스키로 언제/몇 번 인증했나"를 알 수 없다.
- `credential.last_used_at` 은 **마지막 1회** 시점만 — 이력이 아니다.
- `audit_log` 는 관리자 행위(`CREDENTIAL_REVOKE`)만 기록 — 인증 행위는 없다.

따라서 P2(admin-ui 기록 탭)가 소비할 "credential별 인증 이벤트" 데이터를 먼저 만들어야 한다.

## 2. 목표 / 비목표

### 목표
- 인증 ceremony 의 성공/실패를 **credential 단위로** 기록하는 경량 이벤트 테이블 추가.
- passkey-app 인증 finish 흐름에서 best-effort 로 기록 (ceremony 자체에 영향 없음).
- admin-app 에서 credential 단위 이벤트를 페이지 조회하는 API 제공.

### 비목표 (YAGNI)
- hash chain 무결성 / 위변조 탐지 — 경량 이벤트로 결정(`ceremony_event` 형).
- credential 미식별 단계의 실패 기록 — 붙일 credential 이 없음(ceremony_event 의 AUTHENTICATION 집계가 커버).
- 무제한 보존 — 기존 `RetentionPurgeJob` 재사용.
- admin-ui 화면(상세 패널·기록 탭) — **P2 별도 spec**.

## 3. 확정된 결정

| 항목 | 결정 | 근거 |
|---|---|---|
| 기록 범위 | 성공 + 실패 모두 | 복제 인증기 탐지·공격 흔적 등 보안 가치 |
| 기록 실패 정책 | best-effort, 비전파 | 기록은 지표용 — 인증 성부를 가르면 안 됨 |
| 보존 | 기존 `RetentionPurgeJob` 편입 | 무제한 증가 방지, 인프라 재사용 |
| 테이블 성격 | 경량 이벤트 (hash chain 없음) | `ceremony_event` 와 동일 철학 |
| credential 참조 | credential PK FK | 조인·무결성 깔끔 |
| 회수(revoke) 시 | `ON DELETE CASCADE` | credential 삭제 시 이벤트도 제거, 일관성 |

## 4. 아키텍처 (데이터 흐름)

```
사용자 인증 → passkey-app AuthenticationFinishService
   ├─ 성공: signCount 갱신 직후 → recordAfterCommit(credentialPk, tenantId, SUCCESS, newSignCount)
   └─ 실패: replay 탐지 / 서명 검증 실패 catch 지점 → recordAfterCommit(..., FAILED, reason)
              ⬇ (afterCommit, REQUIRES_NEW, best-effort)
   core: credential_auth_event 테이블 (FK→credential, ON DELETE CASCADE)
              ⬇
   admin-app: GET .../credentials/{credentialId}/auth-events?page&size
```

## 5. 컴포넌트 (core)

### 5.1 `CredentialAuthEvent` 엔티티
`BaseEntity` 상속 (UUID PK `id`, `createdAt`, `updatedAt`). 추가 컬럼:

| 필드 | 타입 | 설명 |
|---|---|---|
| `credentialId` | UUID (RAW(16), FK→credential.ID) | 대상 credential 의 **내부 PK** (WebAuthn credentialId byte[] 아님) |
| `tenantId` | UUID (RAW(16), not null) | 테넌트 |
| `result` | String (16) | `SUCCESS` / `FAILED` |
| `failureReason` | String (64, nullable) | 실패 시 사유 (예: `SIGN_COUNT_REPLAY`, `SIGNATURE_INVALID`); 성공이면 null |
| `signCount` | long | 검증 시점의 signCount (성공: 갱신된 값; 실패: 관측 값 또는 0) |

hash chain 컬럼 없음. `created_at` 이 이벤트 시각.

### 5.2 V43 Flyway 마이그레이션
- `CREDENTIAL_AUTH_EVENT` 테이블 생성 (위 컬럼). 기존 V41 패턴(idempotent — `CREATE`를 EXCEPTION 으로 감싸거나 존재 검사) 따름.
- FK: `credential_id REFERENCES credential(id) ON DELETE CASCADE`.
- 조회 인덱스: `(credential_id, created_at DESC)` — P2 페이지 조회용.
- retention 인덱스: `(created_at)` — purge 용.
- APP_ADMIN 에 DELETE 권한 (retention purge 가 admin-app 에서 실행됨 — ceremony_event 전례 따름).
- VPD: ceremony_event 와 동일 정책(미적용 또는 동일 predicate). 기존 V41 의 VPD 처리를 그대로 모방.

### 5.3 `CredentialAuthEventRepository`
- `Page<CredentialAuthEvent> findByCredentialIdOrderByCreatedAtDesc(UUID credentialId, Pageable p)` — P2 조회.
- `int deleteCreatedBefore(Instant cutoff, int batchSize)` — retention (ceremony_event 의 동명 메서드 시그니처 모방).

### 5.4 `CredentialAuthEventRecorder`
`CeremonyEventRecorder` 패턴 재사용 — `TransactionTemplate(REQUIRES_NEW)`, 예외 swallow + WARN. **두 메서드를 제공**한다:

```
// 성공: outer 쓰기 트랜잭션이 커밋 확정된 뒤 기록 (afterCommit 동기화)
void recordAfterCommit(UUID credentialPk, UUID tenantId, String result, String failureReason, long signCount)

// 실패: 즉시 독립 커밋 (REQUIRES_NEW). 인증 실패는 보통 예외→outer 롤백이므로
//       afterCommit 콜백이 호출되지 않는다. 실패 이벤트는 롤백과 무관하게 남아야 하므로
//       afterCommit 이 아니라 즉시 기록을 쓴다.
void record(UUID credentialPk, UUID tenantId, String result, String failureReason, long signCount)
```

**핵심 구분**: 성공은 `recordAfterCommit`(커밋 확정 후), 실패는 `record`(즉시, REQUIRES_NEW 독립 커밋). 실패 경로에서 outer 트랜잭션이 롤백돼도 FAILED 이벤트는 독립 커밋되어 보존된다.

## 6. 컴포넌트 (passkey-app)

### 6.1 `AuthenticationFinishService`
- **성공 경로**: signCount 검증·갱신(현 `AuthenticationFinishService` 의 persist 지점, 약 line 234 근처) 직후 → `recordAfterCommit(cred.id, tenantId, "SUCCESS", null, newSignCount)`. outer 커밋이 확정돼야 "성공"이므로 afterCommit.
- **실패 경로 (credential 식별 이후)** — 즉시 `record(...)` 사용(롤백 무관 보존):
  - signCount replay 탐지(`signCount did not advance`, 약 line 222–231) → throw 직전 `record(cred.id, tenantId, "FAILED", "SIGN_COUNT_REPLAY", observedCount)`.
  - 서명/검증 실패 중 credential 이 이미 로드된 단계 → `record(..., "FAILED", "SIGNATURE_INVALID", ...)`.
- credential 을 식별하기 **전** 실패(credentialId 미존재 등)는 기록하지 않음 — 붙일 대상 없음.
- 기록은 best-effort: `record`/`recordAfterCommit` 내부에서 예외 흡수. 실패 경로는 기록 호출(즉시 독립 커밋) 직후 원래 예외를 그대로 전파.

## 7. 컴포넌트 (admin-app)

### 7.1 `CredentialAdminController`
신규 엔드포인트:
```
GET /admin/api/tenants/{tenantId}/credentials/{credentialId}/auth-events?page=0&size=50
```
- `credentialId` 는 기존 revoke 와 동일하게 base64url WebAuthn credentialId. 서비스에서 디코드 → credential 행 조회 → 내부 PK 로 이벤트 조회.
- tenant boundary 검사 재사용(`tenantBoundary.assertCanAccessTenant` + tenantId 일치).
- 권한: 조회이므로 기존 list 와 동일 수준(추가 `@PreAuthorize` 없이 controller 기본).

### 7.2 `CredentialAdminService` + DTO
- `PageView<AuthEventView> listAuthEvents(UUID tenantId, String credentialIdB64, int page, int size)`.
- `AuthEventView(String result, String failureReason, long signCount, Instant createdAt)`.
- credential 없으면 `ENTITY_NOT_FOUND`; cross-tenant 면 `ACCESS_DENIED` (revoke 와 동일 방어).

## 8. 에러 처리

| 상황 | 처리 |
|---|---|
| 이벤트 기록 실패(DB 오류 등) | `recordAfterCommit` 내부 WARN 후 swallow. 인증 응답 정상. |
| credential 미식별 실패 | 기록 안 함(대상 없음). ceremony_event AUTHENTICATION 집계가 커버. |
| 조회 시 credential 없음 | `ENTITY_NOT_FOUND` |
| 조회 시 cross-tenant | `ACCESS_DENIED` |

## 9. 테스트

| 모듈 | 테스트 |
|---|---|
| core | 엔티티 저장/조회 round-trip; FK cascade(credential 삭제 → 이벤트 동반 삭제); `deleteCreatedBefore` retention 삭제; `findByCredentialIdOrderByCreatedAtDesc` 정렬·페이지 |
| passkey-app | 인증 성공 → SUCCESS 이벤트 1건(기존 `Fido2EndToEndIT`/finish 서비스 테스트 패턴); replay 탐지 → FAILED(`SIGN_COUNT_REPLAY`) 기록; 기록 실패해도 인증 성부 불변 |
| admin-app | 조회 API 페이지·정렬; tenant boundary 슬라이스(@WebMvcTest, 기존 credential 슬라이스 패턴 + 신규 협력 빈 MockBean) |

## 10. 변경 파일 (예상)

- `core/.../entity/CredentialAuthEvent.java` (신규)
- `core/.../repository/CredentialAuthEventRepository.java` (신규)
- `core/.../ceremony/` 또는 신규 패키지 `CredentialAuthEventRecorder.java` (신규)
- `core/src/main/resources/db/migration/V43__credential_auth_event.sql` (신규)
- `passkey-app/.../authentication/AuthenticationFinishService.java` (수정)
- `admin-app/.../credential/CredentialAdminController.java` (수정)
- `admin-app/.../credential/CredentialAdminService.java` (수정)
- `admin-app/.../credential/CredentialAdminDto.java` (수정 — AuthEventView 추가)
- admin-app `RetentionPurgeJob` (수정 — credential_auth_event purge 편입)
- 각 모듈 테스트 (신규/수정)

## 11. P2 와의 인터페이스 (다음 sub-project)

P2(admin-ui)는 본 P1 의 산출물을 소비한다:
- `GET .../credentials/{credentialId}/auth-events` → 기록 탭.
- credential 상세 필드(attestationFmt, backupState, publicKey 등)는 P1 범위 밖일 수 있음 — P2 brainstorm 에서 상세 단건 조회 API 필요 여부 결정.
