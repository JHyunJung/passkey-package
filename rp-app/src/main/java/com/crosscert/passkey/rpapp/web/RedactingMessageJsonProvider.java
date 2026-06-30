package com.crosscert.passkey.rpapp.web;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonGenerator;
import net.logstash.logback.composite.AbstractFieldJsonProvider;
import net.logstash.logback.composite.JsonWritingUtils;

import java.io.IOException;

/**
 * JSON 로그의 message 필드에 텍스트 로그와 동일한 비밀값 마스킹을 적용한다.
 * LogstashEncoder 기본 provider 는 마스킹을 우회하므로 이 provider 로 교체한다.
 */
public class RedactingMessageJsonProvider extends AbstractFieldJsonProvider<ILoggingEvent> {

    public RedactingMessageJsonProvider() {
        setFieldName("message");
    }

    @Override
    public void writeTo(JsonGenerator generator, ILoggingEvent event) throws IOException {
        JsonWritingUtils.writeStringField(
                generator, getFieldName(),
                SecretRedactor.redact(event.getFormattedMessage()));
    }
}
