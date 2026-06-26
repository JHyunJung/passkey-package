# Flyway 마이그레이션 squash + 부트스트랩 초기화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영 DB가 없는 지금, V1~V52(52개/2459줄) 누적 Flyway 마이그레이션을 현재 최종 스키마를 그대로 담은 단일 `V1__baseline_schema.sql`로 squash 하고, 부트스트랩 스크립트를 SE2-clean 하게 정리한다.

**Architecture:** 실DB에서 추출이 진실의 원천(source of truth)이다. 깨끗한 Oracle 컨테이너에 기존 V1~V52를 끝까지 적용해 "정답 스키마"를 만들고, `DBMS_METADATA`로 추출·정리해 새 baseline을 만든다. 정확성은 **A(기존 52개) vs B(새 baseline) 스키마 diff = 0**과 **기존 Testcontainers IT 그린**으로 증명한다. squash는 동등 변환이며 스키마 의미를 바꾸지 않는다.

**Tech Stack:** Oracle XE 21c (`gvenzl/oracle-xe:21-slim-faststart`), Flyway, `DBMS_METADATA.GET_DDL`, Testcontainers, Gradle, sqlplus/Docker.

## Global Constraints

- **운영 배포 DB 없음** — 마이그레이션 히스토리 보존 의무 0. 신규 DB만 대상.
- **운영 타깃 = Oracle SE2** — baseline은 SE2-clean(EE 전용 기능 0, STORAGE/TABLESPACE/SEGMENT_ATTRIBUTES 제거). 근거: `docs/superpowers/specs/2026-06-27-se2-compatibility-audit.md`.
- **동등 변환 원칙** — squash는 스키마 의미를 변경하지 않는다. 리팩토링·개선·신규 객체 추가 금지.
- **R__ 시드 불변** — `R__seed_operators`(alice), `R__seed_dev_tenant`(bob/dev), `R__seed_local_tenant`(local), testfix V9000/V9001 은 건드리지 않는다. 운영자 시드는 이미 versioned 밖에 있다.
- **작업 격리** — per-phase worktree에서 작업, 검증 통과 후 main으로 `--no-ff` 머지. worktree 안에서는 **상대경로로 Write**하고 `git status`로 검증(메인 repo 절대경로 Write 금지).
- **build 회귀 판정** — `./gradlew build` 전체는 SliceConfig 충돌+Oracle 컨테이너 경합으로 pre-existing 빨강. 머지 게이트로 쓰지 말고, **변경한 모듈의 해당 IT만** 돌려 base와 대조.
- **인프라 시드 = baseline 포함, 환경 시드 = 제외** — lock/싱글톤/bootstrap 함수는 baseline. 테넌트·운영자 행은 R__가 담당. (추출이 자동으로 구분하지 못하는 부분은 §Task 3에서 수동 점검.)

---

## File Structure

- **Create**: `core/src/main/resources/db/migration/V1__baseline_schema.sql` — 신규 단일 baseline
- **Delete**: `core/src/main/resources/db/migration/V1__platform_scoped_tables.sql` ~ `V52__drop_vpd.sql` (기존 52개)
- **Modify**: `scripts/bootstrap-schema.sql`, `scripts/bootstrap-external-body.sql`, `scripts/run-bootstrap.sh`, `scripts/reset-app-owner.sql` — VPD 주석 정리 + `XEPDB1` → `${ORA_SERVICE}`
- **Scratch** (커밋 안 함): `scripts/_squash/extract-baseline.sql`, `scripts/_squash/dump-schema.sql` — 추출/덤프 도구. 작업용이며 `/private/tmp/.../scratchpad`에 둘 수도 있음.
- **불변**: `db/seed-common/`, `db/dev/`, `db/local/`, `db/qa/`, `db/prod/`, `db/testfix/`, 모든 `application*.yml`

---

## Task 1: 추출/덤프 도구 작성 + "정답 스키마(A)" 덤프

기존 V1~V52를 깨끗한 DB에 적용하고, 정규화된 스키마 덤프를 만든다. 이 덤프가 모든 후속 비교의 기준(A)이다.

**Files:**
- Create (scratch): `/private/tmp/claude-501/-Users-jhyun-Git-10-work-crosscert-Passkey2/<session>/scratchpad/dump-schema.sql`
- 결과물: `scratchpad/schema-A.txt` (정규화된 기존 스키마 덤프)

