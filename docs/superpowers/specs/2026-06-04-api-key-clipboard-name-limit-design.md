# API Key 생성 — 클립보드 복사 수정 + 이름 제한 정합성 설계

작성일: 2026-06-04
작성자: JhyunJung (with Claude)
상태: 승인됨 → 구현 계획 작성 단계

## 배경 / 문제

어드민 콘솔에서 테넌트의 API key를 생성할 때 두 가지 문제가 있다.

### 문제 1 — 클립보드 복사가 동작하지 않음

`admin-ui/src/pages/tenant/ApiKeysTab.tsx:321`:
```jsx
onClick={() => { navigator.clipboard?.writeText(issued.plaintext); setCopied(true); }}
```
- `navigator.clipboard`는 **secure context(HTTPS 또는 localhost)에서만** 존재한다. 운영/스테이징을 HTTP + IP/도메인으로 접속하면 `navigator.clipboard === undefined`가 되고, optional chaining(`?.`) 때문에 복사가 조용히 스킵된 뒤 `setCopied(true)`만 실행된다 → "복사됨" 표시는 뜨지만 실제로는 복사되지 않는다.
- `writeText`가 반환하는 Promise를 await/catch하지 않아 실패해도 무시된다.
- 폴백(`document.execCommand('copy')`)이 없다.

### 문제 2 — 이름 제한 안내문 + 실제 적용 여부

- 백엔드: `admin-app/.../apikey/ApiKeyAdminDto.java:19` — `@NotBlank @Size(max = 256)`. 빈값 금지 + 최대 256자가 **이미 적용**돼 있다. 문자 종류 제한은 없다.
- 프론트: 입력 hint(`ApiKeysTab.tsx:243`)는 "배포 환경이나 용도를 짧게..."뿐, **길이 제한(256자) 안내가 없고 `maxLength` 속성도 없다**. 사용자가 한도를 초과해도 발급 버튼을 누른 뒤에야 서버 400을 받는다.
- 즉 길이 제한은 백엔드에 있으나 프론트가 안내·강제하지 않고, 256자는 "이름"으로는 과도하게 길다.

## 목표

- 클립보드 복사가 insecure context(HTTP)를 포함한 모든 환경에서 동작하고, 실패 시 정직한 피드백을 준다.
- API key 이름 제한을 합리적 길이(64자)로 정하고, 백엔드 검증·프론트 안내·프론트 강제가 같은 값을 가리키게 한다.

## 결정 사항 (브레인스토밍 합의)

1. 클립보드: **폴백 추가 + 실패 시 정직한 피드백**. `navigator.clipboard` 우선, 없거나 실패하면 `execCommand('copy')` 폴백, 둘 다 실패하면 toast 안내. 성공했을 때만 "복사됨" 표시.
2. 이름 제한: **길이 축소 + 프론트 안내/사전검증**. 문자 종류 제한은 두지 않음(한글 용도명 등 자유).
3. 길이 값: **1~64자**. (`@NotBlank`가 빈값을 막으므로 실질 1~64.)
4. DB 컬럼: **유지**(`ApiKey.NAME length=256`, `VARCHAR2(256)`). 검증만 64로 축소 → 기존 데이터 영향 없음, 마이그레이션 불필요. (자율 결정 — DB 축소는 기존 64자 초과 데이터 손상 위험이 있어 배제.)

## 설계

### 변경 1 — 클립보드 복사: 폴백 + 정직한 피드백

`ApiKeysTab.tsx`의 복사 버튼 인라인 핸들러를 작은 복사 헬퍼로 교체한다.

복사 헬퍼 `copyToClipboard(text: string): Promise<boolean>`:
1. `navigator.clipboard?.writeText`가 있으면 `await` 후 성공 시 `true`.
2. 없거나 던지면 → `document.execCommand('copy')` 폴백: 임시 `textarea`를 만들어 값 설정·`select()`·`execCommand('copy')`·제거.
3. 둘 다 실패하면 `false`.

