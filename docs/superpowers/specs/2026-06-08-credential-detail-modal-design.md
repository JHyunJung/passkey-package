# Credential 상세 + 인증 기록 모달 (P2) — 설계

- **작성일**: 2026-06-08
- **상태**: 승인됨 (구현 대기)
- **범위**: `admin-ui` 한정 (백엔드 무변경 — P1 의 auth-events API 소비)
- **상위 목표**: admin-ui Credential 탭에서 credential 클릭 시 상세 정보 + 인증 기록을 본다. P1(백엔드 파이프라인, 머지됨) + P2(이 화면) 두 sub-project 중 **P2**.

## 1. 문제

Credential 탭은 목록만 보여준다(`CredentialsTab.tsx`). 행을 클릭해도 아무 일도 일어나지 않는다. 운영자가 특정 credential 의 상세 메타데이터와 인증 이력을 확인할 방법이 없다.

P1 에서 credential 단위 인증 이벤트 API 가 머지됐다:
`GET /admin/api/tenants/{tenantId}/credentials/{credentialId}/auth-events?page&size`
→ `PageView<AuthEventView{result, failureReason, signCount, createdAt}>`.

P2 는 이 API 를 소비해 상세+기록 모달을 만든다.

## 2. 목표 / 비목표

### 목표
- Credential 탭 행 클릭 시 중앙 모달로 상세 정보 + 인증 기록 표시.
- 상세는 이미 로드된 목록 데이터로 즉시 렌더(추가 호출 없음).
- 인증 기록은 P1 auth-events API 로 최근 20건 조회.

### 비목표 (YAGNI)
- 백엔드 변경 — admin-ui 순수 작업.
- 인증 기록 페이지네이션/더 보기 — 최근 20건 고정.
- 우측 드로어 패턴 — 기존 `shell/Dialog`(중앙 모달) 재사용.
- publicKey/backupState 등 깊은 필드 — 단건 상세 API 불필요.
- 상세 인라인 편집 — 조회 전용.

## 3. 확정된 결정

| 항목 | 결정 | 근거 |
|---|---|---|
| 상세 정보 범위 | 목록 API 필드 + attestationFormat | 추가 호출/백엔드 변경 회피 |
| 표시 방식 | 중앙 모달 (`shell/Dialog` 재사용) | RevokeCredentialDialog 와 일관, 구현 최소 |
| 인증 기록 | auth-events API, 최근 20건 (page=0, size=20) | 운영 확인엔 최근 패턴이면 충분 |
| 빈/에러 | 상세는 항상, 기록 섹션만 상태 표시 | 기록 실패가 상세 확인을 막지 않음 |
| 백엔드 | 무변경 | P2 는 화면에 한정 |

## 4. 아키텍처

변경은 admin-ui 3개 지점에 한정:

```
CredentialsTab.tsx (행 클릭 핸들러 + selected 상태 + 모달 렌더)
   └─ 클릭 → setSelected(c)
        └─ CredentialDetailDialog.tsx (신규)
             ├─ 상단: 상세 정보 (이미 가진 Credential 객체 — 추가 호출 없음)
             └─ 하단: 인증 기록 (credentialsApi.authEvents() 호출, 최근 20건)
                       ↑
   credentials.ts (authEvents() + AuthEvent 타입 + attestationFormat 어댑터 보강)
```

## 5. 컴포넌트

### 5.1 `credentials.ts` (API 레이어 보강)
- **어댑터 보강**: `adapt()` 가 현재 버리는 서버 `CredentialView.attestationFormat` 을 designTypes `Credential` 로 전달. → designTypes `Credential` 에 `attestationFormat: string` 추가.
- **AuthEvent 타입** (신규, designTypes 또는 credentials.ts):
  ```ts
  export type AuthEvent = {
    result: 'SUCCESS' | 'FAILED';
    failureReason: string | null;
    signCount: number;
    createdAt: string;
  };
  ```
  (서버 `AuthEventView` 와 동일 필드명 — 어댑터 단순.)
- **authEvents 함수**:
  ```ts
  authEvents: async (tenantId, credentialId, size = 20): Promise<AuthEvent[]> => {
    const q = new URLSearchParams({ page: '0', size: String(size) });
    const res = await api.get<PageView<AuthEventView>>(
      `/admin/api/tenants/${tenantId}/credentials/${encodeURIComponent(credentialId)}/auth-events?${q}`);
    return res.content;   // AuthEventView ≡ AuthEvent (필드 동일)
  }
  ```
- 서버 응답 타입 `AuthEventView`(types.ts): `{ result, failureReason, signCount, createdAt }`.

