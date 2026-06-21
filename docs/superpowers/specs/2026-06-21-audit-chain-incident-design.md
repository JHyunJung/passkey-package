# Audit Chain Incident 생성 — 설계 문서

**작성일:** 2026-06-21
**대상:** admin-app (백엔드) + admin-ui (프론트엔드) + core (alert)
**상태:** 승인됨 → 구현 계획 대기

## 1. 배경 / 문제

Audit Chain 화면(`admin-ui/src/pages/AuditChainPage.tsx`)은 테넌트별 audit_log
hash chain을 검증해 위변조(tampered)를 감지한다. 위변조가 감지되면 빨간 경고
카드와 함께 **"Incident 생성" 버튼**(`AuditChainPage.tsx:250`)이 표시되지만,
현재 `disabled` + `title="향후 지원 예정"` 인 미구현 placeholder다.

현재 위변조 감지 시 일어나는 일:
- 백엔드 `AuditChainVerifier`가 `tamperedEntryId`(첫 깨진 audit_log 행)를 반환
- 화면에 휘발성 경고 카드 표시 (새로고침하면 그 순간의 검증 결과일 뿐)

**빈틈:** "감지"는 되지만 "추적·관리"가 안 된다. 위변조는 가장 심각한 보안
이벤트(감사 증거 자체의 무결성 붕괴)인데, 누가 인지했고 어떻게 처리했는지가
시스템에 남지 않는다. compliance 대응상 이 추적이 특히 중요하다.

**목표:** 일회성 감지를 "열고 → 조사하고 → 닫는" 추적 가능한 정식 incident로
등록·관리하는 기능을 구현한다.

## 2. 확정된 요구사항

| 항목 | 결정 |
|---|---|
| 상태 워크플로 | `OPEN → RESOLVED` 2단계 |
| 관리 위치 | AuditChainPage 내 "Incident" 섹션 (목록/상세/해결) |
| 중복 방지 | **테넌트당 OPEN 1건** (DB 부분 유니크 인덱스로 강제) |
| 권한 | `PLATFORM_OPERATOR` (기존 audit chain 화면과 동일) |
| 해결 입력 | 해결 메모(필수) + resolvedBy/resolvedAt 자동 기록 |
| 생성 시 알림 | CRITICAL `SecurityAlertEvent` 자동 발생 (기존 AlertDispatcher 재사용) |
| 저장 방식 | **전용 `security_incident` 테이블 신설** (audit_log 재활용 불가 — append-only라 상태 변경 불가능) |

## 3. 데이터 모델 — `security_incident` 테이블 (V50)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | RAW(16) PK | UUID (기존 패턴) |
| `tenant_id` | RAW(16) NOT NULL | 위변조가 감지된 테넌트 |
| `tampered_entry_id` | RAW(16) NULL | 깨진 audit_log 행 (증거 박제, 생성 시점 값) |
| `type` | VARCHAR2(64) NOT NULL | `AUDIT_CHAIN_TAMPER` (향후 확장 대비) |
| `severity` | VARCHAR2(16) NOT NULL | `CRITICAL` |
| `status` | VARCHAR2(16) NOT NULL | `OPEN` / `RESOLVED` |
| `detail` | VARCHAR2(1024) NULL | 생성 시점 스냅샷 JSON (테넌트명, verifiedRows 등) |
| `created_at` | TIMESTAMP WITH TIME ZONE NOT NULL | 생성 시각 (KST, OffsetDateTime 패턴) |
| `created_by` | RAW(16) NOT NULL | 생성한 admin_user |
| `resolved_at` | TIMESTAMP WITH TIME ZONE NULL | 해결 시각 |
| `resolved_by` | RAW(16) NULL | 해결한 admin_user |
| `resolution_note` | VARCHAR2(1024) NULL | 해결 메모 (RESOLVED 시 필수) |

