# Phase 7 — Tenant Config + ApiKey Scopes Normalization Design

**Date:** 2026-05-27
**Status:** Design (awaiting plan)
**Predecessor:** Phase 6 (UUID migration) — tag `phase-6-uuid-migration-complete`

## 1. Goal

3개 JSON CLOB 컬럼을 관계형 schema로 정규화한다:

| Before (Phase 6) | After (Phase 7) |
|------------------|------------------|
| `tenant.allowed_origins` CLOB JSON array | `tenant_allowed_origin` 자식 테이블 |
| `tenant.attestation_policy` CLOB JSON object | `tenant.require_user_verification` + `tenant.mds_required` 컬럼 + `tenant_accepted_format` 자식 테이블 |
| `api_key.scopes` CLOB JSON array | `api_key_scope` 자식 테이블 |

결과: indexable, FK 제약, DB-level validation 가능, admin-ui form input 구조화 (chip / checkbox grid).

## 2. Why Normalize, Why Now

JSON CLOB 보관 패턴의 단점이 운영 가시권에 들어왔다:

1. **Search/filter 불가** — "scope에 `registration`이 포함된 모든 API key" 같은 query가 application-level filter 강제
2. **Validation 분산** — Java parser (AttestationPolicy.fromJson)와 DB `IS JSON` 제약 2층, 정작 값 범위 화이트리스트는 application-only
3. **코드 중복** — `parseOrigins`가 RegistrationFinishService + AuthenticationFinishService 두 곳에 동일하게 존재
4. **Admin UX 빈약** — operator가 raw JSON `<textarea>`에 직접 입력, 형식 실수 시 server-side error만 받음
5. **Phase 6 V19 schema 재작성 직후** — DROP/recreate 전략이 진행 중인 동안 추가 schema 변경 비용이 가장 낮은 시점

Phase 5 admin UI redesign이 Form input 구조화의 토대 (디자인 시스템 + 컴포넌트)를 만들어 둠. Phase 7은 그 토대 위에 chip-style origin / checkbox-grid format 입력 UX를 올린다.

## 3. Architecture

### 3.1 핵심 설계 결정 (확정)

| 항목 | 결정 |
|------|------|
| 정규화 범위 | 3개 컬럼만 (allowed_origins + attestation_policy + scopes) |
| Protocol-bound 제외 | audit_log.payload (freeform schema-less), signing_key.public_jwk (RFC 7517), mds_blob_cache.blob_jwt (JWT binary) |
| 정규화 패턴 | 모두 자식 테이블 1:N (UUID PK + parent FK + 값 + sort_order) |
| `@ElementCollection` 사용 안 함 | 명시적 entity로 통일 — repository / fetch / index 제어권 |
| Tenant 정책 평탄화 | `require_user_verification` CHAR(1) + `mds_required` CHAR(1) 직접 컬럼 |
| Format 화이트리스트 | DB CHECK constraint로 허용 값 강제 |
| Migration 전략 | V21 Flyway migration — `ALTER TABLE` (Phase 6 V19와 달리, 이번엔 운영 데이터 가정 0 그대로 유지하지만 V19를 수정하지 않고 V21 forward-only) |
| Application parser 제거 | `AttestationPolicy.fromJson` 삭제, `parseOrigins` 두 곳 삭제. JPA entity가 값을 직접 보유 |
| Admin UI UX | Form input 구조화 — origin chip 입력, format checkbox grid, scope checkbox grid, 2 boolean toggle |
| RP API contract | `TenantView`에 동일 필드명으로 노출 (TS interface 호환). admin-ui 동작 무변경 |

### 3.2 새 ERD (3 entities)

```
tenant (UUID id) ────────┬─→ tenant_allowed_origin (UUID id, tenant_id FK, origin, sort_order)
                         │
                         └─→ tenant_accepted_format (UUID id, tenant_id FK, format CHECK)

api_key (UUID id) ─────→ api_key_scope (UUID id, api_key_id FK, scope CHECK)
```

