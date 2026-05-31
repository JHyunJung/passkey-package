# 데이터 retention/purge (그룹 D) — Design

- **작성일**: 2026-05-31
- **대상**: Crosscert Passkey Platform (core / admin-app)
- **근거 spec**: [2026-05-29-saas-readiness-gap-audit-design.md](2026-05-29-saas-readiness-gap-audit-design.md) §4 P1-4
- **성격**: 구현 설계. 후속 writing-plans의 입력물.

## 1. 목적 / 범위

SaaS gap audit의 P1-4(데이터 retention 정책)를 마감한다. 무한 증가하는 **hash-chain 없는 테이블 5개**를 주기적으로 purge한다. P1은 이로써 7건 전부 완료된다.

**대상 (체인 없음, 단순 DELETE)**:
| 테이블 | 삭제 조건 | 나이 컬럼 | retention 기본 |
|---|---|---|---|
| `admin_user_invitation` | (accepted_at 있음 OR expires_at 과거) 그 시점 < cutoff | accepted_at/expires_at | 90d |
| `admin_password_reset_token` | (consumed_at 있음 OR expires_at 과거) < cutoff | consumed_at/expires_at | 30d |
| `admin_user_recovery_code` | used_at 있음 AND used_at < cutoff (미사용 보존) | used_at | 180d |
| `tenant_webauthn_snapshot` | taken_at < cutoff | taken_at | 365d |
| `mds_sync_history` | started_at < cutoff | started_at | 90d |

**범위 밖(deferred — 명시적 제외)**:
- **`audit_log`**: 이중 hash-chain(global + per-tenant). 중간/불연속 삭제 시 `AuditChainVerifier`가 깨짐(genesis부터 순회). anchor 보존 + 연속 선두 블록 purge는 복잡·고위험이고, spec도 "audit는 forensic 보존과 균형"이라 명시 → **이번 범위 제외**. 감사 규정상 audit 보존기간이 짧아지면 별도 anchor-based phase.
- **`credential`**: soft-delete 없음(revoke 즉시 hard-delete). retention 대상 아님.
- **`challenge`**: Redis 5분 TTL 자동 — 수동 정리 불필요.

**원칙**: 기존 `KeyExpirationJob` 패턴(@Scheduled + `SchedulerLeaseService` leader election + `passkey.*` 설정값 + `(scheduler)` 액터 audit) 재사용. **신규 스키마 없음**(기존 컬럼으로 나이 계산). 테스트 최소화(그룹 C 정책). admin-app에 배치(스케줄러 도메인).

## 2. 아키텍처

**구조** (admin-app `retention/` 패키지):
- **`RetentionPurgeJob`** (@Component, @Scheduled): 하루 1회. `SchedulerLeaseService.tryAcquire("retention-purge", holder, Duration.ofMinutes(30))`로 leader election(다중 인스턴스 1회만; TTL 30분 = purge 소요 여유). lease 실패 시 log.debug + 반환. 5개 service 메서드를 **테이블별 try/catch로 격리** 호출 → 한 테이블 실패가 나머지·전체 job 안 막음. 끝에 총계+실패 목록을 audit 1건.
- **`RetentionPurgeService`** (@Service): 테이블별 purge 메서드(cutoff Instant 받아 bulk DELETE, 삭제 건수 반환). 각 메서드 독립 `@Transactional`(테이블별 격리 — 한 트랜잭션 실패가 다른 테이블 롤백 안 함).

**데이터 흐름**: Job → cutoff = `clock.instant().minus(retention)` 계산(테이블별) → service.purgeX(cutoff) → repository bulk DELETE → 건수 집계 → audit.

## 3. Repository bulk DELETE (core)

각 엔티티 repository에 `@Modifying @Transactional @Query` 메서드 추가. `int` 반환(삭제 건수). 활성 데이터는 쿼리상 절대 매칭 안 됨(안전 불변식).

- `AdminUserInvitationRepository.deleteConsumedOrExpiredBefore(Instant cutoff)`:
  `DELETE FROM AdminUserInvitation i WHERE (i.acceptedAt IS NOT NULL AND i.acceptedAt < :cutoff) OR (i.acceptedAt IS NULL AND i.expiresAt < :cutoff)` — pending(미수락·미만료)은 제외.
- `AdminPasswordResetTokenRepository.deleteConsumedOrExpiredBefore(Instant cutoff)`: 동일 패턴(consumedAt/expiresAt).
- `AdminUserRecoveryCodeRepository.deleteUsedBefore(Instant cutoff)`:
  `DELETE WHERE r.usedAt IS NOT NULL AND r.usedAt < :cutoff` — 미사용(MFA 백업) 보존.
- `TenantWebauthnSnapshotRepository.deleteTakenBefore(Instant cutoff)`: `DELETE WHERE s.takenAt < :cutoff`. (repository 존재 — 메서드만 추가.)
- `MdsSyncHistoryRepository.deleteStartedBefore(Instant cutoff)`: `DELETE WHERE h.startedAt < :cutoff`. (**repository 신규 생성** — 엔티티는 존재.)

