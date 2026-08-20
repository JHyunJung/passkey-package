-- ============================================================================
-- Passkey2 — 전체 DB 생성 DDL (계정 + 스키마 + 권한)
--
-- 생성 시점 기준: V1~V4 마이그레이션 반영
-- 소스: scripts/bootstrap-schema.sql
--       core/src/main/resources/db/migration/V1__baseline_schema.sql
--                                            V2__admin_user_totp_replay_guard.sql
--                                            V3__admin_user_mfa_lockout_counter.sql
--                                            V4__grant_security_incident_to_app_runtime.sql
--
-- ⚠️ 이 파일은 위 소스들을 합쳐 만든 "스냅샷"이다. 정본은 여전히 위 파일들이며,
--    스키마를 바꿀 때는 이 파일이 아니라 새 V<n> 마이그레이션을 추가해야 한다.
--    (이 파일을 고쳐도 Flyway 는 모른다.)
--
-- 용도:
--   - 신규 환경에 스키마를 한 번에 올릴 때
--   - DBA 검토용 전체 구조 제출
--   - Flyway 를 쓰지 않는 환경(수동 배포)
--
-- ============================================================================
-- 실행 방법
-- ============================================================================
--
-- 1) 변수 치환 — 아래 DEFINE 값을 환경에 맞게 바꾼다.
--    비밀번호는 반드시 강한 값으로 교체할 것(기본값은 로컬 개발용).
--
-- 2) 두 단계로 나뉜다. 접속 계정이 다르므로 순서를 지켜야 한다.
--
--    [1단계] SYSDBA 로 실행 — 롤/유저/시스템권한 생성
--      sqlplus sys/<pw>@<host>:1521/<service> as sysdba @full-schema.sql
--
--    [2단계] PSK_APP_OWNER 로 실행 — 테이블/인덱스/뷰/패키지/객체권한 생성
--      sqlplus PSK_APP_OWNER/<pw>@<host>:1521/<service> @full-schema.sql
--
--    ⚠️ 한 파일로 두 단계를 모두 담고 있으나, SQL*Plus 는 세션 중간에 접속
--       계정을 바꿀 수 없다. 아래 PART 1 / PART 2 주석을 기준으로 잘라서
--       각각 실행하거나, 파일 두 개로 분리해 사용하라.
--
-- 3) Flyway 를 함께 쓸 경우 — 이 스크립트로 스키마를 만든 뒤에는
--    flyway_schema_history 가 비어 있으므로 baseline 처리가 필요하다:
--      flyway baseline -baselineVersion=4
--    그렇지 않으면 다음 배포에서 V1 부터 다시 적용하려다 실패한다.
--
-- ============================================================================

-- ############################################################################
-- ############################################################################
--
--   PART 1 — 계정 / 롤 / 시스템 권한   ★ SYSDBA 로 실행 ★
--
--   ⚠️ 이 파일만으로는 스키마가 완성되지 않는다. 실행 후 반드시
--      scripts/full-schema-part2-schema.sql 을 PSK_APP_OWNER 로 실행할 것.
--
-- ############################################################################
-- ############################################################################

-- 환경에 맞게 수정할 것 ------------------------------------------------------
DEFINE ora_service        = XEPDB1
DEFINE app_owner_pw       = CHANGE_ME_owner
DEFINE app_runtime_pw     = CHANGE_ME_runtime
DEFINE app_admin_pw       = CHANGE_ME_admin
-- ---------------------------------------------------------------------------

WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT SQL.SQLCODE

ALTER SESSION SET CONTAINER = &ora_service;

