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
-- SE2 guard: ADD_POLICY is attempted directly; ORA-00439 (no fine-grained
-- access control on Standard Edition 2) is swallowed so the migration
-- succeeds. No v$option query → no SYS dynamic-view SELECT dependency.
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
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -439 THEN NULL;      -- ORA-00439: fine-grained access control not enabled (SE2) → skip
    ELSIF SQLCODE = -28101 THEN NULL; -- ORA-28101: policy already exists (idempotent re-run)
    ELSE RAISE;
    END IF;
END;
/

-- Re-attach to api_key (V8 invariant).
-- SE2 guard: same direct-attempt + ORA-00439 swallow pattern as above.
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
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -439 THEN NULL;      -- ORA-00439: fine-grained access control not enabled (SE2) → skip
    ELSIF SQLCODE = -28101 THEN NULL; -- ORA-28101: policy already exists (idempotent re-run)
    ELSE RAISE;
    END IF;
END;
/
