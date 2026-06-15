# Activity 화면 개선 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PLATFORM_OPERATOR Activity 대시보드에 단일 필터 바(카테고리·테넌트·액션) + 행 문장화 + 행 클릭 시 payload 상세 패널을 추가해, "필터가 헷갈리고 / 무엇이 어떻게 바뀌었는지 안 보이고 / 테넌트로 못 좁히는" 세 불편을 해소한다.

**Architecture:** 백엔드는 기존 `GET /admin/api/activity`(피드/KPI)를 그대로 두고, payload 상세를 위한 단건 조회 `GET /admin/api/activity/{id}`를 신설(폴링 피드에 CLOB 안 실음). 프론트는 `ActivityPage.tsx`에서 헤더 클라이언트 필터를 제거하고 상단 단일 필터 바로 통합, 피드 행을 한글 문장으로 렌더, 행 클릭 시 우측 상세 패널에서 단건 조회한 payload를 표시.

**Tech Stack:** 백엔드 Spring Boot(Java 17, record DTO, `@PreAuthorize`), 통합테스트 `@SpringBootTest`+Testcontainers(Oracle/Redis). 프론트 React+TypeScript+Vite, 테스트 Vitest+@testing-library/react.

---

## File Structure

**백엔드 (admin-app / core):**
- `admin-app/.../activity/ActivityDetailView.java` — **Create**: payload 포함 단건 DTO
- `admin-app/.../activity/ActivityService.java` — **Modify**: `detail(UUID)` 메서드 추가
- `admin-app/.../activity/ActivityController.java` — **Modify**: `GET /{id}` 엔드포인트 추가
- `admin-app/.../activity/ActivityControllerIT.java` — **Modify**: 단건 조회 IT 추가
- `ActivityService`는 단건 조회를 위해 `AuditLogRepository.findById(UUID)`(JpaRepository 상속, 이미 존재) 사용 — 신규 리포지토리 메서드 불필요.

**프론트 (admin-ui):**
- `src/api/types.ts` — **Modify**: `ActivityDetailView` 인터페이스 추가
- `src/api/activity.ts` — **Modify**: `fetchDetail(id)` 추가
- `src/pages/activityLabels.ts` — **Create**: 액션 코드 → 한글 라벨 매핑 + 문장 빌더
- `src/pages/activityLabels.test.ts` — **Create**: 라벨/문장 단위 테스트
- `src/pages/ActivityPage.tsx` — **Modify**: 헤더 클라이언트 필터 제거, 단일 필터 바(테넌트 드롭다운+액션 검색), 행 문장화, 상세 패널, "활발한 Tenant" 클릭→필터

---

## Task 1: 백엔드 — ActivityDetailView DTO

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityDetailView.java`

- [ ] **Step 1: ActivityDetailView record 작성**

기존 `ActivityView.Event`(id, action, actorEmail, targetType, targetId, tenantId, tenantSlug, createdAt, category)와 동일 필드에 `actorId`(UUID)와 `payload`(String, canonical JSON)를 추가한다. `AuditLog`에서 변환하는 정적 팩토리를 둔다(tenantSlug는 서비스가 주입 — 엔티티에 없음).

```java
package com.crosscert.passkey.admin.activity;

import com.crosscert.passkey.core.entity.AuditLog;

import java.time.Instant;
import java.util.UUID;

/**
 * 단건 Activity 상세 — payload 포함. 폴링 피드({@link ActivityView.Event})에는
 * CLOB payload 를 싣지 않고, 행 클릭 시 GET /admin/api/activity/{id} 로만 조회한다.
 *
 * <p>{@code tenantSlug} 와 {@code category} 는 엔티티에 없는 파생 값이라 서비스가
 * 주입한다. {@link #from} 는 엔티티 직역 필드만 채우고 그 둘은 인자로 받는다.
 */
