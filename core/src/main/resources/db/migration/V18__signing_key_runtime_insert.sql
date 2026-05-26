-- Bootstrap-only signing-key INSERT for APP_RUNTIME via a definer-rights
-- PL/SQL package. Phase 3 codex review (T6 follow-up) flagged a plain
-- `GRANT INSERT ON signing_key TO APP_RUNTIME` as too broad: a
-- compromised runtime principal could insert attacker-controlled
-- ROTATED rows, and SigningKeyProvider.publicJwkSet exposes ACTIVE+
-- ROTATED → JWKS would serve an attacker public key → ID Token forgery.
--
-- This package narrows the attack surface to exactly one operation:
-- "insert the very first ACTIVE row if none exists yet". The package
-- runs with definer rights (APP_OWNER), and only APP_RUNTIME has
-- EXECUTE on it. APP_RUNTIME itself has no direct INSERT grant on
-- signing_key (V16 grants SELECT only).
--
-- Operational expectation:
--   - admin-app boots first and calls KeyRotationService.rotate
--     (which holds direct INSERT via APP_ADMIN's V15 grant).
--   - passkey-app boots after admin-app, finds the ACTIVE row, and
--     never calls this package.
--   - IT environments (Fido2EndToEndIT) boot only passkey-app; the
--     package's "create only if absent" rule produces the initial
--     ACTIVE row without elevating APP_RUNTIME's privileges otherwise.

CREATE OR REPLACE PACKAGE APP_OWNER.signing_key_bootstrap_pkg
  AUTHID DEFINER
AS
  -- Inserts a new ACTIVE row ONLY if there is currently no ACTIVE row.
  -- The function-based unique index `signing_key_one_active_uix` (V15)
  -- is a belt-and-suspenders safety net; this package layer prevents
  -- runtime from issuing UPDATE/DELETE or arbitrary INSERTs.
  --
  -- p_inserted OUT = 1 if we created the row, 0 if an ACTIVE row was
  -- already present and we left it alone.
  PROCEDURE bootstrap_active(
    p_kid           IN  VARCHAR2,
    p_alg           IN  VARCHAR2,
    p_public_jwk    IN  CLOB,
    p_private_pkcs8 IN  BLOB,
    p_inserted      OUT NUMBER);
END signing_key_bootstrap_pkg;
/

CREATE OR REPLACE PACKAGE BODY APP_OWNER.signing_key_bootstrap_pkg AS

  PROCEDURE bootstrap_active(
    p_kid           IN  VARCHAR2,
    p_alg           IN  VARCHAR2,
    p_public_jwk    IN  CLOB,
    p_private_pkcs8 IN  BLOB,
    p_inserted      OUT NUMBER) IS
    v_count NUMBER;
  BEGIN
    SELECT COUNT(*) INTO v_count FROM signing_key WHERE status = 'ACTIVE';
    IF v_count > 0 THEN
      p_inserted := 0;
      RETURN;
    END IF;
    INSERT INTO signing_key (id, kid, alg, status, public_jwk, private_pkcs8, created_at)
      VALUES (signing_key_seq.NEXTVAL, p_kid, p_alg, 'ACTIVE',
              p_public_jwk, p_private_pkcs8, SYSTIMESTAMP);
    p_inserted := 1;
  EXCEPTION
    WHEN DUP_VAL_ON_INDEX THEN
      -- Concurrent bootstrap: another caller won. Treat as no-op.
      p_inserted := 0;
  END bootstrap_active;

END signing_key_bootstrap_pkg;
/

-- APP_RUNTIME may call the package only; it has no direct INSERT on
-- signing_key. APP_ADMIN retains direct INSERT via V15 for rotation.
GRANT EXECUTE ON APP_OWNER.signing_key_bootstrap_pkg TO APP_RUNTIME;
