# Admin Console — 디자인 시스템 정렬 + P0 운영 기능 풀스택 구현

- **작성일**: 2026-05-28
- **작성자**: jhyun (with Claude)
- **상위 컨텍스트**: monorepo merge 완료 직후, admin-ui/admin-app 가 한 저장소 안에 정렬된 시점에서 디자인 패키지(`docs/design-package/`)의 `Passkey Admin Console.html` 디자인을 기준으로 admin 콘솔의 비주얼/구조를 재정렬하고, 멀티테넌트 Passkey 운영에 필수적인 P0 기능을 풀스택으로 구현한다.
- **이번 spec 의 한 줄 정의**: admin-ui 를 디자인 패키지와 동일한 비주얼/셸 구조로 재구성하고, 멀티테넌트 운영에 필수적인 P0 4개 기능(Audit Chain per-tenant, AAGUID Policy, WebAuthn diff/경고, Admin 사용자 관리)을 풀스택으로 구현한다.

---

## 1. 배경 및 현재 상태

### 1.1 디자인 패키지
- 위치: `docs/design-package/` (handoff bundle from claude.ai/design)
- 메인 파일: `project/Passkey Admin Console.html`
- 디자인 시스템: OKLCH 색상 토큰 + Geist/Pretendard 폰트 페어 + light/dark + density(compact/comfortable) + Tweaks 패널(테마/Accent 5종/밀도/테이블 스타일/사이드바 모드)
- shell 구조: `shell.jsx` (Sidebar/Header/Dialog/Toast/Layout), `extras.jsx` (Idle/CommandPalette/Tweaks), `pages-1~6.jsx` (페이지별)
- 핵심 의도: Stripe/Linear 풍의 깨끗한 대시보드, 운영자가 자주 보는 정보 밀도 높이기, 보안 사고 방지(diff 미리보기, plaintext 1회 노출, idle 만료, chain 검증)

### 1.2 현재 admin-ui 상태 (React + TypeScript + Vite)
- 라우트: `/login`, `/tenants`, `/tenants/new`, `/tenants/:id` (5탭), `/activity`, `/audit`, `/keys`, `/mds`
- 스타일: 인라인 style + `src/styles/tokens.css` (OKLCH 토큰은 존재하지만 UI 라이브러리 없음)
- 구현 완료: 로그인, role 기반 라우팅, idle 모달, Tenant 목록/생성/상세, Activity 폴링, AuditLog 기본, MdsStatus, Credentials, ApiKeys, WebAuthn Config 편집
- 미구현 (디자인 대비): AAGUID Policy 탭, Funnel, Settings 페이지, Tweaks 패널, Audit Chain Monitor 페이지, 사이드바 Audit Chain 인디케이터

### 1.3 현재 admin-app 상태 (Spring Boot)
- 패키지: tenant, credential, config, auth, activity, keymgmt, apikey, audit, mds, scheduler
- 구현 완료: Tenant CRUD, Credential 회수, ApiKey 발급(plaintext 1회), AuditLog + 전체 chain 검증, MDS 동기화, Signing key 회전, Activity polling
- 미구현 (디자인 대비): AAGUID Policy entity/CRUD, Admin 사용자 관리(entity만 있음, controller 전무), per-tenant audit chain 검증, WebAuthn Config 변경 히스토리/diff, 초대 메일 인프라

### 1.4 "비슷하지만 다른" 갭 (주의 포인트)
| 영역 | 디자인 가정 | 현재 서버 |
|---|---|---|
| Audit Chain | tenant 별 무결성 카드 + sparkline | 전체 단일 chain 만 |
| Activity 피드 | "실시간 스트림" 뉘앙스 | 5초 polling + `sinceId` |
| Tenant 신규 생성 | 다이얼로그 | 풀페이지 (`/tenants/new`) |
| AAGUID Policy | 핵심 운영 화면 | entity 자체가 없음 |
| Admin 사용자 | 초대 메일 + PENDING + MFA | entity 만 존재, 관리 API 없음 |
| 보안 정책 저장소 | idle/비밀번호/CORS 동적 설정 | 하드코딩/Spring Security 설정 |