public record ActivityDetailView(
        UUID id,
        String action,
        UUID actorId,
        String actorEmail,
        String targetType,
        String targetId,
        UUID tenantId,
        String tenantSlug,
        Instant createdAt,
        String category,
        String payload
) {
    public static ActivityDetailView from(AuditLog a, String tenantSlug, String category) {
        return new ActivityDetailView(
                a.getId(),
                a.getAction(),
                a.getActorId(),
                a.getActorEmail(),
                a.getTargetType(),
                a.getTargetId(),
                a.getTenantId(),
                tenantSlug,
                a.getCreatedAt(),
                category,
                a.getPayload());
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :admin-app:compileJava`
Expected: BUILD SUCCESSFUL (새 record 컴파일됨)

- [ ] **Step 3: 커밋**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityDetailView.java
git commit -m "feat(activity): payload 포함 단건 상세 DTO ActivityDetailView"
```

---

## Task 2: 백엔드 — ActivityService.detail(id)

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityService.java`

ActivityService는 이미 `ActivityRepository activity`, `TenantRepository tenants`, `Clock clock`를 주입받는다(`@RequiredArgsConstructor`). 단건 조회는 `AuditLog`를 id로 가져와야 하는데 `ActivityRepository`는 `Repository<AuditLog,UUID>`라 `findById`가 없다. **`AuditLogRepository`(JpaRepository, `findById` 보유)를 추가 주입**한다.

- [ ] **Step 1: AuditLogRepository import + 필드 추가**

`ActivityService.java` 상단 import에 추가:

```java
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import java.util.Optional;
```

필드 선언부(`private final ActivityRepository activity;` 등이 있는 곳)에 추가:

```java
    private final AuditLogRepository auditLogs;
```

> `@RequiredArgsConstructor`라 final 필드 추가만으로 생성자 주입됨. 필드 선언 순서는 기존 필드 뒤에 두면 됨.

- [ ] **Step 2: detail(UUID) 메서드 추가**

클래스 안, `categorize(String)` 메서드 근처에 추가. `categorize`는 기존 private 메서드를 재사용한다.

```java
    /**
     * 행 클릭 시 단건 상세 — payload 포함. 존재하지 않으면 NOT_FOUND.
     * PLATFORM_OPERATOR 전용 엔드포인트에서만 호출되므로 글로벌 조회(테넌트 무관) 허용.
     */
    @Transactional(readOnly = true)
    public ActivityDetailView detail(UUID id) {
        AuditLog a = auditLogs.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "activity not found: " + id));
        String slug = a.getTenantId() == null ? null
                : tenants.findById(a.getTenantId()).map(Tenant::getSlug).orElse("(deleted)");
        return ActivityDetailView.from(a, slug, categorize(a.getAction()));
    }
```

> `ErrorCode.NOT_FOUND`가 없으면 `ErrorCode` enum을 확인해 가장 가까운 값(예: `RESOURCE_NOT_FOUND`)으로 교체한다. Step 3에서 컴파일로 검증된다.

- [ ] **Step 3: 컴파일 확인 (ErrorCode 상수 검증)**

Run: `./gradlew :admin-app:compileJava`
Expected: BUILD SUCCESSFUL. `cannot find symbol ErrorCode.NOT_FOUND`가 나오면 `grep -n "NOT_FOUND\|NOTFOUND" core/src/main/java/com/crosscert/passkey/core/api/ErrorCode.java`로 실제 상수명을 찾아 교체 후 재컴파일.

- [ ] **Step 4: 커밋**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityService.java
git commit -m "feat(activity): 단건 상세 조회 ActivityService.detail(id)"
```

---

## Task 3: 백엔드 — GET /admin/api/activity/{id} 엔드포인트

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityController.java`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/activity/ActivityControllerIT.java`

- [ ] **Step 1: 실패하는 통합 테스트 작성**

`ActivityControllerIT.java`에 테스트 메서드를 추가한다. 기존 `loginAs(email, password)` 헬퍼와 `resetState()`(demo-rp/alice/bob 세팅)를 사용한다. payload가 있는 audit_log 행 1건을 owner 풀로 직접 INSERT하고, alice(PLATFORM_OPERATOR)로 단건 조회해 payload가 내려오는지 검증한다. 추가로 bob(RP_ADMIN)은 403, 없는 id는 404를 확인한다.

import가 부족하면 추가: `import java.util.UUID;`는 이미 있음. `org.springframework.http.HttpStatus`는 필요 시 추가.

