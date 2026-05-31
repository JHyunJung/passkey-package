# RP_ADMIN role 게이팅 (admin-ui) — Follow-ups

- **작성일**: 2026-05-31
- **브랜치**: `worktree-rp-admin-role-gating`
- **spec**: [2026-05-31-rp-admin-role-gating-design.md](../specs/2026-05-31-rp-admin-role-gating-design.md)
- **plan**: [2026-05-31-rp-admin-role-gating.md](../plans/2026-05-31-rp-admin-role-gating.md)

RP_ADMIN role 게이팅을 7개 Task로 마감했다. subagent-driven + Task별 spec/quality 리뷰. vitest를 admin-ui에 처음 도입. 아래는 의도적으로 미룬 항목과 후속 권고다.

---

## 1. admin-ui 다른 후속 화면 (별도 phase — 가장 큰 잔여)

이번은 **RP_ADMIN 게이팅만**. 그룹 A·B·C 백엔드의 UI는 아직:
- **그룹 A**: MFA recovery code 1회 표시 화면(confirm 응답의 recoveryCodes 10개), password reset 랜딩(`/reset-password?token=`).
- **그룹 B**: API key rotation 버튼 + 신규 평문 키 1회 표시 + 구 키 만료 안내, scope 선택 UI, insufficient_scope(403) 안내.
- **그룹 C**: alert 메일 설정 UI(`passkey.alert.mail.*`).

## 2. NAV_RP 가 현재 dead code (Phase E2 의존)

코드 리뷰에서 발견: `App.tsx`가 `tenant={null}` 하드코딩(Phase E2 "tenant 로딩 추가" TODO)이라 Sidebar의 `tenant ? NAV_RP : navItems` 분기에서 **NAV_RP가 현재 렌더 안 됨**. RP_ADMIN은 자기 테넌트로 redirect되지만 sidebar는 navItems(이번에 '설정'만 남김)를 본다.

- **현황**: 이번 phase는 navItems에 '설정'을 남겨 RP_ADMIN의 계정 접근 dead-end를 막음(코드 리뷰 Critical 해소).
- **권고**: Phase E2에서 tenant 로딩이 붙어 NAV_RP가 살아나면, RP_ADMIN이 자기 테넌트 진입 시 NAV_RP(개요/WebAuthn/API Keys/Credentials/Audit/Funnel)를 보게 됨. 그때 navItems('설정' 단독)와 NAV_RP의 관계 재검토(NAV_RP에 설정/내 계정 항목 추가 등). 현재는 '설정' 진입점이 안전망.

## 3. Header user menu 에 설정/내 계정 링크 없음

`Header.tsx`의 user 메뉴는 role-switch/API-docs/logout만. RP_ADMIN의 '설정' 진입은 Sidebar nav '설정' 항목에 의존(이번에 유지). Header에도 '내 계정' 링크를 두면 동선이 더 견고(선택, 범위 밖).

## 4. 브라우저 dogfooding 미수행

게이팅 동작은 vitest 단위 테스트로 검증. 실제 브라우저로 RP_ADMIN(seed `bob`) / PLATFORM(seed `alice`) 로그인해 nav·redirect·탭 육안 확인은 미수행(로컬 서버 8081 + seed 계정 구동 환경 의존).

- **권고**: 로컬 환경에서 `bob@crosscert.com`(RP_ADMIN) 로그인 → 자기 테넌트 redirect·sidebar에 PLATFORM 항목 없음·설정에 account 탭만, `alice`(PLATFORM) 회귀 없음 dogfooding. (메모리 `project_rp_admin_dashboard_crash`의 dogfooding 흐름.)

## 5. 분산 role 체크 — 통일됨

이번에 `roles.ts`(isPlatform/isRpAdmin/rpTenantId)로 중앙화. Sidebar의 duplicate `me.role === 'PLATFORM_OPERATOR'` 제거. App.tsx breadcrumb의 role 체크(`me.role === 'PLATFORM_OPERATOR'`)와 TenantDetailPage의 `isPlatformOperator` prop은 이번 범위 밖이라 그대로 — 추후 헬퍼로 통일 가능(선택).

## 6. App.tsx 잔여 `as any` cast

이번에 Sidebar의 `me as any`는 제거. 그러나 `currentRoute as any`/`setRoute as any`(AppRoute 판별 union vs Sidebar 느슨한 타입 불일치)와 Header의 `me as any`(HeaderProps.me가 displayName 포함 느슨한 타입)는 실제 shape 불일치라 그대로 — 각 컴포넌트 타입 강화는 별도 cleanup.

## 7. codex 독립 리뷰 미실행

OpenAI usage limit(resets Jun 1)로 `/codex review` 미실행. Task별 spec + code-quality subagent 2단계로 게이트.

- **권고**: 6/1 리셋 후 누적 diff(`3728f04..HEAD`)에 `/codex review`. 특히 RequirePlatform redirect loop 불변식, RP 설정 dead-end 방지, PLATFORM 무영향.

## 8. 검증 환경 메모

admin-ui 변경만(백엔드 무관). 게이트: **tsc -b 빌드 + vitest 12 tests green**(roles 4 + RequirePlatform 3 + Sidebar 2 + SettingsPage 2 + smoke 1). vitest는 이번에 처음 도입(첫 admin-ui 테스트 인프라). 보안 경계는 여전히 BE(`TenantBoundary`/`@PreAuthorize`) — FE 게이팅은 IA/UX defense-in-depth.
