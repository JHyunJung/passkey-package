# API Key 월 단위 만료일 선택 + 어드민 만료 표시 — 설계

날짜: 2026-06-07
상태: 설계 승인 대기

## 1. 목표

어드민에서 새 API Key 발급 시 **월 단위 만료 기간**(6/12/24/36개월 또는 무기한)을
선택하고, 어드민 API Keys 목록에 **만료일을 EXPIRED 배지와 함께** 표시한다.

## 2. 현재 상태 (탐색 결과)

요청한 두 기능 모두 미구현이지만, 만료 인프라 일부는 이미 존재한다.

| 레이어 | 현재 | 필요 작업 |
|--------|------|----------|
| ApiKey 엔티티 | `expiresAt` 필드 + `isValid()` 만료 검사 + `setExpiresAt` 존재 | 발급 시 set만 |
| 발급 서비스 `issue()` | 만료 안 받고 안 설정 → 모든 키 무기한(expires_at=NULL) | 만료 받아 계산·설정 |
| 요청 DTO | tenantId/name/scopes만 | expiresInMonths 추가 |
| 응답 DTO | 만료 없음 | expiresAt 추가 |
| 뷰 DTO `ApiKeyView` | `expiresAt` 이미 포함 | 변경 없음 |
| 프론트 발급 다이얼로그 | name + scope만 | 만료 기간 선택 UI |
| 프론트 목록 표 | prefix/이름/상태/마지막사용/생성일 — 만료일 없음 | 만료일 컬럼 + EXPIRED 배지 |

`ApiKeyAdminService`에는 `Clock clock` 빈이 이미 주입돼 있다(테스트 가능).

## 3. 결정 사항

- 입력: **프리셋 개월 선택** (드롭다운/세그먼트).
- 프리셋: **6 / 12 / 24 / 36개월 + 무기한**, 기본 선택 **24개월**.
- 만료 표시: **전용 '만료일' 컬럼 + EXPIRED 배지**.
- "무기한" = `expiresInMonths=null` → `expires_at=NULL` (기존 키와 동일, 하위 호환).
- 만료 상태(EXPIRED)는 **계산 파생값** — DB 컬럼/상태 저장 안 함. 프론트가
  `expiresAt < now` 로 판정(엔티티 `isValid()` 와 일관). **DB 마이그레이션 불필요.**

## 4. 아키텍처

```
[발급 다이얼로그]  만료 선택(기본 24개월) ──┐
                                          ▼
[apiKeysApi.create]  body 에 expiresInMonths 추가
                                          ▼
[ApiKeyAdminController/Service.issue]  now + N개월 → expires_at 계산·검증·저장
                                          ▼
[ApiKey 엔티티]  setExpiresAt — isValid() 만료 검사는 이미 존재
                                          ▼
[ApiKeyView DTO]  expiresAt 이미 노출
                                          ▼
[목록 표]  '만료일' 컬럼 + EXPIRED/임박 배지
```

## 5. 백엔드 변경

### 5.1 요청 DTO (`ApiKeyAdminDto.ApiKeyCreateRequest`)
```java
public record ApiKeyCreateRequest(
        @NotNull UUID tenantId,
        @NotBlank @Size(max = 64) String name,
        @NotEmpty Set<@NotBlank @Size(max = 32) String> scopes,
        @Min(1) @Max(36) Integer expiresInMonths   // 신규. null = 무기한
) {}
```
화면은 프리셋이지만 백엔드는 범위(1~36) 검증으로 방어. 프리셋 외 값도 1~36이면 수용
(화이트리스트보다 범위가 단순·충분).

