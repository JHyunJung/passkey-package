# Phase 1 — `:passkey-app`: 설계 문서

작성일: 2026-05-25
상태: 검토 대기
대상 Phase: 1 / 전체 5개 Phase 중 두 번째
선행 Phase: Phase 0 (Foundation) — `docs/superpowers/specs/2026-05-25-phase-0-foundation-design.md` 완료, tag `phase-0-foundation-complete`

## 1. 배경과 목표

Phase 0에서 두 앱이 부팅되고 Oracle VPD에 의해 테넌트 격리가 동작하는 것을 자동 테스트로 증명했다. Phase 1은 그 위에 `:passkey-app`의 도메인 로직을 구축한다. 끝나면 고객사 RP 백엔드가 우리 RP API를 호출해 실제 passkey 등록·인증을 수행하고 우리가 발급한 ID Token으로 자체 세션을 만들 수 있다.

### 1.1 Phase 1의 목표 (Definition of Done)

`:passkey-app`이 다음을 모두 만족한다:

- `:passkey-app/api/v1/rp/**` 4개 endpoint를 노출 — `/registration/start`, `/registration/finish`, `/authentication/start`, `/authentication/finish`
- 모든 RP endpoint는 `X-API-Key` 인증과 Rate Limit으로 보호됨
- FIDO2 ceremony는 webauthn4j 위에서 동작 (attestation 형식 6종 모두 검증 가능)
- 인증 성공 시 우리 서명키로 RS256 ID Token 발급
- `/.well-known/jwks` 엔드포인트에서 공개키 노출
- 통합 테스트 `Fido2EndToEndIT`가 등록→인증→ID Token 검증→cross-tenant 격리→Rate Limit→오류 처리 8개 시나리오를 검증

## 2. 기술 스택 (확정 / 추가)

| 영역 | 선택 |
|---|---|
| Phase 0에서 결정된 스택 | 그대로 — Java 17, Spring Boot 3.5.x, Gradle Kotlin DSL, Oracle 19c, Redis 7, Flyway, HikariCP |
| FIDO2 ceremony 라이브러리 | **webauthn4j** (Apache 2.0) |
| JWT 라이브러리 | Nimbus JOSE+JWT (Spring Security 기본) |
| Rate Limit | **Bucket4j** + Redis backend |
| API Key hashing | **BCrypt (cost 12)** |
| ID Token 서명 | **RS256**, RSA 2048-bit |

## 3. 의도적으로 Phase 1에 넣지 않는 것 (deferred)

| 항목 | 다룰 Phase |
|---|---|
| API Key 발급 admin UI | Phase 2 |
| API Key revoke, rotate workflow | Phase 2 |
| 운영자 인증 + Spring Session | Phase 2 |
| Admin SPA (React/TS) | Phase 2 |
| `audit_log` 체인 작성·검증 | Phase 2 |
| ID Token 서명키 영구 저장 + 회전 | Phase 2 또는 Phase 3 (key rotation orchestration은 admin 책임) |
| MDS 실제 다운로드, Redis pub/sub, BLOB cache 채움 | Phase 3 |
| `sdk-java`, `examples/sample-rp` | Phase 4 |
| External KMS (AWS KMS, Vault) | Phase 4 이후 |

## 4. RP API 표면 (확정)

모든 endpoint는 `Content-Type: application/json`, RFC 7807 ProblemDetail 에러 응답. 모두 `X-API-Key` 헤더 필수.

### 4.1 POST `/api/v1/rp/registration/start`

**Request:**
```json
{
  "userHandle": "<base64url 32-64 bytes>",
  "displayName": "Choi Crosscert",
  "username": "choi@bank.com"
}
```

