package com.crosscert.passkey.admin.system

import com.crosscert.passkey.core.api.ApiResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/api/system/info")
class SystemInfoController(private val service: SystemInfoService) {

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping
    fun get(): ApiResponse<SystemInfoView> = ApiResponse.ok(service.get())
}
