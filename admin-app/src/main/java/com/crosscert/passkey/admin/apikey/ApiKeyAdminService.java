package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.ApiKey;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.ApiKeyRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class ApiKeyAdminService {

    private static final String PREFIX_HEADER = "pk_";
    private static final int PREFIX_RANDOM_BYTES = 6;   // 6 bytes → 8 b64url chars → 11-char prefix
    private static final int SECRET_RANDOM_BYTES = 32;  // 32 bytes → 43 b64url chars

    /** api_key_scope V21 CHECK 제약과 동일한 허용 scope 집합. */
    private static final Set<String> ALLOWED_SCOPES = Set.of("registration", "authentication", "admin");

    private final ApiKeyRepository repo;
    private final AuditLogService audit;
    private final PasswordEncoder encoder;
    private final SecureRandom random;
    private final Clock clock;
    private final TenantBoundary tenantBoundary;
    private final TenantRepository tenants;

    /** P1-5: 구 키가 살아있는 grace 창. 기본 24h. */
    @Value("${passkey.api-key.rotation.grace:PT24H}")
    private Duration rotationGrace = Duration.ofHours(24);

    public ApiKeyAdminService(ApiKeyRepository repo,
                              AuditLogService audit,
                              PasswordEncoder encoder,
                              SecureRandom random,
                              Clock clock,
                              TenantBoundary tenantBoundary,
                              TenantRepository tenants) {
        this.repo = repo;
        this.audit = audit;
        this.encoder = encoder;
        this.random = random;
        this.clock = clock;
        this.tenantBoundary = tenantBoundary;
        this.tenants = tenants;
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

        // P0-2 seam: suspended 테넌트엔 어떤 경로로도 새 키가 살아남으면 안 됨.
        // suspend() 가 기존 키를 일괄 revoke 하고 ceremony start 가 거부하더라도,
        // 운영자가 정지 상태에서 새 키를 발급하면 그 키로 finish/self-service 가 동작한다.
        Tenant tenant = tenants.findById(req.tenantId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));
        if (tenant.isSuspended()) {
            throw new BusinessException(ErrorCode.TENANT_SUSPENDED,
                    "cannot issue api key for suspended tenant: " + req.tenantId());
        }

        String prefix = generateUniquePrefix();
        String secret = b64url(SECRET_RANDOM_BYTES);
        String hash = encoder.encode(secret);

        // P1-5 enforcement seam: scope 를 whitelist 검증 + 소문자 정규화한다.
        // 필터가 exact match("registration") 로 enforce 하므로 "Registration"·오타가
        // verbatim 저장되면 200 받지만 모든 RP 호출이 403 인 silent dead key 가 된다.
        Set<String> normalized = new LinkedHashSet<>();
        for (String scope : req.scopes()) {
            normalized.add(normalizeScope(scope));
        }

        ApiKey key = new ApiKey(req.tenantId(), prefix, hash, req.name());
        for (String scope : normalized) {
            key.addScope(scope);
        }
        // 월 단위 만료: now + N개월 (KST). null 이면 무기한(expires_at=NULL, 기존 키와 동일).
        OffsetDateTime expiresAt = null;
        if (req.expiresInMonths() != null) {
            expiresAt = OffsetDateTime.now(clock).plusMonths(req.expiresInMonths());
            key.expireAt(expiresAt);
        }
        ApiKey saved = repo.saveAndFlush(key);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", saved.getId().toString());
        payload.put("prefix", prefix);
        payload.put("tenantId", req.tenantId().toString());
        payload.put("name", req.name());
        payload.put("scopes", normalized);
        payload.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "API_KEY_ISSUE",
                "API_KEY", saved.getId().toString(),
                req.tenantId(),
                payload));

        log.info("api-key issued: prefix={} name={} tenantId={} scopes={}",
                prefix, req.name(), req.tenantId(), normalized);

        return new ApiKeyAdminDto.ApiKeyCreateResponse(
                saved.getId(), prefix + secret, prefix, normalized, expiresAt);
    }

    @Transactional
    public void revoke(UUID id, UUID actorId, String actorEmail) {
        ApiKey k = repo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.API_KEY_NOT_FOUND));
        tenantBoundary.assertCanAccessTenant(k.getTenantId());
        k.revoke(OffsetDateTime.now(clock));
        repo.save(k);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prefix", k.getKeyPrefix());
        payload.put("tenantId", k.getTenantId().toString());
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "API_KEY_REVOKE",
                "API_KEY", id.toString(),
                k.getTenantId(),
                payload));

        log.warn("api-key revoked: prefix={} reason=admin tenantId={}",
                k.getKeyPrefix(), k.getTenantId());
    }

    /**
     * P1-5 무중단 로테이션: 구 키의 tenant/name/scopes 를 복사한 새 키를 발급하고,
     * 구 키의 expiresAt 을 now+grace 로 설정한다. grace 창 동안 두 키 모두 동작하다가
     * 구 키가 자동 만료된다. 신규 키 plaintext 는 이 응답에서 단 한 번만 노출된다.
     */
    @Transactional
    public ApiKeyAdminDto.ApiKeyRotateResponse rotate(UUID oldKeyId, UUID actorId, String actorEmail) {
        ApiKey old = repo.findById(oldKeyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.API_KEY_NOT_FOUND));
        tenantBoundary.assertCanAccessTenant(old.getTenantId());

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!old.isActive(now)) {
            throw new BusinessException(ErrorCode.API_KEY_NOT_FOUND,
                    "cannot rotate inactive (revoked/expired) key: " + oldKeyId);
        }

        Tenant tenant = tenants.findById(old.getTenantId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));
        if (tenant.isSuspended()) {
            throw new BusinessException(ErrorCode.TENANT_SUSPENDED,
                    "cannot rotate api key for suspended tenant: " + old.getTenantId());
        }

        String prefix = generateUniquePrefix();
        String secret = b64url(SECRET_RANDOM_BYTES);
        String hash = encoder.encode(secret);

        ApiKey fresh = new ApiKey(old.getTenantId(), prefix, hash, old.getName());
        for (String scope : old.getScopeValues()) {
            fresh.addScope(scope);
        }
        ApiKey savedNew = repo.saveAndFlush(fresh);

        OffsetDateTime oldExpiry = now.plus(rotationGrace);
        old.expireAt(oldExpiry);
        repo.save(old);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("oldPrefix", old.getKeyPrefix());
        payload.put("newPrefix", prefix);
        payload.put("tenantId", old.getTenantId().toString());
        payload.put("oldKeyExpiresAt", oldExpiry.toString());
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "API_KEY_ROTATED",
                "API_KEY", savedNew.getId().toString(), old.getTenantId(), payload));

        log.warn("api-key rotated: oldPrefix={} newPrefix={} tenantId={} oldExpiresAt={}",
                old.getKeyPrefix(), prefix, old.getTenantId(), oldExpiry);

        return new ApiKeyAdminDto.ApiKeyRotateResponse(
                savedNew.getId(), prefix + secret, prefix, fresh.getScopeValues(), oldExpiry);
    }

    /** scope 를 소문자 정규화 + whitelist 검증. 알 수 없는 값이면 BusinessException(400). */
    private static String normalizeScope(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCOPES.contains(s)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "unknown api key scope: " + raw);
        }
        return s;
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
