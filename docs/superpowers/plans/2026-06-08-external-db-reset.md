# 외부 DB(SE) APP_OWNER reset 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 우리 시스템 잔재가 남은 외부 Oracle SE 의 APP_OWNER 스키마를 DBeaver(APP_OWNER 계정)로 비우는 SQL 스크립트와, 그 후 Flyway 만 재적용하도록 init-db-external.sh 에 SKIP_BOOTSTRAP 옵션을 추가한다.

**Architecture:** SYSDBA·sqlplus 없이 APP_OWNER self-service 로 동작. `reset-app-owner-external.sql`(DBeaver 붙여넣기, user_* 딕셔너리 + DROP_POLICY(USER), CTX_PKG/APP_CTX 보존)로 스키마를 비우고, `init-db-external.sh SKIP_BOOTSTRAP=1 PASSKEY_VPD_ENABLED=false` 로 Flyway 만 재적용. 컨테이너 DB(외부와 동일 APP_OWNER 권한)로 검증한다.

**Tech Stack:** Oracle SE, PL/SQL(DBMS_RLS), bash, Spring Boot Flyway, DBeaver(수동 실행), Docker(검증용 컨테이너).

**Spec:** `docs/superpowers/specs/2026-06-08-external-db-reset-design.md`

---

## File Structure

- **Create** `scripts/reset-app-owner-external.sql` — DBeaver 에 붙여넣어 APP_OWNER 가 자기 스키마를 비우는 PL/SQL. 테이블·VPD정책·시퀀스·뷰·기타패키지 DROP, CTX_PKG·APP_CTX 보존, 잔존 0 검증. sqlplus 지시어(`WHENEVER`, `ALTER SESSION ... CONTAINER`, `as sysdba`) 없음.
- **Modify** `scripts/init-db-external.sh` — `SKIP_BOOTSTRAP=1` 이면 sqlplus 단계를 건너뛰고 Flyway 단계만; 단계 2 에 `PASSKEY_VPD_ENABLED` 환경변수 전달.

검증은 별도 테스트 파일 없이 컨테이너 DB 에 대해 `docker exec ... APP_OWNER` 로 SQL 을 실행해 결과를 단언한다(이 코드베이스는 SQL 단위테스트 프레임워크가 없고, 기존 reset-app-owner.sql 도 동일하게 실DB 실행으로 검증함).

---

## Task 1: reset-app-owner-external.sql 작성

**Files:**
- Create: `scripts/reset-app-owner-external.sql`

- [ ] **Step 1: 스크립트 작성**

아래 내용을 그대로 작성한다. 컨테이너 APP_OWNER 세션에서 실행 검증된 형태다(VPD 정책 7개 분리 + 테이블/시퀀스/뷰 DROP + CTX_PKG·APP_CTX 보존 + 테이블0·정책0 검증 성공).

