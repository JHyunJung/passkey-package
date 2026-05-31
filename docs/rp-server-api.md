# RP 서버 API 명세서 (클라이언트 → RP 서버)

클라이언트(웹/앱)가 **RP 서버**에 패스키 등록·로그인을 요청하는 API 명세서입니다. sample-rp의 실제 구현(`/webauthn/**`)을 기준으로 작성했습니다.

- **호출 관계**: 클라이언트(브라우저 JS / 모바일 앱) → **RP 서버**(당신이 구현하는 서버). RP 서버는 내부적으로 Passkey 서버를 호출하지만, 그 부분은 클라이언트에 노출되지 않습니다.
- **범위**: 패스키 등록(`/webauthn/register/**`)과 로그인(`/webauthn/login/**`)입니다.
- **기준 포트**: sample-rp는 `9090`에서 동작합니다(`http://localhost:9090`).

---

## 1. 개요

RP 서버는 클라이언트와 패스키 ceremony를 주고받습니다. 각 ceremony는 **options**(challenge 발급)와 **complete**(결과 검증) 두 단계입니다.

```
┌──────────┐   ① options 요청    ┌─────────────┐        ┌────────────────┐
│ 클라이언트 │ ──────────────────▶ │   RP 서버    │ ─────▶ │  Passkey 서버   │
│ (웹/앱)   │ ◀── options 응답 ── │  (sample-rp) │ ◀───── │  (내부 호출)     │
│          │                     │             │        └────────────────┘
│  navigator.credentials.        │             │
│  create()/get() 수행           │             │
│          │   ② complete 요청   │             │
│          │ ──────────────────▶ │ 검증·세션확립 │
└──────────┘ ◀── 결과 응답 ───── └─────────────┘
```

**핵심 사항입니다.**

- **`userHandle`은 RP 서버가 내부에서 만듭니다.** 클라이언트는 `username`·`displayName`만 보냅니다. userHandle 생성·Passkey 서버 호출·ID Token 검증은 모두 RP 서버가 처리하며 클라이언트에는 보이지 않습니다.
- **세션 기반입니다.** `options` 단계에서 RP 서버가 진행 상태(pending token)를 **세션에 저장**하고, `complete` 단계에서 같은 세션으로 이어받습니다. 클라이언트는 세션 쿠키(`credentials: 'same-origin'`)를 유지해야 합니다.
- **CSRF 보호가 적용됩니다.** 모든 POST 요청에 CSRF 토큰을 헤더(`X-XSRF-TOKEN`)로 보내야 합니다(§2).
- 로그인 성공(`login/complete`) 시 RP 서버가 ID Token을 검증한 뒤 **자체 세션을 확립**합니다. 클라이언트는 별도 토큰을 받지 않고 세션 쿠키로 인증됩니다.

---

## 2. 공통 규약

### 인증 / 세션 / CSRF

- **세션 쿠키**: ceremony는 `options` → `complete`가 같은 세션이어야 합니다. fetch 시 `credentials: 'same-origin'`을 지정합니다.
- **CSRF**: POST 요청에 `X-XSRF-TOKEN` 헤더가 필요합니다. 페이지의 `<meta name="csrf">`에서 토큰을 읽어 보냅니다.

### 응답 Envelope (`ApiResponse<T>`)

모든 응답은 envelope으로 감싸집니다.

```jsonc
// 성공
{
  "success": true,
  "code": "OK",
  "message": "Success",
  "data": { /* 엔드포인트별 응답 */ },
  "error": null,
  "traceId": "a1b2c3...",
  "timestamp": "2026-06-01T12:00:00"
}

// 실패
{
  "success": false,
  "code": "W002",                 // §6 ErrorCode
  "message": "No pending registration in session",
  "data": null,
  "error": { "errorCode": "W002", "fieldErrors": null },
  "traceId": "a1b2c3...",
  "timestamp": "2026-06-01T12:00:00"
}
```

아래 각 엔드포인트의 **응답**은 envelope의 `data` 필드 내용만 표기합니다.

