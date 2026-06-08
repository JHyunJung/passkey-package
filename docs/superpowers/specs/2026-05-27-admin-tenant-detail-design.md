# Admin Tenant Detail (Detail + Credentials + WebAuthn 설정) 설계 문서

작성일: 2026-05-27
상태: 검토 대기
선행: Phase 0~8 완료, sdk-and-sample-rp dogfood 묶음 완료 (2026-05-27 merge)
관련: Passkey Admin Console 디자인 전체 (외부 디자인 명세, 약 30% 구현 상태)

## 1. 배경과 목표

Passkey Admin Console 디자인 전체는 매우 큰 스코프 — 역할 모델 확장(PLATFORM_OPERATOR/RP_ADMIN), Activity 피드, Audit Chain Monitor, Funnel, 보안 정책 탭, Admin 사용자 관리 등 다수의 독립 영역. 현재 admin-app + admin-ui 구현률은 약 30% (Tenant 생성/목록, API Key 발급/회수, MDS Status, Signing Key 회전, Audit Log 조회/검증).

본 phase 는 **운영자의 가장 빈번한 흐름**(특정 tenant 의 credential 을 보고 회수 + WebAuthn 설정 수정) 을 우선 묶음으로 잡는다. 디자인의 RP_ADMIN 전용 영역 중 "Credentials" 와 "WebAuthn Configuration" 두 탭을 노출하되, role 모델 확장 없이 기존 ADMIN/VIEWER 권한 안에서 동작한다.

### 1.1 Definition of Done

운영자가 admin-ui 로그인 후 다음 전 흐름이 동작한다:

1. `/tenants` 에서 tenant 행 클릭 → `/tenants/:id` 로 이동
2. Tenant Detail 페이지의 4 개 탭 중 **Overview** 에서 기본 정보 + WebAuthn 설정 요약 + KPI 2 종 확인
3. **WebAuthn Configuration** 탭에서 displayName / rpName / allowedOrigins / acceptedFormats / requireUserVerification / mdsRequired 수정 후 저장 → audit log 기록
4. **Credentials** 탭에서 tenant 의 credential 목록 (페이지 50 개) 조회, credentialId / userHandle 일부로 검색, 행 클릭 시 상세, 회수 (마지막 8 자 입력 확인) → audit
5. **API Keys** 탭은 기존 ApiKeyList 페이지 로직을 임베드 (테넌트 컨텍스트 고정)
6. 사이드바에서 "API Keys" 메뉴 제거 (tenant scope 내부로 이동)

### 1.2 의도적 제외 (후속 phase)

| 항목 | 미루는 이유 |
|---|---|
| Role 확장 (PLATFORM_OPERATOR/RP_ADMIN) | 아키텍처 수준 변경, 별도 phase |
| Credential 엔티티 신규 필드 (externalUserId / nickname / status / revokedAt) | 회수는 hard delete 로 시작 |
| Tenant rpId / slug 변경 | credential / VPD 영향 분석 워크플로우 필요 |
| Activity 피드, Audit Chain Monitor, Funnel | metric 인프라 / audit_log tenant_id 컬럼 필요 |
| 보안 정책 탭, 시스템 탭, Admin 사용자 관리 | 별도 phase |
| WebAuthn 설정 diff 미리보기 모달 | 디자인 fidelity 우선순위 후순위 |
| CSV 내보내기, Tweaks 패널, ⌘K 액션 확장 | UX 폴리시 |

## 2. 백엔드 API + 엔티티 변경

### 2.1 엔티티

엔티티 변경 없음. 마이그레이션 없음. Tenant, Credential, ApiKey, AuditLog, MdsBlobCache 그대로 사용.

### 2.2 신규/변경 컨트롤러

#### TenantAdminController 확장

```
PUT /admin/api/tenants/{idOrSlug}        @PreAuthorize("hasRole('ADMIN')")
  request  : TenantAdminDto.TenantUpdateRequest (기존)
  response : ApiResponse<TenantAdminDto.TenantView>
```

`TenantUpdateRequest` record 는 이미 정의됨 — displayName, rpId, rpName, allowedOrigins, acceptedFormats, requireUserVerification, mdsRequired. 본 phase 에서 rpId 는 서비스 측에서 무시 (silent no-op + 디버그 로그). slug 는 record 에 없음.

#### CredentialAdminController 신규