```sql
-- reset-app-owner-external.sql — 외부 Oracle SE 의 APP_OWNER 스키마를 DBeaver 에서
-- APP_OWNER 계정으로 비운다. SYSDBA·sqlplus 불필요.
--
-- ⚠️ 파괴적: APP_OWNER 의 모든 테이블·데이터(테넌트·계정·패스키·인증기록)를 삭제한다.
-- ⚠️ dev/qa 전용. prod 에는 절대 실행하지 말 것.
--
-- 보존: CTX_PKG(패키지+바디), APP_CTX(컨텍스트).
--   - APP_OWNER 는 DROP CONTEXT 권한이 없고(CREATE ANY CONTEXT 만 보유),
--     시드(R__)가 CTX_PKG.set_tenant 를 호출하므로 둘 다 남겨 재적용이 바로 되게 한다.
--   - CTX_PKG 는 bootstrap 의 CREATE OR REPLACE 로 멱등 — 남아 있어도 무해.
-- SE 노트: SE 는 VPD 미지원이라 user_policies 가 보통 0건이다(아래 루프는 빈 채 통과).
--   EE 잔재로 정책이 남아있으면 DBMS_RLS.DROP_POLICY 로 분리한다.
--
-- 사용법(DBeaver, APP_OWNER 로 접속한 SQL 에디터에 전체 붙여넣고 실행):
--   1) 먼저 아래 확인 쿼리로 대상 DB/스키마를 눈으로 확인한다.
--   2) PL/SQL 블록을 실행한다.

-- [실행 전 확인] 어느 DB·어느 스키마에 붙어있는지 반드시 확인.
SELECT USER AS current_schema, ORA_DATABASE_NAME AS database FROM dual;

SET SERVEROUTPUT ON SIZE UNLIMITED

DECLARE
  v_cnt PLS_INTEGER := 0;

  -- 동적 DDL 한 건 실행 + "이미 없음" 류 오류만 무시. 그 외는 RAISE.
  PROCEDURE try_ddl(p_sql IN VARCHAR2) IS
  BEGIN
    EXECUTE IMMEDIATE p_sql;
    v_cnt := v_cnt + 1;
  EXCEPTION WHEN OTHERS THEN
    --   ORA-00942 table or view does not exist / ORA-04043 object does not exist
    --   ORA-02289 sequence does not exist / ORA-28104,-28102 policy already gone
    IF SQLCODE IN (-942, -4043, -2289, -28104, -28102) THEN
      NULL;
    ELSE
      DBMS_OUTPUT.PUT_LINE('  [error] ' || SQLERRM || ' :: ' || SUBSTR(p_sql, 1, 120));
      RAISE;
    END IF;
  END try_ddl;
BEGIN
  -- 1) VPD 정책 분리(SE 면 0건). DROP_POLICY(USER, ...) — 자기 스키마 대상.
  FOR p IN (SELECT object_name, policy_name FROM user_policies) LOOP
    BEGIN
      DBMS_RLS.DROP_POLICY(USER, p.object_name, p.policy_name);
      v_cnt := v_cnt + 1;
      DBMS_OUTPUT.PUT_LINE('  dropped policy ' || p.policy_name || ' on ' || p.object_name);
    EXCEPTION WHEN OTHERS THEN
      IF SQLCODE IN (-28102, -28104) THEN NULL;
      ELSE
        DBMS_OUTPUT.PUT_LINE('  [error] drop_policy ' || p.policy_name || ': ' || SQLERRM);
        RAISE;
      END IF;
    END;
  END LOOP;

  -- 2) 테이블: FK 무시(CASCADE CONSTRAINTS), 휴지통 우회(PURGE). flyway_schema_history 포함.
  FOR t IN (SELECT table_name FROM user_tables) LOOP
    try_ddl('DROP TABLE "' || t.table_name || '" CASCADE CONSTRAINTS PURGE');
  END LOOP;

  -- 3) 시퀀스
  FOR s IN (SELECT sequence_name FROM user_sequences) LOOP
    try_ddl('DROP SEQUENCE "' || s.sequence_name || '"');
  END LOOP;

  -- 4) 뷰
  FOR v IN (SELECT view_name FROM user_views) LOOP
    try_ddl('DROP VIEW "' || v.view_name || '"');
  END LOOP;

  -- 5) 패키지/프로시저/함수/트리거/타입 — CTX_PKG 는 보존(set_tenant 의존 + DROP CONTEXT 불가).
  FOR o IN (
    SELECT object_name, object_type
    FROM   user_objects
    WHERE  object_type IN ('PACKAGE','PROCEDURE','FUNCTION','TRIGGER',
                           'TYPE','SYNONYM','MATERIALIZED VIEW')
      AND  object_name NOT LIKE 'SYS_%'
      AND  object_name <> 'CTX_PKG'
  ) LOOP
    try_ddl('DROP ' || o.object_type || ' "' || o.object_name || '"');
  END LOOP;

  -- 6) APP_CTX 컨텍스트: 보존(DROP 권한 없음 + CREATE OR REPLACE 로 재사용). 손대지 않음.

  -- 7) 휴지통: APP_OWNER 자기 것만 PURGE(전역 PURGE 금지).
  FOR r IN (SELECT object_name FROM user_recyclebin) LOOP
    try_ddl('PURGE TABLE "' || r.object_name || '"');
  END LOOP;

  -- 8) 검증: 테이블 0 + VPD 정책 0. CTX_PKG/APP_CTX 는 의도적 보존이라 카운트 제외.
  SELECT COUNT(*) INTO v_cnt FROM user_tables;
  IF v_cnt > 0 THEN
    RAISE_APPLICATION_ERROR(-20099, 'reset incomplete: ' || v_cnt || ' table(s) remain. Aborting.');
  END IF;

  SELECT COUNT(*) INTO v_cnt FROM user_policies;
  IF v_cnt > 0 THEN
    RAISE_APPLICATION_ERROR(-20098, 'reset incomplete: ' || v_cnt || ' VPD policy/policies remain. Aborting.');
  END IF;

  DBMS_OUTPUT.PUT_LINE('==> external reset done. 0 tables, 0 policies remain (CTX_PKG/APP_CTX preserved).');
END;
/

-- [실행 후 확인] 테이블 0 + CTX_PKG 보존 확인.
SELECT COUNT(*) AS remaining_tables FROM user_tables;
SELECT object_name, object_type, status FROM user_objects WHERE object_name = 'CTX_PKG' ORDER BY object_type;
```

