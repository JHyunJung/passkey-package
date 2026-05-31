package com.crosscert.passkey.core.alert;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 알림 비동기 인프라 (P1-3). 전용 executor 로 발송이 요청 스레드를 막지 않게 한다. */
@Configuration
@EnableAsync
public class AlertConfig {

    @Bean("alertExecutor")
    public TaskExecutor alertExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("alert-");
        ex.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        ex.initialize();
        return ex;
    }
}
