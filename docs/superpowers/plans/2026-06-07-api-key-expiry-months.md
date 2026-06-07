# API Key 월 단위 만료일 선택 + 어드민 만료 표시 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 어드민에서 API Key 발급 시 만료 기간(6/12/24/36개월·무기한, 기본 24개월)을 선택하고, API Keys 목록에 만료일을 EXPIRED 배지와 함께 표시한다.

**Architecture:** 만료 인프라(ApiKey.expiresAt 컬럼, isActive(now) 만료 검사, ApiKeyView.expiresAt 노출)는 이미 존재한다. 발급 요청 DTO에 expiresInMonths를 추가하고 issue()에서 `clock.instant().plusMonths(N)`로 expires_at을 계산·저장한다. 프론트는 발급 다이얼로그에 만료 선택 UI, 목록에 만료일 컬럼을 추가한다. 무기한=null(하위호환), DB 마이그레이션 없음.

**Tech Stack:** Java 17, Spring Boot 3.5, Bean Validation, JUnit5 + Mockito + AssertJ, React/TS + Vitest.

작업 위치: 워크트리 `.worktrees/api-key-expiry-months`, 브랜치 `feat/api-key-expiry-months`. 모든 명령/커밋은 이 워크트리에서 실행.

---

## 파일 구조

- **Modify** `admin-app/.../apikey/ApiKeyAdminDto.java` — CreateRequest에 expiresInMonths, CreateResponse에 expiresAt
- **Modify** `admin-app/.../apikey/ApiKeyAdminService.java` — issue()에서 expireAt 계산·설정·audit·응답
- **Modify** `admin-app/.../apikey/ApiKeyAdminServiceTest.java` — 3-arg→4-arg, 만료 계산 테스트 추가
- **Modify** `admin-app/.../apikey/ApiKeyCreateRequestValidationTest.java` — 3-arg→4-arg, @Min/@Max 케이스
- **Modify** `admin-ui/src/api/types.ts` — ApiKeyCreateRequest에 expiresInMonths, ApiKeyCreateResponse에 expiresAt
- **Modify** `admin-ui/src/api/designTypes.ts` — ApiKey 타입에 expiresAt
- **Modify** `admin-ui/src/api/apiKeys.ts` — adapt expiresAt 매핑, create에 expiresInMonths
- **Modify** `admin-ui/src/api/apiKeys.test.ts` — 4-arg create, expiresInMonths 전달 검증
- **Modify** `admin-ui/src/pages/tenant/ApiKeysTab.tsx` — NewKeyDialog 만료 선택, 목록 만료일 컬럼, IssuedKeyModal·handleIssue

> 주의: 엔티티 메서드는 `expireAt(Instant)`이고 만료 검사는 `isActive(Instant)`다(`setExpiresAt`/`isValid` 아님). ApiKey 엔티티는 변경하지 않는다(기존 expireAt 재사용).

---

## Task 1: 백엔드 DTO — expiresInMonths 요청 + expiresAt 응답

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminDto.java`

- [ ] **Step 1: CreateRequest에 expiresInMonths, CreateResponse에 expiresAt 추가**

import에 `jakarta.validation.constraints.Max`, `jakarta.validation.constraints.Min` 추가(상단 validation import 블록).

`ApiKeyCreateRequest`를 다음으로 교체:
```java
    public record ApiKeyCreateRequest(
            @NotNull UUID tenantId,
            @NotBlank @Size(max = 64) String name,
            @NotEmpty Set<@NotBlank @Size(max = 32) String> scopes,
            @Min(1) @Max(36) Integer expiresInMonths   // null = 무기한
    ) {}
```

`ApiKeyCreateResponse`를 다음으로 교체(expiresAt 추가):
```java
    public record ApiKeyCreateResponse(
            UUID id,
            String plainText,          // ONE-TIME — only returned at issue
            String prefix,
            Set<String> scopes,
            Instant expiresAt          // null = 무기한
    ) {}
