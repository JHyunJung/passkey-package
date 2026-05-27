# Admin Role 분리 (PLATFORM_OPERATOR / RP_ADMIN) 설계 문서

작성일: 2026-05-27
상태: 검토 대기
선행: Phase 0~8 + sdk-and-sample-rp + admin-tenant-detail 완료
관련: Passkey Admin Console 디자인의 role 모델 명세

## 1. 배경과 목표

현재 admin-app 은 `ADMIN` / `VIEWER` 2 role 평면 모델. 모든 admin 운영자가 모든 tenant 의 데이터에 접근. dogfood 단계엔 OK 였지만 외부 RP 고객사 운영자에게 자기 tenant 만 보이도록 분리할 시점. Passkey Admin Console 디자인의 `PLATFORM_OPERATOR` / `RP_ADMIN` 모델 적용.

### 1.1 Definition of Done

1. **DB**: V23 마이그레이션이 `admin_user` 의 role CHECK 를 `('PLATFORM_OPERATOR','RP_ADMIN')` 으로 교체 + `tenant_id RAW(16) NULL` 컬럼 + 검증 CHECK (RP_ADMIN 은 tenant_id NOT NULL, PLATFORM_OPERATOR 는 NULL) 추가
2. **V11 시드 변환**: alice → PLATFORM_OPERATOR (tenant_id=NULL), bob → RP_ADMIN (demo-rp tenant 의 admin). demo tenant 는 V23 안에서 결정적 UUID(`...C0DE`)로 INSERT
3. **AdminUser 엔티티**: `tenantId` UUID nullable 필드 + `role` String 그대로 (값만 변경)
4. **로그인 흐름**: `AdminUserDetails` (UserDetails 구현체) — `tenantId` 포함. SecurityContext 의 principal 에서 cast 로 추출
5. **Service boundary**: 모든 admin service 의 list/get/mutate 메서드 첫 줄에서 `TenantBoundary` helper 호출. PLATFORM_OPERATOR 는 무제한, RP_ADMIN 은 자기 tenant 만
6. **`@PreAuthorize` 8 곳**: platform-only (signing key rotate, MDS sync, tenant create, audit) 는 `hasRole('PLATFORM_OPERATOR')`. 양쪽 가능 (tenant update, credential revoke, api-key issue/revoke) 는 `hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')`
7. **admin-ui**: Me 응답에 `tenantId` 추가. 로그인 후 role 기반 자동 라우팅 (`RP_ADMIN` → `/tenants/{myTenantId}`). 사이드바 conditional 렌더 (RP_ADMIN 은 'My Tenant' 단일 메뉴). `PlatformOnlyGuard` 컴포넌트로 platform-only 페이지 보호
8. **테스트**: 자동 IT 2 개만 — `RpAdminBoundaryIT` + `PlatformOperatorUnrestrictedIT`. 기존 `AdminFlowIT` 의 bob VIEWER assertion 갱신

### 1.2 의도적으로 제외 (후속 phase)

| 항목 | 미루는 이유 |
|---|---|
| 운영자 관리 UI (추가/정지/초대 다이얼로그) | 큰 덩어리, 별도 phase |
| MFA 필수 옵션, PENDING status, 초대 이메일 | 운영자 관리 UI 의 일부 |
| audit_log.tenant_id 컬럼 + RP_ADMIN 의 자기 tenant audit 접근 | 마이그레이션 + payload 검색 정책 결정 필요 |
| suspended tenant 의 RP_ADMIN 접근 정책 | 운영 워크플로우 합의 필요 |
| role enum 의 Java enum 화 (현재 String) | type safety 개선, 후속 |
| RP_ADMIN 전용 페이지 재구성 (Overview/Funnel/AAGUID 등) | 디자인의 RP_ADMIN UX 디테일 |

## 2. DB 마이그레이션 + AdminUser 엔티티 + 시드 재설계

### 2.1 V23 마이그레이션

`core/src/main/resources/db/migration/V23__admin_role_separation.sql`:

