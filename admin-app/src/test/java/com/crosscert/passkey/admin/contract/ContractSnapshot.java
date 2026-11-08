package com.crosscert.passkey.admin.contract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 계약 스냅샷 기록·비교. 파일은 {@code src/test/resources/contract/} 아래에 둔다.
 *
 * <p>기본(비교) 모드: 파일이 없거나 내용이 다르면 실패한다. 기록 모드
 * ({@code ./gradlew :admin-app:test --tests '<cls>' -Pcontract.record=true})에서는 파일을 새로 쓴다.
 * 기록한 파일은 커밋 전에 사람이 검토하고, 그 뒤로는 수정하지 않는다(새 스냅샷 추가만 허용).
 */
final class ContractSnapshot {

    static final boolean RECORD = Boolean.getBoolean("contract.record");
    private static final Path ROOT = Path.of("src/test/resources/contract");

    private ContractSnapshot() {}

    /** 텍스트 스냅샷(UTF-8). */
    static void assertMatches(String relativePath, String actual) {
        Path file = ROOT.resolve(relativePath);
        try {
            if (RECORD) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, actual);
                return;
            }
            if (!Files.exists(file)) {
                fail("스냅샷 없음: " + file + " — -Pcontract.record=true 로 기록 후 검토하세요");
            }
            assertThat(actual).as("contract/" + relativePath).isEqualTo(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 바이트 스냅샷 — 첫 차이 위치를 텍스트로 보이도록 UTF-8 로 디코드해 비교하고, 바이트 길이·내용도 단언한다. */
    static void assertBytesMatch(String relativePath, byte[] actual) {
        Path file = ROOT.resolve(relativePath);
        try {
            if (RECORD) {
                Files.createDirectories(file.getParent());
                Files.write(file, actual);
                return;
            }
            if (!Files.exists(file)) {
                fail("스냅샷 없음: " + file + " — -Pcontract.record=true 로 기록 후 검토하세요");
            }
            byte[] expected = Files.readAllBytes(file);
            assertThat(new String(actual, java.nio.charset.StandardCharsets.UTF_8))
                    .as("contract/" + relativePath + " (UTF-8 view)")
                    .isEqualTo(new String(expected, java.nio.charset.StandardCharsets.UTF_8));
            assertThat(actual).as("contract/" + relativePath + " (bytes)").isEqualTo(expected);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
