-- reset-app-owner.sql — APP_OWNER 스키마의 모든 객체를 SYS(SYSDBA) 세션에서 DROP 한다.
--
-- 컨테이너/볼륨/유저(APP_OWNER, APP_RUNTIME_USER, APP_ADMIN_USER)/role 은 건드리지
-- 않는다. 스키마 안의 테이블·시퀀스·패키지·컨텍스트·트리거·뷰·과거 EE 잔재 VPD 정책만 비운다.
-- 테넌트 격리는 앱 레벨 Hibernate @Filter(TenantFilterAspect) 전담 (VPD/DBMS_RLS 미사용, SE2 호환).
-- 실행 후에는 run-bootstrap.sh 로 스키마/role 을 재생성하고 Flyway 로 다시 마이그레이션한다.
--
-- 멱등성(idempotent): 객체가 없어도 실패하지 않도록 모든 DROP 을 데이터 딕셔너리
-- 기반 동적 루프로 감싸고, "이미 없음" 류 오류는 무시한다. 여러 번 재실행해도 안전.
--
-- 호출 방법(컨테이너 내부 SYS 세션):
--   docker exec -i passkey-oracle \
--     sqlplus -S sys/oracle@localhost:1521/XEPDB1 as sysdba < reset-app-owner.sql

WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT SQL.SQLCODE

-- 서비스/PDB 명: 호출측(reset-app-owner.sh)에서 DEFINE ora_service=... 로 주입 필수.
-- SQL 파일에 in-file DEFINE 이 없으므로 호출측 주입이 유일 정의가 된다.
-- 직접 실행 시엔 먼저 `DEFINE ora_service = <PDB명>` 을 선행 실행하라.
ALTER SESSION SET CONTAINER = &ora_service;

SET SERVEROUTPUT ON SIZE UNLIMITED

DECLARE
  v_owner CONSTANT VARCHAR2(30) := 'APP_OWNER';
  v_cnt   PLS_INTEGER := 0;

  -- 동적 DDL 한 건 실행 + "이미 없음" 류 오류만 무시.
  -- 그 외 예상 못 한 오류(lock, 미지원 종속 등)는 RAISE 해서 스크립트가 성공
  -- 종료하지 않게 한다. 조용히 삼키면 stale 객체를 남긴 채 부트스트랩·Flyway 가
  -- 이어져 "초기화됐다"는 거짓 성공이 난다(codex P1).
  PROCEDURE try_ddl(p_sql IN VARCHAR2) IS
  BEGIN
    EXECUTE IMMEDIATE p_sql;
    v_cnt := v_cnt + 1;
  EXCEPTION WHEN OTHERS THEN
    -- 무시 가능한 오류: 객체/정책이 이미 없음.
    --   ORA-00942  table or view does not exist
    --   ORA-04043  object does not exist
    --   ORA-02289  sequence does not exist
    --   ORA-28104  input value for argument is wrong (정책 이미 제거됨)
    --   ORA-28102  policy does not exist
    IF SQLCODE IN (-942, -4043, -2289, -28104, -28102) THEN
      NULL;
    ELSE
      DBMS_OUTPUT.PUT_LINE('  [error] ' || SQLERRM || ' :: ' || SUBSTR(p_sql, 1, 120));
      RAISE;
    END IF;
  END try_ddl;
