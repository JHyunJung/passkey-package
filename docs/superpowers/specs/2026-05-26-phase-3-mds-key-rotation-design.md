# Phase 3 — MDS BLOB 검증 + ID Token 서명키 회전 Design

작성일: 2026-05-26
선행: Phase 0/1/2 완료 (admin-app + passkey-app + audit hash chain).

## 0. 한 줄 요약

(1) admin scheduler가 FIDO MDS3에서 BLOB을 주기 fetch + 검증 + 캐시하여
passkey-app이 attestation 시 실제 AAGUID status를 강제. (2) ID Token
서명키를 DB에 envelope-암호화하여 영구 저장 + admin 수동 회전 + grace
period를 둔 ROTATED → REVOKED 자동 만료.

## 1. Phase 3 범위

- **MDS BLOB scheduler** (admin): FIDO MDS3 자동 fetch, JWT 서명 검증,
  mds_blob_cache UPSERT, Redis cache 무효화
- **MdsVerifier** (passkey-app): mds_blob_cache 조회, AAGUID status 확인,
  blocking status 거부
- **서명키 영구 저장** (core + admin): signing_key 테이블, AES-256-GCM
  envelope 암호화, 부팅 시 ACTIVE 키 로드/생성
- **수동 회전** (admin): admin UI 트리거, ACTIVE → ROTATED 전환, 새 ACTIVE 생성
- **Grace period 만료** (admin scheduler): ROTATED + 30min → REVOKED 자동 전환,
  JWKS에서 제거

### Phase 4+로 미루는 항목 (의식적 제외)

- Master key를 AWS KMS / HashiCorp Vault로 마이그레이션 (현재는 env var)
- MDS fetch 실패 alert (Slack/email)
- ES256 등 다중 알고리즘 지원
- 오래된 REVOKED 키 archive policy
- Tenant attestation policy 자동 갱신 (REVOKED AAGUID 사용 credential 알림)

## 2. 합격 게이트

두 IT가 통과해야 Phase 3 완료:

### MdsSchedulerIT (Testcontainers Oracle+Redis + WireMock)

```
① WireMock stub: GET /mds3 → 미리 빌드한 valid signed JWT BLOB 응답
② admin scheduler tryAcquire("mds-sync") → fetch → verify → mds_blob_cache UPSERT
③ DB row 검증: version, next_update, blob_jwt 채워짐
④ Redis cache 무효화 확인 (DEL "mds:aaguid:*")
⑤ audit_log: MDS_BLOB_SYNC row + payload {version, fetched_at}
⑥ MdsVerifier.verify(packed-attestation aaguid) → true
⑦ MdsVerifier.verify(REVOKED-aaguid) → false
⑧ WireMock 503 → scheduler skip + 다음 cycle 대기 + 기존 cache 유지
⑨ 동시 multi-instance 시뮬레이션: 두 threads tryAcquire → 한 쪽만 성공
```

### KeyRotationIT (Testcontainers Oracle)

```
① 부팅 → SigningKeyProvider가 ACTIVE 키 1개 생성, audit_log SIGNING_KEY_INIT
② admin POST /admin/api/keys/rotate → 201
③ DB: ACTIVE 1개 + ROTATED 1개 (rotated_at set)
④ GET /.well-known/jwks.json → 2개 키 모두 포함
⑤ 구 ACTIVE 키로 발급된 가짜 JWT (테스트 helper) → verify OK
⑥ rotated_at + 30min 직접 UPDATE (시간 advance 시뮬레이션)
⑦ KeyExpirationJob 트리거 → 구 키 REVOKED
⑧ JWKS → ACTIVE 1개만
⑨ 구 JWT → JWKS에서 kid 못 찾음 → verify fail
⑩ envelope tamper 시뮬레이션: DB의 private_pkcs8 한 바이트 변경 →
   부팅 시 AEADBadTagException → ApplicationFailedEvent (재시작 거부)
```

## 3. 기술 스택