```sql
-- ============================================================
-- V23 — admin_user role 모델 확장 (ADMIN/VIEWER → PLATFORM_OPERATOR/RP_ADMIN)
-- + tenant_id FK 추가 (RP_ADMIN 의 자기 tenant 매핑)
-- ============================================================

-- 1. tenant_id 컬럼 추가 (RAW(16) NULL) — RP_ADMIN 만 채움
ALTER TABLE admin_user ADD (
  tenant_id RAW(16)  -- NULL 허용: PLATFORM_OPERATOR 는 NULL, RP_ADMIN 은 NOT NULL (CHECK 로 강제)
);

-- 2. FK to tenant (cascade restrict — tenant 삭제 전 admin 정리 필요)
ALTER TABLE admin_user ADD CONSTRAINT fk_admin_user_tenant
  FOREIGN KEY (tenant_id) REFERENCES tenant(id);

-- 3. 인덱스: RP_ADMIN 의 같은 tenant 조회 효율화
CREATE INDEX ix_admin_user_tenant ON admin_user (tenant_id);

-- 4. 기존 ADMIN/VIEWER → PLATFORM_OPERATOR 일괄 UPDATE
--    (alice 는 ADMIN, bob 은 VIEWER — 둘 다 우선 PLATFORM_OPERATOR 변환 후
--     step 9 에서 bob 만 RP_ADMIN 으로 재지정)
UPDATE admin_user SET role = 'PLATFORM_OPERATOR' WHERE role IN ('ADMIN', 'VIEWER');

-- 5. 기존 CHECK 제약 제거
ALTER TABLE admin_user DROP CONSTRAINT ck_admin_user_role;

-- 6. 신규 CHECK — role enum 2 종
ALTER TABLE admin_user ADD CONSTRAINT ck_admin_user_role
  CHECK (role IN ('PLATFORM_OPERATOR', 'RP_ADMIN'));

-- 7. role <-> tenant_id invariant
ALTER TABLE admin_user ADD CONSTRAINT ck_admin_user_role_tenant
  CHECK (
    (role = 'PLATFORM_OPERATOR' AND tenant_id IS NULL)
    OR
    (role = 'RP_ADMIN' AND tenant_id IS NOT NULL)
  );

-- 8. demo tenant 신규 — bob 이 소속될 RP. 결정적 UUID 로 idempotent.
--    NOT EXISTS 가드는 (id, slug) 둘 다 체크 — 운영 환경에 'demo-rp' slug 가
--    다른 id 로 이미 있으면 INSERT skip (unique 위반 방지).
DECLARE
  v_demo_tenant_id RAW(16) := HEXTORAW('0000000000000000000000000000C0DE');
BEGIN
  INSERT INTO tenant (
    id, slug, display_name, rp_id, rp_name, status,
    require_user_verification, mds_required,
    created_at, updated_at
  )
  SELECT
    v_demo_tenant_id, 'demo-rp', 'Demo RP', 'localhost', 'Demo RP',
    'active', 'Y', 'N',
    SYSTIMESTAMP, SYSTIMESTAMP
  FROM dual
  WHERE NOT EXISTS (
    SELECT 1 FROM tenant WHERE id = v_demo_tenant_id OR slug = 'demo-rp'
  );

  INSERT INTO tenant_allowed_origin (id, tenant_id, origin, sort_order)
  SELECT SYS_GUID(), v_demo_tenant_id, 'http://localhost:9090', 0
  FROM dual
  WHERE NOT EXISTS (
    SELECT 1 FROM tenant_allowed_origin WHERE tenant_id = v_demo_tenant_id
  );

  INSERT INTO tenant_accepted_format (id, tenant_id, format)
  SELECT SYS_GUID(), v_demo_tenant_id, 'none' FROM dual
  WHERE NOT EXISTS (SELECT 1 FROM tenant_accepted_format WHERE tenant_id = v_demo_tenant_id AND format='none');

  INSERT INTO tenant_accepted_format (id, tenant_id, format)
  SELECT SYS_GUID(), v_demo_tenant_id, 'packed' FROM dual
  WHERE NOT EXISTS (SELECT 1 FROM tenant_accepted_format WHERE tenant_id = v_demo_tenant_id AND format='packed');
END;
/

-- 9. bob 을 demo tenant 의 RP_ADMIN 으로 재지정.
--    slug 가 이미 있고 다른 id 인 경우에도 그 실제 id 를 가리키도록 SELECT.
UPDATE admin_user
   SET role = 'RP_ADMIN',
       tenant_id = (SELECT id FROM tenant WHERE slug = 'demo-rp')
 WHERE email = 'bob@crosscert.com'
   AND EXISTS (SELECT 1 FROM tenant WHERE slug = 'demo-rp');

COMMIT;
```

