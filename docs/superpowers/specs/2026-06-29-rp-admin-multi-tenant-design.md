# RP_ADMIN 다중 RP 운영 (운영자 ↔ RP N:M 전환) 설계

- 작성일: 2026-06-29
- 상태: 설계 승인 → 구현 계획 작성 대기
- 관련 메모리: `project_flyway_squash_done`(baseline 직접 수정 가능), `project_vpd_removed_complete`(격리는 앱 레벨 TenantBoundary + Hibernate @Filter 단독), `project_local_admin_boot_rpadmin`(RP_ADMIN 시드)

## 1. 배경 / 문제

현재 운영자(`admin_user`)와 RP(`tenant`)는 **운영자→RP 방향 1:1**로 묶여 있다.

- `admin_user.tenant_id` 단일 컬럼 (RP_ADMIN은 NOT NULL, PLATFORM_OPERATOR는 NULL)
- DB 제약: `fk_admin_user_tenant`(FK → tenant.id), `ck_admin_user_role_tenant`(role=RP_ADMIN ⟺ tenant_id NOT NULL)
- 이메일 전역 UNIQUE: `uq_admin_user_email`
- 결과 카디널리티: 1 RP_ADMIN : 1 tenant / 1 tenant : N RP_ADMIN (반대 방향은 이미 N:1)

**불편한 점 (사용자 확인):**
1. 한 담당자가 여러 RP를 한 계정으로 운영하고 싶은데, RP마다 별도 이메일 계정을 만들어야 한다.
2. 이메일 전역 UNIQUE 때문에 같은 사람을 여러 RP 운영자로 등록할 수 없다.

두 불편은 같은 원인의 양면이다: `admin_user` 한 행이 운영자 1명 + tenant 1개로 강결합되어 있다.

## 2. 목표 / 비목표

**목표**
- 한 명의 운영자(이메일 1개)가 여러 RP를 운영할 수 있게 한다 (운영자↔RP **N:M**).
- 로그인 후 상단 **RP 스위처**로 한 번에 하나의 RP 컨텍스트만 보며 작업한다.
- 멀티테넌트 격리 강도(현행 TenantBoundary + Hibernate @Filter)를 그대로 유지한다.

**비목표 (YAGNI)**
- RP별 권한 차등 — 한 운영자가 가진 모든 RP에서 **동일한 RP_ADMIN** 권한. 조인 테이블에 role 컬럼 두지 않음.
- RP_ADMIN의 자체 운영자 초대 — 부여/회수는 **PLATFORM_OPERATOR만**.
- 여러 RP 통합(cross-RP aggregate) 화면 — 스위처로 단일 RP 컨텍스트만.
- 운영 데이터 마이그레이션 — 운영DB가 없으므로 baseline을 직접 수정해 처음부터 N:M로 설계 (과도기 컬럼 없음).

## 3. 데이터 모델

`admin_user.tenant_id` 단일 컬럼을 제거하고 조인 테이블로 N:M을 표현한다.

```
admin_user (운영자)
  ├─ id, email(UNIQUE 유지), role, bcrypt_hash, enabled, status, mfa_enabled, ...
  └─ tenant_id 컬럼 제거                          ← 핵심 변경

admin_user_tenant (신규 조인 테이블)
  ├─ admin_user_id  RAW(16)  FK → admin_user.id (ON DELETE CASCADE)
  ├─ tenant_id      RAW(16)  FK → tenant.id
  ├─ PK (admin_user_id, tenant_id)                ← 동일 페어 중복 방지
  ├─ created_at     TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
  └─ created_by     VARCHAR2(255)

tenant (RP) — 변경 없음
```

**역할별 규칙 (baseline + 앱 레벨 검증으로 재정의):**
- `PLATFORM_OPERATOR`: `admin_user_tenant`에 **행이 없어야 함** → 전체 접근(기존 의미 유지).
- `RP_ADMIN`: `admin_user_tenant`에 **1개 이상** 행. 1개면 현행과 동일, 2개 이상이면 멀티-RP 운영자.

