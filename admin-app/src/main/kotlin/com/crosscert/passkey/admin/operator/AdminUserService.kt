package com.crosscert.passkey.admin.operator

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.entity.AdminUserTenant
import com.crosscert.passkey.core.repository.AdminUserRepository
import com.crosscert.passkey.core.repository.AdminUserTenantRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

private val log = LoggerFactory.getLogger(AdminUserService::class.java)

/** Masks an email to "a***@example.com" — never the full local part. */
private fun mask(email: String?): String {
    if (email == null) return "null"
    val at = email.indexOf('@')
    if (at <= 1) return "***"
    return email[0] + "***" + email.substring(at)
}

@Service
class AdminUserService(
    private val userRepo: AdminUserRepository,
    private val mappingRepo: AdminUserTenantRepository,
    private val audit: AuditLogService,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(): List<AdminUserDto.View> {
        val users = userRepo.findAll()
        // G17: single IN-query for all mappings instead of one query per user
        // (F09 activity-feed findAllById batch pattern).
        val userIds = users.map { it.id }
        val tenantIdsByUser = HashMap<UUID, MutableList<UUID>>()
        for (m in mappingRepo.findByAdminUserIdIn(userIds)) {
            tenantIdsByUser.computeIfAbsent(m.adminUserId) { ArrayList() }
                .add(m.tenantId)
        }
        return users.map { u -> toView(u, tenantIdsByUser.getOrDefault(u.id, java.util.List.of())) }
    }

    /**
     * tenantId nullable: 컨트롤러가 TenantRef.tenantId(@NotNull 검증)를 그대로 넘긴다. null 이면 원 Java 처럼
     * 감사 payload 의 tenantId.toString() 지점에서 NullPointerException.
     */
    @Transactional
    fun addTenant(adminUserId: UUID, tenantId: UUID?, actorId: UUID?, actor: String?) {
        val u = userRepo.findById(adminUserId)
            .orElseThrow { IllegalStateException("admin not found: $adminUserId") }
        if ("RP_ADMIN" != u.role) {
            throw IllegalArgumentException("only RP_ADMIN can be mapped to a tenant")
        }
        if (mappingRepo.existsByAdminUserIdAndTenantId(adminUserId, tenantId)) {
            return // 멱등
        }
        mappingRepo.save(AdminUserTenant.of(adminUserId, tenantId, actor))
        audit.append(
            AuditAppendRequest(
                actorId, actor, "ADMIN_USER_TENANT_ADD",
                "ADMIN_USER", adminUserId.toString(), tenantId,
                // 원 Java 의 tenantId.toString() 과 같은 지점의 NPE(@NotNull 검증을 거친 컨트롤러 경로에선 불가).
                java.util.Map.of<String, Any?>("tenantId", tenantId!!.toString()),
            ),
        )
        log.info("admin tenant added: adminId={} tenantId={} by={}", adminUserId, tenantId, mask(actor))
    }

    @Transactional
    fun removeTenant(adminUserId: UUID, tenantId: UUID, actorId: UUID?, actor: String?) {
        val u = userRepo.findById(adminUserId)
            .orElseThrow { IllegalStateException("admin not found: $adminUserId") }
        // G08: lock every mapping row for this admin BEFORE counting so a
        // concurrent removeTenant on a different tenant of the same admin
        // serializes against this one (write-skew fix — see repository
        // Javadoc for the full concurrency argument).
        val mappings = mappingRepo.findByAdminUserIdForUpdate(adminUserId)
        if ("RP_ADMIN" == u.role && mappings.size <= 1) {
            throw IllegalStateException("cannot remove last tenant of RP_ADMIN")
        }
        mappingRepo.deleteByAdminUserIdAndTenantId(adminUserId, tenantId)
        audit.append(
            AuditAppendRequest(
                actorId, actor, "ADMIN_USER_TENANT_REMOVE",
                "ADMIN_USER", adminUserId.toString(), tenantId,
                java.util.Map.of<String, Any?>("tenantId", tenantId.toString()),
            ),
        )
        log.info("admin tenant removed: adminId={} tenantId={} by={}", adminUserId, tenantId, mask(actor))
    }

    @Transactional
    fun suspend(userId: UUID, actorId: UUID?, byUser: String?) {
        val user = userRepo.findById(userId).orElseThrow()
        assertNotLockingOut(user, byUser, "suspend")
        user.status = "SUSPENDED"
        user.suspendedAt = OffsetDateTime.now(clock)
        user.suspendedBy = byUser
        // G03: STATUS alone is not read by the login gate — DaoAuthenticationProvider's
        // DefaultPreAuthenticationChecks only consults isEnabled()/isAccountNonLocked().
        // Flip ENABLED so a suspended operator is actually rejected at /admin/login,
        // not just cosmetically flagged.
        user.isEnabled = false
        audit.append(
            AuditAppendRequest(
                actorId, byUser, "ADMIN_USER_SUSPEND",
                "ADMIN_USER", userId.toString(), null, java.util.Map.of<String, Any?>(),
            ),
        )
        log.info("admin suspended: emailMasked={}", mask(user.email))
    }

    @Transactional
    fun activate(userId: UUID, actorId: UUID?, byUser: String?) {
        val user = userRepo.findById(userId).orElseThrow()
        user.status = "ACTIVE"
        user.suspendedAt = null
        user.suspendedBy = null
        user.isEnabled = true
        audit.append(
            AuditAppendRequest(
                actorId, byUser, "ADMIN_USER_ACTIVATE",
                "ADMIN_USER", userId.toString(), null, java.util.Map.of<String, Any?>(),
            ),
        )
        log.info("admin activated: emailMasked={}", mask(user.email))
    }

    private fun assertNotLockingOut(user: AdminUser, byUser: String?, action: String) {
        // 원 Java 의 user.getEmail().equals(byUser) — email 이 null 이면 NPE(== 는 null-safe 라 쓰지 않는다).
        if (user.email.equals(byUser)) {
            throw IllegalStateException("Cannot $action yourself")
        }
        if ("PLATFORM_OPERATOR" == user.role &&
            "ACTIVE" == (if (user.status != null) user.status else "ACTIVE")
        ) {
            // G06: lock every active-PLATFORM_OPERATOR row BEFORE counting so a
            // concurrent suspend of a different operator serializes against
            // this one instead of both observing a stale pre-suspend count
            // (write-skew fix — see repository Javadoc for the full argument).
            val activePoCount = userRepo.findActivePlatformOperatorsForUpdate().size.toLong()
            if (activePoCount <= 1) {
                throw IllegalStateException("Cannot $action the last active PLATFORM_OPERATOR")
            }
        }
    }

    /** 원 Java 는 package-private(호출자 없음) — internal 로 둔다. */
    internal fun toView(u: AdminUser): AdminUserDto.View {
        val tids = mappingRepo.findTenantIdsByAdminUserId(u.id)
        return toView(u, tids)
    }

    private fun toView(u: AdminUser, tids: List<UUID>): AdminUserDto.View =
        AdminUserDto.View(
            u.id, u.email, u.role,
            if (u.status != null) u.status else "ACTIVE",
            tids,
            u.createdAt, u.lastLoginAt,
            u.suspendedAt, u.createdBy, u.isMfaEnabled,
        )
}