**Interfaces:**
- Produces: `dump-schema.sql` — APP_OWNER 스키마 전체를 정규화 텍스트로 출력하는 sqlplus 스크립트. 후속 Task가 동일 스크립트로 B를 덤프해 diff 한다.

- [ ] **Step 1: worktree 생성 (전체 작업 격리)**

REQUIRED SUB-SKILL: `superpowers:using-git-worktrees` 로 worktree 생성. 브랜치명 예: `squash-flyway-baseline`. 이후 모든 파일 작업은 worktree cwd 안에서 상대경로로 수행한다.

- [ ] **Step 2: 깨끗한 Oracle 컨테이너 기동 + 기존 마이그레이션 적용**

```bash
# worktree 루트에서
docker compose down -v && docker compose up -d oracle
bash scripts/wait-for-oracle.sh
bash scripts/run-bootstrap.sh          # APP_OWNER 계정/역할/권한
# 기존 V1~V52 + seed-common(alice) 적용 — admin-app Flyway 사용
SPRING_PROFILES_ACTIVE=dev ./gradlew :admin-app:bootRun --args='--spring.flyway.locations=classpath:db/migration,classpath:db/seed-common' &
# 부팅 후 Flyway 적용 완료되면 종료 (또는 init-dev-db.sh 사용)
```

대안(더 단순): `bash scripts/init-dev-db.sh` 가 compose down -v + up + bootstrap + Flyway 를 한 번에 한다. dev 프로필 시드(테넌트/bob)까지 들어가므로, **순수 스키마 비교를 위해서는 dev 시드가 만든 데이터 행은 덤프에서 제외**하거나(스키마만 비교) 동일 조건으로 B도 만들어 대조한다. → **본 계획은 "스키마 구조(DDL)만" 비교한다**(데이터 행은 R__가 동일하게 적용하므로 비교 대상 아님).

- [ ] **Step 3: 덤프 스크립트 작성**

`dump-schema.sql` (sqlplus, APP_OWNER 접속) — 스키마 구조만 정규화 출력:

```sql
SET LONG 2000000 LONGCHUNKSIZE 2000000 PAGESIZE 0 LINESIZE 200 FEEDBACK OFF VERIFY OFF TRIMSPOOL ON
-- DBMS_METADATA 변환 파라미터: 스토리지/테이블스페이스/세그먼트 제거 → 환경중립 비교
BEGIN
  DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'STORAGE', FALSE);
  DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'TABLESPACE', FALSE);
  DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'SEGMENT_ATTRIBUTES', FALSE);
  DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'SQLTERMINATOR', TRUE);
  DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'PRETTY', TRUE);
END;
/
-- 객체 타입별, 이름순 정렬하여 결정적 출력 (flyway_schema_history 는 제외)
SELECT DBMS_METADATA.GET_DDL(object_type, object_name, 'APP_OWNER')
FROM (
  SELECT object_type, object_name FROM user_objects
  WHERE object_type IN ('TABLE','SEQUENCE','VIEW','INDEX','PACKAGE','PACKAGE BODY','FUNCTION','PROCEDURE','TRIGGER','TYPE')
    AND object_name NOT LIKE 'FLYWAY_%'
  ORDER BY object_type, object_name
);
-- 제약·GRANT 도 별도 추출 (DDL 에 inline 되지 않는 경우 대비)
SELECT 'GRANT '||privilege||' ON '||table_name||' TO '||grantee
FROM user_tab_privs_made WHERE grantee IN ('APP_RUNTIME','APP_ADMIN','APP_RUNTIME_USER','APP_ADMIN_USER')
ORDER BY table_name, grantee, privilege;
EXIT
```

- [ ] **Step 4: A 덤프 생성**

```bash
docker exec -i passkey-oracle sqlplus -S APP_OWNER/app_owner_pw@localhost:1521/XEPDB1 < scratchpad/dump-schema.sql > scratchpad/schema-A.txt
# 후처리 정규화: 공백/빈줄 정리, 이름순 안정 정렬이 안 된 부분 보정
grep -c "CREATE TABLE" scratchpad/schema-A.txt   # 21 근처여야 함 (테이블 21개)
```

