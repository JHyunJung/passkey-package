package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.Credential;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CredentialRepository extends JpaRepository<Credential, Long> {
}
