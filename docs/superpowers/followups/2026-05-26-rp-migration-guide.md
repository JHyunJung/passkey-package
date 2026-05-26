# RP Migration Guide — Phase 4 API Response Standardization

**Date:** 2026-05-26
**Affects:** RP-side JavaScript clients calling `/api/v1/rp/registration/**` and `/api/v1/rp/authentication/**` on passkey-app.

## What changed

passkey-app's WebAuthn ceremony endpoints (4 methods) now return a uniform `ApiResponse<T>` envelope instead of raw `PublicKeyCredentialCreationOptions` / `PublicKeyCredentialRequestOptions` / verification result shapes.

**Unchanged (no migration needed):**
- `GET /.well-known/jwks.json` — still RFC 7517 wire format. All JWT verifier libraries (Nimbus, jose4j, jsonwebtoken, jose) continue to work without changes.

## Before / After

### Registration start

**Before:**
```javascript
const options = await fetch('/api/v1/rp/registration/start', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({ /* ... */ })
}).then(r => r.json());
const credential = await startRegistration(options); // simplewebauthn
```

**After:**
```javascript
const response = await fetch('/api/v1/rp/registration/start', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({ /* ... */ })
}).then(r => r.json());
if (!response.success) {
  throw new Error(`[${response.code}] ${response.message}`);
}
const credential = await startRegistration(response.data); // ← .data
```

### Registration finish, Authentication start, Authentication finish

Same pattern — `.data` is the previous top-level body.

## Envelope shape

Success:

```json
{
  "success": true,
  "code": "OK",
  "message": "Success",
  "data": { /* ...the actual WebAuthn payload... */ },
  "traceId": "abc1234567890def",
  "timestamp": "2026-05-26T10:00:00Z"
}
```

Error:

```json
{
  "success": false,
  "code": "F001",
  "message": "Challenge invalid or expired",
  "error": { "errorCode": "F001" },
  "traceId": "abc1234567890def",
  "timestamp": "2026-05-26T10:00:00Z"
}
```

Validation error (with field-level details):

```json
{
  "success": false,
  "code": "C001",
  "message": "Invalid input value",
  "error": {
    "errorCode": "C001",
    "fieldErrors": [
      { "field": "challengeId", "rejectedValue": null, "reason": "must not be blank" }
    ]
  },
  "traceId": "abc1234567890def",
  "timestamp": "2026-05-26T10:00:00Z"
}
```

## Error code reference (FIDO2 prefix `F`)

| Code | Status | Meaning |
|------|--------|---------|
| F001 | 400 | Challenge invalid or expired |
| F002 | 400 | Registration verification failed |
| F003 | 401 | Authentication verification failed |
| F004 | 403 | Attestation rejected by policy |
| F005 | 403 | Authenticator revoked (AAGUID blocklist) |

## Generic prefixes (shared across all endpoints)

| Prefix | Domain | Examples |
|--------|--------|----------|
| `C` | Common | `C001` invalid input, `C005` type mismatch, `C999` server error |
| `A` | Auth | `A001` unauthorized, `A002` access denied |

## Trace ID

Every response includes an `X-Trace-Id` header and a `traceId` field in the envelope. When reporting bugs, include this value — it matches server-side log entries via MDC.

You can also send your own `X-Trace-Id` request header (recommended: 16 hex chars) to thread a distributed trace through your call.

## Recommended client helper

```javascript
async function rp(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(body),
  });
  const env = await res.json();
  if (!env.success) {
    const err = new Error(`[${env.code}] ${env.message}`);
    err.code = env.code;
    err.fieldErrors = env.error?.fieldErrors;
    err.traceId = env.traceId;
    throw err;
  }
  return env.data;
}

// Usage:
const options = await rp('/api/v1/rp/registration/start', { /* ... */ });
const cred = await startRegistration(options);
```

## JWKS still untouched

```javascript
// Unchanged — RFC 7517 wire format preserved
const jwks = await fetch('https://passkey.crosscert.com/.well-known/jwks.json')
  .then(r => r.json());
// jwks.keys[0].kty === "RSA"
```

Standard JWT verifier libraries continue to work without any changes:

```java
// Java / Nimbus — unchanged
JWKSet jwkSet = JWKSet.load(
    new URL("https://passkey.crosscert.com/.well-known/jwks.json"));
```

```javascript
// Node / jose — unchanged
import { createRemoteJWKSet } from 'jose';
const JWKS = createRemoteJWKSet(
  new URL('https://passkey.crosscert.com/.well-known/jwks.json'));
```

## Migration checklist (RP-side)

- [ ] Identify all call sites to `/api/v1/rp/registration/start`, `/finish`, `/api/v1/rp/authentication/start`, `/finish`
- [ ] Wrap response with `.data` access OR adopt the recommended `rp()` helper
- [ ] Test error path: trigger F001 (expired challenge) and confirm UI surfaces the message
- [ ] Verify JWKS verification still works (no changes expected)
- [ ] Log `traceId` from failed responses for support