`tenant` 본인은 `require_user_verification CHAR(1)` + `mds_required CHAR(1)` 두 컬럼이 평탄화되어 추가됨.

### 3.3 Schema 상세

#### `tenant` 변경

```sql
ALTER TABLE tenant DROP COLUMN allowed_origins;
ALTER TABLE tenant DROP COLUMN attestation_policy;
ALTER TABLE tenant ADD (
  require_user_verification CHAR(1) DEFAULT 'Y' NOT NULL,
  mds_required              CHAR(1) DEFAULT 'N' NOT NULL,
  CONSTRAINT ck_tenant_uv CHECK (require_user_verification IN ('Y','N')),
  CONSTRAINT ck_tenant_mds CHECK (mds_required IN ('Y','N'))
);
```

#### `tenant_allowed_origin` (신규)

```sql
CREATE TABLE tenant_allowed_origin (
  id          RAW(16)        DEFAULT SYS_GUID() NOT NULL,
  tenant_id   RAW(16)                           NOT NULL,
  origin      VARCHAR2(512)                     NOT NULL,
  sort_order  NUMBER(5,0)    DEFAULT 0          NOT NULL,
  CONSTRAINT pk_tenant_allowed_origin     PRIMARY KEY (id),
  CONSTRAINT fk_tao_tenant                FOREIGN KEY (tenant_id) REFERENCES tenant (id) ON DELETE CASCADE,
  CONSTRAINT uq_tao_tenant_origin         UNIQUE (tenant_id, origin),
  CONSTRAINT ck_tao_origin_format         CHECK (REGEXP_LIKE(origin, '^https?://[a-zA-Z0-9.-]+(:[0-9]+)?$'))
);
CREATE INDEX ix_tao_tenant ON tenant_allowed_origin (tenant_id);
```

`origin VARCHAR2(512)` — WebAuthn origin은 URL이라 충분. `REGEXP_LIKE` CHECK로 형식 1차 방어 (정밀 검증은 webauthn4j `Origin.create()`).

#### `tenant_accepted_format` (신규)

```sql
CREATE TABLE tenant_accepted_format (
  id          RAW(16)       DEFAULT SYS_GUID() NOT NULL,
  tenant_id   RAW(16)                          NOT NULL,
  format      VARCHAR2(32)                     NOT NULL,
  CONSTRAINT pk_tenant_accepted_format    PRIMARY KEY (id),
  CONSTRAINT fk_taf_tenant                FOREIGN KEY (tenant_id) REFERENCES tenant (id) ON DELETE CASCADE,
  CONSTRAINT uq_taf_tenant_format         UNIQUE (tenant_id, format),
  CONSTRAINT ck_taf_format CHECK (format IN
    ('none','packed','android-key','android-safetynet','fido-u2f','apple','tpm'))
);
CREATE INDEX ix_taf_tenant ON tenant_accepted_format (tenant_id);
```

7개 표준 attestation format을 화이트리스트 enum으로 강제.

#### `api_key_scope` (신규)

```sql
CREATE TABLE api_key_scope (
  id          RAW(16)       DEFAULT SYS_GUID() NOT NULL,
  api_key_id  RAW(16)                          NOT NULL,
  scope       VARCHAR2(32)                     NOT NULL,
  CONSTRAINT pk_api_key_scope        PRIMARY KEY (id),
  CONSTRAINT fk_aks_api_key          FOREIGN KEY (api_key_id) REFERENCES api_key (id) ON DELETE CASCADE,
  CONSTRAINT uq_aks_api_key_scope    UNIQUE (api_key_id, scope),
  CONSTRAINT ck_aks_scope CHECK (scope IN ('registration','authentication','admin'))
);
CREATE INDEX ix_aks_api_key ON api_key_scope (api_key_id);
```

