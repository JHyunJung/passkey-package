# Admin 콘솔 QA 개선 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** QA에서 발견한 admin 콘솔의 죽은 버튼·deep-link 버그(High+Medium 7건)를 케이스별로 연결/구현/숨김/근본수정한다.

**Architecture:** 대부분 기존 백엔드 API·프론트 패턴에 UI 핸들러를 연결하는 작업. #1 deep-link만 Spring 라우팅 설정(forward + permitAll)을 와일드카드로 정리. 검증은 빌드 → admin-app(dev) 재기동 → 브라우저 실측 중심. 순수 로직(CSV)은 가벼운 단위 테스트.

**Tech Stack:** React 18 + TypeScript + Vite (admin-ui), Spring Boot (admin-app), vitest (테스트), Chrome DevTools MCP (브라우저 검증).

---

## 작업 브랜치

이미 `qa-remediation-admin-console` 브랜치에서 작업 중(설계 문서 커밋 완료). 모든 태스크는 이 브랜치에 커밋한다.

## File Structure

| 파일 | 책임 | 태스크 |
|------|------|--------|
| `admin-ui/src/pages/TenantDetailPage.tsx` | 테넌트 상세 헤더 버튼(#3 RP열기, #4 Refresh) | T1, T2 |
| `admin-ui/src/pages/TenantsListPage.tsx` | 테넌트 목록 필터(#6)·CSV(#7) | T3, T4 |
| `admin-ui/src/pages/AuditChainPage.tsx` | Incident 버튼 disabled(#8) | T5 |
| `admin-ui/src/pages/settings/SecurityPolicyTab.tsx` | CORS add/remove(#10) | T6 |
| `admin-app/.../config/AdminWebMvcConfig.java` | SPA forward 라우팅(#1) | T7 |
| `admin-app/.../config/AdminSecurityConfig.java` | 페이지 permitAll(#1) | T7 |

태스크 순서: 프론트 단순 연결(T1~T5) → 프론트 로직(T6) → 백엔드(T7) → 통합 브라우저 검증(T8). 각 태스크는 독립 커밋.

---

### Task 1: #3 "RP 사이트 열기" 버튼 연결

**Files:**
- Modify: `admin-ui/src/pages/TenantDetailPage.tsx:136`

`TenantHeader` 컴포넌트(라인 114)는 `{ tenant, onSuspend, onActivate }`를 받는다. `tenant.rpId`는 이미 로드됨(`designTypes.ts` Tenant 타입에 `rpId: string`).

- [ ] **Step 1: onClick 연결**

`admin-ui/src/pages/TenantDetailPage.tsx:136` 의 다음 줄:
```tsx
        <button className="btn btn--sm" onClick={() => {}}><Icons.ExternalLink size={12} /> RP 사이트 열기</button>
```
을 아래로 변경:
```tsx
        <button className="btn btn--sm" onClick={() => tenant.rpId && window.open('https://' + tenant.rpId, '_blank', 'noopener,noreferrer')}><Icons.ExternalLink size={12} /> RP 사이트 열기</button>
```

- [ ] **Step 2: 타입체크**

Run: `cd admin-ui && npx tsc -b --noEmit`
Expected: 에러 없음 (rpId는 string 타입이라 통과)

- [ ] **Step 3: 커밋**

```bash
git add admin-ui/src/pages/TenantDetailPage.tsx
git commit -m "fix(admin-ui): RP 사이트 열기 버튼 연결 (#3)

tenant.rpId 로 새 탭 열기. noopener,noreferrer 로 역참조 방지.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: #4 "Refresh" 버튼 연결 (onReload prop drilling)

**Files:**
- Modify: `admin-ui/src/pages/TenantDetailPage.tsx` (TenantHeader 시그니처 라인 114, 호출처 라인 88-94, 버튼 라인 137)

`TenantDetailPage`는 `onReload`를 받지만(라인 83) `TenantHeader`로 전달하지 않는다. `TenantHeader`에 prop을 추가하고 버튼에 연결한다.

- [ ] **Step 1: TenantHeader 시그니처에 onReload 추가**

`admin-ui/src/pages/TenantDetailPage.tsx:114`:
```tsx
function TenantHeader({ tenant, onSuspend, onActivate }: { tenant: Tenant; onSuspend: () => void; onActivate: () => void }) {
```
을:
```tsx
function TenantHeader({ tenant, onSuspend, onActivate, onReload }: { tenant: Tenant; onSuspend: () => void; onActivate: () => void; onReload: () => void }) {
```

- [ ] **Step 2: 호출처에서 onReload 전달**

`admin-ui/src/pages/TenantDetailPage.tsx:88-90`:
```tsx
      <TenantHeader
        tenant={tenant}
        onSuspend={() => setSuspendOpen(true)}
```
의 `tenant={tenant}` 다음 줄(라인 89와 90 사이)에 prop 추가 — 결과:
```tsx
      <TenantHeader
        tenant={tenant}
        onReload={onReload}
        onSuspend={() => setSuspendOpen(true)}
```

- [ ] **Step 3: Refresh 버튼 onClick 연결**

`admin-ui/src/pages/TenantDetailPage.tsx:137`:
```tsx
        <button className="btn btn--sm" onClick={() => {}}><Icons.Refresh size={12} /> Refresh</button>
```
을:
```tsx
        <button className="btn btn--sm" onClick={onReload}><Icons.Refresh size={12} /> Refresh</button>
```

- [ ] **Step 4: 타입체크**

Run: `cd admin-ui && npx tsc -b --noEmit`
Expected: 에러 없음

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/TenantDetailPage.tsx
git commit -m "fix(admin-ui): Refresh 버튼을 onReload 에 연결 (#4)

TenantHeader 에 onReload prop 추가해 기존 reload() refetch 연결.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: #6 테넌트 목록 status 필터 (클라이언트)

**Files:**
- Modify: `admin-ui/src/pages/TenantsListPage.tsx` (state 라인 51 근처, filtered useMemo 라인 86-96, 필터 버튼 라인 140)

기존 `q` 검색 useMemo를 status와 AND 결합하도록 확장. "필터" 버튼은 status 순환 토글(ALL→ACTIVE→SUSPENDED→ALL)로 단순 구현(드롭다운 대신 칩 토글 — ActivityPage 패턴과 일관).

- [ ] **Step 1: status state 추가**

`admin-ui/src/pages/TenantsListPage.tsx:51`:
```tsx
  const [q, setQ] = useState('');
```
다음 줄에 추가:
```tsx
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ACTIVE' | 'SUSPENDED'>('ALL');
```

- [ ] **Step 2: filtered useMemo 를 status 결합으로 교체**

`admin-ui/src/pages/TenantsListPage.tsx:86-96` 의 filtered useMemo 전체:
```tsx
  const filtered = useMemo(
    () =>
      q
        ? tenants.filter(
            (t) =>
              t.name.toLowerCase().includes(q.toLowerCase()) ||
              t.slug.includes(q.toLowerCase()),
          )
        : tenants,
    [q, tenants],
  );
```
를 아래로 교체:
```tsx
  const filtered = useMemo(
    () =>
      tenants.filter((t) => {
        const matchesQ =
          !q ||
          t.name.toLowerCase().includes(q.toLowerCase()) ||
          t.slug.includes(q.toLowerCase());
        const matchesStatus = statusFilter === 'ALL' || t.status === statusFilter;
        return matchesQ && matchesStatus;
      }),
    [q, statusFilter, tenants],
  );
```

- [ ] **Step 3: 필터 버튼을 status 토글로 연결**

`admin-ui/src/pages/TenantsListPage.tsx:140`:
```tsx
            <button className="btn btn--sm"><Icons.Filter size={12} /> 필터</button>
```
를 아래로 교체 (ALL→ACTIVE→SUSPENDED→ALL 순환, 현재 상태 라벨 표시):
```tsx
            <button
              className={`btn btn--sm ${statusFilter !== 'ALL' ? 'btn--active' : ''}`}
              onClick={() =>
                setStatusFilter((s) => (s === 'ALL' ? 'ACTIVE' : s === 'ACTIVE' ? 'SUSPENDED' : 'ALL'))
              }
            >
              <Icons.Filter size={12} /> {statusFilter === 'ALL' ? '필터' : statusFilter}
            </button>
```

- [ ] **Step 4: 타입체크**

Run: `cd admin-ui && npx tsc -b --noEmit`
Expected: 에러 없음

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/TenantsListPage.tsx
git commit -m "feat(admin-ui): 테넌트 목록 status 필터 (#6)

필터 버튼을 ALL/ACTIVE/SUSPENDED 순환 토글로 구현. q 검색과 AND 결합.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: #7 테넌트 목록 CSV 내보내기

**Files:**
- Modify: `admin-ui/src/pages/TenantsListPage.tsx` (import 라인 1-8, CSV 버튼 라인 144)

`downloadCsv(filename, header, rows)` (`lib/csvExport.ts`) 를 사용. **현재 필터링된 목록**(`filtered`)을 내보낸다.

- [ ] **Step 1: downloadCsv import 추가**

`admin-ui/src/pages/TenantsListPage.tsx:8` (`import { Dialog } from '@/shell/Dialog';`) 다음 줄에 추가:
```tsx
import { downloadCsv } from '@/lib/csvExport';
```

- [ ] **Step 2: CSV 버튼 onClick 연결**

`admin-ui/src/pages/TenantsListPage.tsx:144`:
```tsx
            <button className="btn btn--sm"><Icons.Download size={12} /> CSV</button>
```
를 아래로 교체:
```tsx
            <button
              className="btn btn--sm"
              onClick={() =>
                downloadCsv(
                  `tenants-${new Date().toISOString().slice(0, 10)}.csv`,
                  ['name', 'slug', 'rpId', 'status', 'credentials', 'apiKeys', 'createdAt'],
                  filtered.map((t) => [t.name, t.slug, t.rpId, t.status, t.credentials, t.apiKeys, t.createdAt]),
                )
              }
            >
              <Icons.Download size={12} /> CSV
            </button>
```

- [ ] **Step 3: 타입체크**

Run: `cd admin-ui && npx tsc -b --noEmit`
Expected: 에러 없음 (downloadCsv 의 rows 는 (string|number|null)[][] 허용)

- [ ] **Step 4: 커밋**

```bash
git add admin-ui/src/pages/TenantsListPage.tsx
git commit -m "feat(admin-ui): 테넌트 목록 CSV 내보내기 (#7)

현재 필터링된 목록(filtered)을 downloadCsv 로 내보냄. Activity/Credentials 패턴 일관.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: #8 "Incident 생성" 버튼 disabled 처리

**Files:**
- Modify: `admin-ui/src/pages/AuditChainPage.tsx:251`

백엔드에 incident 기능이 없음(조사 확인). disabled + 툴팁으로 로드맵 존재만 표현.

- [ ] **Step 1: 버튼을 disabled + title 로 변경**

`admin-ui/src/pages/AuditChainPage.tsx:251`:
```tsx
            <button className="btn btn--danger btn--sm"><Icons.Alert size={12} /> Incident 생성</button>
```
를 아래로 교체:
```tsx
            <button className="btn btn--danger btn--sm" disabled title="향후 지원 예정"><Icons.Alert size={12} /> Incident 생성</button>
```

- [ ] **Step 2: 타입체크**

Run: `cd admin-ui && npx tsc -b --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add admin-ui/src/pages/AuditChainPage.tsx
git commit -m "fix(admin-ui): Incident 생성 버튼 disabled 처리 (#8)

백엔드 incident 기능 미존재. disabled + '향후 지원 예정' 툴팁으로
'되는 줄 알았는데 안 됨' 혼란 제거.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: #10 보안 정책 CORS Allowlist 추가/삭제

**Files:**
- Modify: `admin-ui/src/pages/settings/SecurityPolicyTab.tsx` (state 라인 78 근처, CORS 블록 라인 146-156)

`WebauthnConfigTab`의 `addOrigin`/`removeOrigin`/`originInput` 패턴(라인 36, 55-67, 159-168)을 복사. 저장은 `handleSave`가 이미 `corsAllowlist` 포함(라인 108).

- [ ] **Step 1: originInput state + add/remove 함수 추가**

`admin-ui/src/pages/settings/SecurityPolicyTab.tsx:78`:
```tsx
  const [corsAllowlist, setCorsAllowlist] = useState<string[]>([]);
```
다음 줄에 state 추가:
```tsx
  const [originInput, setOriginInput] = useState('');
```

그리고 `applyView` 함수(라인 83-88) 바로 다음(라인 88의 `}` 다음 줄)에 두 함수 추가:
```tsx

  function addCorsOrigin() {
    const v = originInput.trim();
    if (!v) return;
    if (corsAllowlist.includes(v)) return;
    setCorsAllowlist([...corsAllowlist, v]);
    setOriginInput('');
  }

  function removeCorsOrigin(o: string) {
    setCorsAllowlist(corsAllowlist.filter((x) => x !== o));
  }
```

- [ ] **Step 2: CORS chip 삭제 버튼 + input 연결**

`admin-ui/src/pages/settings/SecurityPolicyTab.tsx:148-154` 의 CORS map + input 블록:
```tsx
              {corsAllowlist.map((origin, i) => (
                <span key={i} className="chip mono" style={{ fontSize: 11 }}>
                  {origin}
                  <button className="chip__x"><Icons.X size={11} /></button>
                </span>
              ))}
              <input placeholder="https://… 추가" style={{ border: 0, outline: 'none', fontSize: 12, padding: '2px 4px', flex: 1, minWidth: 200, background: 'transparent', color: 'var(--text)' }} />
```
을 아래로 교체:
```tsx
              {corsAllowlist.map((origin) => (
                <span key={origin} className="chip mono" style={{ fontSize: 11 }}>
                  {origin}
                  <button className="chip__x" onClick={() => removeCorsOrigin(origin)}><Icons.X size={11} /></button>
                </span>
              ))}
              <input
                placeholder="https://… 추가 후 Enter"
                style={{ border: 0, outline: 'none', fontSize: 12, padding: '2px 4px', flex: 1, minWidth: 200, background: 'transparent', color: 'var(--text)' }}
                value={originInput}
                onChange={(e) => setOriginInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), addCorsOrigin())}
              />
```

- [ ] **Step 3: 타입체크**

Run: `cd admin-ui && npx tsc -b --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add admin-ui/src/pages/settings/SecurityPolicyTab.tsx
git commit -m "feat(admin-ui): CORS Allowlist 추가/삭제 구현 (#10)

WebauthnConfigTab 의 origin chip add/remove 패턴 복사.
입력 Enter 로 추가, chip x 로 삭제. 저장은 기존 handleSave 가 처리.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: #1 deep-link forbidden — /admin/** 와일드카드 패턴화

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminWebMvcConfig.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java:93-94`

현재 React 라우트(`/tenants`, `/activity`, `/audit-chain`, `/settings`, `/license`, `/forgot-password`, `/reset-password`)가 두 config의 화이트리스트에서 일부 누락됨. 와일드카드로 정리하되, **Spring 라우팅 우선순위 충돌 시 6개 명시 추가로 폴백**.

- [ ] **Step 1: AdminWebMvcConfig 의 누락된 SPA 라우트 forward 추가**

먼저 와일드카드를 시도한다. `addViewController("/admin/**")` 는 정적 자산까지 가로챌 위험이 있으므로, **안전한 1차 시도는 누락된 라우트를 명시 추가**(폴백을 1차로 채택 — 와일드카드는 ViewController 매칭에서 `/admin/assets/**` 와 충돌 위험이 실제 크기 때문).

`admin-app/.../config/AdminWebMvcConfig.java` 의 기존 `addViewController` 나열(`/admin/keys` 까지) 다음에 누락 라우트 추가. 현재 마지막 줄:
```java
                registry.addViewController("/admin/keys").setViewName("forward:/admin/index.html");
```
다음에 추가:
```java
                registry.addViewController("/admin/activity").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/audit-chain").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/settings").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/license").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/forgot-password").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/reset-password").setViewName("forward:/admin/index.html");
```

> 폴백 노트: 와일드카드(`/admin/**` minus 정적/api)를 정말 쓰려면 ViewController 가 아니라 별도 HandlerMapping 우선순위 제어가 필요해 위험. 명시 추가가 기존 패턴과 일관되고 안전하므로 명시 추가를 채택한다.

- [ ] **Step 2: AdminSecurityConfig permitAll 에 누락 라우트 추가**

`admin-app/.../config/AdminSecurityConfig.java:94`:
```java
                .requestMatchers("/admin/tenants", "/admin/tenants/**", "/admin/api-keys", "/admin/audit", "/admin/mds", "/admin/keys").permitAll()
```
를 아래로 교체 (누락 페이지 라우트 추가; 이들은 미인증 시 SPA 로드 후 클라이언트가 /admin 로그인으로 유도):
```java
                .requestMatchers("/admin/tenants", "/admin/tenants/**", "/admin/api-keys", "/admin/audit", "/admin/mds", "/admin/keys",
                                 "/admin/activity", "/admin/audit-chain", "/admin/settings", "/admin/license",
                                 "/admin/forgot-password", "/admin/reset-password").permitAll()
```

- [ ] **Step 3: 빌드**

Run: `./gradlew :admin-app:bootJar -x test 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminWebMvcConfig.java admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java
git commit -m "fix(admin-app): deep-link 라우트 forward + permitAll 추가 (#1)

activity/audit-chain/settings/license/forgot-password/reset-password 가
SPA forward·permitAll 화이트리스트에서 누락되어 직접 진입/새로고침 시
forbidden JSON 노출. 6개 라우트를 양쪽에 명시 추가.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: 통합 빌드 + admin-app 재기동 + 브라우저 검증

**Files:** (없음 — 검증만)

모든 변경을 빌드하고 admin-app 을 재기동한 뒤 브라우저로 7건 전부 실측한다.

- [ ] **Step 1: admin-ui 포함 전체 빌드**

Run: `./gradlew :admin-app:build -x test 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`, vite 빌드 성공 로그

- [ ] **Step 2: admin-app 재기동 (dev)**

기존 8081 종료 후 재기동:
```bash
APP_PID=$(lsof -nP -iTCP:8081 -sTCP:LISTEN 2>/dev/null | awk 'NR>1{print $2}' | head -1)
WORKER_PIDS=$(ps aux | grep -v grep | grep "admin-app:bootRun" | awk '{print $2}')
[ -n "$APP_PID" ] && kill "$APP_PID"
for p in $WORKER_PIDS; do kill "$p"; done
sleep 2
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
./gradlew :admin-app:bootRun --args='--spring.profiles.active=dev' > /tmp/passkey-run/admin-app.log 2>&1 &
```
"Started AdminApplication" 로그 뜰 때까지 폴링(약 6초).

- [ ] **Step 3: #1 deep-link curl 검증 (미인증)**

Run:
```bash
for p in /admin/settings /admin/license /admin/activity /admin/audit-chain /admin/forgot-password /admin/reset-password; do
  printf "%-26s " "$p"; curl -s -o /dev/null -w "status=%{http_code} type=%{content_type}\n" "http://localhost:8081$p"
done
```
Expected: 각 경로가 forbidden JSON(403/application/json)이 **아니라** 302(로그인 리다이렉트) 또는 200(text/html). `{"error":"forbidden"}` 가 안 나오면 통과. `/admin/api/**` 는 별도로 401 유지.

- [ ] **Step 4: 브라우저 검증 (Chrome DevTools MCP)**

alice@crosscert.com / alice-temp-pw 로 로그인 후:
1. `/admin/settings` 새로고침 → SPA 정상 렌더(forbidden JSON 아님)
2. `/admin/activity`, `/admin/license`, `/admin/audit-chain` 새로고침 → 정상
3. 테넌트 상세: "RP 사이트 열기" → 새 탭 `https://dev-passkey.crosscert.com` 열림
4. 테넌트 상세: "Refresh" → tenant refetch 네트워크 요청 발생
5. 테넌트 목록: "필터" 클릭 → ACTIVE/SUSPENDED 토글, 행 필터링. "CSV" → 다운로드 발생
6. Audit Chain: "Incident 생성" → disabled, 툴팁 "향후 지원 예정"
7. 보안 정책: CORS 입력+Enter → 칩 추가, chip x → 칩 제거

각 항목 스크린샷/네트워크로 증거 수집.

- [ ] **Step 5: QA 리포트 상태 갱신 커밋**

`docs/qa-admin-console-dead-buttons-2026-06-02.md` 상단에 처리 완료 표시(#1,3,4,6,7,8,10 ✅ 해결, #5,9,11,12,13 백로그) 추가 후:
```bash
git add docs/qa-admin-console-dead-buttons-2026-06-02.md
git commit -m "docs: QA 리포트에 개선 처리 상태 반영

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 6: 커밋 전 codex 독립 리뷰**

전체 변경(T1~T7)에 대해 `/codex:review` 실행(사용자 선호). 지적사항 있으면 수정 후 재검증.

---

## Self-Review 체크 결과

- **Spec 커버리지**: 설계의 7건(#1,#3,#4,#6,#7,#8,#10) 각각 T7,T1,T2,T3,T4,T5,T6 로 1:1 매핑. 백로그 5건은 의도적 제외(설계 명시).
- **Placeholder**: 모든 코드 step 에 실제 before/after 코드 포함. TBD/TODO 없음.
- **타입 일관성**: `onReload`(T2), `statusFilter`(T3), `originInput`/`addCorsOrigin`/`removeCorsOrigin`(T6) — 정의와 사용처 일치 확인.
- **#1 폴백 결정**: 와일드카드의 ViewController 충돌 위험이 실제로 크므로 T7 에서 "명시 추가"를 1차 채택으로 명문화(설계의 폴백 조건 충족).