---

## 2. 결정 요약 (Brainstorming session 결과)

| 결정 항목 | 채택안 |
|---|---|
| Spec 스코프 | 디자인 시스템 정식 적용 + P0 4개 기능 풀스택 (Phase A~D 분할) |
| 디자인 적용 방식 | **shadcn/ui + Tailwind CSS** (OKLCH 토큰을 Tailwind config + CSS 변수로 매핑) |
| P0 백엔드 범위 | 풀 스택 구현 (entity/CRUD/ceremony 후크/초대 토큰 모두 포함) |
| Admin 사용자 초대 메일 | **MailSender 추상 + LogMailSender 구현** (DB 로그 + UI 링크 복사) |
| Audit Chain 재설계 | **Tenant 별 분리 체인** + **글로벌 chain 유지(이중 chain)** |
| 백필 방식 | **명시적 endpoint** (`POST /admin/api/audit/chain/backfill`) |
| 테스트 전략 | **최소화** — Phase 별 핵심 IT 만 (총 IT 5개), 그 외 수동 smoke |
| OUT-OF-SCOPE | Funnel 페이지, monthly PDF, Tenant CSV, Settings 시스템탭/보안정책탭, Activity SSE 전환 |
| 그 외 OUT-OF-SCOPE | Role 전환 데모 버튼 (P3), MFA enforcement (UI 체크박스만), 사이드바 Audit Chain 인디케이터 (placeholder 자리만) |

---

## 3. Phase 분할 및 수용 기준

각 phase 는 자체 PR, 자체 worktree, 자체 codex review.

### Phase A — 디자인 토큰 + 셸 (UI only)
- Tailwind + shadcn 설치 + 디자인 토큰 매핑
- shell 컴포넌트 재작성 (Sidebar/Header/Layout)
- 베이스 컴포넌트 (Dialog/Toast/Button/Input/Switch/MetricCard/StatusBadge/Tabs/Table/Badge/Progress/Popover)
- TweaksPanel + TweaksProvider (theme/density/accent/tableStyle/sidebarMode)
- Command Palette 확장 (cmdk)
- IdleTimeout 을 Dialog 로 리팩터
- 기존 페이지를 새 셸/컴포넌트 위로 이관 (기능 변화 0)
- **수용 기준**: 모든 기존 페이지 동작, light/dark/density 토글 즉시 반영, Command Palette 키보드 네비게이션
- **테스트**: 수동 smoke 만 (전 페이지 1회 클릭 + 토글 조합 확인)

### Phase B — Audit Chain per-tenant
- V24 마이그레이션: `audit_log` 에 `tenant_prev_hash`, `tenant_hash` 컬럼 추가 + `(tenant_id, id)` 인덱스
- `AuditLogService.append()` 가 글로벌 + tenant chain 동시 계산 (락 순서: global → tenant)
- `AuditChainVerifier.verifyTenant(UUID)`, `verifyAllTenants()`, `verifyGlobal()` (기존 유지)
- `GET /admin/api/audit/chain/overview?windowHours=24` — 24h sparkline + KPI 일괄 응답
- `GET /admin/api/audit/chain/verify?tenantId=` — 단일 tenant 검증 (RP_ADMIN 은 `me`)
- `POST /admin/api/audit/chain/backfill` — 명시적 백필 endpoint (PLATFORM_OPERATOR, idempotent)
- UI: 신규 `AuditChainMonitor.tsx` 페이지 (route `/audit-chain`)
  - KPI 4종 + 위변조 tenant 빨간 배너 + tenant 카드 그리드 + 24h sparkline + "전체 즉시 검증" 버튼
  - PDF 보고서 다이얼로그는 disabled placeholder (OUT-OF-SCOPE)
