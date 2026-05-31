# Passkey 서버 RP API 명세서

RP(Relying Party) 백엔드 개발자가 Crosscert Passkey 서버를 호출해 패스키(WebAuthn/FIDO2) 등록·인증을 구현하기 위한 API 레퍼런스입니다.

- **범위**: 패스키 등록·인증 ceremony(`/api/v1/rp/registration/**`, `/api/v1/rp/authentication/**`)와 ID Token 검증용 JWKS입니다.

---

## 1. 개요

Passkey 서버는 RP 백엔드를 위한 **WebAuthn ceremony API**를 제공합니다. RP는 자체 사용자 DB·세션을 보유한 채, 패스키 등록/인증의 암호학적 부분만 Passkey 서버에 위임합니다.

```
┌──────────┐          ┌─────────────┐   X-API-Key    ┌────────────────┐
│ Browser  │  (RP UI) │  RP 백엔드   │ ─────────────▶ │  Passkey 서버   │
│ (User)   │ ◀──────▶ │  (당신)      │ ◀── ID Token ──│                │
└──────────┘          └─────────────┘   (RS256 JWT)   └────────────────┘
   │  navigator.credentials.create()/get() 는 브라우저에서 직접 수행합니다.
   │  RP 백엔드는 start 응답의 options 를 브라우저에 전달하고,
   └─ 브라우저 결과(publicKeyCredential)를 finish 로 다시 서버에 전달합니다.
```

**핵심 사항입니다.**

- 브라우저의 `navigator.credentials.create()/get()` 호출은 RP가 자체 처리합니다. Passkey 서버는 ceremony의 **start**(challenge 발급)와 **finish**(검증·저장)만 담당합니다.
- **모든 `/api/v1/rp/**` 호출은 RP 백엔드 ↔ Passkey 서버 간 서버-투-서버입니다.** `X-API-Key`는 비밀이므로 **브라우저에서 직접 호출하면 안 됩니다**(키 노출 및 CORS 차단). 브라우저는 RP 백엔드와만 통신하고, RP 백엔드가 Passkey 서버를 호출합니다.
- **challenge 유효시간은 5분입니다.** `start` 응답을 받은 뒤 5분 이내에 `finish`를 호출해야 합니다(사용자가 생체인증 프롬프트 앞에서 오래 머뭇거리면 만료되어 `C001`이 반환됩니다). 이 5분은 서버 challenge TTL이며, options의 `timeout`(브라우저 ceremony 타임아웃, 기본 60초)과는 별개입니다.

### Base URL

| 환경 | Base URL |
|---|---|
| 개발(dev) | `https://dev-passkey.crosscert.com` |
| 운영(prod) | RP에게 발급된 Passkey 서버 호스트 (예: `https://passkey.crosscert.com`) |

모든 경로는 이 Base URL 기준 절대 경로입니다.

### 브라우저 연동 — base64url ↔ ArrayBuffer 변환 (필독)

> ⚠️ start 응답의 options를 `navigator.credentials.create()/get()`에 **그대로 넘기면 `TypeError`로 실패합니다.** WebAuthn JS API는 `challenge`·`user.id`·`excludeCredentials[].id`·`allowCredentials[].id`를 `ArrayBuffer`로 요구하는데, 이 API의 응답은 전부 **base64url 문자열**이기 때문입니다. 반대로 finish에 보낼 때는 브라우저가 돌려준 `ArrayBuffer` 필드들을 다시 **base64url 문자열로 인코딩**해야 합니다. (`JSON.stringify(credential)`을 그냥 하면 ArrayBuffer가 `{}`로 직렬화되어 서버가 거부합니다.)

**권장 방법 — 표준 브라우저 헬퍼**(최신 브라우저에서 변환을 자동 처리합니다):

```js
// 등록
const options = resp.data.publicKeyCredentialCreationOptions;        // 서버 응답
const cred = await navigator.credentials.create({
  publicKey: PublicKeyCredential.parseCreationOptionsFromJSON(options)
});
await postToRpBackend('/passkey/register/finish', {
  registrationToken: resp.data.registrationToken,
  publicKeyCredential: cred.toJSON()                                 // ArrayBuffer → base64url 자동
});

// 인증
const options = resp.data.publicKeyCredentialRequestOptions;
const cred = await navigator.credentials.get({
  publicKey: PublicKeyCredential.parseRequestOptionsFromJSON(options)
});
await postToRpBackend('/passkey/login/finish', {
  authenticationToken: resp.data.authenticationToken,
  publicKeyCredential: cred.toJSON()
});
```