**테넌트당 OPEN 1건 강제 (DB 레벨):**
```sql
CREATE UNIQUE INDEX ux_incident_open_per_tenant
  ON security_incident (CASE WHEN status='OPEN' THEN tenant_id END);
```
함수 기반 부분 유니크 인덱스. status='OPEN'인 행만 tenant_id 유일 → 동시 요청에도
DB가 두 번째를 거부(애플리케이션 레벨 체크보다 안전).

**그랜트:** 기존 패턴대로 `APP_ADMIN`에 SELECT/INSERT/UPDATE 부여. DELETE는
부여하지 않음(incident는 영구 보존, 삭제 안 함).

**VPD:** `security_incident`는 `tenant_id`를 갖지만 **PLATFORM_OPERATOR 전용
플랫폼 테이블**이다. 운영자가 전 테넌트 incident를 봐야 하므로
tenant-격리 VPD 정책을 **걸지 않는다** (audit_log/scheduler_lease와 동일 취급).

## 4. 백엔드 (admin-app + core)

**새 파일:**
1. `SecurityIncident` 엔티티 — 위 테이블 매핑. `OffsetDateTime`(KST),
   `IncidentStatus`/`IncidentType`/`Severity` enum.
2. `SecurityIncidentRepository` — JPA.
   `findAllByOrderByCreatedAtDesc`, `existsByTenantIdAndStatus(id, OPEN)`,
   `findByIdAndStatus`.
3. `SecurityIncidentService` — 트랜잭션 경계.

**`core` 변경:**
- `SecurityAlertEvent.AlertType` enum에 `AUDIT_CHAIN_TAMPERING` 추가
  (현재: API_KEY_BRUTE_FORCE, COUNTER_REGRESSION, TENANT_BOUNDARY_VIOLATION,
  ADMIN_LOGIN_FAILURE, MDS_SYNC_FAILURE).

**`SecurityIncidentService.create(tenantId, tamperedEntryId, actor)`:**
1. **위변조 실재 재검증** — `AuditChainVerifier`로 해당 테넌트가 실제로 tampered
   인지 재확인. 위조된 요청으로 가짜 incident 생성 방지. (아니면 422)
2. 테넌트당 OPEN 1건 선체크 (DB 유니크 인덱스가 최종 방어, 서비스 선체크로
   친절한 409 에러).
3. 스냅샷 `detail` 구성(테넌트명/verifiedRows 등) → `saveAndFlush`.
4. `AuditLogService.append(action="AUDIT_CHAIN_INCIDENT_CREATED",
   targetType="SECURITY_INCIDENT", targetId=incidentId, tenantId, payload)`.
5. `SecurityAlertEvent(AUDIT_CHAIN_TAMPERING, CRITICAL, ...)` publish.

**`SecurityIncidentService.resolve(incidentId, note, actor)`:**
1. OPEN 상태 확인 (아니면 거부).
2. status=RESOLVED, resolvedAt/By/Note 기록.
3. `AuditLogService.append(action="AUDIT_CHAIN_INCIDENT_RESOLVED", ...)`.

**`SecurityIncidentService.list()`** — 전체 목록 (최신순).

**엔드포인트** — `AuditChainMonitorController`에 추가 (매핑 `/admin/api/audit/chain`,
전부 `@PreAuthorize("hasRole('PLATFORM_OPERATOR')")`):
- `GET    /admin/api/audit/chain/incidents` — 목록
- `POST   /admin/api/audit/chain/incidents` — 생성 `{tenantId, tamperedEntryId?}`
- `POST   /admin/api/audit/chain/incidents/{id}/resolve` — 해결 `{note}`

**DTO:** `IncidentView`(목록/상세), `IncidentCreateRequest`,
`IncidentResolveRequest`(`@NotBlank note`).

**에러 처리:**
- OPEN 중복 → 409 Conflict ("이미 진행 중인 incident가 있습니다")
- 실제 tampered 아님 → 422 ("위변조가 확인되지 않습니다")
- 권한 없음 → 기존 403 경로

## 5. 프론트엔드 (admin-ui)

