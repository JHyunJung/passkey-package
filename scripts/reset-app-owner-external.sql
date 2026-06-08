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