- 기존 `AuditLog.tsx` Chain 검증 버튼 → `verifyTenant` 호출로 변경
- **수용 기준**: tenant1 row 의도 변조 → tenant1 만 broken, tenant2 intact, 위변조 배너 표시
- **테스트**: IT 2개 (per-tenant isolation, backfill 후 verifyAllTenants intact)

### Phase C — AAGUID Policy + WebAuthn diff/경고
- V25 마이그레이션: `tenant_aaguid_policy` + `tenant_aaguid_policy_entry`
- V26 마이그레이션: `tenant_webauthn_snapshot`
- core 의 새 패키지: `policy/` (TenantAaguidPolicy entity + repo + AaguidPolicyChecker)
- admin/policy/ Controller: GET/PUT `/admin/api/tenants/{id}/aaguid-policy`
- passkey-app 등록 ceremony 에 `AaguidPolicyChecker.check()` 후크 (인증 ceremony 는 적용 안 함)
- Redis 캐시 `tenant:{id}:aaguid-policy` TTL 60s, PUT 시 invalidate
- 정책 위반 시 명확한 에러 코드 (`AAGUID_NOT_ALLOWED`, `AAGUID_DENIED`, `AAGUID_MDS_UNKNOWN`)
- WebAuthn Config PUT 시 변경 직전 값을 `tenant_webauthn_snapshot` 으로 INSERT (전체 히스토리 유지)
- `POST /admin/api/tenants/{id}/webauthn-config/diff` — UI 미리보기용 diff 계산 (서버가 단일 진실)
- diff response 의 `warnings` enum: `RP_ID_CHANGED`, `ORIGIN_REMOVED`, `UV_RAISED_TO_REQUIRED`, `ATTESTATION_RAISED`
- PUT 시 warnings 있어도 차단 X (UI 가 확인 책임), audit_log 에 warnings 포함 기록
- 신규 Tenant 생성 시 policy(mode=ANY, mds_strict=false) + 초기 WebauthnConfig snapshot 자동 INSERT
- UI: 신규 `AaguidPolicyTab.tsx` (모드 카드 3종 + UUID chip 입력 + MDS name 표시 + 메모 + mdsStrict 토글)
- UI: 기존 `WebAuthnConfigTab.tsx` 에 "변경 미리보기" 버튼 → diff 모달 (+/- 라인, 경고 박스)
- **수용 기준**: ALLOWLIST 미포함 AAGUID 등록 시도 시 400 + `AAGUID_NOT_ALLOWED`, WebAuthn 변경 시 +/- diff 표시, rpId 변경 경고 배너
- **테스트**: IT 2개 (registration 차단, snapshot 저장)

### Phase D — Admin 사용자 관리 + Settings 셸
- V27 마이그레이션: `admin_user` 에 `status`/`created_by`/`suspended_at`/`suspended_by` 컬럼, `password_hash` NULL 허용, `admin_user_invitation` 테이블
- core/admin: `AdminUserInvitation` entity + invitation repo
- admin/operator/ Controller:
  - 인증 필요: `GET/POST/DELETE /admin-users`, `POST .../suspend`, `POST .../activate`, `POST .../invitation/resend`
  - 비인증: `GET/POST /invitations/{token}`, `POST /invitations/{token}/accept`
- 권한 가드: 본인 자신 suspend/delete 금지, 마지막 ACTIVE PLATFORM_OPERATOR suspend/delete 금지 (lockout 방지)
- `MailSender` 인터페이스 + `LogMailSender` 구현 (`@ConditionalOnMissingBean`), invite 시 호출
- Invite 응답에 plaintext token + `acceptUrl` 1회 노출 (API Key 패턴)
- 신규 라우트 `/accept-invite?token=...` (비인증, minimal layout) — 비밀번호 설정 폼
- 신규 라우트 `/settings` (PLATFORM_OPERATOR) — Tab 셸:
  - `AdminUsersTab` — 본 phase 핵심
  - `MdsStatusTab` — 기존 `/mds` 이관 (`/mds` 는 `/settings?tab=mds` 로 리다이렉트)
  - `SystemTab`, `SecurityPolicyTab` — disabled placeholder
