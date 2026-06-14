package com.crosscert.passkey.rpapp.user;

import com.crosscert.passkey.rpapp.common.exception.BusinessException;
import com.crosscert.passkey.rpapp.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryUserStoreTest {

    /** 프로덕션의 Boot ObjectMapper 와 동일 포맷(JavaTimeModule + ISO-8601 문자열). */
    private static ObjectMapper mapper() {
        return Jackson2ObjectMapperBuilder.json().build();
    }

    @Test
    void confirmedUserSurvivesNewStoreInstance(@TempDir Path dir) {
        Path file = dir.resolve("users.json");

        InMemoryUserStore first = new InMemoryUserStore(mapper(), file.toString());
        String handle = first.createPending("alice", "Alice");
        first.confirmRegistration(handle, "alice", "Alice", "cred-123");

        // 새 인스턴스가 같은 파일에서 복원
        InMemoryUserStore second = new InMemoryUserStore(mapper(), file.toString());

        Optional<RpAppUser> byHandle = second.findByUserHandle(handle);
        assertThat(byHandle).isPresent();
        assertThat(byHandle.get().getUsername()).isEqualTo("alice");
        assertThat(byHandle.get().getDisplayName()).isEqualTo("Alice");
        assertThat(byHandle.get().getCredentialId()).isEqualTo("cred-123");
        assertThat(byHandle.get().getCreatedAt()).isNotNull();

        assertThat(second.findByUsername("alice")).isPresent();
        assertThat(second.findByUsername("alice").get().getUserHandle()).isEqualTo(handle);
    }

    /**
     * P0-4 회귀 가드: pending 이 전혀 없는 빈 store 에서 confirmRegistration(4-arg)을 호출해도
     * relay 의 서명된 username/displayName 로 user 가 결정적으로 생성·확정되어야 한다.
     * (rp-app 재시작·다중 인스턴스로 begin 의 메모리 pending 이 유실된 경로를 모사.)
     */
    @Test
    void confirmRegistration_createsUserDeterministically_onEmptyStore(@TempDir Path dir) {
        Path file = dir.resolve("users.json");

        InMemoryUserStore store = new InMemoryUserStore(mapper(), file.toString());
        // createPending 을 거치지 않음 — handle 이 store 에 전혀 없는 상태.
        store.confirmRegistration("handle-x", "dave", "Dave", "cred-x");

        Optional<RpAppUser> byHandle = store.findByUserHandle("handle-x");
        assertThat(byHandle).isPresent();
        assertThat(byHandle.get().getUserHandle()).isEqualTo("handle-x");
        assertThat(byHandle.get().getUsername()).isEqualTo("dave");
        assertThat(byHandle.get().getDisplayName()).isEqualTo("Dave");
        assertThat(byHandle.get().getCredentialId()).isEqualTo("cred-x");
        assertThat(byHandle.get().getCreatedAt()).isNotNull();

        // username→handle 매핑도 복구되어 로그인(unknown-sub 회피)이 가능해야 한다.
        assertThat(store.findByUsername("dave")).isPresent();
        assertThat(store.findByUsername("dave").get().getUserHandle()).isEqualTo("handle-x");

        // 확정 user 는 영속화되어 새 인스턴스에서도 살아남아야 한다(완전 무상태).
        InMemoryUserStore reloaded = new InMemoryUserStore(mapper(), file.toString());
        assertThat(reloaded.findByUserHandle("handle-x")).isPresent();
        assertThat(reloaded.findByUserHandle("handle-x").get().getCredentialId()).isEqualTo("cred-x");
    }

    /**
     * 탈취/충돌 방지: username 이 이미 다른 userHandle 로 확정돼 있으면 새 handle 로의
     * confirmRegistration 은 USERNAME_TAKEN 으로 거부돼야 한다. HMAC 은 "유효 begin 에서 온
     * username"만 증명하지 "finish 시점 미점유"는 증명하지 못하므로 store 가 방어한다.
     */
    @Test
    void confirmRegistration_rejectsUsernameOwnedByAnotherHandle(@TempDir Path dir) {
        Path file = dir.resolve("users.json");
        InMemoryUserStore store = new InMemoryUserStore(mapper(), file.toString());

        store.confirmRegistration("h1", "alice", "Alice", "c1");

        // 같은 username("alice")을 다른 handle("h2")로 확정 시도 → 거부.
        assertThatThrownBy(() -> store.confirmRegistration("h2", "alice", "A2", "c2"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USERNAME_TAKEN);

        // 원 매핑은 그대로 유지(탈취 안 됨).
        assertThat(store.findByUsername("alice")).isPresent();
        assertThat(store.findByUsername("alice").get().getUserHandle()).isEqualTo("h1");
        assertThat(store.findByUsername("alice").get().getCredentialId()).isEqualTo("c1");
        // 거부된 handle 은 만들어지지 않아야 한다.
        assertThat(store.findByUserHandle("h2")).isEmpty();
    }

    /** 같은 handle 로의 재확정(정상 재시도·idempotent)은 허용되고 credentialId 만 갱신된다. */
    @Test
    void confirmRegistration_sameHandleReConfirm_isIdempotent(@TempDir Path dir) {
        Path file = dir.resolve("users.json");
        InMemoryUserStore store = new InMemoryUserStore(mapper(), file.toString());

        store.confirmRegistration("h1", "alice", "Alice", "c1");
        assertThatCode(() -> store.confirmRegistration("h1", "alice", "Alice", "c1b"))
                .doesNotThrowAnyException();

        assertThat(store.findByUserHandle("h1")).isPresent();
        assertThat(store.findByUserHandle("h1").get().getCredentialId()).isEqualTo("c1b");
        assertThat(store.findByUsername("alice").get().getUserHandle()).isEqualTo("h1");
    }

    /** isUsernameTakenByOther: 점유 없음/같은 handle → false, 다른 handle → true. */
    @Test
    void isUsernameTakenByOther_reflectsOwnership(@TempDir Path dir) {
        Path file = dir.resolve("users.json");
        InMemoryUserStore store = new InMemoryUserStore(mapper(), file.toString());

        assertThat(store.isUsernameTakenByOther("alice", "h1")).isFalse();  // 미점유
        store.confirmRegistration("h1", "alice", "Alice", "c1");
        assertThat(store.isUsernameTakenByOther("alice", "h1")).isFalse();  // 같은 handle
        assertThat(store.isUsernameTakenByOther("alice", "h2")).isTrue();   // 다른 handle
    }

    @Test
    void pendingUserIsNotPersisted(@TempDir Path dir) {
        Path file = dir.resolve("users.json");

        InMemoryUserStore first = new InMemoryUserStore(mapper(), file.toString());
        String handle = first.createPending("bob", "Bob");   // confirm 하지 않음

        InMemoryUserStore second = new InMemoryUserStore(mapper(), file.toString());

        assertThat(second.findByUserHandle(handle)).isEmpty();
        assertThat(second.findByUsername("bob")).isEmpty();
    }

    /**
     * 미완료 등록 재시도 가드: begin(createPending)만 하고 finish(confirmRegistration)를
     * 하지 않은 username 은 같은 인스턴스에서 다시 begin 할 수 있어야 한다. begin 단계에서
     * username 을 영구 점유하면 다이얼로그 취소·페이지 이탈로 등록을 끝내지 못한 사용자가
     * 재기동 전까지 그 username 으로 재시도조차 못 하는 W001 버그가 된다. 실제 충돌 방지는
     * confirmRegistration 의 putIfAbsent 가 권위 있게 처리하므로 begin 은 점유하지 않는다.
     */
    @Test
    void createPending_allowsReBeginForUnconfirmedUsername(@TempDir Path dir) {
        Path file = dir.resolve("users.json");
        InMemoryUserStore store = new InMemoryUserStore(mapper(), file.toString());

        String firstHandle = store.createPending("erin", "Erin");   // finish 안 함
        // 같은 username 으로 재-begin — 점유되지 않아야 하므로 예외 없이 새 handle 발급.
        String secondHandle = store.createPending("erin", "Erin");

        assertThat(secondHandle).isNotNull();
        assertThat(secondHandle).isNotEqualTo(firstHandle);
        // pending 은 username→handle 매핑을 만들지 않는다(확정 전까지 미점유).
        assertThat(store.findByUsername("erin")).isEmpty();

        // 재-begin 후 정상 finish 는 여전히 성공한다.
        store.confirmRegistration(secondHandle, "erin", "Erin", "cred-erin");
        assertThat(store.findByUsername("erin")).isPresent();
        assertThat(store.findByUsername("erin").get().getUserHandle()).isEqualTo(secondHandle);
    }

    /**
     * 확정된 username 은 여전히 보호된다: 정상 등록을 끝낸 username 으로 다시 begin 하면
     * confirmRegistration 단계에서 다른 handle 로의 점유 시도가 USERNAME_TAKEN 으로 거부된다.
     * (begin 점유를 제거해도 진짜 충돌 방지가 약해지지 않음을 보장.)
     */
    @Test
    void confirmedUsernameStillRejectedOnDifferentHandle(@TempDir Path dir) {
        Path file = dir.resolve("users.json");
        InMemoryUserStore store = new InMemoryUserStore(mapper(), file.toString());

        store.confirmRegistration("h1", "frank", "Frank", "c1");
        // begin 은 자유롭게 되지만(점유 없음)...
        String reHandle = store.createPending("frank", "Frank");
        // ...finish 단계에서 이미 다른 handle 이 확정 점유 → 거부.
        assertThatThrownBy(() -> store.confirmRegistration(reHandle, "frank", "Frank", "c2"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USERNAME_TAKEN);
    }

    @Test
    void corruptFileYieldsEmptyStoreWithoutCrash(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("users.json");
        java.nio.file.Files.writeString(file, "{ this is not valid json ][");

        InMemoryUserStore store = new InMemoryUserStore(mapper(), file.toString());

        assertThat(store.findByUsername("anyone")).isEmpty();
    }

    @Test
    void validJsonWithNullRequiredFieldYieldsEmptyStoreWithoutCrash(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("users.json");
        // 유효 JSON 이지만 username/userHandle 이 누락 → 맵 key 가 null 이면 NPE 위험.
        java.nio.file.Files.writeString(file,
                "[{\"credentialId\":\"c\",\"createdAt\":\"2020-01-01T00:00:00Z\"}]");

        InMemoryUserStore store = new InMemoryUserStore(mapper(), file.toString());

        assertThat(store.findByUsername("x")).isEmpty();
    }

    @Test
    void corruptFileIsQuarantinedNotOverwrittenOnNextConfirm(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("users.json");
        java.nio.file.Files.writeString(file, "{bad json ][");

        // load 가 손상 파일을 quarantine 하고 빈 store 로 시작
        InMemoryUserStore store = new InMemoryUserStore(mapper(), file.toString());
        assertThat(store.findByUsername("x")).isEmpty();

        // 새 user 확정 → persist 발생 (손상 원본을 덮어쓰면 안 됨)
        String handle = store.createPending("carol", "Carol");
        store.confirmRegistration(handle, "carol", "Carol", "cred-xyz");

        // 손상본이 .corrupt- prefix 로 보존되어 있어야 한다
        try (var paths = java.nio.file.Files.list(dir)) {
            long corruptCount = paths
                    .filter(p -> p.getFileName().toString().startsWith("users.json.corrupt-"))
                    .count();
            assertThat(corruptCount).isEqualTo(1);
        }

        // 새 users.json 에는 방금 확정한 user 가 들어있어야 한다 (reload 로 검증)
        InMemoryUserStore reloaded = new InMemoryUserStore(mapper(), file.toString());
        assertThat(reloaded.findByUsername("carol")).isPresent();
        assertThat(reloaded.findByUsername("carol").get().getCredentialId()).isEqualTo("cred-xyz");
    }
}
