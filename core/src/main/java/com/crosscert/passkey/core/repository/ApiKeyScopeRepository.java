package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.ApiKeyScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;
import java.util.UUID;

public interface ApiKeyScopeRepository extends JpaRepository<ApiKeyScope, UUID> {

    /**
     * 인증된 API key 의 scope 문자열 집합. 인증 성공 후 호출되므로
     * TenantContextHolder 가 설정돼 있어 앱 레벨 @Filter 가 정상 작동(키는 그 테넌트 소유).
     */
    @Query("select s.scope from ApiKeyScope s where s.apiKey.id = :apiKeyId")
    Set<String> findScopeValuesByApiKeyId(@Param("apiKeyId") UUID apiKeyId);
}