### 5.2 발급 서비스 (`ApiKeyAdminService.issue`)
```java
ApiKey key = new ApiKey(req.tenantId(), prefix, hash, req.name());
for (String scope : normalized) key.addScope(scope);
if (req.expiresInMonths() != null) {
    Instant expiresAt = clock.instant()
        .atZone(ZoneOffset.UTC)
        .plusMonths(req.expiresInMonths())
        .toInstant();
    key.setExpiresAt(expiresAt);   // 엔티티 기존 메서드
}
ApiKey saved = repo.saveAndFlush(key);
```
- `plusMonths` 로 월 단위 정확 계산(28/30/31일 자동 처리). UTC, 주입된 Clock 사용.
- audit payload 에 `expiresAt`(또는 null) 추가.

### 5.3 응답 DTO (`ApiKeyCreateResponse`)
`expiresAt` (Instant, nullable) 추가 → 발급 직후 모달에서 만료일 노출.

### 5.4 범위 밖
`rotate`(회전)의 만료 승계는 다루지 않는다. 회전된 새 키는 기존 동작대로 무기한 —
회귀 아님. (YAGNI)

## 6. 프론트 변경

### 6.1 API (`api/apiKeys.ts`)
- `ApiKeyCreateRequest` 타입에 `expiresInMonths?: number | null`
- `create(tenantId, name, scopes, expiresInMonths)` 시그니처 확장 → body 포함
- `ApiKey` 타입의 `expiresAt` 매핑 확인(뷰 DTO 에 이미 존재)

### 6.2 발급 다이얼로그 (`ApiKeysTab.tsx` NewKeyDialog)
- name·scope 아래 **만료 기간 선택** 추가(scope 체크박스와 유사 스타일):
  `[6개월] [12개월] [24개월(기본)] [36개월] [무기한]`
- 기본 선택 24개월. 선택값 아래 1줄 미리보기: "만료일: YYYY-MM-DD"(now+N개월) /
  무기한 시 "만료 없음".
- `handleIssue(name, scopes, expiresInMonths)` 로 전달.

### 6.3 목록 표 — '만료일' 컬럼
- 헤더 "만료일" 추가(생성일과 액션 사이).
- 행:
  - `expiresAt == null` → "무기한"(faint)
  - `expiresAt > now` → 날짜. 30일 이내면 경고색(임박)
  - `expiresAt <= now` → **EXPIRED 배지(danger)** + 날짜
- 만료 판정은 프론트 계산(`new Date(expiresAt) < new Date()`), 엔티티 `isValid()` 와 일관.

### 6.4 발급 완료 모달 (`IssuedKeyModal`)
plaintext 아래 만료일 1줄 추가("만료: YYYY-MM-DD" / "만료 없음").

### 6.5 스타일
기존 패턴 재사용: scope 체크박스 스타일, `StatusBadge`, `fmtDateTime`, `timeAgo`.
새 디자인 토큰 없음.

## 7. 에러 처리
- `expiresInMonths` 1~36 밖 → Bean Validation 400. null 허용(무기한).
- `now + N개월` 이라 과거 시각 위험 없음.
- suspended 테넌트 차단 등 기존 검증 그대로.
- 프론트는 프리셋만 선택 → 추가 검증 불필요, 기존 실패 토스트 재사용.

## 8. 테스트
- **백엔드 단위** (`ApiKeyAdminServiceTest` 등): 고정 Clock 으로
  expiresInMonths=24 → expiresAt = now+24개월, null → null, 경계 6/12/36 정확 계산,
  audit payload 에 expiresAt 포함.
- **검증**: @Min/@Max — 0/37 → 400(컨트롤러 검증 테스트 있으면 케이스 추가).
- **프론트** (vitest 있으면): 다이얼로그 기본 24개월, create 가 expiresInMonths 전달,
  목록 null→"무기한"·과거→EXPIRED·미래→날짜.
- 기존 ApiKey 슬라이스/보안 테스트 회귀 없음 확인.

## 9. 범위 밖 (Non-goals)
- rotate 만료 승계.
- 만료 임박 알림/이메일(UI 표시까지만).
- 기존 무기한 키 일괄 만료 부여(마이그레이션 없음, NULL=무기한 유지).
