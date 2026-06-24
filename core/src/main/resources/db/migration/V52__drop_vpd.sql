-- ============================================================
-- V52 — VPD(Oracle Virtual Private Database / DBMS_RLS) 객체 전면 제거 (forward-only)
--
-- 신규 DB: 재작성된 V3/V8/V19/V20/V35/V42 가 VPD 객체를 애초에 만들지 않으므로
--          아래 DROP 은 전부 "객체 없음" 경로로 no-op 통과한다.
-- 기배포 DB(EE/XE): 실제 7개 정책 + tenant_predicate 함수 + api_key_lookup_pkg +
--          CTX_PKG 패키지 + APP_CTX 컨텍스트를 제거한다.
-- SE2: 정책이 원래 없으므로 DROP_POLICY 는 ORA-28101, 함수/패키지/컨텍스트가
--          없으면 ORA-04043 → 전부 삼켜 no-op.
--
-- ⚠️ GRANT 독립 설계: 이 마이그레이션이 실행되는 시점에 bootstrap 은 더 이상
--    APP_OWNER 에게 EXECUTE ON DBMS_RLS 를 GRANT 하지 않는다(Task 7 에서 제거).
--    PL/SQL anonymous block 이 DBMS_RLS 를 "정적(static)" 참조하면, EXECUTE 권한이
--    없을 때 EXCEPTION 핸들러가 실행되기 전 PLS-00201/ORA-06550 컴파일 단계에서
--    깨진다(EXCEPTION WHEN OTHERS 로 못 잡음). 따라서 모든 DBMS_RLS/DROP CONTEXT
--    호출을 EXECUTE IMMEDIATE 동적 SQL 로 감싼다 — 동적 SQL 은 런타임에 해석되어
--    권한/객체 부재가 SQLCODE 로 잡히므로 멱등하게 삼킬 수 있다.
--    exec_ignore 가 삼키는 코드: -28101(정책 없음) -439(SE2 FGAC 미지원)
--    -942(테이블 없음) -4043(객체 없음) -6550/-201(식별자 미해결, PLS-00201).
--    ⚠️ -1031(권한 없음)은 exec_ignore 가 삼키지 않는다 — 정책/함수/패키지 DROP 이
--    권한 부족으로 실패하면 stale 객체 false success 가 되므로 RAISE 한다. 단
--    DROP CONTEXT APP_CTX 의 알려진 -1031(외부 SE 권한 제약)만 아래 별도 블록에서
--    경고와 함께 관용한다(무해한 빈 컨텍스트).
--
-- 배포: 이 변경으로 V3/V8/V19/V20/V35/V42 의 체크섬이 바뀌므로, 기배포 DB 는
--    `flyway repair` 후 `flyway migrate` 가 필요하다(상세 runbook 은 별도 문서).
-- ============================================================