Expected: `schema-A.txt`에 테이블 21·시퀀스 6·뷰 1·패키지 등이 담김. `CREATE TABLE` 카운트가 21에 근접.

- [ ] **Step 5: 커밋 (도구만, scratch 덤프는 커밋 안 함)**

이 Task는 분석 단계라 소스 변경이 없다. worktree 브랜치만 존재. 다음 Task에서 baseline 생성 후 함께 커밋한다. (덤프 산출물은 scratchpad에 두고 git에 넣지 않는다.)

---

## Task 2: V1__baseline_schema.sql 생성 + 기존 52개 삭제

추출물을 정리해 새 baseline을 만들고, 기존 52개를 삭제한다.

**Files:**
- Create: `core/src/main/resources/db/migration/V1__baseline_schema.sql`
- Delete: `core/src/main/resources/db/migration/V{1..52}__*.sql` (기존 52개 전부)

**Interfaces:**
- Consumes: Task 1의 `schema-A.txt`(정답 스키마 구조) + 기존 V14/V17/V18 인프라 시드 INSERT.
- Produces: 새 `V1__baseline_schema.sql` — 후속 Task 3가 이것만 적용해 schema-B를 만든다.

- [ ] **Step 1: 추출물을 baseline로 정리**

`schema-A.txt`를 토대로 `V1__baseline_schema.sql` 작성. 순서와 내용:

```
1. (주석 헤더) 이 파일이 V1~V52 squash 결과임을 명시 + 생성일 + SE2-clean 근거
2. CREATE TABLE — FK 의존성 순서로 부모→자식 (tenant 먼저, credential/api_key 등 나중)
3. CREATE SEQUENCE — 6개
4. CREATE INDEX — PK/UNIQUE 외 보조 인덱스 (V38 api_key 등)
5. CREATE VIEW — V40 UUID 디버깅 뷰
6. CREATE PACKAGE / PACKAGE BODY — signing_key bootstrap 등 (AUTHID DEFINER 유지)
7. GRANT 블록 — APP_RUNTIME/APP_ADMIN 객체 권한 (각 GRANT 개별 문장)
8. 인프라 시드 INSERT — 아래 Step 2 참조
```

DBMS_METADATA 출력의 `"APP_OWNER".` 스키마 한정자, 따옴표 식별자는 가독성을 위해 정리하되, 동작 동일성을 해치지 않는 선에서만(과한 손질로 오타 유발 금지). diff가 잡아주므로 보수적으로.

- [ ] **Step 2: 인프라 시드만 baseline 끝에 추가**

기존 마이그레이션에서 **환경 무관 인프라 시드만** 가져온다. 각 원본 파일의 INSERT를 그대로(멱등 가드 포함):

- `V14__audit_chain_lock_seed.sql` 의 `audit_chain_lock` 싱글톤 INSERT
- `V17__mds_blob_cache_singleton_seed.sql` 의 `mds_blob_cache` 싱글톤 INSERT
- `V18__signing_key_runtime_insert.sql` 의 bootstrap 함수/INSERT (패키지는 Step 1의 PACKAGE 블록에 이미 포함됐는지 확인 — 중복 금지)

**제외 (환경 시드 — R__ 담당이거나 테넌트 의존)**:
- `V26__tenant_aaguid_policy.sql:29` INSERT — `tenant_id` 참조. 특정 테넌트 의존 → baseline 제외(R__/앱이 생성). **단 schema-A 추출 시 이 행이 데이터로 존재했다면, 그건 dev 시드 테넌트 때문이므로 baseline에 넣지 않는다.**
- `V27__tenant_webauthn_snapshot.sql:31` INSERT — 동일 이유로 제외.
- V11(이미 no-op), V29 운영자 시드 없음 — 해당 없음.

판단 기준: **"빈 DB(테넌트 0개)에서도 앱이 부팅하려면 반드시 있어야 하는 행"만 baseline**. 테넌트가 생겨야 의미있는 행은 제외.

- [ ] **Step 3: 기존 52개 마이그레이션 삭제**

