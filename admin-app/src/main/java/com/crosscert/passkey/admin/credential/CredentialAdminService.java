package com.crosscert.passkey.admin.credential;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.admin.credential.CredentialAdminDto.CredentialView;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.api.PageView;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.mds.MdsAaguidCache;
import com.crosscert.passkey.core.repository.CredentialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class CredentialAdminService {

    private static final Logger log = LoggerFactory.getLogger(CredentialAdminService.class);

    private final CredentialRepository creds;
    private final MdsAaguidCache mds;
    private final AuditLogService audit;
    private final TenantBoundary tenantBoundary;

    public CredentialAdminService(CredentialRepository creds,
                                  MdsAaguidCache mds,
                                  AuditLogService audit,
                                  TenantBoundary tenantBoundary) {
        this.creds = creds;
        this.mds = mds;
        this.audit = audit;
        this.tenantBoundary = tenantBoundary;
    }

    @Transactional(readOnly = true)
    public PageView<CredentialView> list(UUID tenantId, int page, int size, String q) {
        tenantBoundary.assertCanAccessTenant(tenantId);
        int cappedSize = Math.min(Math.max(size, 1), 200);
        // Sort 는 findAllByTenantId 의 @Query ORDER BY 절에서 처리 (NULLS LAST 포함).
        // searchByTenantId native query 는 Pageable sort 미적용 — 필요 시 쿼리 내 ORDER BY 추가.
        Pageable pageReq = PageRequest.of(Math.max(page, 0), cappedSize,
                Sort.by(Sort.Order.desc("lastUsedAt"))
                    .and(Sort.by(Sort.Order.desc("id"))));

        Page<Credential> rows = (q == null || q.isBlank())
                ? creds.findAllByTenantId(tenantId, pageReq)
                : creds.searchByTenantId(tenantId, normalizeQ(q), pageReq);

        log.debug("list credentials tenant={} page={} size={} q={} totalElements={}",
                tenantId, page, cappedSize, q, rows.getTotalElements());
        return PageView.from(rows.map(this::toView));
    }

    @Transactional
    public void revoke(UUID tenantId, String credentialIdB64,
                       UUID actorId, String actorEmail) {
        tenantBoundary.assertCanAccessTenant(tenantId);
        byte[] credId;
        try {
            credId = Base64.getUrlDecoder().decode(credentialIdB64);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "credentialId 가 base64url 형식이 아님");
        }

        Credential c = creds.findByCredentialIdForUpdate(credId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENTITY_NOT_FOUND,
                        "credential 없음"));

        // VPD 가 admin-app 에서 비활성 — tenantId 일치 검사가 cross-tenant 누출 방어의 단일 layer
        if (!c.getTenantId().equals(tenantId)) {
            log.warn("cross-tenant revoke attempt: pathTenant={} actualTenant={} credentialId={}",
                    tenantId, c.getTenantId(), idTail(credentialIdB64));
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "tenant boundary 위반");
        }

        byte[] aaguid = c.getAaguid();
        byte[] userHandle = c.getUserHandle();
        creds.delete(c);

        Map<String, Object> payload = new HashMap<>();
        payload.put("tenantId", tenantId.toString());
        payload.put("aaguidHex", aaguid == null ? null : HexFormat.of().formatHex(aaguid));
        payload.put("userHandleB64url",
                Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle));

        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "CREDENTIAL_REVOKE",
                "CREDENTIAL", credentialIdB64,
                tenantId,
                payload));

        log.warn("credential revoked: id={} reason=admin tenantId={}",
                idTail(credentialIdB64), tenantId);
    }

    /** Last 12 chars of a base64url id — never the full id. Short ids masked. */
    private static String idTail(String id) {
        if (id == null) return "null";
        if (id.length() <= 12) return "***";
        return "..." + id.substring(id.length() - 12);
    }

    private CredentialView toView(Credential c) {
        byte[] aaguid = c.getAaguid();
        String aaguidHex = aaguid == null ? null : HexFormat.of().formatHex(aaguid);
        String authName = aaguid == null
                ? null
                : mds.lookup(aaguid)
                     .map(entry -> entry.statuses().isEmpty() ? null : String.join(",", entry.statuses()))
                     .orElse(null);
        return new CredentialView(
                Base64.getUrlEncoder().withoutPadding().encodeToString(c.getCredentialId()),
                Base64.getUrlEncoder().withoutPadding().encodeToString(c.getUserHandle()),
                aaguidHex, authName,
                c.getAttestationFmt(), c.getTransports(),
                c.getSignCount(), c.getLastUsedAt(), c.getCreatedAt());
    }

    /**
     * base64url 시도 → 성공 시 hex 변환. 실패 시 q 그대로 (운영자가 hex 일부 직접 입력 가능).
     * LIKE wildcard 는 \ % _ → \\ \% \_ 로 escape (CredentialRepository 의 ESCAPE '\\' 절과 짝).
     */
    private String normalizeQ(String q) {
        String hexOrRaw;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(q);
            hexOrRaw = HexFormat.of().formatHex(bytes).toLowerCase();
        } catch (IllegalArgumentException e) {
            hexOrRaw = q.toLowerCase();
        }
        return hexOrRaw.replace("\\", "\\\\")
                       .replace("%",  "\\%")
                       .replace("_",  "\\_");
    }
}
