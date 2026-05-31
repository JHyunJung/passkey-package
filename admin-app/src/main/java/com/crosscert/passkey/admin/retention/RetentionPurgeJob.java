package com.crosscert.passkey.admin.retention;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 하나만 실행. 5개 테이블을 테이블별 try/catch 로 격리 purge(한 테이블 실패가
 * 나머지·전체 job 을 막지 않음). 끝에 총계+실패 목록을 (scheduler) 액터로 audit.
 * audit_log 자체는 hash-chain forensic 이라 purge 대상 아님(spec).
 */
@Component
public class RetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeJob.class);
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

    public RetentionPurgeJob(RetentionPurgeService service,
                             SchedulerLeaseService leases,
                             AuditLogService audit,
                             Clock clock,
                             @Value("${passkey.retention.invitation:P90D}") Duration invitationRetention,
                             @Value("${passkey.retention.password-reset-token:P30D}") Duration resetTokenRetention,
                             @Value("${passkey.retention.recovery-code:P180D}") Duration recoveryCodeRetention,
                             @Value("${passkey.retention.webauthn-snapshot:P365D}") Duration snapshotRetention,
                             @Value("${passkey.retention.mds-sync-history:P90D}") Duration mdsHistoryRetention) {
        this.service = service;
        this.leases = leases;
        this.audit = audit;
        this.clock = clock;
        this.invitationRetention = invitationRetention;
        this.resetTokenRetention = resetTokenRetention;
        this.recoveryCodeRetention = recoveryCodeRetention;
        this.snapshotRetention = snapshotRetention;
        this.mdsHistoryRetention = mdsHistoryRetention;
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

        payload.put("failed", failed);
        try {
            audit.append(new AuditAppendRequest(
                    null, "(scheduler)", "RETENTION_PURGE", null, null, null, payload));
        } catch (RuntimeException e) {
            log.error("retention purge audit append failed (purge already applied): cause={}", e.toString());
        }
        log.info("retention purge done: {} failed={}", payload, failed);
    }

    private void purgeOne(Map<String, Object> payload, List<String> failed,
                          String countKey, String name, IntSupplier purge) {
        try {
            payload.put(countKey, purge.getAsInt());
        } catch (RuntimeException e) {
            log.error("retention purge failed: table={} cause={}", name, e.toString());
            failed.add(name);
            payload.put(countKey, -1);
        }
    }
}
