package com.crosscert.passkey.admin.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulerLeaseServiceTest {

    private JdbcTemplate jdbc;
    private SchedulerLeaseService svc;
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        svc = new SchedulerLeaseService(jdbc, clock);
    }

    @Test
    void tryAcquireInsertsLeaseWhenAbsent() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString()))
                .thenReturn(0);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        boolean got = svc.tryAcquire("mds-sync", "host-1", Duration.ofMinutes(5));

        assertThat(got).isTrue();
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void tryAcquireTakesOverExpiredLease() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString()))
                .thenReturn(1);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        boolean got = svc.tryAcquire("mds-sync", "host-1", Duration.ofMinutes(5));

        assertThat(got).isTrue();
    }

    @Test
    void tryAcquireReturnsFalseWhenAnotherHolderActive() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString()))
                .thenReturn(1);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        boolean got = svc.tryAcquire("mds-sync", "host-1", Duration.ofMinutes(5));

        assertThat(got).isFalse();
    }
}
