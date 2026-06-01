# RP 서버 API 명세서 (클라이언트 → RP 서버)

클라이언트(웹/앱)가 **RP 서버**에 패스키 등록·인증을 요청하는 API 명세서입니다. sample-rp의 실제 구현(`/passkey/**`)을 기준으로 작성했습니다.

- **호출 관계**: 클라이언트(브라우저 JS / 모바일 앱) → **RP 서버**(당신이 구현하는 서버). RP 서버는 내부적으로 Passkey 서버를 호출하지만, 그 부분은 클라이언트에 노출되지 않습니다.
- **범위**: 패스키 등록(`/passkey/register/**`)과 인증(`/passkey/authenticate/**`)입니다.
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
- 인증 성공(`authenticate/finish`) 시 RP 서버가 ID Token을 검증한 뒤 **자체 세션을 확립**합니다. 클라이언트는 별도 토큰을 받지 않고 세션 쿠키로 인증됩니다.

### 시작하기 전 (온보딩)

RP 서버를 Passkey 서버에 연결하려면 먼저 다음이 필요합니다.

1. **테넌트 생성** — 관리 콘솔에서 RP를 위한 테넌트를 만듭니다. 이때 `rpId`(RP 서버 도메인)와 `allowedOrigins`(RP 서버 origin 목록)를 등록합니다. 이 값이 패스키의 신뢰 경계가 됩니다.
2. **API key 발급** — 그 테넌트에 `registration`·`authentication` scope를 가진 API key를 발급받습니다. 평문 키는 발급 시 1회만 표시됩니다.
3. **tenantId 확인** — ID Token의 `iss`/`aud` 검증에 쓸 tenantId(UUID 형식)를 받아둡니다.

> 테넌트 생성·키 발급·도메인 등록의 구체 절차는 운영 환경에 따라 다릅니다(관리 콘솔 또는 운영팀). 로컬/단일 인스턴스 구성은 [single-instance-deployment.md](single-instance-deployment.md) §4를 참조하세요.

### ID Token 검증 (RP 서버가 직접 구현할 때)

`authenticate/finish`에서 Passkey 서버가 발급한 ID Token(RS256 JWT)을 RP 서버가 검증합니다. **Java라면 제공되는 검증 라이브러리를 쓰면 아래가 내장**되며, 직접 구현할 때 다음을 지켜야 합니다.

- **서명**: JWKS(`/.well-known/jwks.json`)에서 JWT 헤더의 `kid`에 해당하는 RSA 공개키로 RS256 검증. `alg`가 `RS256`인지도 확인(alg confusion 방지).
- **`exp`**: 만료 검증. 시계 오차를 고려해 **clock skew leeway(예: ±60초)** 를 두는 것을 권장합니다.
- **`iss`/`aud`**: `iss = <Passkey 서버 주소>/<tenantId>`, `aud = <tenantId>`. **tenantId는 UUID 소문자 대시 형식**(`7f00dead-0000-...`)으로 옵니다. 설정값이 RAW hex 등 다른 표기면 비교 전 UUID로 정규화하세요(§6 P004).
- **JWKS 캐싱**: 매 요청마다 JWKS를 가져오지 말고 캐시합니다(권장 TTL 수 분). **키 회전 주의** — 서버가 서명키를 교체하면 캐시에 없는 새 `kid`가 올 수 있습니다. `kid`를 못 찾으면 캐시를 즉시 무효화하고 1회 재조회하도록 구현하는 것이 안전합니다(TTL만 의존하면 회전 직후 TTL 동안 검증이 실패할 수 있음).

---

## 2. 공통 규약

### 인증 / 세션 / CSRF

- **세션 쿠키**: ceremony는 `options` → `complete`가 같은 세션이어야 합니다. fetch 시 `credentials: 'same-origin'`을 지정합니다.
- **CSRF**: 모든 POST 요청에 CSRF 토큰을 `X-XSRF-TOKEN` 헤더로 보내야 합니다(누락 시 `403 Forbidden`).