- [ ] **Step 2: 컨테이너 APP_OWNER 로 실행 검증**

컨테이너 DB 는 EE 라 VPD 정책 7개가 있으므로 "정책 분리 + 테이블/시퀀스/뷰 DROP + CTX_PKG 보존" 전 경로를 검증할 수 있다(SE 의 0-정책 경로는 이 경로의 부분집합).

Run:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
docker exec -i passkey-oracle sqlplus -S APP_OWNER/app_owner_pw@localhost:1521/XEPDB1 < scripts/reset-app-owner-external.sql 2>&1 | tail -25
```
Expected (정상):
- `dropped policy ... on ...` 7줄 (컨테이너 EE 기준; SE 면 이 줄 없음)
- `==> external reset done. 0 tables, 0 policies remain (CTX_PKG/APP_CTX preserved).`
- `REMAINING_TABLES` = 0
- CTX_PKG PACKAGE/PACKAGE BODY 가 `VALID` 로 보존
- `[error]` 라인이나 ORA-01031(insufficient privileges) 없음

- [ ] **Step 3: 컨테이너 DB 복구**

검증으로 스키마를 비웠으니 dogfooding 환경을 되돌린다(SYS reset 스크립트로 재시드).

Run:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
PROFILE=local timeout 300 bash scripts/reset-app-owner.sh --yes 2>&1 | grep -E "0 objects|✅ 완료|❌"
```
Expected: `==> APP_OWNER reset done. 0 objects, 0 policies remain.` 와 `==> ✅ 완료. ...`

- [ ] **Step 4: 커밋**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git add scripts/reset-app-owner-external.sql
git commit -m "feat(scripts): 외부 SE DB용 APP_OWNER reset SQL (DBeaver, CTX 보존)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: init-db-external.sh 에 SKIP_BOOTSTRAP + VPD_ENABLED 추가

**Files:**
- Modify: `scripts/init-db-external.sh:41` (환경변수 기본값 선언부), `:52-62`(단계1 가드/실행), `:71-78`(단계2 환경변수)

- [ ] **Step 1: SKIP_BOOTSTRAP 환경변수 기본값 추가**

`scripts/init-db-external.sh` 의 `PROFILE="${PROFILE:-dev}"` 줄(41행) 바로 아래에 추가:

```bash
PROFILE="${PROFILE:-dev}"
# SKIP_BOOTSTRAP=1 이면 단계 1(sqlplus bootstrap)을 건너뛴다. 이미 부트스트랩된
# (예: DBeaver 로 bootstrap-external.sql 을 실행한) 외부 DB 에 Flyway 만 재적용할 때.
SKIP_BOOTSTRAP="${SKIP_BOOTSTRAP:-0}"
# SE(Standard Edition) 는 VPD 미지원 — false 로 두면 app-level @Filter 격리.
PASSKEY_VPD_ENABLED="${PASSKEY_VPD_ENABLED:-false}"
```