- 백엔드: Spring Boot 3.5.14 (기존), Spring `@Scheduled`, `@EnableScheduling`
- MDS HTTP client: Apache HttpClient 5 (Spring Boot가 가져옴) 또는 `RestClient`
- JWT 검증: Nimbus JOSE+JWT 9.40 (Phase 1 기존)
- AES-256-GCM: JDK 표준 (`Cipher`, `GCMParameterSpec`)
- WireMock 3.x for IT (FIDO MDS3 endpoint stub)
- 프론트엔드: 기존 React+Vite SPA에 2개 페이지 추가

## 4. 파일 구조

```
core/src/main/java/com/crosscert/passkey/core/
├ entity/SigningKey.java                              (NEW — V15 매핑)
├ repository/SigningKeyRepository.java                (NEW)
├ jwt/
│  ├ SigningKeyProvider.java                          (재작성 — DB-backed)
│  ├ JwksAssembler.java                               (NEW — ACTIVE+ROTATED 합치기)
│  └ KeyEnvelope.java                                 (NEW — AES-256-GCM)
└ resources/db/migration/
   ├ V15__signing_key_table.sql                       (NEW)
   ├ V16__signing_key_runtime_grants.sql              (NEW — APP_RUNTIME SELECT)
   └ V17__mds_blob_cache_singleton_seed.sql          (NEW — id=1 sentinel row)

admin-app/src/main/java/com/crosscert/passkey/admin/
├ mds/                                                 (NEW)
│  ├ MdsClient.java                                   (FIDO MDS3 HTTP fetch)
│  ├ MdsBlobVerifier.java                             (JWT 서명 + cert chain 검증)
│  ├ MdsBlobParser.java                               (JWT payload → MdsEntry list)
│  ├ MdsEntry.java                                    (record: aaguid, statusReports, metadata)
│  ├ MdsSchedulerService.java                         (lease + fetch + 저장 + 무효화)
│  ├ MdsSyncJob.java                                  (@Scheduled(fixedDelayString))
│  └ MdsAdminController.java                          (GET /admin/api/mds/status, POST /sync)
├ keymgmt/                                             (NEW)
│  ├ KeyRotationService.java                          (rotate 트리거)
│  ├ KeyExpirationJob.java                            (@Scheduled, ROTATED→REVOKED)
│  └ KeyMgmtController.java                           (GET /admin/api/keys, POST /keys/rotate)
└ scheduler/                                           (NEW — 공유)
   └ SchedulerLeaseService.java                       (JdbcTemplate SELECT FOR UPDATE)

admin-app/src/main/resources/
└ fido/
   └ fido-mds-root.crt                                (NEW — FIDO Alliance root CA)

passkey-app/src/main/java/com/crosscert/passkey/app/
└ fido2/mds/
   ├ MdsStubVerifier.java                             (DELETE)
   ├ MdsVerifier.java                                 (NEW — mds_blob_cache 참조)
   └ MdsAaguidCache.java                              (NEW — Redis 30분 cache)

admin-ui/src/
├ pages/
│  ├ MdsStatus.tsx                                    (NEW)
│  └ KeyManagement.tsx                                (NEW)
└ App.tsx                                             (route 추가)
```

## 5. 데이터 모델

### 5.1 signing_key (V15)

```sql
CREATE SEQUENCE signing_key_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE signing_key (
  id                NUMBER(19,0)             NOT NULL,
  kid               VARCHAR2(64)             NOT NULL,
  alg               VARCHAR2(16)             NOT NULL,
  status            VARCHAR2(16)             NOT NULL,
  public_jwk        CLOB                     NOT NULL,
  private_pkcs8     BLOB                     NOT NULL,
  created_at        TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  rotated_at        TIMESTAMP WITH TIME ZONE,
  revoked_at        TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_signing_key PRIMARY KEY (id),
  CONSTRAINT uq_signing_key_kid UNIQUE (kid),
  CONSTRAINT ck_signing_key_status CHECK (status IN ('ACTIVE','ROTATED','REVOKED')),
  CONSTRAINT ck_signing_key_alg CHECK (alg IN ('RS256'))
);
CREATE INDEX signing_key_status_ix ON signing_key(status);

GRANT SELECT, INSERT, UPDATE ON signing_key TO APP_ADMIN;
GRANT SELECT ON signing_key_seq TO APP_ADMIN;
-- V16: GRANT SELECT ON signing_key TO APP_RUNTIME (passkey-app 읽기용)
```

