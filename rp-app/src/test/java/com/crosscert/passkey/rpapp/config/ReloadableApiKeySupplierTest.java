package com.crosscert.passkey.rpapp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class ReloadableApiKeySupplierTest {

    private static final Duration NO_THROTTLE = Duration.ZERO;

    @Test
    void readsKeyFromFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("key.txt");
        Files.writeString(f, "pk_fileFIRSTsecret\n");
        ReloadableApiKeySupplier s = new ReloadableApiKeySupplier(f, NO_THROTTLE, "env-fallback");

        assertThat(s.get()).isEqualTo("pk_fileFIRSTsecret");
    }

    @Test
    void picksUpFileChangeWithoutRestart(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("key.txt");
        Files.writeString(f, "pk_old");
        ReloadableApiKeySupplier s = new ReloadableApiKeySupplier(f, NO_THROTTLE, "env-fallback");
        assertThat(s.get()).isEqualTo("pk_old");

        Files.writeString(f, "pk_new");
        Files.setLastModifiedTime(f,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));

        assertThat(s.get()).isEqualTo("pk_new");
    }

    @Test
    void fallsBackToEnvWhenFileNotConfigured() {
        ReloadableApiKeySupplier s = new ReloadableApiKeySupplier(null, NO_THROTTLE, "env-key");
        assertThat(s.get()).isEqualTo("env-key");
    }

    @Test
    void fallsBackToEnvWhenFileEmpty(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("key.txt");
        Files.writeString(f, "   \n");
        ReloadableApiKeySupplier s = new ReloadableApiKeySupplier(f, NO_THROTTLE, "env-key");
        assertThat(s.get()).isEqualTo("env-key");
    }

    @Test
    void keepsLastGoodKeyWhenFileBecomesUnreadable(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("key.txt");
        Files.writeString(f, "pk_good");
        ReloadableApiKeySupplier s = new ReloadableApiKeySupplier(f, NO_THROTTLE, "env-key");
        assertThat(s.get()).isEqualTo("pk_good");

        Files.delete(f);

        assertThat(s.get()).isEqualTo("pk_good");
    }
}