3개 scope만 허용 (현재 운영상 충분). 추후 추가 시 ALTER 1회.

#### `api_key` 변경

```sql
ALTER TABLE api_key DROP COLUMN scopes;
-- (scope는 자식 테이블이 보관)
```

### 3.4 VPD policy 영향

`tenant_allowed_origin` + `tenant_accepted_format` — `tenant_id` 컬럼 있지만 VPD 부착 **안 함**. 이유:
- 이 테이블은 admin operation 대상 (operator가 다른 tenant 데이터 관리). VPD가 차단하면 platform admin이 못 봄.
- 데이터 조회는 항상 `tenant_id` JOIN 또는 WHERE 명시 → application-level isolation.

`api_key_scope` — 동일 이유 (admin-only).

(VPD는 Phase 0~3에서 정한 invariant대로 `credential` + `api_key` 두 테이블만 적용 유지.)

### 3.5 JPA entity 매핑

#### `Tenant` 변경

```java
@Entity
public class Tenant {
    // ... id, slug, displayName, status, rpId, rpName, createdAt, updatedAt 유지

    @Column(name = "REQUIRE_USER_VERIFICATION", columnDefinition = "CHAR(1)", nullable = false)
    private String requireUserVerificationFlag;  // 'Y' or 'N'

    @Column(name = "MDS_REQUIRED", columnDefinition = "CHAR(1)", nullable = false)
    private String mdsRequiredFlag;

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    private List<TenantAllowedOrigin> allowedOrigins = new ArrayList<>();

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<TenantAcceptedFormat> acceptedFormats = new HashSet<>();

    // typed getters expose plain values
    public boolean isRequireUserVerification() { return "Y".equals(requireUserVerificationFlag); }
    public boolean isMdsRequired() { return "Y".equals(mdsRequiredFlag); }
    public Set<String> getAcceptedFormatValues() {
        return acceptedFormats.stream().map(TenantAcceptedFormat::getFormat).collect(Collectors.toSet());
    }
    public List<String> getAllowedOriginValues() {
        return allowedOrigins.stream().map(TenantAllowedOrigin::getOrigin).toList();
    }
}
```

#### `TenantAllowedOrigin` (신규 entity)

```java
@Entity
@Table(name = "TENANT_ALLOWED_ORIGIN")
public class TenantAllowedOrigin {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TENANT_ID", nullable = false, columnDefinition = "RAW(16)")
    private Tenant tenant;

    @Column(name = "ORIGIN", length = 512, nullable = false)
    private String origin;

    @Column(name = "SORT_ORDER", nullable = false)
    private int sortOrder;

    // ... constructor, getters, equals/hashCode by (tenant, origin)
}
```

#### `TenantAcceptedFormat` (신규 entity)

같은 패턴 — `format` 필드 + UNIQUE(tenant, format).

#### `ApiKey` 변경

```java
// scopesJson 필드 제거
// 신규:
@OneToMany(mappedBy = "apiKey", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
private Set<ApiKeyScope> scopes = new HashSet<>();

public Set<String> getScopeValues() {
    return scopes.stream().map(ApiKeyScope::getScope).collect(Collectors.toSet());
}
```

#### `ApiKeyScope` (신규 entity)

같은 패턴.

### 3.6 Repository 변경

신규 3개 repository는 stand-alone JPA repository 만들지 않음. Parent entity의 cascade로 관리. 단, **자식만 조회/삭제할 케이스**가 있으면 추가.

확실히 필요한 케이스:
- 없음 (현재 application에서 origin 단일 조회/삭제 API가 없음 → cascade로 충분)

### 3.7 Service layer 변경

**`AttestationPolicy` record 삭제.** (`passkey-app/.../fido2/policy/AttestationPolicy.java`)

대신 `Tenant`가 직접 값을 노출:
- `tenant.isRequireUserVerification()` boolean
- `tenant.isMdsRequired()` boolean
- `tenant.getAcceptedFormatValues()` Set<String>
- `tenant.getAllowedOriginValues()` List<String>