**API 클라이언트** (`api/auditChainMonitor.ts`에 추가):
```ts
type IncidentView = {
  id: string; tenantId: string; tenantName: string;
  tamperedEntryId: string | null;
  type: string; severity: string; status: 'OPEN' | 'RESOLVED';
  detail: string | null; createdAt: string; createdByEmail: string;
  resolvedAt: string | null; resolvedByEmail: string | null; resolutionNote: string | null;
};
auditChainMonitorApi.listIncidents()
auditChainMonitorApi.createIncident(tenantId, tamperedEntryId?)
auditChainMonitorApi.resolveIncident(id, note)
```

**"Incident 생성" 버튼 활성화** (`AuditChainPage.tsx:250`):
- `disabled` 제거, `onClick` → **확인 다이얼로그**(기존 Dialog/월간보고서 모달 패턴).
- 다이얼로그: 위변조 테넌트명·tamperedEntryId 표시 + 안내 → 확인 시 `createIncident()`.
- 성공 → toast + 새로고침. 이미 OPEN(409) → "이미 진행 중" toast.

**새 "Incident" 섹션** (페이지 하단, tenant 목록 카드 아래):
- 테이블: 상태 뱃지(OPEN 빨강/RESOLVED 회색) · 테넌트 · 유형 · 생성시각 · 생성자.
- 행 클릭 → 상세(detail 스냅샷, 생성/해결 메타, 해결 메모).
- OPEN 행 → "해결" 버튼 → 해결 메모 입력 다이얼로그(메모 필수, 빈값이면 비활성) →
  `resolveIncident(id, note)` → toast + 새로고침.
- 빈 상태: "등록된 incident가 없습니다".

**데이터 로딩:** 기존 `load()`(overview)와 함께 `listIncidents()` 호출.

**UI 흐름:** 위변조 감지 → 경고 카드 "Incident 생성" → 확인 다이얼로그 → 생성 →
하단 섹션에 OPEN 표시 → "해결" → 메모 입력 → RESOLVED.

## 6. 테스트 전략

**백엔드:**
- `SecurityIncidentServiceTest` (단위): 생성 성공(저장+audit+alert mock 검증),
  **OPEN 중복 거부(409)**, **실제 tampered 아닌데 생성 거부(422)**, resolve 성공
  (RESOLVED+메타 기록), 이미 RESOLVED 재해결 거부.
- 컨트롤러 통합: 3개 엔드포인트 권한(PLATFORM_OPERATOR만, RP_ADMIN/익명 403),
  생성→목록→해결 happy path, **DB 부분 유니크 인덱스 동작**(Testcontainers Oracle).

**프론트엔드:** 기존 vitest 수준에 맞춤(최소). API 클라이언트 호출 형태,
해결 다이얼로그 "메모 필수" 검증 정도. 과한 UI 테스트 지양.

**검증 게이트:**
- 마이그레이션은 **Testcontainers Oracle 실행 필수**(inspection이 Oracle DDL 함정
  못 잡음 — 실제 실행으로 확인).
- `./gradlew :admin-app:test` + admin-ui `tsc`/`eslint`/`build`.
- 회귀 판정은 base worktree 대조(전체 build는 pre-existing 함정 많음).

## 7. 범위 경계 (YAGNI — 이번에 안 하는 것)

- ❌ 외부 ITSM(Jira/PagerDuty) 연동
- ❌ assignee(담당자 할당) — 워크플로를 OPEN→RESOLVED로 단순화
- ❌ INVESTIGATING/FALSE_POSITIVE 등 추가 상태
- ❌ incident 삭제 (영구 보존)
- ❌ audit chain 외 다른 유형 — `type` 컬럼은 확장 대비로 두되 지금은
  `AUDIT_CHAIN_TAMPER`만

## 8. 구현 순서

1. 마이그레이션(V50) + 엔티티 + 리포지토리 → Testcontainers DDL 검증
2. 서비스 + alert 타입 추가 + 단위 테스트
3. 컨트롤러 + DTO + 권한 테스트
4. 프론트 API 클라이언트 + 버튼 활성화 + Incident 섹션
5. 통합 검증 (실DB 생성→해결 e2e, dogfooding)