```
(`Instant`는 이미 import됨.)

- [ ] **Step 2: 컴파일 확인 (호출처가 깨지는지 노출)**

Run: `cd .worktrees/api-key-expiry-months && ./gradlew :admin-app:compileJava -q`
Expected: FAIL — `ApiKeyAdminService.issue()`의 `new ApiKeyCreateResponse(saved.getId(), prefix+secret, prefix, normalized)`가 인자 4개로 호출돼 5개 시그니처와 안 맞음. 이는 Task 2에서 고친다. (DTO만 바꾼 시점이라 예상된 실패 — 다음 task와 함께 커밋.)

> 이 task는 Task 2와 같은 컴파일 단위라, 독립 커밋하지 않고 Task 2 완료 후 함께 커밋한다. Step 3 없음.

---

## Task 2: 백엔드 issue() — 만료 계산·저장·응답·audit

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java`

- [ ] **Step 1: issue() 본문에 만료 설정 + 응답/audit 반영**

import에 `java.time.ZoneOffset` 추가(상단 `java.time.Duration` 옆).

`ApiKey key = new ApiKey(...)` ~ `repo.saveAndFlush(key)` 블록(현재 113~117행)을 다음으로 교체:
```java
        ApiKey key = new ApiKey(req.tenantId(), prefix, hash, req.name());
        for (String scope : normalized) {
            key.addScope(scope);
        }
        // 월 단위 만료: now + N개월 (UTC). null 이면 무기한(expires_at=NULL, 기존 키와 동일).
        Instant expiresAt = null;
        if (req.expiresInMonths() != null) {
            expiresAt = clock.instant()
                    .atZone(ZoneOffset.UTC)
                    .plusMonths(req.expiresInMonths())
                    .toInstant();
            key.expireAt(expiresAt);
        }
        ApiKey saved = repo.saveAndFlush(key);
```

audit payload 블록(현재 119~124행)의 `payload.put("scopes", normalized);` 다음 줄에 추가:
```java
        payload.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
```

응답 생성(현재 134~135행)을 다음으로 교체:
```java
        return new ApiKeyAdminDto.ApiKeyCreateResponse(
                saved.getId(), prefix + secret, prefix, normalized, expiresAt);
```

- [ ] **Step 2: admin-app 컴파일 확인**

Run: `cd .worktrees/api-key-expiry-months && ./gradlew :admin-app:compileJava -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit (Task 1 DTO + Task 2 서비스 함께)**

```bash
cd .worktrees/api-key-expiry-months
git add admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminDto.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java
git commit -m "feat(admin): API key 발급 시 expiresInMonths 로 만료일 설정"
```

---

## Task 3: 백엔드 서비스 테스트 — 만료 계산 검증 + 기존 호출 4-arg화

**Files:**
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminServiceTest.java`

- [ ] **Step 1: 기존 3-arg ApiKeyCreateRequest 호출을 4-arg(null)로 갱신**

이 파일에는 `new ApiKeyAdminDto.ApiKeyCreateRequest(TENANT_UUID, "...", Set.of(...))` 형태 호출이 5곳 있다(테스트 메서드: issueProducesPlainTextAndPersistsHash, issueAppendsAuditWithoutSecret, issueRetriesOnPrefixCollision, issueNormalizesScopeCaseBeforePersisting, issueRejectsUnknownScope_withInvalidInputError, issueRejectsSuspendedTenant_withTenantSuspendedError). 각 호출의 마지막 인자로 `, null`을 추가해 무기한으로 둔다. 예:
```java
new ApiKeyAdminDto.ApiKeyCreateRequest(
        TENANT_UUID, "primary", java.util.Set.of("registration", "authentication"), null)
```
(scopes 인자 뒤에 `, null` 추가 — 6개 호출 전부.)

- [ ] **Step 2: 만료 계산 테스트 추가**

