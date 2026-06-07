package com.crosscert.passkey.rpapp.user;

import com.crosscert.passkey.rpapp.common.exception.BusinessException;
import com.crosscert.passkey.rpapp.common.exception.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * username ↔ userHandle ↔ credential 매핑 저장소.
 *
 * 맵은 in-memory 캐시이고, 확정 등록된 user(credentialId ≠ null)만 JSON 파일에
 * 미러링한다. pending user 는 메모리에만 두어 재기동 시 자연 정리된다. 단일 인스턴스
 * 데모를 가정하며 파일 락은 두지 않는다.
 */
@Component
public class InMemoryUserStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryUserStore.class);

    private final ConcurrentMap<String, RpAppUser> byHandle   = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String>    byUsername = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();

    private final ObjectMapper mapper;
    private final Path file;

    public InMemoryUserStore(ObjectMapper mapper,
                             @Value("${rp-app.user-store.file:./data/rp-app-users.json}") String file) {
        this.mapper = mapper;
        this.file   = Path.of(file);
        load();
    }

    /** 기동 시 파일에서 확정 user 복원. 파일이 없으면 빈 상태로 시작. 손상되면 quarantine 후 빈 상태로 시작(크래시 금지). */
    private void load() {
        if (!Files.exists(file)) {
            log.info("user-store: no persisted file at {} — starting empty", file);
            return;
        }
        try {
            List<RpAppUser> users = mapper.readValue(file.toFile(), new TypeReference<List<RpAppUser>>() {});
            for (RpAppUser u : users) {
                // 방어: 확정(credentialId)만 신뢰하고, 맵 key 가 될 필수 필드가 null 이면 skip(NPE 회피).
                if (u.credentialId() == null || u.userHandle() == null || u.username() == null) continue;
                byHandle.put(u.userHandle(), u);
                byUsername.put(u.username(), u.userHandle());
            }
            log.info("user-store: loaded {} confirmed user(s) from {}", byHandle.size(), file);
        } catch (Exception e) {
            log.warn("user-store: failed to read {} — quarantining and starting empty. cause={}", file, e.toString());
            quarantineCorruptFile();
        }
    }

    /** 손상된 store 파일을 .corrupt-<epochMillis> 로 옮겨 다음 persist 가 원본을 덮어쓰지 못하게 한다. */
    private void quarantineCorruptFile() {
        try {
            Path dest = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
            log.warn("user-store: quarantined corrupt file to {}", dest);
        } catch (IOException ignore) {
            // quarantine 실패는 best-effort — 그래도 기동은 계속.
        }
    }

    /** 확정 user(credentialId ≠ null)만 골라 파일에 쓴다. temp 파일 → ATOMIC_MOVE(미지원 FS 면 비원자 move 폴백). 실패해도 예외 비전파. */
    private synchronized void persist() {
        List<RpAppUser> confirmed = byHandle.values().stream()
                .filter(u -> u.credentialId() != null)
                .toList();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            mapper.writeValue(tmp.toFile(), confirmed);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);  // 비원자적 폴백
            }
        } catch (IOException e) {
            log.error("user-store: failed to persist {} user(s) to {} — cause={}",
                    confirmed.size(), file, e.toString());
        } finally {
            // 성공 시엔 move 로 tmp 가 사라져 no-op. 실패 시 stale tmp 정리.
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
                // 정리 실패는 무시.
            }
        }
    }

    /** username 으로 새 userHandle (32B base64url) 발급 + pending 상태로 저장. 중복이면 USERNAME_TAKEN. */
    public String createPending(String username, String displayName) {
        byte[] raw = new byte[32];
        rng.nextBytes(raw);
        String userHandle = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        // putIfAbsent 로 username 을 원자적으로 예약. 동시 호출 시 두 번째는 non-null 반환 → 거부.
        if (byUsername.putIfAbsent(username, userHandle) != null) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        RpAppUser user = new RpAppUser(userHandle, username, displayName, Instant.now(), null);
        byHandle.put(userHandle, user);
        // pending 은 영속화하지 않음 — 재기동 시 자연 정리.
        return userHandle;
    }

    /** registration/finish 성공 후 credentialId 채워서 확정 + 파일에 영속화. */
    public void confirmRegistration(String userHandle, String credentialId) {
        RpAppUser updated = byHandle.computeIfPresent(userHandle, (k, u) ->
                new RpAppUser(u.userHandle(), u.username(), u.displayName(), u.createdAt(), credentialId));
        // 알 수 없는 handle 이면 변경이 없으므로 불필요한 파일 쓰기를 생략.
        if (updated != null) persist();
    }

    public Optional<RpAppUser> findByUserHandle(String userHandle) {
        return Optional.ofNullable(byHandle.get(userHandle));
    }

    public Optional<RpAppUser> findByUsername(String username) {
        String handle = byUsername.get(username);
        return handle == null ? Optional.empty() : findByUserHandle(handle);
    }
}