**핵심 결정**:
- demo tenant id 를 결정적 UUID (`...C0DE`) 로 고정 → idempotent
- 기존 V11 시드는 그대로, V23 가 UPDATE 로 재지정 (Flyway 원칙: 적용된 마이그레이션 불변)
- `ck_admin_user_role_tenant` CHECK 가 (role, tenant_id) 쌍 invariant 강제
- INSERT 는 NOT EXISTS 가드로 idempotent

### 2.2 AdminUser 엔티티

`core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java`:

```java
@Entity
@Table(name = "admin_user")
public class AdminUser extends BaseEntity {

    @Column(name = "email", nullable = false, length = 255, unique = true)
    private String email;

    @Column(name = "bcrypt_hash", nullable = false, length = 72)
    private String bcryptHash;

    @Column(name = "role", nullable = false, length = 16)
    private String role;          // "PLATFORM_OPERATOR" | "RP_ADMIN"

    @Column(name = "tenant_id", columnDefinition = "RAW(16)")
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID tenantId;        // RP_ADMIN 은 NOT NULL, PLATFORM_OPERATOR 는 NULL

    @Column(name = "enabled", nullable = false, length = 1, columnDefinition = "CHAR(1)")
    private String enabledFlag = "Y";

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    // 기존 getter/setter/recordLogin() 유지

    // 신규 helper
    public boolean isPlatformOperator() { return "PLATFORM_OPERATOR".equals(role); }
    public boolean isRpAdmin()          { return "RP_ADMIN".equals(role); }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
}
```

### 2.3 AdminUserRepository 변경

이번 phase 메서드 추가 없음 — `findByEmail(String)` 만 사용. 후속 phase (운영자 관리 UI) 가 `findAllByTenantId` 추가 예정.

## 3. Spring Security — AdminUserDetails + DetailsService + TenantBoundary

### 3.1 AdminUserDetails (UserDetails 구현체)

`admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetails.java`:

```java
package com.crosscert.passkey.admin.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Spring Security principal — login 시 AdminUserDetailsService 가 채워서
 * SecurityContext 에 박는다. tenantId 가 null 이면 PLATFORM_OPERATOR.
 *
 * Service 계층은 SecurityContextHolder 에서 이 객체를 꺼내 tenant boundary 검사.
 */
public final class AdminUserDetails implements UserDetails {

    private final UUID id;
    private final String email;
    private final String passwordHash;
    private final String role;          // "PLATFORM_OPERATOR" | "RP_ADMIN"
    private final UUID tenantId;        // RP_ADMIN 은 non-null
    private final boolean enabled;

    public AdminUserDetails(UUID id, String email, String passwordHash,
                            String role, UUID tenantId, boolean enabled) {
        this.id = Objects.requireNonNull(id);
        this.email = Objects.requireNonNull(email);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.role = Objects.requireNonNull(role);
        this.tenantId = tenantId;
        this.enabled = enabled;
    }

    public UUID getId()       { return id; }
    public String getRole()   { return role; }
    public UUID getTenantId() { return tenantId; }

    public boolean isPlatformOperator() { return "PLATFORM_OPERATOR".equals(role); }
    public boolean isRpAdmin()          { return "RP_ADMIN".equals(role); }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
    @Override public String getPassword()              { return passwordHash; }
    @Override public String getUsername()              { return email; }
    @Override public boolean isAccountNonExpired()     { return true; }
    @Override public boolean isAccountNonLocked()      { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return enabled; }
}
```

### 3.2 AdminUserDetailsService 변경

