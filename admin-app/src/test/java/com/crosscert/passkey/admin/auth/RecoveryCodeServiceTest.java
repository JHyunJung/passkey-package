package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUserRecoveryCode;
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import com.crosscert.passkey.core.config.KstTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoveryCodeServiceTest {

    @Mock AdminUserRecoveryCodeRepository repo;
    Clock clock = Clock.fixed(Instant.parse("2026-05-30T00:00:00Z"), KstTime.ZONE);
    RecoveryCodeService service;

    private static String sha256Hex(String s) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @BeforeEach
    void setUp() { service = new RecoveryCodeService(repo, clock); }

    @Test
    void generate_returns_ten_plaintext_and_purges_old() {
        UUID uid = UUID.randomUUID();
        List<String> codes = service.generate(uid);
        assertThat(codes).hasSize(10);
        assertThat(codes).doesNotHaveDuplicates();
        verify(repo).deleteByAdminUserId(uid);
        verify(repo, times(10)).save(any(AdminUserRecoveryCode.class));
    }

    @Test
    void consume_marks_matching_unused_code() throws Exception {
        UUID uid = UUID.randomUUID();
        String plain = "abcd-efgh";
        String normalizedHash = sha256Hex("ABCD-EFGH"); // service.normalize 결과 기준
        when(repo.markUsed(eq(uid), eq(normalizedHash), eq(OffsetDateTime.now(clock))))
                .thenReturn(1);

        boolean ok = service.consume(uid, plain);

        assertThat(ok).isTrue();
    }

    @Test
    void consume_returns_false_when_no_match() {
        UUID uid = UUID.randomUUID();
        when(repo.markUsed(any(), any(), any())).thenReturn(0);
        assertThat(service.consume(uid, "nope-nope")).isFalse();
    }

    @Test
    void consume_returns_false_for_null_code() {
        assertThat(service.consume(UUID.randomUUID(), null)).isFalse();
        verifyNoInteractions(repo);
    }
}