---

## 3. 클라이언트 연동 — base64url ↔ ArrayBuffer 변환 (필독)

> ⚠️ `options` 응답을 `navigator.credentials.create()/get()`에 **그대로 넘기면 `TypeError`로 실패합니다.** WebAuthn JS API는 `challenge`·`user.id`·`excludeCredentials[].id`·`allowCredentials[].id`를 `ArrayBuffer`로 요구하는데, 응답은 전부 **base64url 문자열**이기 때문입니다. 반대로 `complete`에 보낼 때는 브라우저가 돌려준 `ArrayBuffer` 필드들을 다시 **base64url 문자열로 인코딩**해야 합니다.

아래는 sample-rp가 실제로 쓰는 변환 헬퍼입니다(`/js/helpers.js`).

```js
// base64url → ArrayBuffer
export function b64urlToBuf(s) {
  const pad = '='.repeat((4 - s.length % 4) % 4);
  const bin = atob((s + pad).replace(/-/g, '+').replace(/_/g, '/'));
  const buf = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
  return buf.buffer;
}

// ArrayBuffer → base64url
export function bufToB64url(buf) {
  const bin = String.fromCharCode(...new Uint8Array(buf));
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// 등록 options 디코딩 (서버 → 브라우저)
export function decodeCreationOptions(opts) {
  return {
    ...opts,
    challenge: b64urlToBuf(opts.challenge),
    user: { ...opts.user, id: b64urlToBuf(opts.user.id) },
    excludeCredentials: (opts.excludeCredentials || []).map(c => ({ ...c, id: b64urlToBuf(c.id) }))
  };
}

// 등록 결과 인코딩 (브라우저 → 서버)
export function encodeAttestationCredential(cred) {
  return {
    id: cred.id,
    rawId: bufToB64url(cred.rawId),
    type: cred.type,
    response: {
      clientDataJSON:    bufToB64url(cred.response.clientDataJSON),
      attestationObject: bufToB64url(cred.response.attestationObject)
    },
    clientExtensionResults: cred.getClientExtensionResults()
  };
}

// 로그인 options 디코딩
export function decodeRequestOptions(opts) {
  return {
    ...opts,
    challenge: b64urlToBuf(opts.challenge),
    allowCredentials: (opts.allowCredentials || []).map(c => ({ ...c, id: b64urlToBuf(c.id) }))
  };
}

// 로그인 결과 인코딩
export function encodeAssertionCredential(cred) {
  return {
    id: cred.id,
    rawId: bufToB64url(cred.rawId),
    type: cred.type,
    response: {
      clientDataJSON:    bufToB64url(cred.response.clientDataJSON),
      authenticatorData: bufToB64url(cred.response.authenticatorData),
      signature:         bufToB64url(cred.response.signature),
      userHandle: cred.response.userHandle ? bufToB64url(cred.response.userHandle) : null
    },
    clientExtensionResults: cred.getClientExtensionResults()
  };
}
```

> **localhost 주의**: WebAuthn은 `localhost`와 `127.0.0.1`을 별개 origin으로 취급합니다. 로컬 테스트 시 항상 `localhost`로 접근하세요.
>
> **모바일 앱**: 네이티브 앱은 위 JS 대신 OS API를 씁니다. Android는 Credential Manager가 **WebAuthn JSON을 그대로** 처리하므로 변환이 불필요하고, iOS는 AuthenticationServices에서 `challenge`/`userID`를 `Data`로 디코딩해 넘긴 뒤 결과를 base64url 인코딩해야 합니다.

---

## 4. 엔드포인트 레퍼런스

### 4.1 `POST /webauthn/register/options`

패스키 등록을 시작하고 creation options를 받습니다. RP 서버가 내부에서 `userHandle`을 만들고 pending 상태를 세션에 저장합니다.

- **요청 body**:

  | 필드 | 타입 | 필수 | 설명 |
  |---|---|---|---|
  | `username` | string | ✅ | 사용자명입니다. |
  | `displayName` | string | ✅ | authenticator에 표시될 이름입니다. |

