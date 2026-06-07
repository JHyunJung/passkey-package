package com.crosscert.passkey.rpapp.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryUserStoreTest {

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
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
}
