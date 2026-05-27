# Admin Role 분리 followups

Spec: `docs/superpowers/specs/2026-05-27-admin-role-separation-design.md`
Plan: `docs/superpowers/plans/2026-05-27-admin-role-separation.md`

## Manual smoke result (T16)

운영자가 8 단계 manual smoke 후 체크박스 채움.

- [ ] M.1 alice 로그인 → /tenants 목록 + create 버튼 + Signing Keys/MDS/Audit 메뉴 모두 보임
- [ ] M.2 alice 가 bob 의 demo-rp tenant 클릭 → detail 4 탭 모두 동작
- [ ] M.3 logout → bob (RP_ADMIN) 로그인 → 자동 /tenants/{demo-rp.id} 라우팅. 사이드바 'My Tenant' 단일
- [ ] M.4 bob 이 URL /tenants 직접 입력 → /tenants/{demo-rp.id} 강제 리다이렉트
- [ ] M.5 bob 이 URL /tenants/{tenant_A.id} 입력 → 자기 tenant 로
- [ ] M.6 bob 이 URL /audit 입력 → 자기 tenant detail 로 (PlatformOnlyGuard)
- [ ] M.7 bob detail 에서 WebAuthn 설정 변경 + 저장 → 200 + audit row
- [ ] M.8 bob detail 에서 credential 회수 → 200 + audit row

## Deferred (spec § 7.2)

1. **운영자 관리 UI** (추가/정지/초대 다이얼로그, MFA 옵션) — 큰 덩어리, 별도 phase
2. **PENDING status + 초대 이메일** (SMTP) — 운영자 관리 UI 일부
3. **audit_log.tenant_id 컬럼 + RP_ADMIN scope** — 마이그레이션 + payload 검색 정책
4. **suspended tenant 의 RP_ADMIN 정책** — 현재는 접근 허용. 운영 워크플로우 합의 후
5. **AdminUser.lastLoginAt RP_ADMIN 추가 후 재확인** — minor
6. **role enum 의 Java enum 화** (현재 String) — type safety 개선
7. **사이드바 UX 재디자인** — RP_ADMIN 전용 페이지 (Overview / WebAuthn / AAGUID / Credentials / API Keys / Audit / Funnel)
8. **AAGUID Attestation Policy** (ANY/ALLOWLIST/DENYLIST) — Tenant 엔티티 확장
9. **Tenant suspend/activate workflow** — status 변경 UI

## In-loop findings (phase 진행 중 codex review 가 잡은 항목 + 의도된 결정)

### T1 — V23 migration
- PL/SQL block 의 `/` terminator 가 Flyway 인식 못 함 → plain SQL idempotent INSERT 로 재작성 (commit `289b994` amend)
- **VARCHAR2(16) 컬럼 < 'PLATFORM_OPERATOR'(17자)** — T12 가 발견. `ALTER TABLE admin_user MODIFY (role VARCHAR2(20))` 추가
- **DROP CONSTRAINT 순서** — UPDATE 전에 DROP 해야 'PLATFORM_OPERATOR' 값 들어감. step 순서 조정

### T11 — SecurityTest 갱신
- 의미 변한 5 test 삭제 (issue/revoke/list 의 RP_ADMIN test) — RpAdminBoundaryIT 가 IT 에서 cover
- ServiceTest 에 TenantBoundary mock 추가 — happy path 만 검증 (boundary 거부는 IT)

### T12 — AdminFlowIT
- resetState() 가 FK fk_admin_user_tenant 와 충돌 → `tenant_id NULL set → DELETE tenant → demo-rp 재시드 → bob 재할당` 패턴 도입
- **이 패턴이 후속 IT 들에도 필요** — T16 에서 동일 fix 적용 (TenantAdminControllerUpdateIT + CredentialAdminControllerSecurityIT)

### T13 / T14 — 보안 + 운영 능력 IT
- updateBody 에 rpId 누락 → 400 C001 (admin-tenant-detail phase 의 @NotBlank rpId)
- Jackson `default-property-inclusion: non_null` 가 null tenantId 를 omit → `isMissingNode()` 로 검증

### T15 — admin-ui
- **PlatformOnlyGuard 가 loading 무시** — children 잠깐 렌더 + 불필요한 backend 호출. wrapper 패턴 + loading 가드로 fix (commit `ce704cf`)
- **TenantList 가 me=null 일 때도 fetch** — loading 가드 + RP_ADMIN early-return 추가

### T16 — finalization
- 2 IT (TenantAdminControllerUpdateIT, CredentialAdminControllerSecurityIT) 가 resetState FK 처리 안 함 → T12 와 동일 패턴 inline 적용 (commit `783908e`)

## 다음 작업 후보

- **`AdminITBase` 공통 base class 추출** — 5 IT 가 동일 Testcontainers + loginAs + resetState 패턴 inline. 다음 phase 첫 task 로 분리 검토.
- **audit_log.tenant_id 컬럼 도입 + RP_ADMIN 의 자기 tenant audit 접근** — 디자인 fidelity 회복
- **Tenant suspend/activate workflow + RP_ADMIN 의 suspended 처리 정책**