- [ ] **Step 2: 단계 1 을 SKIP_BOOTSTRAP 으로 감싸기**

기존 52~62 행(sqlplus 사전점검 + 단계1 실행)을 아래로 교체:

```bash
# ---- 단계 1: 부트스트랩 (SKIP_BOOTSTRAP=1 이면 건너뜀) ----
if [ "${SKIP_BOOTSTRAP}" = "1" ]; then
  echo "==> [1/2] 부트스트랩 건너뜀 (SKIP_BOOTSTRAP=1) — 이미 부트스트랩된 DB 전제."
  echo "    (APP_OWNER/role/CTX_PKG 가 이미 있어야 합니다. 없으면 DBeaver 로"
  echo "     bootstrap-external.sql 을 먼저 실행하세요.)"
else
  if ! command -v sqlplus >/dev/null 2>&1; then
    echo "ERROR: sqlplus 가 PATH 에 없습니다. Oracle Instant Client(SQL*Plus)를 설치하거나," >&2
    echo "       DBeaver 등으로 단계 1 SQL 을 수동 실행한 뒤 SKIP_BOOTSTRAP=1 로 재실행하세요:" >&2
    echo "         (DBeaver, SYSDBA 또는 적절 권한) < ${SCRIPT_DIR}/bootstrap-external.sql" >&2
    exit 1
  fi
  echo "==> [1/2] 부트스트랩 (SYSDBA): APP_OWNER 유저 + role + CTX_PKG"
  sqlplus -S "${SYS_CONN}" < "${SCRIPT_DIR}/bootstrap-external.sql"
  echo "    부트스트랩 완료."
fi
echo ""
```

- [ ] **Step 3: 단계 2 에 PASSKEY_VPD_ENABLED 전달**

기존 단계 2 의 환경변수 블록(71~78행)에서 `PASSKEY_KEY_ENVELOPE_MASTER_KEY=...` 줄 다음에 한 줄 추가:

```bash
SPRING_DATASOURCE_URL="${JDBC_URL}" \
SPRING_DATASOURCE_USERNAME='APP_ADMIN_USER' \
SPRING_DATASOURCE_PASSWORD='admin_pw' \
SPRING_DATA_REDIS_HOST="${REDIS_HOST:-localhost}" \
PASSKEY_KEY_ENVELOPE_MASTER_KEY="${PASSKEY_KEY_ENVELOPE_MASTER_KEY:-jDKp21WXeDAwinZI91Hf+8L2zv4xlIQI15YPLhttyYM=}" \
PASSKEY_VPD_ENABLED="${PASSKEY_VPD_ENABLED}" \
./gradlew :admin-app:bootRun \
  --args="--spring.profiles.active=${PROFILE} --spring.datasource.url=${JDBC_URL}" \
  > "${LOG}" 2>&1 &
```

- [ ] **Step 4: bash 문법 검사 + SKIP_BOOTSTRAP 분기 동작 확인(빠른 dry)**

Run:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
bash -n scripts/init-db-external.sh && echo "syntax OK"
# SKIP_BOOTSTRAP=1 이면 sqlplus 없이도 단계1 가드를 통과하는지 (단계2 직전까지만 확인):
# 잘못된 호스트로 즉시 실패시켜 단계1 분기만 관찰 (gradle 까진 안 감)
SKIP_BOOTSTRAP=1 ORA_HOST=127.0.0.1 ORA_PORT=1 ORA_SERVICE=NOPE PROFILE=qa \
  timeout 20 bash scripts/init-db-external.sh 2>&1 | grep -E "\[1/2\]|건너뜀|\[2/2\]" | head
