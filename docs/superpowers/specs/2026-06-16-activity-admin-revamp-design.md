# Activity 화면 개선 설계 (PLATFORM_OPERATOR)

- 날짜: 2026-06-16
- 대상: admin-app Activity 대시보드 (`GET /admin/api/activity`, `admin-ui/src/pages/ActivityPage.tsx`)
- 범위: PLATFORM_OPERATOR 전용 Activity 화면. RP_ADMIN 은 제외(아래 "범위 밖" 참조).

## 문제 정의

현재 Activity 화면에서 운영자가 겪는 세 가지 불편 (코드 검증 완료):

1. **필터가 두 곳으로 쪼개져 혼란**
   - 피드 헤더의 클라이언트 필터(`mutations`/`failures`, `ActivityPage.tsx:393-402`)와
   - 우측 "카테고리 필터" 카드(`ops`/`security`, 서버 호출, `ActivityPage.tsx:557-581`)가
   - 사실상 같은 ops/security 구분인데 따로 동작한다. 카테고리=`ops` 서버 필터 + 헤더=`failures` 조합 시 결과 0건이 되는 모순.

2. **"누가·무엇을·어떻게 바뀌었는지" 구분 불가**
   - 피드 한 줄이 `actor {이메일10자} → subject {targetId12자}` 만 노출(`ActivityPage.tsx:440-442`).
   - `payload`(before→after·사유·IP 등 상세)가 서버 `ActivityView.Event` 에 아예 포함되지 않는다(`ActivityService.java:109-120`). 행 끝 `>` 버튼도 id/subject 만 toast.

3. **테넌트로 좁혀볼 방법이 없음**
   - 화면에 테넌트 선택 UI 자체가 없다. `tenantId` 는 URL `?tenantId=` 로만 들어온다(`ActivityPage.tsx:187`).
   - "활발한 Tenant" 카드 클릭은 필터가 아니라 테넌트 상세 페이지로 navigate(`ActivityPage.tsx:268-271`).
   - 단, 서버 API 는 이미 `?tenantId=` 파라미터를 명시적 WHERE 로 처리하므로(`ActivityController.java:43`, `ActivityRepository`), **UI 만 붙이면** 동작한다.

## 목표

PLATFORM_OPERATOR 가 Activity 한 화면에서:
- 하나의 일관된 필터 바로 카테고리·테넌트·액션을 좁힐 수 있다.
- 각 이벤트를 "누가 → 무엇을 → 어디에" 문장으로 읽을 수 있다.
- 행을 클릭하면 우측 상세 패널에서 payload 전체(변경 diff·IP·사유)를 본다.

## 설계

### 1. 단일 필터 바 (문제 ① · ③ 해결)

피드 헤더의 클라이언트 필터(`filter` state, `mutations`/`failures`)를 **제거**하고, 화면 상단에 단일 필터 바를 둔다. 세 가지 필터가 모두 서버로 전달돼 일관 동작한다.

- **카테고리 칩**: 전체 / 운영 / 보안 — 기존 `categoryFilter` state(`ActivityCategory`)를 그대로 사용. 서버 `category` 파라미터로 전달(기존 동작).
- **테넌트 드롭다운**: `tenantsApi.list()`(`admin-ui/src/api/tenants.ts:23`)로 전체 테넌트 목록을 받아 `{id, slug, displayName}` 으로 셀렉트 구성. 선택 시 `tenantId` state 갱신 → 서버 `tenantId` 파라미터로 전달. "전체"(null) 옵션 포함. 기존엔 URL `?tenantId=` 로만 들어오던 값을, 이 드롭다운이 화면 내 상태로 승격(URL 동기화는 유지해 딥링크 호환).
- **액션 검색**: 텍스트 입력. 클라이언트 측에서 `e.type`(action 코드) 부분일치 필터. 액션 종류가 닫힌 집합이라 서버 왕복 없이 즉시 필터. (선택 사항이지만 "운영 액션만/보안 실패만" 보다 세밀한 좁히기를 제공.)

기존 우측 "카테고리 필터" 카드는 제거(상단 바로 흡수). "활발한 Tenant" 카드는 유지하되, 클릭 동작에 옵션 추가: 카드 클릭 시 **상세 이동 대신 해당 테넌트로 필터**(드롭다운 값 갱신)하도록 변경. 상세로 가는 navigate 는 별도 아이콘/링크로 분리.

폴링(5초)·KPI·top5 는 그대로. KPI/top5 는 의도적으로 글로벌 24h 유지(`ActivityService.java:56-60` 주석의 계약).

### 2. 행 문장화 (문제 ② 1차 해결)

`recentActivityAdapter.ts` 의 `RecentActivityEvent` 를 사람이 읽는 한 줄로 렌더한다.

- **액션 라벨 매핑**: action 코드 → 한글 라벨 맵을 신설(예: `API_KEY_ISSUE → "API 키 발급"`, `WEBAUTHN_CONFIG_UPDATED → "설정 변경"`, `ADMIN_LOGIN_FAILED → "로그인 실패"`). 매핑에 없는 코드는 원문 표시(fallback).
- **문장 포맷**: `{actorEmail} 님이 {tenantSlug} 테넌트에 {대상} {액션}` 형태. 행위자가 없으면(system/실패) "system"/이메일 그대로. 대상은 `targetType`+`targetId`(끝 일부) 조합.
- 색상 톤(danger/warning/info/teal)·범례는 기존 로직 유지(`eventTone`).

이 단계는 **피드 응답(payload 불필요)** 만으로 가능 — 행 자체는 기존 `ActivityView.Event` 필드로 구성.

### 3. 우측 상세 패널 + 단건 조회 (문제 ② 완결)