```java
@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminUserRepository repo;

    public AdminUserDetailsService(AdminUserRepository repo) {
        this.repo = repo;
    }

    @Override
    public AdminUserDetails loadUserByUsername(String email) {
        AdminUser u = repo.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("admin not found: " + email));
        return new AdminUserDetails(
                u.getId(),
                u.getEmail(),
                u.getBcryptHash(),
                u.getRole(),
                u.getTenantId(),
                "Y".equals(u.getEnabledFlag())
        );
    }
}
```

### 3.3 MeController — tenantId 노출

```java
@RestController
@RequestMapping("/admin/api")
public class MeController {

    @GetMapping("/me")
    public ApiResponse<MeView> me(Authentication auth) {
        AdminUserDetails principal = (AdminUserDetails) auth.getPrincipal();
        return ApiResponse.ok(new MeView(
                principal.getUsername(),
                principal.getRole(),
                principal.getTenantId()
        ));
    }

    public record MeView(String email, String role, UUID tenantId) {}
}
```

### 3.4 TenantBoundary

`admin-app/src/main/java/com/crosscert/passkey/admin/auth/TenantBoundary.java`:

```java
package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * RP_ADMIN 이 자기 tenant 외부에 접근하지 못하게 차단하는 단일 진입점.
 * 모든 admin service 의 첫 줄에서 호출.
 */
@Component
public class TenantBoundary {

    public void assertCanAccessTenant(UUID tenantId) {
        AdminUserDetails me = currentPrincipal();
        if (me.isPlatformOperator()) return;
        if (me.isRpAdmin()) {
            if (!me.getTenantId().equals(tenantId)) {
                throw new BusinessException(ErrorCode.ACCESS_DENIED,
                        "RP_ADMIN cannot access tenant " + tenantId);
            }
            return;
        }
        throw new BusinessException(ErrorCode.ACCESS_DENIED, "unknown role: " + me.getRole());
    }

    public Optional<UUID> currentTenantScope() {
        AdminUserDetails me = currentPrincipal();
        if (me.isPlatformOperator()) return Optional.empty();
        if (me.isRpAdmin())          return Optional.of(me.getTenantId());
        throw new BusinessException(ErrorCode.ACCESS_DENIED, "unknown role: " + me.getRole());
    }

    public void assertPlatformOperator() {
        AdminUserDetails me = currentPrincipal();
        if (!me.isPlatformOperator()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "platform-only operation");
        }
    }

    private AdminUserDetails currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AdminUserDetails p)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "not authenticated");
        }
        return p;
    }
}
```

## 4. 8 개 endpoint — `@PreAuthorize` + Service boundary 매트릭스

| Endpoint | `@PreAuthorize` | Service boundary | RP_ADMIN |
|---|---|---|---|
| `GET /tenants` | (없음, 인증만) | `currentTenantScope()` 분기 | 자기 1개만 |
| `GET /tenants/{id}` | (없음) | `assertCanAccessTenant(t.id)` | 자기만 |
| `POST /tenants` | `hasRole('PLATFORM_OPERATOR')` | — | 403 |
| `PUT /tenants/{id}` | `hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')` | `assertCanAccessTenant(t.id)` | 자기만 |
| `GET /tenants/{tid}/credentials` | (없음) | `assertCanAccessTenant(tid)` | 자기만 |
| `DELETE /tenants/{tid}/credentials/{cid}` | `hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')` | `assertCanAccessTenant(tid)` + entity 비교 | 자기만 |
| `GET /api-keys?tenantId=...` | (없음) | `assertCanAccessTenant(query.tenantId)` | 자기만 |
| `POST /api-keys` | `hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')` | `assertCanAccessTenant(req.tenantId)` | 자기만 |
| `DELETE /api-keys/{id}` | `hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')` | `assertCanAccessTenant(entity.tenantId)` | 자기만 |
| `GET /audit` | `hasRole('PLATFORM_OPERATOR')` | — | 403 |
| `GET /audit/verify` | `hasRole('PLATFORM_OPERATOR')` | — | 403 |
| `GET /keys`, `POST /keys/rotate` | `hasRole('PLATFORM_OPERATOR')` | — | 403 |
| `GET /mds/status`, `POST /mds/sync` | `hasRole('PLATFORM_OPERATOR')` | — | 403 |
| `GET /me`, `GET /profile` | (인증만 / 비인증) | — | 동일 |

