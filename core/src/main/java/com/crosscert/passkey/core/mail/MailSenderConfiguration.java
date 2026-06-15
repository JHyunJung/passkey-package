package com.crosscert.passkey.core.mail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

/**
 * MailSender 빈을 단일 결정 규칙으로 선택한다.
 *
 * <p>{@code spring.mail.host} 가 설정되면 {@link SmtpMailSender}(JavaMailSender 위임),
 * 아니면 {@link LogMailSender}(로그 전용)를 등록한다. MailSender 타입 빈 정의가 항상
 * 정확히 하나만 생성되므로, 컴포넌트 스캔 순서나 조건부 빈 평가 타이밍과 무관하게
 * 결정적이다(이전 @ConditionalOnMissingBean 방식의 NoUniqueBeanDefinitionException 위험 제거).
 */
@Configuration
public class MailSenderConfiguration {

    @Bean
    public MailSender passkeyMailSender(
            Environment environment,
            ObjectProvider<JavaMailSender> javaMailSenderProvider,
            @Value("${passkey.mail.from:no-reply@passkey.local}") String from) {
        String host = environment.getProperty("spring.mail.host");
        if (StringUtils.hasText(host)) {
            return new SmtpMailSender(javaMailSenderProvider.getObject(), from);
        }
        return new LogMailSender();
    }
}