- Sidebar 의 별도 `/mds` 항목 제거, `/settings` 추가
- 신규 audit event types: `ADMIN_USER_INVITED/ACCEPTED/SUSPENDED/ACTIVATED/INVITATION_RESENT`
- **수용 기준**: 새 운영자 초대 → 링크 복사 → 다른 브라우저 수락 → 로그인 → role 적용
- **테스트**: IT 1개 (invite→accept→login 풀 플로우, audit 2건 기록 확인)

---

## 4. 기술 스택 / 인프라 결정

### 4.1 디자인 시스템 매핑 (Phase A)
- **Tailwind config 와 CSS 변수 분리**: 정적 토큰(spacing, radius, font family, shadow)은 tailwind theme, 런타임 변경 토큰(theme/density/accent/tableStyle/sidebarMode) 은 CSS 변수
- **shadcn 컴포넌트 선택**: button, dialog, input/label/textarea, select, checkbox, switch, radio-group, dropdown-menu, command(cmdk), tabs, table, sonner(toast), popover, tooltip, badge, progress
- **shadcn 제외**: accordion, alert-dialog (Dialog 로 통일), carousel, calendar, sheet
- **폰트**: Geist + Geist Mono + Pretendard, self-host via `@font-face`

### 4.2 TweaksProvider
```ts
type Tweaks = {
  theme: 'light' | 'dark';
  density: 'compact' | 'comfortable';
  accent: 'indigo' | 'violet' | 'blue' | 'teal' | 'amber';
  tableStyle: 'lines' | 'stripes' | 'borderless';
  sidebarMode: 'labels' | 'icons';
};
```
- localStorage key: `passkey-admin:tweaks`
- 첫 방문 default: `prefers-color-scheme` 따라감, 이후엔 사용자 선택 우선
- 변경 시 `document.documentElement.setAttribute('data-*', value)` 즉시 반영

### 4.3 디렉토리 구조 (admin-ui)
```
src/
├── app/
│   ├── AppRouter.tsx
│   └── providers/
│       ├── MeProvider.tsx           # 기존 MeContext 이관
│       ├── TweaksProvider.tsx       # 신규
│       ├── ToastProvider.tsx        # sonner host
│       └── CommandPaletteProvider.tsx
├── shell/
│   ├── Layout.tsx
│   ├── Sidebar.tsx
│   ├── Header.tsx
│   ├── TweaksPanel.tsx
│   ├── CommandPalette.tsx
│   └── IdleTimeoutDialog.tsx
├── components/ui/                   # shadcn 생성물
├── pages/
│   ├── Login.tsx, AcceptInvite.tsx
│   ├── TenantList.tsx, TenantDetail.tsx, ...
│   ├── AuditChainMonitor.tsx        # 신규 (Phase B)
│   ├── settings/
│   │   ├── Settings.tsx, AdminUsersTab.tsx, MdsStatusTab.tsx,
│   │   ├── SystemTab.tsx (placeholder), SecurityPolicyTab.tsx (placeholder)
│   └── tenant/
│       ├── OverviewTab.tsx, WebAuthnConfigTab.tsx,
│       ├── AaguidPolicyTab.tsx      # 신규 (Phase C)
│       ├── CredentialsTab.tsx, ApiKeysTab.tsx, AuditTab.tsx
├── api/                             # 기존 유지, 신규 endpoint 헬퍼 추가
├── lib/
└── styles/
    ├── tokens.css                   # OKLCH 토큰 (data-theme/density/accent)
    ├── globals.css                  # @tailwind base/components/utilities
    └── fonts.css
```