클래스 끝(마지막 `}` 직전)에 추가. 고정 Clock은 `2026-06-01T00:00:00Z`이므로 +24개월 = `2028-06-01T00:00:00Z`:
```java
    @Test
    void issueWithExpiresInMonthsSetsExpiresAt() {
        when(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty());

        ApiKeyAdminDto.ApiKeyCreateResponse resp = service.issue(
                new ApiKeyAdminDto.ApiKeyCreateRequest(
                        TENANT_UUID, "primary", java.util.Set.of("registration"), 24),
                ACTOR_UUID, "alice@example.com");

        // 응답에 만료일 노출
        assertThat(resp.expiresAt()).isEqualTo(Instant.parse("2028-06-01T00:00:00Z"));

        // 저장된 키에 만료일 설정됨
        ArgumentCaptor<ApiKey> keyCaptor = ArgumentCaptor.forClass(ApiKey.class);
        verify(repo).saveAndFlush(keyCaptor.capture());
        assertThat(keyCaptor.getValue().getExpiresAt()).isEqualTo(Instant.parse("2028-06-01T00:00:00Z"));
    }

    @Test
    void issueWithNullExpiresInMonthsLeavesNoExpiry() {
        when(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty());

        ApiKeyAdminDto.ApiKeyCreateResponse resp = service.issue(
                new ApiKeyAdminDto.ApiKeyCreateRequest(
                        TENANT_UUID, "primary", java.util.Set.of("registration"), null),
                ACTOR_UUID, "alice@example.com");

        assertThat(resp.expiresAt()).isNull();
        ArgumentCaptor<ApiKey> keyCaptor = ArgumentCaptor.forClass(ApiKey.class);
        verify(repo).saveAndFlush(keyCaptor.capture());
        assertThat(keyCaptor.getValue().getExpiresAt()).isNull();
    }

    @Test
    void issueAuditPayloadContainsExpiresAt() {
        when(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty());

        service.issue(new ApiKeyAdminDto.ApiKeyCreateRequest(
                        TENANT_UUID, "primary", java.util.Set.of("registration"), 6),
                ACTOR_UUID, "alice@example.com");

        ArgumentCaptor<AuditAppendRequest> auditCaptor = ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(auditCaptor.capture());
        // 6개월 후 = 2026-12-01T00:00:00Z
        assertThat(auditCaptor.getValue().payload().get("expiresAt"))
                .isEqualTo("2026-12-01T00:00:00Z");
    }
```

- [ ] **Step 3: 테스트 실행**

Run: `cd .worktrees/api-key-expiry-months && ./gradlew :admin-app:test --tests "com.crosscert.passkey.admin.apikey.ApiKeyAdminServiceTest"`
Expected: PASS (기존 + 신규 3개).

- [ ] **Step 4: Commit**

```bash
cd .worktrees/api-key-expiry-months
git add admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminServiceTest.java
git commit -m "test(admin): API key 만료 계산(plusMonths)·무기한·audit 검증"
```

---

## Task 4: 백엔드 검증 테스트 — @Min/@Max + 4-arg화

**Files:**
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyCreateRequestValidationTest.java`

- [ ] **Step 1: req 헬퍼 4-arg화 + expiresInMonths 헬퍼 추가**

`req(String name)` 헬퍼(현재 scopes까지 3-arg)를 4-arg로 갱신하고, 만료 검증용 헬퍼를 추가:
```java
    private ApiKeyAdminDto.ApiKeyCreateRequest req(String name) {
        return new ApiKeyAdminDto.ApiKeyCreateRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                name,
                Set.of("registration"),
                12);
    }

    private ApiKeyAdminDto.ApiKeyCreateRequest reqMonths(Integer months) {
        return new ApiKeyAdminDto.ApiKeyCreateRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "valid-name",
                Set.of("registration"),
                months);
    }