행 클릭 시 우측에 상세 패널을 슬라이드로 표시. payload 는 폴링 피드에 싣지 않고 **클릭 시 단건 조회**한다(폴링 트래픽 증가 방지 — 50건 × CLOB 를 5초마다 보내지 않음).

**백엔드:**
- `ActivityController` 에 `GET /admin/api/activity/{id}` 추가. `@PreAuthorize("hasRole('PLATFORM_OPERATOR')")` 동일.
- `ActivityService` 에 `detail(UUID id)` 추가 → `AuditLog` 단건 조회 후 payload 포함 DTO 반환.
  - 조회는 `ActivityRepository`(또는 기존 `AuditLog` 리포지토리)에서 id 로 단건 fetch. tenant 격리: PLATFORM_OPERATOR 전용 엔드포인트이므로 글로벌 조회 허용(기존 Activity 와 동일 권한 경계).
- **신규 DTO** `ActivityDetailView`: `id, action, actorEmail, actorId, targetType, targetId, tenantId, tenantSlug, createdAt, category, payload`(String, canonical JSON). 기존 `ActivityView.Event` 는 그대로 두고 detail 만 payload 포함.

**프론트:**
- `api/activity.ts` 에 `fetchDetail(id): Promise<ActivityDetailView>` 추가 → `GET /admin/api/activity/{id}`.
- `ActivityPage.tsx` 에 `selectedId` state + 상세 패널 컴포넌트. 패널은 누가/테넌트/대상/시각 + payload 를 표시.
  - payload 가 `before`/`after` 키를 가지면 **diff 형태**(− before / + after)로 렌더. 그 외에는 key-value 또는 pretty JSON.
  - AuditTab 의 `PayloadDialog`(`AuditTab.tsx:277-340`) 가 동일 목적의 기존 구현 — 가능한 표시 로직/스타일을 참고해 일관성 유지(단, Activity 는 다이얼로그가 아니라 우측 슬라이드 패널).

레이아웃: 피드(좌) + 상세 패널(우). 패널 미선택 시 "활발한 Tenant" 카드가 우측을 차지하고, 행 선택 시 상세 패널이 그 자리에 표시(또는 오버레이). 구체 배치는 구현 시 결정.

## 범위 밖 (YAGNI)

- **RP_ADMIN 의 Activity 접근**: RP_ADMIN 은 TenantDetail 의 AuditTab(`AuditTab.tsx`)에서 자기 테넌트 감사로그를 이미 충분히 본다 — 시각/이벤트/행위자/대상/payload 다이얼로그 + eventType·windowHours 필터. 로컬 기동 후 bob(RP_ADMIN)으로 직접 확인 완료(2026-06-16). 따라서 Activity 사이드 메뉴는 PLATFORM_OPERATOR 전용으로 유지하고, 백엔드 `@PreAuthorize`·`RequirePlatform` 가드·사이드바 노출 조건은 변경하지 않는다.
- **p95 응답시간 KPI**: 현재 미구현(`p95Ms: null`). 현행 "측정 중" 표시 유지.
- **테이블/정렬 전면 재구성**: 실시간 대시보드 성격 유지 — 피드 카드 형태 유지.

## 영향 받는 파일

**프론트엔드 (admin-ui):**
- `src/pages/ActivityPage.tsx` — 필터 바, 행 문장화, 상세 패널, 헤더 클라이언트 필터 제거, 활발한 Tenant 클릭 동작 변경
- `src/pages/tenant/recentActivityAdapter.ts` — 문장화에 필요한 필드 보강(필요 시)
- `src/api/activity.ts` — `fetchDetail` 추가
- `src/api/types.ts` — `ActivityDetailView` 타입 추가
- 액션 라벨 매핑 — 신규 작은 모듈(예: `src/pages/activityLabels.ts`)
- 테넌트 드롭다운 — `src/api/tenants.ts` 의 `tenantsApi.list()` 재사용

**백엔드 (admin-app / core):**
- `admin-app/.../activity/ActivityController.java` — `GET /{id}` 단건 엔드포인트
- `admin-app/.../activity/ActivityService.java` — `detail(id)` 메서드
- `admin-app/.../activity/ActivityDetailView.java` — 신규 DTO(payload 포함)
- `core/.../repository/ActivityRepository.java` — 단건 조회 메서드(없으면 추가; 기존 `AuditLog` 리포지토리 재사용 가능)

## 테스트

- **백엔드**: `ActivityController` `GET /{id}` — PLATFORM_OPERATOR 200 + payload 포함, RP_ADMIN/미인증 403/401, 존재하지 않는 id 404. `ActivityService.detail` 단위 테스트.
- **프론트엔드**: 필터 바 상호작용(카테고리/테넌트/액션) → 올바른 쿼리 구성. 행 문장화 라벨 매핑(매핑 존재/fallback). 상세 패널 — 행 클릭 시 단건 조회 호출 + payload diff 렌더.
- **회귀**: 헤더 클라이언트 필터 제거로 깨지는 기존 테스트 확인. 폴링·KPI·top5·CSV 내보내기 동작 유지.

## 검증 메모

- 로컬 기동 절차·함정(SPRING_PROFILES_ACTIVE 환경변수, APP_OWNER 접속, flyway 소문자 식별자, V8 repair, RP_ADMIN 수동 시드)은 별도 기록. local 프로필 = demo-rp 테넌트.
- 단건 조회가 hash chain 을 검증하지 않는 점은 의도적(Activity 는 조회 전용; chain 검증은 PLATFORM_OPERATOR 의 audit-chain 화면/AuditTab ChainVerify 별도 담당).