`RegistrationFinishService` / `AuthenticationFinishService`:
- `parseOrigins(t)` 메서드 삭제
- `Set<Origin> origins = t.getAllowedOriginValues().stream().map(Origin::create).collect(Collectors.toSet());` 인라인

`AttestationPolicyTest.java`:
- 삭제 (AttestationPolicy record 삭제 함께)
- Tenant entity-level 단위 테스트로 대체 (boolean 값, format set 동작)

### 3.8 Admin UI 변경

#### `TenantCreate.tsx` form 구조화

**Before (Phase 5):**
```tsx
<textarea value={allowedOriginsJson} ... />  // raw JSON
<textarea value={attestationPolicyJson} ... />  // raw JSON
```

**After (Phase 7):**

```tsx
// Origin chip input
<OriginChipInput value={origins} onChange={setOrigins} />
//    rendering: chip list + "+ 추가" 버튼 + 새 origin URL 입력 placeholder
//    validation: regex /^https?:\/\/.../

// Format checkbox grid
<FormatCheckboxGrid value={acceptedFormats} onChange={setFormats} />
//    7 checkboxes: none, packed, android-key, android-safetynet, fido-u2f, apple, tpm
//    default: all 7 checked (operator unchecks unwanted)

// UV toggle
<Switch label="User Verification 필수" checked={requireUV} onChange={setRequireUV} />
//    default ON (recommended for passkey)

// MDS toggle
<Switch label="FIDO MDS 검증 필수" checked={mdsRequired} onChange={setMdsRequired} />
//    default OFF (소수의 고보안 tenant만 ON)
```

#### `ApiKeyCreateModal.tsx` form 구조화

```tsx
<ScopeCheckboxGrid value={scopes} onChange={setScopes} />
//   3 checkboxes: registration, authentication, admin
//   default: registration + authentication checked
```

#### POST request body shape

**Before:**
```json
{
  "slug": "acme",
  "displayName": "Acme",
  "rpId": "...",
  "rpName": "...",
  "allowedOriginsJson": "[\"http://...\"]",
  "attestationPolicyJson": "{\"acceptedFormats\":[...], ...}"
}
```

**After:**
```json
{
  "slug": "acme",
  "displayName": "Acme",
  "rpId": "...",
  "rpName": "...",
  "allowedOrigins": ["http://...", "..."],
  "acceptedFormats": ["none", "packed"],
  "requireUserVerification": true,
  "mdsRequired": false
}
```

`TenantCreateRequest` DTO 재작성. `ApiKeyCreateRequest`에 `scopes: string[]` 필드 추가 (was `scopesJson: string`).

#### `TenantView` response shape

```typescript
interface TenantView {
  id: string;                       // UUID
  slug: string;
  displayName: string;
  status: string;
  rpId: string;
  rpName: string;
  allowedOrigins: string[];         // ← was allowedOriginsJson: string
  acceptedFormats: string[];        // ← was inside attestationPolicyJson
  requireUserVerification: boolean; // ← was inside attestationPolicyJson
  mdsRequired: boolean;             // ← was inside attestationPolicyJson
  createdAt: string;
  updatedAt: string;
}

interface ApiKeyView {
  // ... existing fields
  scopes: string[];   // ← was scopesJson: string
}
```

기존 admin-ui pages가 사용하는 `t.allowedOriginsJson` / `t.attestationPolicyJson` 표시 부분이 새 필드로 교체됨.

## 4. Component Inventory

**Database:**
- Create `core/src/main/resources/db/migration/V21__tenant_config_normalize.sql`

**Backend — `:core` (new entities + Tenant/ApiKey rewrite):**
- Create `core/src/main/java/com/crosscert/passkey/core/entity/TenantAllowedOrigin.java`
- Create `core/src/main/java/com/crosscert/passkey/core/entity/TenantAcceptedFormat.java`
- Create `core/src/main/java/com/crosscert/passkey/core/entity/ApiKeyScope.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java`

