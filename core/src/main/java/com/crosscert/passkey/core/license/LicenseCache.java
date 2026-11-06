package com.crosscert.passkey.core.license;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
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
@Slf4j
@Component
public class LicenseCache {

    // Jackson 3: configureForJackson2() — 업그레이드 전(Jackson 2 + JavaTimeModule 기본값)과 같은
    // 파일 형식 유지(Instant 를 epoch 초 숫자로 기록). 읽기는 숫자·ISO 문자열 모두 허용.
    private static final ObjectMapper M = JsonMapper.builder()
            .configureForJackson2()
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
        } catch (IOException | JacksonException e) {
            // Jackson 3 예외는 unchecked(JacksonException) — Jackson 2 시절 IOException 으로 잡히던
            // 파싱 실패를 같은 경로(경고 후 빈 값)로 처리한다.
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
        } catch (IOException | JacksonException e) {
            log.warn("license cache write failed: path={} reason={}", path, e.getMessage());
        }
    }
}