```
@RestController
@RequestMapping("/admin/api/tenants/{tenantId}/credentials")
class CredentialAdminController {
    GET  ""                                          Authenticated
         query: page=0, size=50 (cap 200), q? (선택, base64url 또는 hex 일부)
         response: ApiResponse<PageView<CredentialView>>

    DELETE "/{credentialId}"                         @PreAuthorize("hasRole('ADMIN')")
         path:  credentialId = base64url
         response: ApiResponse<Void>                  audit: CREDENTIAL_REVOKE
}
```

### 2.3 신규 DTO

```java
// admin-app/.../credential/CredentialAdminDto.java
public record CredentialView(
    String  credentialId,        // base64url
    String  userHandle,          // base64url
    String  aaguidHex,           // 32-char hex (HexFormat.formatHex)
    String  authenticatorName,   // MdsAaguidCache 룩업 결과 또는 null
    String  attestationFormat,
    String  transports,
    long    signCount,
    Instant lastUsedAt,
    Instant createdAt
) {}
```

```java
// core/api/PageView.java (신규)
public record PageView<T>(
    List<T> content, int page, int size,
    long totalElements, boolean hasNext
) {
    public static <T> PageView<T> from(Page<T> p) {
        return new PageView<>(p.getContent(), p.getNumber(), p.getSize(),
                              p.getTotalElements(), p.hasNext());
    }
}
```

응답은 항상 기존 `ApiResponse<T>` envelope 으로 감싼다 (docs/spring-boot-api-response-template.md 패턴 그대로). Page → PageView 변환은 Spring Data 의 PageImpl serialization 경고를 피하고 응답 메타 형태를 명시한다.

### 2.4 신규 서비스

#### CredentialAdminService (신규)

`admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminService.java`

```java
@Service
public class CredentialAdminService {

    private final CredentialRepository creds;
    private final MdsAaguidCache mds;
    private final AuditLogService audit;

    public PageView<CredentialView> list(UUID tenantId, int page, int size, String q) {
        size = Math.min(size, 200);
        Pageable pageReq = PageRequest.of(page, size,
                Sort.by("lastUsedAt").descending().nullsLast()
                    .and(Sort.by("id").descending()));
        Page<Credential> rows = (q == null || q.isBlank())
                ? creds.findAllByTenantId(tenantId, pageReq)
                : creds.searchByTenantId(tenantId, normalizeQ(q), pageReq);
        return PageView.from(rows.map(this::toView));
    }

    public void revoke(UUID tenantId, String credentialIdB64,
                       UUID actorId, String actorEmail) {
        byte[] credId = Base64.getUrlDecoder().decode(credentialIdB64);
        Credential c = creds.findByCredentialIdForUpdate(credId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENTITY_NOT_FOUND));
        if (!c.getTenantId().equals(tenantId))
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        byte[] aaguid = c.getAaguid();
        byte[] userHandle = c.getUserHandle();
        creds.delete(c);
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "CREDENTIAL_REVOKE",
                "credential", credentialIdB64,
                Map.of("tenantId", tenantId.toString(),
                       "aaguidHex", aaguid == null ? null : HexFormat.of().formatHex(aaguid),
                       "userHandleB64url", Base64.getUrlEncoder().withoutPadding()
                                                   .encodeToString(userHandle))));
    }

    private CredentialView toView(Credential c) {
        byte[] aaguid = c.getAaguid();
        String aaguidHex = aaguid == null ? null : HexFormat.of().formatHex(aaguid);
        String authName = aaguid == null ? null
                : mds.lookup(aaguid).map(MdsAaguidCache.Entry::description).orElse(null);
        return new CredentialView(
                Base64.getUrlEncoder().withoutPadding().encodeToString(c.getCredentialId()),
                Base64.getUrlEncoder().withoutPadding().encodeToString(c.getUserHandle()),
                aaguidHex, authName,
                c.getAttestationFmt(), c.getTransports(),
                c.getSignCount(), c.getLastUsedAt(), c.getCreatedAt());
    }

    private String normalizeQ(String q) {
        // base64url 시도 → 성공 시 hex 변환. 실패 시 그대로 (운영자가 hex 일부 직접 입력 가능).
        // LIKE wildcard 는 native query 의 ESCAPE '\\' 절과 함께 escape.
        String hexOrRaw;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(q);
            hexOrRaw = HexFormat.of().formatHex(bytes).toLowerCase();
        } catch (IllegalArgumentException e) {
            hexOrRaw = q.toLowerCase();
        }
        return hexOrRaw.replace("\\", "\\\\")
                       .replace("%",  "\\%")
                       .replace("_",  "\\_");
    }
}
```