**audit_log 차단 결정 근거**: 현재 `audit_log` 테이블에 `tenant_id` 컬럼이 없어 RP_ADMIN 의 자기 tenant 격리 불가. 이번 phase 는 platform-only 로 단순화. 후속 phase (audit_log.tenant_id 컬럼 + RP_ADMIN scope) 에서 도입.

### 4.1 Service boundary 적용 예시

#### TenantAdminService

```java
@Transactional(readOnly = true)
public List<TenantView> list() {
    return tenantBoundary.currentTenantScope()
            .map(tid -> tenants.findById(tid).map(TenantView::from).stream().toList())
            .orElseGet(() -> tenants.findAll().stream().map(TenantView::from).toList());
}

@Transactional(readOnly = true)
public TenantView get(String idOrSlug) {
    Tenant t = lookup(idOrSlug);
    tenantBoundary.assertCanAccessTenant(t.getId());
    return TenantView.from(t);
}

@Transactional
public TenantView update(String idOrSlug, TenantUpdateRequest req, UUID actorId, String actorEmail) {
    Tenant t = lookup(idOrSlug);
    tenantBoundary.assertCanAccessTenant(t.getId());
    // ... 기존 로직 ...
}
```

#### CredentialAdminService

```java
public PageView<CredentialView> list(UUID tenantId, int page, int size, String q) {
    tenantBoundary.assertCanAccessTenant(tenantId);
    // ... 기존 로직 ...
}

public void revoke(UUID tenantId, String credentialIdB64, UUID actorId, String actorEmail) {
    tenantBoundary.assertCanAccessTenant(tenantId);
    // ... 기존 로직: entity.tenantId.equals(tenantId) 검사 유지 ...
}
```

#### ApiKeyAdminService

```java
public List<ApiKeyView> list(UUID tenantId) {
    tenantBoundary.assertCanAccessTenant(tenantId);
    return repo.findAllByTenantId(tenantId).stream().map(ApiKeyView::from).toList();
}

public ApiKeyCreateResponse issue(ApiKeyCreateRequest req, UUID actorId, String actorEmail) {
    tenantBoundary.assertCanAccessTenant(req.tenantId());
    // ... 기존 로직 ...
}

public void revoke(UUID id, UUID actorId, String actorEmail) {
    ApiKey k = repo.findById(id).orElseThrow(...);
    tenantBoundary.assertCanAccessTenant(k.getTenantId());
    // ... 기존 로직 ...
}
```

## 5. admin-ui — 라우팅, 사이드바, Me 처리

### 5.1 `api/types.ts`

```typescript
export interface Me {
    email: string;
    role: 'PLATFORM_OPERATOR' | 'RP_ADMIN';
    tenantId: string | null;
}
```

### 5.2 `api/client.ts`

```typescript
export const getMe = () => api.get<Me>('/admin/api/me');
```

### 5.3 `pages/Login.tsx` — 로그인 후 role 기반 라우팅

```typescript
async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
        await fetch('/admin/api/me', { credentials: 'include' }).catch(() => null);
        const ok = await api.loginForm(email, password);
        if (!ok) {
            setError('이메일 또는 비밀번호가 올바르지 않습니다.');
            return;
        }
        const me = await getMe();
        if (me.role === 'RP_ADMIN' && me.tenantId) {
            nav(`/tenants/${me.tenantId}`);
        } else {
            nav('/tenants');
        }
    } finally {
        setBusy(false);
    }
}
```

### 5.4 MeContext

`admin-ui/src/me/MeContext.tsx`:

