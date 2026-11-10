package com.crosscert.passkey.admin.audit

import com.crosscert.passkey.core.entity.SecurityIncident
import com.crosscert.passkey.core.entity.Tenant
import com.crosscert.passkey.core.repository.AdminUserRepository
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.core.repository.TenantRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.stream.Collectors

@RestController
@RequestMapping("/admin/api/audit/chain")
class AuditChainMonitorController(
    private val verifier: AuditChainVerifier,
    private val backfillService: AuditChainBackfillService,
    private val auditRepo: AuditLogRepository,
    private val tenantRepo: TenantRepository,
    private val clock: Clock,
    private val incidents: SecurityIncidentService,
    private val admins: AdminUserRepository,
) {

    @GetMapping("/overview")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun overview(@RequestParam(defaultValue = "24") windowHours: Int): AuditChainOverview {
        if (windowHours < 1 || windowHours > 168) {
            throw IllegalArgumentException("windowHours must be between 1 and 168 (1 week)")
        }
        val startMs = clock.millis()
        val now = clock.instant()
        val since = now.minus(windowHours.toLong(), ChronoUnit.HOURS)
        val bucketSizeMinutes = 60

        val tenantIds = auditRepo.findDistinctTenantIds()
        // Collectors.toMap: 원 Java 와 같이 중복 id 면 IllegalStateException.
        val tenantsById: Map<UUID, Tenant> = tenantRepo.findAll().stream()
            .collect(Collectors.toMap({ it.id }, { it }))

        val tenants = ArrayList<AuditChainTenantOverview>(tenantIds.size)
        var verifiedRowsTotal = 0L
        var tampered = 0
        for (tenantId in tenantIds) {
            // tenant 당 audit_log 를 한 번만 읽어 체인 검증과 버킷 집계에 같은 행을 쓴다(P3).
            // 검증은 첫 break 에서 멈추지만 집계는 break 이후 행까지 전부 센다(기존과 동일).
            val rows = auditRepo.findAllByTenantOrdered(tenantId)
            val r = verifier.verifyTenantRows(tenantId, rows)
            verifiedRowsTotal += rows.size
            if (!r.ok) tampered++

            val buckets = LongArray(windowHours)
            for (row in rows) {
                // Window math is purely relative (minutes-between); compare on the
                // absolute instant so the KST offset is irrelevant to bucketing.
                val t = row.createdAt.toInstant()
                if (t.isBefore(since)) continue
                val minutesSince = ChronoUnit.MINUTES.between(t, now)
                val idx = (minutesSince / bucketSizeMinutes).toInt()
                if (idx >= 0 && idx < windowHours) {
                    buckets[windowHours - 1 - idx]++
                }
            }
            val bucketList = ArrayList<Long>(windowHours)
            for (b in buckets) bucketList.add(b)

            val name: String? = if (r.tenantId == null) {
                "[platform]"
            } else if (tenantsById.containsKey(r.tenantId)) {
                tenantsById.getValue(r.tenantId).displayName
            } else {
                r.tenantId.toString()
            }
            tenants.add(
                AuditChainTenantOverview(
                    r.tenantId, name, r.ok, rows.size.toLong(), bucketList, r.brokenAt,
                ),
            )
        }

        val verifyMs = clock.millis() - startMs
        val totals = AuditChainOverview.Totals(
            tenantIds.size - tampered, tenantIds.size, tampered, verifiedRowsTotal, verifyMs,
        )
        return AuditChainOverview(now, windowHours, bucketSizeMinutes, totals, tenants)
    }

    data class TenantVerifyResponse(
        val tenantId: UUID?,
        val intact: Boolean,
        val tamperedEntryId: UUID?,
        val verifiedAt: Instant?,
    )

    @GetMapping("/verify")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun verifyTenant(@RequestParam(required = false) tenantId: String?): TenantVerifyResponse {
        // Java String.isBlank 와 같은 판정(Character.isWhitespace) — Kotlin isBlank 는 NBSP 등도 공백으로 본다.
        val resolved: UUID? =
            if (tenantId == null || tenantId.all(Character::isWhitespace) || "null".equals(tenantId, ignoreCase = true)) {
                null // platform chain
            } else {
                UUID.fromString(tenantId)
            }
        val r = verifier.verifyTenant(resolved)
        return TenantVerifyResponse(r.tenantId, r.ok, r.brokenAt, clock.instant())
    }

    data class BackfillResponse(val tenantsProcessed: Int, val rowsUpdated: Int, val rowsSkipped: Int)

    @PostMapping("/backfill")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun backfill(): BackfillResponse {
        val s = backfillService.backfill()
        return BackfillResponse(s.tenantsProcessed, s.rowsUpdated, s.rowsSkipped)
    }

    // ---- Incident 관리 (OPEN → RESOLVED) ----------------------------------

    @GetMapping("/incidents")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun listIncidents(): List<IncidentDto.IncidentView> {
        val all = incidents.list()
        // N+1 회피 — 페이지의 모든 tenant/admin id 를 모아 한 번에 배치 로드(overview/ActivityService 패턴).
        val lookups = buildLookups(all)
        return all.map { toView(it, lookups) }
    }

    @PostMapping("/incidents")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    fun createIncident(
        @Valid @RequestBody req: IncidentDto.CreateRequest,
        auth: Authentication,
    ): IncidentDto.IncidentView {
        val actorId = admins.findByEmail(auth.name).orElseThrow().id
        val tenantId = UUID.fromString(req.tenantId)
        // tamperedEntryId 는 caller 가 보내지 않는다 — 서비스가 위변조 재검증 결과에서 도출한다.
        val created = incidents.create(tenantId, actorId, auth.name)
        return toView(created, buildLookups(java.util.List.of(created)))
    }

    @PostMapping("/incidents/{id}/resolve")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun resolveIncident(
        @PathVariable id: String,
        @Valid @RequestBody req: IncidentDto.ResolveRequest,
        auth: Authentication,
    ): IncidentDto.IncidentView {
        val actorId = admins.findByEmail(auth.name).orElseThrow().id
        val resolved = incidents.resolve(UUID.fromString(id), req.note, actorId, auth.name)
        return toView(resolved, buildLookups(java.util.List.of(resolved)))
    }

    @ExceptionHandler(IncidentConflictException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConflict(e: IncidentConflictException): Map<String, String?> =
        java.util.Map.of("error", "conflict", "message", e.message)

    @ExceptionHandler(IncidentNotTamperedException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onNotTampered(e: IncidentNotTamperedException): Map<String, String?> =
        java.util.Map.of("error", "not_tampered", "message", e.message)

    @ExceptionHandler(IncidentNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onNotFound(e: IncidentNotFoundException): Map<String, String?> =
        java.util.Map.of("error", "not_found", "message", e.message)

    /** toView 가 N+1 없이 조회하도록 미리 배치 로드한 tenant displayName / admin email 맵. */
    private data class ViewLookups(val tenantNames: Map<UUID, String?>, val emails: Map<UUID, String?>)

    private fun buildLookups(list: List<SecurityIncident>): ViewLookups {
        val tenantIds: Set<UUID> = list.stream()
            .map { it.tenantId }
            .filter { it != null }
            .collect(Collectors.toSet())
        val adminIds = HashSet<UUID>()
        for (i in list) {
            if (i.createdBy != null) adminIds.add(i.createdBy)
            if (i.resolvedBy != null) adminIds.add(i.resolvedBy)
        }
        val tenantNames = HashMap<UUID, String?>()
        tenantRepo.findAllById(tenantIds).forEach { t -> tenantNames[t.id] = t.displayName }
        val emails = HashMap<UUID, String?>()
        admins.findAllById(adminIds).forEach { a -> emails[a.id] = a.email }
        return ViewLookups(tenantNames, emails)
    }

    private fun toView(i: SecurityIncident, lookups: ViewLookups): IncidentDto.IncidentView {
        val tenantName = lookups.tenantNames.getOrDefault(i.tenantId, i.tenantId.toString())
        val createdByEmail = lookups.emails.getOrDefault(i.createdBy, "—")
        val resolvedByEmail = if (i.resolvedBy == null) null else lookups.emails.getOrDefault(i.resolvedBy, "—")
        return IncidentDto.IncidentView(
            i.id.toString(), i.tenantId.toString(), tenantName,
            if (i.tamperedEntryId == null) null else i.tamperedEntryId.toString(),
            i.type, i.severity, i.status, i.detail,
            i.createdAt.toString(), createdByEmail,
            if (i.resolvedAt == null) null else i.resolvedAt.toString(),
            resolvedByEmail, i.resolutionNote,
        )
    }
}
