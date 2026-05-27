package com.crosscert.passkey.admin.mds;

import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.entity.MdsBlobCache;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/api/mds")
public class MdsAdminController {

    private static final String SINGLETON_HEX =
            MdsBlobCache.SINGLETON_ID.toString().replace("-", "");

    private final JdbcTemplate jdbc;
    private final MdsSchedulerService scheduler;

    public MdsAdminController(JdbcTemplate jdbc, MdsSchedulerService scheduler) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping("/status")
    public ApiResponse<MdsStatusView> status() {
        MdsStatusView view = jdbc.queryForObject(
                "SELECT version AS \"version\", " +
                "       next_update AS \"nextUpdate\", " +
                "       fetched_at AS \"fetchedAt\" " +
                "FROM APP_OWNER.mds_blob_cache WHERE id=HEXTORAW('" + SINGLETON_HEX + "')",
                (rs, n) -> new MdsStatusView(
                        rs.getLong("version"),
                        rs.getDate("nextUpdate") == null
                                ? null : rs.getDate("nextUpdate").toLocalDate().toString(),
                        rs.getTimestamp("fetchedAt") == null
                                ? null : rs.getTimestamp("fetchedAt").toInstant().toString()
                ));
        return ApiResponse.ok(view);
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @PostMapping("/sync")
    public ApiResponse<MdsSchedulerService.SyncResult> sync() {
        return ApiResponse.ok(scheduler.runOnce());
    }
}
