package com.crosscert.passkey.admin.mds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.crosscert.passkey.core.license.RequiresFeature;

/**
 * Fires MdsSchedulerService.runOnce() every 6 hours. Initial delay
 * 30s to avoid all instances thundering at boot.
 */
@Component
public class MdsSyncJob {

    private static final Logger log = LoggerFactory.getLogger(MdsSyncJob.class);

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