#### TenantAdminService.update() 신규 메서드

기존 `TenantAdminService` 에 추가:

```java
public TenantView update(String idOrSlug, TenantUpdateRequest req,
                         UUID actorId, String actorEmail) {
    Tenant t = lookup(idOrSlug);                       // 기존 헬퍼 재사용
    TenantSnapshot before = TenantSnapshot.of(t);

    // rpId / slug 무시
    if (req.rpId() != null && !req.rpId().equals(t.getRpId())) {
        log.debug("rpId update ignored — not yet implemented (tenant={})", t.getId());
    }

    t.setDisplayName(req.displayName());
    t.setRpName(req.rpName());
    replaceAllowedOrigins(t, req.allowedOrigins());
    replaceAcceptedFormats(t, req.acceptedFormats());
    t.setRequireUserVerification(req.requireUserVerification());
    t.setMdsRequired(req.mdsRequired());

    tenants.saveAndFlush(t);
    TenantSnapshot after = TenantSnapshot.of(t);
    List<String> changed = before.diff(after);

    if (!changed.isEmpty()) {
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "TENANT_UPDATE",
                "tenant", t.getId().toString(),
                Map.of("before", before, "after", after, "changedFields", changed)));
    }
    return TenantView.from(t);
}
```

`TenantSnapshot` 은 6 개 비교 필드만 담는 record. `diff` 는 field-by-field 동등성 비교 후 변경된 field 이름 리스트.

### 2.5 Repository 변경

`CredentialRepository` 에 메서드 2 개 추가:

```java
Page<Credential> findAllByTenantId(UUID tenantId, Pageable p);

@Query(value = """
    SELECT * FROM credential
    WHERE tenant_id = :tid
      AND (
        LOWER(RAWTOHEX(credential_id)) LIKE LOWER('%' || :q || '%') ESCAPE '\\'
        OR LOWER(RAWTOHEX(user_handle)) LIKE LOWER('%' || :q || '%') ESCAPE '\\'
      )
    """, nativeQuery = true,
    countQuery = """
    SELECT COUNT(*) FROM credential
    WHERE tenant_id = :tid
      AND (
        LOWER(RAWTOHEX(credential_id)) LIKE LOWER('%' || :q || '%') ESCAPE '\\'
        OR LOWER(RAWTOHEX(user_handle)) LIKE LOWER('%' || :q || '%') ESCAPE '\\'
      )
    """)
Page<Credential> searchByTenantId(@Param("tid") UUID tid,
                                  @Param("q") String hexQ, Pageable p);
```

LIKE wildcard escape: 서비스의 `normalizeQ` 가 `\` `%` `_` 를 모두 `\\` `\%` `\_` 로 치환 (§2.4 코드 참고). native query 의 `ESCAPE '\\'` 절이 이 escape 를 인식. Oracle 의 LIKE 는 기본 escape 없으므로 명시 필수.

### 2.6 MdsAaguidCache 위치 이동

현재: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsAaguidCache.java`

이동 후: `core/src/main/java/com/crosscert/passkey/core/mds/MdsAaguidCache.java`

passkey-app 의 import 갱신. admin-app 이 같은 클래스 공유. 회귀는 `Fido2EndToEndIT` + `AdminFlowIT` 가 검출.

### 2.7 인증/권한

- 모든 신규 endpoint: 세션 인증 (기존 `/admin/api/**` 패턴, `.authenticated()`)
- `PUT /tenants/{id}` + `DELETE /credentials/{id}` 는 `@PreAuthorize("hasRole('ADMIN')")` — VIEWER 는 read-only
- VPD: admin-app 은 `APP_ADMIN_USER` 로 접속해 VPD 비활성 → credential 조회 시 `tenant_id = :tid` 명시 필터가 멀티테넌트 boundary 의 단일 방어선

## 3. admin-ui — 컴포넌트, 라우트, 데이터 흐름

### 3.1 라우트 변경 (`App.tsx`)

```diff
  <Route path="/tenants" element={<TenantList />} />
  <Route path="/tenants/new" element={<TenantCreate />} />
+ <Route path="/tenants/:id" element={<TenantDetail />} />
- <Route path="/api-keys" element={<ApiKeyList />} />
  <Route path="/audit" element={<AuditLog />} />
  <Route path="/mds" element={<MdsStatus />} />
  <Route path="/keys" element={<KeyManagement />} />
```

