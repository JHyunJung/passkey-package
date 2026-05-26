package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.MdsBlobCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MdsBlobCacheRepository extends JpaRepository<MdsBlobCache, UUID> {
}
