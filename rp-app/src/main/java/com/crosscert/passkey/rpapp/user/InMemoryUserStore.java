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

    /**
     * username 으로 새 userHandle (32B base64url) 발급 + pending 상태로 저장.
     *
     * <p>begin 단계에서는 username 을 점유하지 <b>않는다</b>(byUsername 매핑을 만들지 않음).
     * begin 만 하고 finish 를 못 한 사용자(다이얼로그 취소·페이지 이탈·네트워크 단절)가 같은
     * username 으로 영구히 재시도하지 못하던 W001 버그를 막기 위함. 진짜 username 충돌 방지는
     * confirmRegistration 의 putIfAbsent(최종 권위) + 컨트롤러의 isUsernameTakenByOther
     * 선검사가 처리하므로, begin 점유는 정상 재시도를 차단하는 부작용만 있었다.
     */
    public String createPending(String username, String displayName) {
        byte[] raw = new byte[32];
        rng.nextBytes(raw);
        String userHandle = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        RpAppUser user = new RpAppUser(userHandle, username, displayName, Instant.now(), null);
        // byHandle 에만 pending 을 둔다(username 미점유). 영속화하지 않음 — 재기동 시 자연 정리.
        byHandle.put(userHandle, user);
        return userHandle;
    }

    /**
     * username 이 이미 <b>다른</b> userHandle 로 점유돼 있는지 검사. 컨트롤러가 upstream
     * registrationFinish <b>전에</b> 호출해, 유효 begin 에서 온(HMAC 으로 증명된) username 이라도
     * finish 시점에 다른 사용자에게 확정돼 있으면 typed-login 탈취/충돌을 미리 차단하기 위함이다.
     * 같은 handle(정상 재확정)이면 false.
     */
    public boolean isUsernameTakenByOther(String username, String userHandle) {
        String existingHandle = byUsername.get(username);
        return existingHandle != null && !existingHandle.equals(userHandle);
    }

    /**
     * registration/finish 성공 후 확정. userHandle 이 (pending 으로) 있으면 credentialId 를 채우고,
     * 없으면(재시작·다중 인스턴스로 pending 유실) relay 의 서명된 username/displayName 로 user 를
     * 결정적으로 생성해 확정한다. 어느 경로든 확정 user 는 파일에 영속화된다(완전 무상태, P0-4).
     *
     * <p>방어(이중): username 이 이미 다른 userHandle 로 점유돼 있으면 거부(탈취/충돌 방지).
     * putIfAbsent 로 원자적 점유 검사 + 같은-handle 재확정(idempotent)은 허용. HMAC 은 "유효
     * begin 에서 온 username"만 증명하지 "finish 시점 미점유"는 증명하지 못하므로 필요하다.
     */
    public void confirmRegistration(String userHandle, String username, String displayName, String credentialId) {
        String existingHandle = byUsername.putIfAbsent(username, userHandle);
        if (existingHandle != null && !existingHandle.equals(userHandle)) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        byHandle.compute(userHandle, (k, existing) -> existing != null
                ? new RpAppUser(existing.userHandle(), existing.username(), existing.displayName(), existing.createdAt(), credentialId)
                : new RpAppUser(userHandle, username, displayName, Instant.now(), credentialId));
        persist();
    }

    public Optional<RpAppUser> findByUserHandle(String userHandle) {
        return Optional.ofNullable(byHandle.get(userHandle));
    }

    public Optional<RpAppUser> findByUsername(String username) {
        String handle = byUsername.get(username);
        return handle == null ? Optional.empty() : findByUserHandle(handle);
    }
}
