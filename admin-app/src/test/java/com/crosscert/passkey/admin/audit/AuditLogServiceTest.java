package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.isNull;

class AuditLogServiceTest {

    private AuditLogRepository repo;
    private EntityManager em;
    private AuditLogService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repo = mock(AuditLogRepository.class);
        em = mock(EntityManager.class);
        // Stub the chain-lock SELECT FOR UPDATE so unit tests don't need a DB.
        Query lockQuery = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(lockQuery);
        when(lockQuery.setParameter(anyString(), any())).thenReturn(lockQuery);
        when(lockQuery.getSingleResult()).thenReturn(1);
        // Phase B — findLatestByTenant 기본 stub: genesis (테넌트 chain 없음)
        when(repo.findLatestByTenant(isNull(), any())).thenReturn(List.of());
        when(repo.findLatestByTenant(any(UUID.class), any())).thenReturn(List.of());
        service = new AuditLogService(repo, em, new ObjectMapper(), clock);
    }

    @Test
    void genesisRowHasNullPrevHashAndHashOfInputs() throws Exception {
        when(repo.findLatestForUpdate()).thenReturn(Optional.empty());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ip", "127.0.0.1");
        payload.put("ua", "JUnit");

        UUID actorUuid = UUID.fromString("00000000-0000-0000-0000-000000000042");
        service.append(new AuditAppendRequest(
                actorUuid, "alice@example.com", "ADMIN_LOGIN",
                null, null, null, payload));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(captor.capture());
        AuditLog row = captor.getValue();

        assertThat(row.getPrevHash()).isNull();
        assertThat(row.getActorId()).isEqualTo(actorUuid);
        assertThat(row.getAction()).isEqualTo("ADMIN_LOGIN");
        assertThat(row.getPayload()).isEqualTo("{\"ip\":\"127.0.0.1\",\"ua\":\"JUnit\"}");

        // Manually recompute the expected hash (actorId is UUID string in hash input).
        byte[] expected = MessageDigest.getInstance("SHA-256")
                .digest(("|" + actorUuid + "|ADMIN_LOGIN||"
                         + "|2026-06-01T00:00:00Z|"
                         + row.getPayload()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(row.getHash()).containsExactly(toIntArray(expected));
    }

    @Test
    void secondRowChainsToFirst() {
        UUID actorUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AuditLog prev = new AuditLog(
                null, new byte[]{9, 9, 9}, actorUuid, "alice@example.com",
                "ADMIN_LOGIN", null, null, null, null, null, "{}", clock.instant());
        when(repo.findLatestForUpdate()).thenReturn(Optional.of(prev));

        service.append(new AuditAppendRequest(
                actorUuid, "alice@example.com", "TENANT_CREATE",
                "TENANT", "T_A", null, Map.of("id", "T_A")));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(captor.capture());
        AuditLog row = captor.getValue();
        assertThat(row.getPrevHash()).containsExactly(toIntArray(new byte[]{9, 9, 9}));
    }

    @Test
    void payloadKeysAreSortedAlphabetically() {
        when(repo.findLatestForUpdate()).thenReturn(Optional.empty());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("z", 1);
        payload.put("a", 2);

        service.append(new AuditAppendRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "alice@example.com", "X", null, null, null, payload));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(captor.capture());
        // ORDER_MAP_ENTRIES_BY_KEYS reorders to "a" then "z".
        assertThat(captor.getValue().getPayload()).isEqualTo("{\"a\":2,\"z\":1}");
    }

    @Test
    void chainLockIsAcquiredBeforeReadingHead() {
        when(repo.findLatestForUpdate()).thenReturn(Optional.empty());
        Query lockQuery = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(lockQuery);
        when(lockQuery.setParameter(anyString(), any())).thenReturn(lockQuery);
        when(lockQuery.getSingleResult()).thenReturn(1);

        service.append(new AuditAppendRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "alice@example.com", "ADMIN_LOGIN", null, null, null, Map.of()));

        // The lock query on the sentinel row must precede the chain-head read.
        var ordered = inOrder(em, repo);
        ordered.verify(em).createNativeQuery(anyString());
        ordered.verify(repo).findLatestForUpdate();
    }

    private static int[] toIntArray(byte[] b) {
        int[] out = new int[b.length];
        for (int i = 0; i < b.length; i++) out[i] = b[i] & 0xff;
        return out;
    }
}
