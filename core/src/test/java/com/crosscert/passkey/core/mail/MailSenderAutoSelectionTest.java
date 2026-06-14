package com.crosscert.passkey.core.mail;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spring.mail.host 유무에 따른 MailSender 빈 선택 검증.
 * MailSenderConfiguration 팩토리가 항상 정확히 하나의 MailSender 를 만든다.
 * - host 설정 → SmtpMailSender (JavaMailSender 는 MailSenderAutoConfiguration 이 제공)
 * - host 미설정 → LogMailSender
 */
class MailSenderAutoSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(MailSenderConfiguration.class);

    @Test
    void usesSmtpMailSender_whenMailHostIsSet() {
        runner.withPropertyValues("spring.mail.host=smtp.example.com")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(MailSender.class);
                    assertThat(ctx.getBean(MailSender.class)).isInstanceOf(SmtpMailSender.class);
                });
    }

    @Test
    void usesLogMailSender_whenMailHostIsAbsent() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(MailSender.class);
            assertThat(ctx.getBean(MailSender.class)).isInstanceOf(LogMailSender.class);
        });
    }
}
