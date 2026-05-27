package com.crosscert.passkey.samplerp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record LoginCompleteReq(@NotNull JsonNode publicKeyCredential) {}