### 3.2 사이드바 (`components/Sidebar.tsx`)

API Keys 메뉴 1 줄 제거. CommandPalette hardcoded 6 항목 중 "API Keys" / "신규 API Key" 도 제거. tenant scope 내부에서만 접근.

### 3.3 TenantList 행 클릭

table row 에 `onClick={() => nav('/tenants/' + t.id)}` + `cursor: pointer`. credential 수 / API key 수 / 마지막 이벤트 컬럼은 후속 phase (별도 metric 집계 필요).

### 3.4 파일 구조

```
src/pages/
├ TenantDetail.tsx               신규 — 라우터 + 탭 컨테이너
├ tenant/                        신규 폴더
│   ├ OverviewTab.tsx
│   ├ WebAuthnConfigTab.tsx
│   ├ CredentialsTab.tsx
│   └ ApiKeysTab.tsx             기존 ApiKeyList 로직 + tenantId props
src/components/
├ RevokeCredentialDialog.tsx     신규
├ Pagination.tsx                 신규
└ Mono.tsx                       신규 (clipboard copy + tooltip)
```

기존 `pages/ApiKeyList.tsx`, `pages/ApiKeyCreateModal.tsx` → `pages/tenant/ApiKeysTab.tsx` 로 이관 (rename + tenantId props 화).

### 3.5 TenantDetail.tsx

```tsx
export default function TenantDetail() {
    const { id } = useParams();
    const [tenant, setTenant] = useState<TenantView | null>(null);
    const [tab, setTab] = useState<TabKey>('overview');

    useEffect(() => {
        api.get<TenantView>(`/admin/api/tenants/${id}`).then(setTenant);
    }, [id]);

    if (!tenant) return <SkeletonLoader />;

    return (
        <div className="stack-4">
            <Header tenant={tenant} />
            <Tabs current={tab} onChange={setTab} />
            {tab === 'overview'    && <OverviewTab    tenant={tenant} />}
            {tab === 'webauthn'    && <WebAuthnConfigTab tenant={tenant} onUpdated={setTenant} />}
            {tab === 'credentials' && <CredentialsTab tenantId={tenant.id} />}
            {tab === 'apikeys'     && <ApiKeysTab     tenantId={tenant.id} />}
        </div>
    );
}
```

탭은 in-component state. 새로고침 시 overview 로 복귀. URL query sync 는 후속 phase.

### 3.6 OverviewTab

| 카드 | 출처 |
|---|---|
| Credentials 수 | `GET /admin/api/tenants/{id}/credentials?page=0&size=1` → `totalElements` |
| API Keys 수 | `GET /admin/api/api-keys?tenantId=...` → `length` |
| WebAuthn 설정 요약 | tenant.rpId / rpName / allowedOrigins (chip) / acceptedFormats / requireUserVerification 배지 |

등록·인증 성공률 7d KPI 와 최근 활동 5건 카드는 metric 인프라 부재로 후속 phase.

### 3.7 WebAuthnConfigTab

```tsx
function WebAuthnConfigTab({ tenant, onUpdated }: Props) {
    const [draft, setDraft] = useState(toDraft(tenant));
    const dirty = !shallowEqual(draft, toDraft(tenant));
    const toast = useToast();

    async function save() {
        const updated = await api.put<TenantView>(
            `/admin/api/tenants/${tenant.id}`,
            {
                displayName: draft.displayName,
                rpName: draft.rpName,
                allowedOrigins: draft.allowedOrigins,
                acceptedFormats: draft.acceptedFormats,
                requireUserVerification: draft.requireUserVerification,
                mdsRequired: draft.mdsRequired,
            });
        onUpdated(updated);
        toast({ kind: 'ok', title: 'WebAuthn 설정 저장됨' });
    }

    return (
        <form className="stack-4">
            <ReadOnlyField label="rpId" value={tenant.rpId}
                           note="rpId 변경은 credential 영향 분석 후 별도 워크플로우" />
            <ReadOnlyField label="slug" value={tenant.slug} />
            <Input label="displayName" value={draft.displayName} onChange={...} />
            <Input label="rpName"      value={draft.rpName}      onChange={...} />
            <OriginChipInput value={draft.allowedOrigins}        onChange={...} />
            <FormatCheckboxGrid value={draft.acceptedFormats}    onChange={...} />
            <Switch label="requireUserVerification" checked={draft.requireUserVerification} onChange={...} />
            <Switch label="mdsRequired"             checked={draft.mdsRequired}             onChange={...} />
            <div className="row">
                <button className="btn btn--primary" disabled={!dirty} onClick={save}>저장</button>
                <button className="btn"               disabled={!dirty} onClick={() => setDraft(toDraft(tenant))}>되돌리기</button>
            </div>
        </form>
    );
}
```