### 4.4 디렉토리 구조 (admin-app)
- 신규 패키지: `admin/policy/` (AAGUID Policy), `admin/operator/` (Admin 사용자 + 초대), `admin/mail/` (MailSender 추상)
- core 에 신규 entity: `TenantAaguidPolicy`, `TenantAaguidPolicyEntry`, `TenantWebauthnSnapshot`, `AdminUserInvitation`
- core 의 `AdminUser` 에 새 필드 추가 (status, created_by, suspended_at, suspended_by, password_hash nullable)

---

## 5. 데이터 모델 변경 (Flyway 마이그레이션)

### V24 — Audit chain per-tenant
```sql
ALTER TABLE audit_log
  ADD COLUMN tenant_prev_hash VARCHAR(64),
  ADD COLUMN tenant_hash      VARCHAR(64);
CREATE INDEX idx_audit_log_tenant_seq ON audit_log (tenant_id, id);
-- 백필은 endpoint 호출로 처리 (V24 안에서는 컬럼만 추가)
```

### V25 — AAGUID policy
```sql
CREATE TABLE tenant_aaguid_policy (
  tenant_id    UUID PRIMARY KEY REFERENCES tenant(id) ON DELETE CASCADE,
  mode         VARCHAR(16) NOT NULL,   -- ANY | ALLOWLIST | DENYLIST
  mds_strict   BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   VARCHAR(128)
);
CREATE TABLE tenant_aaguid_policy_entry (
  tenant_id    UUID NOT NULL REFERENCES tenant_aaguid_policy(tenant_id) ON DELETE CASCADE,
  aaguid       UUID NOT NULL,
  note         VARCHAR(256),
  PRIMARY KEY (tenant_id, aaguid)
);
-- 기존 tenant 행에 대해 backfill: mode=ANY, mds_strict=false 정책 자동 생성
INSERT INTO tenant_aaguid_policy (tenant_id, mode)
SELECT id, 'ANY' FROM tenant
ON CONFLICT (tenant_id) DO NOTHING;
```

### V26 — WebAuthn config snapshot
```sql
CREATE TABLE tenant_webauthn_snapshot (
  id                      BIGSERIAL PRIMARY KEY,
  tenant_id               UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
  rp_id                   VARCHAR(255) NOT NULL,
  rp_name                 VARCHAR(255) NOT NULL,
  allowed_origins         TEXT NOT NULL,        -- JSON array
  accepted_formats        TEXT NOT NULL,        -- JSON array
  user_verification       VARCHAR(16) NOT NULL,
  attestation_conveyance  VARCHAR(16) NOT NULL,
  timeout_ms              INTEGER NOT NULL,
  taken_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
  taken_by                VARCHAR(128)
);
CREATE INDEX idx_tenant_snapshot_tenant_taken
  ON tenant_webauthn_snapshot (tenant_id, taken_at DESC);
-- 기존 tenant 행에 대해 초기 snapshot 자동 INSERT
INSERT INTO tenant_webauthn_snapshot
  (tenant_id, rp_id, rp_name, allowed_origins, accepted_formats,
   user_verification, attestation_conveyance, timeout_ms, taken_by)
SELECT id, rp_id, rp_name, allowed_origins_json, accepted_formats_json,
       user_verification, attestation_conveyance, timeout_ms, 'migration:v26'
FROM tenant;
```

### V27 — Admin user invitation + status
```sql
ALTER TABLE admin_user
  ADD COLUMN status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  ADD COLUMN created_by     VARCHAR(128),
  ADD COLUMN suspended_at   TIMESTAMPTZ,
  ADD COLUMN suspended_by   VARCHAR(128),
  ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE admin_user_invitation (
  id              BIGSERIAL PRIMARY KEY,
  admin_user_id   BIGINT NOT NULL REFERENCES admin_user(id) ON DELETE CASCADE,
  token_hash      VARCHAR(64) NOT NULL UNIQUE,
  token_prefix    VARCHAR(8) NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      VARCHAR(128) NOT NULL,
  expires_at      TIMESTAMPTZ NOT NULL,
  accepted_at     TIMESTAMPTZ,
  resent_count    INTEGER NOT NULL DEFAULT 0,
  resent_at       TIMESTAMPTZ
);
CREATE INDEX idx_invitation_user_active
  ON admin_user_invitation (admin_user_id) WHERE accepted_at IS NULL;
```

