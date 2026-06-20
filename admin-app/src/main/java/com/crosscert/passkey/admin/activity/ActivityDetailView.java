package com.crosscert.passkey.admin.activity;

import com.crosscert.passkey.core.entity.AuditLog;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 단건 Activity 상세 — payload 포함. 폴링 피드({@link ActivityView.Event})에는
 * CLOB payload 를 싣지 않고, 행 클릭 시 GET /admin/api/activity/{id} 로만 조회한다.
 *
 * <p>{@code tenantSlug} 와 {@code category} 는 엔티티에 없는 파생 값이라 서비스가
 * 주입한다. {@link #from} 는 엔티티 직역 필드만 채우고 그 둘은 인자로 받는다.
 */
public record ActivityDetailView(
        UUID id,
        String action,
        UUID actorId,
        String actorEmail,
        String targetType,
        String targetId,
        UUID tenantId,
        String tenantSlug,
        OffsetDateTime createdAt,
        String category,
        String payload
) {
    public static ActivityDetailView from(AuditLog a, String tenantSlug, String category) {
        return new ActivityDetailView(
                a.getId(),
                a.getAction(),
                a.getActorId(),
                a.getActorEmail(),
                a.getTargetType(),
                a.getTargetId(),
                a.getTenantId(),
                tenantSlug,
                a.getCreatedAt(),
                category,
                a.getPayload());
    }
}