기존 TenantCreate.tsx 의 컴포넌트 (OriginChipInput, FormatCheckboxGrid, Switch) 재사용. diff 미리보기 모달은 후속 phase.

### 3.8 CredentialsTab

```tsx
function CredentialsTab({ tenantId }: { tenantId: string }) {
    const [page, setPage] = useState(0);
    const size = 50;
    const [q, setQ] = useState('');
    const [data, setData] = useState<PageView<CredentialView> | null>(null);
    const [target, setTarget] = useState<CredentialView | null>(null);

    useEffect(() => {
        const qs = new URLSearchParams({
            page: String(page), size: String(size),
            ...(q && { q })
        });
        api.get<PageView<CredentialView>>(
            `/admin/api/tenants/${tenantId}/credentials?${qs}`
        ).then(setData);
    }, [tenantId, page, q]);

    return (
        <div className="stack-3">
            <SearchInput placeholder="credentialId 또는 userHandle 일부…"
                         value={q} onChange={debounced(setQ)} />
            <Table>
                <thead><tr><th>credentialId</th><th>userHandle</th><th>Authenticator</th>
                           <th>fmt</th><th>signCount</th><th>last used</th>
                           <th>created</th><th></th></tr></thead>
                <tbody>{data?.content.map(c => (
                    <tr key={c.credentialId}>
                        <td><Mono short={c.credentialId.slice(-8)} full={c.credentialId} /></td>
                        <td><Mono short={c.userHandle.slice(0, 12)} full={c.userHandle} /></td>
                        <td>{c.authenticatorName ?? <span className="muted">aaguid {c.aaguidHex?.slice(0,8)}…</span>}</td>
                        <td>{c.attestationFormat}</td>
                        <td className="tabular-nums">{c.signCount}</td>
                        <td>{c.lastUsedAt ? formatDateTime(c.lastUsedAt) : '—'}</td>
                        <td>{formatDateTime(c.createdAt)}</td>
                        <td><button className="btn btn--danger btn--sm"
                                    onClick={() => setTarget(c)}>회수</button></td>
                    </tr>
                ))}</tbody>
            </Table>
            <Pagination page={page} size={size} total={data?.totalElements ?? 0}
                        onChange={setPage} />
            {target && <RevokeCredentialDialog credential={target} tenantId={tenantId}
                                                onClose={() => setTarget(null)}
                                                onRevoked={() => { setTarget(null); refresh(); }} />}
        </div>
    );
}
```

`Mono` 는 short text 표시 + hover tooltip (full) + click 으로 clipboard. `Pagination` 은 `<<` `<` X/Y `>` `>>` + page 번호 입력.

### 3.9 RevokeCredentialDialog

```tsx
function RevokeCredentialDialog({ credential, tenantId, onClose, onRevoked }) {
    const last8 = credential.credentialId.slice(-8);
    const [input, setInput] = useState('');
    const match = input === last8;

    async function confirm() {
        await api.delete(
            `/admin/api/tenants/${tenantId}/credentials/${encodeURIComponent(credential.credentialId)}`);
        onRevoked();
    }

    return (
        <Dialog title="Credential 회수" onClose={onClose}>
            <p className="text-danger">
                이 credential 을 회수하면 해당 사용자는 다시 등록(register)해야 합니다.
                취소할 수 없는 작업입니다.
            </p>
            <p>확인을 위해 credential ID 의 마지막 8자를 입력하세요: <Mono>{last8}</Mono></p>
            <input className="input" autoFocus value={input} onChange={e => setInput(e.target.value)} />
            <div className="row">
                <button className="btn" onClick={onClose}>취소</button>
                <button className="btn btn--danger" disabled={!match} onClick={confirm}>회수</button>
            </div>
        </Dialog>
    );
}
```

### 3.10 ApiKeysTab

기존 `ApiKeyList.tsx` 의 로직을 `tenant/ApiKeysTab.tsx` 로 옮기되:
- tenant 드롭다운 제거
- `props.tenantId` 로 고정
- ApiKeyCreateModal 도 `tenantId` props 화

기존 `pages/ApiKeyList.tsx` 와 `pages/ApiKeyCreateModal.tsx` 파일은 삭제.