**제거되는 것 (baseline에서):**
- `admin_user.tenant_id` 컬럼
- `fk_admin_user_tenant` FK
- `ck_admin_user_role_tenant` CHECK

> role↔매핑 카디널리티 규칙(PLATFORM_OPERATOR=0행, RP_ADMIN≥1행)은 단일 행 CHECK로 표현할 수 없으므로 DB 제약이 아니라 **앱 레벨 검증**(AdminUserService)으로 강제한다. 이메일 UNIQUE는 유지 → "한 사람 = 한 계정" 보존.

## 4. Tenant 바인딩 & RP 스위처

현재는 로그인 시 `AdminUserDetails.tenantId`(단일 UUID)가 SecurityContext에 박혀 모든 격리 로직이 그것을 참조한다. N:M에서는 **"로그인 정체성"과 "현재 활성 RP"를 분리**한다.

```
AdminUserDetails
  ├─ tenantId(단일 UUID)  →  allowedTenantIds(Set<UUID>)   ← 로그인 시 admin_user_tenant에서 로드
  └─ PLATFORM_OPERATOR면 빈 Set (전체 접근 의미 유지)

활성 RP (스위처)
  └─ 세션 속성 ACTIVE_TENANT_ID 에 "현재 활성 tenant_id" 저장
     - 기본값: allowedTenantIds 중 하나 (안정적 순서, 예: 정렬 후 첫 번째)
     - 스위처 변경 시 세션 갱신
```

**TenantBoundary 변경:**

```
assertCanAccessTenant(tenantId)
  PLATFORM_OPERATOR → 통과 (기존과 동일)
  RP_ADMIN          → allowedTenantIds.contains(tenantId) 이면 통과, 아니면 ACCESS_DENIED
                       (기존: me.getTenantId().equals(tenantId))

currentTenantScope()  // 목록 조회 필터
  PLATFORM_OPERATOR → Optional.empty()  (전체)
  RP_ADMIN          → Optional.of(activeTenantId)   ← 현재 활성 RP만
```

**TenantContextAdminFilter** (Hibernate @Filter 방어선): RP_ADMIN이면 `activeTenantId`를 `TenantContextHolder`에 set → ORM 레벨에서도 "지금 보고 있는 하나의 RP"만 노출. PLATFORM_OPERATOR는 set하지 않음(전체). 격리 강도 현행 유지.

**스위처 API:** `POST /admin/api/active-tenant { tenantId }`
- 요청한 tenantId가 `allowedTenantIds`에 포함되는지 검증 → 아니면 `ACCESS_DENIED`.
- 통과 시 세션 `ACTIVE_TENANT_ID` 갱신.
- `allowedTenantIds`가 1개뿐인 운영자는 스위처 미노출 + 그 RP 자동 활성.

**안전장치:** 활성 RP는 항상 `allowedTenantIds` 범위 안에서만 선택 가능 → 세션 변조로 권한 밖 RP 열람 차단.

## 5. 부여 API (PLATFORM_OPERATOR 전용)

모두 `@PreAuthorize("hasRole('PLATFORM_OPERATOR')")`.

**초대 (invite) — 복수 tenant 지원:**
```
InviteRequest { email, role, tenantIds: List<UUID> }   ← 단수 tenantId → 복수 tenantIds
  role=RP_ADMIN           → tenantIds 1개 이상 필수, admin_user_tenant에 N행 INSERT
  role=PLATFORM_OPERATOR  → tenantIds 비어 있어야 함 (기존 검증 유지)
```

**매핑 추가/회수 (기존 운영자):**
```
POST   /admin/api/admin-users/{id}/tenants            { tenantId }   → 매핑 1건 추가
DELETE /admin/api/admin-users/{id}/tenants/{tenantId}                 → 매핑 1건 회수
  - RP_ADMIN의 마지막 매핑 회수는 차단 (고아 RP_ADMIN 방지)
  - 중복 추가(이미 있는 페어)는 멱등 무시
```