### 5.2 상태 전환

```
        admin manual rotate            grace period elapses (30min default)
ACTIVE ──────────────────────► ROTATED ─────────────────────────────► REVOKED
                                  │                                        │
                                  │ JWKS exposes                           │ JWKS hides
                                  │ rotated_at set                         │ revoked_at set
                                  ▼                                        ▼
       (구 JWT verify OK)                          (구 JWT verify FAIL — 401)
```

**JWKS 응답:** `status IN ('ACTIVE', 'ROTATED')`. REVOKED 제외.

**`KeyExpirationJob`** (@Scheduled fixedDelay=1min): ROTATED 중
`rotated_at + 30min < now()` 인 row → status='REVOKED', revoked_at=now() +
audit_log `SIGNING_KEY_REVOKE` append.

### 5.3 mds_blob_cache singleton (V17)

기존 V1의 multi-row 가능 schema는 그대로 두되, application 코드에서
`id=1` 고정 UPSERT (Oracle `MERGE`). V17은 id=1 sentinel을 빈 BLOB로 seed.

```sql
MERGE INTO mds_blob_cache USING dual ON (id = 1)
WHEN NOT MATCHED THEN INSERT (id, version, next_update, fetched_at, blob_jwt)
  VALUES (1, 0, DATE '1970-01-01', TIMESTAMP '1970-01-01 00:00:00 +00:00', '{}');
```

`mds_blob_cache_seq`는 사용 안 함 (drop은 followup).

## 6. Scheduler 패턴

### 6.1 SchedulerLeaseService

Phase 0의 `scheduler_lease` 테이블 활용. SchedulerLease entity는 read-only 유지하고 `SchedulerLeaseService`는 JdbcTemplate 기반 (Phase 2 AuditLogService와 동일 패턴):

```java
public class SchedulerLeaseService {
    /** Try to acquire lease. SELECT FOR UPDATE on row, then UPDATE expires_at.
     *  Returns true if we hold the lease. */
    public boolean tryAcquire(String name, String holder, Duration ttl);

    public void release(String name, String holder);
}
```

### 6.2 두 scheduler 동작

| Job | fixedDelay | Lease name | Lease TTL | 작업 |
|---|---|---|---|---|
| MdsSyncJob | 6h | "mds-sync" | 5min | fetch + verify + UPSERT + cache invalidate |
| KeyExpirationJob | 1min | "key-expiration" | 30s | ROTATED → REVOKED 전환 |

부팅 시 jitter: `@Scheduled(initialDelayString = "${...:PT30S}")`로 multi-instance 동시 실행 방지.

## 7. MDS 검증 흐름

### 7.1 admin scheduler

```
MdsClient.fetch()
  → GET https://mds3.fidoalliance.org/ (Accept: application/jose)
  → response body = JWT (header.payload.signature)

MdsBlobVerifier.verify(jwt)
  → JWT 헤더 x5c chain 추출
  → FIDO root CA (classpath:fido/fido-mds-root.crt)와 chain 검증
  → cert 만료/revocation 확인
  → signature verify (RS256)
  → payload의 nextUpdate 값 추출

UPSERT mds_blob_cache (id=1, version, next_update, blob_jwt, fetched_at)

Redis: DEL keys matching "mds:aaguid:*"
audit_log: MDS_BLOB_SYNC + payload {version, fetched_at}
```

### 7.2 passkey-app registration 시점