#### CSRF 토큰을 얻는 방법

RP 서버는 Spring Security의 `CookieCsrfTokenRepository`를 씁니다. 토큰은 **쿠키 `XSRF-TOKEN`**으로 내려오며, 이를 읽어 **헤더 `X-XSRF-TOKEN`**에 그대로 실어 보내는 방식입니다. 클라이언트 종류별로 토큰을 얻는 방법이 다릅니다.

| 클라이언트 | 토큰 출처 | 보내는 방법 |
|---|---|---|
| 서버 렌더링 페이지(Thymeleaf) | 페이지의 `<meta name="csrf">` | `document.querySelector('meta[name=csrf]').content`를 헤더에 실음 |
| SPA / fetch / 모바일 웹뷰 | 쿠키 `XSRF-TOKEN` | 쿠키 값을 읽어 헤더에 실음 |
| 모바일 네이티브 앱 | 응답의 `Set-Cookie: XSRF-TOKEN=...` | 쿠키 jar에서 값을 꺼내 헤더에 실음(§7) |

쿠키는 `HttpOnly`가 **아니므로**(`withHttpOnlyFalse`) JavaScript에서 읽을 수 있습니다. SPA·모바일 웹뷰에서 쿠키로 토큰을 얻는 예시입니다.

```js
// 쿠키 XSRF-TOKEN 값을 읽어 헤더에 싣는다 (SPA / 웹뷰용)
function csrfToken() {
  return document.cookie.split('; ')
    .find(c => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '';
}

await fetch('/passkey/register/begin', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
  body: JSON.stringify({ username, displayName }),
  credentials: 'same-origin'
});
```

> **토큰 발급 시점**: 쿠키 `XSRF-TOKEN`은 RP 서버 페이지에 **GET으로 한 번 접근하면** 응답의 `Set-Cookie`로 내려옵니다. SPA·앱은 첫 POST 전에 RP 서버에 GET 요청(예: `/`)을 한 번 보내 쿠키를 받아두면 됩니다. 쿠키 값은 URL-decode가 필요할 수 있으니 헤더에 실을 때 디코딩하세요.

### 로그아웃 / 로그인 상태

- **로그아웃**: `POST /logout` — 세션을 무효화하고 `/`로 리다이렉트(`302`)합니다. CSRF 토큰이 필요합니다. 로그아웃 후 클라이언트는 보관 중인 세션 쿠키를 폐기하세요.
- **로그인 상태 확인**: 별도 상태 조회 엔드포인트는 없습니다. 보호된 페이지/리소스를 호출했을 때 **인증되어 있으면 정상 응답, 아니면 `A001`(401)** 로 판단합니다(세션 쿠키 유효성 기반).

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

### 4.1 `POST /passkey/register/begin`

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
  | `excludeCredentials` | array | 이미 등록한 패스키 목록 `[{type:"public-key",id:<base64url>}]`입니다(중복 등록 방지). `transports` 힌트는 **의도적으로 포함하지 않습니다**(없어도 동작하며, 등록 시점 정보라 stale 위험이 있어 생략). |
  | `authenticatorSelection` | object | `userVerification`, `residentKey` 설정입니다. |

- **Request**:

```http
POST /passkey/register/begin
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

### 4.2 `POST /passkey/register/finish`

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
POST /passkey/register/finish
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

### 4.3 `POST /passkey/authenticate/begin`

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
  | `allowCredentials` | array | `username`이 있으면 그 사용자의 패스키 목록, 없으면 `[]`(discoverable)입니다. `transports` 힌트는 의도적으로 포함하지 않습니다(excludeCredentials와 동일 이유). |

- **Request**:

```http
POST /passkey/authenticate/begin
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

### 4.4 `POST /passkey/authenticate/finish`

브라우저 로그인 결과를 보냅니다. RP 서버가 ID Token을 검증하고 **세션을 확립**합니다.