**감사:** 매핑 추가/회수는 `audit_log`에 기록 (운영자 초대 감사와 동일 패턴).

## 6. UI (admin-ui)

1. **운영자 관리 화면** — 초대/상세에서 tenant **다중 선택**(멀티셀렉트/체크박스). 운영자 목록에 "운영 RP 수" 또는 RP 칩 표시.
2. **상단 RP 스위처** — 로그인한 RP_ADMIN의 `allowedTenantIds`가
   - 1개: 스위처 숨김, 그 RP 자동 활성 (현행 경험 동일).
   - 2개 이상: 헤더 드롭다운 노출, 선택 시 `POST /admin/api/active-tenant` 호출 후 데이터 리프레시.
3. **PLATFORM_OPERATOR 화면** — 변화 없음 (전체 보기 유지).

## 7. 에러 처리

| 상황 | 처리 |
|------|------|
| RP_ADMIN인데 초대 tenantIds 비어 있음 | `IllegalArgumentException` "RP_ADMIN requires at least one tenant" |
| PLATFORM_OPERATOR인데 tenantIds 있음 | 기존 검증 유지 "PLATFORM_OPERATOR must not have tenant" |
| 스위처가 allowedTenantIds 밖 tenant 요청 | `ACCESS_DENIED` (세션 변조 방어) |
| RP_ADMIN 마지막 매핑 회수 시도 | `BusinessException` "cannot remove last tenant of RP_ADMIN" |
| 중복 매핑 추가 (이미 있는 페어) | 멱등 무시 |

## 8. 시드 변경 (운영DB 없음, 깨끗하게)

- `R__seed_dev_tenant.sql` bob: `admin_user.tenant_id` 직접 INSERT → `admin_user_tenant`에 (bob, dev-passkey) 행 INSERT.
- `V9001__test_seed_operators.sql` 및 `AdminFlowIT.resetState()`의 bob RP_ADMIN 전환 로직 → 매핑 테이블 기준으로 수정.
- alice(PLATFORM_OPERATOR): 매핑 행 없음 — 변화 없음.

## 9. 테스트 전략

멀티테넌시 격리가 핵심이라 회귀 위험이 크다.

1. **단위 — TenantBoundary**: allowedTenantIds 안/밖 tenant 접근, 멀티-RP 운영자의 활성 RP 전환, PLATFORM_OPERATOR 무제한.
2. **단위 — 부여 검증**: RP_ADMIN 빈 tenantIds 거부, 마지막 회수 차단, 중복 멱등.
3. **통합(IT)**: bob을 2개 RP 운영자로 만들고 ① 스위처 전환 시 목록 변화 ② 권한 밖 RP 직접 접근 차단 ③ Hibernate @Filter가 활성 RP만 노출.
4. **회귀 기준**: `tenant_id` 컬럼 제거로 기존 슬라이스 테스트가 깨질 수 있으니 base worktree 대조로 pre-existing과 구분 (`project_full_build_preexisting_traps` 패턴).

## 10. 영향 범위

- **DB**: `core/.../db/migration/V1__baseline_schema.sql` (admin_user.tenant_id·FK·CHECK 제거 + `admin_user_tenant` 신규).
- **core**: `AdminUser` 엔티티(tenant_id 제거 + 매핑), 신규 매핑 엔티티/리포지토리.
- **admin-app**: `AdminUserDetails`, `AdminUserDetailsService`, `TenantBoundary`, `TenantContextAdminFilter`, `AdminUserService`, `AdminUserController`, 신규 활성-tenant API/컨트롤러.
- **admin-ui**: 다중 tenant 선택 UI, RP 스위처.
- **시드/테스트**: `R__seed_dev_tenant.sql`, `V9001__test_seed_operators.sql`, `AdminFlowIT`, 관련 슬라이스/단위 테스트.

## 11. 미해결 / 후속 (이번 범위 밖)

- RP_ADMIN 자체 운영자 초대 (권한 위임) — 필요 시 별도 설계.
- 여러 RP 통합 대시보드 — 스위처로 충분, 요청 시 재검토.
