package com.crosscert.passkey.core.config;

import com.crosscert.passkey.core.vpd.TenantAwareDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>When {@code passkey.vpd.enabled=true} the pool is wrapped in
 * {@link TenantAwareDataSource}, which calls {@code ctx_pkg.set_tenant} /
 * {@code ctx_pkg.clear_tenant} on every connection borrow/return so that
 * Oracle's VPD policy can enforce the per-tenant predicate at the DB kernel
 * level. This is the EE/XE production path.
 *
 * <p>When {@code passkey.vpd.enabled=false} (the default for SE2 deployments)
 * the physical pool is returned as-is — Oracle VPD is not bootstrapped on SE2
 * so the {@code ctx_pkg} calls would fail. Tenant isolation in this mode is
 * provided entirely by the app-level Hibernate {@code @Filter} activated by
 * {@link com.crosscert.passkey.core.vpd.TenantFilterAspect}.
 *
 * <p>The exposed bean is {@code @Primary} so JPA, Flyway, and actuator health
 * all resolve to it. Flyway is unaffected by VPD because APP_ADMIN_USER has
 * EXEMPT ACCESS POLICY and no TenantContextHolder value is present during
 * migration.
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
    public DataSource dataSource(
            HikariDataSource physicalDataSource,
            @Value("${passkey.vpd.enabled:false}") boolean vpdEnabled) {
        // VPD on (EE/XE): wrap so the DB kernel enforces the tenant predicate
        // via ctx_pkg.set_tenant / ctx_pkg.clear_tenant on every connection.
        // VPD off (SE2): return the physical DataSource as-is — isolation is
        // handled entirely by the app-level Hibernate @Filter (TenantFilterAspect).
        return vpdEnabled
                ? new TenantAwareDataSource(physicalDataSource)
                : physicalDataSource;
    }
}
