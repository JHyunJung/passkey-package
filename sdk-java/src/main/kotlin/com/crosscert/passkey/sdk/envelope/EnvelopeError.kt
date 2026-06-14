package com.crosscert.passkey.sdk.envelope

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class EnvelopeError(
    @get:JvmName("errorCode") @get:JsonProperty("errorCode") @param:JsonProperty("errorCode") val errorCode: String?,
    @get:JvmName("fieldErrors") @get:JsonProperty("fieldErrors") @param:JsonProperty("fieldErrors") val fieldErrors: List<EnvelopeFieldError>?,
)
