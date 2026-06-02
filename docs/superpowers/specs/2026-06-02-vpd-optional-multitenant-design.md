# 멀티테넌트 격리: 앱 레벨 + VPD Optional 설계

- **작성일**: 2026-06-02
- **배경**: 현재 테넌트 격리는 Oracle VPD(DBMS_RLS)에 강하게 의존. VPD는 Enterprise Edition 전용(SE2 미지원). 배포 대상에 SE2가 포함되어, VPD 없이도 격리되도록 앱 레벨 격리를 주 방어선으로 승격하고 VPD를 Optional로 전환한다.
- **핵심 결정**: 격리를 2층(앱 레벨 @Filter + VPD)으로 분리. @Filter는 항상 ON, VPD는 `passkey.vpd.enabled`로 선택. 두 층 모두 `TenantContextHolder`를 공통 진실 원천으로 사용.

## 목표

1. **SE2에서 부팅·격리 정상**: VPD 없이도 cross-tenant 누출 없음.
2. **EE/XE에서 이중 방어**: @Filter(앱) + VPD(DB) 동시 적용.
3. **VPD를 DB Edition에 따라 선택**: 설정 한 줄로 on/off.
4. **기존 격리 동작 보존**: passkey-app fail-closed, admin PLATFORM_OPERATOR 전체 조회.

## 비목표 (YAGNI)

- 기존 운영 DB 재마이그레이션 / flyway repair: **불필요** (새 환경에만 Flyway 사용, 기존 DB는 재마이그레이션 안 함).
- 신규 V38 마이그레이션: **불필요** (V3~V35 조건화로 충분).
- ADMIN_USER/AUDIT_LOG에 VPD 부착: 범위 밖(현재도 VPD 미부착, 앱 레벨 게이팅 유지).

---

## 아키텍처: 2층 방어 + 독립 스위치

```
TenantContextHolder (ThreadLocal, 기존) — 현재 테넌트 = 단일 진실 원천
   │
   ├──→ [Layer 1] Hibernate @Filter    ← 항상 ON. ORM이 모든 SELECT에
   │     tenant_id = :tenantId 자동 주입    tenant 술어 주입 (앱 레벨, SE2 주 방어선)
   │
   └──→ [Layer 2] VPD (TenantAwareDataSource)  ← Optional. passkey.vpd.enabled=true
         set_tenant(hex) → DB가 술어 강제           일 때만. EE/XE에서 이중 방어
```

- 두 층 모두 `TenantContextHolder.get()`을 읽는다.
- Layer 1은 VPD 유무와 무관하게 격리를 보장하는 **주 방어선**.
- Layer 2는 "있으면 더 강한" 보조 방어선(DB 커널 강제).

---

## Layer 1: Hibernate @Filter (앱 레벨, 항상 ON)

### 대상 엔티티 (7개)
`Credential`, `ApiKey`, `TenantAllowedOrigin`, `TenantAcceptedFormat`, `TenantAaguidPolicy`, `TenantAaguidPolicyEntry`(@ElementCollection), `TenantWebauthnSnapshot`.

### 선언
각 엔티티(또는 공통 위치 1회 + 각 엔티티 @Filter):
```java
@FilterDef(name = "tenantFilter",
    parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
```
**구현 시 검증**: `tenant_id`는 RAW(16), 파라미터는 UUID. Hibernate UUID→RAW 바인딩이 안 맞으면 `condition`에 변환(예: `tenant_id = :tenantId` + 커스텀 type, 또는 hex 비교) 필요. 실제 생성 SQL로 확정.

### 활성화: TenantFilterAspect (신규)
`@Transactional` 진입 시 현재 Hibernate Session에 필터를 켠다(Spring AOP @Around).
```java
UUID tid = TenantContextHolder.get();
if (tid != null) {
    session.enableFilter("tenantFilter").setParameter("tenantId", tid);
}
// null이면 enable 안 함 → 필터 미적용
```
- 위치: `core/vpd/` (또는 `core/config/`).
- 이 코드베이스의 모든 DB 접근이 서비스 `@Transactional`을 거치므로 트랜잭션 경계 정렬이 자연스럽다.

### fail-closed (passkey-app)
- passkey-app은 ceremony마다 항상 tenant가 있어야 한다. `ApiKeyAuthFilter`가 이미 context를 보장(없으면 401)하므로 정상 흐름에선 항상 enable.
- 추가 가드: 서비스 진입점의 `requireTenant()`로 context null이면 예외(현재 AuthenticationStartService:72-76 패턴 존재).

### native query 예외
`@Filter`는 JPQL/Criteria에만 적용되고 **nativeQuery=true에는 적용 안 됨**. `CredentialRepository.searchByTenantId`(nativeQuery)는 이미 `WHERE tenant_id = :tid`를 명시하므로 그대로 둔다. JPQL `@Query`(findCredentialIdsByUserHandle 등)는 @Filter가 적용된다.

---

## Layer 2: VPD Optional (스위치 + 마이그레이션)

### 런타임 스위치 — CoreDataSourceConfig
```java
@Bean @Primary
DataSource dataSource(HikariDataSource physical,
                      @Value("${passkey.vpd.enabled:false}") boolean vpdEnabled) {
    return vpdEnabled
        ? new TenantAwareDataSource(physical)  // set_tenant/clear_tenant 호출
        : physical;                             // 그대로 — VPD 미사용
}
```
- `false`면 `set_tenant`를 호출하지 않음 → SE2(ctx_pkg/APP_CTX 없음)에서도 런타임 에러 없음.
- 기본값 `false` (앱 레벨이 기본, VPD는 opt-in). 기존 EE/XE 환경은 명시적으로 `passkey.vpd.enabled: true` 설정.

