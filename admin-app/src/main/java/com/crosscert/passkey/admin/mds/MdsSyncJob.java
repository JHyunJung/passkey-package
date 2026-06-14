package com.crosscert.passkey.admin.mds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.crosscert.passkey.core.license.RequiresFeature;

/**
 * Fires MdsSchedulerService.runOnce() every 6 hours. Initial delay
 * 30s to avoid all instances thundering at boot.
 */
@Slf4j
@Component
public class MdsSyncJob {

    private final MdsSchedulerService scheduler;

    public MdsSyncJob(MdsSchedulerService scheduler) {
        this.scheduler = scheduler;
    }

    @RequiresFeature("mds")
    @Scheduled(
            fixedDelayString = "${passkey.mds.fixed-delay:PT6H}",
            initialDelayString = "${passkey.mds.initial-delay:PT30S}")
    public void run() {
        log.debug("MdsSyncJob firing");
        MdsSchedulerService.SyncResult result = scheduler.runOnce();
        log.info("MdsSyncJob result: {}", result);
    }
}