```bash
cd core/src/main/resources/db/migration
git rm V1__platform_scoped_tables.sql V2__credential_table.sql V3__vpd_policies.sql \
  V4__app_runtime_read_grants.sql V5__app_runtime_sequence_grants.sql V6__tenant_rp_columns.sql \
  V7__api_key_table.sql V8__api_key_vpd_policy.sql V9__admin_user_table.sql V10__audit_log_table.sql \
  V11__seed_admin_user.sql V12__admin_user_runtime_grants.sql V13__audit_log_runtime_grants.sql \
  V14__audit_chain_lock_seed.sql V15__signing_key_table.sql V16__signing_key_runtime_grants.sql \
  V17__mds_blob_cache_singleton_seed.sql V18__signing_key_runtime_insert.sql V19__uuid_migration.sql \
  V20__vpd_policy_for_uuid.sql V21__tenant_config_normalize.sql V22__base_entity_timestamps.sql \
  V23__admin_role_separation.sql V24__audit_log_tenant_id.sql V25__audit_log_tenant_chain.sql \
  V26__tenant_aaguid_policy.sql V27__tenant_webauthn_snapshot.sql V28__aaguid_policy_runtime_grants.sql \
  V29__admin_user_invitation.sql V30__admin_user_invitation_runtime_grants.sql V31__security_policy.sql \
  V32__admin_user_mfa.sql V33__tenant_webauthn_extra.sql V34__mds_sync_history.sql \
  V35__tenant_child_vpd_policies.sql V36__credential_label_and_mfa_recovery.sql V37__admin_account_security.sql \
  V38__api_key_tenant_index.sql V39__missing_runtime_seq_grants.sql V40__readable_uuid_views.sql \
  V41__ceremony_event.sql V42__api_key_touch_last_used_vpd_off.sql V43__credential_auth_event.sql \
  V44__credential_cose_schema.sql V45__admin_user_update_signing_pkg_exec_grant.sql \
  V46__audit_log_backfill_grant.sql V47__drop_unused_columns.sql V48__tenant_allowed_origin_android.sql \
  V49__kst_session_timezone.sql V50__security_incident.sql V51__drop_password_min_length.sql V52__drop_vpd.sql
ls V*.sql   # V1__baseline_schema.sql 하나만 남아야 함
```

Expected: `V1__baseline_schema.sql` 하나만 존재.

- [ ] **Step 4: 커밋**

```bash
git add core/src/main/resources/db/migration/
git commit -m "refactor(db): V1~V52 마이그레이션을 단일 V1 baseline로 squash

운영 DB 부재 → 히스토리 보존 의무 0. 실DB(V1~V52 적용) DBMS_METADATA
추출로 최종 스키마 재현. STORAGE/TABLESPACE off로 SE2-clean. 인프라
시드(lock/싱글톤/bootstrap)만 포함, 테넌트/운영자 시드는 R__ 유지.
정확성은 다음 커밋의 A-vs-B diff + IT 그린으로 검증."
```

---

## Task 3: A-vs-B 스키마 diff = 0 검증 (핵심 게이트)

새 baseline이 기존 52개와 **동일한 스키마**를 만드는지 객관적으로 증명한다. 이 게이트를 통과하지 못하면 Task 2로 돌아간다.

**Files:**
- 사용: Task 1의 `dump-schema.sql`, `schema-A.txt`
- 생성(scratch): `scratchpad/schema-B.txt`, `scratchpad/schema-diff.txt`

**Interfaces:**
- Consumes: 새 `V1__baseline_schema.sql`, 기준 `schema-A.txt`.
- Produces: diff = 0 확인. 불일치 시 누락/오타 목록.

- [ ] **Step 1: 깨끗한 DB에 새 baseline만 적용**

```bash
docker compose down -v && docker compose up -d oracle
bash scripts/wait-for-oracle.sh
bash scripts/run-bootstrap.sh
# 새 baseline만 적용 (db/migration 에는 이제 V1 하나뿐)
SPRING_PROFILES_ACTIVE=dev DASH... ./gradlew :admin-app:flywayMigrate  # 또는 init-dev-db.sh
```

대안: `bash scripts/init-dev-db.sh` (compose 재생성 + bootstrap + Flyway). dev 시드까지 적용되지만 **스키마 구조 비교에는 영향 없음**(R__는 A·B 동일하게 적용).

- [ ] **Step 2: B 덤프 생성**

