# Passkey 서버 RP API 명세서

RP(Relying Party) 백엔드 개발자가 **Crosscert Passkey 서버**(`passkey-app`)를 직접 호출해 패스키(WebAuthn/FIDO2) 등록·인증을 구현하기 위한 API 레퍼런스.

- **대상**: SDK 없이 raw HTTP로 통합하는 RP 백엔드. (Java라면 [`sdk-java`](../sdk-java/)를 쓰면 ID Token 검증·재시도·redaction이 내장 — 이 문서는 SDK 내부가 호출하는 계약이기도 하다.)
- **범위**: `/api/v1/rp/**` (registration / authentication / credentials) + JWKS. admin 콘솔 API와 sample-rp 데모 자체 구현(`/users`, `/passkey/register/begin` 등 — `rp-client-api-quickref.md` 참조)은 범위 밖.
- **작성일**: 2026-05-31 (Flyway V38 / 하드닝 머지 `8d4a403` 기준)

---

## 1. 개요

Passkey 서버는 RP 백엔드를 위한 **WebAuthn ceremony API**를 제공한다. RP는 자체 사용자 DB·세션을 보유한 채, 패스키 등록/인증의 암호학적 부분만 Passkey 서버에 위임한다.

```
┌──────────┐          ┌─────────────┐   X-API-Key    ┌────────────────┐
│ Browser  │  (RP UI) │  RP 백엔드   │ ─────────────▶ │  Passkey 서버   │
│ (User)   │ ◀──────▶ │  (당신)      │ ◀── ID Token ──│  (passkey-app) │
└──────────┘          └─────────────┘   (RS256 JWT)   └────────────────┘
   │  navigator.credentials.create()/get() 는 브라우저에서 직접 수행
   │  RP 백엔드는 start 응답의 options 를 브라우저에 전달하고,
   └─ 브라우저 결과(publicKeyCredential)를 finish 로 다시 서버에 전달한다.
```

**핵심**: 브라우저의 `navigator.credentials.create()/get()` 호출은 RP가 자체 처리한다. Passkey 서버는 ceremony의 **start**(challenge 발급)와 **finish**(검증·저장)만 담당한다.

### Base URL

| 환경 | Base URL |
|---|---|
| 로컬 dev | `http://localhost:8080` |
| 운영(prod) | RP에게 발급된 Passkey 서버 호스트 (예: `https://passkey.crosscert.com`) |

모든 경로는 이 Base URL 기준 절대 경로다.

---

## 2. 인증 — `X-API-Key`

JWKS(`/.well-known/jwks.json`)를 제외한 모든 `/api/v1/rp/**` 요청은 API key가 필요하다.

```
X-API-Key: pk_<11자 prefix 까지 포함><secret>
```

- **형식**: `pk_` + 8자 base64url prefix(합쳐서 11자) + secret을 **콜론·구분자 없이 이어붙인** 단일 문자열.
  예: `pk_devacme0dev_acme_secret_known_plaintext_for_local_test_only`
- **검증**: 서버가 prefix로 키를 찾아 secret을 BCrypt로 검증한다. 평문은 발급 시 1회만 노출되며 서버는 해시만 저장한다.
- **발급**: admin 콘솔(`admin-app`)에서 테넌트별로 발급. 발급 시 scope를 지정한다.

### Scope

각 경로는 API key가 특정 scope를 보유할 것을 요구한다. scope가 없으면 **403**.

| 경로 | 요구 scope |
|---|---|
| `/api/v1/rp/registration/**` | `registration` |
| `/api/v1/rp/authentication/**` | `authentication` |
| `/api/v1/rp/credentials/**` | `registration` |

> 등록 ceremony와 self-service credential 관리(목록/이름변경/삭제)는 모두 `registration` scope다. 등록·인증 양쪽을 쓰려면 키에 두 scope를 모두 부여한다(콘솔 발급 시 선택).

### 인증 실패 응답 — `application/problem+json` (envelope 아님)

> ⚠️ 인증·rate-limit 실패는 본문 §3의 `ApiResponse` envelope이 **아니라** RFC 9457 `application/problem+json`이다. RP 에러 핸들링에서 두 형식을 구분해야 한다.

| 상황 | HTTP | 본문 |
|---|---|---|
| 키 없음/형식오류/미등록/만료/폐기/secret 불일치 | **401** | `{"type":"about:blank","status":401,"title":"Unauthorized"}` |
| 키는 유효하나 scope 부족 | **403** | `{"type":"about:blank","status":403,"title":"Forbidden","error":"insufficient_scope"}` |

---

## 3. 공통 응답 규약 — `ApiResponse<T>` envelope

JWKS를 제외한 모든 응답(성공·비즈니스 에러 모두)은 envelope으로 감싼다.

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

아래 각 엔드포인트의 **응답**은 envelope의 `data` 필드 내용만 표기한다.

---

## 4. 엔드포인트 레퍼런스

### 4.1 `POST /api/v1/rp/registration/start`

패스키 등록 ceremony 시작 — challenge 포함 creation options 발급.

