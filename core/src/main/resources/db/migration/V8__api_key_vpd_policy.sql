-- VPD policy on api_key, mirroring V3's CREDENTIAL_TENANT_ISOLATION.
-- The same APP_OWNER.tenant_predicate function works because the
-- predicate just compares tenant_id against SYS_CONTEXT.
--
-- update_check=TRUE means cross-tenant INSERT/UPDATE is rejected
-- with ORA-28115, same as credential. APP_ADMIN with EXEMPT ACCESS
-- POLICY bypasses (admin issues keys for any tenant).

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

-- ============================================================
-- Definer-rights API-key lookup package.
--
-- The auth filter (ApiKeyAuthFilter, T16) receives an X-API-Key
-- header carrying no tenant id, so it CANNOT set APP_CTX before
-- looking up the row. With VPD active and no APP_CTX, a plain
-- SELECT returns zero rows.
--
-- Workaround: the package below uses AUTHID DEFINER (Oracle default
-- for packages), so all SELECTs/UPDATEs inside it execute with
-- APP_OWNER's privileges. Granting EXEMPT ACCESS POLICY to APP_OWNER
-- (the next statement) makes those internal SELECT/UPDATEs bypass
-- VPD on api_key. The CALLER (APP_RUNTIME) does NOT receive
-- EXEMPT ACCESS POLICY — only the package body running as APP_OWNER
-- does — so direct queries from APP_RUNTIME sessions are still
-- VPD-filtered. This is the narrowest possible bypass surface.
--
-- The lookup function returns the api_key row's identity + key_hash
-- + tenant_id + expiration markers. The caller (ApiKeyAuthFilter)
-- BCrypt-verifies the secret and then sets TenantContextHolder so
-- the rest of the request sees the correct tenant.

GRANT EXEMPT ACCESS POLICY TO APP_OWNER;

-- Step 2: the lookup package.
CREATE OR REPLACE PACKAGE APP_OWNER.api_key_lookup_pkg AUTHID DEFINER AS
  -- Lookup record: returns the data the auth filter needs to BCrypt-verify
  -- and set TenantContextHolder. Returns no_data_found when key_prefix is
  -- unknown — caller treats that as 401.
  TYPE api_key_row IS RECORD (
    id            NUMBER,
    tenant_id     VARCHAR2(64),
    key_hash      VARCHAR2(255),
    expires_at    TIMESTAMP WITH TIME ZONE,
    revoked_at    TIMESTAMP WITH TIME ZONE
  );

  -- find_by_prefix returns row-or-empty via OUT params + a found boolean.
  -- Using OUT params (rather than RETURN of a record) keeps the JDBC
  -- binding shape simple: a CallableStatement with registered OUT params.
  PROCEDURE find_by_prefix(
    p_prefix       IN  VARCHAR2,
    p_found        OUT NUMBER,           -- 1 if found, 0 otherwise
    p_id           OUT NUMBER,
    p_tenant_id    OUT VARCHAR2,
    p_key_hash     OUT VARCHAR2,
    p_expires_at   OUT TIMESTAMP WITH TIME ZONE,
    p_revoked_at   OUT TIMESTAMP WITH TIME ZONE);

  -- touch_last_used updates last_used_at after BCrypt success. Caller
  -- runs this AFTER TenantContextHolder is set; the column-scoped UPDATE
  -- grant on APP_RUNTIME allows it to run from the runtime path too,
  -- but we go through this package so the touch is logged with the
  -- exact API key id (and survives a future tightening of grants).
  PROCEDURE touch_last_used(p_id IN NUMBER, p_now IN TIMESTAMP WITH TIME ZONE);
END api_key_lookup_pkg;
/

CREATE OR REPLACE PACKAGE BODY APP_OWNER.api_key_lookup_pkg AS

  PROCEDURE find_by_prefix(
    p_prefix       IN  VARCHAR2,
    p_found        OUT NUMBER,
    p_id           OUT NUMBER,
    p_tenant_id    OUT VARCHAR2,
    p_key_hash     OUT VARCHAR2,
    p_expires_at   OUT TIMESTAMP WITH TIME ZONE,
    p_revoked_at   OUT TIMESTAMP WITH TIME ZONE) IS
  BEGIN
    -- Definer rights + APP_OWNER's EXEMPT ACCESS POLICY mean this
    -- SELECT bypasses the API_KEY_TENANT_ISOLATION policy even though
    -- APP_RUNTIME (the caller) does not have EXEMPT ACCESS POLICY.
    -- key_prefix is globally UNIQUE in the table (V7), so at most one
    -- row matches.
    SELECT id, tenant_id, key_hash, expires_at, revoked_at
      INTO p_id, p_tenant_id, p_key_hash, p_expires_at, p_revoked_at
      FROM api_key
     WHERE key_prefix = p_prefix;
    p_found := 1;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      p_found := 0;
      p_id := NULL;
      p_tenant_id := NULL;
      p_key_hash := NULL;
      p_expires_at := NULL;
      p_revoked_at := NULL;
  END find_by_prefix;

  PROCEDURE touch_last_used(p_id IN NUMBER, p_now IN TIMESTAMP WITH TIME ZONE) IS
  BEGIN
    -- Even though APP_OWNER's EXEMPT ACCESS POLICY would let this
    -- procedure update ANY tenant's key, we explicitly constrain to
    -- the caller's tenant via SYS_CONTEXT. The auth filter sets
    -- TenantContextHolder (and thus APP_CTX) before calling this
    -- procedure, so the predicate matches exactly the row that just
    -- authenticated. This closes a hardening gap codex review flagged.
    UPDATE api_key
       SET last_used_at = p_now
     WHERE id = p_id
       AND tenant_id = SYS_CONTEXT('APP_CTX','TENANT_ID');
  END touch_last_used;

END api_key_lookup_pkg;
/

-- Grant EXECUTE on the package to both roles. APP_RUNTIME calls it
-- from the auth filter; APP_ADMIN gets it for completeness.
GRANT EXECUTE ON APP_OWNER.api_key_lookup_pkg TO APP_RUNTIME;
GRANT EXECUTE ON APP_OWNER.api_key_lookup_pkg TO APP_ADMIN;
