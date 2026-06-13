package com.crosscert.passkey.core.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 보안 알림 fan-out (P1-3). @Async @EventListener 로 SecurityAlertEvent 를 수신해
 * supports 통과 채널에 per-channel 격리(try/catch)로 발송한다. 한 채널 실패가
 * 다른 채널·발행 스레드(요청)를 막지 않는다. @Async 라 발행 지점 지연 없음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertDispatcher {

    private final List<AlertChannel> channels;

    @Async("alertExecutor")
    @EventListener
    public void onAlert(SecurityAlertEvent event) {
        for (AlertChannel channel : channels) {
            if (!channel.supports(event.severity())) continue;
            try {
                channel.send(event);
            } catch (RuntimeException e) {
                log.warn("alert channel failed: channel={} type={} cause={}",
                        channel.getClass().getSimpleName(), event.type(), e.toString());
            }
        }
    }
}
