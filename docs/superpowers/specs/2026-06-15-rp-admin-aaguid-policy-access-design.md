# RP_ADMIN 의 테넌트 AAGUID 정책 접근 허용 — 설계

- 작성일: 2026-06-15
- 대상 모듈: `admin-app` (백엔드만; 프론트 무변경)
- 상태: 승인됨 (구현 대기)

## 1. 문제 진단

RP_ADMIN 으로 어드민에 접속해 테넌트의 "AAGUID 정책" 탭을 열면 "AAGUID policy 로드 실패"
토스트가 뜨고 정책 페이지가 동작하지 않는다.

확정된 원인 — 프론트와 백엔드의 권한 정책 불일치:

- 프론트 `admin-ui/src/pages/TenantDetailPage.tsx` 는 AAGUID 탭을 **모든 역할에게 무조건**
  노출한다 (탭 배열에 role 조건 없음; 유일하게 Audit 탭만 `me.role === 'PLATFORM_OPERATOR'`
  체크가 있다).
- 백엔드 `admin-app/.../policy/AaguidPolicyController.java` 는 GET/PUT 두 엔드포인트 모두
  `@PreAuthorize("hasRole('PLATFORM_OPERATOR')")` 로 RP_ADMIN 을 차단한다 → 403.
- 프론트 `AaguidPolicyTab.tsx` 의 `useEffect` 가 `aaguidPolicyApi.get()` 실패를 catch 해서
  "AAGUID policy 로드 실패" 토스트를 띄운다 (`AaguidPolicyTab.tsx:45-48`). 이것이 사용자가 본 화면.

대조군 (테넌트 하위 컨트롤러 게이팅):

| 엔드포인트 | 현재 @PreAuthorize | 성격 |
|---|---|---|
| `GET/DELETE /tenants/{id}/credentials` | `hasAnyRole(PO, RP_ADMIN)` + 경계검사 | per-tenant |
| `.../webauthn-config/diff` | `hasAnyRole(PO, RP_ADMIN)` + 경계검사 | per-tenant |
| `GET /tenants/{id}/funnel` | `hasAnyRole(PO, RP_ADMIN)` | per-tenant |
| **`GET/PUT /tenants/{id}/aaguid-policy`** | **`hasRole(PO)` only** | per-tenant URL 인데 PO 전용 |
| `GET/PUT /security-policy` | `hasRole(PO)` only | 전역(테넌트 무관) |

AAGUID 정책은 URL 이 `/tenants/{tenantId}/aaguid-policy` 인 **per-tenant 리소스**다
(security-policy 는 테넌트 무관 전역 `/security-policy`). per-tenant 그룹과 동일한 권한
모델이어야 한다.

## 2. 결정 / 목표

- AAGUID 정책을 per-tenant 리소스로 취급한다. **RP_ADMIN 이 자기 테넌트의 AAGUID 정책을
  읽고(GET) 쓸(PUT) 수 있다.** credentials / webauthn-config 와 동일한 권한 모델로 통일.
- 보안 필수 사항: RP_ADMIN 허용 시 **테넌트 경계 검사(IDOR 방지)** 를 함께 넣는다.

비목표:

- 프론트 변경 (탭은 이미 노출되며, API 가 200 을 주면 정상 동작).
- 읽기/쓰기 분리(읽기만 허용) — 이번엔 읽기+쓰기 모두 허용.
- security-policy 등 전역 정책의 권한 변경.

## 3. 아키텍처

백엔드 2개 파일 + 테스트. 핵심은 **role 게이트 확대 + IDOR 방지 경계 검사**.

### 3.1 AaguidPolicyController — role 게이트 확대
GET 과 PUT 의 `@PreAuthorize` 를 둘 다 다음으로 바꾼다:

```java
@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
```

이것은 coarse 게이트(역할 수준)다. 어느 테넌트인지까지는 보지 않으므로, 테넌트 경계는
서비스에서 강제한다(3.2).

### 3.2 AaguidPolicyService — 테넌트 경계 검사 추가 (보안 필수)
`CredentialAdminService` 패턴을 그대로 따른다. `TenantBoundary` 를 생성자 주입하고,
`get()` 과 `update()` 진입 시 **가장 먼저** 호출한다:

```java
tenantBoundary.assertCanAccessTenant(tenantId);
```

`TenantBoundary.assertCanAccessTenant(UUID)` 의 동작(`admin-app/.../auth/TenantBoundary.java`):
- PLATFORM_OPERATOR → 무제한 통과
- RP_ADMIN + `tenantId == 본인 tenantId` → 통과
- RP_ADMIN + 다른 테넌트 → `ACCESS_DENIED`
- 그 외 → `ACCESS_DENIED`