### 5.2 `CredentialDetailDialog.tsx` (신규)
- **props**: `c: Credential`, `tenantId: string`, `onClose: () => void`.
- **상단(상세)**: `RevokeCredentialDialog` 의 grid 표시 패턴 재사용. 표시 필드: externalUserId, nickname, authenticator(MDS 배지/aaguid), aaguid, transports, signatureCounter, attestationFormat, lastUsedAt, createdAt, credentialId(전체, mono).
- **하단(기록)**: 마운트 시 `useEffect` 로 `credentialsApi.authEvents(tenantId, c.credentialId, 20)`.
  - 로딩: "로딩 중…".
  - 성공: 테이블(시간 / 결과 배지 / 사유 / signCount). SUCCESS 초록 배지, FAILED 빨강 배지 + failureReason.
  - 빈: "아직 인증 이력이 없습니다".
  - 에러: "기록을 불러오지 못했습니다" + 재시도 버튼.
- 시간 표기는 CredentialsTab 의 `fmtDateTime`(KST) 패턴 재사용.

### 5.3 `CredentialsTab.tsx` (수정)
- `selected: Credential | null` 상태 추가.
- 테이블 행에 `onClick={() => setSelected(c)}` + hover 커서(클릭 가능 표시).
- **회수(Trash) 버튼은 행 클릭과 분리**: 버튼 `onClick` 에 `e.stopPropagation()` 추가해 회수 버튼이 상세 모달을 열지 않게.
- `selected && <CredentialDetailDialog c={selected} tenantId={tenant.id} onClose={() => setSelected(null)} />`.

## 6. 데이터 흐름

1. 행 클릭 → `setSelected(c)`.
2. `CredentialDetailDialog` 마운트 → 상세는 `c` 로 즉시 렌더(추가 호출 없음).
3. `useEffect` → `authEvents(tenant.id, c.credentialId, 20)` → 기록 섹션 채움.
4. 닫기 → `setSelected(null)`.

## 7. 에러 처리

| 상황 | 처리 |
|---|---|
| 기록 로드 실패 | 기록 섹션에만 에러 + 재시도 버튼. 상세 영향 없음. |
| 기록 0건 | "아직 인증 이력이 없습니다" 안내. |
| credentialId URL 안전 | `encodeURIComponent` (revoke 와 동일). |
| 모달 언마운트 중 응답 도착 | cancelled 가드(`useEffect` cleanup) — CredentialsTab 의 기존 list useEffect 패턴 재사용. |

## 8. 테스트

admin-ui 는 vitest + @testing-library/react 보유(`api/apiKeys.test.ts`, `pages/NewTenantDialog.test.tsx` 전례).

| 테스트 | 검증 |
|---|---|
| `credentials.test.ts` (신규/보강) | `authEvents()` 가 올바른 URL 호출 + content 반환; `adapt()` 가 attestationFormat 전달 |
| `CredentialDetailDialog.test.tsx` (신규) | 상세 필드 렌더(c 로 즉시); 기록 로딩→성공 테이블; 빈 상태; 에러+재시도 |
| `CredentialsTab` (보강, 가능 시) | 행 클릭 → 모달 열림; 회수 버튼 클릭 → 모달 안 열림(stopPropagation) |

## 9. 변경 파일 (예상)

- `admin-ui/src/api/credentials.ts` — authEvents + adapt 보강 (수정)
- `admin-ui/src/api/designTypes.ts` — Credential.attestationFormat + AuthEvent 타입 (수정)
- `admin-ui/src/api/types.ts` — AuthEventView 서버 타입 (수정, 없으면 추가)
- `admin-ui/src/pages/tenant/CredentialDetailDialog.tsx` — 신규 모달 (신규)
- `admin-ui/src/pages/tenant/CredentialsTab.tsx` — 행 클릭 + selected + stopPropagation (수정)
- `admin-ui/src/api/credentials.test.ts` — API 테스트 (신규)
- `admin-ui/src/pages/tenant/CredentialDetailDialog.test.tsx` — 컴포넌트 테스트 (신규)

## 10. 수동 검증 (dogfooding)

admin-ui dev 서버(vite :5173 → admin-app :8081 proxy)에서:
1. Credential 탭 → 행 클릭 → 모달에 상세 + 인증 기록(또는 빈 안내).
2. 회수 버튼 클릭 → 모달 안 열리고 회수 확인만.
3. 인증 이력이 있는 credential(P1 으로 기록됨)은 SUCCESS/FAILED 행 표시.