-- ============================================================
-- 계정을 3개로 나누는 이유 — 최소권한(least privilege)
-- ============================================================
--   PSK_APP_OWNER        : 스키마 소유자. DDL 권한 보유. Flyway 마이그레이션 전용.
--   PSK_APP_RUNTIME_USER : passkey-app 런타임. 테이블별 GRANT 만. DDL 불가.
--   PSK_APP_ADMIN_USER   : admin-app 런타임. 관리 테이블 추가 권한. DDL 불가.
--
-- 앱이 접속하는 두 유저에서 DDL 을 제거해, SQL 인젝션이나 버그로 DROP TABLE
-- 이 실행되더라도 권한 부족으로 실패하게 만든다. 스키마 변경은 배포 시점에
-- PSK_APP_OWNER 자격증명으로만 가능하다.
--
-- 테넌트 격리는 앱 레벨 Hibernate @Filter(TenantFilterAspect)가 전담한다.
-- VPD/DBMS_RLS 는 쓰지 않는다(SE2 미지원 — 라이선스 호환).
-- ============================================================

-- ------------------------------------------------------------
-- 롤
-- ------------------------------------------------------------

-- PSK_APP_RUNTIME: 런타임 세션(passkey-app, admin-app 일반 트랜잭션)
BEGIN
  EXECUTE IMMEDIATE 'CREATE ROLE PSK_APP_RUNTIME';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1921 THEN NULL;  -- ORA-01921: role already exists
  ELSE RAISE;
  END IF;
END;
/
GRANT CREATE SESSION TO PSK_APP_RUNTIME;

-- PSK_APP_ADMIN: admin-app 런타임 + 스케줄러. 크로스테넌트 조회는 앱 레이어에서
-- 명시적 tenant_id 검사로 처리한다.
BEGIN
  EXECUTE IMMEDIATE 'CREATE ROLE PSK_APP_ADMIN';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1921 THEN NULL;
  ELSE RAISE;
  END IF;
END;
/
GRANT CREATE SESSION TO PSK_APP_ADMIN;

-- ------------------------------------------------------------
-- PSK_APP_OWNER (스키마 소유자)
-- ------------------------------------------------------------
-- docker-compose 환경에서는 gvenzl/oracle-xe 이미지가 APP_USER env 로 이미
-- 만들어 준다. 외부 Oracle 에서는 아래 블록이 생성한다.
BEGIN
  EXECUTE IMMEDIATE 'CREATE USER PSK_APP_OWNER IDENTIFIED BY "&app_owner_pw"';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1920 THEN NULL;  -- ORA-01920: user already exists
  ELSE RAISE;
  END IF;
END;
/

-- 권한은 한 문장에 하나씩 — 이름 하나가 틀려도 나머지가 통째로 롤백되지 않게
-- 한다(Oracle 의 compound-grant 특성).
GRANT CREATE SESSION       TO PSK_APP_OWNER;
GRANT CREATE TABLE         TO PSK_APP_OWNER;
GRANT CREATE SEQUENCE      TO PSK_APP_OWNER;
GRANT CREATE PROCEDURE     TO PSK_APP_OWNER;
GRANT CREATE TRIGGER       TO PSK_APP_OWNER;
GRANT CREATE VIEW          TO PSK_APP_OWNER;
GRANT UNLIMITED TABLESPACE TO PSK_APP_OWNER;

-- ------------------------------------------------------------
-- 런타임 유저
-- ------------------------------------------------------------

BEGIN
  EXECUTE IMMEDIATE 'CREATE USER PSK_APP_RUNTIME_USER IDENTIFIED BY "&app_runtime_pw"';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1920 THEN NULL;
  ELSE RAISE;
  END IF;
END;
/
GRANT PSK_APP_RUNTIME TO PSK_APP_RUNTIME_USER;

BEGIN
  EXECUTE IMMEDIATE 'CREATE USER PSK_APP_ADMIN_USER IDENTIFIED BY "&app_admin_pw"';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1920 THEN NULL;
  ELSE RAISE;
  END IF;
END;
/
GRANT PSK_APP_ADMIN TO PSK_APP_ADMIN_USER;

PROMPT
PROMPT ============================================================
PROMPT  PART 1 완료 — 롤/유저 생성됨.
PROMPT  이제 PSK_APP_OWNER 로 접속해 PART 2 를 실행하라.
PROMPT ============================================================
PROMPT

-- ############################################################################
-- ############################################################################
--