```java
    @Test
    void detail_returnsPayload_forPlatformOperator() {
        // demo-rp 테넌트(...C0DE)에 payload 있는 행 1건 직접 삽입.
        String idHex = "0000000000000000000000000000DA01";
        ownerJdbc().update("""
                INSERT INTO APP_OWNER.audit_log
                    (id, prev_hash, hash, actor_id, actor_email, action,
                     target_type, target_id, payload, created_at, updated_at,
                     tenant_id, tenant_prev_hash, tenant_hash)
                VALUES (HEXTORAW(?), NULL, SYS_GUID(), NULL, 'admin@acme.com',
                     'WEBAUTHN_CONFIG_UPDATED', 'TENANT', 'demo-rp',
                     '{"before":{"uv":false},"after":{"uv":true}}',
                     SYSTIMESTAMP, SYSTIMESTAMP,
                     HEXTORAW('0000000000000000000000000000C0DE'), NULL, SYS_GUID())
                """, idHex);

        HttpHeaders auth = loginAs("alice@crosscert.com", "alice-temp-pw");
        ResponseEntity<String> resp = rest.exchange(
                url("/admin/api/activity/00000000-0000-0000-0000-0000000000da01"),
                HttpMethod.GET, new HttpEntity<>(auth), String.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = readJson(resp.getBody());
        assertThat(body.get("action").asText()).isEqualTo("WEBAUTHN_CONFIG_UPDATED");
        assertThat(body.get("payload").asText()).contains("\"after\"");
        assertThat(body.get("tenantSlug").asText()).isEqualTo("demo-rp");
    }

    @Test
    void detail_isForbidden_forRpAdmin() {
        String idHex = "0000000000000000000000000000DA02";
        ownerJdbc().update("""
                INSERT INTO APP_OWNER.audit_log
                    (id, prev_hash, hash, actor_id, actor_email, action,
                     target_type, target_id, payload, created_at, updated_at,
                     tenant_id, tenant_prev_hash, tenant_hash)
                VALUES (HEXTORAW(?), NULL, SYS_GUID(), NULL, 'x@x.com', 'API_KEY_ISSUE',
                     'API_KEY', 'pk_x', '{}', SYSTIMESTAMP, SYSTIMESTAMP,
                     HEXTORAW('0000000000000000000000000000C0DE'), NULL, SYS_GUID())
                """, idHex);
        HttpHeaders auth = loginAs("bob@crosscert.com", "bob-temp-pw");
        ResponseEntity<String> resp = rest.exchange(
                url("/admin/api/activity/00000000-0000-0000-0000-0000000000da02"),
                HttpMethod.GET, new HttpEntity<>(auth), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void detail_isNotFound_forUnknownId() {
        HttpHeaders auth = loginAs("alice@crosscert.com", "alice-temp-pw");
        ResponseEntity<String> resp = rest.exchange(
                url("/admin/api/activity/00000000-0000-0000-0000-0000000000dead"),
                HttpMethod.GET, new HttpEntity<>(auth), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }
```

테스트가 JSON을 읽는 헬퍼 `readJson`이 없으면 클래스에 추가(ObjectMapper `om`은 이미 주입됨). 응답이 `ApiResponse` envelope이면 `.get("data")`를 거쳐야 한다 — 기존 활동 피드 테스트가 envelope을 어떻게 푸는지 보고 동일하게 맞춘다:

```java
    private JsonNode readJson(String body) {
        try {
            JsonNode root = om.readTree(body);
            return root.has("data") ? root.get("data") : root;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
```

> **id 표기 주의:** 위 테스트의 임시 `uuid` 변수 줄은 제거하고, URL과 INSERT의 RAW(16) `DA01`이 UUID 문자열 `00000000-0000-0000-0000-0000000000da01`과 정확히 대응하는지 확인한다. RAW `0000...DA01`(32 hex)는 UUID `00000000-0000-0000-0000-0000000000da01`이다. INSERT의 `HEXTORAW(?)` 인자도 동일 32 hex(`0000000000000000000000000000DA01`)여야 한다.

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew :admin-app:test --tests ActivityControllerIT`
Expected: FAIL — `detail_*` 3개가 404/405(엔드포인트 없음) 또는 컴파일 에러로 실패. (피드 기존 테스트는 통과)

- [ ] **Step 3: 컨트롤러에 GET /{id} 추가**

`ActivityController.java`에 import + 메서드 추가:

```java
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
```

```java
    /** 행 클릭 시 단건 상세 — payload 포함. PLATFORM_OPERATOR 전용(피드와 동일 경계). */
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping("/{id}")
    public ApiResponse<ActivityDetailView> detail(@PathVariable UUID id) {
        return service.detail(id);
    }
```

> 반환은 `ApiResponse.ok(...)` 패턴을 기존 `activity(...)`와 맞춘다: `return ApiResponse.ok(service.detail(id));` (service.detail은 DTO를 직접 반환하므로 컨트롤러에서 `ApiResponse.ok`로 감싼다). 위 스니펫을 `return ApiResponse.ok(service.detail(id));`로 작성할 것.

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew :admin-app:test --tests ActivityControllerIT`
Expected: PASS (detail 3개 + 기존 피드 테스트 전부 통과)