- **응답 `data`** (`RegisterOptionsResp`):

  | 필드 | 타입 | 설명 |
  |---|---|---|
  | `publicKeyCredentialCreationOptions` | object | WebAuthn creation options입니다. `decodeCreationOptions()`로 변환해 `navigator.credentials.create()`에 넘깁니다. |

  `publicKeyCredentialCreationOptions` 내부 필드입니다.

  | 필드 | 타입 | 설명 |
  |---|---|---|
  | `challenge` | string | 서버 생성 challenge(base64url)입니다. |
  | `rp.id` / `rp.name` | string | Relying Party ID와 표시 이름입니다. |
  | `user.id` | string | RP 서버가 만든 `userHandle`(base64url)입니다. 클라이언트는 그대로 전달만 합니다. |
  | `user.displayName` / `user.name` | string | 요청의 `displayName` / `username`입니다. |
  | `pubKeyCredParams` | array | 허용 알고리즘 `[ES256(-7), RS256(-257)]`입니다. |
  | `timeout` | number | ceremony 타임아웃(ms)입니다. |
  | `attestation` | string | `none`/`indirect`/`direct`/`enterprise` 중 하나입니다. |
  | `excludeCredentials` | array | 이미 등록한 패스키 목록입니다(중복 등록 방지). |
  | `authenticatorSelection` | object | `userVerification`, `residentKey` 설정입니다. |

- **Request**:

```http
POST /webauthn/register/options
Content-Type: application/json
X-XSRF-TOKEN: <CSRF 토큰>

{ "username": "alice", "displayName": "Alice" }
```

- **Response** `200 OK`:

```jsonc
{
  "success": true,
  "code": "OK",
  "message": "Success",
  "data": {
    "publicKeyCredentialCreationOptions": {
      "challenge": "k7Hd2...Qw",
      "rp":   { "id": "localhost", "name": "Sample RP" },
      "user": { "id": "ZGV2LXVzZXItMDAx", "displayName": "Alice", "name": "alice" },
      "pubKeyCredParams": [ { "type": "public-key", "alg": -7 }, { "type": "public-key", "alg": -257 } ],
      "timeout": 60000,
      "attestation": "none",
      "excludeCredentials": [],
      "authenticatorSelection": { "userVerification": "preferred", "residentKey": "preferred" }
    }
  },
  "error": null,
  "traceId": "a1b2c3d4",
  "timestamp": "2026-06-01T12:00:00"
}
```

### 4.2 `POST /webauthn/register/complete`

브라우저 등록 결과를 보내 검증·저장을 완료합니다. `options`와 같은 세션이어야 합니다(pending token).

- **요청 body**:

  | 필드 | 타입 | 필수 | 설명 |
  |---|---|---|---|
  | `publicKeyCredential` | object | ✅ | `navigator.credentials.create()` 결과를 `encodeAttestationCredential()`로 인코딩한 것입니다. |

- **응답 `data`** (`RegistrationFinishResponse`):

  | 필드 | 타입 | 설명 |
  |---|---|---|
  | `credentialId` | string | 등록된 credential ID(base64url)입니다. |
  | `aaguid` | string | authenticator AAGUID입니다. |
  | `attestationFormat` | string | attestation 포맷입니다. |
  | `createdAt` | string(ISO instant) | 생성 시각입니다. |

- **Request**:

```http
POST /webauthn/register/complete
Content-Type: application/json
X-XSRF-TOKEN: <CSRF 토큰>

{
  "publicKeyCredential": {
    "id": "AbCd...Ef",
    "rawId": "AbCd...Ef",
    "type": "public-key",
    "response": {
      "clientDataJSON": "eyJ0eXBl...",
      "attestationObject": "o2NmbXQ..."
    },
    "clientExtensionResults": {}
  }
}
```

- **Response** `200 OK`:

