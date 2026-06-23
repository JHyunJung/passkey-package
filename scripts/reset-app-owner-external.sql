-- reset-app-owner-external.sql — 외부 Oracle SE 의 APP_OWNER 스키마를 DBeaver 에서
-- APP_OWNER 계정으로 비운다. SYSDBA·sqlplus 불필요.
--
-- ⚠️ 파괴적: APP_OWNER 의 모든 테이블·데이터(테넌트·계정·패스키·인증기록)를 삭제한다.
-- ⚠️ dev/qa 전용. prod 에는 절대 실행하지 말 것.
--
-- VPD 제거됨: 격리는 앱 레벨(Hibernate @Filter). 이 스크립트는 더 이상 CTX_PKG/
--   APP_CTX 를 보존하지 않는다(CTX_PKG 는 패키지 DROP 루프에서 함께 제거).
-- APP_CTX(컨텍스트)는 APP_OWNER 에 DROP CONTEXT 권한이 없어 남는다(무해, 미사용).
-- VPD 정책 잔재 가드: 과거 EE 잔재로 user_policies 가 남아있으면 DBMS_RLS.DROP_POLICY
--   로 분리한다. SE/현행 스키마에선 0건이라 아래 루프는 빈 채 통과한다(멱등 no-op).
--
-- 사용법(DBeaver, APP_OWNER 로 접속한 SQL 에디터에 전체 붙여넣고 실행):
--   1) 먼저 아래 확인 쿼리로 대상 DB/스키마를 눈으로 확인한다.
--   2) PL/SQL 블록 안의 c_confirm 을 'NO' → 'RESET' 으로 바꾼다(안전장치).
--   3) PL/SQL 블록을 실행한다. (USER 가 APP_OWNER 가 아니면 자동 중단)

-- [실행 전 확인] 어느 DB·어느 스키마에 붙어있는지 반드시 확인.
SELECT USER AS current_schema, ORA_DATABASE_NAME AS database FROM dual;

SET SERVEROUTPUT ON SIZE UNLIMITED

