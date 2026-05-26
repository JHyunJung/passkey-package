package com.crosscert.passkey.admin.mds;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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
    public Map<String, Object> status() {
        return jdbc.queryForObject(
                "SELECT version AS \"version\", " +
                "       next_update AS \"nextUpdate\", " +
                "       fetched_at AS \"fetchedAt\" " +
                "FROM APP_OWNER.mds_blob_cache WHERE id=1",
                (rs, n) -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("version", rs.getLong("version"));
                    m.put("nextUpdate", rs.getDate("nextUpdate") == null
                            ? null : rs.getDate("nextUpdate").toLocalDate());
                    m.put("fetchedAt", rs.getTimestamp("fetchedAt") == null
                            ? null : rs.getTimestamp("fetchedAt").toInstant());
                    return m;
                });
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/sync")
    public MdsSchedulerService.SyncResult sync() {
        return scheduler.runOnce();
    }
}