```jsonc
{
  "success": true,
  "code": "OK",
  "message": "Passkey registered",
  "data": {
    "credentialId": "AbCd...Ef",
    "aaguid": "08987058-cadc-4b81-b6e1-30de50dcbe96",
    "attestationFormat": "none",
    "createdAt": "2026-06-01T12:00:01Z"
  },
  "error": null,
  "traceId": "a1b2c3d4",
  "timestamp": "2026-06-01T12:00:01"
}
```

### 4.3 `POST /webauthn/login/options`

패스키 로그인을 시작하고 request options를 받습니다.

- **요청 body**:

  | 필드 | 타입 | 필수 | 설명 |
  |---|---|---|---|
  | `username` | string | ❌ | 지정하면 해당 사용자의 패스키로 좁힙니다. 생략하면 discoverable(usernameless) 로그인입니다. |

- **응답 `data`** (`LoginOptionsResp`):

  | 필드 | 타입 | 설명 |
  |---|---|---|
  | `publicKeyCredentialRequestOptions` | object | WebAuthn request options입니다. `decodeRequestOptions()`로 변환해 `navigator.credentials.get()`에 넘깁니다. |

  `publicKeyCredentialRequestOptions` 내부 필드입니다.

  | 필드 | 타입 | 설명 |
  |---|---|---|
  | `challenge` | string | 서버 생성 challenge(base64url)입니다. |
  | `rpId` | string | Relying Party ID입니다. |
  | `timeout` | number | ceremony 타임아웃(ms)입니다. |
  | `userVerification` | string | `required` 또는 `preferred`입니다. |
  | `allowCredentials` | array | `username`이 있으면 그 사용자의 패스키 목록, 없으면 `[]`(discoverable)입니다. |

- **Request**:

```http
POST /webauthn/login/options
Content-Type: application/json
X-XSRF-TOKEN: <CSRF 토큰>

{ "username": "alice" }
```

- **Response** `200 OK`:

```jsonc
{
  "success": true,
  "code": "OK",
  "message": "Success",
  "data": {
    "publicKeyCredentialRequestOptions": {
      "challenge": "p9Lm3...Zx",
      "rpId": "localhost",
      "timeout": 60000,
      "userVerification": "preferred",
      "allowCredentials": [ { "type": "public-key", "id": "AbCd...Ef" } ]
    }
  },
  "error": null,
  "traceId": "b2c3d4e5",
  "timestamp": "2026-06-01T12:05:00"
}
```

### 4.4 `POST /webauthn/login/complete`

브라우저 로그인 결과를 보냅니다. RP 서버가 ID Token을 검증하고 **세션을 확립**합니다.

- **요청 body**:

  | 필드 | 타입 | 필수 | 설명 |
  |---|---|---|---|
  | `publicKeyCredential` | object | ✅ | `navigator.credentials.get()` 결과를 `encodeAssertionCredential()`로 인코딩한 것입니다. |

- **응답 `data`**: `null` (성공 시 세션이 확립됩니다. 이후 요청은 세션 쿠키로 인증됩니다.)

- **Request**:

```http
POST /webauthn/login/complete
Content-Type: application/json
X-XSRF-TOKEN: <CSRF 토큰>

{
  "publicKeyCredential": {
    "id": "AbCd...Ef",
    "rawId": "AbCd...Ef",
    "type": "public-key",
    "response": {
      "clientDataJSON": "eyJ0eXBl...",
      "authenticatorData": "SZYN5Y...",
      "signature": "MEUCIQ...",
      "userHandle": "ZGV2LXVzZXItMDAx"
    },
    "clientExtensionResults": {}
  }
}
```

- **Response** `200 OK`:

```jsonc
{
  "success": true,
  "code": "OK",
  "message": "Success",
  "data": null,
  "error": null,
  "traceId": "b2c3d4e5",
  "timestamp": "2026-06-01T12:05:02"
}
```

---

## 5. 전체 흐름 (브라우저 JS)

sample-rp의 실제 등록·로그인 흐름입니다.

### 등록

