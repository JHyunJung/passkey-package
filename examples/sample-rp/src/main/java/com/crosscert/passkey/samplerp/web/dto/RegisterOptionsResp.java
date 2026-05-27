package com.crosscert.passkey.samplerp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record RegisterOptionsResp(JsonNode publicKeyCredentialCreationOptions) {}
