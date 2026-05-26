package com.crosscert.passkey.admin.mds;

import com.crosscert.passkey.core.api.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/api/mds")
public class MdsAdminController {

    private final JdbcTemplate jdbc;
    private final MdsSchedulerService scheduler;

    public MdsAdminController(JdbcTemplate jdbc, MdsSchedulerService scheduler) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
    }

    @GetMapping("/status")
    public ApiResponse<MdsStatusView> status() {
        MdsStatusView view = jdbc.queryForObject(
                "SELECT version AS \"version\", " +
                "       next_update AS \"nextUpdate\", " +
                "       fetched_at AS \"fetchedAt\" " +
                "FROM APP_OWNER.mds_blob_cache WHERE id=1",
                (rs, n) -> new MdsStatusView(
                        rs.getLong("version"),
                        rs.getDate("nextUpdate") == null
                                ? null : rs.getDate("nextUpdate").toLocalDate().toString(),
                        rs.getTimestamp("fetchedAt") == null
                                ? null : rs.getTimestamp("fetchedAt").toInstant().toString()
                ));
        return ApiResponse.ok(view);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/sync")
    public ApiResponse<MdsSchedulerService.SyncResult> sync() {
        return ApiResponse.ok(scheduler.runOnce());
    }
}