**호출 순서가 중요하다**: `assertCanAccessTenant(tenantId)` 를 `repo.findById(tenantId)`
**보다 먼저** 둔다. 그래야 다른 테넌트의 정책 존재/부재 여부가 에러 메시지로 새어나가지
않는다(정보 노출 차단). 현재는 컨트롤러의 `hasRole(PO)` 가 우연히 RP_ADMIN 을 막아
경계 검사 없이도 IDOR 이 불가능했지만, RP_ADMIN 을 허용하면 경계 검사가 반드시 필요하다.

### 3.3 프론트 — 변경 없음
`TenantDetailPage.tsx` 의 탭 노출, `AaguidPolicyTab.tsx`, `aaguidPolicy.ts` 모두 그대로.
백엔드가 200 을 주면 기존 로직이 정상 렌더한다.

## 4. 데이터 흐름

```
RP_ADMIN → AAGUID 탭 클릭
  → GET /admin/api/tenants/{myTenantId}/aaguid-policy
    → @PreAuthorize hasAnyRole(PO, RP_ADMIN)            (coarse 통과)
    → AaguidPolicyService.get(tenantId)
      → tenantBoundary.assertCanAccessTenant(tenantId)  (fine; 본인 테넌트면 통과)
      → repo.findById(tenantId) → View(200)
    → 프론트 정상 렌더 (에러 토스트 없음)
```

PUT(저장)도 동일 + 기존 audit 로깅(`AAGUID_POLICY_UPDATED`) 유지. 다른 테넌트 접근 시
`assertCanAccessTenant` 가 `ACCESS_DENIED` → `GlobalExceptionHandler` 가 403 → 프론트
에러 토스트(의도된 보안 경계 동작).

## 5. 에러 처리

- 다른 테넌트 tenantId 로 접근 → `ACCESS_DENIED`(403). 정상적 보안 거부.
- 정책 미존재 → 기존 `IllegalStateException` 동작 유지. 단 경계 검사가 먼저 실행되므로,
  본인 테넌트가 아닌 경우엔 "not found" 가 아니라 403 이 먼저 반환된다(정보 노출 방지).
- PLATFORM_OPERATOR 동작은 100% 불변(assertCanAccessTenant 가 PO 를 무조건 통과시킴).

## 6. 테스트

1. **`RpAdminBoundaryIT` 확장** (현재 AAGUID 커버리지 없음 — Explore 가 확인한 갭).
   기존 번호 매긴 단계(`── N. ...`) 패턴을 따라 RP_ADMIN(bob) 시나리오에 4 케이스 추가:
   - `GET /tenants/{myTenant}/aaguid-policy` → 200
   - `GET /tenants/{otherTenant}/aaguid-policy` → 403
   - `PUT /tenants/{myTenant}/aaguid-policy` → 200
   - `PUT /tenants/{otherTenant}/aaguid-policy` → 403
   (이 IT 는 실제 REST + 쿠키/CSRF 흐름. PUT 바디는 기존 tenants PUT 케이스의 JSON 작성
   방식을 따른다 — mode/mdsStrict/entries.)

2. **`AaguidPolicyControllerSecurityTest` 신규** (없음; `SecurityPolicyControllerSecurityTest`
   를 템플릿으로). `@WebMvcTest(AaguidPolicyController.class)` 슬라이스로:
   - PLATFORM_OPERATOR(@WithMockUser roles=PLATFORM_OPERATOR) → GET/PUT 200(서비스 mock)
   - RP_ADMIN → GET/PUT 200 (서비스 mock; @PreAuthorize 통과 확인)
   - 익명/권한없음 → 401/403
   슬라이스 컨텍스트는 admin-app 의 기존 @WebMvcTest 들이 쓰는 MockBean 세트
   (AdminSecurityConfig 가 요구하는 TenantBoundary/SecurityPolicyService 등)를 동일하게
   선언해야 컨텍스트가 로드된다(메모리: 슬라이스 테스트 MockBean 회귀 주의).

3. **기존 `AaguidPolicyCeremonyIT` 회귀** 통과 확인 (정책 적용 ceremony 경로 불변).

## 7. 영향 범위 / 리스크

- 변경 파일:
  - `admin-app/.../policy/AaguidPolicyController.java` — @PreAuthorize 2곳
  - `admin-app/.../policy/AaguidPolicyService.java` — TenantBoundary 주입 + get/update 에
    경계 검사(조회보다 먼저)
  - `admin-app/.../auth/RpAdminBoundaryIT.java` — AAGUID 4 케이스
  - `admin-app/.../policy/AaguidPolicyControllerSecurityTest.java` — 신규
  - 프론트 무변경
- 리스크 낮음: PLATFORM_OPERATOR 경로 불변, RP_ADMIN 경로만 신규.
- 보안 강화: 서비스 레벨에 잠재했던 IDOR(경계 검사 부재)을 함께 차단.
