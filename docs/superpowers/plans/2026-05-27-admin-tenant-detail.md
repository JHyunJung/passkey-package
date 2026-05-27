# Admin Tenant Detail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Passkey Admin Console 디자인 중 "운영자의 가장 빈번한 흐름" — Tenant Detail 페이지(4 탭: Overview, WebAuthn Config, Credentials, API Keys) + credential 회수 + WebAuthn 설정 수정 — 을 admin-app + admin-ui 에 추가한다.

**Architecture:** worktree 안에서 진행. 백엔드는 기존 `TenantAdminController` 에 PUT 추가 + 신규 `CredentialAdminController` (nested route `/admin/api/tenants/{tenantId}/credentials`). 엔티티 변경 없이 기존 Credential / Tenant / AuditLog 사용. MdsAaguidCache 를 passkey-app → :core 로 이동해 admin-app 도 공유. admin-ui 는 `/tenants/:id` 라우트 + tabbed page + 3 신규 컴포넌트 (RevokeCredentialDialog, Pagination, Mono). 사이드바 "API Keys" 메뉴 제거 (tenant scope 내부로 이동). 자동 테스트 2 개만 — security boundary + audit happy path.

**Tech Stack:**
- 백엔드: Spring Boot 3.5, Spring Data JPA, Oracle 21 + Flyway (변경 없음), HexFormat / Base64URL, AuditLogService (기존)
- 프런트엔드: React 18 + Vite + TypeScript, react-router-dom 6, 기존 `api/client.ts` envelope unwrap, 기존 Dialog/Switch/OriginChipInput/FormatCheckboxGrid 재사용
- 테스트: Testcontainers Oracle XE + Redis 7 + webauthn4j-test ClientPlatform (기존 AdminFlowIT 패턴 재사용)

**Spec:** `docs/superpowers/specs/2026-05-27-admin-tenant-detail-design.md`

**Worktree note:** 모든 경로는 worktree 루트 `.claude/worktrees/admin-tenant-detail` 기준. 마지막 task 에서 `git merge --no-ff` 로 main 으로 도착.

---

## Task 1: MdsAaguidCache 를 :core 로 이동

admin-app 의 CredentialAdminService 가 aaguid → 인증기 이름을 룩업하려면 MdsAaguidCache 를 의존해야 한다. 현재는 passkey-app 패키지. `StringRedisTemplate` 만 의존하므로 :core 로 이동해도 결합도 변화 없음.

**Files:**
- Move: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsAaguidCache.java` → `core/src/main/java/com/crosscert/passkey/core/mds/MdsAaguidCache.java`
- Modify: passkey-app 측 import 갱신 (이동된 파일을 import 하는 모든 곳)

- [ ] **Step 1: 이동 위치 미리 생성 + 파일 옮김**

```bash
mkdir -p core/src/main/java/com/crosscert/passkey/core/mds
git mv passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsAaguidCache.java \
       core/src/main/java/com/crosscert/passkey/core/mds/MdsAaguidCache.java
```

- [ ] **Step 2: 이동된 파일의 package 선언 변경**

`core/src/main/java/com/crosscert/passkey/core/mds/MdsAaguidCache.java` 의 첫 줄:

```java
package com.crosscert.passkey.core.mds;
```

(기존 `package com.crosscert.passkey.app.fido2.mds;` 에서 변경)

- [ ] **Step 3: 이 클래스를 import 하던 passkey-app 코드 찾기**

```bash
grep -rn "com.crosscert.passkey.app.fido2.mds.MdsAaguidCache" passkey-app/src admin-app/src core/src
```

각 결과 파일의 import 문을 `com.crosscert.passkey.core.mds.MdsAaguidCache` 로 변경.

대표적으로 변경될 파일들:
- `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsSchedulerService.java`
- 기타 mds 패키지 내 다른 클래스

각 파일에서:

```java
// before
import com.crosscert.passkey.app.fido2.mds.MdsAaguidCache;
// after
import com.crosscert.passkey.core.mds.MdsAaguidCache;
```

같은 패키지 내 클래스(MdsAaguidCache.Entry 같이 inner type 참조)는 import 안 했을 수도 있으니, **passkey-app/fido2/mds 패키지 안의 동료 클래스**들은 명시 import 가 필요해진다:

```bash
grep -rn "MdsAaguidCache" passkey-app/src
```

모든 결과를 확인해 정확한 import 추가/변경.

- [ ] **Step 4: 양쪽 모듈 컴파일 확인**

```bash
./gradlew :core:compileJava :passkey-app:compileJava :admin-app:compileJava
```

Expected: BUILD SUCCESSFUL. admin-app 은 아직 MdsAaguidCache 를 import 안 하지만 (T4 에서 함) 컴파일에는 영향 없음.

- [ ] **Step 5: 회귀 확인 — passkey-app 테스트**

```bash
./gradlew :passkey-app:test --tests Fido2EndToEndIT
```

Expected: 8 시나리오 모두 PASS. 이 IT 가 MdsAaguidCache 의 lookup/put 을 실제로 사용 — 이동 후 동작 유지 검증.

만약 실패 시: import 누락 가능성 — Step 3 의 grep 을 다시 돌려 빠진 파일 찾기.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/mds/MdsAaguidCache.java
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/
# 만약 다른 모듈도 변경됐다면 그 디렉토리도 add
git status   # 변경 파일 최종 확인
git commit -m "refactor(core): MdsAaguidCache 를 passkey-app 에서 :core 로 이동 (T1)

admin-app 의 CredentialAdminService 가 aaguid → 인증기 이름 룩업에 사용
하려면 :core 로 위치해야 한다. StringRedisTemplate 만 의존하므로 결합도
변화 없음. Fido2EndToEndIT 회귀 확인."
```

---

## Task 2: core/api/PageView<T> 추가