```bash
docker exec -i passkey-oracle sqlplus -S APP_OWNER/app_owner_pw@localhost:1521/XEPDB1 < scratchpad/dump-schema.sql > scratchpad/schema-B.txt
```

- [ ] **Step 3: diff 비교**

```bash
diff <(sort scratchpad/schema-A.txt) <(sort scratchpad/schema-B.txt) > scratchpad/schema-diff.txt
cat scratchpad/schema-diff.txt
wc -l scratchpad/schema-diff.txt
```

Expected: **0 줄** (완전 일치). 정렬 비교로 객체 순서 차이는 무시하고 내용 차이만 검출.

- [ ] **Step 4: diff 발견 시 처리**

diff가 0이 아니면 — 누락된 컬럼/제약/인덱스/GRANT 또는 오타. `schema-diff.txt`의 각 항목을 `V1__baseline_schema.sql`에 반영(추가/수정)한 뒤 Step 1~3 반복. **diff = 0 될 때까지 반복.**

- [ ] **Step 5: 커밋 (baseline 보정이 있었던 경우만)**

```bash
git add core/src/main/resources/db/migration/V1__baseline_schema.sql
git commit -m "fix(db): baseline A-vs-B diff 보정 — 기존 52개와 스키마 동일성 확보"
```

---

## Task 4: 기존 IT 그린 검증 (앱 동작 게이트)

baseline 하나만으로 앱이 부팅하고 테넌트 격리·CRUD가 동작하는지. 인프라 시드 누락은 여기서 드러난다.

**Files:**
- 사용: 기존 Testcontainers IT (core/admin-app/passkey-app)

**Interfaces:**
- Consumes: 새 `V1__baseline_schema.sql`.
- Produces: IT 그린 = baseline 충분성 확인.

- [ ] **Step 1: base 브랜치(main)에서 동일 IT 베이스라인 먼저 측정**

메모리 함정: 일부 IT는 pre-existing 실패(SliceConfig/Oracle 경합). 회귀만 판정하려면 base 결과가 필요하다.

```bash
# main(또는 squash 이전 커밋) 에서 — 또는 별도 base worktree에서
./gradlew :core:test --tests '*IT' :admin-app:test --tests '*IT' 2>&1 | tee scratchpad/it-base.log
# 통과/실패 목록 기록
```

- [ ] **Step 2: squash worktree에서 동일 IT 실행**

```bash
./gradlew :core:test --tests '*IT' :admin-app:test --tests '*IT' :passkey-app:test --tests '*IT' 2>&1 | tee scratchpad/it-squash.log
```

- [ ] **Step 3: base 대비 회귀 판정**

```bash
diff <(grep -E "PASSED|FAILED" scratchpad/it-base.log | sort) \
     <(grep -E "PASSED|FAILED" scratchpad/it-squash.log | sort)
```

Expected: **base에서 통과하던 IT가 squash에서 새로 실패한 건 0건.** base에서도 실패하던 건 pre-existing이므로 무시. 새 실패가 있으면 → baseline에 누락된 시드/객체. Task 2로 회귀.

특히 확인할 IT (스키마/시드 의존):
- `AppLevelIsolationIT`, `TenantFilterAspectIT` (테넌트 격리 @Filter)
- `AuditChainPerTenantIT`, `AuditChainBackfillIT` (audit_chain_lock 시드 의존)
- `KeyRotationIT`, `MdsSchedulerIT` (signing_key/mds_blob_cache 싱글톤 의존)
- `AdminFlowIT` (alice 시드 = R__, 부팅)

- [ ] **Step 4: 커밋 (수정이 있었던 경우만)**

baseline 보정이 있었으면 Task 3 재실행 후 커밋. 없으면 이 Task는 검증만.

---

## Task 5: 부트스트랩 스크립트 SE2 정리

같이 손대는 김에 SE2 감사 🟡 항목 흡수. 계정/권한 **로직은 변경 금지**, 주석·서비스명만.

**Files:**
- Modify: `scripts/bootstrap-schema.sql:21`, `scripts/run-bootstrap.sh:7`, `scripts/reset-app-owner.sql:20`, `scripts/bootstrap-external-body.sql:13`

**Interfaces:**
- Produces: `${ORA_SERVICE:-XEPDB1}` 매개변수화된 스크립트. `init-db-external.sh:61`의 기존 패턴과 일관.

