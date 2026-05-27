package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.ApiKey;
import com.crosscert.passkey.core.repository.ApiKeyRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ApiKeyAdminService {

    private static final String PREFIX_HEADER = "pk_";
    private static final int PREFIX_RANDOM_BYTES = 6;   // 6 bytes → 8 b64url chars → 11-char prefix
    private static final int SECRET_RANDOM_BYTES = 32;  // 32 bytes → 43 b64url chars

    private final ApiKeyRepository repo;
    private final AuditLogService audit;
    private final PasswordEncoder encoder;
    private final SecureRandom random;
    private final Clock clock;
    private final TenantBoundary tenantBoundary;

    public ApiKeyAdminService(ApiKeyRepository repo,
                              AuditLogService audit,
                              PasswordEncoder encoder,
                              SecureRandom random,
                              Clock clock,
                              TenantBoundary tenantBoundary) {
        this.repo = repo;
        this.audit = audit;
        this.encoder = encoder;
        this.random = random;
        this.clock = clock;
        this.tenantBoundary = tenantBoundary;
    }

    @Transactional(readOnly = true)
    public List<ApiKeyAdminDto.ApiKeyView> list(String tenantId) {
        UUID scopeTid = tenantBoundary.currentTenantScope().orElse(null);
        return repo.findAll().stream()
                .filter(k -> {
                    UUID kid = k.getTenantId();
                    if (scopeTid != null && !scopeTid.equals(kid)) return false;
                    if (tenantId != null && !tenantId.equals(kid == null ? null : kid.toString())) return false;
                    return true;
                })
                .map(ApiKeyAdminDto.ApiKeyView::from)
                .toList();
    }

    @Transactional
    public ApiKeyAdminDto.ApiKeyCreateResponse issue(ApiKeyAdminDto.ApiKeyCreateRequest req,
                                                     UUID actorId, String actorEmail) {
        tenantBoundary.assertCanAccessTenant(req.tenantId());
        String prefix = generateUniquePrefix();
        String secret = b64url(SECRET_RANDOM_BYTES);
        String hash = encoder.encode(secret);

        ApiKey key = new ApiKey(req.tenantId(), prefix, hash, req.name());
        for (String scope : req.scopes()) {
            key.addScope(scope);
        }
        ApiKey saved = repo.saveAndFlush(key);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", saved.getId().toString());
        payload.put("prefix", prefix);
        payload.put("tenantId", req.tenantId().toString());
        payload.put("name", req.name());
        payload.put("scopes", req.scopes());
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "API_KEY_ISSUE",
                "API_KEY", saved.getId().toString(), payload));

        return new ApiKeyAdminDto.ApiKeyCreateResponse(
                saved.getId(), prefix + secret, prefix, req.scopes());
    }

    @Transactional
    public void revoke(UUID id, UUID actorId, String actorEmail) {
        ApiKey k = repo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.API_KEY_NOT_FOUND));
        tenantBoundary.assertCanAccessTenant(k.getTenantId());
        k.revoke(clock.instant());
        repo.save(k);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prefix", k.getKeyPrefix());
        payload.put("tenantId", k.getTenantId().toString());
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "API_KEY_REVOKE",
                "API_KEY", id.toString(), payload));
    }

    private String generateUniquePrefix() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String prefix = PREFIX_HEADER + b64url(PREFIX_RANDOM_BYTES);
            if (repo.findByKeyPrefix(prefix).isEmpty()) return prefix;
        }
        throw new IllegalStateException("API key prefix exhausted — 10 collisions");
    }

    private String b64url(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