### 3.11 API 클라이언트 (`api/client.ts` + `api/types.ts`)

`types.ts` 신규:
```ts
export interface TenantUpdateRequest {
    displayName: string;
    rpName: string;
    allowedOrigins: string[];
    acceptedFormats: string[];
    requireUserVerification: boolean;
    mdsRequired: boolean;
}

export interface CredentialView {
    credentialId: string;
    userHandle: string;
    aaguidHex: string | null;
    authenticatorName: string | null;
    attestationFormat: string;
    transports: string;
    signCount: number;
    lastUsedAt: string | null;
    createdAt: string;
}

export interface PageView<T> {
    content: T[];
    page: number;
    size: number;
    totalElements: number;
    hasNext: boolean;
}
```

`client.ts` 신규 helper 3:
```ts
export const updateTenant = (id: string, req: TenantUpdateRequest) =>
    api.put<TenantView>(`/admin/api/tenants/${id}`, req);

export const listCredentials = (
    tenantId: string, params: { page: number; size: number; q?: string }
) => {
    const qs = new URLSearchParams({
        page: String(params.page), size: String(params.size),
        ...(params.q ? { q: params.q } : {})
    });
    return api.get<PageView<CredentialView>>(
        `/admin/api/tenants/${tenantId}/credentials?${qs}`);
};

export const revokeCredential = (tenantId: string, credentialId: string) =>
    api.delete<void>(
        `/admin/api/tenants/${tenantId}/credentials/${encodeURIComponent(credentialId)}`);
```

### 3.12 파일 분할 목표 (라인 추정)

| 파일 | ~라인 | 책임 |
|---|---|---|
| App.tsx | -2 / +1 | 라우트 |
| Sidebar.tsx | -1 | 메뉴 |
| TenantList.tsx | +3 | onClick 라우팅 |
| TenantDetail.tsx | 120 | 탭 컨테이너 |
| tenant/OverviewTab.tsx | 150 | KPI + 요약 |
| tenant/WebAuthnConfigTab.tsx | 250 | form + dirty + save |
| tenant/CredentialsTab.tsx | 220 | 테이블 + 검색 + 페이지 |
| tenant/ApiKeysTab.tsx | 250 | ApiKeyList 이관 |
| components/RevokeCredentialDialog.tsx | 80 | 확인 다이얼로그 |
| components/Pagination.tsx | 50 | 페이지네이션 컨트롤 |
| components/Mono.tsx | 30 | clipboard + tooltip |

## 4. Audit log 통합 + 데이터 흐름

### 4.1 신규 audit action

| action | targetType | targetId | payload |
|---|---|---|---|
| `TENANT_UPDATE` | `tenant` | tenant.id (UUID) | `{ "before": {...}, "after": {...}, "changedFields": [...] }` |
| `CREDENTIAL_REVOKE` | `credential` | credentialId (base64url) | `{ "tenantId": "<uuid>", "aaguidHex": "...", "userHandleB64url": "..." }` |

`AuditLogService.append()` 시그니처 변경 없음. 기존 free-form String action 그대로.

### 4.2 PUT /tenants/{id} 흐름

```
HTTP PUT /admin/api/tenants/{idOrSlug}
  → Authentication 에서 actorEmail, actorId 추출
  → TenantAdminService.update(idOrSlug, req, actorId, actorEmail)
      1. tenant lookup (id UUID 파싱 시도 → 실패 시 slug 조회)
      2. before snapshot
      3. rpId / slug 무시 (로그)
      4. displayName / rpName 단순 set
      5. allowedOrigins → child entity 전부 삭제 후 재삽입 (Tenant.replaceAllowedOrigins)
      6. acceptedFormats → 동일 패턴
      7. requireUserVerification, mdsRequired 단순 set
      8. tenantRepository.saveAndFlush
      9. after snapshot
     10. changedFields = before.diff(after)
     11. changedFields 비어있으면 audit 생략 (no-op)
     12. audit.append(TENANT_UPDATE, ...)
     13. return TenantView.from(tenant)
```

`TenantUpdateRequest` 의 boolean primitive 가 JSON 누락 시 false 로 들어오는 문제는 admin-ui `updateTenant` helper 가 항상 명시값 전송으로 보장. 후속 phase 에서 Boolean wrapper + null = no change 의미론으로 변경 검토.

### 4.3 DELETE /tenants/{id}/credentials/{credentialId} 흐름