- **인증**: X-API-Key (scope `registration`)
- **요청 body**:

  | 필드 | 타입 | 필수 | 설명 |
  |---|---|---|---|
  | `userHandle` | string | ✅ | 사용자 식별자(base64url). RP가 부여하며 테넌트 내 고유. |
  | `displayName` | string | ✅ | authenticator에 표시될 이름 |
  | `username` | string | ✅ | 사용자명 |

- **응답 `data`** (`RegistrationStartResponse`):

  | 필드 | 타입 | 설명 |
  |---|---|---|
  | `registrationToken` | string | finish 호출 시 그대로 돌려줄 불투명 토큰(challenge 상태 바인딩) |
  | `publicKeyCredentialCreationOptions` | object | WebAuthn PublicKeyCredentialCreationOptions JSON — 브라우저 `navigator.credentials.create()`에 그대로 전달 |

```bash
curl -X POST http://localhost:8080/api/v1/rp/registration/start \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: pk_devacme0dev_acme_secret_known_plaintext_for_local_test_only' \
  -d '{"userHandle":"ZGV2LXVzZXItMDAx","displayName":"Dev User","username":"dev-user-001"}'
```

### 4.2 `POST /api/v1/rp/registration/finish`

브라우저 등록 결과 검증·저장.

- **인증**: X-API-Key (scope `registration`)
- **요청 body**:

  | 필드 | 타입 | 필수 | 설명 |
  |---|---|---|---|
  | `registrationToken` | string | ✅ | start 응답의 토큰 |
  | `publicKeyCredential` | object | ✅ | 브라우저 `navigator.credentials.create()` 결과(PublicKeyCredential JSON) |

- **응답 `data`** (`RegistrationFinishResponse`):

  | 필드 | 타입 | 설명 |
  |---|---|---|
  | `credentialId` | string | 저장된 credential ID(base64url) |
  | `aaguid` | string | authenticator AAGUID |
  | `attestationFormat` | string | attestation 포맷 |
  | `createdAt` | string(ISO instant) | 생성 시각 |

### 4.3 `POST /api/v1/rp/authentication/start`

패스키 인증 ceremony 시작 — challenge 포함 request options 발급.

- **인증**: X-API-Key (scope `authentication`)
- **요청 body**:

  | 필드 | 타입 | 필수 | 설명 |
  |---|---|---|---|
  | `userHandle` | string | ❌ | 지정 시 해당 사용자의 credential로 좁힘(`allowCredentials`). 생략 시 discoverable(usernameless) 인증. |

- **응답 `data`** (`AuthenticationStartResponse`):

  | 필드 | 타입 | 설명 |
  |---|---|---|
  | `authenticationToken` | string | finish에 돌려줄 불투명 토큰 |
  | `publicKeyCredentialRequestOptions` | object | WebAuthn PublicKeyCredentialRequestOptions JSON — 브라우저 `navigator.credentials.get()`에 전달 |

### 4.4 `POST /api/v1/rp/authentication/finish`

인증 결과 검증 → **ID Token 발급**.

- **인증**: X-API-Key (scope `authentication`)
- **요청 body**:

  | 필드 | 타입 | 필수 | 설명 |
  |---|---|---|---|
  | `authenticationToken` | string | ✅ | start 응답의 토큰 |
  | `publicKeyCredential` | object | ✅ | 브라우저 `navigator.credentials.get()` 결과 |

- **응답 `data`** (`AuthenticationFinishResponse`):

  | 필드 | 타입 | 설명 |
  |---|---|---|
  | `idToken` | string | RS256 서명 JWT(§5). 인증된 사용자 신원. |
  | `tokenType` | string | `"Bearer"` |
  | `expiresIn` | number | 토큰 유효 기간(초). `900`(15분). |

> RP는 `idToken`을 검증(§5)해 사용자 신원을 확인하고, 자체 세션/토큰을 발급한다.

### 4.5 self-service credential 관리

사용자가 자신의 패스키를 조회·이름변경·삭제. 모두 scope `registration`.

#### `GET /api/v1/rp/credentials?userHandle=<base64url>`

- **query**: `userHandle` (string, 필수)
- **응답 `data`**: `CredentialView[]`

  | 필드 | 타입 | 설명 |
  |---|---|---|
  | `credentialId` | string | credential ID(base64url) |
  | `label` | string | 사용자 지정 라벨 |
  | `aaguidHex` | string | authenticator AAGUID(hex) |
  | `lastUsedAt` | string(ISO instant) | 마지막 사용 시각 |

#### `POST /api/v1/rp/credentials/{credentialId}/label`

- **요청 body** (`RenameRequest`):

  | 필드 | 타입 | 필수 | 설명 |
  |---|---|---|---|
  | `userHandle` | string | ✅ | 소유자 식별자 |
  | `label` | string | ✅ | 새 라벨(최대 128자) |

- **응답 `data`**: `null` (성공 envelope)

#### `DELETE /api/v1/rp/credentials/{credentialId}?userHandle=<base64url>`

- **query**: `userHandle` (string, 필수)
- **응답 `data`**: `null`
- ⚠️ 파괴적 작업. rate limit이 가장 엄격(20/min, §7).

