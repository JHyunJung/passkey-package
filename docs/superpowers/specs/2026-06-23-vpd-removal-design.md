# VPD 멀티테넌트 격리 완전 제거 설계

- 날짜: 2026-06-23
- 상태: 승인됨 (구현 대기)
- 범위: Oracle VPD(Virtual Private Database / DBMS_RLS) 기반 테넌트 격리 코드·설정·마이그레이션·스크립트·테스트 전면 제거

## 1. 배경과 핵심 전제

### 현재 상태 — 이미 절반은 와 있다
- `passkey.vpd.enabled` 기본값은 모든 비-test 프로필에서 **이미 `false`**
  (`core/.../application-common.yml:77`, dev/qa/prod yml 동일).
- VPD off에서 테넌트 격리는 **앱 레벨 Hibernate `@Filter`(`TenantFilterAspect`)** 가
  전담 — 이미 운영 경로다.
- admin-app은 이미 VPD에 의존하지 않고 **명시 `tenantId` 일치 검사**로 격리한다
  (`CredentialAdminService.java:85,114` 주석에 "VPD가 admin-app에서 비활성 —
  tenantId 일치 검사가 cross-tenant 누출 방어의 단일 layer"로 명시됨).

### 핵심 전제: VPD on 경로는 죽은 코드 → 들어내도 격리 무손실
`passkey.vpd.enabled=true` 경로(DB 커널 정책)는 운영에서 쓰이지 않는다. 이번 작업은
그 죽은 경로를 물리적으로 제거하는 것이며, **테넌트 격리 자체는 약해지지 않는다.**

### 제거 후에도 유지되는 격리 계층
- **`TenantContextHolder`** (ThreadLocal) — VPD 전용 아님, 18개 파일이 의존하는 공유 컨텍스트.
- **`TenantFilterAspect` + 엔티티 `@Filter`/`@FilterDef`** — passkey-app 격리의 단일 계층이 됨.
- **admin-app의 명시 `tenantId` 일치 검사** — PLATFORM_OPERATOR cross-tenant는 의도적으로 `@Filter` 미적용.
- **DB 유저 3분할** (APP_OWNER/APP_RUNTIME/APP_ADMIN) — 유지(아래 결정 참조). EXEMPT ACCESS POLICY 권한만 무의미해짐(무해).

## 2. 승인된 결정 사항

1. **범위**: 전부 제거 + 과거 마이그레이션까지 재작성(흔적 0).
2. **DB 유저**: APP_OWNER/APP_RUNTIME/APP_ADMIN 3분할 **유지**. 최소권한 원칙상 가치 있음(테이블별 GRANT, DDL 격리). EXEMPT ACCESS POLICY GRANT만 무의미해짐.
3. **ApiKey 룩업**: VPD 우회용 PL/SQL 패키지 `api_key_lookup_pkg`(AUTHID DEFINER) **제거 → 앱 네이티브 쿼리로 교체**.
4. **기배포 DB**: 과거 마이그레이션 재작성 + `flyway repair` 절차 문서화.
5. **패키지 리네임**: `core/.../vpd/` → `core/.../tenant/` (VPD 흔적 0 취지).

## 3. 컴포넌트별 변경 (Java / 설정)

### 3-1. core 모듈

| 파일 | 조치 | 상세 |
|------|------|------|
| `core/.../vpd/TenantAwareDataSource.java` | **삭제** | VPD on 전용 DB 커널 브리지(`ctx_pkg.set_tenant`/`clear_tenant`). off에선 한 번도 안 쓰임. |
| `core/.../config/CoreDataSourceConfig.java` | **단순화** | `vpdEnabled` 분기 제거. `dataSource` 빈이 physical pool을 그대로 반환하거나, `physicalDataSource`를 `@Primary`로 승격하고 래퍼 빈 제거. |
| `core/.../vpd/TenantContextHolder.java` | **유지** + 리네임 | 공유 컨텍스트. `tenant/` 패키지로 이동. |
| `core/.../vpd/TenantFilterAspect.java` | **유지** + 리네임 | 앱 레벨 격리의 단일 hook. |
| `core/.../vpd/DevTenantHeaderFilter.java` | **유지** + 리네임 | dev 편의 필터, VPD 무관. |

패키지 리네임은 IDE 계열 리네임으로 진행, import 경로가 ~18개 파일에서 바뀐다.
완료 후 `grep -r '\.vpd\.'` 잔재 0 확인.

### 3-2. passkey-app

| 파일 | 조치 | 상세 |
|------|------|------|
| `passkey-app/.../security/ApiKeyLookupService.java` | **재작성** | `api_key_lookup_pkg` CallableStatement → 직접 SQL. |
| `passkey-app/.../security/ApiKeyAuthFilter.java` | **유지** | 룩업 서비스 시그니처 동일하면 무변경. `TenantContextHolder.set/clear` 흐름 그대로. |

**ApiKey 룩업 교체 핵심**: `findByPrefix`는 인증 시점(아직 context 없음)에 전역 unique
prefix를 룩업해야 한다. JPA `findByKeyPrefix`는 `@Filter`에 막혀 0행이므로 — definer
패키지 대신 **JdbcTemplate native `SELECT ... WHERE key_prefix=?`** 로 교체한다.
Hibernate `@Filter`는 ORM 레벨이라 JDBC 직접 쿼리에는 걸리지 않으므로 동작이 동일하다.
`touchLastUsed`도 명시 `tenant_id` 파라미터 UPDATE를 native로 유지(V42에서 이미
SYS_CONTEXT 의존 제거 완료된 시맨틱을 그대로 native에 옮김).

### 3-3. 설정 (yml) — `passkey.vpd.enabled` 키 완전 제거

| 파일 | 조치 |
|------|------|
| `core/.../application-common.yml` (`vpd.enabled: false` + 주석) | 블록 삭제 |
| `passkey-app`/`admin-app` 의 `application-dev/qa/prod.yml` (`vpd:` 블록) | 삭제 (`PASSKEY_VPD_ENABLED` 환경변수 참조 포함) |
| `passkey-app/.../test/application-test.yml` (`vpd.enabled: true`) | 삭제 → 모든 IT가 앱 레벨 격리로 동작 |
| `application.yml` 주석의 bootstrap-vpd 언급 | 정리 |

`@Value("${passkey.vpd.enabled:false}")` 참조가 사라지므로 키가 어디에도 안 남는다.

## 4. 마이그레이션 전략 (재작성 + forward DROP + repair)

### 4-1. 과거 마이그레이션 재작성 (흔적 0)

| 마이그레이션 | 현재 | 재작성 후 |
|------|------|-----------|
| `V3__vpd_policies.sql` | `tenant_predicate` 함수 + CREDENTIAL 정책 | VPD 부분 제거. 파일 전체가 VPD 전용이면 no-op 주석으로 비움. |
| `V8__api_key_vpd_policy.sql` | API_KEY 정책 + `api_key_lookup_pkg` | 정책·패키지 제거(패키지는 native로 대체되어 불필요). |
| `V19__uuid_migration.sql` | UUID 변환 + DROP_POLICY 2건 + 패키지 재정의 | **테이블 재생성 로직 유지**, DROP_POLICY/패키지 부분만 제거. |
| `V20__vpd_policy_for_uuid.sql` | predicate 재정의 + 정책 2건 재부착 | 내용 제거 → no-op 주석. |
| `V35__tenant_child_vpd_policies.sql` | 자식 테이블 5개 정책 | 내용 제거 → no-op 주석. |
| `V42__api_key_touch_last_used_vpd_off.sql` | touch_last_used 명시 파라미터화 | 패키지가 사라지므로 제거 → no-op. |

**원칙**: 파일과 버전 번호는 **남기되 내용을 비운다**(빈 PL/SQL 블록 + "VPD removed in
V52" 주석). 파일 삭제는 신규 환경 Flyway 버전 점프·후속 의존을 깨뜨릴 수 있어 위험하다.

### 4-2. Forward-only 정리 마이그레이션 — `V52__drop_vpd.sql` (신규)

기배포 DB에서 실제 객체를 떼어낸다. 모든 호출에 멱등 가드(ORA-28101 정책없음 /
ORA-04043 객체없음 / ORA-00439 SE2 미지원 / ORA-01031 권한없음 삼킴) 포함:

- 7개 `DBMS_RLS.DROP_POLICY`:
  CREDENTIAL_TENANT_ISOLATION, API_KEY_TENANT_ISOLATION,
  TENANT_ALLOWED_ORIGIN_ISOLATION, TENANT_ACCEPTED_FORMAT_ISOLATION,
  TENANT_AAGUID_POLICY_ISOLATION, TENANT_AAGUID_ENTRY_ISOLATION,
  TENANT_WEBAUTHN_SNAPSHOT_ISOLATION.
- `DROP FUNCTION APP_OWNER.tenant_predicate`
- `DROP PACKAGE APP_OWNER.api_key_lookup_pkg`
- `DROP PACKAGE APP_OWNER.CTX_PKG` + `DROP CONTEXT APP_CTX`
  — **권한 가드 필수**: APP_OWNER는 `DROP CONTEXT` 권한이 없을 수 있다(ORA-01031).
  EXCEPTION으로 삼켜 보존(외부 SE 운영 환경 교훈). SE는 애초 정책 없어 전부 no-op 통과.

**멱등 가드는 fail-closed로**: Oracle 3치 논리(`NULL <> x` = unknown) 함정 회피 위해
조건은 `SQLCODE = -28101`처럼 양성 매칭 또는 `IS NULL OR` 명시 패턴 사용.

### 4-3. 두 전략이 충돌하지 않는 이유

- **신규 환경**: 재작성된 V3~V42(no-op) 실행 → VPD 객체 애초 안 생김. V52도 no-op. 깨끗.
- **기배포 dev/qa**: 과거 V3~V42는 이미 적용 완료(체크섬 검증 대상). 재작성으로 체크섬이
  바뀌므로 → `flyway repair`로 히스토리 체크섬 재정렬. 이후 V52가 실제 VPD 객체 제거.

### 4-4. 배포 runbook (문서화)

`docs/`에 절차 명시:
1. 앱 배포 전 `flyway repair` (재작성된 마이그레이션 체크섬 재정렬)
2. `flyway migrate` → V52가 VPD 정책/패키지/컨텍스트 제거
3. 검증: `SELECT * FROM user_policies` 0행, `api_key_lookup_pkg`/`tenant_predicate`/`CTX_PKG` 부재 확인
4. SE 환경: 정책이 원래 없으므로 repair만, V52는 no-op

## 5. 스크립트 정리

| 파일 | 조치 | 상세 |
|------|------|------|
| `scripts/bootstrap-vpd.sql` | **재작성 + 리네임** | 유저 분리(3-user 생성·GRANT) 유지, `CTX_PKG`/`APP_CTX CONTEXT`/`GRANT EXECUTE ON DBMS_RLS`/`EXEMPT ACCESS POLICY`만 제거. `bootstrap-schema.sql`로 리네임. |
| `scripts/bootstrap-external-body.sql` | **동일** | `CTX_PKG` 정의(96-117)·`set_tenant` 제거, 유저/스키마 유지. |
| `scripts/bootstrap-external.sql` | VPD 참조 정리 | 래퍼/호출부 점검. |
| `scripts/init-db-external.sh` | **`PASSKEY_VPD_ENABLED` 제거** | 라인 39 주석, 66 기본값, 116 bootRun 전달부 삭제. SE 가드 설명 갱신. |
| `scripts/run-bootstrap.sh` | bootstrap-vpd 참조 → 새 파일명 | 리네임 반영. |
| `scripts/reset-app-owner-external.sql` | **점검** | DROP_POLICY 루프가 정책 없을 때 no-op이 되도록 멱등 가드 유지. CTX_PKG 보존 로직은 VPD 제거 후 불필요해지나 무해. |

유저 분리 유지 결정에 따라 부트스트랩 핵심(3-user + 테이블 GRANT)은 그대로 둔다.

## 6. 테스트 정리

| 테스트 | 조치 | 이유 |
|------|------|------|
| `VpdIsolationIT.java` | **삭제** | VPD on(DB 커널 정책) 전용. |
| `VpdToggleIT.java` | **삭제** | on/off 토글 자체 소멸. |
| `TenantAwareDataSourceTest.java` | **삭제** | 클래스 자체 삭제됨. |
| `AppLevelIsolationIT.java` | **유지 + 회귀 게이트 승격** | `vpd.enabled=false` 격리 증명 → 유일한 격리 경로의 핵심 회귀 테스트. |
| `TenantFilterAspectIT.java` | **유지** + 리네임 | 앱 레벨 격리 hook 검증. |
| `TenantFilterBindingIT.java` | **유지** + 리네임 | 동일. |
| `TenantContextHolderTest.java` | **유지** + 리네임 | 공유 컨텍스트. |
| `DevTenantHeaderFilterTest.java` | **유지** + 리네임 | VPD 무관. |
| `RuntimeDsHelper.java` | **점검** | VPD IT 헬퍼면 정리. |
| `passkey-app/.../test/application-test.yml` | `vpd.enabled: true` 제거 | 모든 IT가 앱 레벨 격리로. |
| ApiKey 룩업 테스트 | **재작성** | 패키지 호출 → native 쿼리로 바뀐 동작 검증(context 없는 전역 prefix 룩업 + tenant context 설정). |

**핵심 안전장치**: VPD on 테스트를 지우는 대신 `AppLevelIsolationIT`(off 격리 증명)를
**회귀 게이트로 승격**한다. "VPD를 떼도 cross-tenant 누출 없음"을 이 테스트가 증명해야
정리가 안전하다. 커버리지가 부족하면 보강한다.

주석 정리: repository/service/entity의 "VPD filters by SYS_CONTEXT" 주석 → "app-level
@Filter isolates by tenant"로 갱신. 이미 정확한 주석(`CredentialAdminService` 등)은 보존.

## 7. 작업 순서 (Phase별 worktree)

1. **Phase A — Java 코어 정리**: `TenantAwareDataSource` 삭제 → `CoreDataSourceConfig` 단순화 → `vpd/`→`tenant/` 리네임(import 18곳). 컴파일 통과 게이트.
2. **Phase B — ApiKey 룩업 교체**: `ApiKeyLookupService`를 native 쿼리로 재작성 + 테스트. `api_key_lookup_pkg` 의존 제거.
3. **Phase C — 설정/스크립트**: yml의 `vpd.enabled` 전부 제거, bootstrap 스크립트 재작성·리네임, `init-db-external.sh` 정리.
4. **Phase D — 마이그레이션**: 과거 V3/V8/V19/V20/V35/V42 재작성(no-op) + `V52__drop_vpd.sql` 신규.
5. **Phase E — 테스트 정리**: VPD on 테스트 삭제, `AppLevelIsolationIT` 회귀 게이트 보강, 주석 갱신.
6. **Phase F — 문서**: repair runbook 작성.

각 Phase: main brainstorm(완료) → worktree → 실행 → `merge --no-ff`.

## 8. 검증 전략 — "격리 무손실" 증명

- **앱 레벨 격리 회귀**: `AppLevelIsolationIT` — 테넌트 A 컨텍스트에서 B 데이터 조회 시 0행. 통과해야 정리 안전.
- **ApiKey 인증 E2E**: context 없는 상태 prefix 룩업 → 인증 성공 → `TenantContextHolder` 설정 → 테넌트 데이터만 보임. native 교체 후 동일 동작.
- **Testcontainers 필수**: Oracle 동작은 inspection으로 안 잡힘. V52 DROP 멱등성·CTX_PKG DROP 권한 가드는 실제 Oracle 컨테이너로 검증.
- **회귀 판정**: `./gradlew build` 빨감은 신뢰 불가(SliceConfig/Oracle 경합 pre-existing) → **base worktree 대조**로 신규 회귀만 확정.
- **dogfooding**: dev 부팅 후 admin-ui에서 테넌트별 데이터 격리 육안 확인 + passkey-app 등록/인증 ceremony 1회.

## 9. 리스크 & 완화

| 리스크 | 완화 |
|------|------|
| 기배포 DB 체크섬 깨짐 | `flyway repair` runbook 명시. SE는 정책 없어 V52 no-op. |
| CTX_PKG/APP_CTX DROP 권한 부족(ORA-01031) | V52에서 EXCEPTION 삼킴(보존). 외부 SE 교훈 반영. |
| PL/SQL 가드 NULL fail-open | V52 멱등 가드는 양성 매칭 / `IS NULL OR` 명시 패턴. |
| ApiKey native 쿼리가 @Filter 우회 확인 | JdbcTemplate은 Hibernate 필터 우회 — IT로 context 없는 전역 룩업 확인. |
| 리네임 누락 import | IDE 계열 리네임 + 컴파일 게이트 + grep `\.vpd\.` 잔재 0 확인. |
| VPD 정책 active DB에 잘못 배포 | 토글 자체가 없어 불일치 리스크 소멸(기존 V35 리스크 근본 제거). |

## 10. 비범위 (YAGNI)

- DB 유저 3분할 통합 안 함(유지 결정).
- `TenantContextHolder`/`TenantFilterAspect`/`@Filter` 로직 변경 안 함(격리 그대로).
- 새로운 격리 메커니즘 도입 안 함.