```
RegistrationFinishService → MdsVerifier.verify(policy, aaguid)
  if !policy.mdsRequired() → return true     (Phase 1과 동일)

  MdsAaguidCache.lookup(aaguid):
    Redis cache hit → return cached MdsEntry
    miss → mds_blob_cache 조회 + JWT parse + entries 색인 + cache (TTL 30min)
           → return cached MdsEntry or null

  entry == null & mdsRequired:
    → log.warn + return false   (fail-closed)

  entry.statusReports[last].status ∈ BLOCKING_SET → return false
  otherwise → return true
```

### 7.3 Blocking status set

`REVOKED, USER_VERIFICATION_BYPASS, ATTESTATION_KEY_COMPROMISE,
USER_KEY_REMOTE_COMPROMISE, USER_KEY_PHYSICAL_COMPROMISE`

W3C WebAuthn Level 3 권장 reject 목록.

그 외 (`FIDO_CERTIFIED_L1/L2/L3, UPDATE_AVAILABLE, NOT_FIDO_CERTIFIED,
SELF_ASSERTION_SUBMITTED, REVISION_LISTED`)는 통과.

## 8. Key envelope 암호화

### 8.1 KeyEnvelope (AES-256-GCM)

```java
@Component
public class KeyEnvelope {
    private static final int NONCE_LEN = 12;
    private static final int TAG_LEN_BITS = 128;
    private final SecretKey masterKey;
    private final SecureRandom random;

    public KeyEnvelope(@Value("${passkey.key-envelope.master-key}") String masterB64,
                       SecureRandom random) {
        byte[] keyBytes = Base64.getDecoder().decode(masterB64);
        if (keyBytes.length != 32) {
            throw new IllegalStateException("master key must be 32 bytes (AES-256)");
        }
        this.masterKey = new SecretKeySpec(keyBytes, "AES");
        this.random = random;
    }

    /** plaintext PKCS8 → nonce(12) || ciphertext || tag(16). */
    public byte[] seal(byte[] pkcs8);

    /** envelope → plaintext PKCS8. Throws AEADBadTagException on tamper. */
    public byte[] open(byte[] envelope);
}
```

### 8.2 Master key 보관

- `application-local.yml`: 하드코딩 dev key
  ```yaml
  passkey:
    key-envelope:
      master-key: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" # 32-byte b64, dev only
  ```
- prod: 환경변수 `PASSKEY_KEY_ENVELOPE_MASTER_KEY`
- Spring Boot 환경변수 → property mapping: `PASSKEY_KEY_ENVELOPE_MASTER_KEY` →
  `passkey.key-envelope.master-key`

Followup-notes에 명시:
- 환경변수 설정 필수
- Key rotation은 envelope key 변경과 독립 — envelope key 변경은 별도 운영
  절차 (모든 키 re-seal)
- Phase 4+ KMS 마이그레이션 경로

### 8.3 SigningKeyProvider 재작성

```java
@Component
public class SigningKeyProvider {
    private final SigningKeyRepository repo;
    private final KeyEnvelope envelope;
    private final ObjectMapper mapper;
    private final AuditLogService audit;   // SIGNING_KEY_INIT
    private final Clock clock;
    private volatile RSAKey cachedActive;

    @PostConstruct
    public void init() {
        SigningKey active = repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")
                .orElseGet(this::createInitialKey);
        cachedActive = loadRsaKey(active);
    }

    public RSAKey signingKey() { return cachedActive; }

    public JWKSet publicJwkSet() {
        // ACTIVE + ROTATED 모두 노출
        return new JwksAssembler(repo).build();
    }

    /** Called by KeyRotationService.rotate() after DB update. */
    public void reload() {
        // re-read ACTIVE row + replace cachedActive
    }

    private SigningKey createInitialKey() {
        // RSA-2048 생성 + envelope seal + INSERT (status='ACTIVE')
        // audit_log SIGNING_KEY_INIT
    }
}
```

### 8.4 KeyRotationService