### 마이그레이션 — Edition 감지 후 조건부 ADD_POLICY
V3/V8/V20/V35의 `DBMS_RLS.ADD_POLICY`를 PL/SQL로 감싸서 fine-grained access control 가능 시에만 실행:
```sql
DECLARE
  v_fgac NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_fgac FROM v$option
   WHERE parameter = 'Fine-grained access control' AND value = 'TRUE';
  IF v_fgac = 1 THEN
    DBMS_RLS.ADD_POLICY( ... );  -- 기존 호출 그대로
  END IF;                         -- SE2: skip, 마이그레이션 성공
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -439 THEN NULL;  -- ORA-00439 이중 안전
    ELSE RAISE;
    END IF;
END;
/
```
- 마이그레이션 파일 자체는 모든 Edition에서 성공 → Flyway 안 깨짐.
- `tenant_predicate` 함수, `ctx_pkg`/`APP_CTX` 생성은 SE2에서도 무해하므로 유지(또는 동일 조건화 — 구현 시 결정).

### baseline: repair 불필요
- 새 환경에만 Flyway 사용. 빈 DB → 수정된 V3~V35를 처음 실행 → checksum mismatch 없음.
- 기존 V35 적용 EE/XE DB는 재마이그레이션하지 않음 → validate 안 거침 → repair 불필요.

---

## admin-app: 격리 일원화 + TenantBoundary 유지

- **@Filter를 admin-app에도 적용**: RP_ADMIN은 `TenantContextHolder` set → 자기 tenant만. PLATFORM_OPERATOR는 context 미설정 → 전체 조회(의도대로).
- **TenantBoundary 유지**: 쓰기 권한 검증(`assertCanAccessTenant`)·role 분기(`currentTenantScope`)는 그대로. @Filter=읽기 격리, TenantBoundary=권한/쓰기 검증으로 역할 분담.
- admin-app은 VPD를 EXEMPT로 우회하므로 `passkey.vpd.enabled`와 무관하게 @Filter가 유일한 ORM 격리 → SE2/EE 동일 동작.
- **구현 시 검증**: admin 요청에서 RP_ADMIN일 때 `TenantContextHolder`를 set하는 진입점이 현재 있는지 확인. 없으면 role 기반 context set 컴포넌트 추가.

---

## 테스트: VPD on/off 두 모드

- **VpdIsolationIT (기존, 유지)**: `vpd.enabled=true`. Testcontainers Oracle XE에서 DB 레벨 격리 검증(기존 5 시나리오).
- **AppLevelIsolationIT (신규)**: `vpd.enabled=false`. @Filter만으로 격리되는지 검증. 특히 위험 4메서드(`findByUserHandle`, `findCredentialIdsByUserHandle`, `findByCredentialIdForUpdate`, `findOwnedForUpdate`)가 cross-tenant 행을 반환하지 않는지 명시 검증.
- **공통**: tenant A context에서 B 조회 → 0행. context 없이 passkey-app 경로 → fail-closed.

---

## 영향 범위

| 구성요소 | 변경 | 위치 |
|---|---|---|
| 7개 엔티티 | `@FilterDef`/`@Filter` 선언 | core/entity/ |
| `TenantFilterAspect` (신규) | @Transactional 진입 시 context 기반 filter enable | core/vpd/ 또는 core/config/ |
| `CoreDataSourceConfig` | `passkey.vpd.enabled`로 TenantAwareDataSource 조건화 | core/config/ |
| V3/V8/V20/V35 | ADD_POLICY를 Edition 감지 PL/SQL로 감쌈 | core/.../db/migration/ |
| `bootstrap-vpd.sql` | ctx_pkg/APP_CTX 조건화(또는 무해하니 유지) | scripts/ |
| admin 요청 필터 | RP_ADMIN context set (없으면 추가) | admin-app/ |
| 테스트 | VpdIsolationIT 유지 + AppLevelIsolationIT 신규 | core/test/vpd/ |
| 설정 | `passkey.vpd.enabled: false` 기본 + 환경별 override | application*.yml |

## 핵심 불변식 (검증 대상)

1. `vpd.enabled=false` + SE2 → 앱 부팅 정상, @Filter로 격리 보장.
2. `vpd.enabled=true` + EE/XE → @Filter + VPD 이중 방어.
3. 두 모드 모두 위험 4메서드가 cross-tenant 누출 없음.
4. PLATFORM_OPERATOR는 두 모드 모두 전체 조회 가능.

## 구현 시 확정할 항목 (열린 질문)

1. **RAW(16) ↔ UUID 바인딩**: @Filter condition에서 tenant_id(RAW) vs UUID 파라미터 타입 정합. 실제 생성 SQL 확인 후 확정.
2. **admin RP_ADMIN context 진입점**: 현재 set 지점 존재 여부. 없으면 추가.
3. **TenantFilterAspect 활성화 위치**: @Transactional AOP vs Hibernate Session 이벤트. AOP 1차 채택, 트랜잭션 밖 조회 발견 시 재검토.
4. **bootstrap-vpd.sql 조건화 범위**: ctx_pkg/APP_CTX를 SE2에서 만들지 말지(무해하므로 유지 가능).
