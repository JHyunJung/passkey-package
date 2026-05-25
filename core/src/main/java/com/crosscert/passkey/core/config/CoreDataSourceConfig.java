package com.crosscert.passkey.core.config;

import com.crosscert.passkey.core.vpd.TenantAwareDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Wires the physical HikariCP pool and the tenant-aware wrapper.
 *
 * <p>Spring Boot's auto-config provides DataSourceProperties bound to
 * {@code spring.datasource.*} (url/username/password/driver-class-name).
 * We use {@link DataSourceProperties#initializeDataSourceBuilder()} to
 * get a builder pre-loaded with those base settings, then bind
 * {@code spring.datasource.hikari.*} onto the resulting HikariDataSource
 * directly so pool tuning takes effect. Doing this in two passes is the
 * canonical Spring Boot pattern when the framework's default DataSource
 * bean is being replaced.
 *
 * <p>The wrapped {@link TenantAwareDataSource} is exposed as {@code @Primary}
 * so JPA, Flyway, and actuator health all resolve to it. Flyway is unaffected
 * by VPD because APP_ADMIN_USER has EXEMPT ACCESS POLICY and no
 * TenantContextHolder value is present during migration.
 */
@Configuration
public class CoreDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource physicalDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource physicalDataSource) {
        return new TenantAwareDataSource(physicalDataSource);
    }
}
