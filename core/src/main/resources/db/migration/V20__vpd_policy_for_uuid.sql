-- Phase 6 V20: Reattach VPD policies after V19 recreated tables with
-- RAW(16) PK + FK. Policy predicate compares tenant_id (RAW(16))
-- against HEXTORAW(SYS_CONTEXT('APP_CTX','TENANT_ID')) — the context
-- value is the 32-char hex string set by CTX_PKG.set_tenant.
--
-- Function name (tenant_predicate) and policy names (CREDENTIAL_TENANT_ISOLATION,
-- API_KEY_TENANT_ISOLATION) are preserved from V3/V8 to avoid naming drift.

CREATE OR REPLACE FUNCTION APP_OWNER.tenant_predicate(
  schema_name IN VARCHAR2,
  object_name IN VARCHAR2
) RETURN VARCHAR2 IS
BEGIN
  -- Empty predicate '1=0' when no tenant context → preserves Phase 0
  -- invariant: no tenant set means no rows visible (no cross-tenant leak).
  IF SYS_CONTEXT('APP_CTX','TENANT_ID') IS NULL THEN
    RETURN '1=0';
  END IF;
  RETURN 'tenant_id = HEXTORAW(SYS_CONTEXT(''APP_CTX'',''TENANT_ID''))';
END tenant_predicate;
/

-- Re-attach to credential (V3 invariant).
-- update_check=TRUE makes Oracle reject INSERT/UPDATE whose tenant_id
-- does not match SYS_CONTEXT — same semantics as the original V3 policy.
BEGIN
  DBMS_RLS.ADD_POLICY(
    object_schema   => 'APP_OWNER',
    object_name     => 'CREDENTIAL',
    policy_name     => 'CREDENTIAL_TENANT_ISOLATION',
    function_schema => 'APP_OWNER',
    policy_function => 'TENANT_PREDICATE',
    statement_types => 'SELECT,INSERT,UPDATE,DELETE',
    update_check    => TRUE
  );
END;
/

-- Re-attach to api_key (V8 invariant).
BEGIN
  DBMS_RLS.ADD_POLICY(
    object_schema   => 'APP_OWNER',
    object_name     => 'API_KEY',
    policy_name     => 'API_KEY_TENANT_ISOLATION',
    function_schema => 'APP_OWNER',
    policy_function => 'TENANT_PREDICATE',
    statement_types => 'SELECT,INSERT,UPDATE,DELETE',
    update_check    => TRUE
  );
END;
/
