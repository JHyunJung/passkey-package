package com.crosscert.passkey.admin.activity;

import com.crosscert.passkey.core.api.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/admin/api/activity")
public class ActivityController {

    private final ActivityService service;

    public ActivityController(ActivityService service) {
        this.service = service;
    }

    /**
     * PLATFORM_OPERATOR 전용. RP_ADMIN 은 TenantDetail 의 Activity 탭에서
     * /admin/api/audit?tenantId={self} 를 사용 — admin-role-separation 의
     * scope check 가 자기 tenant 강제.
     *
     * <p>Phase F5 — added optional {@code before} (backward pagination) and
     * {@code tenantId} (tenant-scoped feed) params. KPIs / top5 always stay on
     * the latest 24h global window; only {@code feed} responds to these
     * cursors. {@code sinceId} (forward polling) and {@code before} (backward
     * pagination) are mutually exclusive — when both are supplied,
     * {@code sinceId} wins (matches dashboard polling contract).
     */
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping
    public ApiResponse<ActivityView> activity(
            @RequestParam(required = false) UUID sinceId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(required = false) UUID tenantId) {
        return ApiResponse.ok(service.snapshot(sinceId, category, before, tenantId));
    }

    /** 행 클릭 시 단건 상세 — payload 포함. PLATFORM_OPERATOR 전용(피드와 동일 경계). */
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping("/{id}")
    public ApiResponse<ActivityDetailView> detail(@PathVariable UUID id) {
        return ApiResponse.ok(service.detail(id));
    }
}
