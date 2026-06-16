# DB 미사용 컬럼 정리 — 설계

날짜: 2026-06-17
상태: 승인됨 (구현 대기)

## 배경 / 목표

"현재 DB 구조에서 코드 기준으로 안 쓰는 테이블·컬럼을 확인해 불필요한 것을 정리한다."

두 개의 read-only 탐색 에이전트(테이블 레벨 / 엔티티 컬럼 레벨)와 dev DB 행 수 데이터를 교차해 전수 조사했다.

### 조사 결론

- **테이블 21개 전부 ACTIVE** — JPA 엔티티 또는 네이티브 SQL(JdbcTemplate)로 런타임에서 읽기/쓰기됨. **미사용 테이블 0개.**
  - 0행 테이블 4개(`admin_password_reset_token`, `admin_user_invitation`, `admin_user_recovery_code`, `tenant_aaguid_policy_entry`)도 코드에서 활발히 사용 — dev에서 해당 기능을 안 돌렸을 뿐이라 DROP 대상 아님.
  - `@ElementCollection` 매핑 테이블(api_key_scope, tenant_accepted_format, tenant_allowed_origin, tenant_aaguid_policy_entry)·네이티브 전용(scheduler_lease, mds_sync_history)도 전부 사용 중.
- **미사용 컬럼 2개 + 죽은 Repository 1개** 발견 → 이번 정리 대상.

## 정리 대상 (확정)

| # | 대상 | 종류 | 근거 |
|---|---|---|---|
| 1 | `credential.backup_state` (CLOB) + `ck_credential_backup_state` CHECK 제약 | 미사용 컬럼 | `Credential.java:46` `@Column(name="BACKUP_STATE")` 매핑만 존재. getter 호출 0건. webauthn 모듈의 `backupState`(authenticator BS 비트)는 **무관한 별개** — DB 컬럼 아님. |
| 2 | `mds_blob_cache.blob_jwt` (BLOB, NOT NULL) | 쓰기 전용 컬럼 | `MdsBlobStore.java:44` raw SQL UPDATE로 쓰기만, 읽는 코드 없음. "감사·재검증용" 주석이나 그 로직 미구현. 사용자 결정: DROP. |
| 3 | `MdsBlobCacheRepository` | 죽은 Repository | 프로덕션 코드 주입 0건(JdbcTemplate로 대체). 오직 10개 슬라이스 테스트의 `@MockBean`으로만 참조. |

비대상(유지): `MdsBlobCache` 엔티티·테이블 자체(`SINGLETON_ID` 활발히 사용), 그 외 모든 컬럼.

## 변경 구성 (5개 영역)

### A. Flyway 마이그레이션 — `V47__drop_unused_columns.sql` (신규)

`core/src/main/resources/db/migration/V47__drop_unused_columns.sql`

- `ALTER TABLE credential DROP CONSTRAINT ck_credential_backup_state` (컬럼보다 제약 먼저)
- `ALTER TABLE credential DROP COLUMN backup_state`
- `ALTER TABLE mds_blob_cache DROP COLUMN blob_jwt`
- 각 statement를 PL/SQL EXCEPTION 가드로 멱등 처리:
  - `ORA-00904`(컬럼 없음), `ORA-02443`(제약 없음)를 PRAGMA EXCEPTION_INIT으로 잡아 무시.
- **함정 회피**: DROP COLUMN은 NOT NULL과 무관하게 동작 → 메모리의 "MODIFY BLOB NOT NULL → ORA-22296"은 해당 없음(우리는 MODIFY가 아니라 DROP).
- 모든 환경(dev/qa/prod/SE) 부팅 시 자동 적용. 정방향 전용(롤백 스크립트 없음 — Flyway 원칙).

### B. 엔티티 필드 제거

- `core/.../entity/Credential.java` — `backupStateJson` 필드 + `@Column(name="BACKUP_STATE")` 어노테이션 제거. 관련 getter/빌더 인자 있으면 함께 제거.
- `core/.../entity/MdsBlobCache.java:39-41` — `@Lob @Column(name="BLOB_JWT") private String blobJwt;` 제거 + `MdsBlobCache.java:66` `getBlobJwt()` getter 제거.

### C. raw SQL 정리 — `MdsBlobStore.java`

- `store()`의 UPDATE 문(line 43-45)에서 `blob_jwt=?` 컬럼과 마지막 바인딩 파라미터(`rawJwt`, line 49) 제거.
- `rawJwt` 메서드 파라미터가 그 후 미사용이 되면: 시그니처 정리 여부를 호출부 확인 후 결정(과한 변경이면 파라미터는 유지하되 미사용 표시). 구현 계획에서 호출부 추적.
- 클래스 Javadoc(line 19 "원본 BLOB JWT를 그대로 저장한다 — 감사·재검증 가능") 갱신.

### D. 죽은 Repository 제거

- `core/.../repository/MdsBlobCacheRepository.java` 삭제.
- `@MockBean ... MdsBlobCacheRepository` 줄을 참조하는 **슬라이스 테스트 10개**에서 해당 줄 제거:
  - TenantAdminControllerSecurityTest, MeControllerSecurityTest, LicenseGuardFilterIT, SystemInfoControllerSecurityTest, KeyMgmtControllerSecurityTest, ApiKeyAdminControllerSecurityTest, AuditLogControllerSecurityTest, MdsAdminControllerSecurityTest, SecurityPolicyControllerSecurityTest, AaguidPolicyControllerSecurityTest.
  - **주의**(메모리 "슬라이스 MockBean 회귀"): 각 테스트가 그 목을 실제 stubbing/검증에 쓰는지 먼저 확인. 단순 컨텍스트 충족용 `@MockBean`이면 줄 삭제로 충분. 어떤 빈이 이 Repository에 의존해 컨텍스트 로드가 깨지면 안 됨(프로덕션 주입 0건이라 의존 없음이 확인됨 → 줄 삭제 안전 예상).

### E. 데이터 확인 (구현 전)

- DROP 전 dev DB에서 두 컬럼에 보존 가치 데이터 없음 재확인. `blob_jwt`는 JdbcTemplate이 매 sync마다 덮어쓰는 캐시값이라 폐기 무방. `backup_state`는 현재 credential 1행에서도 미사용.

## 데이터 흐름 / 에러처리 / 테스트

- **데이터 흐름 변화**: MDS sync 시 `blob_jwt` 미저장. 파싱 엔트리는 기존대로 Redis 캐시 + `mds_blob_cache`의 version/next_update/fetched_at만 사용. 읽던 코드가 없었으므로 동작 영향 없음.
- **에러처리**: 마이그레이션 멱등 EXCEPTION 가드. 재실행/환경별 상태 차이에도 안전.
- **검증**:
  1. 영향 모듈(core, admin-app) 컴파일 통과.
  2. admin-app dev 부팅 → V47 적용 로그 + `actuator/health` UP 확인.
  3. dev DB에서 두 컬럼 DROP 확인(`all_tab_columns` 조회).
  4. MDS 관련 슬라이스 테스트(MdsAdminControllerSecurityTest) + 영향받은 10개 슬라이스 테스트 그린.
  - 전체 `./gradlew build`는 메모리상 pre-existing 함정(SliceConfig 충돌·Oracle 경합)이 있어 머지 게이트로 쓰지 않고, base worktree 대비로 회귀만 확정.

## 작업 격리

- Per-Phase worktree: main에서 분기한 worktree에서 구현 → `--no-ff` 머지.
- main의 미커밋 변경(README, R__seed_dev_tenant.sql — RP_ADMIN 시드 별개 작업)은 이 worktree에 섞지 않음.
