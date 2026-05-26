-- Seed two initial operators so AdminFlowIT and local development have
-- something to log in with. The temporary passwords ("alice-temp-pw",
-- "bob-temp-pw") are documented in followup-notes as "MUST be rotated
-- before any non-local deploy".

INSERT INTO admin_user (id, email, bcrypt_hash, role, enabled, created_at)
  VALUES (admin_user_seq.NEXTVAL, 'alice@crosscert.com', '$2a$12$jpftll2M2sOc8XRs99Zw0ODgKWBiRKQcIieK/UqUBbizW7xKI8awS', 'ADMIN', 'Y', SYSTIMESTAMP);

INSERT INTO admin_user (id, email, bcrypt_hash, role, enabled, created_at)
  VALUES (admin_user_seq.NEXTVAL, 'bob@crosscert.com',   '$2a$12$gvD5tGra6vKnSn/9cxqfQOKZOzlzp4LCg276Ddfkpwl8Kk24Zbb1G', 'VIEWER','Y', SYSTIMESTAMP);

COMMIT;
