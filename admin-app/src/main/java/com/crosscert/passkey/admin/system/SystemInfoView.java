package com.crosscert.passkey.admin.system;

import java.util.List;

public record SystemInfoView(
        String serverVersion,
        String deployedAt,
        Integer apiP95Ms,
        Integer apiAvgMs,
        Integer apiP99Ms,
        Double uptimePercent,
        Long uptimeDays,
        Long uptimeIncidentMinutes,
        Host host,
        List<Component> components
) {
    public record Host(
            String apiHostname,
            String adminConsole,
            String region,
            String environment,
            String deployMethod
    ) {}

    public record Component(
            String name,
            String version,
            String status,
            int instances,
            String note
    ) {}
}