```

- [ ] **Step 2: @Min/@Max 케이스 추가**

import에 `jakarta.validation.constraints.Max`, `jakarta.validation.constraints.Min` 추가. 클래스 끝(마지막 `}` 직전)에 추가:
```java
    @Test
    void nullExpiresInMonthsIsValid() {
        assertThat(validator.validate(reqMonths(null))).isEmpty();
    }

    @Test
    void expiresInMonths24IsValid() {
        assertThat(validator.validate(reqMonths(24))).isEmpty();
    }

    @Test
    void expiresInMonths0ViolatesMin() {
        var violations = validator.validate(reqMonths(0));
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("expiresInMonths")
                        && v.getConstraintDescriptor().getAnnotation() instanceof Min);
    }

    @Test
    void expiresInMonths37ViolatesMax() {
        var violations = validator.validate(reqMonths(37));
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("expiresInMonths")
                        && v.getConstraintDescriptor().getAnnotation() instanceof Max);
    }
```

- [ ] **Step 3: 테스트 실행**

Run: `cd .worktrees/api-key-expiry-months && ./gradlew :admin-app:test --tests "com.crosscert.passkey.admin.apikey.ApiKeyCreateRequestValidationTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
cd .worktrees/api-key-expiry-months
git add admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyCreateRequestValidationTest.java
git commit -m "test(admin): expiresInMonths @Min/@Max 검증 테스트"
```

---

## Task 5: 프론트 타입 — expiresInMonths / expiresAt

**Files:**
- Modify: `admin-ui/src/api/types.ts`
- Modify: `admin-ui/src/api/designTypes.ts`

- [ ] **Step 1: types.ts — 요청에 expiresInMonths, 응답에 expiresAt**

`ApiKeyCreateRequest` 인터페이스(현재 `expiresAt?: string` 포함)에 필드 추가:
```typescript
  expiresInMonths?: number | null;   // null/생략 = 무기한
```
`ApiKeyCreateResponse` 인터페이스(현재 id/plainText/prefix/scopes)에 추가:
```typescript
  expiresAt?: string | null;
```
(`ApiKeyView`의 `expiresAt?: string`은 이미 존재 — 변경 없음.)

- [ ] **Step 2: designTypes.ts — ApiKey 타입에 expiresAt**

`ApiKey` 타입(현재 id/prefix/name/status/createdAt/lastUsedAt/scopes)에 추가:
```typescript
  expiresAt: string | null;
```

- [ ] **Step 3: 타입 컴파일 확인(빌드는 Task 7에서; 여기선 tsc만)**

Run: `cd .worktrees/api-key-expiry-months/admin-ui && npx tsc --noEmit 2>&1 | head -20`
Expected: 에러가 나면 adapt/create/ApiKeysTab가 ApiKey.expiresAt를 아직 안 채워서일 수 있음 — 그 에러는 Task 6/7에서 해소된다. types.ts/designTypes.ts 자체 문법 에러만 없으면 OK. (이 task는 다음 task와 함께 커밋 — Step 4 없음.)

---

## Task 6: 프론트 API — adapt 매핑 + create expiresInMonths

**Files:**
- Modify: `admin-ui/src/api/apiKeys.ts`

- [ ] **Step 1: adapt에 expiresAt 매핑 추가**

`adapt` 함수의 반환 객체(현재 scopes로 끝남)에 추가:
```typescript
    expiresAt: s.expiresAt ?? null,
```

- [ ] **Step 2: create 시그니처에 expiresInMonths 추가 + body 포함 + 반환 key에 expiresAt**

`create`를 다음으로 교체:
```typescript
  create: async (
    tenantId: string,
    name: string,
    scopes: string[],
    expiresInMonths: number | null,
  ): Promise<{ key: ApiKey; plaintext: string }> => {
    const body: ApiKeyCreateRequest = { tenantId, name, scopes, expiresInMonths };
    const res = await api.post<ApiKeyCreateResponse>('/admin/api/api-keys', body);
    const key: ApiKey = {
      id: res.id,
      prefix: res.prefix,
      name,
      status: 'ACTIVE',
      createdAt: new Date().toISOString(),
      lastUsedAt: null,
      scopes: res.scopes ?? scopes,
      expiresAt: res.expiresAt ?? null,
    };
    return { key, plaintext: res.plainText };
  },
