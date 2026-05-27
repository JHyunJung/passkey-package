package com.crosscert.passkey.samplerp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record LoginOptionsResp(JsonNode publicKeyCredentialRequestOptions) {}