- [ ] **Step 5: 커밋**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityController.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/activity/ActivityControllerIT.java
git commit -m "feat(activity): GET /admin/api/activity/{id} 단건 상세 엔드포인트 + IT"
```

---

## Task 4: 프론트 — ActivityDetailView 타입 + fetchDetail API

**Files:**
- Modify: `admin-ui/src/api/types.ts`
- Modify: `admin-ui/src/api/activity.ts`

- [ ] **Step 1: types.ts에 ActivityDetailView 추가**

기존 `ActivityEvent` 인터페이스 아래에 추가. 백엔드 DTO와 1:1 매칭(actorId, payload 추가):

```typescript
export interface ActivityDetailView {
  id: string;
  action: string;
  actorId: string | null;
  actorEmail: string;
  targetType: string | null;
  targetId: string | null;
  tenantId: string | null;
  tenantSlug: string | null;
  createdAt: string;
  category: 'ops' | 'security' | 'system';
  payload: string; // canonical JSON string
}
```

- [ ] **Step 2: activity.ts에 fetchDetail 추가**

`activityApi` 객체에 메서드 추가. import에 `ActivityDetailView` 추가:

```typescript
import type { ActivityView, ActivityCategory, ActivityDetailView } from './types';
```

`fetch` 아래에 추가:

```typescript
  fetchDetail: (id: string): Promise<ActivityDetailView> =>
    api.get<ActivityDetailView>(`/admin/api/activity/${id}`),
```

- [ ] **Step 3: 타입 체크**

Run: `cd admin-ui && npm run build`
Expected: tsc 통과(타입 에러 없음). vite build까지 성공.

- [ ] **Step 4: 커밋**

```bash
git add admin-ui/src/api/types.ts admin-ui/src/api/activity.ts
git commit -m "feat(activity): 프론트 ActivityDetailView 타입 + fetchDetail API"
```

---

## Task 5: 프론트 — 액션 한글 라벨 + 문장 빌더 (TDD)

**Files:**
- Create: `admin-ui/src/pages/activityLabels.ts`
- Test: `admin-ui/src/pages/activityLabels.test.ts`

- [ ] **Step 1: 실패하는 테스트 작성**

```typescript
import { describe, it, expect } from 'vitest';
import { actionLabel, eventSentence } from './activityLabels';

describe('actionLabel', () => {
  it('매핑된 액션은 한글 라벨', () => {
    expect(actionLabel('API_KEY_ISSUE')).toBe('API 키 발급');
    expect(actionLabel('WEBAUTHN_CONFIG_UPDATED')).toBe('설정 변경');
    expect(actionLabel('ADMIN_LOGIN_FAILED')).toBe('로그인 실패');
  });
  it('매핑 없는 액션은 원문 fallback', () => {
    expect(actionLabel('SOME_UNKNOWN_ACTION')).toBe('SOME_UNKNOWN_ACTION');
  });
});

describe('eventSentence', () => {
  it('행위자·테넌트·대상을 문장으로', () => {
    const s = eventSentence({
      action: 'API_KEY_ISSUE',
      actorEmail: 'admin@acme.com',
      tenantSlug: 'acme',
      targetType: 'API_KEY',
      targetId: 'pk_abcdef123456',
    });
    expect(s).toContain('admin@acme.com');
    expect(s).toContain('acme');
    expect(s).toContain('API 키 발급');
  });
  it('행위자 없으면 system', () => {
    const s = eventSentence({
      action: 'ADMIN_LOGIN_FAILED',
      actorEmail: '',
      tenantSlug: null,
      targetType: null,
      targetId: null,
    });
    expect(s).toContain('로그인 실패');
  });
});
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd admin-ui && npm run test -- activityLabels`
Expected: FAIL — `activityLabels.ts` 없음 / export 없음.

- [ ] **Step 3: activityLabels.ts 구현**

```typescript
// 액션 코드 → 한글 라벨, 그리고 피드 행을 사람이 읽는 문장으로 조립한다.
// 매핑에 없는 코드는 원문 그대로 노출(fallback).

const ACTION_LABELS: Record<string, string> = {
  TENANT_CREATE: '테넌트 생성',
  TENANT_UPDATE: '테넌트 수정',
  CREDENTIAL_REVOKE: 'Credential 폐기',
  CREDENTIAL_REVOKED: 'Credential 폐기',
  CREDENTIAL_REGISTERED: 'Credential 등록',
  CREDENTIAL_AUTHENTICATED: '인증 성공',
  API_KEY_ISSUE: 'API 키 발급',
  API_KEY_ISSUED: 'API 키 발급',
  API_KEY_REVOKE: 'API 키 회수',
  API_KEY_REVOKED: 'API 키 회수',
  SIGNING_KEY_ROTATE: '서명키 회전',
  WEBAUTHN_CONFIG_UPDATED: '설정 변경',
  ATTESTATION_POLICY_UPDATED: 'AAGUID 정책 변경',
  ADMIN_LOGIN: '관리자 로그인',
  ADMIN_LOGIN_FAILED: '로그인 실패',
  SIGNATURE_COUNTER_REGRESSION: '서명 카운터 이상',
  ATTESTATION_TRUST_FAILED: 'Attestation 신뢰 실패',
  MDS_BLOB_SYNC: 'MDS 동기화',
  RETENTION_PURGE: '보존기간 정리',
};