BEGIN
  -- 1) VPD 정책 먼저 분리(테이블 DROP 전에 떼는 게 깔끔).
  --    DBMS_RLS.DROP_POLICY(object_schema, object_name, policy_name).
  --    DBA_POLICIES 의 대상 객체 컬럼은 OBJECT_NAME(앞서 검증).
  FOR p IN (
    SELECT object_name, policy_name
    FROM   dba_policies
    WHERE  object_owner = v_owner
  ) LOOP
    BEGIN
      DBMS_RLS.DROP_POLICY(v_owner, p.object_name, p.policy_name);
      v_cnt := v_cnt + 1;
      DBMS_OUTPUT.PUT_LINE('  dropped policy ' || p.policy_name || ' on ' || p.object_name);
    EXCEPTION WHEN OTHERS THEN
      -- try_ddl 와 동일하게 "이미 없음"(ORA-28102/28104)만 무시하고 그 외는 RAISE.
      -- 삼키면 stale 정책이 남은 채 성공 보고된다(codex P1 re-review).
      IF SQLCODE IN (-28102, -28104) THEN NULL;
      ELSE
        DBMS_OUTPUT.PUT_LINE('  [error] drop_policy ' || p.policy_name || ': ' || SQLERRM);
        RAISE;
      END IF;
    END;
  END LOOP;

  -- 2) 테이블: FK 제약을 무시하도록 CASCADE CONSTRAINTS, 휴지통 거치지 않게 PURGE.
  --    flyway_schema_history 도 함께 비워야 재마이그레이션이 가능하다.
  FOR t IN (
    SELECT table_name FROM dba_tables WHERE owner = v_owner
  ) LOOP
    try_ddl('DROP TABLE ' || v_owner || '."' || t.table_name || '" CASCADE CONSTRAINTS PURGE');
  END LOOP;

  -- 3) 시퀀스
  FOR s IN (
    SELECT sequence_name FROM dba_sequences WHERE sequence_owner = v_owner
  ) LOOP
    try_ddl('DROP SEQUENCE ' || v_owner || '."' || s.sequence_name || '"');
  END LOOP;

  -- 4) 뷰
  FOR v IN (
    SELECT view_name FROM dba_views WHERE owner = v_owner
  ) LOOP
    try_ddl('DROP VIEW ' || v_owner || '."' || v.view_name || '"');
  END LOOP;

  -- 5) 패키지/프로시저/함수/타입/트리거 등 나머지 객체.
  --    과거 VPD 잔재인 CTX_PKG 패키지가 남아 있으면 여기서 정리된다(bootstrap 은
  --    더 이상 재생성하지 않는다). 이미 DROP 된 테이블의 종속 객체는 건너뛴다.
  FOR o IN (
    SELECT object_name, object_type
    FROM   dba_objects
    WHERE  owner = v_owner
      AND  object_type IN ('PACKAGE','PROCEDURE','FUNCTION','TRIGGER',
                           'TYPE','SYNONYM','MATERIALIZED VIEW')
      AND  object_name NOT LIKE 'SYS_%'
  ) LOOP
    try_ddl('DROP ' || o.object_type || ' ' || v_owner || '."' || o.object_name || '"');
  END LOOP;

  -- 6) APP_CTX 컨텍스트(과거 VPD 잔재). 남아 있으면 정리(bootstrap 은 더 이상 복구하지 않음).
  try_ddl('DROP CONTEXT APP_CTX');

  -- 7) APP_OWNER 휴지통만 비운다. SYS 로 'PURGE DBA_RECYCLEBIN' 을 하면 PDB 전역
  --    휴지통(다른 스키마의 복구 가능 객체 포함)이 날아가므로 절대 쓰지 않는다
  --    (codex P1). 테이블 DROP 이 이미 PURGE 라 보통은 비어 있지만 방어적으로.
  --    USER_RECYCLEBIN 은 현재 SYS 세션 기준이므로 APP_OWNER 객체를 직접 PURGE.
  FOR r IN (
    SELECT object_name FROM dba_recyclebin WHERE owner = v_owner
  ) LOOP
    try_ddl('PURGE TABLE ' || v_owner || '."' || r.object_name || '"');
  END LOOP;

  -- 8) 최종 검증: APP_OWNER 에 사용자 객체나 VPD 정책이 남아 있으면 실패로 종료.
  --    조용히 넘어가면 stale 상태 위에 부트스트랩·Flyway 가 돌아 거짓 성공이 난다
  --    (codex P1). 객체(SYS_*/BIN$* 제외)와 정책 양쪽을 확인한다.
  SELECT COUNT(*) INTO v_cnt
  FROM   dba_objects
  WHERE  owner = v_owner
    AND  object_name NOT LIKE 'SYS_%'
    AND  object_name NOT LIKE 'BIN$%';
  IF v_cnt > 0 THEN
    RAISE_APPLICATION_ERROR(-20099,
      'APP_OWNER reset incomplete: ' || v_cnt || ' object(s) remain. Aborting.');
  END IF;

  SELECT COUNT(*) INTO v_cnt FROM dba_policies WHERE object_owner = v_owner;
  IF v_cnt > 0 THEN
    RAISE_APPLICATION_ERROR(-20098,
      'APP_OWNER reset incomplete: ' || v_cnt || ' VPD policy/policies remain. Aborting.');
  END IF;

  DBMS_OUTPUT.PUT_LINE('==> APP_OWNER reset done. 0 objects, 0 policies remain.');
END;
/

-- 남은 객체 확인(0 이어야 정상; 위 블록이 0 이 아니면 이미 -20099 로 중단됨).
SET HEADING ON
SELECT object_type, COUNT(*) AS remaining
FROM   dba_objects
WHERE  owner = 'APP_OWNER'
  AND  object_name NOT LIKE 'SYS_%'
  AND  object_name NOT LIKE 'BIN$%'
GROUP  BY object_type
ORDER  BY object_type;

EXIT;
