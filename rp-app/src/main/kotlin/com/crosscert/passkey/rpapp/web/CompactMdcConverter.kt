package com.crosscert.passkey.rpapp.web

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * 값이 있는 MDC 키만 `[traceId=.. tenantId=.. ..]` 로 묶어 출력한다. 빈 키 생략, 전부 비면 "".
 *
 * core 의 `com.crosscert.passkey.core.logging.CompactMdcConverter` Kotlin 트윈
 * — rp-app 은 :core 비의존이라 중복. 두 파일 동작을 동일하게 유지할 것.
 */
class CompactMdcConverter : ClassicConverter() {

    private companion object {
        val KEYS = arrayOf("traceId", "tenantId", "actorEmail", "apiKeyPrefix")
        const val MAX_VALUE_LEN = 16
    }

    override fun convert(event: ILoggingEvent): String {
        val mdc = event.mdcPropertyMap ?: return ""
        if (mdc.isEmpty()) return ""
        val sb = StringBuilder()
        for (key in KEYS) {
            var v = mdc[key]
            if (v.isNullOrBlank()) continue
            if (v.length > MAX_VALUE_LEN) v = v.substring(0, MAX_VALUE_LEN)
            sb.append(if (sb.isEmpty()) "[" else " ").append(key).append('=').append(v)
        }
        return if (sb.isEmpty()) "" else sb.append(']').toString()
    }
}
