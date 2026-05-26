package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
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

    public ApiKeyAdminService(ApiKeyRepository repo,
                              AuditLogService audit,
                              PasswordEncoder encoder,
                              SecureRandom random,
                              Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.encoder = encoder;
        this.random = random;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ApiKeyAdminDto.ApiKeyView> list(String tenantId) {
        return repo.findAll().stream()
                .filter(k -> tenantId == null || tenantId.equals(k.getTenantId()))
                .map(ApiKeyAdminDto.ApiKeyView::from)
                .toList();
    }

    @Transactional
    public ApiKeyAdminDto.ApiKeyCreateResponse issue(ApiKeyAdminDto.ApiKeyCreateRequest req,
                                                     UUID actorId, String actorEmail) {
        String prefix = generateUniquePrefix();
        String secret = b64url(SECRET_RANDOM_BYTES);
        String hash = encoder.encode(secret);

        ApiKey key = new ApiKey(UUID.fromString(req.tenantId()), prefix, hash, req.name(), req.scopesJson());
        // expiresAt is not set by Phase 2 (entity has no setter). Phase 2
        // ignores ApiKeyCreateRequest.expiresAt. If a future task wants
        // expiry support, add a setter on ApiKey + wire here.
        ApiKey saved = repo.save(key);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", saved.getId().toString());
        payload.put("prefix", prefix);
        payload.put("tenantId", req.tenantId());
        payload.put("name", req.name());
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "API_KEY_ISSUE",
                "API_KEY", saved.getId().toString(), payload));

        return new ApiKeyAdminDto.ApiKeyCreateResponse(
                saved.getId(), prefix, prefix + secret, saved.getName(),
                saved.getTenantId().toString(), saved.getCreatedAt(), saved.getExpiresAt());
    }

    @Transactional
    public void revoke(UUID id, UUID actorId, String actorEmail) {
        ApiKey k = repo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.API_KEY_NOT_FOUND));
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
