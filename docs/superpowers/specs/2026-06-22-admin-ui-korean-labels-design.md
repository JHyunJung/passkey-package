# admin-ui 화면 용어 한국어화 설계

작성일: 2026-06-22
대상: `admin-ui/src`
유형: FE 전용 (BE/DB/API 무변경)

## 1. 목적

어드민 화면에 영어로 노출되는 **일반 어드민 용어**(상태값, 네비/탭/헤더 라벨, 정책 모드명)를
한국어로 바꾼다. 단, **Passkey/WebAuthn/FIDO 고유 기술 용어**는 영어로 유지한다.

핵심 원칙: 화면에 그리는 **표시 라벨만** 한국어로 바꾸고, enum 값·API 계약·코드 로직은
영어 그대로 둔다. 따라서 BE/DB/API 변경이 전혀 없고 회귀 위험이 없다.

## 2. 확정된 결정 사항

| 결정 | 내용 |
|---|---|
| 범위 | 상태 배지 + 일반 라벨(네비/탭/헤더/모드) 모두 |
| enum 처리 | 표시만 한국어, 값·API는 영어 유지 (FE 전용) |
| 구조 | 중앙 라벨 파일(`src/i18n/labels.ts`) + StatusBadge 통일 |
| i18n 라이브러리 | 미도입 (한국어 단일 언어, 수동 매핑으로 충분) |
| Tenant 용어 | 영어 `Tenant` 유지 (통일 안 함) |
| Audit Chain | `감사 체인`으로 한국어화 |
| AAGUID 정책(라벨) | `AAGUID 정책` 유지 (이미 혼합어) |
| AAGUID 모드 | ANY=전체 허용, ALLOWLIST=허용 목록, DENYLIST=차단 목록 |

## 3. 구조

### 3.1 새 파일: `admin-ui/src/i18n/labels.ts`

상태 enum → 한국어 표시 라벨 매핑과 헬퍼를 모은다. 미매핑 값은 원본 그대로 fallback하여
새 상태값이 추가돼도 화면이 깨지지 않게 한다(안전한 degrade).

```ts
// 상태 배지 공용 라벨 — 값(enum)은 영어 유지, 화면 표시만 변환.
export const STATUS_LABELS: Record<string, string> = {
  ACTIVE: '활성',
  SUSPENDED: '정지',
  REVOKED: '회수',
  EXPIRED: '만료',
  PENDING: '대기',
  INTACT: '정상',
  TAMPERED: '위변조',
  OPEN: '처리중',
  RESOLVED: '해결',
  SUCCESS: '성공',
  FAILED: '실패',
  ROTATED: '교체됨',
  SYNCED: '동기화됨',
  SKIPPED: '건너뜀',
};

/** 상태 enum 값을 한국어 표시 라벨로. 미매핑 값은 원본 그대로 반환. */
export const statusLabel = (v: string): string => STATUS_LABELS[v] ?? v;
```

### 3.2 두 가지 렌더 경로 통일

상태 배지는 현재 두 방식으로 렌더된다 — 둘 다 `statusLabel`을 경유하게 한다.

1. **`StatusBadge.tsx` (공통 컴포넌트)** — 색상 매핑(값 기준)은 그대로 두고, 표시 텍스트만
   `statusLabel(status)`로 변환.
2. **페이지 하드코딩 배지** — 직접 문자열 대신 `statusLabel(...)` 호출로 교체:
   - `AuditChainPage.tsx`: INTACT / TAMPERED / OPEN / RESOLVED
   - `CredentialDetailDialog.tsx`: SUCCESS / FAILED (ResultBadge)
   - `TenantOverview.tsx`: INTACT / TAMPERED

## 4. 변경 대상

### 4.1 상태 배지 (값 유지, 표시만 변환)

| 영어 값 | 한국어 표시 | 사용처 |
|---|---|---|
| ACTIVE / SUSPENDED | 활성 / 정지 | 테넌트, Admin User |
| ACTIVE / EXPIRED / REVOKED | 활성 / 만료 / 회수 | API Key |
| ACTIVE / REVOKED | 활성 / 회수 | Credential |
| ACTIVE / PENDING / SUSPENDED | 활성 / 대기 / 정지 | Admin User |
| INTACT / TAMPERED | 정상 / 위변조 | Audit Chain |
| OPEN / RESOLVED | 처리중 / 해결 | Incident |
| SUCCESS / FAILED | 성공 / 실패 | Auth Event |
| ROTATED / SYNCED / SKIPPED | 교체됨 / 동기화됨 / 건너뜀 | Signing Key, MDS |

### 4.2 네비/탭/헤더/모드 라벨 (직접 문자열 교체)

- **사이드바(`Sidebar.tsx`)·`App.tsx`**: Activity→활동, Audit Chain→감사 체인,
  Audit Chain Monitor→감사 체인 모니터, License→라이선스, Audit Logs→감사 로그,
  Funnel→퍼널, API Keys→API 키
  - **`Tenants` 네비 라벨은 영어 유지** — "Tenant 용어는 통일하지 않는다"는 결정에 따라
    메뉴명도 영어로 둔다(테이블 헤더 `Tenant`와 일관).
- **테이블 헤더**: Slug→슬러그, Status→상태, Key Prefix→키 접두사
  (`Tenant`·`RP ID`·`Credentials` 헤더는 영어 유지 — Credentials는 WebAuthn 고유 용어 보존)
- **AAGUID 정책 모드(`AaguidPolicyTab.tsx`)**: ANY→전체 허용, ALLOWLIST→허용 목록,
  DENYLIST→차단 목록
- **다이얼로그 잔여 영어**: from/to→시작일/종료일, ON/OFF→켜짐/꺼짐

## 5. 영어 유지 (보존 — WebAuthn/FIDO 기술 용어)

다음은 **절대 한국어화하지 않는다**:

- 필드/속성명: `rpId`, `rpName`, `origins`, `attestation`, `attestationConveyance`,
  `userVerification`, `timeoutMs`, `transports`
- 약어/고유명사: `AAGUID`, `MDS`, `FIDO2`, `WebAuthn`, `credential`(필드명), `ceremony`
- WebAuthn enum 선택지: `REQUIRED / PREFERRED / DISCOURAGED`,
  `NONE / INDIRECT / DIRECT / ENTERPRISE` (스펙 값 — 기존처럼 한글 설명만 병기)
- `Tenant` (네비/헤더 라벨) — 아키텍처 용어로 영어 유지

## 6. 검증

- `tsc --noEmit` exit 0 (타입 안전성)
- enum 값 비교/필터 로직 무영향 확인: 값은 영어 그대로이므로 `=== 'ACTIVE'` 등 비교가
  표시 변경과 분리됨을 grep으로 점검
- dev admin-app 재기동 후 browse 스크린샷으로 주요 화면 육안 확인:
  테넌트 목록/상세, Audit Chain(상태 배지 + Incident), Credential 상세, API Keys, Admin Users

## 7. 범위 밖 (이번에 안 건드림)

- BE/DB/API 일절 무변경
- WebAuthn enum 선택지 영어 유지
- i18n 라이브러리 미도입 (다국어 필요 시 향후 react-i18next 검토)
- `activityLabels.ts`는 이미 한국어 매핑이 구현돼 있어 대상 외
