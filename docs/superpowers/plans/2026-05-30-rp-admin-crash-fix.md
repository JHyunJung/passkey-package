# RP_ADMIN 대시보드 크래시 수정 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development 또는 executing-plans.

**Goal:** RP_ADMIN 로그인 시 admin-ui 전체가 화이트스크린되는 크래시를 막는다 (옵션 A: 크래시 핵심 + ErrorBoundary 안전망). role 게이팅(전용 IA)은 범위 밖.

**근본 원인 (브라우저 재현으로 확정):** `Sidebar`의 audit-chain 위젯이 `auditChainMonitorApi.overview()`(=`api.getRaw`)를 호출하는데, `client.ts`의 `rawRequest`가 **401만 특수처리하고 403은 본문(에러 envelope JSON `{success:false,...}`)을 정상 파싱해 반환**한다. 그래서 Sidebar의 `catch{}`가 안 돌고 `chain`이 null 대신 에러 envelope가 되어, `chain === null` 가드를 통과한 뒤 `chain.totals.tenantsTampered` 접근에서 `totals`가 undefined → throw → ErrorBoundary 없어 전체 트리 언마운트 → 화이트스크린.

**Architecture:** 세 겹 수정 — (1) `rawRequest`가 비-2xx를 ApiError로 throw(401처럼), (2) Sidebar 가드를 shape 체크로 보강, (3) ErrorBoundary로 렌더 크래시를 부분 격리. blast radius 주의: `getRaw`/`postRaw`/`putRaw` 호출처 6곳 중 `audit.ts`가 "403 envelope 정상 반환"에 의존(success===false 직접 체크)하므로, throw 전환에 맞춰 그 부분을 ApiError catch로 보정.

**Tech Stack:** React 18 + TS + Vite. 게이트: `tsc --noEmit` + `vite build` + 브라우저 재현(bob RP_ADMIN).

**근거:** 메모리 project-rp-admin-dashboard-crash, dogfooding followups §1.1.

---

## 파일 구조
- Modify: `admin-ui/src/api/client.ts` — `rawRequest` 비-2xx → ApiError throw
- Modify: `admin-ui/src/api/audit.ts` — getRaw throw 전환에 맞춰 에러 처리 보정 (success===false 직접 체크가 ApiError catch로)
- Modify: `admin-ui/src/shell/Sidebar.tsx` — 가드를 `chain?.totals` shape 체크로
- Create: `admin-ui/src/shell/ErrorBoundary.tsx` — 렌더 크래시 격리
- Modify: `admin-ui/src/App.tsx` — AuthenticatedApp의 Routes(및 Sidebar)를 ErrorBoundary로 감쌈

---

## Task 1: rawRequest 비-2xx throw + 호출처 보정

**Files:** `admin-ui/src/api/client.ts`, `admin-ui/src/api/audit.ts`

- [ ] **Step 1: 현재 동작 정독**

`client.ts`의 `rawRequest`(약 21-46행)를 읽는다. 현재: 401이면 ApiError throw(+redirect), 그 외엔 `return (await res.json()) as T` (403 포함 본문 반환). `request`(envelope 버전)는 이미 `!envelope.success`면 throw하므로 변경 불필요.

- [ ] **Step 2: rawRequest가 비-2xx를 throw하도록 수정**

`rawRequest`에서 401 블록은 그대로 두고(redirect 유지), `res.json()` 반환 전에 `!res.ok` 가드 추가:
```typescript
  // 401 블록(redirect) 다음, json 파싱 전:
  if (!res.ok) {
    // 비-2xx (403 등) — 본문이 에러 envelope({success:false,code,message})일 수 있으나
    // raw 호출자도 실패를 throw 로 받도록 통일. envelope code/message 있으면 보존.
    let code = 'C999', message = `HTTP ${res.status}`;
    try {
      const body = await res.json() as { code?: string; message?: string; error?: string };
      code = body.code ?? body.error ?? code;
      message = body.message ?? body.error ?? message;
    } catch { /* 비-JSON 본문 */ }
    throw new ApiError(res.status, code, message);
  }
  try {
    return (await res.json()) as T;
  } catch {
    throw new ApiError(res.status, 'C999', `Non-JSON response (status ${res.status})`);
  }
```
> 주의: `res.json()`은 한 번만 읽을 수 있으므로, !res.ok 분기에서 읽으면 정상 분기로 안 감(이미 throw). 위 구조가 그것을 보장(throw가 정상 return보다 먼저).

