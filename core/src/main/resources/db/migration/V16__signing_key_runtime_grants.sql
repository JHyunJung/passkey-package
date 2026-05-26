-- passkey-app boots as APP_RUNTIME_USER and reads signing_key (and the
-- public_jwk column) for JWKS construction + signing key load. It does
-- NOT write — admin-app owns lifecycle transitions.
--
-- ddl-validate at passkey-app boot needs SELECT on the table + sequence
-- (mirrors V12/V13 pattern from Phase 2 admin_user and audit_log).

GRANT SELECT ON signing_key TO APP_RUNTIME;
GRANT SELECT ON signing_key_seq TO APP_RUNTIME;