```
Expected: `==> [1/2] 부트스트랩 건너뜀 (SKIP_BOOTSTRAP=1) ...` 출력 후 `==> [2/2] ...` 로 진입(이후 gradle 이 잘못된 DB 로 실패하지만 단계1 스킵은 확인됨). sqlplus 부재로 인한 조기 `exit 1` 이 **없어야** 한다.

- [ ] **Step 5: 커밋**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git add scripts/init-db-external.sh
git commit -m "feat(scripts): init-db-external 에 SKIP_BOOTSTRAP/PASSKEY_VPD_ENABLED 옵션

sqlplus 없는 환경에서 DBeaver 로 부트스트랩한 외부 SE DB 에 Flyway 만 재적용.
SE 는 VPD 미지원이라 PASSKEY_VPD_ENABLED 기본 false.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 사용 절차 문서화 (init-db-external.sh 헤더 주석 보강)

**Files:**
- Modify: `scripts/init-db-external.sh:22-30` (사용법 주석 블록)

- [ ] **Step 1: 헤더 주석에 SE reset 워크플로 추가**

`init-db-external.sh` 의 사용법 주석(22~30행 부근, `# 사용법(환경변수로 대상 지정):` 블록)에 아래 SE reset 절차를 덧붙인다. 기존 사용법 예시 바로 다음에:

```bash
#
#   [이미 우리 잔재가 있는 외부 SE DB 를 비우고 재적용하는 절차]
#     1) DBeaver 에서 APP_OWNER 로 접속해 scripts/reset-app-owner-external.sql 실행
#        (테이블·데이터 삭제, CTX_PKG/APP_CTX 보존).
#     2) 아래처럼 SKIP_BOOTSTRAP=1 로 Flyway 만 재적용:
#        SKIP_BOOTSTRAP=1 PASSKEY_VPD_ENABLED=false \
#        ORA_HOST=db.example.com ORA_PORT=1521 ORA_SERVICE=ORCLPDB1 PROFILE=qa \
#        scripts/init-db-external.sh
#     SE(Standard Edition) 는 VPD 미지원 — PASSKEY_VPD_ENABLED=false 로 둔다.
```

- [ ] **Step 2: bash 문법 재확인**

Run:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
bash -n scripts/init-db-external.sh && echo "syntax OK"
```
Expected: `syntax OK`

- [ ] **Step 3: 커밋**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git add scripts/init-db-external.sh
git commit -m "docs(scripts): init-db-external 헤더에 외부 SE DB reset+재적용 절차 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage:**
- 산출물1 `reset-app-owner-external.sql` → Task 1 ✅ (user_* 변환, DROP_POLICY(USER), CTX 보존, 검증 블록 전부 포함)
- 산출물2 `init-db-external.sh` SKIP_BOOTSTRAP + PASSKEY_VPD_ENABLED → Task 2 ✅
- 워크플로 2단계(reset → Flyway) → Task 3 헤더 문서화 ✅
- §3 VPD 재분리 미작성(SE 미부착) → spec 결정대로 plan 에 없음 ✅
- 안전장치(파괴 경고·대상 확인 쿼리·dev/qa 전용) → Task 1 SQL 상단/첫 쿼리 ✅

**2. Placeholder scan:** TBD/TODO/"적절히" 없음. 모든 SQL·bash 코드 완전 기재. ✅

**3. Type consistency:** `reset-app-owner-external.sql` 의 검증 단언(user_tables=0, user_policies=0)과 DROP 루프 대상(user_tables/sequences/views/objects/policies/recyclebin)이 일관. CTX_PKG 제외 조건(`object_name <> 'CTX_PKG'`)이 보존 결정과 일치. init-db-external.sh 의 `SKIP_BOOTSTRAP`/`PASSKEY_VPD_ENABLED` 변수명이 Step 1 선언과 Step 2/3 사용에서 동일. ✅

**검증 근거:** Task 1 의 SQL 은 설계 단계에서 컨테이너 APP_OWNER 세션으로 실제 실행해 "정책7 분리 + 테이블/시퀀스/뷰 DROP + CTX_PKG VALID 보존 + 테이블0/정책0" 을 확인한 코드와 동일하다.