```typescript
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { getMe } from '../api/client';
import type { Me } from '../api/types';

interface MeContextValue {
    me: Me | null;
    loading: boolean;
    reload: () => Promise<void>;
}

const MeContext = createContext<MeContextValue>({ me: null, loading: true, reload: async () => {} });

export function MeProvider({ children }: { children: ReactNode }) {
    const [me, setMe] = useState<Me | null>(null);
    const [loading, setLoading] = useState(true);

    const reload = async () => {
        try {
            setMe(await getMe());
        } catch {
            setMe(null);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => { reload(); }, []);

    return <MeContext.Provider value={{ me, loading, reload }}>{children}</MeContext.Provider>;
}

export const useMe = () => useContext(MeContext);
```

`App.tsx`:
```tsx
<Route element={<MeProvider><Layout /></MeProvider>}>
    <Route path="/tenants" element={<TenantList />} />
    ...
</Route>
```

**401 / 비인증 상태**: `MeProvider.reload()` 가 401 받으면 `me=null` set. Sidebar/Header 가 me=null 일 때 placeholder (사이드바는 빈 nav 또는 nav 자체 안 렌더, Header 는 email/role 영역 비움). Login 페이지는 MeProvider 외부라 영향 없음. Layout 안에서 logout 후에는 ApiErrorBridge 의 401 redirect 가 `/login` 으로 보냄 (기존 패턴).

### 5.5 Sidebar — RP_ADMIN 단일 메뉴

```typescript
const PLATFORM_NAV = [
    { to: '/tenants', label: 'Tenants',      icon: Building },
    { to: '/keys',    label: 'Signing Keys', icon: Key },
    { to: '/mds',     label: 'MDS',          icon: Refresh },
    { to: '/audit',   label: 'Audit Log',    icon: Receipt },
];

const RP_ADMIN_NAV = (tenantId: string) => [
    { to: `/tenants/${tenantId}`, label: 'My Tenant', icon: Building },
];

export default function Sidebar() {
    const { me } = useMe();
    const nav = me?.role === 'RP_ADMIN' && me.tenantId
        ? RP_ADMIN_NAV(me.tenantId)
        : PLATFORM_NAV;
    // ...
}
```

### 5.6 Header — role + tenant 식별

```typescript
{me && (
    <span className="muted">
        {me.email} · <span className="badge">{me.role}</span>
        {me.tenantId && <span className="muted"> · {me.tenantId.slice(0, 8)}…</span>}
    </span>
)}
```

### 5.7 PlatformOnlyGuard

`admin-ui/src/components/PlatformOnlyGuard.tsx`:

```typescript
import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMe } from '../me/MeContext';

export default function PlatformOnlyGuard() {
    const { me } = useMe();
    const nav = useNavigate();
    useEffect(() => {
        if (me?.role === 'RP_ADMIN' && me.tenantId) {
            nav(`/tenants/${me.tenantId}`, { replace: true });
        }
    }, [me, nav]);
    return null;
}
```

`TenantCreate.tsx`, `AuditLog.tsx`, `KeyManagement.tsx`, `MdsStatus.tsx` 첫 줄에 `<PlatformOnlyGuard />`.

### 5.8 TenantList — RP_ADMIN 자기 detail 리다이렉트

```typescript
useEffect(() => {
    if (me?.role === 'RP_ADMIN' && me.tenantId) {
        nav(`/tenants/${me.tenantId}`, { replace: true });
    }
}, [me, nav]);
```

### 5.9 TenantDetail — 다른 tenant 차단

```typescript
useEffect(() => {
    if (!id) return;
    if (me?.role === 'RP_ADMIN' && me.tenantId && me.tenantId !== id) {
        nav(`/tenants/${me.tenantId}`, { replace: true });
        return;
    }
    // ... 기존 fetch ...
}, [id, me]);
```

### 5.10 파일 변경 요약

