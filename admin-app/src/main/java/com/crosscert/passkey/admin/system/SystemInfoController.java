package com.crosscert.passkey.admin.system;

import com.crosscert.passkey.core.api.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/api/system/info")
public class SystemInfoController {

    private final SystemInfoService service;

    public SystemInfoController(SystemInfoService service) {
        this.service = service;
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping
    public ApiResponse<SystemInfoView> get() {
        return ApiResponse.ok(service.get());
    }
}