---

## 6. REST API 인벤토리 (신규/변경)

| 메서드 | Path | 권한 | 설명 | Phase |
|---|---|---|---|---|
| GET | `/admin/api/audit/chain/overview?windowHours=24` | PLATFORM_OPERATOR | Audit Chain Monitor 페이지 일괄 응답 | B |
| GET | `/admin/api/audit/chain/verify?tenantId=` | both (RP_ADMIN 은 `me`) | 단일 tenant 검증 | B |
| POST | `/admin/api/audit/chain/backfill` | PLATFORM_OPERATOR | 명시적 백필 (idempotent) | B |
| GET | `/admin/api/audit/verify` (기존) | PLATFORM_OPERATOR | 글로벌 chain 검증 (유지) | — |
| GET | `/admin/api/tenants/{id}/aaguid-policy` | both | AAGUID 정책 조회 | C |
| PUT | `/admin/api/tenants/{id}/aaguid-policy` | both | AAGUID 정책 교체 | C |
| POST | `/admin/api/tenants/{id}/webauthn-config/diff` | both | 변경 미리보기 diff 계산 | C |
| PUT | `/admin/api/tenants/{id}/webauthn-config` (기존) | both | 저장 + snapshot 자동 INSERT | C |
| GET | `/admin/api/admin-users` | PLATFORM_OPERATOR | 운영자 목록 | D |
| POST | `/admin/api/admin-users` | PLATFORM_OPERATOR | 초대 (PENDING 생성 + invitation) | D |
| DELETE | `/admin/api/admin-users/{id}` | PLATFORM_OPERATOR | 삭제 (lockout 가드) | D |
| POST | `/admin/api/admin-users/{id}/suspend` | PLATFORM_OPERATOR | 정지 (lockout 가드) | D |
| POST | `/admin/api/admin-users/{id}/activate` | PLATFORM_OPERATOR | 활성화 | D |
| POST | `/admin/api/admin-users/{id}/invitation/resend` | PLATFORM_OPERATOR | 새 토큰 발급 (기존 revoke) | D |
| GET | `/admin/api/invitations/{token}` | 비인증 | 토큰 유효성 + email/role/tenant 미리보기 | D |
| POST | `/admin/api/invitations/{token}/accept` | 비인증 | 비밀번호 설정 + status=ACTIVE | D |

---

## 7. 도메인 동작 상세

### 7.1 Audit chain — 이중 체인 (Phase B)
- 글로벌 chain (`prev_hash`/`hash`): 테이블 전체 append-only 무결성 검증 (row 삭제/재배치 공격 감지)
- Tenant chain (`tenant_prev_hash`/`tenant_hash`): tenant 내부 row 변조 검증, 다른 tenant 영향 없음 (isolation)
- append 시 락 순서: **global → tenant** (deadlock 방지)
- hash 계산: `SHA-256(prev_hash || canonicalJson(row_minus_hashes))`, tenant chain 도 동일 식
- platform-level audit (tenant_id NULL) 은 별도 tenant chain (`tenant_id IS NULL`)
- 백필 동작: tenant 그룹별 `id ASC` 순회, GENESIS 부터 재계산, tenant 별 락, 멱등 (이미 채워진 행 skip)

### 7.2 AAGUID Policy (Phase C)
- 신규 tenant 생성 시 자동으로 mode=ANY row INSERT (정책 미설정 상태 제거)
- 등록 ceremony 의 attestation 파싱 직후, credential 저장 직전 `check()`
- 인증 ceremony 에는 적용 안 함 (정책 변경이 기존 사용자 무력화 방지)
- mode ANY = entry 무시
- mode ALLOWLIST = entry 에 있어야 허용
- mode DENYLIST = entry 에 있으면 거부
- mds_strict=true = MDS BLOB 에 없는 aaguid 는 mode 무관 거부
- 위반 시 `AaguidPolicyViolationException` → 400 + 에러 코드
- Redis 캐시 TTL 60s, PUT 시 invalidate, multi-instance 만료 차이 ≤60s 허용
- 정책 변경 시 audit event `AAGUID_POLICY_UPDATED` 자동 기록 (diff 포함)