| 파일 | 변경 |
|---|---|
| `api/types.ts` | Me.role enum + tenantId 추가 |
| `api/client.ts` | getMe() helper |
| `me/MeContext.tsx` | 신규 (Provider + useMe) |
| `App.tsx` | MeProvider 감싸기 |
| `pages/Login.tsx` | 로그인 후 role 기반 nav |
| `components/Sidebar.tsx` | RP_ADMIN 메뉴 분기 |
| `components/Header.tsx` | role badge + tenant 식별 |
| `components/PlatformOnlyGuard.tsx` | 신규 |
| `pages/TenantList.tsx` | RP_ADMIN 리다이렉트 |
| `pages/TenantCreate.tsx` | PlatformOnlyGuard |
| `pages/TenantDetail.tsx` | RP_ADMIN 다른 tenant 차단 |
| `pages/AuditLog.tsx` | PlatformOnlyGuard |
| `pages/KeyManagement.tsx` | PlatformOnlyGuard |
| `pages/MdsStatus.tsx` | PlatformOnlyGuard |

## 6. 테스트 전략 (개발 속도 우선, 축소판)

### 6.1 자동 테스트 2 IT

| 테스트 | 위치 | 왜 필수 |
|---|---|---|
| `RpAdminBoundaryIT` | `admin-app/.../auth/` | **보안 회귀 채널** — RP_ADMIN 의 다른 tenant 접근 403. service boundary 누락 시 200 회귀 노출. |
| `PlatformOperatorUnrestrictedIT` | `admin-app/.../auth/` | PLATFORM_OPERATOR 의 무제한 동작 유지. role 변경이 기존 운영 능력을 안 깨뜨림 보장. |

### 6.2 RpAdminBoundaryIT 시나리오 (14 assertion)

bob (RP_ADMIN, demo-rp) 로그인:
1. `GET /admin/api/me` → role=RP_ADMIN, tenantId=demo-rp.id
2. `GET /tenants` → demo-rp 만 포함
3. `GET /tenants/{demo-rp.id}` → 200
4. `GET /tenants/{tenant_A.id}` → 403
5. `PUT /tenants/{demo-rp.id}` → 200
6. `PUT /tenants/{tenant_A.id}` → 403
7. `GET /tenants/{demo-rp.id}/credentials` → 200
8. `GET /tenants/{tenant_A.id}/credentials` → 403
9. `POST /tenants` → 403
10. `POST /api-keys {tenantId: demo-rp.id}` → 200
11. `POST /api-keys {tenantId: tenant_A.id}` → 403
12. `POST /keys/rotate` → 403
13. `POST /mds/sync` → 403
14. `GET /audit` → 403

### 6.3 PlatformOperatorUnrestrictedIT 시나리오 (10 assertion)

alice (PLATFORM_OPERATOR) 로그인:
1. `GET /me` → role=PLATFORM_OPERATOR, tenantId=null
2. tenant_A, tenant_B 생성 → 둘 다 200
3. `GET /tenants` → demo-rp + tenant_A + tenant_B 모두
4. `PUT /tenants/{tenant_A.id}` → 200
5. `PUT /tenants/{demo-rp.id}` → 200
6. `GET /tenants/{demo-rp.id}/credentials` → 200
7. `POST /api-keys {tenantId: demo-rp.id}` → 200
8. `POST /keys/rotate` → 200
9. `GET /mds/status` → 200
10. `GET /audit` → 200

### 6.4 기존 IT 갱신

| IT | 영향 | 갱신 |
|---|---|---|
| `AdminFlowIT` | bob 'VIEWER' → 'RP_ADMIN' 시드 변경 | bob 의 "VIEWER mutation 403" step 의도 변경 또는 제거. RpAdminBoundaryIT 가 대신 cover. |
| `TenantAdminControllerUpdateIT` | alice 로 로그인 + PUT → 정상 | V23 후 alice 가 PLATFORM_OPERATOR — assertion 변경 없음 |
| `CredentialAdminControllerSecurityIT` | alice 로 cross-tenant boundary | alice = PLATFORM_OPERATOR 라 path mismatch 만으로 403. 시나리오 유효 |

### 6.5 의도적으로 제외

- AdminUserDetails 단위 테스트
- TenantBoundary 단위 테스트 — IT 가 모든 분기 cover
- AdminUserDetailsService 단위 테스트 — Spring Security 표준
- admin-ui PlatformOnlyGuard / MeContext / Login 자동 라우팅
- V23 마이그레이션 자체 — IT 부팅 시 적용 + 검증