버튼 핸들러:
```ts
const ok = await copyToClipboard(issued.plaintext);
if (ok) setCopied(true);
else toast({ kind: 'warn', title: '복사 실패', message: '클립보드 복사에 실패했습니다. 키를 직접 선택해 복사하세요.' });
```
- 성공했을 때만 "복사됨"을 표시 → 가짜 피드백 제거.
- 헬퍼는 재사용 가능한 작은 순수 함수로 분리(파일 비대화 방지). 다른 곳에서 복사가 필요해지면 util로 승격.

### 변경 2 — 이름 제한

**(a) 백엔드 — 검증만 64자로 축소** (`ApiKeyAdminDto.java:19`)
```java
@NotBlank @Size(max = 64) String name,   // 기존 max = 256
```
- DB 컬럼(`ApiKey.NAME length=256`)은 유지. 검증만 줄이므로 기존 64자 초과 이름의 조회·표시·rotation·revoke에 영향 없음, 마이그레이션 0.
- 문자 종류 제한 없음. `@NotBlank`가 빈값/공백을 막으므로 최소 길이 별도 불필요.

**(b) 프론트 — 안내문 + maxLength + 카운터** (`ApiKeysTab.tsx` 발급 폼, 243~244행)
- `const NAME_MAX = 64;` 상수를 한 번 정의(안내문·maxLength·카운터가 드리프트하지 않게).
- 입력에 `maxLength={NAME_MAX}` → 브라우저가 64자 초과 입력을 물리적으로 차단.
- hint 문구에 `"(최대 64자)"` 추가.
- 입력 아래 글자 수 카운터(예: `${name.length} / ${NAME_MAX}`)를 작게 표시.
- 발급 버튼 `disabled={!name || scopes.length === 0}`는 유지(빈값 방어). maxLength로 상한이 강제되므로 추가 클라이언트 검증 로직은 불필요(YAGNI).

## 테스트 전략

- 백엔드: `name` 64자 통과 / 65자 검증 실패, 빈값·공백 실패. 기존 admin-app 검증 테스트 패턴 재사용.
- 프론트(복사 헬퍼): `navigator.clipboard.writeText` mock로 (1) 성공→true, (2) clipboard 부재→execCommand 폴백, (3) 둘 다 실패→false. jsdom엔 기본 없으므로 vitest stub.
- 프론트(UI): 성공 mock→"복사됨" 표시 / 실패 mock→toast(warn) 호출, 가짜 "복사됨" 미표시 검증. 입력에 `maxLength=64` 존재 + 카운터가 길이 반영.

## 영향 범위 / 리스크

- 변경 파일: `ApiKeyAdminDto.java`(1줄), `ApiKeysTab.tsx`(복사 핸들러 + 발급 폼), + 테스트. 좁고 격리됨.
- 호환성: 백엔드 64자 축소는 신규 요청에만 적용. 기존 키 영향 없음. DB 컬럼 유지로 마이그레이션 0.
- 회귀 위험 낮음: 복사는 폴백 추가로 secure context에선 동작 동일, insecure에선 개선. 이름은 상한만 좁아짐.

## 변경 요약

| # | 위치 | 변경 | 효과 |
|---|------|------|------|
| 1 | `ApiKeysTab.tsx` 복사 버튼 | clipboard 우선 + execCommand 폴백 + 성공 시에만 "복사됨", 실패 시 toast | HTTP 포함 모든 환경 복사 동작, 가짜 피드백 제거 |
| 2a | `ApiKeyAdminDto.java:19` | `@Size(max=256)` → `@Size(max=64)` (DB 컬럼 유지) | 새 발급 이름 64자 제한 실제 적용, 마이그레이션 불필요 |
| 2b | `ApiKeysTab.tsx` 발급 폼 | `maxLength=64` + "최대 64자" 안내 + 글자 수 카운터 (`NAME_MAX` 상수) | 제한 안내 + 입력 단계 강제, 서버 400 전 차단 |