- [ ] **Step 3: audit.ts 보정**

`audit.ts`(약 67-83행)는 현재 getRaw 결과에서 `success === false`를 직접 체크해 throw한다. 이제 getRaw가 비-2xx면 **ApiError를 던지므로** 그 success-체크 분기는 정상 응답에선 도달 안 한다(여전히 두어도 무해 — 방어). 변경 핵심: 이 호출이 ApiError를 던질 수 있게 됐으므로, 호출하는 화면(TenantOverview 등)이 try/catch로 처리하는지 확인. **audit.ts 자체는 success-체크를 남겨두되**(이중 방어), getRaw가 throw해도 호출자에 전파되도록 추가 try/catch로 삼키지 않는다. 즉 audit.ts는 변경 최소 — 동작 검증만. 만약 success-체크가 `s.intact` 같은 필드 접근 전에 있어 throw 후 도달 안 하면 그대로 OK.

- [ ] **Step 4: blast radius 확인 — 다른 getRaw/postRaw 호출처**

`funnel.ts`, `webauthn.ts`, `securityPolicy.ts`, `aaguidPolicy.ts`, `auditChainMonitor.ts`가 getRaw/postRaw 사용. 이들은 모두 **성공 응답을 가정**하고 실패 시 호출 화면의 try/catch에 의존. throw 전환은 오히려 일관성↑(실패가 조용히 envelope로 흘러가지 않음). 각 호출 화면이 ApiError를 잡아 toast/빈 상태로 처리하는지 grep으로 점검하고, 잡지 않아 크래시할 새 지점이 생기면 그 화면에 catch 추가. (대부분 이미 try/catch 또는 ErrorBoundary(Task 3)가 backstop.)

- [ ] **Step 5: 빌드**

`cd admin-ui && npx tsc --noEmit && npm run build` → 성공.

- [ ] **Step 6: commit**
```bash
git add admin-ui/src/api/client.ts admin-ui/src/api/audit.ts
git commit -m "fix(admin-ui): rawRequest 가 비-2xx(403 등)를 ApiError 로 throw — 에러 envelope 정상반환 제거"
```

---

## Task 2: Sidebar 가드 shape 체크

**Files:** `admin-ui/src/shell/Sidebar.tsx`

- [ ] **Step 1: chain 사용부 가드 보강**

Sidebar의 footer 렌더(약 162-184행)에서 `chain === null` 분기와 `chain.totals.tenantsTampered` 접근을 shape-safe하게. `chain` 상태가 정상 ChainOverview일 때만 totals를 읽도록:
```typescript
  // 기존: chain === null ? (loading) : chain.totals.tenantsTampered > 0 ? (danger) : (ok)
  // 변경: totals 가 없으면(에러/미로드) loading 표시로 폴백
  {!chain?.totals ? (
     <loading/확인중 블록>
  ) : chain.totals.tenantsTampered > 0 ? (
     <danger 블록>
  ) : (
     <ok 블록>
  )}
```
`chain.totals.verifiedRows` 등 다른 접근도 `chain.totals` 보장된 ok 분기 안에 있으므로 안전. (Task 1으로 chain은 이제 에러 시 null로 남지만, 이중 방어로 shape 체크 유지 — 향후 다른 비정상 응답에도 견고.)

- [ ] **Step 2: 빌드 + commit**
`cd admin-ui && npx tsc --noEmit && npm run build` → 성공.
```bash
git add admin-ui/src/shell/Sidebar.tsx
git commit -m "fix(admin-ui): Sidebar audit-chain 위젯 가드를 chain?.totals shape 체크로 (이중 방어)"
```

---

## Task 3: ErrorBoundary 안전망

