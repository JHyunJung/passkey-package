package com.crosscert.passkey.sdk.envelope

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponseEnvelope<T>(
    @get:JvmName("success") @get:JsonProperty("success") @param:JsonProperty("success") val success: Boolean,
    @get:JvmName("code") @get:JsonProperty("code") @param:JsonProperty("code") val code: String?,
    @get:JvmName("message") @get:JsonProperty("message") @param:JsonProperty("message") val message: String?,
    @get:JvmName("data") @get:JsonProperty("data") @param:JsonProperty("data") val data: T?,
    @get:JvmName("error") @get:JsonProperty("error") @param:JsonProperty("error") val error: EnvelopeError?,
    @get:JvmName("traceId") @get:JsonProperty("traceId") @param:JsonProperty("traceId") val traceId: String?,
    @get:JvmName("timestamp") @get:JsonProperty("timestamp") @param:JsonProperty("timestamp") val timestamp: String?,
)
