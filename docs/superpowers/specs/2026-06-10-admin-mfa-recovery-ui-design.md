# Admin "내 계정" MFA / 복구 코드 UI 개선 — 설계

작성일: 2026-06-10
대상: `admin-ui` (React 18 + Vite + TypeScript) — 백엔드 무변경
관련 파일:
- `admin-ui/src/pages/settings/RecoveryCodesModal.tsx`
- `admin-ui/src/pages/settings/AccountTab.tsx`
- `admin-ui/src/pages/MfaChallenge.tsx`
- (참조, 무변경) `admin-app/.../auth/RecoveryCodeService.java`, `admin-ui/src/api/types.ts`, `admin-ui/src/styles/tokens.css`

## 목적

어드민 콘솔 "설정 → 내 계정"의 2단계 인증(MFA) 및 복구 코드 사용자 경험을 다듬는다.
사용자가 요청한 5가지 항목을 한 묶음으로 처리한다.

## 배경 / 현재 동작

- "내 계정" = `SettingsPage` 의 `account` 탭 = `AccountTab`.
- MFA 켜기: `AccountTab` 에서 enroll(QR/수동키) → 6자리 확인 → 성공 시 `RecoveryCodesModal` 1회 표시.
- 복구 코드 포맷(백엔드 권위, `RecoveryCodeService`): Base32 알파벳 `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`
  (혼동 문자 `I/O/0/1` 제외), 4-4 그룹 = `XXXX-XXXX`, 대문자, 중간 대시 1개.
- 백엔드 `consume()` 의 `normalize()` 는 `trim().toUpperCase().replace(" ", "")` 만 수행 →
  **대시를 제거하지 않는다.** 따라서 검증이 매칭되려면 입력이 정확히 `XXXX-XXXX`(대시 포함, 대문자)여야 한다.
- 로그인 시 2단계 챌린지 = `MfaChallenge`. TOTP(6자리)·복구코드 모드 전환. 복구코드 input 은 현재
  사용자가 친 값을 그대로 보냄(대시/대문자 보정 없음).
- `Me` 타입 필드: `email`, `role`, `tenantId`, `mfaEnabled`, `mfaRequired`, `sessionIdleTimeoutMinutes`.
  `username`/`displayName` 은 **없다** → 파일명 계정 부분은 `email` 의 local part 사용.

## 변경 항목

### 1. 복구 코드 복사 기능 — 변경 없음 (이미 구현됨)

`RecoveryCodesModal.tsx:14-18, 55-57` 에 "복사" 버튼이 이미 존재한다. 클릭 시 10개 코드를
줄바꿈으로 클립보드에 복사하고 2초간 "복사됨" 피드백을 준다. **추가 작업 없음.**

### 2. 인쇄 기능 제거 (`RecoveryCodesModal.tsx`)

- `print()` 함수(line 28-34) 삭제.
- "인쇄" 버튼(line 59) 삭제.
- 남는 두 버튼(복사 / 다운로드)은 기존 `flex: 1` 로 가로 공간을 자동 재분배(50:50).
- `window.open`/`document.write` 의존 제거로 팝업 차단·about:blank 관련 잠재 이슈도 함께 사라진다.

### 3. 다운로드 txt 파일명 자동 설정 (`RecoveryCodesModal.tsx` + `AccountTab.tsx`)

- 규칙: `passkey-admin-recovery-codes-<account>-<YYYYMMDD>.txt`
  - 예: `alice@corp.com`, 2026-06-10 → `passkey-admin-recovery-codes-alice-20260610.txt`
- `<account>`: `email` 의 `@` 앞 local part 를 sanitize.
  - 소문자화, 허용 문자 `[a-z0-9._-]` 외는 `-` 로 치환, 연속 `-` 축약, 양끝 `-` 제거.
  - email 이 비었거나 sanitize 결과가 빈 문자열이면 `account` 세그먼트를 생략하고
    `passkey-admin-recovery-codes-<YYYYMMDD>.txt` 로 폴백(파일명이 깨지지 않도록).
- `<YYYYMMDD>`: 로컬 시간 기준. 표시용 `formatDate` 는 `2026. 06. 10.` 형식이라 파일명에 부적합 →
  파일명 전용 헬퍼(또는 모달 내 인라인)로 `getFullYear()/getMonth()+1/getDate()` 를 0-pad 하여 생성.
- 배선: `RecoveryCodesModal` 에 `accountEmail?: string` (또는 이미 만들어진 파일명 prefix) prop 추가.
  `AccountTab.tsx:204` 의 `<RecoveryCodesModal codes={...} />` 호출에 `me.email` 전달.
- `download()` 함수의 `a.download = 'recovery-codes.txt'` → 위 규칙으로 생성한 이름 사용.

### 4. 2단계 인증 디자인 개선 — 기존 디자인 시스템 내 정돈

새 디자인 언어/CSS 라이브러리 도입 금지. `tokens.css` 의 기존 토큰·클래스만 사용:
`--surface/--surface-2/-3`, `--border/-subtle/-strong`, `--text/-soft/-mute`,
`--accent*`, `--success*/--warning*/--danger*`, `--radius*`, `--mono`,
`.card/.card__head/.card__title/.card__body`, `.btn/.btn--primary/--danger/--ghost/--sm`,
`.badge/--success/--warning`, `.input/.input.mono`, `.label`, `.hint`, `.row`, `.stack-3/-4`.

