package com.crosscert.passkey.admin.mds

import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.config.DbSchemaProperties
import com.crosscert.passkey.core.entity.MdsBlobCache
import org.springframework.core.env.Environment
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val SINGLETON_HEX: String = MdsBlobCache.SINGLETON_ID.toString().replace("-", "")

@RestController
@RequestMapping("/admin/api/mds")
class MdsAdminController(
    private val jdbc: JdbcTemplate,
    private val scheduler: MdsSchedulerService,
    private val historyService: MdsHistoryService,
    private val redis: StringRedisTemplate,
    private val env: Environment,
    dbSchema: DbSchemaProperties,
) {

    // 원 Java 처럼 생성 시 null 검증을 하지 않는다(DbSchemaProperties 가 검증; 슬라이스 테스트 mock 은 null 을 준다).
    private val schema: String? = dbSchema.schema()

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping("/status")
    fun status(): ApiResponse<MdsStatusView> {
        // Trust anchor count: MDS-derived AAGUID entries live in Redis under
        // "mds:aaguid:*" (populated by MdsSchedulerService after each BLOB
        // fetch). No dedicated table holds them. Best-effort count — if
        // Redis is unreachable, fall back to 0 instead of failing the call.
        //
        // G16: SCAN (cursor-based, non-blocking) instead of KEYS — KEYS walks
        // the entire keyspace on Redis's single thread, blocking every other
        // client (auth cache lookups, challenge lookups, ...) for the duration.
        val trustAnchorCount = try {
            countKeys("mds:aaguid:*")
        } catch (e: RuntimeException) {
            0
        }

        // Trust mode is a server-wide configuration; no per-tenant override
        // at this layer. Default mirrors the MDS_STRICT_OPTIONAL behavior
        // used elsewhere in the codebase.
        val trustMode = env.getProperty("passkey.mds.trust-mode", "MDS_STRICT_OPTIONAL")

        val okCount = historyService.successRate30dCountOk()
        val totalCount = historyService.successRate30dCountTotal()
        val successRate30d = MdsStatusView.SuccessRate(okCount, totalCount)

        val view = jdbc.queryForObject(
            "SELECT version AS \"version\", " +
                "       next_update AS \"nextUpdate\", " +
                "       fetched_at AS \"fetchedAt\" " +
                "FROM " + schema + ".mds_blob_cache WHERE id=HEXTORAW('" + SINGLETON_HEX + "')",
            RowMapper { rs, _ ->
                MdsStatusView(
                    rs.getLong("version"),
                    rs.getDate("nextUpdate")?.toLocalDate()?.toString(),
                    rs.getTimestamp("fetchedAt")?.toInstant()?.toString(),
                    trustAnchorCount,
                    trustMode,
                    successRate30d,
                )
            },
        )
        return ApiResponse.ok(view)
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @PostMapping("/sync")
    fun sync(): ApiResponse<MdsSchedulerService.SyncResult> = ApiResponse.ok(scheduler.runOnce())

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping("/history")
    fun history(
        @RequestParam(name = "limit", defaultValue = "5") limit: Int,
    ): ApiResponse<List<MdsHistoryView>> = ApiResponse.ok(historyService.recent(limit))

    /**
     * Counts keys matching `pattern` via SCAN (cursor-based) instead of
     * KEYS. KEYS blocks the single-threaded Redis server for the duration of
     * a full keyspace walk; SCAN incrementally iterates in small batches so
     * other clients are never blocked, at the cost of an O(N) client-side
     * count instead of a single O(N) server-side one.
     */
    private fun countKeys(pattern: String): Int {
        var count = 0
        redis.scan(ScanOptions.scanOptions().match(pattern).count(500).build()).use { cursor ->
            while (cursor.hasNext()) {
                cursor.next()
                count++
            }
        }
        return count
    }
}