### 6.6 통과 기준

- `./gradlew :admin-app:test --tests RpAdminBoundaryIT --tests PlatformOperatorUnrestrictedIT --tests AdminFlowIT` → 모두 통과
- `./gradlew :admin-app:test --tests *IT` → 5 IT 통과
- admin-ui `npx tsc --noEmit` + `npm run build` 통과
- 수동 smoke 8 단계 (followup)

### 6.7 수동 smoke 체크리스트 (followup)

1. **alice 로그인** → /tenants 목록 + 모든 tenant + create 버튼. Signing Keys / MDS / Audit Log 메뉴 보임
2. **alice 가 bob 의 demo-rp tenant 클릭** → detail 4 탭 모두 동작
3. **alice → logout → bob 로그인** → 자동 /tenants/{demo-rp.id} 라우팅. 사이드바 'My Tenant' 단일
4. **bob 이 URL /tenants 직접 입력** → /tenants/{demo-rp.id} 강제
5. **bob 이 URL /tenants/{tenant_A.id} 입력** → 자기 tenant 로
6. **bob 이 URL /audit 입력** → 자기 tenant detail 로
7. **bob detail 에서 WebAuthn 설정 변경 + 저장** → 200 + audit row
8. **bob detail 에서 Credential 회수** → 200 + audit row

## 7. 위험 + 후속

### 7.1 위험과 대응

| 위험 | 영향 | 대응 |
|---|---|---|
| service boundary 누락 (8 endpoint 중 한 곳) | 중대 보안 사고 | `RpAdminBoundaryIT` 가 8 endpoint cover. 누락 시 200 응답 즉시 fail. 코드 리뷰 시 service 메서드 첫 줄 `tenantBoundary.*` grep. |
| V23 마이그레이션 실패 — CHECK 위반 | admin-app 부팅 불가 | UPDATE 가 CHECK 변경 전 수행 (step 4 → 5/6). idempotent. Testcontainers IT 가 검증. |
| demo tenant id 충돌 | bob FK 가 잘못된 tenant | 결정적 UUID + NOT EXISTS 가드. 충돌 사실상 0. |
| AdminUser tenant 삭제 시 FK 위반 | tenant DELETE 실패 | 의도된 동작. 후속 운영자 관리 UI 에서 재할당 워크플로우. |
| Login 후 me fetch race | UX 미세 거슬림 | Login.tsx 가 me 받은 후 nav. dogfood 수용. |
| RP_ADMIN 의 tenant 가 suspended | login 가능, operation 가능 (이번 phase) | 이번 phase 결정: suspended 도 RP_ADMIN 접근 허용. 정책은 후속 phase. |
| AdminUserDetails cast 실패 | runtime 500 | `TenantBoundary.currentPrincipal()` 이 instanceof check 후 BusinessException throw. |
| audit_log RP_ADMIN 차단 | 디자인 fidelity 떨어짐 | `audit_log.tenant_id` 컬럼 부재로 정확 isolation 불가. PLATFORM_OPERATOR 전용. 후속 phase. |
| AdminFlowIT bob assertion 변경 | 자동 테스트 의미 잃음 | plan task 에서 AdminFlowIT 코드 갱신 또는 step 제거. RpAdminBoundaryIT 가 대신 cover. |

### 7.2 후속 작업

`docs/superpowers/followups/2026-05-27-admin-role-separation-followups.md` 에 다음 9 개 항목 추적:

1. 운영자 관리 UI (추가/정지/초대 다이얼로그, MFA 옵션)
2. PENDING status + 초대 이메일 (SMTP)
3. audit_log.tenant_id 컬럼 + RP_ADMIN scope
4. suspended tenant 의 RP_ADMIN 정책
5. AdminUser.lastLoginAt RP_ADMIN 추가 후 재확인
6. role enum 의 Java enum 화 (현재 String)
7. 사이드바 UX 재디자인 (RP_ADMIN 전용 페이지 — Funnel / AAGUID / Overview)
8. AAGUID Attestation Policy (ANY/ALLOWLIST/DENYLIST)
9. Tenant suspend/activate workflow