**Response 200:**
```json
{
  "registrationToken": "<opaque 32 bytes base64url>",
  "publicKeyCredentialCreationOptions": {
    "challenge": "<base64url>",
    "rp": { "id": "<tenant.rp_id>", "name": "<tenant.rp_name>" },
    "user": { "id": "<userHandle>", "displayName": "...", "name": "..." },
    "pubKeyCredParams": [{"type":"public-key","alg":-7}, {"type":"public-key","alg":-257}],
    "timeout": 60000,
    "attestation": "indirect",
    "authenticatorSelection": {
      "userVerification": "required",
      "residentKey": "preferred"
    }
  }
}
```

서버 책임: challenge를 Redis(`challenge:reg:<registrationToken>`)에 5분 TTL로 저장. payload에는 tenantId, userHandle, attestation policy snapshot 동봉.

### 4.2 POST `/api/v1/rp/registration/finish`

**Request:**
```json
{
  "registrationToken": "<from start>",
  "publicKeyCredential": { /* WebAuthn AuthenticatorAttestationResponse JSON */ }
}
```

서버 책임: token으로 Redis에서 challenge·tenantId·userHandle 조회 → webauthn4j로 origin/challenge/attestation 검증 → tenant attestation_policy 적용 → MDS stub 검증(Phase 1은 always-pass) → `credential` 테이블에 INSERT → Redis 키 삭제(1회용).

**Response 200:**
```json
{
  "credentialId": "<base64url>",
  "aaguid": "<hex>",
  "attestationFormat": "packed",
  "createdAt": "2026-05-25T08:15:30Z"
}
```

### 4.3 POST `/api/v1/rp/authentication/start`

**Request:**
```json
{
  "userHandle": "<base64url>"   // optional — null이면 usernameless 흐름
}
```

**Response 200:**
```json
{
  "authenticationToken": "<opaque base64url>",
  "publicKeyCredentialRequestOptions": {
    "challenge": "<base64url>",
    "rpId": "<tenant.rp_id>",
    "timeout": 60000,
    "userVerification": "required",
    "allowCredentials": [
      { "type":"public-key", "id":"<credential.credential_id base64url>" }
    ]
  }
}
```

서버 책임: userHandle이 주어지면 그 user의 credential 목록을 `allowCredentials`로 반환. 없으면 빈 배열(discoverable credential 흐름).

### 4.4 POST `/api/v1/rp/authentication/finish`

**Request:**
```json
{
  "authenticationToken": "<from start>",
  "publicKeyCredential": { /* WebAuthn AuthenticatorAssertionResponse JSON */ }
}
```

서버 책임: token으로 challenge 조회 → webauthn4j로 signature 검증 → DB의 sign_count와 비교(replay 방어, ↓ 시 거부) → sign_count·last_used_at 갱신 → ID Token 발급.

**Response 200:**
```json
{
  "idToken": "<JWT, RS256>",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

### 4.5 GET `/.well-known/jwks.json`

**Response 200:**
```json
{
  "keys": [
    {
      "kty": "RSA",
      "use": "sig",
      "alg": "RS256",
      "kid": "<deterministic kid from public key thumbprint>",
      "n": "<modulus base64url>",
      "e": "AQAB"
    }
  ]
}
```

Phase 1은 단일 키쌍 (서버 부팅 시 in-memory 생성). Phase 2 또는 3에서 영구 저장 + 회전 도입.

## 5. ID Token (RS256)

**Claims:**

| Claim | 값 |
|---|---|
| `iss` | `https://passkey.crosscert.com/<tenantId>` (Phase 1 application.yml에서 base URL 설정) |
| `sub` | userHandle (base64url) |
| `aud` | API Key가 발급된 tenant id |
| `iat` | 발급 시각 (epoch seconds) |
| `exp` | iat + 900 (15분) |
| `amr` | `["webauthn"]` |
| `cred_id` | credential.id (base64url) |
| `aaguid` | credential.aaguid (hex). credential의 aaguid가 null이면 claim 자체를 omit. |

만료 15분은 운영 합의 기본값. 필요 시 tenant별 override는 Phase 2.

## 6. 데이터 모델 변경

