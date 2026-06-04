# 어드민 로그인 세션 타임아웃 정합성 수정 — 설계

작성일: 2026-06-04
작성자: JhyunJung (with Claude)
상태: 승인됨 → 구현 계획 작성 단계

## 배경 / 문제

어드민 로그인 세션 만료 알림이 "30분"이라고 표시되지만 실제로는 그보다 훨씬 일찍 뜬다. 코드 확인 결과 세 가지 불일치를 발견했다.

### 발견 1 — 프론트 idle 모달이 데모용 90초로 하드코딩

`admin-ui/src/extras/IdleSessionModal.tsx:21`:
```js
}, 90 * 1000); // 90s for demo
```
모달 문구(55번 줄)는 "30분 동안 활동이 없으면..."이라고 말하지만, 실제 idle 타이머는 90초다. 90초 비활동 → 모달 표시 → 60초 카운트다운 → **총 약 2분 30초 만에 강제 로그아웃**.

### 발견 2 — "세션 연장" 버튼이 빈 함수

`admin-ui/src/App.tsx:163`:
```jsx
<IdleSessionModal onExtend={() => { /* refresh /me — Phase E3 */ }} onLogout={onLogout} />
```
연장 클릭 시 실제 백엔드 세션 갱신이 일어나지 않는다 (Phase E3 미구현).

### 발견 3 — 정책값이 실제 세션 만료에 연결되어 있지 않음

- `admin-app/src/main/resources/application.yml:25` — `session.timeout: PT30M` (Spring Session + Redis). 이것이 실제 세션 만료를 좌우하는 유일한 값.
- `sessionIdleTimeoutMinutes` 정책(`SecurityPolicy`, 운영자가 설정 화면에서 변경 가능, 기본 30분)은 DB에 저장·조회만 될 뿐, `setMaxInactiveInterval` 호출이 admin-app 어디에도 없어 **실제 세션에 적용되지 않는다**. 즉 설정 화면의 "세션 idle timeout (분)" 라벨이 거짓이다.

## 목표

- 프론트 경고 모달이 실제 백엔드 세션 만료(30분)와 일치하도록 한다.
- 단일 진실 소스(single source of truth)를 `sessionIdleTimeoutMinutes` 정책값으로 통일한다.
- "세션 연장" 버튼이 실제로 세션을 갱신하도록 구현한다.

## 결정 사항 (브레인스토밍 합의)

1. 목표 동작: **백엔드와 일치 (30분 idle − 60초 경고)**.
2. 값 소스: **정책 API의 `sessionIdleTimeoutMinutes`를 단일 소스로 사용**.
3. 정책 연동 범위: **정책값을 실제 백엔드 세션 만료에도 연결한다 (백엔드까지 수정)**.
4. 적용 타이밍: **로그인 성공 시 세션에 적용 (로그인 시점 스냅샷)**.
5. 연장 버튼: **이번에 구현 (`/me` 호출로 세션 갱신)**.

## 설계

단일 진실 소스 = `sessionIdleTimeoutMinutes` 정책값. 세 가지 독립 변경으로 구성한다.

### 변경 1 — 백엔드: 정책값을 실제 세션 만료에 적용 (근본 수정)

`AdminSecurityConfig.adminLoginSuccessHandler`(현재 `AdminSecurityConfig.java:202` 부근)에서 로그인 성공 시 현재 정책의 `sessionIdleTimeoutMinutes`를 읽어 세션에 스냅샷 적용한다.

```java
int idleMin = securityPolicyService.get().sessionIdleTimeoutMinutes();
req.getSession().setMaxInactiveInterval(idleMin * 60);
```

- `adminLoginSuccessHandler` Bean 메서드 파라미터에 `SecurityPolicyService`를 주입한다 (기존에 `AuditLogService`, `AdminUserRepository`, `Clock`, `ObjectMapper`를 받는 것과 동일 패턴).
- `application.yml`의 `session.timeout: PT30M`은 **로그인 전 세션의 기본값/폴백**으로 유지한다. 정책 기본도 30분이라 동작이 일치한다.
- MFA 분기(`req.getSession().setAttribute(MFA_PENDING_ATTR, ...)`)와 무관하게, 세션이 생성되는 시점에 적용되도록 한다.
- 효과: 정책 화면 "세션 idle timeout (분)" 라벨이 진실이 된다. 운영자가 15분으로 바꾸면 **다음 로그인부터** 실제 세션이 15분.

