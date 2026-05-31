# 데이터 retention/purge (그룹 D) — Follow-ups

- **작성일**: 2026-05-31
- **브랜치**: `worktree-data-retention-group-d`
- **spec**: [2026-05-31-data-retention-group-d-design.md](../specs/2026-05-31-data-retention-group-d-design.md)
- **plan**: [2026-05-31-data-retention-group-d.md](../plans/2026-05-31-data-retention-group-d.md)

P1-4를 5개 Task로 마감했다(**이로써 P1 7건 전부 완료**). subagent-driven + Task별 spec/code-quality 2단계 리뷰. 아래는 의도적으로 미룬 항목과 후속 권고다.

---

## 1. audit_log purge 제외 (의도적, spec)

audit_log는 이중 hash-chain(global + per-tenant)이라 중간/불연속 삭제 시 `AuditChainVerifier`(genesis부터 순회)가 깨진다. anchor 보존 + 연속 선두 블록 purge는 복잡·고위험이고, spec도 "audit는 forensic 보존과 균형"이라 명시 → **이번 범위 제외**.

- **권고**: 감사 규정상 audit 보존기간이 짧아져야 하면 별도 anchor-based purge phase(genesis 보존 + 연속 선두 블록 삭제 전/후 verify). global·per-tenant 두 체인 각각 anchor 필요.

## 2. credential soft-delete 부재 (제외)

credential은 soft-delete 없이 revoke 즉시 hard-delete(이력은 audit에만). retention 대상 아님. soft-delete 도입은 별도 스키마·설계 결정이라 제외.

## 3. 배칭 — 반영됨 (코드 리뷰 carry-forward 해소)

D-Task 4 리뷰에서 "첫 prod 실행(수개월 누적) 시 unbounded DELETE 한 방이 긴 lock·undo + lease 만료 중 second instance 경합" 위험이 제기돼 **이번에 반영**: 모든 purge가 native `ROWNUM <= :batchSize`(BATCH=1000) 캡 + service loop(삭제<BATCH까지 반복) + lease try/finally release. 첫 실행도 1000행 단위 짧은 트랜잭션.

- **잔여 고려**: BATCH=1000은 보수적. 운영 데이터량 보고 조정 가능. 배치당 짧은 트랜잭션이라 안전.

## 4. retention 기간 — 운영 조정

기본값: invitation P90D, password-reset-token P30D, recovery-code P180D, webauthn-snapshot P365D, mds-sync-history P90D. 감사·규정 요구에 따라 `passkey.retention.*`로 조정.

- **권고**: GDPR 등 규정 보존기간이 명확해지면 그에 맞춰. 현재는 합리적 기본값.

## 5. mds_sync_history는 JPA 엔티티 아님 (JdbcTemplate)

`mds_sync_history`(V34)는 JPA 엔티티 없이 `MdsHistoryService`가 JdbcTemplate(`APP_OWNER.` prefix)로 관리. purge도 일관되게 native DELETE(`MdsHistoryService.purgeStartedBefore`). 다른 4개는 JPA repository native 쿼리(`{h-schema}`).

## 6. lease release 패턴 추가 (기존 job과 차이)

`RetentionPurgeJob`은 기존 `KeyExpirationJob`/`MdsSyncJob`(release 미호출, TTL 만료 의존)과 달리 try/finally로 `leases.release(name, holder)`를 명시 호출 — 정상 종료 시 lease 즉시 반환(다음 인스턴스가 30분 안 기다림). 30분 TTL은 long-run 안전망.

- **권고**: 기존 두 job도 동일 release 패턴으로 통일하면 일관성↑(선택, 범위 밖).

## 7. codex 독립 리뷰 미실행 (전 Task 공통)

OpenAI usage limit(resets Jun 1)로 `/codex review` 미실행. Task별 spec + code-quality subagent 2단계로 게이트.

- **권고**: 6/1 리셋 후 누적 diff(`b838f5a..HEAD`)에 `/codex review`. 특히 native 쿼리 안전 불변식·ROWNUM placement, 배칭 종료성, lease release.

## 8. 검증 환경 메모

신규 마이그레이션 없음. 게이트: **core 94 + admin-app 125 단위/슬라이스 green**(retention 8 포함). bulk DELETE의 Oracle 실동작·배칭 루프는 Testcontainers IT(flaky)라 단위/슬라이스로 게이트 — CI 재실행 권장. native 쿼리 컬럼명은 마이그레이션(V27/V29/V36/V37)과 엔티티 @Column 매핑으로 검증됨.
