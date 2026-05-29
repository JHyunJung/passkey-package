package com.crosscert.passkey.admin.system;

import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class SystemInfoService {

    @Nullable private final BuildProperties build;
    private final JdbcTemplate jdbc;
    private final Environment env;

    public SystemInfoService(@Nullable BuildProperties build, JdbcTemplate jdbc, Environment env) {
        this.build = build;
        this.jdbc = jdbc;
        this.env = env;
    }

    public SystemInfoView get() {
        String version = build != null ? build.getVersion() : "dev";
        String deployedAt = build != null && build.getTime() != null
                ? build.getTime().toString() : Instant.now().toString();

        long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        long uptimeDays = Duration.ofMillis(uptimeMillis).toDays();

        SystemInfoView.Host host = new SystemInfoView.Host(
                env.getProperty("passkey.host.api", "api.passkey.example.com"),
                env.getProperty("passkey.host.admin-console", "admin.passkey.example.com"),
                env.getProperty("passkey.host.region", "ap-northeast-2 (Seoul)"),
                env.getProperty("spring.profiles.active", "development"),
                env.getProperty("passkey.host.deploy-method", "local"),
                env.getProperty("passkey.deployment.mode", "saas")
        );

        List<SystemInfoView.Component> components = new ArrayList<>();
        components.add(new SystemInfoView.Component("admin-app", version, "OK", 1, "Spring Boot"));
        components.add(new SystemInfoView.Component("passkey-app", version, "OK", 1, "Spring Boot"));
        String dbBanner = "Oracle (unknown)";
        try {
            String b = jdbc.queryForObject("SELECT banner FROM v$version WHERE ROWNUM=1", String.class);
            if (b != null && !b.isBlank()) dbBanner = b;
        } catch (Exception ignore) {
            // APP_ADMIN_USER may lack SELECT on v$version — leave dbBanner default.
        }
        components.add(new SystemInfoView.Component("Oracle DB", dbBanner, "OK", 1, "primary"));
        components.add(new SystemInfoView.Component(
                "MDS sync scheduler", version, "OK", 1, "03:00 KST daily"));

        return new SystemInfoView(
                version,
                deployedAt,
                null, null, null,           // p95/avg/p99 — actuator not configured
                null, uptimeDays, null,     // uptimePercent/incident — null
                host,
                components);
    }
}
