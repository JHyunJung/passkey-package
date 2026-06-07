package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record RegisterOptionsResp(JsonNode publicKeyCredentialCreationOptions) {}