**Files:** Create `admin-ui/src/shell/ErrorBoundary.tsx`, Modify `admin-ui/src/App.tsx`

- [ ] **Step 1: ErrorBoundary 컴포넌트**

Create `admin-ui/src/shell/ErrorBoundary.tsx`:
```typescript
import { Component, type ReactNode } from 'react';

type Props = { children: ReactNode; fallback?: ReactNode };
type State = { hasError: boolean; message?: string };

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(err: unknown): State {
    return { hasError: true, message: err instanceof Error ? err.message : String(err) };
  }

  componentDidCatch(err: unknown) {
    console.error('UI error boundary caught:', err);
  }

  render() {
    if (this.state.hasError) {
      return this.props.fallback ?? (
        <div style={{ padding: 32, color: 'var(--text-mute)' }}>
          <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--text)', marginBottom: 6 }}>
            화면을 표시하는 중 문제가 발생했습니다.
          </div>
          <div style={{ fontSize: 13 }}>새로고침하거나 다른 메뉴로 이동해 보세요.</div>
        </div>
      );
    }
    return this.props.children;
  }
}
```

- [ ] **Step 2: App.tsx에서 콘텐츠 영역을 ErrorBoundary로 감쌈**

`AuthenticatedApp`(약 141-172행)의 `<main>` 안 `<Routes>`를 `<ErrorBoundary>`로 감싼다. 이렇게 하면 페이지 렌더 크래시가 셸(Sidebar/네비)까지 무너뜨리지 않고 콘텐츠 영역에 국한된다. 추가로 Sidebar도 보호하려면 `<Sidebar.../>`를 별도 ErrorBoundary로 감싸 셸 전체 화이트스크린을 원천 차단(권장):
```typescript
import { ErrorBoundary } from '@/shell/ErrorBoundary';
// ...
  <ErrorBoundary fallback={null}>
    <Sidebar ... />
  </ErrorBoundary>
  ...
  <main style={{ padding: 24 }}>
    <ErrorBoundary>
      <Routes> ... </Routes>
    </ErrorBoundary>
  </main>
```
> Sidebar fallback={null} — 사이드바가 죽어도 콘텐츠는 보이게. 콘텐츠 ErrorBoundary는 기본 fallback 메시지.

- [ ] **Step 3: 빌드 + commit**
`cd admin-ui && npx tsc --noEmit && npm run build` → 성공.
```bash
git add admin-ui/src/shell/ErrorBoundary.tsx admin-ui/src/App.tsx
git commit -m "feat(admin-ui): ErrorBoundary — 렌더 크래시를 전체 화이트스크린 대신 부분 격리"
```

---

## Task 4: 브라우저 재현 검증

- [ ] **Step 1: 빌드 + admin-app 기동**
admin-ui build → admin-app이 dist 정적 서빙. `SPRING_PROFILES_ACTIVE=dev ./gradlew :admin-app:bootRun` (8081). DB(Oracle/Redis) 기동 상태 가정.

- [ ] **Step 2: bob(RP_ADMIN) 재현**
playwright로 bob@crosscert.com/bob-temp-pw 로그인 → 이전엔 화이트스크린이던 랜딩이 이제 **정상 렌더**되는지 확인(Sidebar audit-chain 위젯은 "확인 중…" 또는 숨김, 콘텐츠는 보임). 콘솔에 uncaught 에러 없음. tenants 목록(자기 테넌트) 표시. 스크린샷.
- 추가: alice(PLATFORM_OPERATOR)도 회귀 없는지(audit-chain 위젯 정상 표시) 확인.

- [ ] **Step 3: 결과 기록** — 크래시 해소 확인. 남은 이슈(Activity/Audit Chain 라우트가 RP_ADMIN에 여전히 서버 403 생텍스트 — 별개 role-게이팅 범위) 메모.

---

## 범위 밖 (follow-up 유지)
- RP_ADMIN role 게이팅(전용 네비/IA, PLATFORM 전용 라우트 숨김) — 이 plan은 "안 죽게"만. 제대로 된 RP_ADMIN 경험은 별도 phase.
- `/admin/favicon.ico` 500.