### 7.3 WebAuthn Config diff (Phase C)
- PUT 시 변경 직전 값을 snapshot INSERT, 그 후 tenant UPDATE (같은 트랜잭션)
- diff endpoint 별도 분리 — UI 가 미리보기 모달에서 호출, 서버가 단일 진실
- `warnings` enum 코드:
  - `RP_ID_CHANGED`: 모든 기존 credential 영향
  - `ORIGIN_REMOVED`: 진행 중 ceremony 실패 가능
  - `UV_RAISED_TO_REQUIRED`: 일부 인증기 미지원
  - `ATTESTATION_RAISED`: 새 등록만 영향
- PUT 차단 X — UI 가 확인 책임 (모달에서 명시적 동의)
- audit event `WEBAUTHN_CONFIG_UPDATED` 에 warnings 포함

### 7.4 Admin 사용자 관리 (Phase D)
- 초대 시 admin_user INSERT (`password_hash=NULL`, `status=PENDING`) + invitation INSERT (token hash 만, plaintext 는 응답에 1회)
- LogMailSender 호출 (실패해도 invite 자체는 성공, UI 가 링크 복사 fallback)
- 수락 시: 토큰 유효성 검증 → password_hash UPDATE → status=ACTIVE → accepted_at SET
- 토큰 만료 = 7일 (하드코딩, 보안 정책 설정은 추후 phase)
- 재발송 = 기존 invitation 의 token_hash 무효화 (resent_at SET) + 새 invitation 생성
- 권한 가드 (lockout 방지):
  - 본인 자신 suspend/delete 금지
  - 마지막 ACTIVE PLATFORM_OPERATOR suspend/delete 금지 (count 확인)
- 신규 audit events: `ADMIN_USER_INVITED/ACCEPTED/SUSPENDED/ACTIVATED/INVITATION_RESENT`
  - PLATFORM_OPERATOR 관련 = tenant_id NULL (platform chain)
  - RP_ADMIN 관련 = 해당 tenant chain

---

## 8. 테스트 전략 (최소화)

원칙: 회귀 위험이 큰 핵심만 IT, 나머지는 수동 smoke. 총 IT 5개 (기존 V23 회귀 IT 3개에 +5 → 총 8개).

| Phase | 필수 IT |
|---|---|
| A | 없음. 수동 smoke (전 페이지 1회 클릭, light/dark/density 토글 조합) |
| B | (1) per-tenant verify isolation — tenant1 row 변조 시 tenant1 만 broken, tenant2 intact, platform chain(tenant_id NULL) intact, global broken. (2) backfill 후 verifyAllTenants 모두 intact (platform chain 포함) |
| C | (1) ALLOWLIST 미포함 AAGUID 등록 차단 (400 + AAGUID_NOT_ALLOWED). (2) WebAuthn PUT 시 snapshot 자동 INSERT |
| D | (1) invite → accept → login 풀 플로우 + audit 2건 기록 확인 |

---

## 9. 마이그레이션/운영 절차

| Phase | 절차 |
|---|---|
| A | UI 배포만. 백엔드 무관 |
| B | (1) V24 SQL 배포, (2) 앱 재시작, (3) PLATFORM_OPERATOR 가 `POST /audit/chain/backfill` 명시 호출, (4) `verifyAllTenants()` 로 검증 |
| C | (1) V25, V26 SQL 배포 — 기존 tenant 에 자동 INSERT (정책 ANY + 초기 snapshot), (2) 앱 재시작 |
| D | (1) V27 SQL 배포 — `password_hash` NULL 허용, status default ACTIVE 로 기존 사용자 보존, (2) 앱 재시작 |