```

- [ ] **Step 3: apiKeys.test.ts — create 호출 4-arg화 + expiresInMonths 검증**

`admin-ui/src/api/apiKeys.test.ts`의 기존 create 테스트에서 `apiKeysApi.create('t1', 'prod', ['registration', 'authentication'])`를 `apiKeysApi.create('t1', 'prod', ['registration', 'authentication'], 24)`로 바꾸고, body 검증 블록에 추가:
```typescript
    expect(body.expiresInMonths).toBe(24);
```
또한 그 테스트의 mock envelope에 `expiresAt`을 넣어도 되지만 필수 아님(create는 expiresAt 없어도 null 처리). 새 테스트 1개 추가:
```typescript
  it('create with null expiresInMonths sends null (무기한)', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      envelope({ id: 'k1', prefix: 'pk_x', plainText: 'pk_x.secret', scopes: ['registration'], expiresAt: null }),
    );
    await apiKeysApi.create('t1', 'prod', ['registration'], null);
    const [, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    const body = JSON.parse(init.body);
    expect(body.expiresInMonths).toBeNull();
  });
```

- [ ] **Step 4: 프론트 테스트 실행**

Run: `cd .worktrees/api-key-expiry-months/admin-ui && npx vitest run src/api/apiKeys.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit (Task 5 타입 + Task 6 API 함께)**

```bash
cd .worktrees/api-key-expiry-months
git add admin-ui/src/api/types.ts admin-ui/src/api/designTypes.ts \
        admin-ui/src/api/apiKeys.ts admin-ui/src/api/apiKeys.test.ts
git commit -m "feat(admin-ui): apiKeys 타입/매핑에 만료(expiresInMonths/expiresAt) 추가"
```

---

## Task 7: 프론트 UI — 발급 다이얼로그 만료 선택 + 목록 만료일 컬럼 + 모달

**Files:**
- Modify: `admin-ui/src/pages/tenant/ApiKeysTab.tsx`

- [ ] **Step 1: 만료 프리셋 상수 + 만료일 계산 헬퍼 추가**

파일 상단(컴포넌트 밖, 기존 `SCOPE_OPTIONS`/`NAME_MAX` 상수 근처)에 추가:
```tsx
// 만료 프리셋(개월). null = 무기한.
const EXPIRY_OPTIONS: { months: number | null; label: string }[] = [
  { months: 6, label: '6개월' },
  { months: 12, label: '12개월' },
  { months: 24, label: '24개월' },
  { months: 36, label: '36개월' },
  { months: null, label: '무기한' },
];

// now + N개월의 ISO 날짜(YYYY-MM-DD) 미리보기. null이면 null.
function previewExpiry(months: number | null): string | null {
  if (months == null) return null;
  const d = new Date();
  d.setMonth(d.getMonth() + months);
  return d.toISOString().slice(0, 10);
}

// 만료 상태 판정. 'none' | 'expired' | 'soon'(30일 이내) | 'ok'
function expiryState(expiresAt: string | null): 'none' | 'expired' | 'soon' | 'ok' {
  if (!expiresAt) return 'none';
  const exp = new Date(expiresAt).getTime();
  const now = Date.now();
  if (exp <= now) return 'expired';
  if (exp - now <= 30 * 24 * 60 * 60 * 1000) return 'soon';
  return 'ok';
}
```

- [ ] **Step 2: handleIssue 시그니처에 expiresInMonths 전달**

