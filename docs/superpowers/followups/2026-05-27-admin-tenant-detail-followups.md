# Admin Tenant Detail followups

Spec: `docs/superpowers/specs/2026-05-27-admin-tenant-detail-design.md`
Plan: `docs/superpowers/plans/2026-05-27-admin-tenant-detail.md`

## Manual smoke result (T15)

운영자가 5 분 manual smoke 후 체크박스 채움.

- [ ] T15.1 docker compose ps — oracle + redis Up
- [ ] T15.2 admin-app + passkey-app 8081/8080 LISTEN
- [ ] T15.3 admin-ui 5173 LISTEN (vite dev)
- [ ] T15.4 /tenants 행 클릭 → /tenants/:id 이동, Overview 첫 화면
- [ ] T15.5 WebAuthn 탭 → displayName 변경 + 저장 + 새로고침 유지
- [ ] T15.6 Audit Log 페이지에서 `action` 필터 TENANT_UPDATE → row 보이고 payload.changedFields 표시
- [ ] T15.7 Credentials 탭 → 등록된 credential 보임 (sample-rp 통해 등록한 것)
- [ ] T15.8 Revoke 다이얼로그 → 마지막 8자 오입력 시 비활성 → 정확 입력 시 활성 → 회수 → 테이블에서 사라짐 + Audit Log 에 CREDENTIAL_REVOKE

## Deferred (spec § 6.2)

1. **Credential 엔티티 확장** — externalUserId / nickname / status / revokedAt 추가 (soft delete)
2. **Tenant rpId 변경 워크플로우** — credential 영향 분석 다이얼로그 + 재등록 안내
3. **WebAuthn 설정 diff 미리보기 모달** (+/- 라인 표시)
4. **Activity 페이지** + tenant Overview 의 "최근 활동 5건" — audit_log 에 tenant_id 컬럼 추가
5. **Audit Chain Monitor** — sparkline + tenant 별 검증 + 월간 PDF 보고서
6. **Funnel + metric 집계 인프라** (이벤트 source 확장)
7. **Role 모델 확장** — PLATFORM_OPERATOR / RP_ADMIN + AdminUser ↔ Tenant 매핑
8. **Admin 사용자 관리 UI** (추가/정지/초대)
9. **보안 정책 탭** (idle / password / MFA / CORS allowlist) DB 저장
10. **Tweaks 패널, ⌘K 액션 확장, CSV 내보내기**

## In-loop findings (phase 진행 중 codex review 가 잡은 결정·우회·deferred)

이번 phase 진행 중 codex review 가 잡은 항목 중 의도된 동작 또는 후속 예정인 것:

### T1 — MdsAaguidCache 이동
- **canonicalAaguid 의 가드 부재** — null/short array 입력 시 ArrayIndexOutOfBoundsException. 후속 phase 에서 가드 추가.
- **MdsAaguidCacheTest 없음** — MdsVerifierTest 가 mock 으로만 검증. 단위 테스트 보강 후속.

### T4 — CredentialAdminService
- **idToken 16자 prefix 노출 (sample-rp/SDK 패턴 재사용 안 함, admin-app 은 idToken 직접 안 보임)** — 해당 없음.
- **`@Authenticated` annotation method 레벨 미부착** — AdminSecurityConfig 의 baseline `.authenticated()` 가 `/admin/api/**` 전체 보호. 다른 admin controller 와 일관성 위해 그대로 둠.
- **MdsAaguidCache.Entry.statuses() 빈 list → null** — 현재 의도된 fallback. 후속 phase 에서 MDS description 필드 도입 시 교체.

### T5 — TenantAdmin update
- **rpId silent ignore** — 400 거부가 아닌 log + 무시. 서버측 defense-in-depth 후속.
- **saveAndFlush 가 no-op 에도 child SQL 일부 발생** — JPA dirty checking 한계. 후속에서 normalized equality 사전 검사.
- **TenantSnapshot allowedOrigins ↔ acceptedFormats Set 통일** — T5 followup 에서 Set 으로 변경 완료 (commit ad80628).
- **boolean primitive → @NotNull Boolean** — T5 followup 에서 변경 완료. 보안 정책 silently 약화 위험 차단.

### T6 — TenantAdminControllerUpdateIT
- **Hibernate orphan DELETE vs INSERT 순서 → unique constraint 위반** — `replaceAllowedOrigins`/`replaceAcceptedFormats` 에 `em.flush()` 추가로 해결.
- **AdminFlowIT extends 불가 (loginAs private)** — IT 가 Testcontainers + 로그인 셋업 inline 복사. T7 도 같은 패턴. 후속에 AdminITBase 공통화 권장.
- **audit assertion 엄격화** — `>=1 / contains` 대신 `exact count / exact list` 후속.
- **rpId silent-ignore 테스트 부재** — 단위 테스트 추가 후속.

### T7 — CredentialAdminControllerSecurityIT
- **Spring Data nullsLast() + 파생 query 호환성 버그** — `CredentialRepository.findAllByTenantId` 를 derived → JPQL `@Query` 로 변경하고 service 에서 `nullsLast()` 제거. ORDER BY 는 query 안에 명시.
- **native query 의 ORDER BY NULLS LAST 누락** — searchByTenantId 는 native 인데 ORDER BY 가 명시되지 않음. service 의 Sort 만 의존. 후속에 native query 도 ORDER BY 명시.
- **A002 가 HttpStatus.FORBIDDEN(403) 인지 정확 assertion 부재** — `is4xxClientError()` 까지만 검증. 후속에 exact status assertion.
- **AdminITBase 공통화** — T6 + T7 두 IT 가 동일 셋업 inline. 다음 IT 추가 시점에 공통 base class 추출.

## 다음 작업 후보

- AdminITBase 공통 추출 (T6/T7 의 코드 중복 제거)
- searchByTenantId 의 ORDER BY 명시
- TenantSnapshot diff 의 saveAndFlush 사전 short-circuit (no-op 시 child SQL 도 안 일어나게)
- credential soft delete (status='REVOKED' + revokedAt) 도입 + UI 표시
- Activity 페이지 (audit_log + tenant_id 컬럼)
