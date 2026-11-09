package com.crosscert.passkey.admin.operator

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.entity.AdminUserTenant
import com.crosscert.passkey.core.repository.AdminUserRepository
import com.crosscert.passkey.core.repository.AdminUserTenantRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.util.Optional
import java.util.UUID

class AdminUserServiceTest {

    private val userRepo: AdminUserRepository = mock()
    private val mappingRepo: AdminUserTenantRepository = mock()
    private val audit: AuditLogService = mock()
    private val clock: Clock = Clock.systemUTC()

    private val service = AdminUserService(
        userRepo, mappingRepo, audit, clock,
    )

    @Test
    fun removeLastTenantOfRpAdminRejected() {
        val adminId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()

        val user = AdminUser.create()
        user.role = "RP_ADMIN"
        whenever(userRepo.findById(adminId)).thenReturn(Optional.of(user))
        whenever(mappingRepo.findByAdminUserIdForUpdate(adminId))
            .thenReturn(listOf(AdminUserTenant.of(adminId, tenantId, "alice")))

        assertThatThrownBy { service.removeTenant(adminId, tenantId, UUID.randomUUID(), "alice") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("cannot remove last tenant of RP_ADMIN")
    }

    @Test
    fun removeTenantLocksMappingRowsBeforeCounting() {
        // G08: count-then-delete must be serialized by a pessimistic lock on
        // the admin's mapping rows (findByAdminUserIdForUpdate), not the
        // lock-free countByAdminUserId — otherwise two concurrent removals
        // of distinct tenants both observe count=2 and both proceed
        // (write-skew leaving 0 tenants).
        val adminId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val otherTenantId = UUID.randomUUID()
        val user = AdminUser.create()
        user.role = "RP_ADMIN"
        whenever(userRepo.findById(adminId)).thenReturn(Optional.of(user))
        whenever(mappingRepo.findByAdminUserIdForUpdate(adminId)).thenReturn(
            listOf(
                AdminUserTenant.of(adminId, tenantId, "alice"),
                AdminUserTenant.of(adminId, otherTenantId, "alice"),
            ),
        )

        service.removeTenant(adminId, tenantId, UUID.randomUUID(), "alice")

        verify(mappingRepo, times(1)).findByAdminUserIdForUpdate(adminId)
        verify(mappingRepo, never()).countByAdminUserId(anyOrNull())
        verify(mappingRepo).deleteByAdminUserIdAndTenantId(adminId, tenantId)
    }

    @Test
    fun suspendDisablesLoginAndAppendsAuditEntry() {
        // G03 + G04: suspend must flip ENABLED=false (the actual login gate)
        // and append a tamper-evident audit row — not just flip STATUS.
        val actorId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val actor = AdminUser.create()
        actor.email = "alice@x.com"
        actor.role = "PLATFORM_OPERATOR"
        val target = AdminUser.create()
        target.email = "bob@x.com"
        target.role = "RP_ADMIN"
        whenever(userRepo.findById(targetId)).thenReturn(Optional.of(target))

        service.suspend(targetId, actorId, "alice@x.com")

        assertThat(target.status).isEqualTo("SUSPENDED")
        assertThat(target.isEnabled).isFalse()
        verify(audit).append(any<AuditAppendRequest>())
    }

    @Test
    fun activateReenablesLogin() {
        val targetId = UUID.randomUUID()
        val target = AdminUser.create()
        target.email = "bob@x.com"
        target.status = "SUSPENDED"
        target.isEnabled = false
        whenever(userRepo.findById(targetId)).thenReturn(Optional.of(target))

        service.activate(targetId, UUID.randomUUID(), "alice@x.com")

        assertThat(target.status).isEqualTo("ACTIVE")
        assertThat(target.isEnabled).isTrue()
        verify(audit).append(any<AuditAppendRequest>())
    }

    @Test
    fun suspendLastActivePlatformOperatorLocksBeforeCounting() {
        // G06: the last-PO lockout guard must count under a pessimistic lock
        // on the active-PO row set (findActivePlatformOperatorsForUpdate),
        // not a lock-free findAll() scan — otherwise two concurrent suspends
        // of distinct operators both observe count=2 and both proceed.
        val actorId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val actor = AdminUser.create()
        actor.email = "alice@x.com"
        actor.role = "PLATFORM_OPERATOR"
        val target = AdminUser.create()
        target.email = "bob@x.com"
        target.role = "PLATFORM_OPERATOR"
        whenever(userRepo.findById(targetId)).thenReturn(Optional.of(target))
        whenever(userRepo.findActivePlatformOperatorsForUpdate()).thenReturn(listOf(actor, target))

        service.suspend(targetId, actorId, "alice@x.com")

        verify(userRepo, times(1)).findActivePlatformOperatorsForUpdate()
        verify(userRepo, never()).findAll()
    }

    @Test
    fun suspendLastActivePlatformOperatorRejected() {
        val actorId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val target = AdminUser.create()
        target.email = "bob@x.com"
        target.role = "PLATFORM_OPERATOR"
        whenever(userRepo.findById(targetId)).thenReturn(Optional.of(target))
        whenever(userRepo.findActivePlatformOperatorsForUpdate()).thenReturn(listOf(target))

        assertThatThrownBy { service.suspend(targetId, actorId, "someoneelse@x.com") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("last active PLATFORM_OPERATOR")
    }

    @Test
    fun addTenantAppendsAuditEntry() {
        val adminId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val user = AdminUser.create()
        user.role = "RP_ADMIN"
        whenever(userRepo.findById(adminId)).thenReturn(Optional.of(user))
        whenever(mappingRepo.existsByAdminUserIdAndTenantId(adminId, tenantId)).thenReturn(false)

        service.addTenant(adminId, tenantId, UUID.randomUUID(), "alice@x.com")

        verify(audit).append(any<AuditAppendRequest>())
    }

    @Test
    fun removeTenantAppendsAuditEntry() {
        val adminId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val otherTenantId = UUID.randomUUID()
        val user = AdminUser.create()
        user.role = "RP_ADMIN"
        whenever(userRepo.findById(adminId)).thenReturn(Optional.of(user))
        whenever(mappingRepo.findByAdminUserIdForUpdate(adminId)).thenReturn(
            listOf(
                AdminUserTenant.of(adminId, tenantId, "alice"),
                AdminUserTenant.of(adminId, otherTenantId, "alice"),
            ),
        )

        service.removeTenant(adminId, tenantId, UUID.randomUUID(), "alice@x.com")

        verify(audit).append(any<AuditAppendRequest>())
    }

    @Test
    fun listBatchesTenantMappingsInsteadOfPerUserQueries() {
        // G17: list() must load mappings for all admin users via a single
        // IN-query (findByAdminUserIdIn), never via the per-user
        // findTenantIdsByAdminUserId (which would be N+1 for N admins).
        // AdminUser.getId() is populated by Hibernate's @UuidGenerator on
        // persist — mock the entity to give each user a distinct, stable id.
        val u1Id = UUID.randomUUID()
        val u2Id = UUID.randomUUID()
        val u1 = mock<AdminUser>()
        whenever(u1.id).thenReturn(u1Id)
        whenever(u1.email).thenReturn("a@x.com")
        whenever(u1.role).thenReturn("RP_ADMIN")
        whenever(u1.status).thenReturn("ACTIVE")
        val u2 = mock<AdminUser>()
        whenever(u2.id).thenReturn(u2Id)
        whenever(u2.email).thenReturn("b@x.com")
        whenever(u2.role).thenReturn("RP_ADMIN")
        whenever(u2.status).thenReturn("ACTIVE")
        whenever(userRepo.findAll()).thenReturn(listOf(u1, u2))

        val tenantA = UUID.randomUUID()
        val tenantB = UUID.randomUUID()
        whenever(mappingRepo.findByAdminUserIdIn(anyOrNull()))
            .thenReturn(
                listOf(
                    AdminUserTenant.of(u1Id, tenantA, "alice"),
                    AdminUserTenant.of(u2Id, tenantB, "alice"),
                ),
            )

        val views = service.list()

        assertThat(views).hasSize(2)
        assertThat(views[0].tenantIds).containsExactly(tenantA)
        assertThat(views[1].tenantIds).containsExactly(tenantB)
        verify(mappingRepo, times(1)).findByAdminUserIdIn(anyOrNull())
        verify(mappingRepo, never()).findTenantIdsByAdminUserId(anyOrNull())
    }
}