- [ ] **Step 1: run-bootstrap.sh 서비스명 매개변수화**

`scripts/run-bootstrap.sh:7` 의 하드코드 `XEPDB1`:

```bash
# 파일 상단에 추가
ORA_SERVICE="${ORA_SERVICE:-XEPDB1}"
# line 7 수정
  sqlplus -S sys/oracle@localhost:1521/${ORA_SERVICE} as sysdba \
```

- [ ] **Step 2: SQL 스크립트의 ALTER SESSION SET CONTAINER 매개변수화**

`bootstrap-schema.sql:21`, `reset-app-owner.sql:20`, `bootstrap-external-body.sql:13` 의 `ALTER SESSION SET CONTAINER = XEPDB1;`:

sqlplus DEFINE 변수로 — 각 파일 상단에:
```sql
-- 서비스/PDB 명: 호출측에서 DEFINE ora_service=... 로 주입, 미주입 시 XEPDB1
DEFINE ora_service = XEPDB1
```
그리고 line을:
```sql
ALTER SESSION SET CONTAINER = &ora_service;
```

호출하는 셸(`run-bootstrap.sh`, `reset-app-owner.sh`)에서 `DEFINE` 값을 환경변수로 넘기도록 맞춘다. `bootstrap-external-body.sql`은 이미 `bootstrap-external.sql` 래퍼가 DEFINE을 주입하므로 그 패턴을 따른다.

> **주의**: `ALTER SESSION SET CONTAINER`는 CDB 접속 시에만 유효. 일부 SE2 비-CDB(non-multitenant) 인스턴스에서는 이 줄이 ORA-에러. 운영이 비-CDB면 이 줄을 조건부/생략해야 할 수 있음 — 운영 DB 형태 확정 시 재검토(현재는 XEPDB1 디폴트 유지로 dev 무영향).

- [ ] **Step 3: VPD 죽은 주석 정리**

`bootstrap-schema.sql:3-4,90-91`, `bootstrap-external-body.sql:88`, `reset-app-owner.sql:4-8` 의 "VPD 제거됨 — 과거엔..." 설명 주석을 간결화. **단 `reset-app-owner.sql:51-60`의 `DBMS_RLS.DROP_POLICY` 정적 호출 루프는 유지** — reset은 "과거 EE 잔재가 남은 기존 DB 비우기"용이라 잔재 처리 코드가 의미 있음. (메모리 함정: 정적 DBMS_RLS 참조는 GRANT 없으면 PLS-00201 — reset.sql은 SYS로 실행되므로 권한 있음. 확인 후 유지.)

주석 정리 예 (bootstrap-schema.sql:3-4):
```sql
-- 테넌트 격리는 앱 레벨 Hibernate @Filter(TenantFilterAspect)가 전담한다.
-- (VPD/DBMS_RLS 미사용 — SE2 미지원 기능 0)
```

- [ ] **Step 4: 부트스트랩 동작 검증**

```bash
# 매개변수화 후에도 dev 부트스트랩이 동일 동작하는지
docker compose down -v && docker compose up -d oracle && bash scripts/wait-for-oracle.sh
bash scripts/run-bootstrap.sh   # 기본값 XEPDB1로 동작해야 함
# APP_OWNER/역할/권한 생성 확인
docker exec -i passkey-oracle sqlplus -S sys/oracle@localhost:1521/XEPDB1 as sysdba <<'SQL'
SELECT username FROM dba_users WHERE username IN ('APP_OWNER','APP_RUNTIME_USER','APP_ADMIN_USER');
SELECT role FROM dba_roles WHERE role IN ('APP_RUNTIME','APP_ADMIN');
EXIT
SQL
```

Expected: 3개 유저 + 2개 역할 생성됨. 매개변수화 전과 동일.

- [ ] **Step 5: 커밋**

```bash
git add scripts/
git commit -m "chore(scripts): 부트스트랩 SE2 정리 — XEPDB1→\${ORA_SERVICE}, VPD 주석 정리

서비스/PDB명 환경변수화(init-db-external.sh 패턴과 일관)로 운영 SE2
PDB 대응. VPD 죽은 설명 주석 간결화. 계정/권한 로직·reset의 EE 잔재
DROP_POLICY 루프는 불변. 근거: SE2 호환성 감사 🟡 항목."
```

