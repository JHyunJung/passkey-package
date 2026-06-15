package com.crosscert.passkey.rpapp.web

import ch.qos.logback.classic.spi.ILoggingEvent
import com.fasterxml.jackson.core.JsonGenerator
import net.logstash.logback.composite.AbstractFieldJsonProvider
import net.logstash.logback.composite.JsonWritingUtils
import java.io.IOException

/**
 * JSON 로그의 message 필드에 텍스트 모드와 동일한 비밀값 마스킹을 적용한다.
 * LogstashEncoder 기본 message provider 는 마스킹을 우회하므로 이걸로 교체한다.
 */
class RedactingMessageJsonProvider : AbstractFieldJsonProvider<ILoggingEvent>() {

    init {
        fieldName = "message"
    }

    @Throws(IOException::class)
    override fun writeTo(generator: JsonGenerator, event: ILoggingEvent) {
        JsonWritingUtils.writeStringField(
            generator, fieldName,
            SecretRedactor.redact(event.formattedMessage)
        )
    }
}
