package com.crosscert.passkey.admin.operator

import com.crosscert.passkey.admin.auth.AdminUserDetails
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * actorId(UUID) — G04 audit trail anchor; actorEmail changes, actorId doesn't.
 * 원 Java 의 (AdminUserDetails) 캐스트 — 다른 principal 은 ClassCastException, null 은 NPE.
 */
private fun actorId(auth: Authentication): UUID = (auth.principal as AdminUserDetails).id

@RestController
@RequestMapping("/admin/api/admin-users")
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
class AdminUserController(
    private val service: AdminUserService,
) {

    data class TenantRef(@field:NotNull val tenantId: UUID?)

    @GetMapping
    fun list(): List<AdminUserDto.View> = service.list()

    @PostMapping("/{id}/suspend")
    fun suspend(@PathVariable id: UUID, auth: Authentication) {
        service.suspend(id, actorId(auth), auth.name)
    }

    @PostMapping("/{id}/activate")
    fun activate(@PathVariable id: UUID, auth: Authentication) {
        service.activate(id, actorId(auth), auth.name)
    }

    @PostMapping("/{id}/tenants")
    fun addTenant(
        @PathVariable id: UUID,
        @Valid @RequestBody body: TenantRef,
        auth: Authentication,
    ) {
        service.addTenant(id, body.tenantId, actorId(auth), auth.name)
    }

    @DeleteMapping("/{id}/tenants/{tenantId}")
    fun removeTenant(
        @PathVariable id: UUID,
        @PathVariable tenantId: UUID,
        auth: Authentication,
    ) {
        service.removeTenant(id, tenantId, actorId(auth), auth.name)
    }
}
