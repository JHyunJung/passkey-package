# 신규 테넌트 생성 — slug/rpId 자동생성 버그 수정 설계

작성일: 2026-06-04
작성자: JhyunJung (with Claude)
상태: 승인됨 → 구현 계획 작성 단계

## 배경 / 문제

신규 테넌트 생성 다이얼로그(`admin-ui/src/pages/TenantsListPage.tsx`의 `NewTenantDialog`)에서 표시이름(displayName)을 입력하면 slug와 rpId 필드에 이상한 값이 자동으로 채워진다.

### 원인

`generate()` 함수(290행 부근)가 표시이름을 slug로 변환할 때 `replace(/[^a-z0-9]+/g, '-')`로 영문·숫자가 아닌 모든 문자(한글 포함)를 `-`로 치환한다. 결과:

| 표시이름 | 자동 생성 slug | 문제 |
|---|---|---|
| "한글회사" | 빈값 | 한글이 전부 제거됨 |
| "크로스서트 Inc" | `inc` | 한글 버리고 영문 조각만 남김(엉뚱) |
| "Acme 회사" | `acme` | 뒷부분 한글 잘림 |
| "123회사" | `123` | 숫자 시작 → 프론트 slug 검증 실패 |

추가 문제:
- **318행 `if (!slug) generate(...)` 가드**: slug가 빈값이면(한글 입력 시) 매 키 입력마다 generate가 재실행되어 slug가 계속 덮어써진다.
- **프론트/백엔드 slug 규칙 불일치**: 프론트 `slugRe`는 `^[a-z][a-z0-9-]{1,62}$`(영문 시작), 백엔드 `TenantAdminDto`의 `@Pattern`은 `^[a-z0-9][a-z0-9-]{1,62}$`(숫자도 시작 허용). generate가 숫자 시작 slug를 만들면 프론트 검증에서 막힌다.
- **rpId 자동제안**: slug 기반 `${slug}.crosscert.com`을 자동 채움 — slug가 엉뚱하면 rpId도 엉뚱해진다.

## 목표

표시이름 입력이 slug/rpId에 이상한 값을 자동으로 넣지 않게 하고, 프론트/백엔드 slug 검증 규칙을 일치시킨다.

## 결정 사항 (브레인스토밍 합의)

1. 자동생성 방식: **자동생성 완전 제거** — displayName→slug, slug→rpId 자동 체인을 모두 없애고 세 필드를 독립 수동 입력으로.
2. slug 검증: **프론트를 백엔드 규칙(`^[a-z0-9][a-z0-9-]{1,62}$`)에 맞춤** + 실시간 안내 유지.
3. rpId 자동제안: **제거** — rpId도 항상 수동 입력.

## 설계

`NewTenantDialog`(`TenantsListPage.tsx`) 단일 컴포넌트 수정 + 신규 테스트.

### 변경 1 — 자동생성 체인 완전 제거

- `generate()` 함수(289–294행) 전체 삭제.
- 표시이름 onChange의 `if (!slug) generate(...)`(318행) → `setName(e.target.value)`만 남김.
- `rpIdEdited` state와 관련 로직 전부 삭제: 273행 선언, 286행 reset, 293행 rpId 제안, 332행 `setRpIdEdited(true)`. rpId 자동제안이 사라지므로 "사용자가 수정했는지" 추적이 불필요.

결과: 표시이름·slug·rpId 세 필드가 완전히 독립적인 수동 입력. 표시이름에 한글을 넣어도 slug/rpId에 아무 값도 자동으로 들어가지 않는다.

### 변경 2 — 프론트 slug 검증을 백엔드 규칙에 일치

- `slugRe`(275행): `/^[a-z][a-z0-9-]{1,62}$/` → `/^[a-z0-9][a-z0-9-]{1,62}$/` (백엔드 `@Pattern`과 동일, 숫자 시작 허용).
- slug 안내문(326행)의 "영문 소문자로 시작하고" 표현을 백엔드 규칙에 맞게 조정(영문 소문자·숫자로 시작). `slugOk`/`rpIdOk` 실시간 검증과 placeholder는 유지.
- rpId 검증(`rpIdRe`, 279행)은 그대로 — 이미 백엔드 의도(도메인 hostname)와 부합하고 이번 버그와 무관.

## 테스트 전략

`NewTenantDialog`는 현재 테스트가 없으므로 신규 vitest 파일로 검증. 컴포넌트가 파일 내부 함수라 테스트를 위해 export 추가(동작 영향 없음)가 필요할 수 있다.

1. 자동생성 제거 회귀 방지(핵심): 표시이름에 "한글회사"/"Acme Corp" 입력 → slug·rpId 필드가 비어 있음(자동으로 안 채워짐).
2. slug 검증 규칙: 숫자 시작 slug(예: `123abc`)가 이제 유효로 처리되어 에러 표시 안 됨.
3. 수동 입력 + 생성: slug·rpId를 직접 입력하면 `onCreate`가 입력 그대로의 `{name, slug, rpId}`로 호출됨.

기존 vitest 패턴(`render`/`screen`/`fireEvent`)을 따른다.

## 영향 범위 / 리스크

- 변경 파일: `admin-ui/src/pages/TenantsListPage.tsx`(`NewTenantDialog`만) + 신규 테스트. 단일 컴포넌트, 좁고 격리됨.
- 백엔드 변경 없음: slug `@Pattern`이 이미 `^[a-z0-9]...`라 프론트를 거기에 맞추는 것뿐.
- 인터페이스 보존: `onCreate({name, slug, rpId})`, `handleCreate`, `tenantsApi.create` 모두 그대로 — 데이터 흐름 불변.
- UX 변화: 영문 표시이름의 slug 자동제안이 사라짐(편의 감소). 하지만 그 자동제안이 한글에서 엉뚱한 값을 만든 근본 원인이므로 제거. placeholder·안내문이 입력 가이드를 계속 제공.
- 회귀 위험 낮음: 제거 위주 변경. 미사용이 될 `rpIdEdited` state도 함께 제거해 dead code 없음.

## 변경 요약

| # | 위치 | 변경 | 효과 |
|---|------|------|------|
| 1 | `TenantsListPage.tsx` `NewTenantDialog` | `generate()` + `if(!slug)generate` + `rpIdEdited` 관련 전부 제거 | 자동 체인 제거, 한글 입력 시 이상한 값 안 들어감 |
| 2 | 같은 컴포넌트 `slugRe` + 안내문 | `^[a-z]...` → `^[a-z0-9]...` (백엔드 일치) | 프론트/백 slug 규칙 불일치 제거 |
| 3 | 신규 테스트 | 자동생성 제거·slug 규칙·수동 생성 검증 | 회귀 방지 |