### 6.1 `tenant` 테이블 확장 (Flyway V6)

새 컬럼:

- `rp_id VARCHAR2(256) NOT NULL` — 예: `crosscert.com`
- `rp_name VARCHAR2(256) NOT NULL` — 예: `Crosscert Bank`
- `allowed_origins CLOB NOT NULL` — JSON array, `IS JSON` 제약. 예: `["https://crosscert.com","https://app.crosscert.com"]`
- `attestation_policy CLOB NOT NULL` — JSON, `IS JSON` 제약. Phase 1 기본값:
  ```json
  {
    "acceptedFormats": ["none","packed","android-key","android-safetynet","fido-u2f","apple","tpm"],
    "requireUserVerification": true,
    "mdsRequired": false
  }
  ```

기본값 채우기: 기존 row(`T_A`, `T_B` 같은 Phase 0 테스트 데이터)는 마이그레이션 시 dev 기본값으로 backfill.

### 6.2 `api_key` 테이블 신규 (Flyway V6)

- `id NUMBER(19,0) PK` (sequence `api_key_seq`)
- `tenant_id VARCHAR2(64) NOT NULL` (FK to tenant, VPD 대상)
- `key_prefix VARCHAR2(16) NOT NULL` — 키의 앞 prefix (lookup 인덱스)
- `key_hash VARCHAR2(255) NOT NULL` — BCrypt full hash
- `name VARCHAR2(256) NOT NULL` — 운영자가 부여한 라벨
- `scopes CLOB NOT NULL` — JSON array, `IS JSON`. Phase 1은 `["passkey:register","passkey:authenticate"]` 같은 형식이지만 enforcement는 미실시(필드만 저장). Phase 2에서 RBAC 적용.
- `created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT SYSTIMESTAMP`
- `last_used_at TIMESTAMP WITH TIME ZONE`
- `expires_at TIMESTAMP WITH TIME ZONE` — nullable
- `revoked_at TIMESTAMP WITH TIME ZONE` — nullable

인덱스: `(tenant_id, key_prefix)` UNIQUE — 한 tenant 내에서 prefix 충돌 방지(SaaS 차원의 충돌은 random 32바이트 prefix로 사실상 0).

VPD policy: V3 패턴 그대로 `api_key` 테이블에도 `CREDENTIAL_TENANT_ISOLATION`과 같은 정책 부착(이름은 `API_KEY_TENANT_ISOLATION`).

grants: APP_RUNTIME에 SELECT, UPDATE(last_used_at 갱신용). INSERT/DELETE는 APP_ADMIN만(키 생성/철회는 admin의 일).

### 6.3 `credential` 테이블 (Phase 1에서는 변경 없음)

Phase 0의 컬럼이 충분하다. `backup_state` CLOB은 webauthn4j의 backup state 갱신을 JSON으로 직렬화해 누적.

### 6.4 Flyway V6, V7 분할 결정

- **V6** — `tenant` 컬럼 확장 + 기존 Phase 0 dev 테이블 backfill (필요 시).
- **V7** — `api_key` 테이블 + `api_key_seq` + grants + VPD policy.

V6와 V7을 한 마이그레이션으로 합치지 않는 이유: Flyway는 한 마이그레이션이 atomic이어야 의미가 명확한데, tenant 컬럼 추가와 새 테이블 생성은 의도상 별개의 변경이고 롤백 단위로도 분리가 합리적.

## 7. 컴포넌트 구조 (`:passkey-app` 내부)