DECLARE
  v_cnt PLS_INTEGER := 0;

  -- ▼▼▼ 실행하려면 여기를 'NO' → 'RESET' 으로 바꾸세요 ▼▼▼
  -- 기본값 'NO' 면 파괴 전에 ORA-20096 으로 중단된다(안전). 의도적으로 RESET 해야만 비워진다.
  c_confirm CONSTANT VARCHAR2(8) := 'NO';
  -- ▲▲▲ 실행하려면 위 'NO' 를 'RESET' 으로 ▲▲▲

  -- 식별자 이스케이프: 큰따옴표 포함 이름까지 안전하게 따옴표로 감싼다.
  FUNCTION q(p_name IN VARCHAR2) RETURN VARCHAR2 IS
  BEGIN
    RETURN '"' || REPLACE(p_name, '"', '""') || '"';
  END q;

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
  -- 0) 런타임 가드 — 파괴 루프 이전에 두 가지를 검사한다.
  --    (a) 접속 USER 가 APP_OWNER 가 아니면 즉시 중단(실수로 prod 스키마 접속 차단).
  IF USER <> 'APP_OWNER' THEN
    RAISE_APPLICATION_ERROR(-20097,
      'aborting: connected as "' || USER || '", not APP_OWNER. 이 스크립트는 APP_OWNER 전용입니다.');
  END IF;
  --    (b) 확인 플래그. c_confirm 을 RESET 으로 바꿔야만 진행(기본 NO 는 안전 중단).
  --        NULL fail-closed: Oracle 에선 빈 문자열이 NULL 이고 NULL <> 'RESET' 는 unknown 이라
  --        IF 본문이 스킵돼 가드가 우회된다. IS NULL 을 명시해 빈 문자열도 막는다.
  IF c_confirm IS NULL OR c_confirm <> 'RESET' THEN
    RAISE_APPLICATION_ERROR(-20096,
      '실행하려면 스크립트 상단 c_confirm 을 RESET 으로 바꾸세요 (현재: ' || c_confirm || ').');
  END IF;

  -- 1) VPD 정책 잔재 분리(현행/SE 면 0건, 멱등 no-op). DROP_POLICY(USER, ...) — 자기 스키마 대상.
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
  --    MV 컨테이너 테이블은 제외(DROP TABLE 시 ORA-12083 → try_ddl 무시목록 밖이라 RAISE).
  --    MV 자체는 아래 객체 DROP 루프의 'MATERIALIZED VIEW' 분기가 처리한다.
  FOR t IN (
    SELECT table_name FROM user_tables
    WHERE table_name NOT IN (SELECT mview_name FROM user_mviews)
  ) LOOP
    try_ddl('DROP TABLE ' || q(t.table_name) || ' CASCADE CONSTRAINTS PURGE');
  END LOOP;

  -- 3) 시퀀스
  FOR s IN (SELECT sequence_name FROM user_sequences) LOOP
    try_ddl('DROP SEQUENCE ' || q(s.sequence_name));
  END LOOP;

  -- 4) 뷰
  FOR v IN (SELECT view_name FROM user_views) LOOP
    try_ddl('DROP VIEW ' || q(v.view_name));
  END LOOP;

  -- 5) 패키지/프로시저/함수/트리거/타입 (CTX_PKG 잔재 포함 — VPD 제거로 더는 보존 안 함).
  --    종속성 실패를 줄이려고 타입을 마지막에 DROP 하도록 정렬하고, TYPE 만 FORCE 를 붙인다.
  FOR o IN (
    SELECT object_name, object_type
    FROM   user_objects
    WHERE  object_type IN ('PACKAGE','PROCEDURE','FUNCTION','TRIGGER',
                           'TYPE','SYNONYM','MATERIALIZED VIEW')
      AND  object_name NOT LIKE 'SYS\_%' ESCAPE '\'
    ORDER  BY CASE object_type
                WHEN 'TRIGGER'           THEN 1
                WHEN 'PROCEDURE'         THEN 2
                WHEN 'FUNCTION'          THEN 3
                WHEN 'PACKAGE'           THEN 4
                WHEN 'SYNONYM'           THEN 5
                WHEN 'MATERIALIZED VIEW' THEN 6
                WHEN 'TYPE'              THEN 7
                ELSE 8
              END
  ) LOOP
    IF o.object_type = 'TYPE' THEN
      try_ddl('DROP TYPE ' || q(o.object_name) || ' FORCE');
    ELSE
      try_ddl('DROP ' || o.object_type || ' ' || q(o.object_name));
    END IF;
  END LOOP;

  -- 6) APP_CTX 컨텍스트: APP_OWNER 에 DROP CONTEXT 권한이 없어 남는다(미사용·무해). 손대지 않음.

  -- 7) 휴지통: APP_OWNER 자기 것만 PURGE(전역 PURGE 금지).
  FOR r IN (SELECT object_name FROM user_recyclebin) LOOP
    try_ddl('PURGE TABLE ' || q(r.object_name));
  END LOOP;

  -- 8) 검증: 테이블 0 + VPD 정책 잔재 0.
  SELECT COUNT(*) INTO v_cnt FROM user_tables;
  IF v_cnt > 0 THEN
    RAISE_APPLICATION_ERROR(-20099, 'reset incomplete: ' || v_cnt || ' table(s) remain. Aborting.');
  END IF;

  SELECT COUNT(*) INTO v_cnt FROM user_policies;
  IF v_cnt > 0 THEN
    RAISE_APPLICATION_ERROR(-20098, 'reset incomplete: ' || v_cnt || ' VPD policy/policies remain. Aborting.');
  END IF;

  DBMS_OUTPUT.PUT_LINE('==> external reset done. 0 tables, 0 policies remain.');
END;
/

-- [실행 후 확인] 테이블 0 확인.
SELECT COUNT(*) AS remaining_tables FROM user_tables;