`handleIssue`(현재 `(name, scopes)`)를 교체:
```tsx
  async function handleIssue(name: string, scopes: string[], expiresInMonths: number | null) {
    try {
      const result = await apiKeysApi.create(tenant.id, name, scopes, expiresInMonths);
      setShowNew(false);
      setIssued(result);
      await reload();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '발급 실패', message: msg });
    }
  }
```

- [ ] **Step 3: 목록 표에 만료일 컬럼 추가**

헤더(현재 `<th>생성</th>` 다음)에 추가:
```tsx
              <th>만료일</th>
```

각 행에서 생성일 셀(`<td><span className="muted">{fmtDateTime(k.createdAt)}</span></td>`) 다음에 만료 셀 추가:
```tsx
                <td>
                  {(() => {
                    const st = expiryState(k.expiresAt);
                    if (st === 'none') return <span className="faint">무기한</span>;
                    if (st === 'expired') return (
                      <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
                        <span className="badge badge--danger">EXPIRED</span>
                        <span className="faint" style={{ fontSize: 11 }}>{fmtDateTime(k.expiresAt!)}</span>
                      </span>
                    );
                    return (
                      <span className={st === 'soon' ? '' : 'muted'} style={st === 'soon' ? { color: 'var(--warning)' } : undefined}>
                        {fmtDateTime(k.expiresAt!)}
                      </span>
                    );
                  })()}
                </td>
```

- [ ] **Step 4: NewKeyDialog에 만료 선택 UI**

`NewKeyDialog`의 props 타입과 onIssue 시그니처를 교체:
```tsx
function NewKeyDialog({ open, onClose, onIssue }: {
  open: boolean;
  onClose: () => void;
  onIssue: (name: string, scopes: string[], expiresInMonths: number | null) => void;
}) {
  const [name, setName] = useState('');
  const [scopes, setScopes] = useState<string[]>(['registration', 'authentication']);
  const [expiresInMonths, setExpiresInMonths] = useState<number | null>(24); // 기본 24개월

  function toggle(v: string) {
    setScopes((prev) => prev.includes(v) ? prev.filter((s) => s !== v) : [...prev, v]);
  }
  function submit() {
    if (!name || scopes.length === 0) return;
    onIssue(name, scopes, expiresInMonths);
    setName('');
    setScopes(['registration', 'authentication']);
    setExpiresInMonths(24);
  }
```

scope 선택 블록(`</div>` 으로 끝나는 권한 범위 div) 다음, Dialog 닫기 전에 만료 선택 블록 추가:
```tsx
      <div style={{ marginTop: 14 }}>
        <label className="label">만료 기간</label>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 6 }}>
          {EXPIRY_OPTIONS.map((o) => {
            const selected = expiresInMonths === o.months;
            return (
              <button
                key={o.label}
                type="button"
                onClick={() => setExpiresInMonths(o.months)}
                style={{
                  padding: '6px 12px', borderRadius: 8,
                  border: `1px solid ${selected ? 'var(--accent)' : 'var(--border)'}`,
                  background: selected ? 'var(--accent-soft)' : 'var(--surface)',
                  color: selected ? 'var(--accent)' : 'var(--text)',
                  fontWeight: selected ? 600 : 500, cursor: 'pointer', fontSize: 13,
                }}
              >
                {o.label}
              </button>
            );
          })}
        </div>
        <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>
          {expiresInMonths == null
            ? '만료 없음 — 키가 무기한으로 유효합니다.'
            : `만료일: ${previewExpiry(expiresInMonths)}`}
        </div>
      </div>
```

- [ ] **Step 5: IssuedKeyModal에 만료일 표시**

`IssuedKeyModal`의 발급 정보 블록(이름/prefix를 보여주는 `<div style={{ display: 'flex', gap: 12, ... }}>` 영역) 안, prefix 셀 다음에 만료 셀 추가:
```tsx
          <div>
            <div className="muted" style={{ fontSize: 12 }}>만료</div>
            <div style={{ fontSize: 12 }}>{issued.key.expiresAt ? fmtDateTime(issued.key.expiresAt) : <span className="faint">무기한</span>}</div>
          </div>
```