```
passkey-app/src/main/java/com/crosscert/passkey/app/
├ PasskeyApplication.java                    (Phase 0 그대로)
├ api/v1/rp/
│   ├ RegistrationController                 (/start, /finish)
│   ├ AuthenticationController               (/start, /finish)
│   └ JwksController                         (/.well-known/jwks.json)
├ fido2/
│   ├ registration/
│   │   ├ RegistrationStartService
│   │   └ RegistrationFinishService
│   ├ authentication/
│   │   ├ AuthenticationStartService
│   │   └ AuthenticationFinishService
│   ├ challenge/
│   │   ├ ChallengeStore                     (Redis JSON store, 5분 TTL)
│   │   └ ChallengeIssuer                    (SecureRandom 32 bytes)
│   ├ idtoken/
│   │   ├ SigningKeyProvider                 (@PostConstruct RSA 2048 keypair)
│   │   └ IdTokenIssuer                      (Nimbus JOSE+JWT RS256)
│   └ mds/
│       └ MdsStubVerifier                    (Phase 1: always-pass; Phase 3에서 구현)
└ security/
    ├ ApiKeyAuthFilter                       (@Order(HIGHEST_PRECEDENCE) — DevTenantHeaderFilter보다 먼저)
    ├ ApiKeyCache                            (Redis 30초 cache to avoid BCrypt on every request)
    └ RateLimitFilter                        (Bucket4j, Redis-backed)
```

`:core/entity`에 `ApiKey` entity 추가 (admin도 read하므로 :core에 위치).
`:core/repository`에 `ApiKeyRepository` 추가.

## 8. 인증 흐름 (요청 진입 → ceremony 실행)

```
HTTP 요청
  ↓
RateLimitFilter           ← 너무 빠른 호출 시 429 + Retry-After
  ↓
ApiKeyAuthFilter          ← X-API-Key 헤더 파싱
                            → 캐시 lookup(Redis apikey:<prefix>)
                            → 없으면 DB lookup → BCrypt match → 캐시 set
                            → TenantContextHolder.set(tenant_id)
  ↓
DevTenantHeaderFilter     ← Phase 1에서 dev/test profile 한정으로 유지
                            (X-API-Key가 이미 set했으면 skip)
  ↓
Controller (RegistrationController 등)
  ↓ (TenantAwareDataSource borrow 시 ctx_pkg.set_tenant 자동 호출)
Service (webauthn4j ceremony, JPA save)
  ↓
Filter chain 끝
  ↓
TenantContextHolder.clear() (finally)
```

`ApiKeyAuthFilter`는 `DevTenantHeaderFilter`보다 먼저 동작. Dev 프로파일에서 X-API-Key가 없고 X-Tenant-Id만 있다면 DevTenantHeaderFilter가 처리(테스트 편의).

## 9. Rate Limit 정책 (Bucket4j)

| Endpoint | 한 tenant당 limit | 한 IP당 limit (보조) |
|---|---|---|
| `/registration/start` | 60 req/min | 10 req/min |
| `/registration/finish` | 60 req/min | 10 req/min |
| `/authentication/start` | 300 req/min | 30 req/min |
| `/authentication/finish` | 300 req/min | 30 req/min |
| `/.well-known/jwks.json` | 무제한 | 무제한 |

값은 Phase 1 합리적 기본값. tenant별 override는 Phase 2에서 admin policy로.

Redis key 패턴: `ratelimit:tenant:<tenant_id>:<endpoint>` + `ratelimit:ip:<ip>:<endpoint>`.

429 응답: `Retry-After: <seconds>` 헤더 + ProblemDetail body.

## 10. Attestation 정책 적용 흐름

`tenant.attestation_policy` JSON을 `AttestationPolicy` 도메인 객체로 파싱.

```java
record AttestationPolicy(
    Set<String> acceptedFormats,
    boolean requireUserVerification,
    boolean mdsRequired
) {}
```

`RegistrationFinishService`는:
1. tenant policy 조회.
2. webauthn4j `WebAuthnManager` 빌더에 acceptedFormats를 주입.
3. `requireUserVerification`을 ceremony 옵션과 일치 확인.
4. `mdsRequired=true`면 `MdsStubVerifier`를 호출하지만 stub은 always-pass (Phase 3에서 실제 검증).

## 11. ID Token 발급

`SigningKeyProvider`:

