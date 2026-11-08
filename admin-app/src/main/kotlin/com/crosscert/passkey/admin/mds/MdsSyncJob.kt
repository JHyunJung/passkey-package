package com.crosscert.passkey.admin.mds

import com.crosscert.passkey.core.license.RequiresFeature
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(MdsSyncJob::class.java)

/**
 * Fires MdsSchedulerService.runOnce() every 6 hours. Initial delay
 * 30s to avoid all instances thundering at boot.
 */
@Component
class MdsSyncJob(private val scheduler: MdsSchedulerService) {

    @RequiresFeature("mds")
    @Scheduled(
        fixedDelayString = "\${passkey.mds.fixed-delay:PT6H}",
        initialDelayString = "\${passkey.mds.initial-delay:PT30S}",
    )
    fun run() {
        log.debug("MdsSyncJob firing")
        val result = scheduler.runOnce()
        log.info("MdsSyncJob result: {}", result)
    }
}
