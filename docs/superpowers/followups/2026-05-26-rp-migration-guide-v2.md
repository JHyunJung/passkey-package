# RP Migration Guide v2 — Phase 6 UUID Migration

**Date:** 2026-05-27
**Affects:** RP-side code that consumes Crosscert Passkey-issued ID Tokens.
**Predecessor:** v1 (`2026-05-26-rp-migration-guide.md`, Phase 4 envelope change)

## What changed in Phase 6

Database primary keys migrated from `Long` / `String` to **UUID** (Oracle `RAW(16)`). This is mostly internal — but **one wire-format change reaches the RP**: the `cred_id` claim in ID Tokens grew from 11 base64url chars (8-byte Long) to 22 base64url chars (16-byte UUID).

**Unchanged:**
- `/.well-known/jwks.json` — RFC 7517 wire format preserved (Phase 4 invariant)
- WebAuthn ceremony endpoints (`/api/v1/rp/registration/**`, `/api/v1/rp/authentication/**`) — request/response shapes inside `ApiResponse<T>` envelope unchanged
- JWT `iss`, `sub`, `aud`, `iat`, `exp` claims — unchanged
- JWT signing key (`kid`) — still SHA-256 thumbprint of the public JWK
- Tenant slug-based URLs — `/admin/api/tenants/acme` continues to work (admin-only; RP-irrelevant)

## The `cred_id` claim format change

### Before (Phase 5 and earlier)

```json
{
  "iss": "https://passkey.crosscert.com",
  "sub": "user-...",
  "aud": "acme",
  "cred_id": "AAAAAAAAACo",  // 11 chars — base64url of 8 bytes (Long ID)
  "iat": 1748234567,
  "exp": 1748235467
}
```

`cred_id` = base64url-encode(8-byte big-endian Long credentialId), no padding.

### After (Phase 6)

```json
{
  "iss": "https://passkey.crosscert.com",
  "sub": "user-...",
  "aud": "acme",
  "cred_id": "AAAAAAAAAAAAAAAAAAAAAA",  // 22 chars — base64url of 16 bytes (UUID)
  "iat": 1748234567,
  "exp": 1748235467
}
```

`cred_id` = base64url-encode(16-byte big-endian UUID credentialId), no padding.

The UUID bytes are MSB (8 bytes) || LSB (8 bytes), i.e., the same big-endian byte layout `java.util.UUID#getMostSignificantBits()` / `#getLeastSignificantBits()` produce.

## RP impact assessment

### If your RP code...

| ...does | Impact | Action |
|---------|--------|--------|
| Treats `cred_id` as **opaque string** (stores it, looks it up, never parses) | None | Nothing to do. New tokens have a longer string; old tokens (none in prod) had a shorter one. Storage column should accept up to 32 chars to be safe. |
| **Parses `cred_id` as 8-byte Long** | Breaks | Switch to UUID parsing (see recipe below). |
| **Compares `cred_id` length === 11** | Breaks | Remove the length check or switch to `=== 22`. |
| Uses `cred_id` as a **DB foreign key referencing 8-byte BIGINT** | Breaks | Migrate the column to `RAW(16)` / `BINARY(16)` / `CHAR(22)`. |

### Quick decoder snippets

**Java (Nimbus JOSE+JWT)**

```java
String credIdB64 = claims.getStringClaim("cred_id");
byte[] credIdBytes = Base64.getUrlDecoder().decode(credIdB64);
assert credIdBytes.length == 16 : "cred_id must be 16 bytes (UUID)";

ByteBuffer bb = ByteBuffer.wrap(credIdBytes);
UUID credentialId = new UUID(bb.getLong(), bb.getLong());
// Use credentialId.toString() for storage/logging
```

**Node / jose**

```javascript
const credIdB64 = payload.cred_id;
const credIdBytes = Buffer.from(credIdB64, 'base64url');
if (credIdBytes.length !== 16) throw new Error('cred_id must be 16 bytes');

// Format as UUID string (canonical 8-4-4-4-12):
const hex = credIdBytes.toString('hex');
const uuidStr = `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
```

**Python / PyJWT**

```python
import base64, uuid

cred_id_b64 = claims['cred_id']
# Pad to multiple of 4 chars for base64.urlsafe_b64decode (22 → 24)
pad = '=' * (-len(cred_id_b64) % 4)
cred_id_bytes = base64.urlsafe_b64decode(cred_id_b64 + pad)
assert len(cred_id_bytes) == 16
credential_id = uuid.UUID(bytes=cred_id_bytes)
```

**Go**

```go
import (
    "encoding/base64"
    "github.com/google/uuid"
)
credIdBytes, _ := base64.RawURLEncoding.DecodeString(claims["cred_id"].(string))
if len(credIdBytes) != 16 {
    return errors.New("cred_id must be 16 bytes")
}
credentialId, _ := uuid.FromBytes(credIdBytes)
```

## Migration timeline

- **2026-05-27 (today):** Phase 6 merged to `main`. dev/staging running new format.
- **Prod cutover:** Coordinated with deployment. Crosscert team will notify the RP integration mailing list ≥ 1 week before flipping prod.
- **Backwards compatibility window:** Crosscert holds no production data with old `cred_id` format (Phase 6 spec premise). So **there's no overlap period** — once prod is flipped, all new ID Tokens carry 22-char `cred_id`. Older tokens (if any cached) become unverifiable when their key rotates out of grace window.

## How to test your change before prod

Crosscert's dev environment (`https://passkey-dev.crosscert.com`) runs Phase 6 from 2026-05-27. Hit the WebAuthn ceremony there and verify your `cred_id` parser handles the 22-char format. The full JWKS at `https://passkey-dev.crosscert.com/.well-known/jwks.json` is unchanged in format (RFC 7517) so signature verification doesn't need any code change.

## Related changes (internal — not RP-facing)

These changes happen on Crosscert's side and have no RP impact, but are listed for transparency:

- All Oracle PK columns are now `RAW(16)` (UUID-encoded)
- VPD tenant isolation policy uses `HEXTORAW(SYS_CONTEXT('APP_CTX','TENANT_ID'))` (RAW(16) compare)
- Admin URL `/admin/api/tenants/{slug}` continues to use slug strings (`acme`, `globex`); slug is a separate UNIQUE column from the UUID PK
- `SchedulerLease.id` is UUID with `name` UNIQUE — internal scheduler lookup unchanged externally
- `signing_key.kid` (SHA-256 thumbprint) is unchanged — JWKS continues to expose stable kid values

## Reporting issues

If your client breaks unexpectedly after the cutover, please include:
- Library + version (e.g., `jose 5.2.4`, `nimbus-jose-jwt 9.40`)
- A failing `cred_id` value (the 22-char base64url string)
- The error message + stack trace
- Your `X-Trace-Id` from the failing request (Phase 4 envelope)

Submit to: `support@crosscert.com` with subject `[Passkey Phase 6 RP]`.
