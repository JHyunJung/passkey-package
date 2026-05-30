# 설정값 미적용 마감 (그룹 B) — Follow-ups

- **작성일**: 2026-05-31
- **브랜치**: `worktree-config-enforcement-group-b`
- **spec**: [2026-05-31-config-enforcement-group-b-design.md](../specs/2026-05-31-config-enforcement-group-b-design.md)
- **plan**: [2026-05-31-config-enforcement-group-b.md](../plans/2026-05-31-config-enforcement-group-b.md)

P1-1(per-tenant WebAuthn ceremony 반영) + P1-5(API key scope 검증 + rotation)를 6개 Task로 마감했다. subagent-driven 실행 + Task별 spec/code-quality 2단계 리뷰. 아래는 리뷰에서 **의도적으로 범위 밖으로 미룬** 항목과 후속 권고다 (deferred-by-design).

---

## 0. 최종 통합 리뷰에서 닫은 보안 seam (2건, 반영 완료)

최종 cross-task 리뷰가 발견한 두 seam을 머지 전 닫았다:
- **scope 발급 whitelist + 정규화** (`9b714fc`): 발급 시 scope를 `{registration,authentication,admin}`(api_key_scope V21 CHECK와 동일)로 검증 + 소문자 정규화. 이전엔 `"Registration"`/오타 scope가 200으로 발급되나 enforcement(exact match)에서 모든 RP 호출 403 = "silent dead key". 이제 unknown scope는 발급 시 `BusinessException(INVALID_INPUT, 400)`로 차단, 응답·audit도 정규화값 반영(truthful).
- **resolver fail-closed** (`05a0d38`): `/api/v1/rp/` 하위인데 세 prefix에 미매핑인 경로는 sentinel scope `"__unmapped_rp__"`(enum에 없어 어떤 키도 보유 불가)를 요구해 403. 미래 RP 엔드포인트가 scope 매핑 없이 추가돼도 fail-open 안 됨(매핑을 명시 추가해야 동작). `/api/v1/rp` 밖(jwks, actuator)은 그대로 scope 불요.

---

## 1. self-service credential scope 정책 — delete 분리 여지 (보안 정책 결정)

`ApiKeyScopeResolver`가 `/api/v1/rp/credentials/**`(목록/이름변경/**삭제**)를 전부 `registration` scope로 매핑한다. P0-4 self-service credential이 등록 계열이라는 판단. **단, DELETE(기존 패스키 삭제)는 파괴적 작업**이라 `registration` 키가 사용자의 기존 credential을 삭제할 수 있다.

- **현황**: 코드 코멘트로 민감성 명시(`ApiKeyScopeResolver`). 정책 변경 없음.
- **권고**: threat model이 "등록 권한"과 "기존 credential 삭제 권한"을 분리해야 하면, DELETE를 별도 scope(예: 미사용 중인 `admin` scope 활용 또는 신규 scope)로 분리. 현재는 registration으로 통일.

## 2. ceremony conveyance 기본값 변화 ("indirect" → "none")

P1-1 전 registration/start는 attestation을 항상 `"indirect"` 하드코딩했다. 이제 테넌트의 `attestationConveyance`(엔티티/DB 기본 `NONE`)를 따르므로 **대부분 테넌트가 `"none"`을 받는다**.

- **현황**: 의도된 변화 — conveyance가 테넌트 정책을 반영. `"none"`은 privacy-preserving WebAuthn 기본값(불필요한 attestation 데이터 요청 안 함)이고, finish의 format/MDS/AAGUID 검증은 conveyance hint와 독립이라 검증이 약화되지 않음.
- **권고**: 기존 테넌트가 attestation을 받길 원하면 테넌트 설정을 `INDIRECT`/`DIRECT`로 명시. wire 응답이 조용히 바뀌므로 운영 공지에 포함 권장.

## 3. API key rotation — minor (taste/cosmetic)

- **grace 주입 스타일**: `ApiKeyAdminService`가 `@Value` 필드 주입(`InvitationService` 패턴). 더 유사한 `KeyExpirationJob`(`passkey.key-rotation.grace`)은 생성자 주입을 씀 — 테스트가 reflection으로 set해야 하므로 생성자 주입이 약간 더 깔끔. taste-level, 변경 안 함.
- **inactive 키 rotate → 404**: 존재하지만 revoked/expired인 키 rotate 시 `API_KEY_NOT_FOUND`(404). 의미상 409 Conflict가 더 정직하나 plan이 "신규 ErrorCode 지양"을 명시 → 기존 코드 재사용. 커스텀 메시지로 로그에선 구분됨.
- **DTO naming 불일치**: `ApiKeyCreateResponse.plainText` vs `ApiKeyRotateResponse.plaintextKey` — 같은 개념, 다른 필드명. cosmetic.
- **동시 rotate window**: 같은 키 동시 rotate 2회 → 신규 키 2개 + 구 키 만료. operator-initiated rare라 무해. optimistic lock(`@Version`)으로 닫을 수 있으나 범위 밖.

## 4. suspend 일괄 revoke 의미 변화 (active 정의 통일)

`findActiveByTenantId`가 `expiresAt` 체크를 포함하도록 통일되면서, suspend의 일괄 revoke가 **이미 만료된 키를 건너뛴다**(전엔 만료 키도 revoke). 만료 키는 이미 gateway `isActive`에서 비활성이라 기능적 무해 — `revokedAt` 스탬프 일관성만 약간 잃음. 의도된 정리.

## 5. codex 독립 리뷰 미실행 (전 Task 공통)

메모리 지침(커밋 전 `/codex review`)을 따르려 했으나 전 Task에서 OpenAI usage limit(resets Jun 1)로 **codex 독립 리뷰 미실행**. Task별 spec + code-quality subagent 2단계 리뷰로 게이트.

- **권고**: 6/1 리셋 후 누적 diff(`4b797d1..HEAD`)에 `/codex review`. 특히 scope enforcement(403 경계·TenantContext 누출 수정·getServletPath fail-open 방지), rotation 트랜잭션 원자성, conveyance 매핑.

## 6. admin-ui 후속 (별도 phase)

이번 그룹 B는 백엔드만. 운영자 UI가 필요한 것:
- **API key rotation 버튼**: `POST /admin/api/api-keys/{id}/rotate` 호출 + 신규 평문 키 1회 표시(인쇄/저장 유도) + 구 키 만료 시각 안내(RP 교체 데드라인).
- **scope 표시/편집**: 발급 시 scope 선택 UI(현재 발급은 scope 필수). insufficient_scope(403) 에러의 사용자 안내.

## 7. 검증 환경 메모

그룹 B는 **신규 마이그레이션 없음**(기존 컬럼 재사용). 검증 게이트: **core 전체 green(VPD IT 포함) + passkey-app 단위/슬라이스 46 + admin-app 115 green**. Oracle/Redis Testcontainers `*IT`는 이 환경에서 flaky(이전 phase와 동일) — `WebauthnConfigSnapshotIT`(Redis 연결), `LicenseGuardFilterIT`(pre-existing `TenantLifecycleService` bean 누락)는 그룹 B 변경과 무관. CI/충분 리소스 환경 재실행 권장.

JpaStubs 회귀: `ApiKeyScopeRepository` 추가로 admin-app의 7개 `@WebMvcTest` 슬라이스(빈 Metamodel JpaStubs 패턴)에 `@MockBean ApiKeyScopeRepository` 추가함(그룹 A와 동일 패턴). passkey-app 슬라이스는 영향 없음(직접 필터 단위 테스트 + 풀 컨텍스트 IT).