### 4.6 `GET /.well-known/jwks.json` (public)

ID Token 서명 검증용 공개키 집합. **인증 불필요.** 응답은 envelope이 아닌 **RFC 7517 raw JSON**.

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

`authentication/finish`가 발급하는 `idToken`은 RS256 서명 JWT다. RP는 다음을 검증해야 한다:

1. **서명**: JWKS(`/.well-known/jwks.json`)에서 JWT 헤더의 `kid`에 해당하는 RSA 공개키로 RS256 서명 검증. (`alg`가 `RS256`인지도 확인 — alg confusion 방지.)
2. **`exp`**: 만료 시각이 현재 이후인지(TTL 15분).
3. **`iss`/`aud`**: 기대값과 일치하는지(아래 claim 참조).

### Claims

| claim | 값 | 설명 |
|---|---|---|
| `iss` | `<issuer-base>/<tenantId>` | 발급자. issuer-base는 운영 환경변수 `PASSKEY_ID_TOKEN_ISSUER_BASE`로 설정. |
| `sub` | base64url(userHandle) | 인증된 사용자(패딩 없는 base64url). |
| `aud` | `<tenantId>` | 대상 테넌트. RP는 자신의 tenantId와 일치하는지 검증 권장. |
| `iat` | epoch seconds | 발급 시각 |
| `exp` | epoch seconds | 만료 시각(iat + 15분) |
| `amr` | `["webauthn"]` | 인증 수단 |
| `cred_id` | base64url(UUID 16바이트) | 사용된 credential ID |
| `aaguid` | hex | authenticator AAGUID (있을 때만) |

> Java RP는 [`sdk-java`](../sdk-java/)의 `IdTokenVerifier`를 쓰면 JWKS 캐시 + RS256 핀 + exp 검증이 내장된다. `expectedIssuer`/`expectedAudience`를 설정해 iss/aud도 강제하는 것을 권장.

---

## 6. 에러 코드

비즈니스 에러는 §3 envelope의 `code`로 식별한다. (인증/rate-limit 실패는 §2/§7의 problem+json.)

| code | HTTP | 의미 | RP가 만나는 상황 |
|---|---|---|---|
| `C001` | 400 | INVALID_INPUT | 필드 검증 실패, 잘못된 base64url, **토큰 만료**, malformed JSON |
| `C003` | 404 | ENTITY_NOT_FOUND | credential/사용자 미존재 |
| `C004` | 400 | MISSING_PARAMETER | 필수 query param 누락(예: `userHandle`) |
| `C005` | 400 | TYPE_MISMATCH | 타입 불일치 |
| `C999` | 500 | INTERNAL_SERVER_ERROR | 서버 오류 |
| `F001` | 400 | CHALLENGE_INVALID | challenge 만료 또는 재사용(start→finish 사이 시간 초과/중복) |
| `F002` | 400 | REGISTRATION_FAILED | 등록 검증 실패(서명/origin/RP ID 불일치 등) |
| `F003` | 401 | AUTHENTICATION_FAILED | 인증 검증 실패(서명/counter 회귀 등) |
| `F004` | 403 | ATTESTATION_REJECTED | 테넌트 attestation 정책 거부 |
| `F005` | 403 | AAGUID_REVOKED | 폐기된 authenticator |
| `L001` | 503 | LICENSE_EXPIRED | (on-prem) 라이선스 만료 |
| `L002` | 403 | FEATURE_NOT_LICENSED | (on-prem) 미라이선스 기능 |

> 인증 실패(잘못된 API key)는 `A001`/envelope이 아니라 §2의 **401 problem+json**으로 반환된다 — 필터 단계에서 끊기기 때문.

---

## 7. Rate Limit

경로별로 IP 버킷 + API-key 버킷 두 가지가 적용된다. 한도 초과 시 **429 Too Many Requests** + `Retry-After: 60` 헤더 + problem+json 본문(`{"type":"about:blank","status":429,"title":"Too Many Requests"}`).

| 경로 | 한도(분당) |
|---|---|
| `POST /registration/start`, `/registration/finish` | 60 |
| `POST /authentication/start`, `/authentication/finish` | 300 |
| `GET /credentials`, `POST /credentials/{id}/label` | 60 |
| `DELETE /credentials/{id}` | 20 |

---

## 8. end-to-end 예시 (curl)

> dev 시드 acme-corp 키 기준. tenant/키 발급은 [dev-setup.md](dev-setup.md) 참조.

### 등록 1사이클

```bash
KEY='pk_devacme0dev_acme_secret_known_plaintext_for_local_test_only'
BASE='http://localhost:8080'

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

---

## 참고

- dev 환경 기동/시드: [dev-setup.md](dev-setup.md)
- on-prem 배포: [onprem-deployment.md](onprem-deployment.md)
- Java SDK: [`sdk-java/`](../sdk-java/) — `PasskeyClient` + `IdTokenVerifier`
- 코드: 컨트롤러 `passkey-app/.../api/v1/rp/`, 인증 필터 `.../security/ApiKeyAuthFilter.java`, ID Token `core/.../jwt/IdTokenIssuer.java`
