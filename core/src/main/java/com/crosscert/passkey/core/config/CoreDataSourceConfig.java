package com.crosscert.passkey.core.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the physical HikariCP pool as the application DataSource.
 *
 * <p>Spring Boot's auto-config provides DataSourceProperties bound to
 * {@code spring.datasource.*} (url/username/password/driver-class-name).
 * We use {@link DataSourceProperties#initializeDataSourceBuilder()} to
 * get a builder pre-loaded with those base settings, then bind
 * {@code spring.datasource.hikari.*} onto the resulting HikariDataSource
 * directly so pool tuning takes effect.
 *
 * <p>Tenant isolation is provided entirely by the app-level Hibernate
 * {@code @Filter} activated by
 * {@link com.crosscert.passkey.core.tenant.TenantFilterAspect}. There is
 * no DB-kernel VPD layer.
 */
@Configuration
public class CoreDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