export function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action;
}

export interface EventSentenceInput {
  action: string;
  actorEmail: string;
  tenantSlug: string | null;
  targetType: string | null;
  targetId: string | null;
}

/** "{행위자} 님이 {테넌트} 테넌트에 {대상} {액션}" 형태의 한 줄 문장. */
export function eventSentence(e: EventSentenceInput): string {
  const actor = e.actorEmail && e.actorEmail.trim().length > 0 ? e.actorEmail : 'system';
  const label = actionLabel(e.action);
  const tenant = e.tenantSlug ? `${e.tenantSlug} 테넌트` : '플랫폼';
  const target =
    e.targetType && e.targetId
      ? `${e.targetType} ${e.targetId}`
      : e.targetId ?? '';
  // 대상이 있으면 포함, 없으면 생략.
  return target
    ? `${actor} 님이 ${tenant}에 ${target} · ${label}`
    : `${actor} · ${tenant} · ${label}`;
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `cd admin-ui && npm run test -- activityLabels`
Expected: PASS (6 assertion 통과)

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/activityLabels.ts admin-ui/src/pages/activityLabels.test.ts
git commit -m "feat(activity): 액션 한글 라벨 + 행 문장 빌더 (TDD)"
```

---

## Task 6: 프론트 — 행 문장화를 ActivityPage 피드에 적용

**Files:**
- Modify: `admin-ui/src/pages/ActivityPage.tsx`

기존 피드 행은 `EventTypeBadge`(코드) + `actor {tail(actorId,10)} → subject {tail(subjectId,12)}`(`ActivityPage.tsx:440-442`)를 보여준다. 이것을 `eventSentence`로 교체한다. badge는 한글 라벨로 바꾼다.

- [ ] **Step 1: import 추가**

`ActivityPage.tsx` 상단 import 블록에 추가:

```typescript
import { actionLabel, eventSentence } from './activityLabels';
```

- [ ] **Step 2: EventTypeBadge 라벨을 한글로**

`EventTypeBadge`(`ActivityPage.tsx:119-121`)의 표시 텍스트 `{type}`을 `{actionLabel(type)}`으로 변경:

```typescript
function EventTypeBadge({ type, category }: { type: string; category: string }) {
  return <span className={TONE_BADGE[eventTone(type, category)]} style={{ fontSize: 10 }}>{actionLabel(type)}</span>;
}
```

- [ ] **Step 3: 행 본문을 문장으로 교체**

`ActivityPage.tsx:440-442`의 다음 블록:

```typescript
                  <div className="muted mono" style={{ fontSize: 11, marginTop: 3 }}>
                    actor {tail(e.actorId, 10)} → subject {tail(e.subjectId, 12)}
                  </div>
```

을 아래로 교체:

```typescript
                  <div className="muted" style={{ fontSize: 11, marginTop: 3 }}>
                    {eventSentence({
                      action: e.type,
                      actorEmail: e.actorId ?? '',
                      tenantSlug: e.tenantSlug,
                      targetType: null,
                      targetId: e.subjectId,
                    })}
                  </div>
```

> `RecentActivityEvent`는 `actorId`에 actorEmail을 담고 있다(`recentActivityAdapter.ts:28` — `actorId: e.actorEmail ?? null`). `targetType`은 어댑터에 없으므로 null로 두고 `targetId`(subjectId)만 사용한다. `tail` import가 다른 곳에서 안 쓰이면 남겨둬도 무방(badge 행에서 여전히 사용).

- [ ] **Step 4: 타입 체크 + 기존 테스트**

Run: `cd admin-ui && npm run build && npm run test`
Expected: tsc 통과, 기존 테스트(smoke + activityLabels) 통과.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/ActivityPage.tsx
git commit -m "feat(activity): 피드 행을 한글 문장 + 라벨로 표시"
```

---

## Task 7: 프론트 — 단일 필터 바 (헤더 클라이언트 필터 제거 + 테넌트 드롭다운 + 액션 검색)

**Files:**
- Modify: `admin-ui/src/pages/ActivityPage.tsx`

현재 필터는 두 곳: 피드 헤더의 `filter`(all/mutations/failures, `ChipTab` 3개, `ActivityPage.tsx:392-402`)와 우측 "카테고리 필터" 카드(`categoryFilter`, `ActivityPage.tsx:557-581`). 헤더 `filter`를 제거하고, 상단에 카테고리 칩 + 테넌트 드롭다운 + 액션 검색을 단일 바로 둔다.

- [ ] **Step 1: 테넌트 목록 로드 + 신규 state**

import에 추가:

```typescript
import { tenantsApi } from '@/api/tenants';
import type { Tenant } from '@/api/designTypes';
```

`ActivityPage` 컴포넌트 상단 state 영역(`const [filter, setFilter] = ...` 근처)에서 `filter` 관련 state를 **제거**하고 다음을 추가:

```typescript
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [actionQuery, setActionQuery] = useState('');
  // tenantFilter 는 URL ?tenantId= 에서 초기화하되, 드롭다운으로 갱신 가능하게 state 화
  const [selectedTenant, setSelectedTenant] = useState<string | undefined>(
    searchParams.get('tenantId') ?? undefined,
  );
```

> 기존 `const tenantFilter = searchParams.get('tenantId') ?? undefined;`(line 187)는 제거하고, 이후 `tenantFilter`를 참조하던 모든 곳(`fetchOnce`, polling deps, `handleRefresh`, "더 보기")을 `selectedTenant`로 바꾼다.

테넌트 목록 1회 로드 useEffect 추가:

```typescript
  useEffect(() => {
    tenantsApi.list().then(setTenants).catch(() => setTenants([]));
  }, []);
```

- [ ] **Step 2: polling effect와 fetch 호출의 tenantFilter → selectedTenant 치환**

`useEffect`(line 204-242)의 deps `[categoryFilter, tenantFilter]`를 `[categoryFilter, selectedTenant]`로, 그 안 `activityApi.fetch(..., tenantFilter)`를 `..., selectedTenant)`로. `handleRefresh`(line 273-291)와 "이전 24시간 더 보기"(line 463-468)의 `tenantFilter`도 동일 치환.

- [ ] **Step 3: 필터 클라이언트 필터링 로직 교체**

기존 `filtered`(line 249-257, `filter`로 mutations/failures 분기)를 액션 검색 기반으로 교체:

```typescript
  const filtered = useMemo(
    () =>
      displayEvents.filter((e) => {
        if (!actionQuery.trim()) return true;
        const q = actionQuery.trim().toUpperCase();
        return e.type.toUpperCase().includes(q) || actionLabel(e.type).includes(actionQuery.trim());
      }),
    [displayEvents, actionQuery],
  );
```

`failureCount`/`mutationCount`(line 259-266)가 다른 곳에서 안 쓰이면 제거. 쓰이면 유지.

- [ ] **Step 4: 헤더의 ChipTab 3개(전체/운영 액션/보안 실패) 제거**

`ActivityPage.tsx:392-402`의 `<div className="row">...ChipTab(filter)...</div>` 블록과 그 위 `card__sub`의 필터 설명(line 381-390)을 제거한다. 카드 헤더는 제목만 남긴다.

- [ ] **Step 5: 상단 단일 필터 바 추가**

KPI 카드(`grid-4`, line 353-374) **아래**, `grid-2`(line 376) **위**에 필터 바를 삽입:

```typescript
      <div className="card" style={{ marginBottom: 16, padding: '10px 14px' }}>
        <div className="row" style={{ gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
          <span className="muted" style={{ fontSize: 12, fontWeight: 600 }}>카테고리</span>
          <ChipTab active={categoryFilter === 'all'} onClick={() => setCategoryFilter('all')}>전체</ChipTab>
          <ChipTab active={categoryFilter === 'ops'} onClick={() => setCategoryFilter('ops')}>운영</ChipTab>
          <ChipTab active={categoryFilter === 'security'} onClick={() => setCategoryFilter('security')}>보안</ChipTab>

          <span className="muted" style={{ fontSize: 12, fontWeight: 600, marginLeft: 8 }}>테넌트</span>
          <select
            value={selectedTenant ?? ''}
            onChange={(ev) => setSelectedTenant(ev.target.value || undefined)}
            style={{ padding: '4px 8px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text)', fontSize: 12 }}
          >
            <option value="">전체</option>
            {tenants.map((t) => (
              <option key={t.id} value={t.id}>{t.name} ({t.slug})</option>
            ))}
          </select>

          <span className="muted" style={{ fontSize: 12, fontWeight: 600, marginLeft: 8 }}>액션</span>
          <input
            value={actionQuery}
            onChange={(ev) => setActionQuery(ev.target.value)}
            placeholder="액션 검색…"
            style={{ padding: '4px 8px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text)', fontSize: 12, minWidth: 140 }}
          />
        </div>
      </div>
```

- [ ] **Step 6: 우측 "카테고리 필터" 카드 제거**

`ActivityPage.tsx:557-581`의 카테고리 필터 카드 `<div className="card">...</div>`를 제거(이미 상단 바로 이동). "활발한 Tenant" 카드는 유지.

- [ ] **Step 7: "활발한 Tenant" 클릭 → 필터로 변경**

`handleOpenTenant`(line 268-271)는 상세로 navigate한다. "활발한 Tenant" 항목 클릭 핸들러(line 488-490 `onClick={() => handleOpenTenant(t.tenantId)}`)를 필터 설정으로 변경:

```typescript
                  onClick={() => setSelectedTenant(t.tenantId)}
```

> 상세 이동이 필요하면 별도 작은 링크/아이콘으로 분리할 수 있으나, 이번 범위에선 클릭=필터로 단순화. `handleOpenTenant`는 피드 행의 테넌트 버튼(line 430)에서 여전히 사용하므로 함수 자체는 유지.

- [ ] **Step 8: 타입 체크 + 테스트**

Run: `cd admin-ui && npm run build && npm run test`
Expected: tsc 통과(미사용 변수 에러 없게 정리), 테스트 통과.

> tsc가 미사용 `filter`/`failureCount`/`tail` 등으로 에러를 내면 해당 잔재를 제거한다.

- [ ] **Step 9: 커밋**

```bash
git add admin-ui/src/pages/ActivityPage.tsx
git commit -m "feat(activity): 단일 필터 바(카테고리·테넌트·액션) + 헤더 중복 필터 제거"
```

---

## Task 8: 프론트 — 행 클릭 상세 패널 (단건 조회 + payload diff)

**Files:**
- Modify: `admin-ui/src/pages/ActivityPage.tsx`

행 클릭 시 우측에 상세 패널을 띄우고 `activityApi.fetchDetail(id)`로 payload를 로드한다. payload에 `before`/`after`가 있으면 diff로, 아니면 pretty JSON으로 표시(AuditTab PayloadDialog의 `<pre>+JSON.stringify` 패턴 재사용).

- [ ] **Step 1: import + state**

import에 추가:

```typescript
import type { ActivityDetailView } from '@/api/types';
```

state 추가:

```typescript
  const [detail, setDetail] = useState<ActivityDetailView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  function openDetail(id: string) {
    setDetailLoading(true);
    setDetail(null);
    activityApi
      .fetchDetail(id)
      .then((d) => setDetail(d))
      .catch(() => toast({ kind: 'err', title: '상세 로드 실패' }))
      .finally(() => setDetailLoading(false));
  }
```

- [ ] **Step 2: 행 클릭을 openDetail로 연결**

기존 행 끝 `>` 버튼(`ActivityPage.tsx:444-448`)의 toast 동작을 `openDetail(e.id)`로 교체:

```typescript
                <button className="btn btn--ghost btn--xs" onClick={() => openDetail(e.id)}>
                  <Icons.ChevronRight size={12} />
                </button>
```

- [ ] **Step 3: payload diff 렌더 헬퍼 + 상세 패널 컴포넌트**

파일 내(컴포넌트 밖) 헬퍼 추가:

```typescript
function parsePayload(raw: string): Record<string, unknown> | null {
  try { return JSON.parse(raw) as Record<string, unknown>; } catch { return null; }
}
```

상세 패널 컴포넌트를 파일 내에 추가:

```typescript
function DetailPanel({
  detail, loading, onClose,
}: { detail: ActivityDetailView | null; loading: boolean; onClose: () => void }) {
  const payload = detail ? parsePayload(detail.payload) : null;
  const before = payload && typeof payload.before === 'object' ? payload.before as Record<string, unknown> : null;
  const after = payload && typeof payload.after === 'object' ? payload.after as Record<string, unknown> : null;
  return (
    <div className="card" style={{ padding: 14 }}>
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <h3 className="card__title">상세</h3>
        <button className="btn btn--ghost btn--xs" onClick={onClose}>닫기</button>
      </div>
      {loading && <div className="muted" style={{ fontSize: 12 }}>불러오는 중…</div>}
      {!loading && !detail && <div className="muted" style={{ fontSize: 12 }}>행을 클릭하면 상세가 표시됩니다.</div>}
      {!loading && detail && (
        <div style={{ fontSize: 12 }}>
          <div style={{ fontWeight: 600, marginBottom: 4 }}>{actionLabel(detail.action)}</div>
          <div className="muted" style={{ marginBottom: 8 }}>{new Date(detail.createdAt).toLocaleString()}</div>
          <div><b>누가</b> {detail.actorEmail || 'system'}</div>
          <div><b>테넌트</b> {detail.tenantSlug ?? '플랫폼'}</div>
          <div style={{ marginBottom: 8 }}><b>대상</b> {detail.targetType ?? '—'} {detail.targetId ?? ''}</div>
          <div className="label" style={{ marginBottom: 4 }}>어떻게 바뀜</div>
          {before && after ? (
            <pre style={{ margin: 0, padding: 10, background: 'var(--surface-3)', borderRadius: 8, fontSize: 11, fontFamily: 'var(--mono)', overflow: 'auto', color: 'var(--text)' }}>
{Object.keys({ ...before, ...after }).map((k) =>
  `- ${k}: ${JSON.stringify(before[k])}\n+ ${k}: ${JSON.stringify(after[k])}`).join('\n')}
            </pre>
          ) : (
            <pre style={{ margin: 0, padding: 10, background: 'var(--surface-3)', borderRadius: 8, fontSize: 11, fontFamily: 'var(--mono)', overflow: 'auto', color: 'var(--text)' }}>
{payload ? JSON.stringify(payload, null, 2) : detail.payload}
            </pre>
          )}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 4: 우측 컬럼에 DetailPanel 배치**

우측 `stack-4` 컬럼(line 481, "활발한 Tenant" 카드가 있는 곳) 최상단에 상세 패널을 추가한다. detail이 있을 때만 노출:

```typescript
        <div className="stack-4">
          {(detail || detailLoading) && (
            <DetailPanel detail={detail} loading={detailLoading} onClose={() => setDetail(null)} />
          )}
          {/* 기존 활발한 Tenant 카드 ... */}
```

- [ ] **Step 5: 타입 체크 + 테스트**

Run: `cd admin-ui && npm run build && npm run test`
Expected: tsc 통과, 테스트 통과.

- [ ] **Step 6: 커밋**

```bash
git add admin-ui/src/pages/ActivityPage.tsx
git commit -m "feat(activity): 행 클릭 상세 패널 — 단건 조회 payload diff/JSON 표시"
```

---

## Task 9: 통합 검증 (수동 dogfooding)

**Files:** (없음 — 검증만)

- [ ] **Step 1: 백엔드 전체 Activity 테스트**

Run: `./gradlew :admin-app:test --tests ActivityControllerIT`
Expected: PASS (피드 + 단건 3개 전부)

- [ ] **Step 2: 프론트 빌드 + 테스트 + 린트**

Run: `cd admin-ui && npm run build && npm run test && npm run lint`
Expected: 전부 통과.

- [ ] **Step 3: 로컬 기동 dogfooding (선택)**

local 프로필로 admin-app 기동(메모리 `project_local_admin_boot_rpadmin` 절차: `SPRING_PROFILES_ACTIVE=local` 환경변수, V8 repair 필요 시 적용), admin-ui dev(5173). alice(alice-temp-pw)로 로그인 → Activity 메뉴 → 다음 확인:
  - 상단 필터 바: 카테고리 칩 / 테넌트 드롭다운 / 액션 검색이 한 줄에
  - 피드 행이 한글 문장 + 한글 라벨
  - 테넌트 드롭다운에서 demo-rp 선택 시 피드가 그 테넌트로 좁혀짐
  - "활발한 Tenant" 클릭 시 상세 이동이 아니라 필터 적용
  - 행 `>` 클릭 시 우측 상세 패널에 payload(가능하면 before→after diff) 표시

> demo-rp에 payload 있는 audit_log가 없으면 메모리 절차의 더미 INSERT로 샘플 생성(WEBAUTHN_CONFIG_UPDATED with before/after).

- [ ] **Step 4: 최종 커밋(검증 메모 없으면 생략)**

검증에서 수정이 필요하면 해당 Task로 돌아가 고치고 재검증. 수정 없으면 이 Task는 커밋 없음.

---

## Self-Review 메모

- **스펙 커버리지**: ① 필터 통합=Task 7, ② 행 문장화=Task 5·6 + 상세 payload=Task 1·2·3·8, ③ 테넌트 필터=Task 7(드롭다운)·Task 1-3(서버는 이미 지원). RP_ADMIN 제외=백엔드 `@PreAuthorize` 그대로(변경 없음). ✓
- **타입 일관성**: 백엔드 `ActivityDetailView`(actorId, payload 포함) ↔ 프론트 `ActivityDetailView` 인터페이스 1:1. `fetchDetail` 반환 타입 일치. ✓
- **단건 조회 권한**: PLATFORM_OPERATOR 전용(`hasRole`)으로 피드와 동일 경계. RP_ADMIN 403 테스트로 회귀 방지. ✓
- **주의(구현 시 확인)**: `ErrorCode.NOT_FOUND` 실제 상수명(Task 2 Step 3), `ApiResponse` envelope의 data 래핑 여부(Task 3 readJson), RAW(16)↔UUID 문자열 대응(Task 3 Step 1).