- [ ] **Step 6: 빌드 확인**

Run: `cd .worktrees/api-key-expiry-months/admin-ui && npm run build 2>&1 | tail -6`
Expected: 빌드 성공(타입 오류 없음).

- [ ] **Step 7: 프론트 전체 테스트**

Run: `cd .worktrees/api-key-expiry-months/admin-ui && npx vitest run 2>&1 | tail -6`
Expected: 전부 PASS.

- [ ] **Step 8: Commit**

```bash
cd .worktrees/api-key-expiry-months
git add admin-ui/src/pages/tenant/ApiKeysTab.tsx
git commit -m "feat(admin-ui): API key 발급 만료 선택 + 목록 만료일 컬럼/EXPIRED 배지"
```

---

## Task 8: 전체 검증

- [ ] **Step 1: admin-app + admin-ui 빌드/테스트**

Run: `cd .worktrees/api-key-expiry-months && ./gradlew :admin-app:compileJava -q && ./gradlew :admin-app:test --tests "com.crosscert.passkey.admin.apikey.*"`
Expected: admin-app 컴파일 + apikey 테스트 PASS.

Run: `cd .worktrees/api-key-expiry-months/admin-ui && npm run build 2>&1 | tail -4 && npx vitest run 2>&1 | tail -4`
Expected: 빌드 + 전체 vitest PASS.

- [ ] **Step 2: 슬라이스 회귀 확인 (선택)**

`ApiKeyAdminControllerSecurityTest`는 main 기준 pre-existing 실패(SecurityPolicyService mock 누락, 이 작업 무관)일 수 있다. DTO 시그니처 변경이 컨트롤러 컴파일을 깨지 않았는지만 `:admin-app:compileTestJava -q`로 확인.

Run: `cd .worktrees/api-key-expiry-months && ./gradlew :admin-app:compileTestJava -q`
Expected: BUILD SUCCESSFUL (테스트 컴파일 통과 — 4-arg 누락 호출 없음).

- [ ] **Step 3: codex 리뷰 (커밋 전 사용자 선호 — 이미 task별 커밋됨, 최종 1회)**

Run: `cd .worktrees/api-key-expiry-months && node "/Users/jhyun/.claude/plugins/cache/openai-codex/codex/1.0.4/scripts/codex-companion.mjs" review`
Expected: 회귀 없음. 지적 있으면 수정 후 재검증.

> 머지(main으로 --no-ff)는 plan 범위 밖 — 실행 완료 후 사용자 승인 하에 진행.

---

## Self-Review 결과

- **Spec 커버리지**: §5.1 요청DTO(Task1) / §5.1 응답DTO(Task1) / §5.2 issue 만료계산(Task2) / §6.1 API타입·매핑(Task5,6) / §6.2 다이얼로그(Task7) / §6.3 목록 컬럼·EXPIRED(Task7) / §6.4 모달(Task7) / §7 검증(Task4) / §8 테스트(Task3,4,6,7) — 모두 매핑됨.
- **실제 코드명 확인**: 엔티티는 `expireAt(Instant)`(setExpiresAt 아님), 만료검사 `isActive(Instant)`(isValid 아님), getter `getExpiresAt()`. ApiKeyView/types.ts의 expiresAt은 이미 존재. 고정 Clock `2026-06-01T00:00:00Z` → +24m=2028-06-01, +6m=2026-12-01.
- **기존 호출 깨짐 처리**: DTO 4-arg화로 깨지는 호출 — 서비스 응답(Task2), 서비스 테스트 6곳(Task3), 검증 테스트 헬퍼(Task4), 프론트 create 테스트(Task6) 모두 명시적으로 갱신.
- **Placeholder**: 모든 코드 스텝에 완전한 코드 포함. 타입/시그니처 일관(expiresInMonths: Integer/number|null, expiresAt: Instant/string|null).
