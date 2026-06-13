package com.crosscert.passkey.app.fido2.credential;

import com.crosscert.passkey.app.api.v1.rp.dto.CredentialView;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.repository.CredentialRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** P0-4: end-user self-service credential 관리. tenant 격리는 VPD 가 담당. */
@Slf4j
@Service
public class CredentialSelfService {

    private final CredentialRepository creds;

    public CredentialSelfService(CredentialRepository creds) {
        this.creds = creds;
    }

    @Transactional(readOnly = true)
    public List<CredentialView> list(byte[] userHandle) {
        return creds.findByUserHandle(userHandle).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public void rename(byte[] userHandle, byte[] credentialId, String label) {
        Credential c = creds.findOwnedForUpdate(credentialId, userHandle)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENTITY_NOT_FOUND,
                        "credential not found or not owned"));
        c.setLabel(label);
    }

    @Transactional
    public void delete(byte[] userHandle, byte[] credentialId) {
        Credential c = creds.findOwnedForUpdate(credentialId, userHandle)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENTITY_NOT_FOUND,
                        "credential not found or not owned"));
        creds.delete(c);
        // Self-service delete is destructive. passkey-app has no audit-chain
        // infra (AuditLogService lives in admin-app — out of scope), so a
        // masked log line is the trace we keep. idTail mirrors
        // CredentialAdminService.revoke — never log the full credentialId.
        log.warn("self-service credential deleted: id={} reason=rp-self-service",
                idTail(Base64.getUrlEncoder().withoutPadding().encodeToString(credentialId)));
    }

    private CredentialView toView(Credential c) {
        return new CredentialView(
                Base64.getUrlEncoder().withoutPadding().encodeToString(c.getCredentialId()),
                c.getLabel(),
                c.getAaguid() == null ? null : HexFormat.of().formatHex(c.getAaguid()),
                c.getLastUsedAt());
    }

    /** Last 12 chars of a base64url id — never the full id. Short ids masked. */
    private static String idTail(String id) {
        if (id == null) return "null";
        if (id.length() <= 12) return "***";
        return "..." + id.substring(id.length() - 12);
    }
}
