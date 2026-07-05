package com.crosscert.passkey.admin.mds;

import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService;
import com.crosscert.passkey.core.alert.SecurityAlertEvent;
import com.crosscert.passkey.webauthn.mds.MdsBlob;
import com.crosscert.passkey.webauthn.mds.MdsBlobEntry;
import com.crosscert.passkey.webauthn.mds.MdsStatusReport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * G07-mdsAtomic — MdsSchedulerService.runOnce() must never leave a window
 * where a previously-cached AAGUID entry is absent from Redis. The old
 * implementation deleted every "mds:aaguid:*" key BEFORE repopulating them
 * (delete-then-repopulate), so any lookup landing inside that window saw a
 * cache miss and mdsRequired registrations failed closed.
 *
 * <p>This test drives runOnce() with a mocked StringRedisTemplate and
 * asserts the ordering of Redis calls: every new-entry SET must happen
 * BEFORE the stale-key cleanup DELETE. That way, at every point in time,
 * a key that existed before the cycle either still holds its (possibly
 * stale-but-valid) previous value or has already been overwritten with the
 * fresh value — it is never simply absent.
 */
class MdsSchedulerServiceCacheOrderTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private MdsBlobClient client;
    private MdsBlobStore store;
    private SchedulerLeaseService leases;
    private AuditLogService audit;
    private MdsHistoryService historyService;
    private MdsSchedulerService scheduler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        client = mock(MdsBlobClient.class);
        store = mock(MdsBlobStore.class);
        leases = mock(SchedulerLeaseService.class);
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        audit = mock(AuditLogService.class);
        historyService = mock(MdsHistoryService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-04T00:00:00Z"), ZoneOffset.UTC);

        scheduler = new MdsSchedulerService(
                leases, client, store, redis, audit, historyService,
                clock, new SimpleMeterRegistry(), eventPublisher, "test-holder");
    }

    @Test
    void runOnceWritesFreshEntriesBeforeDeletingStaleKeys() {
        UUID keptUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID staleUuid = UUID.fromString("99999999-8888-7777-6666-555555555555");

        // Previous cycle populated both AAGUIDs; the new BLOB only contains "kept".
        String staleKey = "mds:aaguid:" + staleUuid;
        String keptKey = "mds:aaguid:" + keptUuid;
        when(redis.keys("mds:aaguid:*")).thenReturn(Set.of(staleKey, keptKey));

        MdsBlob blob = new MdsBlob(7, java.time.LocalDate.of(2099, 1, 1),
                List.of(entry(keptUuid, List.of("FIDO_CERTIFIED_L1"))));
        when(client.fetch()).thenReturn(new MdsBlobClient.FetchResult("raw.jwt", blob));

        MdsSchedulerService.SyncResult result = scheduler.runOnce();

        assertThat(result.status()).isEqualTo("SYNCED");

        InOrder inOrder = inOrder(valueOps, redis);
        // Fresh SET for the surviving AAGUID must be issued first...
        inOrder.verify(valueOps).set(eq(keptKey), anyString(), any(Duration.class));
        // ...and only stale keys not present in the new BLOB are deleted afterwards.
        inOrder.verify(redis).delete(argThat((Set<String> deleted) ->
                deleted.contains(staleKey) && !deleted.contains(keptKey)));

        // The surviving key must never be deleted at any point in the cycle.
        verify(redis, never()).delete(argThat((Set<String> deleted) -> deleted.contains(keptKey)));
    }

    @Test
    void runOnceDoesNotDeleteAnythingWhenNoStaleKeysRemain() {
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String key = "mds:aaguid:" + uuid;
        // Previous cycle had exactly this one key, and the new BLOB still has it.
        when(redis.keys("mds:aaguid:*")).thenReturn(Set.of(key));

        MdsBlob blob = new MdsBlob(8, java.time.LocalDate.of(2099, 1, 1),
                List.of(entry(uuid, List.of("FIDO_CERTIFIED_L1"))));
        when(client.fetch()).thenReturn(new MdsBlobClient.FetchResult("raw.jwt", blob));

        MdsSchedulerService.SyncResult result = scheduler.runOnce();

        assertThat(result.status()).isEqualTo("SYNCED");
        verify(valueOps).set(eq(key), anyString(), any(Duration.class));
        // Nothing left to clean up — delete must not be called with a non-empty set.
        verify(redis, never()).delete(argThat((Set<String> deleted) -> !deleted.isEmpty()));
    }

    private static byte[] uuidBytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }

    private static MdsBlobEntry entry(UUID aaguid, List<String> statuses) {
        List<MdsStatusReport> reports = statuses.stream()
                .map(s -> new MdsStatusReport(s, null))
                .toList();
        return new MdsBlobEntry(uuidBytes(aaguid), reports);
    }
}
