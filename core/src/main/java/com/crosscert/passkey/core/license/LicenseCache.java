package com.crosscert.passkey.core.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Persists the most recently verified license token + the wall-clock
 * timestamp of the last successful heartbeat to a single JSON file on
 * disk. Used to (1) bridge restarts without re-contacting the license
 * server, (2) compute NETWORK_GRACE remaining time, (3) detect clock
 * rollback by comparing system time against lastVerifiedAt.
 *
 * File format (single JSON object):
 *   { "tokenJws": "...", "lastVerifiedAt": "2026-05-29T08:30:00Z" }
 */
@Component
public class LicenseCache {

    private static final Logger log = LoggerFactory.getLogger(LicenseCache.class);
    private static final ObjectMapper M = JsonMapper.builder()
            .findAndAddModules()
            .build();
    private static final Set<PosixFilePermission> RW_USER =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    public record Entry(String tokenJws, Instant lastVerifiedAt) {}

    public Optional<Entry> read(Path path) {
        if (!Files.exists(path)) return Optional.empty();
        try {
            byte[] bytes = Files.readAllBytes(path);
            Entry e = M.readValue(bytes, Entry.class);
            return Optional.ofNullable(e);
        } catch (IOException e) {
            log.warn("license cache read failed: path={} reason={}", path, e.getMessage());
            return Optional.empty();
        }
    }

    public void write(Path path, Entry entry) {
        try {
            Files.createDirectories(path.getParent());
            byte[] bytes = M.writeValueAsBytes(entry);
            Files.write(path, bytes);
            try {
                Files.setPosixFilePermissions(path, RW_USER);
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX FS (Windows) — skip.
            }
        } catch (IOException e) {
            log.warn("license cache write failed: path={} reason={}", path, e.getMessage());
        }
    }
}
