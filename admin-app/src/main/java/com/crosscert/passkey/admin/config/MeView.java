package com.crosscert.passkey.admin.config;

import java.util.UUID;

public record MeView(String email, String role, UUID tenantId,
                     boolean mfaEnabled, boolean mfaRequired) {}
