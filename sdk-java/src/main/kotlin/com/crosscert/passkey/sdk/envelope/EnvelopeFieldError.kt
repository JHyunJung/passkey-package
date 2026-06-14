package com.crosscert.passkey.sdk.envelope

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class EnvelopeFieldError(
    @get:JvmName("field") @get:JsonProperty("field") @param:JsonProperty("field") val field: String?,
    @get:JvmName("rejectedValue") @get:JsonProperty("rejectedValue") @param:JsonProperty("rejectedValue") val rejectedValue: Any?,
    @get:JvmName("reason") @get:JsonProperty("reason") @param:JsonProperty("reason") val reason: String?,
)
