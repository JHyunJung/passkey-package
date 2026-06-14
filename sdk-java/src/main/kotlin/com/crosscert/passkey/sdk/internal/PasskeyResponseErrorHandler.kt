package com.crosscert.passkey.sdk.internal

import com.crosscert.passkey.sdk.envelope.ApiResponseEnvelope
import com.crosscert.passkey.sdk.exception.PasskeyApiException
import com.crosscert.passkey.sdk.exception.PasskeyAuthException
import com.crosscert.passkey.sdk.exception.PasskeyRateLimitException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.client.ClientHttpResponse
import org.springframework.web.client.ResponseErrorHandler
import java.io.IOException

class PasskeyResponseErrorHandler(
    private val objectMapper: ObjectMapper,
) : ResponseErrorHandler {

    @Throws(IOException::class)
    override fun hasError(response: ClientHttpResponse): Boolean {
        return response.statusCode.isError
    }

    @Throws(IOException::class)
    override fun handleError(response: ClientHttpResponse) {
        val status = response.statusCode.value()
        val bodyBytes = response.body.readAllBytes()
        val env = parseQuietly(bodyBytes)
        // Treat a parsed envelope with no code as a non-envelope response (e.g. RFC 7807 problem+json)
        val isEnvelope = env != null && env.code != null

        val code = if (isEnvelope) env!!.code else "C999"
        val message = if (isEnvelope) env!!.message else "Upstream error (no envelope)"
        val traceId = if (isEnvelope) env!!.traceId else null
        val fieldErrors = if (isEnvelope && env!!.error != null) env.error!!.fieldErrors else null

        if (status == 401) {
            throw PasskeyAuthException(code, message, traceId, fieldErrors)
        }
        if (status == 429) {
            val retryAfter = response.headers.getFirst("Retry-After")
            val retry = if (retryAfter == null) 0 else retryAfter.toLong()
            throw PasskeyRateLimitException(code, message, traceId, fieldErrors, retry)
        }
        throw PasskeyApiException(status, code, message, traceId, fieldErrors)
    }

    private fun parseQuietly(body: ByteArray?): ApiResponseEnvelope<*>? {
        return try {
            if (body == null || body.isEmpty()) {
                null
            } else {
                objectMapper.readValue(body, ApiResponseEnvelope::class.java)
            }
        } catch (e: Exception) {
            null
        }
    }
}
