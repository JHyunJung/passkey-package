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
        first.confirmRegistration(handle, "alice", "Alice", "cred-123");

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
        assertThat(byHandle.get().userHandle()).isEqualTo("handle-x");
        assertThat(byHandle.get().username()).isEqualTo("dave");
        assertThat(byHandle.get().displayName()).isEqualTo("Dave");
        assertThat(byHandle.get().credentialId()).isEqualTo("cred-x");
        assertThat(byHandle.get().createdAt()).isNotNull();

        // username→handle 매핑도 복구되어 로그인(unknown-sub 회피)이 가능해야 한다.
        assertThat(store.findByUsername("dave")).isPresent();
        assertThat(store.findByUsername("dave").get().userHandle()).isEqualTo("handle-x");

        // 확정 user 는 영속화되어 새 인스턴스에서도 살아남아야 한다(완전 무상태).
        InMemoryUserStore reloaded = new InMemoryUserStore(mapper(), file.toString());
        assertThat(reloaded.findByUserHandle("handle-x")).isPresent();
        assertThat(reloaded.findByUserHandle("handle-x").get().credentialId()).isEqualTo("cred-x");
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
        assertThat(reloaded.findByUsername("carol").get().credentialId()).isEqualTo("cred-xyz");
    }
}
