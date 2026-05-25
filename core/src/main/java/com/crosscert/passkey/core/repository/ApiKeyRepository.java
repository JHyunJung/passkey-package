package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /**
     * Look up an API key by its prefix. VPD filters by tenant_id =
     * SYS_CONTEXT, so this call only returns a row when the calling
     * session has already set TenantContextHolder to the same tenant
     * that owns the key. Useful from admin endpoints (Phase 2) and
     * test fixtures (where APP_ADMIN bypasses VPD).
     *
     * <p>Phase 1 auth filter does NOT use this — auth runs before
     * tenant context exists. See {@code ApiKeyLookupService} (T16)
     * which calls a definer-rights PL/SQL package
     * ({@code APP_OWNER.api_key_lookup_pkg.find_by_prefix}) that
     * bypasses VPD safely.
     */
    Optional<ApiKey> findByKeyPrefix(String keyPrefix);
}