```
HTTP DELETE /admin/api/tenants/{tenantId}/credentials/{credentialIdB64}
  → CredentialAdminService.revoke(tenantId, credentialIdB64, actorId, actorEmail)
      1. base64url → byte[] decode
      2. creds.findByCredentialIdForUpdate(byte[])    PESSIMISTIC_WRITE
         - 락으로 passkey-app ceremony 와 race 차단
      3. tenantId 일치 검사 (불일치 시 ACCESS_DENIED)
      4. aaguid / userHandle 임시 보존 (delete 후 payload 용)
      5. creds.delete(c)
      6. audit.append(CREDENTIAL_REVOKE, ...)
```

**보안 핵심**: VPD 가 admin-app 에서 비활성이므로 step 3 의 tenantId 비교가 cross-tenant 누수 방어의 단일 layer. `CredentialAdminControllerSecurityIT` 가 회귀 채널.

### 4.4 GET /tenants/{id}/credentials 흐름

```
HTTP GET /admin/api/tenants/{tenantId}/credentials?page=0&size=50&q=optional
  → CredentialAdminService.list(tenantId, page, size, q)
      1. size = min(size, 200)
      2. PageRequest with sort: lastUsedAt DESC NULLS LAST, id DESC
      3. q blank → findAllByTenantId; q 있음 → searchByTenantId(tenantId, normalizeQ(q), pageReq)
      4. each Credential → CredentialView
         - credentialId / userHandle → base64url 인코딩
         - aaguid → HexFormat.formatHex
         - mds.lookup(aaguid).map(Entry::description).orElse(null)
      5. PageView.from(page)
```

`normalizeQ(q)`: base64url decode 성공 시 hex 로 정규화, 실패 시 q 소문자 그대로. wildcard escape 는 `\%` `\_`.

조회 자체는 audit 안 함 (read-only). 후속 phase 에서 별도 access log 도입 시 추가.

### 4.5 TraceId 전파

`core/api/TraceIdFilter` 가 `X-Trace-Id` 헤더 발급/전파. 신규 endpoint 도 동일. admin-ui `ApiErrorBridge` 가 envelope traceId 를 Toast 에 표시.

## 5. 테스트 전략 (개발 속도 우선, 축소판)

### 5.1 자동 테스트는 2 개만

| 테스트 | 위치 | 왜 필수 |
|---|---|---|
| `CredentialAdminControllerSecurityIT` | `admin-app/.../credential/` | cross-tenant 누수 회귀 채널. 이 회귀가 깨지면 보안 사고 |
| `TenantAdminControllerUpdateIT` | `admin-app/.../tenant/` | PUT happy path + audit log row 검증, envelope 모양 회귀 |

기존 `AdminFlowIT` 패턴 (`@SpringBootTest` + Testcontainers Oracle/Redis + admin 로그인 → endpoint → 검증) 재사용.

### 5.2 시나리오

**CredentialAdminControllerSecurityIT:**
1. tenant_A 에 credential C_A 등록 (passkey-app ceremony 시뮬레이션, AdminFlowIT 패턴)
2. tenant_B 도 생성 (credential 없음)
3. GET /admin/api/tenants/{A}/credentials → C_A 포함
4. GET /admin/api/tenants/{B}/credentials → C_A 미포함
5. DELETE /admin/api/tenants/{B}/credentials/{C_A.id} → 403 ACCESS_DENIED
6. DB 의 credential row 여전히 존재

**TenantAdminControllerUpdateIT:**
1. tenant 생성
2. PUT /admin/api/tenants/{id} 로 displayName + allowedOrigins 변경
3. GET /admin/api/tenants/{id} → 변경값 반영
4. GET /admin/api/audit?action=TENANT_UPDATE → row 1 개, payload.changedFields = ["displayName","allowedOrigins"]
5. PUT 동일 body 재호출 → audit row 추가 안 됨

### 5.3 의도적으로 제외

- Credential 페이지네이션 boundary 테스트 (Spring Data Page 자체 신뢰)
- WebAuthnConfigTab dirty 검사 단위 테스트
- RevokeCredentialDialog 마지막 8자 매칭 단위 테스트
- MdsAaguidCache 이동 후 lookup 동작 — passkey-app 의 기존 사용처가 같은 코드 공유, 회귀 없음
- native query 검색 syntax unit 테스트 — manual smoke 로
- admin-ui 컴포넌트 단위 테스트 (Vitest 셋업 없음, 기존 phase 도 안 함)

### 5.4 통과 기준