DECLARE
  -- 무시 가능한 SQLCODE: "이미 없음 / SE2 미지원 / 식별자 미해결(GRANT 없는 DBMS_RLS
  -- 정적 참조 회피용)". ⚠️ ORA-01031(권한 없음)은 여기서 삼키지 않는다 — 정책/함수/
  -- 패키지 DROP 이 권한 부족으로 실패하면 그건 stale 객체를 남기는 진짜 문제이므로
  -- RAISE 해서 드러내야 한다(조용한 false success 방지). DROP CONTEXT 의 알려진
  -- ORA-01031 제약만 아래에서 별도로, 경고와 함께 관용한다.
  PROCEDURE exec_ignore(p_sql IN VARCHAR2) IS
  BEGIN
    EXECUTE IMMEDIATE p_sql;
  EXCEPTION
    WHEN OTHERS THEN
      IF SQLCODE IN (-28101, -439, -942, -4043, -6550, -201) THEN
        NULL;  -- 정책/객체 없음, SE2 미지원, 식별자 미해결 → 멱등 no-op
      ELSE
        RAISE;  -- ORA-01031 포함 그 외 전부 RAISE: stale 객체 false success 방지
      END IF;
  END exec_ignore;

  -- DBMS_RLS.DROP_POLICY 를 동적 SQL 로 호출(정적 참조 시 GRANT 없으면 PLS-00201).
  PROCEDURE drop_policy(p_table IN VARCHAR2, p_policy IN VARCHAR2) IS
  BEGIN
    exec_ignore(
      'BEGIN DBMS_RLS.DROP_POLICY(' ||
      'object_schema => ''APP_OWNER'', ' ||
      'object_name   => ''' || p_table  || ''', ' ||
      'policy_name   => ''' || p_policy || '''); END;');
  END drop_policy;
BEGIN
  -- 1) 7개 VPD 정책 제거 (멱등)
  drop_policy('CREDENTIAL',                 'CREDENTIAL_TENANT_ISOLATION');
  drop_policy('API_KEY',                    'API_KEY_TENANT_ISOLATION');
  drop_policy('TENANT_ALLOWED_ORIGIN',      'TENANT_ALLOWED_ORIGIN_ISOLATION');
  drop_policy('TENANT_ACCEPTED_FORMAT',     'TENANT_ACCEPTED_FORMAT_ISOLATION');
  drop_policy('TENANT_AAGUID_POLICY',       'TENANT_AAGUID_POLICY_ISOLATION');
  drop_policy('TENANT_AAGUID_POLICY_ENTRY', 'TENANT_AAGUID_ENTRY_ISOLATION');
  drop_policy('TENANT_WEBAUTHN_SNAPSHOT',   'TENANT_WEBAUTHN_SNAPSHOT_ISOLATION');

  -- 2) tenant_predicate 함수 제거
  exec_ignore('DROP FUNCTION APP_OWNER.tenant_predicate');

  -- 3) api_key_lookup_pkg 패키지 제거 (앱 native 쿼리로 대체됨)
  exec_ignore('DROP PACKAGE APP_OWNER.api_key_lookup_pkg');

  -- 4) CTX_PKG 패키지 제거 (tenant 컨텍스트 set/clear 브리지)
  exec_ignore('DROP PACKAGE APP_OWNER.CTX_PKG');

  -- 5) APP_CTX 컨텍스트 제거.
  --    ⚠️ APP_OWNER 는 DROP CONTEXT 에 DROP ANY CONTEXT 권한이 필요한데, 외부 SE
  --    배포에서는 이를 보유하지 않아 ORA-01031 이 난다(실측 — 외부 SE DB 초기화 경험).
  --    여기서 RAISE 하면 그 환경의 마이그레이션이 통째로 깨진다. APP_CTX 는 참조
  --    패키지 CTX_PKG 가 위에서 제거돼 동작 불가 빈 껍데기일 뿐이라 보안/기능상
  --    무해하다. 따라서 ORA-01031 만 관용하되, 조용히 넘기지 않고 경고를 남겨
  --    운영자가 인지하고 필요 시 SYSDBA 로 수동 제거하게 한다(false success 방지).
  --    그 외 오류(객체 없음 -4043 포함)는 exec_ignore 가 적절히 처리/전파한다.
  BEGIN
    EXECUTE IMMEDIATE 'DROP CONTEXT APP_CTX';
  EXCEPTION
    WHEN OTHERS THEN
      IF SQLCODE = -4043 THEN
        NULL;  -- 컨텍스트 없음 → 멱등 no-op
      ELSIF SQLCODE = -1031 THEN
        DBMS_OUTPUT.PUT_LINE(
          '[V52][WARN] DROP CONTEXT APP_CTX 권한 부족(ORA-01031). 무해한 빈 컨텍스트가 '
          || '잔존합니다 — 참조 패키지 CTX_PKG 는 이미 제거됨. 완전 제거하려면 SYSDBA 로 '
          || 'DROP CONTEXT APP_CTX 를 실행하세요.');
      ELSE
        RAISE;
      END IF;
  END;
END;
/