```java
@Component
public class SigningKeyProvider {
    private final RSAKey signingKey;  // includes private+public

    @PostConstruct
    void init() {
        KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        // wrap into Nimbus RSAKey with kid = thumbprint
    }

    public JWKSet publicJwks() { /* public-only export */ }
    public JWSSigner signer() { /* private */ }
}
```

`IdTokenIssuer`:

```java
public String issue(String tenantId, String userHandleB64, Long credentialId, byte[] aaguid) {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer("https://passkey.crosscert.com/" + tenantId)
        .subject(userHandleB64)
        .audience(tenantId)
        .issueTime(now)
        .expirationTime(now + 15m)
        .claim("amr", List.of("webauthn"))
        .claim("cred_id", base64url(credentialId))
        .claim("aaguid", aaguidHex)
        .build();
    SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKid()).build(),
        claims);
    jwt.sign(signingKey.signer());
    return jwt.serialize();
}
```

## 12. 통합 테스트 — `Fido2EndToEndIT`

`@SpringBootTest @ActiveProfiles("test") @Testcontainers`. 동일한 Testcontainers Oracle XE + Redis 7 컨테이너를 사용. webauthn4j의 `TestAttestationStatementBuilder` 같은 in-memory authenticator simulator를 사용 (실제 USB key 없이 ceremony 시뮬레이션).

테스트 시나리오 (8개):

1. **Happy path registration** — start → finish → DB에 credential row 1개.
2. **Happy path authentication** — start → finish → JWT 발급 → JWKS로 검증 OK.
3. **Cross-tenant credential isolation** — tenant A의 credential은 tenant B의 API Key로 접근 불가.
4. **Wrong API Key** → 401.
5. **Missing API Key** → 401.
6. **Expired challenge** (5분 후 finish 시도) → 400.
7. **signCount replay defense** — assertion의 signCount가 DB값 이하면 거부.
8. **Rate Limit 429** — 빠른 연속 호출이 한계 초과 시.

## 13. 위험 요소와 대응

| 위험 | 대응 |
|---|---|
| webauthn4j 의존성이 BouncyCastle/Jackson 등을 끌고와 Spring Boot 기본 의존과 충돌 | 정확한 BOM 확인, 필요 시 exclude. |
| Bucket4j 8.x의 Redis backend 패키지 좌표가 자주 바뀜 | 실제 구현 시 최신 Bucket4j 문서 확인, libs.versions.toml에 명시. |
| in-memory 서명키는 서버 재시작 시 변경 — 발급된 JWT 무효화 | Phase 1 범위 — 운영 안정성은 Phase 2/3에서 영구 저장 + 회전으로 해결. test 환경에서는 부팅마다 새로 시작 OK. |
| MDS 미적용 상태에서 attestation 검증 부실 위험 | tenant policy의 `mdsRequired=false` 기본값 — 운영자가 의식적으로 활성화하기 전에는 stub로 통과시킴(Phase 1은 등록 가능성을 우선). prod-hardening followups에 기록. |
| RP가 잘못된 origin에서 ceremony 시작 | `tenant.allowed_origins` 화이트리스트로 webauthn4j가 origin 검증, 안 맞으면 400. |
| API Key가 dev/test 환경에서 hardcode되어 git에 들어감 | followups에 기록; Phase 2 admin UI 도입 시 자동 발급 흐름 정착. |

## 14. 후속 Phase로 넘기는 결정

- 운영자 권한 모델 (RBAC vs ABAC), audit_log 체인 알고리즘 → Phase 2
- API Key admin UI, key rotation, key 발급 워크플로우 → Phase 2
- ID Token 서명키 영구 저장 + 회전 + 만료된 키 retention → Phase 2 또는 3
- MDS 실제 다운로드 + Redis pub/sub + BLOB cache 채움 → Phase 3
- SDK 패키지 좌표 및 배포 채널, sample-rp 구현 → Phase 4