### 변경 2 — 프론트: idle 모달을 정책값과 동기화 (거짓 알림 수정)

`admin-ui/src/extras/IdleSessionModal.tsx`:

(a) 하드코딩된 90초를 정책값 기반 동적 계산으로 교체. 모달이 `idleTimeoutMinutes` prop을 받아 **(전체 idle 시간 − 60초 경고 리드타임)** 후 경고를 띄운다.

```js
const warnAfterMs = Math.max(idleTimeoutMinutes * 60 - 60, 10) * 1000;
```
- 최소 10초 가드로 정책이 극단적으로 짧아도(예: 1분) 음수/즉시발동 방지.
- 예) 정책 30분 → 29분 비활동 시 모달 → 60초 카운트다운 → 30분 정각 로그아웃 (백엔드와 일치).

(b) "30분" 하드코딩 문구(55번 줄)를 동적 문구로 교체.
```js
`보안을 위해 ${idleTimeoutMinutes}분 동안 활동이 없으면 자동으로 로그아웃됩니다. ...`
```

값 전달 경로: `App.tsx`에서 `securityPolicyApi.get()`으로 `sessionIdleTimeoutMinutes`를 읽어 `<IdleSessionModal idleTimeoutMinutes={...} />`로 주입. 정책 로드 전/실패 시 **30분 폴백**.

### 변경 3 — 프론트: "세션 연장" 버튼 실제 동작 구현

`admin-ui/src/App.tsx:163`의 빈 `onExtend`를 `getMe()` 호출로 교체한다.

```jsx
onExtend={() => { void getMe(); }}
```
- `getMe()`(`GET /admin/api/me`)가 서버에 도달하면 Spring Session의 `lastAccessedTime`이 갱신되어 `maxInactiveInterval` 카운트다운이 재시작된다 → 실제 세션 연장.
- 모달 내부는 기존대로 "세션 연장" 클릭 시 `onExtend()` 후 `setOpen(false)`. 모달이 닫히면 prop 기반 idle 타이머가 자연히 재시작.
- `getMe()`가 401(이미 백엔드 세션 만료)이면 기존 client의 401 핸들링이 로그인 화면으로 보낸다. 별도 처리 불필요.

## 테스트 전략

- 백엔드: `adminLoginSuccessHandler`가 정책값으로 `setMaxInactiveInterval`을 호출하는지 검증. 정책을 15분으로 설정 후 로그인 → 세션 `maxInactiveInterval == 900` 확인. 기존 `SecurityPolicyIT` 패턴 재사용.
- 프론트: `IdleSessionModal`에 `idleTimeoutMinutes={2}` 등 작은 값 + fake timer로 (전체−60초) 후 모달 표시, 문구에 해당 분 표시, 연장 클릭 시 `getMe` 호출을 vitest로 검증.

## 영향 범위 / 리스크

- 변경 파일: `AdminSecurityConfig.java`, `IdleSessionModal.tsx`, `App.tsx` (+ 테스트). 좁고 격리됨.
- 기존 동작 보존: 정책 기본 30분 → 현재 사용자 입장에서 백엔드 세션은 그대로 30분. 달라지는 건 "이제 진짜 30분에 맞춰 경고가 뜬다".
- `application.yml`의 `PT30M`은 폴백으로 유지 → 정책 로드 실패 시에도 안전.

## 변경 요약

| # | 위치 | 변경 | 효과 |
|---|------|------|------|
| 1 | `AdminSecurityConfig.java` (백엔드) | 로그인 시 정책 `sessionIdleTimeoutMinutes` → `setMaxInactiveInterval` | 정책 라벨이 진실, 실제 세션이 정책 따름 |
| 2 | `IdleSessionModal.tsx` + `App.tsx` (프론트) | 90초 하드코딩 → 정책값 동적, "30분" 문구 동적 | 거짓 알림 수정, 백엔드와 일치 |
| 3 | `App.tsx` (프론트) | 빈 `onExtend` → `getMe()` 호출 | 연장 버튼이 실제로 세션 갱신 |