엔티티 필드명은 구현 시 확인(JPQL은 필드명 기준). 컬럼명이 아니라 엔티티 속성명을 쓴다.

## 4. VPD / 권한

admin-app은 VPD 비활성(조사 확인). 대상 테이블은 tenant_id가 없거나(admin_*, mds_sync_history) cross-tenant 정리가 의도(tenant_webauthn_snapshot). retention은 전 테넌트 대상이고 **스케줄러 컨텍스트(인증 주체 없음)**에서 실행되므로 `@PreAuthorize` 불필요(엔드포인트 아님). DELETE는 cutoff·상태 조건으로만 제한 — 전 행 삭제 같은 위험 없음.

## 5. 설정값 (admin-app application.yml `passkey.retention.*`)

```yaml
passkey:
  retention:
    fixed-delay: P1D            # 하루 1회 실행
    initial-delay: PT5M         # 부팅 후 5분
    invitation: P90D            # 만료/수락 후 90일
    password-reset-token: P30D  # 만료/소비 후 30일
    recovery-code: P180D        # 사용 후 180일
    webauthn-snapshot: P365D    # 스냅샷 1년
    mds-sync-history: P90D      # sync 이력 90일
```
(Duration ISO-8601, 그룹 A·B·C 패턴. 테이블별 독립 retention. Job 생성자 `@Value`로 주입, lease TTL은 purge 여유 고려 30분.)

## 6. audit 기록

Job 끝에 `audit.append(new AuditAppendRequest(null, "(scheduler)", "RETENTION_PURGE", null, null, null, payload))` (KeyExpirationJob 패턴). payload(LinkedHashMap — hash 재현성)에:
- 테이블별 삭제 건수(`invitationsPurged`, `resetTokensPurged`, `recoveryCodesPurged`, `snapshotsPurged`, `mdsHistoryPurged`)
- 실패한 테이블 목록(`failed`: 비었으면 생략 또는 빈 리스트)
- 각 cutoff timestamp

매 실행마다 audit(삭제 0건이어도) — job 동작 확인 + 무한증가 방지 증빙.

## 7. 에러 처리 / 경계

- **테이블별 격리**: Job이 5개 메서드를 각각 try/catch. 한 테이블 DELETE 실패(DB 오류) → log.error + 실패 테이블을 audit payload에 + 나머지 계속 + 다음 일정 재시도(멱등 DELETE라 안전).
- **lease 안전**: tryAcquire 실패 시 조용히 반환. TTL은 purge 소요 여유(30분).
- **audit 실패 격리**: 마지막 audit.append 실패해도 purge는 이미 커밋(별개 트랜잭션) — audit 실패는 log.error만, job 성공 종료.
- **활성 데이터 보호 (핵심 불변식)**: 모든 DELETE가 cutoff·상태 조건으로 제한 — pending invitation, 미만료/미소비 토큰, 미사용 recovery code는 쿼리상 절대 매칭 안 됨.
- **0건 정상**: 매일 도는데 대부분 0건 가능. 정상 처리, audit는 매번.
- **시계**: `Clock` 주입(테스트 가능).

## 8. 테스트 전략 (최소화 — 그룹 C 정책)

- **핵심 단위 — 활성 보존 불변식**: invitation purge가 **pending(미수락·미만료)을 보존하고 만료/수락된 것만 삭제**하는지 `@DataJpaTest`(또는 H2/실DB) 또는 정밀 mock으로 검증(안전 핵심). recovery code의 미사용 보존도 1개. 나머지 3개(reset/snapshot/mds_history)는 동일 단순 패턴이라 생략.
- **Job 격리**: `RetentionPurgeJob`이 한 service 메서드 throw 시 나머지 호출 + audit 기록(mock service) — 그룹 C dispatcher 격리와 동일 성격, 1개.
- **생략(속도)**: lease 통합, 각 repository 쿼리 개별 검증(대표 외), Oracle bulk DELETE 동작(Testcontainers IT flaky → 제외, core 전체 게이트 자연 커버).
- **회귀**: 신규 repository 메서드/클래스라 기존 테스트 영향 적음. JpaStubs 슬라이스에 신규 `MdsSyncHistoryRepository` @MockBean 추가 필요 가능성(그룹 A·B·C 학습 — 대비).

## 9. 커밋 전 게이트

메모리 지침: 커밋 전 `/codex review`(6/1 quota 리셋 후 누적 diff) + code quality subagent. 특히 활성 데이터 보존 쿼리 조건, 테이블별 격리, lease.

## 10. 구현 순서(권장)

1. repository 메서드 5개(+ MdsSyncHistoryRepository 신규).
2. `RetentionPurgeService`(테이블별 purge + 활성 보존 쿼리).
3. `RetentionPurgeJob`(@Scheduled + lease + 격리 + audit) + 설정.
4. 검증 + followups.