```java
@Service
public class KeyRotationService {
    @Transactional
    public RotateResult rotate(long actorId, String actorEmail) {
        SchedulerLeaseService.tryAcquire("key-rotation", holder, 30s)
          failure → throw IllegalStateException → 409 Conflict
        새 RSA-2048 생성 + envelope seal
        현재 ACTIVE → status='ROTATED', rotated_at=now()
        새 키 INSERT (status='ACTIVE')
        signingKeyProvider.reload()
        audit_log: SIGNING_KEY_ROTATE + payload {old_kid, new_kid}
        return RotateResult(newKid, oldKid)
    }
}
```

## 9. RBAC matrix (Phase 2 확장)

| Method | Path | ADMIN | VIEWER |
|---|---|---|---|
| GET | /admin/api/mds/status | ✓ | ✓ |
| POST | /admin/api/mds/sync | ✓ | 403 |
| GET | /admin/api/keys | ✓ | ✓ |
| POST | /admin/api/keys/rotate | ✓ | 403 |

## 10. 위험 & 완화

| 위험 | 완화 |
|---|---|
| FIDO MDS3 endpoint 다운/지연 | scheduler skip + 기존 cache 유지. 6h 주기 충분 retry. Followup: 24h+ stale alarm |
| MDS BLOB cert chain 만료 | MdsBlobVerifier가 만료 cert 거부 + fail-closed. 정기 chain 검증 followup |
| Envelope master key 유실 | 모든 ACTIVE+ROTATED 키 복호화 불가 → 강제 회전. Followup: master key 별도 backup + rotation 절차 |
| Envelope master key 노출 | DB dump 동시 노출 시 모든 서명키 복호화. env var 보관 + filesystem read 제한 + Phase 4+ KMS |
| Key rotation 도중 race | SchedulerLease pessimistic lock. 두 번째 rotate는 409 Conflict |
| ROTATED → REVOKED 시간 단축 (15min grace 부족) | 기본 30min, `passkey.key-rotation.grace` property override |
| 테스트용 fake MDS BLOB의 cert chain | 테스트 전용 self-signed CA + 테스트 fixture. test profile에서만 root CA swap |
| `@Scheduled` 부팅 시 동시 동작 | `initialDelay` 지터 + SchedulerLease |
| ddl-validate가 새 컬럼 미존재 시 fail | V15 마이그레이션이 admin-app boot 시 먼저 적용. V16 APP_RUNTIME grant 필수 (Phase 2 V12/V13 패턴) |

## 11. Phase 4+로 미루는 항목

- Master key → AWS KMS / HashiCorp Vault 마이그레이션
- MDS fetch 실패 alert (Slack/email/PagerDuty)
- ES256 등 다중 알고리즘 (현재 RS256 only)
- 오래된 REVOKED 키 archive (S3 + Object Lock)
- Tenant attestation policy 자동 갱신 (REVOKED AAGUID 사용 credential
  알림)
- mds_blob_cache_seq drop (V17이 sequence 사용 안 하지만 drop은 followup)

## 12. Plan task 예상 수

25-28 tasks:

- V15/V16/V17 migration: 3
- core 신규/변경: SigningKey entity+repo, KeyEnvelope, SigningKeyProvider
  재작성, JwksAssembler: 5
- admin-app/scheduler: SchedulerLeaseService: 1
- admin-app/mds: MdsClient, MdsBlobVerifier, MdsBlobParser,
  MdsSchedulerService, MdsAdminController, MdsSyncJob: 6
- admin-app/keymgmt: KeyRotationService, KeyExpirationJob,
  KeyMgmtController + RBAC test: 4
- passkey-app: MdsVerifier (replace stub), MdsAaguidCache: 2
- admin-ui: MdsStatus, KeyManagement 페이지 + nav: 2
- MdsSchedulerIT + KeyRotationIT: 2
- DoD verify + tag: 1

Phase 1 (27) / Phase 2 (25)와 동급.