```js
import { decodeCreationOptions, encodeAttestationCredential, postJson } from '/js/helpers.js';

const start = await postJson('/webauthn/register/options', { username, displayName });
const cred = await navigator.credentials.create({
  publicKey: decodeCreationOptions(start.publicKeyCredentialCreationOptions)
});
const fin = await postJson('/webauthn/register/complete', {
  publicKeyCredential: encodeAttestationCredential(cred)
});
// fin.credentialId 등록 완료
```

### 로그인

```js
import { decodeRequestOptions, encodeAssertionCredential, postJson } from '/js/helpers.js';

const start = await postJson('/webauthn/login/options', { username });   // username 생략 가능
const assertion = await navigator.credentials.get({
  publicKey: decodeRequestOptions(start.publicKeyCredentialRequestOptions)
});
await postJson('/webauthn/login/complete', {
  publicKeyCredential: encodeAssertionCredential(assertion)
});
window.location = '/';   // 세션 확립됨
```

`postJson`은 CSRF 토큰을 붙이고 envelope을 풀어 `data`를 반환하며, 실패 시 `code`/`message`/`traceId`를 담은 에러를 던집니다.

```js
export async function postJson(url, body) {
  const csrf = document.querySelector('meta[name=csrf]')?.content ?? '';
  const res  = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
    body: JSON.stringify(body),
    credentials: 'same-origin'
  });
  const env = await res.json();
  if (!env.success) {
    const err = new Error(env.message || 'Unknown error');
    err.code = env.code; err.traceId = env.traceId; err.fieldErrors = env.error?.fieldErrors;
    throw err;
  }
  return env.data;
}
```

---

## 6. 에러 코드

실패 응답은 §2 envelope의 `code`로 식별합니다.

| code | HTTP | 의미 | 상황 |
|---|---|---|---|
| `C001` | 400 | INVALID_INPUT | 필드 검증 실패(예: `username`/`displayName` 누락), 잘못된 입력입니다. |
| `C002` | 405 | METHOD_NOT_ALLOWED | 잘못된 HTTP 메서드입니다. |
| `C003` | 404 | ENTITY_NOT_FOUND | 리소스가 없습니다. |
| `C999` | 500 | INTERNAL_SERVER_ERROR | 서버 오류입니다. |
| `A001` | 401 | UNAUTHORIZED | 인증이 필요합니다. |
| `W001` | 409 | USERNAME_TAKEN | 이미 등록된 username입니다. |
| `W002` | 400 | PENDING_REG_MISSING | 세션에 진행 중인 등록이 없습니다(`options` 없이 `complete` 호출, 또는 세션 만료). |
| `W003` | 400 | PENDING_AUTH_MISSING | 세션에 진행 중인 로그인이 없습니다. |
| `P001` | 502 | PASSKEY_API_ERROR | RP 서버가 호출한 Passkey 서버에서 오류가 발생했습니다. |
| `P003` | 429 | PASSKEY_RATE_LIMITED | Passkey 서버 rate limit을 초과했습니다. |
| `P004` | 401 | PASSKEY_ID_TOKEN | ID Token 검증에 실패했습니다(로그인 ceremony 검증 실패 포함). |

> `P00x` 코드는 RP 서버가 Passkey 서버와 통신하면서 만나는 오류입니다. 클라이언트 입장에서는 "서버 측 일시 오류"로 처리하고 재시도하면 됩니다.

### 에러 응답 예시

세션 만료로 등록 완료 실패 (`400`):

```jsonc
{
  "success": false,
  "code": "W002",
  "message": "No pending registration in session",
  "data": null,
  "error": { "errorCode": "W002", "fieldErrors": null },
  "traceId": "e5f6a7b8",
  "timestamp": "2026-06-01T12:03:00"
}
```

필드 검증 실패 (`400`):

```jsonc
{
  "success": false,
  "code": "C001",
  "message": "Invalid input value",
  "data": null,
  "error": {
    "errorCode": "C001",
    "fieldErrors": [ { "field": "username", "rejectedValue": null, "reason": "must not be blank" } ]
  },
  "traceId": "e5f6a7b8",
  "timestamp": "2026-06-01T12:00:00"
}
```