**4a. AccountTab MFA 카드**
- enroll 진행 영역(QR → 수동 입력 키 → 인증 코드)의 단계감을 시각적으로 명확히 한다.
  기존 `.stack-3` 구조를 유지하되, 각 단계(스캔 / 수동키 / 코드 입력)를 구분선 또는
  소제목(`.label` 위계)으로 정돈. 단계 번호는 기존 토큰 범위 내 텍스트로 표현(별도 stepper 컴포넌트 신설 금지).
- 켜짐/꺼짐 상태 배지와 안내 `hint` 의 위계·간격 정리.
- 수동 입력 키(`enroll.secret`)에 복사 가능성 개선 여지가 있으면 기존 `CopyBtn`/`clipboard.ts` 재사용 검토(YAGNI: 요청 범위 밖이면 생략).
- 코드 입력 `input` 의 정렬·letterSpacing 일관성 정리.

**4b. MfaChallenge 화면**
- 6자리 TOTP 입력의 시각적 강조(현 `letterSpacing: 4`, center 정렬 유지·정돈).
- TOTP ↔ 복구코드 모드 전환 버튼의 명확성, 안내 문구(`mode` 별 sub 텍스트) 다듬기.
- 에러는 현재 toast 로만 표시 → 입력 필드 인접 인라인 힌트 보강 여부는 토큰 범위 내에서 가볍게(과설계 금지).
- 카드 컨테이너(현 인라인 style)의 간격·radius 를 토큰(`--radius-lg` 등)에 맞춤.

> 4번은 "리스킨"이 아니라 "정돈"이다. 레이아웃 골격과 클래스는 유지하고, 간격·위계·상태 표현만 손본다.

### 5. 복구코드 입력 시 자동 "-" (`MfaChallenge.tsx`)

- recovery 모드 input 의 `onChange` 에 포매터 적용(TOTP 모드의 `sanitizeCode` 와 대칭):
  ```
  function formatRecovery(v: string): string {
    const cleaned = v.toUpperCase().replace(/[^A-Z2-9]/g, '').slice(0, 8); // 8 chars max, Base32 alphabet
    return cleaned.length > 4 ? cleaned.slice(0, 4) + '-' + cleaned.slice(4) : cleaned;
  }
  ```
  - 대문자화 + 허용 문자(`A-Z2-9`)만 통과 → 혼동 문자/숫자 `0,1` 및 소문자·기호 자동 제거.
  - 4글자 초과 시 자동 대시 삽입 → `XXXX-XXXX`. 백스페이스 시 자연히 대시도 사라짐(파생 상태라 별도 처리 불필요).
- `submit()` 은 현재 `code.trim()` 을 보냄 → 포매터가 이미 `XXXX-XXXX`(대시·대문자) 형태를 보장하므로
  백엔드 매칭과 정확히 일치. (`trim()` 유지, 추가 정규화 불필요.)
- `ready` 판정: recovery 모드는 현재 `code.trim().length > 0`. 완성 형태(9자, `XXXX-XXXX`)일 때만
  활성화하도록 조일지(`/^[A-Z2-9]{4}-[A-Z2-9]{4}$/`) 여부는 구현 시 결정 — 과도하면 길이>0 유지.
- `placeholder` 를 대문자 예시로 조정 검토(`AB3F-2K7M`). 안내 문구 `복구 코드(xxxx-xxxx)` 도 대문자 표기로 통일 가능.

## 비범위 (YAGNI)

- 백엔드(`RecoveryCodeService`, MFA 컨트롤러/엔티티) 변경 없음.
- 복구 코드 재발급(regenerate) 신규 기능 없음 — 현재 enroll 시 1회 발급 흐름만.
- 새 디자인 시스템·UI 라이브러리·stepper 공용 컴포넌트 신설 없음.
- `MfaChallenge` 의 인증 로직/상태머신 변경 없음(입력 포매팅만).

## 테스트 / 검증

- 기존 admin-ui 테스트 스위트(있으면) 회귀 통과.
- 수동 검증:
  1. MFA 켜기 → 복구 코드 모달에 인쇄 버튼이 **없고**, 복사·다운로드만 있음.
  2. 다운로드 파일명이 `passkey-admin-recovery-codes-<email-local>-<YYYYMMDD>.txt` 로 떨어짐.
  3. 로그인 챌린지에서 복구코드 모드: 소문자/대시 없이 `ab3f2k7m` 입력 시 화면에 `AB3F-2K7M` 으로 자동 정형.
  4. 그 값으로 실제 검증 통과(백엔드가 대시 포함 대문자 기대 → 일치).
  5. AccountTab/MfaChallenge 가 light/dark 양 테마에서 깨지지 않음.

## 구현 순서(권장)

1. (2) 인쇄 제거 — 가장 단순, 독립.
2. (3) 파일명 자동 — 모달 prop 추가 + AccountTab 배선.
3. (5) 복구코드 자동 대시 — MfaChallenge 포매터.
4. (4) 디자인 정돈 — AccountTab + MfaChallenge, 위 변경 위에서 시각 마감.