> **누가 로그인했는지 식별** — discoverable(usernameless) 로그인에서는 클라이언트가 username을 안 보냈으므로, RP 서버는 검증된 ID Token의 **`sub`(= 등록 시 부여한 `userHandle`)** 로 사용자를 역매핑합니다. 즉 `sub`가 "누가 로그인했는가"의 신뢰 가능한 출처입니다. (요청 body의 `response.userHandle`은 검증 전 클라이언트 입력이므로 신뢰하지 말고, 반드시 ID Token의 `sub`를 쓰세요.)

- **요청 body**:

  | 필드 | 타입 | 필수 | 설명 |
  |---|---|---|---|
  | `publicKeyCredential` | object | ✅ | `navigator.credentials.get()` 결과를 `encodeAssertionCredential()`로 인코딩한 것입니다. |

- **응답 `data`**: `null` (성공 시 세션이 확립됩니다. 이후 요청은 세션 쿠키로 인증됩니다.)

- **Request**:

```http
POST /passkey/authenticate/finish
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

### 4.5 `POST /logout`

확립된 세션을 무효화합니다. Spring Security 기본 로그아웃이므로 envelope이 아닌 **`302` 리다이렉트**(`Location: /`)로 응답합니다.

- **요청 body**: 없음. CSRF 토큰(`X-XSRF-TOKEN`)이 필요합니다.
- **응답**: `302 Found`, `Location: /`. 서버 세션이 무효화되고 세션 쿠키가 만료됩니다.

- **Request**:

```http
POST /logout
X-XSRF-TOKEN: <CSRF 토큰>
```

- **Response** `302 Found`:

```http
HTTP/1.1 302 Found
Location: /
Set-Cookie: JSESSIONID=...; Max-Age=0
```

> fetch로 호출할 때 리다이렉트를 따라가지 않으려면 `redirect: 'manual'`을 쓰고, 응답 후 클라이언트가 보관한 세션 쿠키를 폐기하세요.

---

## 5. 전체 흐름 (브라우저 JS)

sample-rp의 실제 등록·로그인 흐름입니다.

### 등록

```js
import { decodeCreationOptions, encodeAttestationCredential, postJson } from '/js/helpers.js';

const start = await postJson('/passkey/register/begin', { username, displayName });
const cred = await navigator.credentials.create({
  publicKey: decodeCreationOptions(start.publicKeyCredentialCreationOptions)
});
const fin = await postJson('/passkey/register/finish', {
  publicKeyCredential: encodeAttestationCredential(cred)
});
// fin.credentialId 등록 완료
```

### 로그인

```js
import { decodeRequestOptions, encodeAssertionCredential, postJson } from '/js/helpers.js';

