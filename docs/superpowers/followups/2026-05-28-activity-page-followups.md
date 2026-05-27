# Activity 페이지 Phase — Followups

Phase: activity-page (2026-05-27)
Plan: [docs/superpowers/plans/2026-05-27-activity-page.md](../plans/2026-05-27-activity-page.md)
Spec: [docs/superpowers/specs/2026-05-27-activity-page-design.md](../specs/2026-05-27-activity-page-design.md)

## Manual Smoke Checklist (반드시 실행)

브랜치 머지 전 / dogfood 환경에서 다음 8 단계 모두 확인:

- [ ] **1.** alice@crosscert.com 로그인 → 사이드바에 'Activity' 메뉴 보임 → 클릭 시 페이지 로드 (3초 안에 KPI 표시)
- [ ] **2.** Activity 페이지에서 KPI 4 카드 표시 — events24h / ops24h / security24h 는 숫자, p95 응답 (ms) 은 'N/A'
- [ ] **3.** 5초 polling — alice 가 별 탭에서 tenant 생성 → 5초 이내에 Activity 페이지 feed 상단에 새 TENANT_CREATE row 추가
- [ ] **4.** 카테고리 chip — '운영' 선택 시 TENANT_CREATE/UPDATE/CREDENTIAL_REVOKE/API_KEY/SIGNING/ADMIN_LOGIN 만, '보안' 선택 시 ADMIN_LOGIN_FAILED 만, '전체' 로 복귀
- [ ] **5.** bob@demo-rp.com (RP_ADMIN) 로그인 → 사이드바에 Activity 메뉴 없음. URL 에 `/activity` 직접 입력 → `/tenants/{demo-rp-id}` 로 리다이렉트 (PlatformOnlyGuard)
- [ ] **6.** bob 의 TenantDetail 5번째 탭 'Activity' 클릭 → demo-rp 의 audit 이벤트만 표시. 5초 polling 도 동작
- [ ] **7.** alice 가 임의 tenant 의 detail Activity 탭 → 해당 tenant 의 audit 만 보임 (PLATFORM_OPERATOR 가 명시 tenantId 호출)
- [ ] **8.** alice 가 /audit 페이지 (PLATFORM_OPERATOR) → tenantId input 으로 임의 tenant 필터 가능. TENANT 컬럼에 8자 prefix 표시

## Deferred Items (후속 Phase)

이번 phase 에서 의도적으로 미루기로 결정한 것 — 별도 brainstorm/plan 으로 진행:

### 1. p95 응답 metric (Micrometer/Actuator)
- **왜 미룸**: audit_log 에 latency 칼럼 없음. Micrometer 의 `http.server.requests` percentile 을 별도 wiring 필요
- **scope**: Spring Boot Actuator + Micrometer percentiles + Activity 페이지 KPI 의 p95Ms 가 실제 값으로 표시

### 2. WebSocket / SSE 실시간 push
- **왜 미룸**: 5초 polling 으로 dogfood 충분. SSE 는 별도 endpoint + admin-ui 의 EventSource wiring 필요
- **scope**: ActivitySseController + Activity.tsx 의 EventSource subscription

### 3. 기존 row tenant_id backfill
- **왜 미룸**: 24h 윈도우라 historical NULL 무관. payload.tenantId 추출 + UPDATE 권한 필요
- **scope**: V25 migration — payload JSON 의 tenantId 키를 audit_log.tenant_id 컬럼으로 추출 (NULL 만 대상)

### 4. Audit Chain Monitor 페이지 (sparkline, 월간 PDF)
- **왜 미룸**: 별도 phase — chain verify 가시화, 월간 PDF 리포트
- **scope**: `/admin/api/audit/chain-status` daily summary + 페이지 + PDF export

### 5. 운영자 관리 UI (admin-role-separation 의 deferred)
- **왜 미룸**: admin-role-separation phase 에서 이미 deferred 로 캡처됨
- **scope**: PLATFORM_OPERATOR 가 다른 운영자/RP_ADMIN 추가/삭제/role 변경

### 6. ADMIN_LOGIN tenantId 분류 옵션
- **왜 미룸**: 현재는 ADMIN_LOGIN 의 tenantId 가 null — RP_ADMIN 의 자기 로그인이 자기 tenant audit 에 안 보임
- **scope**: AdminSecurityConfig 의 login success handler 에서 principal.getTenantId() 로 fill (RP_ADMIN 만, PLATFORM_OPERATOR 는 여전히 null)

### 7. Activity 페이지 시간 윈도우 선택 (24h/7d/30d)
- **왜 미룸**: 현재 24h 고정. 드롭다운 + ActivityService.snapshot 의 WINDOW 파라미터화 필요
- **scope**: GET /activity?windowHours= 추가 + UI 드롭다운

### 8. action 분류 i18n + 색상 토큰 분리
- **왜 미룸**: 현재 OPS_ACTIONS/SECURITY_ACTIONS 가 Service 안에 hardcoded. dogfood UI fidelity 후순위
- **scope**: action → metadata (display name, icon, color, category) registry 추출 + 백엔드 enum + i18n

---
*문서 갱신 책임: 각 followup 시작 시 brainstorm/plan 링크 추가, 완료 시 status [DONE] 표시*
