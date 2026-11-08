package com.crosscert.passkey.admin.keymgmt

import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.repository.AdminUserRepository
import com.crosscert.passkey.core.repository.SigningKeyRepository
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/api/keys")
class KeyMgmtController(
    private val repo: SigningKeyRepository,
    private val rotation: KeyRotationService,
    private val admins: AdminUserRepository,
) {

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping
    fun list(): ApiResponse<KeyMgmtDto.KeyList> {
        val keys = repo.findAll().map { KeyMgmtDto.SigningKeyView.from(it) }
        return ApiResponse.ok(KeyMgmtDto.KeyList(keys))
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @PostMapping("/rotate")
    fun rotate(auth: Authentication): ApiResponse<KeyMgmtDto.RotateResponse> {
        val actorId = admins.findByEmail(auth.name).orElseThrow().id
        val result = rotation.rotate(actorId, auth.name)
        return ApiResponse.ok(KeyMgmtDto.RotateResponse(result.oldKid, result.newKid))
    }
}
