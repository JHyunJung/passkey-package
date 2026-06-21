package com.crosscert.passkey.admin.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * audit chain incident 엔드포인트의 요청/응답 DTO 묶음.
 *
 * <p>{@code AuditChainMonitorController} 는 raw POJO 를 반환한다(ApiResponse 엔벨로프 미사용).
 * {@link IncidentView} 는 그대로 직렬화돼 프론트로 나간다.
 */
public final class IncidentDto {
    private IncidentDto() {}

    // tamperedEntryId 는 요청에서 받지 않는다 — 서버가 위변조 재검증 결과에서 도출(증거 위조 방지).
    public record CreateRequest(@NotBlank String tenantId) {}

    public record ResolveRequest(@NotBlank @Size(max = 1024) String note) {}

    public record IncidentView(
            String id, String tenantId, String tenantName, String tamperedEntryId,
            String type, String severity, String status, String detail,
            String createdAt, String createdByEmail,
            String resolvedAt, String resolvedByEmail, String resolutionNote) {}
}
