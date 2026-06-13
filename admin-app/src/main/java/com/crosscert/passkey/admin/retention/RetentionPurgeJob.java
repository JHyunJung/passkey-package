package com.crosscert.passkey.admin.retention;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * P1-4 데이터 retention purge job. 하루 1회, SchedulerLease 로 다중 인스턴스 중
 * 하나만 실행. 6개 테이블을 테이블별 try/catch 로 격리 purge(한 테이블 실패가
 * 나머지·전체 job 을 막지 않음). 끝에 총계+실패 목록을 (scheduler) 액터로 audit.
 * audit_log 자체는 hash-chain forensic 이라 purge 대상 아님(spec).
 */
@Slf4j
@Component
public class RetentionPurgeJob {

    private static final String LEASE_NAME = "retention-purge";

    private final RetentionPurgeService service;
    private final SchedulerLeaseService leases;
    private final AuditLogService audit;
    private final Clock clock;
    private final Duration invitationRetention;
    private final Duration resetTokenRetention;
    private final Duration recoveryCodeRetention;
    private final Duration snapshotRetention;
    private final Duration mdsHistoryRetention;
    private final Duration ceremonyEventRetention;
    private final Duration credentialAuthEventRetention;

    public RetentionPurgeJob(RetentionPurgeService service,
                             SchedulerLeaseService leases,
                             AuditLogService audit,
                             Clock clock,
                             @Value("${passkey.retention.invitation:P90D}") Duration invitationRetention,
                             @Value("${passkey.retention.password-reset-token:P30D}") Duration resetTokenRetention,
                             @Value("${passkey.retention.recovery-code:P180D}") Duration recoveryCodeRetention,
                             @Value("${passkey.retention.webauthn-snapshot:P365D}") Duration snapshotRetention,
                             @Value("${passkey.retention.mds-sync-history:P90D}") Duration mdsHistoryRetention,
                             @Value("${passkey.retention.ceremony-event:P90D}") Duration ceremonyEventRetention,
                             @Value("${passkey.retention.credential-auth-event:P90D}") Duration credentialAuthEventRetention) {
        this.service = service;
        this.leases = leases;
        this.audit = audit;
        this.clock = clock;
        this.invitationRetention = invitationRetention;
        this.resetTokenRetention = resetTokenRetention;
        this.recoveryCodeRetention = recoveryCodeRetention;
        this.snapshotRetention = snapshotRetention;
        this.mdsHistoryRetention = mdsHistoryRetention;
        this.ceremonyEventRetention = ceremonyEventRetention;
        this.credentialAuthEventRetention = credentialAuthEventRetention;
    }

    @Scheduled(
            fixedDelayString = "${passkey.retention.fixed-delay:P1D}",
            initialDelayString = "${passkey.retention.initial-delay:PT5M}")
    public void runOnce() {
        String holder = ManagementFactory.getRuntimeMXBean().getName();
        if (!leases.tryAcquire(LEASE_NAME, holder, Duration.ofMinutes(30))) {
            log.debug("RetentionPurgeJob skipped — another instance holds the lease");
            return;
        }
        // try/finally: 정상·예외 어느 경로든 lease 를 즉시 반환한다. batch 분할로 작업이
        // 길어져도 끝나는 즉시 release 하므로 다음 실행/다른 인스턴스가 30분 TTL 만료를
        // 기다릴 필요 없이 곧바로 획득 가능(TTL 은 크래시 시 stale lease 회수용 안전망일 뿐).
        try {
            Instant now = clock.instant();
            Map<String, Object> payload = new LinkedHashMap<>();
            List<String> failed = new ArrayList<>();

            purgeOne(payload, failed, "invitationsPurged", "invitations",
                    () -> service.purgeInvitations(now.minus(invitationRetention)));
            purgeOne(payload, failed, "resetTokensPurged", "resetTokens",
                    () -> service.purgeResetTokens(now.minus(resetTokenRetention)));
            purgeOne(payload, failed, "recoveryCodesPurged", "recoveryCodes",
                    () -> service.purgeRecoveryCodes(now.minus(recoveryCodeRetention)));
            purgeOne(payload, failed, "snapshotsPurged", "snapshots",
                    () -> service.purgeSnapshots(now.minus(snapshotRetention)));
            purgeOne(payload, failed, "mdsHistoryPurged", "mdsHistory",
                    () -> service.purgeMdsHistory(now.minus(mdsHistoryRetention)));
            purgeOne(payload, failed, "ceremonyEventsPurged", "ceremonyEvents",
                    () -> service.purgeCeremonyEvents(now.minus(ceremonyEventRetention)));
            purgeOne(payload, failed, "credentialAuthEventsPurged", "credentialAuthEvents",
                    () -> service.purgeCredentialAuthEvents(now.minus(credentialAuthEventRetention)));

            payload.put("failed", failed);
            try {
                audit.append(new AuditAppendRequest(
                        null, "(scheduler)", "RETENTION_PURGE", null, null, null, payload));
            } catch (RuntimeException e) {
                log.error("retention purge audit append failed (purge already applied): cause={}", e.toString());
            }
            log.info("retention purge done: {} failed={}", payload, failed);
        } finally {
            leases.release(LEASE_NAME, holder);
        }
    }

    private void purgeOne(Map<String, Object> payload, List<String> failed,
                          String countKey, String name, IntSupplier purge) {
        try {
            payload.put(countKey, purge.getAsInt());
        } catch (RuntimeException e) {
            // count 키를 아예 넣지 않는다 — sentinel(-1) 은 합산·통계를 오염시키므로,
            // failed 리스트 멤버십이 유일한 실패 신호다.
            log.error("retention purge failed: table={} cause={}", name, e.toString());
            failed.add(name);
        }
    }
}