`parse*FromJSON()` / `.toJSON()`을 지원하지 않는 환경에서는 [`@simplewebauthn/browser`](https://simplewebauthn.dev) 같은 라이브러리를 쓰거나 직접 변환합니다. **직접 변환할 때는 base64url에 주의해야 합니다.** 표준 `btoa()`는 base64(`+`/`/`/`=`)를 만들므로 `+`→`-`, `/`→`_`로 바꾸고 패딩(`=`)을 제거해야 합니다. 한 글자만 틀려도 `C001`로 거부되며 message만으로는 원인을 파악하기 어렵습니다.

### `userHandle` 만들기

`userHandle`은 **RP가 부여하는 사용자 식별자를 base64url 인코딩한 문자열**입니다. 디코딩하면 RP가 사용자를 식별할 수 있는 바이트가 나와야 합니다.

- **무엇을 넣나요**: RP DB의 안정적인 사용자 키(예: user UUID의 16바이트, 또는 무작위 16~32바이트 식별자)를 base64url 인코딩합니다. 예를 들어 UUID `0x7f00...` 16바이트는 `fwD...`가 됩니다.
- **제약**: 디코딩 결과는 **1~64바이트**여야 합니다(WebAuthn `user.id` 한도). 테넌트 내에서 고유해야 합니다.
- **권장**: 이메일·username처럼 **PII나 추측 가능한 값은 넣지 않는 것이 좋습니다**(WebAuthn 권고 사항). 변하지 않는 불투명 식별자를 쓰고, RP가 `userHandle ↔ 내부 user`를 자체 매핑합니다.
- 인증 성공 후 ID Token의 **`sub`는 RP가 보낸 `userHandle` 문자열과 그대로 같습니다**(§5). 서버가 내부적으로 디코딩 → 저장 → 재인코딩하지만 왕복이 상쇄되기 때문입니다. 따라서 RP는 `sub`를 받은 그대로 `userHandle`로 보고 사용자를 역매핑하면 되며, 추가 디코딩은 필요하지 않습니다.

---

## 2. 인증 — `X-API-Key`

JWKS(`/.well-known/jwks.json`)를 제외한 모든 `/api/v1/rp/**` 요청은 API key가 필요합니다.

```
X-API-Key: pk_<11자 prefix 까지 포함><secret>
```

- **형식**: `pk_` + 8자 base64url prefix(합쳐서 11자)에 secret을 **콜론·구분자 없이 이어붙인** 단일 문자열입니다.
  예: `pk_devacme0dev_acme_secret_known_plaintext_for_local_test_only`
- **검증**: 서버가 prefix로 키를 찾아 secret을 BCrypt로 검증합니다. 평문은 발급 시 1회만 노출되며 서버는 해시만 저장합니다.
- **발급**: 관리 콘솔에서 테넌트별로 발급받습니다. 발급 시 scope를 지정합니다.

### Scope

각 경로는 API key가 특정 scope를 보유할 것을 요구합니다. scope가 없으면 **403**이 반환됩니다.

| 경로 | 요구 scope |
|---|---|
| `/api/v1/rp/registration/**` | `registration` |
| `/api/v1/rp/authentication/**` | `authentication` |

> 등록·인증 양쪽을 쓰려면 키에 두 scope를 모두 부여하면 됩니다(콘솔 발급 시 선택).

### 인증 실패 응답 — `application/problem+json` (envelope 아님)

> ⚠️ 인증·rate-limit 실패는 §3의 `ApiResponse` envelope이 **아니라** RFC 9457 `application/problem+json`으로 반환됩니다. RP 에러 핸들링에서 두 형식을 구분해야 합니다.

| 상황 | HTTP | 본문 |
|---|---|---|
| 키 없음/형식오류/미등록/만료/폐기/secret 불일치 | **401** | `{"type":"about:blank","status":401,"title":"Unauthorized"}` |
| 키는 유효하나 scope 부족 | **403** | `{"type":"about:blank","status":403,"title":"Forbidden","error":"insufficient_scope"}` |

---

## 3. 공통 응답 규약 — `ApiResponse<T>` envelope

JWKS를 제외한 모든 응답(성공·비즈니스 에러 모두)은 envelope으로 감싸집니다.

```jsonc
// 성공
{
  "success": true,
  "code": "OK",
  "message": "Success",
  "data": { /* 엔드포인트별 응답 DTO */ },
  "error": null,
  "traceId": "a1b2c3...",        // 요청 추적용 (로그 대조)
  "timestamp": "2026-05-31T12:00:00Z"
}

// 비즈니스 에러 (검증 실패, challenge 만료 등)
{
  "success": false,
  "code": "C001",               // §6 ErrorCode
  "message": "Invalid input",
  "data": null,
  "error": {
    "errorCode": "C001",
    "fieldErrors": [             // body/param 검증 실패 시에만
      { "field": "userHandle", "rejectedValue": null, "reason": "must not be blank" }
    ]
  },
  "traceId": "a1b2c3...",
  "timestamp": "2026-05-31T12:00:00Z"
}
```

아래 각 엔드포인트의 **응답**은 envelope의 `data` 필드 내용만 표기합니다.

---

## 4. 엔드포인트 레퍼런스

### 4.1 `POST /api/v1/rp/registration/start`

패스키 등록 ceremony를 시작하며, challenge가 포함된 creation options를 발급합니다.

- **인증**: X-API-Key (scope `registration`)
- **요청 body**:

  | 필드 | 타입 | 필수 | 설명 |
  |---|---|---|---|
  | `userHandle` | string | ✅ | 사용자 식별자(base64url). RP가 부여하며 테넌트 내에서 고유합니다. |
  | `displayName` | string | ✅ | authenticator에 표시될 이름입니다. |
  | `username` | string | ✅ | 사용자명입니다. |

- **응답 `data`** (`RegistrationStartResponse`):

  | 필드 | 타입 | 설명 |
  |---|---|---|
  | `registrationToken` | string | finish 호출 시 그대로 돌려줄 불투명 토큰입니다(challenge 상태 바인딩). |
  | `publicKeyCredentialCreationOptions` | object | WebAuthn PublicKeyCredentialCreationOptions입니다. 브라우저 `navigator.credentials.create({ publicKey })`에 전달합니다. 내부 필드는 아래를 참조하세요. |

  `publicKeyCredentialCreationOptions` 내부 필드입니다.

  | 필드 | 타입 | 설명 |
  |---|---|---|
  | `challenge` | string | 서버가 생성한 challenge(base64url)입니다. 매 요청마다 고유하며 finish에서 검증됩니다. |
  | `rp.id` | string | Relying Party ID(테넌트 `rpId`)입니다. 브라우저 origin의 등록 가능 도메인이어야 합니다. |
  | `rp.name` | string | RP 표시 이름(테넌트 `rpName`)입니다. |
  | `user.id` | string | 요청의 `userHandle`(base64url) 그대로입니다. |
  | `user.displayName` | string | 요청의 `displayName`입니다. |
  | `user.name` | string | 요청의 `username`입니다. |
  | `pubKeyCredParams` | array | 허용 알고리즘입니다. 고정값 `[{type:"public-key",alg:-7}(ES256), {type:"public-key",alg:-257}(RS256)]`입니다. |
  | `timeout` | number | ceremony 타임아웃(ms)입니다. 테넌트 `webauthnTimeoutMs` 값을 따릅니다(예: 60000). |
  | `attestation` | string | attestation conveyance입니다. 테넌트 설정에 따라 `none`/`indirect`/`direct`/`enterprise` 중 하나입니다. |
  | `excludeCredentials` | array | 이 `userHandle`이 이미 등록한 패스키 목록 `[{type:"public-key",id:<base64url>}]`입니다. 중복 등록을 방지하며, 없으면 `[]`입니다. |
  | `authenticatorSelection.userVerification` | string | 테넌트가 UV 필수이면 `required`, 아니면 `preferred`입니다. |
  | `authenticatorSelection.residentKey` | string | 고정값 `preferred`입니다(discoverable credential 선호). |

- **Request**:

```http
POST /api/v1/rp/registration/start
X-API-Key: <발급받은 X-API-Key>
Content-Type: application/json

{
  "userHandle": "ZGV2LXVzZXItMDAx",
  "displayName": "Dev User",
  "username": "dev-user-001"
}
```

- **Response** `200 OK`:

```jsonc
{
  "success": true,
  "code": "OK",
  "message": "Success",
  "data": {
    "registrationToken": "rGt_9f3c1a8b...e2",          // finish 에 그대로 전달
    "publicKeyCredentialCreationOptions": {
      "challenge": "k7Hd2...Qw",                        // base64url
      "rp":   { "id": "example.com", "name": "Example Corp" },
      "user": { "id": "ZGV2LXVzZXItMDAx", "displayName": "Dev User", "name": "dev-user-001" },
      "pubKeyCredParams": [
        { "type": "public-key", "alg": -7 },            // ES256
        { "type": "public-key", "alg": -257 }           // RS256
      ],
      "timeout": 60000,
      "attestation": "none",
      "excludeCredentials": [
        { "type": "public-key", "id": "AbCd...Ef" }     // 이미 등록된 패스키(중복 등록 방지)
      ],
      "authenticatorSelection": { "userVerification": "preferred", "residentKey": "preferred" }
    }
  },
  "error": null,
  "traceId": "a1b2c3d4",
  "timestamp": "2026-05-31T12:00:00Z"
}
```

> `publicKeyCredentialCreationOptions`를 브라우저로 보내 `navigator.credentials.create({ publicKey: options })`에 전달합니다. `rp.id`·`timeout`·`attestation`·`userVerification`은 테넌트 설정에 따라 달라집니다.

### 4.2 `POST /api/v1/rp/registration/finish`

브라우저 등록 결과를 검증하고 저장합니다.

- **인증**: X-API-Key (scope `registration`)
- **요청 body**:

  | 필드 | 타입 | 필수 | 설명 |
  |---|---|---|---|
  | `registrationToken` | string | ✅ | start 응답의 토큰입니다. |
  | `publicKeyCredential` | object | ✅ | 브라우저 `navigator.credentials.create()` 결과(PublicKeyCredential JSON)입니다. |

- **응답 `data`** (`RegistrationFinishResponse`):

  | 필드 | 타입 | 설명 |
  |---|---|---|
  | `credentialId` | string | 저장된 credential ID(base64url)입니다. |
  | `aaguid` | string | authenticator AAGUID입니다. |
  | `attestationFormat` | string | attestation 포맷입니다. |
  | `createdAt` | string(ISO instant) | 생성 시각입니다. |

- **Request** (`publicKeyCredential`은 브라우저 `navigator.credentials.create()` 결과를 JSON 직렬화한 것입니다):

```http
POST /api/v1/rp/registration/finish
X-API-Key: <발급받은 X-API-Key>
Content-Type: application/json

{
  "registrationToken": "rGt_9f3c1a8b...e2",
  "publicKeyCredential": {
    "id": "AbCd...Ef",
    "rawId": "AbCd...Ef",
    "type": "public-key",
    "response": {
      "clientDataJSON": "eyJ0eXBl...",                 // base64url
      "attestationObject": "o2NmbXQ..."                // base64url
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
  "data": {
    "credentialId": "AbCd...Ef",
    "aaguid": "08987058-cadc-4b81-b6e1-30de50dcbe96",
    "attestationFormat": "none",
    "createdAt": "2026-05-31T12:00:01Z"
  },
  "error": null,
  "traceId": "a1b2c3d4",
  "timestamp": "2026-05-31T12:00:01Z"
}
```

### 4.3 `POST /api/v1/rp/authentication/start`

패스키 인증 ceremony를 시작하며, challenge가 포함된 request options를 발급합니다.

- **인증**: X-API-Key (scope `authentication`)
- **요청 body**:

  | 필드 | 타입 | 필수 | 설명 |
  |---|---|---|---|
  | `userHandle` | string | ❌ | 지정하면 해당 사용자의 credential로 좁힙니다(`allowCredentials`). 생략하면 discoverable(usernameless) 인증입니다. |

- **응답 `data`** (`AuthenticationStartResponse`):

  | 필드 | 타입 | 설명 |
  |---|---|---|
  | `authenticationToken` | string | finish에 돌려줄 불투명 토큰입니다. |
  | `publicKeyCredentialRequestOptions` | object | WebAuthn PublicKeyCredentialRequestOptions입니다. 브라우저 `navigator.credentials.get({ publicKey })`에 전달합니다. 내부 필드는 아래를 참조하세요. |

  `publicKeyCredentialRequestOptions` 내부 필드입니다.

  | 필드 | 타입 | 설명 |
  |---|---|---|
  | `challenge` | string | 서버가 생성한 challenge(base64url)입니다. finish에서 검증됩니다. |
  | `rpId` | string | Relying Party ID(테넌트 `rpId`)입니다. |
  | `timeout` | number | ceremony 타임아웃(ms)입니다. 테넌트 `webauthnTimeoutMs` 값을 따릅니다. |
  | `userVerification` | string | 테넌트가 UV 필수이면 `required`, 아니면 `preferred`입니다. |
  | `allowCredentials` | array | 인증 허용 패스키 `[{type:"public-key",id:<base64url>}]`입니다. 요청에 `userHandle`이 있으면 그 사용자의 등록 패스키로 채워지고, 없으면 `[]`입니다(discoverable/usernameless 인증). |

- **Request** (`userHandle`을 생략하면 discoverable 인증입니다):

```http
POST /api/v1/rp/authentication/start
X-API-Key: <발급받은 X-API-Key>
Content-Type: application/json

{ "userHandle": "ZGV2LXVzZXItMDAx" }
```

- **Response** `200 OK`:

```jsonc
{
  "success": true,
  "code": "OK",
  "message": "Success",
  "data": {
    "authenticationToken": "aTk_4b8e2c1f...9a",
    "publicKeyCredentialRequestOptions": {
      "challenge": "p9Lm3...Zx",                        // base64url
      "rpId": "example.com",
      "timeout": 60000,
      "userVerification": "preferred",
      "allowCredentials": [
        { "type": "public-key", "id": "AbCd...Ef" }     // userHandle 의 등록 패스키. 생략 시 빈 배열.
      ]
    }
  },
  "error": null,
  "traceId": "b2c3d4e5",
  "timestamp": "2026-05-31T12:05:00Z"
}
```

### 4.4 `POST /api/v1/rp/authentication/finish`

인증 결과를 검증하고 **ID Token을 발급**합니다.

- **인증**: X-API-Key (scope `authentication`)
- **요청 body**:

  | 필드 | 타입 | 필수 | 설명 |
  |---|---|---|---|
  | `authenticationToken` | string | ✅ | start 응답의 토큰입니다. |
  | `publicKeyCredential` | object | ✅ | 브라우저 `navigator.credentials.get()` 결과입니다. |

- **응답 `data`** (`AuthenticationFinishResponse`):

  | 필드 | 타입 | 설명 |
  |---|---|---|
  | `idToken` | string | RS256 서명 JWT입니다(§5). 인증된 사용자의 신원을 담습니다. |
  | `tokenType` | string | `"Bearer"`입니다. |
  | `expiresIn` | number | 토큰 유효 기간(초)입니다. `900`(15분)입니다. |

- **Request**:

```http
POST /api/v1/rp/authentication/finish
X-API-Key: <발급받은 X-API-Key>
Content-Type: application/json

{
  "authenticationToken": "aTk_4b8e2c1f...9a",
  "publicKeyCredential": {
    "id": "AbCd...Ef",
    "rawId": "AbCd...Ef",
    "type": "public-key",
    "response": {
      "clientDataJSON": "eyJ0eXBl...",                 // base64url
      "authenticatorData": "SZYN5Y...",                // base64url
      "signature": "MEUCIQ...",                        // base64url
      "userHandle": "ZGV2LXVzZXItMDAx"                 // base64url (있을 때)
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
  "data": {
    "idToken": "eyJhbGciOiJSUzI1Ni...",                 // RS256 JWT (§5 에서 검증)
    "tokenType": "Bearer",
    "expiresIn": 900
  },
  "error": null,
  "traceId": "b2c3d4e5",
  "timestamp": "2026-05-31T12:05:02Z"
}
```

> RP는 `idToken`을 검증(§5)해 사용자 신원을 확인하고, 자체 세션/토큰을 발급합니다.

### 4.5 `GET /.well-known/jwks.json` (public)

ID Token 서명 검증용 공개키 집합입니다. **인증이 필요하지 않습니다.** 응답은 envelope이 아닌 **RFC 7517 raw JSON**입니다.

```jsonc
{
  "keys": [
    { "kty": "RSA", "use": "sig", "alg": "RS256",
      "kid": "<SHA-256 thumbprint>", "n": "<modulus>", "e": "AQAB" }
  ]
}
```

---

## 5. ID Token 검증

`authentication/finish`가 발급하는 `idToken`은 RS256 서명 JWT입니다. RP는 다음을 검증해야 합니다.

1. **서명**: JWKS(`/.well-known/jwks.json`)에서 JWT 헤더의 `kid`에 해당하는 RSA 공개키로 RS256 서명을 검증합니다. (`alg`가 `RS256`인지도 확인합니다 — alg confusion 방지.)
2. **`exp`**: 만료 시각이 현재 이후인지 확인합니다(TTL 15분).
3. **`iss`/`aud`**: 기대값과 일치하는지 확인합니다(아래 claim 참조).

### Claims

| claim | 값 | 설명 |
|---|---|---|
| `iss` | `<issuer-base>/<tenantId>` | 발급자입니다. issuer-base는 운영 환경마다 정해진 Passkey 서버 발급자 주소입니다. |
| `sub` | `userHandle` | 인증된 사용자입니다. **요청에 보낸 `userHandle` 문자열과 동일합니다**(서버 디코딩↔재인코딩 왕복이 상쇄됨). RP는 이 값으로 사용자를 역매핑하며, discoverable(usernameless) 인증에서 "누가 로그인했는지"를 아는 신뢰 가능한 출처입니다. |
| `aud` | `<tenantId>` | 대상 테넌트입니다. RP는 자신의 tenantId와 일치하는지 검증하는 것을 권장합니다. |
| `iat` | epoch seconds | 발급 시각입니다. |
| `exp` | epoch seconds | 만료 시각입니다(iat + 15분). |
| `amr` | `["webauthn"]` | 인증 수단입니다. |
| `cred_id` | base64url(UUID 16바이트) | 사용된 credential ID입니다. |
| `aaguid` | hex | authenticator AAGUID입니다(있을 때만). |

> Java로 구현하는 경우 ID Token 검증용 SDK를 제공하며, JWKS 캐시 + RS256 핀 + exp 검증이 내장되어 있습니다. 기대하는 `iss`/`aud`를 설정해 함께 강제하는 것을 권장합니다.

---

## 6. 에러 코드

비즈니스 에러는 §3 envelope의 `code`로 식별합니다. (인증/rate-limit 실패는 §2/§7의 problem+json입니다.)

| code | HTTP | 의미 | RP가 만나는 상황 |
|---|---|---|---|
| `C001` | 400 | INVALID_INPUT | **ceremony finish의 거의 모든 실패** — challenge 만료/재사용, attestation·assertion 검증 실패, signCount 재생, credential 미등록, 잘못된 base64url, malformed JSON, 필드 검증 실패입니다. 구체 원인은 envelope의 `message`로 구분합니다(예: `"assertion verify failed"`, `"signCount replay detected"`, `"...token missing or expired"`). |
| `C003` | 404 | ENTITY_NOT_FOUND | 리소스가 존재하지 않습니다. |
| `C004` | 400 | MISSING_PARAMETER | 필수 query param이 누락되었습니다. |
| `C005` | 400 | TYPE_MISMATCH | 타입이 일치하지 않습니다. |
| `C999` | 500 | INTERNAL_SERVER_ERROR | 서버 오류입니다. |
| `T003` | 403 | TENANT_SUSPENDED | 테넌트가 정지되었습니다. `registration/start`·`authentication/start`에서 발생합니다. |
| `F004` | 403 | ATTESTATION_REJECTED | AAGUID 정책이 authenticator를 거부했습니다. |
| `F005` | 403 | AAGUID_REVOKED | 폐기된 AAGUID의 authenticator입니다. |

> ⚠️ **ceremony 검증 실패는 세분화된 코드가 아니라 모두 `C001`/HTTP 400으로 옵니다.** challenge 만료든 서명 불일치든 인증 실패든 `code`는 `"C001"`이며, **실패 원인은 `message` 문자열로만 구분**됩니다(코드로 분기하지 말고 message를 참고하세요). `F004`/`F005`(AAGUID 정책)와 `T003`(테넌트 정지)만 별도 코드/403으로 옵니다.
>
> 인증 실패(잘못된 API key)는 envelope이 아니라 §2의 **401 problem+json**으로 반환됩니다 — 필터 단계에서 끊기기 때문입니다.

### 에러 응답 예시

필드 검증 실패 (`400`) — `fieldErrors`로 어느 필드인지 표기됩니다.

```jsonc
{
  "success": false,
  "code": "C001",
  "message": "Invalid input value",
  "data": null,
  "error": {
    "errorCode": "C001",
    "fieldErrors": [
      { "field": "userHandle", "rejectedValue": null, "reason": "must not be blank" }
    ]
  },
  "traceId": "e5f6a7b8",
  "timestamp": "2026-05-31T12:00:00Z"
}
```

ceremony 검증 실패 (`400`) — `message`로 원인을 구분합니다(challenge 만료 예).

```jsonc
{
  "success": false,
  "code": "C001",
  "message": "registration token missing or expired",   // 원인은 message 로
  "data": null,
  "error": { "errorCode": "C001", "fieldErrors": null },
  "traceId": "e5f6a7b8",
  "timestamp": "2026-05-31T12:03:00Z"
}
```

API key 무효 (`401`, **problem+json** — envelope 아님):

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/problem+json

{ "type": "about:blank", "status": 401, "title": "Unauthorized" }
```

---

## 7. Rate Limit

경로별로 IP 버킷과 API-key 버킷 두 가지가 적용됩니다. 한도를 초과하면 **429 Too Many Requests**와 `Retry-After: 60` 헤더, problem+json 본문(`{"type":"about:blank","status":429,"title":"Too Many Requests"}`)이 반환됩니다.

| 경로 | 한도(분당) |
|---|---|
| `POST /registration/start`, `/registration/finish` | 60 |
| `POST /authentication/start`, `/authentication/finish` | 300 |

---

## 8. end-to-end 예시 (curl)

> 발급받은 X-API-Key로 치환해 실행합니다.

### 등록 1사이클

```bash
KEY='<발급받은 X-API-Key>'
BASE='https://dev-passkey.crosscert.com'

# 1) start — registrationToken + creation options 수령
curl -s -X POST $BASE/api/v1/rp/registration/start \
  -H 'Content-Type: application/json' -H "X-API-Key: $KEY" \
  -d '{"userHandle":"ZGV2LXVzZXItMDAx","displayName":"Dev User","username":"dev-user-001"}'
# → data.publicKeyCredentialCreationOptions 를 브라우저 navigator.credentials.create() 에 전달
# → 브라우저 결과(publicKeyCredential)를 아래 finish 에 그대로 전달

# 2) finish — 브라우저 결과 검증·저장
curl -s -X POST $BASE/api/v1/rp/registration/finish \
  -H 'Content-Type: application/json' -H "X-API-Key: $KEY" \
  -d '{"registrationToken":"<1단계 토큰>","publicKeyCredential":<브라우저 결과>}'
```

### 인증 1사이클

```bash
# 1) start — authenticationToken + request options
curl -s -X POST $BASE/api/v1/rp/authentication/start \
  -H 'Content-Type: application/json' -H "X-API-Key: $KEY" \
  -d '{"userHandle":"ZGV2LXVzZXItMDAx"}'
# → publicKeyCredentialRequestOptions 를 navigator.credentials.get() 에 전달

# 2) finish — 검증 → ID Token
curl -s -X POST $BASE/api/v1/rp/authentication/finish \
  -H 'Content-Type: application/json' -H "X-API-Key: $KEY" \
  -d '{"authenticationToken":"<토큰>","publicKeyCredential":<브라우저 결과>}'
# → data.idToken (RS256 JWT) 검증(§5) 후 RP 세션 발급
```
