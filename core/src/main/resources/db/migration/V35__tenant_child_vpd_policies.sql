-- P0-1: Extend VPD row-level isolation to tenant child tables that were
-- previously protected only at the application layer. Reuses the existing
-- APP_OWNER.tenant_predicate function (V20) — '1=0' when no tenant context,
-- else tenant_id = HEXTORAW(SYS_CONTEXT('APP_CTX','TENANT_ID')).
--
-- admin-app runs as APP_ADMIN (EXEMPT ACCESS POLICY) so these policies do
-- not affect admin queries; they enforce isolation for APP_RUNTIME
-- (passkey-app) reads during ceremonies.
--
-- All five tables carry their own tenant_id RAW(16) column (verified against
-- V21/V26/V27): TENANT_AAGUID_POLICY_ENTRY is a JPA @ElementCollection table
-- (@CollectionTable name=TENANT_AAGUID_POLICY_ENTRY) whose joinColumn IS the
-- tenant_id (PK = tenant_id, aaguid), so the shared predicate applies cleanly.
--
-- Idempotent: each ADD_POLICY is wrapped so a re-run (policy already exists,
-- ORA-28101) does not fail the migration.

DECLARE
  PROCEDURE add_tenant_policy(p_table IN VARCHAR2, p_policy IN VARCHAR2) IS
  BEGIN
    DBMS_RLS.ADD_POLICY(
      object_schema   => 'APP_OWNER',
      object_name     => p_table,
      policy_name     => p_policy,
      function_schema => 'APP_OWNER',
      policy_function => 'TENANT_PREDICATE',
      statement_types => 'SELECT,INSERT,UPDATE,DELETE',
      update_check    => TRUE
    );
  EXCEPTION
    WHEN OTHERS THEN
      IF SQLCODE = -28101 THEN NULL;  -- policy already exists → idempotent
      ELSE RAISE;
      END IF;
  END;
BEGIN
  add_tenant_policy('TENANT_ALLOWED_ORIGIN',      'TENANT_ALLOWED_ORIGIN_ISOLATION');
  add_tenant_policy('TENANT_ACCEPTED_FORMAT',     'TENANT_ACCEPTED_FORMAT_ISOLATION');
  add_tenant_policy('TENANT_AAGUID_POLICY',       'TENANT_AAGUID_POLICY_ISOLATION');
  add_tenant_policy('TENANT_AAGUID_POLICY_ENTRY', 'TENANT_AAGUID_ENTRY_ISOLATION');
  add_tenant_policy('TENANT_WEBAUTHN_SNAPSHOT',   'TENANT_WEBAUTHN_SNAPSHOT_ISOLATION');
END;
/