const start = await postJson('/passkey/authenticate/begin', { username });   // username 생략 가능
const assertion = await navigator.credentials.get({
  publicKey: decodeRequestOptions(start.publicKeyCredentialRequestOptions)
});
await postJson('/passkey/authenticate/finish', {
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
| `P004` | 401 | PASSKEY_ID_TOKEN | ID Token 검증에 실패했습니다(인증 ceremony 검증 실패, 또는 `iss`/`aud` 불일치 포함). RP 서버가 ID Token의 `iss`/`aud`(tenantId 기반)를 검증할 때 **tenantId는 UUID 소문자 대시 형식**(`7f00dead-0000-...`)으로 옵니다. RP 설정값을 RAW hex로 두면 표기 차이로 mismatch가 나므로, 비교 전 UUID로 정규화하거나 설정을 UUID 형식으로 맞추세요. |

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

---

## 7. 모바일 네이티브 앱 연동

네이티브 앱은 `navigator.credentials` 대신 OS 패스키 API를 씁니다. **RP 서버 엔드포인트(§4)와 요청/응답 형식은 동일**하며, 달라지는 것은 (1) ceremony 수행 주체가 OS API라는 점, (2) 세션·CSRF를 앱이 직접 관리한다는 점입니다.

### 7.1 공통 — 세션·CSRF·도메인 연결

- **세션 쿠키**: 네이티브 앱에는 브라우저 쿠키 jar가 없으므로 HTTP 클라이언트가 쿠키를 보관·재전송하도록 설정해야 합니다. iOS는 `URLSession`이 `HTTPCookieStorage`로 자동 처리하고, Android는 OkHttp에 `CookieJar`(예: `PersistentCookieJar`)를 설정합니다. `options` → `finish` 요청이 같은 세션 쿠키를 공유해야 합니다(§1).
- **CSRF 토큰**: 첫 요청 전에 RP 서버에 GET을 한 번 보내 응답의 `Set-Cookie: XSRF-TOKEN=...`을 받아두고, 이후 POST마다 그 값을 `X-XSRF-TOKEN` 헤더에 싣습니다(§2). 쿠키 jar를 쓰면 쿠키 저장은 자동이지만, **헤더로 옮겨 싣는 것은 앱이 직접** 해야 합니다.
- **도메인 연결(필수)**: 네이티브 패스키는 앱과 도메인의 소유 관계를 OS가 검증합니다. 이 설정이 없으면 OS가 ceremony를 거부합니다.
  - **iOS**: Associated Domains에 `webcredentials:<rpId>` 추가 + 서버의 `https://<rpId>/.well-known/apple-app-site-association`에 앱 App ID 등록.
  - **Android**: Digital Asset Links — 서버의 `https://<rpId>/.well-known/assetlinks.json`에 앱 패키지명·서명 지문 등록.
  - 여기서 `<rpId>`는 `options` 응답의 `rp.id`(등록) / `rpId`(인증)와 일치해야 합니다.

### 7.2 iOS (AuthenticationServices)

iOS는 base64url 문자열을 **`Data`로 디코딩**해 OS API에 넘기고, 결과로 받은 `Data` 필드들을 다시 **base64url로 인코딩**해 RP 서버에 보냅니다(웹의 §3 변환과 같은 역할).

**등록** — `/passkey/register/begin` 응답의 `challenge`·`user.id`를 `Data`로:

```swift
import AuthenticationServices

let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(
    relyingPartyIdentifier: opts.rp.id)               // rp.id

let request = provider.createCredentialRegistrationRequest(
    challenge: Data(base64URLEncoded: opts.challenge)!,   // base64url → Data
    name:      opts.user.name,
    userID:    Data(base64URLEncoded: opts.user.id)!)     // userHandle

let controller = ASAuthorizationController(authorizationRequests: [request])
controller.delegate = self
controller.performRequests()

// delegate — 결과를 base64url로 인코딩해 /passkey/register/finish 로 전송
func authorizationController(controller: ASAuthorizationController,
        didCompleteWithAuthorization auth: ASAuthorization) {
    let c = auth.credential as! ASAuthorizationPlatformPublicKeyCredentialRegistration
    let body: [String: Any] = ["publicKeyCredential": [
        "id":    c.credentialID.base64URLEncodedString(),
        "rawId": c.credentialID.base64URLEncodedString(),
        "type":  "public-key",
        "response": [
            "clientDataJSON":    c.rawClientDataJSON.base64URLEncodedString(),
            "attestationObject": c.rawAttestationObject!.base64URLEncodedString()
        ],
        "clientExtensionResults": [:]
    ]]
    // POST /passkey/register/finish (X-XSRF-TOKEN, 세션 쿠키 포함)
}
```

**인증** — `/passkey/authenticate/begin` 응답의 `challenge`를 `Data`로:

```swift
let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(
    relyingPartyIdentifier: opts.rpId)                // rpId

let request = provider.createCredentialAssertionRequest(
    challenge: Data(base64URLEncoded: opts.challenge)!)

let controller = ASAuthorizationController(authorizationRequests: [request])
controller.delegate = self
controller.performRequests()

// delegate — assertion 결과를 base64url 인코딩해 /passkey/authenticate/finish 로
func authorizationController(controller: ASAuthorizationController,
        didCompleteWithAuthorization auth: ASAuthorization) {
    let c = auth.credential as! ASAuthorizationPlatformPublicKeyCredentialAssertion
    let body: [String: Any] = ["publicKeyCredential": [
        "id":    c.credentialID.base64URLEncodedString(),
        "rawId": c.credentialID.base64URLEncodedString(),
        "type":  "public-key",
        "response": [
            "clientDataJSON":    c.rawClientDataJSON.base64URLEncodedString(),
            "authenticatorData": c.rawAuthenticatorData.base64URLEncodedString(),
            "signature":         c.signature.base64URLEncodedString(),
            "userHandle":        c.userID.base64URLEncodedString()
        ],
        "clientExtensionResults": [:]
    ]]
    // POST /passkey/authenticate/finish
}
```

> `Data(base64URLEncoded:)` / `base64URLEncodedString()`는 표준 `Data` API가 아니므로(base64**url**), `-`·`_` 치환과 패딩 처리를 하는 확장을 직접 두거나 라이브러리를 쓰세요. §3의 `b64urlToBuf`/`bufToB64url`과 동일한 변환입니다.

### 7.3 Android (Credential Manager)

Android의 Credential Manager는 **WebAuthn JSON을 그대로** 주고받으므로 base64url 변환이 **불필요**합니다. `options` 응답 객체를 그대로 JSON 문자열로 만들어 넘기고, 결과 JSON을 그대로 RP 서버에 보냅니다.

**등록** — `/passkey/register/begin` 응답의 `publicKeyCredentialCreationOptions`를 JSON 문자열로:

```kotlin
import androidx.credentials.*

val credentialManager = CredentialManager.create(context)

// optionsJson = publicKeyCredentialCreationOptions 를 그대로 직렬화한 JSON 문자열
val request = CreatePublicKeyCredentialRequest(requestJson = optionsJson)
val result = credentialManager.createCredential(context, request)
        as CreatePublicKeyCredentialResponse

// result.registrationResponseJson 을 그대로 finish 로 보냄.
// RP 서버는 { "publicKeyCredential": <이 JSON> } 형태를 기대하므로 한 번 감싼다.
val body = """{ "publicKeyCredential": ${result.registrationResponseJson} }"""
// POST /passkey/register/finish (X-XSRF-TOKEN, 세션 쿠키 포함)
```

**인증** — `/passkey/authenticate/begin` 응답의 `publicKeyCredentialRequestOptions`를 JSON 문자열로:

```kotlin
val option = GetPublicKeyCredentialOption(requestJson = optionsJson)
val request = GetCredentialRequest(listOf(option))
val result = credentialManager.getCredential(context, request)

val cred = result.credential as PublicKeyCredential
val body = """{ "publicKeyCredential": ${cred.authenticationResponseJson} }"""
// POST /passkey/authenticate/finish
```

> `registrationResponseJson`/`authenticationResponseJson`은 이미 §4의 `publicKeyCredential` 객체와 같은 형식(WebAuthn 표준 JSON)입니다. RP 서버 요청 body는 이를 `publicKeyCredential` 키로 한 번 감싼 형태이므로 위처럼 감싸 보내면 됩니다.

---

## 8. 클라이언트 측 에러 처리

§6 에러 코드는 **RP 서버가 내려주는** 오류입니다. 그 외에 **OS·브라우저가 ceremony 수행 중에 던지는** 오류가 있으며, 이는 서버 응답이 아니라 `navigator.credentials.*`(웹) 또는 OS delegate/exception(앱)에서 발생합니다. 사용자에게 보여줄 메시지를 다르게 처리해야 합니다.

### 8.1 웹 (`navigator.credentials`)

`create()`/`get()`는 실패 시 `DOMException`을 던집니다. `err.name`으로 분기합니다.

| `err.name` | 상황 | 권장 처리 |
|---|---|---|
| `NotAllowedError` | 사용자가 취소했거나 ceremony 타임아웃입니다(둘을 구분할 수 없음). | "취소되었거나 시간이 초과되었습니다. 다시 시도해 주세요." — 재시도 버튼 노출. 에러로 시끄럽게 알리지 마세요(정상적인 취소 포함). |
| `InvalidStateError` | 등록 시 **이미 등록된** authenticator입니다(`excludeCredentials` 충돌). | "이미 이 기기에 패스키가 등록되어 있습니다." — 로그인으로 안내. |
| `NotSupportedError` | 요청한 알고리즘/옵션을 authenticator가 지원하지 않습니다. | "이 기기에서 지원하지 않는 패스키입니다." |
| `SecurityError` | `rpId`가 현재 origin과 불일치하거나 안전하지 않은 컨텍스트입니다(HTTPS/localhost 아님). | 개발/설정 오류입니다. rpId·origin·HTTPS를 점검하세요. |
| `AbortError` | `AbortSignal`로 중단되었습니다. | 보통 무시(앱이 의도적으로 취소한 경우). |

```js
try {
  const cred = await navigator.credentials.get({ publicKey: decodeRequestOptions(opts) });
  // ...
} catch (e) {
  if (e.name === 'NotAllowedError') {
    showRetry('취소되었거나 시간이 초과되었습니다.');
  } else if (e.name === 'InvalidStateError') {
    showInfo('이미 등록된 기기입니다.');
  } else {
    showError(`패스키 오류: ${e.name}`);
  }
}
```

> **"패스키 없음"(authenticator에 자격증명이 없음)** 은 별도 에러로 오지 않고, 인증 ceremony에서 사용자가 선택할 패스키가 없어 결과적으로 `NotAllowedError`(취소/타임아웃)로 귀결됩니다. 따라서 "패스키 없음"을 명시적으로 구분할 수는 없으며, 재시도/등록 안내로 처리합니다.

### 8.2 모바일 네이티브

- **iOS**: 실패는 delegate의 `authorizationController(controller:didCompleteWithError:)`로 옵니다. `error`를 `ASAuthorizationError`로 캐스팅해 `code`로 분기합니다.
  - `.canceled` — 사용자가 취소했습니다. 조용히 재시도 가능 상태로 둡니다(에러 알림 금지).
  - `.failed` / `.invalidResponse` — ceremony 실패입니다. 재시도 안내.
  - `.notHandled` / `.unknown` — 일반 오류로 처리.
- **Android**: `createCredential`은 `CreateCredentialException`을, `getCredential`은 `GetCredentialException`을 던집니다(하위 타입으로 분기).
  - `CreateCredentialCancellationException` / `GetCredentialCancellationException` — 사용자가 취소했습니다(조용히 처리).
  - `NoCredentialException`(인증 시) — **선택할 패스키가 없습니다.** 등록으로 안내할 수 있습니다(웹과 달리 Android는 이 경우를 구분할 수 있음).
  - `GetCredentialInterruptedException` — 일시적 중단입니다. 재시도 가능.
  - 그 외 하위 타입(`Unknown`/`ProviderConfiguration`/`Unsupported` 등) — 일반 오류로 처리.

### 8.3 서버 오류와의 구분

| 발생 위치 | 형태 | 예 |
|---|---|---|
| OS/브라우저 (ceremony) | `DOMException`(웹) / OS 예외(앱) | 사용자 취소, 타임아웃, 미지원 기기 |
| RP 서버 | §6 envelope `code` | `W002`(세션 만료), `C001`(입력 오류), `P004`(ID Token 검증 실패) |

ceremony 단계(OS/브라우저)와 `finish` 응답(RP 서버)을 각각 `try/catch`로 감싸 둘을 구분해 메시지를 다르게 보여주는 것을 권장합니다. 예: `begin`/`finish`는 서버 오류(§6)로, 그 사이 `navigator.credentials.*`는 OS 오류(§8.1)로 처리합니다.
