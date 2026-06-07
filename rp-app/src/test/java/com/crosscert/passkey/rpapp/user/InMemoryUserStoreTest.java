package com.crosscert.passkey.rpapp.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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
        first.confirmRegistration(handle, "cred-123");

        // 새 인스턴스가 같은 파일에서 복원
        InMemoryUserStore second = new InMemoryUserStore(mapper(), file.toString());

        Optional<RpAppUser> byHandle = second.findByUserHandle(handle);
        assertThat(byHandle).isPresent();
        assertThat(byHandle.get().username()).isEqualTo("alice");
        assertThat(byHandle.get().displayName()).isEqualTo("Alice");
        assertThat(byHandle.get().credentialId()).isEqualTo("cred-123");
        assertThat(byHandle.get().createdAt()).isNotNull();

        assertThat(second.findByUsername("alice")).isPresent();
        assertThat(second.findByUsername("alice").get().userHandle()).isEqualTo(handle);
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
        store.confirmRegistration(handle, "cred-xyz");

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
        assertThat(reloaded.findByUsername("carol").get().credentialId()).isEqualTo("cred-xyz");
    }
}
