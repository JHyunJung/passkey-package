package com.crosscert.passkey.admin.config;

import java.util.List;
import java.util.UUID;

public record MeView(String email, String role, List<UUID> tenantIds,
                     boolean mfaEnabled, boolean mfaRequired,
                     int sessionIdleTimeoutMinutes) {}
