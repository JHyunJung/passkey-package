package com.crosscert.passkey.admin.system;

import java.util.List;

public record SystemInfoView(
        String serverVersion,
        /** 빌드 시각(KST, OffsetDateTime.toString) — build-info 가 없으면 null 이고 UI 는 "—" 로 표시한다. */
        String deployedAt,
        Long uptimeDays,
        Host host,
        List<Component> components
) {
    public record Host(
            /** 활성 Spring 프로필을 쉼표로 이은 값 — 없으면 "default". */
            String environment
    ) {}

    public record Component(
            String name,
            String version,
            String status,
            String note
    ) {}
}