롤백:
- B: `tenant_prev_hash`/`tenant_hash` 컬럼 DROP (글로벌 chain 무손실)
- C: 새 테이블 DROP, ceremony 후크 코드 revert
- D: 새 테이블 DROP, admin_user 컬럼 DROP (운영자 데이터는 password_hash 그대로 유지되므로 안전)

---

## 10. OUT-OF-SCOPE (다음 phase 후보)

| 항목 | 비고 |
|---|---|
| Funnel 페이지 | 서버 ceremony 단계별 집계 쿼리 + UI 차트 |
| Audit Chain monthly PDF | PDF 렌더러 도입 (Jasper/iText) |
| Tenant 목록 CSV 내보내기 | 단순하지만 후순위 |
| Settings — 시스템 탭 | actuator 기반 컴포넌트/호스트/버전 view |
| Settings — 보안 정책 탭 | idle timeout/비밀번호 길이/CORS 동적 설정 + 저장소 |
| Activity SSE 전환 | 폴링 5초로 충분 |
| Role 전환 데모 버튼 | 운영 가치 낮음 (P3) |
| 사이드바 Audit Chain 인디케이터 | 디자인에서도 v1.1 표시, placeholder 자리만 |
| MFA enforcement | UI 체크박스만, 실제 enforcement 는 별도 spec |
| Tenant 신규 생성 다이얼로그화 | 현재 풀페이지 유지 (기능 변화 0 원칙) |
| Audit Logs 페이지 eventType chip 필터 + payload 상세 모달 | Phase A 후속 UX 개선 |
| Overview KPI 4종 + 무결 상태 카드 | Phase B 응답 활용한 UX 개선 후속 |
| Audit Chain Monitor 자동 폴링 | 수동 새로고침 + 명시적 "전체 검증" 버튼만 |

---

## 11. 의존성 / 외부 영향

- **passkey-app**: Phase C 의 ceremony 후크 변경이 있으나 backward-compatible (정책 ANY = 동작 변화 없음)
- **sdk-java**: 영향 없음 (admin API 만 변경)
- **sample-rp**: 영향 없음
- **monorepo merge phase**: 완료된 상태에서 시작. 기존 V23 회귀 IT 3개 유지.

---

## 12. 위험 / 결정 트레이드오프

| 위험 | 완화 |
|---|---|
| Audit append 시 락 2개 (global+tenant) 로 처리량 저하 | 락 순서 고정으로 deadlock 방지, tenant 락은 행 단위라 경합 적음. 부하 측정은 별도 phase |
| 백필 endpoint 가 idempotent 하지 않을 위험 | 백필 시작 시 tenant 별 락 + 이미 채워진 행 skip, 트랜잭션 단위로 분리 |
| 디자인 토큰 매핑 누락 | Phase A 수용 기준에 "토글 조합 확인" 포함 |
| WebAuthn snapshot 테이블 비대화 | 변경 빈도 낮음, 필요 시 추후 purge 정책 추가 |
| LogMailSender 로 운영자 초대 누락 사고 | UI 가 항상 plaintext 링크를 1회 노출 + "전달 완료 확인" 체크박스 강제 |
| 마지막 PLATFORM_OPERATOR lockout | 서버 가드 + UI 사전 안내 (수정 시점에 disabled) |
| Tenant 신규 생성이 풀페이지 그대로 (디자인은 다이얼로그) | 기능 변화 0 원칙. 디자인 정렬은 추후 phase |

---

## 13. 다음 단계

이 spec 이 승인되면 `writing-plans` skill 을 통해 **Phase A 부터** 상세 implementation plan 을 작성한다. 각 phase 는 자체 worktree + 자체 PR + codex review (commit 전) + merge --no-ff 로 진행 (CLAUDE.md per-phase worktree 정책).