**Backend — `:passkey-app` (remove AttestationPolicy + inline parseOrigins):**
- Delete `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/policy/AttestationPolicy.java`
- Delete `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/policy/AttestationPolicyTest.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsVerifier.java` (policy → tenant.isMdsRequired)

**Backend — `:admin-app` (DTOs + service signatures):**
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminDto.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminDto.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java`

**Frontend — admin-ui:**
- Create `admin-ui/src/components/OriginChipInput.tsx`
- Create `admin-ui/src/components/FormatCheckboxGrid.tsx`
- Create `admin-ui/src/components/ScopeCheckboxGrid.tsx`
- Create `admin-ui/src/components/Switch.tsx` (toggle UI)
- Modify `admin-ui/src/api/types.ts` (TenantView, TenantCreateRequest, ApiKeyView, ApiKeyCreateRequest)
- Modify `admin-ui/src/pages/TenantCreate.tsx`
- Modify `admin-ui/src/pages/TenantList.tsx` (display new fields)
- Modify `admin-ui/src/pages/ApiKeyCreateModal.tsx`
- Modify `admin-ui/src/pages/ApiKeyList.tsx` (display scopes if shown)

**Tests:**
- Modify `core/src/test/java/com/crosscert/passkey/core/entity/TenantTest.java` (if exists, else create) — new fields/relations
- Modify all `*ControllerSecurityTest.java`, `*ServiceTest.java` that reference allowedOriginsJson/scopesJson/attestationPolicyJson
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java`
- Modify `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/Fido2EndToEndIT.java`

**Documentation:**
- Update RP migration guide if cred_id contract reachable (not in this Phase — internal change only)

## 5. Migration order

```
T1.  V21 Flyway migration: schema changes (DROP cols + new tables + ALTER tenant)
T2.  TenantAllowedOrigin + TenantAcceptedFormat entities (no consumers yet)
T3.  ApiKeyScope entity
T4.  Tenant entity: add OneToMany + tenant.require_user_verification + mds_required
T5.  ApiKey entity: scopesJson → scopes Set<ApiKeyScope>
T6.  Delete AttestationPolicy record + AttestationPolicyTest
T7.  RegistrationFinishService + AuthenticationFinishService — inline parseOrigins,
     use tenant.isRequireUserVerification()
T8.  MdsVerifier — use tenant.isMdsRequired() instead of policy.mdsRequired()
T9.  TenantAdminDto + TenantAdminService — new request/view shapes
T10. ApiKeyAdminDto + ApiKeyAdminService — scopes Set<String>
T11. admin-ui types.ts + new components (OriginChipInput, FormatCheckboxGrid,
     ScopeCheckboxGrid, Switch)
T12. admin-ui TenantCreate.tsx + TenantList.tsx
T13. admin-ui ApiKeyCreateModal.tsx + ApiKeyList.tsx
T14. Update unit tests + service tests (entity-level fixtures with structured data)
T15. Update AdminFlowIT + Fido2EndToEndIT (POST body shape)
T16. DoD verify + tag
```

각 task는 자체적으로 build green 유지. T1 schema 변경 → T2-T5 entity → T6-T8 service → T9-T10 admin DTO → T11-T13 admin-ui → T14-T15 tests → T16 DoD.

## 6. Testing strategy

### Unit
- Tenant + ApiKey entity tests — UV/MDS boolean flag round-trip, Origin/Format/Scope collection management
- TenantAllowedOrigin/Format/ApiKeyScope entity tests — UUID + parent relationship + equals/hashCode

### Service
- TenantAdminServiceTest — create with structured request, response shape includes structured fields
- ApiKeyAdminServiceTest — issue with scopes array, persisted as child rows