---

## Task 6: 최종 통합 검증 + main 머지

전체 초기화 시나리오를 처음부터 끝까지 한 번 돌려 깨끗함을 확인하고 머지한다.

**Files:** 없음(검증 + 머지)

- [ ] **Step 1: 완전 초기화 end-to-end**

```bash
# 가장 깨끗한 경로: 전부 새로
bash scripts/init-dev-db.sh   # compose down -v + up + bootstrap + Flyway(V1 baseline) + dev 시드
```

Expected: 에러 없이 완료. Flyway가 V1 baseline 하나만 적용. dev 시드(테넌트/alice/bob) 정상.

- [ ] **Step 2: 앱 부팅 스모크**

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew :admin-app:bootRun &
# 부팅 로그에 Flyway "Successfully applied 1 migration" + 앱 정상 기동 확인
# 헬스 체크 후 종료
```

Expected: admin-app 정상 부팅. flyway_schema_history에 V1 + R__ 시드만.

- [ ] **Step 3: Task 3 diff + Task 4 IT 최종 재확인**

diff = 0, IT 회귀 0 재확인(이미 통과했으면 생략 가능).

- [ ] **Step 4: scratch 도구 정리 + main 머지**

```bash
# scratchpad 덤프/도구는 커밋하지 않음 (이미 git 밖)
# worktree에서 main으로 --no-ff 머지
git checkout main
git merge --no-ff squash-flyway-baseline -m "Merge: Flyway V1~V52 squash + 부트스트랩 SE2 정리

운영 DB 부재 시점에 52개 누적 마이그레이션을 단일 V1 baseline로 squash.
A-vs-B 스키마 diff=0 + IT 회귀 0 검증. 부트스트랩 XEPDB1→\${ORA_SERVICE}."
# worktree 정리 (using-git-worktrees 스킬의 ExitWorktree)
```

- [ ] **Step 5: 메모리 기록**

`/Users/jhyun/.claude/projects/-Users-jhyun-Git-10-work-crosscert-Passkey2/memory/` 에 project 메모리 추가: "Flyway squash 완료 — V1~V52→단일 V1 baseline, DBMS_METADATA 추출(STORAGE off), A-vs-B diff 검증법, 운영자 시드는 R__ 분리(squash 무관)". MEMORY.md 인덱스 한 줄 추가.

---

## Self-Review

**1. Spec coverage:**
- spec §3 추출 파이프라인 → Task 1+2 ✅
- spec §4 시드 처리(인프라 baseline, 운영자 R__ 불변) → Task 2 Step 2 ✅
- spec §5.A A-vs-B diff → Task 3 ✅
- spec §5.B IT 그린 → Task 4 ✅
- spec §5.C testfix 무충돌 → Global Constraints + Task 4 (V9000/V9001 격리) ✅
- spec §6 위험관리 → 각 Task의 검증 게이트 + Global Constraints ✅
- spec §7 부트스트랩 정리 → Task 5 ✅
- spec §8 worktree 격리 → Task 1 Step 1 + Task 6 Step 4 ✅
- spec §2 산출물 4종 → Task 2(baseline+삭제), Task 5(스크립트), Task 3/4(검증) ✅

**2. Placeholder scan:** "적절히 처리" 류 없음. diff 발견 시 처리(Task 3 Step 4)는 반복 절차로 구체화됨. ✅

**3. Type/이름 consistency:** `dump-schema.sql`/`schema-A.txt`/`schema-B.txt`/`schema-diff.txt` 명칭이 Task 1·3에서 일관. `${ORA_SERVICE}`/`&ora_service` 매개변수명 Task 5 내 일관. baseline 파일명 `V1__baseline_schema.sql` 전체 일관. ✅

**미해결 리스크 (실행 중 판단):**
- Task 5 Step 2의 `ALTER SESSION SET CONTAINER`는 비-CDB SE2에서 문제 가능 — 주석으로 명시, 운영 DB 형태 확정 시 재검토. 현재 dev(XEPDB1=CDB) 무영향.
- DBMS_METADATA 추출물의 가독성 정리 범위는 실행자 판단 — diff=0 게이트가 안전망.
