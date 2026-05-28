package com.crosscert.passkey.admin.mds;

import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.entity.MdsBlobCache;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/admin/api/mds")
public class MdsAdminController {

    private static final String SINGLETON_HEX =
            MdsBlobCache.SINGLETON_ID.toString().replace("-", "");

    private final JdbcTemplate jdbc;
    private final MdsSchedulerService scheduler;
    private final MdsHistoryService historyService;
    private final StringRedisTemplate redis;
    private final Environment env;

    public MdsAdminController(JdbcTemplate jdbc,
                              MdsSchedulerService scheduler,
                              MdsHistoryService historyService,
                              StringRedisTemplate redis,
                              Environment env) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
        this.historyService = historyService;
        this.redis = redis;
        this.env = env;
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping("/status")
    public ApiResponse<MdsStatusView> status() {
        // Trust anchor count: MDS-derived AAGUID entries live in Redis under
        // "mds:aaguid:*" (populated by MdsSchedulerService after each BLOB
        // fetch). No dedicated table holds them. Best-effort count — if
        // Redis is unreachable, fall back to 0 instead of failing the call.
        int trustAnchorCount;
        try {
            Set<String> keys = redis.keys("mds:aaguid:*");
            trustAnchorCount = keys == null ? 0 : keys.size();
        } catch (RuntimeException e) {
            trustAnchorCount = 0;
        }

        // Trust mode is a server-wide configuration; no per-tenant override
        // at this layer. Default mirrors the MDS_STRICT_OPTIONAL behavior
        // used elsewhere in the codebase.
        String trustMode = env.getProperty("passkey.mds.trust-mode", "MDS_STRICT_OPTIONAL");

        int okCount = historyService.successRate30dCountOk();
        int totalCount = historyService.successRate30dCountTotal();
        MdsStatusView.SuccessRate successRate30d =
                new MdsStatusView.SuccessRate(okCount, totalCount);

        final int finalTrustAnchorCount = trustAnchorCount;
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
                                ? null : rs.getTimestamp("fetchedAt").toInstant().toString(),
                        finalTrustAnchorCount,
                        trustMode,
                        successRate30d
                ));
        return ApiResponse.ok(view);
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @PostMapping("/sync")
    public ApiResponse<MdsSchedulerService.SyncResult> sync() {
        return ApiResponse.ok(scheduler.runOnce());
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping("/history")
    public ApiResponse<List<MdsHistoryView>> history(
            @RequestParam(name = "limit", defaultValue = "5") int limit) {
        return ApiResponse.ok(historyService.recent(limit));
    }
}
