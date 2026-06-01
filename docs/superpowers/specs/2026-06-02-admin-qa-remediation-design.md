# Admin 콘솔 QA 개선 설계

- **작성일**: 2026-06-02
- **출처**: `docs/qa-admin-console-dead-buttons-2026-06-02.md` (QA 발견 13건)
- **범위**: High + Medium 7건 (#1, #3, #4, #6, #7, #8, #10). Low 5건은 백로그.
- **참고**: #2(favicon 500)는 본 설계 이전 별도 작업으로 이미 해결됨.

## 목표

운영자/QA 관점에서 "버튼이 있어 보이는데 눌러도 안 되는" 항목을 케이스별로 처리한다.
백엔드·프론트 패턴이 이미 존재하면 **연결**, 없으면 **숨김(disabled)**, 버그는 **근본 수정**.

## 처리 방침 요약

| # | 발견 | 심각도 | 방식 |
|---|------|--------|------|
| 1 | deep-link/새로고침 시 `{"error":"forbidden"}` | 🔴 High | `/admin/**` 와일드카드 패턴화 (복잡 시 6개 명시 폴백) |
| 3 | 테넌트 상세 "RP 사이트 열기" 죽은 버튼 | 🟠 Med | `window.open('https://'+rpId)` 연결 |
| 4 | 테넌트 상세 "Refresh" 죽은 버튼 | 🟠 Med | 기존 `onReload` 연결 |
| 6 | 테넌트 목록 "필터" 죽은 버튼 | 🟠 Med | 클라이언트 status 필터 (q와 AND 결합) |
| 7 | 테넌트 목록 "CSV" 죽은 버튼 | 🟠 Med | `downloadCsv()` 연결, **현재 필터링된 목록** 내보내기 |
| 8 | Audit Chain "Incident 생성" 죽은 버튼 | 🟠 Med | disabled + "향후 지원 예정" 툴팁 (백엔드 기능 미존재) |
| 10 | 보안 정책 CORS Allowlist 추가/삭제 미구현 | 🟠 Med | WebauthnConfigTab의 add/removeOrigin 패턴 복사 |

## 상세 설계

### 1. deep-link forbidden (🔴 High)

**현재 구조 문제**: React 라우트가 두 곳에 하드코딩되어 추가 때마다 양쪽을 같이 고쳐야 함.
- `AdminWebMvcConfig.addViewControllers` — `forward:/admin/index.html` 대상 경로 나열
- `AdminSecurityConfig` permitAll 페이지 매처 목록

양쪽에서 `/admin/settings`, `/admin/license`, `/admin/activity`, `/admin/audit-chain`,
`/admin/forgot-password`, `/admin/reset-password`가 누락되어, 직접 진입/새로고침 시
index.html로 forward되지 못하고 forbidden JSON이 노출됨.

**변경안 (와일드카드 우선)**:
1. `AdminWebMvcConfig` — 개별 나열 대신 `/admin/api/**`와 정적 자산(`/admin/assets/**`,
   확장자 있는 경로)을 제외한 `/admin/**` GET을 `forward:/admin/index.html`로 보내는
   단일 규칙으로 교체.
2. `AdminSecurityConfig` — 페이지 permitAll 매처도 `/admin/**` 기반으로 정렬.
   `/admin/api/**`는 `authenticated()` 유지(미인증 시 401 JSON → SPA가 로그인 유도).

**폴백 조건**: Spring 라우팅 우선순위 검증에서 와일드카드가 정적 자산/`/admin/api/**`와
충돌하거나 ViewController 와일드카드 매칭이 불안정하면, **누락된 6개 라우트를 양쪽에
명시 추가**하는 방식으로 전환한다.

**검증**: 인증/미인증 각각에서 6개 라우트 직접 진입·새로고침 → SPA 정상 로드.
`/admin/api/**`는 여전히 401 JSON 반환 확인.

**리스크**: 와일드카드가 너무 넓으면 의도치 않은 경로까지 forward. 정적 자산·API 제외
규칙을 명확히 하고 빌드 후 실제 라우팅을 검증한다.

### 2. 연결 그룹 (#3, #4, #7, #10)

기존 백엔드 API·프론트 함수·UI 패턴이 모두 존재. 연결만 한다.

**#3 RP 사이트 열기** — `TenantDetailPage.tsx:136`
```
onClick={() => {}}
→ onClick={() => tenant.rpId && window.open('https://' + tenant.rpId, '_blank', 'noopener,noreferrer')}
```
`noopener,noreferrer`로 역참조 방지. rpId 없으면 no-op. rpId는 TenantView→Tenant에서 이미 로드됨.

**#4 Refresh** — `TenantDetailPage.tsx:137`
`TenantDetailRoute`에 `reload()`가 정의되어 `onReload` prop으로 전달됨(기존 코드).
```
onClick={() => {}} → onClick={onReload}
```

**#7 CSV** — `TenantsListPage.tsx:144`
`lib/csvExport.ts`의 `downloadCsv(filename, header, rows)` 호출. ActivityPage·CredentialsTab 패턴.
- filename: `tenants-<YYYY-MM-DD>.csv`
- header: `['name','slug','rpId','status','credentials','apiKeys','createdAt']`
- rows: `filtered.map(t => [...])` — **현재 필터링된 목록**(검색 q + status 필터 반영)

**#10 CORS 추가/삭제** — `SecurityPolicyTab.tsx:150-151`
`WebauthnConfigTab`의 `addOrigin()`/`removeOrigin()`/`originInput` 패턴 복사.
- 입력란 `onKeyDown` Enter → `addOrigin()` (공백·중복 제거)
- 칩 `chip__x`에 `onClick={() => removeOrigin(o)}`
- 저장은 `handleSave()`가 이미 `corsAllowlist` 포함(line 108) — 추가 작업 없음

### 3. 필터 구현 (#6)

`TenantsListPage.tsx:140` — 클라이언트 status 필터.
- `status` state 추가(기본 `ALL`).
- "필터" 버튼 클릭 시 status 선택 UI 노출(ActivityPage 칩 필터 패턴: `ALL / ACTIVE / SUSPENDED`).
- `filtered = tenants.filter(q 매칭 && status 매칭)` — 검색과 status를 **AND 결합**.
- #7 CSV가 이 `filtered`를 내보내므로 status 필터가 CSV에도 자동 반영됨.

**YAGNI**: 백엔드 필터(Option B)는 테넌트가 수천 개로 커질 때 검토. 현재는 클라이언트로 충분.

### 4. 숨김 처리 (#8)

`AuditChainPage.tsx:251` — disabled + 툴팁.
```
<button className="btn btn--danger btn--sm"><Icons.Alert/> Incident 생성</button>
→ <button className="btn btn--danger btn--sm" disabled title="향후 지원 예정"><Icons.Alert/> Incident 생성</button>
```
백엔드에 incident 엔드포인트·테이블이 전혀 없음(조사 확인). 동작 부여 없이 로드맵 존재만 표현.

## 백로그 (이번 범위 밖, Low)

별도 추적. 본 작업 완료 후 `docs/qa-admin-console-dead-buttons-2026-06-02.md`에 상태 갱신.

- **#5** 테넌트 상세 Dots — 추가 액션 메뉴 placeholder
- **#9** Audit Chain 행별 "검증" — "전체 즉시 검증"으로 대체 가능
- **#11** Admin 사용자 Dots — 추가 액션 메뉴 placeholder
- **#12** 개요 탭 성공률 "Phase E3 연결 예정" — 백엔드 데이터 연결(E3) 필요
- **#13** 시스템 탭 p95·Uptime "—" — 백엔드 메트릭 연결 필요

## 검증 전략

빌드 → admin-app 재기동(dev) → 브라우저:
1. **#1**: 인증/미인증 각각 6개 라우트 deep-link·새로고침 → SPA 정상. `/admin/api/**` 401 유지.
2. **#3**: "RP 사이트 열기" → 새 탭에 `https://<rpId>` 열림.
3. **#4**: "Refresh" → tenant 상세 데이터 refetch 네트워크 요청 발생.
4. **#6**: status 필터 토글 → 목록 행 변화. q 검색과 AND 결합.
5. **#7**: "CSV" → 다운로드 발생, 현재 필터링된 행만 포함.
6. **#8**: "Incident 생성" → disabled, 툴팁 노출.
7. **#10**: CORS 입력+Enter → 칩 추가, chip__x → 칩 제거, 저장 반영.

커밋 전 `/codex:review`로 staged diff 독립 리뷰(사용자 선호).

## 영향 파일

- `admin-app/.../config/AdminWebMvcConfig.java` (#1)
- `admin-app/.../config/AdminSecurityConfig.java` (#1)
- `admin-ui/src/pages/TenantDetailPage.tsx` (#3, #4)
- `admin-ui/src/pages/TenantsListPage.tsx` (#6, #7)
- `admin-ui/src/pages/AuditChainPage.tsx` (#8)
- `admin-ui/src/pages/settings/SecurityPolicyTab.tsx` (#10)