### Controller
- *ControllerSecurityTest — response body jsonPath assertions on new field names

### Integration
- **VpdIsolationIT** — verify tenant_allowed_origin / tenant_accepted_format / api_key_scope **not** subject to VPD (admin-only)
- **AdminFlowIT** — 11-step flow uses new POST body shape
- **Fido2EndToEndIT** — registration/authentication ceremony pulls origins + UV + formats from new structure

### Manual smoke (DoD)
- Admin login → Create tenant via new form (chip + checkbox + toggle) → submit → row appears with structured display
- Issue API key with scope checkboxes → list shows scopes as chips
- Trigger registration ceremony → server uses tenant.allowedOrigins / acceptedFormats / requireUserVerification
- AAGUID-restricted scenario → tenant with mds_required=true rejects unknown AAGUID

## 7. Risks & mitigations

| 위험 | 완화 |
|------|------|
| **CHECK constraint format 화이트리스트가 미래 expandability 막음** | DDL 1회 ALTER로 enum 확장 가능. 새 format 추가 빈도 낮음 |
| **`@OneToMany` 잘못 사용 시 N+1 query** | Tenant 조회는 `JOIN FETCH` 또는 EntityGraph 사용. 운영 빈도 낮은 admin operation이라 N+1 영향 미미 |
| **admin-ui 컴포넌트 4개 신규 — 디자인 시스템 일관성** | tokens.css 활용 + Phase 5 디자인 mock에 chip/checkbox 패턴 존재 |
| **데이터 마이그레이션** (만약 dev/staging에 데이터 있으면) | Phase 7 V21에 backfill SQL 포함 — JSON parse → child row insert. 운영 전제는 데이터 없음 |
| **service-layer breaking change — RP 측 X-Tenant-Id 흐름** | 영향 없음 (RP는 tenant.id를 통해 격리만; 내부 origin 처리는 internal) |
| **AttestationPolicy.fromJson 의존 다른 코드** | grep 확인 — 없음 (record 단독 사용). 안전 |
| **WebAuthn ceremony 회귀** | Fido2EndToEndIT가 catch. 8 scenarios |
| **admin-ui 사용자 입력 폼 작동 안 함** | T13 후 Playwright smoke |
| **Tenant.allowedOriginsJson getter 호출하는 외부 코드** | grep 확인 — admin-app 내부 + 테스트. T9 + T14에서 정리 |

## 8. Out-of-scope (의도적 제외)

- **audit_log.payload normalize** — freeform 의도, schema-less로 둠
- **signing_key.public_jwk normalize** — RFC 7517 wire format 강제
- **mds_blob_cache.blob_jwt normalize** — JWT binary
- **credential.backup_state normalize** — webauthn4j 구조 의존, 별도 Phase 후보
- **multi-language slug / displayName** — i18n은 별도 Phase
- **tenant.status enum 강제** (`'active'` lowercase) — Phase 5에서 발견된 case, 별도 정리
- **Scope에 wildcard 또는 패턴 지원** — `*` 같은 권한 매처는 별도 RBAC Phase
- **Origin에 `*.example.com` 같은 wildcard 패턴** — WebAuthn spec 위반, 영구 제외
- **operational data backfill** — 가정상 운영 데이터 0
- **신규 API endpoint** — `/admin/api/tenants/{id}/origins` 같은 명시적 sub-resource API 미도입 (cascade로 충분)

## 9. Plan task 예상 수

16 tasks:
- T1 V21 migration: 1
- T2-T5 entity migrations: 4
- T6-T8 service-layer adaptation: 3
- T9-T10 admin DTO/service: 2
- T11-T13 admin-ui (components + 2 pages + scope page): 3
- T14-T15 tests: 2
- T16 DoD + tag: 1

Phase 6 (23 tasks) / Phase 5 (16 tasks) / Phase 4 (13 tasks) 범위. Backend + UI 모두 영향이라 통합 IT 검증이 핵심.