admin 측 페이지네이션 응답을 위한 공통 record. Spring Data 의 `Page<T>` 를 그대로 직렬화하면 PageImpl 직렬화 경고 + 형태가 일관되지 않음 — `PageView.from(page)` 로 변환.

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/api/PageView.java`

- [ ] **Step 1: PageView.java 작성**

```java
package com.crosscert.passkey.core.api;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageView<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        boolean hasNext
) {
    public static <T> PageView<T> from(Page<T> p) {
        return new PageView<>(
                p.getContent(),
                p.getNumber(),
                p.getSize(),
                p.getTotalElements(),
                p.hasNext());
    }
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/api/PageView.java
git commit -m "feat(core): PageView<T> 공통 페이지네이션 응답 record (T2)

Spring Data 의 PageImpl 직렬화 경고를 피하고 응답 메타 형태를 명시적으로
제어. ApiResponse<PageView<T>> envelope 안에서 사용."
```

---

## Task 3: CredentialRepository 에 검색·조회 메서드 추가

CredentialAdminService 가 호출할 두 메서드. native query 의 `ESCAPE '\\'` 절이 `\%` `\_` `\\` 를 인식.

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java`

- [ ] **Step 1: CredentialRepository 의 기존 코드 확인**

```bash
cat core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java
```

기존 메서드: `findByCredentialIdForUpdate(byte[])` PESSIMISTIC_WRITE 만 있음.

- [ ] **Step 2: 두 메서드 추가**

`core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java` 의 interface 본문 마지막 (마지막 `}` 바로 위) 에 추가:

```java
    /**
     * tenant 의 모든 credential — 정렬은 호출자 Pageable 이 결정.
     * admin-app 의 CredentialAdminService.list() 가 사용.
     */
    org.springframework.data.domain.Page<Credential>
        findAllByTenantId(java.util.UUID tenantId, org.springframework.data.domain.Pageable p);

    /**
     * tenant 안에서 credential_id 또는 user_handle 의 hex 표현이 q 를 substring 으로 포함하는 행.
     * q 는 서비스 측 normalizeQ 가 이미 lowercase + wildcard escape 한 상태로 들어와야 한다.
     * ESCAPE '\\' 절이 백슬래시 escape 를 인식.
     */
    @org.springframework.data.jpa.repository.Query(value = """
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
    org.springframework.data.domain.Page<Credential>
        searchByTenantId(@org.springframework.data.repository.query.Param("tid") java.util.UUID tid,
                         @org.springframework.data.repository.query.Param("q") String hexQ,
                         org.springframework.data.domain.Pageable p);
```

inline FQCN 으로 작성 — 기존 파일이 어떤 import 를 갖는지 모를 때 충돌 없음. 컴파일 후 IDE 가 import 정리해주거나, 다음 step 에서 수동 정리.

- [ ] **Step 3: import 정리 (선택)**

기존 import 에 다음이 모두 있는지 확인 + 없으면 추가:

```java
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;
```

이미 일부 있을 수 있음 (예: java.util.UUID). 위 코드의 FQCN 을 줄이려면 import 추가 후 메서드 시그니처에서 패키지 prefix 제거. 단순성을 위해 inline FQCN 유지해도 무방.

- [ ] **Step 4: 컴파일 확인**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java
git commit -m "feat(core): CredentialRepository — findAllByTenantId + searchByTenantId (T3)

admin-app 의 CredentialAdminService 가 사용. native LIKE 는
ESCAPE '\\\\' 절로 \\% \\_ \\\\ wildcard escape 를 인식.
서비스 측 normalizeQ 가 q 를 이미 lowercase + escape 처리."
```

---

## Task 4: CredentialAdminDto + CredentialAdminService + CredentialAdminController

이 phase 의 핵심 백엔드 — credential 조회 + 회수. cross-tenant boundary 검사를 항상 path tenantId vs entity tenantId 로 강제.

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminDto.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminService.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminController.java`

- [ ] **Step 1: CredentialAdminDto 작성**

`admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminDto.java`:

```java
package com.crosscert.passkey.admin.credential;

import java.time.Instant;

public final class CredentialAdminDto {

    private CredentialAdminDto() {}

    public record CredentialView(
            String  credentialId,        // base64url, no padding
            String  userHandle,          // base64url, no padding
            String  aaguidHex,           // 32-char hex (HexFormat.formatHex). null 가능
            String  authenticatorName,   // MdsAaguidCache 룩업 결과. 없으면 null
            String  attestationFormat,
            String  transports,
            long    signCount,
            Instant lastUsedAt,          // null 가능
            Instant createdAt
    ) {}
}
```

- [ ] **Step 2: CredentialAdminService 작성**

`admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminService.java`:

```java
package com.crosscert.passkey.admin.credential;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.credential.CredentialAdminDto.CredentialView;
import com.crosscert.passkey.core.api.PageView;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.mds.MdsAaguidCache;
import com.crosscert.passkey.core.repository.CredentialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class CredentialAdminService {

    private static final Logger log = LoggerFactory.getLogger(CredentialAdminService.class);

    private final CredentialRepository creds;
    private final MdsAaguidCache mds;
    private final AuditLogService audit;

    public CredentialAdminService(CredentialRepository creds,
                                  MdsAaguidCache mds,
                                  AuditLogService audit) {
        this.creds = creds;
        this.mds = mds;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageView<CredentialView> list(UUID tenantId, int page, int size, String q) {
        int cappedSize = Math.min(Math.max(size, 1), 200);
        Pageable pageReq = PageRequest.of(Math.max(page, 0), cappedSize,
                Sort.by(Sort.Order.desc("lastUsedAt").nullsLast())
                    .and(Sort.by(Sort.Order.desc("id"))));

        Page<Credential> rows = (q == null || q.isBlank())
                ? creds.findAllByTenantId(tenantId, pageReq)
                : creds.searchByTenantId(tenantId, normalizeQ(q), pageReq);

        log.debug("list credentials tenant={} page={} size={} q={} totalElements={}",
                tenantId, page, cappedSize, q, rows.getTotalElements());
        return PageView.from(rows.map(this::toView));
    }

    @Transactional
    public void revoke(UUID tenantId, String credentialIdB64,
                       UUID actorId, String actorEmail) {
        byte[] credId;
        try {
            credId = Base64.getUrlDecoder().decode(credentialIdB64);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "credentialId 가 base64url 형식이 아님");
        }

        Credential c = creds.findByCredentialIdForUpdate(credId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENTITY_NOT_FOUND,
                        "credential 없음"));

        // VPD 가 admin-app 에서 비활성 — tenantId 일치 검사가 cross-tenant 누출 방어의 단일 layer
        if (!c.getTenantId().equals(tenantId)) {
            log.warn("cross-tenant revoke attempt: pathTenant={} actualTenant={} credentialId={} actor={}",
                    tenantId, c.getTenantId(), credentialIdB64, actorEmail);
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "tenant boundary 위반");
        }

        byte[] aaguid = c.getAaguid();
        byte[] userHandle = c.getUserHandle();
        creds.delete(c);

        Map<String, Object> payload = new HashMap<>();
        payload.put("tenantId", tenantId.toString());
        payload.put("aaguidHex", aaguid == null ? null : HexFormat.of().formatHex(aaguid));
        payload.put("userHandleB64url",
                Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle));

        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "CREDENTIAL_REVOKE",
                "CREDENTIAL", credentialIdB64, payload));

        log.info("credential revoked tenant={} credentialId={} actor={}",
                tenantId, credentialIdB64, actorEmail);
    }

    private CredentialView toView(Credential c) {
        byte[] aaguid = c.getAaguid();
        String aaguidHex = aaguid == null ? null : HexFormat.of().formatHex(aaguid);
        String authName = aaguid == null
                ? null
                : mds.lookup(aaguid)
                     .map(entry -> entry.statuses().isEmpty() ? null : String.join(",", entry.statuses()))
                     .orElse(null);
        return new CredentialView(
                Base64.getUrlEncoder().withoutPadding().encodeToString(c.getCredentialId()),
                Base64.getUrlEncoder().withoutPadding().encodeToString(c.getUserHandle()),
                aaguidHex, authName,
                c.getAttestationFmt(), c.getTransports(),
                c.getSignCount(), c.getLastUsedAt(), c.getCreatedAt());
    }

    /**
     * base64url 시도 → 성공 시 hex 변환. 실패 시 q 그대로 (운영자가 hex 일부 직접 입력 가능).
     * LIKE wildcard 는 \\ % _ → \\\\ \\% \\_ 로 escape (CredentialRepository 의 ESCAPE '\\\\' 절과 짝).
     */
    private String normalizeQ(String q) {
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

**MdsAaguidCache.Entry**: T1 에서 확인된 record 는 `List<String> statuses` 만 가짐 — description 필드 없음. 인증기 이름 자체는 별도 MDS 메타 데이터에서 와야 하지만 현재는 statuses (`"FIDO_CERTIFIED"` 등) 가 노출됨. spec 의 `authenticatorName` 은 의미상 "MDS 가 알고 있는 인증기 식별 정보" 로 해석 — statuses 를 콤마 합쳐서 반환. 후속 phase 에서 MDS BLOB 의 description 필드 캐싱 도입 시 교체.

**BusinessException / ErrorCode 위치**: spec 의 `:core/api` 가정 — 실제 패키지는 worktree 의 `core/src/main/java/com/crosscert/passkey/core/api/` 확인. 만약 다른 위치이면 import 경로만 조정.

- [ ] **Step 3: BusinessException / ErrorCode 의 실제 위치 확인**

```bash
find core/src/main/java -name "BusinessException.java" -o -name "ErrorCode.java"
```

위치에 맞춰 위 service 의 import 두 줄을 조정. 만약 admin-app 안에 있다면 (다른 phase 가 옮겼을 수 있음) 그 경로로.

- [ ] **Step 4: CredentialAdminController 작성**

`admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminController.java`:

```java
package com.crosscert.passkey.admin.credential;

import com.crosscert.passkey.admin.adminuser.AdminUserRepository;
import com.crosscert.passkey.admin.credential.CredentialAdminDto.CredentialView;
import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.api.PageView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/api/tenants/{tenantId}/credentials")
public class CredentialAdminController {

    private final CredentialAdminService service;
    private final AdminUserRepository admins;

    public CredentialAdminController(CredentialAdminService service, AdminUserRepository admins) {
        this.service = service;
        this.admins = admins;
    }

    @GetMapping
    public ApiResponse<PageView<CredentialView>> list(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(service.list(tenantId, page, size, q));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{credentialId}")
    public ResponseEntity<ApiResponse<Void>> revoke(
            @PathVariable UUID tenantId,
            @PathVariable String credentialId,
            Authentication auth) {
        UUID actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        service.revoke(tenantId, credentialId, actorId, auth.getName());
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
```

**AdminUserRepository 경로**: T1 의 explore 결과 — `admin-app/.../admin/adminuser/AdminUserRepository.java` 또는 다른 패키지일 수 있음. 실제 경로 확인:

```bash
find admin-app/src/main/java -name "AdminUserRepository.java"
```

import 경로를 거기에 맞춤.

- [ ] **Step 5: 컴파일 확인**

```bash
./gradlew :admin-app:compileJava
```

Expected: BUILD SUCCESSFUL.

만약 import 오류:
- `BusinessException` 또는 `ErrorCode` 못 찾음 → Step 3 의 grep 결과로 경로 수정
- `AdminUserRepository` 못 찾음 → Step 4 의 grep 결과로 경로 수정
- `ApiResponse.ok()` 메서드가 `ApiResponse<Void>` 반환이 아니면 → `ApiResponse.<Void>ok(null)` 또는 적절한 형태로 조정

- [ ] **Step 6: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/credential/
git commit -m "feat(admin): CredentialAdminController + Service + Dto (T4)

GET    /admin/api/tenants/{tenantId}/credentials?page&size&q  (Authenticated)
DELETE /admin/api/tenants/{tenantId}/credentials/{credentialId}  (ADMIN)

핵심 보안: admin-app 은 VPD 비활성. revoke 시 entity.tenantId 와
path tenantId 비교가 cross-tenant 누출 방어의 단일 layer.
findByCredentialIdForUpdate PESSIMISTIC_WRITE 락으로 passkey-app
ceremony 와 race 차단. hard delete (status='REVOKED' 는 후속 phase)."
```

---

## Task 5: TenantAdminController PUT + TenantAdminService.update + TenantSnapshot

WebAuthn 설정 수정 — `displayName`, `rpName`, `allowedOrigins`, `acceptedFormats`, `requireUserVerification`, `mdsRequired`. `rpId` / `slug` 는 request 에서 받되 silent ignore.

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantSnapshot.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java`

- [ ] **Step 1: TenantSnapshot 작성**

`admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantSnapshot.java`:

```java
package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.entity.Tenant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tenant 의 변경 추적용 스냅샷. update() before/after 비교에 쓰인다.
 * audit log payload 의 before/after 키로 직렬화 가능 (record + 기본 타입).
 */
public record TenantSnapshot(
        String displayName,
        String rpName,
        List<String> allowedOrigins,
        Set<String> acceptedFormats,
        boolean requireUserVerification,
        boolean mdsRequired
) {

    public static TenantSnapshot of(Tenant t) {
        return new TenantSnapshot(
                t.getDisplayName(),
                t.getRpName(),
                new ArrayList<>(t.getAllowedOriginValues()),
                new TreeSet<>(t.getAcceptedFormatValues()),
                t.isRequireUserVerification(),
                t.isMdsRequired());
    }

    /**
     * before vs after 비교해 변경된 필드 이름 리스트 반환.
     * audit payload 의 changedFields 키로 직렬화.
     */
    public List<String> diff(TenantSnapshot other) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(this.displayName, other.displayName))         changed.add("displayName");
        if (!Objects.equals(this.rpName, other.rpName))                   changed.add("rpName");
        if (!Objects.equals(this.allowedOrigins, other.allowedOrigins))   changed.add("allowedOrigins");
        if (!Objects.equals(this.acceptedFormats, other.acceptedFormats)) changed.add("acceptedFormats");
        if (this.requireUserVerification != other.requireUserVerification) changed.add("requireUserVerification");
        if (this.mdsRequired != other.mdsRequired)                         changed.add("mdsRequired");
        return changed;
    }
}
```

**Tenant 메서드 확인**: `getAllowedOriginValues()`, `getAcceptedFormatValues()`, `isRequireUserVerification()`, `isMdsRequired()` 가 존재하는지:

```bash
grep -n "getAllowedOriginValues\|getAcceptedFormatValues\|isRequireUserVerification\|isMdsRequired" core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java
```

T1 의 explore 결과로 이미 존재 확인됨. 만약 메서드 이름이 다르면 (`getAllowedOrigins()` 등) snapshot 도 그 이름에 맞춤.

- [ ] **Step 2: TenantAdminService 의 update 메서드 추가**

`admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java` 의 기존 `create()` 메서드 아래에 추가:

```java
    @Transactional
    public TenantAdminDto.TenantView update(String idOrSlug,
                                            TenantAdminDto.TenantUpdateRequest req,
                                            UUID actorId,
                                            String actorEmail) {
        Tenant t = lookup(idOrSlug);                       // 기존 get() 의 내부 헬퍼 재사용

        TenantSnapshot before = TenantSnapshot.of(t);

        // rpId / slug 는 silent ignore (별도 워크플로우 필요 — spec § 6.1)
        if (req.rpId() != null && !req.rpId().equals(t.getRpId())) {
            log.debug("rpId update ignored — not yet implemented (tenant={} from={} to={})",
                    t.getId(), t.getRpId(), req.rpId());
        }

        t.setDisplayName(req.displayName());
        t.setRpName(req.rpName());
        replaceAllowedOrigins(t, req.allowedOrigins());
        replaceAcceptedFormats(t, req.acceptedFormats());
        t.setRequireUserVerification(req.requireUserVerification());
        t.setMdsRequired(req.mdsRequired());

        tenants.saveAndFlush(t);

        TenantSnapshot after = TenantSnapshot.of(t);
        java.util.List<String> changed = before.diff(after);

        if (!changed.isEmpty()) {
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("before", before);
            payload.put("after", after);
            payload.put("changedFields", changed);
            audit.append(new AuditAppendRequest(
                    actorId, actorEmail, "TENANT_UPDATE",
                    "TENANT", t.getId().toString(), payload));
            log.info("tenant updated id={} slug={} changed={} actor={}",
                    t.getId(), t.getSlug(), changed, actorEmail);
        } else {
            log.debug("tenant update no-op id={} slug={} actor={}",
                    t.getId(), t.getSlug(), actorEmail);
        }

        return TenantAdminDto.TenantView.from(t);
    }

    private void replaceAllowedOrigins(Tenant t, java.util.List<String> origins) {
        // 기존 child 모두 제거 후 재삽입 — JPA cascade orphan-removal 가정
        // Tenant 엔티티의 helper 메서드 활용
        t.clearAllowedOrigins();   // 신규 helper (Step 3 에서 추가)
        int order = 0;
        for (String origin : origins) {
            t.addAllowedOrigin(origin, order++);
        }
    }

    private void replaceAcceptedFormats(Tenant t, java.util.Set<String> formats) {
        t.clearAcceptedFormats();  // 신규 helper
        for (String format : formats) {
            t.addAcceptedFormat(format);
        }
    }

    /**
     * idOrSlug 의 UUID 파싱 시도 후 실패하면 slug 로 조회.
     * 기존 get() 메서드의 내부 로직과 동일 — 메서드로 추출.
     */
    private Tenant lookup(String idOrSlug) {
        try {
            UUID id = UUID.fromString(idOrSlug);
            return tenants.findById(id)
                    .orElseThrow(() -> new com.crosscert.passkey.core.api.BusinessException(
                            com.crosscert.passkey.core.api.ErrorCode.ENTITY_NOT_FOUND,
                            "tenant 없음 id=" + idOrSlug));
        } catch (IllegalArgumentException e) {
            return tenants.findBySlug(idOrSlug)
                    .orElseThrow(() -> new com.crosscert.passkey.core.api.BusinessException(
                            com.crosscert.passkey.core.api.ErrorCode.ENTITY_NOT_FOUND,
                            "tenant 없음 slug=" + idOrSlug));
        }
    }
```

**상단 추가 필요한 import:**

```java
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

**클래스 필드 추가 (constructor 위에):**

```java
private static final Logger log = LoggerFactory.getLogger(TenantAdminService.class);
```

**중요**: 기존 `get(idOrSlug)` 메서드의 내부에 동일한 UUID parse → slug 폴백 로직이 있다면 그것을 `lookup()` 로 호출하도록 리팩토링. 기존 `get()` 도 `return TenantView.from(lookup(idOrSlug))` 로 단순화. 변경 범위가 부담스러우면 `lookup()` 을 신규로 두고 `get()` 은 유지 (DRY 위반이지만 안전).

- [ ] **Step 3: Tenant 엔티티에 clear* helper 추가**

`core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java` 의 `addAllowedOrigin(...)` 메서드 근처에 추가:

```java
    public void clearAllowedOrigins() {
        this.allowedOrigins.clear();
    }

    public void clearAcceptedFormats() {
        this.acceptedFormats.clear();
    }
```

기존 `addAllowedOrigin` / `addAcceptedFormat` 이 child 컬렉션에 add 하는 패턴이라면 같은 컬렉션 필드를 clear 만 하면 됨. JPA cascade 가 orphan-removal 로 설정돼 있어야 child 가 실제 DELETE 까지 이어짐:

```bash
grep -A 2 "@OneToMany" core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java
```

`orphanRemoval = true` 가 있는지 확인. 없으면 추가:

```java
@OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, orphanRemoval = true)
```

orphanRemoval 추가는 V21 마이그레이션 (`tenant_config_normalize`) 후 이미 적용돼 있을 가능성 큼 — explore 결과 확인.

- [ ] **Step 4: TenantAdminController 에 PUT 핸들러 추가**

`admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java` 의 기존 `POST` 핸들러 아래에 추가:

```java
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{idOrSlug}")
    public ApiResponse<TenantAdminDto.TenantView> update(
            @PathVariable String idOrSlug,
            @Valid @RequestBody TenantAdminDto.TenantUpdateRequest req,
            Authentication auth) {
        UUID actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        return ApiResponse.ok("Tenant updated",
                service.update(idOrSlug, req, actorId, auth.getName()));
    }
```

**상단 import 추가:**

```java
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import jakarta.validation.Valid;
```

(이미 일부 있을 가능성 큼.)

- [ ] **Step 5: 컴파일 확인**

```bash
./gradlew :admin-app:compileJava
```

Expected: BUILD SUCCESSFUL.

흔한 오류:
- `Tenant.clearAllowedOrigins` 메서드 시그니처 불일치 — Step 3 의 추가가 누락됐을 가능성. 다시 추가.
- `lookup()` 가 기존 `get()` 의 내부 코드와 충돌 — 기존 `get()` 의 내부를 그대로 두고 신규 `lookup()` 을 별도 메서드로 추가 (DRY 위반 허용).

- [ ] **Step 6: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantSnapshot.java
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java
git add core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java
git commit -m "feat(admin): PUT /admin/api/tenants/{id} — WebAuthn 설정 수정 (T5)

TenantSnapshot 으로 before/after diff 계산 후 audit log payload 에
changedFields 와 함께 기록. 변경 없는 호출 (no-op) 은 audit 생략.

수정 가능 필드: displayName, rpName, allowedOrigins, acceptedFormats,
requireUserVerification, mdsRequired.
silent ignore: rpId, slug (별도 워크플로우 — credential / VPD 영향 큼).

child 엔티티 (allowed_origin, accepted_format) 은 clear + 재삽입.
orphanRemoval 이 child DELETE 까지 이어줌."
```

---

## Task 6: TenantAdminControllerUpdateIT — PUT happy path + audit (자동 1)

자동 테스트의 첫 IT. AdminFlowIT 패턴 (Testcontainers Oracle + Redis + admin 로그인 helper) 재사용.

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminControllerUpdateIT.java`

- [ ] **Step 1: AdminFlowIT 의 admin 로그인 helper 위치 확인**

```bash
grep -n "private.*loginAsAdmin\|loginAsAdmin(\|protected.*loginAsAdmin" \
  admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java
```

helper 메서드 이름과 시그니처 확인. 보통 `HttpHeaders loginAsAdmin(String email, String password)` 또는 비슷.

- [ ] **Step 2: 테스트 클래스 작성**

`admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminControllerUpdateIT.java`:

```java
package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.admin.AdminFlowIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PUT /admin/api/tenants/{id} happy path + audit log row 생성 검증.
 * AdminFlowIT 의 Testcontainers 셋업과 admin 로그인 helper 재사용.
 *
 * 시나리오:
 *   1. tenant 생성 (POST)
 *   2. PUT 으로 displayName + allowedOrigins 변경
 *   3. GET 으로 변경 확인
 *   4. GET /audit?action=TENANT_UPDATE 로 audit row 1 개 + changedFields 검증
 *   5. PUT 동일 body 재호출 → audit row 추가 안 됨 (no-op)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TenantAdminControllerUpdateIT extends AdminFlowIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper om;

    private final RestTemplate http = new RestTemplate();

    @Test
    void putUpdatesFields_andAppendsAuditRow_withChangedFields() throws Exception {
        HttpHeaders auth = loginAsAdmin("alice@crosscert.com", "alice-temp-pw");

        // 1. tenant 생성
        Map<String, Object> create = Map.of(
                "slug", "update-it",
                "displayName", "Original Name",
                "rpId", "localhost",
                "rpName", "Original RP",
                "allowedOrigins", List.of("http://localhost:9090"),
                "acceptedFormats", List.of("none", "packed"),
                "requireUserVerification", true,
                "mdsRequired", false);
        ResponseEntity<JsonNode> createRes = http.exchange(
                "http://localhost:" + port + "/admin/api/tenants",
                HttpMethod.POST,
                new HttpEntity<>(om.writeValueAsString(create), auth),
                JsonNode.class);
        assertThat(createRes.getStatusCode().is2xxSuccessful()).isTrue();
        String tenantId = createRes.getBody().get("data").get("id").asText();

        // 2. PUT — displayName + allowedOrigins 변경
        Map<String, Object> update = Map.of(
                "displayName", "Updated Name",
                "rpName", "Original RP",
                "allowedOrigins", List.of("http://localhost:9090", "http://localhost:9091"),
                "acceptedFormats", List.of("none", "packed"),
                "requireUserVerification", true,
                "mdsRequired", false);
        ResponseEntity<JsonNode> putRes = http.exchange(
                "http://localhost:" + port + "/admin/api/tenants/" + tenantId,
                HttpMethod.PUT,
                new HttpEntity<>(om.writeValueAsString(update), auth),
                JsonNode.class);
        assertThat(putRes.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(putRes.getBody().get("data").get("displayName").asText()).isEqualTo("Updated Name");

        // 3. GET 확인
        ResponseEntity<JsonNode> getRes = http.exchange(
                "http://localhost:" + port + "/admin/api/tenants/" + tenantId,
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JsonNode.class);
        assertThat(getRes.getBody().get("data").get("displayName").asText()).isEqualTo("Updated Name");
        assertThat(getRes.getBody().get("data").get("allowedOrigins"))
                .anyMatch(n -> n.asText().equals("http://localhost:9091"));

        // 4. audit row 검증
        ResponseEntity<JsonNode> auditRes = http.exchange(
                "http://localhost:" + port + "/admin/api/audit?action=TENANT_UPDATE",
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JsonNode.class);
        JsonNode rows = auditRes.getBody().get("data");
        assertThat(rows.size()).isGreaterThanOrEqualTo(1);
        JsonNode firstRow = rows.get(0);
        assertThat(firstRow.get("action").asText()).isEqualTo("TENANT_UPDATE");
        assertThat(firstRow.get("targetId").asText()).isEqualTo(tenantId);
        JsonNode payload = firstRow.get("payload");
        assertThat(payload.get("changedFields"))
                .anyMatch(n -> n.asText().equals("displayName"))
                .anyMatch(n -> n.asText().equals("allowedOrigins"));

        // 5. PUT 동일 body 재호출 → audit row 추가 안 됨 (no-op)
        int auditCountBefore = rows.size();
        ResponseEntity<JsonNode> putAgain = http.exchange(
                "http://localhost:" + port + "/admin/api/tenants/" + tenantId,
                HttpMethod.PUT,
                new HttpEntity<>(om.writeValueAsString(update), auth),
                JsonNode.class);
        assertThat(putAgain.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<JsonNode> auditAgain = http.exchange(
                "http://localhost:" + port + "/admin/api/audit?action=TENANT_UPDATE",
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JsonNode.class);
        assertThat(auditAgain.getBody().get("data").size()).isEqualTo(auditCountBefore);
    }
}
```

**중요**:
- `extends AdminFlowIT` 가 가능한지 확인 — AdminFlowIT 가 `public` class 인지 + `@SpringBootTest` 가 상속되는지. 만약 final/private 이면 helper 메서드를 별도 utility class 로 추출하거나 코드 복사.
- `loginAsAdmin` 의 정확한 시그니처에 맞춤. 만약 없으면 AdminFlowIT 코드를 보고 동일 로직을 IT 안에 inline.
- `payload` 가 audit 응답에 JSON 으로 직렬화되어 있는지 — AuditLogService 가 canonical JSON 으로 저장, controller 가 JsonNode 로 노출하는지 확인.

- [ ] **Step 3: 테스트 실행**

```bash
./gradlew :admin-app:test --tests TenantAdminControllerUpdateIT
```

Expected: BUILD SUCCESSFUL, 1 test passed.

흔한 실패:
- AdminFlowIT extends 불가 → helper 코드 inline 으로 복사
- `payload` 가 String 으로 직렬화돼 있음 (controller 가 그대로 String 반환) → `om.readTree(payload.asText())` 로 파싱
- audit row 순서가 최신순이 아님 → `getRes.getBody().get("data")` 안에서 `targetId` 가 tenantId 인 row 를 find

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminControllerUpdateIT.java
git commit -m "test(admin): TenantAdminControllerUpdateIT — PUT happy path + audit (T6)

자동 IT 1 — AdminFlowIT 패턴 재사용. tenant 생성 → PUT 으로
displayName + allowedOrigins 변경 → GET 확인 → audit row 의
changedFields 검증 → 동일 body 재 PUT → audit 추가 안 됨 (no-op)."
```

---

## Task 7: CredentialAdminControllerSecurityIT — cross-tenant boundary (자동 2)

**보안 핵심 회귀 채널**. tenant A 의 admin 이 tenant B 의 credential 을 회수 못 함 검증.

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/credential/CredentialAdminControllerSecurityIT.java`

- [ ] **Step 1: AdminFlowIT 의 credential 등록 helper 위치 확인**

```bash
grep -n "registerCredential\|ClientPlatform\|webauthn4j" \
  admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java
```

webauthn4j-test 의 ClientPlatform 으로 ceremony 시뮬레이션하는 helper 가 있는지 확인.

- [ ] **Step 2: 테스트 클래스 작성**

`admin-app/src/test/java/com/crosscert/passkey/admin/credential/CredentialAdminControllerSecurityIT.java`:

```java
package com.crosscert.passkey.admin.credential;

import com.crosscert.passkey.admin.AdminFlowIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * cross-tenant boundary 회귀 채널. admin-app 은 VPD 비활성이므로
 * tenant_A 의 credential 을 tenant_B 의 path 로 회수하면 403 ACCESS_DENIED
 * 가 떨어져야 한다. 또한 GET 결과에 다른 tenant 의 credential 이 보여서는 안 됨.
 *
 * 시나리오:
 *   1. tenant_A, tenant_B 두 개 생성
 *   2. tenant_A 의 API key 발급 후 passkey-app ceremony 로 credential C_A 등록
 *   3. GET /admin/api/tenants/{tenant_A}/credentials → C_A 포함
 *   4. GET /admin/api/tenants/{tenant_B}/credentials → C_A 미포함
 *   5. DELETE /admin/api/tenants/{tenant_B}/credentials/{C_A.id} → 4xx
 *   6. DB 의 credential row 여전히 존재 (회수 안 됨)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CredentialAdminControllerSecurityIT extends AdminFlowIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper om;

    private final RestTemplate http = new RestTemplate();

    @Test
    void crossTenantRevoke_isRejected_andCredentialIsListedOnlyInOwnTenant() throws Exception {
        HttpHeaders auth = loginAsAdmin("alice@crosscert.com", "alice-temp-pw");

        // 1. tenant_A, tenant_B 생성
        String tenantAId = createTenant(auth, "sec-it-a", "Tenant A", "localhost", "RP A");
        String tenantBId = createTenant(auth, "sec-it-b", "Tenant B", "localhost", "RP B");

        // 2. tenant_A 에 credential 등록 (AdminFlowIT 의 helper 재사용)
        //    helper 이름은 explore 단계에서 확인 — 예: registerCredentialFor(tenantId, auth)
        String credentialIdB64 = registerCredentialFor(tenantAId, auth);

        // 3. GET tenant_A → 포함
        ResponseEntity<JsonNode> listA = http.exchange(
                "http://localhost:" + port + "/admin/api/tenants/" + tenantAId + "/credentials",
                HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(listA.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(listA.getBody().get("data").get("content"))
                .anyMatch(n -> n.get("credentialId").asText().equals(credentialIdB64));

        // 4. GET tenant_B → 미포함
        ResponseEntity<JsonNode> listB = http.exchange(
                "http://localhost:" + port + "/admin/api/tenants/" + tenantBId + "/credentials",
                HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(listB.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(listB.getBody().get("data").get("content"))
                .noneMatch(n -> n.get("credentialId").asText().equals(credentialIdB64));

        // 5. DELETE 를 tenant_B path 로 시도 → 403 또는 4xx + ACCESS_DENIED
        assertThatThrownBy(() -> http.exchange(
                "http://localhost:" + port + "/admin/api/tenants/" + tenantBId +
                        "/credentials/" + credentialIdB64,
                HttpMethod.DELETE, new HttpEntity<>(auth), JsonNode.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    HttpClientErrorException ex = (HttpClientErrorException) e;
                    assertThat(ex.getStatusCode().is4xxClientError()).isTrue();
                    JsonNode body = om.readTree(ex.getResponseBodyAsString());
                    assertThat(body.get("success").asBoolean()).isFalse();
                    assertThat(body.get("code").asText()).isEqualTo("A002");  // ACCESS_DENIED
                });

        // 6. DB 확인 — credential 여전히 존재
        ResponseEntity<JsonNode> listAAfter = http.exchange(
                "http://localhost:" + port + "/admin/api/tenants/" + tenantAId + "/credentials",
                HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(listAAfter.getBody().get("data").get("content"))
                .anyMatch(n -> n.get("credentialId").asText().equals(credentialIdB64));
    }

    private String createTenant(HttpHeaders auth, String slug, String displayName,
                                String rpId, String rpName) throws Exception {
        Map<String, Object> body = Map.of(
                "slug", slug,
                "displayName", displayName,
                "rpId", rpId,
                "rpName", rpName,
                "allowedOrigins", List.of("http://localhost:9090"),
                "acceptedFormats", List.of("none", "packed"),
                "requireUserVerification", true,
                "mdsRequired", false);
        ResponseEntity<JsonNode> res = http.exchange(
                "http://localhost:" + port + "/admin/api/tenants",
                HttpMethod.POST,
                new HttpEntity<>(om.writeValueAsString(body), auth),
                JsonNode.class);
        return res.getBody().get("data").get("id").asText();
    }
}
```

**`registerCredentialFor` helper**: AdminFlowIT 에 webauthn4j-test ClientPlatform 으로 credential 등록하는 helper 가 있어야 함. 만약 inline 으로만 있다면, 그 코드를 AdminFlowIT 의 protected helper 로 추출. Step 1 의 grep 결과로 결정.

**대안 — helper 가 없는 경우**: AdminFlowIT 의 ceremony 코드를 통째로 IT 안에 inline. webauthn4j-test 의 `ClientPlatform` + `PackedAuthenticator` 패턴은 `passkey-app/src/test/java/.../Fido2EndToEndIT.java` 도 참고 가능.

**A002 ACCESS_DENIED 매핑**: spec § 5.2 의 ErrorCode.ACCESS_DENIED 가 code="A002" 임. 실제 매핑이 다르면 spec 의 `:core/api/ErrorCode` 확인 후 코드 조정.

- [ ] **Step 3: 테스트 실행**

```bash
./gradlew :admin-app:test --tests CredentialAdminControllerSecurityIT
```

Expected: BUILD SUCCESSFUL, 1 test passed.

흔한 실패:
- `registerCredentialFor` helper 없음 → AdminFlowIT 의 ceremony 코드 inline
- A002 가 아닌 다른 코드 → 실제 코드로 조정
- 403 이 아닌 200 응답 → **CRITICAL** — service 의 tenantId 비교 누락. T4 의 service 코드 재확인

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/credential/CredentialAdminControllerSecurityIT.java
git commit -m "test(admin): CredentialAdminControllerSecurityIT — cross-tenant boundary (T7)

자동 IT 2 — admin-app VPD 비활성이므로 entity.tenantId vs path.tenantId
비교가 cross-tenant 누출 방어의 단일 layer. 이 IT 가 회귀 채널.
시나리오: tenant_A 의 credential 을 tenant_B path 로 회수 시도 → 403
ACCESS_DENIED, list 도 다른 tenant 에서 안 보임."
```

---

## Task 8: admin-ui — api/types.ts + client.ts helpers

백엔드가 노출하는 신규 엔드포인트를 호출하는 TypeScript 타입 + helper 함수.

**Files:**
- Modify: `admin-ui/src/api/types.ts`
- Modify: `admin-ui/src/api/client.ts`

- [ ] **Step 1: types.ts 에 3 종 추가**

`admin-ui/src/api/types.ts` 파일 끝에 추가:

```typescript
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

- [ ] **Step 2: client.ts 에 3 helper 추가**

`admin-ui/src/api/client.ts` 파일 끝에 추가:

```typescript
import type {
    TenantUpdateRequest,
    TenantView,
    CredentialView,
    PageView,
} from './types';

export const updateTenant = (id: string, req: TenantUpdateRequest) =>
    api.put<TenantView>(`/admin/api/tenants/${id}`, req);

export const listCredentials = (
    tenantId: string,
    params: { page: number; size: number; q?: string }
): Promise<PageView<CredentialView>> => {
    const qs = new URLSearchParams({
        page: String(params.page),
        size: String(params.size),
        ...(params.q ? { q: params.q } : {}),
    });
    return api.get<PageView<CredentialView>>(
        `/admin/api/tenants/${tenantId}/credentials?${qs}`,
    );
};

export const revokeCredential = (tenantId: string, credentialId: string) =>
    api.delete<void>(
        `/admin/api/tenants/${tenantId}/credentials/${encodeURIComponent(credentialId)}`,
    );
```

**기존 `TenantView` import 가 이미 있으면 중복 안 일어나도록** — 기존 import 문에 새 타입 합치기. import 충돌 시 한 줄로 합치거나 분리.

- [ ] **Step 3: typecheck**

```bash
cd admin-ui && npx tsc --noEmit
```

Expected: 에러 없음.

- [ ] **Step 4: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-tenant-detail
git add admin-ui/src/api/types.ts admin-ui/src/api/client.ts
git commit -m "feat(admin-ui): TenantUpdateRequest + CredentialView + PageView + helpers (T8)

API 클라이언트에 updateTenant, listCredentials, revokeCredential 3 helper.
타입은 백엔드 record 와 1:1 대응."
```

---

## Task 9: admin-ui 공통 컴포넌트 — Pagination + Mono

CredentialsTab 이 사용할 공통 UI 컴포넌트 2 개. 기존 `components/` 폴더의 패턴(Switch, Dialog 등) 을 따른다.

**Files:**
- Create: `admin-ui/src/components/Pagination.tsx`
- Create: `admin-ui/src/components/Mono.tsx`

- [ ] **Step 1: Pagination.tsx 작성**

`admin-ui/src/components/Pagination.tsx`:

```tsx
interface Props {
    page: number;
    size: number;
    total: number;
    onChange: (page: number) => void;
}

export default function Pagination({ page, size, total, onChange }: Props) {
    const totalPages = Math.max(1, Math.ceil(total / size));
    const canPrev = page > 0;
    const canNext = page < totalPages - 1;

    return (
        <div className="row" style={{ gap: 8, justifyContent: 'flex-end', alignItems: 'center', fontSize: 13 }}>
            <span className="muted">{total === 0 ? '0 / 0' : `${page * size + 1}–${Math.min((page + 1) * size, total)} / ${total}`}</span>
            <button className="btn btn--sm" disabled={!canPrev} onClick={() => onChange(0)}>{'«'}</button>
            <button className="btn btn--sm" disabled={!canPrev} onClick={() => onChange(page - 1)}>{'‹'}</button>
            <span style={{ minWidth: 56, textAlign: 'center' }}>{page + 1} / {totalPages}</span>
            <button className="btn btn--sm" disabled={!canNext} onClick={() => onChange(page + 1)}>{'›'}</button>
            <button className="btn btn--sm" disabled={!canNext} onClick={() => onChange(totalPages - 1)}>{'»'}</button>
        </div>
    );
}
```

- [ ] **Step 2: Mono.tsx 작성**

`admin-ui/src/components/Mono.tsx`:

```tsx
import { useState } from 'react';

interface Props {
    /** 화면에 표시할 짧은 형태 (예: 마지막 8자 또는 prefix). */
    short: string;
    /** clipboard 복사 + tooltip 의 전체 값. */
    full: string;
}

/**
 * monospace 식별자 표시 + click 으로 clipboard 복사 + hover 시 전체 값 tooltip.
 * credential id, user handle 등 base64url 식별자에 사용.
 */
export default function Mono({ short, full }: Props) {
    const [copied, setCopied] = useState(false);

    async function copy() {
        try {
            await navigator.clipboard.writeText(full);
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
        } catch {
            /* clipboard 권한 없으면 무시 */
        }
    }

    return (
        <span
            title={full}
            onClick={copy}
            style={{
                fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                fontSize: 12,
                background: 'var(--surface-2, #f4f5f7)',
                padding: '2px 6px',
                borderRadius: 4,
                cursor: 'pointer',
                userSelect: 'all',
            }}
        >
            {copied ? '✓ 복사됨' : short}
        </span>
    );
}
```

- [ ] **Step 3: typecheck**

```bash
cd admin-ui && npx tsc --noEmit
```

Expected: 에러 없음.

- [ ] **Step 4: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-tenant-detail
git add admin-ui/src/components/Pagination.tsx admin-ui/src/components/Mono.tsx
git commit -m "feat(admin-ui): Pagination + Mono 공통 컴포넌트 (T9)

Pagination: <<  <  N/M  >  >> + range 표시.
Mono: monospace 식별자 + click clipboard + hover tooltip."
```

---

## Task 10: admin-ui RevokeCredentialDialog

credential 회수 확인 다이얼로그. 마지막 8자 입력 일치 시에만 회수 버튼 활성화.

**Files:**
- Create: `admin-ui/src/components/RevokeCredentialDialog.tsx`

- [ ] **Step 1: 작성**

`admin-ui/src/components/RevokeCredentialDialog.tsx`:

```tsx
import { useState } from 'react';
import Dialog from './Dialog';
import Mono from './Mono';
import { revokeCredential } from '../api/client';
import { useToast } from './Toast';
import type { CredentialView } from '../api/types';

interface Props {
    open: boolean;
    credential: CredentialView | null;
    tenantId: string;
    onClose: () => void;
    onRevoked: () => void;
}

export default function RevokeCredentialDialog({
    open, credential, tenantId, onClose, onRevoked,
}: Props) {
    const [input, setInput] = useState('');
    const [busy, setBusy] = useState(false);
    const toast = useToast();

    if (!credential) return null;
    const last8 = credential.credentialId.slice(-8);
    const match = input === last8;

    async function confirm() {
        if (!credential || !match || busy) return;
        setBusy(true);
        try {
            await revokeCredential(tenantId, credential.credentialId);
            toast({ kind: 'ok', title: 'Credential 회수됨' });
            setInput('');
            onRevoked();
        } finally {
            setBusy(false);
        }
    }

    return (
        <Dialog
            open={open}
            onClose={onClose}
            title="Credential 회수"
            sub="이 작업은 되돌릴 수 없습니다."
            footer={
                <>
                    <button className="btn" onClick={onClose} disabled={busy}>취소</button>
                    <button className="btn btn--danger" onClick={confirm} disabled={!match || busy}>
                        {busy ? '회수 중…' : '회수'}
                    </button>
                </>
            }
        >
            <p style={{ color: 'var(--danger, #b00020)' }}>
                이 credential 을 회수하면 해당 사용자는 다시 등록(register)해야 로그인할 수 있습니다.
            </p>
            <p>확인을 위해 credential ID 의 마지막 8자를 입력하세요: <Mono short={last8} full={credential.credentialId} /></p>
            <input
                className="input"
                autoFocus
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="마지막 8자"
                style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}
            />
        </Dialog>
    );
}
```

- [ ] **Step 2: typecheck**

```bash
cd admin-ui && npx tsc --noEmit
```

Expected: 에러 없음. Dialog 의 props 가 spec 의 `wide`/`closeOnScrim` 등 optional 이라 `open` + `onClose` + `title` + `children` + `footer` 만 채워도 충분.

- [ ] **Step 3: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-tenant-detail
git add admin-ui/src/components/RevokeCredentialDialog.tsx
git commit -m "feat(admin-ui): RevokeCredentialDialog — 마지막 8자 확인 + revoke 호출 (T10)

input 이 credential.credentialId 의 마지막 8자와 정확히 일치할 때만
회수 버튼 활성. revokeCredential API 호출 후 Toast + onRevoked 콜백."
```

---

## Task 11: admin-ui WebAuthnConfigTab + OverviewTab

Tenant Detail 의 첫 두 탭. OverviewTab 은 KPI 2 종 + WebAuthn 요약. WebAuthnConfigTab 은 form + dirty + 저장.

**Files:**
- Create: `admin-ui/src/pages/tenant/OverviewTab.tsx`
- Create: `admin-ui/src/pages/tenant/WebAuthnConfigTab.tsx`

- [ ] **Step 1: 폴더 생성**

```bash
mkdir -p admin-ui/src/pages/tenant
```

- [ ] **Step 2: OverviewTab 작성**

`admin-ui/src/pages/tenant/OverviewTab.tsx`:

```tsx
import { useEffect, useState } from 'react';
import { api, listCredentials } from '../../api/client';
import type { TenantView, ApiKeyView } from '../../api/types';

interface Props {
    tenant: TenantView;
}

export default function OverviewTab({ tenant }: Props) {
    const [credCount, setCredCount] = useState<number | null>(null);
    const [keyCount, setKeyCount] = useState<number | null>(null);

    useEffect(() => {
        listCredentials(tenant.id, { page: 0, size: 1 })
            .then((p) => setCredCount(p.totalElements))
            .catch(() => setCredCount(null));
        api.get<ApiKeyView[]>(`/admin/api/api-keys?tenantId=${tenant.id}`)
            .then((rows) => setKeyCount(rows.length))
            .catch(() => setKeyCount(null));
    }, [tenant.id]);

    return (
        <div className="stack-4">
            <div className="row" style={{ gap: 16 }}>
                <Kpi label="Credentials" value={credCount} />
                <Kpi label="API Keys"    value={keyCount} />
            </div>

            <section className="card stack-3">
                <h3 style={{ marginTop: 0 }}>WebAuthn 설정</h3>
                <Row label="rpId"   value={tenant.rpId} mono />
                <Row label="rpName" value={tenant.rpName} />
                <Row label="Allowed Origins">
                    <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
                        {tenant.allowedOrigins.map((o) => (
                            <span key={o} className="chip">{o}</span>
                        ))}
                    </div>
                </Row>
                <Row label="Accepted Formats">
                    <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
                        {tenant.acceptedFormats.map((f) => (
                            <span key={f} className="chip">{f}</span>
                        ))}
                    </div>
                </Row>
                <Row label="requireUserVerification" value={tenant.requireUserVerification ? 'Y' : 'N'} />
                <Row label="mdsRequired"             value={tenant.mdsRequired ? 'Y' : 'N'} />
            </section>
        </div>
    );
}

function Kpi({ label, value }: { label: string; value: number | null }) {
    return (
        <div className="card" style={{ flex: 1, padding: 16 }}>
            <div className="muted" style={{ fontSize: 12 }}>{label}</div>
            <div style={{ fontSize: 28, fontWeight: 600 }}>{value === null ? '—' : value}</div>
        </div>
    );
}

function Row({
    label, value, mono, children,
}: { label: string; value?: string; mono?: boolean; children?: React.ReactNode }) {
    return (
        <div className="row" style={{ gap: 16, alignItems: 'baseline' }}>
            <div style={{ width: 200, color: 'var(--text-mute)' }}>{label}</div>
            {children ?? (
                <div style={{ fontFamily: mono ? 'ui-monospace, SFMono-Regular, Menlo, monospace' : undefined }}>
                    {value}
                </div>
            )}
        </div>
    );
}
```

- [ ] **Step 3: WebAuthnConfigTab 작성**

`admin-ui/src/pages/tenant/WebAuthnConfigTab.tsx`:

```tsx
import { useState } from 'react';
import { updateTenant } from '../../api/client';
import { useToast } from '../../components/Toast';
import OriginChipInput from '../../components/OriginChipInput';
import FormatCheckboxGrid from '../../components/FormatCheckboxGrid';
import Switch from '../../components/Switch';
import type { TenantView, TenantUpdateRequest } from '../../api/types';

interface Props {
    tenant: TenantView;
    onUpdated: (t: TenantView) => void;
}

type Draft = TenantUpdateRequest;

function toDraft(t: TenantView): Draft {
    return {
        displayName: t.displayName,
        rpName: t.rpName,
        allowedOrigins: [...t.allowedOrigins],
        acceptedFormats: [...t.acceptedFormats],
        requireUserVerification: t.requireUserVerification,
        mdsRequired: t.mdsRequired,
    };
}

function shallowEqual(a: Draft, b: Draft): boolean {
    return (
        a.displayName === b.displayName &&
        a.rpName === b.rpName &&
        a.requireUserVerification === b.requireUserVerification &&
        a.mdsRequired === b.mdsRequired &&
        a.allowedOrigins.length === b.allowedOrigins.length &&
        a.allowedOrigins.every((v, i) => v === b.allowedOrigins[i]) &&
        a.acceptedFormats.length === b.acceptedFormats.length &&
        a.acceptedFormats.every((v) => b.acceptedFormats.includes(v))
    );
}

export default function WebAuthnConfigTab({ tenant, onUpdated }: Props) {
    const [draft, setDraft] = useState<Draft>(toDraft(tenant));
    const [busy, setBusy] = useState(false);
    const toast = useToast();
    const dirty = !shallowEqual(draft, toDraft(tenant));

    async function save() {
        setBusy(true);
        try {
            const updated = await updateTenant(tenant.id, draft);
            onUpdated(updated);
            toast({ kind: 'ok', title: 'WebAuthn 설정 저장됨' });
        } finally {
            setBusy(false);
        }
    }

    return (
        <form
            className="stack-4"
            onSubmit={(e) => { e.preventDefault(); if (dirty && !busy) save(); }}
        >
            <ReadOnlyField label="rpId" value={tenant.rpId}
                           note="rpId 변경은 credential 영향 분석 후 별도 워크플로우" />
            <ReadOnlyField label="slug" value={tenant.slug} />

            <Field label="displayName">
                <input className="input" value={draft.displayName}
                       onChange={(e) => setDraft({ ...draft, displayName: e.target.value })} />
            </Field>
            <Field label="rpName">
                <input className="input" value={draft.rpName}
                       onChange={(e) => setDraft({ ...draft, rpName: e.target.value })} />
            </Field>

            <Field label="Allowed Origins">
                <OriginChipInput
                    value={draft.allowedOrigins}
                    onChange={(v) => setDraft({ ...draft, allowedOrigins: v })} />
            </Field>

            <Field label="Accepted Formats">
                <FormatCheckboxGrid
                    value={draft.acceptedFormats}
                    onChange={(v) => setDraft({ ...draft, acceptedFormats: v })} />
            </Field>

            <Field label="requireUserVerification">
                <Switch
                    checked={draft.requireUserVerification}
                    onChange={(v) => setDraft({ ...draft, requireUserVerification: v })} />
            </Field>

            <Field label="mdsRequired">
                <Switch
                    checked={draft.mdsRequired}
                    onChange={(v) => setDraft({ ...draft, mdsRequired: v })} />
            </Field>

            <div className="row" style={{ gap: 8 }}>
                <button type="submit" className="btn btn--primary" disabled={!dirty || busy}>
                    {busy ? '저장 중…' : '저장'}
                </button>
                <button type="button" className="btn" disabled={!dirty || busy}
                        onClick={() => setDraft(toDraft(tenant))}>되돌리기</button>
            </div>
        </form>
    );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <div className="stack-2">
            <label className="label">{label}</label>
            {children}
        </div>
    );
}

function ReadOnlyField({ label, value, note }: { label: string; value: string; note?: string }) {
    return (
        <div className="stack-2">
            <label className="label">{label}</label>
            <div className="input" style={{
                background: 'var(--surface-2, #f4f5f7)',
                color: 'var(--text-mute, #666)',
                cursor: 'not-allowed',
                fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
            }}>{value}</div>
            {note && <div className="muted" style={{ fontSize: 12 }}>{note}</div>}
        </div>
    );
}
```

**OriginChipInput / FormatCheckboxGrid / Switch 의 정확한 props**: 기존 컴포넌트의 export 시그니처에 따라 `value`/`onChange` 가 다를 수 있음. 컴파일 오류 시 import 후 hover 로 확인.

```bash
grep -n "interface Props\|export default" \
  admin-ui/src/components/OriginChipInput.tsx \
  admin-ui/src/components/FormatCheckboxGrid.tsx \
  admin-ui/src/components/Switch.tsx
```

차이가 있으면 그 시그니처에 맞춤.

- [ ] **Step 4: typecheck**

```bash
cd admin-ui && npx tsc --noEmit
```

Expected: 에러 없음.

- [ ] **Step 5: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-tenant-detail
git add admin-ui/src/pages/tenant/OverviewTab.tsx admin-ui/src/pages/tenant/WebAuthnConfigTab.tsx
git commit -m "feat(admin-ui): OverviewTab + WebAuthnConfigTab — Tenant Detail 1·2 탭 (T11)

OverviewTab: Credentials/API Keys KPI 카드 + WebAuthn 설정 요약 (chip 표시).
WebAuthnConfigTab: rpId/slug read-only, 6 필드 편집 가능, dirty 검사로
저장/되돌리기 버튼 활성화. updateTenant API 호출 + Toast."
```

---

## Task 12: admin-ui CredentialsTab

credential 테이블 + 검색 + 페이지네이션 + RevokeCredentialDialog 통합.

**Files:**
- Create: `admin-ui/src/pages/tenant/CredentialsTab.tsx`

- [ ] **Step 1: 작성**

`admin-ui/src/pages/tenant/CredentialsTab.tsx`:

```tsx
import { useCallback, useEffect, useState } from 'react';
import { listCredentials } from '../../api/client';
import Pagination from '../../components/Pagination';
import Mono from '../../components/Mono';
import RevokeCredentialDialog from '../../components/RevokeCredentialDialog';
import { formatDateTime } from '../../lib/formatDateTime';
import type { CredentialView, PageView } from '../../api/types';

interface Props {
    tenantId: string;
}

const PAGE_SIZE = 50;

export default function CredentialsTab({ tenantId }: Props) {
    const [page, setPage] = useState(0);
    const [q, setQ] = useState('');
    const [data, setData] = useState<PageView<CredentialView> | null>(null);
    const [target, setTarget] = useState<CredentialView | null>(null);
    const [busy, setBusy] = useState(false);

    const refresh = useCallback(() => {
        setBusy(true);
        listCredentials(tenantId, { page, size: PAGE_SIZE, q: q || undefined })
            .then(setData)
            .finally(() => setBusy(false));
    }, [tenantId, page, q]);

    useEffect(() => { refresh(); }, [refresh]);

    return (
        <div className="stack-3">
            <input
                className="input"
                placeholder="credentialId 또는 userHandle 일부…"
                value={q}
                onChange={(e) => { setPage(0); setQ(e.target.value); }}
                style={{ maxWidth: 360 }}
            />

            <table className="table">
                <thead>
                    <tr>
                        <th>credentialId</th>
                        <th>userHandle</th>
                        <th>Authenticator</th>
                        <th>fmt</th>
                        <th style={{ textAlign: 'right' }}>signCount</th>
                        <th>last used</th>
                        <th>created</th>
                        <th />
                    </tr>
                </thead>
                <tbody>
                    {data?.content.length === 0 && (
                        <tr><td colSpan={8} className="muted" style={{ textAlign: 'center', padding: 24 }}>
                            credential 없음
                        </td></tr>
                    )}
                    {data?.content.map((c) => (
                        <tr key={c.credentialId}>
                            <td><Mono short={c.credentialId.slice(-8)} full={c.credentialId} /></td>
                            <td><Mono short={c.userHandle.slice(0, 12)}  full={c.userHandle} /></td>
                            <td>
                                {c.authenticatorName ?? (
                                    <span className="muted">
                                        aaguid {c.aaguidHex ? c.aaguidHex.slice(0, 8) + '…' : '—'}
                                    </span>
                                )}
                            </td>
                            <td>{c.attestationFormat}</td>
                            <td style={{ textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>{c.signCount}</td>
                            <td>{c.lastUsedAt ? formatDateTime(c.lastUsedAt) : '—'}</td>
                            <td>{formatDateTime(c.createdAt)}</td>
                            <td>
                                <button className="btn btn--danger btn--sm"
                                        onClick={() => setTarget(c)}>회수</button>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>

            <Pagination
                page={page}
                size={PAGE_SIZE}
                total={data?.totalElements ?? 0}
                onChange={setPage}
            />

            <RevokeCredentialDialog
                open={target !== null}
                credential={target}
                tenantId={tenantId}
                onClose={() => setTarget(null)}
                onRevoked={() => { setTarget(null); refresh(); }}
            />

            {busy && <div className="muted" style={{ fontSize: 12 }}>불러오는 중…</div>}
        </div>
    );
}
```

**`formatDateTime` 위치**: Phase 8 에서 추가됨. 경로는 `admin-ui/src/lib/formatDateTime.ts`. 확인:

```bash
ls admin-ui/src/lib/formatDateTime.ts
```

없으면 동일 폴더에서 `grep -rn "formatDateTime" admin-ui/src` 로 위치 추적.

- [ ] **Step 2: typecheck**

```bash
cd admin-ui && npx tsc --noEmit
```

Expected: 에러 없음.

- [ ] **Step 3: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-tenant-detail
git add admin-ui/src/pages/tenant/CredentialsTab.tsx
git commit -m "feat(admin-ui): CredentialsTab — credential 목록 + 검색 + 회수 (T12)

50 개 페이지네이션. 검색은 credentialId/userHandle base64url 또는 hex 일부.
검색어 변경 시 page=0 으로 리셋. 회수는 RevokeCredentialDialog (마지막 8자
확인). Mono 컴포넌트로 식별자 표시 + click 복사."
```

---

## Task 13: admin-ui ApiKeyList → tenant/ApiKeysTab 이관

기존 `pages/ApiKeyList.tsx` + `pages/ApiKeyCreateModal.tsx` 의 로직을 `pages/tenant/ApiKeysTab.tsx` 로 이동. tenant 드롭다운 제거, `tenantId` props 화.

**Files:**
- Create: `admin-ui/src/pages/tenant/ApiKeysTab.tsx`
- Create: `admin-ui/src/pages/tenant/ApiKeyCreateModal.tsx`
- Delete: `admin-ui/src/pages/ApiKeyList.tsx`
- Delete: `admin-ui/src/pages/ApiKeyCreateModal.tsx`

- [ ] **Step 1: 기존 ApiKeyList.tsx 의 핵심 로직 옮기기**

`admin-ui/src/pages/tenant/ApiKeysTab.tsx` 작성:

```tsx
import { useEffect, useState } from 'react';
import { api } from '../../api/client';
import { useToast } from '../../components/Toast';
import { formatDateTime } from '../../lib/formatDateTime';
import ApiKeyCreateModal from './ApiKeyCreateModal';
import type { ApiKeyView } from '../../api/types';

interface Props {
    tenantId: string;
}

export default function ApiKeysTab({ tenantId }: Props) {
    const [keys, setKeys] = useState<ApiKeyView[]>([]);
    const [creating, setCreating] = useState(false);
    const [busy, setBusy] = useState(false);
    const toast = useToast();

    function refresh() {
        setBusy(true);
        api.get<ApiKeyView[]>(`/admin/api/api-keys?tenantId=${tenantId}`)
            .then(setKeys)
            .finally(() => setBusy(false));
    }

    useEffect(() => { refresh(); }, [tenantId]);

    async function revoke(id: string, name: string) {
        if (!confirm(`API key "${name}" 을 회수하시겠습니까?`)) return;
        await api.delete(`/admin/api/api-keys/${id}`);
        toast({ kind: 'ok', title: `API key 회수됨: ${name}` });
        refresh();
    }

    return (
        <div className="stack-3">
            <div className="row" style={{ justifyContent: 'space-between' }}>
                <div className="muted">{keys.length} 개</div>
                <button className="btn btn--primary" onClick={() => setCreating(true)}>
                    키 발급
                </button>
            </div>

            <table className="table">
                <thead>
                    <tr>
                        <th>이름</th>
                        <th>prefix</th>
                        <th>scopes</th>
                        <th>status</th>
                        <th>created</th>
                        <th>expires</th>
                        <th>last used</th>
                        <th />
                    </tr>
                </thead>
                <tbody>
                    {keys.length === 0 && (
                        <tr><td colSpan={8} className="muted" style={{ textAlign: 'center', padding: 24 }}>
                            API key 없음
                        </td></tr>
                    )}
                    {keys.map((k) => {
                        const revoked = !!k.revokedAt;
                        return (
                            <tr key={k.id}>
                                <td>{k.name}</td>
                                <td style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>{k.keyPrefix}</td>
                                <td>{k.scopes.join(', ')}</td>
                                <td>{revoked
                                    ? <span className="badge badge--danger">REVOKED</span>
                                    : <span className="badge badge--ok">ACTIVE</span>}</td>
                                <td>{formatDateTime(k.createdAt)}</td>
                                <td>{k.expiresAt ? formatDateTime(k.expiresAt) : '—'}</td>
                                <td>{k.lastUsedAt ? formatDateTime(k.lastUsedAt) : '—'}</td>
                                <td>
                                    {!revoked && (
                                        <button className="btn btn--danger btn--sm"
                                                onClick={() => revoke(k.id, k.name)}>회수</button>
                                    )}
                                </td>
                            </tr>
                        );
                    })}
                </tbody>
            </table>

            {busy && <div className="muted" style={{ fontSize: 12 }}>불러오는 중…</div>}

            <ApiKeyCreateModal
                open={creating}
                tenantId={tenantId}
                onClose={() => setCreating(false)}
                onCreated={() => { setCreating(false); refresh(); }}
            />
        </div>
    );
}
```

**기존 ApiKeyList.tsx 의 로직과 맞추기**: 위 코드는 합리적인 기본 — 실제 기존 파일에 더 많은 로직이 있다면 (예: status 컬럼 계산, expired 상태 등) 그것도 옮기기. 차이가 작으면 위 코드 그대로.

- [ ] **Step 2: ApiKeyCreateModal 옮기기**

기존 `admin-ui/src/pages/ApiKeyCreateModal.tsx` 의 내용을 `admin-ui/src/pages/tenant/ApiKeyCreateModal.tsx` 로 복사. **수정 사항**:
- `tenantId` 를 props 로 받음 (기존엔 props 였을 수 있음 — 확인)
- tenant 드롭다운 제거 (기존 ApiKeyList 에 있었을 수 있음)
- 외부 import 경로 `../api/client` → `../../api/client`, `../components/...` → `../../components/...`

기존 파일 먼저 확인:

```bash
cat admin-ui/src/pages/ApiKeyCreateModal.tsx
```

그 내용을 복사한 후 import 경로만 한 단계 더 깊은 폴더 기준으로 변경.

- [ ] **Step 3: 기존 파일 삭제**

```bash
git rm admin-ui/src/pages/ApiKeyList.tsx
git rm admin-ui/src/pages/ApiKeyCreateModal.tsx
```

- [ ] **Step 4: typecheck**

```bash
cd admin-ui && npx tsc --noEmit
```

Expected: 에러 없음.

만약 다른 파일이 `import ApiKeyList from '../pages/ApiKeyList'` 하던 곳이 있으면 (App.tsx) — 다음 task 에서 처리.

- [ ] **Step 5: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-tenant-detail
git add admin-ui/src/pages/tenant/ApiKeysTab.tsx admin-ui/src/pages/tenant/ApiKeyCreateModal.tsx
git commit -m "feat(admin-ui): ApiKeyList → tenant/ApiKeysTab 이관 (T13)

기존 페이지의 tenant 드롭다운 제거 + tenantId props 화. 라우트 분리.
파일 삭제: pages/ApiKeyList.tsx, pages/ApiKeyCreateModal.tsx."
```

---

## Task 14: admin-ui TenantDetail.tsx + App.tsx route + Sidebar + TenantList onClick

마지막 admin-ui 통합. 라우터에 새 경로 추가, 사이드바 메뉴 정리, TenantList 행 클릭 → 상세 페이지로.

**Files:**
- Create: `admin-ui/src/pages/TenantDetail.tsx`
- Modify: `admin-ui/src/App.tsx`
- Modify: `admin-ui/src/components/Sidebar.tsx`
- Modify: `admin-ui/src/components/CommandPalette.tsx`
- Modify: `admin-ui/src/pages/TenantList.tsx`

- [ ] **Step 1: TenantDetail.tsx 작성**

`admin-ui/src/pages/TenantDetail.tsx`:

```tsx
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import OverviewTab from './tenant/OverviewTab';
import WebAuthnConfigTab from './tenant/WebAuthnConfigTab';
import CredentialsTab from './tenant/CredentialsTab';
import ApiKeysTab from './tenant/ApiKeysTab';
import type { TenantView } from '../api/types';

type TabKey = 'overview' | 'webauthn' | 'credentials' | 'apikeys';

const TABS: { key: TabKey; label: string }[] = [
    { key: 'overview',    label: 'Overview' },
    { key: 'webauthn',    label: 'WebAuthn Configuration' },
    { key: 'credentials', label: 'Credentials' },
    { key: 'apikeys',     label: 'API Keys' },
];

export default function TenantDetail() {
    const { id } = useParams<{ id: string }>();
    const nav = useNavigate();
    const [tenant, setTenant] = useState<TenantView | null>(null);
    const [tab, setTab] = useState<TabKey>('overview');
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!id) return;
        setError(null);
        api.get<TenantView>(`/admin/api/tenants/${id}`)
            .then(setTenant)
            .catch((e) => setError(e?.message ?? 'tenant 조회 실패'));
    }, [id]);

    if (error) {
        return (
            <div className="stack-3">
                <button className="btn btn--ghost" onClick={() => nav('/tenants')}>← 목록으로</button>
                <div className="banner banner--danger">{error}</div>
            </div>
        );
    }

    if (!tenant) {
        return <div className="muted">불러오는 중…</div>;
    }

    return (
        <div className="stack-4">
            <div className="stack-2">
                <button className="btn btn--ghost btn--sm" onClick={() => nav('/tenants')}>← 목록으로</button>
                <div className="row" style={{ gap: 12, alignItems: 'baseline' }}>
                    <h1 style={{ margin: 0 }}>{tenant.displayName}</h1>
                    <span className="muted" style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>
                        {tenant.slug}
                    </span>
                    <span className={'badge ' + (tenant.status === 'active' ? 'badge--ok' : 'badge--warn')}>
                        {tenant.status}
                    </span>
                </div>
            </div>

            <div className="row" style={{ gap: 4, borderBottom: '1px solid var(--border)' }}>
                {TABS.map((t) => (
                    <button
                        key={t.key}
                        className={'tab ' + (tab === t.key ? 'tab--active' : '')}
                        onClick={() => setTab(t.key)}
                    >
                        {t.label}
                    </button>
                ))}
            </div>

            {tab === 'overview'    && <OverviewTab tenant={tenant} />}
            {tab === 'webauthn'    && <WebAuthnConfigTab tenant={tenant} onUpdated={setTenant} />}
            {tab === 'credentials' && <CredentialsTab tenantId={tenant.id} />}
            {tab === 'apikeys'     && <ApiKeysTab tenantId={tenant.id} />}
        </div>
    );
}
```

- [ ] **Step 2: App.tsx 라우트 변경**

`admin-ui/src/App.tsx` 의 `<Routes>` 블록:

```diff
  <Route path="/tenants" element={<TenantList />} />
  <Route path="/tenants/new" element={<TenantCreate />} />
+ <Route path="/tenants/:id" element={<TenantDetail />} />
- <Route path="/api-keys" element={<ApiKeyList />} />
  <Route path="/audit" element={<AuditLog />} />
```

상단 import:

```diff
- import ApiKeyList from './pages/ApiKeyList';
+ import TenantDetail from './pages/TenantDetail';
```

- [ ] **Step 3: Sidebar.tsx — API Keys 메뉴 제거**

`admin-ui/src/components/Sidebar.tsx` 에서 API Keys 항목 한 줄 제거. 예시 (실제 구조에 맞게):

```diff
  { to: '/tenants',  label: 'Tenants',     icon: <Building /> },
- { to: '/api-keys', label: 'API Keys',    icon: <Key /> },
  { to: '/keys',     label: 'Signing Keys', icon: <Key /> },
  { to: '/mds',      label: 'MDS',          icon: <Refresh /> },
  { to: '/audit',    label: 'Audit Log',    icon: <Receipt /> },
```

실제 코드를 보고 한 줄만 제거.

- [ ] **Step 4: CommandPalette.tsx — API Keys 항목 제거**

`admin-ui/src/components/CommandPalette.tsx` 에서 "API Keys" 와 "신규 API Key" 두 항목 제거:

```bash
grep -n "API Keys\|api-keys\|신규 API" admin-ui/src/components/CommandPalette.tsx
```

해당 항목 라인 삭제.

- [ ] **Step 5: TenantList.tsx — 행 클릭 라우팅**

`admin-ui/src/pages/TenantList.tsx` 의 `<tr>` 또는 `<table>` 행에 onClick 추가:

```bash
grep -n "<tr\|map(.*tenant\|nav(" admin-ui/src/pages/TenantList.tsx
```

기존 row rendering 부분을 찾아 다음 형태로 수정:

```tsx
<tr
    key={t.id}
    onClick={() => nav('/tenants/' + t.id)}
    style={{ cursor: 'pointer' }}
>
```

`useNavigate` import 가 없으면 추가:

```diff
- import { Link } from 'react-router-dom';
+ import { Link, useNavigate } from 'react-router-dom';
```

컴포넌트 본문 상단:

```diff
  export default function TenantList() {
+   const nav = useNavigate();
    ...
```

- [ ] **Step 6: typecheck + dev build**

```bash
cd admin-ui
npx tsc --noEmit
npm run build
```

Expected: 둘 다 에러 없음. build 가 dist/ 생성.

- [ ] **Step 7: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-tenant-detail
git add admin-ui/src/pages/TenantDetail.tsx \
        admin-ui/src/App.tsx \
        admin-ui/src/components/Sidebar.tsx \
        admin-ui/src/components/CommandPalette.tsx \
        admin-ui/src/pages/TenantList.tsx
git commit -m "feat(admin-ui): TenantDetail 페이지 + 라우터/사이드바 정리 (T14)

- 신규: /tenants/:id → TenantDetail (4 탭 컨테이너)
- 제거: /api-keys 라우트, 사이드바 메뉴, CommandPalette 항목 2개
- TenantList 행 클릭 시 detail 페이지로 라우팅."
```

---

## Task 15: End-to-end 수동 smoke + followup 기록 + main 으로 merge

자동 테스트 2 개는 T6/T7 에서 통과. 이번 task 는 운영자 시점의 manual smoke + followup 파일 작성 + `--no-ff` merge.

**Files:**
- Create: `docs/superpowers/followups/2026-05-27-admin-tenant-detail-followups.md`

- [ ] **Step 1: 전체 빌드 한 번 더**

```bash
./gradlew :core:build :passkey-app:build :admin-app:build -x test
cd admin-ui && npm run build && cd ..
```

Expected: 모두 BUILD SUCCESSFUL.

- [ ] **Step 2: 두 자동 IT 한 번 더 실행**

```bash
./gradlew :admin-app:test --tests TenantAdminControllerUpdateIT --tests CredentialAdminControllerSecurityIT
```

Expected: 2 passed.

- [ ] **Step 3: 수동 smoke (운영자가 직접 — 자동화 안 함)**

운영자가 다음 절차로 brand new 환경에서 확인:

```
[1] docker compose up -d  (Passkey2 root)
[2] ./gradlew :admin-app:bootRun                          (별 터미널, profile=local)
[3] ./gradlew :passkey-app:bootRun \
       --args="--passkey.id-token.issuer-base=http://localhost:8080"  (별 터미널)
[4] cd admin-ui && npm run dev   →  http://localhost:5173
```

브라우저 5분 manual smoke:

1. /tenants 행 클릭 → /tenants/:id 이동, Overview 첫 화면 + Credentials/API Keys KPI 카드
2. WebAuthn 탭 → displayName 변경 + 저장 → Toast 성공 → 새로고침해도 값 유지
3. Audit Log 페이지 → `action` 필터 TENANT_UPDATE 선택 → row 보이고 payload.changedFields = ["displayName"]
4. (sample-rp 동작 중이면) Credentials 탭 → 등록된 credential 보임 + Authenticator 컬럼
5. Revoke 다이얼로그 → 마지막 8자 오입력 → 버튼 비활성 → 정확 입력 → 회수 → 테이블에서 사라짐 + Audit Log 에 CREDENTIAL_REVOKE

- [ ] **Step 4: followup 파일 작성**

`docs/superpowers/followups/2026-05-27-admin-tenant-detail-followups.md`:

```markdown
# Admin Tenant Detail followups

Spec: docs/superpowers/specs/2026-05-27-admin-tenant-detail-design.md
Plan: docs/superpowers/plans/2026-05-27-admin-tenant-detail.md

## Manual smoke result (T15)

운영자가 5분 manual smoke 후 체크박스 채움.

- [ ] T15.1 docker compose ps — oracle + redis Up
- [ ] T15.2 admin-app + passkey-app 8081/8080 LISTEN
- [ ] T15.3 admin-ui 5173 LISTEN
- [ ] T15.4 /tenants 행 클릭 → /tenants/:id 이동, Overview 첫 화면
- [ ] T15.5 WebAuthn 탭 → displayName 변경 + 저장 + 새로고침 유지
- [ ] T15.6 Audit Log 에 TENANT_UPDATE row + changedFields 표시
- [ ] T15.7 Credentials 탭 → 등록된 credential 보임
- [ ] T15.8 Revoke 다이얼로그 → 마지막 8자 검증 → 회수 + audit row

## Deferred (spec § 6.2)

1. Credential 엔티티 확장 (externalUserId / nickname / status / revokedAt) — soft delete
2. Tenant rpId 변경 워크플로우 — credential 영향 분석 다이얼로그
3. WebAuthn 설정 diff 미리보기 모달 (+/- 라인)
4. Activity 페이지 + 최근 활동 5건 — audit_log 에 tenant_id 컬럼
5. Audit Chain Monitor — sparkline + tenant 별 검증 + 월간 PDF
6. Funnel + metric 집계 인프라
7. Role 모델 확장 — PLATFORM_OPERATOR / RP_ADMIN
8. Admin 사용자 관리 UI
9. 보안 정책 탭 (idle / password / MFA / CORS)
10. Tweaks 패널, ⌘K 액션 확장, CSV 내보내기

## In-loop findings

phase 진행 중 codex review / 구현 과정에서 발견된 결정·우회·deferred 항목을 여기에 누적.
```

- [ ] **Step 5: followup commit**

```bash
git add docs/superpowers/followups/2026-05-27-admin-tenant-detail-followups.md
git commit -m "docs(followups): admin-tenant-detail manual smoke checklist + deferred 10개 (T15)"
```

- [ ] **Step 6: branch 전체 codex review (옵션 — feedback_codex_review_before_commit)**

```bash
git log --stat feature/admin-tenant-detail ^main
```

이 출력과 함께 codex 에 final review 요청. 결과를 followup 에 in-loop findings 로 추가.

- [ ] **Step 7: main 으로 merge**

```bash
# worktree 에서 빠져 나오기
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git checkout main
git merge --no-ff feature/admin-tenant-detail -m "$(cat <<'EOF'
Merge feature/admin-tenant-detail — Tenant Detail + Credentials + WebAuthn 설정

15 개 task 완료:
- T1: MdsAaguidCache 를 passkey-app → :core 로 이동
- T2: core/api/PageView<T> 공통 페이지네이션 record
- T3: CredentialRepository.findAllByTenantId + searchByTenantId (ESCAPE '\')
- T4: CredentialAdminController + Service + Dto (cross-tenant boundary 단일 layer)
- T5: PUT /admin/api/tenants/{id} + TenantSnapshot before/after diff + audit
- T6: TenantAdminControllerUpdateIT (자동 1 — happy path + audit)
- T7: CredentialAdminControllerSecurityIT (자동 2 — cross-tenant 거부)
- T8: admin-ui types.ts + client.ts helpers
- T9: Pagination + Mono 공통 컴포넌트
- T10: RevokeCredentialDialog (마지막 8자 검증)
- T11: OverviewTab + WebAuthnConfigTab
- T12: CredentialsTab (검색 + 페이지 + revoke)
- T13: ApiKeyList → tenant/ApiKeysTab 이관
- T14: TenantDetail 페이지 + 라우터/사이드바/CommandPalette 정리
- T15: manual smoke + followup + merge

자동 테스트는 보안 boundary 1 + audit happy path 1 = 2 IT 만.
개발 속도 우선 합의 적용.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 8: worktree 정리**

```bash
git worktree remove .claude/worktrees/admin-tenant-detail
git branch -d feature/admin-tenant-detail
```

Expected: worktree 제거 + branch 정리.

---

## Self-Review

### 1. Spec coverage

| Spec 섹션 | 구현 task |
|---|---|
| § 1.1 DoD (4 탭 + 사이드바 정리) | T11 + T12 + T13 + T14 |
| § 1.2 의도적 제외 | T15 followup 에 deferred 10 개 기록 |
| § 2.1 엔티티 변경 없음 | (변경 없음) |
| § 2.2 PUT /tenants/{id}, GET/DELETE credentials | T5, T4 |
| § 2.3 CredentialView, PageView<T> | T4, T2 |
| § 2.4 CredentialAdminService, TenantAdminService.update | T4, T5 |
| § 2.5 Repository 메서드 + ESCAPE '\' | T3 |
| § 2.6 MdsAaguidCache 이동 | T1 |
| § 2.7 인증/권한 (ADMIN role + tenantId 검사) | T4, T5 (controller @PreAuthorize + service 첫 줄 검사) |
| § 3.1 라우트 변경 | T14 |
| § 3.2 사이드바 | T14 |
| § 3.3 TenantList 행 클릭 | T14 |
| § 3.4 파일 구조 (pages/tenant/ 폴더) | T11~T13 |
| § 3.5 TenantDetail.tsx | T14 |
| § 3.6 OverviewTab | T11 |
| § 3.7 WebAuthnConfigTab | T11 |
| § 3.8 CredentialsTab | T12 |
| § 3.9 RevokeCredentialDialog | T10 |
| § 3.10 ApiKeysTab 이관 | T13 |
| § 3.11 API 클라이언트 | T8 |
| § 4.1~4.4 audit 통합 + 데이터 흐름 | T4 (CREDENTIAL_REVOKE), T5 (TENANT_UPDATE) |
| § 4.5 PageView | T2 |
| § 4.6 traceId 전파 (기존 패턴 그대로) | 변경 없음 |
| § 5 자동 테스트 2 | T6, T7 |
| § 5.5 수동 smoke | T15 |
| § 6 위험 / 후속 | T15 followup |

모든 spec 항목 구현 task 매핑됨. 빠진 곳 없음.

### 2. Placeholder scan

- "TBD" / "TODO" / "implement later" / "fill in" — 검색 결과 0.
- 모든 step 에 실제 코드 또는 명령 포함.
- "Similar to Task N" 없음 — 각 task 의 코드 전체 명시.

### 3. Type consistency

- `CredentialView` — T4 (백엔드 record), T8 (TS interface), T9~T12 (사용처) 모두 동일 필드.
- `PageView<T>` — T2 (core), T8 (TS), T12 (사용) 동일.
- `TenantUpdateRequest` — 기존 record 활용 + T8 의 TS interface — 6 필드 일치.
- `TenantSnapshot` — T5 에서 정의, 같은 task 의 update() 가 사용.
- `revokeCredential(tenantId, credentialId)` — T8 helper, T10 사용처 동일 시그니처.
- `listCredentials(tenantId, { page, size, q? })` — T8 helper, T11/T12 사용처 동일.
- `updateTenant(id, req)` — T8 helper, T11 사용처 동일.
- AuditAppendRequest 호출 시 `targetType` 은 모두 대문자 ("TENANT", "CREDENTIAL") — 기존 패턴 일관.
- Sort.Order.desc("lastUsedAt").nullsLast() — T4 service 에서 정확한 Spring Data 6 API 시그니처.

### 4. Ambiguity

- T1 의 grep 결과로 import 갱신 대상 명시.
- T4 의 BusinessException 위치는 Step 3 의 find 명령으로 동적 결정 — 양쪽 후보 (`core/api/` 또는 `admin-app/.../`) 모두 처리.
- T5 의 `lookup()` 메서드 — 기존 `get()` 의 내부 로직 재사용 또는 신규 메서드 (DRY 위반 허용 명시).
- T6 의 `extends AdminFlowIT` 가 불가하면 helper 코드 inline 으로 대안 명시.
- T13 의 ApiKeyCreateModal 옮기기 — 기존 파일 cat 후 import 경로만 한 단계 깊게.
