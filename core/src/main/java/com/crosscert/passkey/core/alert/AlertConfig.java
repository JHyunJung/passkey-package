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
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("alert-");
        ex.setRejectedExecutionHandler((r, executor) -> {
            // 큐 포화 시 조용히 버리되, 드롭 자체는 반드시 관측되게(공격 중 가시성 보존).
            org.slf4j.LoggerFactory.getLogger(AlertConfig.class)
                .error("alert dropped: executor queue saturated (queueCapacity={})", executor.getQueue().size());
        });
        ex.initialize();
        return ex;
    }
}
