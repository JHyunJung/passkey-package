package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record LoginOptionsResp(JsonNode publicKeyCredentialRequestOptions) {}
