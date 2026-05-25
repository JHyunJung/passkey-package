-- VPD policy predicate function.
-- Explicitly schema-qualified APP_OWNER.tenant_predicate so the migration
-- is robust to any future drift in Flyway's CURRENT_SCHEMA behavior.
-- Signature (schema_name, object_name) RETURN VARCHAR2 is the canonical
-- shape required by DBMS_RLS for VPD policy functions.
CREATE OR REPLACE FUNCTION APP_OWNER.tenant_predicate(
  schema_name IN VARCHAR2,
  object_name IN VARCHAR2
) RETURN VARCHAR2 IS
BEGIN
  RETURN 'tenant_id = SYS_CONTEXT(''APP_CTX'',''TENANT_ID'')';
END tenant_predicate;
/

-- Attach the policy to credential. With APP_CTX unset, the predicate
-- evaluates to UNKNOWN for every row and Oracle filters them all out
-- (safe default — no leakage when tenant context is missing).
-- update_check=TRUE makes Oracle reject INSERT/UPDATE attempts whose
-- tenant_id does not match SYS_CONTEXT.
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
