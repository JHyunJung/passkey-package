# admin-ui 후속 화면 (그룹 A·B UI) — Follow-ups

- **작성일**: 2026-05-31
- **브랜치**: `worktree-admin-ui-followup-screens`
- **spec**: [2026-05-31-admin-ui-followup-screens-design.md](../specs/2026-05-31-admin-ui-followup-screens-design.md)
- **plan**: [2026-05-31-admin-ui-followup-screens.md](../plans/2026-05-31-admin-ui-followup-screens.md)

3 Phase(password reset / MFA 복구 코드 / API key scope+rotate)를 11 Task로 마감. 백엔드 변경 없음. subagent-driven 실행 + Phase별 spec/quality 2단계 리뷰. vitest 단위 테스트 추가: passwordReset 3 + RecoveryCodesModal 2 + apiKeys 2 = 7개(총 12→19). 게이트: tsc -b EXIT 0, vitest 19 green, vite prod build EXIT 0.

---

## 1. 버그 수정됨: api-key scope `'ceremony'`
발급 UI가 whitelist(`registration`/`authentication`/`admin`) 밖 `'ceremony'` 를 하드코딩해 백엔드 `ApiKeyAdminService.normalizeScope` 에서 `INVALID_INPUT`(400) 으로 **발급이 깨져 있던 것**을 scope 인자화로 수정(Task 9). 이제 발급 다이얼로그에서 `registration`/`authentication` 를 선택한다.
- **권고**: seed 데이터의 기존 api_key 들은 직접 INSERT 였을 수 있으므로, **운영 전 실제 발급 흐름을 한 번 brower dogfooding** 권장(이 환경에선 미수행).

## 2. 리뷰에서 나온 minor (의도적 용인 / 후속)
- **확인 다이얼로그 double-submit 가드 부재**: `RotateKeyDialog`/`RevokeKeyDialog` 의 confirm 버튼에 in-flight `disabled` 가 없어 빠른 더블클릭 시 rotate/revoke 가 2회 발생 가능. 단, **기존 RevokeKeyDialog 패턴과 동일**(이번에 introduce한 회귀 아님). `Dialog` 컴포넌트에 busy state 가 없어 전 confirm 다이얼로그를 한 번에 손보는 별도 cleanup 권장.
- **`handleRotate` 의 stale 필드**: `{...k}` 스프레드로 구 키의 `status:'ACTIVE'`/`createdAt`/`lastUsedAt` 가 issued 객체에 실림. `IssuedKeyModal` 이 name/prefix/id 만 렌더해 무해(cosmetic). 곧 `reload()` 가 서버 상태로 덮음.
- **ResetPasswordPage 에러 메시지**: 리뷰 1차에서 `err.message`(`[code] msg`) → `err.serverMessage`(bare) 로 수정 완료.

## 3. CRITICAL 수정됨 (Phase 1 리뷰): public 경로 /me redirect
초기 구현은 `App.tsx` 의 `/me` fetch useEffect 가 public 경로(`/forgot-password`/`/reset-password`)에서도 실행 → `/me` 가 permitAll 아니라 401 → `client.ts` 가 `/admin/login` 으로 redirect → **reset 페이지(이메일 링크 타겟)가 도달 불가**했다. `isPublicPath` 게이트로 effect 가 public 경로에서 /me 를 호출하지 않게 수정(early return 은 effect 실행을 막지 못함). 코드 리뷰가 잡아낸 실기능 파괴 — 수정 후 재확인.

## 4. 미룬 항목 (범위 밖, spec §1 과 일치)
- **MFA 복구 코드 재발급**(이미 켠 상태에서 재생성): 백엔드 전용 엔드포인트 없음(confirm 은 enroll 직후 흐름 가정). 별도 phase.
- **그룹 C alert 메일 설정 UI**: 운영 인프라(코드 밖)로 분류 — Prometheus/Grafana/Alertmanager + `passkey.alert.mail.*` 운영 설정.
- **`admin` scope**: `ApiKeyScopeResolver` 에 매핑된 RP 경로가 없어(dead scope) UI 미노출. 향후 admin API 경로 생기면 추가.
- **password-reset 타이밍 사이드채널**: 그룹 A followups §3 과 동일(존재 이메일은 동기 mail.send, 없으면 즉시 return). FE 는 항상 동일 메시지로 enumeration 방지에 기여하나 근본은 BE async dispatch 필요.

## 5. 브라우저 dogfooding 미수행
vitest 단위 테스트로 검증. 실제 브라우저 확인은 미수행:
- password-reset 흐름(SMTP/메일 발송 환경 필요)
- MFA 켜기 → 복구 코드 모달 → 복구 코드로 로그인
- API key 발급(scope 체크박스) → rotate(구 키 만료 배너)
- **권고**: 로컬 8081 + seed(`alice`/`bob`) + SMTP 구동 후 흐름 육안 확인. 특히 §1 의 api-key 발급이 실제 동작하는지.

## 6. codex 독립 리뷰 미실행
OpenAI usage limit(resets Jun 1)로 `/codex review` 미실행. Phase별 spec + code-quality subagent 2단계로 게이트.
- **권고**: 6/1 리셋 후 누적 diff(`6f6bac7..HEAD`)에 `/codex review`. 특히 password-reset enumeration 동일 메시지·public /me 게이트, 복구 코드 1회 표시 게이팅, scope whitelist 일치(`'ceremony'` 제거), rotate 비가역 확인.

## 7. 검증 환경 메모
admin-ui 변경만(백엔드 무관). vitest 19 green(roles 4 + RequirePlatform 3 + Sidebar 2 + SettingsPage 2 + smoke 1 + passwordReset 3 + RecoveryCodesModal 2 + apiKeys 2). 보안 경계는 여전히 BE(`TenantBoundary`/`@PreAuthorize`/permitAll 화이트리스트) — FE 화면은 UX/IA.