- `./gradlew :admin-app:test --tests *Update*IT *Security*IT` → 2 IT 통과
- `./gradlew :admin-app:bootRun` 정상 부팅 (Spring 매핑 테이블에 신규 endpoint 노출)
- admin-ui `npx tsc --noEmit` 통과
- admin-ui `npm run build` 통과
- 수동 smoke 5 단계 (다음 절)

### 5.5 수동 smoke 체크리스트

운영자가 서버 기동 후 5분 내:

1. `/tenants` 행 클릭 → `/tenants/:id` 이동, Overview 첫 화면
2. WebAuthn 탭 → displayName 변경 → 저장 → Toast 성공 → 새로고침해도 값 유지
3. Audit Log 페이지 → `action=TENANT_UPDATE` 필터 → row 보임 + payload.changedFields 확인
4. Credentials 탭 → 등록된 credential 보임 + Authenticator 컬럼 (MDS 또는 aaguid hex)
5. Revoke 다이얼로그 → 마지막 8자 오입력 시 비활성 → 정확히 입력 시 활성 → 회수 → 테이블에서 사라짐 + Audit Log 에 `CREDENTIAL_REVOKE`

## 6. 위험과 후속 작업

### 6.1 위험과 대응

| 위험 | 영향 | 대응 |
|---|---|---|
| admin-app VPD 미적용, tenantId 일치 검사 누락 시 cross-tenant 누수 | 중대 보안 사고 | 모든 신규 service 첫 줄에서 tenantId 검사. SecurityIT 가 회귀 채널. path variable 만 신뢰 (body tenantId 무시) |
| `credentialId base64url` URL path 인코딩 | 회수 실패 | base64url 정의상 `/` `+` 가 `_` `-`. admin-ui 도 `encodeURIComponent` 적용 (이중 안전) |
| PUT 의 rpId/slug silent ignore — 운영자 오해 | UX 혼동 | admin-ui rpId/slug read-only + "별도 워크플로우" 안내. 무시 시 디버그 로그. 후속에서 명시적 거부 |
| boolean primitive JSON 누락 → false 변환 | 보안 정책 약화 | admin-ui helper 가 항상 명시값. 후속에서 Boolean wrapper 도입 |
| native query SQL injection | 보안 사고 | `:q` 파라미터 바인딩만. wildcard `%` `_` escape 처리 (서비스 측 normalizeQ) |
| Credential hard delete — 복구 불가 | 운영 사고 | 마지막 8자 입력 다이얼로그 + audit payload 에 userHandle/aaguidHex 보존. 후속에 soft delete |
| `findByCredentialIdForUpdate` 가 ceremony 락 경쟁 | 회수 도중 인증 지연 | PESSIMISTIC_WRITE 정상 동작. 회수도 ms 단위 |
| MdsAaguidCache 이동 — passkey-app import 깨짐 | 컴파일 실패 | 양쪽 동시 컴파일 검증. AdminFlowIT + Fido2EndToEndIT 회귀 채널 |
| 10만+ credential tenant 에서 offset 페이지 지연 | UI 느림 | dogfood/MVP 충분. 후속에서 cursor 페이지네이션 |
| ApiKeyList → ApiKeysTab 이관 시 tenantId 누락 호출 | 잘못된 tenant 키 표시 | TenantDetail 이 props 강제. export 위치를 `pages/tenant/` 안으로 한정 |

### 6.2 후속 작업

`docs/superpowers/followups/2026-05-27-admin-tenant-detail-followups.md` 에 다음 10 개 항목 추적:

1. Credential 엔티티 확장 (externalUserId / nickname / status / revokedAt) — soft delete 도입
2. Tenant rpId 변경 워크플로우 — credential 영향 분석 다이얼로그 + 재등록 안내
3. WebAuthn 설정 diff 미리보기 모달 (+/- 라인)
4. Activity 페이지 + tenant Overview 의 "최근 활동 5건" — audit_log 에 tenant_id 컬럼
5. Audit Chain Monitor — sparkline + tenant 별 검증 + 월간 PDF
6. Funnel + metric 집계 인프라 (이벤트 source 확장)
7. Role 모델 확장 — PLATFORM_OPERATOR / RP_ADMIN + AdminUser ↔ Tenant 매핑
8. Admin 사용자 관리 UI (추가/정지/초대)
9. 보안 정책 탭 (idle / password / MFA / CORS allowlist) DB 저장
10. Tweaks 패널, 사이드바 audit chain 인디케이터, ⌘K 액션 확장, CSV 내보내기
