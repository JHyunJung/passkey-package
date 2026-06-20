-- ============================================================
-- tenant_allowed_origin.origin 에 Android 네이티브 앱 origin 형식 허용.
-- WebAuthn Android 클라이언트는 clientData.origin 을
--   android:apk-key-hash:<base64url(SHA-256(서명인증서))>  (padding 없는 43자)
-- 로 보낸다. 기존 web(https?://) origin 검증은 그대로 두고, OR 로 android
-- key-hash 패턴을 추가한다. 제약 이름은 V21 과 동일하게 유지(재현성).
-- ============================================================

ALTER TABLE tenant_allowed_origin DROP CONSTRAINT ck_tao_origin_format;

ALTER TABLE tenant_allowed_origin ADD CONSTRAINT ck_tao_origin_format CHECK (
  REGEXP_LIKE(origin, '^https?://[A-Za-z0-9][A-Za-z0-9.-]*(:[1-9][0-9]{0,4})?$')
  OR REGEXP_LIKE(origin, '^android:apk-key-hash:[A-Za-z0-9_-]{43}$')
);
